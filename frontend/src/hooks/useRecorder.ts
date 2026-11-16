import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

/** 实时识别接口只接受 16000，其他值会直接任务失败。 */
const TARGET_RATE = 16000
/** 每批 100ms，和后端 3200 字节/帧的预期一致。 */
const FRAME_SAMPLES = TARGET_RATE / 10

interface RecorderOptions {
  onAudio: (pcm: Int16Array) => void
  onLevel?: (level: number) => void
}

interface Recorder {
  /** 提前申请麦克风并搭好采集链；在「开始面试」那次真实点击里调。 */
  prepare: () => Promise<boolean>
  start: () => Promise<boolean>
  stop: () => void
  dispose: () => void
  ready: boolean
  error: string | null
}

/**
 * 麦克风采集：getUserMedia → AudioWorklet → 16k Int16 PCM 分帧。
 *
 * 为什么不用 MediaRecorder：它给的是 webm 封装，而实时接口的 opus 要求 Ogg 封装，
 * 且 stream 分片不能独立解码。PCM 才是这条链路唯一稳的形式。
 */
export function useRecorder({ onAudio, onLevel }: RecorderOptions): Recorder {
  const contextRef = useRef<AudioContext | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const nodeRef = useRef<AudioWorkletNode | null>(null)
  const recordingRef = useRef(false)
  const carryRef = useRef<Float32Array | null>(null)

  const [ready, setReady] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 回调放 ref：避免因为父组件重渲染导致采集链重建
  const onAudioRef = useRef(onAudio)
  const onLevelRef = useRef(onLevel)
  useEffect(() => {
    onAudioRef.current = onAudio
    onLevelRef.current = onLevel
  }, [onAudio, onLevel])

  const dispose = useCallback(() => {
    recordingRef.current = false
    const node = nodeRef.current
    if (node) {
      node.port.onmessage = null
      node.disconnect()
      nodeRef.current = null
    }
    const stream = streamRef.current
    if (stream) {
      stream.getTracks().forEach(track => track.stop())
      streamRef.current = null
    }
    const context = contextRef.current
    if (context && context.state !== 'closed') {
      void context.close()
    }
    contextRef.current = null
    setReady(false)
  }, [])

  const prepare = useCallback(async (): Promise<boolean> => {
    if (nodeRef.current) {
      return true
    }
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      setError('当前浏览器不支持录音')
      return false
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1
        }
      })
      streamRef.current = stream

      const Context = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
      // 让浏览器自己重采样到 16k，省掉手写重采样的一大坨边界情况
      const context = new Context({ sampleRate: TARGET_RATE })
      await context.audioWorklet.addModule('/pcm-worklet.js')

      const source = context.createMediaStreamSource(stream)
      const node = new AudioWorkletNode(context, 'pcm-processor', {
        processorOptions: { frameSamples: FRAME_SAMPLES }
      })

      node.port.onmessage = event => {
        if (!recordingRef.current) {
          return
        }
        const pcm = event.data as Int16Array
        if (!(pcm instanceof Int16Array) || pcm.length === 0) {
          return
        }
        let level = 0
        for (let i = 0; i < pcm.length; i += 1) {
          const value = pcm[i] / 32768
          level += value * value
        }
        level = Math.min(1, Math.sqrt(level / pcm.length) * 4)
        onLevelRef.current?.(level)

        const converted = context.sampleRate === TARGET_RATE
          ? pcm
          : resample(pcm, context.sampleRate, TARGET_RATE, carryRef)
        if (converted.length > 0) {
          onAudioRef.current(converted)
        }
      }

      source.connect(node)
      // 必须接到 destination 才会被音频图拉动；用一个 0 增益节点避免回放自己的声音
      const mute = context.createGain()
      mute.gain.value = 0
      node.connect(mute)
      mute.connect(context.destination)

      contextRef.current = context
      nodeRef.current = node
      setReady(true)
      setError(null)
      return true
    } catch (err) {
      const name = err instanceof DOMException ? err.name : ''
      setError(name === 'NotAllowedError' ? '麦克风权限被拒绝' : '麦克风不可用')
      setReady(false)
      return false
    }
  }, [])

  const start = useCallback(async (): Promise<boolean> => {
    const ok = await prepare()
    if (!ok) {
      return false
    }
    const context = contextRef.current
    if (context && context.state === 'suspended') {
      await context.resume()
    }
    carryRef.current = null
    recordingRef.current = true
    return true
  }, [prepare])

  const stop = useCallback(() => {
    recordingRef.current = false
    onLevelRef.current?.(0)
  }, [])

  useEffect(() => dispose, [dispose])

  return useMemo(
    () => ({ prepare, start, stop, dispose, ready, error }),
    [prepare, start, stop, dispose, ready, error]
  )
}

/**
 * 兜底重采样：只有在 AudioContext 拒绝按 16k 创建时才走到这里。
 * 用上一帧的尾样本做插值锚点，避免每批之间出现台阶。
 */
function resample(pcm: Int16Array, from: number, to: number, carry: { current: Float32Array | null }): Int16Array {
  const ratio = from / to
  const previous = carry.current
  const source = new Float32Array((previous ? previous.length : 0) + pcm.length)
  if (previous) {
    source.set(previous, 0)
  }
  for (let i = 0; i < pcm.length; i += 1) {
    source[(previous ? previous.length : 0) + i] = pcm[i] / 32768
  }
  const outputLength = Math.floor(source.length / ratio)
  if (outputLength <= 1) {
    carry.current = source
    return new Int16Array(0)
  }
  const output = new Int16Array(outputLength)
  for (let i = 0; i < outputLength; i += 1) {
    const position = i * ratio
    const left = Math.floor(position)
    const right = Math.min(left + 1, source.length - 1)
    const weight = position - left
    const sample = source[left] * (1 - weight) + source[right] * weight
    output[i] = Math.max(-1, Math.min(1, sample)) * 0x7fff
  }
  // 保留最后一个样本，下一批从这里接着插值
  const consumed = Math.floor(outputLength * ratio)
  carry.current = source.slice(Math.max(0, consumed - 1))
  return output
}
