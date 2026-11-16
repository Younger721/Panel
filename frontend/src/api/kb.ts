import apiClient, { buildAuthenticatedHeaders, requestBlob } from './client'
import { consumeSseStream } from '@/utils/sseStream'

export type KbKind = 'QUESTION_BANK' | 'TECH_DOC' | 'JOB_KNOWLEDGE' | 'CUSTOM'
export type KbVisibility = 'PUBLIC' | 'INTERNAL'

export interface KnowledgeBaseView {
  id: number
  code: string
  name: string
  description: string | null
  kind: KbKind
  visibility: KbVisibility
  chunkStrategy: string
  chunkSize: number
  chunkOverlap: number
  topK: number
  rerankEnabled: boolean
  scoreThreshold: number
  builtin: boolean
  documentCount: number
  chunkCount: number
  createdAt: string
  updatedAt: string
}

export interface CreateKnowledgeBasePayload {
  code: string
  name: string
  description?: string | null
  kind: KbKind
  visibility: KbVisibility
  chunkStrategy?: string
  chunkSize?: number
  chunkOverlap?: number
  topK?: number
  rerankEnabled?: boolean
  scoreThreshold?: number
}

export interface UpdateKnowledgeBasePayload {
  name?: string
  description?: string | null
  visibility?: KbVisibility
  chunkStrategy?: string
  chunkSize?: number
  chunkOverlap?: number
  topK?: number
  rerankEnabled?: boolean
  scoreThreshold?: number
}

export type KbDocumentStatus =
  | 'UPLOADING'
  | 'PARSING'
  | 'CHUNKED'
  | 'INDEXING'
  | 'READY'
  | 'FAILED'
  | 'DELETED'

export interface KbDocumentView {
  id: number
  kbId: number
  fileName: string
  contentType: string
  sizeBytes: number
  status: KbDocumentStatus
  errorMessage: string | null
  chunkCount: number
  tokenCount: number
  published: boolean
  indexGeneration: string | null
  createdAt: string
  updatedAt: string
  indexedAt: string | null
}

export interface KbDocumentPageView {
  items: KbDocumentView[]
  total: number
  page: number
  size: number
}

/** 存量文档类型重判结果：一篇文档一行。 */
export interface KbDocKindScanRow {
  documentId: number
  fileName: string
  chunkCount: number
  questionCount: number
  questionBank: boolean
  score: number
  source: string
  evidence: string
}

export interface KbDocKindScanView {
  kbId: number
  dryRun: boolean
  scanned: number
  demoted: number
  kept: number
  rows: KbDocKindScanRow[]
  note: string
}

export interface KbChunkView {
  id: number
  documentId: number
  ordinal: number
  heading: string | null
  pageNo: number | null
  tokenCount: number
  text: string
}

export interface KbChunkPageView {
  items: KbChunkView[]
  total: number
  page: number
  size: number
}

export type KbRetrievalMode = 'HYBRID' | 'KEYWORD' | 'VECTOR'

export interface KbSearchHitView {
  chunkKey: string
  documentId: number
  kbId: number
  documentName: string | null
  ordinal: number
  heading: string | null
  pageNo: number | null
  text: string
  score: number
  rerankScore: number | null
  keywordRank: number | null
  vectorRank: number | null
  sources: string[]
}

export interface KbSearchDebugView {
  mode: string
  latencyMs: number
  keywordHits: number
  vectorHits: number
  fused: number
  reranked: boolean
  degraded: boolean
  threshold: number
  topK: number
}

export interface KbSearchResultView {
  hits: KbSearchHitView[]
  debug: KbSearchDebugView
}

export type KbSearchStageStatus = 'RUNNING' | 'COMPLETED' | 'SKIPPED' | 'FAILED'

/** 检索链路的一段，由 /search/stream 按真实执行顺序推过来。 */
export interface KbSearchStageView {
  id: string
  label: string
  status: KbSearchStageStatus
  count: number | null
  durationMs: number | null
  detail: string | null
}

export interface KbSearchStreamHandlers {
  onStage?: (stage: KbSearchStageView) => void
  onResult?: (result: KbSearchResultView) => void
}

export interface KbSearchPayload {
  query: string
  topK?: number
  mode?: KbRetrievalMode
  rerank?: boolean
  publishedOnly?: boolean
  categoryIds?: number[]
}

