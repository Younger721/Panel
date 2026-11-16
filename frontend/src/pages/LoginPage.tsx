import { useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import {
  ArrowRight,
  CircleExclamationPoint,
  Eye,
  EyeOff,
  LoaderCircle,
  Lock,
  ShieldCheck,
  TriangleExclamationPoint,
  User
} from 'lucide-react'
import { authApi } from '@/api/auth'
import { ApiError, setToken } from '@/api/client'
import { AuthFrame } from '@/components/AuthFrame'

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation() as { state?: { from?: string } }

  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [capsOn, setCapsOn] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const usernameRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (loading) return
    if (!username.trim()) {
      setError('请输入用户名')
      usernameRef.current?.focus()
      return
    }
    if (!password) {
      setError('请输入密码')
      passwordRef.current?.focus()
      return
    }
    setLoading(true)
    setError(null)
    try {
      const result = await authApi.login(username.trim(), password)
      setToken(result.accessToken)
      navigate(location.state?.from || '/', { replace: true })
    } catch (err) {
      // 公共 client 把 401 统一叫「登录已过期」，在登录页要改成用户能理解的提示
      setError(
        err instanceof ApiError && err.code === 'UNAUTHORIZED'
          ? '用户名或密码不正确'
          : err instanceof Error
            ? err.message
            : '登录失败'
      )
    } finally {
      setLoading(false)
    }
  }

  const inputClass =
    'h-12 w-full rounded-2xl border border-slate-200 bg-white text-sm text-slate-900 shadow-[0_1px_2px_rgba(15,23,42,0.04)] outline-none transition-[border-color,box-shadow,background-color] duration-150 placeholder:text-slate-400 hover:border-slate-300 focus:border-primary-400 focus:ring-4 focus:ring-primary-100/80'

  return (
    <AuthFrame>
      <h1 className="sr-only">Panel 登录</h1>

      <form onSubmit={submit} className="space-y-4" noValidate>
        <label className="group block">
          <span className="mb-1.5 block text-[12px] font-medium text-slate-600">用户名</span>
          <div className="relative">
            <User className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400 transition-colors group-focus-within:text-primary-500" />
            <input
              ref={usernameRef}
              value={username}
              onChange={event => {
                setUsername(event.target.value)
                if (error) setError(null)
              }}
              className={`${inputClass} pl-10 pr-3`}
              autoComplete="username"
              placeholder="请输入用户名"
              aria-label="用户名"
            />
          </div>
        </label>

        <label className="group block">
          <span className="mb-1.5 flex items-center justify-between text-[12px] font-medium text-slate-600">
            密码
            {capsOn && (
              <span className="inline-flex items-center gap-1 text-[11px] font-normal text-amber-600">
                <TriangleExclamationPoint className="h-3 w-3" />
                大写锁定已开启
              </span>
            )}
          </span>
          <div className="relative">
            <Lock className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400 transition-colors group-focus-within:text-primary-500" />
            <input
              ref={passwordRef}
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={event => {
                setPassword(event.target.value)
                if (error) setError(null)
              }}
              onKeyUp={event => setCapsOn(event.getModifierState?.('CapsLock') ?? false)}
              onBlur={() => setCapsOn(false)}
              className={`${inputClass} pl-10 pr-11`}
              autoComplete="current-password"
              placeholder="请输入密码"
              aria-label="密码"
              aria-invalid={Boolean(error)}
              autoFocus
            />
            <button
              type="button"
              onClick={() => setShowPassword(value => !value)}
              aria-label={showPassword ? '隐藏密码' : '显示密码'}
              aria-pressed={showPassword}
              className="absolute right-2 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600"
            >
              {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </label>

        {error && (
          <div
            key={error}
            role="alert"
            className="flex animate-shake items-start gap-2.5 rounded-xl border border-rose-100 bg-rose-50/80 px-3.5 py-2.5 text-[12px] leading-relaxed text-rose-600"
          >
            <CircleExclamationPoint className="mt-px h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        <button
          type="submit"
          disabled={loading}
          className="group relative flex h-12 w-full items-center justify-center gap-2 overflow-hidden rounded-2xl bg-gradient-to-r from-primary-600 via-primary-500 to-violet-500 text-sm font-semibold text-white shadow-[0_14px_32px_-14px_rgba(79,70,229,0.8)] transition-all duration-200 hover:shadow-[0_20px_42px_-16px_rgba(79,70,229,0.9)] hover:brightness-[1.04] active:scale-[0.99] disabled:cursor-not-allowed disabled:opacity-70 disabled:shadow-none"
        >
          <span className="pointer-events-none absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/25 to-transparent transition-transform duration-700 ease-out group-hover:translate-x-full" />
          {loading ? (
            <>
              <LoaderCircle className="h-4 w-4 animate-spin" />
              正在登录…
            </>
          ) : (
            <>
              登录
              <ArrowRight className="h-4 w-4 transition-transform duration-200 group-hover:translate-x-0.5" />
            </>
          )}
        </button>
      </form>

      <div className="mt-6 flex items-center justify-center gap-1 text-[12px] text-slate-500">
        <span>还没有账号？</span>
        <Link
          to="/register"
          className="font-medium text-primary-600 underline-offset-4 transition-colors hover:text-primary-700 hover:underline"
        >
          注册一个工作区
        </Link>
      </div>

      <div className="mt-3 flex items-center justify-center gap-1.5 text-[11px] text-slate-400">
        <ShieldCheck className="h-3.5 w-3.5" />
        也可以由管理员统一开通后直接登录
      </div>
    </AuthFrame>
  )
}
