import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { CheckCircle2, Loader2, Save } from 'lucide-react'
import { resumeApi } from '@/api/resume'
import { buildEditorDocument, type EditorDocument } from '@/utils/resumeDocument'

/**
 * 简历编辑器。
 *
 * 编辑器本身是一份自包含的静态页（public/resume-editor.html），这里用 iframe 挂进工作台：
 * - 不重写它的排版/撤销/导出逻辑
 * - 带 `:resumeId` 时，从简历库选中的简历里取出 blocks，映射成编辑器文档后推给 iframe
 * - 每份简历存在自己的 localStorage 键下（nova-resume-editor:v1:resume-<id>），不会覆盖默认草稿
 * - 「保存并重新分析」把编辑器里的纯文本回写后端，走一遍解析 → 批注
 */
export default function ResumeEditorPage() {
  const { resumeId } = useParams()
  const navigate = useNavigate()
  const numericId = resumeId ? Number(resumeId) : null

  const [loading, setLoading] = useState(Boolean(numericId))
  const [error, setError] = useState<string | null>(null)
  const [warning, setWarning] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const frameRef = useRef<HTMLIFrameElement | null>(null)
  const documentRef = useRef<EditorDocument | null>(null)
  const readyRef = useRef(false)
  const sentRef = useRef(false)
  const saveRequestRef = useRef<string | null>(null)

  const storageKey = numericId ? `nova-resume-editor:v1:resume-${numericId}` : undefined

  /** 编辑器就绪且文档已备好时推一次；两个条件谁先到都能触发 */
  const deliver = useCallback(() => {
    const frame = frameRef.current
    const document = documentRef.current
    if (sentRef.current || !readyRef.current || !frame?.contentWindow || !document) {
      return
    }
    frame.contentWindow.postMessage(
      { type: 'nova:editor:load', document, storageKey },
      window.location.origin
    )
    sentRef.current = true
    setLoading(false)
  }, [storageKey])

  useEffect(() => {
    if (!numericId) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    setWarning(null)
    sentRef.current = false
    documentRef.current = null

    resumeApi
      .analysis(numericId)
      .then(view => {
        if (cancelled) {
          return
        }
        const document = buildEditorDocument(view.blocks)
        if (!document.html) {
          setError('这份简历没有解析出正文，先回到简历库重新上传')
          setLoading(false)
          return
        }
        documentRef.current = document
        setWarning(document.warning || null)
        deliver()
      })
      .catch(err => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '读取简历失败')
          setLoading(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [numericId, deliver])

  /** 向编辑器要当前内容，拿到后回写后端 */
  const saveAndReanalyze = useCallback(async (text: string, html: string) => {
    if (!numericId) {
      return
    }
    try {
      await resumeApi.saveContent(numericId, text, html)
      setNotice('已保存，正在重新解析这份简历…')
      window.setTimeout(() => navigate(`/resumes/${numericId}`), 600)
    } catch (err) {
      setSaving(false)
      setNotice(err instanceof Error ? err.message : '保存失败，请重试')
    }
  }, [navigate, numericId])

  const requestSave = useCallback(() => {
    const frame = frameRef.current
    if (!numericId || saving) {
      return
    }
    if (!frame?.contentWindow) {
      setNotice('编辑器还没准备好')
      return
    }
    const requestId = String(Date.now())
    saveRequestRef.current = requestId
    setSaving(true)
    setNotice(null)
    frame.contentWindow.postMessage(
      { type: 'nova:editor:request-document', requestId },
      window.location.origin
    )
    window.setTimeout(() => {
      if (saveRequestRef.current === requestId) {
        saveRequestRef.current = null
        setSaving(false)
        setNotice('编辑器没有响应，请再试一次')
      }
    }, 5000)
  }, [numericId, saving])

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) {
        return
      }
      const data = event.data
      if (data?.type === 'nova:editor:ready') {
        readyRef.current = true
        deliver()
        return
      }
      if (data?.type === 'nova:editor:document' && data.requestId && data.requestId === saveRequestRef.current) {
        saveRequestRef.current = null
        const text = typeof data.text === 'string' ? data.text.trim() : ''
        if (!text) {
          setSaving(false)
          setNotice('编辑器里没有内容可保存')
          return
        }
        void saveAndReanalyze(text, typeof data.html === 'string' ? data.html : '')
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [deliver, saveAndReanalyze])

  useEffect(() => {
    if (!warning) {
      return
    }
    const timer = window.setTimeout(() => setWarning(null), 12000)
    return () => window.clearTimeout(timer)
  }, [warning])

  useEffect(() => {
    if (!notice) {
      return
    }
    const timer = window.setTimeout(() => setNotice(null), 6000)
    return () => window.clearTimeout(timer)
  }, [notice])

  return (
    <div className="relative h-screen w-full overflow-hidden bg-white">
      <iframe
        ref={frameRef}
        title="简历编辑器"
        src="/resume-editor.html?embed=1"
        className="h-full w-full border-0"
      />

      {warning && (
        <div className="pointer-events-none absolute left-1/2 top-16 z-10 -translate-x-1/2 rounded-xl border border-amber-200 bg-amber-50/95 px-4 py-2 text-xs text-amber-700 shadow-card">
          {warning}
        </div>
      )}

      {numericId && !error && (
        <div className="absolute bottom-6 left-6 z-10 flex items-center gap-3">
          <button onClick={requestSave} disabled={saving} className="btn-primary shadow-card disabled:opacity-60">
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            {saving ? '保存中…' : '保存并重新分析'}
          </button>
          {notice && (
            <span className="flex items-center gap-1.5 rounded-lg bg-white/95 px-3 py-1.5 text-xs text-slate-500 shadow-card">
              {notice.startsWith('已保存') && <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />}
              {notice}
            </span>
          )}
        </div>
      )}

      {loading && !error && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-white/85 text-sm text-slate-400">
          正在把简历载入编辑器…
        </div>
      )}

      {error && (
        <div className="absolute inset-0 flex items-center justify-center bg-white/95">
          <div className="card max-w-sm p-8 text-center">
            <p className="text-sm text-slate-600">{error}</p>
            <Link to="/resumes" className="btn-ghost mt-4">
              返回简历库
            </Link>
          </div>
        </div>
      )}
    </div>
  )
}
