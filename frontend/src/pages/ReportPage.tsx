import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ChevronLeft, TrendingUp } from 'lucide-react'
import { interviewApi } from '@/api/interview'
import type { InterviewSessionView, ReportPayload, TurnEvaluation } from '@/types/api'
import { formatDate, scoreBg, scoreLabel, scoreTone } from '@/utils/format'

function ScoreRing({ score }: { score: number }) {
  const radius = 44
  const circumference = 2 * Math.PI * radius
  const dash = (Math.max(0, Math.min(100, score)) / 100) * circumference
  const stroke = score >= 80 ? '#10b981' : score >= 60 ? '#f59e0b' : '#f43f5e'
  return (
    <div className="relative h-28 w-28 shrink-0">
      <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90">
        <circle cx="50" cy="50" r={radius} fill="none" stroke="#eef2ff" strokeWidth="9" />
        <circle
          cx="50"
          cy="50"
          r={radius}
          fill="none"
          stroke={stroke}
          strokeWidth="9"
          strokeLinecap="round"
          strokeDasharray={`${dash} ${circumference}`}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className={`text-3xl font-bold ${scoreTone(score)}`}>{score}</span>
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
      <div className="card mx-auto mt-16 max-w-lg p-10 text-center">
        <p className="text-sm text-rose-600">{error}</p>
        <Link to={`/interview/${sessionId}`} className="btn-ghost mt-5">
          回到面试
        </Link>
      </div>
    )
  }
  if (!session || !payload) {
    return <p className="py-24 text-center text-sm text-slate-400">加载中…</p>
  }

  const report = payload.report || {}
  const overall = payload.overallScore ?? session.overallScore ?? 0
  const solvedTurns = session.turns.filter(turn => turn.answer !== null)

  return (
    <div className="mx-auto max-w-3xl">
      <div className="flex items-center gap-4">
        <Link
          to={`/resumes/${session.resumeId}`}
          className="flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          简历批注
        </Link>
        <Link to={`/interview/${session.id}`} className="text-xs text-slate-400 hover:text-slate-600">
          面试过程
        </Link>
        <Link to="/history" className="ml-auto text-xs text-slate-400 hover:text-slate-600">
          全部记录 →
        </Link>
      </div>

      <section className="card mt-5 flex flex-wrap items-center gap-8 p-7">
        <ScoreRing score={overall} />
        <div className="min-w-0 flex-1">
          <h1 className="text-xl font-bold tracking-tight text-slate-900">面试报告</h1>
          <p className="mt-1 text-xs text-slate-400">
            {formatDate(session.createdAt)} · {session.questionCount} 个主问题 · 追问上限 {session.followUpLimit} ·{' '}
            {solvedTurns.length} 轮作答
          </p>
          {report.summary && <p className="mt-3 text-[13.5px] leading-relaxed text-slate-700">{report.summary}</p>}
        </div>
      </section>

      {(report.skillScores || []).length > 0 && (
        <section className="card mt-5 p-7">
          <h2 className="section-label">能力分布</h2>
          <ul className="mt-4 space-y-4">
            {(report.skillScores || []).map(item => (
              <li key={item.skill}>
                <div className="flex items-baseline justify-between text-[13px]">
                  <span className="font-medium text-slate-700">{item.skill}</span>
                  <span className={`font-mono font-semibold ${scoreTone(item.score)}`}>{item.score}</span>
                </div>
                <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-slate-100">
                  <div
                    className={`h-full rounded-full ${scoreBg(item.score)}`}
                    style={{ width: `${Math.max(0, Math.min(100, item.score))}%` }}
                  />
                </div>
                {item.comment && <p className="mt-1.5 text-[11.5px] leading-relaxed text-slate-500">{item.comment}</p>}
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="mt-5 grid gap-5 sm:grid-cols-2">
        {(report.strengths || []).length > 0 && (
          <section className="card p-6">
            <h2 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-emerald-600">
              <TrendingUp className="h-3.5 w-3.5" />
              做得好的
            </h2>
            <ul className="mt-3 space-y-2.5">
              {(report.strengths || []).map((item, index) => (
                <li key={index} className="flex gap-2 text-[12.5px] leading-relaxed text-slate-700">
                  <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-emerald-400" />
                  <span>{item}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
        {(report.improvements || []).length > 0 && (
          <section className="card p-6">
            <h2 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-rose-600">
              <TrendingUp className="h-3.5 w-3.5 rotate-180" />
              需要补的
            </h2>
            <ul className="mt-3 space-y-2.5">
              {(report.improvements || []).map((item, index) => (
                <li key={index} className="flex gap-2 text-[12.5px] leading-relaxed text-slate-700">
                  <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-rose-400" />
                  <span>{item}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>

      <section className="card mt-5 p-7">
        <h2 className="section-label">逐题记录</h2>
        <ul className="mt-4 divide-y divide-slate-100">
          {session.turns.map(turn => {
            const evaluation = turn.evaluation as TurnEvaluation | null
            return (
              <li key={turn.seq} className="py-4 first:pt-0 last:pb-0">
                <div className="flex items-center gap-2">
                  <span
                    className={`flex h-6 min-w-6 items-center justify-center rounded-lg px-1.5 font-mono text-[11px] font-semibold ${
                      turn.kind === 'FOLLOW_UP' ? 'bg-amber-50 text-amber-600' : 'bg-primary-50 text-primary-600'
                    }`}
                  >
                    Q{turn.seq}
                  </span>
                  {turn.kind === 'FOLLOW_UP' && (
                    <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">
                      追问 L{turn.depth}
                    </span>
                  )}
                  <span className="text-[11px] text-slate-400">{turn.skill}</span>
                  {turn.score !== null && (
                    <span className={`ml-auto font-mono text-sm font-bold ${scoreTone(turn.score)}`}>{turn.score}</span>
                  )}
                </div>
                <p className="mt-2 text-[13.5px] font-medium leading-relaxed text-slate-900">{turn.question}</p>
                {turn.answer && (
                  <p className="mt-2 whitespace-pre-wrap border-l-2 border-slate-200 pl-3 text-[12px] leading-relaxed text-slate-500">
                    {turn.answer}
                  </p>
                )}
                {turn.feedback && <p className="mt-2 text-[11.5px] leading-relaxed text-slate-500">{turn.feedback}</p>}
                {(evaluation?.evidence || []).filter(item => item.quote).length > 0 && (
                  <ul className="mt-2 space-y-1">
                    {(evaluation?.evidence || []).map((item, index) => (
                      <li key={index} className="text-[11px] leading-relaxed text-slate-400">
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
