import { useEffect, useMemo, useState } from 'react'
import { Briefcase, BookOpen, Database, FileText, FolderTree, Globe, Loader2, Lock, Plus, Trash2, X, ChevronRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { kbApi, type KbKind, type KbVisibility, type KnowledgeBaseView } from '@/api/kb'
import { hasPermission } from '@/auth/actor'

const KIND_LABEL: Record<KbKind, string> = {
  QUESTION_BANK: '面试题库',
  TECH_DOC: '技术知识',
  JOB_KNOWLEDGE: '岗位与公司',
  CUSTOM: '自定义'
}

const KIND_ICON: Record<KbKind, typeof Database> = {
  QUESTION_BANK: BookOpen,
  TECH_DOC: FileText,
  JOB_KNOWLEDGE: Briefcase,
  CUSTOM: Database
}

const KIND_STYLE: Record<KbKind, { tile: string; dot: string }> = {
  QUESTION_BANK: { tile: 'bg-rose-50 text-rose-500 ring-rose-100', dot: 'bg-rose-400' },
  TECH_DOC: { tile: 'bg-emerald-50 text-emerald-600 ring-emerald-100', dot: 'bg-emerald-400' },
  JOB_KNOWLEDGE: { tile: 'bg-amber-50 text-amber-600 ring-amber-100', dot: 'bg-amber-400' },
  CUSTOM: { tile: 'bg-indigo-50 text-indigo-500 ring-indigo-100', dot: 'bg-indigo-400' }
}

const KIND_OPTIONS: { value: KbKind; label: string }[] = [
  { value: 'QUESTION_BANK', label: '面试题库（一题一片）' },
  { value: 'TECH_DOC', label: '技术知识（按标题切片）' },
  { value: 'JOB_KNOWLEDGE', label: '岗位与公司（JD / 面经）' },
  { value: 'CUSTOM', label: '自定义' }
]

const BADGE =
  'inline-flex items-center rounded-md bg-slate-50 px-2 py-1 text-[11px] font-medium text-slate-500 ring-1 ring-inset ring-slate-200/70'
const INPUT =
  'w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none transition-colors placeholder:text-slate-300 focus:border-primary-400 focus:ring-2 focus:ring-primary-100'

export default function KnowledgeBaseListPage() {
  const [items, setItems] = useState<KnowledgeBaseView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const canWrite = useMemo(() => hasPermission('knowledge:write'), [])

  useEffect(() => {
    let cancelled = false
    kbApi
      .list()
      .then(list => {
        if (!cancelled) setItems(list)
      })
      .catch(err => {
        if (!cancelled) setError(err instanceof Error ? err.message : '加载失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  async function remove(item: KnowledgeBaseView) {
    if (item.builtin) return
    if (!window.confirm(`删除知识库「${item.name}」？库里的文档与切片会一起删除，且不可恢复。`)) return
    try {
      await kbApi.remove(item.id)
      setItems(prev => prev.filter(base => base.id !== item.id))
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  return (
    <div className="mx-auto max-w-5xl">
      <div className="mb-8 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">知识库</h1>
          <p className="mt-1 text-sm text-slate-500">
            按库组织资料：题库用于出题，技术文档和岗位资料用于评分与候选人问答。
          </p>
        </div>
        {canWrite && (
          <button type="button" className="btn-primary" onClick={() => setCreating(true)}>
            <Plus className="h-4 w-4" />
            新建知识库
          </button>
        )}
      </div>

      {error && (
        <div className="mb-6 flex items-start gap-3 rounded-xl border border-rose-100 bg-rose-50 px-4 py-3 text-sm text-rose-600">
          <span className="flex-1">{error}</span>
          <button type="button" onClick={() => setError(null)} aria-label="关闭">
            <X className="h-4 w-4" />
          </button>
        </div>
      )}

      {loading ? (
        <p className="text-sm text-slate-400">加载中…</p>
      ) : items.length === 0 ? (
        <div className="card flex flex-col items-center justify-center py-20">
          <Database className="h-10 w-10 text-slate-300" />
          <p className="mt-4 text-sm text-slate-500">还没有知识库</p>
          {canWrite && (
            <button type="button" className="btn-primary mt-6" onClick={() => setCreating(true)}>
              新建第一个知识库
            </button>
          )}
        </div>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {items.map(item => {
            const KindIcon = KIND_ICON[item.kind] ?? Database
            const style = KIND_STYLE[item.kind] ?? KIND_STYLE.CUSTOM
            return (
              <div
                key={item.id}
                className="group relative flex flex-col rounded-2xl border border-slate-200/80 bg-white p-5 shadow-[0_1px_2px_rgba(15,23,42,0.03)] transition-all duration-200 hover:border-slate-300/80 hover:shadow-[0_12px_32px_-16px_rgba(15,23,42,0.18)]"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex min-w-0 items-center gap-3">
                    <span
                      className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ring-1 ring-inset ${style.tile}`}
                    >
                      <KindIcon className="h-[18px] w-[18px]" />
                    </span>
                    <div className="min-w-0">
                      <p className="truncate text-[15px] font-semibold tracking-tight text-slate-900">{item.name}</p>
                      <p className="mt-0.5 truncate font-mono text-[11px] tracking-tight text-slate-400">{item.code}</p>
                    </div>
                  </div>
                  {!item.builtin && canWrite && (
                    <button
                      type="button"
                      className="rounded-lg p-1.5 text-slate-400 opacity-70 transition-all hover:bg-rose-50 hover:text-rose-500 focus-visible:opacity-100 group-hover:opacity-100"
                      onClick={() => remove(item)}
                      aria-label={`删除 ${item.name}`}
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  )}
                </div>

                <p className="mt-4 line-clamp-2 min-h-[2.75rem] text-[13px] leading-[1.55] text-slate-500">
                  {item.description || '暂无描述'}
                </p>

                <div className="mt-4 flex flex-wrap items-center gap-1.5">
                  <span className={BADGE}>
                    <span className={`mr-1.5 h-1.5 w-1.5 rounded-full ${style.dot}`} />
                    {KIND_LABEL[item.kind] ?? item.kind}
                  </span>
                  <span className={BADGE}>
                    {item.visibility === 'PUBLIC' ? (
                      <Globe className="mr-1 h-3 w-3" />
                    ) : (
                      <Lock className="mr-1 h-3 w-3" />
                    )}
                    {item.visibility === 'PUBLIC' ? '公开' : '内部'}
                  </span>
                  {item.builtin && <span className={BADGE}>内置</span>}
                </div>

                <div className="mt-auto flex items-center justify-between border-t border-slate-100 pt-4 text-[11px] text-slate-400">
                  <span className="tabular-nums">
                    文档 {item.documentCount} · 切片 {item.chunkCount}
                  </span>
                  <span className="flex items-center gap-3">
                    <Link
                      to={`/kb/${item.id}/catalog`}
                      className="inline-flex items-center gap-1 text-xs font-medium text-slate-400 transition-colors hover:text-primary-600"
                    >
                      <FolderTree className="h-3.5 w-3.5" />
                      目录
                    </Link>
                    <Link
                      to={`/kb/${item.id}`}
                      className="inline-flex items-center gap-0.5 text-xs font-medium text-slate-600 transition-colors hover:text-primary-600"
                    >
                      进入
                      <ChevronRight className="h-3.5 w-3.5 transition-transform duration-200 group-hover:translate-x-0.5" />
                    </Link>
                  </span>
                </div>
              </div>
            )
          })}
        </div>
      )}

      <p className="mt-8 text-xs text-slate-400">
        先上传资料：题库库会按题号拆成题目，技术文档会切成知识点并自动挂到目录上。
      </p>

      {creating && (
        <CreateKnowledgeBaseDialog
          onClose={() => setCreating(false)}
          onCreated={created => {
            setItems(prev => [...prev, created])
            setCreating(false)
          }}
        />
      )}
    </div>
  )
}

function CreateKnowledgeBaseDialog({
  onClose,
  onCreated
}: {
  onClose: () => void
  onCreated: (created: KnowledgeBaseView) => void
}) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [kind, setKind] = useState<KbKind>('TECH_DOC')
  const [visibility, setVisibility] = useState<KbVisibility>('INTERNAL')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (saving) return
    if (!code.trim() || !name.trim()) {
      setError('code 和名称不能为空')
      return
    }
    setSaving(true)
    setError(null)
    try {
      const created = await kbApi.create({
        code: code.trim(),
        name: name.trim(),
        description: description.trim() || null,
        kind,
        visibility
      })
      onCreated(created)
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/30 p-4">
      <form onSubmit={submit} className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-xl">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">新建知识库</h2>
            <p className="mt-1 text-xs text-slate-400">code 创建后不可修改，用来做检索与接口标识。</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭" className="rounded-lg p-1 text-slate-400 hover:bg-slate-50">
            <X className="h-4 w-4" />
          </button>
        </div>

        {error && <p className="mt-4 rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-600">{error}</p>}

        <div className="mt-5 grid gap-4">
          <label className="grid gap-1.5 text-xs text-slate-500">
            <span>code</span>
            <input
              className={INPUT}
              value={code}
              onChange={event => setCode(event.target.value.toLowerCase())}
              placeholder="例如 frontend-interview"
              maxLength={64}
              autoFocus
            />
          </label>
          <label className="grid gap-1.5 text-xs text-slate-500">
            <span>名称</span>
            <input
              className={INPUT}
              value={name}
              onChange={event => setName(event.target.value)}
              placeholder="例如 前端面试题库"
              maxLength={128}
            />
          </label>
          <label className="grid gap-1.5 text-xs text-slate-500">
            <span>描述（可选）</span>
            <textarea
              className={`${INPUT} min-h-[64px]`}
              value={description}
              onChange={event => setDescription(event.target.value)}
              placeholder="这个库放什么资料"
              maxLength={512}
            />
          </label>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="grid gap-1.5 text-xs text-slate-500">
              <span>类型</span>
              <select className={INPUT} value={kind} onChange={event => setKind(event.target.value as KbKind)}>
                {KIND_OPTIONS.map(option => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="grid gap-1.5 text-xs text-slate-500">
              <span>可见性</span>
              <select
                className={INPUT}
                value={visibility}
                onChange={event => setVisibility(event.target.value as KbVisibility)}
              >
                <option value="INTERNAL">内部（仅运营/面试官）</option>
                <option value="PUBLIC">公开（候选人可见）</option>
              </select>
            </label>
          </div>
        </div>

        <div className="mt-6 flex justify-end gap-3">
          <button type="button" className="btn-ghost" onClick={onClose} disabled={saving}>
            取消
          </button>
          <button type="submit" className="btn-primary" disabled={saving}>
            {saving && <Loader2 className="h-4 w-4 animate-spin" />}
            创建
          </button>
        </div>
      </form>
    </div>
  )
}
