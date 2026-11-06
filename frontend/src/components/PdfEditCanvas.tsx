import { forwardRef, useCallback, useEffect, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  Bold,
  Check,
  ImagePlus,
  Italic,
  Palette,
  RemoveFormatting,
  Underline,
  Type,
  X
} from 'lucide-react'
import { resumeApi } from '@/api/resume'
import { htmlToPlainText, pageRightOf, toSeedHtml } from '@/utils/pdfSection'
import type { TextLayerView, TextSection } from '@/types/api'

/**
 * PDF 板块编辑器。
 *
 * 底图是原文件渲染的位图，页面看起来和上传的 PDF 一模一样；点某一页上的板块（实习经历、
 * 教育背景……），**下面弹出一个编辑框**整块改，不在原位置动文字。原处只留一个高亮框告诉你
 * 现在改的是哪一块，改过的块打个「已编辑」标。
 *
 * 为什么不原位改：PDF 的排版是死的，就地长出来的富文本框会盖住下面的内容，
 * 而且宽度只有一栏，调字号就挤成一团。挪到下面给足宽度，才像在 Word 里改。
 */

export interface PdfEditSurfaceHandle {
  /** 按阅读顺序拼出正文（包含还没提交的编辑框内容） */
  getText(): string
  getEdits(): Record<string, string>
}

interface Props {
  resumeId: number
  layer: TextLayerView
  edits: Record<string, string>
  onEditsChange: (edits: Record<string, string>) => void
}

const FONT_SIZES = [10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32]
const FONT_FAMILIES = [
  { label: '微软雅黑', value: '"Microsoft YaHei", "PingFang SC", sans-serif' },
  { label: '宋体', value: 'SimSun, "Songti SC", serif' },
  { label: '黑体', value: 'SimHei, "Heiti SC", sans-serif' },
  { label: '楷体', value: 'KaiTi, "Kaiti SC", serif' },
  { label: '等线', value: 'DengXian, "PingFang SC", sans-serif' }
]

