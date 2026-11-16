import apiClient from './client'
import { setActor } from '@/auth/actor'

export interface LoginResult {
  userId: number
  tenantId: number | null
  username: string
  email: string | null
  displayName: string | null
  roles: string[]
  permissions: string[]
  accessToken: string
  accessExpiresAt?: string
  refreshToken?: string
  refreshExpiresAt?: string
}

export interface RegisterPayload {
  tenantCode: string
  tenantName: string
  email: string
  password: string
  displayName: string
}

/** 登录与注册拿到的都是同一份 AuthResponse，落地到 actor 的方式也一样。 */
function remember(result: LoginResult) {
  setActor({
    userId: result.userId,
    tenantId: result.tenantId ?? null,
    username: result.username,
    displayName: result.displayName ?? null,
    email: result.email ?? null,
    roles: result.roles ?? [],
    permissions: result.permissions ?? []
  })
}

export const authApi = {
  async login(username: string, password: string): Promise<LoginResult> {
    const result = await apiClient.post<unknown, LoginResult>('/v1/auth/login', { username, password })
    remember(result)
    return result
  },

  /** 自助注册：建一个工作区（租户）并把注册人设为该租户的管理员，注册即登录。 */
  async register(payload: RegisterPayload): Promise<LoginResult> {
    const result = await apiClient.post<unknown, LoginResult>('/v1/auth/register', payload)
    remember(result)
    return result
  }
}
