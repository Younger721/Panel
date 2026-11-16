import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ChevronRight, Users } from 'lucide-react'
import { interviewApi } from '@/api/interview'
import type { InterviewSessionSummary } from '@/types/api'
import { formatDate, scoreLabel, scoreTone } from '@/utils/format'

const STATUS_LABEL: Record<InterviewSessionSummary['status'], { text: string; className: string }> = {
  PLANNED: { text: '待开始', className: 'bg-slate-100 text-slate-500' },
  IN_PROGRESS: { text: '进行中', className: 'bg-amber-50 text-amber-600' },
  COMPLETED: { text: '已完成', className: 'bg-emerald-50 text-emerald-600' },
  ABANDONED: { text: '已结束', className: 'bg-slate-100 text-slate-500' },
  FAILED: { text: '异常', className: 'bg-rose-50 text-rose-600' }
}

export default function HistoryPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<InterviewSessionSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    interviewApi
      .sessions()
      .then(setItems)
      .catch(err => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-8">
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">面试记录</h1>
        <p className="mt-1 text-sm text-slate-500">每一场都保存了逐题评分与证据，点开可以回看。</p>
      </div>

      {loading ? (
        <p className="text-sm text-slate-400">加载中…</p>
      ) : error ? (
        <p className="text-sm text-rose-600">{error}</p>
      ) : items.length === 0 ? (
        <div className="card flex flex-col items-center justify-center py-20">
          <Users className="h-10 w-10 text-slate-300" />
          <p className="mt-4 text-sm text-slate-500">还没有面试记录</p>
          <p className="mt-1 text-xs text-slate-400">先去简历批注页点「模拟面试」</p>
          <Link to="/resumes" className="btn-primary mt-6">
            去简历库
          </Link>
        </div>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-card">
          {items.map((item, index) => {
            const status = STATUS_LABEL[item.status]
            const done = item.status === 'COMPLETED'
            return (
              <button
                key={item.id}
                onClick={() => navigate(done ? `/interview/${item.id}/report` : `/interview/${item.id}`)}
                className={`flex w-full items-center gap-5 px-6 py-4 text-left transition-colors hover:bg-slate-50 ${
                  index > 0 ? 'border-t border-slate-50' : ''
                }`}
              >
                <div className="flex h-14 w-14 shrink-0 flex-col items-center justify-center rounded-xl bg-slate-50">
                  <span className={`text-lg font-bold ${scoreTone(item.overallScore)}`}>
                    {item.overallScore ?? '—'}
                  </span>
                  <span className="text-[10px] text-slate-400">{scoreLabel(item.overallScore)}</span>
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-semibold text-slate-900">简历 #{item.resumeId}</span>
                    <span className={`rounded-md px-1.5 py-0.5 text-[11px] font-medium ${status.className}`}>
                      {status.text}
                    </span>
                  </div>
                  <p className="mt-1 truncate text-xs text-slate-500">
                    {item.summary || `${item.questionCount} 个主问题 · 每个最多追问 ${item.followUpLimit} 次`}
                  </p>
                </div>
                <div className="shrink-0 text-right">
                  <p className="text-xs text-slate-400">{formatDate(item.createdAt)}</p>
                  <p className="mt-0.5 text-[11px] text-slate-300">
                    {item.questionCount} 题 / 追问 {item.followUpLimit}
                  </p>
                </div>
                <ChevronRight className="h-4 w-4 shrink-0 text-slate-300" />
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
