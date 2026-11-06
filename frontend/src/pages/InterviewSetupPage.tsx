import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ChevronLeft, Loader2, Play, Sparkles } from 'lucide-react'
import { resumeApi } from '@/api/resume'
import { interviewApi, type AgentProgress } from '@/api/interview'
import type { AnalysisStats, ResumeView } from '@/types/api'

const FOLLOW_UP_OPTIONS = [0, 1, 2, 3]

export default function InterviewSetupPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const resumeId = Number(params.get('resumeId') || 0)

  const [resume, setResume] = useState<ResumeView | null>(null)
  const [stats, setStats] = useState<AnalysisStats | null>(null)
  const [questionCount, setQuestionCount] = useState(5)
  const [followUpLimit, setFollowUpLimit] = useState(2)
  const [steps, setSteps] = useState<AgentProgress[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!resumeId) {
      return
    }
    resumeApi.find(resumeId).then(setResume).catch(() => undefined)
    resumeApi
      .analysis(resumeId)
      .then(view => {
        setStats(view.stats)
        setQuestionCount(Math.max(1, Math.min(5, view.stats.points)))
      })
      .catch(err => setError(err instanceof Error ? err.message : '简历还没有批注结果'))
  }, [resumeId])

  const maxQuestions = Math.max(1, Math.min(10, stats ? stats.points : 10))

  async function start() {
    setBusy(true)
    setError(null)
    setSteps([])
    try {
      await interviewApi.createStream({ resumeId, questionCount, followUpLimit }, (type, data) => {
        if (type === 'agent') {
          setSteps(prev => [...prev.filter(step => step.status === 'COMPLETED'), data as AgentProgress])
        } else if (type === 'done') {
          const session = data as { id: number }
          navigate(`/interview/${session.id}`, { replace: true })
        } else if (type === 'error') {
          setError((data as { message?: string }).message || '创建面试失败')
          setBusy(false)
        }
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建面试失败')
      setBusy(false)
    }
  }

  if (!resumeId) {
    return (
      <div className="card mx-auto mt-16 max-w-lg p-10 text-center">
        <p className="text-sm text-slate-500">缺少简历 id，请从批注页进入。</p>
        <Link to="/resumes" className="btn-ghost mt-5">
          去简历库
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-2xl">
      <Link
        to={`/resumes/${resumeId}`}
        className="flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600"
      >
        <ChevronLeft className="h-3.5 w-3.5" />
        返回批注
      </Link>

      <div className="mb-6 mt-4">
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">开始模拟面试</h1>
        <p className="mt-1 text-sm text-slate-500">
          {resume ? resume.fileName : `简历 #${resumeId}`}
          {stats ? ` · ${stats.modules} 个模块 / ${stats.points} 个小点（弱 ${stats.weak}）` : ''}
        </p>
      </div>

      <section className="card space-y-8 p-7">
        <div>
          <div className="flex items-baseline justify-between">
            <label htmlFor="count" className="text-sm font-semibold text-slate-800">
              主问题数量
            </label>
            <span className="rounded-lg bg-primary-50 px-2.5 py-1 text-sm font-bold text-primary-600">
              {questionCount}
            </span>
          </div>
          <input
            id="count"
            type="range"
            min={1}
            max={maxQuestions}
            value={questionCount}
            onChange={event => setQuestionCount(Number(event.target.value))}
            disabled={busy}
            className="mt-4 w-full accent-primary-500"
          />
          <p className="mt-2 text-xs text-slate-400">每个问题对应简历里的一个批注点，优先问写得弱的。</p>
        </div>

        <div>
          <p className="text-sm font-semibold text-slate-800">每个问题最多追问</p>
          <div className="mt-3 grid grid-cols-4 gap-2">
            {FOLLOW_UP_OPTIONS.map(option => (
              <button
                key={option}
                type="button"
                onClick={() => setFollowUpLimit(option)}
                disabled={busy}
                className={`rounded-xl px-3 py-2.5 text-sm font-medium transition-all ${
                  followUpLimit === option
                    ? 'bg-gradient-to-r from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/25'
                    : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                }`}
              >
                {option === 0 ? '不追问' : `${option} 次`}
              </button>
            ))}
          </div>
          <p className="mt-2 text-xs text-slate-400">
            回答低于 70 分才会追问，答得好直接进入下一题；最多 {questionCount * (1 + followUpLimit)} 轮对话。
          </p>
        </div>

        <div className="rounded-xl bg-slate-50 p-4">
          <div className="flex items-center gap-2 text-xs text-slate-500">
            <Sparkles className="h-3.5 w-3.5 text-primary-500" />
            <span>
              Planner 从 {stats ? stats.points : '—'} 个批注点里挑 {questionCount} 个，Interviewer 逐题提问，
              Evaluator 带证据评分，Reviewer 只在一题多解时复核。
            </span>
          </div>
        </div>

        {error && <p className="text-xs text-rose-600">{error}</p>}

        <button onClick={() => void start()} disabled={busy} className="btn-primary w-full py-3 text-base">
          {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
          {busy ? '正在准备…' : '开始面试'}
        </button>

        {steps.length > 0 && (
          <ul className="space-y-2 rounded-xl border border-slate-100 bg-slate-50/60 p-4">
            {steps.map((step, index) => (
              <li key={index} className="flex items-center gap-2 text-xs text-slate-600">
                <span
                  className={`h-1.5 w-1.5 rounded-full ${
                    step.status === 'COMPLETED' ? 'bg-emerald-500' : 'bg-amber-500'
                  }`}
                />
                <span className="font-medium">{step.agent}</span>
                <span className="text-slate-400">{step.stage}</span>
                {step.message && <span className="truncate text-slate-400">· {step.message}</span>}
                {step.status === 'COMPLETED' && step.durationMs > 0 && (
                  <span className="ml-auto font-mono text-[10px] text-slate-400">{step.durationMs}ms</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
