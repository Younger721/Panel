import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Sparkles } from 'lucide-react'
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
    <div className="flex min-h-screen items-center justify-center px-6">
      <div className="w-full max-w-sm animate-fade-up">
        <div className="mb-8 flex flex-col items-center">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/30">
            <Sparkles className="h-7 w-7" />
          </div>
          <h1 className="mt-5 text-2xl font-bold tracking-tight text-slate-900">Nova AI</h1>
          <p className="mt-1 text-sm text-slate-500">简历批注 · 模拟面试</p>
        </div>

        <form onSubmit={submit} className="card space-y-4 p-7">
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-slate-500">用户名</span>
            <input
              value={username}
              onChange={event => setUsername(event.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100"
              autoComplete="username"
            />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-slate-500">密码</span>
            <input
              type="password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100"
              autoComplete="current-password"
            />
          </label>
          {error && <p className="text-xs text-rose-600">{error}</p>}
          <button type="submit" disabled={loading || !password} className="btn-primary w-full py-3">
            {loading ? '登录中…' : '登录'}
          </button>
        </form>
      </div>
    </div>
  )
}
