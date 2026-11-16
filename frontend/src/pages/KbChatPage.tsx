import { useEffect, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import KbTabs from '@/components/KbTabs'
import {
  BookOpen,
  Loader2,
  MessageSquare,
  Minus,
  Plus,
  Send,
  Sparkles,
  Trash2
} from 'lucide-react'
import { kbApi, type KnowledgeBaseView } from '@/api/kb'
import {
  chatApi,
  type KbChatCitation,
  type KbChatMessageView,
  type KbChatSessionView,
  type KbChatStage
} from '@/api/chat'
import ChatProcessGraph from '@/components/ChatProcessGraph'
import { renderChatMarkdown } from '@/utils/chatMarkdown'

type Mode = 'hybrid' | 'keyword' | 'vector'

const MODES: { value: Mode; label: string }[] = [
  { value: 'hybrid', label: '混合' },
  { value: 'keyword', label: '关键词' },
  { value: 'vector', label: '向量' }
]

const EXAMPLES = [
  'RDB 和 AOF 的区别是什么？',
  'Spring 事务失效的常见场景',
  '线程池参数怎么配才合理？'
]

interface ChatItem {
  key: string
  role: 'user' | 'assistant'
  content: string
  citations: KbChatCitation[]
  stages: KbChatStage[]
  streaming: boolean
  error?: string
  latencyMs?: number | null
  startedAt?: number
  fromHistory?: boolean
}

export default function KbChatPage() {
  const [params] = useSearchParams()
  // 库内进来时（/kb/:id/chat）这个库就是唯一选项，不再给选择器
  const routeKbId = Number(useParams().id)
  const [kbs, setKbs] = useState<KnowledgeBaseView[]>([])
  const [kbId, setKbId] = useState<number | null>(Number.isFinite(routeKbId) && routeKbId > 0 ? routeKbId : null)
  const [sessions, setSessions] = useState<KbChatSessionView[]>([])
  const [sessionId, setSessionId] = useState<number | null>(null)
  const [items, setItems] = useState<ChatItem[]>([])
  const [draft, setDraft] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [mode, setMode] = useState<Mode>('hybrid')
  const [topK, setTopK] = useState(8)
  const [rerank, setRerank] = useState(true)
  const [publishedOnly, setPublishedOnly] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const [highlight, setHighlight] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [booting, setBooting] = useState(true)

  const scrollRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const kb = kbs.find(item => item.id === kbId) ?? null

  useEffect(() => {
    let cancelled = false
    kbApi
      .list()
      .then(list => {
        if (cancelled) return
        setKbs(list)
        const wanted = Number(params.get('kbId'))
        const fromRoute = list.find(item => item.id === routeKbId)
        const initial = fromRoute ?? list.find(item => item.id === wanted) ?? list[0]
        if (initial) setKbId(initial.id)
      })
      .catch(error => {
        if (!cancelled) setNotice(error instanceof Error ? error.message : '知识库加载失败')
      })
      .finally(() => {
        if (!cancelled) setBooting(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!kbId) return
    const base = kbs.find(item => item.id === kbId)
    if (base) {
      setTopK(base.topK)
      setRerank(base.rerankEnabled)
      setMode('hybrid')
    }
    setSessionId(null)
    setItems([])
    setNotice(null)
    void loadSessions(kbId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kbId])

  useEffect(() => {
    if (!streaming) return
    const timer = window.setInterval(() => setNow(Date.now()), 200)
    return () => window.clearInterval(timer)
  }, [streaming])

  useEffect(() => {
    const container = scrollRef.current
    if (container) {
      container.scrollTop = container.scrollHeight
    }
  }, [items, streaming])

  async function loadSessions(targetKbId: number) {
    try {
      const list = await chatApi.sessions(targetKbId)
      setSessions(list)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '会话加载失败')
    }
  }

  async function openSession(nextSessionId: number) {
    if (!kbId || streaming) return
    setNotice(null)
    setSessionId(nextSessionId)
    try {
      const messages = await chatApi.messages(kbId, nextSessionId)
      setItems(messages.map(toItem))
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '会话内容加载失败')
    }
  }

  function startNew() {
    if (streaming) return
    setSessionId(null)
    setItems([])
    setNotice(null)
    setDraft('')
    textareaRef.current?.focus()
  }

  async function removeSession(nextSessionId: number) {
    if (!kbId || streaming) return
    try {
      await chatApi.deleteSession(kbId, nextSessionId)
      setSessions(previous => previous.filter(item => item.id !== nextSessionId))
      if (sessionId === nextSessionId) {
        startNew()
      }
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '删除会话失败')
    }
  }

  function patch(key: string, updater: (item: ChatItem) => ChatItem) {
    setItems(previous => previous.map(item => (item.key === key ? updater(item) : item)))
  }

  async function send(questionOverride?: string) {
    const question = (questionOverride ?? draft).trim()
    if (!question || streaming || !kbId) return

    const startedAt = Date.now()
    const assistantKey = `assistant-${startedAt}`
    const userKey = `user-${startedAt}`
    setDraft('')
    setNotice(null)
    setStreaming(true)
    setNow(startedAt)
    setItems(previous => [
      ...previous,
      {
        key: userKey,
        role: 'user',
        content: question,
        citations: [],
        stages: [],
        streaming: false
      },
      {
        key: assistantKey,
        role: 'assistant',
        content: '',
        citations: [],
        stages: [],
        streaming: true,
        startedAt
      }
    ])

    try {
      const answer = await chatApi.stream(
        kbId,
        {
          sessionId: sessionId ?? undefined,
          question,
          topK,
          mode,
          rerank,
          publishedOnly
        },
        {
          onSession: info => {
            setSessionId(info.id)
            void loadSessions(kbId)
          },
          onStage: stage => {
            patch(assistantKey, item => ({ ...item, stages: upsertStage(item.stages, stage) }))
          },
          onCitations: citations => {
            patch(assistantKey, item => ({ ...item, citations }))
          },
          onToken: token => {
            patch(assistantKey, item => ({ ...item, content: item.content + token }))
          },
          onDone: done => {
            patch(assistantKey, item => ({
              ...item,
              content: done.content || item.content,
              citations: done.citations.length > 0 ? done.citations : item.citations,
              latencyMs: done.latencyMs,
              streaming: false
            }))
          }
        }
      )
      patch(assistantKey, item => ({
        ...item,
        content: answer.content || item.content,
        citations: answer.citations.length > 0 ? answer.citations : item.citations,
        latencyMs: answer.latencyMs,
        streaming: false
      }))
      if (kbId) void loadSessions(kbId)
    } catch (error) {
      const message = error instanceof Error ? error.message : '回答失败，请稍后重试'
      patch(assistantKey, item => ({ ...item, streaming: false, error: message }))
      setNotice(message)
    } finally {
      setStreaming(false)
    }
  }

  function scrollToCitation(key: string, index: number) {
    const id = `cite-${key}-${index}`
    const element = document.getElementById(id)
    if (!element) return
    element.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setHighlight(id)
    window.setTimeout(() => setHighlight(current => (current === id ? null : current)), 1600)
  }

  if (booting) {
    return (
      <div className="flex h-[calc(100vh-5rem)] items-center justify-center">
        <Loader2 className="h-5 w-5 animate-spin text-primary-500" />
      </div>
    )
  }

  return (
    <div className="flex h-[calc(100vh-5rem)] flex-col gap-3">
      {routeKbId > 0 && (
        <div className="shrink-0">
          <KbTabs kbId={routeKbId} active="chat" />
        </div>
      )}
      <div className="flex min-h-0 flex-1 gap-4">
        <aside className="flex w-[236px] shrink-0 flex-col gap-3">
          <div className="card p-3">
            <label htmlFor="kb-chat-kb" className="text-[11px] font-medium text-slate-400">
              知识库
            </label>
            <select
              id="kb-chat-kb"
              value={kbId ?? ''}
              onChange={event => setKbId(Number(event.target.value))}
            className="mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-2.5 py-2 text-[12.5px] text-slate-700 outline-none transition focus:border-primary-300 focus:ring-4 focus:ring-primary-500/10"
          >
            {kbs.length === 0 && <option value="">暂无知识库</option>}
            {kbs.map(item => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
          {kb && (
            <p className="mt-1.5 text-[10.5px] text-slate-400">
              {kb.documentCount} 篇文档 · 阈值 {kb.scoreThreshold} · 默认 topK {kb.topK}
            </p>
          )}
        </div>

        <button type="button" onClick={startNew} className="btn-primary w-full py-2 text-[12.5px]">
          <Plus className="h-4 w-4" />
          新对话
        </button>

        <div className="card flex min-h-0 flex-1 flex-col overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 px-3 py-2">
            <span className="text-[11px] font-medium text-slate-400">历史会话</span>
            <span className="text-[10.5px] text-slate-300">{sessions.length}</span>
          </div>
          <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto p-1.5">
            {sessions.length === 0 ? (
              <p className="px-2 py-8 text-center text-[11px] text-slate-400">还没有会话，直接提问即可</p>
            ) : (
              sessions.map(item => {
                const active = item.id === sessionId
                return (
                  <div
                    key={item.id}
                    className={`group flex items-center gap-1 rounded-lg px-2 py-1.5 transition-colors ${
                      active ? 'bg-primary-50' : 'hover:bg-slate-50'
                    }`}
                  >
                    <button
                      type="button"
                      onClick={() => void openSession(item.id)}
                      className="min-w-0 flex-1 text-left"
                    >
                      <span
                        className={`block truncate text-[11.5px] ${
                          active ? 'font-medium text-primary-700' : 'text-slate-600'
                        }`}
                      >
                        {item.title}
                      </span>
                      <span className="block text-[10px] text-slate-400">{formatRelative(item.updatedAt)}</span>
                    </button>
                    <button
                      type="button"
                      title="删除会话"
                      onClick={() => void removeSession(item.id)}
                      className="shrink-0 rounded p-1 text-slate-300 opacity-0 transition hover:bg-rose-50 hover:text-rose-500 group-hover:opacity-100"
                    >
                      <Trash2 className="h-3 w-3" />
                    </button>
                  </div>
                )
              })
            )}
          </div>
        </div>
      </aside>

      <section className="card flex min-w-0 flex-1 flex-col overflow-hidden">
        <header className="flex items-center justify-between gap-3 border-b border-slate-100 px-5 py-3">
          <div className="min-w-0">
            <h1 className="flex items-center gap-2 text-[13.5px] font-semibold text-slate-800">
              <MessageSquare className="h-4 w-4 text-primary-500" />
              问答助手
            </h1>
            <p className="mt-0.5 truncate text-[11px] text-slate-400">
              回答只依据「{kb?.name ?? '知识库'}」，每个结论都能点回引用
            </p>
          </div>
          <Link
            to={kb ? `/kb/${kb.id}` : '/kb'}
            className="flex shrink-0 items-center gap-1 text-[11px] text-primary-600 transition hover:text-primary-700"
          >
            <BookOpen className="h-3.5 w-3.5" />
            管理文档
          </Link>
        </header>

        <div ref={scrollRef} className="scrollbar-thin min-h-0 flex-1 overflow-y-auto px-5 py-5">
          {items.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center text-center">
              <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/25">
                <Sparkles className="h-6 w-6" />
              </div>
              <h2 className="mt-3 text-sm font-semibold text-slate-800">基于知识库问答</h2>
              <p className="mt-1 max-w-sm text-[11.5px] leading-relaxed text-slate-400">
                检索、重排、生成的过程会实时画成思考链路；答案里的 [1][2] 可以直接点回原文片段。
              </p>
              <div className="mt-4 flex flex-wrap justify-center gap-2">
                {EXAMPLES.map(example => (
                  <button
                    key={example}
                    type="button"
                    onClick={() => void send(example)}
                    className="rounded-full border border-slate-200 bg-white px-3 py-1.5 text-[11px] text-slate-500 transition hover:border-primary-200 hover:text-primary-600"
                  >
                    {example}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="space-y-6">
              {items.map(item =>
                item.role === 'user' ? (
                  <div key={item.key} className="flex justify-end">
                    <div className="max-w-[78%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-gradient-to-br from-primary-500 to-primary-600 px-4 py-2.5 text-[13px] leading-relaxed text-white shadow-md shadow-primary-500/20">
                      {item.content}
                    </div>
                  </div>
                ) : (
                  <div key={item.key} className="space-y-2.5">
                    <div className="flex items-center gap-2 text-[10.5px] text-slate-400">
                      <span className="flex h-5 w-5 items-center justify-center rounded-md bg-gradient-to-br from-primary-500 to-primary-600 text-white">
                        <Sparkles className="h-3 w-3" />
                      </span>
                      <span className="font-medium text-slate-500">Panel</span>
                      {item.latencyMs != null && <span>· {formatMs(item.latencyMs)}</span>}
                      {item.streaming && <span>· 正在生成</span>}
                      {item.fromHistory && <span>· 历史会话</span>}
                    </div>

                    {item.stages.length > 0 && (
                      <ChatProcessGraph
                        stages={item.stages}
                        phase={item.streaming ? 'running' : item.error ? 'error' : 'done'}
                        elapsedMs={
                          item.streaming ? now - (item.startedAt ?? now) : (item.latencyMs ?? null)
                        }
                        defaultCollapsed={Boolean(item.fromHistory)}
                      />
                    )}

                    {item.content ? (
                      <div className="space-y-1.5">
                        {renderChatMarkdown(item.content, index => scrollToCitation(item.key, index))}
                      </div>
                    ) : (
                      item.streaming && (
                        <div className="space-y-2 py-1">
                          <div className="flex items-center gap-2 text-[10.5px] text-slate-400">
                            <span className="chat-typing flex items-center gap-1">
                              <span className="h-1.5 w-1.5 rounded-full bg-primary-400" />
                              <span className="h-1.5 w-1.5 rounded-full bg-primary-400" />
                              <span className="h-1.5 w-1.5 rounded-full bg-primary-400" />
                            </span>
                            正在组织答案
                          </div>
                          <div className="h-2.5 w-[92%] animate-pulse rounded-full bg-slate-100" />
                          <div className="h-2.5 w-[78%] animate-pulse rounded-full bg-slate-100" />
                          <div className="h-2.5 w-[56%] animate-pulse rounded-full bg-slate-100" />
                        </div>
                      )
                    )}

                    {item.error && (
                      <div className="rounded-xl border border-rose-100 bg-rose-50 px-3 py-2 text-[11.5px] text-rose-600">
                        {item.error}
                      </div>
                    )}

                    {item.citations.length > 0 && (!item.streaming || Boolean(item.error)) && (
                      <div className="rounded-xl border border-slate-100 bg-slate-50/70 p-2.5">
                        <p className="mb-1.5 text-[10.5px] font-medium text-slate-400">
                          引用 {item.citations.length} 条
                        </p>
                        <div className="space-y-1">
                          {item.citations.map(citation => {
                            const id = `cite-${item.key}-${citation.index}`
                            return (
                              <div
                                key={id}
                                id={id}
                                className={`flex gap-2 rounded-lg bg-white px-2 py-1.5 transition-shadow ${
                                  highlight === id ? 'ring-2 ring-primary-300' : ''
                                }`}
                              >
                                <span className="mt-0.5 flex h-4 min-w-4 shrink-0 items-center justify-center rounded bg-primary-50 px-1 text-[9.5px] font-semibold text-primary-600">
                                  {citation.index}
                                </span>
                                <div className="min-w-0 flex-1">
                                  <p className="truncate text-[11px] font-medium text-slate-700">
                                    {citation.documentName ?? '未命名文档'}
                                    {citation.heading ? ` · ${citation.heading}` : ''}
                                    {citation.pageNo ? ` · P${citation.pageNo}` : ''}
                                  </p>
                                  <p className="line-clamp-2 text-[10.5px] leading-snug text-slate-400">
                                    {citation.excerpt}
                                  </p>
                                </div>
                                <span className="shrink-0 text-[9.5px] tabular-nums text-slate-400">
                                  {citation.score.toFixed(3)}
                                </span>
                              </div>
                            )
                          })}
                        </div>
                      </div>
                    )}
                  </div>
                )
              )}
            </div>
          )}
        </div>

        {notice && (
          <div className="mx-3 mb-1 rounded-lg border border-amber-100 bg-amber-50 px-3 py-1.5 text-[11px] text-amber-700">
            {notice}
          </div>
        )}

        <div className="border-t border-slate-100 p-3">
          <div className="rounded-2xl border border-slate-200 bg-white shadow-sm transition focus-within:border-primary-300 focus-within:ring-4 focus-within:ring-primary-500/10">
            <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 px-3 py-2">
              <span className="text-[11px] text-slate-400">模式</span>
              <div className="inline-flex rounded-lg bg-slate-100 p-0.5">
                {MODES.map(item => (
                  <button
                    key={item.value}
                    type="button"
                    onClick={() => setMode(item.value)}
                    className={`rounded-md px-2 py-0.5 text-[11px] transition ${
                      mode === item.value
                        ? 'bg-white font-medium text-primary-600 shadow-sm'
                        : 'text-slate-500 hover:text-slate-700'
                    }`}
                  >
                    {item.label}
                  </button>
                ))}
              </div>

              <span className="ml-1 text-[11px] text-slate-400">topK</span>
              <div className="inline-flex items-center gap-0.5 rounded-lg border border-slate-200 px-1 py-0.5">
                <button
                  type="button"
                  onClick={() => setTopK(value => Math.max(1, value - 1))}
                  className="flex h-5 w-5 items-center justify-center rounded text-slate-400 transition hover:bg-slate-100 hover:text-slate-600"
                >
                  <Minus className="h-3 w-3" />
                </button>
                <span className="w-5 text-center text-[11.5px] font-medium tabular-nums text-slate-700">{topK}</span>
                <button
                  type="button"
                  onClick={() => setTopK(value => Math.min(20, value + 1))}
                  className="flex h-5 w-5 items-center justify-center rounded text-slate-400 transition hover:bg-slate-100 hover:text-slate-600"
                >
                  <Plus className="h-3 w-3" />
                </button>
              </div>

              <Toggle checked={rerank} onChange={setRerank} label="启用 rerank" />
              <Toggle checked={publishedOnly} onChange={setPublishedOnly} label="仅已发布" />
            </div>

            <div className="flex items-end gap-2 p-2.5">
              <textarea
                ref={textareaRef}
                value={draft}
                onChange={event => setDraft(event.target.value)}
                onKeyDown={event => {
                  if (event.key === 'Enter' && !event.shiftKey) {
                    event.preventDefault()
                    void send()
                  }
                }}
                rows={1}
                placeholder="问点什么，比如：RDB 和 AOF 怎么选？"
                className="max-h-36 min-h-[36px] flex-1 resize-none bg-transparent px-1.5 py-1.5 text-[13px] leading-relaxed text-slate-800 outline-none placeholder:text-slate-400"
              />
              <button
                type="button"
                onClick={() => void send()}
                disabled={streaming || !draft.trim()}
                className="btn-primary h-9 shrink-0 px-3.5 text-[12.5px]"
              >
                {streaming ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                {streaming ? '生成中' : '发送'}
              </button>
            </div>
          </div>
          <p className="mt-1.5 px-1 text-[10px] text-slate-400">
            Enter 发送 · Shift+Enter 换行 · 回答基于所选知识库，未覆盖的内容会明确说明
          </p>
        </div>
        </section>
      </div>
    </div>
  )
}

function Toggle({
  checked,
  onChange,
  label
}: {
  checked: boolean
  onChange: (value: boolean) => void
  label: string
}) {
  return (
    <button
      type="button"
      onClick={() => onChange(!checked)}
      className="inline-flex items-center gap-1.5 text-[11px] text-slate-500 transition hover:text-slate-700"
    >
      <span
        className={`relative h-[18px] w-8 rounded-full transition-colors ${
          checked ? 'bg-primary-500' : 'bg-slate-200'
        }`}
      >
        <span
          className={`absolute top-[2px] h-3.5 w-3.5 rounded-full bg-white shadow transition-all ${
            checked ? 'left-[16px]' : 'left-[2px]'
          }`}
        />
      </span>
      {label}
    </button>
  )
}

function upsertStage(stages: KbChatStage[], next: KbChatStage): KbChatStage[] {
  const index = stages.findIndex(stage => stage.id === next.id)
  if (index < 0) return [...stages, next]
  const copy = [...stages]
  copy[index] = next
  return copy
}

function toItem(message: KbChatMessageView): ChatItem {
  return {
    key: `history-${message.id}`,
    role: message.role === 'USER' ? 'user' : 'assistant',
    content: message.content,
    citations: message.citations ?? [],
    stages: message.stages ?? [],
    streaming: false,
    latencyMs: message.latencyMs,
    fromHistory: true
  }
}

function formatMs(value: number) {
  if (value < 1000) return `${Math.round(value)}ms`
  return `${(value / 1000).toFixed(1)}s`
}

function formatRelative(value: string) {
  const time = new Date(value).getTime()
  if (Number.isNaN(time)) return ''
  const diff = Date.now() - time
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}
