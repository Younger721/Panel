export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T
  traceId?: string | null
}

export type ResumeStatus = 'PENDING' | 'PARSING' | 'ANALYZING' | 'PARSED' | 'FAILED'

export interface ResumeView {
  id: number
  fileName: string
  contentType: string
  status: ResumeStatus
  lastError: string | null
  createdAt: string
  hasProfile: boolean
  yearsOfExperience: number | null
  skillCount: number | null
}

export type Level = 'STRONG' | 'MEDIUM' | 'WEAK'

export interface Anchor {
  blockId: string | null
  start: number
  end: number
}

export interface PointView {
  id: string
  title: string
  quote: string
  anchor: Anchor
  level: Level
  comment: string
  improvements: string[]
  questions: string[]
}

export interface ModuleView {
  type: string
  title: string
  summary: string
  blockIds: string[]
  points: PointView[]
}

export interface Block {
  id: string
  type: 'TITLE' | 'CLAUSE' | 'PARAGRAPH' | 'LIST_ITEM' | 'TABLE_ROW' | string
  level: number
  text: string
}

export interface AnalysisStats {
  modules: number
  points: number
  strong: number
  medium: number
  weak: number
}

export interface AnalysisView {
  resumeId: number
  modelName: string
  markdown: string
  blocks: Block[]
  modules: ModuleView[]
  stats: AnalysisStats
  createdAt: string
}

export interface InterviewSessionView {
  id: number
  resumeId: number
  status: 'PLANNED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'
  turnBudget: number
  questionCount: number
  followUpLimit: number
  currentTurn: number
  overallScore: number | null
  createdAt: string
  turns: InterviewTurnView[]
}

export interface InterviewTurnView {
  seq: number
  kind: 'MAIN' | 'FOLLOW_UP'
  parentSeq: number | null
  depth: number
  question: string
  skill: string
  difficulty: string
  answer: string | null
  score: number | null
  feedback: string | null
  evaluation: unknown
  review: unknown
  followUps: string[]
}

export interface InterviewQuestionView {
  seq: number
  kind: 'MAIN' | 'FOLLOW_UP'
  parentSeq: number | null
  depth: number
  question: string
  skill: string
  difficulty: string
  followUps: string[]
}

export interface InterviewAnswerResult {
  evaluatedTurn: InterviewTurnView
  nextQuestion: InterviewQuestionView | null
  status: InterviewSessionView['status']
  overallScore: number | null
  report: unknown
}

export interface InterviewSessionSummary {
  id: number
  resumeId: number
  status: InterviewSessionView['status']
  questionCount: number
  followUpLimit: number
  overallScore: number | null
  summary: string | null
  createdAt: string
}
