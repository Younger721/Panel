import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ChevronLeft, Eye, Mic, Sparkles } from 'lucide-react'
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
  y: number
  x1: number
  x2: number
}

const STROKE: Record<Level, string> = {
  STRONG: '#10b981',
  MEDIUM: '#f59e0b',
  WEAK: '#f43f5e'
}

/** 卡片之间的最小步进：只露出标题条，要看全文就悬浮展开 */
const MIN_STEP = 46

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
  const [tops, setTops] = useState<Record<string, number>>({})
  const [layerHeight, setLayerHeight] = useState(0)

  const containerRef = useRef<HTMLDivElement | null>(null)
  const paperRef = useRef<HTMLDivElement | null>(null)
  const layerRef = useRef<HTMLDivElement | null>(null)
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

  /**
   * 批注卡按原文的纵向位置摆放：悬浮哪一句，右边就在对应那一行展开。
   * 位置相近的卡片允许重叠（最小步进 46px，只露标题条），避免挤成一片。
   */
  const measure = useCallback(() => {
    const container = containerRef.current
    const paper = paperRef.current
    const layer = layerRef.current
    if (!container || !paper || !layer) {
      return
    }
    const base = container.getBoundingClientRect()
    const paperRect = paper.getBoundingClientRect()
    const layerRect = layer.getBoundingClientRect()
    if (layerRect.width === 0) {
      return
    }

    const items: { id: string; level: Level; markY: number }[] = []
    const lines: Connector[] = []
    for (const { point } of visiblePoints) {
      const mark = markRefs.current.get(point.id)
      if (!mark) {
        continue
      }
      const rect = mark.getBoundingClientRect()
      if (rect.width === 0) {
        continue
      }
      const centerY = rect.top + rect.height / 2
      items.push({ id: point.id, level: point.level, markY: centerY - layerRect.top })
      lines.push({
        id: point.id,
        level: point.level,
        y: centerY - base.top,
        x1: paperRect.right - base.left + 2,
        x2: layerRect.left - base.left
      })
    }

    items.sort((a, b) => a.markY - b.markY)
    const nextTops: Record<string, number> = {}
    let previous = Number.NEGATIVE_INFINITY
    for (const item of items) {
      const top = Math.max(item.markY - 26, previous + MIN_STEP)
      previous = top
      nextTops[item.id] = Math.round(top)
    }

    setTops(current => {
      const keys = Object.keys(nextTops)
      if (keys.length === Object.keys(current).length && keys.every(key => current[key] === nextTops[key])) {
        return current
      }
      return nextTops
    })
    setConnectors(current =>
      current.length === lines.length &&
      current.every(
        (item, index) =>
          item.y === lines[index].y && item.x1 === lines[index].x1 && item.x2 === lines[index].x2 && item.id === lines[index].id
      )
        ? current
        : lines
    )

    setLayerHeight(Math.max(paperRect.bottom - layerRect.top, previous + 160))
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
    }
  }, [])

  if (loading) {
    return <p className="py-24 text-center text-sm text-slate-400">正在加载批注…</p>
  }

  if (error || !analysis) {
    return (
      <div className="card mx-auto mt-16 max-w-lg p-10 text-center">
        <p className="text-sm text-slate-500">{error || '还没有分析结果'}</p>
        <Link to="/resumes" className="btn-ghost mt-5">
          返回简历库
        </Link>
      </div>
    )
  }

  const stats = analysis.stats

  return (
    <div className="-mx-10 -mt-10 pb-16">
      <header className="sticky top-0 z-50 border-b border-slate-100 bg-white px-10 py-4">
        <div className="flex flex-wrap items-center gap-4">
          <Link
            to="/resumes"
            className="flex shrink-0 items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            简历库
          </Link>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-slate-900">
              {resume ? resume.fileName : `简历 #${resumeId}`}
            </p>
            <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px]">
              <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-slate-500">{stats.modules} 个模块</span>
              <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-slate-500">{stats.points} 个小点</span>
              <span className="rounded-md bg-emerald-50 px-1.5 py-0.5 text-emerald-600">强 {stats.strong}</span>
              <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-amber-600">中 {stats.medium}</span>
              <span className="rounded-md bg-rose-50 px-1.5 py-0.5 text-rose-600">弱 {stats.weak}</span>
              <span className="flex items-center gap-1 text-slate-400">
                <Sparkles className="h-3 w-3" />
                {analysis.modelName}
              </span>
            </div>
          </div>
          <button
            onClick={() => setOnlyWeak(value => !value)}
            className={`flex shrink-0 items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition-colors ${
              onlyWeak
                ? 'border-rose-200 bg-rose-50 text-rose-600'
                : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            }`}
          >
            <Eye className="h-3.5 w-3.5" />
            只看弱项
          </button>
          <button onClick={() => navigate(`/interview/new?resumeId=${resumeId}`)} className="btn-primary shrink-0">
            <Mic className="h-4 w-4" />
            模拟面试
          </button>
        </div>
      </header>

      <div ref={containerRef} className="relative mx-auto max-w-[1240px] px-10 pt-6">
        <svg className="pointer-events-none absolute inset-0 hidden h-full w-full xl:block" aria-hidden>
          {connectors.map(connector => (
            <g key={connector.id}>
              <path
                d={`M${connector.x1.toFixed(1)},${connector.y.toFixed(1)} H${connector.x2.toFixed(1)}`}
                fill="none"
                stroke={STROKE[connector.level]}
                strokeWidth={hovered === connector.id ? 2 : 1.5}
                strokeDasharray={hovered === connector.id ? undefined : '2 3'}
                opacity={hovered === null ? 0.5 : hovered === connector.id ? 1 : 0.18}
                className="transition-opacity duration-150"
              />
              <circle
                cx={connector.x1}
                cy={connector.y}
                r={hovered === connector.id ? 3.4 : 2.4}
                fill={STROKE[connector.level]}
                opacity={hovered === null ? 0.8 : hovered === connector.id ? 1 : 0.18}
              />
            </g>
          ))}
        </svg>

        <div className="grid grid-cols-1 gap-x-11 xl:grid-cols-[minmax(0,700px)_340px]">
          <div ref={paperRef} className="min-w-0">
            <ResumePaper
              blocks={analysis.blocks}
              anchors={anchors}
              hoveredId={hovered}
              onHover={setHovered}
              registerMark={registerMark}
            />
          </div>

          <div ref={layerRef} className="relative hidden xl:block" style={{ height: layerHeight }}>
            {visiblePoints.length === 0 && (
              <p className="rounded-xl border border-dashed border-slate-200 bg-white/70 p-6 text-center text-xs text-slate-400">
                {onlyWeak ? '没有弱项批注' : '这份简历还没有批注'}
              </p>
            )}
            {visiblePoints.map((item, index) => (
              <AnnotationCard
                key={item.point.id}
                point={item.point}
                moduleTitle={item.moduleTitle}
                index={index + 1}
                active={hovered === item.point.id}
                top={tops[item.point.id] ?? 0}
                onHover={setHovered}
                register={registerCard}
                onFocusPoint={focusPoint}
              />
            ))}
          </div>
        </div>

        {/* 窄屏：卡片回到常规流式列表，全部展开 */}
        <div className="mt-6 space-y-3 xl:hidden">
          {visiblePoints.map((item, index) => (
            <AnnotationCard
              key={item.point.id}
              point={item.point}
              moduleTitle={item.moduleTitle}
              index={index + 1}
              active
              layered={false}
              top={0}
              onHover={setHovered}
              register={registerCard}
              onFocusPoint={focusPoint}
            />
          ))}
        </div>
      </div>
    </div>
  )
}
