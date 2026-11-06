import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, CheckCircle2, Loader2, Palette, RefreshCw, RotateCcw, Save } from 'lucide-react'
import { resumeApi } from '@/api/resume'
import PdfEditSurface, {
  DEFAULT_DIVIDER,
  type DividerStyle,
  type PdfEditSurfaceHandle
} from '@/components/PdfEditCanvas'
import StructuredEditor from '@/components/StructuredEditor'
import type { TextLayerView } from '@/types/api'

/** 间隔条样式是全局的：所有简历、所有标题共用一份，存在本机 */
const DIVIDER_KEY = 'nova-editor-divider:v1'

function loadDivider(): DividerStyle {
  try {
    const raw = localStorage.getItem(DIVIDER_KEY)
    if (raw) {
      const value = JSON.parse(raw) as Partial<DividerStyle>
      return {
        background: value.background ?? DEFAULT_DIVIDER.background,
        color: value.color ?? DEFAULT_DIVIDER.color,
        fontSize: typeof value.fontSize === 'number' ? value.fontSize : DEFAULT_DIVIDER.fontSize
      }
    }
  } catch {
    // 坏配置按默认走
  }
  return DEFAULT_DIVIDER
}

/**
 * 简历编辑页。
 *
 * 两种形态，按文件类型自动切换：
 * - **PDF**：原页渲染图 + PDF 文字层叠成的可编辑图层，看到的就是原文件的样子（{@link PdfEditSurface}）
 * - **DOCX / TXT**：没有可渲染的版面，退回结构化编辑器（富文本模板）
 *
 * 保存会把正文回写成新的原文并触发重新解析，所以「改完 → 批注更新」是闭环的。
 */
