import type { Block } from '@/types/api'

/**
 * 把简历解析出来的 blocks 还原成编辑器的文档模型。
 *
 * 编辑器（public/resume-editor.html）不认 markdown，认的是它自己的 DOM 契约：
 *   header.resume-head > .name/.headline/.contact
 *   section[data-section-type] > h2.section-title + .section-body
 *     .section-body > .entry > .entry-head(.entry-title/.entry-meta) + .entry-sub + ul.bullets
 * 所以这里做一次「块 → 编辑器 HTML」的映射，`data-role` 必须齐，否则编辑器不会把它变成可编辑块。
 */
export interface EditorDocument {
  html: string
  /** 需要提示用户的降级说明 */
  warning?: string
}

interface SectionRule {
  type: string
  match: RegExp
}

/** 章节标题只认短句，避免把「2021.07-至今 某公司 后端工程师」误判成章节 */
const SECTION_RULES: SectionRule[] = [
  { type: 'summary', match: /(个人简介|个人介绍|自我介绍|自我评价|个人总结|关于我)/i },
  { type: 'education', match: /(教育|学历)/ },
  { type: 'work', match: /(工作经历|工作经验|实习经历|实习|职业经历|任职)/ },
  { type: 'project', match: /(项目|作品)/ },
  { type: 'skills', match: /(技能|技术栈|专业能力)/ },
  { type: 'awards', match: /(证书|奖项|荣誉|获奖|竞赛)/ },
  { type: 'languages', match: /(语言能力|外语)/ }
]

const ENTRY_TYPES = new Set(['education', 'work', 'project'])
const LIST_TYPES = new Set(['skills', 'awards', 'languages'])

/**
 * 日期片段：2021.07 / 2021年7月 / 2024-03
 * 范围：2020.07-2021.06 / 2021.07-至今 / 2019-2023
 * 注意月份后面的 `[-–—]` 不能再被月份吃掉，否则「2020.07-2021.06」会被截成 meta=2020.07-、title=2021.06…
 */
const DATE_TOKEN = '(?:19|20)\\d{2}\\s*[年.\\-/]\\s*\\d{1,2}\\s*月?'
const DATE_RANGE = `${DATE_TOKEN}(?:\\s*[-–—~至到]\\s*(?:${DATE_TOKEN}|至今|现在|今))?`
const DATE_PREFIX = new RegExp(`^(${DATE_RANGE})\\s+(.+)$`)
const DATE_IN_PARENS = new RegExp(`^(.+?)\\s*[（(]\\s*(${DATE_RANGE})\\s*[)）]\\s*$`)
/** 日期落在行尾：「某公司 · 后端工程师  2022.07 – 至今」 */
const TRAILING_DATE = new RegExp(`^(.+?)\\s+(${DATE_RANGE})$`)

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch] as string)
}

function tidy(value: string): string {
  return value.replace(/\s+/g, ' ').replace(/^[\s·|｜,，、:：-]+/, '').trim()
}

/** 标题两头的分隔符也要剪掉：「某公司 · 后端工程师 ·」→「某公司 · 后端工程师」 */
function tidyTitle(value: string): string {
  return tidy(value).replace(/[\s·|｜,，、:：-]+$/, '')
}

/** PDF 抽出来的字之间常带空格（「性 别： 男」），把中文字符之间的空白合并掉 */
function cleanInline(value: string): string {
  return tidy(value)
    .replace(/([\u4e00-\u9fa5])\s+([\u4e00-\u9fa5])/g, '$1$2')
    .replace(/\s*[：:]\s*/g, '：')
}

/** 日期范围统一成 `2020.07 – 2021.06` / `2021.07 – 至今`，注意别把「至今」拆开 */
function normalizeRange(value: string): string {
  return tidy(value)
    .replace(/\s*[-–—~]\s*/g, ' – ')
    .replace(/(\d)\s*[至到]\s*(?=\d)/g, '$1 – ')
    .replace(/\s+/g, ' ')
}

