import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { interviewApi } from '@/api/interview'
import type { InterviewSessionView, ReportPayload, TurnEvaluation } from '@/types/api'
import { formatDate, scoreBg, scoreLabel, scoreTone } from '@/utils/format'

function ScoreRing({ score }: { score: number }) {
  const radius = 44
  const circumference = 2 * Math.PI * radius
  const dash = (Math.max(0, Math.min(100, score)) / 100) * circumference
  const stroke = score >= 80 ? '#059669' : score >= 60 ? '#d97706' : '#dc2626'
  return (
    <div className="relative h-28 w-28">
      <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90">
        <circle cx="50" cy="50" r={radius} fill="none" stroke="#e2e8f0" strokeWidth="8" />
        <circle
          cx="50"
          cy="50"
          r={radius}
          fill="none"
          stroke={stroke}
          strokeWidth="8"
          strokeLinecap="round"
          strokeDasharray={`${dash} ${circumference}`}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className={`text-2xl font-semibold ${scoreTone(score)}`}>{score}</span>
        <span className="text-[10px] text-slate-400">{scoreLabel(score)}</span>
      </div>
    </div>
  )
}

export default function ReportPage() {
  const params = useParams()
  const sessionId = Number(params.id)
  const [session, setSession] = useState<InterviewSessionView | null>(null)
  const [payload, setPayload] = useState<ReportPayload | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    Promise.all([interviewApi.detail(sessionId), interviewApi.report(sessionId)])
      .then(([detail, report]) => {
        if (cancelled) {
          return
        }
        setSession(detail)
        setPayload(report as ReportPayload)
      })
      .catch(err => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '报告还没生成')
        }
      })
    return () => {
      cancelled = true
    }
  }, [sessionId])

  if (error) {
    return (
      <div className="mx-auto max-w-lg px-6 py-24 text-center">
        <p className="text-sm text-rose-600">{error}</p>
        <Link to={`/interview/${sessionId}`} className="mt-4 inline-block text-sm underline">
          回到面试
        </Link>
      </div>
    )
  }
  if (!session || !payload) {
    return <p className="p-12 text-sm text-slate-400">加载中…</p>
  }

  const report = payload.report || {}
  const overall = payload.overallScore ?? session.overallScore ?? 0

  return (
    <div className="mx-auto max-w-3xl px-6 py-12">
      <div className="flex items-center gap-3">
        <Link to={`/resumes/${session.resumeId}`} className="text-xs text-slate-400 hover:text-slate-600">
          ← 简历批注
        </Link>
        <Link to={`/interview/${session.id}`} className="text-xs text-slate-400 hover:text-slate-600">
          面试过程
        </Link>
        <Link to="/history" className="ml-auto text-xs text-slate-400 hover:text-slate-600">
          全部记录 →
        </Link>
      </div>

      <section className="mt-6 flex flex-wrap items-center gap-8 rounded-2xl border border-slate-200 bg-white p-6">
        <ScoreRing score={overall} />
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold text-slate-900">面试报告</h1>
          <p className="mt-1 text-[11px] text-slate-400">
            {formatDate(session.createdAt)} · {session.questionCount} 个主问题 · 追问上限 {session.followUpLimit}
          </p>
          {report.summary && (
            <p className="mt-3 text-[13px] leading-relaxed text-slate-700">{report.summary}</p>
          )}
        </div>
      </section>

      {(report.skillScores || []).length > 0 && (
        <section className="mt-6 rounded-2xl border border-slate-200 bg-white p-6">
          <h2 className="text-xs font-medium uppercase tracking-wide text-slate-400">能力分布</h2>
          <ul className="mt-4 space-y-3">
            {(report.skillScores || []).map(item => (
              <li key={item.skill}>
                <div className="flex items-baseline justify-between text-[12.5px]">
                  <span className="text-slate-700">{item.skill}</span>
                  <span className={`font-mono ${scoreTone(item.score)}`}>{item.score}</span>
                </div>
                <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-slate-100">
                  <div className={`h-full ${scoreBg(item.score)}`} style={{ width: `${item.score}%` }} />
                </div>
                {item.comment && <p className="mt-1 text-[11.5px] text-slate-500">{item.comment}</p>}
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="mt-6 grid gap-6 sm:grid-cols-2">
        {(report.strengths || []).length > 0 && (
          <section className="rounded-2xl border border-slate-200 bg-white p-6">
            <h2 className="text-xs font-medium uppercase tracking-wide text-emerald-600">做得好的</h2>
            <ul className="mt-3 space-y-2">
              {(report.strengths || []).map((item, index) => (
                <li key={index} className="text-[12.5px] leading-relaxed text-slate-700">
                  {item}
                </li>
              ))}
            </ul>
          </section>
        )}
        {(report.improvements || []).length > 0 && (
          <section className="rounded-2xl border border-slate-200 bg-white p-6">
            <h2 className="text-xs font-medium uppercase tracking-wide text-rose-600">需要补的</h2>
            <ul className="mt-3 space-y-2">
              {(report.improvements || []).map((item, index) => (
                <li key={index} className="text-[12.5px] leading-relaxed text-slate-700">
                  {item}
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>

      <section className="mt-6 rounded-2xl border border-slate-200 bg-white p-6">
        <h2 className="text-xs font-medium uppercase tracking-wide text-slate-400">逐题记录</h2>
        <ul className="mt-4 space-y-5">
          {session.turns.map(turn => {
            const evaluation = turn.evaluation as TurnEvaluation | null
            return (
              <li key={turn.seq} className="border-b border-slate-100 pb-4 last:border-0 last:pb-0">
                <div className="flex items-center gap-2 text-[11px]">
                  <span className="font-mono text-slate-400">Q{turn.seq}</span>
                  {turn.kind === 'FOLLOW_UP' && (
                    <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[10px] text-amber-700">
                      追问 L{turn.depth}
                    </span>
                  )}
                  <span className="text-slate-400">{turn.skill}</span>
                  {turn.score !== null && (
                    <span className={`ml-auto font-mono font-semibold ${scoreTone(turn.score)}`}>{turn.score}</span>
                  )}
                </div>
                <p className="mt-1.5 text-[13px] leading-relaxed text-slate-900">{turn.question}</p>
                {turn.answer && (
                  <p className="mt-1.5 whitespace-pre-wrap border-l-2 border-slate-200 pl-3 text-[12px] leading-relaxed text-slate-500">
                    {turn.answer}
                  </p>
                )}
                {turn.feedback && <p className="mt-1.5 text-[11.5px] text-slate-500">{turn.feedback}</p>}
                {(evaluation?.evidence || []).filter(item => item.quote).length > 0 && (
                  <ul className="mt-1.5 space-y-0.5">
                    {(evaluation?.evidence || []).map((item, index) => (
                      <li key={index} className="text-[11px] text-slate-400">
                        「{item.quote}」{item.note ? ` — ${item.note}` : ''}
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            )
          })}
        </ul>
      </section>
    </div>
  )
}
