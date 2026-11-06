import apiClient from './client'
import type { AnalysisView, ResumeView } from '@/types/api'

/** 统一返回已经拆掉 ApiResponse 外壳的业务数据。 */
export const resumeApi = {
  list(): Promise<ResumeView[]> {
    return apiClient.get<unknown, ResumeView[]>('/v1/interview/resumes')
  },

  find(id: number): Promise<ResumeView> {
    return apiClient.get<unknown, ResumeView>(`/v1/interview/resumes/${id}`)
  },

  upload(file: File): Promise<ResumeView> {
    const form = new FormData()
    form.append('file', file)
    return apiClient.post<unknown, ResumeView>('/v1/interview/resumes', form)
  },

  analysis(id: number): Promise<AnalysisView> {
    return apiClient.get<unknown, AnalysisView>(`/v1/interview/resumes/${id}/analysis`)
  }
}
