import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  AlertCircle,
  ArrowRight,
  FileStack,
  FileText,
  Loader2,
  PenLine,
  Plus,
  RefreshCw,
  RotateCcw,
  Trash2,
  X
} from 'lucide-react'
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

/** 列表按创建时间倒序；撤销回来的一条也要落回原来的位置。 */
const byCreatedDesc = (a: ResumeView, b: ResumeView) => (a.createdAt < b.createdAt ? 1 : -1)

/** 撤销提示的存活时间：够看清楚删了哪份，也不至于一直糊在屏幕底部。 */
const UNDO_WINDOW_MS = 8000

export default function ResumeListPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<ResumeView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // 删除确认弹窗的目标；null = 没开
  const [target, setTarget] = useState<ResumeView | null>(null)
  const [removing, setRemoving] = useState(false)
  const [removeError, setRemoveError] = useState<string | null>(null)

  // 刚删掉的一条：留着做"撤销"，过一会儿自动消失
  const [lastDeleted, setLastDeleted] = useState<ResumeView | null>(null)
  const [restoring, setRestoring] = useState(false)
  /** 正在重投解析的简历 id，用来只转那一张卡片的图标 */
  const [retrying, setRetrying] = useState<number | null>(null)
  const pollCount = useRef(0)

  const loadList = useCallback(async () => {
    const list = await resumeApi.list()
    setItems([...list].sort(byCreatedDesc))
  }, [])

  useEffect(() => {
    let cancelled = false
    resumeApi
      .list()
      .then(list => {
        if (!cancelled) {
          setItems([...list].sort(byCreatedDesc))
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

  const workingCount = items.filter(
    item => item.status === 'PENDING' || item.status === 'PARSING' || item.status === 'ANALYZING'
  ).length

  /**
   * 有简历还在解析就自动刷新。
   *
   * <p>没有这段之前，从上传页回到简历库看到的永远是「排队中」——状态明明在推进，
   * 页面却要手动刷新才知道。轮询有上限（约 2 分钟），避免某份简历卡死时
   * 这个页面永久空转。</p>
   */
  useEffect(() => {
    if (workingCount === 0) {
      pollCount.current = 0
      return
    }
    if (pollCount.current >= 40) {
      return
    }
    const timer = window.setInterval(() => {
      pollCount.current += 1
      loadList().catch(() => null)
    }, 3000)
    return () => window.clearInterval(timer)
  }, [workingCount, loadList])

  useEffect(() => {
    if (!lastDeleted) {
      return
    }
    const timer = window.setTimeout(() => setLastDeleted(null), UNDO_WINDOW_MS)
    return () => window.clearTimeout(timer)
  }, [lastDeleted])

  const openConfirm = (item: ResumeView) => {
    setRemoveError(null)
    setTarget(item)
  }

  const confirmRemove = async () => {
    if (!target) {
      return
    }
    setRemoving(true)
    setRemoveError(null)
    try {
      await resumeApi.remove(target.id)
      setItems(prev => prev.filter(item => item.id !== target.id))
      setLastDeleted(target)
      setTarget(null)
    } catch (err) {
      setRemoveError(err instanceof Error ? err.message : '删除失败')
    } finally {
      setRemoving(false)
    }
  }

  const undoRemove = async () => {
    if (!lastDeleted) {
      return
    }
    setRestoring(true)
    try {
      const restored = await resumeApi.restore(lastDeleted.id)
      setItems(prev => [...prev.filter(item => item.id !== restored.id), restored].sort(byCreatedDesc))
      setLastDeleted(null)
    } catch (err) {
      setRemoveError(err instanceof Error ? err.message : '撤销失败')
    } finally {
      setRestoring(false)
    }
  }

  /**
   * 失败/卡住的简历重新排队。
   *
   * <p>这个按钮之前不存在：卡片上写着「失败」并给出原因，却没有任何出路——
   * 用户只能删掉重传，重传又要再烧一遍模型。后端早就有 {@code POST /{id}/reset}。</p>
   */
  const retryParse = async (item: ResumeView) => {
    setRetrying(item.id)
    setRemoveError(null)
    try {
      const updated = await resumeApi.reset(item.id)
      setItems(prev => prev.map(row => (row.id === updated.id ? updated : row)))
      pollCount.current = 0
    } catch (err) {
      setRemoveError(err instanceof Error ? err.message : '重新分析失败')
    } finally {
      setRetrying(null)
    }
  }

  return (
    <div className="mx-auto max-w-5xl">
      <div className="mb-8 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">简历库</h1>
          <p className="mt-1 text-sm text-slate-500">
            选一份简历看逐条批注，或者直接进编辑器改排版、改措辞。删除只做软删，随时可撤销。
          </p>
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
      ) : items.length === 0 ? (        <div className="card flex flex-col items-center justify-center py-20">
          <FileStack className="h-10 w-10 text-slate-300" />
          <p className="mt-4 text-sm text-slate-500">还没有简历</p>
          <Link to="/" className="btn-primary mt-6">
            去上传
          </Link>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {removeError && (
            <p className="col-span-full rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-600">{removeError}</p>
          )}
          {items.map(item => {
            const meta = STATUS_STYLE[item.status]
            const ready = item.status === 'PARSED'
            const working =
              item.status === 'PENDING' || item.status === 'PARSING' || item.status === 'ANALYZING'
            return (
              <div
                key={item.id}
                className={`card flex flex-col p-5 transition-all duration-200 ${
                  ready ? 'hover:-translate-y-0.5 hover:shadow-card-hover' : ''
                }`}
              >
                <button
                  type="button"
                  onClick={() => ready && navigate(`/resumes/${item.id}`)}
                  disabled={!ready}
                  className={`w-full text-left ${ready ? 'cursor-pointer' : 'cursor-default'}`}
                >
                  <div className="mb-4 flex w-full items-start justify-between">
                    <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-50 text-primary-600">
                      {working ? <Loader2 className="h-5 w-5 animate-spin" /> : <FileText className="h-5 w-5" />}
                    </div>
                    <span className={`rounded-lg px-2 py-1 text-xs font-medium ${meta.className}`}>{meta.label}</span>
                  </div>
                  <p className="w-full truncate font-semibold text-slate-900">{item.fileName}</p>
                  <p className="mt-1 text-xs text-slate-400">
                    #{item.id} · {formatDate(item.createdAt)}
                  </p>

                  {item.status === 'FAILED' && item.lastError && (
                    <p className="mt-3 flex items-start gap-1.5 text-xs text-rose-600">
                      <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      <span className="line-clamp-2">{item.lastError}</span>
                    </p>
                  )}
                </button>

                <div className="mt-4 flex w-full flex-wrap items-center gap-2 border-t border-slate-50 pt-3 text-xs text-slate-400">
                  {item.hasProfile && item.yearsOfExperience !== null && (
                    <span>{item.yearsOfExperience} 年经验</span>
                  )}
                  {item.hasProfile && item.skillCount !== null && <span>{item.skillCount} 项技能</span>}

                  <div className="ml-auto flex items-center gap-1">
                    {item.status === 'FAILED' && (
                      <button
                        type="button"
                        onClick={() => void retryParse(item)}
                        disabled={retrying === item.id}
                        title="把这份简历重新排队解析"
                        className="flex items-center gap-1 rounded-lg px-2 py-1 font-medium text-primary-600 transition-colors hover:bg-primary-50 disabled:opacity-50"
                      >
                        {retrying === item.id ? (
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        ) : (
                          <RefreshCw className="h-3.5 w-3.5" />
                        )}
                        重新分析
                      </button>
                    )}
                    {ready && (
                      <>
                        <Link
                          to={`/resume-editor/${item.id}`}
                          className="flex items-center gap-1 rounded-lg px-2 py-1 font-medium text-slate-500 transition-colors hover:bg-slate-50 hover:text-slate-700"
                        >
                          <PenLine className="h-3.5 w-3.5" />
                          编辑
                        </Link>
                        <Link
                          to={`/resumes/${item.id}`}
                          className="flex items-center gap-1 rounded-lg px-2 py-1 font-medium text-primary-600 transition-colors hover:bg-primary-50"
                        >
                          查看批注
                          <ArrowRight className="h-3.5 w-3.5" />
                        </Link>
                      </>
                    )}
                    <button
                      type="button"
                      onClick={() => openConfirm(item)}
                      title="删除简历"
                      aria-label={`删除 ${item.fileName}`}
                      className="rounded-lg p-1.5 text-slate-300 transition-colors hover:bg-rose-50 hover:text-rose-500 focus-visible:bg-rose-50 focus-visible:text-rose-500 focus-visible:outline-none"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}

      {target && (
        <DeleteResumeDialog
          resume={target}
          removing={removing}
          error={removeError}
          onCancel={() => {
            setTarget(null)
            setRemoveError(null)
          }}
          onConfirm={confirmRemove}
        />
      )}

      {lastDeleted && (
        <div className="fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 items-center gap-3 rounded-xl bg-slate-900/95 px-4 py-2.5 text-xs text-white shadow-xl backdrop-blur">
          <span className="max-w-[22rem] truncate">已删除「{lastDeleted.fileName}」，原文与批注都还在。</span>
          <button
            type="button"
            onClick={undoRemove}
            disabled={restoring}
            className="inline-flex items-center gap-1 rounded-lg bg-white/10 px-2 py-1 font-medium text-white transition-colors hover:bg-white/20 disabled:opacity-50"
          >
            {restoring ? <Loader2 className="h-3 w-3 animate-spin" /> : <RotateCcw className="h-3 w-3" />}
            撤销
          </button>
          <button
            type="button"
            onClick={() => setLastDeleted(null)}
            title="关闭"
            aria-label="关闭提示"
            className="rounded-lg p-1 text-white/60 transition-colors hover:bg-white/10 hover:text-white"
          >
            <X className="h-3 w-3" />
          </button>
        </div>
      )}
    </div>
  )
}

/**
 * 删除确认弹窗。
 *
 * <p>明确写清是"软删除"：用户不会以为原文和批注被销毁了，
 * 也就不会为了留着底稿而不敢点。</p>
 */
function DeleteResumeDialog({
  resume,
  removing,
  error,
  onCancel,
  onConfirm
}: {
  resume: ResumeView
  removing: boolean
  error: string | null
  onCancel: () => void
  onConfirm: () => void
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/20 p-4 backdrop-blur-sm">
      <div className="w-full max-w-sm rounded-2xl bg-white p-5 shadow-xl">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-rose-50 text-rose-500">
          <Trash2 className="h-4 w-4" />
        </div>
        <h3 className="mt-3 text-base font-semibold text-slate-900">把「{resume.fileName}」移出简历库？</h3>
        <p className="mt-1.5 text-xs leading-5 text-slate-500">
          软删除：只是从列表里隐藏并记下删除时间，原文、画像和批注都保留，删完还能撤销。
        </p>

        {error && <p className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-600">{error}</p>}

        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-xl px-3 py-2 text-xs text-slate-500 hover:bg-slate-50"
          >
            取消
          </button>
          <button
            type="button"
            disabled={removing}
            onClick={onConfirm}
            className="inline-flex items-center gap-1.5 rounded-xl bg-rose-500 px-3.5 py-2 text-xs font-medium text-white hover:bg-rose-600 disabled:opacity-50"
          >
            {removing && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
            删除
          </button>
        </div>
      </div>
    </div>
  )
}
