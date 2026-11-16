import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  ArrowLeft,
  BookOpen,
  Check,
  ChevronRight,
  FileText,
  Folder,
  FolderInput,
  History,
  Inbox,
  Loader2,
  Plus,
  Sparkles,
  X
} from 'lucide-react'
import { catalogApi, type KbCatalogGroup, type KbCatalogItem, type KbCatalogNode } from '@/api/catalog'
import { kbApi, type KbChunkView, type KnowledgeBaseView } from '@/api/kb'
import { quizApi, type KbInterviewBackfillView } from '@/api/quiz'
import { questionApi, type KbQuestionView } from '@/api/questions'
import KbCategoryTree from '@/components/KbCategoryTree'
import KbTabs from '@/components/KbTabs'
import { renderChatMarkdown } from '@/utils/chatMarkdown'

type Tab = 'CHUNK' | 'QUESTION'

const PAGE_SIZE = 12

/** 后端约定的虚拟「未分类」节点 id（不落库）。 */
const UNCATEGORIZED_ID = -1

function findNode(nodes: KbCatalogNode[], id: number): KbCatalogNode | null {
  for (const node of nodes) {
    if (node.id === id) return node
    const found = findNode(node.children, id)
    if (found) return found
  }
  return null
}

function findPath(nodes: KbCatalogNode[], id: number, trail: KbCatalogNode[] = []): KbCatalogNode[] {
  for (const node of nodes) {
    const next = [...trail, node]
    if (node.id === id) return next
    const found = findPath(node.children, id, next)
    if (found.length > 0) return found
  }
  return []
}

function collectIds(nodes: KbCatalogNode[], acc: Set<number> = new Set()): Set<number> {
  for (const node of nodes) {
    acc.add(node.id)
    collectIds(node.children, acc)
  }
  return acc
}

