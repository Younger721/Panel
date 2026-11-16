import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  AlertCircle,
  ArrowLeft,
  Download,
  Eye,
  FileText,
  Loader2,
  RefreshCw,
  Trash2,
  Upload,
  X
} from 'lucide-react'
import KbTabs from '@/components/KbTabs'
import {
  kbApi,
  type KbChunkView,
  type KbDocumentStatus,
  type KbDocumentView,
  type KnowledgeBaseView
} from '@/api/kb'
import KbSearchPanel from '@/components/KbSearchPanel'
import { formatDate } from '@/utils/format'

const STATUS_META: Record<KbDocumentStatus, { label: string; className: string; busy: boolean }> = {
  UPLOADING: { label: '排队中', className: 'bg-slate-100 text-slate-500', busy: true },
  PARSING: { label: '解析中', className: 'bg-sky-50 text-sky-600', busy: true },
  CHUNKED: { label: '已切片', className: 'bg-indigo-50 text-indigo-600', busy: true },
  INDEXING: { label: '索引中', className: 'bg-violet-50 text-violet-600', busy: true },
  READY: { label: '就绪', className: 'bg-emerald-50 text-emerald-600', busy: false },
  FAILED: { label: '失败', className: 'bg-rose-50 text-rose-600', busy: false },
  DELETED: { label: '已删除', className: 'bg-slate-100 text-slate-400', busy: false }
}

const STATUS_FILTERS: { value: string; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'READY', label: '就绪' },
  { value: 'FAILED', label: '失败' },
  { value: 'INDEXING', label: '索引中' },
  { value: 'PARSING', label: '解析中' }
]

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function TabButton({
  active,
  onClick,
  children
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`-mb-px border-b-2 px-4 py-2.5 text-sm font-medium transition ${
        active
          ? 'border-primary-500 text-primary-600'
          : 'border-transparent text-slate-500 hover:text-slate-800'
      }`}
    >
      {children}
    </button>
  )
}

