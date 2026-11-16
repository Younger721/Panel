import { Fragment, type ReactNode } from 'react'

/**
 * 简历 Markdown 的预览渲染。
 *
 * <p>刻意做得很宽容：用户在 md 里写什么就是什么，渲染器只负责把认得出的语法画得好看一点，
 * 认不出的一律原样当段落显示 —— 不像聊天那条链路会裁掉未闭合的 `**`、也不做任何"语法纠错"。
 * 用户手写 `##` 层级错了、列表写成 `1、`、表格用 `|` 对齐，预览都不会吞字。</p>
 *
 * <p>为什么不引 react-markdown：这个页面只需要标题/列表/引用/代码/粗斜体/链接，
 * 为一个编辑器挂 remark + unified 整条依赖链不划算（与 {@link renderChatMarkdown} 同一取舍）。</p>
 *
 * <p>注意这里只管"看起来像什么"。落库的是用户敲的原文，结构由后端 markdown 解析器决定
 * （`#` → 标题、`- ` → 列表），前端不做任何回写转换，也就不会出现"预览和结果不一致"的第三方真相。</p>
 */
export function renderResumeMarkdown(text: string): ReactNode {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let index = 0
  let key = 0

  while (index < lines.length) {
    const line = lines[index]

    if (!line.trim()) {
      index += 1
      continue
    }

    // 代码围栏：原样保留缩进和空行，简历里放一段代码/命令很常见
    if (/^\s*(```|~~~)/.test(line)) {
      const fence = line.trimStart().slice(0, 3)
      const language = line.trimStart().slice(3).trim()
      const body: string[] = []
      index += 1
      while (index < lines.length && !lines[index].trimStart().startsWith(fence)) {
        body.push(lines[index])
        index += 1
      }
      index += 1
      blocks.push(
        <pre
          key={`code-${key++}`}
          data-lang={language || undefined}
          className="my-2 overflow-x-auto rounded-lg bg-slate-900 px-3 py-2.5 text-[12px] leading-relaxed text-slate-100"
        >
          <code>{body.join('\n')}</code>
        </pre>
      )
      continue
    }

    const heading = /^\s*(#{1,6})\s+(.*)$/.exec(line)
    if (heading) {
      const level = heading[1].length
      const size = ['text-xl', 'text-lg', 'text-base', 'text-sm', 'text-sm', 'text-sm'][level - 1]
      blocks.push(
        <p
          key={`h-${key++}`}
          className={`mt-3 mb-1 font-semibold text-slate-900 ${size} ${
            level <= 2 ? 'border-b border-slate-200 pb-1' : ''
          }`}
        >
          {inline(heading[2], `h-${key}`)}
        </p>
      )
      index += 1
      continue
    }

    // 分隔线：三个以上 - * _ 且整行只有它们
    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) {
      blocks.push(<hr key={`hr-${key++}`} className="my-3 border-slate-200" />)
      index += 1
      continue
    }

    if (/^\s*[-*+]\s+/.test(line)) {
      const items: string[] = []
      while (index < lines.length && /^\s*[-*+]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*[-*+]\s+/, ''))
        index += 1
      }
      blocks.push(
        <ul key={`ul-${key++}`} className="my-1.5 space-y-1 pl-4">
          {items.map((item, itemIndex) => (
            <li
              key={itemIndex}
              className="relative text-[13.5px] leading-relaxed text-slate-700"
            >
              <span className="absolute -left-3 top-[0.62em] h-1 w-1 rounded-full bg-slate-400" />
              {inline(item, `ul-${key}-${itemIndex}`)}
            </li>
          ))}
        </ul>
      )
      continue
    }

    if (/^\s*\d+[.)]\s+/.test(line)) {
      const items: string[] = []
      while (index < lines.length && /^\s*\d+[.)]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*\d+[.)]\s+/, ''))
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

    if (/^\s*>/.test(line)) {
      const quote: string[] = []
      while (index < lines.length && /^\s*>/.test(lines[index])) {
        quote.push(lines[index].replace(/^\s*>\s?/, ''))
        index += 1
      }
      blocks.push(
        <blockquote
          key={`q-${key++}`}
          className="my-2 border-l-2 border-slate-300 bg-slate-50 px-3 py-1.5 text-[13px] text-slate-600"
        >
          {inline(quote.join(' '), `q-${key}`)}
        </blockquote>
      )
      continue
    }

    // 普通段落：连续非空、且不开启任何块语法的行合成一段
    const paragraph: string[] = []
    while (index < lines.length) {
      const current = lines[index]
      if (
        !current.trim() ||
        /^\s*(```|~~~)/.test(current) ||
        /^\s*#{1,6}\s+/.test(current) ||
        /^\s*[-*+]\s+/.test(current) ||
        /^\s*\d+[.)]\s+/.test(current) ||
        /^\s*>/.test(current) ||
        /^\s*([-*_])\1{2,}\s*$/.test(current)
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

/**
 * 行内语法：粗体、斜体、行内代码、链接。
 *
 * <p>刻意不处理"未闭合"的情况 —— 用户正打到一半的 `**` 应该原样显示在预览里，
 * 而不是像流式回答那样被裁掉（那样用户会以为字丢了）。</p>
 */
function inline(text: string, keyPrefix: string): ReactNode[] {
  const tokens = text.split(/(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\[[^\]]+\]\([^)]+\)|\*[^*\n]+\*)/g)
  return tokens.map((token, index) => {
    const key = `${keyPrefix}-${index}`
    if (!token) {
      return null
    }
    if ((token.startsWith('**') && token.endsWith('**')) || (token.startsWith('__') && token.endsWith('__'))) {
      return (
        <strong key={key} className="font-semibold text-slate-900">
          {token.slice(2, -2)}
        </strong>
      )
    }
    if (token.startsWith('`') && token.endsWith('`') && token.length > 2) {
      return (
        <code key={key} className="rounded bg-slate-100 px-1 py-0.5 font-mono text-[12px] text-indigo-700">
          {token.slice(1, -1)}
        </code>
      )
    }
    if (token.startsWith('*') && token.endsWith('*') && token.length > 2) {
      return (
        <em key={key} className="italic">
          {token.slice(1, -1)}
        </em>
      )
    }
    const link = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(token)
    if (link) {
      return (
        <a
          key={key}
          href={link[2]}
          target="_blank"
          rel="noreferrer"
          className="text-indigo-600 underline decoration-indigo-200 underline-offset-2 hover:text-indigo-700"
        >
          {link[1]}
        </a>
      )
    }
    return <Fragment key={key}>{token}</Fragment>
  })
}
