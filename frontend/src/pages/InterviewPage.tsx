import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  Camera,
  CameraOff,
  ChevronLeft,
  ChevronRight,
  History,
  Loader2,
  Mic,
  MicOff,
  Send,
  Square,
  Volume2,
  VolumeX,
  X
} from 'lucide-react'
import { interviewApi, type AgentProgress } from '@/api/interview'
import AgentSteps, { agentLabel } from '@/components/AgentSteps'
import { type InterviewerPhase } from '@/components/DigitalInterviewer'
import VideoInterviewer from '@/components/VideoInterviewer'
import SelfCameraPiP from '@/components/SelfCameraPiP'
import { useInterviewerSpeech } from '@/hooks/useInterviewerSpeech'
import { usePushToTalk } from '@/hooks/usePushToTalk'
import type { InterviewSessionView, InterviewTurnView } from '@/types/api'

/**
 * 人设展示文案。与后端 InterviewerPersona 一一对应，
 * 留着只为兜底（后端返回未知 code 时也能显示），不是唯一真源。
 */
const PERSONA_META: Record<string, { label: string; title: string }> = {
  SERIOUS: { label: '严肃型', title: '技术面试官' },
  WARM: { label: '亲和型', title: 'HR 面试官' },
  PRESSURE: { label: '压力面', title: '资深技术专家' }
}

const PHASE_TEXT: Record<InterviewerPhase, string> = {
  PREPARING: '准备中',
  THINKING: '正在思考',
  SPEAKING: '正在提问',
  LISTENING: '正在听你回答',
  FINISHED: '面试结束'
}

/**
 * 题面的字号分档。真实数据里题面从 114 字（开场）到 300 字以上（追问叠了上下文），
 * 同一套字号撑不住：小字读着费劲，大字会把题面顶到人像身上。
 *
 * 所以按字数缩字号，行数上限再兜一层（`QUESTION_BOX` 的 max-height）。
 * 注意这些 class 必须是字面量，Tailwind 才扫得到。
 */
const QUESTION_SCALE: { max: number; cls: string }[] = [
  { max: 60, cls: 'text-[26px] leading-[1.55] xl:text-[30px]' },
  { max: 120, cls: 'text-[24px] leading-[1.55] xl:text-[27px]' },
  { max: 200, cls: 'text-[22px] leading-[1.6] xl:text-[24px]' },
  { max: Number.POSITIVE_INFINITY, cls: 'text-[20px] leading-[1.65] xl:text-[22px]' }
]

function questionScale(text: string): string {
  return (QUESTION_SCALE.find(scale => text.length <= scale.max) ?? QUESTION_SCALE[0]).cls
}

/**
 * 题面盒子的硬上限：超过就自己滚，且底部渐隐提示「下面还有」。
 * 极端长题（实测见过 434 字）如果放任它长，人像会被挤成一寸照——
 * 这里改成让文字在自己的区域里滚，而不是去占人像的地盘。
 */
const QUESTION_BOX = 'max-h-[min(44vh,340px)] overflow-y-auto scrollbar-thin'
const QUESTION_BOX_FADED =
  '[mask-image:linear-gradient(to_bottom,#000_calc(100%-34px),transparent)]'

function formatClock(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
}

/** 已答完的一轮。分数留到报告，面试中只留题目和你的回答。 */
function TranscriptEntry({ turn }: { turn: InterviewTurnView }) {
  const isFollowUp = turn.kind === 'FOLLOW_UP'
  const isOpening = turn.kind === 'OPENING'
  return (
    <li className="border-b border-slate-100 py-4 first:pt-0 last:border-0 last:pb-0">
      <div className="flex items-center gap-2">
        <span
          className={`flex h-5 min-w-5 items-center justify-center rounded-md px-1 font-mono text-[10px] font-semibold ${
            isFollowUp ? 'bg-amber-100 text-amber-700' : 'bg-indigo-100 text-indigo-700'
          }`}
        >
          Q{turn.seq}
        </span>
        {isFollowUp && (
          <span className="rounded bg-slate-100 px-1 py-0.5 text-[9.5px] text-slate-500">
            追问 L{turn.depth}
          </span>
        )}
        {isOpening && (
          <span className="rounded bg-emerald-50 px-1 py-0.5 text-[9.5px] text-emerald-600">
            开场
          </span>
        )}
        <span className="truncate text-[10.5px] text-slate-400">{turn.skill}</span>
      </div>
      <p className="mt-1.5 text-[13px] leading-relaxed text-slate-800">{turn.question}</p>
      <p className="mt-1.5 whitespace-pre-wrap border-l-2 border-indigo-200 pl-2.5 text-[12px] leading-relaxed text-slate-500">
        {turn.answer}
      </p>
    </li>
  )
}

