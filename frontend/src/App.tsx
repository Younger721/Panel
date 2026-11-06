import { Navigate, Route, Routes, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useEffect } from 'react'
import { getToken, setToken } from '@/api/client'
import LoginPage from '@/pages/LoginPage'
import UploadPage from '@/pages/UploadPage'
import ResumePage from '@/pages/ResumePage'
import PlaceholderPage from '@/pages/PlaceholderPage'

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

function Guard({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  if (!getToken()) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <>{children}</>
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
        path="/interview/*"
        element={
          <Guard>
            <PlaceholderPage
              title="模拟面试"
              detail="面试页与报告页在 T9/T10 实现，后端接口已就绪（POST /sessions、POST /sessions/{id}/answers）。"
            />
          </Guard>
        }
      />
      <Route
        path="/history"
        element={
          <Guard>
            <PlaceholderPage
              title="面试历史"
              detail="历史列表接口已就绪（GET /sessions），页面在 T10 实现。"
            />
          </Guard>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
