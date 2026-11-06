import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { interviewApi } from '@/api/interview'
import type { InterviewSessionSummary } from '@/types/api'
import { formatDate, scoreLabel, scoreTone } from '@/utils/format'

const STATUS_LABEL: Record<InterviewSessionSummary['status'], string> = {
  PLANNED: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  FAILED: '异常'
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
    <div className="mx-auto max-w-4xl px-6 py-12">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-slate-900">面试记录</h1>
          <p className="mt-1 text-sm text-slate-500">每一场都保存了逐题评分与证据。</p>
        </div>
        <Link to="/" className="text-xs text-slate-400 hover:text-slate-600">
          ← 简历列表
        </Link>
      </div>

      {loading ? (
        <p className="mt-8 text-sm text-slate-400">加载中…</p>
      ) : error ? (
        <p className="mt-8 text-sm text-rose-600">{error}</p>
      ) : items.length === 0 ? (
        <p className="mt-8 text-sm text-slate-400">还没有面试记录，先去简历批注页点「模拟面试」。</p>
      ) : (
        <ul className="mt-6 divide-y divide-slate-200 overflow-hidden rounded-xl border border-slate-200 bg-white">
          {items.map(item => {
            const done = item.status === 'COMPLETED'
            return (
              <li
                key={item.id}
                onClick={() => navigate(done ? `/interview/${item.id}/report` : `/interview/${item.id}`)}
                className="flex cursor-pointer items-center gap-4 px-4 py-3.5 transition hover:bg-slate-50"
              >
                <div className="w-14 shrink-0 text-center">
                  <p className={`font-mono text-lg font-semibold ${scoreTone(item.overallScore)}`}>
                    {item.overallScore ?? '—'}
                  </p>
                  <p className="text-[10px] text-slate-400">{scoreLabel(item.overallScore)}</p>
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm text-slate-800">简历 #{item.resumeId}</p>
                  <p className="mt-0.5 truncate text-[11.5px] text-slate-400">
                    {item.summary || `${item.questionCount} 个主问题 · 追问上限 ${item.followUpLimit}`}
                  </p>
                </div>
                <div className="shrink-0 text-right">
                  <p className="text-[11px] text-slate-500">{STATUS_LABEL[item.status]}</p>
                  <p className="text-[10.5px] text-slate-400">{formatDate(item.createdAt)}</p>
                </div>
                <span className="shrink-0 text-slate-300">›</span>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