export default function ResumeEditorPage() {
  const { resumeId } = useParams()
  const navigate = useNavigate()
  const numericId = resumeId ? Number(resumeId) : null

  const [mode, setMode] = useState<'loading' | 'pdf' | 'text' | 'error'>('loading')
  const [layer, setLayer] = useState<TextLayerView | null>(null)
  const [reason, setReason] = useState<string | null>(null)
  const [fileName, setFileName] = useState('')
  const [edits, setEdits] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [divider, setDivider] = useState<DividerStyle>(loadDivider)
  const [layoutOpen, setLayoutOpen] = useState(false)

  const surfaceRef = useRef<PdfEditSurfaceHandle | null>(null)
  const draftKey = numericId ? `nova-pdf-edit:v1:resume-${numericId}` : null

  // 打开时先看有没有 PDF 文字层；没有就走结构化编辑器
  useEffect(() => {
    if (!numericId) {
      setMode('error')
      setError('请从简历库选一份简历再编辑')
      return
    }
    let cancelled = false
    setMode('loading')
    setError(null)

    resumeApi
      .find(numericId)
      .then(view => {
        if (!cancelled) {
          setFileName(view.fileName)
        }
      })
      .catch(() => null)

    resumeApi
      .textLayer(numericId)
      .then(view => {
        if (cancelled) {
          return
        }
        if (view.available && view.sections.length > 0) {
          setLayer(view)
          setReason(null)
          setMode('pdf')
          // 服务端存着上次保存的板块改动（换设备也在），本地草稿只覆盖它
          const restored: Record<string, string> = { ...(view.edits ?? {}) }
          const raw = draftKey ? window.localStorage.getItem(draftKey) : null
          if (raw) {
            try {
              const saved = JSON.parse(raw) as { edits?: Record<string, string> }
              Object.entries(saved.edits ?? {}).forEach(([key, value]) => {
                restored[Number(key)] = value
              })
            } catch {
              // 草稿坏了就用服务端的
            }
          }
          setEdits(restored)
        } else {
          setReason(view.reason ?? '这份文件没有 PDF 文字层，将使用结构化编辑器')
          setMode('text')
        }
      })
      .catch(err => {
        if (!cancelled) {
          setReason('读取原版失败，将使用结构化编辑器')
          setMode('text')
          setNotice(err instanceof Error ? err.message : null)
        }
      })

    return () => {
      cancelled = true
    }
  }, [numericId, draftKey])

  const handleEditsChange = useCallback(
    (next: Record<string, string>) => {
      setEdits(next)
      if (!draftKey) {
        return
      }
      if (Object.keys(next).length === 0) {
        window.localStorage.removeItem(draftKey)
      } else {
        window.localStorage.setItem(draftKey, JSON.stringify({ edits: next, savedAt: Date.now() }))
      }
    },
    [draftKey]
  )

  const goToAnnotations = useCallback(
    (message: string) => {
      setNotice(message)
      if (numericId) {
        window.setTimeout(() => navigate(`/resumes/${numericId}`), 600)
      }
    },
    [navigate, numericId]
  )

  const saveAndReanalyze = useCallback(async () => {
    if (!numericId || saving) {
      return
    }
    const text = surfaceRef.current?.getText() ?? ''
    if (!text.trim()) {
      setNotice('简历正文是空的，没有可保存的内容')
      return
    }
    setSaving(true)
    setNotice(null)
    try {
      const sectionEdits = surfaceRef.current?.getEdits() ?? {}
      await resumeApi.saveContent(numericId, text, undefined, sectionEdits)
      if (draftKey) {
        window.localStorage.removeItem(draftKey)
      }
      goToAnnotations('已保存，正在重新解析这份简历…')
    } catch (err) {
      setSaving(false)
      setNotice(err instanceof Error ? err.message : '保存失败，请重试')
    }
  }, [draftKey, goToAnnotations, numericId, saving])

  const reanalyze = useCallback(async () => {
    if (!numericId || saving) {
      return
    }
    setSaving(true)
    try {
      await resumeApi.reanalyze(numericId)
      goToAnnotations('已提交重新分析…')
    } catch (err) {
      setSaving(false)
      setNotice(err instanceof Error ? err.message : '重新分析失败')
    }
  }, [goToAnnotations, numericId, saving])

  const changedCount = Object.keys(edits).length

  const updateDivider = useCallback((patch: Partial<DividerStyle>) => {
    setDivider(current => {
      const next = { ...current, ...patch }
      try {
        localStorage.setItem(DIVIDER_KEY, JSON.stringify(next))
      } catch {
        // 存不了就只是这次生效
      }
      return next
    })
  }, [])
  // 保存后文件名会变成 edited.txt，但这里展示的还是原来那份简历的名字
  const displayName = fileName.replace(/\.[^.]+$/, '')

  if (mode === 'error') {
    return (
      <div className="flex h-screen items-center justify-center bg-white">
        <div className="card max-w-sm p-8 text-center">
          <p className="text-sm text-slate-600">{error}</p>
          <Link to="/resumes" className="btn-ghost mt-4">
            返回简历库
          </Link>
        </div>
      </div>
    )
  }

  if (mode === 'text') {
    return <StructuredEditor resumeId={numericId} reason={reason} />
  }

  return (
    <div className="relative flex h-screen flex-col bg-slate-100">
      <header className="flex h-14 shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-5">
        <Link
          to="/resumes"
          className="flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          简历库
        </Link>
        <span className="truncate text-sm font-medium text-slate-800">{displayName || `简历 #${numericId}`}</span>
        <span className="hidden rounded-md bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-500 sm:inline">
          点一块就能整块改
        </span>
        {changedCount > 0 && (
          <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-600">
            已修改 {changedCount} 处
          </span>
        )}

        <div className="ml-auto flex items-center gap-2">
          <div className="relative">
            <button
              type="button"
              onClick={() => setLayoutOpen(open => !open)}
              className="btn-ghost border border-slate-200 text-slate-600"
              title="间隔条样式（全局）"
            >
              <Palette className="h-4 w-4" />
              版式
            </button>
            {layoutOpen && (
              <>
                <div className="fixed inset-0 z-30" onClick={() => setLayoutOpen(false)} />
                <div className="absolute right-0 top-11 z-40 w-64 rounded-xl border border-slate-200 bg-white p-3 shadow-card">
                  <div className="mb-2 text-[11px] text-slate-400">
                    板块标题的间隔条，全局统一
                  </div>
                  <label className="mb-2 flex items-center justify-between text-xs text-slate-600">
                    底色
                    <input
                      type="color"
                      value={divider.background}
                      onChange={event => updateDivider({ background: event.target.value })}
                      className="h-6 w-10 cursor-pointer rounded border border-slate-200 bg-transparent p-0.5"
                    />
                  </label>
                  <label className="mb-2 flex items-center justify-between text-xs text-slate-600">
                    文字颜色
                    <input
                      type="color"
                      value={divider.color}
                      onChange={event => updateDivider({ color: event.target.value })}
                      className="h-6 w-10 cursor-pointer rounded border border-slate-200 bg-transparent p-0.5"
                    />
                  </label>
                  <label className="mb-2 flex items-center justify-between text-xs text-slate-600">
                    字号
                    <select
                      value={divider.fontSize}
                      onChange={event => updateDivider({ fontSize: Number(event.target.value) })}
                      className="rounded-md border border-slate-200 px-1.5 py-0.5 text-xs text-slate-600"
                    >
                      {[11, 12, 13, 14, 15, 16, 18, 20].map(size => (
                        <option key={size} value={size}>
                          {size} pt
                        </option>
                      ))}
                    </select>
                  </label>
                  <div
                    className="mb-2 flex h-7 items-center rounded px-2 text-xs font-semibold"
                    style={{
                      background: divider.background,
                      color: divider.color,
                      fontSize: divider.fontSize
                    }}
                  >
                    实习经历
                  </div>
                  <button
                    type="button"
                    onClick={() => updateDivider(DEFAULT_DIVIDER)}
                    className="w-full rounded-lg border border-slate-200 py-1 text-[11px] text-slate-500 hover:bg-slate-50"
                  >
                    恢复默认
                  </button>
                </div>
              </>
            )}
          </div>
          {changedCount > 0 && (
            <button
              type="button"
              onClick={() => handleEditsChange({})}
              className="btn-ghost border border-slate-200 text-slate-500"
            >
              <RotateCcw className="h-4 w-4" />
              还原
            </button>
          )}
          <button
            type="button"
            onClick={() => void reanalyze()}
            disabled={saving}
            className="btn-ghost border border-slate-200 text-slate-600 disabled:opacity-60"
          >
            <RefreshCw className={`h-4 w-4 ${saving ? 'animate-spin' : ''}`} />
            重新分析
          </button>
          <button type="button" onClick={() => void saveAndReanalyze()} disabled={saving} className="btn-primary">
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            {saving ? '保存中…' : '保存并重新分析'}
          </button>
        </div>
      </header>

      {notice && (
        <div className="absolute left-1/2 top-16 z-30 flex -translate-x-1/2 items-center gap-1.5 rounded-lg bg-white px-3 py-1.5 text-xs text-slate-600 shadow-card">
          {notice.startsWith('已保存') && <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />}
          {notice}
        </div>
      )}

      <div className="min-h-0 flex-1">
        {mode === 'loading' || !layer ? (
          <div className="flex h-full items-center justify-center text-sm text-slate-400">
            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            正在渲染原版 PDF…
          </div>
        ) : (
          <PdfEditSurface
            ref={surfaceRef}
            resumeId={numericId as number}
            layer={layer}
            edits={edits}
            onEditsChange={handleEditsChange}
            divider={divider}
          />
        )}
      </div>
    </div>
  )
}
