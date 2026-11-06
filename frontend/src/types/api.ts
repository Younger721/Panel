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

/** 原版预览：PDF 按页渲染，坐标系为 PDF 点、原点在页面左上。 */
export interface PreviewPage {
  index: number
  width: number
  height: number
}

export interface PreviewView {
  available: boolean
  reason: string | null
  pages: PreviewPage[]
}

export interface HighlightRect {
  x: number
  y: number
  width: number
  height: number
}

export interface HighlightView {
  page: number
  rects: HighlightRect[]
}

export interface QuoteRequest {
  pointId: string
  quote: string
  fallback: string | null
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

export interface TurnScores {
  accuracy: number
  depth: number
  completeness: number
  clarity: number
}

export interface TurnEvidence {
  quote: string
  note?: string | null
}

export interface TurnEvaluation {
  scores?: TurnScores | null
  evidence?: TurnEvidence[] | null
  deductions?: string[] | null
  feedback?: string | null
  answered?: boolean | null
}

export interface SkillScore {
  skill: string
  score: number
  comment: string
}

export interface InterviewReport {
  summary?: string
  strengths?: string[]
  improvements?: string[]
  skillScores?: SkillScore[]
}

export interface ReportPayload {
  report: InterviewReport
  overallScore: number
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
  evaluation: TurnEvaluation | null
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
  report: ReportPayload | null
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
