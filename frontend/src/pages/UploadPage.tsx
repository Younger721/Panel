import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertCircle, CheckCircle2, FileText, Loader2, Upload, X } from 'lucide-react'
import { resumeApi } from '@/api/resume'
import type { ResumeStatus, ResumeView } from '@/types/api'

const STATUS_TEXT: Record<ResumeStatus, string> = {
  PENDING: '排队中',
  PARSING: '正在解析简历…',
  ANALYZING: 'AI 正在逐条批注…',
  PARSED: '分析完成',
  FAILED: '解析失败'
}

/** 与后端 ResumeService.MAX_BYTES 对齐的 10MB。 */
const MAX_BYTES = 10 * 1024 * 1024
/**
 * 与后端 ResumeService.normalizeContentType 的白名单保持一致。
 *
 * <p>`.md` 是后端原生支持的简历格式（解析器认标题和列表，比 txt 分得更细），
 * 这里漏掉的话用户会被前端拦在门外，明明后端收。</p>
 */
const ALLOWED_SUFFIXES = ['.pdf', '.docx', '.md', '.txt']

/** 本机预检：返回不能上传的原因，能传就返回 null。 */
function rejectReason(file: File): string | null {
  const name = file.name.toLowerCase()
  if (!ALLOWED_SUFFIXES.some(suffix => name.endsWith(suffix))) {
    return '只支持 PDF、Word（.docx）、Markdown（.md）和纯文本（.txt）简历'
  }
  if (file.size === 0) {
    return '这个文件是空的'
  }
  if (file.size > MAX_BYTES) {
    return `文件 ${(file.size / 1024 / 1024).toFixed(1)} MB，超过 10 MB 上限`
  }
  return null
}

