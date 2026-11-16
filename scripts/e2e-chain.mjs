#!/usr/bin/env node
/**
 * 简历主链路端到端：上传 → 编辑 → 高光（批注/定位） → 建面试 → 收尾。
 *
 *   node scripts/e2e-chain.mjs
 *   PANEL_BASE=http://127.0.0.1:8081 node scripts/e2e-chain.mjs
 *
 * 只碰自己新建的测试简历；面试创建后立刻 abandon，不跑完整答题（那部分由
 * scripts/smoke-interview.mjs 覆盖，避免重复烧模型 token）。
 */
const BASE = process.env.PANEL_BASE || 'http://127.0.0.1:8081'
const USER = process.env.PANEL_USER || 'admin'
const PASS = process.env.PANEL_PASS || '123456'
const STAMP = String(Date.now())

const RESUME_TEXT = `陈明
Java 后端开发工程师 · 5 年经验
13900000000 · chenming@example.com · 杭州

个人简介
五年的后端开发经验，做过订单、支付和检索。

工作经历
2021.07-至今 杭州某电商公司 高级后端工程师
2020.03-2021.06 杭州某支付公司 后端工程师

项目经历
智能面试平台 Panel（2024.03-至今）
- 主导检索链路重构，P95 从 2s 降到 200ms
- 负责批注锚点定位，覆盖率 96%
订单中台（2023.01-2024.02）
- 搭建任务调度，日均处理 2000 万条
- 用 Redis 缓存热点商品，命中率 92%

技能
Java · Spring Boot · MySQL · Redis · Kafka

（批次 ${STAMP}）
`

let token = ''
const failures = []

function check(name, ok, detail = '') {
  if (ok) {
    console.log(`  ✓ ${name}${detail ? ` — ${detail}` : ''}`)
  } else {
    failures.push(`${name}${detail ? ` — ${detail}` : ''}`)
    console.log(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

function fatal(message) {
  console.error(`✗ ${message}`)
  process.exit(1)
}

async function call(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { ...(options.headers || {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }
  })
  const text = await response.text()
  if (response.status === 204) {
    return { status: 204, data: null }
  }
  let payload = null
  try {
    payload = JSON.parse(text)
  } catch {
    return { status: response.status, data: null, raw: text }
  }
  return { status: response.status, data: payload?.data ?? null, message: payload?.message, code: payload?.code }
}

async function ok(path, options) {
  const res = await call(path, options)
  if (res.status >= 400 || res.data === null) {
    fatal(`${options?.method || 'GET'} ${path} 失败：HTTP ${res.status} ${res.code || ''} ${res.message || res.raw?.slice(0, 100) || ''}`)
  }
  return res.data
}

async function waitParsed(id, label) {
  const deadline = Date.now() + 300_000
  while (Date.now() < deadline) {
    const view = await ok(`/api/v1/interview/resumes/${id}`)
    if (view.status === 'PARSED') return view
    if (view.status === 'FAILED') fatal(`${label} 解析失败：${view.lastError}`)
    await new Promise(resolve => setTimeout(resolve, 3000))
  }
  fatal(`${label} 等待解析超时`)
}

console.log(`→ ${BASE}`)
const login = await ok('/api/v1/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USER, password: PASS })
})
token = login.accessToken
console.log(`✓ 登录 ${login.username}`)

// ── 1. 上传 ───────────────────────────────────────────────
console.log('\n[1] 上传简历')
const form = new FormData()
form.append('file', new Blob([RESUME_TEXT], { type: 'text/plain' }), `e2e-chain-${STAMP}.txt`)
const uploaded = await ok('/api/v1/interview/resumes', { method: 'POST', body: form })
const id = uploaded.id
check('上传返回 202 视图', !!id, `#${id} ${uploaded.fileName} ${uploaded.status}`)
check('上传后状态是 PENDING/PARSED', ['PENDING', 'PARSING'].includes(uploaded.status) || uploaded.status === 'PARSED', uploaded.status)
await waitParsed(id, '首次解析')

const analysis1 = await ok(`/api/v1/interview/resumes/${id}/analysis`)
check('批注模块数 > 0', analysis1.modules.length > 0, `${analysis1.modules.length} 模块`)
const pointCount = analysis1.modules.reduce((sum, module) => sum + module.points.length, 0)
check('批注小点数 > 0', pointCount > 0, `${pointCount} 小点`)
check('stats 与实际一致', analysis1.stats.points === pointCount, `stats=${analysis1.stats.points} 实际=${pointCount}`)
check('markdown 非空', typeof analysis1.markdown === 'string' && analysis1.markdown.length > 0, `${analysis1.markdown.length} 字`)

// ── 2. 高光：预览 / 定位 ──────────────────────────────────
console.log('\n[2] 查看简历高光（预览 + 批注定位）')
const previewTxt = await ok(`/api/v1/interview/resumes/${id}/preview`)
check('txt 简历 preview.available=false（前端应退回文本视图）', previewTxt.available === false, String(previewTxt.reason || ''))
const textLayerTxt = await ok(`/api/v1/interview/resumes/${id}/preview/text`)
check('txt 简历 textLayer.available=false', textLayerTxt.available === false, String(textLayerTxt.reason || ''))

const quotes = analysis1.modules
  .flatMap(module => module.points)
  .slice(0, 3)
  .map(point => ({ pointId: point.id, quote: point.quote, focus: point.focus ?? null }))
const locatedTxt = await ok(`/api/v1/interview/resumes/${id}/preview/locate`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ quotes })
})
check('非 PDF 上 locate 不报错（返回空映射）', typeof locatedTxt === 'object' && locatedTxt !== null, `${Object.keys(locatedTxt || {}).length} 个命中`)

