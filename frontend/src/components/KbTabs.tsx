import { Link } from 'react-router-dom'

/**
 * 库内的四个视角：同一份资料，四种用法。
 *
 * <p>放在页面顶部一排，是让人不用回侧栏就能换视角——用户记不住层级，只记得"我想练一下"。</p>
 */
export type KbTabKey = 'documents' | 'catalog' | 'practice' | 'chat'

const TABS: { key: KbTabKey; label: string; suffix: string }[] = [
  { key: 'documents', label: '资料', suffix: '' },
  { key: 'catalog', label: '目录', suffix: '/catalog' },
  { key: 'practice', label: '练习', suffix: '/quiz' },
  { key: 'chat', label: '问答', suffix: '/chat' }
]

export default function KbTabs({ kbId, active }: { kbId: number; active: KbTabKey }) {
  return (
    <nav className="mt-3 flex items-center gap-1 border-b border-slate-200">
      {TABS.map(tab => {
        const isActive = tab.key === active
        return (
          <Link
            key={tab.key}
            to={`/kb/${kbId}${tab.suffix}`}
            className={`-mb-px border-b-2 px-4 py-2.5 text-sm font-medium transition ${
              isActive
                ? 'border-primary-500 text-primary-600'
                : 'border-transparent text-slate-500 hover:text-slate-800'
            }`}
          >
            {tab.label}
          </Link>
        )
      })}
    </nav>
  )
}