export default function UploadPage() {
  const navigate = useNavigate()
  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [active, setActive] = useState<ResumeView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)
  /** 轮询代次：重新上传/离开页面时作废旧的那一轮，避免旧结果覆盖新的。 */
  const pollTokenRef = useRef(0)

  useEffect(
    () => () => {
      pollTokenRef.current += 1
    },
    []
  )

  /**
   * 轮询解析进度。
   *
   * <p>以前这里有两个坑：catch 里直接 return（一次网络抖动就把进度永久钉住），
   * 以及循环走完 90 次也不告诉用户（页面一直转圈，其实任务早就没在等了）。
   * 现在一次失败继续重试，连续三次才放弃并给出"去简历库看"的出口。</p>
   */
  const poll = useCallback(async (id: number) => {
    const token = ++pollTokenRef.current
    let failures = 0
    for (let i = 0; i < 90; i += 1) {
      await new Promise(resolve => window.setTimeout(resolve, 2000))
      if (pollTokenRef.current !== token) {
        return
      }
      try {
        const view = await resumeApi.find(id)
        if (pollTokenRef.current !== token) {
          return
        }
        failures = 0
        setActive(view)
        if (view.status === 'PARSED' || view.status === 'FAILED') {
          return
        }
      } catch {
        failures += 1
        if (failures >= 3) {
          setError('获取解析进度失败，可以到「简历库」查看结果')
          return
        }
      }
    }
    setError('解析用时超出预期，可以到「简历库」继续查看进度')
  }, [])

  const upload = useCallback(
    async (file: File) => {
      // 先在本机拦一道：后端只收 10MB / pdf,docx,md,txt，不拦的话用户要等整个文件
      // 传完（几十秒）才收到 400，网慢的时候尤其难受。拖拽不受 accept 约束。
      const rejection = rejectReason(file)
      if (rejection) {
        setActive(null)
        setError(rejection)
        return
      }
      setUploading(true)
      setError(null)
      setActive(null)
      try {
        const view = await resumeApi.upload(file)
        setActive(view)
        void poll(view.id)
      } catch (err) {
        setError(err instanceof Error ? err.message : '上传失败')
      } finally {
        setUploading(false)
      }
    },
    [poll]
  )

  const busy =
    uploading ||
    (active !== null && (active.status === 'PENDING' || active.status === 'PARSING' || active.status === 'ANALYZING'))

  return (
    <div className="mx-auto max-w-3xl pt-16">
      <div className="mb-12 text-center">
        <h1 className="mb-4 text-4xl font-bold tracking-tight text-slate-900 md:text-5xl">
          开始您的 AI 模拟面试
        </h1>
        <p className="text-lg text-slate-500">上传 PDF 或 Word 简历，AI 将为您定制专属面试方案</p>
      </div>

      <div className="rounded-2xl bg-gradient-to-r from-indigo-200 via-purple-200 to-indigo-200 p-[2px] shadow-card">
        <div
          onDragOver={event => {
            event.preventDefault()
            setDragging(true)
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={event => {
            event.preventDefault()
            setDragging(false)
            const file = event.dataTransfer.files?.[0]
            if (file) {
              void upload(file)
            }
          }}
          onClick={() => inputRef.current?.click()}
          className={`cursor-pointer rounded-[14px] bg-white p-12 transition-transform duration-300 ${
            dragging ? 'scale-[1.01]' : ''
          }`}
        >
          <div className="text-center">
            <div
              className={`mx-auto mb-5 flex h-20 w-20 items-center justify-center rounded-2xl transition-colors ${
                dragging ? 'bg-primary-100 text-primary-600' : 'bg-slate-100 text-slate-400'
              }`}
            >
              <Upload className="h-10 w-10" />
            </div>
            <h3 className="mb-2 text-xl font-semibold text-slate-900">点击或拖拽文件至此处</h3>
            <p className="mb-6 text-slate-400">支持 PDF / DOCX / MD / TXT（最大 10MB）</p>
            <button
              type="button"
              className="btn-primary px-8 py-3.5 text-base"
              onClick={event => {
                event.stopPropagation()
                inputRef.current?.click()
              }}
            >
              选择简历文件
            </button>
            <input
              ref={inputRef}
              type="file"
              className="hidden"
              accept=".pdf,.docx,.md,.txt"
              onChange={event => {
                const file = event.target.files?.[0]
                if (file) {
                  void upload(file)
                }
                event.target.value = ''
              }}
            />
          </div>
        </div>
      </div>

      {error && (
        <div className="mt-6 flex items-center justify-center gap-2 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-600">
          <AlertCircle className="h-5 w-5" />
          {error}
        </div>
      )}

      {active && (
        <div className="mt-6 rounded-2xl border border-slate-100 bg-white p-5 shadow-card">
          <div className="flex items-center gap-4">
            <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-600">
              <FileText className="h-6 w-6" />
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold text-slate-900">{active.fileName}</p>
              <p className="mt-0.5 flex items-center gap-1.5 text-sm text-slate-500">
                {active.status === 'PARSED' ? (
                  <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                ) : active.status === 'FAILED' ? (
                  <AlertCircle className="h-4 w-4 text-rose-500" />
                ) : (
                  <Loader2 className="h-4 w-4 animate-spin text-primary-500" />
                )}
                {STATUS_TEXT[active.status]}
                {active.lastError ? ` · ${active.lastError}` : ''}
              </p>
            </div>
            {active.status === 'PARSED' && (
              <button onClick={() => navigate(`/resumes/${active.id}`)} className="btn-primary shrink-0">
                查看分析
              </button>
            )}
            {!busy && (
              <button
                onClick={() => setActive(null)}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-400 hover:bg-slate-200"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
          {(active.status === 'PARSING' || active.status === 'ANALYZING') && (
            <p className="mt-3 text-xs text-slate-400">
              大约 40–60 秒。解析完会切模块，再逐条批注，期间可以先去「简历库」看进度。
            </p>
          )}
        </div>
      )}
    </div>
  )
}
