import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import KbTabs from '@/components/KbTabs'
import {
  ArrowRight,
  BookOpen,
  Check,
  CircleHelp,
  HelpCircle,
  Loader2,
  RotateCcw,
  Sparkles,
  TriangleAlert,
  X
} from 'lucide-react'
import { kbApi, type KnowledgeBaseView } from '@/api/kb'
import { catalogApi } from '@/api/catalog'
import {
  quizApi,
  type KbQuizCoverageView,
  type KbQuizGradeView,
  type KbQuizRevealView,
  type KbQuizSessionView,
  type KbQuizStatsView,
  type KbQuizWrongItemView,
  type KbSkillsView,
  type QuizResult
} from '@/api/quiz'
import { renderChatMarkdown } from '@/utils/chatMarkdown'

const SIZES = [5, 10, 20]

const DIFFICULTY: Record<string, { label: string; className: string }> = {
  EASY: { label: '简单', className: 'bg-emerald-50 text-emerald-600 border-emerald-100' },
  MEDIUM: { label: '中等', className: 'bg-amber-50 text-amber-600 border-amber-100' },
  HARD: { label: '困难', className: 'bg-rose-50 text-rose-600 border-rose-100' }
}

const GRADES: { result: QuizResult; label: string; className: string; icon: typeof Check }[] = [
  { result: 'CORRECT', label: '答对了', className: 'border-emerald-200 bg-emerald-50 text-emerald-700', icon: Check },
  { result: 'WRONG', label: '答错了', className: 'border-rose-200 bg-rose-50 text-rose-700', icon: X },
  { result: 'UNSURE', label: '不确定', className: 'border-amber-200 bg-amber-50 text-amber-700', icon: HelpCircle }
]

function percent(value: number) {
  return `${Math.round(value * 1000) / 10}%`
}

