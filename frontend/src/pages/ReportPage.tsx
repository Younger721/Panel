import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ChevronLeft, TrendingUp } from 'lucide-react'
import { interviewApi } from '@/api/interview'
import TurnEvaluationPanel from '@/components/TurnEvaluationPanel'
import type { InterviewSessionView, ReportPayload, TurnEvaluation, TurnReview } from '@/types/api'
import { formatDate, scoreBg, scoreLabel, scoreTone } from '@/utils/format'

/**
 * 总评分环。
 *
 * <p>{@code score} 允许为 null：报告生成失败或被放弃的会话没有总评分。
 * 原来这里兜底成 0，于是"根本没评分"被画成一个扎眼的 0 分——用户会以为自己考砸了。</p>
 */
function ScoreRing({ score }: { score: number | null }) {
  const radius = 44
  const circumference = 2 * Math.PI * radius
  const value = score ?? 0
  const dash = (Math.max(0, Math.min(100, value)) / 100) * circumference
  const stroke = score === null ? '#cbd5e1' : value >= 80 ? '#10b981' : value >= 60 ? '#f59e0b' : '#f43f5e'
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
        <span className={`text-3xl font-bold ${score === null ? 'text-slate-300' : scoreTone(value)}`}>
          {score === null ? '—' : value}
        </span>
        <span className="text-[10px] text-slate-400">{score === null ? '未评分' : scoreLabel(value)}</span>
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
  const [reportMissing, setReportMissing] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    // 两个请求分开结算：报告还没生成（或生成失败）时，分数环和逐题记录仍然有价值，
    // 用 Promise.all 会把整页变成一句错误，用户连自己答了什么、得了多少分都看不到
    Promise.allSettled([interviewApi.detail(sessionId), interviewApi.report(sessionId)]).then(
      ([detailResult, reportResult]) => {
        if (cancelled) {
          return
        }
        if (detailResult.status === 'rejected') {
          setError(detailResult.reason instanceof Error ? detailResult.reason.message : '面试记录加载失败')
          return
        }
        setSession(detailResult.value)
        if (reportResult.status === 'fulfilled') {
          setPayload(reportResult.value as ReportPayload)
          setReportMissing(null)
        } else {
          const reason = reportResult.reason
          setReportMissing(reason instanceof Error ? reason.message : '报告还没生成')
        }
      },
    )
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
  if (!session) {
    return <p className="py-24 text-center text-sm text-slate-400">加载中…</p>
  }

  const report = payload?.report || {}
  const fit = payload?.fit
  // 总评分可能是 null：报告没生成（或会话被放弃）时不该画成 0 分
  const overall = payload?.overallScore ?? session.overallScore ?? null
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

      {reportMissing && (
        <section className="card mt-5 border-amber-100 bg-amber-50/60 p-5">
          <p className="text-[12.5px] leading-relaxed text-amber-800">
            总体评价还没生成：{reportMissing}。下面仍然显示本次的逐题评分——报告生成是最后一跳，
            它失败不影响已经评过的题目。
          </p>
        </section>
      )}

      {fit && (
        <section className="card mt-5 p-7">
          <div className="flex items-center justify-between">
            <h2 className="section-label">岗位匹配</h2>
            {typeof fit.score === 'number' && (
              <span className="text-[12px] text-slate-400">
                覆盖度 <span className={`font-semibold ${scoreTone(fit.score)}`}>{fit.score}</span> / 100
              </span>
            )}
          </div>
          {fit.headline && (
            <p className="mt-2 text-[13.5px] leading-relaxed text-slate-700">{fit.headline}</p>
          )}
          <div className="mt-4 grid gap-5 sm:grid-cols-2">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-emerald-600">对得上</p>
              <ul className="mt-2.5 space-y-2">
                {(fit.matched || []).map((item, index) => (
                  <li key={index} className="text-[12.5px] leading-relaxed text-slate-700">
                    <span className="font-medium">{item.skill}</span>
                    {item.evidence && <span className="text-slate-400"> · {item.evidence}</span>}
                  </li>
                ))}
                {(fit.matched || []).length === 0 && (
                  <li className="text-[12px] text-slate-400">JD 要求的技能在简历里没有找到对应表述</li>
                )}
              </ul>
            </div>
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-rose-500">缺口</p>
              <ul className="mt-2.5 space-y-2">
                {(fit.gaps || []).map((item, index) => (
                  <li key={index} className="text-[12.5px] leading-relaxed text-slate-700">
                    <span className="font-medium">{item.skill}</span>
                    {item.why && <span className="text-slate-400"> · {item.why}</span>}
                  </li>
                ))}
                {(fit.gaps || []).length === 0 && (
                  <li className="text-[12px] text-slate-400">JD 的硬性要求都有对应经历</li>
                )}
              </ul>
            </div>
          </div>
          {(fit.prioritySkills || []).length > 0 && (
            <p className="mt-4 text-[11px] text-slate-400">
              本场优先验证：{(fit.prioritySkills || []).join('、')}
            </p>
          )}
        </section>
      )}

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
                {/* 出题意图：让"为什么问这道题"有答案，而不是只看到一句题目 */}
                {turn.intent && (
                  <p className="mt-1.5 text-[11px] leading-relaxed text-slate-400">出题意图 · {turn.intent}</p>
                )}
                {turn.answer && (
                  <p className="mt-2 whitespace-pre-wrap border-l-2 border-slate-200 pl-3 text-[12px] leading-relaxed text-slate-500">
                    {turn.answer}
                  </p>
                )}
                {turn.feedback && <p className="mt-2 text-[11.5px] leading-relaxed text-slate-500">{turn.feedback}</p>}
                {/* 面试进行中不显示分数，四维拆解和证据统一放在这里 */}
                <TurnEvaluationPanel evaluation={evaluation} />
                {turn.review && <ReviewNote review={turn.review} />}
              </li>
            )
          })}
        </ul>
      </section>
    </div>
  )
}

/**
 * Reviewer 的仲裁结果：只在整个流程判定"评分与证据矛盾"时才出现。
 * 平时不该打扰候选人，所以不作单独块，贴在逐题面板下面一行。
 */
function ReviewNote({ review }: { review: TurnReview }) {
  const adjustments = review.adjustments || []
  const adjusted = Boolean(review.adjusted) && adjustments.length > 0
  return (
    <div
      className={`mt-2 rounded-lg px-3 py-2 text-[11.5px] leading-relaxed ${
        adjusted ? 'bg-amber-50/80 text-amber-800' : 'bg-slate-50 text-slate-500'
      }`}
    >
      <span className="font-medium">{adjusted ? '复核已调整' : '复核维持原分'}</span>
      {adjusted ? (
        <ul className="mt-1 space-y-0.5">
          {adjustments.map(item => (
            <li key={item.axis}>
              {item.axis}：{item.from} → {item.to}
              {item.why ? `（${item.why}）` : ''}
            </li>
          ))}
        </ul>
      ) : review.reason ? (
        <span> · {review.reason}</span>
      ) : null}
    </div>
  )
}
