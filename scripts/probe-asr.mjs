/**
 * T0 探测脚本：直连百炼实时语音识别，验证协议、首字延迟、断句与 token 消耗。
 *
 * 不是产品代码，只用来在接后端之前把参数摸清：
 *   node scripts/probe-asr.mjs --file tmp/asr-sample.wav
 *   node scripts/probe-asr.mjs --file tmp/asr-sample.wav --model qwen-audio-3.1-asr-flash-streaming
 *
 * 输出：每帧事件的时间线 + 首字延迟 + 断句数 + usage。
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const WS_URL = 'wss://dashscope.aliyuncs.com/api-ws/v1/inference'
const FRAME_MS = 100

// ---------- 参数 ----------
function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}
const audioFile = arg('file', 'tmp/asr-sample.wav')
const model = arg('model', 'qwen-audio-3.1-asr-flash-message')
const silenceMs = Number(arg('max-silence', '1200'))
const vad = arg('vad', 'near_meeting_16k')
const speed = Number(arg('speed', '1')) // 1 = 实时，>1 加速回放

// ---------- API Key ----------
function readKey() {
  const envPath = path.resolve('.env')
  if (!fs.existsSync(envPath)) throw new Error('找不到 .env')
  const m = fs.readFileSync(envPath, 'utf8').match(/^\s*DASHSCOPE_API_KEY\s*=\s*(.+)$/m)
  if (!m || !m[1].trim()) throw new Error('.env 里没有 DASHSCOPE_API_KEY')
  return m[1].trim()
}

// ---------- WAV 解析 ----------
function readPcm(file) {
  const buf = fs.readFileSync(file)
  if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') {
    throw new Error('不是 WAV 文件')
  }
  let offset = 12
  let fmt = null
  let data = null
  while (offset + 8 <= buf.length) {
    const id = buf.toString('ascii', offset, offset + 4)
    const size = buf.readUInt32LE(offset + 4)
    const body = offset + 8
    if (id === 'fmt ') {
      fmt = {
        format: buf.readUInt16LE(body),
        channels: buf.readUInt16LE(body + 2),
        sampleRate: buf.readUInt32LE(body + 4),
        bits: buf.readUInt16LE(body + 14)
      }
    } else if (id === 'data') {
      data = buf.subarray(body, Math.min(body + size, buf.length))
    }
    offset = body + size + (size % 2)
  }
  if (!fmt || !data) throw new Error('WAV 缺少 fmt/data 块')
  if (fmt.format !== 1) throw new Error(`只支持 PCM（当前 format=${fmt.format}）`)
  if (fmt.sampleRate !== 16000) throw new Error(`实时接口只收 16k（当前 ${fmt.sampleRate}）`)
  return { ...fmt, data, durationMs: Math.round((data.length / (fmt.sampleRate * fmt.channels * (fmt.bits / 8))) * 1000) }
}

// ---------- 主流程 ----------
const key = readKey()
const pcm = readPcm(audioFile)
const stats = { partials: 0, sentences: 0 }
const started = Date.now()
const mark = (label, extra = '') =>
  console.log(`[+${String(Date.now() - started).padStart(5)}ms] ${label}${extra ? ' ' + extra : ''}`)

console.log(`模型: ${model}`)
console.log(`音频: ${audioFile}  ${pcm.sampleRate}Hz ${pcm.channels}ch ${pcm.bits}bit  ${(pcm.durationMs / 1000).toFixed(2)}s`)
console.log(`参数: vad=${vad} max_sentence_silence=${silenceMs}ms intermediate=true\n`)

const taskId = crypto.randomUUID()
const ws = new WebSocket(WS_URL, { headers: { Authorization: `Bearer ${key}` } })
let audioTimer = null
let finishSentAt = 0
let finalUsage = null
let finalText = ''

const hardTimeout = setTimeout(() => {
  console.log('\n[超时] 60s 未收到 task-finished，强制退出')
  process.exit(2)
}, 60_000)

ws.onopen = () => {
  mark('WS 已连接', '→ 发 run-task')
  ws.send(JSON.stringify({
    header: { action: 'run-task', task_id: taskId, streaming: 'duplex' },
    payload: {
      task_group: 'audio',
      task: 'asr',
      function: 'recognition',
      model,
      parameters: {
        format: 'pcm',
        sample_rate: 16000,
        intermediate_result_enabled: true,
        disfluency_removal_enabled: true,
        vad_model: vad,
        max_sentence_silence: silenceMs,
        heartbeat: true
      },
      input: {}
    }
  }))
}

ws.onmessage = event => {
  if (typeof event.data !== 'string') return
  let msg
  try { msg = JSON.parse(event.data) } catch { return }

  const ev = msg.header?.event
  if (ev === 'task-started') {
    mark('task-started', '→ 开始推流')
    startStreaming()
    return
  }

  if (ev === 'result-generated') {
    const s = msg.payload?.output?.sentence ?? {}
    if (s.heartbeat) return
    const text = s.text ?? ''
    if (s.sentence_end === false) {
      stats.partials += 1
      mark(`partial  #${s.sentence_id}`, JSON.stringify(text))
    } else {
      stats.sentences += 1
      finalText += text
      mark(`SENTENCE #${s.sentence_id}`, `${(s.begin_time ?? 0)}–${(s.end_time ?? 0)}ms ${JSON.stringify(text)}`)
    }
    if (msg.payload?.usage) finalUsage = msg.payload.usage
    return
  }

  if (ev === 'task-finished') {
    finalUsage = msg.payload?.usage ?? finalUsage
    const waited = finishSentAt ? Date.now() - finishSentAt : 0
    clearTimeout(hardTimeout)
    mark('task-finished', `松手→定稿 ${waited}ms`)
    console.log('\n---------------- 结果 ----------------')
    console.log('全文:', finalText || '(空)')
    console.log('断句数:', stats.sentences, '| 中间结果数:', stats.partials)
    console.log('usage:', JSON.stringify(finalUsage))
    if (finalUsage?.total_tokens && pcm.durationMs) {
      const ratio = finalUsage.total_tokens / (pcm.durationMs / 1000)
      console.log(`token 密度: ${ratio.toFixed(1)} token/秒  →  1M token ≈ ${(1_000_000 / ratio / 3600).toFixed(1)} 小时音频`)
    }
    ws.close()
    process.exit(0)
  }

  if (ev === 'task-failed') {
    mark('TASK FAILED', JSON.stringify(msg.header?.error_code) + ' ' + JSON.stringify(msg.header?.error_message))
    clearTimeout(hardTimeout)
    process.exit(1)
  }
}

ws.onerror = e => {
  console.log('[WS error]', e?.message ?? '(无 message，通常是握手被拒)')
}

ws.onclose = e => {
  if (e.code !== 1000) console.log(`[WS close] code=${e.code} reason=${e.reason}`)
}

function startStreaming() {
  const bytesPerFrame = pcm.sampleRate * pcm.channels * (pcm.bits / 8) * (FRAME_MS / 1000) // 3200
  let offset = 0
  audioTimer = setInterval(() => {
    if (offset >= pcm.data.length) {
      clearInterval(audioTimer)
      finishSentAt = Date.now()
      mark('音频推完 → finish-task')
      ws.send(JSON.stringify({
        header: { action: 'finish-task', task_id: taskId, streaming: 'duplex' },
        payload: { input: {} }
      }))
      return
    }
    const chunk = pcm.data.subarray(offset, offset + bytesPerFrame)
    offset += bytesPerFrame
    // 等 task-started 之后再发，避免首批帧被丢
    if (ws.readyState === WebSocket.OPEN) ws.send(chunk)
  }, FRAME_MS / speed)
}
