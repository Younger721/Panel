import { useEffect, useRef } from 'react'
import { Sparkles } from 'lucide-react'

export function BrandMark({ size = 'md' }: { size?: 'sm' | 'md' | 'lg' }) {
  const box =
    size === 'lg'
      ? 'h-16 w-16 rounded-[22px]'
      : size === 'sm'
        ? 'h-9 w-9 rounded-xl'
        : 'h-11 w-11 rounded-[15px]'
  const icon = size === 'lg' ? 'h-8 w-8' : size === 'sm' ? 'h-[18px] w-[18px]' : 'h-5 w-5'
  return (
    <span
      className={[
        'relative flex shrink-0 items-center justify-center overflow-hidden bg-gradient-to-br from-indigo-400 via-primary-500 to-violet-600 text-white shadow-[0_18px_44px_-16px_rgba(99,102,241,0.95)]',
        box
      ].join(' ')}
    >
      <span className="absolute inset-0 bg-[radial-gradient(circle_at_28%_18%,rgba(255,255,255,0.5),transparent_58%)]" />
      <Sparkles className={['relative', icon].join(' ')} strokeWidth={2.2} />
    </span>
  )
}

/**
 * 左侧舞台：一片会跟着鼠标起伏的点阵地形。
 * 纯装饰（aria-hidden），不承载任何交互与焦点。
 * 用 canvas 而不是 DOM 点阵，312 个点每帧写 transform 在低端机上会掉帧。
 */
