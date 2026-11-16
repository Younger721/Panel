import { Link, useLocation, useNavigate } from 'react-router-dom'
import {
  ChevronRight,
  Database,
  FileStack,
  LogOut,
  MessageSquare,
  Sparkles,
  Upload,
  Users
} from 'lucide-react'
import { clearToken } from '@/api/client'
import { clearActor } from '@/auth/actor'

interface NavItem {
  path: string
  label: string
  description: string
  icon: React.ComponentType<{ className?: string }>
  disabled?: boolean
  external?: boolean
}

const NAV_GROUPS: { id: string; title: string; items: NavItem[] }[] = [
  {
    id: 'career',
    title: '简历与面试',
    items: [
      { path: '/', label: '上传简历', description: 'AI 分析简历', icon: Upload },
      { path: '/resumes', label: '简历库', description: '管理所有简历', icon: FileStack },
      { path: '/history', label: '面试记录', description: '查看面试历史', icon: Users }
    ]
  },
  {
    id: 'knowledge',
    title: '知识库',
    items: [
      { path: '/kb', label: '我的资料', description: '文档与目录', icon: Database },
      { path: '/kb/quiz', label: '练习', description: '抽题自测，错题跳知识点', icon: Sparkles },
      { path: '/kb/chat', label: '问答', description: '基于资料回答', icon: MessageSquare }
    ]
  }
]

function isActive(path: string, current: string) {
  if (path === '/') {
    return current === '/'
  }
  if (path === '/resumes') {
    return current.startsWith('/resumes')
  }
  if (path === '/history') {
    return current.startsWith('/history') || current.startsWith('/interview')
  }
  if (path === '/kb') {
    // 库内的四个视角（资料/目录/练习/问答）都算"我的资料"，否则进库以后侧栏会全灭
    return current === '/kb' || (/^\/kb\/\d+/.test(current) && !/\/(quiz|chat)$/.test(current))
  }
  if (path === '/kb/chat') {
    return current.startsWith('/kb/chat') || /^\/kb\/\d+\/chat$/.test(current)
  }
  if (path === '/kb/quiz') {
    // 挑题页（/kb/questions）是从练习里点进去的，仍算"练习"，否则侧栏会一个都不亮
    return current.startsWith('/kb/quiz') || current.startsWith('/kb/questions') || /^\/kb\/\d+\/quiz$/.test(current)
  }
  return current.startsWith(path)
}

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  // 编辑器是自带顶栏与画布的全屏应用，宿主只留侧栏，不给内边距
  const fullBleed = location.pathname.startsWith('/resume-editor')
  // 面试进行中（/interview/12）整页接管：连侧栏一起收掉，
  // 否则舞台只有 1435px，撑不出"人在场景里"的感觉。/interview/new 仍走常规布局。
  const immersive = /^\/interview\/\d+$/.test(location.pathname)

  if (immersive) {
    return (
      <main key={location.pathname} className="h-screen w-full overflow-hidden">
        {children}
      </main>
    )
  }

  return (
    <div className="flex min-h-screen">
      <aside className="fixed left-0 top-0 z-50 flex h-screen w-64 flex-col border-r border-slate-100 bg-white">
        <div className="flex items-center border-b border-slate-100 p-6">
          <Link to="/" className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/30">
              <Sparkles className="h-5 w-5" />
            </div>
            <div>
              <span className="block text-lg font-bold tracking-tight text-slate-800">Panel</span>
              <span className="text-xs text-slate-400">智能面试助手</span>
            </div>
          </Link>
        </div>

        <nav className="scrollbar-thin flex-1 overflow-y-auto p-4">
          <div className="space-y-6">
            {NAV_GROUPS.map(group => (
              <div key={group.id}>
                <div className="mb-2 px-3">
                  <span className="section-label">{group.title}</span>
                </div>
                <div className="space-y-1">
                  {group.items.map(item => {
                    const active = !item.disabled && !item.external && isActive(item.path, location.pathname)
                    const Icon = item.icon
                    if (item.disabled) {
                      return (
                        <div
                          key={item.path}
                          className="flex cursor-not-allowed items-center gap-3 rounded-xl px-3 py-2.5 text-slate-400 opacity-60"
                          title="知识库链路暂缓，先聚焦简历分析与面试"
                        >
                          <Icon className="h-[18px] w-[18px] shrink-0 text-slate-300" />
                          <div className="min-w-0 flex-1">
                            <span className="block text-sm font-medium">{item.label}</span>
                            <span className="block truncate text-xs text-slate-400">暂未开放</span>
                          </div>
                        </div>
                      )
                    }
                    const className = `group relative flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors duration-150 ${
                      active
                        ? 'bg-primary-50/80 text-primary-700'
                        : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
                    }`
                    const content = (
                      <>
                        {active && (
                          <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-full bg-primary-500" />
                        )}
                        <Icon
                          className={`h-[18px] w-[18px] shrink-0 transition-colors ${
                            active ? 'text-primary-600' : 'text-slate-400 group-hover:text-slate-500'
                          }`}
                        />
                        <div className="min-w-0 flex-1">
                          <span className={`block text-sm ${active ? 'font-semibold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                          <span
                            className={`block truncate text-xs ${
                              active ? 'text-primary-500/80' : 'text-slate-400'
                            }`}
                          >
                            {item.description}
                          </span>
                        </div>
                        {active && <ChevronRight className="h-3.5 w-3.5 text-primary-400" />}
                      </>
                    )
                    if (item.external) {
                      return (
                        <a
                          key={item.path}
                          href={item.path}
                          className={className}
                          title={`${item.label} · ${item.description}`}
                        >
                          {content}
                        </a>
                      )
                    }
                    return (
                      <Link key={item.path} to={item.path} className={className}>
                        {content}
                      </Link>
                    )
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        <div className="border-t border-slate-100 p-4">
          <div className="rounded-xl bg-gradient-to-r from-primary-50 to-indigo-50 px-3 py-2">
            <p className="text-xs font-medium text-primary-600">Panel 面试助手 v1.0</p>
            <p className="mt-0.5 truncate text-xs text-slate-400">Planner · Evaluator · Reviewer</p>
          </div>
          <button
            onClick={() => {
              clearToken()
              clearActor()
              navigate('/login')
            }}
            className="mt-2 flex w-full items-center justify-center gap-2 rounded-xl px-3 py-2 text-xs text-slate-400 transition-colors hover:bg-slate-50 hover:text-slate-600"
          >
            <LogOut className="h-3.5 w-3.5" />
            退出登录
          </button>
        </div>
      </aside>

      <main
        key={location.pathname}
        className={
          fullBleed ? 'ml-64 h-screen flex-1 overflow-hidden bg-white' : 'ml-64 min-h-screen flex-1 animate-fade-up p-10'
        }
      >
        {children}
      </main>
    </div>
  )
}
