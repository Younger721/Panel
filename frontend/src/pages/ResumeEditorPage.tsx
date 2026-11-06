import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, CheckCircle2, Loader2, RefreshCw, RotateCcw, Save } from 'lucide-react'
import { resumeApi } from '@/api/resume'
import PdfEditSurface, { type PdfEditSurfaceHandle } from '@/components/PdfEditCanvas'
import StructuredEditor from '@/components/StructuredEditor'
import type { TextLayerView } from '@/types/api'

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
  const [edits, setEdits] = useState<Record<number, string>>({})
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

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
        if (view.available && view.lines.length > 0) {
          setLayer(view)
          setReason(null)
          setMode('pdf')
          // 服务端存着上次保存的行改动（换设备也在），本地草稿只覆盖它
          const restored: Record<number, string> = {}
          Object.entries(view.edits ?? {}).forEach(([key, value]) => {
            const index = Number(key)
            if (Number.isInteger(index) && typeof value === 'string') {
              restored[index] = value
            }
          })
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
    (next: Record<number, string>) => {
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
      const lineEdits = surfaceRef.current?.getEdits() ?? {}
      const payload: Record<string, string> = {}
      Object.entries(lineEdits).forEach(([key, value]) => {
        payload[String(key)] = value
      })
      await resumeApi.saveContent(numericId, text, undefined, payload)
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
          点一下文字就能改
        </span>
        {changedCount > 0 && (
          <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-600">
            已修改 {changedCount} 处
          </span>
        )}

        <div className="ml-auto flex items-center gap-2">
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
          />
        )}
      </div>
    </div>
  )
}
