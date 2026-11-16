import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, CheckCircle2, Eraser, Loader2, Save, FileCode2 } from 'lucide-react'
import { resumeApi } from '@/api/resume'
import { renderResumeMarkdown } from '@/utils/markdownView'

/**
 * Markdown 内容稿编辑器。
 *
 * <p>和原版排版编辑的分工：</p>
 * <ul>
 *   <li>原版排版（{@code PdfEditSurface}）：改"看到的版面"，导出 PDF 走它；</li>
 *   <li>这里：改"文本内容"，`#` 是标题、`- ` 是列表，整条经历复制粘贴改一份最顺手。</li>
 * </ul>
 *
 * <p>保存走 {@code format=markdown}：后端只挪 md 指针，PDF 原版、原位改动、
 * 「导出 PDF」都还在。所以从这边改完再回原版排版，两边的成果互不覆盖。</p>
 *
 * <p>不做语法约束：用户在左边写什么，右边就渲染成什么，认不出的语法原样当段落显示。
 * 结构（哪行是章节、哪行是条目）由后端 markdown 解析器判定，前端不参与，
 * 也就不存在"预览一套、落库一套"的两份真相。</p>
 */
interface Props {
  resumeId: number
  /** 当前 PDF 原位改动，保存时原样回传：md 视图不认识板块序号，不传就会被后端当成"清空" */
  edits?: Record<string, string>
  /** 返回原版排版 */
  onClose: () => void
  /** 保存成功后父组件决定后续动作（一般为跳去批注页等重解析） */
  onSaved?: (message: string) => void
}

const MAX_CHARS = 200_000

