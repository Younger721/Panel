import { Fragment, type ComponentType } from 'react'
import {
  AlertTriangle,
  Boxes,
  Check,
  Database,
  Filter,
  GitMerge,
  Minus,
  Sparkles,
  Timer,
  Type
} from 'lucide-react'
import type { KbSearchStageStatus, KbSearchStageView } from '@/api/kb'

export type RetrievalPhase = 'idle' | 'thinking' | 'done' | 'error'

interface Props {
  stages: KbSearchStageView[]
  phase: RetrievalPhase
  modeLabel: string
  elapsedMs: number | null
  degraded?: boolean
  totalMs?: number | null
  hitCount?: number | null
}

type IconComponent = ComponentType<{ className?: string }>

interface StepTemplate {
  id: string
  label: string
  icon: IconComponent
}

/** 顺序即后端真实执行顺序；缺哪个阶段就画成待命，不会错位。 */
const TEMPLATE: StepTemplate[] = [
  { id: 'scope', label: '文档范围', icon: Database },
  { id: 'keyword', label: 'BM25 召回', icon: Type },
  { id: 'vector', label: '向量召回', icon: Boxes },
  { id: 'fusion', label: 'RRF 融合', icon: GitMerge },
  { id: 'rerank', label: '精排重排', icon: Sparkles },
  { id: 'filter', label: '阈值筛选', icon: Filter }
]

const NODE_STYLE: Record<KbSearchStageStatus | 'PENDING', string> = {
  PENDING: 'border-dashed border-slate-200 bg-slate-50 text-slate-300',
  RUNNING: 'chain-node-running border-primary-300 bg-primary-50 text-primary-600',
  COMPLETED: 'border-emerald-200 bg-emerald-50 text-emerald-600',
  SKIPPED: 'border-dashed border-slate-200 bg-white text-slate-300',
  FAILED: 'border-rose-200 bg-rose-50 text-rose-500'
}

function statusBadge(status: KbSearchStageStatus) {
  if (status === 'COMPLETED') {
    return (
      <span className="absolute -bottom-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-emerald-500 text-white ring-2 ring-white">
        <Check className="h-2.5 w-2.5" strokeWidth={3.5} />
      </span>
    )
  }
  if (status === 'FAILED') {
    return (
      <span className="absolute -bottom-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-rose-500 text-white ring-2 ring-white">
        <AlertTriangle className="h-2.5 w-2.5" strokeWidth={3} />
      </span>
    )
  }
  if (status === 'SKIPPED') {
    return (
      <span className="absolute -bottom-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-slate-300 text-white ring-2 ring-white">
        <Minus className="h-2.5 w-2.5" strokeWidth={3.5} />
      </span>
    )
  }
  return null
}

function metric(step: KbSearchStageView | undefined) {
  if (!step) return '—'
  if (step.status === 'SKIPPED') return '跳过'
  if (step.status === 'FAILED') return '失败'
  if (step.count === null || step.count === undefined) return '检索中…'
  return `${step.count} 条`
}

function timing(step: KbSearchStageView | undefined) {
  if (!step || step.durationMs === null || step.durationMs === undefined) return ''
  return `${step.durationMs}ms`
}

