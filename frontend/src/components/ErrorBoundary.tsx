import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

/**
 * 兜底：页面渲染炸了要让人看得见，而不是"点了没反应"。
 *
 * 之前前端热更新中途换模块，组件里引到一个已删掉的变量，整棵树静默崩掉——
 * 用户看到的是编辑框打不进字、点「完成」没动静，完全猜不出发生了什么。
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('页面渲染失败', error, info.componentStack)
  }

  render(): ReactNode {
    const { error } = this.state
    if (!error) {
      return this.props.children
    }
    return (
      <div className="flex min-h-[60vh] items-center justify-center p-8">
        <div className="card max-w-md p-8 text-center">
          <p className="text-sm font-medium text-slate-800">这个页面没能加载出来</p>
          <p className="mt-2 break-all text-xs leading-relaxed text-slate-500">{error.message}</p>
          <div className="mt-5 flex justify-center gap-3">
            <button type="button" className="btn-primary" onClick={() => window.location.reload()}>
              刷新页面
            </button>
            <button
              type="button"
              className="btn-ghost border border-slate-200 text-slate-600"
              onClick={() => {
                this.setState({ error: null })
                window.location.assign('/resumes')
              }}
            >
              回简历库
            </button>
          </div>
        </div>
      </div>
    )
  }
}
