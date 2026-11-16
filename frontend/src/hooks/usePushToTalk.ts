import { useCallback, useEffect, useRef, useState } from 'react'
import { interviewApi } from '@/api/interview'
import { useRecorder } from '@/hooks/useRecorder'

export type VoiceState = 'IDLE' | 'CONNECTING' | 'STREAMING' | 'FINISHING'

interface Options {
  sessionId: number | null
  /** 面试进行中、且面试官念完了才允许录音。 */
  enabled: boolean
  /** 一句（或几句）定稿，交给作答框。 */
  onCommit: (text: string) => void
  /** 给页面显示的一行提示；null 表示清除。 */
  onError?: (message: string | null) => void
}

interface PushToTalk {
  state: VoiceState
  /** 当前句的中间结果（会反复修订，只用于展示）。 */
  partial: string
  seconds: number
  level: number
  available: boolean
  error: string | null
  prepare: () => Promise<boolean>
  start: () => void
  stop: () => void
  cancel: () => void
  setVoiceMode: (on: boolean) => void
  voiceMode: boolean
}

/** 单次说话上限，和后端 max-session-seconds 留出余量。 */
const MAX_SECONDS = 60

/** 这些错误重试也没用，直接把语音入口关掉。 */
const FATAL = new Set(['QUOTA_EXHAUSTED', 'UPSTREAM_AUTH', 'DISABLED', 'NOT_CONFIGURED', 'CONNECT_FAILED'])

const MESSAGES: Record<string, string> = {
  QUOTA_EXHAUSTED: '语音额度用完了，先用键盘输入吧',
  UPSTREAM_AUTH: '语音服务未开通，先用键盘输入吧',
  DISABLED: '语音输入未开启',
  NOT_CONFIGURED: '语音服务未配置',
  CONNECT_FAILED: '连不上语音服务，先用键盘输入吧',
  UPSTREAM_THROTTLED: '语音服务繁忙，请稍后重试',
  UPSTREAM_TIMEOUT: '识别超时了，请重说一次',
  UPSTREAM_CLOSED: '连接中断了，请重说一次',
  UPSTREAM_ERROR: '语音服务出错了，请重说一次',
  UPSTREAM_CLIENT: '这次请求被拒绝了，请重说一次',
  BACKPRESSURE: '网络没跟上，请重说一次',
  IDLE_TIMEOUT: '没有听到声音，请重说一次'
}

/**
 * 按住空格说话。
 *
 * 一条连接 = 一次说话：keydown 取票建连，收到 final 就关掉。
 * 这样没有跨句状态，也不会出现「上一次的尾巴串到这一次」。
 *
 * 空格与输入法的冲突靠「语音模式」开关解决：模式开着时，
 * 捕获阶段的 keydown 直接把空格吃掉（preventDefault），
 * 所以 textarea 里既不会插入空格，也不会触发输入法上屏。
 */
