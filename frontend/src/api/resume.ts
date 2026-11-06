import apiClient from './client'
import type { AnalysisView, ApiResponse, ResumeView } from '@/types/api'

export const resumeApi = {
  list() {
    return apiClient.get<any, ApiResponse<ResumeView[]>>('/v1/interview/resumes')
  },

  find(id: number) {
    return apiClient.get<any, ApiResponse<ResumeView>>(`/v1/interview/resumes/${id}`)
  },

  upload(file: File) {
    const form = new FormData()
    form.append('file', file)
    return apiClient.post<any, ApiResponse<ResumeView>>('/v1/interview/resumes', form)
  },

  analysis(id: number) {
    return apiClient.get<any, ApiResponse<AnalysisView>>(`/v1/interview/resumes/${id}/analysis`)
  }
}
