import type { TextLine } from '@/types/api'

/**
 * PDF 板块 → 可编辑富文本的转换。
 *
 * 直接按"视觉行"编辑是不行的：用户一调字号，断行就全错位。这里先把被 PDF
 * 折断的行拼回段落，再生成一段和原文排版接近的 HTML 种子，交给富文本框。
 */

export interface Paragraph {
  text: string
  /** 页面单位（≈ PDF 点）的字号 */
  size: number
  /** PDF 里的字体名（已去掉子集前缀），未知时为空 */
  font: string | null
  bold: boolean
  /** 页面单位的行距（按原件量出来的基线间距） */
  lineHeight: number
}

const BULLETS = new Set(['•', '·', '▪', '■', '◦', '●', '-', '—'])
const TERMINAL = /[；;。：:！!？?]$/

/**
 * PDF 字体名 → CSS font-family。
 *
 * 简历里中文基本是楷体/宋体/黑体，英文是 Arial/Times，PDF 里存的是 PostScript 名
 * （KaiTi、SimSun、ArialMT…）。映射不上就原样引用，再把系统里常见的几个兜底字体接在后面，
 * 这样至少不会突然退化成浏览器默认的无衬线字体。
 */
const FONT_MAP: Record<string, string> = {
  kaiti: "KaiTi, 'Kaiti SC', STKaiti, '楷体', serif",
  stkaiti: "STKaiti, KaiTi, '楷体', serif",
  simsun: "SimSun, 'Songti SC', '宋体', serif",
  songti: "'Songti SC', SimSun, '宋体', serif",
  nsimsun: "NSimSun, SimSun, '新宋体', serif",
  stsong: "STSong, SimSun, '华文宋体', serif",
  simhei: "SimHei, 'Heiti SC', '黑体', sans-serif",
  heiti: "'Heiti SC', SimHei, '黑体', sans-serif",
  microsoftyahei: "'Microsoft YaHei', '微软雅黑', sans-serif",
  msyh: "'Microsoft YaHei', '微软雅黑', sans-serif",
  dengxian: "DengXian, '等线', sans-serif",
  fangsong: "FangSong, '仿宋', serif",
  arialmt: 'Arial, Helvetica, sans-serif',
  arial: 'Arial, Helvetica, sans-serif',
  timesnewroman: "'Times New Roman', Times, serif",
  times: "'Times New Roman', Times, serif",
  calibri: "Calibri, 'Microsoft YaHei', sans-serif",
  cambria: "Cambria, 'Times New Roman', serif"
}

const FALLBACK_FAMILY = "'Microsoft YaHei', 'PingFang SC', sans-serif"

/** 生成 font-family；只用单引号，方便塞进 style="..." 属性里 */
export function cssFamily(font: string | null | undefined): string {
  if (!font) {
    return FALLBACK_FAMILY
  }
  const key = font.toLowerCase().replace(/[\s_-]+/g, '')
  const mapped = FONT_MAP[key]
  if (mapped) {
    return mapped
  }
  const cleaned = font.replace(/["'<>]/g, '').trim()
  return cleaned ? `'${cleaned}', ${FALLBACK_FAMILY}` : FALLBACK_FAMILY
}

/** 一页里正文最右边界，用来判断"这行是被折行折断的"。 */
export function pageRightOf(lines: TextLine[]): number {
  return lines.reduce((right, line) => Math.max(right, line.x + line.width), 0)
}

/**
 * 把视觉行拼成段落：
 * - 单独一个项目符号的行，和下一行合成一条（"•" + 正文）
 * - 上一行顶到右边界、且这行左对齐 → 是折行，接上
 * - 其余情况（上一行没顶到右边、或以句末标点收尾）→ 新段落
 *
 * 行距按 PDF 里实际的基线间距量出来（不是套一个 1.5 倍）：简历正文排得比网页密，
 * 凭空放大行距，编辑框里的东西会明显比上面那张纸"散"。
 */
export function toParagraphs(lines: TextLine[], pageRight: number): Paragraph[] {
  const blockPitch = medianPitch(lines)
  const drafts: {
    texts: string[]
    size: number
    font: string | null
    bold: boolean
    ys: number[]
  }[] = []
  let previous: TextLine | null = null

  const push = (line: TextLine, text: string) => {
    drafts.push({
      texts: [text],
      // 用渲染出来的字号（后端按全角字宽量的），不是 PDF 里写的 Tf 值：
      // 有的简历 PDF 带文字矩阵缩放，两个数能差三成
      size: line.fontSize || line.size,
      font: line.font ?? null,
      bold: line.bold ?? false,
      ys: [line.y]
    })
  }

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]
    const text = line.text.replace(/\s+/g, ' ').trim()
    if (!text) {
      continue
    }

    // 项目符号：并入下一行
    if (BULLETS.has(text) && i + 1 < lines.length) {
      const next = lines[i + 1]
      push(next, `• ${next.text.replace(/\s+/g, ' ').trim()}`)
      previous = next
      i += 1
      continue
    }

    // 上一行顶到右边界（留 20pt 容差，右对齐的日期之类不会误判）、
    // 且这行左对齐 → 是被折行折断的同一段
    const wrapped =
      previous !== null &&
      !BULLETS.has(text) &&
      Math.abs(previous.x - line.x) < 2 &&
      previous.x + previous.width >= pageRight - 20 &&
      !TERMINAL.test(previous.text.trim())

    const last = drafts[drafts.length - 1]
    if (wrapped && last) {
      last.texts.push(text)
      last.ys.push(line.y)
    } else {
      push(line, text)
    }
    previous = line
  }

  return drafts.map(draft => {
    const size = draft.size
    const measured = pitchOf(draft.ys)
    return {
      text: draft.texts.reduce((head, tail) => joinText(head, tail)),
      size,
      font: draft.font,
      bold: draft.bold,
      lineHeight: measured || blockPitch || size * 1.45
    }
  })
}