function TerrainScene() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches

    const COLS = 26
    const ROWS = 14
    const GAP = 23
    const PLANE_W = (COLS - 1) * GAP
    const PLANE_H = (ROWS - 1) * GAP

    type Point = { x: number; y: number }
    const points: Point[] = []
    for (let r = 0; r < ROWS; r += 1) {
      for (let c = 0; c < COLS; c += 1) {
        points.push({ x: c * GAP - PLANE_W / 2, y: r * GAP - PLANE_H / 2 })
      }
    }
    // 远的先画，近的后画
    points.sort((a, b) => a.y - b.y)

    const A = (60 * Math.PI) / 180
    const B = (-26 * Math.PI) / 180
    const cosA = Math.cos(A)
    const sinA = Math.sin(A)
    const cosB = Math.cos(B)
    const sinB = Math.sin(B)
    const PERSP = 1250
    const SIGMA = 132
    const PEAK = 96

    let width = 0
    let height = 0
    let cx = 0
    let cy = 0

    const target = { x: 0, y: 0 }
    const current = { x: 0, y: 0 }
    let lastMove = 0
    let raf = 0
    const start = performance.now()

    function resize() {
      const parent = canvas!.parentElement
      if (!parent) return
      const rect = parent.getBoundingClientRect()
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      width = rect.width
      height = rect.height
      canvas!.width = Math.round(width * dpr)
      canvas!.height = Math.round(height * dpr)
      canvas!.style.width = `${width}px`
      canvas!.style.height = `${height}px`
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0)
      cx = width * 0.5
      cy = height * 0.6
    }

    /** 平面坐标 → 屏幕坐标（近似 CSS 的 rotateX(60) rotateZ(-26) + perspective） */
    function project(x: number, y: number, z: number): [number, number] {
      const x1 = x * cosB - y * sinB
      const y1 = x * sinB + y * cosB
      const y2 = y1 * cosA - z * sinA
      const z2 = y1 * sinA + z * cosA
      const s = PERSP / (PERSP - z2)
      return [x1 * s, y2 * s]
    }

    /** 屏幕位移 → 平面坐标（反投影，迭代两次足够） */
    function unproject(sx: number, sy: number): { x: number; y: number } {
      let y1 = sy / cosA
      for (let i = 0; i < 2; i += 1) {
        const s = PERSP / (PERSP - y1 * sinA)
        y1 = sy / (cosA * s)
      }
      const x1 = sx
      return { x: x1 * cosB + y1 * sinB, y: -x1 * sinB + y1 * cosB }
    }

    function handleMove(event: PointerEvent) {
      const rect = canvas!.getBoundingClientRect()
      const sx = event.clientX - rect.left - cx
      const sy = event.clientY - rect.top - cy
      if (Math.abs(sx) > rect.width * 0.75 || Math.abs(sy) > rect.height * 0.75) return
      const p = unproject(sx, sy)
      target.x = Math.max(-PLANE_W / 2, Math.min(PLANE_W / 2, p.x))
      target.y = Math.max(-PLANE_H / 2, Math.min(PLANE_H / 2, p.y))
      lastMove = performance.now()
    }

    function draw(now: number) {
      const t = (now - start) / 1000
      const idle = now - lastMove > 3200
      if (idle) {
        target.x = Math.cos(t * 0.26) * (PLANE_W * 0.3)
        target.y = Math.sin(t * 0.2) * (PLANE_H * 0.28)
      }
      current.x += (target.x - current.x) * 0.075
      current.y += (target.y - current.y) * 0.075

      ctx!.clearRect(0, 0, width, height)
      ctx!.save()
      ctx!.translate(cx, cy)

      // 地面的一层极淡底色
      const corners: [number, number][] = [
        [-PLANE_W / 2, -PLANE_H / 2],
        [PLANE_W / 2, -PLANE_H / 2],
        [PLANE_W / 2, PLANE_H / 2],
        [-PLANE_W / 2, PLANE_H / 2]
      ].map(([x, y]) => project(x, y, 0)) as [number, number][]
      ctx!.beginPath()
      ctx!.moveTo(corners[0][0], corners[0][1])
      for (let i = 1; i < corners.length; i += 1) ctx!.lineTo(corners[i][0], corners[i][1])
      ctx!.closePath()
      ctx!.fillStyle = 'rgba(99, 102, 241, 0.045)'
      ctx!.fill()
      ctx!.strokeStyle = 'rgba(165, 180, 252, 0.1)'
      ctx!.lineWidth = 1
      ctx!.stroke()

      // 峰顶的柔光
      const peakH = PEAK + 8
      const [px, py] = project(current.x, current.y, peakH)
      const glow = ctx!.createRadialGradient(px, py, 0, px, py, 170)
      glow.addColorStop(0, 'rgba(34, 211, 238, 0.24)')
      glow.addColorStop(0.45, 'rgba(99, 102, 241, 0.13)')
      glow.addColorStop(1, 'rgba(99, 102, 241, 0)')
      ctx!.fillStyle = glow
      ctx!.beginPath()
      ctx!.arc(px, py, 170, 0, Math.PI * 2)
      ctx!.fill()

      for (const p of points) {
        const dx = p.x - current.x
        const dy = p.y - current.y
        const bulge = PEAK * Math.exp(-(dx * dx + dy * dy) / (2 * SIGMA * SIGMA))
        const wave = 5 * Math.sin(p.x * 0.045 + t * 1.25) * Math.cos(p.y * 0.04 - t * 0.85) + 5
        const h = Math.max(0, bulge + wave)
        const [sx, sy] = project(p.x, p.y, h)
        const mix = Math.min(1, h / (PEAK * 0.85))
        const r = Math.round(99 + (34 - 99) * mix)
        const g = Math.round(102 + (211 - 102) * mix)
        const b = Math.round(241 + (238 - 241) * mix)
        const radius = 1.9 + mix * 1.7
        ctx!.beginPath()
        ctx!.arc(sx, sy, radius, 0, Math.PI * 2)
        ctx!.fillStyle = `rgba(${r}, ${g}, ${b}, ${0.34 + mix * 0.58})`
        ctx!.fill()
      }

      ctx!.restore()
    }

    function loop(now: number) {
      draw(now)
      raf = requestAnimationFrame(loop)
    }

    resize()
    const observer = new ResizeObserver(resize)
    if (canvas.parentElement) observer.observe(canvas.parentElement)

    if (reduce) {
      draw(start)
    } else {
      window.addEventListener('pointermove', handleMove, { passive: true })
      raf = requestAnimationFrame(loop)
    }

    const onVisibility = () => {
      if (document.hidden) {
        cancelAnimationFrame(raf)
      } else if (!reduce) {
        raf = requestAnimationFrame(loop)
      }
    }
    document.addEventListener('visibilitychange', onVisibility)

    return () => {
      cancelAnimationFrame(raf)
      observer.disconnect()
      window.removeEventListener('pointermove', handleMove)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [])

  return <canvas ref={canvasRef} aria-hidden className="auth-terrain absolute inset-0" />
}

