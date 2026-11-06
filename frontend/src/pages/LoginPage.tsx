import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { setToken } from '@/api/client'

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation() as { state?: { from?: string } }
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setLoading(true)
    setError(null)
    try {
      const result = await authApi.login(username.trim(), password)
      setToken(result.accessToken)
      navigate(location.state?.from || '/', { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-full items-center justify-center px-6 py-16">
      <div className="w-full max-w-sm">
        <div className="mb-8">
          <div className="text-2xl font-semibold tracking-tight text-slate-900">Nova</div>
          <p className="mt-1 text-sm text-slate-500">简历批注 · 模拟面试</p>
        </div>
        <form onSubmit={submit} className="space-y-4 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-500">用户名</span>
            <input
              value={username}
              onChange={event => setUsername(event.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
              autoComplete="username"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-500">密码</span>
            <input
              type="password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
              autoComplete="current-password"
            />
          </label>
          {error && <p className="text-xs text-rose-600">{error}</p>}
          <button
            type="submit"
            disabled={loading || !password}
            className="w-full rounded-lg bg-slate-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-40"
          >
            {loading ? '登录中…' : '登录'}
          </button>
        </form>
        <p className="mt-4 text-center text-xs text-slate-400">
          <Link to="/" className="hover:text-slate-600">
            返回首页
          </Link>
        </p>
      </div>
    </div>
  )
}
