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
}

const BULLETS = new Set(['•', '·', '▪', '■', '◦', '●', '-', '—'])
const TERMINAL = /[；;。：:！!？?]$/

/** 一页里正文最右边界，用来判断"这行是被折行折断的"。 */
export function pageRightOf(lines: TextLine[]): number {
  return lines.reduce((right, line) => Math.max(right, line.x + line.width), 0)
}

/**
 * 把视觉行拼成段落：
 * - 单独一个项目符号的行，和下一行合成一条（"•" + 正文）
 * - 上一行顶到右边界、且这行左对齐 → 是折行，接上
 * - 其余情况（上一行没顶到右边、或以句末标点收尾）→ 新段落
 */
export function toParagraphs(lines: TextLine[], pageRight: number): Paragraph[] {
  const paragraphs: Paragraph[] = []
  let previous: TextLine | null = null

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]
    const text = line.text.replace(/\s+/g, ' ').trim()
    if (!text) {
      continue
    }

    // 项目符号：并入下一行
    if (BULLETS.has(text) && i + 1 < lines.length) {
      const next = lines[i + 1]
      paragraphs.push({ text: `• ${next.text.replace(/\s+/g, ' ').trim()}`, size: next.size })
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

    if (wrapped) {
      const last = paragraphs[paragraphs.length - 1]
      if (last) {
        last.text = joinText(last.text, text)
      }
    } else {
      paragraphs.push({ text, size: line.size })
    }
    previous = line
  }

  return paragraphs
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

/** 生成编辑器初始 HTML；字号用页面单位，外层靠 CSS zoom 缩放。 */
export function toSeedHtml(lines: TextLine[], pageRight: number): string {
  const paragraphs = toParagraphs(lines, pageRight)
  const body = paragraphs.reduce((sum, item) => sum + item.size, 0) / Math.max(1, paragraphs.length)
  return paragraphs
    .map((paragraph, index) => {
      const isTitle = index === 0 && paragraphs.length > 1 && paragraph.size >= body * 1.1
      const style = [
        `font-size:${paragraph.size}px`,
        `line-height:${Math.round(paragraph.size * 1.5)}px`,
        'margin:0 0 3px 0',
        isTitle ? 'font-weight:600' : ''
      ]
        .filter(Boolean)
        .join(';')
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
