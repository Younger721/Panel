import apiClient, { buildAuthenticatedHeaders } from './client'
import type {
  InterviewAnswerResult,
  InterviewerPersonaView,
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
    // 409 是"这一题已经被别的请求认领"（双击、重试、另一个标签页），
    // 把服务端的话术原样带出去，比一个裸的状态码有用得多。
    let detail = ''
    try {
      const payload = await response.clone().json()
      detail = payload?.message || payload?.error?.message || ''
    } catch {
      /* 非 JSON 错误体 */
    }
    throw new Error(detail || `请求失败（HTTP ${response.status}）`)
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

export interface AsrTicket {
  ticket: string
  wsUrl: string
  model: string
  sampleRate: number
  format: string
  expiresInSeconds: number
}

export const interviewApi = {
  sessions(): Promise<InterviewSessionSummary[]> {
    return apiClient.get<unknown, InterviewSessionSummary[]>('/v1/interview/sessions')
  },

  personas(): Promise<InterviewerPersonaView[]> {
    return apiClient.get<unknown, InterviewerPersonaView[]>('/v1/interview/personas')
  },

  detail(id: number): Promise<InterviewSessionView> {
    return apiClient.get<unknown, InterviewSessionView>(`/v1/interview/sessions/${id}`)
  },

  create(body: {
    resumeId: number
    questionCount: number
    followUpLimit: number
    persona?: string
    /** 岗位 JD 原文，可选：传了才会跑岗位匹配并让选题偏向 JD 要求的技能。 */
    jd?: string
  }): Promise<InterviewSessionView> {
    return apiClient.post<unknown, InterviewSessionView>('/v1/interview/sessions', body)
  },

  /**
   * 提交作答。
   *
   * @param seq 前端当前看到的题号。带上它，服务端才能在"另一个标签页已经推进到下一题"时
   *            直接拒绝，而不是把你的答案记到下一题上。
   */
  answer(id: number, answer: string, seq?: number): Promise<InterviewAnswerResult> {
    return apiClient.post<unknown, InterviewAnswerResult>(`/v1/interview/sessions/${id}/answers`, {
      answer,
      seq
    })
  },

  /** 主动结束一场没答完的面试：会话进入 ABANDONED，不再挂着"进行中"。 */
  abandon(id: number): Promise<InterviewSessionView> {
    return apiClient.post<unknown, InterviewSessionView>(`/v1/interview/sessions/${id}/abandon`)
  },

  report(id: number): Promise<unknown> {
    return apiClient.get<unknown, unknown>(`/v1/interview/sessions/${id}/report`)
  },

  /**
   * 面试官语音。服务端合成失败时返回 204，这里翻译成 null——
   * 调用方据此静音降级，不弹错误。
   */
  async speech(
    id: number,
    options: { turnSeq?: number; canned?: 'OPENING' | 'ACK' }
  ): Promise<Blob | null> {
    const query = new URLSearchParams()
    if (options.turnSeq !== undefined) {
      query.set('turnSeq', String(options.turnSeq))
    }
    if (options.canned) {
      query.set('canned', options.canned)
    }
    let response: Response
    try {
      response = await fetch(`/api/v1/interview/sessions/${id}/speech?${query.toString()}`, {
        headers: buildAuthenticatedHeaders()
      })
    } catch {
      return null
    }
    if (response.status === 204) {
      return null
    }
    if (!response.ok) {
      return null
    }
    const blob = await response.blob()
    return blob.size > 0 ? blob : null
  },

  /** 换一张 30 秒、一次性的语音入场券：浏览器 WebSocket 带不了 Authorization 头。 */
  asrTicket(id: number): Promise<AsrTicket> {
    return apiClient.post<unknown, AsrTicket>(`/v1/interview/sessions/${id}/asr-ticket`)
  },

  /** 创建面试并订阅进度：agent → session → question → done。 */
  createStream(
    body: { resumeId: number; questionCount: number; followUpLimit: number; persona?: string; jd?: string },
    onEvent: (type: string, data: any) => void
  ) {
    return sse('/v1/interview/sessions/stream', body, onEvent)
  },

  /**
   * 提交回答并订阅进度：agent → evaluation → question / report → done。
   *
   * @param seq 当前题号；服务端用它挡住"答案被记到下一题上"的过期提交。
   */
  answerStream(
    id: number,
    answer: string,
    seq: number | undefined,
    onEvent: (type: string, data: any) => void
  ) {
    return sse(`/v1/interview/sessions/${id}/answers/stream`, { answer, seq }, onEvent)
  }
}
