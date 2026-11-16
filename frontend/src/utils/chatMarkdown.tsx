import { Fragment, type ReactNode } from 'react'

/**
 * 极简 Markdown 渲染：只覆盖模型回答常用的标题、列表、代码块、粗体、行内代码和 [n] 引用。
 * 不引入 react-markdown，避免为一个页面加整条 remark 依赖链。
 */
export function renderChatMarkdown(text: string, onCitation: (index: number) => void): ReactNode {
  // 流式输出时可能停在 ** 或 ` 中间，先裁掉未闭合的尾巴，避免满屏星号
  const sanitized = trimUnclosedMarkup(text)
  const lines = sanitized.replace(/\r\n/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let index = 0
  let key = 0

  const inline = (value: string, prefix: string): ReactNode[] =>
    renderInline(value, onCitation, prefix)

  while (index < lines.length) {
    const line = lines[index]

    if (!line.trim()) {
      index += 1
      continue
    }

    // 代码块：保留空行，交给 <pre> 原样展示
    if (line.trimStart().startsWith('```')) {
      const language = line.trim().slice(3).trim()
      const body: string[] = []
      index += 1
      while (index < lines.length && !lines[index].trimStart().startsWith('```')) {
        body.push(lines[index])
        index += 1
      }
      index += 1
      blocks.push(
        <pre
          key={`code-${key++}`}
          className="my-2 overflow-x-auto rounded-xl bg-slate-900 px-3.5 py-3 text-[12px] leading-relaxed text-slate-100"
        >
          <code>{body.join('\n')}</code>
          {language && (
            <span className="mt-1 block text-[10px] uppercase tracking-wide text-slate-500">{language}</span>
          )}
        </pre>
      )
      continue
    }

    const heading = /^(#{1,4})\s+(.*)$/.exec(line)
    if (heading) {
      const level = heading[1].length
      const size = level <= 2 ? 'text-[15px]' : 'text-[14px]'
      blocks.push(
        <p key={`h-${key++}`} className={`mt-3 mb-1 ${size} font-semibold text-slate-900`}>
          {inline(heading[2], `h-${key}`)}
        </p>
      )
      index += 1
      continue
    }

    if (/^[-*]\s+/.test(line)) {
      const items: string[] = []
      while (index < lines.length && /^[-*]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^[-*]\s+/, ''))
        index += 1
      }
      blocks.push(
        <ul key={`ul-${key++}`} className="my-1.5 space-y-1 pl-4">
          {items.map((item, itemIndex) => (
            <li key={itemIndex} className="relative text-[13.5px] leading-relaxed text-slate-700">
              <span className="absolute -left-3 top-[0.62em] h-1 w-1 rounded-full bg-primary-400" />
              {inline(item, `ul-${key}-${itemIndex}`)}
            </li>
          ))}
        </ul>
      )
      continue
    }

    if (/^\d+[.)]\s+/.test(line)) {
      const items: string[] = []
      while (index < lines.length && /^\d+[.)]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\d+[.)]\s+/, ''))
        index += 1
      }
      blocks.push(
        <ol key={`ol-${key++}`} className="my-1.5 space-y-1 pl-5">
          {items.map((item, itemIndex) => (
            <li key={itemIndex} className="list-decimal text-[13.5px] leading-relaxed text-slate-700">
              {inline(item, `ol-${key}-${itemIndex}`)}
            </li>
          ))}
        </ol>
      )
      continue
    }

    if (line.startsWith('>')) {
      const quote: string[] = []
      while (index < lines.length && lines[index].startsWith('>')) {
        quote.push(lines[index].replace(/^>\s?/, ''))
        index += 1
      }
      blocks.push(
        <blockquote
          key={`quote-${key++}`}
          className="my-2 rounded-r-lg border-l-2 border-primary-200 bg-primary-50/50 px-3 py-2 text-[13px] text-slate-600"
        >
          {inline(quote.join(' '), `quote-${key}`)}
        </blockquote>
      )
      continue
    }

    const paragraph: string[] = []
    while (index < lines.length) {
      const current = lines[index]
      if (
        !current.trim() ||
        current.trimStart().startsWith('```') ||
        /^(#{1,4})\s+/.test(current) ||
        /^[-*]\s+/.test(current) ||
        /^\d+[.)]\s+/.test(current) ||
        current.startsWith('>')
      ) {
        break
      }
      paragraph.push(current)
      index += 1
    }
    blocks.push(
      <p
        key={`p-${key++}`}
        className="whitespace-pre-wrap text-[13.5px] leading-[1.75] text-slate-700"
      >
        {inline(paragraph.join('\n'), `p-${key}`)}
      </p>
    )
  }

  return <>{blocks}</>
}

function trimUnclosedMarkup(text: string): string {
  let value = text
  const fences = (value.match(/```/g) ?? []).length
  if (fences % 2 === 1) {
    value = value.slice(0, value.lastIndexOf('```'))
  }
  const bolds = (value.match(/\*\*/g) ?? []).length
  if (bolds % 2 === 1) {
    value = value.slice(0, value.lastIndexOf('**'))
  }
  const ticks = (value.match(/`/g) ?? []).length
  if (ticks % 2 === 1) {
    value = value.slice(0, value.lastIndexOf('`'))
  }
  return value
}

function renderInline(text: string, onCitation: (index: number) => void, keyPrefix: string): ReactNode[] {
  const tokens = text.split(/(\*\*[^*]+\*\*|`[^`]+`|\[\d{1,2}\])/g)
  return tokens.map((token, index) => {
    const key = `${keyPrefix}-${index}`
    if (!token) {
      return null
    }
    if (token.startsWith('**') && token.endsWith('**') && token.length > 4) {
      return (
        <strong key={key} className="font-semibold text-slate-900">
          {token.slice(2, -2)}
        </strong>
      )
    }
    if (token.startsWith('`') && token.endsWith('`') && token.length > 2) {
      return (
        <code key={key} className="rounded bg-slate-100 px-1 py-0.5 font-mono text-[12px] text-primary-700">
          {token.slice(1, -1)}
        </code>
      )
    }
    const citation = /^\[(\d{1,2})\]$/.exec(token)
    if (citation) {
      const number = Number(citation[1])
      return (
        <button
          key={key}
          type="button"
          onClick={() => onCitation(number)}
          title={`查看引用 ${number}`}
          className="mx-0.5 inline-flex h-[17px] min-w-[17px] items-center justify-center rounded bg-primary-50 px-1 align-[1px] text-[10px] font-semibold text-primary-600 transition-colors hover:bg-primary-100"
        >
          {number}
        </button>
      )
    }
    return <Fragment key={key}>{token}</Fragment>
  })
}