/** "2021.07-至今  某互联网公司  高级后端工程师" → meta + 标题 */
function splitEntryTitle(text: string): { meta: string; title: string } {
  // 双空格是原简历里「公司 / 职位」的分隔，先换成 · 再匹配日期
  const plain = text.trim().replace(/\s{2,}/g, ' · ')
  const matched = DATE_PREFIX.exec(plain)
  if (matched && tidy(matched[2])) {
    return { meta: normalizeRange(matched[1]), title: tidyTitle(matched[2]) }
  }
  // 「Panel 智能面试平台（2024.03-至今）」这类把时间写在括号里的
  const inParens = DATE_IN_PARENS.exec(plain)
  if (inParens && tidy(inParens[1])) {
    return { meta: normalizeRange(inParens[2]), title: tidyTitle(inParens[1]) }
  }
  // 「某公司 · 后端工程师  2022.07 – 至今」——从编辑器存回去的文本就是这种顺序，
  // 日期在末尾。不认的话每存一次日期就多沉在标题里一层。
  const trailing = TRAILING_DATE.exec(plain)
  if (trailing && tidy(trailing[2])) {
    return { meta: normalizeRange(trailing[2]), title: tidyTitle(trailing[1]) }
  }
  return { meta: '', title: plain }
}

/** 技能/证书这类一行罗列，按分隔符拆成多条要点 */
function splitListItems(text: string): string[] {
  return text
    .split(/[,，、;；/|｜]/)
    .map(item => tidy(item))
    .filter(item => item.length > 0)
}

/** 经历类的长段落，按分号拆要点；没有分号就整段一条 */
function splitBullets(text: string): string[] {
  const parts = text
    .split(/[；;]/)
    .map(item => tidy(item).replace(/[。.]$/, ''))
    .filter(item => item.length > 0)
  return parts.length > 0 ? parts : [tidy(text)]
}

interface Entry {
  title: string
  meta: string
  bullets: string[]
}

interface Section {
  type: string
  title: string
  entries: Entry[]
  bullets: string[]
  paragraphs: string[]
}

