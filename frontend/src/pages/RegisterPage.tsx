import { useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  ArrowRight,
  AtSign,
  Building2,
  CircleExclamationPoint,
  Eye,
  EyeOff,
  Hash,
  LoaderCircle,
  Lock,
  ShieldCheck,
  User
} from 'lucide-react'
import { authApi } from '@/api/auth'
import { ApiError, setToken } from '@/api/client'
import { AuthFrame } from '@/components/AuthFrame'

/** 工作区标识：小写字母 / 数字 / 连字符，首尾不能是连字符。 */
const CODE_PATTERN = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const inputClass =
  'h-12 w-full rounded-2xl border border-slate-200 bg-white text-sm text-slate-900 shadow-[0_1px_2px_rgba(15,23,42,0.04)] outline-none transition-[border-color,box-shadow,background-color] duration-150 placeholder:text-slate-400 hover:border-slate-300 focus:border-primary-400 focus:ring-4 focus:ring-primary-100/80'
const iconClass =
  'pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400 transition-colors group-focus-within:text-primary-500'
const labelClass = 'mb-1.5 block text-[12px] font-medium text-slate-600'

export default function RegisterPage() {
  const navigate = useNavigate()

  const [tenantName, setTenantName] = useState('')
  const [tenantCode, setTenantCode] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const nameRef = useRef<HTMLInputElement>(null)

  function clearError() {
    if (error) setError(null)
  }

  /** 前端只做「不填就提交不出去」的拦截，真正的规则以后端校验为准。 */
  function validate(): string | null {
    if (!tenantName.trim()) return '给工作区起个名字'
    if (!tenantCode.trim()) return '填写工作区标识'
    if (!CODE_PATTERN.test(tenantCode.trim())) return '工作区标识只能用小写字母、数字和连字符'
    if (!displayName.trim()) return '填写你的姓名'
    if (!email.trim()) return '填写邮箱'
    if (!EMAIL_PATTERN.test(email.trim())) return '邮箱格式看起来不对'
    if (password.length < 8) return '密码至少 8 位'
    if (password.length > 72) return '密码不要超过 72 位'
    if (password !== confirm) return '两次输入的密码不一致'
    return null
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (loading) return
    const problem = validate()
    if (problem) {
      setError(problem)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const result = await authApi.register({
        tenantCode: tenantCode.trim(),
        tenantName: tenantName.trim(),
        email: email.trim(),
        password,
        displayName: displayName.trim()
      })
      setToken(result.accessToken)
      navigate('/', { replace: true })
    } catch (err) {
      if (err instanceof ApiError && err.code === 'CONFLICT') {
        setError('这个邮箱或工作区标识已经被占用了，换一个再试')
      } else if (err instanceof ApiError && err.code === 'VALIDATION_FAILED') {
        setError('有几项没通过校验，检查一下邮箱和必填项')
      } else {
        setError(err instanceof Error ? err.message : '注册失败，稍后再试')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthFrame formWidth="wide">
      <h1 className="sr-only">注册 Panel 工作区</h1>

      <div className="mb-5">
        <h2 className="text-[19px] font-semibold tracking-[-0.01em] text-slate-900">创建你的工作区</h2>
        <p className="mt-1 text-[12.5px] leading-relaxed text-slate-500">
          注册后你就是这个工作区的管理员，简历、面试记录与知识库彼此隔离，别人看不到。
        </p>
      </div>

      <form onSubmit={submit} className="space-y-3.5" noValidate>
        <label className="group block">
          <span className={labelClass}>工作区名称</span>
          <div className="relative">
            <Building2 className={iconClass} />
            <input
              value={tenantName}
              onChange={event => {
                setTenantName(event.target.value)
                clearError()
              }}
              className={`${inputClass} pl-10 pr-3`}
              placeholder="例如：我的团队 / 个人求职"
              maxLength={128}
              aria-label="工作区名称"
              autoFocus
            />
          </div>
        </label>

        <div className="grid grid-cols-2 gap-3">
          <label className="group block">
            <span className={labelClass}>工作区标识</span>
            <div className="relative">
              <Hash className={iconClass} />
              <input
                value={tenantCode}
                onChange={event => {
                  setTenantCode(event.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ''))
                  clearError()
                }}
                className={`${inputClass} pl-10 pr-3`}
                placeholder="myteam"
                maxLength={64}
                aria-label="工作区标识"
              />
            </div>
          </label>

          <label className="group block">
            <span className={labelClass}>你的姓名</span>
            <div className="relative">
              <User className={iconClass} />
              <input
                ref={nameRef}
                value={displayName}
                onChange={event => {
                  setDisplayName(event.target.value)
                  clearError()
                }}
                className={`${inputClass} pl-10 pr-3`}
                placeholder="张三"
                maxLength={128}
                aria-label="你的姓名"
              />
            </div>
          </label>
        </div>

        <label className="group block">
          <span className={labelClass}>邮箱</span>
          <div className="relative">
            <AtSign className={iconClass} />
            <input
              type="email"
              value={email}
              onChange={event => {
                setEmail(event.target.value)
                clearError()
              }}
              className={`${inputClass} pl-10 pr-3`}
              placeholder="you@example.com"
              autoComplete="email"
              maxLength={320}
              aria-label="邮箱"
            />
          </div>
          <span className="mt-1 block text-[11px] text-slate-400">登录名就是邮箱 @ 前面那一段</span>
        </label>

        <div className="grid grid-cols-2 gap-3">
          <label className="group block">
            <span className={labelClass}>密码</span>
            <div className="relative">
              <Lock className={iconClass} />
              <input
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={event => {
                  setPassword(event.target.value)
                  clearError()
                }}
                className={`${inputClass} pl-10 pr-10`}
                placeholder="至少 8 位"
                autoComplete="new-password"
                maxLength={72}
                aria-label="密码"
              />
              <button
                type="button"
                onClick={() => setShowPassword(value => !value)}
                aria-label={showPassword ? '隐藏密码' : '显示密码'}
                aria-pressed={showPassword}
                className="absolute right-1.5 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600"
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </label>

          <label className="group block">
            <span className={labelClass}>确认密码</span>
            <div className="relative">
              <Lock className={iconClass} />
              <input
                type={showPassword ? 'text' : 'password'}
                value={confirm}
                onChange={event => {
                  setConfirm(event.target.value)
                  clearError()
                }}
                className={`${inputClass} pl-10 pr-3`}
                placeholder="再输一次"
                autoComplete="new-password"
                maxLength={72}
                aria-label="确认密码"
              />
            </div>
          </label>
        </div>

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
              正在创建…
            </>
          ) : (
            <>
              创建工作区
              <ArrowRight className="h-4 w-4 transition-transform duration-200 group-hover:translate-x-0.5" />
            </>
          )}
        </button>
      </form>

      <div className="mt-5 flex items-center justify-center gap-1 text-[12px] text-slate-500">
        <span>已经有账号了？</span>
        <Link
          to="/login"
          className="font-medium text-primary-600 underline-offset-4 transition-colors hover:text-primary-700 hover:underline"
        >
          去登录
        </Link>
      </div>

      <div className="mt-3 flex items-center justify-center gap-1.5 text-[11px] text-slate-400">
        <ShieldCheck className="h-3.5 w-3.5" />
        每个工作区独立隔离，简历与面试记录互不可见
      </div>
    </AuthFrame>
  )
}