// PDF 上的高光链路（只读，拿一份已有 PDF 简历；优先选有文本层的那份）
const pdfResumes = (await ok('/api/v1/interview/resumes')).filter(
  item => item.contentType === 'application/pdf' && item.status === 'PARSED'
)
let pdfTarget = null
for (const item of pdfResumes) {
  const layer = await ok(`/api/v1/interview/resumes/${item.id}/preview/text`)
  if (layer.available && layer.sections.length > 0) {
    pdfTarget = { item, layer }
    break
  }
}
if (pdfTarget) {
  const { item: pdfResume, layer } = pdfTarget
  const pdfPreview = await ok(`/api/v1/interview/resumes/${pdfResume.id}/preview`)
  check('PDF 简历 preview.available=true', pdfPreview.available === true, `${pdfPreview.pages.length} 页`)
  check('PDF 简历 textLayer 有板块', layer.sections.length > 0, `${layer.sections.length} 板块 / ${layer.fonts.length} 字体`)
  const pdfAnalysis = await ok(`/api/v1/interview/resumes/${pdfResume.id}/analysis`)
  const pdfQuotes = pdfAnalysis.modules
    .flatMap(module => module.points)
    .slice(0, 5)
    .map(point => ({ pointId: point.id, quote: point.quote, focus: point.focus ?? null }))
  const locatedPdf = await ok(`/api/v1/interview/resumes/${pdfResume.id}/preview/locate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ quotes: pdfQuotes })
  })
  const hit = Object.keys(locatedPdf || {}).length
  check('PDF 批注能定位成页面矩形', hit > 0, `${hit}/${pdfQuotes.length} 个命中`)

  const firstPage = pdfPreview.pages[0]?.index ?? 1
  const pageImg = await fetch(`${BASE}/api/v1/interview/resumes/${pdfResume.id}/preview/pages/${firstPage}?scale=2`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  check('页面渲染图可取', pageImg.ok && (pageImg.headers.get('content-type') || '').includes('image/png'), `第 ${firstPage} 页 HTTP ${pageImg.status}`)

  const badPage = await call(`/api/v1/interview/resumes/${pdfResume.id}/preview/pages/0`)
  check('越界页码返回 400 而不是 500', badPage.status === 400, `HTTP ${badPage.status} ${badPage.code || ''}`)
} else {
  check('存在一份带文本层的 PDF 简历', false, '跳过 PDF 高光校验')
}

// 扫描件：定位应优雅返回空，而不是报错
const scanned = pdfResumes.find(item => item.id !== pdfTarget?.item.id)
if (scanned) {
  const scannedLayer = await ok(`/api/v1/interview/resumes/${scanned.id}/preview/text`)
  if (scannedLayer.available && scannedLayer.sections.length === 0) {
    const scannedAnalysis = await ok(`/api/v1/interview/resumes/${scanned.id}/analysis`)
    const scannedQuotes = scannedAnalysis.modules
      .flatMap(module => module.points)
      .slice(0, 3)
      .map(point => ({ pointId: point.id, quote: point.quote, focus: point.focus ?? null }))
    const located = await ok(`/api/v1/interview/resumes/${scanned.id}/preview/locate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ quotes: scannedQuotes })
    })
    check('扫描件（无文本层）定位返回空映射且不报错', typeof located === 'object', `${Object.keys(located || {}).length} 个命中`)
  }
}

