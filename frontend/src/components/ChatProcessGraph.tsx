import { useMemo, useState } from 'react'
import {
  Boxes,
  Check,
  ChevronDown,
  Circle,
  GitMerge,
  Layers,
  Loader2,
  MessageSquareText,
  SlidersHorizontal,
  Type,
  Workflow,
  X
} from 'lucide-react'
import type { KbChatStage, KbChatStageStatus } from '@/api/chat'

type Phase = 'idle' | 'running' | 'done' | 'error'

interface NodeTemplate {
  id: string
  label: string
  icon: React.ComponentType<{ className?: string }>
  wave: number
  row: number
}

/**
 * 5 波 6 节点：关键词/向量并行召回，融合、精排、上下文、生成依次收口。
 * 坐标是固定的，所以边可以直接用 SVG 画，不用测量 DOM。
 */
const NODES: NodeTemplate[] = [
  { id: 'keyword', label: '关键词召回', icon: Type, wave: 0, row: 0 },
  { id: 'vector', label: '向量召回', icon: Boxes, wave: 0, row: 1 },
  { id: 'fusion', label: 'RRF 融合', icon: GitMerge, wave: 1, row: 0.5 },
  { id: 'rerank', label: '精排重排', icon: SlidersHorizontal, wave: 2, row: 0.5 },
  { id: 'context', label: '组装上下文', icon: Layers, wave: 3, row: 0.5 },
  { id: 'generate', label: '生成回答', icon: MessageSquareText, wave: 4, row: 0.5 }
]

const EDGES: [string, string][] = [
  ['keyword', 'fusion'],
  ['vector', 'fusion'],
  ['fusion', 'rerank'],
  ['rerank', 'context'],
  ['context', 'generate']
]

const NODE_W = 146
const NODE_H = 76
const GAP_X = 26
const GAP_Y = 18
const CANVAS_W = 5 * NODE_W + 4 * GAP_X
const CANVAS_H = 2 * NODE_H + GAP_Y

const STATUS_TEXT: Record<KbChatStageStatus | 'PENDING', string> = {
  PENDING: '等待前序阶段',
  RUNNING: '正在执行',
  COMPLETED: '已完成',
  SKIPPED: '已跳过',
  FAILED: '执行失败'
}

function nodeStyle(status: KbChatStageStatus | 'PENDING') {
  switch (status) {
    case 'RUNNING':
      return 'border-primary-300 bg-primary-50/80 shadow-lg shadow-primary-500/10'
    case 'COMPLETED':
      return 'border-emerald-200 bg-emerald-50/50'
    case 'FAILED':
      return 'border-rose-200 bg-rose-50/60'
    case 'SKIPPED':
      return 'border-slate-200 bg-slate-50/70'
    default:
      return 'border-dashed border-slate-200 bg-white/70'
  }
}

function statusColor(status: KbChatStageStatus | 'PENDING') {
  switch (status) {
    case 'RUNNING':
      return 'text-primary-600'
    case 'COMPLETED':
      return 'text-emerald-600'
    case 'FAILED':
      return 'text-rose-600'
    case 'SKIPPED':
      return 'text-slate-400'
    default:
      return 'text-slate-400'
  }
}

function StatusGlyph({ status }: { status: KbChatStageStatus | 'PENDING' }) {
  if (status === 'RUNNING') {
    return <Loader2 className="h-3 w-3 animate-spin text-primary-500" />
  }
  if (status === 'COMPLETED') {
    return <Check className="h-3 w-3 text-emerald-500" />
  }
  if (status === 'FAILED') {
    return <X className="h-3 w-3 text-rose-500" />
  }
  if (status === 'SKIPPED') {
    return <Circle className="h-2 w-2 text-slate-300" />
  }
  return <Circle className="h-2 w-2 text-slate-300" />
}

interface ChatProcessGraphProps {
  stages: KbChatStage[]
  phase: Phase
  elapsedMs?: number | null
  defaultCollapsed?: boolean
}

