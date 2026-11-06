import { Navigate, Route, Routes, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useEffect } from 'react'
import { getToken, setToken } from '@/api/client'
import AppLayout from '@/components/AppLayout'
import LoginPage from '@/pages/LoginPage'
import UploadPage from '@/pages/UploadPage'
import ResumeListPage from '@/pages/ResumeListPage'
import ResumePage from '@/pages/ResumePage'
import InterviewSetupPage from '@/pages/InterviewSetupPage'
import InterviewPage from '@/pages/InterviewPage'
import ReportPage from '@/pages/ReportPage'
import HistoryPage from '@/pages/HistoryPage'
import ResumeEditorPage from '@/pages/ResumeEditorPage'

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
      <AppLayout>{children}</AppLayout>
    </Guard>
  )
}

/** 仅开发环境：/dev-login?token=…&next=/resumes/1，用于截图与端到端冒烟。 */
function DevLogin() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const token = params.get('token')
  const next = params.get('next') || '/'
  useEffect(() => {
    if (token) {
      setToken(token)
    }
    navigate(next, { replace: true })
  }, [token, next, navigate])
  return null
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
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
      <Route
        path="/resume-editor"
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
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
