import { useEffect, useMemo, useState } from 'react'
import { BookOpen, Building2, CalendarDays, FileText, Loader2, Search, Tag, X } from 'lucide-react'
import { kbApi, type KnowledgeBaseView } from '@/api/kb'
import KbTabs from '@/components/KbTabs'
import {
  questionApi,
  type KbQuestionDifficulty,
  type KbQuestionReferenceView,
  type KbQuestionView
} from '@/api/questions'
import { renderChatMarkdown } from '@/utils/chatMarkdown'

const DIFFICULTIES: { value: KbQuestionDifficulty; label: string; className: string }[] = [
  { value: 'EASY', label: '简单', className: 'bg-emerald-50 text-emerald-600 border-emerald-100' },
  { value: 'MEDIUM', label: '中等', className: 'bg-amber-50 text-amber-600 border-amber-100' },
  { value: 'HARD', label: '困难', className: 'bg-rose-50 text-rose-600 border-rose-100' }
]

function difficultyMeta(value: KbQuestionDifficulty | null) {
  return DIFFICULTIES.find(item => item.value === value)
}

export default function KbQuestionBankPage() {
  const [bases, setBases] = useState<KnowledgeBaseView[]>([])
  const [kbId, setKbId] = useState<number | null>(null)
  const [items, setItems] = useState<KbQuestionView[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [keyword, setKeyword] = useState('')
  const [difficulty, setDifficulty] = useState<KbQuestionDifficulty | 'ALL'>('ALL')
  const [company, setCompany] = useState<string>('ALL')
  const [selected, setSelected] = useState<KbQuestionView | null>(null)
  const [detail, setDetail] = useState<KbQuestionView | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [references, setReferences] = useState<KbQuestionReferenceView[]>([])
  const [refsLoading, setRefsLoading] = useState(false)

  // 列表接口不带答案，选中后再拉详情：这样浏览列表不会提前看到答案。
  const shown = detail && detail.id === selected?.id ? detail : selected

  useEffect(() => {
    let cancelled = false
    kbApi
      .list()
      .then(list => {
        if (cancelled) return
        const banks = list.filter(item => item.kind === 'QUESTION_BANK')
        setBases(banks)
        setKbId(banks[0]?.id ?? null)
      })
      .catch(err => {
        if (!cancelled) setError(err instanceof Error ? err.message : '知识库加载失败')
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (kbId == null) return
    let cancelled = false
    setLoading(true)
    setError(null)
    questionApi
      .list(kbId, 100)
      .then(page => {
        if (cancelled) return
        setItems(page.items)
        setTotal(page.total)
        setSelected(page.items[0] ?? null)
      })
      .catch(err => {
        if (!cancelled) setError(err instanceof Error ? err.message : '题目加载失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [kbId])

  useEffect(() => {
    if (!selected) {
      setDetail(null)
      return
    }
    if (detail && detail.id === selected.id) return
    let cancelled = false
    setDetailLoading(true)
    questionApi
      .detail(selected.id)
      .then(view => {
        if (!cancelled) setDetail(view)
      })
      .catch(() => {
        if (!cancelled) setDetail(null)
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [selected, detail])

  useEffect(() => {
    if (!selected) {
      setReferences([])
      return
    }
    let cancelled = false
    setReferences([])
    setRefsLoading(true)
    questionApi
      .references(selected.id)
      .then(list => {
        if (!cancelled) setReferences(list)
      })
      .catch(() => {
        if (!cancelled) setReferences([])
      })
      .finally(() => {
        if (!cancelled) setRefsLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [selected])

  const companies = useMemo(() => {
    const values = new Set<string>()
    items.forEach(item => {
      if (item.company) values.add(item.company)
    })
    return Array.from(values).sort((a, b) => a.localeCompare(b, 'zh-CN'))
  }, [items])

  const filtered = useMemo(() => {
    const query = keyword.trim().toLowerCase()
    return items.filter(item => {
      if (difficulty !== 'ALL' && item.difficulty !== difficulty) return false
      if (company !== 'ALL' && item.company !== company) return false
      if (!query) return true
      // 列表接口不再下发答案，本地搜索只匹配题干和标签。
      const haystack = [item.stem, item.tags.join(' ')].join(' ').toLowerCase()
      return haystack.includes(query)
    })
  }, [items, keyword, difficulty, company])

  const activeBase = bases.find(item => item.id === kbId) ?? null

  return (
    <div className="flex h-[calc(100vh-5rem)] flex-col gap-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-xl font-semibold text-slate-900">
            <BookOpen className="h-5 w-5 text-primary-500" />
            面试题库
          </h1>
          <p className="mt-1 text-xs text-slate-500">
            题干是检索主字段；按难度、公司、标签筛选。答案里的每个结论后续都能跳到文档段落。
          </p>
        </div>
        <span className="rounded-full bg-primary-50 px-3 py-1 text-[11px] font-medium text-primary-600">
          {total} 道题
        </span>
      </header>

      {kbId != null && <KbTabs kbId={kbId} active="practice" />}

      {bases.length === 0 && !loading && (
        <div className="flex flex-1 flex-col items-center justify-center rounded-2xl border border-dashed border-slate-200 bg-white text-center">
          <BookOpen className="h-8 w-8 text-slate-300" />
          <p className="mt-3 text-sm font-medium text-slate-700">还没有题库型知识库</p>
          <p className="mt-1 text-xs text-slate-400">在「我的资料」里新建一个类型为 QUESTION_BANK 的库，再上传题库 md。</p>
        </div>
      )}

      {bases.length > 0 && (
        <div className="flex min-h-0 flex-1 gap-4">
          <section className="flex min-h-0 min-w-0 flex-1 flex-col rounded-2xl border border-slate-200 bg-white shadow-sm">
            <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 p-3">
              <select
                value={kbId ?? ''}
                onChange={event => setKbId(Number(event.target.value))}
                className="h-8 rounded-lg border border-slate-200 bg-white px-2 text-xs text-slate-700 outline-none focus:border-primary-300"
              >
                {bases.map(base => (
                  <option key={base.id} value={base.id}>
                    {base.name}
                  </option>
                ))}
              </select>

              <div className="relative min-w-[160px] flex-1">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
                <input
                  value={keyword}
                  onChange={event => setKeyword(event.target.value)}
                  placeholder="搜题干、答案或标签"
                  className="h-8 w-full rounded-lg border border-slate-200 bg-slate-50 pl-8 pr-7 text-xs text-slate-700 outline-none transition focus:border-primary-300 focus:bg-white"
                />
                {keyword && (
                  <button
                    type="button"
                    onClick={() => setKeyword('')}
                    className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-slate-400 hover:text-slate-600"
                    aria-label="清空搜索"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>

              <select
                value={company}
                onChange={event => setCompany(event.target.value)}
                className="h-8 rounded-lg border border-slate-200 bg-white px-2 text-xs text-slate-700 outline-none focus:border-primary-300"
              >
                <option value="ALL">全部公司</option>
                {companies.map(item => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>

              <div className="flex items-center gap-1 rounded-lg bg-slate-100 p-0.5">
                <button
                  type="button"
                  onClick={() => setDifficulty('ALL')}
                  className={`rounded-md px-2 py-1 text-[11px] transition ${
                    difficulty === 'ALL' ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500'
                  }`}
                >
                  全部
                </button>
                {DIFFICULTIES.map(item => (
                  <button
                    key={item.value}
                    type="button"
                    onClick={() => setDifficulty(item.value)}
                    className={`rounded-md px-2 py-1 text-[11px] transition ${
                      difficulty === item.value ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500'
                    }`}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
            </div>

            {error && (
              <div className="border-b border-rose-100 bg-rose-50 px-3 py-2 text-[11px] text-rose-600">{error}</div>
            )}

            <div className="min-h-0 flex-1 overflow-y-auto">
              {loading && (
                <div className="flex h-full items-center justify-center text-slate-400">
                  <Loader2 className="h-5 w-5 animate-spin" />
                </div>
              )}
              {!loading && filtered.length === 0 && (
                <div className="flex h-full flex-col items-center justify-center text-center">
                  <p className="text-sm text-slate-500">没有匹配的题目</p>
                  <p className="mt-1 text-xs text-slate-400">换个关键词，或把筛选条件放宽</p>
                </div>
              )}
              {!loading &&
                filtered.map(item => {
                  const meta = difficultyMeta(item.difficulty)
                  const active = selected?.id === item.id
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => setSelected(item)}
                      className={`block w-full border-b border-slate-100 px-3 py-3 text-left transition last:border-b-0 ${
                        active ? 'bg-primary-50/60' : 'hover:bg-slate-50'
                      }`}
                    >
                      <p className="line-clamp-2 text-[13px] font-medium leading-6 text-slate-800">{item.stem}</p>
                      <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                        {meta && (
                          <span className={`rounded border px-1.5 py-0.5 text-[10px] ${meta.className}`}>{meta.label}</span>
                        )}
                        {item.company && (
                          <span className="inline-flex items-center gap-1 text-[10.5px] text-slate-400">
                            <Building2 className="h-3 w-3" />
                            {item.company}
                          </span>
                        )}
                        {item.year && (
                          <span className="inline-flex items-center gap-1 text-[10.5px] text-slate-400">
                            <CalendarDays className="h-3 w-3" />
                            {item.year}
                          </span>
                        )}
                        {item.tags.slice(0, 3).map(tag => (
                          <span key={tag} className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">
                            {tag}
                          </span>
                        ))}
                      </div>
                    </button>
                  )
                })}
            </div>
          </section>

          <aside className="hidden min-h-0 w-[420px] shrink-0 flex-col rounded-2xl border border-slate-200 bg-white shadow-sm lg:flex">
            {selected ? (
              <>
                <div className="border-b border-slate-100 p-4">
                  <div className="flex flex-wrap items-center gap-1.5">
                    {difficultyMeta(selected.difficulty) && (
                      <span
                        className={`rounded border px-1.5 py-0.5 text-[10px] ${
                          difficultyMeta(selected.difficulty)!.className
                        }`}
                      >
                        {difficultyMeta(selected.difficulty)!.label}
                      </span>
                    )}
                    {selected.company && (
                      <span className="text-[10.5px] text-slate-400">· {selected.company}</span>
                    )}
                    {selected.role && <span className="text-[10.5px] text-slate-400">· {selected.role}</span>}
                    {selected.year && <span className="text-[10.5px] text-slate-400">· {selected.year}</span>}
                  </div>
                  <h2 className="mt-2 text-[15px] font-semibold leading-6 text-slate-900">{selected.stem}</h2>
                  {selected.tags.length > 0 && (
                    <div className="mt-2 flex flex-wrap items-center gap-1">
                      <Tag className="h-3 w-3 text-slate-300" />
                      {selected.tags.map(tag => (
                        <span key={tag} className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <div className="min-h-0 flex-1 overflow-y-auto p-4">
                  {detailLoading && !shown?.answer ? (
                    <p className="flex items-center gap-1.5 text-xs text-slate-400">
                      <Loader2 className="h-3 w-3 animate-spin" /> 加载答案…
                    </p>
                  ) : shown?.answer ? (
                    <div className="text-[12.5px] leading-6 text-slate-700">
                      {renderChatMarkdown(shown.answer, () => {})}
                    </div>
                  ) : (
                    <p className="text-xs text-slate-400">这道题还没有解析答案。</p>
                  )}

                  {(refsLoading || references.length > 0) && (
                    <div className="mt-6 border-t border-slate-100 pt-3">
                      <p className="flex items-center gap-1.5 text-[11px] font-medium text-slate-500">
                        <FileText className="h-3 w-3 text-slate-400" />
                        关联文档片段
                        {refsLoading && <Loader2 className="h-3 w-3 animate-spin text-slate-400" />}
                      </p>
                      <div className="mt-2 space-y-2">
                        {references.map(ref => (
                          <div key={ref.chunkId} className="rounded-lg border border-slate-100 bg-slate-50/70 p-2.5">
                            <p className="text-[11px] font-medium text-slate-600">
                              {ref.documentName ?? `文档 #${ref.documentId}`}
                              {ref.heading ? ` · ${ref.heading}` : ''}
                            </p>
                            <p className="mt-1 line-clamp-3 text-[11.5px] leading-5 text-slate-500">{ref.text}</p>
                            {ref.concepts.length > 0 && (
                              <div className="mt-1.5 flex flex-wrap gap-1">
                                {ref.concepts.map(concept => (
                                  <span
                                    key={concept}
                                    className="rounded bg-white px-1.5 py-0.5 text-[10px] text-slate-500 ring-1 ring-slate-100"
                                  >
                                    {concept}
                                  </span>
                                ))}
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  <p className="mt-6 border-t border-dashed border-slate-200 pt-3 text-[10.5px] text-slate-400">
                    来自「{activeBase?.name ?? '题库'}」· 文档 #{selected.documentId}
                    {selected.chunkId ? ` · 切片 #${selected.chunkId}` : ''}
                  </p>
                </div>
              </>
            ) : (
              <div className="flex h-full flex-col items-center justify-center text-center">
                <BookOpen className="h-6 w-6 text-slate-300" />
                <p className="mt-2 text-xs text-slate-400">选一道题看答案</p>
              </div>
            )}
          </aside>
        </div>
      )}
    </div>
  )
}
