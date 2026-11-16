import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { CheckCircle2, FileCode2, Loader2, Save } from 'lucide-react'
import { resumeApi } from '@/api/resume'
import { buildEditorDocument, type EditorDocument } from '@/utils/resumeDocument'

/**
 * 结构化编辑器：DOCX / TXT 这类没有版面的文件走这里。
 *
 * 编辑器本身是自包含静态页（public/resume-editor.html），用 iframe 挂进来，
 * 只负责「把解析出的 blocks 变成编辑器文档推过去」和「把改完的正文要回来」。
 */

interface Props {
  resumeId: number | null
  reason: string | null
  /** 为什么会退到这个编辑器：缺文件、网络异常、没有文字层…… */
  detail?: string | null
  /** 原始文件已不在存储里——这时候唯一能做的就是重新上传 */
  missing?: boolean
  /** 切到 Markdown 内容稿编辑（没有版面的文件用 md 改更快） */
  onEditMarkdown?: () => void
}

export default function StructuredEditor({ resumeId, reason, detail, missing, onEditMarkdown }: Props) {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const frameRef = useRef<HTMLIFrameElement | null>(null)
  const documentRef = useRef<EditorDocument | null>(null)
  const readyRef = useRef(false)
  const sentRef = useRef(false)
  const saveRequestRef = useRef<string | null>(null)

  const storageKey = resumeId ? `panel-resume-editor:v1:resume-${resumeId}` : undefined

  const deliver = useCallback(() => {
    const frame = frameRef.current
    const document = documentRef.current
    if (sentRef.current || !readyRef.current || !frame?.contentWindow || !document) {
      return
    }
    frame.contentWindow.postMessage({ type: 'panel:editor:load', document, storageKey }, window.location.origin)
    sentRef.current = true
    setLoading(false)
  }, [storageKey])

  useEffect(() => {
    if (!resumeId) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    sentRef.current = false
    documentRef.current = null

    resumeApi
      .analysis(resumeId)
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
  }, [resumeId, deliver])

  const requestSave = useCallback(() => {
    const frame = frameRef.current
    if (!resumeId || saving) {
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
    frame.contentWindow.postMessage({ type: 'panel:editor:request-document', requestId }, window.location.origin)
    window.setTimeout(() => {
      if (saveRequestRef.current === requestId) {
        saveRequestRef.current = null
        setSaving(false)
        setNotice('编辑器没有响应，请再试一次')
      }
    }, 5000)
  }, [resumeId, saving])

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) {
        return
      }
      const data = event.data
      if (data?.type === 'panel:editor:ready') {
        readyRef.current = true
        deliver()
        return
      }
      if (data?.type === 'panel:editor:document' && data.requestId && data.requestId === saveRequestRef.current) {
        saveRequestRef.current = null
        const text = typeof data.text === 'string' ? data.text.trim() : ''
        if (!text) {
          setSaving(false)
          setNotice('编辑器里没有内容可保存')
          return
        }
        void (async () => {
          try {
            await resumeApi.saveContent(resumeId as number, text, typeof data.html === 'string' ? data.html : '')
            setNotice('已保存，正在重新解析这份简历…')
            window.setTimeout(() => navigate(`/resumes/${resumeId}`), 600)
          } catch (err) {
            setSaving(false)
            setNotice(err instanceof Error ? err.message : '保存失败，请重试')
          }
        })()
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [deliver, navigate, resumeId])

  useEffect(() => {
    if (!notice) {
      return
    }
    const timer = window.setTimeout(() => setNotice(null), 6000)
    return () => window.clearTimeout(timer)
  }, [notice])

  return (
    <div className="relative h-screen w-full overflow-hidden bg-white">
      <iframe ref={frameRef} title="简历编辑器" src="/resume-editor.html?embed=1" className="h-full w-full border-0" />

      {reason && (
        <div className="absolute left-1/2 top-16 z-10 w-[min(92vw,520px)] -translate-x-1/2 rounded-xl border border-amber-200 bg-amber-50/95 px-4 py-3 text-xs text-amber-700 shadow-card">
          <p className="font-medium">{reason}</p>
          {detail && <p className="mt-1 leading-relaxed text-amber-600/90">{detail}</p>}
          {missing && (
            <Link
              to="/resumes"
              className="mt-2 inline-block font-medium text-amber-800 underline decoration-amber-300 underline-offset-2 hover:text-amber-900"
            >
              去简历库重新上传 →
            </Link>
          )}
        </div>
      )}

      {resumeId && !error && (
        <div className="absolute bottom-6 left-6 z-10 flex items-center gap-3">
          <button onClick={requestSave} disabled={saving} className="btn-primary shadow-card disabled:opacity-60">
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            {saving ? '保存中…' : '保存并重新分析'}
          </button>
          {onEditMarkdown && (
            <button
              onClick={onEditMarkdown}
              className="btn-ghost border border-slate-200 bg-white text-slate-600 shadow-card"
              title="按 Markdown 改正文：整条经历复制粘贴最快"
            >
              <FileCode2 className="h-4 w-4" />
              Markdown 编辑
            </button>
          )}
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