export function usePushToTalk(options: Options): PushToTalk {
  const { sessionId, enabled } = options

  const [state, setState] = useState<VoiceState>('IDLE')
  const [partial, setPartial] = useState('')
  const [seconds, setSeconds] = useState(0)
  const [level, setLevel] = useState(0)
  const [available, setAvailable] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [voiceMode, setVoiceMode] = useState(true)

  const wsRef = useRef<WebSocket | null>(null)
  const sentencesRef = useRef<string[]>([])
  const stateRef = useRef<VoiceState>('IDLE')
  const finishedRef = useRef(false)
  const timeoutRef = useRef<number | null>(null)
  /**
   * 轮次号。一次「按下 → 松开」就是一轮，每次 {@link teardown} 都会作废在途的旧轮次。
   *
   * <p>没有它的后果：`start()` 在 `await asrTicket` / `await socket.open` /
   * `await recorder.start()` 之间都可能被松手打断，而异步续体照样会跑完——
   * 于是 `recorder.start()` 把麦克风又打开了，界面却已经是 IDLE，
   * 再按空格也停不下来，只能刷新页面。用户看到的是「麦克风一直亮着」。</p>
   */
  const generationRef = useRef(0)
  const optionsRef = useRef(options)
  optionsRef.current = options

  const recorder = useRecorder({
    onAudio: pcm => {
      const socket = wsRef.current
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(pcm)
      }
    },
    onLevel: setLevel
  })
  const recorderRef = useRef(recorder)
  recorderRef.current = recorder

  const setPhase = useCallback((next: VoiceState) => {
    stateRef.current = next
    setState(next)
  }, [])

  const fail = useCallback((code: string, message?: string) => {
    const text = MESSAGES[code] || message || '语音输入出错了，先用键盘输入吧'
    setError(text)
    optionsRef.current.onError?.(text)
    if (FATAL.has(code)) {
      setAvailable(false)
    }
  }, [])

  const teardown = useCallback(() => {
    // 作废在途轮次：让 start() 里所有 await 之后的续体自己退出
    generationRef.current += 1
    if (timeoutRef.current !== null) {
      window.clearTimeout(timeoutRef.current)
      timeoutRef.current = null
    }
    const socket = wsRef.current
    wsRef.current = null
    if (socket && socket.readyState <= WebSocket.OPEN) {
      try {
        socket.close()
      } catch {
        /* 关闭失败无所谓 */
      }
    }
    recorderRef.current.stop()
    sentencesRef.current = []
    setPartial('')
    setSeconds(0)
    setLevel(0)
    setPhase('IDLE')
  }, [setPhase])

  /** 把已定稿的句子交出去。中途断线时也要保住已经说出来的部分。 */
  const commit = useCallback(() => {
    const text = sentencesRef.current.join('').trim()
    sentencesRef.current = []
    setPartial('')
    if (text) {
      optionsRef.current.onCommit(text)
    }
  }, [])

  const start = useCallback(() => {
    if (!optionsRef.current.enabled || !available || stateRef.current !== 'IDLE' || sessionId === null) {
      return
    }
    optionsRef.current.onError?.(null)
    setError(null)
    finishedRef.current = false
    sentencesRef.current = []
    setPartial('')
    setSeconds(0)
    setPhase('CONNECTING')

    const generation = ++generationRef.current
    const stale = () => generationRef.current !== generation

    void (async () => {
      let socket: WebSocket | null = null
      try {
        const ticket = await interviewApi.asrTicket(sessionId)
        if (stale()) {
          return
        }
        // 用局部常量绑回调：socket 是可重赋值的变量，TS 在下面的 Promise 回调里会把它
        // 再当成可能为 null，用 ws 就没有这个问题，语义也更清楚
        const ws = new WebSocket(wsUrlFor(ticket.wsUrl, ticket.ticket))
        socket = ws
        ws.binaryType = 'arraybuffer'
        wsRef.current = ws

        ws.onmessage = event => {
          if (typeof event.data !== 'string') {
            return
          }
          let frame: { type?: string; text?: string; code?: string; message?: string }
          try {
            frame = JSON.parse(event.data)
          } catch {
            return
          }
          switch (frame.type) {
            case 'ready':
              break
            case 'partial':
              setPartial(frame.text || '')
              break
            case 'sentence':
              if (frame.text) {
                sentencesRef.current.push(frame.text)
              }
              setPartial('')
              break
            case 'final':
              finishedRef.current = true
              commit()
              teardown()
              break
            case 'error':
              finishedRef.current = true
              commit()
              fail(frame.code || 'UPSTREAM_ERROR', frame.message)
              teardown()
              break
            default:
              break
          }
        }

        ws.onclose = () => {
          // 正常收尾由 final 帧负责；走到这里说明连接提前断了
          if (!finishedRef.current && stateRef.current !== 'IDLE') {
            commit()
            fail('UPSTREAM_CLOSED')
            teardown()
          }
        }

        await new Promise<void>((resolve, reject) => {
          ws.onopen = () => resolve()
          ws.onerror = () => reject(new Error('websocket open failed'))
        })
        if (stale()) {
          closeQuietly(ws)
          return
        }

        const started = await recorderRef.current.start()
        if (stale()) {
          // 松手发生在「麦克风已经打开」之后：必须自己关掉，
          // 此时 teardown 已经在旧轮次里跑过了，不会再帮我关
          recorderRef.current.stop()
          closeQuietly(ws)
          return
        }
        if (!started) {
          fail('NOT_CONFIGURED', recorderRef.current.error || '麦克风不可用')
          teardown()
          return
        }
        setPhase('STREAMING')
      } catch {
        if (stale()) {
          closeQuietly(socket)
          return
        }
        fail('CONNECT_FAILED')
        teardown()
      }
    })()
  }, [available, commit, fail, sessionId, setPhase, teardown])

  const stop = useCallback(() => {
    // 还没连上就松手：直接取消，别停在 CONNECTING
    if (stateRef.current === 'CONNECTING') {
      finishedRef.current = true
      teardown()
      return
    }
    if (stateRef.current !== 'STREAMING') {
      return
    }
    recorderRef.current.stop()
    setPhase('FINISHING')
    const socket = wsRef.current
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'finish' }))
    }
    // 上游迟迟不给定稿时兜底收尾，别让界面卡在「正在整理」
    timeoutRef.current = window.setTimeout(() => {
      if (stateRef.current === 'FINISHING') {
        commit()
        fail('UPSTREAM_TIMEOUT')
        teardown()
      }
    }, 6000)
  }, [commit, fail, setPhase, teardown])

  const cancel = useCallback(() => {
    if (stateRef.current === 'IDLE') {
      return
    }
    finishedRef.current = true
    teardown()
  }, [teardown])

  // 计时 + 单次上限
  useEffect(() => {
    if (state !== 'STREAMING') {
      return
    }
    const timer = window.setInterval(() => {
      setSeconds(previous => {
        const next = previous + 1
        if (next >= MAX_SECONDS) {
          window.setTimeout(stopRef.current, 0)
        }
        return next
      })
    }, 1000)
    return () => window.clearInterval(timer)
  }, [state])

  const stopRef = useRef(stop)
  const startRef = useRef(start)
  const cancelRef = useRef(cancel)
  useEffect(() => {
    stopRef.current = stop
    startRef.current = start
    cancelRef.current = cancel
  }, [start, stop, cancel])

  // 空格按住说话：捕获阶段拦截，避免字符落进 textarea
  useEffect(() => {
    if (!enabled || !voiceMode) {
      return
    }
    const isSpace = (event: KeyboardEvent) => event.code === 'Space'
    const onKeyDown = (event: KeyboardEvent) => {
      if (!isSpace(event) || event.repeat || event.isComposing) {
        return
      }
      if (event.altKey || event.ctrlKey || event.metaKey) {
        return
      }
      event.preventDefault()
      startRef.current()
    }
    const onKeyUp = (event: KeyboardEvent) => {
      if (!isSpace(event)) {
        return
      }
      event.preventDefault()
      stopRef.current()
    }
    const onBlur = () => cancelRef.current()

    window.addEventListener('keydown', onKeyDown, true)
    window.addEventListener('keyup', onKeyUp, true)
    window.addEventListener('blur', onBlur)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      window.removeEventListener('keyup', onKeyUp, true)
      window.removeEventListener('blur', onBlur)
    }
  }, [enabled, voiceMode])

  // 卸载时务必断开，否则会一直占着麦克风。用 ref 挂载，
  // 避免 teardown 依赖变化时把正在进行的录音拆掉。
  const teardownRef = useRef(teardown)
  teardownRef.current = teardown
  useEffect(() => () => teardownRef.current(), [])

  const prepare = useCallback(() => recorderRef.current.prepare(), [])

  return {
    state,
    partial,
    seconds,
    level,
    available,
    error,
    prepare,
    start,
    stop,
    cancel,
    setVoiceMode,
    voiceMode
  }
}

/** 服务端只给相对路径，真机/开发都按当前站点拼，https 自动升级 wss。 */
function wsUrlFor(path: string, ticket: string): string {
  const base = /^wss?:/i.test(path)
    ? path
    : `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}${path}`
  return `${base}?ticket=${encodeURIComponent(ticket)}`
}

/** 关掉一个可能还没连上、也可能已经死掉的 socket，任何异常都咽下去。 */
function closeQuietly(socket: WebSocket | null) {
  if (!socket) {
    return
  }
  try {
    socket.onmessage = null
    socket.onclose = null
    socket.onerror = null
    socket.close()
  } catch {
    /* 已经关了 */
  }
}
