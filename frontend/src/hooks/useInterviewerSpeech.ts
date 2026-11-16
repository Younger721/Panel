import { useCallback, useEffect, useRef, useState } from 'react'
import { interviewApi } from '@/api/interview'

/** 静音或合成失败时，用估算时长让字幕和状态照常推进。 */
const CANNED_FALLBACK_MS = 2600
const PER_CHAR_MS = 190
const MIN_ESTIMATE_MS = 1200
const MAX_ESTIMATE_MS = 20000

export type CannedSpeech = 'OPENING' | 'ACK'

export interface SpeakOptions {
  turnSeq?: number
  canned?: CannedSpeech
  /** 已知文本时用它估算降级时长（题目朗读用） */
  text?: string
}

interface SpeechController {
  speak: (options: SpeakOptions) => Promise<void>
  stop: () => void
  unlock: () => void
  speaking: boolean
  muted: boolean
  toggleMuted: () => void
}

function estimateDuration(options: SpeakOptions): number {
  if (typeof options.text === 'string' && options.text.length > 0) {
    return Math.min(MAX_ESTIMATE_MS, Math.max(MIN_ESTIMATE_MS, options.text.length * PER_CHAR_MS))
  }
  return CANNED_FALLBACK_MS
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => window.setTimeout(resolve, ms))
}

/**
 * 面试官语音播放器。
 *
 * 用 AudioContext + AudioBufferSourceNode 而不是 <audio>：
 * 需要精确知道「这一句什么时候播完」来切回倾听状态。
 *
 * 面试官的一段话必定念完：这里不提供暂停/续播，作答入口在念完之后才放开，
 * 所以不存在「半句话被截断」的状态。
 *
 * 所有失败路径都降级成「静默播完估算时长」——语音挂了面试不能停。
 */
export function useInterviewerSpeech(sessionId: number): SpeechController {
  const [speaking, setSpeaking] = useState(false)
  const [muted, setMuted] = useState(false)

  const contextRef = useRef<AudioContext | null>(null)
  const sourceRef = useRef<AudioBufferSourceNode | null>(null)
  const bufferCacheRef = useRef<Map<string, AudioBuffer>>(new Map())
  const generationRef = useRef(0)
  const resolveRef = useRef<(() => void) | null>(null)
  const mutedRef = useRef(false)

  useEffect(() => {
    mutedRef.current = muted
  }, [muted])

  const stop = useCallback(() => {
    generationRef.current += 1
    const source = sourceRef.current
    sourceRef.current = null
    if (source) {
      try {
        source.onended = null
        source.stop()
      } catch {
        /* 已经停了 */
      }
    }
    setSpeaking(false)
    const resolve = resolveRef.current
    resolveRef.current = null
    resolve?.()
  }, [])

  const unlock = useCallback(() => {
    if (!contextRef.current) {
      const Ctor =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
      if (!Ctor) {
        return
      }
      contextRef.current = new Ctor()
    }
    if (contextRef.current.state === 'suspended') {
      void contextRef.current.resume()
    }
  }, [])

  const playBuffer = useCallback(
    (context: AudioContext, buffer: AudioBuffer, generation: number): Promise<void> => {
      return new Promise(resolve => {
        const source = context.createBufferSource()
        source.buffer = buffer
        source.connect(context.destination)
        sourceRef.current = source
        resolveRef.current = resolve
        source.onended = () => {
          if (generation !== generationRef.current) {
            return
          }
          sourceRef.current = null
          resolveRef.current = null
          setSpeaking(false)
          resolve()
        }
        setSpeaking(true)
        source.start(0)
      })
    },
    []
  )
  const speak = useCallback(
    async (options: SpeakOptions) => {
      if (!sessionId) {
        return
      }
      stop()
      const generation = generationRef.current
      const estimated = estimateDuration(options)

      if (mutedRef.current) {
        await delay(estimated)
        return
      }
      const context = contextRef.current
      if (!context) {
        await delay(estimated)
        return
      }

      const cacheKey =
        options.turnSeq !== undefined ? `turn:${options.turnSeq}` : `canned:${options.canned ?? 'OPENING'}`
      let buffer = bufferCacheRef.current.get(cacheKey)
      if (!buffer) {
        const blob = await interviewApi.speech(sessionId, {
          turnSeq: options.turnSeq,
          canned: options.canned
        })
        if (generation !== generationRef.current) {
          return
        }
        if (!blob) {
          await delay(estimated)
          return
        }
        try {
          const decoded = await context.decodeAudioData(await blob.arrayBuffer())
          buffer = decoded
          bufferCacheRef.current.set(cacheKey, decoded)
        } catch {
          await delay(estimated)
          return
        }
      }
      if (generation !== generationRef.current) {
        return
      }
      await playBuffer(context, buffer, generation)
    },
    [playBuffer, sessionId, stop]
  )

  useEffect(() => {
    return () => {
      generationRef.current += 1
      const source = sourceRef.current
      if (source) {
        try {
          source.stop()
        } catch {
          /* 忽略 */
        }
      }
      const context = contextRef.current
      contextRef.current = null
      if (context) {
        void context.close()
      }
    }
  }, [])

  const toggleMuted = useCallback(() => {
    setMuted(previous => {
      if (!previous) {
        stop()
      }
      return !previous
    })
  }, [stop])

  return { speak, stop, unlock, speaking, muted, toggleMuted }
}
