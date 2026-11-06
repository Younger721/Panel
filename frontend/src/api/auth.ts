import apiClient from './client'

export interface LoginResult {
  accessToken: string
  expiresIn?: number
  displayName?: string
  roles?: string[]
}

export const authApi = {
  login(username: string, password: string): Promise<LoginResult> {
    return apiClient.post<unknown, LoginResult>('/v1/auth/login', { username, password })
  }
}
