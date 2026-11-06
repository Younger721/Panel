import type { Level, PointView } from '@/types/api'

export const LEVEL_META: Record<Level, { label: string; badge: string; bar: string }> = {
  STRONG: { label: '写得好', badge: 'bg-emerald-50 text-emerald-700 border-emerald-200', bar: 'border-l-emerald-400' },
  MEDIUM: { label: '一般', badge: 'bg-amber-50 text-amber-700 border-amber-200', bar: 'border-l-amber-400' },
  WEAK: { label: '偏弱', badge: 'bg-rose-50 text-rose-700 border-rose-200', bar: 'border-l-rose-400' }
}

interface Props {
  point: PointView
  moduleTitle: string
  index: number
  /** 悬浮或聚焦时才展开全文 */
  active: boolean
  top: number
  /** true：绝对定位贴合原文行（宽屏）；false：常规流式（窄屏） */
  layered?: boolean
  onHover: (id: string | null) => void
  register: (pointId: string, el: HTMLElement | null) => void
  onFocusPoint: (pointId: string) => void
}

export function AnnotationCard({
  point,
  moduleTitle,
  index,
  active,
  top,
  layered = true,
  onHover,
  register,
  onFocusPoint
}: Props) {
  const meta = LEVEL_META[point.level]
  const expanded = active || !layered
  return (
    <article
      ref={el => register(point.id, el)}
      style={layered ? { top } : undefined}
      onMouseEnter={() => onHover(point.id)}
      onMouseLeave={() => onHover(null)}
      onClick={() => onFocusPoint(point.id)}
      className={`cursor-pointer transition-shadow duration-200 ${
        layered ? 'absolute inset-x-0' : 'relative'
      } ${active && layered ? 'z-40' : 'z-10'}`}
    >
      <div
        className={`overflow-hidden rounded-xl border bg-white transition-all duration-200 ${
          expanded
            ? 'max-h-[480px] overflow-y-auto border-primary-200 shadow-card-hover ring-2 ring-primary-100'
            : 'max-h-[84px] border-slate-200/70 shadow-sm hover:border-primary-200 hover:shadow-card'
        }`}
      >
        <div className={`border-l-[3px] ${meta.bar} px-3 py-2`}>
          <header className="flex items-start gap-1.5">
            <span className="mt-[3px] shrink-0 font-mono text-[10px] text-slate-300">
              {String(index).padStart(2, '0')}
            </span>
            <h3 className="min-w-0 flex-1 text-[12.5px] font-semibold leading-5 text-slate-900">{point.title}</h3>
            <span className={`shrink-0 rounded border px-1.5 py-0.5 text-[10px] font-medium ${meta.badge}`}>
              {meta.label}
            </span>
          </header>

          {expanded ? (
            <div className="space-y-2.5 pb-1.5 pl-5">
              <p className="text-[10px] uppercase tracking-wide text-slate-300">{moduleTitle}</p>

              {point.quote && (
                <p className="border-l-2 border-slate-200 pl-2 text-[11.5px] leading-5 text-slate-500">{point.quote}</p>
              )}

              <p className="text-[12px] leading-[1.7] text-slate-700">{point.comment}</p>

              {point.improvements.length > 0 && (
                <div>
                  <p className="section-label">怎么改</p>
                  <ul className="mt-1 space-y-1">
                    {point.improvements.map((item, i) => (
                      <li key={i} className="flex gap-1.5 text-[11.5px] leading-[1.65] text-slate-600">
                        <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-slate-300" />
                        <span>{item}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {point.questions.length > 0 && (
                <div>
                  <p className="section-label">模拟追问（{point.questions.length}）</p>
                  <ol className="mt-1 space-y-1">
                    {point.questions.map((question, i) => (
                      <li key={i} className="flex gap-1.5 text-[11.5px] leading-[1.65] text-slate-600">
                        <span className="font-mono text-[10px] text-slate-400">Q{i + 1}</span>
                        <span>{question}</span>
                      </li>
                    ))}
                  </ol>
                </div>
              )}
            </div>
          ) : (
            <p className="mt-0.5 truncate pl-5 text-[11px] text-slate-400">{point.quote || point.comment}</p>
          )}
        </div>
      </div>
    </article>
  )
}
