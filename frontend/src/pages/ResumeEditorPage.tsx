import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ArrowLeft,
  CheckCircle2,
  Download,
  FileCode2,
  Loader2,
  Palette,
  RefreshCw,
  RotateCcw,
  Save
} from 'lucide-react'
import { resumeApi } from '@/api/resume'
import { ApiError, requestBlob } from '@/api/client'
import PdfEditSurface, {
  DEFAULT_DIVIDER,
  DIVIDERS_KEY,
  FONT_FAMILIES,
  type DividerStyle,
  type PdfEditSurfaceHandle
} from '@/components/PdfEditCanvas'
import StructuredEditor from '@/components/StructuredEditor'
import MarkdownResumeEditor from '@/components/MarkdownResumeEditor'
import type { TextLayerView } from '@/types/api'

/** 间隔条样式是全局的：所有简历、所有标题共用一份，存在本机 */
const DIVIDER_KEY = 'panel-editor-divider:v1'

/** 两份板块改动是否完全一致（key 集合 + 每个值）。 */
function sameEdits(left: Record<string, string>, right: Record<string, string>): boolean {
  const leftKeys = Object.keys(left)
  if (leftKeys.length !== Object.keys(right).length) {
    return false
  }
  return leftKeys.every(key => left[key] === right[key])
}

function loadDivider(): DividerStyle {
  try {
    const raw = localStorage.getItem(DIVIDER_KEY)
    if (raw) {
      const value = JSON.parse(raw) as Partial<DividerStyle>
      return {
        background: value.background ?? DEFAULT_DIVIDER.background,
        color: value.color ?? DEFAULT_DIVIDER.color,
        fontSize: typeof value.fontSize === 'number' ? value.fontSize : DEFAULT_DIVIDER.fontSize,
        fontFamily: value.fontFamily ?? DEFAULT_DIVIDER.fontFamily
      }
    }
  } catch {
    // 坏配置按默认走
  }
  return DEFAULT_DIVIDER
}

/**
 * 简历编辑页。
 *
 * 两种形态，按文件类型自动切换：
 * - **PDF**：原页渲染图 + PDF 文字层叠成的可编辑图层，看到的就是原文件的样子（{@link PdfEditSurface}）
 * - **DOCX / TXT**：没有可渲染的版面，退回结构化编辑器（富文本模板）
 *
 * 保存会把正文回写成新的原文并触发重新解析，所以「改完 → 批注更新」是闭环的。
 */
