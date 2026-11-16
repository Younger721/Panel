import apiClient, { requestBlob, requestText } from './client'
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
   *
   * format=markdown 表示这次改的是 Markdown 内容稿：后端只挪 md 指针，
   * PDF 原版、原位改动和「导出改后的 PDF」都保持可用。
   */
  saveContent(
    id: number,
    text: string,
    html?: string,
    edits?: Record<string, string>,
    format?: 'text' | 'markdown'
  ): Promise<ResumeView> {
    return apiClient.put<unknown, ResumeView>(`/v1/interview/resumes/${id}/content`, { text, html, edits, format })
  },

  /**
   * Markdown 内容稿的原文；null 表示这份简历还没用 md 改过。
   *
   * <p>编辑器打开时优先用它，而不是解析后重新渲染的 md——渲染会规范化列表前缀、
   * 洗掉行内强调，用户会以为自己写的东西被系统改了。</p>
   */
  markdownSource(id: number): Promise<string | null> {
    return requestText(`/v1/interview/resumes/${id}/content/markdown`)
  },

  /** 原文不变，只重跑解析与批注。 */
  reanalyze(id: number): Promise<ResumeView> {    return apiClient.post<unknown, ResumeView>(`/v1/interview/resumes/${id}/analyze`)
  },

  /**
   * 解卡：把长期停在解析中/分析中、或已经 FAILED 的简历退回排队并重投任务。
   *
   * <p>和 {@link reanalyze} 的区别是后者会被"正在解析"挡成 409 ——
   * 任务被异常中断、状态卡死时只有这个能救。</p>
   */
  reset(id: number): Promise<ResumeView> {
    return apiClient.post<unknown, ResumeView>(`/v1/interview/resumes/${id}/reset`)
  },

  /**
   * 软删除：后端只写删除时间戳，行、原文、画像、批注都留着。
   * 返回体带 deletedAt，撤销提示用它确认删的是哪一条。
   */
  remove(id: number): Promise<ResumeView> {
    return apiClient.delete<unknown, ResumeView>(`/v1/interview/resumes/${id}`)
  },

  /** 撤销软删除，记录原样回到列表。 */
  restore(id: number): Promise<ResumeView> {
    return apiClient.post<unknown, ResumeView>(`/v1/interview/resumes/${id}/restore`)
  }
}
