#!/usr/bin/env node
/**
 * Markdown 双源端到端验证（只碰自己新建的测试简历，不动已有数据）。
 *
 *   node tmp/e2e-markdown-source.mjs
 *
 * 覆盖：
 *   1. md 上传 → 解析 → 章节/列表结构识别
 *   2. format=markdown 保存 → 读回「用户亲手写的原文」而不是渲染结果
 *   3. 列表前缀 / 标题层级往返不漂移
 *   4. format=text 保存 → md 稿指针被清掉（不然下次解析会读回旧稿）
 *   5. 软删除清理
 */
const BASE = process.env.PANEL_BASE || 'http://127.0.0.1:8081'
const USER = process.env.PANEL_USER || 'admin'
const PASS = process.env.PANEL_PASS || '123456'

const STAMP = process.env.PANEL_E2E_STAMP || String(Date.now())

const ORIGINAL = `# 张三

Java 后端开发工程师 · 4 年经验
13800000000 · zhangsan@example.com · 上海

## 个人简介

四年的后端经验，主要做高并发检索与异步任务编排。（批次 ${STAMP}）

## 项目经历

### 智能面试平台 Panel | 2024.03 – 至今
- 主导检索链路重构，P95 从 2s 降到 200ms
- 负责批注锚点定位，覆盖率 96%

### 数据同步中台 | 2023.01 – 2024.02
- 搭建任务调度，日均处理 2000 万条

## 技能

Java · Spring Boot · MySQL · Redis
`

const EDITED = ORIGINAL.replace(
  '### 数据同步中台 | 2023.01 – 2024.02\n- 搭建任务调度，日均处理 2000 万条',
  '### 数据同步中台 | 2023.01 – 2024.02\n- 搭建任务调度，日均处理 2000 万条\n- 新增一条：故障自动降级，重试三次进死信'
)

let token = ''

function fail(message) {
  console.error(`✗ ${message}`)
  process.exit(1)
}

async function api(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { ...(options.headers || {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }
  })
  const text = await response.text()
  if (response.status === 204) {
    return { status: 204, data: null }
  }
  let payload
  try {
    payload = JSON.parse(text)
  } catch {
    fail(`${path} 返回的不是 JSON（HTTP ${response.status}）：${text.slice(0, 120)}`)
  }
  if (!payload.success) {
    fail(`${path} 失败：HTTP ${response.status} ${payload.code} ${payload.message}`)
  }
  return { status: response.status, data: payload.data }
}

/** 纯文本接口（content/markdown）：不做 JSON 解析 */
async function rawText(path) {
  const response = await fetch(`${BASE}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  const text = await response.text()
  return { status: response.status, text }
}

async function waitParsed(id, label) {
  const deadline = Date.now() + 240_000
  let last = ''
  while (Date.now() < deadline) {
    const { data } = await api(`/api/v1/interview/resumes/${id}`)
    if (data.status !== last) {
      last = data.status
      console.log(`  · ${label} 状态 ${data.status}`)
    }
    if (data.status === 'PARSED') return data
    if (data.status === 'FAILED') fail(`${label} 解析失败：${data.lastError}`)
    await new Promise(resolve => setTimeout(resolve, 3000))
  }
  fail(`${label} 等待解析超时`)
}

console.log(`→ ${BASE}`)

const login = await api('/api/v1/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USER, password: PASS })
})
token = login.data.accessToken
console.log(`✓ 登录 ${login.data.username}`)

// 1. 上传一份 md 测试简历
const form = new FormData()
form.append('file', new Blob([ORIGINAL], { type: 'text/markdown' }), 'e2e-md-resume.md')
const upload = await api('/api/v1/interview/resumes', { method: 'POST', body: form })
const id = upload.data.id
console.log(`✓ 上传 md 测试简历 #${id}`)

const parsed = await waitParsed(id, '首次解析')
console.log(`✓ 解析完成（${parsed.fileName}，${parsed.contentType}）`)

// 2. 还没用 md 改过：内容稿接口应返回 204
const empty = await rawText(`/api/v1/interview/resumes/${id}/content/markdown`)
if (empty.status !== 204) fail(`新建记录不该有 md 稿，实际 HTTP ${empty.status}`)
console.log('✓ 无 md 稿时 /content/markdown = 204')

const analysis = (await api(`/api/v1/interview/resumes/${id}/analysis`)).data
const types = analysis.blocks.map(block => block.type)
const titles = analysis.blocks.filter(block => block.type === 'TITLE').map(block => block.text)
const listItems = analysis.blocks.filter(block => block.type === 'LIST_ITEM').map(block => block.text)
console.log(`  · blocks=${types.length} TITLE=${titles.length} LIST_ITEM=${listItems.length}`)
console.log(`  · 标题：${titles.join(' / ')}`)
if (!analysis.markdown.startsWith('# 张三')) {
  fail(`渲染出的 markdown 标题层级不对：${analysis.markdown.slice(0, 40)}`)
}
if (listItems.length === 0) {
  fail('没解析出任何列表项，章节结构丢了（多半是被当成了纯文本）')
}
if (listItems.some(item => item.startsWith('- '))) {
  fail(`列表项文本里还带着 - 前缀，渲染会变成 - - x：${JSON.stringify(listItems[0])}`)
}
if (!analysis.markdown.includes('- 主导检索链路重构')) {
  fail('渲染出的 markdown 里列表前缀丢了')
}

// 3. 用 Markdown 改一版
const save = await api(`/api/v1/interview/resumes/${id}/content`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ text: EDITED, format: 'markdown' })
})
if (save.data.status !== 'PENDING') fail(`保存后状态应为 PENDING，实际 ${save.data.status}`)
console.log('✓ markdown 保存被接受（202 / PENDING）')
await waitParsed(id, '第二次解析')