export const kbApi = {
  list(): Promise<KnowledgeBaseView[]> {
    return apiClient.get<KnowledgeBaseView[], KnowledgeBaseView[]>('/v1/kb')
  },
  get(id: number): Promise<KnowledgeBaseView> {
    return apiClient.get<KnowledgeBaseView, KnowledgeBaseView>(`/v1/kb/${id}`)
  },
  create(payload: CreateKnowledgeBasePayload): Promise<KnowledgeBaseView> {
    return apiClient.post<KnowledgeBaseView, KnowledgeBaseView>('/v1/kb', payload)
  },
  update(id: number, payload: UpdateKnowledgeBasePayload): Promise<KnowledgeBaseView> {
    return apiClient.patch<KnowledgeBaseView, KnowledgeBaseView>(`/v1/kb/${id}`, payload)
  },
  remove(id: number, force = false): Promise<void> {
    return apiClient.delete<void, void>(`/v1/kb/${id}${force ? '?force=true' : ''}`)
  },
  documents(
    kbId: number,
    params: { status?: string; keyword?: string; page?: number; size?: number } = {}
  ): Promise<KbDocumentPageView> {
    const query = new URLSearchParams()
    if (params.status) query.set('status', params.status)
    if (params.keyword) query.set('keyword', params.keyword)
    query.set('page', String(params.page ?? 1))
    query.set('size', String(params.size ?? 20))
    return apiClient.get<KbDocumentPageView, KbDocumentPageView>(`/v1/kb/${kbId}/documents?${query.toString()}`)
  },
  document(documentId: number): Promise<KbDocumentView> {
    return apiClient.get<KbDocumentView, KbDocumentView>(`/v1/kb/documents/${documentId}`)
  },
  chunks(documentId: number, page = 1, size = 100): Promise<KbChunkPageView> {
    return apiClient.get<KbChunkPageView, KbChunkPageView>(
      `/v1/kb/documents/${documentId}/chunks?page=${page}&size=${size}`
    )
  },
  chunk(chunkId: number): Promise<KbChunkView> {
    return apiClient.get<KbChunkView, KbChunkView>(`/v1/kb/chunks/${chunkId}`)
  },
  upload(kbId: number, file: File): Promise<KbDocumentView> {
    const form = new FormData()
    form.append('file', file)
    return apiClient.post<KbDocumentView, KbDocumentView>(`/v1/kb/${kbId}/documents`, form)
  },
  retry(documentId: number): Promise<KbDocumentView> {
    return apiClient.post<KbDocumentView, KbDocumentView>(`/v1/kb/documents/${documentId}/retry`)
  },
  reindex(documentId: number): Promise<KbDocumentView> {
    return apiClient.post<KbDocumentView, KbDocumentView>(`/v1/kb/documents/${documentId}/reindex`)
  },
  /** 存量文档类型重判：dryRun=true 只返回"会删哪些题"，false 才落库。 */
  kindScan(kbId: number, dryRun = true): Promise<KbDocKindScanView> {
    return apiClient.post<KbDocKindScanView, KbDocKindScanView>(
      `/v1/kb/${kbId}/documents/kind-scan?dryRun=${dryRun}`
    )
  },
  search(
    kbId: number,
    payload: {
      query: string
      topK?: number
      mode?: KbRetrievalMode
      rerank?: boolean
      publishedOnly?: boolean
    }
  ): Promise<KbSearchResultView> {
    return apiClient.post<KbSearchResultView, KbSearchResultView>(`/v1/kb/${kbId}/search`, payload)
  },

  /**
   * 检索（流式）：后端按真实执行顺序推 stage，最后 result + done。
   * 「思考链路」靠它逐个点亮；同步 search() 保留给不需要过程的调用方。
   */
  async searchStream(
    kbId: number,
    payload: KbSearchPayload,
    handlers: KbSearchStreamHandlers = {}
  ): Promise<KbSearchResultView> {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 60000)
    let result: KbSearchResultView | null = null
    let failure: string | null = null
    try {
      const response = await fetch(`/api/v1/kb/${kbId}/search/stream`, {
        method: 'POST',
        headers: buildAuthenticatedHeaders({
          'Content-Type': 'application/json',
          Accept: 'text/event-stream'
        }),
        body: JSON.stringify(payload),
        signal: controller.signal
      })
      if (!response.ok || !response.body) {
        throw new Error(`检索失败（HTTP ${response.status}）`)
      }
      await consumeSseStream(response.body.getReader(), ({ type, data }) => {
        if (type === 'stage') {
          try {
            handlers.onStage?.(JSON.parse(data) as KbSearchStageView)
          } catch {
            /* 单个坏事件不影响整条链路 */
          }
        } else if (type === 'result') {
          const parsed = JSON.parse(data) as KbSearchResultView
          result = parsed
          handlers.onResult?.(parsed)
        } else if (type === 'error') {
          try {
            failure = (JSON.parse(data) as { message?: string }).message || '检索失败'
          } catch {
            failure = data
          }
        }
      })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw new Error('检索超时，请稍后重试')
      }
      throw error
    } finally {
      window.clearTimeout(timeout)
    }
    if (failure) throw new Error(failure)
    if (!result) throw new Error('检索未返回结果')
    return result
  },
  publish(documentId: number, published: boolean): Promise<KbDocumentView> {
    return apiClient.patch<KbDocumentView, KbDocumentView>(`/v1/kb/documents/${documentId}/publication`, {
      published
    })
  },
  deleteDocument(documentId: number): Promise<void> {
    return apiClient.delete<void, void>(`/v1/kb/documents/${documentId}`)
  },
  downloadDocument(documentId: number): Promise<Blob> {
    return requestBlob(`/v1/kb/documents/${documentId}/download`)
  }
}
