import apiClient, { requestBlob } from './client'
import type {
  AnalysisView,
  HighlightView,
  PreviewView,
  QuoteRequest,
  ResumeView
} from '@/types/api'

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
  },

  /** 原版预览的页面尺寸；available=false 表示该格式走文本视图。 */
  preview(id: number): Promise<PreviewView> {
    return apiClient.get<unknown, PreviewView>(`/v1/interview/resumes/${id}/preview`)
  },

  /** 单页渲染图；2.5 倍分辨率约等于 180 DPI，放大看也不糊。 */
  pageImage(id: number, page: number, scale = 2.5): Promise<Blob> {
    return requestBlob(`/v1/interview/resumes/${id}/preview/pages/${page}?scale=${scale}`)
  },

  /** 把批注引用的原文批量映射成页面高亮框。 */
  locate(id: number, quotes: QuoteRequest[]): Promise<Record<string, HighlightView>> {
    return apiClient.post<unknown, Record<string, HighlightView>>(`/v1/interview/resumes/${id}/preview/locate`, {
      quotes
    })
  },

  /**
   * 编辑器保存：正文写回成新的原文并重新排队解析（202，状态回到 PENDING）。
   */
  saveContent(id: number, text: string, html?: string): Promise<ResumeView> {
    return apiClient.put<unknown, ResumeView>(`/v1/interview/resumes/${id}/content`, { text, html })
  },

  /** 原文不变，只重跑解析与批注。 */
  reanalyze(id: number): Promise<ResumeView> {
    return apiClient.post<unknown, ResumeView>(`/v1/interview/resumes/${id}/analyze`)
  }
}
