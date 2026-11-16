import type { ApiResponse } from '@/types/api'
import { clearActor } from '@/auth/actor'

const TOKEN_KEY = 'panel.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export function buildAuthenticatedHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { ...extra }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return headers
}

export class ApiError extends Error {
  readonly code: string
  readonly traceId?: string | null

  constructor(message: string, code: string, traceId?: string | null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

async function request<R>(method: string, url: string, body?: unknown): Promise<R> {
  const isForm = body instanceof FormData
  const headers = buildAuthenticatedHeaders(
    isForm || body === undefined ? {} : { 'Content-Type': 'application/json' }
  )
  let response: Response
  try {
    response = await fetch(`/api${url}`, {
      method,
      headers,
      body: isForm ? body : body === undefined ? undefined : JSON.stringify(body)
    })
  } catch {
    throw new ApiError('网络异常，请确认后端已启动', 'NETWORK')
  }

  let payload: ApiResponse<R> | null = null
  if (response.status === 204) {
    return undefined as R
  }
  try {
    payload = (await response.json()) as ApiResponse<R>
  } catch {
    payload = null
  }

  if (response.status === 401) {
    clearToken()
    clearActor()
    if (!location.pathname.startsWith('/login')) {
      location.replace('/login')
    }
    throw new ApiError('登录已过期，请重新登录', 'UNAUTHORIZED')
  }
  if (!response.ok || !payload || payload.success === false) {
    throw new ApiError(
      payload?.message || `请求失败（HTTP ${response.status}）`,
      payload?.code || `HTTP_${response.status}`,
      payload?.traceId
    )
  }
  return payload.data
}

const apiClient = {
  get<D = unknown, R = D>(url: string): Promise<R> {
    return request<R>('GET', url)
  },
  post<D = unknown, R = D>(url: string, body?: unknown): Promise<R> {
    return request<R>('POST', url, body)
  },
  put<D = unknown, R = D>(url: string, body?: unknown): Promise<R> {
    return request<R>('PUT', url, body)
  },
  patch<D = unknown, R = D>(url: string, body?: unknown): Promise<R> {
    return request<R>('PATCH', url, body)
  },
  delete<D = unknown, R = D>(url: string): Promise<R> {
    return request<R>('DELETE', url)
  }
}

/** 二进制接口（页面渲染图、导出 PDF）：同样带登录态，返回 Blob 由调用方转 object URL。 */
export async function requestBlob(url: string): Promise<Blob> {  let response: Response
  try {
    response = await fetch(`/api${url}`, { headers: buildAuthenticatedHeaders() })
  } catch {
    throw new ApiError('网络异常，请确认后端已启动', 'NETWORK')
  }
  if (response.status === 401) {
    clearToken()
    clearActor()
    if (!location.pathname.startsWith('/login')) {
      location.replace('/login')
    }
    throw new ApiError('登录已过期，请重新登录', 'UNAUTHORIZED')
  }
  if (!response.ok) {
    // 后端出错时返回的是 JSON（ApiResponse），把它的话原样交给用户。
    // 之前这里写死"原版预览加载失败"，但同一个函数也在导出 PDF——
    // 导出失败时提示"预览失败"只会让人更糊涂。
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const payload = (await response.json()) as { message?: string }
      if (payload?.message) {
        message = payload.message
      }
    } catch {
      // 不是 JSON（比如网关返回的 HTML），用上面的兜底文案
    }
    throw new ApiError(message, `HTTP_${response.status}`)
  }
  return response.blob()
}

/**
 * 纯文本接口（Markdown 内容稿这类），同样带登录态。
 *
 * <p>204 返回 null：表示"还没有这份内容"，调用方据此决定退回什么，
 * 而不是把一个空串当成"用户写了空的"。</p>
 */
export async function requestText(url: string): Promise<string | null> {
  let response: Response
  try {
    response = await fetch(`/api${url}`, { headers: buildAuthenticatedHeaders() })
  } catch {
    throw new ApiError('网络异常，请确认后端已启动', 'NETWORK')
  }
  if (response.status === 401) {
    clearToken()
    clearActor()
    if (!location.pathname.startsWith('/login')) {
      location.replace('/login')
    }
    throw new ApiError('登录已过期，请重新登录', 'UNAUTHORIZED')
  }
  if (response.status === 204) {
    return null
  }
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const payload = (await response.json()) as { message?: string }
      if (payload?.message) {
        message = payload.message
      }
    } catch {
      // 不是 JSON 就用兜底文案
    }
    throw new ApiError(message, `HTTP_${response.status}`)
  }
  return response.text()
}

export default apiClient
