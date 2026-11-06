import apiClient, { buildAuthenticatedHeaders } from './client'
import type {
  InterviewAnswerResult,
  InterviewSessionSummary,
  InterviewSessionView
} from '@/types/api'

export interface AgentProgress {
  agent: string
  stage: string
  status: 'STARTED' | 'COMPLETED' | 'FAILED'
  message: string | null
  resultCount: number | null
  durationMs: number
}

/** 统一的 SSE 消费：把 event/data 拆好交给回调，流结束（或收到 done）后 resolve。 */
async function sse(path: string, body: unknown, onEvent: (type: string, data: any) => void) {
  const response = await fetch(`/api${path}`, {
    method: 'POST',
    headers: buildAuthenticatedHeaders({
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    }),
    body: JSON.stringify(body)
  })
  if (!response.ok || !response.body) {
    throw new Error(`请求失败（HTTP ${response.status}）`)
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
      let parsed: unknown = data
      try {
        parsed = JSON.parse(data)
      } catch {
        /* 纯文本事件原样透传 */
      }
      onEvent(event, parsed)
      if (event === 'done') return
    }
  }
}

export const interviewApi = {
  sessions(): Promise<InterviewSessionSummary[]> {
    return apiClient.get<unknown, InterviewSessionSummary[]>('/v1/interview/sessions')
  },

  detail(id: number): Promise<InterviewSessionView> {
    return apiClient.get<unknown, InterviewSessionView>(`/v1/interview/sessions/${id}`)
  },

  create(body: { resumeId: number; questionCount: number; followUpLimit: number }): Promise<InterviewSessionView> {
    return apiClient.post<unknown, InterviewSessionView>('/v1/interview/sessions', body)
  },

  answer(id: number, answer: string): Promise<InterviewAnswerResult> {
    return apiClient.post<unknown, InterviewAnswerResult>(`/v1/interview/sessions/${id}/answers`, { answer })
  },

  report(id: number): Promise<unknown> {
    return apiClient.get<unknown, unknown>(`/v1/interview/sessions/${id}/report`)
  },

  /** 创建面试并订阅进度：agent → session → question → done。 */
  createStream(
    body: { resumeId: number; questionCount: number; followUpLimit: number },
    onEvent: (type: string, data: any) => void
  ) {
    return sse('/v1/interview/sessions/stream', body, onEvent)
  },

  /** 提交回答并订阅进度：agent → evaluation → question / report → done。 */
  answerStream(id: number, answer: string, onEvent: (type: string, data: any) => void) {
    return sse(`/v1/interview/sessions/${id}/answers/stream`, { answer }, onEvent)
  }
}
