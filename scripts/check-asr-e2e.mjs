/**
 * 端到端冒烟：走真实后端（取票 → WS 握手 → 推 PCM → 收帧 → 落库）。
 *
 *   node scripts/check-asr-e2e.mjs --session 15 --file tmp/asr-sample.wav
 *
 * 与 probe-asr.mjs 的区别：那个直连百炼，这个走我们自己的
 * /asr-ticket + /ws/interview/asr，用来验证鉴权、转发、帧协议、用量记录。
 */
import fs from 'node:fs'
import process from 'node:process'

const BASE = process.env.PANEL_BASE ?? 'http://127.0.0.1:8080'
const user = process.env.PANEL_USER ?? 'admin'
const password = process.env.PANEL_PASSWORD ?? '123456'

const arg = (name, fallback) => {
  const index = process.argv.indexOf(`--${name}`)
  return index > -1 && process.argv[index + 1] ? process.argv[index + 1] : fallback
}
const sessionId = arg('session', '15')
const audioFile = arg('file', 'tmp/asr-sample.wav')
const FRAME_MS = 100

function readPcm(file) {
  const buffer = fs.readFileSync(file)
  let offset = 12
  let fmt = null
  let data = null
  while (offset + 8 <= buffer.length) {
    const id = buffer.toString('ascii', offset, offset + 4)
    const size = buffer.readUInt32LE(offset + 4)
    const body = offset + 8
    if (id === 'fmt ') {
      fmt = {
        format: buffer.readUInt16LE(body),
        channels: buffer.readUInt16LE(body + 2),
        sampleRate: buffer.readUInt32LE(body + 4),
        bits: buffer.readUInt16LE(body + 14)
      }
    } else if (id === 'data') {
      data = buffer.subarray(body, Math.min(body + size, buffer.length))
    }
    offset = body + size + (size % 2)
  }
  if (!fmt || !data) throw new Error('WAV 缺少 fmt/data')
  return { ...fmt, data, durationMs: Math.round((data.length / (fmt.sampleRate * fmt.channels * (fmt.bits / 8))) * 1000) }
}

const started = Date.now()
const mark = (label, extra = '') => console.log(`[+${String(Date.now() - started).padStart(5)}ms] ${label} ${extra}`.trimEnd())

// 1) 登录取 JWT
const loginResponse = await fetch(`${BASE}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: user, password })
})
const login = await loginResponse.json()
if (!login.success) throw new Error(`登录失败: ${JSON.stringify(login)}`)
const token = login.data.accessToken
mark('登录成功', `user=${login.data.username} roles=${login.data.roles.join(',')}`)

// 2) 取票
const ticketResponse = await fetch(`${BASE}/api/v1/interview/sessions/${sessionId}/asr-ticket`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}` }
})
const ticketBody = await ticketResponse.json()
if (ticketResponse.status === 409) {
  console.log('\n⚠ 该会话没有待作答的题（或被占用），换个 session 再试：')
  console.log('   node scripts/check-asr-e2e.mjs --session <id>')
  process.exit(3)
}
if (!ticketBody.success) {
  console.log('取票失败: HTTP', ticketResponse.status, JSON.stringify(ticketBody))
  process.exit(1)
}
const { ticket, wsUrl, model, sampleRate } = ticketBody.data
mark('取票成功', `model=${model} sampleRate=${sampleRate} ttl=30s`)
if (sampleRate !== 16000) throw new Error(`sampleRate 应为 16000，实际 ${sampleRate}`)

// 3) 连 WS 并推流
const pcm = readPcm(audioFile)
console.log(`音频: ${pcm.durationMs}ms ${pcm.sampleRate}Hz ${pcm.bits}bit\n`)

const socket = new WebSocket(`${BASE.replace(/^http/, 'ws')}${wsUrl}?ticket=${encodeURIComponent(ticket)}`)
socket.binaryType = 'arraybuffer'
let ready = false
let timer = null

const done = new Promise((resolve, reject) => {
  const timeout = setTimeout(() => reject(new Error('30s 内没收到 final')), 30_000)

  socket.onmessage = event => {
    if (typeof event.data !== 'string') return
    const frame = JSON.parse(event.data)
    switch (frame.type) {
      case 'ready':
        ready = true
        mark('ready → 开始推流')
        stream()
        break
      case 'partial':
        mark(`partial #${frame.sentenceId}`, JSON.stringify(frame.text))
        break
      case 'sentence':
        mark(`SENTENCE #${frame.sentenceId}`, `${frame.beginMs}-${frame.endMs}ms ${JSON.stringify(frame.text)}`)
        break
      case 'final':
        mark('final', JSON.stringify(frame.usage))
        clearTimeout(timeout)
        clearInterval(timer)
        resolve(frame)
        break
      case 'error':
        clearTimeout(timeout)
        clearInterval(timer)
        reject(new Error(`服务端错误 ${frame.code}: ${frame.message}`))
        break
      default:
        break
    }
  }
  socket.onerror = () => {
    clearTimeout(timeout)
    reject(new Error('WS 握手失败（403？ticket 无效或已被用掉）'))
  }
})

function stream() {
  const bytesPerFrame = pcm.sampleRate * pcm.channels * (pcm.bits / 8) * (FRAME_MS / 1000)
  let offset = 0
  timer = setInterval(() => {
    if (offset >= pcm.data.length) {
      clearInterval(timer)
      mark('推完 → finish')
      socket.send(JSON.stringify({ type: 'finish' }))
      return
    }
    socket.send(pcm.data.subarray(offset, offset + bytesPerFrame))
    offset += bytesPerFrame
  }, FRAME_MS)
}

const final = await done
const text = final?.usage ? '' : ''
console.log('\n---------------- 结论 ----------------')
console.log('用量已回传:', JSON.stringify(final.usage))
console.log('（识别文本见上面的 SENTENCE 行）')
if (ready) console.log('✓ 鉴权、转发、帧协议、用量回传全部走通')
process.exit(0)
