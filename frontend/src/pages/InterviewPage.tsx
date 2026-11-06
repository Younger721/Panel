import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
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
    <div className="mt-2 space-y-2 rounded-lg border border-slate-200 bg-slate-50/60 p-3">
      <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 sm:grid-cols-4">
        {rows.map(row => (
          <div key={row.label}>
            <div className="flex items-baseline justify-between text-[10.5px] text-slate-500">
              <span>
                {row.label} <span className="text-slate-300">{row.weight}</span>
              </span>
              <span className={`font-mono ${scoreTone(row.value)}`}>{row.value}</span>
            </div>
            <div className="mt-1 h-1 overflow-hidden rounded-full bg-slate-200">
              <div className={`h-full ${scoreBg(row.value)}`} style={{ width: `${row.value}%` }} />
            </div>
          </div>
        ))}
      </div>

      {(evaluation?.evidence || []).filter(item => item.quote).length > 0 && (
        <div>
          <p className="text-[10.5px] uppercase tracking-wide text-slate-400">评分依据</p>
          <ul className="mt-1 space-y-1">
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
          <p className="text-[10.5px] uppercase tracking-wide text-slate-400">扣分点</p>
          <ul className="mt-1 space-y-0.5">
            {(evaluation?.deductions || []).map((item, index) => (
              <li key={index} className="text-[11.5px] leading-relaxed text-slate-600">
                · {item}
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
  return (
    <article className="border-b border-slate-200 pb-6 last:border-0">
      <div className="flex flex-wrap items-center gap-2 text-[11px]">
        <span className="font-mono text-slate-400">Q{turn.seq}</span>
        {turn.kind === 'FOLLOW_UP' ? (
          <span className="rounded border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] text-amber-700">
            追问 · 第 {turn.depth} 层
          </span>
        ) : (
          <span className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-[10px] text-slate-500">
            主问题
          </span>
        )}
        <span className="text-slate-400">{turn.skill}</span>
        <span className="text-slate-300">·</span>
        <span className="text-slate-400">{turn.difficulty}</span>
        {turn.score !== null && (
          <span className={`ml-auto font-mono text-sm font-semibold ${scoreTone(turn.score)}`}>{turn.score}</span>
        )}
      </div>

      <p className="mt-2 text-[14px] leading-relaxed text-slate-900">{turn.question}</p>

      {answered ? (
        <>
          <div className="mt-3 whitespace-pre-wrap rounded-lg border border-slate-200 bg-white px-3 py-2 text-[13px] leading-relaxed text-slate-700">
            {turn.answer}
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            {turn.feedback && <span className="text-[11.5px] text-slate-500">{turn.feedback}</span>}
          </div>
          <EvaluationPanel evaluation={turn.evaluation} />
        </>
      ) : (
        <p className="mt-2 text-[11.5px] text-slate-400">等待作答…</p>
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
    return <p className="p-12 text-sm text-rose-600">{error}</p>
  }
  if (!session) {
    return <p className="p-12 text-sm text-slate-400">加载中…</p>
  }

  const answeredMains = session.turns.filter(turn => turn.kind === 'MAIN' && turn.answer !== null).length
  const pending = session.turns.find(turn => turn.answer === null)
  const completed = session.status === 'COMPLETED'
  const progress = Math.min(100, Math.round((answeredMains / Math.max(1, session.questionCount)) * 100))

  return (
    <div className="mx-auto flex min-h-full max-w-3xl flex-col px-6">
      <header className="sticky top-0 z-10 -mx-6 border-b border-slate-200 bg-slate-100/90 px-6 py-3 backdrop-blur">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <Link to={`/resumes/${session.resumeId}`} className="text-xs text-slate-400 hover:text-slate-600">
            ← 简历
          </Link>
          <span className="text-xs text-slate-500">
            主问题 {answeredMains}/{session.questionCount} · 追问上限 {session.followUpLimit}
          </span>
          <span className="ml-auto text-xs text-slate-400">{formatDate(session.createdAt)}</span>
          {completed && (
            <button
              onClick={() => navigate(`/interview/${session.id}/report`)}
              className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-slate-800"
            >
              查看报告
            </button>
          )}
        </div>
        <div className="mt-2 h-1 overflow-hidden rounded-full bg-slate-200">
          <div className="h-full bg-slate-900 transition-all" style={{ width: `${progress}%` }} />
        </div>
      </header>

      <main className="flex-1 space-y-6 py-8">
        {session.turns.map(turn => (
          <TurnBlock key={turn.seq} turn={turn} />
        ))}
        <div ref={bottomRef} />
      </main>

      <footer className="sticky bottom-0 -mx-6 border-t border-slate-200 bg-slate-100/95 px-6 py-4 backdrop-blur">
        {completed ? (
          <div className="flex items-center justify-between">
            <p className="text-sm text-slate-600">面试已结束，总分 {session.overallScore}</p>
            <button
              onClick={() => navigate(`/interview/${session.id}/report`)}
              className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800"
            >
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
                placeholder={busy ? (stage || '评分中…') : '写下你的回答，⌘/Ctrl + Enter 提交'}
                className="flex-1 resize-none rounded-xl border border-slate-300 bg-white px-3 py-2 text-[13px] leading-relaxed outline-none focus:border-slate-500 disabled:bg-slate-50"
              />
              <button
                onClick={() => void submit()}
                disabled={busy || !draft.trim()}
                className="h-10 shrink-0 rounded-lg bg-slate-900 px-4 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-40"
              >
                {busy ? stage || '提交中' : '提交'}
              </button>
            </div>
            {error && <p className="mt-2 text-xs text-rose-600">{error}</p>}
          </>
        )}
      </footer>
    </div>
  )
}
