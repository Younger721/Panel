import { useCallback, useEffect, useRef, useState } from 'react'
import DigitalInterviewer, {
  FINISHED_BOX,
  LIFTED_BOX,
  type InterviewerPhase
} from './DigitalInterviewer'

/** 交叉淡入时长：太短会看见两帧叠影，太长会让「她开口」慢半拍。240ms 实测刚好。 */
const FADE_MS = 240

const LISTEN_SRC = '/interviewer/listen-loop-v3.webm'
const DEFAULT_SPEAK_SRC = '/interviewer/speak-alpha.webm'

/**
 * 视频路线总开关。
 *
 * 2026-11-13 换过素材后重开。新素材（即梦 10 秒任务出的 5 秒片子）实测：
 *   头发重心垂直跨度 1.4 px，末帧相对首帧位移 (0, 0)，
 *   121 帧透明率 48.7% ~ 49.1%，无坏帧。
 * 也就是说它天生就是一个能闭环的片段，crossfade 融合 10 帧之后接缝完全消失。
 *
 * 旧素材（2428）为什么关掉过：那是一段单向长镜头，头在 1.6 秒里持续下沉 12~14 px，
 * 任何循环都必须把这 12 px 还回去，而「还账」那一刻就是肉眼可见的跳。
 * 运动补偿能把跨度压到 5 px，但观感变成「头在极缓慢地上下浮」，更怪。
 *
 * 判断素材能不能开这条路，看三个数：
 *   ① 头发重心跨度  < 3 px
 *   ② 末帧→首帧位移 < 3 px
 *   ③ 坏帧数        = 0
 * 三项都过，`USE_VIDEO = true` 才成立。
 */
const USE_VIDEO: boolean = true

/**
 * 视频能力分级。
 *
 * `alpha`：可以播带透明通道的 VP9 webm —— 人像直接叠在办公场景上，没有矩形边界。
 * `none` ：退回静态 `base.webp`。
 *
 * 为什么 Safari 一律判 none：Safari 能解 VP9（macOS 14+），但**不认 VP9 的 alpha 通道**
 * （那是 Chromium / Firefox 的实现）。alpha 一丢，透明区会渲染成黑底方块，比静态图难看得多。
 * 这里宁可主动降级，也不赌 UA 探测的边界情况。
 */
type VideoSupport = 'alpha' | 'none'

function detectVideoSupport(): VideoSupport {
  if (typeof document === 'undefined' || typeof navigator === 'undefined') {
    return 'none'
  }
  const probe = document.createElement('video')
  if (!probe.canPlayType('video/webm; codecs="vp9"')) {
    return 'none'
  }
  const ua = navigator.userAgent
  // 排除 chrome / chromium / android / Edge（UA 里带 Chrome 字样）
  const isSafari = /^((?!chrome|chromium|android|crios|fxios).)*safari/i.test(ua)
  const isIOS = /iphone|ipad|ipod/i.test(ua)
  if (isSafari || isIOS) {
    return 'none'
  }
  // 用户开了省流：几百 KB 的视频不值得为它破例
  const connection = (navigator as unknown as { connection?: { saveData?: boolean } }).connection
  if (connection?.saveData) {
    return 'none'
  }
  return 'alpha'
}

interface Props {
  /** 作答条在场时人像下沿探到作答条后面；面试结束后作答条收起，人像落到底 */
  lifted: boolean
  /** 驱动画面状态。SPEAKING 播说话素材，其余一律回倾听循环 */
  phase: InterviewerPhase
  /** 指定说话素材；缺省用 speak-alpha.webm（临时素材，口型尚未与 TTS 对齐） */
  speakSrc?: string
  /**
   * 说话素材是否循环。
   * 现在为 true：speak-alpha.webm 只有 6.6 秒，而一句提问可能十几秒，循环至少保证「她在动」。
   * 等 MuseTalk 出片（口型按音频生成、时长与音频一致）后改成 false，
   * 播完触发 onSpeechEnd 推进流程。
   */
  speakLoop?: boolean
  /** 说话素材播完（speakLoop=false 时才有意义） */
  onSpeechEnd?: () => void
}

