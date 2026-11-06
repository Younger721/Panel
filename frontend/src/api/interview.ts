import apiClient, { buildAuthenticatedHeaders } from './client'
import type {
  ApiResponse,
  InterviewAnswerResult,
  InterviewSessionSummary,
  InterviewSessionView
} from '@/types/api'

export const interviewApi = {
  sessions() {
    return apiClient.get<any, ApiResponse<InterviewSessionSummary[]>>('/v1/interview/sessions')
  },

  detail(id: number) {
    return apiClient.get<any, ApiResponse<InterviewSessionView>>(`/v1/interview/sessions/${id}`)
  },

  create(body: { resumeId: number; questionCount: number; followUpLimit: number }) {
    return apiClient.post<any, ApiResponse<InterviewSessionView>>('/v1/interview/sessions', body)
  },

  answer(id: number, answer: string) {
    return apiClient.post<any, ApiResponse<InterviewAnswerResult>>(
      `/v1/interview/sessions/${id}/answers`,
      { answer }
    )
  },

  report(id: number) {
    return apiClient.get<any, ApiResponse<unknown>>(`/v1/interview/sessions/${id}/report`)
  },

  /** 结构化事件流：agent/question/evaluation/report/done。 */
  async stream(
    body: { resumeId: number; questionCount: number; followUpLimit: number },
    onEvent: (type: string, data: unknown) => void
  ) {
    const response = await fetch('/api/v1/interview/sessions/stream', {
      method: 'POST',
      headers: buildAuthenticatedHeaders({
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      }),
      body: JSON.stringify(body)
    })
    if (!response.ok || !response.body) {
      throw new Error('面试创建失败')
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const chunks = buffer.split(/\r?\n\r?\n/)
      buffer = chunks.pop() || ''
      for (const chunk of chunks) {
        const event = chunk.split(/\r?\n/).find(line => line.startsWith('event:'))?.slice(6).trim() || 'message'
        const data = chunk
          .split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n')
        if (!data) continue
        try {
          onEvent(event, JSON.parse(data))
        } catch {
          onEvent(event, data)
        }
      }
    }
  }
}