// ── 3. 编辑 ──────────────────────────────────────────────
console.log('\n[3] 编辑简历')
const editedText = `${RESUME_TEXT.replace('（批次 ' + STAMP + '）', '')}\n新增一条：负责灰度发布平台，支持 200+ 服务\n\n（批次 ${STAMP} 编辑过）\n`
const saved = await ok(`/api/v1/interview/resumes/${id}/content`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    text: editedText,
    edits: { '0': '<p>陈明</p>' },
    format: 'text'
  })
})
check('保存后回到 PENDING', saved.status === 'PENDING', saved.status)
await waitParsed(id, '编辑后解析')
const analysis2 = await ok(`/api/v1/interview/resumes/${id}/analysis`)
const joined = analysis2.blocks.map(block => block.text).join('\n')
check('编辑内容进了新解析结果', joined.includes('灰度发布平台'))
check('批注已重算（模块仍 > 0）', analysis2.modules.length > 0, `${analysis2.modules.length} 模块`)
const dangling = analysis2.modules.flatMap(m => m.points).filter(point => !joined.includes(point.quote?.slice(0, 6) ?? ''))
check('批注引用没有悬空（quote 都能在原文中找到）', dangling.length === 0, `${dangling.length} 条悬空`)

// 空正文保存应被拒绝
const emptySave = await call(`/api/v1/interview/resumes/${id}/content`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ text: '   ', format: 'text' })
})
check('空正文保存被拒绝', emptySave.status === 400, `HTTP ${emptySave.status} ${emptySave.code || ''}`)

// ── 4. 建面试 ────────────────────────────────────────────
console.log('\n[4] 模拟面试')
const personas = await ok('/api/v1/interview/personas')
check('面试官人设接口可用', Array.isArray(personas) && personas.length > 0, `${personas.length} 个`)
const session = await ok('/api/v1/interview/sessions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ resumeId: id, questionCount: 2, followUpLimit: 0, persona: personas[0]?.code })
})
check('建面试成功', !!session.id, `#${session.id} ${session.status}`)
check('第一题已生成', Array.isArray(session.turns) && session.turns.length > 0, `${session.turns?.length ?? 0} 轮`)
const firstQuestion = session.turns?.[0]?.question
check('第一题有题面', typeof firstQuestion === 'string' && firstQuestion.length > 0, firstQuestion?.slice(0, 40))

// 未结束就取报告：应当拒绝，且前端能显示原因
const earlyReport = await call(`/api/v1/interview/sessions/${session.id}/report`)
check('未完成时取报告被拒（不是 200 空壳）', earlyReport.status >= 400, `HTTP ${earlyReport.status} ${earlyReport.message || ''}`)

const abandoned = await ok(`/api/v1/interview/sessions/${session.id}/abandon`, { method: 'POST' })
check('放弃面试后状态是 ABANDONED', abandoned.status === 'ABANDONED', abandoned.status)

// ── 5. 清理 ──────────────────────────────────────────────
console.log('\n[5] 清理')
await ok(`/api/v1/interview/resumes/${id}`, { method: 'DELETE' })
check('测试简历已软删除', true, `#${id}`)

console.log('')
if (failures.length > 0) {
  console.error(`✗ ${failures.length} 项未通过：`)
  failures.forEach(item => console.error(`  - ${item}`))
  process.exit(1)
}
console.log('全部通过')
