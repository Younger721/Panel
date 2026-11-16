import { forwardRef, useCallback, useEffect, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
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
import { htmlToPlainText, lineBoxOf, pageRightOf, resolveFamily, squeezeOf, toParagraphs, toSeedHtml } from '@/utils/pdfSection'
import type { Paragraph } from '@/utils/pdfSection'
import type { TextLayerView, TextSection } from '@/types/api'

/**
 * PDF 板块编辑器。
 *
 * 底图是原文件渲染的位图，页面看起来就是上传的 PDF；板块**标题**（教育背景、技能特长……）
 * 不算正文，按全局样式画成统一的间隔条（{@link DividerStyle}），正文则在下面弹出的编辑框里整块改。
 * 改过的块会把改后的内容按原来的位置铺回页面上（盖住底图那一块），页面上看到的就是最终的简历。
 *
 * 为什么编辑在下面而不是就地：PDF 的排版是死的，就地长出来的富文本框会盖住下面的内容，
 * 而且宽度只有一栏，调字号就挤成一团。挪到下面给足宽度，才像在 Word 里改；改完再铺回去。
 */

/** 板块间隔条的样式：全局一份，所有标题长一样 */
export interface DividerStyle {
  background: string
  color: string
  fontSize: number
  fontFamily: string
}

export const DEFAULT_DIVIDER: DividerStyle = {
  background: '#3f5a80',
  color: '#ffffff',
  fontSize: 15,
  fontFamily: '"Microsoft YaHei", "PingFang SC", sans-serif'
}

/**
 * 自己加的标题条：原件里没有这条底色带，位置和尺寸都存 PDF 点（和缩放无关）。
 * 宽度、高度默认抄原件里最上面那条，保证和既有标题一样长、一样厚。
 */
export interface ExtraDivider {
  id: string
  page: number
  x: number
  y: number
  width: number
  height: number
  title: string
}

/** 自建标题条也塞在板块改动里，用一个不可能和板块序号撞车的键 */
export const DIVIDERS_KEY = '__dividers__'

function parseDividers(raw: string | undefined): ExtraDivider[] {
  if (!raw) {
    return []
  }
  try {
    const list = JSON.parse(raw) as ExtraDivider[]
    if (!Array.isArray(list)) {
      return []
    }
    return list.filter(
      item =>
        item &&
        typeof item.id === 'string' &&
        typeof item.x === 'number' &&
        typeof item.y === 'number' &&
        typeof item.title === 'string'
    )
  } catch {
    // 存坏了当没有，不能因为一条脏数据把整页画不出来
    return []
  }
}

export interface PdfEditSurfaceHandle {
  /** 按阅读顺序拼出正文（包含还没提交的编辑框内容） */
  getText(): string
  getEdits(): Record<string, string>
  /** 在页面上加一条空白标题条，落在页面中部，之后拖到想要的位置 */
  addDivider(): void
}

interface Props {
  resumeId: number
  layer: TextLayerView
  edits: Record<string, string>
  onEditsChange: (edits: Record<string, string>) => void
  divider: DividerStyle
}

const FONT_SIZES = [10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32]
export const FONT_FAMILIES = [
  { label: '微软雅黑', value: '"Microsoft YaHei", "PingFang SC", sans-serif' },
  { label: '宋体', value: 'SimSun, "Songti SC", serif' },
  { label: '黑体', value: 'SimHei, "Heiti SC", sans-serif' },
  { label: '楷体', value: 'KaiTi, "Kaiti SC", serif' },
  { label: '等线', value: 'DengXian, "PingFang SC", sans-serif' }
]

/**
 * 存下来的板块 HTML 带一份「当时按多大缩放写的」标记。
 *
 * 编辑框里的字号是屏幕像素（打开那一刻的 zoom），换个缩放再铺回页面就会忽大忽小；
 * 记下创建时的 zoom，渲染时按 `当前 zoom / 记录值` 缩回去，纸上的字才和原来一样大。
 */
const EDIT_ZOOM_ATTR = 'data-panel-edit-zoom'

/** 拆开编辑结果：正文（喂给编辑框/画布）+ 当时写它用的缩放 */
function readEditedHtml(html: string): { body: string; zoom: number | null } {
  if (!html) {
    return { body: '', zoom: null }
  }
  const holder = document.createElement('div')
  holder.innerHTML = html
  const wrapper = holder.firstElementChild as HTMLElement | null
  if (wrapper && wrapper.hasAttribute(EDIT_ZOOM_ATTR)) {
    const zoom = Number(wrapper.getAttribute(EDIT_ZOOM_ATTR))
    return { body: wrapper.innerHTML, zoom: Number.isFinite(zoom) && zoom > 0 ? zoom : null }
  }
  return { body: html, zoom: null }
}

function wrapEditedHtml(body: string, zoom: number): string {
  return `<div ${EDIT_ZOOM_ATTR}="${zoom.toFixed(3)}">${body}</div>`
}

/** 把存下来的 HTML 拆成顶层段落节点（一段一个 div，和 toSeedHtml 的产物一一对应） */
function topLevelElements(html: string): HTMLElement[] {
  const holder = document.createElement('div')
  holder.innerHTML = html
  return Array.from(holder.children) as HTMLElement[]
}

/** 比文字时忽略所有空白：只想知道这一段有没有被改过 */
function sameText(left: string, right: string): boolean {
  return left.replace(/\s+/g, '') === right.replace(/\s+/g, '')
}

/**
 * 清掉"混进拉丁字体 span 里的中文"。
 *
 * 段落里中文用楷体、数字和空格用 Arial，两者各自包一层 span。在编辑框里紧挨着
 * Arial 那段打字时，浏览器会让新字继承那层 span 的字体，中文就落到 Arial 上——
 * 浏览器回退到系统雅黑，纸上的字立刻变成另一副字形。
 *
 * 这里只处理"声明了拉丁字体却包含中文"的元素：把它自己的 font-family 摘掉，
 * 回到段落的基础字体。用户主动改成本机中文字体（微软雅黑等）的仍然保留。
 */
const LATIN_ONLY_FAMILY = /(arial|helvetica|times|calibri|cambria|verdana|tahoma|georgia|segoe)/i
const CJK_TEXT = /[\u3400-\u9FFF\u3000-\u303F\uFF00-\uFFEF]/

/** 这些标签在覆盖层里没有任何合法用途，出现即删。 */
const DANGEROUS_TAGS =
  'script,style,iframe,object,embed,link,meta,base,form,input,button,textarea,select,svg,math'

/** CSS 里能发请求或执行脚本的写法。 */
const DANGEROUS_CSS = /(url\s*\(|expression\s*\(|javascript\s*:|behavior\s*:|@import)/i

/**
 * 覆盖层的 HTML 渲染前处理：净化 + 字体修正。
 *
 * <p>这里的内容最终走 {@code dangerouslySetInnerHTML}，而板块富文本来自
 * {@code GET /preview/text} 的 edit_layer（可跨设备同步）——服务端已经净化过一遍，
 * 但前端不能把它当可信输入：任何绕过（旧数据、导入的简历、服务端漏掉的标签）
 * 都等于存储型 XSS，而 JWT 就在 localStorage 里。</p>
 *
 * <p>用 {@link DOMParser} 而不是 {@code div.innerHTML}：后者会按文档解析并
 * 立刻尝试加载 {@code <img src>}（onerror 当场触发），前者是惰性文档，
 * 解析阶段不加载任何资源、也不执行脚本。</p>
 */
function cleanInlineFonts(html: string): string {
  if (!html || html.indexOf('<') < 0) {
    return html
  }
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  const holder = parsed.body

  holder.querySelectorAll(DANGEROUS_TAGS).forEach(element => element.remove())
  holder.querySelectorAll<HTMLElement>('*').forEach(element => {
    for (const attribute of Array.from(element.attributes)) {
      const name = attribute.name.toLowerCase()
      if (name.startsWith('on') || name === 'srcdoc') {
        element.removeAttribute(attribute.name)
        continue
      }
      if (
        (name === 'href' || name === 'src' || name === 'xlink:href') &&
        /^\s*javascript:/i.test(attribute.value)
      ) {
        element.removeAttribute(attribute.name)
      }
    }
    // 图片只保留内联图：外链会变成跟踪像素，也会在导出时变成裂图
    if (element.tagName === 'IMG' && !/^data:image\//i.test(element.getAttribute('src') ?? '')) {
      element.removeAttribute('src')
    }
    const style = element.getAttribute('style')
    if (style && DANGEROUS_CSS.test(style)) {
      element.removeAttribute('style')
    }
  })

  holder.querySelectorAll<HTMLElement>('[style]').forEach(element => {
    // 覆盖层自己按段落坐标摆位，正文里残留的绝对定位会把字挪到别处
    if (element.style.position === 'absolute' || element.style.position === 'relative') {
      element.style.removeProperty('position')
      element.style.removeProperty('left')
      element.style.removeProperty('top')
      element.style.removeProperty('right')
      element.style.removeProperty('bottom')
    }
    const family = element.style.fontFamily
    if (!family || !LATIN_ONLY_FAMILY.test(family)) {
      return
    }
    if (!CJK_TEXT.test(element.textContent ?? '')) {
      return
    }
    element.style.removeProperty('font-family')
    if (!(element.getAttribute('style') ?? '').trim()) {
      element.removeAttribute('style')
    }
  })
  return holder.innerHTML
}

const PdfEditCanvas = forwardRef<PdfEditSurfaceHandle, Props>(function PdfEditCanvas(
  { resumeId, layer, edits, onEditsChange, divider },
  ref
) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const editorRef = useRef<HTMLDivElement | null>(null)
  const partRefs = useRef(new Map<number, HTMLDivElement>())
  const savedRange = useRef<Range | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  /** 打开中的编辑框内容：还没点「完成」也算数，保存时不能丢 */
  const draft = useRef<{ index: number | null; html: string; seed: string }>({
    index: null,
    html: '',
    seed: ''
  })

  const [zoom, setZoom] = useState(1)
  const [active, setActive] = useState<number | null>(null)
  /** 打开编辑框那一刻的缩放：编辑框按这个倍数还原字号，和上面那张纸一样大 */
  const [panelScale, setPanelScale] = useState(1)
  const [images, setImages] = useState<Record<number, string>>({})
  /** 正在改文字的标题条 */
  const [editingDivider, setEditingDivider] = useState<string | null>(null)
  /** 拖动中的标题条位置：只在松手时落盘，不然每一帧都写一次草稿 */
  const [dragPos, setDragPos] = useState<{ id: string; x: number; y: number } | null>(null)
  const drag = useRef<{
    id: string
    pointerId: number
    startX: number
    startY: number
    originX: number
    originY: number
    width: number
    height: number
    pageWidth: number
    pageHeight: number
  } | null>(null)

  const extraDividers = useMemo(() => parseDividers(edits[DIVIDERS_KEY]), [edits])

  /** 写回自建标题条：和板块改动同一条通路，保存/草稿/换设备都跟着走 */
  const writeDividers = useCallback(
    (list: ExtraDivider[]) => {
      const next = { ...edits }
      if (list.length === 0) {
        delete next[DIVIDERS_KEY]
      } else {
        next[DIVIDERS_KEY] = JSON.stringify(list)
      }
      onEditsChange(next)
    },
    [edits, onEditsChange]
  )

  const commitDividerTitle = useCallback(
    (id: string, title: string) => {
      writeDividers(
        extraDividers.map(item => (item.id === id ? { ...item, title: title.trim() || '新标题' } : item))
      )
      setEditingDivider(null)
    },
    [extraDividers, writeDividers]
  )

  const addDivider = useCallback(() => {
    const page = layer.pages[0]
    if (!page) {
      return
    }
    // 尺寸抄原件最上面那条标题：加出来的和「教育背景」一样长、一样厚
    const template = layer.sections.find(section => section.heading)?.heading
    const item: ExtraDivider = {
      id: `d${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`,
      page: page.index,
      x: Math.round(template?.x ?? page.width * 0.07),
      y: Math.round(page.height * 0.45),
      width: Math.round(template?.width ?? page.width * 0.86),
      height: Math.round(template?.height ?? 32),
      title: '新标题'
    }
    writeDividers([...extraDividers, item])
    setEditingDivider(item.id)
  }, [extraDividers, layer.pages, layer.sections, writeDividers])

  const beginDrag = (event: ReactPointerEvent<HTMLDivElement>, item: ExtraDivider) => {
    if (editingDivider === item.id) {
      return
    }
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    const page = layer.pages.find(entry => entry.index === item.page)
    drag.current = {
      id: item.id,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX: item.x,
      originY: item.y,
      width: item.width,
      height: item.height,
      pageWidth: page?.width ?? 595,
      pageHeight: page?.height ?? 842
    }
    setDragPos({ id: item.id, x: item.x, y: item.y })
  }

  const moveDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const state = drag.current
    if (!state || state.pointerId !== event.pointerId) {
      return
    }
    const clamp = (value: number, max: number) => Math.max(0, Math.min(value, max))
    setDragPos({
      id: state.id,
      x: Math.round(clamp(state.originX + (event.clientX - state.startX) / zoom, state.pageWidth - state.width)),
      y: Math.round(clamp(state.originY + (event.clientY - state.startY) / zoom, state.pageHeight - state.height))
    })
  }

  const endDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const state = drag.current
    if (!state) {
      return
    }
    if (event.currentTarget.hasPointerCapture(state.pointerId)) {
      event.currentTarget.releasePointerCapture(state.pointerId)
    }
    const landed = dragPos?.id === state.id ? dragPos : null
    drag.current = null
    setDragPos(null)
    if (landed) {
      writeDividers(
        extraDividers.map(item => (item.id === state.id ? { ...item, x: landed.x, y: landed.y } : item))
      )
    }
  }

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

  /**
   * 字体家族名（line.fontKey → @font-face 家族名）。
   *
   * 名字在 `/preview/text` 里就有了，不用等字体字节下载完：HTML 先按名字写死，
   * 等 {@link FontFace} 注册好，浏览器自己就会用上，不必回头改已经填进编辑框的内容
   * （改 DOM 会把光标和用户刚打的字一起冲掉）。
   */
  const families = useMemo(() => {
    const map: Record<string, string> = {}
    ;(layer.fonts ?? []).forEach(font => {
      map[font.key] = font.family
    })
    return map
  }, [layer.fonts])

  /** 把简历里嵌的字体装进浏览器：直接拿 PDF 里那份字体字节喂给 @font-face。 */
  useEffect(() => {
    const fonts = layer.fonts ?? []
    if (fonts.length === 0) {
      return
    }
    let cancelled = false
    const faces: FontFace[] = []
    void (async () => {
      for (const font of fonts) {
        try {
          const blob = await resumeApi.previewFont(resumeId, font.family)
          const face = new FontFace(font.family, await blob.arrayBuffer())
          await face.load()
          if (cancelled) {
            return
          }
          document.fonts.add(face)
          faces.push(face)
        } catch {
          // 单个字体失败就退回系统字体，不影响编辑
        }
      }
    })()
    return () => {
      cancelled = true
      faces.forEach(face => document.fonts.delete(face))
    }
  }, [layer.fonts, resumeId])

  /** 每一块没被改过时的初始 HTML（按原文排版还原，字号跟着页面缩放走） */
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
      result.set(section.index, toSeedHtml(section.lines, right.get(pageOf) ?? 0, zoom, families))
    })
    return result
  }, [layer.pages, layer.sections, zoom, families])

  /**
   * 每一块按原文切出来的段落及其位置（PDF 点，与缩放无关）。
   *
   * 改过的块要铺回页面上：段落数量和原稿一致时，只盖住真正被改过的那几段，
   * 没改的地方继续用底图——这样配色、logo、栏目标题条这些装饰都还在。
   */
  const layouts = useMemo(() => {
    const right = new Map<number, number>()
    layer.pages.forEach(page => {
      const lines = layer.sections
        .flatMap(section => section.lines)
        .filter(line => line.page === page.index)
      right.set(page.index, pageRightOf(lines))
    })
    const result = new Map<number, Paragraph[]>()
    layer.sections.forEach(section => {
      const pageOf = section.lines[0]?.page ?? 1
      result.set(section.index, toParagraphs(section.lines, right.get(pageOf) ?? 0))
    })
    return result
  }, [layer.pages, layer.sections])

  const htmlOf = useCallback(
    (index: number) => {
      const stored = edits[String(index)]
      // 存的是带缩放标记的壳，编辑框里只放正文
      return stored === undefined ? (seeds.get(index) ?? '') : readEditedHtml(stored).body
    },
    [edits, seeds]
  )

  /** 这一块是哪个缩放下改的；没改过就是 null */
  const editZoomOf = useCallback(
    (index: number) => {
      const stored = edits[String(index)]
      return stored === undefined ? null : readEditedHtml(stored).zoom
    },
    [edits]
  )

  // 只在「打开哪一块」时灌内容：缩放变了不能把正在写的东西冲掉
  const htmlOfRef = useRef(htmlOf)
  htmlOfRef.current = htmlOf

  // 打开编辑框：把这一块现在的 HTML 灌进去
  useEffect(() => {
    const node = editorRef.current
    if (active === null || !node) {
      return
    }
    const html = htmlOfRef.current(active)
    node.innerHTML = html
    draft.current = { index: active, html, seed: html.trim() }
    // 按纸上坐标摆位（两列还是两列），打开这一刻就走一遍，之后每次输入再重排
    relayoutEditorRef.current()
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
  }, [active])

  /**
   * 把编辑框里的内容收进 edits。
   *
   * 以 DOM 为准、`active` 兜底：编辑框开着就一定算「当前这块」，
   * 否则中途热更新/重挂载导致 draft 丢了索引时，点「完成」会一声不响地什么都不做。
   */
  const commitDraft = useCallback(() => {
    const index = draft.current.index ?? active
    const node = editorRef.current
    if (index === null || !node) {
      return
    }
    const seed = draft.current.index === index && draft.current.seed
      ? draft.current.seed
      : htmlOfRef.current(index).trim()
    const value = snapshotHtml().trim()
    const next = { ...edits }
    if (!value || value === seed) {
      delete next[String(index)]
    } else {
      // 带上这次的缩放，画布上按同一个比例铺回去
      next[String(index)] = wrapEditedHtml(value, panelScale)
    }
    onEditsChange(next)
    draft.current = { index: null, html: '', seed: '' }
  }, [active, edits, onEditsChange, panelScale])

  const close = useCallback(
    (commit: boolean) => {
      if (commit) {
        commitDraft()
      } else {
        draft.current = { index: null, html: '', seed: '' }
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

  /**
   * 存进草稿的 HTML：left/top 属于"编辑框怎么摆"，不属于正文。
   * 摘掉再存，不然贴回纸面时会按这些坐标再摆一次，位置就飞了。
   */
  const snapshotHtml = useCallback(() => {
    const node = editorRef.current
    if (!node) {
      return ''
    }
    const clone = node.cloneNode(true) as HTMLElement
    clone.querySelectorAll<HTMLElement>('[style]').forEach(element => {
      ;['position', 'left', 'top', 'right', 'bottom', 'width', 'white-space', 'overflow-wrap'].forEach(prop =>
        element.style.removeProperty(prop)
      )
      if (!(element.getAttribute('style') ?? '').trim()) {
        element.removeAttribute('style')
      }
    })
    return clone.innerHTML
  }, [])

  /** 内容变高时把后面的段落往下让，别叠在一起（每帧重新赋值，见下方 relayoutEditor） */
  const relayoutEditorRef = useRef<() => void>(() => {})

  const syncDraft = useCallback(() => {
    const node = editorRef.current
    if (node && draft.current.index !== null) {
      relayoutEditorRef.current()
      draft.current = { ...draft.current, html: snapshotHtml() }
    }
  }, [snapshotHtml])

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
            const editing = draft.current.index === section.index
            const storing = edits[key]
            const html = editing
              ? draft.current.html
              : storing === undefined
                ? (seeds.get(section.index) ?? '')
                : readEditedHtml(storing).body
            const seed = editing ? draft.current.seed : (seeds.get(section.index) ?? '').trim()
            const body =
              html.trim() && html.trim() !== seed
                ? htmlToPlainText(html)
                : joinLines(section.lines.map(line => line.text))
            // 标题是分隔条不在正文里，但存下来的文本还要有它，否则解析不知道该分到哪一块；
            // 「个人信息」「全部内容」是切出来的，原件里没有这一行，不补
            const titled = section.heading ? section.title : ''
            return titled && !body.trim().startsWith(titled) ? `${titled}\n${body}`.trim() : body
          })
          .filter(Boolean)
          .join('\n\n')
      },
      getEdits() {
        const result: Record<string, string> = { ...edits }
        const { index, html, seed } = draft.current
        if (index !== null) {
          if (html.trim() && html.trim() !== seed) {
            result[String(index)] = wrapEditedHtml(html, panelScale)
          } else {
            delete result[String(index)]
          }
        }
        return result
      },
      addDivider
    }),
    [addDivider, edits, layer.sections, seeds, panelScale]
  )

  const activeSection: TextSection | null =
    active === null ? null : layer.sections.find(item => item.index === active) ?? null

  /** 编辑框的横向压缩比：跟着这一块的首段走，框里的断行才和纸上一致 */
  const panelSqueeze = useMemo(() => {
    if (active === null) {
      return 1
    }
    const list = layouts.get(active) ?? []
    return list.length > 0 ? squeezeOf(list[0]) : 1
  }, [active, layouts])

  /** 编辑框要复刻的那一页版块（按纸上的坐标摆，不是往下堆） */
  const activePart = useMemo(() => {
    if (active === null) {
      return null
    }
    const section = layer.sections.find(item => item.index === active)
    return section?.parts[0] ?? null
  }, [active, layer.sections])

  const activeLayout = useMemo<Paragraph[]>(
    () => (active === null ? [] : layouts.get(active) ?? []),
    [active, layouts]
  )

  /**
   * 编辑框的排版：几何全部由 relayoutEditor 逐段落设成内联样式（见下方），
   * 这里只兜住基本形态——绝对定位的元素不会互相挤。
   */
  const editorCss = `[data-panel-editor] > div{position:absolute;margin:0}`

  /**
   * 打字后重排：绝对定位的段落不会互相挤，某一段折行变高就会压住下一行。
   * 这里统一把 left/top/宽度算一遍——打开编辑框时也走这条路径，
   * 保证框里的坐标、宽度和纸上那一行完全一致。
   */
  relayoutEditorRef.current = () => {
    const node = editorRef.current
    if (!node || !activePart || activeLayout.length === 0) {
      return
    }
    const blockRight = activePart.x + activePart.width
    let push = 0
    const kids = Array.from(node.children) as HTMLElement[]
    kids.forEach((kid, i) => {
      const paragraph = activeLayout[i]
      if (!paragraph) {
        return
      }
      const boxHeight = lineBoxOf(paragraph)
      // 同一行上右边那一段的左边界：单行段落最多长到这里，不会压到隔壁列
      const nextInRow = activeLayout
        .slice(i + 1)
        .find(item => Math.abs(item.box.y - paragraph.box.y) < boxHeight * 0.6)
      const limit = nextInRow
        ? nextInRow.box.x - paragraph.box.x - paragraph.size * 0.4
        : blockRight - paragraph.box.x
      const natural = paragraph.box.width + paragraph.size * 0.5
      const visualWidth = Math.max(limit, natural)

      kid.style.position = 'absolute'
      kid.style.margin = '0'
      kid.style.whiteSpace = 'pre-wrap'
      kid.style.overflowWrap = 'break-word'
      kid.style.left = `${((paragraph.box.x - activePart.x) * panelScale) / panelSqueeze}px`
      kid.style.width = `${(visualWidth * panelScale) / panelSqueeze}px`
      kid.style.top = `${(paragraph.box.y - activePart.y) * panelScale + push}px`

      const rows = Math.max(1, Math.round(paragraph.box.height / boxHeight))
      const lineHeight = (paragraph.lineHeight || boxHeight) * panelScale
      const extra = kid.offsetHeight - rows * lineHeight
      if (extra > 1) {
        push += extra
      }
    })
  }

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

              {/* 板块标题画成统一的间隔条：原件那条底色带被它盖住，所有标题长得一样 */}
              {layer.sections.map(section => {
                const heading = section.heading
                if (!heading || heading.page !== page.index) {
                  return null
                }
                // 厚度用原件那条底色带的高度（正好盖住它）；字号由全局配置决定
                const barHeight = heading.height * zoom
                return (
                  <div
                    key={`heading-${section.index}`}
                    className="pointer-events-none absolute flex items-center overflow-hidden font-semibold tracking-wide"
                    style={{
                      left: heading.x * zoom,
                      top: heading.y * zoom,
                      width: heading.width * zoom,
                      height: barHeight,
                      paddingLeft: 10 * zoom,
                      // 直角：原件的底色带是方的，圆角会在四个角上露出带子的直角，
                      // 看着就像"横条边上还挂着蓝边"
                      borderRadius: 0,
                      background: divider.background,
                      color: divider.color,
                      fontSize: divider.fontSize * zoom,
                      fontFamily: divider.fontFamily,
                      lineHeight: `${barHeight}px`
                    }}
                  >
                    {section.title}
                  </div>
                )
              })}

              {layer.sections.map(section => {
                const parts = section.parts.filter(part => part.page === page.index)
                if (parts.length === 0) {
                  return null
                }
                const isActive = active === section.index
                const stored = edits[String(section.index)]
                const edited = stored !== undefined
                const preview = edited ? readEditedHtml(stored) : null
                const layout = preview ? (layouts.get(section.index) ?? []) : []
                const nodes = preview ? topLevelElements(preview.body) : []
                // 段落一一对得上：只盖住被改过的那几段；对不上（用户加了行）就整块重排
                const aligned = preview !== null && nodes.length > 0 && nodes.length === layout.length
                const editZoom = preview?.zoom ?? zoom
                return parts.map((part, order) => {
                  // 整块重排这一段也要按 PDF 的水平缩放压一下字宽，不然字比原稿胖一圈
                  const partParagraph = layout.find(item => item.page === part.page)
                  const partSqueeze = partParagraph ? squeezeOf(partParagraph) : 1
                  return (
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
                    {aligned &&
                      nodes.map((node, i) => {
                        const paragraph = layout[i]
                        if (!paragraph || paragraph.page !== page.index) {
                          return null
                        }
                        // 没动过的段落保持底图，配色和装饰条都还在
                        if (sameText(node.textContent ?? '', paragraph.text)) {
                          return null
                        }
                        const width = Math.max(paragraph.box.width, 8)
                        // 原本占一行、现在被改长的段落不要折行：名字这种右边界还很空，
                        // 折成两行反而像排版坏了；多行段落照常按原宽度换行
                        const singleLine = paragraph.box.height <= paragraph.size * 1.8
                        // 版式一律按原稿来：字体、字号、行距都取这一段的原文排版，
                        // 存的 HTML 只管"字变成什么"。改一个字不该让整段换字体或变大变小。
                        //
                        // 字号用 em 字高，宽度再按 PDF 的 Tz 压回去：简历 PDF 常把字压窄
                        // （14pt 的字只有 10pt 宽），只给字号会让字又小又胖，一眼看出是贴的。
                        const squeeze = squeezeOf(paragraph)
                        const boxHeight = lineBoxOf(paragraph)
                        // 单行：行盒按 1.12em 摆，基线正好落在 PDF 的 0.92em；多行：保留真实
                        // 行距，再把首行基线补回原位（浏览器把行距平摊到上下两边）。
                        const lineHeight = singleLine ? boxHeight : paragraph.lineHeight
                        const baselineShift = singleLine ? 0 : (lineHeight - boxHeight) / 2
                        return (
                          <div
                            key={`edited-${section.index}-${i}`}
                            className="pointer-events-none absolute z-10 bg-white"
                            style={{
                              left: (paragraph.box.x - part.x) * zoom,
                              top: (paragraph.box.y - part.y) * zoom,
                              width: (width / squeeze) * zoom,
                              minHeight: paragraph.box.height * zoom,
                              transform: `scaleX(${squeeze})`,
                              transformOrigin: 'left top'
                            }}
                          >
                            <div
                              style={{
                                fontFamily: resolveFamily(paragraph.fontKey, paragraph.font, families),
                                fontSize: paragraph.size * zoom,
                                lineHeight: `${lineHeight * zoom}px`,
                                marginTop: -baselineShift * zoom,
                                marginBottom: 0,
                                fontWeight: paragraph.bold ? 700 : 400,
                                fontSynthesis: 'none',
                                letterSpacing: 0,
                                fontKerning: 'none',
                                whiteSpace: singleLine ? 'nowrap' : 'normal'
                              }}
                              dangerouslySetInnerHTML={{ __html: cleanInlineFonts(node.innerHTML) }}
                            />
                          </div>
                        )
                      })}
                    {preview && !aligned && order === 0 && (
                      <div
                        className="pointer-events-none absolute left-0 top-0 z-10 bg-white"
                        style={{ width: part.width * zoom, minHeight: part.height * zoom }}
                      >
                        <div
                          style={{
                            width: (part.width * editZoom) / partSqueeze,
                            transform: `scaleX(${partSqueeze}) scale(${zoom / editZoom})`,
                            transformOrigin: 'top left'
                          }}
                          dangerouslySetInnerHTML={{ __html: cleanInlineFonts(preview.body) }}
                        />
                      </div>
                    )}
                    <button
                      type="button"
                      onClick={() => {
                        if (isActive) {
                          return
                        }
                        if (active !== null) {
                          commitDraft()
                        }
                        // 记下这一刻的缩放：编辑框按同一倍数还原字号，和上面那张纸一样大；
                        // 已经改过的块沿用当初那个缩放，不然再打开字号会跳
                        setPanelScale(editZoomOf(section.index) ?? zoom)
                        setActive(section.index)
                      }}
                      title={`${section.title} · 点击编辑`}
                      className={`group relative block h-full w-full rounded-[3px] text-left transition-colors ${
                        isActive
                          ? 'ring-2 ring-indigo-500'
                          : // 一律不铺底色，只用描边标记：改过的块压在原稿上是纯白的，
                            // 底纹一盖就变成"纸上一个白洞"，反而弄脏了页面
                            'hover:ring-1 hover:ring-indigo-400'
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
                  )
                })
              })}

              {/* 自己加的标题条：和原件标题同款，可拖到任意位置、双击改字、悬浮删除 */}
              {extraDividers
                .filter(item => item.page === page.index)
                .map(item => {
                  const left = dragPos?.id === item.id ? dragPos.x : item.x
                  const top = dragPos?.id === item.id ? dragPos.y : item.y
                  const barHeight = item.height * zoom
                  const isEditing = editingDivider === item.id
                  return (
                    <div
                      key={`extra-${item.id}`}
                      className="group pointer-events-auto absolute z-20 flex select-none items-center overflow-hidden font-semibold tracking-wide"
                      style={{
                        left: left * zoom,
                        top: top * zoom,
                        width: item.width * zoom,
                        height: barHeight,
                        paddingLeft: 10 * zoom,
                        borderRadius: 3 * zoom,
                        background: divider.background,
                        color: divider.color,
                        fontSize: divider.fontSize * zoom,
                        fontFamily: divider.fontFamily,
                        lineHeight: `${barHeight}px`,
                        cursor: isEditing ? 'text' : dragPos?.id === item.id ? 'grabbing' : 'grab',
                        opacity: dragPos?.id === item.id ? 0.9 : 1,
                        touchAction: 'none'
                      }}
                      onPointerDown={event => beginDrag(event, item)}
                      onPointerMove={moveDrag}
                      onPointerUp={endDrag}
                      onPointerCancel={endDrag}
                      onDoubleClick={() => setEditingDivider(item.id)}
                      title={isEditing ? undefined : '拖动改位置 · 双击改标题'}
                    >
                      {isEditing ? (
                        <input
                          autoFocus
                          defaultValue={item.title}
                          onPointerDown={event => event.stopPropagation()}
                          onBlur={event => commitDividerTitle(item.id, event.currentTarget.value)}
                          onKeyDown={event => {
                            if (event.key === 'Enter') {
                              commitDividerTitle(item.id, event.currentTarget.value)
                            } else if (event.key === 'Escape') {
                              setEditingDivider(null)
                            }
                          }}
                          className="w-full min-w-0 bg-transparent pr-1 outline-none"
                          style={{
                            color: divider.color,
                            fontFamily: divider.fontFamily,
                            fontSize: divider.fontSize * zoom,
                            fontWeight: 600
                          }}
                        />
                      ) : (
                        <span className="truncate">{item.title}</span>
                      )}
                      <button
                        type="button"
                        title="删除这条标题"
                        onPointerDown={event => event.stopPropagation()}
                        onClick={event => {
                          event.stopPropagation()
                          writeDividers(extraDividers.filter(entry => entry.id !== item.id))
                        }}
                        className="absolute right-1 top-1/2 -translate-y-1/2 rounded bg-black/25 px-1 text-[11px] leading-4 text-white opacity-0 transition-opacity hover:bg-black/45 group-hover:opacity-100"
                      >
                        ×
                      </button>
                    </div>
                  )
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
                    // 字号按页面单位（pt）给，乘上缩放才是编辑框里的像素
                    styleSelection({ 'font-size': `${Math.round(Number(value) * panelScale)}px` })
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

          <div className="min-h-0 flex-1 overflow-auto bg-slate-100 px-6 py-4">
            {/* 编辑框就是纸上那一块：同宽、同高、同倍数，段落按原坐标绝对定位，
                所以两列还是两列、行距还是行距。字号用 em 字高，再按 PDF 的 Tz
                横向压一下——不压的话每行能放的字数会变，一打开就和纸上对不上。 */}
            <div
              className="relative mx-auto"
              style={{
                width: (activePart?.width ?? baseWidth) * panelScale,
                minHeight: (activePart?.height ?? 0) * panelScale,
                maxWidth: '100%',
                background: '#ffffff',
                boxShadow: '0 1px 3px rgba(15, 23, 42, 0.12)'
              }}
            >
              <style>{editorCss}</style>
              <div
                ref={editorRef}
                data-panel-editor
                contentEditable
                suppressContentEditableWarning
                spellCheck={false}
                onInput={syncDraft}
                onKeyDown={event => {
                  // 段落是按纸上坐标绝对定位的，回车新开一个 div 会插在容器顶部、
                  // 和第一行叠在一起。这里改成段内换行：结构不变，位置也不会飞。
                  if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                    event.preventDefault()
                    document.execCommand('insertLineBreak')
                    syncDraft()
                  }
                }}
                className="text-slate-800 outline-none"
                style={{
                  position: 'absolute',
                  left: 0,
                  top: 0,
                  width: `${100 / panelSqueeze}%`,
                  minHeight: '100%',
                  whiteSpace: 'pre-wrap',
                  fontSynthesis: 'none',
                  fontKerning: 'none',
                  transform: `scaleX(${panelSqueeze})`,
                  transformOrigin: 'top left'
                }}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  )
})

const BULLET_ONLY = /^[•·▪◦‣-]$/

/** 视觉行拼回文本：单独一个「•」的行并进下一行，别让它自己占一行 */
function joinLines(texts: string[]): string {
  const merged: string[] = []
  for (const raw of texts) {
    const text = raw.replace(/\s+/g, ' ').trim()
    if (!text) {
      continue
    }
    const last = merged[merged.length - 1]
    if (last !== undefined && BULLET_ONLY.test(last)) {
      merged[merged.length - 1] = `${last} ${text}`
    } else {
      merged.push(text)
    }
  }
  return merged.join('\n')
}

function ZoomControls({  zoom,
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
