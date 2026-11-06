import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { resumeApi } from '@/api/resume'
import type { ResumeStatus, ResumeView } from '@/types/api'

const STATUS_META: Record<ResumeStatus, { label: string; className: string; busy: boolean }> = {
  PENDING: { label: '排队中', className: 'bg-slate-100 text-slate-600', busy: true },
  PARSING: { label: '解析中', className: 'bg-sky-100 text-sky-700', busy: true },
  ANALYZING: { label: '分析中', className: 'bg-violet-100 text-violet-700', busy: true },
  PARSED: { label: '已完成', className: 'bg-emerald-100 text-emerald-700', busy: false },
  FAILED: { label: '失败', className: 'bg-rose-100 text-rose-700', busy: false }
}

export default function UploadPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<ResumeView[]>([])
  const [loading, setLoading] = useState(true)
  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [activeId, setActiveId] = useState<number | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)
  const polling = useRef(false)

  const refresh = useCallback(async () => {
    try {
      const list = await resumeApi.list()
      setItems([...list].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1)))
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /** 上传后在列表里就地轮询，直到 PARSED / FAILED。 */
  const poll = useCallback(
    async (id: number) => {
      if (polling.current) {
        return
      }
      polling.current = true
      try {
        for (let i = 0; i < 90; i += 1) {
          await new Promise(resolve => window.setTimeout(resolve, 2000))
          const result = await resumeApi.find(id)
          const view = result
          setItems(prev => prev.map(item => (item.id === view.id ? view : item)))
          if (view.status === 'PARSED' || view.status === 'FAILED') {
            break
          }
        }
      } catch {
        /* 轮询失败不打断页面，用户可手动刷新 */
      } finally {
        polling.current = false
      }
    },
    []
  )

  const upload = useCallback(
    async (file: File) => {
      setUploading(true)
      setError(null)
      try {
        const view = await resumeApi.upload(file)
        setActiveId(view.id)
        setItems(prev => [view, ...prev])
        void poll(view.id)
      } catch (err) {
        setError(err instanceof Error ? err.message : '上传失败')
      } finally {
        setUploading(false)
      }
    },
    [poll]
  )

  return (
    <div className="mx-auto max-w-4xl px-6 py-12">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-slate-900">简历批注</h1>
          <p className="mt-1 text-sm text-slate-500">上传 PDF / Word / TXT，自动切成模块并逐条批注。</p>
        </div>
        <button onClick={() => void refresh()} className="text-xs text-slate-400 hover:text-slate-600">
          刷新
        </button>
      </header>

      <div
        onDragOver={event => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={event => {
          event.preventDefault()
          setDragging(false)
          const file = event.dataTransfer.files?.[0]
          if (file) {
            void upload(file)
          }
        }}
        onClick={() => inputRef.current?.click()}
        className={`mt-6 flex cursor-pointer flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed px-6 py-12 transition ${
          dragging ? 'border-slate-500 bg-white' : 'border-slate-300 bg-white/60 hover:border-slate-400 hover:bg-white'
        }`}
      >
        <p className="text-sm font-medium text-slate-700">
          {uploading ? '上传中…' : dragging ? '松手即可上传' : '把简历拖到这里，或点击选择文件'}
        </p>
        <p className="text-xs text-slate-400">支持 .pdf / .docx / .doc / .txt，单份解析约 40–60 秒</p>
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.docx,.doc,.txt"
          className="hidden"
          onChange={event => {
            const file = event.target.files?.[0]
            if (file) {
              void upload(file)
            }
            event.target.value = ''
          }}
        />
      </div>

      {error && <p className="mt-3 text-xs text-rose-600">{error}</p>}

      <section className="mt-10">
        <h2 className="text-xs font-medium uppercase tracking-wide text-slate-400">我的简历</h2>
        {loading ? (
          <p className="mt-4 text-sm text-slate-400">加载中…</p>
        ) : items.length === 0 ? (
          <p className="mt-4 text-sm text-slate-400">还没有上传过简历</p>
        ) : (
          <ul className="mt-3 divide-y divide-slate-200 overflow-hidden rounded-xl border border-slate-200 bg-white">
            {items.map(item => {
              const meta = STATUS_META[item.status] || STATUS_META.PENDING
              const ready = item.status === 'PARSED'
              return (
                <li
                  key={item.id}
                  className={`flex items-center gap-3 px-4 py-3 ${ready ? 'cursor-pointer hover:bg-slate-50' : ''}`}
                  onClick={() => {
                    if (ready) {
                      navigate(`/resumes/${item.id}`)
                    }
                  }}
                >
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm text-slate-800">{item.fileName}</p>
                    <p className="mt-0.5 text-[11px] text-slate-400">
                      #{item.id} · {new Date(item.createdAt).toLocaleString('zh-CN')}
                      {item.lastError ? ` · ${item.lastError}` : ''}
                    </p>
                  </div>
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium ${meta.className}`}>
                    {item.id === activeId && meta.busy ? `${meta.label}…` : meta.label}
                  </span>
                  {ready && (
                    <Link
                      to={`/resumes/${item.id}`}
                      onClick={event => event.stopPropagation()}
                      className="shrink-0 text-xs font-medium text-slate-500 hover:text-slate-900"
                    >
                      看批注 →
                    </Link>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </section>
    </div>
  )
}
