import type { TextLine } from '@/types/api'

/**
 * PDF 板块 → 可编辑富文本的转换。
 *
 * 直接按"视觉行"编辑是不行的：用户一调字号，断行就全错位。这里先把被 PDF
 * 折断的行拼回段落，再生成一段和原文排版接近的 HTML 种子，交给富文本框。
 */

export interface Paragraph {
  text: string
  /** 段落内部按字体切开的段（中文楷体 + 数字 Arial 这种） */
  runs: ParagraphRun[]
  /** 页面单位（≈ PDF 点）的字号 */
  size: number
  /** PDF 里的字体名（已去掉子集前缀），未知时为空 */
  font: string | null
  /** PDF 里的原样字体名，用来找嵌入字体 */
  fontKey: string | null
  bold: boolean
  /** 页面单位的行距（按原件量出来的基线间距） */
  lineHeight: number
}

/** 段落里一段同字体的文字。 */
export interface ParagraphRun {
  text: string
  font: string | null
  fontKey: string | null
  bold: boolean
}

/** 嵌入字体的映射：line.fontKey → @font-face 家族名 */
export type EmbeddedFamilies = Record<string, string>

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

/**
 * 优先用简历里嵌的那个字体文件，其次是按字体名猜的系统字体。
 *
 * 嵌入字体是子集，只包含简历里出现过的字；用户新打的字会落到后面的系统字体上，
 * 浏览器按字回退，所以兜底那一串不能省。
 */
export function resolveFamily(
  fontKey: string | null | undefined,
  font: string | null | undefined,
  families?: EmbeddedFamilies
): string {
  const embedded = fontKey && families ? families[fontKey] : undefined
  const fallback = cssFamily(font)
  return embedded ? `'${embedded}', ${fallback}` : fallback
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
    runs: ParagraphRun[]
    size: number
    /** 这一段的"主字体"：段落基准样式用它，其余字体用 span 覆盖 */
    font: string | null
    fontKey: string | null
    bold: boolean
    ys: number[]
  }[] = []
  let previous: TextLine | null = null

  const push = (line: TextLine, prefix?: string) => {
    const runs = runsOf(line)
    if (prefix && runs.length > 0) {
      runs[0] = { ...runs[0], text: prefix + runs[0].text }
    }
    const primary = runs[0] ?? { text: '', font: null, fontKey: null, bold: false }
    drafts.push({
      runs,
      // 用渲染出来的字号（后端按全角字宽量的），不是 PDF 里写的 Tf 值：
      // 有的简历 PDF 带文字矩阵缩放，两个数能差三成
      size: line.fontSize || line.size,
      font: line.font ?? primary.font,
      fontKey: line.fontKey ?? primary.fontKey,
      bold: line.bold ?? primary.bold,
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
      // 只把「• 」补到第一段前面：这一行可能被切成中文/英文两段，整行再拼一次会重字
      push(next, '• ')
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
      appendRuns(last.runs, runsOf(line))
      last.ys.push(line.y)
    } else {
      push(line)
    }
    previous = line
  }

  return drafts.map(draft => {
    const size = draft.size
    const measured = pitchOf(draft.ys)
    // 行距夹在 1.05~2.2 倍字号之间：量歪了也不至于把一块内容排得东倒西歪
    const raw = measured || blockPitch || size * 1.45
    const lineHeight = Math.min(Math.max(raw, size * 1.05), size * 2.2)
    return {
      text: draft.runs.map(run => run.text).join(''),
      runs: draft.runs,
      size,
      font: draft.font,
      fontKey: draft.fontKey,
      bold: draft.bold,
      lineHeight
    }
  })
}

