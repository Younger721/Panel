import apiClient from './client'
import type { ApiResponse } from '@/types/api'

export interface LoginResult {
  accessToken: string
  expiresIn?: number
  displayName?: string
  roles?: string[]
}

export const authApi = {
  login(username: string, password: string) {
    return apiClient.post<any, ApiResponse<LoginResult>>('/v1/auth/login', { username, password })
  }
}