/** 一块里相邻两行的基线间距中位数（正数、排除离谱值）。 */
function medianPitch(lines: TextLine[]): number {
  const diffs: number[] = []
  for (let i = 1; i < lines.length; i += 1) {
    const dy = lines[i].y - lines[i - 1].y
    if (dy > 4 && dy < 120) {
      diffs.push(dy)
    }
  }
  if (diffs.length === 0) {
    return 0
  }
  diffs.sort((a, b) => a - b)
  return diffs[Math.floor(diffs.length / 2)]
}

/** 一个段落内部（折行折出来的几行）的间距。 */
function pitchOf(ys: number[]): number {
  if (ys.length < 2) {
    return 0
  }
  const min = Math.min(...ys)
  const max = Math.max(...ys)
  return (max - min) / (ys.length - 1)
}

/** 中文直接接，英文/数字之间补一个空格，避免 "Spring Boot和" 这种粘连。 */
function joinText(head: string, tail: string): string {
  const lastChar = head.slice(-1)
  const firstChar = tail.slice(0, 1)
  const needSpace = /[A-Za-z0-9)]/.test(lastChar) && /[A-Za-z0-9(]/.test(firstChar)
  return needSpace ? `${head} ${tail}` : `${head}${tail}`
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 生成编辑器初始 HTML。
 *
 * `scale` 是页面当前的缩放倍数：编辑框里按 scale 放大字号和行距，看起来就和上面那张纸一样大，
 * 字体也用 PDF 里的那一个（楷体就是楷体），不至于一进编辑框就变成另一种排版。
 */
export function toSeedHtml(lines: TextLine[], pageRight: number, scale = 1): string {
  const paragraphs = toParagraphs(lines, pageRight)
  return paragraphs
    .map(paragraph => {
      const size = paragraph.size * scale
      const style = [
        `font-family:${cssFamily(paragraph.font)}`,
        `font-size:${Math.round(size * 10) / 10}px`,
        `line-height:${Math.round(paragraph.lineHeight * scale * 10) / 10}px`,
        `margin:0 0 ${Math.round(paragraph.size * 0.2 * scale * 10) / 10}px 0`,
        `font-weight:${paragraph.bold ? 700 : 400}`
      ].join(';')
      return `<div style="${style}">${escapeHtml(paragraph.text)}</div>`
    })
    .join('')
}

/** 富文本 → 纯文本（给重新解析用），保留段落与空行。 */
export function htmlToPlainText(html: string): string {
  const holder = document.createElement('div')
  holder.innerHTML = html
  holder.querySelectorAll('div, p, li, br').forEach(node => {
    node.appendChild(document.createTextNode('\n'))
  })
  const text = holder.innerText || holder.textContent || ''
  return text
    .replace(/\u00A0/g, ' ')
    .split('\n')
    .map(line => line.trim())
    .filter((line, index, all) => line.length > 0 || (index > 0 && index < all.length - 1))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}