/**
 * 登录 / 注册共用的外壳：左边点阵舞台，右边白色纸张表单。
 * 两端只负责往中缝里塞表单，布局与舞台动画都收在这里。
 */
export function AuthFrame({
  children,
  formWidth = 'narrow'
}: {
  children: React.ReactNode
  formWidth?: 'narrow' | 'wide'
}) {
  const heroRef = useRef<HTMLElement | null>(null)

  /** 深色舞台跟随鼠标的柔光，只是氛围，不参与布局。 */
  function trackPointer(event: React.MouseEvent<HTMLElement>) {
    const element = heroRef.current
    if (!element) return
    const rect = element.getBoundingClientRect()
    element.style.setProperty('--mx', `${event.clientX - rect.left}px`)
    element.style.setProperty('--my', `${event.clientY - rect.top}px`)
  }

  return (
    <div className="flex min-h-screen w-full bg-white">
      {/* ---------- 左：品牌 + 地形舞台 ---------- */}
      <aside
        ref={heroRef}
        onMouseMove={trackPointer}
        className="auth-hero relative hidden overflow-hidden lg:flex lg:w-[57%] xl:w-[55%]"
      >
        <div className="auth-hero-grid absolute inset-0" />
        <div className="auth-spot absolute inset-0" />
        <div className="auth-noise absolute inset-0" />
        <TerrainScene />

        <div className="relative z-10 flex w-full flex-col px-12 py-11 xl:px-16 xl:py-14">
          <div className="flex animate-rise items-center gap-4">
            <BrandMark size="lg" />
            <span className="text-[40px] font-semibold leading-none tracking-[-0.035em] text-white">
              Panel
            </span>
          </div>
        </div>
      </aside>

      {/* ---------- 右：表单 ---------- */}
      <main className="relative flex w-full flex-1 flex-col bg-white lg:w-[43%] xl:w-[45%]">
        {/* 移动端：深色品牌头 */}
        <div className="auth-hero relative overflow-hidden px-6 pb-10 pt-8 lg:hidden">
          <div className="auth-blob auth-blob-a auth-blob-mobile" />
          <div className="auth-blob auth-blob-b auth-blob-mobile" />
          <div className="auth-noise absolute inset-0" />
          <div className="relative flex animate-rise items-center gap-3">
            <BrandMark size="md" />
            <span className="text-[26px] font-semibold leading-none tracking-[-0.03em] text-white">
              Panel
            </span>
          </div>
        </div>

        {/* 白色纸张上浮，盖住深色头的下沿 */}
        <div className="relative -mt-6 flex flex-1 flex-col rounded-t-[26px] bg-white lg:mt-0 lg:rounded-none">
          <div className="auth-form-glow absolute inset-0 rounded-t-[26px] lg:rounded-none" />

          <div className="relative flex flex-1 items-start justify-center px-6 pb-8 pt-12 sm:px-10 lg:items-center lg:py-12">
            <div className={`w-full animate-rise ${formWidth === 'wide' ? 'max-w-[430px]' : 'max-w-[376px]'}`}>
              {children}
            </div>
          </div>
        </div>

        <div className="relative flex items-center justify-between px-6 pb-6 text-[11px] text-slate-400 sm:px-10">
          <span>Panel</span>
          <span>简历批注 · 模拟面试</span>
        </div>
      </main>
    </div>
  )
}
