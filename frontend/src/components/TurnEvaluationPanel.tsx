import type { TurnEvaluation, TurnJudge, TurnScores } from '@/types/api'
import { scoreBg, scoreTone } from '@/utils/format'

/** 与后端 TurnEvaluation.Scores.weightedTotal() 同一套权重，改一处要改两处。 */
const WEIGHTS: Array<[keyof TurnScores, number]> = [
  ['accuracy', 0.4],
  ['depth', 0.25],
  ['completeness', 0.2],
  ['clarity', 0.15]
]

const AXIS_LABEL: Record<keyof TurnScores, string> = {
  accuracy: '准确性',
  depth: '深度',
  completeness: '完整性',
  clarity: '表达'
}

function weightedTotal(scores?: TurnScores | null) {
  if (!scores) {
    return 0
  }
  return Math.round(WEIGHTS.reduce((sum, [axis, weight]) => sum + scores[axis] * weight, 0))
}

/** 两位评委分差最大的维度；只有一位评委或完全一致时返回 null。 */
function biggestGap(judges: TurnJudge[]) {
  const [first, second] = judges
  const a = first?.scores
  const b = second?.scores
  if (!a || !b) {
    return null
  }
  let axis: keyof TurnScores | null = null
  let gap = 0
  for (const [key] of WEIGHTS) {
    const diff = Math.abs(a[key] - b[key])
    if (diff > gap) {
      gap = diff
      axis = key
    }
  }
  return axis && gap > 0 ? { axis, gap, left: a[axis], right: b[axis] } : null
}

/**
 * 单题评分的四维拆解（准确性 40 / 深度 25 / 完整性 20 / 表达 15），
 * 以及技术评委 / 表达评委的分歧。
 *
 * 只在报告页出现：面试进行中刻意不显示分数，避免边答边看分。
 */
export default function TurnEvaluationPanel({ evaluation }: { evaluation: TurnEvaluation | null }) {
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
  const judges = evaluation?.judges || []
  const gap = biggestGap(judges)
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

      {judges.length >= 2 && (
        <div>
          <p className="section-label">评委团</p>
          <div className="mt-1.5 grid gap-2 sm:grid-cols-2">
            {judges.map(judge => {
              const total = weightedTotal(judge.scores)
              return (
                <div key={judge.name} className="rounded-lg bg-white px-3 py-2 ring-1 ring-slate-200/80">
                  <div className="flex items-baseline justify-between">
                    <span className="text-[11px] font-medium text-slate-600">{judge.name}</span>
                    <span className={`font-mono text-[13px] font-bold ${scoreTone(total)}`}>{total}</span>
                  </div>
                  {judge.scores && (
                    <p className="mt-0.5 font-mono text-[10px] text-slate-400">
                      准 {judge.scores.accuracy} · 深 {judge.scores.depth} · 全 {judge.scores.completeness} · 表{' '}
                      {judge.scores.clarity}
                    </p>
                  )}
                  {judge.feedback && (
                    <p className="mt-1 text-[11px] leading-relaxed text-slate-500">{judge.feedback}</p>
                  )}
                </div>
              )
            })}
          </div>
          {gap && (
            <p className="mt-1.5 text-[11px] leading-relaxed text-slate-500">
              两位评委分歧最大的是 <span className="font-medium text-slate-700">{AXIS_LABEL[gap.axis]}</span>：
              {gap.left} 分 vs {gap.right} 分（差 {gap.gap}）
            </p>
          )}
        </div>
      )}

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
