import { createElement, type ReactNode } from 'react'
import type { Block, Level } from '@/types/api'

export interface MarkAnchor {
  pointId: string
  start: number
  end: number
  level: Level
}

interface Props {
  blocks: Block[]
  anchors: Map<string, MarkAnchor[]>
  hoveredId: string | null
  onHover: (id: string | null) => void
  registerMark: (pointId: string, el: HTMLElement | null) => void
}

const BLOCK_CLASS: Record<string, string> = {
  TITLE: 'font-semibold text-slate-900',
  CLAUSE: 'font-semibold text-slate-700',
  PARAGRAPH: 'text-[13.5px] leading-7 text-slate-700',
  LIST_ITEM: 'text-[13.5px] leading-7 text-slate-700',
  TABLE_ROW: 'font-mono text-xs text-slate-500'
}

function tagOf(block: Block): string {
  switch (block.type) {
    case 'TITLE':
      return block.level <= 1 ? 'h1' : block.level === 2 ? 'h2' : 'h3'
    case 'LIST_ITEM':
      return 'li'
    default:
      return 'p'
  }
}

function sizeOf(block: Block): string {
  if (block.type === 'TITLE') {
    return block.level <= 1 ? 'text-xl' : block.level === 2 ? 'text-base' : 'text-sm text-slate-600'
  }
  if (block.type === 'CLAUSE') {
    return 'text-sm'
  }
  return ''
}

/** 用 mark 包住 anchor 区间；重叠时后一条被裁剪，保证不出现嵌套高亮。 */
function renderText(
  text: string,
  marks: MarkAnchor[],
  hoveredId: string | null,
  onHover: (id: string | null) => void,
  registerMark: (pointId: string, el: HTMLElement | null) => void
): ReactNode[] {
  if (!marks.length) {
    return [text]
  }
  const sorted = [...marks].sort((a, b) => a.start - b.start)
  const nodes: ReactNode[] = []
  let cursor = 0
  for (const mark of sorted) {
    const start = Math.max(mark.start, cursor, 0)
    const end = Math.min(mark.end, text.length)
    if (end <= start) {
      continue
    }
    if (start > cursor) {
      nodes.push(text.slice(cursor, start))
    }
    const piece = text.slice(start, end)
    nodes.push(
      <mark
        key={`${mark.pointId}-${start}`}
        data-point-id={mark.pointId}
        data-level={mark.level}
        className={hoveredId === mark.pointId ? 'is-hovered' : undefined}
        onMouseEnter={() => onHover(mark.pointId)}
        onMouseLeave={() => onHover(null)}
        ref={el => registerMark(mark.pointId, el)}
      >
        {piece}
      </mark>
    )
    cursor = end
  }
  if (cursor < text.length) {
    nodes.push(text.slice(cursor))
  }
  return nodes
}

/**
 * 正文纸张。悬浮某条批注时**只加重当前句**，其余段落保持原样——
 * 之前把非当前段落整体调暗 + 模糊，整页就读不下去了。
 */
export function ResumePaper({ blocks, anchors, hoveredId, onHover, registerMark }: Props) {
  return (
    <article className="paper">
      {blocks.map(block => {
        const marks = anchors.get(block.id) || []
        const content = renderText(block.text, marks, hoveredId, onHover, registerMark)
        const className = [
          'paper-block',
          BLOCK_CLASS[block.type] || BLOCK_CLASS.PARAGRAPH,
          sizeOf(block),
          block.type === 'TITLE' ? 'mt-6 mb-2 first:mt-0' : block.type === 'CLAUSE' ? 'mt-4 mb-1' : 'my-1',
          block.type === 'LIST_ITEM' ? 'ml-5 list-disc' : ''
        ]
          .filter(Boolean)
          .join(' ')
        return createElement(tagOf(block), { key: block.id, 'data-block-id': block.id, className }, content)
      })}
    </article>
  )
}
