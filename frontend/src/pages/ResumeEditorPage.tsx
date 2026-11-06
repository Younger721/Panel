import { useEffect, useRef, useState } from 'react'

/**
 * 简历编辑器。
 *
 * 编辑器本身是一份自包含的静态页（public/resume-editor.html，约 2800 行，
 * 内置排版、撤销栈、模板与导出逻辑），这里用 iframe 挂进工作台：
 * 不重写它的状态机，但保留侧栏、登录态与路由。/resume-editor.html 仍可单独打开。
 */
export default function ResumeEditorPage() {
  const [ready, setReady] = useState(false)
  const frameRef = useRef<HTMLIFrameElement | null>(null)

  useEffect(() => {
    const frame = frameRef.current
    if (!frame) {
      return
    }
    const onLoad = () => setReady(true)
    frame.addEventListener('load', onLoad)
    return () => frame.removeEventListener('load', onLoad)
  }, [])

  return (
    <div className="relative h-screen w-full overflow-hidden bg-white">
      {!ready && (
        <div className="absolute inset-0 z-10 flex items-center justify-center bg-white text-sm text-slate-400">
          正在加载编辑器…
        </div>
      )}
      <iframe
        ref={frameRef}
        title="简历编辑器"
        src="/resume-editor.html?embed=1"
        className="h-full w-full border-0"
      />
    </div>
  )
}
