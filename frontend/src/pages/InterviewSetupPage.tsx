import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
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
    return <p className="p-12 text-sm text-slate-500">缺少简历 id，请从批注页进入。</p>
  }

  return (
    <div className="mx-auto max-w-2xl px-6 py-12">
      <Link to={`/resumes/${resumeId}`} className="text-xs text-slate-400 hover:text-slate-600">
        ← 返回批注
      </Link>
      <h1 className="mt-6 text-xl font-semibold text-slate-900">开始模拟面试</h1>
      <p className="mt-1 text-sm text-slate-500">
        {resume ? resume.fileName : `简历 #${resumeId}`}
        {stats ? ` · ${stats.modules} 个模块 / ${stats.points} 个小点（弱 ${stats.weak}）` : ''}
      </p>

      <section className="mt-8 space-y-8 rounded-2xl border border-slate-200 bg-white p-6">
        <div>
          <div className="flex items-baseline justify-between">
            <label htmlFor="count" className="text-sm font-medium text-slate-700">
              主问题数量
            </label>
            <span className="font-mono text-sm text-slate-900">{questionCount}</span>
          </div>
          <input
            id="count"
            type="range"
            min={1}
            max={Math.max(1, Math.min(10, stats ? stats.points : 10))}
            value={questionCount}
            onChange={event => setQuestionCount(Number(event.target.value))}
            disabled={busy}
            className="mt-3 w-full accent-slate-900"
          />
          <p className="mt-2 text-xs text-slate-400">每个问题对应简历里的一个批注点，优先问写得弱的。</p>
        </div>

        <div>
          <p className="text-sm font-medium text-slate-700">每个问题最多追问</p>
          <div className="mt-3 flex gap-2">
            {FOLLOW_UP_OPTIONS.map(option => (
              <button
                key={option}
                type="button"
                onClick={() => setFollowUpLimit(option)}
                disabled={busy}
                className={`rounded-lg border px-4 py-1.5 text-sm transition ${
                  followUpLimit === option
                    ? 'border-slate-900 bg-slate-900 text-white'
                    : 'border-slate-300 text-slate-600 hover:border-slate-400'
                }`}
              >
                {option === 0 ? '不追问' : `${option} 次`}
              </button>
            ))}
          </div>
          <p className="mt-2 text-xs text-slate-400">
            回答低于 70 分才会追问，答得好直接进入下一题；最多 {questionCount * (1 + followUpLimit)} 轮。
          </p>
        </div>

        {error && <p className="text-xs text-rose-600">{error}</p>}

        <button
          onClick={() => void start()}
          disabled={busy}
          className="w-full rounded-lg bg-slate-900 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-50"
        >
          {busy ? '正在准备…' : '开始面试'}
        </button>

        {steps.length > 0 && (
          <ul className="space-y-1.5 rounded-lg bg-slate-50 p-4">
            {steps.map((step, index) => (
              <li key={index} className="flex items-center gap-2 text-xs text-slate-600">
                <span
                  className={`h-1.5 w-1.5 rounded-full ${
                    step.status === 'COMPLETED' ? 'bg-emerald-500' : 'bg-amber-500'
                  }`}
                />
                <span className="font-medium">{step.agent}</span>
                <span className="text-slate-400">{step.stage}</span>
                {step.message && <span className="text-slate-400">· {step.message}</span>}
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
