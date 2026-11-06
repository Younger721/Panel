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

/** 行内一段同字体的文字（中文楷体 + 数字 Arial 这种） */
export interface TextRun {
  text: string
  font?: string | null
  fontKey?: string | null
  size?: number
  bold?: boolean
}

/** 可原位编辑的一行文字：坐标原点为页面左上角，单位 PDF 点 */
export interface TextLine {
  page: number
  text: string
  x: number
  y: number
  width: number
  height: number
  /** 版面用的字高（行框/高亮条） */
  size: number
  /** 实际渲染字号（按全角字宽量的），还原字体大小时用它 */
  fontSize?: number
  /** PDF 里的字体名，例如 KaiTi / ArialMT；拿不到时为 null */
  font?: string | null
  /** PDF 里的原样字体名（带子集前缀，如 BAAAAA+KaiTi），对应 fonts 里的嵌入字体 */
  fontKey?: string | null
  bold?: boolean
  /** 行内按字体切开的段；整行同一个字体时为空 */
  runs?: TextRun[]
}

/** 简历里嵌入的一个字体文件，编辑框用 @font-face 装进浏览器 */
export interface PreviewFont {
  key: string
  /** 稳定的家族名（按字体内容哈希），写进 HTML 也认得出 */
  family: string
  format: string
  size: number
}

/** 板块在某一页上占的矩形（跨页的板块有多段） */
export interface TextPart {
  page: number
  x: number
  y: number
  width: number
  height: number
}

/** 一块可整体编辑的板块（实习经历、教育背景……） */
export interface TextSection {
  index: number
  title: string
  /** 标题那条分隔条的矩形；页首「个人信息」没有标题，为 null */
  heading: TextPart | null
  /** 正文区域，跨页的板块有多段（标题不在正文里） */
  parts: TextPart[]
  lines: TextLine[]
}

export interface TextLayerView {
  available: boolean
  reason: string | null
  pages: PreviewPage[]
  sections: TextSection[]
  /** 简历里嵌入的字体，编辑框按 key 关联到每一行 */
  fonts?: PreviewFont[]
  /** 已保存的板块改动：板块序号 → 富文本 HTML */
  edits: Record<string, string>
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
  /** 面试官人设 code：SERIOUS / WARM / PRESSURE */
  persona: string
  currentTurn: number
  overallScore: number | null
  createdAt: string
  turns: InterviewTurnView[]
}

export interface InterviewerPersonaView {
  code: string
  label: string
  title: string
  description: string
  followUpThreshold: number
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
