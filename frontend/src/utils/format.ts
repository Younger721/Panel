export function scoreTone(score: number | null | undefined): string {
  if (score === null || score === undefined) {
    return 'text-slate-400'
  }
  if (score >= 80) {
    return 'text-emerald-600'
  }
  if (score >= 60) {
    return 'text-amber-600'
  }
  return 'text-rose-600'
}

export function scoreBg(score: number | null | undefined): string {
  if (score === null || score === undefined) {
    return 'bg-slate-200'
  }
  if (score >= 80) {
    return 'bg-emerald-500'
  }
  if (score >= 60) {
    return 'bg-amber-500'
  }
  return 'bg-rose-500'
}

export function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function scoreLabel(score: number | null | undefined): string {
  if (score === null || score === undefined) {
    return '未评'
  }
  if (score >= 80) {
    return '良好'
  }
  if (score >= 60) {
    return '及格'
  }
  return '偏弱'
}
