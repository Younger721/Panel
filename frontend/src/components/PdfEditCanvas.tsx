import { forwardRef, useCallback, useEffect, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  Bold,
  Check,
  ImagePlus,
  Italic,
  Palette,
  RemoveFormatting,
  Underline,
  Type
} from 'lucide-react'
import { resumeApi } from '@/api/resume'
import { htmlToPlainText, pageRightOf, toSeedHtml } from '@/utils/pdfSection'
import type { TextLayerView, TextSection } from '@/types/api'

/**
 * PDF 板块编辑器。
 *
 * 底图是原文件渲染的位图，所以页面看起来和上传的 PDF 一模一样。点哪一块，哪一块
 * 就变成白底富文本框（整块编辑，不是一行一行改）：字号、字体、颜色、加粗、
 * 插图、回车空格都在这一块里。
 */

export interface PdfEditSurfaceHandle {
  /** 按阅读顺序拼出正文（直接读 DOM，避免 state 还没提交） */
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
  const cardRefs = useRef(new Map<number, HTMLDivElement>())
  const savedRange = useRef<Range | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

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

  /** 每块没被改过时的初始 HTML（按原文排版还原） */
  const seeds = useMemo(() => {
    const byPage = new Map<number, TextSection[]>()
    layer.sections.forEach(section => {
      const list = byPage.get(section.page) ?? []
      list.push(section)
      byPage.set(section.page, list)
    })
    const right = new Map<number, number>()
    byPage.forEach((sections, page) => {
      right.set(page, pageRightOf(sections.flatMap(section => section.lines)))
    })
    const result = new Map<number, string>()
    layer.sections.forEach(section => {
      result.set(section.index, toSeedHtml(section.lines, right.get(section.page) ?? 0))
    })
    return result
  }, [layer.sections])

  const commit = useCallback(
    (index: number) => {
      const node = cardRefs.current.get(index)
      if (!node) {
        return
      }
      const html = node.innerHTML.trim()
      const seed = (seeds.get(index) ?? '').trim()
      const next = { ...edits }
      if (!html || html === seed) {
        delete next[String(index)]
      } else {
        next[String(index)] = html
      }
      onEditsChange(next)
    },
    [edits, onEditsChange, seeds]
  )

  // 点工具栏会让输入框失焦，先把选区存下来
  useEffect(() => {
    const onSelectionChange = () => {
      const selection = window.getSelection()
      if (!selection || selection.rangeCount === 0) {
        return
      }
      const range = selection.getRangeAt(0)
      const node = range.commonAncestorContainer
      const element = node.nodeType === Node.ELEMENT_NODE ? (node as Element) : node.parentElement
      if (element?.closest('[data-section]')) {
        savedRange.current = range.cloneRange()
      }
    }
    document.addEventListener('selectionchange', onSelectionChange)
    return () => document.removeEventListener('selectionchange', onSelectionChange)
  }, [])