/**
 * 面试官：两个视频叠放，靠 opacity 交叉淡入切状态。
 *
 * 三个不许动的细节，动了就会露出破绽：
 *
 * 1. **视频永远静音**。声音走 `useInterviewerSpeech`（AudioContext 播 TTS），
 *    视频只提供画面。同一份音频播两遍会变成回声。
 * 2. **倾听视频永不暂停**。SPEAKING 时只是 `opacity: 0`，`play()` 一直在跑。
 *    一旦暂停，下次切回来会从第一帧重来，眨眼动作会「跳」一下。
 * 3. **不能用 `display: none` 隐藏**。Chrome 会暂停不可见的 video，第 2 条立刻失效。
 *    所以只能 opacity + pointer-events。
 */
export default function VideoInterviewer({
  lifted,
  phase,
  speakSrc = DEFAULT_SPEAK_SRC,
  speakLoop = true,
  onSpeechEnd
}: Props) {
  const [support, setSupport] = useState<VideoSupport | null>(null)
  const [speaking, setSpeaking] = useState(false)
  const listenRef = useRef<HTMLVideoElement>(null)
  const speakRef = useRef<HTMLVideoElement>(null)

  useEffect(() => {
    setSupport(detectVideoSupport())
  }, [])

  const wantsSpeak = phase === 'SPEAKING'

  useEffect(() => {
    const speakEl = speakRef.current
    if (!speakEl) {
      return
    }
    if (wantsSpeak) {
      setSpeaking(true)
      // 每条新台词都从第一帧开始：否则第二次提问会接着上次的位置，嘴型对不上开头
      try {
        speakEl.currentTime = 0
      } catch {
        /* 元数据还没到，play 之后再说 */
      }
      void speakEl.play().catch(() => {
        /* 自动播放被拦：保持倾听画面，不报错 */
      })
      return
    }
    setSpeaking(false)
    speakEl.pause()
  }, [wantsSpeak, speakSrc])

  /** 倾听视频要主动 play 一次：部分浏览器拿到 canplay 之前 autoplay 属性不生效 */
  const handleListenReady = useCallback(() => {
    const el = listenRef.current
    if (el) {
      void el.play().catch(() => {})
    }
  }, [])

  const handleSpeakEnded = useCallback(() => {
    setSpeaking(false)
    onSpeechEnd?.()
  }, [onSpeechEnd])

  /** 素材挂了（404 / 解码失败）就当没有视频能力，交给静态图 */
  const handleError = useCallback(() => {
    setSupport('none')
  }, [])

  // 视频路线关闭：素材循环会「跳」，直接用静态图 + 呼吸
  if (!USE_VIDEO) {
    return <DigitalInterviewer lifted={lifted} />
  }

  // 首次渲染前不知道能力，先按静态图铺位，避免先出视频容器再塌陷
  if (support === null) {
    return <DigitalInterviewer lifted={lifted} />
  }

  if (support === 'none') {
    return <DigitalInterviewer lifted={lifted} />
  }

  return (
    /* 外层布局与 DigitalInterviewer 完全一致：题面以下、作答条以上的全部剩余空间 */
    <div
      className="pointer-events-none relative z-10 min-h-0 flex-1"
      style={{
        filter:
          'drop-shadow(0 18px 26px rgba(46, 44, 38, 0.16)) drop-shadow(0 2px 6px rgba(46, 44, 38, 0.10))'
      }}
    >
      <div className={`absolute inset-x-0 ${lifted ? LIFTED_BOX : FINISHED_BOX}`}>
        {/* 素材是 620×540（31:27），不是方图；宽高都由高度推出来，水平居中。
            外层的 interviewer-breath 是 0.6% 的极慢缩放：视频本身有 10 秒的循环节奏，
            叠一层 9 秒、不同周期的呼吸，两个周期不会同时对齐，静态感就被打散了。 */}
        <div className="interviewer-breath relative mx-auto h-full aspect-[31/27]">
          <video
            ref={listenRef}
            src={LISTEN_SRC}
            muted
            loop
            playsInline
            autoPlay
            preload="auto"
            onCanPlay={handleListenReady}
            onError={handleError}
            className="absolute inset-0 h-full w-full object-contain object-top transition-opacity"
            style={{ opacity: speaking ? 0 : 1, transitionDuration: `${FADE_MS}ms` }}
          />
          <video
            ref={speakRef}
            src={speakSrc}
            muted
            loop={speakLoop}
            playsInline
            preload="auto"
            onEnded={handleSpeakEnded}
            onError={handleError}
            className="absolute inset-0 h-full w-full object-contain object-top transition-opacity"
            style={{ opacity: speaking ? 1 : 0, transitionDuration: `${FADE_MS}ms` }}
          />
        </div>
      </div>
    </div>
  )
}
