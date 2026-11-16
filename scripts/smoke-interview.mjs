#!/usr/bin/env node
/**
 * 端到端冒烟：登录 → 找一份已解析的简历 → 建面试 → 逐题回答 → 出报告。
 *
 *   node scripts/smoke-interview.mjs
 *   PANEL_BASE=http://127.0.0.1:8080 PANEL_USER=admin PANEL_PASS=123456 node scripts/smoke-interview.mjs
 *
 * 会真实调用大模型（一份 2 题 / 追问 1 次的面试约 1 分钟），用于发版前回归。
 */
const BASE = process.env.PANEL_BASE || 'http://127.0.0.1:8080'
const USER = process.env.PANEL_USER || 'admin'
const PASS = process.env.PANEL_PASS || '123456'
const QUESTIONS = Number(process.env.PANEL_QUESTIONS || 2)
const FOLLOW_UPS = Number(process.env.PANEL_FOLLOW_UPS || 1)

const ANSWER =
  '这块我在智能面试平台里做过：简历解析接多格式解析器，面试编排是 Planner 选题、Interviewer 出题、' +
  'Evaluator 带证据评分、Reviewer 只在一题多解冲突时介入。量化上，报告接口 P95 从 3.1s 降到 1.2s，' +
  '手段是把逐题评分改成并发调用并缓存简历画像；失败重试走 Kafka 重试队列，超过三次进死信。'

function die(message) {
  console.error(`✗ ${message}`)
  process.exit(1)
}

async function api(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, options)
  const text = await response.text()
  let payload
  try {
    payload = JSON.parse(text)
  } catch {
    die(`${path} 返回的不是 JSON：${text.slice(0, 120)}`)
  }
  if (!payload.success) {
    die(`${path} 失败：${payload.code} ${payload.message}`)
  }
  return payload.data
}

const login = await api('/api/v1/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USER, password: PASS })
})
const auth = { Authorization: `Bearer ${login.accessToken}` }
const json = { ...auth, 'Content-Type': 'application/json' }
console.log('✓ 登录')

const resumes = await api('/api/v1/interview/resumes', { headers: auth })
const resume = resumes.find(item => item.status === 'PARSED')
if (!resume) {
  die('没有已解析的简历，先上传一份')
}
const analysis = await api(`/api/v1/interview/resumes/${resume.id}/analysis`, { headers: auth })
console.log(
  `✓ 简历 #${resume.id}：${analysis.stats.modules} 模块 / ${analysis.stats.points} 小点` +
    `（强 ${analysis.stats.strong} 中 ${analysis.stats.medium} 弱 ${analysis.stats.weak}）`
)
if (analysis.stats.points === 0) {
  die('批注为空，面试没有考点可问')
}

const session = await api('/api/v1/interview/sessions', {
  method: 'POST',
  headers: json,
  body: JSON.stringify({ resumeId: resume.id, questionCount: QUESTIONS, followUpLimit: FOLLOW_UPS })
})
console.log(`✓ 面试 #${session.id}：${session.questionCount} 题 / 追问上限 ${session.followUpLimit}`)

let detail = session
let rounds = 0
while (detail.status === 'IN_PROGRESS' && rounds < QUESTIONS * (1 + FOLLOW_UPS) + 2) {
  rounds += 1
  const pending = detail.turns.find(turn => turn.answer === null)
  if (!pending) {
    die('会话进行中却没有待答题')
  }
  const result = await api(`/api/v1/interview/sessions/${session.id}/answers`, {
    method: 'POST',
    headers: json,
    body: JSON.stringify({ answer: ANSWER })
  })
  const kind = result.evaluatedTurn.kind === 'FOLLOW_UP' ? `追问 L${result.evaluatedTurn.depth}` : '主问题'
  console.log(`  · 第 ${result.evaluatedTurn.seq} 题（${kind}）得分 ${result.evaluatedTurn.score}`)
  detail = await api(`/api/v1/interview/sessions/${session.id}`, { headers: auth })
}

if (detail.status !== 'COMPLETED') {
  die(`面试没有正常结束，状态=${detail.status}`)
}
const report = await api(`/api/v1/interview/sessions/${session.id}/report`, { headers: auth })
const solved = detail.turns.filter(turn => turn.answer !== null).length
console.log(`✓ 结束：${solved} 轮，总分 ${detail.overallScore}`)
console.log(`✓ 报告：${report.report.summary?.slice(0, 60) || '（无摘要）'}…`)
console.log(`\nweb: ${BASE.replace(/:8080$/, ':5173')}/interview/${session.id}/report`)