export default function KbCatalogPage() {
  const { id } = useParams()
  const kbId = Number(id)
  const [kb, setKb] = useState<KnowledgeBaseView | null>(null)
  const [tree, setTree] = useState<KbCatalogNode[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [tab, setTab] = useState<Tab>('CHUNK')
  const [items, setItems] = useState<KbCatalogItem[]>([])
  const [groups, setGroups] = useState<KbCatalogGroup[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffset] = useState(0)
  const [refreshToken, setRefreshToken] = useState(0)
  const [loading, setLoading] = useState(true)
  const [itemsLoading, setItemsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [classifying, setClassifying] = useState(false)
  const [importing, setImporting] = useState(false)
  const [backfilling, setBackfilling] = useState(false)
  const [backfillPlan, setBackfillPlan] = useState<KbInterviewBackfillView | null>(null)
  const [importSources, setImportSources] = useState<KnowledgeBaseView[]>([])
  const [importSourceId, setImportSourceId] = useState<number | null>(null)
  const [moving, setMoving] = useState<KbCatalogItem | null>(null)
  const [detail, setDetail] = useState<KbCatalogItem | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  /** 未分类条目数：为 0 说明都挂上了；>0 就是"上传了但没人归类"，要在页面上喊出来 */
  const [uncategorized, setUncategorized] = useState(0)
  /** 正在确认的条目 id（卡片内小 spinner） */
  const [confirmingIds, setConfirmingIds] = useState<Set<number>>(new Set())
  const [confirmingAll, setConfirmingAll] = useState(false)
  const [kindScanning, setKindScanning] = useState(false)

  const selected = useMemo(
    () => (selectedId == null ? null : findNode(tree, selectedId)),
    [tree, selectedId]
  )
  const path = useMemo(
    () => (selectedId == null ? [] : findPath(tree, selectedId)),
    [tree, selectedId]
  )
  const parentForCreate = selected && selected.id > 0 ? selected : null

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    Promise.all([kbApi.get(kbId), catalogApi.tree(kbId)])
      .then(([base, nodes]) => {
        if (cancelled) return
        setKb(base)
        setTree(nodes)
        setExpanded(collectIds(nodes))
        const first = nodes.find(node => !node.system) ?? nodes[0]
        // 支持 /kb/:id/catalog?node=<categoryId> 深链：刷题答错时直接定位到知识点节点。
        const wanted = Number(new URLSearchParams(window.location.search).get('node') ?? '')
        const focus = Number.isFinite(wanted) && wanted > 0 ? findNode(nodes, wanted) : null
        setSelectedId(focus?.id ?? first?.id ?? null)
        setTab(base.kind === 'QUESTION_BANK' ? 'QUESTION' : 'CHUNK')
      })
      .catch(err => {
        if (!cancelled) setError(err instanceof Error ? err.message : '加载知识目录失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [kbId])

  // 题库库往往没有自己的知识树：列一下别的库，方便一键继承目录。
  useEffect(() => {
    let cancelled = false
    kbApi
      .list()
      .then(list => {
        if (cancelled) return
        const others = list.filter(item => String(item.id) !== String(kbId))
        setImportSources(others)
      })
      .catch(() => {
        if (!cancelled) setImportSources([])
      })
    return () => {
      cancelled = true
    }
  }, [kbId])

  useEffect(() => {
    if (selectedId == null) return
    let cancelled = false
    setItemsLoading(true)
    setGroups([])
    catalogApi
      .items(kbId, selectedId, tab, offset, PAGE_SIZE)
      .then(page => {
        if (cancelled) return
        setItems(page.items)
        setGroups(page.groups ?? [])
        setTotal(page.total)
      })
      .catch(err => {
        if (!cancelled) setError(err instanceof Error ? err.message : '加载条目失败')
      })
      .finally(() => {
        if (!cancelled) setItemsLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [kbId, selectedId, tab, offset, refreshToken])

  // 「未分类」里的条数：只看 total，顺便当成归类是否跑过的探针（上传后静默失败时能看见）
  useEffect(() => {
    let cancelled = false
    const probeType = kb?.kind === 'QUESTION_BANK' ? 'QUESTION' : 'CHUNK'
    catalogApi
      .items(kbId, UNCATEGORIZED_ID, probeType, 0, 1)
      .then(page => {
        if (!cancelled) setUncategorized(page.total)
      })
      .catch(() => {
        if (!cancelled) setUncategorized(0)
      })
    return () => {
      cancelled = true
    }
  }, [kbId, kb?.kind, kb?.documentCount, kb?.chunkCount, refreshToken])

  const selectNode = (next: number) => {
    setSelectedId(next)
    setOffset(0)
  }

  const toggleNode = (target: number) => {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(target)) next.delete(target)
      else next.add(target)
      return next
    })
  }

  const reload = async (focusId?: number) => {
    const nodes = await catalogApi.tree(kbId)
    setTree(nodes)
    setRefreshToken(token => token + 1)
    if (focusId != null) {
      setSelectedId(focusId)
      setOffset(0)
    }
    return nodes
  }

  /**
   * 重判文档类型：题库库里混着的讲义/README/口播稿被切成"题"，就是它们污染了目录。
   * 先干跑，把人话列出来，人点了才删。
   */
  const runKindScan = async () => {
    if (kindScanning) return
    setKindScanning(true)
    setError(null)
    setNotice(null)
    try {
      const dry = await kbApi.kindScan(kbId, true)
      const targets = dry.rows.filter(row => !row.questionBank && row.questionCount > 0)
      if (targets.length === 0) {
        setNotice(`已检查 ${dry.scanned} 篇，都是题库，没有需要重判的文档`)
        return
      }
      const list = targets
        .map(row => `· ${row.fileName}（${row.questionCount} 道题，分数 ${row.score}）`)
        .join('\n')
      const confirmed = window.confirm(
        `这 ${targets.length} 篇会被判成「知识点」：\n\n${list}\n\n它们的题会被删除，切片改为按目录归类（可重新上传恢复题目）。继续？`
      )
      if (!confirmed) return
      const applied = await kbApi.kindScan(kbId, false)
      setNotice(`已重判 ${applied.demoted} 篇：删掉误抽的题，切片转成目录归类`)
      await reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : '文档类型重判失败')
    } finally {
      setKindScanning(false)
    }
  }

  // ---- 目录树就地编辑（新建 / 改名 / 拖拽移动 / 删除）----

  const createNode = async (parentId: number | null, name: string) => {
    setError(null)
    try {
      const created = (await catalogApi.create(kbId, { parentId, name })) as { id?: number }
      if (parentId != null) setExpanded(prev => new Set(prev).add(parentId))
      await reload(created?.id)
      setNotice(`已新建目录「${name}」`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '新建目录失败')
      throw err
    }
  }

  const renameNode = async (id: number, name: string) => {
    setError(null)
    try {
      await catalogApi.renameCategory(id, { name })
      await reload(id)
      setNotice('目录已重命名')
    } catch (err) {
      setError(err instanceof Error ? err.message : '重命名失败')
      throw err
    }
  }

  const moveNode = async (id: number, parentId: number | null, beforeId: number | null) => {
    setError(null)
    try {
      await catalogApi.moveCategory(id, parentId, beforeId)
      await reload(id)
      setNotice(parentId == null || beforeId != null ? '目录顺序已调整' : '目录已移动')
    } catch (err) {
      setError(err instanceof Error ? err.message : '移动目录失败')
      throw err
    }
  }

  const deleteNode = async (node: KbCatalogNode, cascade: boolean) => {
    setError(null)
    try {
      await catalogApi.deleteCategory(node.id, cascade)
      const nodes = await reload()
      if (selectedId === node.id) {
        const next = nodes.find(item => !item.system) ?? nodes[0]
        setSelectedId(next?.id ?? null)
        setOffset(0)
      }
      setNotice(`已删除目录「${node.name}」${cascade ? '（含子目录，内容回到未分类）' : ''}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除目录失败')
      throw err
    }
  }

  const confirmOne = async (item: KbCatalogItem) => {
    if (confirmingIds.has(item.id)) return
    setConfirmingIds(prev => new Set(prev).add(item.id))
    setError(null)
    try {
      await catalogApi.confirmItem(kbId, item.itemType, item.id)
      await reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : '确认归属失败')
    } finally {
      setConfirmingIds(prev => {
        const next = new Set(prev)
        next.delete(item.id)
        return next
      })
    }
  }

  const confirmAll = async () => {
    if (confirmingAll) return
    setConfirmingAll(true)
    setNotice(null)
    setError(null)
    const itemType = kb?.kind === 'QUESTION_BANK' ? 'QUESTION' : 'CHUNK'
    try {
      const result = await catalogApi.confirmAllItems(kbId, itemType)
      setNotice(
        result.confirmed > 0
          ? `已确认 ${result.confirmed} 条归属${result.alreadyLocked > 0 ? `（另有 ${result.alreadyLocked} 条本来已确认）` : ''}，之后自动归类不会再改它们`
          : `没有需要确认的归属${result.alreadyLocked > 0 ? `，${result.alreadyLocked} 条已经是人工确认状态` : ''}`
      )
      await reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : '一键确认失败')
    } finally {
      setConfirmingAll(false)
    }
  }

  const runClassify = async () => {    if (classifying) return
    setClassifying(true)
    setNotice(null)
    setError(null)
    try {
      const result = await catalogApi.classify(kbId)
      setNotice(
        result.mode === 'NO_CATEGORY' ? '先建至少一个目录，再让模型归类' : result.summary
      )
      await reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : '自动归类失败')
    } finally {
      setClassifying(false)
    }
  }

  const runImportTree = async () => {
    if (importSourceId == null || importing) return
    setImporting(true)
    setNotice(null)
    setError(null)
    try {
      const result = await catalogApi.importTree(kbId, importSourceId)
      setNotice(
        `从「${importSources.find(source => source.id === importSourceId)?.name ?? '源库'}」导入 ${result.imported} 个节点，跳过 ${result.skipped} 个（已存在或同名冲突）`
      )
      await reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : '导入目录失败')
    } finally {
      setImporting(false)
    }
  }

  // 面试信号回填：先干跑看"会匹配到哪、有多少条匹配不上"，再点一次才真正写库。
  const runInterviewDryRun = async () => {
    if (backfilling) return
    setBackfilling(true)
    setError(null)
    setNotice(null)
    try {
      const preview = await quizApi.backfillInterview(kbId, true, false)
      setBackfillPlan(preview)
      setNotice(
        `干跑：可折算 ${preview.matched} 轮面试，${preview.unmatchedSkills} 个 skill 在当前目录里找不到对应知识点`
      )
    } catch (err) {
      setError(err instanceof Error ? err.message : '面试信号预检失败')
    } finally {
      setBackfilling(false)
    }
  }

  const runInterviewApply = async () => {
    if (backfilling) return
    setBackfilling(true)
    setError(null)
    try {
      const result = await quizApi.backfillInterview(kbId, false, true)
      setBackfillPlan(null)
      setNotice(
        `已折算 ${result.applied} 轮面试（新建 ${result.createdCategories} 个知识点，累计台账 ${result.importedTotal} 轮）`
      )
      await reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : '面试信号回填失败')
    } finally {
      setBackfilling(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center text-sm text-slate-400">
        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
        正在加载知识目录…
      </div>
    )
  }

  if (error || !kb) {
    return (
      <div className="rounded-2xl border border-rose-100 bg-rose-50 px-4 py-3 text-sm text-rose-600">
        {error || '知识库不存在'}
      </div>
    )
  }

  const showQuestions = kb.kind === 'QUESTION_BANK'
  /** 当前这一页里"自动挂的、还没人确认"的条目数，用来决定「确认归属」按钮显不显眼 */
  const pendingInPage = items.filter(item => item.categoryId != null && !item.locked).length

  return (
    <div className="mx-auto max-w-[1400px]">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link
            to={`/kb/${kb.id}`}
            className="inline-flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            {kb.name}
          </Link>
          <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-900">知识目录</h1>
          <p className="mt-1 text-sm text-slate-500">
            按主题组织知识点 · {kb.documentCount} 篇文档 · {kb.chunkCount} 个切片
          </p>
          {notice && <p className="mt-2 text-xs text-emerald-600">{notice}</p>}
        </div>
        <div className="flex items-center gap-2">
          <Link
            to={`/kb/${kb.id}`}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-medium text-slate-600 transition-colors hover:bg-slate-50"
          >
            <FileText className="h-3.5 w-3.5" />
            文档 {kb.documentCount}
          </Link>
          {showQuestions && importSources.length > 0 && (
            <div className="flex items-center gap-1 rounded-xl border border-slate-200 bg-white pl-2 pr-1 py-1">
              <select
                value={importSourceId ?? ''}
                onChange={event =>
                  setImportSourceId(event.target.value ? Number(event.target.value) : null)
                }
                className="max-w-[150px] bg-transparent text-xs text-slate-600 outline-none"
                title="把另一个库的目录树继承过来"
              >
                <option value="">继承目录…</option>
                {importSources.map(source => (
                  <option key={source.id} value={source.id}>
                    {source.name}
                  </option>
                ))}
              </select>
              <button
                type="button"
                onClick={runImportTree}
                disabled={importing || importSourceId == null}
                className="rounded-lg px-2 py-1 text-xs font-medium text-primary-700 transition-colors hover:bg-primary-50 disabled:opacity-40"
              >
                {importing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : '导入'}
              </button>
            </div>
          )}
          <button
            type="button"
            onClick={runClassify}
            disabled={classifying || !tree.some(node => !node.system)}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-medium text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-40"
          >
            {classifying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            自动归类
          </button>
          <button
            type="button"
            onClick={runInterviewDryRun}
            disabled={backfilling}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-medium text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-40"
            title="把面试记录（skill + 得分）折算成知识点能力值；先干跑，确认后再写库"
          >
            {backfilling ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <History className="h-3.5 w-3.5" />}
            面试信号回填
          </button>
          {showQuestions && (
            <button
              type="button"
              onClick={runKindScan}
              disabled={kindScanning}
              className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-medium text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-40"
              title="把题库库里混着的讲义 / README / 口播稿挑出来，删掉误抽的题，切片转成目录归类"
            >
              {kindScanning ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <FileText className="h-3.5 w-3.5" />}
              重判文档类型
            </button>
          )}
          <button
            type="button"
            onClick={confirmAll}
            disabled={confirmingAll}
            className={`inline-flex items-center gap-1.5 rounded-xl border px-3.5 py-2 text-xs font-medium transition-colors disabled:opacity-40 ${
              pendingInPage > 0
                ? 'border-amber-200 bg-amber-50 text-amber-700 hover:bg-amber-100'
                : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            }`}
            title="把库里所有「自动挂的、还没确认」的归属钉成人工真值，之后自动归类不再覆盖"
          >
            {confirmingAll ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
            确认归属
            {pendingInPage > 0 && (
              <span className="rounded-full bg-amber-500/15 px-1.5 text-[10px] tabular-nums text-amber-700">
                本页 {pendingInPage}
              </span>
            )}
          </button>
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="inline-flex items-center gap-1.5 rounded-xl bg-slate-900 px-3.5 py-2 text-xs font-medium text-white transition-colors hover:bg-slate-800"
          >
            <Plus className="h-3.5 w-3.5" />
            新建目录
          </button>
        </div>
      </div>

      <KbTabs kbId={kb.id} active="catalog" />

      {backfillPlan && (
        <div className="mt-4 flex flex-wrap items-center gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
          <History className="h-4 w-4 shrink-0" />
          <span>
            待折算 <b>{backfillPlan.matched}</b> 轮面试
            {backfillPlan.unmatchedSkills > 0 && `；${backfillPlan.unmatchedSkills} 个 skill 需要新建知识点（挂在「面试评估」下）`}
            。写库后重复执行不会重复计分。
          </span>
          <div className="ml-auto flex items-center gap-2">
            <button
              type="button"
              onClick={runInterviewApply}
              disabled={backfilling}
              className="rounded-lg bg-amber-600 px-2.5 py-1 font-medium text-white transition-colors hover:bg-amber-700 disabled:opacity-50"
            >
              {backfilling ? '回填中…' : '应用回填'}
            </button>
            <button
              type="button"
              onClick={() => setBackfillPlan(null)}
              className="rounded-lg px-2 py-1 font-medium text-amber-700 transition-colors hover:bg-amber-100"
            >
              取消
            </button>
          </div>
        </div>
      )}

      {uncategorized > 0 && (
        <div className="mt-4 flex flex-wrap items-center gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
          <Inbox className="h-4 w-4 shrink-0" />
          <span>
            还有 <b>{uncategorized}</b> {showQuestions ? '道题没挂知识点' : '个切片没挂目录'}
            ——{showQuestions ? '答错时跳不回知识点' : '检索和刷题都找不到落点'}。
            上传后自动归类没命中（模型不可用时会退化成规则匹配），点右侧补一遍。
          </span>
          <div className="ml-auto flex items-center gap-2">
            <button
              type="button"
              onClick={runClassify}
              disabled={classifying}
              className="rounded-lg bg-amber-600 px-2.5 py-1 font-medium text-white transition-colors hover:bg-amber-700 disabled:opacity-50"
            >
              {classifying ? '归类中…' : '自动归类'}
            </button>
            <button
              type="button"
              onClick={() => {
                setTab(showQuestions ? 'QUESTION' : 'CHUNK')
                selectNode(UNCATEGORIZED_ID)
              }}
              className="rounded-lg px-2 py-1 font-medium text-amber-700 transition-colors hover:bg-amber-100"
            >
              看看未分类
            </button>
          </div>
        </div>
      )}

      <div className="mt-6 grid items-start gap-6 lg:grid-cols-[280px_minmax(0,1fr)]">
        <aside className="rounded-2xl border border-slate-200/80 bg-white p-3 shadow-[0_1px_2px_rgba(15,23,42,0.03)] lg:sticky lg:top-6">
          <div className="px-2 pb-2 pt-1 text-[11px] font-medium uppercase tracking-wider text-slate-400">
            目录
          </div>
          <div className="max-h-[calc(100vh-220px)] overflow-y-auto pr-1">
            <KbCategoryTree
              nodes={tree}
              kind={kb.kind}
              selectedId={selectedId}
              expanded={expanded}
              canWrite
              onSelect={selectNode}
              onToggle={toggleNode}
              onExpand={(nodeId: number) => setExpanded(prev => new Set(prev).add(nodeId))}
              onCreate={createNode}
              onRename={renameNode}
              onMove={moveNode}
              onDelete={deleteNode}
            />
          </div>
        </aside>

        <section className="min-w-0">
          <div className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-[0_1px_2px_rgba(15,23,42,0.03)]">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-1 text-xs text-slate-400">
                  {path.length === 0 ? (
                    <span>未选择目录</span>
                  ) : (
                    path.map((node, index) => (
                      <span key={node.id} className="flex items-center gap-1">
                        {index > 0 && <ChevronRight className="h-3 w-3 text-slate-300" />}
                        <button
                          type="button"
                          onClick={() => selectNode(node.id)}
                          className={index === path.length - 1 ? 'text-slate-600' : 'hover:text-slate-600'}
                        >
                          {node.name}
                        </button>
                      </span>
                    ))
                  )}
                </div>
                <h2 className="mt-1 truncate text-lg font-semibold text-slate-900">
                  {selected?.name ?? '选择一个目录'}
                </h2>
                {selected?.description && (
                  <p className="mt-1 text-xs leading-5 text-slate-500">{selected.description}</p>
                )}
              </div>
              {selected && (
                <div className="flex items-center gap-1.5 text-[11px] text-slate-400">
                  <span className="rounded-md bg-slate-50 px-2 py-1 ring-1 ring-inset ring-slate-200/70">
                    知识点 {selected.chunkCount}
                  </span>
                  {showQuestions && (
                    <span className="rounded-md bg-slate-50 px-2 py-1 ring-1 ring-inset ring-slate-200/70">
                      题目 {selected.questionCount}
                    </span>
                  )}
                  {!selected.system && (
                    <span className="text-[11px] text-slate-400">
                      在左侧树上双击改名、悬停＋建子级、拖拽调整层级与顺序
                    </span>
                  )}
                </div>
              )}
            </div>

            <div className="mt-4 flex items-center gap-1 border-b border-slate-100">
              <button
                type="button"
                onClick={() => {
                  setTab('CHUNK')
                  setOffset(0)
                }}
                className={`relative px-3 py-2 text-xs font-medium transition-colors ${
                  tab === 'CHUNK' ? 'text-slate-900' : 'text-slate-400 hover:text-slate-600'
                }`}
              >
                知识点
                {tab === 'CHUNK' && (
                  <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-slate-900" />
                )}
              </button>
              <button
                type="button"
                onClick={() => {
                  setTab('QUESTION')
                  setOffset(0)
                }}
                className={`relative px-3 py-2 text-xs font-medium transition-colors ${
                  tab === 'QUESTION' ? 'text-slate-900' : 'text-slate-400 hover:text-slate-600'
                }`}
              >
                题目
                {tab === 'QUESTION' && (
                  <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-slate-900" />
                )}
              </button>
            </div>

            <div className="mt-4">
              {itemsLoading ? (
                <div className="flex h-40 items-center justify-center text-xs text-slate-400">
                  <Loader2 className="mr-2 h-3.5 w-3.5 animate-spin" />
                  加载中…
                </div>
              ) : items.length === 0 && groups.length === 0 ? (
                <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-slate-200 py-14 text-center">
                  <Inbox className="h-6 w-6 text-slate-300" />
                  <p className="mt-2 text-sm text-slate-500">这个目录还没有内容</p>
                  <p className="mt-1 text-xs text-slate-400">
                    {selected?.system ? '上传文档或把知识点拖进来后会出现在这里' : '在文档或题目里把它挂到这个目录'}
                  </p>
                </div>
              ) : groups.length > 0 ? (
                <div className="space-y-4">
                  {items.length > 0 && (
                    <section className="rounded-xl border border-slate-200/80 p-3">
                      <div className="mb-3 flex items-center gap-2 px-1 text-xs text-slate-500">
                        <FileText className="h-3.5 w-3.5 text-slate-400" />
                        直接挂在本目录
                        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] tabular-nums text-slate-400">
                          {total}
                        </span>
                      </div>
                      <div className="space-y-2">
                        {items.map(item => (
                          <CatalogItemCard
                            key={`${item.itemType}-${item.id}`}
                            item={item}
                            onMove={() => setMoving(item)}
                            onOpen={() => setDetail(item)}
                            onConfirm={() => confirmOne(item)}
                            confirming={confirmingIds.has(item.id)}
                          />
                        ))}
                      </div>
                    </section>
                  )}

                  {groups.map(group => (
                    <section key={group.id} className="overflow-hidden rounded-xl border border-slate-200/80">
                      <button
                        type="button"
                        onClick={() => selectNode(group.id)}
                        className="flex w-full items-center gap-2 bg-slate-50/60 px-3.5 py-2.5 text-left transition-colors hover:bg-slate-100/70"
                      >
                        <Folder className="h-4 w-4 text-primary-500" />
                        <span className="text-sm font-medium text-slate-800">{group.name}</span>
                        <span className="rounded bg-white px-1.5 py-0.5 text-[10px] tabular-nums text-slate-400 ring-1 ring-inset ring-slate-200/70">
                          {group.total}
                        </span>
                        <span className="ml-auto flex items-center gap-0.5 text-[11px] text-slate-400">
                          查看子目录
                          <ChevronRight className="h-3 w-3" />
                        </span>
                      </button>
                      <div className="p-3">
                        {group.items.length > 0 ? (
                          <div className="space-y-2">
                            {group.items.map(item => (
                              <CatalogItemCard
                                key={`${item.itemType}-${item.id}`}
                                item={item}
                                onMove={() => setMoving(item)}
                                onOpen={() => setDetail(item)}
                                onConfirm={() => confirmOne(item)}
                                confirming={confirmingIds.has(item.id)}
                              />
                            ))}
                          </div>
                        ) : (
                          <p className="px-1 py-3 text-xs text-slate-400">这个子目录还没有内容</p>
                        )}
                        {group.total > group.items.length && (
                          <button
                            type="button"
                            onClick={() => selectNode(group.id)}
                            className="mt-3 inline-flex items-center gap-0.5 text-[11px] font-medium text-primary-600 hover:text-primary-700"
                          >
                            查看全部 {group.total} 条
                            <ChevronRight className="h-3 w-3" />
                          </button>
                        )}
                      </div>
                    </section>
                  ))}
                </div>
              ) : (
                <div className="space-y-2">
                  {items.map(item => (
                    <CatalogItemCard
                      key={`${item.itemType}-${item.id}`}
                      item={item}
                      onMove={() => setMoving(item)}
                      onOpen={() => setDetail(item)}
                      onConfirm={() => confirmOne(item)}
                      confirming={confirmingIds.has(item.id)}
                    />
                  ))}
                </div>
              )}

              {groups.length === 0 && total > PAGE_SIZE && (
                <div className="mt-4 flex items-center justify-between text-xs text-slate-400">
                  <span>
                    {offset + 1}–{Math.min(offset + PAGE_SIZE, total)} / {total}
                  </span>
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      disabled={offset === 0}
                      onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
                      className="rounded-lg border border-slate-200 px-2 py-1 transition-colors hover:bg-slate-50 disabled:opacity-40"
                    >
                      上一页
                    </button>
                    <button
                      type="button"
                      disabled={offset + PAGE_SIZE >= total}
                      onClick={() => setOffset(offset + PAGE_SIZE)}
                      className="rounded-lg border border-slate-200 px-2 py-1 transition-colors hover:bg-slate-50 disabled:opacity-40"
                    >
                      下一页
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </section>
      </div>

      {detail && <CatalogDetailDrawer item={detail} onClose={() => setDetail(null)} />}

      {moving && (
        <MoveItemDialog
          kbId={kb.id}
          item={moving}
          tree={tree}
          defaultCategoryId={
            moving.categoryId ?? (selected && selected.id > 0 ? selected.id : null)
          }
          onClose={() => setMoving(null)}
          onMoved={async () => {
            setMoving(null)
            setNotice('已更新目录归属')
            await reload()
          }}
        />
      )}

      {creating && (
        <CreateCategoryDialog
          kbId={kb.id}
          parent={parentForCreate}
          onClose={() => setCreating(false)}
          onCreated={async createdId => {
            setCreating(false)
            if (parentForCreate) {
              setExpanded(prev => new Set(prev).add(parentForCreate.id))
            }
            await reload(createdId)
          }}
        />
      )}
    </div>
  )
}

function CatalogDetailDrawer({ item, onClose }: { item: KbCatalogItem; onClose: () => void }) {
  const [chunk, setChunk] = useState<KbChunkView | null>(null)
  const [question, setQuestion] = useState<KbQuestionView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const isQuestion = item.itemType === 'QUESTION'

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    const request = isQuestion
      ? questionApi.detail(item.questionId ?? item.id).then(view => {
          if (!cancelled) setQuestion(view)
        })
      : kbApi.chunk(item.id).then(view => {
          if (!cancelled) setChunk(view)
        })
    request
      .catch(err => {
        if (!cancelled) setError(err instanceof Error ? err.message : '加载详情失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [item, isQuestion])

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end bg-slate-900/25 backdrop-blur-sm"
      onClick={onClose}
      role="presentation"
    >
      <aside
        className="flex h-full w-full max-w-[560px] flex-col bg-white shadow-2xl"
        onClick={event => event.stopPropagation()}
        aria-label="知识点详情"
      >
        <header className="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
          <span
            className={`mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ring-1 ring-inset ${
              isQuestion ? 'bg-rose-50 text-rose-500 ring-rose-100' : 'bg-emerald-50 text-emerald-600 ring-emerald-100'
            }`}
          >
            {isQuestion ? <BookOpen className="h-4 w-4" /> : <FileText className="h-4 w-4" />}
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-[11px] font-medium text-slate-400">{isQuestion ? '题目' : '知识点'}</p>
            <h3 className="mt-0.5 text-sm font-semibold leading-6 text-slate-900">{item.title}</h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-slate-50 hover:text-slate-600"
            aria-label="关闭"
          >
            <X className="h-4 w-4" />
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading ? (
            <div className="flex h-40 items-center justify-center text-xs text-slate-400">
              <Loader2 className="mr-2 h-3.5 w-3.5 animate-spin" />
              加载完整内容…
            </div>
          ) : error ? (
            <p className="rounded-xl bg-rose-50 px-3.5 py-3 text-xs text-rose-600">{error}</p>
          ) : isQuestion && question ? (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-1.5 text-[10px] text-slate-400">
                {question.company && (
                  <span className="rounded bg-slate-50 px-1.5 py-0.5">{question.company}</span>
                )}
                {question.year && <span className="rounded bg-slate-50 px-1.5 py-0.5">{question.year}</span>}
                {question.difficulty && (
                  <span className="rounded bg-slate-50 px-1.5 py-0.5">{question.difficulty}</span>
                )}
                {question.tags.map(tag => (
                  <span key={tag} className="rounded bg-slate-50 px-1.5 py-0.5">
                    {tag}
                  </span>
                ))}
              </div>
              <section>
                <h4 className="text-xs font-medium text-slate-400">题干</h4>
                <p className="mt-1.5 whitespace-pre-wrap text-[13px] leading-7 text-slate-800">{question.stem}</p>
              </section>
              <section>
                <h4 className="text-xs font-medium text-slate-400">参考答案</h4>
                {question.answer ? (
                  <div className="mt-1.5">{renderChatMarkdown(question.answer, () => {})}</div>
                ) : (
                  <p className="mt-1.5 text-xs text-slate-400">暂无参考答案</p>
                )}
              </section>
            </div>
          ) : chunk ? (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-slate-400">
                <span>{item.documentName}</span>
                <span>·</span>
                <span>第 {chunk.ordinal} 片</span>
                {chunk.pageNo != null && <span>· 第 {chunk.pageNo} 页</span>}
                <span>· {chunk.tokenCount} tokens</span>
              </div>
              {chunk.heading && chunk.heading.trim() !== item.title.trim() && (
                <h4 className="text-sm font-medium text-slate-800">{chunk.heading}</h4>
              )}
              {/* 面试笔记里的代码块很常见，按 markdown 渲染：代码走深色 <pre>，缩进才不会塌成一坨 */}
              <div>{renderChatMarkdown(chunk.text, () => {})}</div>
            </div>
          ) : null}
        </div>

        <footer className="border-t border-slate-100 px-5 py-3 text-[11px] text-slate-400">
          来源：{item.documentName ?? '未知文档'}
        </footer>
      </aside>
    </div>
  )
}

function CatalogItemCard({
  item,
  onMove,
  onOpen,
  onConfirm,
  confirming
}: {
  item: KbCatalogItem
  onMove: () => void
  onOpen: () => void
  onConfirm: () => void
  confirming?: boolean
}) {
  const isQuestion = item.itemType === 'QUESTION'
  const Icon = isQuestion ? BookOpen : FileText
  const sourceLabel =
    item.linkSource === 'LLM'
      ? '模型'
      : item.linkSource === 'RULE'
        ? '规则'
        : item.linkSource === 'MANUAL'
          ? '手动'
          : item.linkSource === 'MOVE'
            ? '移动'
            : item.linkSource === 'IMPORT'
              ? '继承'
              : null
  const confidencePct = item.confidence == null ? null : Math.round(item.confidence * 100)
  // 答案不在这里露：列表只回答"有哪些题"，想读答案点右边那一下
  return (
    <article className="group flex items-center gap-3 rounded-xl border border-slate-200/80 bg-white px-3.5 py-2.5 transition-colors hover:border-slate-300/80 hover:bg-slate-50/40">
      <span
        className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-lg ring-1 ring-inset ${
          isQuestion ? 'bg-rose-50 text-rose-500 ring-rose-100' : 'bg-emerald-50 text-emerald-600 ring-emerald-100'
        }`}
      >
        <Icon className="h-3.5 w-3.5" />
      </span>

      <div className="min-w-0 flex-1">
        <button
          type="button"
          onClick={onOpen}
          className="block w-full truncate text-left text-[13px] font-medium leading-5 text-slate-900 transition-colors hover:text-primary-600"
          title={item.title}
        >
          {item.title}
        </button>
        <div className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-[10px] text-slate-400">
          {isQuestion ? (
            <>
              {item.company && <span>{item.company}</span>}
              {item.year && <span>· {item.year}</span>}
              {item.difficulty && <span>· {item.difficulty}</span>}
              {item.tags.slice(0, 3).map(tag => (
                <span key={tag}>· {tag}</span>
              ))}
            </>
          ) : (
            <>
              <span className="truncate">{item.documentName}</span>
              {item.ordinal != null && <span>· 第 {item.ordinal} 片</span>}
            </>
          )}
          {sourceLabel && item.categoryId != null && !item.locked && <span>· {sourceLabel}挂载</span>}
        </div>
      </div>

      {item.categoryId != null && item.locked && (
        <span
          className="inline-flex shrink-0 items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-600 ring-1 ring-inset ring-emerald-100"
          title="已确认：自动归类不会再覆盖它"
        >
          <Check className="h-2.5 w-2.5" />
          已确认
        </span>
      )}

      {/* 待确认：点状态本身就该能改目录——以前这里只能"确认"，想换地方得绕去「移动」按钮 */}
      {item.categoryId != null && !item.locked && (
        <div className="flex shrink-0 items-center gap-1">
          <button
            type="button"
            onClick={onMove}
            title={`${sourceLabel ?? '自动'}挂载${confidencePct == null ? '' : `，可信度 ${confidencePct}%`}；点一下选目录`}
            className="inline-flex shrink-0 items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700 ring-1 ring-inset ring-amber-200 transition-colors hover:bg-amber-100"
          >
            <FolderInput className="h-2.5 w-2.5" />
            待确认{confidencePct == null ? '' : ` ${confidencePct}%`}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={confirming}
            title="不动目录，直接确认这条归属（钉成人工真值）"
            className="rounded-full p-1 text-amber-500 ring-1 ring-inset ring-amber-200 transition-colors hover:bg-amber-50 disabled:opacity-50"
          >
            {confirming ? <Loader2 className="h-2.5 w-2.5 animate-spin" /> : <Check className="h-2.5 w-2.5" />}
          </button>
        </div>
      )}

      {item.categoryId == null && (
        <button
          type="button"
          onClick={onMove}
          title="还没挂目录：点一下选一个放进去"
          className="inline-flex shrink-0 items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-medium text-slate-500 ring-1 ring-inset ring-slate-200 transition-colors hover:bg-slate-200 hover:text-slate-700"
        >
          <Inbox className="h-2.5 w-2.5" />
          未分类
        </button>
      )}

      <button
        type="button"
        onClick={onMove}
        title="移动到其他目录"
        className="inline-flex shrink-0 items-center gap-1 rounded-lg px-1.5 py-1 text-[11px] font-medium text-slate-400 transition-colors hover:bg-white hover:text-primary-600"
      >
        <FolderInput className="h-3 w-3" />
        移动
      </button>
      <button
        type="button"
        onClick={onOpen}
        className="inline-flex shrink-0 items-center gap-0.5 rounded-lg border border-slate-200 px-2.5 py-1 text-[11px] font-medium text-slate-600 transition-colors hover:border-primary-200 hover:bg-primary-50 hover:text-primary-700"
      >
        {isQuestion ? '查看答案' : '查看内容'}
        <ChevronRight className="h-3 w-3 transition-transform group-hover:translate-x-0.5" />
      </button>
    </article>
  )
}

interface FlatCategory {
  node: KbCatalogNode
  depth: number
}

function flattenCategories(nodes: KbCatalogNode[], depth = 0, acc: FlatCategory[] = []): FlatCategory[] {
  for (const node of nodes) {
    if (node.system) continue
    acc.push({ node, depth })
    flattenCategories(node.children, depth + 1, acc)
  }
  return acc
}

function MoveItemDialog({
  kbId,
  item,
  tree,
  defaultCategoryId,
  onClose,
  onMoved
}: {
  kbId: number
  item: KbCatalogItem
  tree: KbCatalogNode[]
  defaultCategoryId: number | null
  onClose: () => void
  onMoved: () => Promise<void>
}) {
  const options = useMemo(() => flattenCategories(tree), [tree])
  const currentPath = useMemo(
    () =>
      item.categoryId == null
        ? null
        : findPath(tree, item.categoryId)
            .map(node => node.name)
            .join(' / '),
    [tree, item.categoryId]
  )
  const [target, setTarget] = useState<number | null>(defaultCategoryId ?? item.categoryId ?? null)
  const [query, setQuery] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase()
    if (!keyword) return options
    return options.filter(
      ({ node }) =>
        node.name.toLowerCase().includes(keyword) || node.pathKey.toLowerCase().includes(keyword)
    )
  }, [options, query])

  const sameAsCurrent = item.categoryId != null && target === item.categoryId
  const primaryLabel =
    target == null ? '移到未分类' : sameAsCurrent ? '确认留在这里' : '移动到这里'

  const submit = async () => {
    setSaving(true)
    setError(null)
    try {
      await catalogApi.moveItem(kbId, item.itemType, item.id, target)
      await onMoved()
    } catch (err) {
      setError(err instanceof Error ? err.message : '移动失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/20 p-4 backdrop-blur-sm">
      <div className="w-full max-w-md rounded-2xl bg-white p-5 shadow-xl">
        <div className="flex items-start justify-between">
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-slate-900">
              给{item.itemType === 'QUESTION' ? '题目' : '知识点'}选目录
            </h3>
            <p className="mt-1 line-clamp-1 text-xs text-slate-400">{item.title}</p>
            <p className="mt-1 text-[11px] text-slate-500">
              当前：
              <span className="font-medium text-slate-700">{currentPath ?? '未分类'}</span>
              {item.confidence != null && (
                <span className="ml-1 text-slate-400">· 模型可信度 {Math.round(item.confidence * 100)}%</span>
              )}
              {item.locked && <span className="ml-1 text-emerald-600">· 已人工确认</span>}
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-lg p-1 text-slate-400 hover:bg-slate-50">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-3">
          <input
            value={query}
            onChange={event => setQuery(event.target.value)}
            placeholder="搜目录名或 pathKey（如 java / mysql）"
            className="w-full rounded-xl border border-slate-200 px-3 py-2 text-xs text-slate-700 outline-none transition-colors placeholder:text-slate-300 focus:border-primary-400 focus:ring-2 focus:ring-primary-100"
          />
        </div>

        <div className="mt-3 max-h-72 space-y-1 overflow-y-auto pr-1">
          <button
            type="button"
            onClick={() => setTarget(null)}
            className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs transition-colors ${
              target === null ? 'bg-primary-50 text-primary-700' : 'text-slate-600 hover:bg-slate-50'
            }`}
          >
            <span
              className={`h-3.5 w-3.5 shrink-0 rounded-full border ${
                target === null ? 'border-primary-500 bg-primary-500' : 'border-slate-300'
              }`}
            />
            <Inbox className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            未分类（移出所有目录）
          </button>
          {filtered.length === 0 && query.trim() !== '' && (
            <p className="px-2 py-3 text-xs text-slate-400">没搜到「{query}」相关的目录</p>
          )}
          {filtered.map(({ node, depth }) => (
            <button
              key={node.id}
              type="button"
              onClick={() => setTarget(node.id)}
              className={`flex w-full items-center gap-2 rounded-lg py-2 pr-2.5 text-left text-xs transition-colors ${
                target === node.id ? 'bg-primary-50 text-primary-700' : 'text-slate-600 hover:bg-slate-50'
              }`}
              style={{ paddingLeft: 10 + depth * 16 }}
            >
              <span
                className={`h-3.5 w-3.5 shrink-0 rounded-full border ${
                  target === node.id ? 'border-primary-500 bg-primary-500' : 'border-slate-300'
                }`}
              />
              <Folder className="h-3.5 w-3.5 shrink-0 text-slate-400" />
              <span className="min-w-0 flex-1 truncate">{node.name}</span>
              <span className="text-[10px] tabular-nums text-slate-300">{node.pathKey}</span>
            </button>
          ))}
        </div>

        {error && <p className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-600">{error}</p>}

        <div className="mt-4 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl px-3 py-2 text-xs text-slate-500 transition-colors hover:bg-slate-50"
          >
            取消
          </button>
          <button
            type="button"
            disabled={saving}
            onClick={submit}
            className="inline-flex items-center gap-1.5 rounded-xl bg-slate-900 px-3.5 py-2 text-xs font-medium text-white transition-colors hover:bg-slate-800 disabled:opacity-50"
          >
            {saving && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
            {primaryLabel}
          </button>
        </div>
      </div>
    </div>
  )
}


function CreateCategoryDialog({
  kbId,
  parent,
  onClose,
  onCreated
}: {
  kbId: number
  parent: KbCatalogNode | null
  onClose: () => void
  onCreated: (id: number) => void
}) {
  const [name, setName] = useState('')
  const [pathKey, setPathKey] = useState('')
  const [description, setDescription] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    if (!name.trim()) {
      setError('目录名称不能为空')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const created = (await catalogApi.create(kbId, {
        parentId: parent?.id ?? null,
        name: name.trim(),
        description: description.trim() || null,
        pathKey: pathKey.trim() || null
      })) as { id: number }
      onCreated(created.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/20 p-4 backdrop-blur-sm">
      <div className="w-full max-w-md rounded-2xl bg-white p-5 shadow-xl">
        <div className="flex items-start justify-between">
          <div>
            <h3 className="text-base font-semibold text-slate-900">新建目录</h3>
            <p className="mt-1 text-xs text-slate-400">
              父目录：{parent ? parent.name : '根目录'} · 最多 3 级
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-lg p-1 text-slate-400 hover:bg-slate-50">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-4 space-y-3">
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-600">名称</span>
            <input
              value={name}
              onChange={event => setName(event.target.value)}
              placeholder="如：并发编程"
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-700 outline-none transition-colors placeholder:text-slate-300 focus:border-primary-400 focus:ring-2 focus:ring-primary-100"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-600">
              pathKey <span className="font-normal text-slate-400">（可选，用于跨库对齐）</span>
            </span>
            <input
              value={pathKey}
              onChange={event => setPathKey(event.target.value)}
              placeholder={parent ? '如：concurrency' : '如：java'}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs text-slate-700 outline-none transition-colors placeholder:text-slate-300 focus:border-primary-400 focus:ring-2 focus:ring-primary-100"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-600">描述</span>
            <textarea
              value={description}
              onChange={event => setDescription(event.target.value)}
              rows={2}
              placeholder="这个目录放什么内容"
              className="w-full resize-none rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-700 outline-none transition-colors placeholder:text-slate-300 focus:border-primary-400 focus:ring-2 focus:ring-primary-100"
            />
          </label>
          {error && <p className="rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-600">{error}</p>}
        </div>

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl px-3 py-2 text-xs text-slate-500 transition-colors hover:bg-slate-50"
          >
            取消
          </button>
          <button
            type="button"
            disabled={submitting}
            onClick={submit}
            className="inline-flex items-center gap-1.5 rounded-xl bg-slate-900 px-3.5 py-2 text-xs font-medium text-white transition-colors hover:bg-slate-800 disabled:opacity-50"
          >
            {submitting && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
            创建
          </button>
        </div>
      </div>
    </div>
  )
}