export default function InterviewPage() {
  const params = useParams()
  const navigate = useNavigate()
  const sessionId = Number(params.id)

  const [session, setSession] = useState<InterviewSessionView | null>(null)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const [hint, setHint] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [started, setStarted] = useState(false)
  const [cameraOn, setCameraOn] = useState(false)
  const [transcriptOpen, setTranscriptOpen] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [asrError, setAsrError] = useState<string | null>(null)
  const [multiWindow, setMultiWindow] = useState(false)
  const [reportSummary, setReportSummary] = useState('')
  /** 结束面试请求在途：防止连点，也让按钮有个"正在结束"的样子 */
  const [abandoning, setAbandoning] = useState(false)
  /** 本轮作答正在跑的 agent 步骤，只在等待期间显示（见左下角浮层）。 */
  const [steps, setSteps] = useState<AgentProgress[]>([])
  const bottomRef = useRef<HTMLLIElement | null>(null)
  /** 题面盒子会不会滚：只有真的溢出时才加底部渐隐，否则会把最后一行也淡掉 */
  const questionBoxRef = useRef<HTMLDivElement | null>(null)
  const [questionOverflow, setQuestionOverflow] = useState(false)
  const disposedRef = useRef(false)

  const speech = useInterviewerSpeech(sessionId)

  // 同一场面试在多个窗口打开时给个提示：不拦人，只是让他知道——
  // 后端的原子认领会把第二份提交挡成 409，但用户看到错误时已经答完一遍了。
  useEffect(() => {
    if (!Number.isFinite(sessionId) || typeof BroadcastChannel === 'undefined') {
      return
    }
    const channel = new BroadcastChannel(`panel-interview-${sessionId}`)
    const selfId = Math.random().toString(36).slice(2)
    channel.onmessage = event => {
      const data = event.data as { from?: string; kind?: string } | undefined
      if (!data || data.from === selfId) {
        return
      }
      setMultiWindow(true)
      // 只对对方的 probe 回一次，anyone 不再回，避免两个窗口互相 ping 成风暴
      if (data.kind === 'probe') {
        channel.postMessage({ from: selfId, kind: 'anyone' })
      }
    }
    channel.postMessage({ from: selfId, kind: 'probe' })
    return () => channel.close()
  }, [sessionId])

  useEffect(() => {
    disposedRef.current = false
    let cancelled = false
    interviewApi
      .detail(sessionId)
      .then(view => {
        if (!cancelled) {
          setSession(view)
        }
      })
      .catch(err => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '加载失败')
        }
      })
    return () => {
      cancelled = true
      disposedRef.current = true
    }
  }, [sessionId])

  const answered = useMemo(
    () => session?.turns.filter(turn => turn.answer !== null) ?? [],
    [session]
  )
  const pendingTurn = useMemo(
    () => session?.turns.find(turn => turn.answer === null) ?? null,
    [session]
  )
  const answeredMains = answered.filter(turn => turn.kind !== 'FOLLOW_UP').length

  useEffect(() => {
    if (transcriptOpen) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
    }
  }, [answered.length, transcriptOpen])

  const completed = session?.status === 'COMPLETED' || session?.status === 'ABANDONED'

  /**
   * 语音输入。定稿文本追加到草稿末尾（光标本来就在末尾）。
   *
   * 面试官的一段话必定念完：说话键在 SPEAKING 期间是禁用的，
   * 所以录音里不会混进面试官的声音，也不会出现「半句话被截断」。
   */
  const voice = usePushToTalk({
    sessionId: Number.isFinite(sessionId) ? sessionId : null,
    enabled: started && !completed && !busy && !speech.speaking,
    onCommit: text =>
      setDraft(previous => {
        if (!previous) {
          return text
        }
        const needsSpace = /[A-Za-z0-9]$/.test(previous)
        return needsSpace ? `${previous} ${text}` : `${previous}${text}`
      }),
    onError: message => setAsrError(message)
  })

  useEffect(() => {
    if (!started || completed) {
      return
    }
    const timer = window.setInterval(() => setElapsed(previous => previous + 1), 1000)
    return () => window.clearInterval(timer)
  }, [started, completed])

  const persona = PERSONA_META[session?.persona ?? 'SERIOUS'] ?? PERSONA_META.SERIOUS

  const voiceActive = voice.state === 'CONNECTING' || voice.state === 'STREAMING'
  const phase: InterviewerPhase = !started
    ? 'PREPARING'
    : speech.speaking
      ? 'SPEAKING'
      : completed
        ? 'FINISHED'
        : voiceActive
          ? 'LISTENING'
          : voice.state === 'FINISHING' || busy || preparing
            ? 'THINKING'
            : 'LISTENING'

  /** 「开始面试」必须是一个真实点击：解锁 AudioContext、申请摄像头，顺便盖住首题冷启动。 */
  async function startInterview() {
    if (!session || started) {
      return
    }
    speech.unlock()
    // 和摄像头一样，麦克风也只在这唯一一次真实点击里申请，
    // 之后整场复用同一条 stream，避免每说一句弹一次权限
    void voice.prepare()
    setStarted(true)
    setPreparing(true)
    try {
      if (session.turns.length === 0) {
        setHint('面试官正在开场')
        await speech.speak({ canned: 'OPENING' })
      }
    } finally {
      if (!disposedRef.current) {
        setPreparing(false)
        setHint(null)
      }
    }
    if (disposedRef.current) {
      return
    }
    // 念题不 await：念的同时要让用户能立刻开始敲答案
    const last = session.turns[session.turns.length - 1]
    if (last && last.answer === null) {
      void speech.speak({ turnSeq: last.seq, text: last.question })
    }
  }

  /**
   * 主动结束这场面试。
   *
   * <p>没有这个出口，用户关掉浏览器就等于把会话永久留在「进行中」：历史页一直挂着、
   * 报告打不开、语音券还能继续签。结束后会话进入 ABANDONED，已答的题目与评分全部保留。</p>
   */
  async function abandonInterview() {
    if (abandoning || completed) {
      return
    }
    const confirmed =
      typeof window === 'undefined' ||
      window.confirm('结束这场面试？已经答过的题目和评分都会保留，只是不再继续提问。')
    if (!confirmed) {
      return
    }
    setAbandoning(true)
    setError(null)
    try {
      voice.stop()
      speech.stop()
      const view = await interviewApi.abandon(sessionId)
      if (!disposedRef.current) {
        setSession(view)
      }
    } catch (err) {
      if (!disposedRef.current) {
        setError(err instanceof Error ? err.message : '结束失败')
      }
    } finally {
      if (!disposedRef.current) {
        setAbandoning(false)
      }
    }
  }

  async function submit() {
    const text = draft.trim()
    if (!text || busy || completed) {
      return
    }
    setBusy(true)
    setError(null)
    setDraft('')
    setSteps([])
    setReportSummary('')
    setHint('面试官正在整理你的回答')
    // 先出声再等模型：模型要 3-15 秒，静音等待会让"数字人"立刻破功。
    // 这句承接语刻意不预告"下一题还是追问"。
    void speech.speak({ canned: 'ACK' })

    let failure: string | null = null
    let finished = false
    try {
      await interviewApi.answerStream(sessionId, text, pendingTurn?.seq, (type, data) => {
        if (type === 'agent') {
          const progress = data as AgentProgress
          // 同一个 agent 的 STARTED 被它的 COMPLETED 原地替换（顺序不能跳，否则
          // 两位评委并行时列表会来回换位置）；没见过的 agent 追加到末尾。
          setSteps(previous => {
            const index = previous.findIndex(step => step.agent === progress.agent)
            if (index === -1) {
              return [...previous, progress]
            }
            const next = [...previous]
            next[index] = progress
            return next
          })
          setHint(`${agentLabel(progress.agent)} · ${progress.stage}`)
        } else if (type === 'report-summary') {
          // 总评先流式出字：用户在等分项报告的那半分钟里已经有结论可读了
          const delta = (data as { delta?: string }).delta ?? ''
          if (delta) {
            setReportSummary(previous => previous + delta)
          }
        } else if (type === 'report') {
          finished = true
        } else if (type === 'error') {
          failure = (data as { message?: string }).message || '提交失败'
        }
      })
      if (failure) {
        setError(failure)
        setDraft(text)
        return
      }
      const view = await interviewApi.detail(sessionId)
      if (disposedRef.current) {
        return
      }
      setSession(view)

      const last = view.turns[view.turns.length - 1]
      if (finished || view.status === 'COMPLETED') {
        speech.stop()
        return
      }
      if (last && last.answer === null) {
        // 题目刚提交完事务才可见，语音接口要等 detail 回来之后再请求。
        // 不 await：念题期间用户应该能直接开始写下一段。
        void speech.speak({ turnSeq: last.seq, text: last.question })
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交失败')
      setDraft(text)
    } finally {
      if (!disposedRef.current) {
        setBusy(false)
        setHint(null)
      }
    }
  }

  /** 题面文案：结束态、待答题、开场前各一句。提到早退之前，测量 effect 才能依赖它。 */
  const questionText = completed
    ? '你的回答已全部记录，评分和逐题复盘都在报告里。'
    : pendingTurn
      ? pendingTurn.question
      : '面试官马上开始提问。'

  /**
   * 题面溢出探测。CSS 的 max-height 会静默截断，用户看不出下面还有字，
   * 所以溢出时补一层底部渐隐 + 一行"滚动查看"的提示。字一换就重测。
   */
  useLayoutEffect(() => {
    const box = questionBoxRef.current
    if (!box) {
      setQuestionOverflow(false)
      return
    }
    const sync = () => setQuestionOverflow(box.scrollHeight - box.clientHeight > 2)
    sync()
    const observer = new ResizeObserver(sync)
    observer.observe(box)
    void document.fonts?.ready.then(sync)
    return () => observer.disconnect()
  }, [questionText, session])

  if (error && !session) {
    return <p className="py-24 text-center text-sm text-rose-500">{error}</p>
  }
  if (!session) {
    return <p className="py-24 text-center text-sm text-slate-500">加载中…</p>
  }

  return (
    <div className="relative h-full w-full overflow-hidden bg-[#e9edf2] text-slate-800">
      {/* 背景：整屏一张真实的现代办公室照片（已虚化，见 scripts/make-room-background.py）。
          人像素材已经抠成透明，直接叠在这张图上面——不是贴在照片上的一个方块 */}
      <img
        src="/interviewer/room.webp"
        alt=""
        className="pointer-events-none absolute inset-0 h-full w-full object-cover"
      />
      {/* 白色薄纱只为了压住左半边的背景，让题目文字始终可读；
          背景已经虚化过，所以比原先收了一档，别把画面洗成一片白 */}
      <div className="pointer-events-none absolute inset-0 bg-gradient-to-r from-white/60 via-white/30 to-white/5" />
      <div className="pointer-events-none absolute inset-x-0 top-0 h-20 bg-gradient-to-b from-white/45 to-transparent" />
      <div className="pointer-events-none absolute inset-x-0 bottom-0 h-28 bg-gradient-to-t from-white/35 to-transparent" />

      {/* 舞台纵向流：题面按内容高，人像吃掉剩下的高度。
          字和人是同一列里的两个格子，所以「字压住头发」在结构上不可能发生。 */}

      {/* 顶栏 */}
      <header className="absolute inset-x-0 top-0 z-20 flex h-14 items-center gap-3 px-8">
        <Link
          to={`/resumes/${session.resumeId}`}
          className="flex items-center gap-1 rounded-lg px-1.5 py-1 text-[12.5px] text-slate-600 transition-colors hover:bg-white/60 hover:text-slate-900"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          退出
        </Link>
        <span className="text-[13px] font-medium text-slate-700">模拟面试 · {persona.label}{persona.title}</span>

        <div className="ml-3 flex items-center gap-1.5 rounded-full border border-rose-200/60 bg-white/55 px-2.5 py-1 backdrop-blur">
          <span className={`h-1.5 w-1.5 rounded-full ${completed ? 'bg-slate-400' : 'bg-rose-500 animate-pulse'}`} />
          <span className="font-mono text-[11px] tabular-nums text-slate-600">
            {completed ? '已结束' : `面试中 ${formatClock(elapsed)}`}
          </span>
        </div>

        <div className="ml-auto flex items-center gap-2">
          <span className="rounded-full border border-white/60 bg-white/45 px-2.5 py-1 text-[11px] text-slate-500 backdrop-blur">
            主问题 {answeredMains}/{session.questionCount}
          </span>
          <button
            type="button"
            onClick={() => setTranscriptOpen(true)}
            className="flex items-center gap-1.5 rounded-full border border-white/60 bg-white/45 px-2.5 py-1 text-[11px] text-slate-600 backdrop-blur transition hover:bg-white/80"
          >
            <History className="h-3.5 w-3.5" />
            记录 {answered.length}
          </button>
          <button
            type="button"
            onClick={speech.toggleMuted}
            title={speech.muted ? '开启面试官声音' : '静音'}
            className="flex h-8 w-8 items-center justify-center rounded-full border border-white/60 bg-white/45 text-slate-600 backdrop-blur transition hover:bg-white/80"
          >
            {speech.muted ? <VolumeX className="h-4 w-4" /> : <Volume2 className="h-4 w-4" />}
          </button>
          <button
            type="button"
            onClick={() => setCameraOn(value => !value)}
            title={cameraOn ? '关闭摄像头' : '打开摄像头'}
            className="flex h-8 w-8 items-center justify-center rounded-full border border-white/60 bg-white/45 text-slate-600 backdrop-blur transition hover:bg-white/80"
          >
            {cameraOn ? <Camera className="h-4 w-4" /> : <CameraOff className="h-4 w-4" />}
          </button>
          {!completed && (
            <button
              type="button"
              onClick={abandonInterview}
              disabled={abandoning}
              title="结束这场面试（已答内容保留）"
              className="flex items-center gap-1 rounded-full border border-white/60 bg-white/45 px-2.5 py-1 text-[11px] text-slate-600 backdrop-blur transition hover:bg-white/80 disabled:opacity-50"
            >
              <Square className="h-3 w-3" />
              {abandoning ? '结束中…' : '结束'}
            </button>
          )}
        </div>
      </header>

      <div className="relative z-10 flex h-full flex-col">
        {/* 题面：高度由这一题的字数决定。字号按字数分档，再给一个高度上限
            （超长题在自己的区域里滚，不去挤人像）。右缘给右上角的自己留出位置，
            窄屏也不会被摄像头浮窗压住。 */}
        <div className="relative z-20 shrink-0 px-6 pt-[clamp(60px,12.5vh,116px)]">
          <div className="mx-auto w-full max-w-[min(880px,calc(100vw-500px))] text-center">
            <p className="text-[11px] font-semibold uppercase tracking-[0.22em] text-slate-400">
              {completed
                ? '面试已结束'
                : pendingTurn
                  ? pendingTurn.kind === 'FOLLOW_UP'
                    ? `第 ${pendingTurn.depth} 层追问`
                    : pendingTurn.kind === 'OPENING'
                      ? '开场 · 自我介绍'
                      : '面试官提问'
                  : '准备中'}
            </p>
            <div
              ref={questionBoxRef}
              className={`mt-4 ${QUESTION_BOX} ${questionOverflow ? QUESTION_BOX_FADED : ''}`}
            >
              <h1
                className={`font-medium tracking-tight text-slate-700 [text-wrap:balance] ${questionScale(
                  questionText
                )}`}
              >
                {questionText}
              </h1>
            </div>
            {!completed && pendingTurn && (
              <p className="mt-3 text-[12px] text-slate-500">
                {pendingTurn.skill} · {pendingTurn.difficulty}
                <span className="mx-2 text-slate-300">|</span>
                <span
                  className={`inline-block h-1.5 w-1.5 -translate-y-px rounded-full ${
                    phase === 'SPEAKING'
                      ? 'bg-emerald-500 animate-pulse'
                      : phase === 'THINKING'
                        ? 'bg-amber-500 animate-pulse'
                        : 'bg-slate-400'
                  }`}
                />
                <span className="ml-1.5">{hint || PHASE_TEXT[phase]}</span>
                {questionOverflow && (
                  <span className="ml-2 text-slate-400">· 题面较长，滚动可看全文</span>
                )}
              </p>
            )}
            {completed && (
              <div className="mt-6 flex justify-center">
                <button
                  onClick={() => navigate(`/interview/${session.id}/report`)}
                  className="flex h-12 items-center gap-2 rounded-full bg-emerald-500 px-9 text-sm font-medium text-white shadow-lg shadow-emerald-500/25 transition hover:bg-emerald-600"
                >
                  查看面试报告
                  <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            )}
          </div>
        </div>

        {/* 面试官本体：顶边就贴在题面底边下面（组件里那条 top-7 的硬间隙）。
            视频能力不足（Safari / 省流 / 素材 404）时组件内部自动降级到静态图。 */}
        <VideoInterviewer lifted={!completed} phase={phase} />
      </div>

      {/* 作答条：贴着画面底边。
          它同时干两件事——作答入口，以及盖住人像下沿的裁切边。
          人像素材的肩膀在图片 84.2% 处就被截断了，必须靠底部出画 + 这条把它藏掉。 */}
      {!completed && (
        <div className="absolute inset-x-0 bottom-0 z-20">
          <div className="mx-auto w-full max-w-[800px] px-5 pb-4 pt-2">
            <div className="rounded-2xl border border-white/70 bg-white/90 p-2 shadow-[0_-18px_50px_-30px_rgba(15,23,42,0.55)] backdrop-blur-md">
              <div className="flex items-end gap-2">
                {/* 麦克风：按住说话，松手转文字。和空格走同一对 start/stop。 */}
                {voice.available && voice.voiceMode ? (
                  <button
                    type="button"
                    onPointerDown={event => {
                      if (event.button !== 0) {
                        return
                      }
                      event.currentTarget.setPointerCapture(event.pointerId)
                      voice.start()
                    }}
                    onPointerUp={() => voice.stop()}
                    onPointerCancel={() => voice.cancel()}
                    disabled={busy || speech.speaking}
                    title={
                      speech.speaking
                        ? '等面试官说完再作答'
                        : voiceActive
                          ? '松手结束'
                          : '按住说话（也可以按住空格）'
                    }
                    className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition disabled:cursor-not-allowed disabled:opacity-40 ${
                      voiceActive
                        ? 'bg-rose-500 text-white shadow-lg shadow-rose-500/30'
                        : 'bg-slate-100 text-slate-500 hover:bg-slate-200'
                    }`}
                  >
                    {voice.state === 'FINISHING' ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : voiceActive ? (
                      <span className="flex h-4 items-end gap-[3px]">
                        {[0.4, 1, 0.65].map((weight, index) => (
                          <span
                            key={index}
                            className="w-[3px] rounded-full bg-white transition-all duration-100"
                            style={{ height: `${Math.max(4, Math.min(16, weight * voice.level * 46))}px` }}
                          />
                        ))}
                      </span>
                    ) : (
                      <Mic className="h-4 w-4" />
                    )}
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => voice.setVoiceMode(true)}
                    disabled={!voice.available}
                    title={voice.available ? '开启语音输入' : '语音输入当前不可用'}
                    className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-slate-100 text-slate-300 transition hover:bg-slate-200 hover:text-slate-500 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    <MicOff className="h-4 w-4" />
                  </button>
                )}

                <div className="min-w-0 flex-1">
                  {/* 幽灵文本：只显示当前句的中间结果，定稿后才进草稿 */}
                  {voice.partial && (
                    <p className="px-2.5 pt-1.5 text-[13.5px] leading-relaxed text-slate-400">
                      {voice.partial}
                      <span className="ml-0.5 inline-block h-[13px] w-[2px] animate-pulse bg-emerald-400 align-middle" />
                    </p>
                  )}
                  <textarea
                    value={draft}
                    onChange={event => setDraft(event.target.value)}
                    onKeyDown={event => {
                      if (event.key === 'Escape' && voiceActive) {
                        event.preventDefault()
                        voice.cancel()
                        return
                      }
                      if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                        void submit()
                      }
                    }}
                    rows={2}
                    disabled={busy}
                    autoFocus
                    placeholder={
                      busy
                        ? hint || '面试官正在思考…'
                        : voiceActive
                          ? '正在听…'
                          : '写下你的回答，说清楚做法和结果'
                    }
                    className="max-h-40 w-full resize-none bg-transparent px-2.5 py-1.5 text-[13.5px] leading-relaxed text-slate-800 outline-none placeholder:text-slate-400 disabled:opacity-60"
                  />
                </div>
                <button
                  onClick={() => void submit()}
                  disabled={busy || !draft.trim()}
                  title="提交回答（⌘/Ctrl + Enter）"
                  className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-emerald-500 text-white shadow-lg shadow-emerald-500/30 transition hover:bg-emerald-600 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                </button>
              </div>

              {/* 状态行：录音进度 / 快捷键提示 / 语音不可用的原因 */}
              <div className="mt-1 flex flex-wrap items-center justify-center gap-x-2 text-[10.5px]">
                {voiceActive ? (
                  <span className="tabular-nums text-rose-500">
                    {formatClock(voice.seconds)} · 正在听，松手转文字（Esc 取消）
                  </span>
                ) : voice.state === 'FINISHING' ? (
                  <span className="text-slate-400">正在整理最后一句…</span>
                ) : !voice.available ? (
                  <span className="text-slate-400">语音输入不可用，直接用键盘作答</span>
                ) : speech.speaking ? (
                  <span className="text-slate-400">面试官念完题才能作答</span>
                ) : voice.voiceMode ? (
                  <span className="text-slate-400">按住空格说话 · 松手转文字</span>
                ) : (
                  <span className="text-slate-400">
                    语音输入已关闭
                    <button
                      type="button"
                      onClick={() => voice.setVoiceMode(true)}
                      className="ml-1 text-emerald-600 underline decoration-dotted"
                    >
                      开启
                    </button>
                  </span>
                )}
                {asrError && <span className="text-amber-600">{asrError}</span>}
              </div>
            </div>
            {error && (
              <p className="mt-1.5 text-center text-[10.5px] text-rose-500">{error}</p>
            )}
            {multiWindow && (
              <p className="mt-1.5 text-center text-[10.5px] text-amber-600">
                这场面试在另一个窗口也打开了。两边都答题的话，后提交的那次会被拒绝——建议只在一个窗口作答。
              </p>
            )}
            {reportSummary && (
              <div className="mt-3 rounded-xl bg-emerald-50/70 px-3 py-2">
                <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-emerald-600">
                  本场总评 · 正在生成
                </p>
                <p className="mt-1 whitespace-pre-wrap text-[12.5px] leading-relaxed text-slate-700">
                  {reportSummary}
                </p>
              </div>
            )}
          </div>
        </div>
      )}

      {/* 右上：自己的摄像头，下面挂一条说明，和真实线上面试一致 */}
      <div className="absolute right-8 top-16 z-20 w-[190px]">
        {cameraOn ? (
          <SelfCameraPiP enabled={cameraOn} />
        ) : (
          <button
            onClick={() => setCameraOn(true)}
            className="flex aspect-[4/3] w-full flex-col items-center justify-center gap-1.5 rounded-xl border border-white/70 bg-slate-800/25 text-white/85 backdrop-blur transition hover:bg-slate-800/40"
          >
            <CameraOff className="h-5 w-5" />
            <span className="text-[11px]">打开摄像头</span>
          </button>
        )}
        <div className="mt-1.5 flex items-center justify-center gap-1.5 rounded-full border border-white/60 bg-white/60 px-2 py-1 backdrop-blur">
          <span className="text-[10px] text-slate-500">保持正对镜头</span>
        </div>
      </div>

      {/* 左下：本轮评审流程。只在等结果时出现——那是用户唯一需要知道
          "为什么这道题要等十秒"的时刻，也是这套多 agent 唯一能被看见的地方 */}
      {busy && steps.length > 0 && (
        <div className="pointer-events-none absolute left-8 top-16 z-20 hidden w-[190px] xl:block">
          <p className="mb-1.5 px-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-400">
            AI 评审流程
          </p>
          <AgentSteps steps={steps} variant="overlay" />
        </div>
      )}

      {/* 面试记录抽屉 */}
      {transcriptOpen && (
        <div className="fixed inset-0 z-40 flex justify-end">
          <button
            aria-label="关闭"
            onClick={() => setTranscriptOpen(false)}
            className="flex-1 bg-slate-900/15 backdrop-blur-[2px]"
          />
          <aside className="flex h-full w-[420px] flex-col border-l border-slate-200 bg-white/95 shadow-2xl backdrop-blur-xl">
            <div className="flex shrink-0 items-center justify-between border-b border-slate-100 px-5 py-4">
              <h2 className="text-[13px] font-semibold text-slate-700">面试记录</h2>
              <button
                onClick={() => setTranscriptOpen(false)}
                className="flex h-7 w-7 items-center justify-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <ul className="scrollbar-thin min-h-0 flex-1 overflow-y-auto px-5 py-4">
              {answered.map(turn => (
                <TranscriptEntry key={turn.seq} turn={turn} />
              ))}
              {answered.length === 0 && (
                <li className="py-10 text-center text-[12px] text-slate-400">还没有答完的问题</li>
              )}
              <li ref={bottomRef} />
            </ul>
          </aside>
        </div>
      )}

      {/* 开场面：一次点击同时解决自动播放策略和摄像头授权 */}
      {!started && !completed && (
        <div className="absolute inset-0 z-30 flex items-center justify-center bg-white/60 backdrop-blur-md">
          <div className="w-full max-w-md rounded-3xl border border-white bg-white/90 p-9 text-center shadow-[0_30px_80px_-40px_rgba(15,23,42,0.5)]">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600 text-sm font-semibold text-white">
              AI
            </div>
            <h1 className="mt-4 text-lg font-semibold text-slate-800">
              {persona.label} · {persona.title}
            </h1>
            <p className="mt-2 text-[13px] leading-relaxed text-slate-500">
              {session.turns.length === 0
                ? `接下来 ${session.questionCount} 个问题，面试官会把题目念出来，你在左侧作答。中途不显示分数，结束后统一复盘。`
                : '这道题还没答完。点开始继续，面试官会先把题目念一遍。'}
            </p>
            <button
              onClick={() => void startInterview()}
              className="mt-6 h-11 w-full rounded-full bg-emerald-500 text-sm font-medium text-white shadow-lg shadow-emerald-500/25 transition hover:bg-emerald-600"
            >
              开始面试
            </button>
            <p className="mt-3 text-[11px] text-slate-400">需要允许浏览器播放声音，否则只能看字幕</p>
          </div>
        </div>
      )}
    </div>
  )
}
