import apiClient from './client'

export type QuizResult = 'CORRECT' | 'WRONG' | 'UNSURE' | 'SKIPPED'

export interface KbQuizItemView {
  attemptId: number
  ordinal: number
  questionId: number
  stem: string
  difficulty: string | null
  tags: string[]
  answered: boolean
  result: QuizResult | null
}

export interface KbQuizSessionView {
  sessionId: number
  kbId: number
  status: 'RUNNING' | 'FINISHED'
  questionCount: number
  answeredCount: number
  correctCount: number
  wrongCount: number
  startedAt: string
  finishedAt: string | null
  items: KbQuizItemView[]
}

export interface KbQuizPointView {
  categoryId: number
  name: string
  path: string
  catalogLink: string
  description: string | null
}

export interface KbQuizRelatedView {
  categoryId: number
  name: string
  relation: string
  weight: number
  direction: 'OUT' | 'IN'
}

export interface KbQuizRevealView {
  attemptId: number
  questionId: number
  stem: string
  answer: string | null
  thinkingSeconds: number
  point: KbQuizPointView | null
  related: KbQuizRelatedView[]
}

export interface KbQuizWrongItemView {
  questionId: number
  stem: string
  categoryId: number | null
  categoryPath: string | null
  wrongCount: number
}

export interface KbQuizSummaryView {
  correctCount: number
  wrongCount: number
  accuracy: number
  wrongItems: KbQuizWrongItemView[]
}

export interface KbQuizGradeView {
  attemptId: number
  result: QuizResult
  correct: boolean
  answeredCount: number
  questionCount: number
  finished: boolean
  summary: KbQuizSummaryView | null
  point: KbQuizPointView | null
  related: KbQuizRelatedView[]
}

export interface KbQuizStatsView {
  kbId: number
  totalQuestions: number
  assignedQuestions: number
  attemptedQuestions: number
  answered: number
  correct: number
  wrong: number
  unsure: number
  accuracy: number
  wrongDistinctQuestions: number
  weakPoints: { categoryId: number; name: string; path: string | null; wrongCount: number }[]
  recentSessions: KbQuizSessionView[]
}

export interface KbQuizCoverageView {
  kbId: number
  totalQuestions: number
  assignedQuestions: number
  uncategorizedQuestions: number
  lowConfidenceQuestions: number
  categoryCount: number
  leafCategoryCount: number
  coverageRatio: number
  gaps: {
    questionId: number
    stem: string
    categoryId: number | null
    categoryName: string | null
    confidence: number | null
  }[]
  documents: {
    documentId: number
    documentName: string
    total: number
    assigned: number
    lowConfidence: number
  }[]
}

/** 一个知识点的能力值（Elo 口径）。thetaEffective 是扣掉遗忘衰减后的分数。 */
export interface KbSkillPointView {
  categoryId: number
  name: string
  path: string | null
  depth: number
  leaf: boolean
  attempts: number
  correct: number
  wrong: number
  unsure: number
  interviewCount: number
  theta: number
  thetaEffective: number
  wrongRate: number
  lastResult: string | null
  lastSeenAt: string | null
}

export interface KbSkillsView {
  kbId: number
  trackedPoints: number
  points: KbSkillPointView[]
  branches: KbSkillPointView[]
}

/** 面试信号回填：dryRun 时 applied 恒为 0，只看"会匹配到什么"。 */
export interface KbInterviewBackfillView {
  kbId: number
  userId: number
  dryRun: boolean
  considered: number
  matched: number
  applied: number
  unmatchedSkills: number
  createdCategories: number
  unmatchedSamples: string[]
  byCategory: Array<{ categoryId: number; name: string; turns: number }>
  importedTotal: number
}

export interface KbCategoryRelationView {  id: number
  kbId: number
  fromCategoryId: number
  fromName: string | null
  toCategoryId: number
  toName: string | null
  relation: string
  weight: number
  note: string | null
  createdAt: string
}