export default function KnowledgeBaseDetailPage() {
  const { id } = useParams()
  const kbId = Number(id)
  const params = new URLSearchParams(window.location.search)
  const [tab, setTab] = useState<'documents' | 'search'>(() =>
    params.get('tab') === 'search' || params.get('q') ? 'search' : 'documents'
  )
  const [kb, setKb] = useState<KnowledgeBaseView | null>(null)
  const [documents, setDocuments] = useState<KbDocumentView[]>([])
  const [total, setTotal] = useState(0)
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(0)
  const [selected, setSelected] = useState<KbDocumentView | null>(null)
  const [chunks, setChunks] = useState<KbChunkView[]>([])
  const [chunkLoading, setChunkLoading] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    if (!Number.isFinite(kbId) || kbId <= 0) {
      setError('知识库 id 不合法')
      setLoading(false)
      return
    }
    try {
      const [base, page] = await Promise.all([
        kbApi.get(kbId),
        kbApi.documents(kbId, { status: status || undefined, page: 1, size: 50 })
      ])
      setKb(base)
      setDocuments(page.items)
      setTotal(page.total)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [kbId, status])

  useEffect(() => {
    setLoading(true)
    void load()
  }, [load])

  const busy = useMemo(() => documents.some(doc => STATUS_META[doc.status].busy), [documents])

  useEffect(() => {
    if (!busy) return
    const timer = window.setInterval(() => {
      void load()
    }, 2500)
    return () => window.clearInterval(timer)
  }, [busy, load])

  async function handleFiles(files: FileList | null) {
    if (!files || files.length === 0) return
    setUploading(files.length)
    const failures: string[] = []
    for (const file of Array.from(files)) {
      try {
        await kbApi.upload(kbId, file)
      } catch (err) {
        failures.push(`${file.name}：${err instanceof Error ? err.message : '上传失败'}`)
      }
    }
    setUploading(0)
    if (failures.length > 0) {
      setError(failures.join('\n'))
    }
    if (fileInput.current) fileInput.current.value = ''
    await load()
  }

  async function removeKb() {
    if (!kb) return
    if (!window.confirm(`删除知识库「${kb.name}」？\n\n${kb.documentCount} 份文档、${kb.chunkCount} 个切片、整个知识目录和已挂的题目都会一起删除，不可恢复。`)) {
      return
    }
    try {
      await kbApi.remove(kb.id)
      window.location.assign('/kb')
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除知识库失败')
    }
  }

  async function remove(doc: KbDocumentView) {
    if (!window.confirm(`删除「${doc.fileName}」？切片与索引会一起删除。`)) return
    try {
      await kbApi.deleteDocument(doc.id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  async function retry(doc: KbDocumentView) {
    try {
      await kbApi.retry(doc.id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '重试失败')
    }
  }

  async function reindex(doc: KbDocumentView) {
    try {
      await kbApi.reindex(doc.id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '重建索引失败')
    }
  }

  async function togglePublish(doc: KbDocumentView) {
    try {
      await kbApi.publish(doc.id, !doc.published)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败')
    }
  }

  async function download(doc: KbDocumentView) {
    try {
      const blob = await kbApi.downloadDocument(doc.id)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = doc.fileName
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(err instanceof Error ? err.message : '下载失败')
    }
  }

  async function openChunks(doc: KbDocumentView) {
    setSelected(doc)
    setChunks([])
    setChunkLoading(true)
    try {
      const page = await kbApi.chunks(doc.id, 1, 100)
      setChunks(page.items)
    } catch (err) {
      setError(err instanceof Error ? err.message : '切片加载失败')
    } finally {
      setChunkLoading(false)
    }
  }

  if (loading) {
    return <p className="mx-auto max-w-6xl text-sm text-slate-400">加载中…</p>
  }

  if (!kb) {
    return (
      <div className="mx-auto max-w-6xl">
        <p className="text-sm text-rose-600">{error || '知识库不存在'}</p>
        <Link to="/kb" className="btn-ghost mt-4">
          返回知识库
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-6xl">
      <Link to="/kb" className="inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-900">
        <ArrowLeft className="h-4 w-4" />
        知识库
      </Link>

      <div className="mt-4 flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-slate-900">{kb.name}</h1>
            <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-600">
              {kb.code}
            </span>
          </div>
          <p className="mt-1 text-sm text-slate-500">
            {kb.description || '暂无描述'} · 文档 {kb.documentCount} · 切片 {kb.chunkCount}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <input
            ref={fileInput}
            type="file"
            multiple
            className="hidden"
            onChange={event => void handleFiles(event.target.files)}
            accept=".pdf,.docx,.md,.markdown,.txt,.html,.htm"
          />
          <button
            type="button"
            className="btn-primary"
            onClick={() => fileInput.current?.click()}
            disabled={uploading > 0}
          >
            {uploading > 0 ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
            {uploading > 0 ? `上传中（${uploading}）` : '上传文档'}
          </button>
          {!kb.builtin && (
            <button
              type="button"
              onClick={() => void removeKb()}
              title="删除知识库"
              aria-label="删除知识库"
              className="inline-flex items-center gap-1.5 rounded-xl border border-rose-100 bg-white px-3.5 py-2 text-sm font-medium text-rose-500 transition-colors hover:border-rose-200 hover:bg-rose-50"
            >
              <Trash2 className="h-4 w-4" />
              删除知识库
            </button>
          )}
        </div>
      </div>

      <KbTabs kbId={kbId} active="documents" />

      {error && (
        <div className="mt-5 flex items-start gap-3 whitespace-pre-line rounded-xl border border-rose-100 bg-rose-50 px-4 py-3 text-sm text-rose-600">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="flex-1">{error}</span>
          <button type="button" onClick={() => setError(null)} aria-label="关闭">
            <X className="h-4 w-4" />
          </button>
        </div>
      )}

      <div className="mt-6 flex items-center gap-1 border-b border-slate-200">
        <TabButton active={tab === 'documents'} onClick={() => setTab('documents')}>
          文档
          <span className="ml-1.5 text-xs text-slate-400">{total}</span>
        </TabButton>
        <TabButton active={tab === 'search'} onClick={() => setTab('search')}>
          检索测试
        </TabButton>
      </div>

      {tab === 'documents' ? (
        <>
          <div className="mt-5 flex items-center justify-between">
            <p className="text-sm text-slate-500">
              共 {total} 份文档{busy ? ' · 有任务处理中，列表每 3 秒自动刷新' : ''}
            </p>
            <select
              className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600 outline-none focus:border-primary-400"
              value={status}
              onChange={event => setStatus(event.target.value)}
            >
              {STATUS_FILTERS.map(item => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </div>

          {documents.length === 0 ? (
            <div className="card mt-4 flex flex-col items-center justify-center py-20">
              <FileText className="h-10 w-10 text-slate-300" />
              <p className="mt-4 text-sm text-slate-500">还没有文档</p>
              <p className="mt-1 text-xs text-slate-400">支持 PDF、DOCX、Markdown、TXT、HTML，单个文件不超过 20MB</p>
              <button type="button" className="btn-primary mt-6" onClick={() => fileInput.current?.click()}>
                上传第一份文档
              </button>
            </div>
          ) : (
            <div className="card mt-4 divide-y divide-slate-100">
              {documents.map(doc => {
                const meta = STATUS_META[doc.status]
                return (
                  <div key={doc.id} className="flex flex-wrap items-center gap-3 px-5 py-4">
                    <FileText className="h-5 w-5 shrink-0 text-slate-300" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <p className="truncate text-sm font-medium text-slate-800">{doc.fileName}</p>
                        {doc.published && (
                          <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-600">
                            已发布
                          </span>
                        )}
                      </div>
                      <p className="mt-1 text-xs text-slate-400">
                        {formatSize(doc.sizeBytes)} · 切片 {doc.chunkCount} · 更新 {formatDate(doc.updatedAt)}
                      </p>
                      {doc.status === 'FAILED' && doc.errorMessage && (
                        <p className="mt-1 text-xs text-rose-500">{doc.errorMessage}</p>
                      )}
                    </div>

                    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-medium ${meta.className}`}>
                      {meta.busy && <Loader2 className="h-3 w-3 animate-spin" />}
                      {meta.label}
                    </span>

                    <div className="flex items-center gap-1">
                      {doc.status === 'READY' && (
                        <>
                          <button
                            type="button"
                            className="rounded-lg p-2 text-slate-400 hover:bg-slate-50 hover:text-slate-700"
                            title="查看切片"
                            onClick={() => void openChunks(doc)}
                          >
                            <Eye className="h-4 w-4" />
                          </button>
                          <button
                            type="button"
                            className="rounded-lg px-2 py-1.5 text-xs text-slate-500 hover:bg-slate-50"
                            onClick={() => void togglePublish(doc)}
                          >
                            {doc.published ? '下线' : '发布'}
                          </button>
                          <button
                            type="button"
                            className="rounded-lg p-2 text-slate-400 hover:bg-slate-50 hover:text-slate-700"
                            title="重建索引"
                            onClick={() => void reindex(doc)}
                          >
                            <RefreshCw className="h-4 w-4" />
                          </button>
                          <button
                            type="button"
                            className="rounded-lg p-2 text-slate-400 hover:bg-slate-50 hover:text-slate-700"
                            title="下载原文件"
                            onClick={() => void download(doc)}
                          >
                            <Download className="h-4 w-4" />
                          </button>
                        </>
                      )}
                      {doc.status === 'FAILED' && (
                        <button
                          type="button"
                          className="inline-flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-slate-500 hover:bg-slate-50"
                          onClick={() => void retry(doc)}
                        >
                          <RefreshCw className="h-3.5 w-3.5" />
                          重试
                        </button>
                      )}
                      <button
                        type="button"
                        className="rounded-lg p-2 text-slate-300 hover:bg-rose-50 hover:text-rose-500"
                        title="删除"
                        onClick={() => void remove(doc)}
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </>
      ) : (
        <KbSearchPanel kb={kb} />
      )}

      {selected && (
        <div className="fixed inset-0 z-[60] flex justify-end bg-slate-900/30">
          <div className="flex h-full w-full max-w-2xl flex-col bg-white shadow-xl">
            <div className="flex items-start justify-between border-b border-slate-100 px-6 py-4">
              <div>
                <h2 className="text-base font-semibold text-slate-900">切片预览</h2>
                <p className="mt-1 text-xs text-slate-400">
                  {selected.fileName} · 共 {selected.chunkCount} 片
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSelected(null)}
                aria-label="关闭"
                className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-50"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-6 py-4">
              {chunkLoading ? (
                <p className="text-sm text-slate-400">加载中…</p>
              ) : chunks.length === 0 ? (
                <p className="text-sm text-slate-400">还没有切片</p>
              ) : (
                <div className="space-y-3">
                  {chunks.map(chunk => (
                    <div key={chunk.id} className="rounded-xl border border-slate-100 bg-slate-50/60 p-4">
                      <div className="flex items-center justify-between text-[11px] text-slate-400">
                        <span>
                          #{chunk.ordinal + 1}
                          {chunk.heading ? ` · ${chunk.heading}` : ''}
                        </span>
                        <span>{chunk.tokenCount} token</span>
                      </div>
                      <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-slate-700">
                        {chunk.text}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