// 4. 读回原文：必须是我写的那份，而不是渲染后规范化的版本
const readBack = await rawText(`/api/v1/interview/resumes/${id}/content/markdown`)
if (readBack.status !== 200) fail(`读 md 原文失败，HTTP ${readBack.status}`)
const stored = readBack.text
if (stored.trim() !== EDITED.trim()) fail('读回的 md 与保存的不一致（说明读了渲染结果或丢了格式）')
console.log('✓ 读回用户原文，逐字一致')

// 5. 解析结果里新加的那条要点要进 LIST_ITEM
const after = (await api(`/api/v1/interview/resumes/${id}/analysis`)).data
const hasNew = after.blocks.some(block => block.text.includes('故障自动降级'))
if (!hasNew) fail('新加的要点没进解析结果')
const newItem = after.blocks.find(block => block.text.includes('故障自动降级'))
if (!newItem.type.endsWith('LIST_ITEM') && !newItem.type.endsWith('PARAGRAPH')) {
  fail(`新要点的块类型异常：${newItem.type}`)
}
console.log(`✓ 新要点已解析（${newItem.type}：${newItem.text.slice(0, 24)}…）`)
if (/(^|\n)\s*-\s+-\s/.test(after.markdown)) fail('渲染出的列表前缀叠加了（- -）')
console.log('✓ 多轮保存后列表前缀没有叠加')

// 6. 回到纯文本通路：md 稿必须被清掉
const toText = await api(`/api/v1/interview/resumes/${id}/content`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ text: '张三\n项目经历\n- 纯文本通路保存', format: 'text' })
})
if (toText.data.status !== 'PENDING') fail('text 保存后状态应为 PENDING')
await waitParsed(id, '第三次解析')
const cleared = await rawText(`/api/v1/interview/resumes/${id}/content/markdown`)
if (cleared.status !== 204) fail(`text 保存后 md 稿应被清空，实际 HTTP ${cleared.status}`)
console.log('✓ 纯文本保存清掉了 md 稿（不会读回旧内容）')

// 7. 清理
await api(`/api/v1/interview/resumes/${id}`, { method: 'DELETE' })
console.log(`✓ 已软删除测试简历 #${id}`)

// 8. 顺带确认已有 PDF 简历的新端点也通（只读）
const existing = (await api('/api/v1/interview/resumes')).data.filter(item => item.contentType === 'application/pdf')
let checkedPdf = 0
for (const item of existing.slice(0, 2)) {
  const res = await rawText(`/api/v1/interview/resumes/${item.id}/content/markdown`)
  if (res.status !== 204) fail(`PDF 简历 #${item.id} 不该有 md 稿，实际 HTTP ${res.status}`)
  checkedPdf += 1
}
console.log(`✓ 已有 PDF 简历（${checkedPdf} 份）新端点正常，且未被改动`)

console.log('\n全部通过')
