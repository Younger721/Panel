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

export const authApi = {
  async login(username: string, password: string): Promise<LoginResult> {
    const result = await apiClient.post<unknown, LoginResult>('/v1/auth/login', { username, password })
    setActor({
      userId: result.userId,
      tenantId: result.tenantId ?? null,
      username: result.username,
      displayName: result.displayName ?? null,
      email: result.email ?? null,
      roles: result.roles ?? [],
      permissions: result.permissions ?? []
    })
    return result
  }
}
