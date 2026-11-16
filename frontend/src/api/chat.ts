import apiClient, { buildAuthenticatedHeaders } from './client'
import { consumeSseStream } from '@/utils/sseStream'

export type KbChatRole = 'USER' | 'ASSISTANT'
export type KbChatStageStatus = 'RUNNING' | 'COMPLETED' | 'SKIPPED' | 'FAILED'

export interface KbChatStage {
  id: string
  label: string
  status: KbChatStageStatus
  count: number | null
  durationMs: number | null
  detail: string | null
}

export interface KbChatCitation {
  index: number
  chunkKey: string
  documentId: number
  documentName: string | null
  heading: string | null
  pageNo: number | null
  excerpt: string
  score: number
}

export interface KbChatSessionView {
  id: number
  kbId: number
  title: string
  createdAt: string
  updatedAt: string
}

export interface KbChatMessageView {
  id: number
  role: KbChatRole
  content: string
  citations: KbChatCitation[]
  stages: KbChatStage[]
  latencyMs: number | null
  createdAt: string
}

export interface KbChatAnswer {
  sessionId: number
  messageId: number
  content: string
  citations: KbChatCitation[]
  latencyMs: number
}

export interface KbChatPayload {
  sessionId?: number
  question: string
  topK?: number
  mode?: 'hybrid' | 'keyword' | 'vector'
  rerank?: boolean
  publishedOnly?: boolean
}

export interface KbChatStreamHandlers {
  onSession?: (session: { id: number; title: string }) => void
  onStage?: (stage: KbChatStage) => void
  onCitations?: (citations: KbChatCitation[]) => void
  onToken?: (token: string) => void
  onDone?: (answer: KbChatAnswer) => void
}

export const chatApi = {
  sessions(kbId: number): Promise<KbChatSessionView[]> {
    return apiClient.get<KbChatSessionView[], KbChatSessionView[]>(`/v1/kb/${kbId}/chat/sessions`)
  },

  messages(kbId: number, sessionId: number): Promise<KbChatMessageView[]> {
    return apiClient.get<KbChatMessageView[], KbChatMessageView[]>(
      `/v1/kb/${kbId}/chat/sessions/${sessionId}/messages`
    )
  },

  deleteSession(kbId: number, sessionId: number): Promise<void> {
    return apiClient.delete<void, void>(`/v1/kb/${kbId}/chat/sessions/${sessionId}`)
  },

  /**
   * 问答（流式）：session → stage × N → citations → token × N → done。
   * 阶段事件一到达就回调，前端据此把思考链路逐节点点亮。
   */
  async stream(
    kbId: number,
    payload: KbChatPayload,
    handlers: KbChatStreamHandlers = {}
  ): Promise<KbChatAnswer> {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 180000)
    let answer: KbChatAnswer | null = null
    let failure: string | null = null
    try {
      const response = await fetch(`/api/v1/kb/${kbId}/chat/stream`, {
        method: 'POST',
        headers: buildAuthenticatedHeaders({
          'Content-Type': 'application/json',
          Accept: 'text/event-stream'
        }),
        body: JSON.stringify(payload),
        signal: controller.signal
      })
      if (!response.ok || !response.body) {
        throw new Error(`问答失败（HTTP ${response.status}）`)
      }
      await consumeSseStream(response.body.getReader(), ({ type, data }) => {
        try {
          if (type === 'session') {
            handlers.onSession?.(JSON.parse(data) as { id: number; title: string })
          } else if (type === 'stage') {
            handlers.onStage?.(JSON.parse(data) as KbChatStage)
          } else if (type === 'citations') {
            handlers.onCitations?.(JSON.parse(data) as KbChatCitation[])
          } else if (type === 'token') {
            handlers.onToken?.((JSON.parse(data) as { text?: string }).text ?? '')
          } else if (type === 'done') {
            answer = JSON.parse(data) as KbChatAnswer
            handlers.onDone?.(answer)
          } else if (type === 'error') {
            failure = (JSON.parse(data) as { message?: string }).message || '问答失败'
          }
        } catch {
          // 单个坏事件不影响整条流
        }
      })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw new Error('回答超时，请稍后重试')
      }
      throw error
    } finally {
      window.clearTimeout(timeout)
    }
    if (failure) throw new Error(failure)
    if (!answer) throw new Error('问答未返回结果')
    return answer
  }
}
