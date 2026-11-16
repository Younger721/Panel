import { Navigate, Route, Routes, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useEffect } from 'react'
import { getToken, setToken } from '@/api/client'
import { setActor } from '@/auth/actor'
import AppLayout from '@/components/AppLayout'
import ErrorBoundary from '@/components/ErrorBoundary'
import LoginPage from '@/pages/LoginPage'
import RegisterPage from '@/pages/RegisterPage'
import UploadPage from '@/pages/UploadPage'
import ResumeListPage from '@/pages/ResumeListPage'
import ResumePage from '@/pages/ResumePage'
import InterviewSetupPage from '@/pages/InterviewSetupPage'
import InterviewPage from '@/pages/InterviewPage'
import ReportPage from '@/pages/ReportPage'
import HistoryPage from '@/pages/HistoryPage'
import ResumeEditorPage from '@/pages/ResumeEditorPage'
import KnowledgeBaseListPage from '@/pages/KnowledgeBaseListPage'
import KnowledgeBaseDetailPage from '@/pages/KnowledgeBaseDetailPage'
import KbChatPage from '@/pages/KbChatPage'
import KbCatalogPage from '@/pages/KbCatalogPage'
import KbQuestionBankPage from '@/pages/KbQuestionBankPage'
import KbQuizPage from '@/pages/KbQuizPage'

function Guard({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  if (!getToken()) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <>{children}</>
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <Guard>
      <AppLayout>
        <ErrorBoundary>{children}</ErrorBoundary>
      </AppLayout>
    </Guard>
  )
}

/** 仅开发环境：/dev-login?token=…&next=/resumes/1，用于截图与端到端冒烟。 */
function DevLogin() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const token = params.get('token')
  const next = params.get('next') || '/'
  const actor = params.get('actor')
  useEffect(() => {
    if (token) {
      setToken(token)
      if (actor === 'admin') {
        setActor({
          userId: 0,
          tenantId: null,
          username: 'admin',
          displayName: '管理员',
          email: null,
          roles: ['PLATFORM_ADMIN'],
          permissions: []
        })
      }
    }
    navigate(next, { replace: true })
  }, [token, next, navigate, actor])
  return null
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      {import.meta.env.DEV && <Route path="/dev-login" element={<DevLogin />} />}
      <Route
        path="/"
        element={
          <Shell>
            <UploadPage />
          </Shell>
        }
      />
      <Route
        path="/resumes"
        element={
          <Shell>
            <ResumeListPage />
          </Shell>
        }
      />
      <Route
        path="/resumes/:id"
        element={
          <Shell>
            <ResumePage />
          </Shell>
        }
      />
      {/* 编辑器只能从简历库进入：没有选简历就直接回列表 */}
      <Route path="/resume-editor" element={<Navigate to="/resumes" replace />} />
      <Route
        path="/resume-editor/:resumeId"
        element={
          <Shell>
            <ResumeEditorPage />
          </Shell>
        }
      />
      <Route
        path="/interview/new"
        element={
          <Shell>
            <InterviewSetupPage />
          </Shell>
        }
      />
      <Route
        path="/interview/:id"
        element={
          <Shell>
            <InterviewPage />
          </Shell>
        }
      />
      <Route
        path="/interview/:id/report"
        element={
          <Shell>
            <ReportPage />
          </Shell>
        }
      />
      <Route
        path="/history"
        element={
          <Shell>
            <HistoryPage />
          </Shell>
        }
      />
      <Route
        path="/kb"
        element={
          <Shell>
            <KnowledgeBaseListPage />
          </Shell>
        }
      />
      <Route
        path="/kb/chat"
        element={
          <Shell>
            <KbChatPage />
          </Shell>
        }
      />
      <Route
        path="/kb/questions"
        element={
          <Shell>
            <KbQuestionBankPage />
          </Shell>
        }
      />
      <Route
        path="/kb/quiz"
        element={
          <Shell>
            <KbQuizPage />
          </Shell>
        }
      />
      <Route
        path="/kb/:id/catalog"
        element={
          <Shell>
            <KbCatalogPage />
          </Shell>
        }
      />
      <Route
        path="/kb/:id/quiz"
        element={
          <Shell>
            <KbQuizPage />
          </Shell>
        }
      />
      <Route
        path="/kb/:id/chat"
        element={
          <Shell>
            <KbChatPage />
          </Shell>
        }
      />
      <Route
        path="/kb/:id"
        element={
          <Shell>
            <KnowledgeBaseDetailPage />
          </Shell>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
