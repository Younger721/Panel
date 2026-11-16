import apiClient from './client'

export type KbQuestionDifficulty = 'EASY' | 'MEDIUM' | 'HARD'

export interface KbQuestionView {
  id: number
  kbId: number
  documentId: number
  chunkId: number | null
  stem: string
  answer: string | null
  difficulty: KbQuestionDifficulty | null
  company: string | null
  role: string | null
  year: number | null
  tags: string[]
  createdAt: string
}

export interface KbQuestionPageView {
  items: KbQuestionView[]
  total: number
}

export interface KbQuestionReferenceView {
  chunkId: number
  kbId: number
  documentId: number
  documentName: string | null
  heading: string | null
  text: string
  concepts: string[]
}

export const questionApi = {
  list: (kbId: number, limit = 100, offset = 0) =>
    apiClient.get<KbQuestionPageView, KbQuestionPageView>(
      `/v1/kb/${kbId}/questions?limit=${limit}&offset=${offset}`
    ),
  detail: (id: number) => apiClient.get<KbQuestionView, KbQuestionView>(`/v1/kb/questions/${id}`),
  references: (id: number) =>
    apiClient.get<KbQuestionReferenceView[], KbQuestionReferenceView[]>(
      `/v1/kb/questions/${id}/references`
    )
}
