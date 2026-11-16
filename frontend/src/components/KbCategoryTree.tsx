import { useState } from 'react'
import {
  ChevronDown,
  ChevronRight,
  Folder,
  FolderOpen,
  Inbox,
  Pencil,
  Plus,
  Trash2,
  X
} from 'lucide-react'
import type { KbCatalogNode } from '@/api/catalog'

type DropZone = 'before' | 'after' | 'inside'

interface Props {
  nodes: KbCatalogNode[]
  kind: string
  selectedId: number | null
  expanded: Set<number>
  canWrite: boolean
  onSelect: (id: number) => void
  onToggle: (id: number) => void
  onExpand: (id: number) => void
  onCreate: (parentId: number | null, name: string) => Promise<void>
  onRename: (id: number, name: string) => Promise<void>
  onMove: (id: number, parentId: number | null, beforeId: number | null) => Promise<void>
  onDelete: (node: KbCatalogNode, cascade: boolean) => Promise<void>
}

const MAX_DEPTH = 3

/** 目录树：就地改（双击改名 / ＋建子级 / 拖拽移动与排序 / 行内确认删除）。 */
export default function KbCategoryTree({
  nodes,
  kind,
  selectedId,
  expanded,
  canWrite,
  onSelect,
  onToggle,
  onExpand,
  onCreate,
  onRename,
  onMove,
  onDelete
}: Props) {
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [creatingUnder, setCreatingUnder] = useState<number | 'root' | null>(null)
  const [newName, setNewName] = useState('')
  const [confirmId, setConfirmId] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [dragId, setDragId] = useState<number | null>(null)
  const [drop, setDrop] = useState<{ id: number; zone: DropZone } | null>(null)

  const startRename = (node: KbCatalogNode) => {
    setConfirmId(null)
    setEditingId(node.id)
    setDraft(node.name)
  }

  const commitRename = async (node: KbCatalogNode) => {
    const next = draft.trim()
    setEditingId(null)
    if (!next || next === node.name) return
    setBusy(true)
    try {
      await onRename(node.id, next)
    } finally {
      setBusy(false)
    }
  }

  const commitCreate = async (parentId: number | null) => {
    const next = newName.trim()
    if (!next) {
      setCreatingUnder(null)
      return
    }
    setBusy(true)
    try {
      await onCreate(parentId, next)
      setNewName('')
      setCreatingUnder(null)
    } finally {
      setBusy(false)
    }
  }

  const handleDrop = async (target: KbCatalogNode, zone: DropZone) => {
    const source = dragId
    setDragId(null)
    setDrop(null)
    if (source == null || source === target.id) return
    // 不能把节点拖进自己的子树（会形成环）
    if (isDescendant(nodes, source, target.id)) return
    if (zone === 'inside') {
      if (target.depth >= MAX_DEPTH) return
      await onMove(source, target.id, null)
      onExpand(target.id)
      return
    }
    const siblings = siblingsOf(nodes, target.parentId)
    const index = siblings.findIndex(node => node.id === target.id)
    const beforeId = zone === 'before' ? target.id : (siblings[index + 1]?.id ?? null)
    await onMove(source, target.parentId === 0 ? null : target.parentId, beforeId)
  }

  const renderRow = (node: KbCatalogNode, depth: number) => {
    const open = expanded.has(node.id)
    const active = selectedId === node.id
    const count = kind === 'QUESTION_BANK' ? node.questionCount : node.chunkCount
    const Icon = node.system ? Inbox : open || node.children.length === 0 ? FolderOpen : Folder
    const isEditing = editingId === node.id
    const isConfirming = confirmId === node.id
    const dropZone = drop?.id === node.id ? drop.zone : null
    const canDropInside = canWrite && !node.system && node.depth < MAX_DEPTH

    return (
      <div key={node.id}>
        <div
          draggable={canWrite && !node.system && !isEditing}
          onDragStart={event => {
            setDragId(node.id)
            event.dataTransfer.effectAllowed = 'move'
            // Firefox 需要 setData 才肯开始拖拽
            event.dataTransfer.setData('text/plain', String(node.id))
          }}
          onDragEnd={() => {
            setDragId(null)
            setDrop(null)
          }}
          onDragOver={event => {
            if (!canWrite || node.system || dragId == null || dragId === node.id) return
            event.preventDefault()
            const rect = event.currentTarget.getBoundingClientRect()
            const offset = event.clientY - rect.top
            const zone: DropZone =
              offset < rect.height * 0.3 ? 'before' : offset > rect.height * 0.7 ? 'after' : 'inside'
            setDrop({ id: node.id, zone: zone === 'inside' && !canDropInside ? 'after' : zone })
          }}
          onDragLeave={() => setDrop(prev => (prev?.id === node.id ? null : prev))}
          onDrop={event => {
            event.preventDefault()
            const zone = dropZone ?? 'inside'
            void handleDrop(node, zone)
          }}
          className={`group relative flex items-center gap-1.5 rounded-lg py-1.5 pr-1 text-left text-[13px] transition-colors ${
            active
              ? 'bg-primary-50/80 font-medium text-primary-700'
              : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
          } ${dragId === node.id ? 'opacity-50' : ''} ${
            dropZone === 'inside' ? 'ring-1 ring-inset ring-primary-400' : ''
          }`}
          style={{ paddingLeft: 8 + depth * 14 }}
        >
          {dropZone === 'before' && (
            <span className="pointer-events-none absolute inset-x-1 -top-px h-0.5 rounded bg-primary-400" />
          )}
          {dropZone === 'after' && (
            <span className="pointer-events-none absolute inset-x-1 -bottom-px h-0.5 rounded bg-primary-400" />
          )}

          {node.children.length > 0 ? (
            <button
              type="button"
              onClick={event => {
                event.stopPropagation()
                onToggle(node.id)
              }}
              aria-label={open ? '折叠' : '展开'}
              className="flex h-4 w-4 shrink-0 items-center justify-center text-slate-400 hover:text-slate-600"
            >
              {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
            </button>
          ) : (
            <span className="h-4 w-4 shrink-0" />
          )}

          <Icon
            className={`h-4 w-4 shrink-0 ${node.system ? 'text-slate-300' : active ? 'text-primary-500' : 'text-slate-400'}`}
          />

          {isEditing ? (
            <input
              autoFocus
              value={draft}
              onChange={event => setDraft(event.target.value)}
              onKeyDown={event => {
                if (event.key === 'Enter') void commitRename(node)
                if (event.key === 'Escape') setEditingId(null)
              }}
              onBlur={() => void commitRename(node)}
              className="min-w-0 flex-1 rounded border border-primary-300 px-1.5 py-0.5 text-[13px] outline-none"
            />
          ) : (
            <button
              type="button"
              onClick={() => onSelect(node.id)}
              onDoubleClick={event => {
                event.preventDefault()
                if (canWrite && !node.system) startRename(node)
              }}
              className="min-w-0 flex-1 truncate text-left"
              title={canWrite && !node.system ? `${node.pathKey || node.name}（双击改名）` : node.pathKey || node.name}
            >
              {node.name}
            </button>
          )}

          {!isEditing && !isConfirming && (
            <>
              {count > 0 && (
                <span
                  className={`rounded px-1.5 py-0.5 text-[10px] tabular-nums ${
                    active ? 'bg-white/70 text-primary-600' : 'bg-slate-100 text-slate-400'
                  }`}
                >
                  {count}
                </span>
              )}
              {canWrite && !node.system && (
                <span className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                  {node.depth < MAX_DEPTH && (
                    <button
                      type="button"
                      title="新建子目录"
                      aria-label={`在 ${node.name} 下新建目录`}
                      onClick={event => {
                        event.stopPropagation()
                        setConfirmId(null)
                        setCreatingUnder(node.id)
                        setNewName('')
                        onExpand(node.id)
                        onSelect(node.id)
                      }}
                      className="rounded p-1 text-slate-400 hover:bg-white hover:text-primary-600"
                    >
                      <Plus className="h-3.5 w-3.5" />
                    </button>
                  )}
                  <button
                    type="button"
                    title="重命名"
                    aria-label={`重命名 ${node.name}`}
                    onClick={event => {
                      event.stopPropagation()
                      startRename(node)
                    }}
                    className="rounded p-1 text-slate-400 hover:bg-white hover:text-slate-700"
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    title="删除"
                    aria-label={`删除 ${node.name}`}
                    onClick={event => {
                      event.stopPropagation()
                      setEditingId(null)
                      setConfirmId(node.id)
                    }}
                    className="rounded p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-500"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </span>
              )}
            </>
          )}

          {busy && isConfirming && (
            <span className="shrink-0 text-[10px] text-rose-500">处理中…</span>
          )}
        </div>

        {isConfirming && (
          <div
            className="mt-1 flex flex-wrap items-center gap-2 rounded-lg bg-rose-50 px-2 py-1.5 text-[11px] text-rose-700"
            style={{ marginLeft: 8 + depth * 14 }}
          >
            <span className="min-w-0 flex-1">
              {deleteHint(node, count)}
            </span>
            <button
              type="button"
              disabled={busy}
              onClick={async () => {
                setBusy(true)
                try {
                  await onDelete(node, true)
                } finally {
                  setBusy(false)
                  setConfirmId(null)
                }
              }}
              className="rounded bg-rose-600 px-2 py-1 font-medium text-white hover:bg-rose-700 disabled:opacity-50"
            >
              删除
            </button>
            <button
              type="button"
              onClick={() => setConfirmId(null)}
              className="rounded px-1.5 py-1 hover:bg-rose-100"
              aria-label="取消删除"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        )}

        {open && node.children.length > 0 && (
          <div>
            {node.children.map(child => renderRow(child, depth + 1))}
          </div>
        )}

        {creatingUnder === node.id && (
          <div
            className="flex items-center gap-1.5 py-1"
            style={{ paddingLeft: 8 + (depth + 1) * 14 }}
          >
            <Folder className="h-4 w-4 shrink-0 text-slate-300" />
            <input
              autoFocus
              value={newName}
              placeholder="新目录名，回车创建 / Esc 取消"
              onChange={event => setNewName(event.target.value)}
              onKeyDown={event => {
                if (event.key === 'Enter') void commitCreate(node.id)
                if (event.key === 'Escape') setCreatingUnder(null)
              }}
              className="min-w-0 flex-1 rounded border border-primary-300 px-1.5 py-0.5 text-[12px] outline-none"
            />
          </div>
        )}
      </div>
    )
  }

  return (
    <div>
      {nodes.map(node => renderRow(node, 0))}

      {canWrite && (
        creatingUnder === 'root' ? (
          <div className="flex items-center gap-1.5 py-1 pl-2">
            <Folder className="h-4 w-4 shrink-0 text-slate-300" />
            <input
              autoFocus
              value={newName}
              placeholder="新一级目录名，回车创建 / Esc 取消"
              onChange={event => setNewName(event.target.value)}
              onKeyDown={event => {
                if (event.key === 'Enter') void commitCreate(null)
                if (event.key === 'Escape') setCreatingUnder(null)
              }}
              className="min-w-0 flex-1 rounded border border-primary-300 px-1.5 py-0.5 text-[12px] outline-none"
            />
          </div>
        ) : (
          <button
            type="button"
            onClick={() => {
              setCreatingUnder('root')
              setNewName('')
            }}
            className="mt-1 flex w-full items-center gap-1.5 rounded-lg px-2 py-1.5 text-[12px] text-slate-400 transition-colors hover:bg-slate-50 hover:text-slate-600"
          >
            <Plus className="h-3.5 w-3.5" />
            新增一级目录
          </button>
        )
      )}
    </div>
  )
}

/** 删除确认文案：零项不说，避免"删除 X 及 0 个子目录、0 条内容"。 */
function deleteHint(node: KbCatalogNode, count: number): string {
  const parts: string[] = []
  if (node.children.length > 0) parts.push(`${node.children.length} 个子目录`)
  if (count > 0) parts.push(`${count} 条内容`)
  if (parts.length === 0) return `删除「${node.name}」？`
  return `删除「${node.name}」及其 ${parts.join('、')}？内容会回到未分类`
}

function siblingsOf(nodes: KbCatalogNode[], parentId: number): KbCatalogNode[] {  if (parentId === 0) return nodes
  for (const node of nodes) {
    if (node.id === parentId) return node.children
    const found = siblingsOf(node.children, parentId)
    if (found.length > 0) return found
  }
  return []
}

/** 判断 target 是否是 source 的子孙：拖拽时禁止把父节点拖进自己的子树。 */
export function isDescendant(nodes: KbCatalogNode[], sourceId: number, targetId: number): boolean {
  let found = false
  const walk = (list: KbCatalogNode[], inside: boolean) => {
    for (const node of list) {
      const nextInside = inside || node.id === sourceId
      if (nextInside && node.id === targetId) found = true
      walk(node.children, nextInside)
    }
  }
  walk(nodes, false)
  return found
}