export default function KbQuizPage() {
  // 从库详情页进来时（/kb/:id/quiz）直接练这个库，不用再选一次
  const routeKbId = Number(useParams().id)
  const [bases, setBases] = useState<KnowledgeBaseView[]>([])
  const [kbId, setKbId] = useState<number | null>(Number.isFinite(routeKbId) && routeKbId > 0 ? routeKbId : null)
  const [size, setSize] = useState(5)
  const [session, setSession] = useState<KbQuizSessionView | null>(null)
  const [current, setCurrent] = useState(0)
  const [reveals, setReveals] = useState<Record<number, KbQuizRevealView>>({})
  const [grades, setGrades] = useState<Record<number, KbQuizGradeView>>({})
  const [stats, setStats] = useState<KbQuizStatsView | null>(null)
  // 首屏三态：加载中出骨架、拿到了才出数字。别让"还没读到"长得像"就是 0"
  const [statsPhase, setStatsPhase] = useState<'loading' | 'ready'>('loading')
  const [skills, setSkills] = useState<KbSkillsView | null>(null)
  const [classifyingDocId, setClassifyingDocId] = useState<number | null>(null)
  const [coverage, setCoverage] = useState<KbQuizCoverageView | null>(null)
  const [wrongBook, setWrongBook] = useState<KbQuizWrongItemView[]>([])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const activeBase = useMemo(() => bases.find(item => item.id === kbId) ?? null, [bases, kbId])
  const items = session?.items ?? []
  const item = items[current] ?? null
  const reveal = item ? reveals[item.attemptId] : undefined
  const grade = item ? grades[item.attemptId] : undefined
  const finished = session?.status === 'FINISHED'
  const answeredCount = session?.answeredCount ?? 0
  // 题库是空的就别让人点「开始刷题」：点下去只会得到一句后端报错，不如直接把原因挂在按钮上
  const emptyBank = stats != null && stats.totalQuestions === 0

  useEffect(() => {
    let cancelled = false
    kbApi
      .list()
      .then(list => {
        if (cancelled) return
        const banks = list.filter(entry => entry.kind === 'QUESTION_BANK')
        setBases(banks)
        // 路径里指定了库就用它，否则落到第一个题库
        const fixed = banks.find(entry => entry.id === routeKbId)
        setKbId(prev => (prev != null && banks.some(entry => entry.id === prev) ? prev : (fixed ?? banks[0])?.id ?? null))
      })
      .catch(err => {
        if (!cancelled) setError(err instanceof Error ? err.message : '知识库加载失败')
      })
    return () => {
      cancelled = true
    }
  }, [routeKbId])

  const refreshInsights = useCallback(async (targetKbId: number) => {
    setStatsPhase('loading')
    const [nextStats, nextCoverage, nextWrong, nextSkills] = await Promise.all([
      quizApi.stats(targetKbId).catch(() => null),
      quizApi.coverage(targetKbId).catch(() => null),
      quizApi.wrongBook(targetKbId, 50).catch(() => []),
      quizApi.skills(targetKbId).catch(() => null)
    ])
    setStats(nextStats)
    setCoverage(nextCoverage)
    setWrongBook(nextWrong)
    setSkills(nextSkills)
    // 拿不到就当拿到空：宁可让空态出来，也别一直挂着骨架
    setStatsPhase('ready')
  }, [])

  useEffect(() => {
    if (kbId == null) return
    setSession(null)
    setReveals({})
    setGrades({})
    setCurrent(0)
    void refreshInsights(kbId)
  }, [kbId, refreshInsights])

  const start = async () => {
    if (kbId == null) return
    setBusy(true)
    setError(null)
    try {
      const next = await quizApi.start(kbId, size)
      setSession(next)
      setReveals({})
      setGrades({})
      setCurrent(0)
    } catch (err) {
      setError(err instanceof Error ? err.message : '抽题失败')
    } finally {
      setBusy(false)
    }
  }

  const revealAnswer = async () => {
    if (!item) return
    setBusy(true)
    setError(null)
    try {
      const view = await quizApi.reveal(item.attemptId)
      setReveals(prev => ({ ...prev, [item.attemptId]: view }))
    } catch (err) {
      setError(err instanceof Error ? err.message : '展开答案失败')
    } finally {
      setBusy(false)
    }
  }

  const submitGrade = async (result: QuizResult) => {
    if (!item || !kbId) return
    setBusy(true)
    setError(null)
    try {
      const view = await quizApi.grade(item.attemptId, result)
      setGrades(prev => ({ ...prev, [item.attemptId]: view }))
      setSession(prev =>
        prev
          ? {
              ...prev,
              answeredCount: view.answeredCount,
              status: view.finished ? 'FINISHED' : prev.status,
              correctCount: (prev.correctCount ?? 0) + (result === 'CORRECT' ? 1 : 0),
              wrongCount: (prev.wrongCount ?? 0) + (result === 'CORRECT' ? 0 : 1)
            }
          : prev
      )
      void refreshInsights(kbId)
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交自评失败')
    } finally {
      setBusy(false)
    }
  }

  const confirmGap = async (questionId: number) => {
    if (kbId == null) return
    setBusy(true)
    setError(null)
    try {
      await catalogApi.confirmItem(kbId, 'QUESTION', questionId)
      setNotice('已确认这道题的归属')
      await refreshInsights(kbId)
    } catch (err) {
      setError(err instanceof Error ? err.message : '确认归属失败')
    } finally {
      setBusy(false)
    }
  }

  const confirmAllGaps = async (questionIds: number[]) => {
    if (kbId == null || questionIds.length === 0) return
    setBusy(true)
    setError(null)
    try {
      const result = await quizApi.confirmBatch(kbId, questionIds)
      setNotice(`批量确认 ${result.confirmed} 条归属${result.skipped > 0 ? `，跳过 ${result.skipped} 条` : ''}`)
      await refreshInsights(kbId)
    } catch (err) {
      setError(err instanceof Error ? err.message : '批量确认失败')
    } finally {
      setBusy(false)
    }
  }

  /** 按文档一键归类：文档里还有没挂或低置信的题时给一次重跑机会。 */
  const classifyDocument = async (documentId: number, redoLowConfidence: boolean) => {
    if (kbId == null) return
    setClassifyingDocId(documentId)
    setError(null)
    try {
      const result = await catalogApi.classifyDocument(kbId, documentId, redoLowConfidence)
      const parts = [
        result.summary,
        result.cleared > 0 ? `清掉低置信 ${result.cleared} 条` : null
      ].filter(Boolean)
      setNotice(parts.join(' · '))
      await refreshInsights(kbId)
    } catch (err) {
      setError(err instanceof Error ? err.message : '按文档归类失败')
    } finally {
      setClassifyingDocId(null)
    }
  }

  const gotoNext = () => {
    const nextIndex = items.findIndex((entry, index) => index > current && !entry.answered)
    if (nextIndex >= 0) {
      setCurrent(nextIndex)
    } else if (current < items.length - 1) {
      setCurrent(current + 1)
    }
  }

  const difficulty = item?.difficulty ? DIFFICULTY[item.difficulty] : null

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">练习</h1>
          <p className="mt-1 text-xs text-slate-500">
            一次抽一组题：先只看题面，自己想完再展开答案，自评对错；答错直接跳对应知识点。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={kbId ?? ''}
            onChange={event => setKbId(Number(event.target.value))}
            className={`rounded-lg border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-700 ${
              routeKbId > 0 ? 'hidden' : ''
            }`}
          >
            {bases.map(base => (
              <option key={base.id} value={base.id}>
                {base.name}（{base.documentCount} 份文档）
              </option>
            ))}
          </select>
          <div className="flex items-center overflow-hidden rounded-lg border border-slate-200 bg-white">
            {SIZES.map(value => (
              <button
                key={value}
                type="button"
                onClick={() => setSize(value)}
                className={`px-3 py-2 text-[13px] transition-colors ${
                  size === value ? 'bg-primary-50 font-medium text-primary-700' : 'text-slate-500'
                }`}
              >
                {value} 题
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={start}
            disabled={busy || kbId == null || emptyBank}
            title={emptyBank ? '题库里还没有题目，先上传题库文档' : undefined}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary-600 px-4 py-2 text-[13px] font-medium text-white disabled:opacity-50"
          >
            {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
            {session ? '再抽一组' : '开始刷题'}
          </button>
        </div>
      </header>

      {error && (
        <div className="mt-4 flex items-start gap-2 rounded-xl border border-rose-100 bg-rose-50/70 px-4 py-3 text-[13px] text-rose-700">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {notice && !error && (
        <p className="mt-3 text-[12px] text-emerald-600">{notice}</p>
      )}

      {routeKbId > 0 && <KbTabs kbId={routeKbId} active="practice" />}

      {/* 挑题（按知识点/难度/公司看题）不再占一个侧栏位置，收进练习页，需要时点进去 */}
      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-slate-400">
        <span>随机抽题练手感</span>
        <span className="text-slate-300">·</span>
        <Link
          to={kbId != null ? `/kb/questions?kbId=${kbId}` : '/kb/questions'}
          className="inline-flex items-center gap-1 text-slate-500 transition-colors hover:text-primary-600"
        >
          想按知识点挑题看答案 →
        </Link>
      </div>

      <section className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
        {statsPhase === 'loading' ? (
          // 数据没到就别显示 0：屏幕上出现 "0" 就是一句假话
          Array.from({ length: 4 }).map((_, index) => <StatCardSkeleton key={index} />)
        ) : (
          <>
            <StatCard label="题库总题数" value={stats?.totalQuestions ?? 0} hint={`已挂知识点 ${stats?.assignedQuestions ?? 0}`} />
            <StatCard label="做过" value={stats?.attemptedQuestions ?? 0} hint={`作答 ${stats?.answered ?? 0} 次`} />
            <StatCard
              label="正确率"
              // 一次都没作答时没有正确率可言，显示 0% 等于告诉人"你全错了"
              value={stats && stats.answered > 0 ? percent(stats.accuracy) : '—'}
              hint={`对 ${stats?.correct ?? 0} / 错 ${stats?.wrong ?? 0}`}
            />
            <StatCard label="待复习错题" value={stats?.wrongDistinctQuestions ?? 0} hint="错题本按错得次数排" />
          </>
        )}
      </section>

      {coverage && coverage.totalQuestions > 0 && coverage.coverageRatio < 0.85 && (
        <div className="mt-4 flex flex-wrap items-center gap-2 rounded-xl border border-amber-100 bg-amber-50/70 px-4 py-3 text-[13px] text-amber-800">
          <TriangleAlert className="h-4 w-4" />
          <span>
            只有 {percent(coverage.coverageRatio)} 的题目挂了知识点（{coverage.assignedQuestions}/
            {coverage.totalQuestions}），有 {coverage.uncategorizedQuestions} 题未分类、
            {coverage.lowConfidenceQuestions} 题低置信——答错时可能跳不到知识点。
          </span>
          {kbId != null && (
            <Link to={`/kb/${kbId}/catalog`} className="font-medium underline underline-offset-2">
              去补目录
            </Link>
          )}
        </div>
      )}

      {coverage && coverage.gaps.length > 0 && (
        <details className="mt-3 rounded-xl border border-slate-200 bg-white px-4 py-3">
          <summary className="flex cursor-pointer select-none flex-wrap items-center gap-2 text-[13px] font-medium text-slate-700">
            <span>
              目录体检：{coverage.uncategorizedQuestions} 题没挂知识点、{coverage.lowConfidenceQuestions} 题低置信待确认
            </span>
            {coverage.gaps.some(gap => gap.categoryId != null) && (
              <button
                type="button"
                disabled={busy || kbId == null}
                onClick={() =>
                  confirmAllGaps(
                    coverage.gaps.filter(gap => gap.categoryId != null).map(gap => gap.questionId)
                  )
                }
                className="rounded-md border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[11.5px] font-normal text-emerald-700 disabled:opacity-50"
              >
                全部确认（{coverage.gaps.filter(gap => gap.categoryId != null).length} 条）
              </button>
            )}
          </summary>
          <ul className="mt-3 space-y-2">
            {coverage.gaps.map(gap => (
              <li
                key={gap.questionId}
                className="flex flex-wrap items-start gap-x-3 gap-y-1.5 rounded-lg border border-slate-100 bg-slate-50/60 px-3 py-2"
              >
                <p className="min-w-0 flex-1 text-[12.5px] leading-5 text-slate-700">{gap.stem}</p>
                {gap.categoryId ? (
                  <span className="flex items-center gap-2">
                    <span className="text-[11.5px] text-slate-500">
                      建议：{gap.categoryName ?? '未命名节点'}
                      {gap.confidence != null && (
                        <span className="ml-1 text-slate-400">（{(gap.confidence * 100).toFixed(0)}%）</span>
                      )}
                    </span>
                    <button
                      type="button"
                      disabled={busy || kbId == null}
                      onClick={() => confirmGap(gap.questionId)}
                      className="rounded-md border border-emerald-200 bg-emerald-50 px-2 py-1 text-[11.5px] text-emerald-700 disabled:opacity-50"
                    >
                      确认归属
                    </button>
                    <Link
                      to={`/kb/${kbId}/catalog?node=${gap.categoryId}`}
                      className="text-[11.5px] text-primary-600 hover:underline"
                    >
                      改挂
                    </Link>
                  </span>
                ) : (
                  <span className="flex items-center gap-2">
                    <span className="text-[11.5px] text-amber-600">未挂知识点，答错跳不过去</span>
                    <Link
                      to={`/kb/${kbId}/catalog`}
                      className="text-[11.5px] text-primary-600 hover:underline"
                    >
                      去挂一个
                    </Link>
                  </span>
                )}
              </li>
            ))}
          </ul>
          {coverage.gaps.length >= 20 && (
            <p className="mt-2 text-[11px] text-slate-400">只显示前 20 条，补完一批再刷新。</p>
          )}

          {coverage.documents.length > 0 && (
            <div className="mt-4 border-t border-slate-100 pt-3">
              <p className="text-[11px] font-medium text-slate-500">按文档看缺口</p>
              <ul className="mt-2 space-y-1.5">
                {coverage.documents.map(doc => {
                  const pending = Math.max(0, doc.total - doc.assigned)
                  return (
                    <li key={doc.documentId} className="flex flex-wrap items-center gap-2 text-[11.5px]">
                      <span className="min-w-0 flex-1 truncate text-slate-600" title={doc.documentName}>
                        {doc.documentName}
                      </span>
                      <span className="tabular-nums text-slate-400">
                        {doc.assigned}/{doc.total} 已挂
                      </span>
                      {pending > 0 && (
                        <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[10.5px] text-amber-700">
                          未挂 {pending}
                        </span>
                      )}
                      {doc.lowConfidence > 0 && (
                        <span className="rounded bg-rose-50 px-1.5 py-0.5 text-[10.5px] text-rose-600">
                          待确认 {doc.lowConfidence}
                        </span>
                      )}
                      {(pending > 0 || doc.lowConfidence > 0) && (
                        <button
                          type="button"
                          onClick={() => classifyDocument(doc.documentId, doc.lowConfidence > 0)}
                          disabled={classifyingDocId === doc.documentId}
                          className="rounded border border-slate-200 px-1.5 py-0.5 text-[10.5px] text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-40"
                          title={
                            doc.lowConfidence > 0
                              ? '清掉低置信建议后重跑这份文档的归类'
                              : '把这份文档里未挂知识点的题目归类一次'
                          }
                        >
                          {classifyingDocId === doc.documentId ? '归类中…' : '一键归类'}
                        </button>
                      )}
                      <Link
                        to={`/kb/${kbId}/catalog`}
                        className="text-[11px] text-primary-600 hover:underline"
                      >
                        看目录
                      </Link>
                    </li>
                  )
                })}
              </ul>
            </div>
          )}
        </details>
      )}

      <div className="mt-5 grid gap-5 lg:grid-cols-[1.55fr_1fr]">
        <div className="rounded-2xl border border-slate-200 bg-white p-5">
          {!session ? (
            <div className="flex h-64 flex-col items-center justify-center text-center">
              <BookOpen className="h-7 w-7 text-slate-300" />
              <p className="mt-3 text-sm text-slate-500">
                {emptyBank ? '这个题库还没有题目：先去「我的资料」打开「面试题库」上传题库文档' : '选好题库和题量，点「开始刷题」'}
              </p>
              <p className="mt-1 text-xs text-slate-400">
                {emptyBank
                  ? '文档入库完成（就绪）后，题目会按题号自动拆出来。'
                  : '题目按知识点分层抽，错过的题会优先出现；一次最多 20 题。'}
              </p>
            </div>
          ) : finished ? (
            <SummaryPanel
              session={session}
              grades={grades}
              reveals={reveals}
              onRestart={start}
              busy={busy}
            />
          ) : item ? (
            <div>
              <div className="flex items-center justify-between text-xs text-slate-500">
                <span>
                  第 {current + 1} / {items.length} 题 · 已答 {answeredCount}
                </span>
                <span className="flex items-center gap-1.5">
                  {difficulty && (
                    <span className={`rounded border px-1.5 py-0.5 text-[10px] ${difficulty.className}`}>
                      {difficulty.label}
                    </span>
                  )}
                  {item.tags.slice(0, 3).map(tag => (
                    <span key={tag} className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">
                      #{tag}
                    </span>
                  ))}
                </span>
              </div>
              <div className="mt-2 h-1 w-full overflow-hidden rounded-full bg-slate-100">
                <div
                  className="h-full rounded-full bg-primary-500 transition-all"
                  style={{ width: `${Math.round((answeredCount / Math.max(1, items.length)) * 100)}%` }}
                />
              </div>

              <p className="mt-5 whitespace-pre-wrap text-[15px] leading-7 text-slate-900">{item.stem}</p>

              {!reveal ? (
                <button
                  type="button"
                  onClick={revealAnswer}
                  disabled={busy}
                  className="mt-6 inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-4 py-2 text-[13px] font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                >
                  <CircleHelp className="h-4 w-4" />
                  我答完了，看答案
                </button>
              ) : (
                <div className="mt-6">
                  <div className="rounded-xl border border-slate-200 bg-slate-50/60 p-4">
                    <p className="text-[11px] font-medium uppercase tracking-wide text-slate-400">参考答案</p>
                    <div className="mt-2 text-[13.5px] leading-7 text-slate-700">
                      {reveal.answer ? (
                        renderChatMarkdown(reveal.answer, () => {})
                      ) : (
                        <span className="text-slate-400">这道题没有解析答案，凭印象自评。</span>
                      )}
                    </div>
                    <p className="mt-3 text-[10.5px] text-slate-400">
                      从看题到展开答案用了 {reveal.thinkingSeconds} 秒
                      {reveal.thinkingSeconds < 10 ? '（偏快，可能没在回忆）' : ''}
                    </p>
                  </div>

                  <div className="mt-4 flex flex-wrap items-center gap-2">
                    {GRADES.map(entry => {
                      const Icon = entry.icon
                      const active = grade?.result === entry.result
                      return (
                        <button
                          key={entry.result}
                          type="button"
                          disabled={busy}
                          onClick={() => submitGrade(entry.result)}
                          className={`inline-flex items-center gap-1.5 rounded-lg border px-3.5 py-2 text-[13px] transition-colors disabled:opacity-50 ${
                            active ? entry.className : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                          }`}
                        >
                          <Icon className="h-4 w-4" />
                          {entry.label}
                        </button>
                      )
                    })}
                    {grade && !grade.finished && (
                      <button
                        type="button"
                        onClick={gotoNext}
                        className="ml-auto inline-flex items-center gap-1 rounded-lg bg-slate-900 px-3.5 py-2 text-[13px] font-medium text-white"
                      >
                        下一题
                        <ArrowRight className="h-4 w-4" />
                      </button>
                    )}
                  </div>

                  {grade && !grade.correct && (
                    <PointCard kbId={kbId} point={grade.point} related={grade.related} />
                  )}
                </div>
              )}
            </div>
          ) : null}
        </div>

        <aside className="space-y-4">
          <div className="rounded-2xl border border-slate-200 bg-white p-4">
            <div className="flex items-center justify-between">
              <h2 className="text-[13px] font-semibold text-slate-800">能力值</h2>
              <span className="text-[11px] text-slate-400">弱 → 强</span>
            </div>
            {skills && skills.points.length > 0 ? (
              <>
                <ul className="mt-3 space-y-2">
                  {skills.points.slice(0, 6).map(point => (
                    <li key={point.categoryId} className="text-[12.5px]">
                      <div className="flex items-start justify-between gap-2">
                        <Link
                          to={`/kb/${kbId}/catalog?node=${point.categoryId}`}
                          className="min-w-0 flex-1 truncate text-slate-600 hover:text-primary-700"
                          title={point.path ?? point.name}
                        >
                          {point.name}
                        </Link>
                        <span
                          className={`shrink-0 rounded px-1.5 py-0.5 text-[10.5px] tabular-nums ${
                            point.wrongRate >= 0.6
                              ? 'bg-rose-50 text-rose-600'
                              : point.wrongRate >= 0.45
                                ? 'bg-amber-50 text-amber-700'
                                : 'bg-emerald-50 text-emerald-700'
                          }`}
                        >
                          下次答错 {(point.wrongRate * 100).toFixed(0)}%
                        </span>
                      </div>
                      <p className="mt-0.5 text-[10.5px] text-slate-400">
                        能力 {point.thetaEffective.toFixed(2)} · 做过 {point.attempts} 次
                        {point.attempts < 2 && ' · 样本不足'}
                        {point.interviewCount > 0 && ` · 面试 ${point.interviewCount} 次`}
                      </p>
                    </li>
                  ))}
                </ul>
                {skills.branches.length > 0 && (
                  <div className="mt-3 border-t border-slate-100 pt-2">
                    <p className="text-[10.5px] text-slate-400">整块弱（按题量加权）</p>
                    <ul className="mt-1.5 space-y-1">
                      {skills.branches.slice(0, 3).map(branch => (
                        <li key={branch.categoryId} className="flex items-center justify-between gap-2 text-[11.5px]">
                          <Link
                            to={`/kb/${kbId}/catalog?node=${branch.categoryId}`}
                            className="min-w-0 flex-1 truncate text-slate-500 hover:text-primary-700"
                          >
                            {branch.name}
                          </Link>
                          <span className="shrink-0 tabular-nums text-slate-400">
                            {(branch.wrongRate * 100).toFixed(0)}%
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </>
            ) : (
              <p className="mt-3 text-xs text-slate-400">
                还没有能力值。答过几题之后，这里会按「下次答错的概率」排弱项——不是按错题数。
              </p>
            )}
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-4">
            <div className="flex items-center justify-between">
              <h2 className="text-[13px] font-semibold text-slate-800">薄弱知识点</h2>
              <span className="text-[11px] text-slate-400">按错题数</span>
            </div>
            {stats && stats.weakPoints.length > 0 ? (
              <ul className="mt-3 space-y-2">
                {stats.weakPoints.map(point => (
                  <li key={point.categoryId} className="flex items-start justify-between gap-2 text-[12.5px]">
                    <Link
                      to={`/kb/${kbId}/catalog?node=${point.categoryId}`}
                      className="min-w-0 flex-1 truncate text-slate-600 hover:text-primary-700"
                      title={point.path ?? point.name}
                    >
                      {point.path ?? point.name}
                    </Link>
                    <span className="shrink-0 rounded bg-rose-50 px-1.5 py-0.5 text-[10.5px] text-rose-600">
                      错 {point.wrongCount}
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-3 text-xs text-slate-400">还没有错题。答错几道之后这里会按知识点排序。</p>
            )}
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-4">
            <div className="flex items-center justify-between">
              <h2 className="text-[13px] font-semibold text-slate-800">错题本</h2>
              <span className="text-[11px] text-slate-400">{wrongBook.length} 题</span>
            </div>
            {wrongBook.length > 0 ? (
              <ul className="mt-3 max-h-[420px] space-y-2 overflow-y-auto pr-1">
                {wrongBook.map(entry => (
                  <li key={entry.questionId} className="rounded-lg border border-slate-100 bg-slate-50/60 p-2.5">
                    <p className="line-clamp-2 text-[12.5px] leading-5 text-slate-700">{entry.stem}</p>
                    <div className="mt-1.5 flex items-center justify-between gap-2">
                      {entry.categoryId ? (
                        <Link
                          to={`/kb/${kbId}/catalog?node=${entry.categoryId}`}
                          className="truncate text-[11px] text-primary-600 hover:underline"
                          title={entry.categoryPath ?? ''}
                        >
                          {entry.categoryPath ?? '查看知识点'}
                        </Link>
                      ) : (
                        <span className="text-[11px] text-slate-400">未挂知识点</span>
                      )}
                      <span className="shrink-0 text-[10.5px] text-slate-400">错 {entry.wrongCount} 次</span>
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-3 text-xs text-slate-400">暂无错题。</p>
            )}
          </div>

          {activeBase && (
            <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-3 text-[11.5px] text-slate-400">
              「{activeBase.name}」共 {activeBase.documentCount} 份文档 / {activeBase.chunkCount} 个切片。
              抽题不走向量检索：题目是抽样，知识点是目录。
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}

function StatCard({ label, value, hint }: { label: string; value: number | string; hint?: string }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white px-3.5 py-3">
      <p className="text-[11px] text-slate-400">{label}</p>
      <p className="mt-1 text-lg font-semibold tabular-nums text-slate-900">{value}</p>
      {hint && <p className="mt-0.5 text-[10.5px] text-slate-400">{hint}</p>}
    </div>
  )
}

/** 读数据期间的占位：只有灰条，不写 0。 */
function StatCardSkeleton() {
  return (
    <div className="rounded-xl border border-slate-200 bg-white px-3.5 py-3">
      <div className="h-2.5 w-14 animate-pulse rounded bg-slate-100" />
      <div className="mt-2 h-5 w-9 animate-pulse rounded bg-slate-100" />
      <div className="mt-1.5 h-2 w-20 animate-pulse rounded bg-slate-100" />
    </div>
  )
}

function PointCard({
  kbId,
  point,
  related
}: {
  kbId: number | null
  point: KbQuizGradeView['point']
  related: KbQuizGradeView['related']
}) {
  if (!point) {
    return (
      <div className="mt-4 rounded-xl border border-dashed border-slate-200 px-4 py-3 text-[12.5px] text-slate-500">
        这道题还没有挂知识点，所以跳不了目录。去「知识目录」把它挂到对应节点，下次答错就能直接跳。
      </div>
    )
  }
  return (
    <div className="mt-4 rounded-xl border border-primary-100 bg-primary-50/40 p-4">
      <p className="text-[11px] font-medium uppercase tracking-wide text-primary-500">对应的知识点</p>
      <p className="mt-1 text-[13px] font-medium text-slate-800">{point.path}</p>
      {point.description && <p className="mt-1 text-[12px] text-slate-500">{point.description}</p>}
      <Link
        to={point.catalogLink}
        className="mt-2 inline-flex items-center gap-1 text-[12.5px] font-medium text-primary-700 hover:underline"
      >
        去知识点目录看
        <ArrowRight className="h-3.5 w-3.5" />
      </Link>
      {related.length > 0 && (
        <div className="mt-3 border-t border-primary-100/70 pt-2">
          <p className="text-[10.5px] text-slate-400">相关知识点</p>
          <div className="mt-1 flex flex-wrap gap-1.5">
            {related.map(entry => (
              <Link
                key={`${entry.categoryId}-${entry.relation}`}
                to={kbId == null ? '#' : `/kb/${kbId}/catalog?node=${entry.categoryId}`}
                className="rounded bg-white px-2 py-0.5 text-[11.5px] text-slate-600 hover:text-primary-700"
                title={entry.relation === 'PREREQUISITE' ? '前置' : entry.relation === 'CONTRAST' ? '对比' : '相关'}
              >
                {entry.name}
              </Link>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function SummaryPanel({
  session,
  grades,
  reveals,
  onRestart,
  busy
}: {
  session: KbQuizSessionView
  grades: Record<number, KbQuizGradeView>
  reveals: Record<number, KbQuizRevealView>
  onRestart: () => void
  busy: boolean
}) {
  const summary = useMemo(() => {
    for (const item of session.items) {
      const grade = grades[item.attemptId]
      if (grade?.summary) return grade.summary
    }
    return null
  }, [grades, session.items])

  const wrongItems = summary?.wrongItems ?? []

  return (
    <div>
      <div className="flex items-center justify-between">
        <h2 className="text-[15px] font-semibold text-slate-900">这组做完了</h2>
        <button
          type="button"
          onClick={onRestart}
          disabled={busy}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-[12.5px] text-slate-600 hover:bg-slate-50 disabled:opacity-50"
        >
          <RotateCcw className="h-3.5 w-3.5" />
          再抽一组
        </button>
      </div>
      <div className="mt-4 grid grid-cols-3 gap-3">
        <StatCard label="答对" value={summary?.correctCount ?? session.correctCount} />
        <StatCard label="答错" value={summary?.wrongCount ?? session.wrongCount} />
        <StatCard label="正确率" value={summary ? percent(summary.accuracy) : '—'} />
      </div>

      {wrongItems.length > 0 ? (
        <div className="mt-5">
          <p className="text-[12px] font-medium text-slate-600">错题 → 知识点</p>
          <ul className="mt-2 space-y-2">
            {wrongItems.map(entry => (
              <li key={entry.questionId} className="rounded-lg border border-slate-100 bg-slate-50/60 p-3">
                <p className="text-[12.5px] leading-5 text-slate-700">{entry.stem}</p>
                {entry.categoryPath ? (
                  <Link
                    to={`/kb/${session.kbId}/catalog?node=${entry.categoryId}`}
                    className="mt-1 inline-flex items-center gap-1 text-[11.5px] text-primary-600 hover:underline"
                  >
                    {entry.categoryPath}
                    <ArrowRight className="h-3 w-3" />
                  </Link>
                ) : (
                  <p className="mt-1 text-[11px] text-slate-400">未挂知识点</p>
                )}
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <p className="mt-5 text-[13px] text-emerald-600">全对。</p>
      )}

      <details className="mt-4 text-[12px] text-slate-500">
        <summary className="cursor-pointer select-none">看这组的题和答案</summary>
        <ul className="mt-2 space-y-3">
          {session.items.map(entry => {
            const reveal = reveals[entry.attemptId]
            return (
              <li key={entry.attemptId} className="rounded-lg border border-slate-100 p-3">
                <p className="text-[12.5px] text-slate-700">{entry.stem}</p>
                {reveal?.answer && (
                  <div className="mt-1 text-[12px] text-slate-500">{renderChatMarkdown(reveal.answer, () => {})}</div>
                )}
              </li>
            )
          })}
        </ul>
      </details>
    </div>
  )
}