function sectionRuleOf(text: string): SectionRule | null {
  const plain = text.replace(/[\s#*]/g, '')
  if (plain.length > 14) {
    return null
  }
  return SECTION_RULES.find(rule => rule.match.test(plain)) || null
}

function renderEntry(entry: Entry): string {
  const head = `<div class="entry-head"><div class="entry-title" data-role="entry-title">${escapeHtml(
    entry.title
  )}</div>${entry.meta ? `<div class="entry-meta" data-role="entry-meta">${escapeHtml(entry.meta)}</div>` : ''}</div>`
  const bullets = entry.bullets.length
    ? `<ul class="bullets">${entry.bullets.map(b => `<li data-role="bullet">${escapeHtml(b)}</li>`).join('')}</ul>`
    : ''
  return `<div class="entry">${head}${bullets}</div>`
}

function renderSection(section: Section): string {
  const body = section.entries.length
    ? section.entries.map(renderEntry).join('')
    : [
        section.paragraphs.map(p => `<p data-role="paragraph">${escapeHtml(p)}</p>`).join(''),
        section.bullets.length
          ? `<ul class="bullets">${section.bullets.map(b => `<li data-role="bullet">${escapeHtml(b)}</li>`).join('')}</ul>`
          : ''
      ].join('')
  if (!body) {
    return ''
  }
  return `<section class="section" data-section-type="${section.type}"><h2 class="section-title" data-role="section-title">${escapeHtml(
    section.title
  )}</h2><div class="section-body">${body}</div></section>`
}

export function buildEditorDocument(blocks: Block[]): EditorDocument {
  if (!blocks.length) {
    return { html: '' }
  }

  // PDF 抽出来的正文常常整篇都是 PARAGRAPH（没有标题块），这时靠关键词反推章节
  const flat = !blocks.some(block => block.type === 'TITLE')

  const queue = [...blocks]
  let name = ''
  let headline = ''
  const contacts: string[] = []

  const pushContact = (text: string) => {
    const cleaned = cleanInline(text).replace(/\s*[|｜]\s*/g, ' · ')
    if (cleaned) {
      contacts.push(cleaned)
    }
  }

  // 头部：第一块标题当姓名+求职意向；其后连续的段落里挑联系方式
  const first = queue[0]
  if (first && first.type === 'TITLE') {
    queue.shift()
    const text = tidy(first.text)
    const parts = text.split(/\s+[-–—·|]\s+/)
    if (parts.length >= 2) {
      name = parts[0]
      headline = parts.slice(1).join(' · ')
    } else {
      name = parts[0]
    }
  }
  // 扁平文档没有标题块：第一段非联系方式的行当姓名，紧随其后的短句当求职意向
  if (flat) {
    while (queue.length && queue[0].type !== 'TITLE') {
      const text = tidy(queue[0].text)
      const compact = text.replace(/\s+/g, '')
      if (!text) {
        queue.shift()
        continue
      }
      if (/电话|邮箱|@|性别|籍贯|年龄|出生|民族|微信|城市|现居|工作年限|工作经验/.test(compact)) {
        pushContact(text)
        queue.shift()
        continue
      }
      if (!name) {
        name = compact.slice(0, 24)
        queue.shift()
        continue
      }
      if (sectionRuleOf(compact)) {
        break
      }
      if (!headline && compact.length <= 32 && !/^(19|20)\d{2}/.test(compact)) {
        // 求职意向保留词间空格（"Java 后端开发工程师 · 4 年经验"），只有姓名需要去掉
        headline = text
        queue.shift()
        continue
      }
      break
    }
  }
  while (!flat && queue.length && queue[0].type !== 'TITLE') {
    const text = tidy(queue[0].text)
    if (/电话|邮箱|@|微信|城市|现居|工作年限|工作经验/.test(text)) {
      pushContact(text)
      queue.shift()
      continue
    }
    break
  }

  const sections: Section[] = []
  let current: Section | null = null

  const startSection = (type: string, title: string): Section => {
    const section: Section = { type, title, entries: [], bullets: [], paragraphs: [] }
    sections.push(section)
    return section
  }

  for (const block of queue) {
    const text = tidy(block.text)
    if (!text) {
      continue
    }
    // 扁平文档（PDF）：短句命中章节关键词就当标题，日期开头的行当条目
    if (flat && (block.type === 'PARAGRAPH' || block.type === 'LIST_ITEM')) {
      const rule = sectionRuleOf(text)
      if (rule) {
        current = startSection(rule.type, text)
        continue
      }
      if (current && ENTRY_TYPES.has(current.type)) {
        const raw = block.text.trim()
        // 日期开头（原始简历）或日期结尾（从编辑器存回来的）都算一条经历
        if (DATE_PREFIX.test(raw) || (raw.length <= 48 && TRAILING_DATE.test(raw))) {
          const { meta, title } = splitEntryTitle(block.text)
          if (title) {
            current.entries.push({ title, meta, bullets: [] })
            continue
          }
        }
      }
    }
    if (block.type === 'TITLE' || block.type === 'CLAUSE') {
      const rule = sectionRuleOf(text)
      if (rule) {
        current = startSection(rule.type, text)
        continue
      }
      if (current && ENTRY_TYPES.has(current.type)) {
        // 用原始文本：tidy 会把「公司 / 职位」之间的双空格压掉
        const { meta, title } = splitEntryTitle(block.text)
        current.entries.push({ title, meta, bullets: [] })
        continue
      }
      if (current) {
        current.bullets.push(text)
        continue
      }
      current = startSection('custom', text)
      continue
    }

    if (!current) {
      current = startSection('summary', '个人简介')
    }
    const section: Section = current
    if (ENTRY_TYPES.has(section.type)) {
      const entry = section.entries[section.entries.length - 1]
      const bullets = splitBullets(text)
      if (entry) {
        entry.bullets.push(...bullets)
      } else {
        section.entries.push({ title: '', meta: '', bullets })
      }
      continue
    }
    if (LIST_TYPES.has(section.type)) {
      const items = splitListItems(text)
      // 技能是一串关键词，合并成一行更像简历；证书/奖项每条独立成点
      if (section.type === 'awards' || items.length <= 1) {
        section.bullets.push(...items)
      } else {
        section.bullets.push(items.join(' · '))
      }
      continue
    }
    section.paragraphs.push(text)
  }

  const header = `<header class="resume-head"><h1 class="name" data-role="name">${escapeHtml(
    name || '你的姓名'
  )}</h1>${headline ? `<p class="headline" data-role="headline">${escapeHtml(headline)}</p>` : ''}${
    contacts.length ? `<p class="contact" data-role="contact">${escapeHtml(contacts.join(' · '))}</p>` : ''
  }</header>`

  const body = sections.map(renderSection).filter(Boolean).join('')

  return {
    html: header + body,
    warning: flat ? 'PDF 抽取出来的段落顺序可能和原文不一致，进编辑器后建议先核对一遍。' : undefined
  }
}
