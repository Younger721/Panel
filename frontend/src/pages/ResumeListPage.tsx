import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AlertCircle, ArrowRight, FileStack, FileText, Loader2, Plus } from 'lucide-react'
import { resumeApi } from '@/api/resume'
import type { ResumeStatus, ResumeView } from '@/types/api'
import { formatDate } from '@/utils/format'

const STATUS_STYLE: Record<ResumeStatus, { label: string; className: string }> = {
  PENDING: { label: '排队中', className: 'bg-slate-100 text-slate-500' },
  PARSING: { label: '解析中', className: 'bg-sky-50 text-sky-600' },
  ANALYZING: { label: '批注中', className: 'bg-violet-50 text-violet-600' },
  PARSED: { label: '已完成', className: 'bg-emerald-50 text-emerald-600' },
  FAILED: { label: '失败', className: 'bg-rose-50 text-rose-600' }
}

export default function ResumeListPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<ResumeView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    resumeApi
      .list()
      .then(list => {
        if (!cancelled) {
          setItems([...list].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1)))
        }
      })
      .catch(err => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '加载失败')
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="mx-auto max-w-5xl">
      <div className="mb-8 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">简历库</h1>
          <p className="mt-1 text-sm text-slate-500">所有上传过的简历，点开可以看逐条批注。</p>
        </div>
        <Link to="/" className="btn-primary">
          <Plus className="h-4 w-4" />
          上传新简历
        </Link>
      </div>

      {loading ? (
        <p className="text-sm text-slate-400">加载中…</p>
      ) : error ? (
        <p className="text-sm text-rose-600">{error}</p>
      ) : items.length === 0 ? (
        <div className="card flex flex-col items-center justify-center py-20">
          <FileStack className="h-10 w-10 text-slate-300" />
          <p className="mt-4 text-sm text-slate-500">还没有简历</p>
          <Link to="/" className="btn-primary mt-6">
            去上传
          </Link>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {items.map(item => {
            const meta = STATUS_STYLE[item.status]
            const ready = item.status === 'PARSED'
            const working =
              item.status === 'PENDING' || item.status === 'PARSING' || item.status === 'ANALYZING'
            return (
              <button
                key={item.id}
                onClick={() => ready && navigate(`/resumes/${item.id}`)}
                disabled={!ready}
                className={`card group flex flex-col items-start p-5 text-left transition-all duration-200 ${
                  ready ? 'hover:-translate-y-0.5 hover:shadow-card-hover' : 'cursor-default'
                }`}
              >
                <div className="mb-4 flex w-full items-start justify-between">
                  <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-50 text-primary-600">
                    {working ? <Loader2 className="h-5 w-5 animate-spin" /> : <FileText className="h-5 w-5" />}
                  </div>
                  <span className={`rounded-lg px-2 py-1 text-xs font-medium ${meta.className}`}>{meta.label}</span>
                </div>
                <p className="w-full truncate font-semibold text-slate-900">{item.fileName}</p>
                <p className="mt-1 text-xs text-slate-400">#{item.id} · {formatDate(item.createdAt)}</p>

                {item.status === 'FAILED' && item.lastError && (
                  <p className="mt-3 flex items-start gap-1.5 text-xs text-rose-600">
                    <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span className="line-clamp-2">{item.lastError}</span>
                  </p>
                )}

                <div className="mt-4 flex w-full items-center gap-3 border-t border-slate-50 pt-3 text-xs text-slate-400">
                  {item.hasProfile && item.yearsOfExperience !== null && (
                    <span>{item.yearsOfExperience} 年经验</span>
                  )}
                  {item.hasProfile && item.skillCount !== null && <span>{item.skillCount} 项技能</span>}
                  {ready && (
                    <span className="ml-auto flex items-center gap-1 font-medium text-primary-600">
                      查看批注
                      <ArrowRight className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5" />
                    </span>
                  )}
                </div>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
