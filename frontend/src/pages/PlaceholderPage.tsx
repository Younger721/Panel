import { Link } from 'react-router-dom'

export default function PlaceholderPage({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="mx-auto max-w-3xl px-6 py-16">
      <Link to="/" className="text-xs text-slate-400 hover:text-slate-600">
        ← 返回简历列表
      </Link>
      <h1 className="mt-6 text-xl font-semibold text-slate-900">{title}</h1>
      <p className="mt-2 max-w-xl text-sm leading-relaxed text-slate-500">{detail}</p>
    </div>
  )
}