export default function MarkdownResumeEditor({ resumeId, edits, onClose, onSaved }: Props) {
  const draftKey = `panel-md-edit:v1:resume-${resumeId}`

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [value, setValue] = useState('')
  /** 打开时服务器上的版本，用来判断"有没有改过"和"本机草稿是不是过期" */
  const [baseline, setBaseline] = useState('')
  const [hasDraft, setHasDraft] = useState(false)

  const areaRef = useRef<HTMLTextAreaElement | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    // 先要用户上次亲手写的那份 md；没有（老记录 / 一直用原版排版）才用解析结果渲染一份开头
    resumeApi
      .markdownSource(resumeId)
      .catch(() => null)
      .then(source => {
        if (source != null) {
          return source
        }
        return resumeApi.analysis(resumeId).then(view => view.markdown ?? '')
      })
      .then(serverText => {
        if (cancelled) {
          return
        }
        setBaseline(serverText)
        const raw = window.localStorage.getItem(draftKey)
        if (raw != null && raw.trim() && raw !== serverText) {
          // 本机还有没保存的改动：直接续上，并让用户看得见这是草稿
          setValue(raw)
          setHasDraft(true)
        } else {
          setValue(serverText)
          setHasDraft(false)
        }
      })
      .catch(err => {
        if (!cancelled) {
          setError(
            err instanceof Error
              ? `${err.message}（这份简历可能还在解析，等解析完成后再编辑 Markdown）`
              : '读取简历内容失败'
          )
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [draftKey, resumeId])

  const persistDraft = useCallback(
    (next: string) => {
      setValue(next)
      try {
        if (next === baseline) {
          window.localStorage.removeItem(draftKey)
          setHasDraft(false)
        } else {
          window.localStorage.setItem(draftKey, next)
          setHasDraft(true)
        }
      } catch {
        // 存不了就只是这次生效，不影响编辑
      }
    },
    [baseline, draftKey]
  )

  const changed = value !== baseline
  const charCount = value.length
  const tooLong = charCount > MAX_CHARS

  const save = useCallback(async () => {
    if (saving) {
      return
    }
    const text = value.trim()
    if (!text) {
      setNotice('内容还是空的，先在左边写点什么')
      return
    }
    if (text.length > MAX_CHARS) {
      setNotice(`内容太长了（${text.length} 字），上限 ${MAX_CHARS} 字`)
      return
    }
    setSaving(true)
    setNotice('正在保存…')
    try {
      // edits 为 undefined 时后端保留原有原位改动；传 {} 会被当成"清空"，别踩
      await resumeApi.saveContent(resumeId, text, undefined, edits, 'markdown')
      window.localStorage.removeItem(draftKey)
      setHasDraft(false)
      // 正文是 trim 过的，value 不跟着对齐的话保存后还会显示"已修改"
      setValue(text)
      setBaseline(text)
      setSaving(false)
      setNotice('已保存，正在重新解析…')
      onSaved?.('已保存，正在重新解析这份简历…')
    } catch (err) {
      setSaving(false)
      setNotice(err instanceof Error ? err.message : '保存失败，请重试')
    }
  }, [draftKey, edits, onSaved, resumeId, saving, value])

  /** Tab 缩进：md 的嵌套列表和代码块都靠空格，不清掉用户按的 Tab */
  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      const area = areaRef.current
      if (!area) {
        return
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault()
        void save()
        return
      }
      if (event.key === 'Tab') {
        event.preventDefault()
        const start = area.selectionStart
        const end = area.selectionEnd
        const next = `${value.slice(0, start)}  ${value.slice(end)}`
        persistDraft(next)
        window.requestAnimationFrame(() => {
          area.selectionStart = start + 2
          area.selectionEnd = start + 2
        })
      }
    },
    [persistDraft, save, value]
  )

  const preview = useMemo(() => renderResumeMarkdown(value), [value])

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-slate-100 text-sm text-slate-400">
        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
        正在读取 Markdown 内容…
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-4 bg-slate-100">
        <p className="max-w-md text-center text-sm text-slate-600">{error}</p>
        <button type="button" onClick={onClose} className="btn-ghost border border-slate-200 text-slate-600">
          <ArrowLeft className="h-4 w-4" />
          回到原版排版
        </button>
      </div>
    )
  }

  return (
    <div className="flex h-screen flex-col bg-slate-100">
      <header className="flex h-14 shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-5">
        <button
          type="button"
          onClick={onClose}
          className="flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          原版排版
        </button>
        <span className="flex items-center gap-1.5 text-sm font-medium text-slate-800">
          <FileCode2 className="h-4 w-4 text-slate-400" />
          Markdown 内容稿
        </span>
        <span className="hidden rounded-md bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-500 sm:inline">
          # 标题 · - 列表 · 随便写
        </span>
        {hasDraft && (
          <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-600">本机草稿</span>
        )}
        {changed && !hasDraft && (
          <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-600">已修改</span>
        )}

        <div className="ml-auto flex items-center gap-2">
          <span className={`text-[11px] ${tooLong ? 'text-rose-600' : 'text-slate-400'}`}>
            {charCount.toLocaleString()} / {MAX_CHARS.toLocaleString()} 字
          </span>
          {hasDraft && (
            <button
              type="button"
              onClick={() => {
                persistDraft(baseline)
                setNotice('已丢弃本机草稿，回到上次保存的内容')
              }}
              className="btn-ghost border border-slate-200 text-slate-500"
              title="丢掉本机草稿，回到服务器上的版本"
            >
              <Eraser className="h-4 w-4" />
              丢弃草稿
            </button>
          )}
          <button
            type="button"
            onClick={() => void save()}
            disabled={saving || !changed}
            className="btn-primary disabled:opacity-60"
          >
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            {saving ? '保存中…' : '保存并重新分析'}
          </button>
        </div>
      </header>

      {notice && (
        <div className="absolute left-1/2 top-16 z-30 flex -translate-x-1/2 items-center gap-1.5 rounded-lg bg-white px-3 py-1.5 text-xs text-slate-600 shadow-card">
          {notice.startsWith('已保存') && <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />}
          {notice}
        </div>
      )}

      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-2">
        <textarea
          ref={areaRef}
          value={value}
          onChange={event => persistDraft(event.target.value)}
          onKeyDown={handleKeyDown}
          spellCheck={false}
          placeholder={'# 你的姓名\n\n## 项目经历\n\n### 智能面试平台 | 2024.03 – 至今\n- 做了什么，结果如何\n- 再写一条'}
          className="h-full min-h-[50vh] w-full resize-none border-0 bg-white px-6 py-5 font-mono text-[13px] leading-[1.8] text-slate-800 outline-none placeholder:text-slate-300"
        />
        <div className="h-full overflow-y-auto border-l border-slate-200 bg-slate-50 px-6 py-5">
          <div className="mx-auto max-w-[760px] rounded-lg bg-white px-8 py-7 shadow-card">
            {value.trim() ? (
              preview
            ) : (
              <p className="text-[13px] text-slate-400">左边写下内容，这里实时预览。</p>
            )}
          </div>
          <p className="mx-auto mt-3 max-w-[760px] text-[11px] leading-5 text-slate-400">
            预览只影响你看到的排版。保存后由解析器按 Markdown 规则切章节（# 章节 / ### 条目 / - 要点），
            批注和面试提问都基于这份内容。
          </p>
        </div>
      </div>
    </div>
  )
}