export const quizApi = {
  /** 开一组题：返回的题面**不含答案**，答案要调 reveal 才给。 */
  start(kbId: number, size = 5): Promise<KbQuizSessionView> {
    return apiClient.post<KbQuizSessionView, KbQuizSessionView>(`/v1/kb/${kbId}/quiz`, { size })
  },
  session(sessionId: number): Promise<KbQuizSessionView> {
    return apiClient.get<KbQuizSessionView, KbQuizSessionView>(`/v1/kb/quiz/sessions/${sessionId}`)
  },
  reveal(attemptId: number): Promise<KbQuizRevealView> {
    return apiClient.post<KbQuizRevealView, KbQuizRevealView>(
      `/v1/kb/quiz/attempts/${attemptId}/reveal`,
      {}
    )
  },
  grade(attemptId: number, result: QuizResult): Promise<KbQuizGradeView> {
    return apiClient.post<KbQuizGradeView, KbQuizGradeView>(`/v1/kb/quiz/attempts/${attemptId}/grade`, {
      result
    })
  },
  stats(kbId: number): Promise<KbQuizStatsView> {
    return apiClient.get<KbQuizStatsView, KbQuizStatsView>(`/v1/kb/${kbId}/quiz/stats`)
  },
  wrongBook(kbId: number, limit = 50): Promise<KbQuizWrongItemView[]> {
    return apiClient.get<KbQuizWrongItemView[], KbQuizWrongItemView[]>(
      `/v1/kb/${kbId}/quiz/wrong-book?limit=${limit}`
    )
  },
  coverage(kbId: number): Promise<KbQuizCoverageView> {
    return apiClient.get<KbQuizCoverageView, KbQuizCoverageView>(`/v1/kb/${kbId}/catalog/coverage`)
  },
  /** 批量确认归属：把已经挂着但置信度低的题一次性锁掉。 */
  confirmBatch(kbId: number, questionIds: number[]): Promise<{ confirmed: number; skipped: number }> {
    return apiClient.post<{ confirmed: number; skipped: number }, { confirmed: number; skipped: number }>(
      `/v1/kb/${kbId}/catalog/items/confirm-batch`,
      { itemType: 'QUESTION', itemIds: questionIds }
    )
  },
  /** 能力值总览：叶子按弱到强排，父节点是按题量加权聚合的"整块弱"。 */
  skills(kbId: number): Promise<KbSkillsView> {
    return apiClient.get<KbSkillsView, KbSkillsView>(`/v1/kb/${kbId}/quiz/skills`)
  },
  /** 面试信号回填：dryRun=true 只预检；createMissing=true 时把匹配不上的 skill 建成知识点。 */
  backfillInterview(
    kbId: number,
    dryRun = true,
    createMissing = false,
    rootName = '面试评估'
  ): Promise<KbInterviewBackfillView> {
    const query = `?dryRun=${dryRun}&createMissing=${createMissing}&rootName=${encodeURIComponent(rootName)}`
    return apiClient.post<KbInterviewBackfillView, KbInterviewBackfillView>(
      `/v1/kb/${kbId}/quiz/skills/interview-backfill${query}`,
      {}
    )
  },
  relations(kbId: number, categoryId?: number): Promise<KbCategoryRelationView[]> {
    const suffix = categoryId == null ? '' : `?categoryId=${categoryId}`
    return apiClient.get<KbCategoryRelationView[], KbCategoryRelationView[]>(
      `/v1/kb/${kbId}/catalog/relations${suffix}`
    )
  },
  createRelation(
    kbId: number,
    payload: { fromCategoryId: number; toCategoryId: number; relation?: string; weight?: number; note?: string }
  ): Promise<KbCategoryRelationView> {
    return apiClient.post<KbCategoryRelationView, KbCategoryRelationView>(
      `/v1/kb/${kbId}/catalog/relations`,
      payload
    )
  },
  deleteRelation(kbId: number, relationId: number): Promise<void> {
    return apiClient.delete<void, void>(`/v1/kb/${kbId}/catalog/relations/${relationId}`)
  }
}