const PdfEditCanvas = forwardRef<PdfEditSurfaceHandle, Props>(function PdfEditCanvas(
  { resumeId, layer, edits, onEditsChange },
  ref
) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const editorRef = useRef<HTMLDivElement | null>(null)
  const partRefs = useRef(new Map<number, HTMLDivElement>())
  const savedRange = useRef<Range | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  /** 打开中的编辑框内容：还没点「完成」也算数，保存时不能丢 */
  const draft = useRef<{ index: number | null; html: string }>({ index: null, html: '' })

  const [zoom, setZoom] = useState(1)
  const [active, setActive] = useState<number | null>(null)
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

  /** 每一块没被改过时的初始 HTML（按原文排版还原） */
  const seeds = useMemo(() => {
    const right = new Map<number, number>()
    layer.pages.forEach(page => {
      const lines = layer.sections
        .flatMap(section => section.lines)
        .filter(line => line.page === page.index)
      right.set(page.index, pageRightOf(lines))
    })
    const result = new Map<number, string>()
    layer.sections.forEach(section => {
      const pageOf = section.lines[0]?.page ?? 1
      result.set(section.index, toSeedHtml(section.lines, right.get(pageOf) ?? 0))
    })
    return result
  }, [layer.pages, layer.sections])

  const htmlOf = useCallback(
    (index: number) => edits[String(index)] ?? seeds.get(index) ?? '',
    [edits, seeds]
  )

  // 打开编辑框：把这一块现在的 HTML 灌进去
  useEffect(() => {
    const node = editorRef.current
    if (active === null || !node) {
      return
    }
    const html = htmlOf(active)
    node.innerHTML = html
    draft.current = { index: active, html }
    node.focus()
    // 光标放到开头，别一打开就滚到底
    const range = document.createRange()
    range.selectNodeContents(node)
    range.collapse(true)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    savedRange.current = range.cloneRange()

    // 把这一块滚到编辑框上方可见的位置（编辑框占了下面近一半高度）
    const scroller = containerRef.current
    const part = partRefs.current.get(active)
    if (scroller && part?.isConnected) {
      const offset = part.getBoundingClientRect().top - scroller.getBoundingClientRect().top
      scroller.scrollTo({ top: Math.max(0, scroller.scrollTop + offset - 40), behavior: 'smooth' })
    }
  }, [active, htmlOf])

  const commitDraft = useCallback(() => {
    const { index, html } = draft.current
    if (index === null) {
      return
    }
    const seed = (seeds.get(index) ?? '').trim()
    const value = html.trim()
    const next = { ...edits }
    if (!value || value === seed) {
      delete next[String(index)]
    } else {
      next[String(index)] = value
    }
    onEditsChange(next)
    draft.current = { index: null, html: '' }
  }, [edits, onEditsChange, seeds])

  const close = useCallback(
    (commit: boolean) => {
      if (commit) {
        commitDraft()
      } else {
        draft.current = { index: null, html: '' }
      }
      setActive(null)
    },
    [commitDraft]
  )

  // Esc 关编辑器
  useEffect(() => {
    if (active === null) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close(true)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [active, close])

  // 点工具栏会让输入框失焦，先把选区存下来
  useEffect(() => {
    const onSelectionChange = () => {
      const selection = window.getSelection()
      if (!selection || selection.rangeCount === 0 || !editorRef.current) {
        return
      }
      const range = selection.getRangeAt(0)
      if (editorRef.current.contains(range.commonAncestorContainer)) {
        savedRange.current = range.cloneRange()
      }
    }
    document.addEventListener('selectionchange', onSelectionChange)
    return () => document.removeEventListener('selectionchange', onSelectionChange)
  }, [])

  const restoreRange = useCallback((): Range | null => {
    const selection = window.getSelection()
    const stored = savedRange.current
    if (!selection || !stored || !stored.commonAncestorContainer.isConnected) {
      return null
    }
    selection.removeAllRanges()
    selection.addRange(stored)
    return stored
  }, [])

  const syncDraft = useCallback(() => {
    const node = editorRef.current
    if (node && draft.current.index !== null) {
      draft.current = { index: draft.current.index, html: node.innerHTML }
    }
  }, [])

  /** 把样式包到选区上；不走 execCommand，所以工具栏失焦也能用 */
  const styleSelection = useCallback(
    (style: Record<string, string>) => {
      const node = editorRef.current
      if (!node || active === null) {
        return
      }
      const range = restoreRange()
      if (!range) {
        return
      }
      // 没选中任何字 → 改整块（用户说的"这一大块"，而不是逐句调）
      if (range.collapsed) {
        const blocks = node.querySelectorAll<HTMLElement>(':scope > div, :scope > p, :scope > li')
        const targets: HTMLElement[] = blocks.length > 0 ? Array.from(blocks) : [node]
        targets.forEach(element => {
          Object.entries(style).forEach(([key, value]) => element.style.setProperty(key, value))
        })
        syncDraft()
        return
      }
      const span = document.createElement('span')
      Object.entries(style).forEach(([key, value]) => {
        span.style.setProperty(key, value)
      })
      span.appendChild(range.extractContents())
      range.insertNode(span)

      const selection = window.getSelection()
      const restored = document.createRange()
      restored.selectNodeContents(span)
      selection?.removeAllRanges()
      selection?.addRange(restored)
      savedRange.current = restored.cloneRange()
      syncDraft()
    },
    [active, restoreRange, syncDraft]
  )

  const insertImage = useCallback(
    (file: File) => {
      const node = editorRef.current
      if (!node || active === null) {
        return
      }
      const reader = new FileReader()
      reader.onload = () => {
        const image = document.createElement('img')
        image.src = String(reader.result)
        image.style.maxWidth = '100%'
        image.style.display = 'block'
        image.style.margin = '6px 0'

        const range = restoreRange()
        if (range) {
          range.deleteContents()
          range.insertNode(image)
          range.setStartAfter(image)
          range.collapse(true)
        } else {
          node.appendChild(image)
        }
        syncDraft()
      }
      reader.readAsDataURL(file)
    },
    [active, restoreRange, syncDraft]
  )

  useImperativeHandle(
    ref,
    () => ({
      getText() {
        return layer.sections
          .map(section => {
            const key = String(section.index)
            // 正在编辑的那块以编辑框里的内容为准
            const html =
              draft.current.index === section.index
                ? draft.current.html
                : edits[key] ?? seeds.get(section.index) ?? ''
            const seed = (seeds.get(section.index) ?? '').trim()
            if (html.trim() && html.trim() !== seed) {
              return htmlToPlainText(html)
            }
            return section.lines
              .map(line => line.text.replace(/\s+/g, ' ').trim())
              .filter(Boolean)
              .join('\n')
          })
          .filter(Boolean)
          .join('\n\n')
      },
      getEdits() {
        const result: Record<string, string> = { ...edits }
        const { index, html } = draft.current
        if (index !== null) {
          const seed = (seeds.get(index) ?? '').trim()
          if (html.trim() && html.trim() !== seed) {
            result[String(index)] = html
          } else {
            delete result[String(index)]
          }
        }
        return result
      }
    }),
    [edits, layer.sections, seeds]
  )

  const activeSection: TextSection | null =
    active === null ? null : layer.sections.find(item => item.index === active) ?? null

  const fitWidth = useCallback(() => {
    const element = containerRef.current
    if (element) {
      setZoom(Math.min(1.8, Math.max(0.5, (element.clientWidth - 96) / baseWidth)))
    }
  }, [baseWidth])

  return (
    <div className="relative flex h-full flex-col">
      <div
        ref={containerRef}
        className="min-h-0 flex-1 overflow-auto bg-slate-100 px-6 py-8"
        style={{ paddingBottom: active === null ? undefined : '4rem' }}
      >
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

              {layer.sections.map(section => {
                const parts = section.parts.filter(part => part.page === page.index)
                if (parts.length === 0) {
                  return null
                }
                const isActive = active === section.index
                const edited = edits[String(section.index)] !== undefined
                return parts.map((part, order) => (
                  <div
                    key={`${section.index}-${part.page}-${order}`}
                    ref={node => {
                      // 只记这一块的第一段：点开后要滚到它开头的位置
                      if (node && !partRefs.current.has(section.index)) {
                        partRefs.current.set(section.index, node)
                      }
                    }}
                    className="absolute"
                    style={{
                      left: part.x * zoom,
                      top: part.y * zoom,
                      width: part.width * zoom,
                      height: part.height * zoom
                    }}
                  >
                    <button
                      type="button"
                      onClick={() => {
                        if (isActive) {
                          return
                        }
                        if (active !== null) {
                          commitDraft()
                        }
                        setActive(section.index)
                      }}
                      title={`${section.title} · 点击编辑`}
                      className={`group relative block h-full w-full rounded-[3px] text-left transition-colors ${
                        isActive
                          ? 'bg-indigo-500/[0.07] ring-2 ring-indigo-500'
                          : edited
                            ? 'bg-indigo-500/[0.04] ring-1 ring-indigo-300 hover:bg-indigo-500/10'
                            : 'hover:bg-indigo-500/10 hover:ring-1 hover:ring-indigo-400'
                      }`}
                    >
                      {(edited || isActive) && order === 0 && (
                        <span
                          className={`pointer-events-none absolute -top-2 left-0 rounded px-1.5 py-0.5 text-[10px] font-medium text-white ${
                            isActive ? 'bg-indigo-600' : 'bg-indigo-400'
                          }`}
                        >
                          {isActive ? `正在编辑 · ${section.title}` : `已编辑 · ${section.title}`}
                        </span>
                      )}
                      {!edited && !isActive && (
                        <span className="pointer-events-none absolute -top-2 left-0 rounded bg-indigo-500 px-1.5 py-0.5 text-[10px] font-medium text-white opacity-0 transition-opacity group-hover:opacity-100">
                          {section.title} · 点击编辑
                        </span>
                      )}
                    </button>
                  </div>
                ))
              })}
            </div>
          ))}
        </div>
      </div>

      {active === null && (
        <div className="absolute bottom-5 right-5 z-10 flex items-center gap-1 rounded-xl border border-slate-200 bg-white/95 px-2 py-1.5 shadow-card">
          <ZoomControls zoom={zoom} onZoom={setZoom} onFit={fitWidth} />
        </div>
      )}

      {activeSection && (
        <div className="absolute inset-x-0 bottom-0 z-20 flex h-[48%] flex-col border-t border-slate-200 bg-white shadow-[0_-8px_30px_rgba(15,23,42,0.12)]">
          <div className="flex flex-wrap items-center gap-1 border-b border-slate-100 px-3 py-2">
            <span className="mr-1 text-xs font-medium text-slate-700">{activeSection.title}</span>
            <span className="mr-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">
              {activeSection.lines.length} 行 · 整块编辑
            </span>
            <ToolbarButton title="加粗" onClick={() => styleSelection({ 'font-weight': '600' })}>
              <Bold className="h-3.5 w-3.5" />
            </ToolbarButton>
            <ToolbarButton title="斜体" onClick={() => styleSelection({ 'font-style': 'italic' })}>
              <Italic className="h-3.5 w-3.5" />
            </ToolbarButton>
            <ToolbarButton title="下划线" onClick={() => styleSelection({ 'text-decoration': 'underline' })}>
              <Underline className="h-3.5 w-3.5" />
            </ToolbarButton>

            <span className="mx-1 h-4 w-px bg-slate-200" />

            <label
              className="flex items-center gap-1 text-[11px] text-slate-500"
              title="选中文字改选中的部分；没选中就改整块"
            >
              <Type className="h-3.5 w-3.5" />
              <select
                value=""
                onChange={event => {
                  const value = event.target.value
                  if (value) {
                    styleSelection({ 'font-size': `${value}px` })
                  }
                  event.target.value = ''
                }}
                className="rounded-md border border-slate-200 bg-white px-1 py-0.5 text-[11px] text-slate-600"
              >
                <option value="">字号</option>
                {FONT_SIZES.map(size => (
                  <option key={size} value={size}>
                    {size}
                  </option>
                ))}
              </select>
            </label>

            <select
              value=""
              onChange={event => {
                const value = event.target.value
                if (value) {
                  styleSelection({ 'font-family': value })
                }
                event.target.value = ''
              }}
              className="rounded-md border border-slate-200 bg-white px-1 py-0.5 text-[11px] text-slate-600"
            >
              <option value="">字体</option>
              {FONT_FAMILIES.map(item => (
                <option key={item.label} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>

            <label className="flex cursor-pointer items-center gap-1 rounded-md px-1.5 py-1 text-[11px] text-slate-500 hover:bg-slate-100">
              <Palette className="h-3.5 w-3.5" />
              <input
                type="color"
                defaultValue="#1f2937"
                onChange={event => styleSelection({ color: event.target.value })}
                className="h-4 w-5 cursor-pointer border-0 bg-transparent p-0"
              />
            </label>

            <span className="mx-1 h-4 w-px bg-slate-200" />

            <ToolbarButton title="插入图片" onClick={() => fileInputRef.current?.click()}>
              <ImagePlus className="h-3.5 w-3.5" />
            </ToolbarButton>
            <ToolbarButton
              title="清除格式"
              onClick={() =>
                styleSelection({
                  'font-weight': '400',
                  'font-style': 'normal',
                  'text-decoration': 'none',
                  color: '#1f2937'
                })
              }
            >
              <RemoveFormatting className="h-3.5 w-3.5" />
            </ToolbarButton>

            <div className="ml-auto flex items-center gap-1.5">
              <div className="mr-1 hidden items-center gap-0.5 rounded-lg border border-slate-200 px-1 py-0.5 sm:flex">
                <ZoomControls zoom={zoom} onZoom={setZoom} onFit={fitWidth} compact />
              </div>
              <button
                type="button"
                onClick={() => close(false)}
                className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-slate-500 hover:bg-slate-100"
              >
                <X className="h-3.5 w-3.5" />
                取消
              </button>
              <button
                type="button"
                onClick={() => close(true)}
                className="flex items-center gap-1 rounded-lg bg-indigo-600 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-indigo-700"
              >
                <Check className="h-3.5 w-3.5" />
                完成
              </button>
            </div>

            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={event => {
                const file = event.target.files?.[0]
                if (file) {
                  insertImage(file)
                }
                event.target.value = ''
              }}
            />
          </div>

          <div
            ref={editorRef}
            contentEditable
            suppressContentEditableWarning
            spellCheck={false}
            onInput={syncDraft}
            className="min-h-0 flex-1 overflow-auto px-6 py-4 text-slate-800 outline-none"
            style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
          />
        </div>
      )}
    </div>
  )
})

