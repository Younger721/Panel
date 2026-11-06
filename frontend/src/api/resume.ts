import apiClient, { requestBlob } from './client'
import type {
  AnalysisView,
  HighlightView,
  PreviewView,
  QuoteRequest,
  ResumeView,
  TextLayerView
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

  /** 文字图层：每页尺寸 + 逐行文本与坐标（PDF 原位编辑用）。 */
  textLayer(id: number): Promise<TextLayerView> {
    return apiClient.get<unknown, TextLayerView>(`/v1/interview/resumes/${id}/preview/text`)
  },

  /** 单页渲染图；2.5 倍分辨率约等于 180 DPI，放大看也不糊。 */
  pageImage(id: number, page: number, scale = 2.5): Promise<Blob> {
    return requestBlob(`/v1/interview/resumes/${id}/preview/pages/${page}?scale=${scale}`)
  },

  /** 简历里嵌的字体文件（原始字节），给编辑框当网页字体用。 */
  previewFont(id: number, family: string): Promise<Blob> {
    return requestBlob(`/v1/interview/resumes/${id}/preview/fonts/${encodeURIComponent(family)}`)
  },

  /** 把批注引用的原文批量映射成页面高亮框。 */
  locate(id: number, quotes: QuoteRequest[]): Promise<Record<string, HighlightView>> {
    return apiClient.post<unknown, Record<string, HighlightView>>(`/v1/interview/resumes/${id}/preview/locate`, {
      quotes
    })
  },

  /**
   * 编辑器保存：正文写回成新的原文并重新排队解析（202，状态回到 PENDING）。
   * edits 只给 PDF 原位编辑用——行索引 → 新文本，用来还原编辑器里的显形文字。
   */
  saveContent(id: number, text: string, html?: string, edits?: Record<string, string>): Promise<ResumeView> {
    return apiClient.put<unknown, ResumeView>(`/v1/interview/resumes/${id}/content`, { text, html, edits })
  },

  /** 原文不变，只重跑解析与批注。 */
  reanalyze(id: number): Promise<ResumeView> {
    return apiClient.post<unknown, ResumeView>(`/v1/interview/resumes/${id}/analyze`)
  }
}