  // 点空白处就退出编辑（否则用户得去点"完成"才知道自己已经改完）
  useEffect(() => {
    if (active === null) {
      return
    }
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Element | null
      if (target?.closest('[data-section]') || target?.closest('[data-toolbar]')) {
        return
      }
      cardRefs.current.get(active)?.blur()
    }
    document.addEventListener('mousedown', onPointerDown, true)
    return () => document.removeEventListener('mousedown', onPointerDown, true)
  }, [active])

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

  /** 把样式包到选区上；不走 execCommand，所以工具栏失焦也能用 */
  const styleSelection = useCallback(
    (style: Record<string, string>) => {
      if (active === null) {
        return
      }
      const range = restoreRange()
      const container = cardRefs.current.get(active)
      if (!range || !container) {
        return
      }
      // 没选中任何字 → 改整块（用户说的"这一大块"，而不是逐句调）
      if (range.collapsed) {
        const blocks = container.querySelectorAll<HTMLElement>(':scope > div, :scope > p, :scope > li')
        const targets: HTMLElement[] = blocks.length > 0 ? Array.from(blocks) : [container]
        targets.forEach(element => {
          Object.entries(style).forEach(([key, value]) => element.style.setProperty(key, value))
        })
        commit(active)
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
      commit(active)
    },
    [active, commit, restoreRange]
  )

  const insertImage = useCallback(
    (file: File) => {
      if (active === null) {
        return
      }
      const reader = new FileReader()
      reader.onload = () => {
        const image = document.createElement('img')
        image.src = String(reader.result)
        image.style.maxWidth = '100%'
        image.style.display = 'block'
        image.style.margin = '4px 0'

        const range = restoreRange()
        if (range) {
          range.deleteContents()
          range.insertNode(image)
          range.setStartAfter(image)
          range.collapse(true)
        } else {
          cardRefs.current.get(active)?.appendChild(image)
        }
        commit(active)
      }
      reader.readAsDataURL(file)
    },
    [active, commit, restoreRange]
  )

  useImperativeHandle(
    ref,
    () => ({
      getText() {
        return layer.sections
          .map(section => {
            const key = String(section.index)
            const seed = seeds.get(section.index) ?? ''
            // 以 DOM 为准：刚 blur 的改动 state 还没提交，这里必须读得到
            const node = cardRefs.current.get(section.index)
            const html = node ? node.innerHTML : edits[key]
            if (html !== undefined && html.trim() && html !== seed) {
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
        const result: Record<string, string> = {}
        layer.sections.forEach(section => {
          const key = String(section.index)
          const seed = seeds.get(section.index) ?? ''
          const node = cardRefs.current.get(section.index)
          const html = node ? node.innerHTML.trim() : (edits[key] ?? '').trim()
          if (html && html !== seed.trim()) {
            result[key] = html
          }
        })
        return result
      }
    }),
    [edits, layer.sections, seeds]
  )

  const activeSection = active === null ? null : layer.sections.find(item => item.index === active) ?? null

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

              {layer.sections
                .filter(section => section.page === page.index)
                .map(section => (
                  <SectionCard
                    key={section.index}
                    section={section}
                    zoom={zoom}
                    html={edits[String(section.index)] ?? seeds.get(section.index) ?? ''}
                    edited={edits[String(section.index)] !== undefined}
                    active={active === section.index}
                    register={node => {
                      if (node) {
                        cardRefs.current.set(section.index, node)
                      } else {
                        cardRefs.current.delete(section.index)
                      }
                    }}
                    onActivate={() => setActive(section.index)}
                    onCommit={() => {
                      commit(section.index)
                      setActive(current => (current === section.index ? null : current))
                    }}
                  />
                ))}
            </div>
          ))}
        </div>
      </div>

      {activeSection && (
        <div
          data-toolbar
          className="absolute left-1/2 top-2 z-30 flex -translate-x-1/2 flex-wrap items-center gap-1 rounded-xl border border-slate-200 bg-white/95 px-2 py-1.5 shadow-card"
        >
          <span className="px-1.5 text-[11px] text-slate-400">{activeSection.title}</span>
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

          <label className="flex items-center gap-1 text-[11px] text-slate-500" title="选中文字改选中的部分；没选中就改整块">
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
              styleSelection({ 'font-weight': '400', 'font-style': 'normal', 'text-decoration': 'none', color: '#1f2937' })
            }
          >
            <RemoveFormatting className="h-3.5 w-3.5" />
          </ToolbarButton>
          <ToolbarButton
            title="完成"
            onClick={() => {
              const node = active === null ? null : cardRefs.current.get(active)
              node?.blur()
            }}
          >
            <Check className="h-3.5 w-3.5" />
          </ToolbarButton>

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
      )}

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

interface SectionCardProps {
  section: TextSection
  zoom: number
  html: string
  edited: boolean
  active: boolean
  register: (node: HTMLDivElement | null) => void
  onActivate: () => void
  onCommit: () => void
}

function SectionCard({ section, zoom, html, edited, active, register, onActivate, onCommit }: SectionCardProps) {
  const nodeRef = useRef<HTMLDivElement | null>(null)
  const visible = edited || active

  // 富文本内容由浏览器维护，只用 effect 在"不在编辑"时同步一次
  useEffect(() => {
    const node = nodeRef.current
    if (!node || document.activeElement === node) {
      return
    }
    if (node.innerHTML !== html) {
      node.innerHTML = html
    }
  }, [html])

  return (
    <div
      className="absolute"
      style={{ left: section.x * zoom, top: section.y * zoom, width: section.width * zoom, pointerEvents: 'none' }}
    >
      {/* 内层用 zoom 缩放：字号间距都按页面单位写，缩放窗口不会跑版 */}
      <div
        className={`group relative ${visible ? 'rounded-[3px] bg-white shadow-[0_0_0_1.5px_rgba(99,102,241,0.45)]' : ''}`}
        style={{ zoom, width: section.width, minHeight: section.height }}
      >
        <div
          ref={node => {
            nodeRef.current = node
            register(node)
          }}
          data-section={section.index}
          contentEditable={active}
          suppressContentEditableWarning
          spellCheck={false}
          onFocus={onActivate}
          onBlur={onCommit}
          onClick={() => {
            // 改过的板块没有覆盖层了，点正文本身也要能重新进入编辑
            if (!active) {
              onActivate()
            }
          }}
          style={{
            minHeight: section.height,
            padding: '3px 6px',
            outline: 'none',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            cursor: 'text',
            // 没打开也没改过的板块，正文不显示——看见的就是 PDF 原图像素
            opacity: visible ? 1 : 0,
            pointerEvents: visible ? 'auto' : 'none'
          }}
          className={visible ? '' : 'antialiased'}
        />

        {!visible && (
          <button
            type="button"
            onClick={onActivate}
            title={`点击编辑「${section.title}」`}
            className="absolute inset-0 cursor-text rounded-[3px] transition-colors hover:bg-indigo-500/10 hover:shadow-[inset_0_0_0_1px_rgba(99,102,241,0.55)]"
            style={{ pointerEvents: 'auto' }}
          >
            <span className="pointer-events-none absolute -top-4 left-1 rounded bg-indigo-500 px-1.5 py-0.5 text-[10px] text-white opacity-0 transition-opacity group-hover:opacity-100">
              点击编辑「{section.title}」
            </span>
          </button>
        )}
      </div>
    </div>
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
