import type { AgentProgress } from '@/api/interview'

/**
 * agent 名 → 中文。后端发的是类名，界面上不该出现 `ExpressionJudge` 这种词。
 * 新增 agent 时这里不加也能显示，只是会露出英文名。
 */
const AGENT_LABEL: Record<string, string> = {
  Planner: '选题',
  JobFit: '岗位匹配',
  Interviewer: '出题',
  Evaluator: '技术评委',
  ExpressionJudge: '表达评委',
  Reviewer: '复核',
  Report: '报告'
}

export function agentLabel(agent: string): string {
  return AGENT_LABEL[agent] ?? agent
}

function formatMs(ms: number): string {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

/**
 * 多 agent 的实时步骤：同一条 SSE 事件流，面试准备页和面试页共用一套解释。
 *
 * <p>{@code list} 用于设置页（带 stage 与结果摘要），{@code overlay} 用于面试舞台
 * （只有名字和耗时，正在跑的显示它在干什么）。</p>
 */
export default function AgentSteps({ steps, variant = 'list' }: {
  steps: AgentProgress[]
  variant?: 'list' | 'overlay'
}) {
  if (steps.length === 0) {
    return null
  }
  const overlay = variant === 'overlay'
  return (
    <ul
      className={
        overlay
          ? 'space-y-1.5 rounded-2xl border border-white/60 bg-white/55 p-3 shadow-[0_12px_40px_-24px_rgba(15,23,42,0.5)] backdrop-blur-md'
          : 'space-y-2 rounded-xl border border-slate-100 bg-slate-50/60 p-4'
      }
    >
      {steps.map((step, index) => {
        const running = step.status === 'STARTED'
        const failed = step.status === 'FAILED'
        return (
          <li
            key={`${step.agent}-${index}`}
            className={`flex items-center gap-2 text-slate-600 ${overlay ? 'text-[11px]' : 'text-xs'}`}
          >
            <span
              className={`h-1.5 w-1.5 shrink-0 rounded-full ${
                failed ? 'bg-rose-500' : running ? 'bg-amber-500 animate-pulse' : 'bg-emerald-500'
              }`}
            />
            <span className="shrink-0 font-medium">{agentLabel(step.agent)}</span>
            {overlay ? (
              running ? (
                <span className="ml-auto truncate text-[10px] text-amber-600">{step.stage}</span>
              ) : (
                step.durationMs > 0 && (
                  <span className="ml-auto font-mono text-[10px] text-slate-400">{formatMs(step.durationMs)}</span>
                )
              )
            ) : (
              <>
                <span className="shrink-0 text-slate-400">{step.stage}</span>
                {step.message && <span className="truncate text-slate-400">· {step.message}</span>}
                {step.status === 'COMPLETED' && step.durationMs > 0 && (
                  <span className="ml-auto font-mono text-[10px] text-slate-400">{formatMs(step.durationMs)}</span>
                )}
              </>
            )}
          </li>
        )
      })}
    </ul>
  )
}
