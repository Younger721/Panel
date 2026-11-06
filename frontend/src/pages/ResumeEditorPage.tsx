import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { resumeApi } from '@/api/resume'
import { buildEditorDocument, type EditorDocument } from '@/utils/resumeDocument'

/**
 * 简历编辑器。
 *
 * 编辑器本身是一份自包含的静态页（public/resume-editor.html），这里用 iframe 挂进工作台：
 * - 不重写它的排版/撤销/导出逻辑
 * - 带 `:resumeId` 时，从简历库选中的简历里取出 blocks，映射成编辑器文档后推给 iframe
 * - 每份简历存在自己的 localStorage 键下（nova-resume-editor:v1:resume-<id>），
 *   不会覆盖编辑器默认草稿
 */
export default function ResumeEditorPage() {
  const { resumeId } = useParams()
  const numericId = resumeId ? Number(resumeId) : null

  const [loading, setLoading] = useState(Boolean(numericId))
  const [error, setError] = useState<string | null>(null)

  const frameRef = useRef<HTMLIFrameElement | null>(null)
  const documentRef = useRef<EditorDocument | null>(null)
  const readyRef = useRef(false)
  const sentRef = useRef(false)

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

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin || event.data?.type !== 'nova:editor:ready') {
        return
      }
      readyRef.current = true
      deliver()
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [deliver])

  return (
    <div className="relative h-screen w-full overflow-hidden bg-white">
      <iframe
        ref={frameRef}
        title="简历编辑器"
        src="/resume-editor.html?embed=1"
        className="h-full w-full border-0"
      />

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
