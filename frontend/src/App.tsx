import { Navigate, Route, Routes, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useEffect } from 'react'
import { getToken, setToken } from '@/api/client'
import LoginPage from '@/pages/LoginPage'
import UploadPage from '@/pages/UploadPage'
import ResumePage from '@/pages/ResumePage'
import InterviewSetupPage from '@/pages/InterviewSetupPage'
import InterviewPage from '@/pages/InterviewPage'
import ReportPage from '@/pages/ReportPage'
import HistoryPage from '@/pages/HistoryPage'

function Guard({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  if (!getToken()) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <>{children}</>
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
          <Guard>
            <UploadPage />
          </Guard>
        }
      />
      <Route
        path="/resumes/:id"
        element={
          <Guard>
            <ResumePage />
          </Guard>
        }
      />
      <Route
        path="/interview/new"
        element={
          <Guard>
            <InterviewSetupPage />
          </Guard>
        }
      />
      <Route
        path="/interview/:id"
        element={
          <Guard>
            <InterviewPage />
          </Guard>
        }
      />
      <Route
        path="/interview/:id/report"
        element={
          <Guard>
            <ReportPage />
          </Guard>
        }
      />
      <Route
        path="/history"
        element={
          <Guard>
            <HistoryPage />
          </Guard>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