function ZoomControls({
  zoom,
  onZoom,
  onFit,
  compact = false
}: {
  zoom: number
  onZoom: (update: (value: number) => number) => void
  onFit: () => void
  compact?: boolean
}) {
  const step = compact ? 'h-5 w-5 text-[13px]' : 'h-7 w-7'
  return (
    <>
      <button
        type="button"
        onClick={() => onZoom(value => Math.max(0.4, Math.round((value - 0.1) * 100) / 100))}
        className={`${step} rounded-md text-slate-500 hover:bg-slate-100`}
      >
        −
      </button>
      <span className={`${compact ? 'w-9' : 'w-12'} text-center text-xs text-slate-500`}>
        {Math.round(zoom * 100)}%
      </span>
      <button
        type="button"
        onClick={() => onZoom(value => Math.min(2.4, Math.round((value + 0.1) * 100) / 100))}
        className={`${step} rounded-md text-slate-500 hover:bg-slate-100`}
      >
        +
      </button>
      <button
        type="button"
        onClick={onFit}
        className="ml-1 rounded-md px-1.5 py-0.5 text-xs text-slate-500 hover:bg-slate-100"
      >
        适应宽度
      </button>
    </>
  )
}

function ToolbarButton({
  title,
  onClick,
  children
}: {
  title: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      title={title}
      // 不让工具栏抢走焦点，否则选区就没了
      onMouseDown={event => event.preventDefault()}
      onClick={onClick}
      className="rounded-md p-1.5 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700"
    >
      {children}
    </button>
  )
}

export default PdfEditCanvas
