import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { resumeApi } from '@/api/resume'
import type { AnalysisView, Level, PointView, ResumeView } from '@/types/api'
import { AnnotationCard } from '@/components/AnnotationCard'
import { ResumePaper, type MarkAnchor } from '@/components/ResumePaper'

interface FlatPoint {
  point: PointView
  moduleTitle: string
}

interface Connector {
  id: string
  level: Level
  d: string
}

const STROKE: Record<Level, string> = {
  STRONG: '#10b981',
  MEDIUM: '#f59e0b',
  WEAK: '#f43f5e'
}

export default function ResumePage() {
  const params = useParams()
  const navigate = useNavigate()
  const resumeId = Number(params.id)

  const [analysis, setAnalysis] = useState<AnalysisView | null>(null)
  const [resume, setResume] = useState<ResumeView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [hovered, setHovered] = useState<string | null>(null)
  const [onlyWeak, setOnlyWeak] = useState(false)
  const [connectors, setConnectors] = useState<Connector[]>([])

  const containerRef = useRef<HTMLDivElement | null>(null)
  const paperRef = useRef<HTMLDivElement | null>(null)
  const markRefs = useRef(new Map<string, HTMLElement>())
  const cardRefs = useRef(new Map<string, HTMLElement>())

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    Promise.all([resumeApi.analysis(resumeId), resumeApi.find(resumeId).catch(() => null)])
      .then(([view, meta]) => {
        if (cancelled) {
          return
        }
        setAnalysis(view)
        setResume(meta)
        setError(null)
      })
      .catch(err => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '加载失败')
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
  }, [resumeId])

  const visiblePoints = useMemo<FlatPoint[]>(() => {
    if (!analysis) {
      return []
    }
    const flat: FlatPoint[] = []
    for (const module of analysis.modules) {
      for (const point of module.points) {
        if (onlyWeak && point.level !== 'WEAK') {
          continue
        }
        flat.push({ point, moduleTitle: module.title })
      }
    }
    return flat
  }, [analysis, onlyWeak])

  const anchors = useMemo(() => {
    const map = new Map<string, MarkAnchor[]>()
    for (const { point } of visiblePoints) {
      const anchor = point.anchor
      if (!anchor || !anchor.blockId) {
        continue
      }
      const list = map.get(anchor.blockId) || []
      list.push({ pointId: point.id, start: anchor.start, end: anchor.end, level: point.level })
      map.set(anchor.blockId, list)
    }
    return map
  }, [visiblePoints])

  const registerMark = useCallback((pointId: string, el: HTMLElement | null) => {
    if (el) {
      markRefs.current.set(pointId, el)
    } else {
      markRefs.current.delete(pointId)
    }
  }, [])

  const registerCard = useCallback((pointId: string, el: HTMLElement | null) => {
    if (el) {
      cardRefs.current.set(pointId, el)
    } else {
      cardRefs.current.delete(pointId)
    }
  }, [])

  const measure = useCallback(() => {
    const container = containerRef.current
    if (!container) {
      return
    }
    const base = container.getBoundingClientRect()
    const paperRight = paperRef.current ? paperRef.current.getBoundingClientRect().right : base.left
    const next: Connector[] = []
    let index = 0
    for (const { point } of visiblePoints) {
      const mark = markRefs.current.get(point.id)
      const card = cardRefs.current.get(point.id)
      if (!mark || !card) {
        continue
      }
      const markRect = mark.getBoundingClientRect()
      const cardRect = card.getBoundingClientRect()
      if (markRect.width === 0 || cardRect.width === 0) {
        continue
      }
      // 从纸张右边缘引线，不横穿正文；与 mark 的对应关系由纵向位置 + 颜色表达
      const startX = paperRight - base.left + 2
      const markY = markRect.top + markRect.height / 2 - base.top
      const cardLeft = cardRect.left - base.left
      const cardY = cardRect.top + 24 - base.top
      const railX = Math.min(startX + 14 + (index % 5) * 4, cardLeft - 28)
      if (cardLeft <= railX) {
        continue
      }
      index += 1
      next.push({
        id: point.id,
        level: point.level,
        d: [
          `M${startX.toFixed(1)},${markY.toFixed(1)}`,
          `L${(railX - 8).toFixed(1)},${markY.toFixed(1)}`,
          `Q${railX.toFixed(1)},${markY.toFixed(1)} ${railX.toFixed(1)},${(markY + 8).toFixed(1)}`,
          `L${railX.toFixed(1)},${(cardY - 8).toFixed(1)}`,
          `Q${railX.toFixed(1)},${cardY.toFixed(1)} ${(railX + 8).toFixed(1)},${cardY.toFixed(1)}`,
          `L${cardLeft.toFixed(1)},${cardY.toFixed(1)}`
        ].join(' ')
      })
    }
    setConnectors(prev =>
      prev.length === next.length && prev.every((item, index) => item.d === next[index].d && item.id === next[index].id)
        ? prev
        : next
    )
  }, [visiblePoints])

  useLayoutEffect(() => {
    const frame = requestAnimationFrame(measure)
    const observer = new ResizeObserver(() => measure())
    if (containerRef.current) {
      observer.observe(containerRef.current)
    }
    const onResize = () => measure()
    window.addEventListener('resize', onResize)
    if (document.fonts) {
      document.fonts.ready.then(() => measure()).catch(() => undefined)
    }
    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
      window.removeEventListener('resize', onResize)
    }
  }, [measure])

  const focusPoint = useCallback((pointId: string) => {
    const mark = markRefs.current.get(pointId)
    if (mark) {
      mark.scrollIntoView({ behavior: 'smooth', block: 'center' })
      setHovered(pointId)
      window.setTimeout(() => setHovered(current => (current === pointId ? null : current)), 1600)
    }
  }, [])

  if (loading) {
    return (
      <div className="flex min-h-full items-center justify-center">
        <p className="text-sm text-slate-400">正在加载批注…</p>
      </div>
    )
  }

  if (error || !analysis) {
    return (
      <div className="mx-auto max-w-lg px-6 py-24 text-center">
        <p className="text-sm text-slate-500">{error || '还没有分析结果'}</p>
        <Link to="/" className="mt-4 inline-block text-sm font-medium text-slate-900 underline">
          返回列表
        </Link>
      </div>
    )
  }

  const stats = analysis.stats

  return (
    <div className="min-h-full pb-16">
      <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/90 backdrop-blur">
        <div className="mx-auto flex max-w-[1280px] flex-wrap items-center gap-x-4 gap-y-2 px-6 py-3">
          <Link to="/" className="text-xs text-slate-400 hover:text-slate-600">
            ← 列表
          </Link>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-slate-900">{resume ? resume.fileName : `简历 #${resumeId}`}</p>
            <p className="text-[11px] text-slate-400">
              {stats.modules} 个模块 · {stats.points} 个小点 · 强 {stats.strong} / 中 {stats.medium} / 弱 {stats.weak}
            </p>
          </div>
          <label className="flex cursor-pointer select-none items-center gap-1.5 text-xs text-slate-600">
            <input
              type="checkbox"
              checked={onlyWeak}
              onChange={event => setOnlyWeak(event.target.checked)}
              className="h-3.5 w-3.5 rounded border-slate-300"
            />
            只看弱项
          </label>
          <button
            onClick={() => navigate(`/interview/new?resumeId=${resumeId}`)}
            className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-slate-800"
          >
            模拟面试
          </button>
        </div>
      </header>

      <div ref={containerRef} className="relative mx-auto grid max-w-[1280px] grid-cols-1 gap-6 px-6 pt-6 min-[1100px]:grid-cols-[minmax(0,1fr)_360px]">
        <svg className="pointer-events-none absolute inset-0 hidden h-full w-full min-[1100px]:block" aria-hidden>
          {connectors.map(connector => {
            const [start] = connector.d.split(' ')
            const [x, y] = start.slice(1).split(',')
            return (
              <g key={connector.id}>
                <circle
                  cx={x}
                  cy={y}
                  r={hovered === connector.id ? 3.2 : 2.2}
                  fill={STROKE[connector.level]}
                  opacity={hovered === null ? 0.75 : hovered === connector.id ? 1 : 0.15}
                />
                <path
                  d={connector.d}
                  fill="none"
                  stroke={STROKE[connector.level]}
                  strokeWidth={hovered === connector.id ? 1.8 : 1}
                  strokeDasharray={hovered === connector.id ? undefined : '3 3'}
                  opacity={hovered === null ? 0.55 : hovered === connector.id ? 1 : 0.12}
                  className="transition-opacity duration-150"
                />
              </g>
            )
          })}
        </svg>

        <div ref={paperRef} className="min-w-0">
          <ResumePaper
            blocks={analysis.blocks}
            anchors={anchors}
            hoveredId={hovered}
            onHover={setHovered}
            registerMark={registerMark}
          />
        </div>

        <aside className="flex flex-col gap-3">
          {visiblePoints.length === 0 && (
            <p className="rounded-xl border border-dashed border-slate-300 p-6 text-center text-xs text-slate-400">
              {onlyWeak ? '没有弱项批注' : '这份简历还没有批注'}
            </p>
          )}
          {visiblePoints.map((item, index) => (
            <AnnotationCard
              key={item.point.id}
              point={item.point}
              moduleTitle={item.moduleTitle}
              index={index + 1}
              hovered={hovered === item.point.id}
              onHover={setHovered}
              register={registerCard}
              onFocusPoint={focusPoint}
            />
          ))}
        </aside>
      </div>
    </div>
  )
}