export default function ChatProcessGraph({ stages, phase, elapsedMs, defaultCollapsed = false }: ChatProcessGraphProps) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed)

  const byId = useMemo(() => {
    const map = new Map<string, KbChatStage>()
    stages.forEach(stage => map.set(stage.id, stage))
    return map
  }, [stages])

  const statusOf = (id: string): KbChatStageStatus | 'PENDING' => byId.get(id)?.status ?? 'PENDING'
  const graphLink = byId.get('graph-link')
  const graphExpand = byId.get('graph-expand')
  const graphActive = graphLink?.status === 'COMPLETED'
  const completed = NODES.filter(node => statusOf(node.id) === 'COMPLETED').length
  const phaseLabel = phase === 'running' ? '思考中' : phase === 'error' ? '对话中断' : '已完成'
  const total = elapsedMs != null ? formatMs(elapsedMs) : null

  const nodePos = (node: NodeTemplate) => ({
    x: node.wave * (NODE_W + GAP_X),
    y: node.row * (NODE_H + GAP_Y)
  })

  const pathFor = (from: string, to: string) => {
    const source = NODES.find(node => node.id === from)
    const target = NODES.find(node => node.id === to)
    if (!source || !target) return ''
    const a = nodePos(source)
    const b = nodePos(target)
    const x1 = a.x + NODE_W
    const y1 = a.y + NODE_H / 2
    const x2 = b.x
    const y2 = b.y + NODE_H / 2
    const mid = (x1 + x2) / 2
    return `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`
  }

  return (
    <section className="relative overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-4 py-2.5">
        <button
          type="button"
          onClick={() => setCollapsed(value => !value)}
          className="flex min-w-0 items-center gap-2.5 text-left"
        >
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-primary-600 text-white shadow-md shadow-primary-500/25">
            <Workflow className="h-4 w-4" />
          </span>
          <span className="min-w-0">
            <span className="flex items-center gap-1.5 text-[12.5px] font-semibold text-slate-800">
              思考链路
              <ChevronDown
                className={`h-3.5 w-3.5 text-slate-400 transition-transform ${collapsed ? '-rotate-90' : ''}`}
              />
            </span>
            <span className="block truncate text-[10.5px] text-slate-400">
              {completed}/{NODES.length} 阶段{phase === 'running' ? ' · 进行中' : ''}
            </span>
          </span>
        </button>

        <div className="flex items-center gap-2">
          {graphActive && (
            <span className="hidden items-center gap-1 rounded-full bg-indigo-50 px-2 py-0.5 text-[10.5px] font-medium text-indigo-600 sm:inline-flex">
              图谱 {graphLink?.count ?? 0} 概念 · {graphExpand?.count ?? 0} 块
            </span>
          )}
          <span
            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10.5px] font-medium ${
              phase === 'running'
                ? 'bg-primary-50 text-primary-600'
                : phase === 'error'
                  ? 'bg-rose-50 text-rose-600'
                  : 'bg-emerald-50 text-emerald-600'
            }`}
          >
            <span
              className={`h-1.5 w-1.5 rounded-full ${
                phase === 'running'
                  ? 'animate-pulse bg-primary-500'
                  : phase === 'error'
                    ? 'bg-rose-500'
                    : 'bg-emerald-500'
              }`}
            />
            {phaseLabel}
          </span>
          {total && <span className="text-[10.5px] tabular-nums text-slate-400">{total}</span>}
        </div>
      </div>

      {collapsed ? (
        <div className="flex items-center gap-1.5 px-4 py-2.5">
          {NODES.map(node => {
            const status = statusOf(node.id)
            const Icon = node.icon
            return (
              <span
                key={node.id}
                title={`${node.label} · ${STATUS_TEXT[status]}`}
                className={`flex h-6 w-6 items-center justify-center rounded-lg border ${
                  status === 'COMPLETED'
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-600'
                    : status === 'RUNNING'
                      ? 'border-primary-200 bg-primary-50 text-primary-600'
                      : status === 'FAILED'
                        ? 'border-rose-200 bg-rose-50 text-rose-600'
                        : 'border-slate-200 bg-slate-50 text-slate-300'
                }`}
              >
                <Icon className="h-3 w-3" />
              </span>
            )
          })}
          <span className="ml-1 text-[10.5px] text-slate-400">
            {stages.find(stage => stage.id === 'context')?.detail ?? '点击展开'}
          </span>
        </div>
      ) : (
        <div className="chat-graph-bg overflow-x-auto px-4 py-3">
          <div className="relative" style={{ width: CANVAS_W, height: CANVAS_H }}>
            <svg
              className="pointer-events-none absolute inset-0"
              width={CANVAS_W}
              height={CANVAS_H}
              viewBox={`0 0 ${CANVAS_W} ${CANVAS_H}`}
              aria-hidden
            >
              {EDGES.map(([from, to]) => {
                const path = pathFor(from, to)
                const sourceStatus = statusOf(from)
                const targetStatus = statusOf(to)
                const done = sourceStatus === 'COMPLETED' && targetStatus === 'COMPLETED'
                const active = phase === 'running' && targetStatus === 'RUNNING'
                return (
                  <g key={`${from}-${to}`}>
                    <path
                      d={path}
                      fill="none"
                      stroke={done ? '#34d399' : active ? '#818cf8' : '#cbd5e1'}
                      strokeWidth={active ? 2 : 1.4}
                      strokeDasharray={done || active ? undefined : '4 5'}
                    />
                    {active && (
                      <circle r="3" fill="#6366f1">
                        <animateMotion dur="1.1s" repeatCount="indefinite" path={path} />
                      </circle>
                    )}
                  </g>
                )
              })}
            </svg>

            {NODES.map(node => {
              const stage = byId.get(node.id)
              const status = stage?.status ?? 'PENDING'
              const position = nodePos(node)
              const Icon = node.icon
              const metric = stage?.count != null ? `${stage.count} 条` : null
              const line = stage?.detail ?? (status === 'RUNNING' ? '检索中…' : metric ?? STATUS_TEXT[status])
              return (
                <div
                  key={node.id}
                  className={`absolute flex flex-col justify-center rounded-xl border px-2.5 py-2 transition-all duration-300 ${nodeStyle(status)} ${
                    status === 'RUNNING' ? 'chat-node-running' : ''
                  }`}
                  style={{ left: position.x, top: position.y, width: NODE_W, height: NODE_H }}
                >
                  <div className="flex items-center gap-1.5">
                    <span
                      className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-md ${
                        status === 'COMPLETED'
                          ? 'bg-emerald-100 text-emerald-600'
                          : status === 'RUNNING'
                            ? 'bg-primary-100 text-primary-600'
                            : status === 'FAILED'
                              ? 'bg-rose-100 text-rose-600'
                              : 'bg-slate-100 text-slate-400'
                      }`}
                    >
                      <Icon className="h-3 w-3" />
                    </span>
                    <span
                      className={`truncate text-[11.5px] font-semibold ${
                        status === 'PENDING' ? 'text-slate-400' : 'text-slate-800'
                      }`}
                    >
                      {node.label}
                    </span>
                  </div>
                  <div className={`mt-1 flex items-center gap-1 text-[10px] ${statusColor(status)}`}>
                    <StatusGlyph status={status} />
                    <span className="truncate">
                      {STATUS_TEXT[status]}
                      {stage?.durationMs != null && stage.status === 'COMPLETED'
                        ? ` · ${formatMs(stage.durationMs)}`
                        : ''}
                    </span>
                  </div>
                  <div className="mt-0.5 truncate text-[10px] text-slate-400" title={line}>
                    {line}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </section>
  )
}

function formatMs(value: number) {
  if (value < 1000) return `${Math.round(value)}ms`
  return `${(value / 1000).toFixed(1)}s`
}
