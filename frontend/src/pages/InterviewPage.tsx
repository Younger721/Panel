import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ChevronLeft, ChevronRight, Loader2, Send } from 'lucide-react'
import { interviewApi } from '@/api/interview'
import type { InterviewSessionView, InterviewTurnView, TurnEvaluation } from '@/types/api'
import { formatDate, scoreBg, scoreTone } from '@/utils/format'

function EvaluationPanel({ evaluation }: { evaluation: TurnEvaluation | null }) {
  const scores = evaluation?.scores
  if (!scores) {
    return null
  }
  const rows = [
    { label: '准确性', weight: '40%', value: scores.accuracy },
    { label: '深度', weight: '25%', value: scores.depth },
    { label: '完整性', weight: '20%', value: scores.completeness },
    { label: '表达', weight: '15%', value: scores.clarity }
  ]
  return (
    <div className="mt-3 space-y-3 rounded-xl bg-slate-50/80 p-4">
      <div className="grid grid-cols-2 gap-x-5 gap-y-2 sm:grid-cols-4">
        {rows.map(row => (
          <div key={row.label}>
            <div className="flex items-baseline justify-between text-[11px] text-slate-500">
              <span>
                {row.label} <span className="text-slate-300">{row.weight}</span>
              </span>
              <span className={`font-mono font-semibold ${scoreTone(row.value)}`}>{row.value}</span>
            </div>
            <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-200">
              <div className={`h-full rounded-full ${scoreBg(row.value)}`} style={{ width: `${row.value}%` }} />
            </div>
          </div>
        ))}
      </div>

      {(evaluation?.evidence || []).filter(item => item.quote).length > 0 && (
        <div>
          <p className="section-label">评分依据</p>
          <ul className="mt-1.5 space-y-1">
            {(evaluation?.evidence || []).map((item, index) => (
              <li key={index} className="text-[11.5px] leading-relaxed text-slate-600">
                <span className="text-slate-400">「{item.quote}」</span>
                {item.note ? <span className="text-slate-500"> — {item.note}</span> : null}
              </li>
            ))}
          </ul>
        </div>
      )}

      {(evaluation?.deductions || []).length > 0 && (
        <div>
          <p className="section-label">扣分点</p>
          <ul className="mt-1.5 space-y-0.5">
            {(evaluation?.deductions || []).map((item, index) => (
              <li key={index} className="flex gap-1.5 text-[11.5px] leading-relaxed text-slate-600">
                <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-slate-300" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function TurnBlock({ turn }: { turn: InterviewTurnView }) {
  const answered = turn.answer !== null && turn.answer !== undefined
  const isFollowUp = turn.kind === 'FOLLOW_UP'
  return (
    <article className="card p-5">
      <div className="flex flex-wrap items-center gap-2">
        <span
          className={`flex h-6 min-w-6 items-center justify-center rounded-lg px-1.5 font-mono text-[11px] font-semibold ${
            isFollowUp ? 'bg-amber-50 text-amber-600' : 'bg-primary-50 text-primary-600'
          }`}
        >
          Q{turn.seq}
        </span>
        <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-[10.5px] font-medium text-slate-500">
          {isFollowUp ? `追问 · 第 ${turn.depth} 层` : '主问题'}
        </span>
        <span className="text-[11px] text-slate-400">{turn.skill}</span>
        <span className="text-[11px] text-slate-300">·</span>
        <span className="text-[11px] text-slate-400">{turn.difficulty}</span>
        {turn.score !== null && (
          <span
            className={`ml-auto rounded-lg px-2 py-0.5 font-mono text-sm font-bold ${
              turn.score >= 80
                ? 'bg-emerald-50 text-emerald-600'
                : turn.score >= 60
                  ? 'bg-amber-50 text-amber-600'
                  : 'bg-rose-50 text-rose-600'
            }`}
          >
            {turn.score}
          </span>
        )}
      </div>

      <p className="mt-3 text-[15px] font-medium leading-relaxed text-slate-900">{turn.question}</p>

      {answered ? (
        <>
          <div className="mt-3 whitespace-pre-wrap rounded-xl bg-slate-50 px-4 py-3 text-[13px] leading-relaxed text-slate-700">
            {turn.answer}
          </div>
          {turn.feedback && (
            <p className="mt-2.5 text-[12px] leading-relaxed text-slate-500">
              <span className="mr-1.5 font-medium text-primary-500">评价</span>
              {turn.feedback}
            </p>
          )}
          <EvaluationPanel evaluation={turn.evaluation} />
        </>
      ) : (
        <p className="mt-2 flex items-center gap-1.5 text-[11.5px] text-slate-400">
          <Loader2 className="h-3 w-3 animate-spin" />
          等待作答…
        </p>
      )}
    </article>
  )
}

export default function InterviewPage() {
  const params = useParams()
  const navigate = useNavigate()
  const sessionId = Number(params.id)

  const [session, setSession] = useState<InterviewSessionView | null>(null)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [stage, setStage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const bottomRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
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
    }
  }, [sessionId])

  const turnCount = session?.turns.length || 0
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [turnCount])

  async function submit() {
    const text = draft.trim()
    if (!text || busy) {
      return
    }
    setBusy(true)
    setError(null)
    setStage('Evaluator · 评分')
    let failure: string | null = null
    try {
      await interviewApi.answerStream(sessionId, text, (type, data) => {
        if (type === 'agent') {
          const progress = data as { agent: string; stage: string }
          setStage(`${progress.agent} · ${progress.stage}`)
        } else if (type === 'evaluation') {
          setStage('已评分')
        } else if (type === 'error') {
          failure = (data as { message?: string }).message || '提交失败'
        }
      })
      if (failure) {
        setError(failure)
        return
      }
      const view = await interviewApi.detail(sessionId)
      setSession(view)
      setDraft('')
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交失败')
    } finally {
      setBusy(false)
      setStage(null)
    }
  }

  if (error && !session) {
    return <p className="py-24 text-center text-sm text-rose-600">{error}</p>
  }
  if (!session) {
    return <p className="py-24 text-center text-sm text-slate-400">加载中…</p>
  }

  const answeredMains = session.turns.filter(turn => turn.kind === 'MAIN' && turn.answer !== null).length
  const pending = session.turns.find(turn => turn.answer === null)
  const completed = session.status === 'COMPLETED'
  const progress = Math.min(100, Math.round((answeredMains / Math.max(1, session.questionCount)) * 100))

  return (
    <div className="mx-auto flex h-[calc(100vh-5rem)] max-w-3xl flex-col">
      <div className="card mb-4 shrink-0 p-5">
        <div className="flex flex-wrap items-center gap-3">
          <Link
            to={`/resumes/${session.resumeId}`}
            className="flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            简历批注
          </Link>
          <span className="text-xs text-slate-500">
            主问题 {answeredMains}/{session.questionCount} · 追问上限 {session.followUpLimit}
          </span>
          <span className="ml-auto text-xs text-slate-400">{formatDate(session.createdAt)}</span>
          {completed && (
            <button onClick={() => navigate(`/interview/${session.id}/report`)} className="btn-primary">
              查看报告
              <ChevronRight className="h-4 w-4" />
            </button>
          )}
        </div>
        <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-100">
          <div
            className="h-full rounded-full bg-gradient-to-r from-primary-400 to-primary-600 transition-all"
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 space-y-4 overflow-y-auto pr-1">
        {session.turns.map(turn => (
          <TurnBlock key={turn.seq} turn={turn} />
        ))}
        <div ref={bottomRef} />
      </div>

      <div className="card mt-4 shrink-0 p-4">
        {completed ? (
          <div className="flex items-center justify-between">
            <p className="text-sm text-slate-600">面试已结束，总分 {session.overallScore}</p>
            <button onClick={() => navigate(`/interview/${session.id}/report`)} className="btn-primary">
              查看报告
            </button>
          </div>
        ) : (
          <>
            {pending && (
              <p className="mb-2 text-[11px] text-slate-400">
                {pending.kind === 'FOLLOW_UP'
                  ? `这一轮是第 ${pending.depth} 层追问，答完会回到下一个考察点`
                  : '回答尽量给出具体做法和数字，太空会触发追问'}
              </p>
            )}
            <div className="flex items-end gap-2">
              <textarea
                value={draft}
                onChange={event => setDraft(event.target.value)}
                onKeyDown={event => {
                  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                    void submit()
                  }
                }}
                rows={3}
                disabled={busy}
                placeholder={busy ? stage || '评分中…' : '写下你的回答，⌘/Ctrl + Enter 提交'}
                className="flex-1 resize-none rounded-xl border border-slate-200 px-3.5 py-2.5 text-[13px] leading-relaxed outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 disabled:bg-slate-50"
              />
              <button
                onClick={() => void submit()}
                disabled={busy || !draft.trim()}
                className="btn-primary h-11 shrink-0 px-5"
              >
                {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                {busy ? stage || '提交中' : '提交'}
              </button>
            </div>
            {error && <p className="mt-2 text-xs text-rose-600">{error}</p>}
          </>
        )}
      </div>
    </div>
  )
}