export default function ResumeEditorPage() {
  const { resumeId } = useParams()
  const navigate = useNavigate()
  const numericId = resumeId ? Number(resumeId) : null

  const [mode, setMode] = useState<'loading' | 'pdf' | 'text' | 'error'>('loading')
  const [layer, setLayer] = useState<TextLayerView | null>(null)
  const [reason, setReason] = useState<string | null>(null)
  const [detail, setDetail] = useState<string | null>(null)
  const [missingFile, setMissingFile] = useState(false)
  const [fileName, setFileName] = useState('')
  const [edits, setEdits] = useState<Record<string, string>>({})
  /**
   * 本机草稿与服务器版本冲突时，先摆在这里等用户决定。
   *
   * <p>草稿一直是"打开就自动套上"的，两份改动谁新谁旧根本分不出来：
   * 在 A 机器改了没保存，去 B 机器改完保存，回 A 打开时草稿会把 B 的改动
   * 覆盖成旧内容——用户看不到任何提示，再点保存就把 B 的成果抹掉了。</p>
   */
  const [pendingDraft, setPendingDraft] = useState<Record<string, string> | null>(null)
  const serverEditsRef = useRef<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [divider, setDivider] = useState<DividerStyle>(loadDivider)
  const [layoutOpen, setLayoutOpen] = useState(false)
  /**
   * Markdown 内容稿视图。
   *
   * <p>两种编辑方式并存：原版排版改"看到的样子"（定稿/导出 PDF），Markdown 改"文本内容"
   * （复制一条项目经历改一改最快）。后端存的是两个指针，互不覆盖。</p>
   */
  const [mdOpen, setMdOpen] = useState(false)

  const surfaceRef = useRef<PdfEditSurfaceHandle | null>(null)
  const draftKey = numericId ? `panel-pdf-edit:v1:resume-${numericId}` : null

  // 打开时先看有没有 PDF 文字层；没有就走结构化编辑器
  useEffect(() => {
    if (!numericId) {
      setMode('error')
      setError('请从简历库选一份简历再编辑')
      return
    }
    let cancelled = false
    setMode('loading')
    setError(null)
    setDetail(null)
    setMissingFile(false)

    resumeApi
      .find(numericId)
      .then(view => {
        if (!cancelled) {
          setFileName(view.fileName)
        }
      })
      .catch(() => null)

    resumeApi
      .textLayer(numericId)
      .then(view => {
        if (cancelled) {
          return
        }
        if (view.available && view.sections.length > 0) {
          setLayer(view)
          setReason(null)
          setMode('pdf')
          // 服务端存着上次保存的板块改动（换设备也在），本机草稿只在"服务器没变过"时才自动套上
          const serverEdits: Record<string, string> = { ...(view.edits ?? {}) }
          serverEditsRef.current = serverEdits
          const raw = draftKey ? window.localStorage.getItem(draftKey) : null
          let draftEdits: Record<string, string> | null = null
          let draftBase: Record<string, string> | null = null
          if (raw) {
            try {
              const saved = JSON.parse(raw) as {
                edits?: Record<string, string>
                base?: Record<string, string>
              }
              draftEdits = saved.edits ?? null
              draftBase = saved.base ?? null
            } catch {
              // 草稿坏了就用服务端的
            }
          }
          if (!draftEdits || Object.keys(draftEdits).length === 0) {
            setEdits(serverEdits)
          } else if (!draftBase || sameEdits(draftBase, serverEdits)) {
            // 服务器版本和草稿的起点一致：没人在别处改过，直接续编
            setEdits({ ...serverEdits, ...draftEdits })
          } else {
            // 别处保存过：先显示服务器版本，把草稿交回用户决定
            setEdits(serverEdits)
            setPendingDraft(draftEdits)
          }
        } else {
          setReason(view.reason ?? '这份文件没有 PDF 文字层，将使用结构化编辑器')
          setMode('text')
        }
      })
      .catch(err => {
        if (cancelled) {
          return
        }
        // 404 不是"读失败"，是这份简历的原始文件根本不在对象存储里（换过存储 / 清过数据）。
        // 这两种情况的处理动作完全不同：一个是重试，一个是只能重新上传。
        const missing = err instanceof ApiError && err.code === 'RESOURCE_NOT_FOUND'
        setMissingFile(missing)
        setReason(
          missing ? '原始文件已丢失，只能编辑解析出的正文' : '读取原版失败，将使用结构化编辑器'
        )
        setDetail(
          missing
            ? '解析结果还在库里，所以下面能正常改文字；但 PDF 原版版面已经找不回来了。重新上传这份简历可以恢复原版编辑器。'
            : err instanceof Error
              ? err.message
              : null
        )
        setMode('text')
      })

    return () => {
      cancelled = true
    }
  }, [numericId, draftKey])

  const handleEditsChange = useCallback(
    (next: Record<string, string>) => {
      setEdits(next)
      if (!draftKey) {
        return
      }
      if (Object.keys(next).length === 0) {
        window.localStorage.removeItem(draftKey)
      } else {
        // 连"草稿是从哪个服务器版本改出来的"一起存：下次打开才能判断有没有冲突
        window.localStorage.setItem(
          draftKey,
          JSON.stringify({ edits: next, base: serverEditsRef.current, savedAt: Date.now() })
        )
      }
    },
    [draftKey]
  )

  const goToAnnotations = useCallback(
    (message: string) => {
      setNotice(message)
      if (numericId) {
        window.setTimeout(() => navigate(`/resumes/${numericId}`), 600)
      }
    },
    [navigate, numericId]
  )

  const saveAndReanalyze = useCallback(async () => {
    if (!numericId || saving) {
      return
    }
    // getText/getEdits 都放在 try 里面：它们要遍历整个图层拼正文，
    // 抛出来如果落在外面，onClick 上的 void 会把异常吞掉——点保存什么都不会发生。
    setSaving(true)
    setNotice('正在保存…')
    try {
      const text = surfaceRef.current?.getText() ?? ''
      if (!text.trim()) {
        setNotice('简历正文是空的，没有可保存的内容')
        setSaving(false)
        return
      }
      const sectionEdits = surfaceRef.current?.getEdits() ?? {}
      await resumeApi.saveContent(numericId, text, undefined, sectionEdits)
      if (draftKey) {
        window.localStorage.removeItem(draftKey)
      }
      // 存上去的这一版就是新的服务器基线：草稿和冲突提示一起收掉
      serverEditsRef.current = sectionEdits
      setPendingDraft(null)
      goToAnnotations('已保存，正在重新解析这份简历…')
    } catch (err) {
      console.error('[resume-editor] save failed', err)
      setSaving(false)
      setNotice(err instanceof Error ? err.message : '保存失败，请重试')
    }
  }, [draftKey, goToAnnotations, numericId, saving])

  const reanalyze = useCallback(async () => {
    if (!numericId || saving) {
      return
    }
    setSaving(true)
    try {
      await resumeApi.reanalyze(numericId)
      goToAnnotations('已提交重新分析…')
    } catch (err) {
      setSaving(false)
      setNotice(err instanceof Error ? err.message : '重新分析失败')
    }
  }, [goToAnnotations, numericId, saving])

  const changedCount = Object.keys(edits).filter(key => key !== DIVIDERS_KEY).length

  const updateDivider = useCallback((patch: Partial<DividerStyle>) => {
    setDivider(current => {
      const next = { ...current, ...patch }
      try {
        localStorage.setItem(DIVIDER_KEY, JSON.stringify(next))
      } catch {
        // 存不了就只是这次生效
      }
      return next
    })
  }, [])
  // 保存后文件名会变成 edited.txt，但这里展示的还是原来那份简历的名字
  const displayName = fileName.replace(/\.[^.]+$/, '')

  /**
   * 导出改完的 PDF。
   *
   * 有未保存的改动先落库：导出是服务端按 `edit_layer` 现算的，不先保存就会导出旧内容。
   * 生成完直接当附件下载，文件名沿用原简历名。
   */
  const exportPdf = useCallback(async () => {
    if (!numericId || saving) {
      return
    }
    setSaving(true)
    setNotice('正在生成 PDF…')
    try {
      if (changedCount > 0) {
        const text = surfaceRef.current?.getText() ?? ''
        if (!text.trim()) {
          setSaving(false)
          setNotice('简历正文是空的，没有可导出的内容')
          return
        }
        const sectionEdits = surfaceRef.current?.getEdits() ?? {}
        await resumeApi.saveContent(numericId, text, undefined, sectionEdits)
        if (draftKey) {
          window.localStorage.removeItem(draftKey)
        }
      }
      const blob = await requestBlob(`/v1/interview/resumes/${numericId}/pdf`)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${displayName}-edited.pdf`
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 2000)
      setSaving(false)
      setNotice('已导出 PDF')
    } catch (err) {
      console.error('[resume-editor] export failed', err)
      setSaving(false)
      setNotice(err instanceof Error ? err.message : '导出失败，请重试')
    }
  }, [changedCount, displayName, draftKey, numericId, saving])

  if (mode === 'error') {
    return (
      <div className="flex h-screen items-center justify-center bg-white">
        <div className="card max-w-sm p-8 text-center">
          <p className="text-sm text-slate-600">{error}</p>
          <Link to="/resumes" className="btn-ghost mt-4">
            返回简历库
          </Link>
        </div>
      </div>
    )
  }

  // Markdown 内容稿：和原版排版并列的第二种编辑方式。放在 text 分支之前，
  // 这样 PDF / DOCX / TXT 三种来源都能进同一个 md 编辑器。
  if (mdOpen && numericId) {
    return (
      <MarkdownResumeEditor
        resumeId={numericId}
        // 只有 PDF 原位视图才有板块改动；别的形态传 undefined，后端就不会去动 edit_layer
        edits={mode === 'pdf' ? edits : undefined}
        onClose={() => setMdOpen(false)}
        onSaved={goToAnnotations}
      />
    )
  }

  if (mode === 'text') {
    return (
      <StructuredEditor
        resumeId={numericId}
        reason={reason}
        detail={detail}
        missing={missingFile}
        onEditMarkdown={() => setMdOpen(true)}
      />
    )
  }

  return (
    <div className="relative flex h-screen flex-col bg-slate-100">
      <header className="flex h-14 shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-5">
        <Link
          to="/resumes"
          className="flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          简历库
        </Link>
        <span className="truncate text-sm font-medium text-slate-800">{displayName || `简历 #${numericId}`}</span>
        <span className="hidden rounded-md bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-500 sm:inline">
          点一块就能整块改
        </span>
        {changedCount > 0 && (
          <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-600">
            已修改 {changedCount} 处
          </span>
        )}

        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            onClick={() => setMdOpen(true)}
            className="btn-ghost border border-slate-200 text-slate-600"
            title="按 Markdown 改内容：不动原版版面，导出 PDF 仍按原版算"
          >
            <FileCode2 className="h-4 w-4" />
            Markdown
          </button>
          <div className="relative">
            <button
              type="button"
              onClick={() => setLayoutOpen(open => !open)}
              className="btn-ghost border border-slate-200 text-slate-600"
              title="间隔条样式（全局）"
            >
              <Palette className="h-4 w-4" />
              版式
            </button>
            {layoutOpen && (
              <>
                <div className="fixed inset-0 z-30" onClick={() => setLayoutOpen(false)} />
                <div className="absolute right-0 top-11 z-40 w-64 rounded-xl border border-slate-200 bg-white p-3 shadow-card">
                  <div className="mb-2 text-[11px] text-slate-400">
                    板块标题的间隔条，全局统一
                  </div>
                  <label className="mb-2 flex items-center justify-between text-xs text-slate-600">
                    底色
                    <input
                      type="color"
                      value={divider.background}
                      onChange={event => updateDivider({ background: event.target.value })}
                      className="h-6 w-10 cursor-pointer rounded border border-slate-200 bg-transparent p-0.5"
                    />
                  </label>
                  <label className="mb-2 flex items-center justify-between text-xs text-slate-600">
                    文字颜色
                    <input
                      type="color"
                      value={divider.color}
                      onChange={event => updateDivider({ color: event.target.value })}
                      className="h-6 w-10 cursor-pointer rounded border border-slate-200 bg-transparent p-0.5"
                    />
                  </label>
                  <label className="mb-2 flex items-center justify-between text-xs text-slate-600">
                    字体
                    <select
                      value={divider.fontFamily}
                      onChange={event => updateDivider({ fontFamily: event.target.value })}
                      className="w-28 rounded-md border border-slate-200 px-1.5 py-0.5 text-xs text-slate-600"
                    >
                      {FONT_FAMILIES.map(item => (
                        <option key={item.label} value={item.value}>
                          {item.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="mb-2 flex items-center justify-between text-xs text-slate-600">
                    字号
                    <select
                      value={divider.fontSize}
                      onChange={event => updateDivider({ fontSize: Number(event.target.value) })}
                      className="rounded-md border border-slate-200 px-1.5 py-0.5 text-xs text-slate-600"
                    >
                      {[11, 12, 13, 14, 15, 16, 18, 20].map(size => (
                        <option key={size} value={size}>
                          {size} pt
                        </option>
                      ))}
                    </select>
                  </label>
                  <div
                    className="mb-2 flex h-7 items-center rounded px-2 text-xs font-semibold"
                    style={{
                      background: divider.background,
                      color: divider.color,
                      fontSize: divider.fontSize,
                      fontFamily: divider.fontFamily
                    }}
                  >
                    实习经历
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      surfaceRef.current?.addDivider()
                      setLayoutOpen(false)
                    }}
                    className="mb-2 w-full rounded-lg border border-indigo-200 bg-indigo-50 py-1 text-[11px] font-medium text-indigo-600 hover:bg-indigo-100"
                  >
                    ＋ 新增标题条
                  </button>
                  <p className="mb-2 text-[10px] leading-4 text-slate-400">
                    新加的条和「教育背景」一样长；拖到想放的位置，双击改标题，鼠标移上去点 × 删除。
                  </p>
                  <button
                    type="button"
                    onClick={() => updateDivider(DEFAULT_DIVIDER)}
                    className="w-full rounded-lg border border-slate-200 py-1 text-[11px] text-slate-500 hover:bg-slate-50"
                  >
                    恢复默认
                  </button>
                </div>
              </>
            )}
          </div>
          {changedCount > 0 && (
            <button
              type="button"
              onClick={() => handleEditsChange({})}
              className="btn-ghost border border-slate-200 text-slate-500"
            >
              <RotateCcw className="h-4 w-4" />
              还原
            </button>
          )}
          {mode === 'pdf' && (
            <button
              type="button"
              onClick={() => void exportPdf()}
              disabled={saving}
              title="导出保留原版版面、已含你修改的 PDF"
              className="btn-ghost border border-slate-200 text-slate-600 disabled:opacity-60"
            >
              <Download className="h-4 w-4" />
              导出 PDF
            </button>
          )}
          <button
            type="button"
            onClick={() => void reanalyze()}
            disabled={saving}
            className="btn-ghost border border-slate-200 text-slate-600 disabled:opacity-60"
          >
            <RefreshCw className={`h-4 w-4 ${saving ? 'animate-spin' : ''}`} />
            重新分析
          </button>
          <button type="button" onClick={() => void saveAndReanalyze()} disabled={saving} className="btn-primary">
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

      {/* 冲突时不要替用户选：选错一次就是一段改动永久消失 */}
      {pendingDraft && (
        <div className="absolute left-1/2 top-16 z-40 w-[min(92vw,520px)] -translate-x-1/2 rounded-xl border border-amber-200 bg-white p-4 shadow-card">
          <p className="text-xs leading-relaxed text-slate-700">
            这份简历在别处保存过，本机还留着一份没保存的改动，两者内容不一样。用哪一份？
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              className="btn-primary px-3 py-1.5 text-xs"
              onClick={() => {
                handleEditsChange({ ...serverEditsRef.current, ...pendingDraft })
                setPendingDraft(null)
                setNotice('已恢复本机草稿')
              }}
            >
              用本机草稿
            </button>
            <button
              type="button"
              className="btn-ghost border border-slate-200 px-3 py-1.5 text-xs text-slate-600"
              onClick={() => {
                if (draftKey) {
                  window.localStorage.removeItem(draftKey)
                }
                setPendingDraft(null)
                setNotice('已使用服务器最新版本')
              }}
            >
              用服务器版本
            </button>
          </div>
        </div>
      )}

      <div className="min-h-0 flex-1">
        {mode === 'loading' || !layer ? (
          <div className="flex h-full items-center justify-center text-sm text-slate-400">
            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            正在渲染原版 PDF…
          </div>
        ) : (
          <PdfEditSurface
            ref={surfaceRef}
            resumeId={numericId as number}
            layer={layer}
            edits={edits}
            onEditsChange={handleEditsChange}
            divider={divider}
          />
        )}
      </div>
    </div>
  )
}