function compactMs(ms: number) {
  if (ms < 1000) return `${Math.max(0, Math.round(ms))}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

export default function RetrievalThinkingChain({
  stages,
  phase,
  modeLabel,
  elapsedMs,
  degraded = false,
  totalMs = null,
  hitCount = null
}: Props) {
  const byId = new Map(stages.map(stage => [stage.id, stage]))
  const steps = TEMPLATE.map(template => {
    const stage = byId.get(template.id)
    return { ...template, stage, status: stage?.status ?? ('PENDING' as const) }
  })

  const active = steps.find(step => step.status === 'RUNNING')
  const doneCount = steps.filter(step => step.status === 'COMPLETED' || step.status === 'SKIPPED').length
  const failed = steps.some(step => step.status === 'FAILED') || phase === 'error'

  const elapsed = phase === 'done' && totalMs !== null && totalMs !== undefined
    ? compactMs(totalMs)
    : elapsedMs === null
      ? '—'
      : compactMs(elapsedMs)

  const footer = (() => {
    if (phase === 'idle') return '输入检索词后，链路会按真实执行顺序逐段点亮'
    if (phase === 'error') return '检索失败，链路停在出错的那一段'
    if (phase === 'thinking') {
      return active?.stage?.detail
        ? `${active.stage.label} · ${active.stage.detail}`
        : `${active?.label ?? '检索'}执行中…`
    }
    const total = totalMs !== null && totalMs !== undefined ? compactMs(totalMs) : elapsed
    return `共 ${hitCount ?? 0} 条命中 · 全链路 ${total}${degraded ? ' · 有阶段降级' : ''}`
  })()

  return (
    <section className="card relative overflow-hidden">
      <div className="pointer-events-none absolute -right-10 -top-20 h-48 w-48 rounded-full bg-primary-100/50 blur-3xl" />
      <div className="relative z-10 p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="relative flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-indigo-500 text-white shadow-lg shadow-primary-500/25">
              <Sparkles className="h-4 w-4" />
              {phase === 'thinking' && <span className="absolute inset-0 animate-ping rounded-xl bg-primary-400/30" />}
            </div>
            <div>
              <p className="text-sm font-semibold text-slate-800">检索思考链路</p>
              <p className="mt-0.5 text-[11px] text-slate-400">
                {modeLabel} · {doneCount}/{steps.length} 段已完成
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <span
              className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-medium ${
                failed
                  ? 'bg-rose-50 text-rose-600'
                  : degraded
                    ? 'bg-amber-50 text-amber-600'
                    : phase === 'thinking'
                      ? 'bg-primary-50 text-primary-600'
                      : phase === 'done'
                        ? 'bg-emerald-50 text-emerald-600'
                        : 'bg-slate-100 text-slate-500'
              }`}
            >
              {phase === 'thinking' && <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary-500" />}
              {failed ? '失败' : degraded ? '已降级' : phase === 'thinking' ? '思考中' : phase === 'done' ? '已完成' : '待检索'}
            </span>
            <span className="inline-flex items-center gap-1 rounded-full bg-slate-50 px-2.5 py-1 font-mono text-[11px] text-slate-500">
              <Timer className="h-3 w-3" />
              {elapsed}
            </span>
          </div>
        </div>

        {phase === 'thinking' && (
          <div className="mt-4 h-1 overflow-hidden rounded-full bg-slate-100">
            <div className="chain-flow h-full w-1/3 rounded-full bg-gradient-to-r from-transparent via-primary-400 to-transparent" />
          </div>
        )}

        <div className={`mt-5 overflow-x-auto pb-1 ${phase === 'thinking' ? 'opacity-100' : ''}`}>
          <div className="flex min-w-[600px] items-start">
            {steps.map((step, index) => {
              const Icon = step.icon
              const status = step.status
              return (
                <Fragment key={step.id}>
                  <div className="flex w-[84px] shrink-0 flex-col items-center gap-1.5 text-center">
                    <div className={`relative flex h-10 w-10 items-center justify-center rounded-full border-2 transition-all duration-300 ${NODE_STYLE[status]}`}>
                      <Icon className="h-4 w-4" />
                      {status === 'RUNNING' && (
                        <span className="absolute inset-0 animate-ping rounded-full bg-primary-400/25" />
                      )}
                      {status !== 'PENDING' && status !== 'RUNNING' && statusBadge(status)}
                    </div>
                    <span className={`text-[11px] leading-4 ${status === 'RUNNING' ? 'font-semibold text-primary-600' : 'font-medium text-slate-600'}`}>
                      {step.label}
                    </span>
                    <span className="flex flex-col items-center gap-0.5 text-[10px] leading-3">
                      <span className={status === 'RUNNING' ? 'animate-pulse text-primary-500' : 'text-slate-500'}>
                        {metric(step.stage)}
                      </span>
                      <span className="font-mono text-slate-300">{timing(step.stage)}</span>
                    </span>
                  </div>

                  {index < steps.length - 1 && (
                    <div className="mt-5 h-0.5 flex-1 min-w-[10px] overflow-hidden rounded-full bg-slate-200">
                      <div
                        className={`h-full rounded-full transition-all duration-500 ${
                          status === 'COMPLETED' || status === 'SKIPPED'
                            ? 'w-full bg-gradient-to-r from-emerald-300 to-emerald-400'
                            : status === 'FAILED'
                              ? 'w-full bg-gradient-to-r from-rose-300 to-rose-400'
                              : 'w-0'
                        }`}
                      />
                    </div>
                  )}
                </Fragment>
              )
            })}
          </div>
        </div>

        <p className="mt-3 truncate text-[11px] text-slate-400">{footer}</p>
      </div>
    </section>
  )
}
