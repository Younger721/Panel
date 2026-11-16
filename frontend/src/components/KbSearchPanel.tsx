import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertCircle, Loader2, Minus, Plus, Search } from 'lucide-react'
import {
  kbApi,
  type KbRetrievalMode,
  type KbSearchResultView,
  type KbSearchStageView,
  type KnowledgeBaseView
} from '@/api/kb'
import RetrievalThinkingChain, { type RetrievalPhase } from '@/components/RetrievalThinkingChain'
import KbSearchHitCard from '@/components/KbSearchHitCard'
import { catalogApi, type KbCatalogNode } from '@/api/catalog'

const MODES: { value: KbRetrievalMode; label: string }[] = [
  { value: 'HYBRID', label: '混合' },
  { value: 'KEYWORD', label: '关键词' },
  { value: 'VECTOR', label: '向量' }
]

const MODE_LABEL: Record<KbRetrievalMode, string> = {
  HYBRID: '混合检索',
  KEYWORD: '关键词检索',
  VECTOR: '向量检索'
}

const EXAMPLES = ['RDB 是什么', 'Redis 持久化方式', 'Spring 事务失效场景', '线程池参数怎么配']

function Switch({
  checked,
  onChange,
  label,
  hint
}: {
  checked: boolean
  onChange: (next: boolean) => void
  label: string
  hint?: string
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      title={hint}
      onClick={() => onChange(!checked)}
      className="group inline-flex items-center gap-2 text-xs text-slate-500"
    >
      <span
        className={`relative h-5 w-9 rounded-full transition-colors duration-200 ${
          checked ? 'bg-primary-500' : 'bg-slate-200 group-hover:bg-slate-300'
        }`}
      >
        <span
          className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow-sm transition-all duration-200 ${
            checked ? 'left-[18px]' : 'left-0.5'
          }`}
        />
      </span>
      <span className={checked ? 'font-medium text-slate-700' : ''}>{label}</span>
    </button>
  )
}

function upsertStage(stages: KbSearchStageView[], stage: KbSearchStageView) {
  const index = stages.findIndex(item => item.id === stage.id)
  if (index < 0) return [...stages, stage]
  const next = [...stages]
  next[index] = stage
  return next
}

function flattenCategories(nodes: KbCatalogNode[], depth = 0, acc: { node: KbCatalogNode; depth: number }[] = []) {
  for (const node of nodes) {
    if (node.system) continue
    acc.push({ node, depth })
    flattenCategories(node.children, depth + 1, acc)
  }
  return acc
}

export default function KbSearchPanel({ kb }: { kb: KnowledgeBaseView }) {
  const params = new URLSearchParams(window.location.search)
  const [query, setQuery] = useState(() => params.get('q') ?? '')
  const [mode, setMode] = useState<KbRetrievalMode>(() => {
    const initial = params.get('mode')
    return initial === 'KEYWORD' || initial === 'VECTOR' ? initial : 'HYBRID'
  })
  const [topK, setTopK] = useState(() => {
    const value = Number(params.get('topK'))
    return Number.isFinite(value) && value >= 1 && value <= 20 ? value : 8
  })
  const [rerank, setRerank] = useState(() => params.get('rerank') !== 'false')
  const [publishedOnly, setPublishedOnly] = useState(() => params.get('publishedOnly') === 'true')
  const [categories, setCategories] = useState<KbCatalogNode[]>([])
  const [categoryId, setCategoryId] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    catalogApi
      .tree(kb.id)
      .then(nodes => {
        if (!cancelled) setCategories(nodes)
      })
      .catch(() => {
        /* 目录树加载失败时不阻塞检索 */
      })
    return () => {
      cancelled = true
    }
  }, [kb.id])

  const [phase, setPhase] = useState<RetrievalPhase>('idle')
  const [stages, setStages] = useState<KbSearchStageView[]>([])
  const [result, setResult] = useState<KbSearchResultView | null>(null)
  const [activeQuery, setActiveQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [elapsed, setElapsed] = useState<number | null>(null)
  const startRef = useRef(0)

  useEffect(() => {
    if (phase !== 'thinking') return
    const timer = window.setInterval(() => setElapsed(performance.now() - startRef.current), 90)
    return () => window.clearInterval(timer)
  }, [phase])

  const runSearch = useCallback(
    async (override?: string) => {
      const trimmed = (override ?? query).trim()
      if (!trimmed) {
        setError('请输入检索词')
        return
      }
      startRef.current = performance.now()
      setActiveQuery(trimmed)
      setPhase('thinking')
      setElapsed(0)
      setStages([])
      setResult(null)
      setError(null)
      try {
        const view = await kbApi.searchStream(
          kb.id,
          { query: trimmed, topK, mode, rerank, publishedOnly,
            categoryIds: categoryId == null ? undefined : [categoryId] },
          {
            onStage: stage => setStages(previous => upsertStage(previous, stage)),
            onResult: next => setResult(next)
          }
        )
        setResult(view)
        setPhase('done')
        setElapsed(view.debug.latencyMs)
      } catch (err) {
        setPhase('error')
        setError(err instanceof Error ? err.message : '检索失败')
      }
    },
    [kb.id, query, topK, mode, rerank, publishedOnly, categoryId]
  )

  const booted = useRef(false)
  useEffect(() => {
    if (booted.current) return
    booted.current = true
    if (query.trim()) void runSearch(query)
    // 只在首次挂载时按 URL 里的 q 自动检索一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const thinking = phase === 'thinking'
  const degraded = result?.debug.degraded || stages.some(stage => stage.status === 'FAILED')

  return (
    <div className="mt-5 space-y-4">
      <section className="card relative overflow-hidden">
        <div className="pointer-events-none absolute -right-16 -top-24 h-56 w-56 rounded-full bg-primary-100/60 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-24 -left-20 h-52 w-52 rounded-full bg-indigo-100/50 blur-3xl" />
        <div className="relative z-10 p-5 sm:p-6">
          <div className="flex items-center justify-between gap-3">
            <label className="text-sm font-medium text-slate-700" htmlFor="kb-query">
              检索词
            </label>
            <span className="text-[11px] text-slate-400">
              {kb.documentCount} 篇文档 · 阈值 {kb.scoreThreshold} · 默认 topK {kb.topK}
            </span>
          </div>

          <div className="mt-2.5 flex flex-col gap-2 sm:flex-row">
            <div className="relative flex-1">
              <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <input
                id="kb-query"
                value={query}
                onChange={event => setQuery(event.target.value)}
                onKeyDown={event => {
                  if (event.key === 'Enter') void runSearch()
                }}
                placeholder="例如：RDB 是什么 / Redis 持久化方式"
                className="h-12 w-full rounded-xl border border-slate-200 bg-white pl-10 pr-3 text-[15px] text-slate-700 outline-none transition-colors placeholder:text-slate-300 focus:border-primary-400 focus:ring-4 focus:ring-primary-100"
              />
            </div>
            <button
              type="button"
              className="btn-primary h-12 px-6"
              onClick={() => void runSearch()}
              disabled={thinking}
            >
              {thinking ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
              {thinking ? '检索中' : '检索'}
            </button>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-3">
            <div className="flex items-center gap-2">
              <span className="text-xs text-slate-400">模式</span>
              <div className="inline-flex rounded-xl bg-slate-100 p-1">
                {MODES.map(item => (
                  <button
                    key={item.value}
                    type="button"
                    onClick={() => setMode(item.value)}
                    className={`rounded-lg px-3 py-1.5 text-xs transition-all ${
                      mode === item.value
                        ? 'bg-white font-medium text-primary-600 shadow-sm'
                        : 'text-slate-500 hover:text-slate-700'
                    }`}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex items-center gap-2">
              <span className="text-xs text-slate-400">topK</span>
              <div className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white p-0.5">
                <button
                  type="button"
                  aria-label="减少 topK"
                  onClick={() => setTopK(value => Math.max(1, value - 1))}
                  className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-50 hover:text-slate-600"
                >
                  <Minus className="h-3 w-3" />
                </button>
                <span className="w-7 text-center text-sm font-medium text-slate-700">{topK}</span>
                <button
                  type="button"
                  aria-label="增加 topK"
                  onClick={() => setTopK(value => Math.min(20, value + 1))}
                  className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-50 hover:text-slate-600"
                >
                  <Plus className="h-3 w-3" />
                </button>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <span className="text-xs text-slate-400">目录</span>
              <select
                value={categoryId ?? 0}
                onChange={event => setCategoryId(Number(event.target.value) || null)}
                className="h-8 max-w-[180px] rounded-xl border border-slate-200 bg-white px-2.5 text-xs text-slate-600 outline-none transition-colors focus:border-primary-400"
              >
                <option value={0}>全部目录</option>
                {flattenCategories(categories).map(({ node, depth }) => (
                  <option key={node.id} value={node.id}>
                    {'　'.repeat(depth)}
                    {node.name}
                  </option>
                ))}
              </select>
            </div>

            <Switch checked={rerank} onChange={setRerank} label="启用 rerank" hint="用百炼 rerank 对融合候选重新打分" />
            <Switch
              checked={publishedOnly}
              onChange={setPublishedOnly}
              label="仅已发布"
              hint="勾选后与「问答」的检索范围一致"
            />
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2">
            <span className="text-[11px] text-slate-400">试试</span>
            {EXAMPLES.map(example => (
              <button
                key={example}
                type="button"
                onClick={() => {
                  setQuery(example)
                  void runSearch(example)
                }}
                className="rounded-full border border-slate-200 bg-white/70 px-2.5 py-1 text-[11px] text-slate-500 transition-colors hover:border-primary-200 hover:bg-primary-50 hover:text-primary-600"
              >
                {example}
              </button>
            ))}
          </div>

          <p className="mt-3 text-[11px] leading-5 text-slate-400">
            检索在 READY 文档内进行；默认包含未发布文档，勾选「仅已发布」后与「问答」一致。
          </p>
        </div>
      </section>

      <RetrievalThinkingChain
        stages={stages}
        phase={phase}
        modeLabel={MODE_LABEL[mode]}
        elapsedMs={phase === 'idle' ? null : elapsed}
        degraded={Boolean(degraded)}
        totalMs={result?.debug.latencyMs ?? null}
        hitCount={result?.hits.length ?? null}
      />

      {error && (
        <div className="flex items-start gap-3 rounded-xl border border-rose-100 bg-rose-50 px-4 py-3 text-sm text-rose-600">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {thinking && !result && (
        <div className="space-y-3">
          {[0, 1].map(index => (
            <div key={index} className="card animate-pulse p-5">
              <div className="flex items-center gap-3">
                <div className="h-7 w-7 rounded-lg bg-slate-100" />
                <div className="h-3 w-40 rounded bg-slate-100" />
              </div>
              <div className="mt-4 space-y-2">
                <div className="h-3 w-full rounded bg-slate-100" />
                <div className="h-3 w-4/5 rounded bg-slate-100" />
              </div>
            </div>
          ))}
        </div>
      )}

      {result && (
        <div>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-semibold text-slate-800">
              命中结果
              <span className="ml-1.5 text-xs font-normal text-slate-400">{result.hits.length} 条</span>
            </h2>
            {result.debug.degraded && (
              <span className="rounded-full bg-amber-50 px-2.5 py-1 text-[11px] font-medium text-amber-600">
                有阶段降级，已用剩余链路完成检索
              </span>
            )}
          </div>

          {result.hits.length === 0 ? (
            <div className="card flex flex-col items-center justify-center py-16">
              <Search className="h-8 w-8 text-slate-300" />
              <p className="mt-3 text-sm text-slate-500">没有命中</p>
              <p className="mt-1 text-xs text-slate-400">换个说法，或确认该库文档已经是「就绪」状态</p>
            </div>
          ) : (
            <div className="space-y-3">
              {result.hits.map((hit, index) => (
                <div key={hit.chunkKey} className="rise-in" style={{ animationDelay: `${Math.min(index, 8) * 45}ms` }}>
                  <KbSearchHitCard hit={hit} index={index} query={activeQuery} />
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
