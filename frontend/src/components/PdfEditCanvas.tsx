import { forwardRef, useCallback, useEffect, useImperativeHandle, useLayoutEffect, useRef, useState } from 'react'
import { resumeApi } from '@/api/resume'
import type { TextLayerView, TextLine } from '@/types/api'

/**
 * PDF 原位编辑器。
 *
 * 底图是原文件渲染出来的页面位图，所以版式和上传的 PDF 一模一样；文字层是按 PDF
 * 坐标叠上去的可编辑片段，默认透明（看见的就是原图像素），点一下才显形变成白底黑字。
 */

export interface PdfEditSurfaceHandle {
  /** 按阅读顺序拼出当前正文（直接读 DOM，避免 state 还没提交） */
  getText(): string
  getEdits(): Record<number, string>
}

interface Props {
  resumeId: number
  layer: TextLayerView
  edits: Record<number, string>
  onEditsChange: (edits: Record<number, string>) => void
}

const PdfEditCanvas = forwardRef<PdfEditSurfaceHandle, Props>(function PdfEditCanvas(
  { resumeId, layer, edits, onEditsChange },
  ref
) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const lineRefs = useRef(new Map<number, HTMLDivElement>())
  const [zoom, setZoom] = useState(1)
  const [editing, setEditing] = useState<number | null>(null)
  const [images, setImages] = useState<Record<number, string>>({})

  const baseWidth = layer.pages[0]?.width ?? 595

  // 适应宽度：容器多宽就缩放到多宽，肉眼看到的就是一页纸
  useLayoutEffect(() => {
    const element = containerRef.current
    if (!element) {
      return
    }
    const fit = () => {
      const available = element.clientWidth - 96
      if (available > 0) {
        setZoom(Math.min(1.8, Math.max(0.5, available / baseWidth)))
      }
    }
    fit()
    const observer = new ResizeObserver(fit)
    observer.observe(element)
    return () => observer.disconnect()
  }, [baseWidth])

  // 逐页取位图；CSS 尺寸按页面点尺寸算，位图 2.5 倍所以放大也清楚
  useEffect(() => {
    let cancelled = false
    const urls: string[] = []
    void (async () => {
      for (const page of layer.pages) {
        try {
          const blob = await resumeApi.pageImage(resumeId, page.index, 2.5)
          if (cancelled) {
            return
          }
          const url = URL.createObjectURL(blob)
          urls.push(url)
          setImages(current => ({ ...current, [page.index]: url }))
        } catch {
          // 单页失败不影响其它页
        }
      }
    })()
    return () => {
      cancelled = true
      urls.forEach(url => URL.revokeObjectURL(url))
    }
  }, [layer.pages, resumeId])

  const commit = useCallback(
    (index: number, value: string, original: string) => {
      const next = { ...edits }
      const trimmed = value.replace(/\s+/g, ' ').trim()
      if (trimmed === original.replace(/\s+/g, ' ').trim()) {
        delete next[index]
      } else {
        next[index] = trimmed
      }
      onEditsChange(next)
    },
    [edits, onEditsChange]
  )

  const readLine = useCallback(
    (index: number, line: TextLine) => {
      const node = lineRefs.current.get(index)
      const value = node ? node.innerText : edits[index] ?? line.text
      return value.replace(/\s+/g, ' ').trim()
    },
    [edits]
  )

  useImperativeHandle(
    ref,
    () => ({
      getText() {
        return layer.lines
          .map((line, index) => readLine(index, line))
          .filter(Boolean)
          .join('\n')
      },
      getEdits() {
        const result: Record<number, string> = {}
        layer.lines.forEach((line, index) => {
          const value = readLine(index, line)
          if (value !== line.text.replace(/\s+/g, ' ').trim()) {
            result[index] = value
          }
        })
        return result
      }
    }),
    [layer.lines, readLine]
  )

  const linesByPage = new Map<number, { line: TextLine; index: number }[]>()
  layer.lines.forEach((line, index) => {
    const bucket = linesByPage.get(line.page) ?? []
    bucket.push({ line, index })
    linesByPage.set(line.page, bucket)
  })

  return (
    <div className="relative h-full">
      <div ref={containerRef} className="h-full overflow-auto bg-slate-100 px-6 py-8">
        <div className="mx-auto flex flex-col items-center gap-6" style={{ width: baseWidth * zoom }}>
          {layer.pages.map(page => (
            <div
              key={page.index}
              className="relative shrink-0 bg-white shadow-paper"
              style={{ width: page.width * zoom, height: page.height * zoom }}
            >
              {images[page.index] ? (
                <img
                  src={images[page.index]}
                  alt={`第 ${page.index} 页`}
                  className="pointer-events-none absolute inset-0 select-none"
                  style={{ width: '100%', height: '100%' }}
                  draggable={false}
                />
              ) : (
                <div className="absolute inset-0 animate-pulse bg-slate-50" />
              )}

              {(linesByPage.get(page.index) ?? []).map(({ line, index }) => {
                const active = editing === index
                const changed = edits[index] !== undefined
                const value = edits[index] ?? line.text
                return (
                  <div
                    key={index}
                    ref={node => {
                      if (node) {
                        lineRefs.current.set(index, node)
                        // 没在编辑的行由 state 驱动；编辑中的行交给浏览器，避免光标跳动
                        if (!active && node.innerText !== value) {
                          node.textContent = value
                        }
                      } else {
                        lineRefs.current.delete(index)
                      }
                    }}
                    data-line={index}
                    contentEditable
                    suppressContentEditableWarning
                    spellCheck={false}
                    onFocus={() => setEditing(index)}
                    onBlur={event => {
                      commit(index, event.currentTarget.innerText, line.text)
                      setEditing(null)
                    }}
                    style={{
                      left: line.x * zoom,
                      top: line.y * zoom,
                      minWidth: line.width * zoom,
                      height: line.height * zoom,
                      fontSize: line.size * zoom,
                      lineHeight: `${line.height * zoom}px`
                    }}
                    className={[
                      'absolute cursor-text whitespace-pre rounded-[2px] transition-colors',
                      active
                        ? 'z-20 bg-white text-slate-900 outline outline-1 outline-indigo-400'
                        : changed
                          ? 'z-10 bg-white text-slate-900 outline outline-1 outline-dashed outline-amber-300'
                          : 'z-0 text-transparent hover:bg-indigo-500/10 hover:outline hover:outline-1 hover:outline-indigo-300'
                    ].join(' ')}
                  >
                    {value}
                  </div>
                )
              })}
            </div>
          ))}
        </div>
      </div>

      <div className="absolute bottom-5 right-5 flex items-center gap-1 rounded-xl border border-slate-200 bg-white/95 px-2 py-1.5 shadow-card">
        <button
          type="button"
          onClick={() => setZoom(value => Math.max(0.4, Math.round((value - 0.1) * 100) / 100))}
          className="h-7 w-7 rounded-lg text-slate-500 hover:bg-slate-100"
        >
          −
        </button>
        <span className="w-12 text-center text-xs text-slate-500">{Math.round(zoom * 100)}%</span>
        <button
          type="button"
          onClick={() => setZoom(value => Math.min(2.4, Math.round((value + 0.1) * 100) / 100))}
          className="h-7 w-7 rounded-lg text-slate-500 hover:bg-slate-100"
        >
          +
        </button>
        <button
          type="button"
          onClick={() => {
            const element = containerRef.current
            if (element) {
              setZoom(Math.min(1.8, Math.max(0.5, (element.clientWidth - 96) / baseWidth)))
            }
          }}
          className="ml-1 rounded-lg px-2 py-1 text-xs text-slate-500 hover:bg-slate-100"
        >
          适应宽度
        </button>
      </div>
    </div>
  )
})

export default PdfEditCanvas