/** 一行拆成"同字体的几段"；后端没给 runs 时整行算一段。 */
function runsOf(line: TextLine): ParagraphRun[] {
  const runs = line.runs && line.runs.length > 0
    ? line.runs.map(run => ({
        text: run.text.replace(/\s+/g, ' '),
        font: run.font ?? null,
        fontKey: run.fontKey ?? null,
        bold: run.bold ?? false
      }))
    : [{
        text: line.text.replace(/\s+/g, ' '),
        font: line.font ?? null,
        fontKey: line.fontKey ?? null,
        bold: line.bold ?? false
      }]
  const merged = runs.filter(run => run.text.length > 0)
  if (merged.length > 0) {
    merged[0] = { ...merged[0], text: merged[0].text.replace(/^\s+/, '') }
    const last = merged[merged.length - 1]
    merged[merged.length - 1] = { ...last, text: last.text.replace(/\s+$/, '') }
  }
  return merged
}

/** 折行：把下一行的段接上，中间该不该补空格跟 joinText 一个规则。 */
function appendRuns(target: ParagraphRun[], next: ParagraphRun[]): void {
  if (next.length === 0) {
    return
  }
  const last = target[target.length - 1]
  if (last && /[A-Za-z0-9)]$/.test(last.text) && /^[A-Za-z0-9(]/.test(next[0].text)) {
    target[target.length - 1] = { ...last, text: `${last.text} ` }
  }
  target.push(...next)
}

/** 一块里相邻两行的基线间距中位数（正数、排除离谱值）。 */
function medianPitch(lines: TextLine[]): number {
  const diffs: number[] = []
  for (let i = 1; i < lines.length; i += 1) {
    const previous = lines[i - 1]
    const line = lines[i]
    const size = Math.max(sizeOf(previous), sizeOf(line))
    const dy = line.y - previous.y
    // 只信"字号相近的两行"之间的距离：个人信息那种 24pt 的名字 / 20pt 的"个人简历" /
    // 14pt 的正文混排，把大间隔算进来行距会被拉成三倍
    const sameSize = Math.abs(sizeOf(previous) - sizeOf(line)) <= size * 0.2
    if (sameSize && dy > size * 0.8 && dy < size * 2.4) {
      diffs.push(dy)
    }
  }
  if (diffs.length === 0) {
    return 0
  }
  diffs.sort((a, b) => a - b)
  return diffs[Math.floor(diffs.length / 2)]
}

function sizeOf(line: TextLine): number {
  return line.fontSize || line.size
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
 * `scale` 是页面当前的缩放倍数：编辑框里按 scale 放大字号和行距，看起来就和上面那张纸一样大。
 * 字体优先用简历里嵌的那份（{@link EmbeddedFamilies}），没有才按名字猜系统字体——
 * 这样"楷体"就是纸上那个楷体，不会一进编辑框就换一副字形。
 */
export function toSeedHtml(
  lines: TextLine[],
  pageRight: number,
  scale = 1,
  families?: EmbeddedFamilies
): string {
  const paragraphs = toParagraphs(lines, pageRight)
  return paragraphs
    .map(paragraph => {
      const size = paragraph.size * scale
      const baseFamily = resolveFamily(paragraph.fontKey, paragraph.font, families)
      const baseWeight = paragraph.bold ? 700 : 400
      const style = [
        `font-family:${baseFamily}`,
        `font-size:${Math.round(size * 10) / 10}px`,
        `line-height:${Math.round(paragraph.lineHeight * scale * 10) / 10}px`,
        `margin:0 0 ${Math.round(paragraph.size * 0.2 * scale * 10) / 10}px 0`,
        `font-weight:${baseWeight}`
      ].join(';')
      const inner = paragraph.runs
        .map(run => {
          const family = resolveFamily(run.fontKey, run.font, families)
          const weight = run.bold ? 700 : 400
          if (family === baseFamily && weight === baseWeight) {
            return escapeHtml(run.text)
          }
          // 行内换字体要保留：正文里的数字和英文在原文件里本来就不是中文字体
          return `<span style="font-family:${family};font-weight:${weight}">${escapeHtml(run.text)}</span>`
        })
        .join('')
      return `<div style="${style}">${inner || escapeHtml(paragraph.text)}</div>`
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
