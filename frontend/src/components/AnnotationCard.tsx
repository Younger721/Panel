import type { Level, PointView } from '@/types/api'

export const LEVEL_META: Record<Level, { label: string; badge: string; bar: string }> = {
  STRONG: { label: '写得好', badge: 'bg-emerald-50 text-emerald-700 border-emerald-200', bar: 'border-l-emerald-500' },
  MEDIUM: { label: '一般', badge: 'bg-amber-50 text-amber-700 border-amber-200', bar: 'border-l-amber-500' },
  WEAK: { label: '偏弱', badge: 'bg-rose-50 text-rose-700 border-rose-200', bar: 'border-l-rose-500' }
}

interface Props {
  point: PointView
  moduleTitle: string
  index: number
  hovered: boolean
  onHover: (id: string | null) => void
  register: (pointId: string, el: HTMLElement | null) => void
  onFocusPoint: (pointId: string) => void
}

export function AnnotationCard({ point, moduleTitle, index, hovered, onHover, register, onFocusPoint }: Props) {
  const meta = LEVEL_META[point.level]
  return (
    <article
      ref={el => register(point.id, el)}
      onMouseEnter={() => onHover(point.id)}
      onMouseLeave={() => onHover(null)}
      onClick={() => onFocusPoint(point.id)}
      className={`card cursor-pointer border-l-4 ${meta.bar} ${hovered ? 'is-hovered' : ''}`}
    >
      <header className="flex items-start gap-2">
        <span className="mt-0.5 shrink-0 text-[11px] font-mono text-slate-400">{String(index).padStart(2, '0')}</span>
        <h3 className="flex-1 text-[13px] font-semibold leading-5 text-slate-900">{point.title}</h3>
        <span className={`shrink-0 rounded border px-1.5 py-0.5 text-[10px] font-medium ${meta.badge}`}>
          {meta.label}
        </span>
      </header>

      <p className="mt-1.5 text-[10.5px] uppercase tracking-wide text-slate-400">{moduleTitle}</p>

      {point.quote && (
        <p className="mt-2 border-l-2 border-slate-200 pl-2 text-[11.5px] leading-5 text-slate-500">{point.quote}</p>
      )}

      <p className="mt-2 text-[12px] leading-[1.65] text-slate-700">{point.comment}</p>

      {point.improvements.length > 0 && (
        <div className="mt-2.5">
          <p className="text-[10.5px] font-medium uppercase tracking-wide text-slate-400">怎么改</p>
          <ul className="mt-1 space-y-1">
            {point.improvements.map((item, i) => (
              <li key={i} className="flex gap-1.5 text-[11.5px] leading-[1.6] text-slate-600">
                <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-slate-300" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {point.questions.length > 0 && (
        <details className="group mt-2.5">
          <summary className="cursor-pointer list-none text-[10.5px] font-medium uppercase tracking-wide text-slate-400 hover:text-slate-600">
            模拟追问（{point.questions.length}）
          </summary>
          <ol className="mt-1.5 space-y-1.5">
            {point.questions.map((question, i) => (
              <li key={i} className="flex gap-1.5 text-[11.5px] leading-[1.6] text-slate-600">
                <span className="font-mono text-[10px] text-slate-400">Q{i + 1}</span>
                <span>{question}</span>
              </li>
            ))}
          </ol>
        </details>
      )}
    </article>
  )
}
