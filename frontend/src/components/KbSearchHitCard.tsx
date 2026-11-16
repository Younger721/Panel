import { useState } from 'react'
import { ChevronDown, CornerDownRight } from 'lucide-react'
import type { KbSearchHitView } from '@/api/kb'

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** 把检索词在正文里高亮；只做字面量匹配，不当正则用。 */
function highlight(text: string, query: string) {
  const terms = query.trim().split(/\s+/).filter(Boolean)
  if (terms.length === 0) return text
  const pattern = new RegExp(`(${terms.map(escapeRegExp).join('|')})`, 'gi')
  const lowered = new Set(terms.map(term => term.toLowerCase()))
  return text.split(pattern).map((part, index) =>
    lowered.has(part.toLowerCase()) ? (
      <mark key={index} className="rounded bg-amber-200/70 px-0.5 text-inherit">
        {part}
      </mark>
    ) : (
      part
    )
  )
}

const SOURCE_META: Record<string, { label: string; className: string }> = {
  keyword: { label: 'BM25', className: 'bg-sky-50 text-sky-600' },
  vector: { label: '向量', className: 'bg-violet-50 text-violet-600' }
}

export default function KbSearchHitCard({
  hit,
  index,
  query
}: {
  hit: KbSearchHitView
  index: number
  query: string
}) {
  const [expanded, setExpanded] = useState(false)
  const finalScore = hit.rerankScore ?? hit.score
  const percent = Math.max(0, Math.min(1, finalScore)) * 100
  const scoreTone =
    hit.rerankScore !== null
      ? finalScore >= 0.7
        ? 'text-emerald-600'
        : finalScore >= 0.4
          ? 'text-amber-600'
          : 'text-rose-500'
      : 'text-slate-600'
  const barTone =
    hit.rerankScore !== null
      ? finalScore >= 0.7
        ? 'from-emerald-400 to-emerald-500'
        : finalScore >= 0.4
          ? 'from-amber-400 to-amber-500'
          : 'from-rose-400 to-rose-500'
      : 'from-slate-300 to-slate-400'
  const long = hit.text.length > 220

  return (
    <article className="card group relative overflow-hidden p-5 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-card-hover">
      <div className="flex items-start justify-between gap-4">
        <div className="flex min-w-0 items-start gap-3">
          <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-primary-500 to-indigo-600 text-xs font-semibold text-white shadow-sm shadow-primary-500/30">
            {index + 1}
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-slate-800">{hit.documentName || '未命名文档'}</p>
            <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-slate-400">
              {hit.heading && (
                <span className="inline-flex min-w-0 items-center gap-1">
                  <CornerDownRight className="h-3 w-3 shrink-0" />
                  <span className="max-w-[260px] truncate">{hit.heading}</span>
                </span>
              )}
              {hit.pageNo !== null && (
                <span className="rounded bg-slate-100 px-1.5 py-0.5">第 {hit.pageNo} 页</span>
              )}
              <span className="rounded bg-slate-100 px-1.5 py-0.5">切片 #{hit.ordinal + 1}</span>
            </p>
          </div>
        </div>

        <div className="shrink-0 text-right">
          <p className={`font-mono text-sm font-semibold ${scoreTone}`}>{finalScore.toFixed(4)}</p>
          <div className="mt-1.5 h-1 w-20 overflow-hidden rounded-full bg-slate-100">
            <div className={`h-full rounded-full bg-gradient-to-r ${barTone}`} style={{ width: `${percent}%` }} />
          </div>
          <p className="mt-1 text-[10px] text-slate-400">{hit.rerankScore !== null ? 'rerank' : '融合'}</p>
        </div>
      </div>

      <p
        className={`mt-3 whitespace-pre-wrap break-words text-sm leading-6 text-slate-700 ${
          expanded ? '' : 'line-clamp-4'
        }`}
      >
        {highlight(hit.text, query)}
      </p>
      {long && (
        <button
          type="button"
          onClick={() => setExpanded(value => !value)}
          className="mt-1.5 inline-flex items-center gap-1 text-[11px] font-medium text-primary-600 hover:text-primary-700"
        >
          {expanded ? '收起' : '展开全文'}
          <ChevronDown className={`h-3 w-3 transition-transform ${expanded ? 'rotate-180' : ''}`} />
        </button>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-1.5 text-[11px]">
        {hit.sources.map(source => {
          const meta = SOURCE_META[source] ?? { label: source, className: 'bg-slate-100 text-slate-500' }
          return (
            <span key={source} className={`rounded-full px-2 py-0.5 font-medium ${meta.className}`}>
              {meta.label}
            </span>
          )
        })}
        {hit.keywordRank !== null && <span className="text-slate-400">BM25 #{hit.keywordRank}</span>}
        {hit.vectorRank !== null && <span className="text-slate-400">向量 #{hit.vectorRank}</span>}
      </div>
    </article>
  )
}
