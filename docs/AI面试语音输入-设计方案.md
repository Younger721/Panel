# AI 面试 · 语音输入设计方案（流式版）

> 状态：**T0 已实测通过**（`scripts/probe-asr.mjs`，数据见 §16），待评审后进入 T1
> 需求：**按住空格说话 → 边说边看到识别文本 → 松手定稿 → 文本落进作答框可编辑**
> 变更：原方案是「松手后一次性识别」，2026-11-08 需求确认要「边讲边看」，改为**流式实时识别**；
> 一次性方案降级为兜底路径（§14）。

---

## 1. 目标与非目标

### 目标
1. 说话过程中 **300–500ms 内看到第一批字**，松手后 **800ms 内定稿**。
2. 中间文本只做「展示」，定稿文本才进作答框；落框后可编辑，再走现有 `answerStream`。
3. 复用百炼 key、配额、计量、审计；面试模块仍然不认识百炼。
4. 语音链路挂了，手打照常（降级是常态，不是意外）。

### 非目标（v1）
- 自动判停后直接提交（松手即提交）
- 语速/停顿/口头禅分析（要留音频，v1.1）
- 移动端、多说话人分离、方言专项

---

## 2. 现状盘点

| 已有 | 位置 | 与本设计关系 |
|---|---|---|
| 作答框 + ⌘/Ctrl+Enter 提交 | `frontend/src/pages/InterviewPage.tsx` | 定稿文本的落点，提交流程零改动 |
| 面试官 TTS 播放器 | `frontend/src/hooks/useInterviewerSpeech.ts` | 录音前 `stop()` 打断，避免录进自己播的声音 |
| TTS 接口（只念库里已有文本） | `interview/api/SpeechController.java` | 防刷范式参考 |
| 百炼原生端点适配器 | `ai/infrastructure/bailian/BailianSpeechAdapter.java` | ASR 适配器照这个风格写 |
| 每日配额范式 | `ocr/application/DefaultOcrService#ensureDailyQuota` | 直接照搬 |
| 租户限流 | `shared/security/TenantRateLimitFilter` | 租户维度已有，ASR 再加用户维度 |
| 依赖 | `pom.xml` 只有 `starter-web` | **需新增 `spring-boot-starter-websocket`** |

**缺口**：没有音频采集、没有 ASR 端口、没有 WebSocket 基础设施、没有转写记录。
**已存在的洞**：面试链路（TTS + LLM）都不写 `ai_usage_record`，租户配额管不到（§15 附）。

---

## 3. 模型与协议（2026-11 核对官方文档）

### 3.1 实时模型清单

| 模型 | 采样率 | 格式 | 时长 | 中间结果 | 热词/上下文 | 结论 |
|---|---|---|---|---|---|---|
| `qwen-audio-3.1-asr-flash-message` | **仅 16k** | pcm/wav/mp3/opus(Ogg)/speex/aac/amr | 不限 | ✅（需显式开） | ✅ 即时热词 + 对话上下文 | **选它** |
| `qwen-audio-3.1-asr-flash-streaming` | 任意 | 同上 | 不限 | ✅ | ✅ 热词 + Prompt 上下文 | 备选（长时转写） |
| `qwen3-asr-flash-realtime` | 8k/16k | pcm/opus | 不限 | ✅ | ❌ | 需要情感识别时 |
| `fun-asr-realtime` | 任意 | 同第一行 | 不限 | ✅ | ✅ 热词 | 性价比备选 |
| `paraformer-realtime-v2` | 任意 | 同上 | 不限 | ✅ | ✅ | 官方建议迁移，不用 |

选 `-message` 的理由：官方定位就是「输入法、语音消息等短段语音输入」，首字和整体延迟最低，支持**文本润色**（去语气词、自我纠正），并且支持即时热词 + 上下文——正好对应「候选人一句一句说」的场景。

### 3.2 价格与限流（北京，官方模型页核对）

| 模型 | 输入 | 输出 | RPM |
|---|---|---|---|
| `qwen-audio-3.1-asr-flash-message` | 6 元/百万 token | 4.5 元/百万 token | **1200** |
| `qwen-audio-3.1-asr-flash-streaming` | 6 元/百万 token | 4.5 元/百万 token | **1200** |

- **没有单独的「并发连接数」指标**，官方只给 RPM。对 WebSocket 而言，一次 `run-task` 算一次请求，1200 RPM ≈ 20 次/秒建连；面试场景（一人一次说一句）远达不到。
- 免费额度：**每个模型各 1M token，独立计算**，控制台显示 2026-12-21 到期。实测 token 密度 14.6–15.5 token/秒音频，1M token ≈ **18–19 小时音频** ≈ 1100 段 60 秒回答（详见 §16）。
- 控制台开了「**免费额度用尽即停**」：额度耗尽后返回 `403 AllocationQuota.FreeTierOnly`，服务直接停，不会自动转后付费。代码必须把这条映射成**降级**，不能当成普通 500。

### 3.3 协议：三段式 task

```
客户端 → 百炼
  run-task      {header:{action,task_id(UUID),streaming:"duplex"},
                 payload:{task_group:"audio",task:"asr",function:"recognition",
                          model:"qwen-audio-3.1-asr-flash-message",
                          parameters:{format:"pcm",sample_rate:16000,
                                      intermediate_result_enabled:true,
                                      disfluency_removal_enabled:true,
                                      vad_model:"near_meeting_16k",
                                      max_sentence_silence:1200,
                                      heartbeat:true,
                                      vocabulary:{"Kubernetes":5}},
                          input:{context:[...]}}}
  二进制音频帧   PCM 16k / 16bit / 单声道（每帧 100–200ms）
  continue-task 更新上下文
  finish-task   {header:{action:"finish-task",task_id,streaming:"duplex"},payload:{input:{}}}

百炼 → 客户端
  task-started
  result-generated  payload.output.sentence{ sentence_id, sentence_begin, sentence_end,
                                             begin_time, end_time, text, words[] }
                    payload.usage{duration, input_tokens, output_tokens, total_tokens}
  task-finished     payload.usage（本任务累计）
  task-failed       header.error_code / error_message
```

**三个必须显式设置的参数**（默认值都会让「边讲边看」失效或变差）：

| 参数 | 默认 | 必须设 | 后果 |
|---|---|---|---|
| `intermediate_result_enabled` | **false** | `true` | 不开就只在句子结束时出字，等于退回一次性 |
| `sample_rate` | 无 | `16000` | 只支持 16000，其他值直接失败 |
| `vad_model` | `far_field_meeting_16k` | `near_meeting_16k` | 笔记本麦克风是近场，远场模型断句偏保守 |

`max_sentence_silence` 默认 800ms：面试回答中间停顿常超过 0.8s，会被切成很多短句。调到 **1200ms** 减少碎句，代价是每句定稿晚 0.4s。

### 3.4 中间结果的处理规则（官方明确要求）

> 中间文本可能修订，应按 `sentence_id` 更新展示，**不要直接拼接**。

对应到 UI：
- `sentence_end=false` → **替换**当前句的展示文本（同一 `sentence_id` 覆盖）
- `sentence_end=true` → 该句定稿，**追加**到草稿，并把「当前句」清空
- `sentence_id` 从 1 递增；`heartbeat=true` 时 `sentence_id=0`，直接跳过

---

## 4. 关键决策

### D1 流式是基线，一次性是兜底
边讲边看意味着必须持续上传音频、持续收结果。v1 主路径是 WebSocket；HTTP 一次性识别保留为降级路径（§14），不做也行，但公司代理拦 WS 的情况并不罕见。

### D2 采集必须回到 AudioWorklet + 16k PCM
**这是被模型约束逼回来的**：
- `sample_rate` 只支持 16000；
- `opus` 要求 **Ogg 封装**，而 `MediaRecorder` 给的是 **webm** 封装，不匹配；
- 流式要求连续小帧，`MediaRecorder` 的 `timeslice` 分片不能独立解码。

所以：`getUserMedia` → `AudioWorklet` 拿 Float32 → 重采样到 16k（浏览器常给 48k，必须显式重采样） → Int16 → 每 100ms 一帧（3.2KB）→ WS 二进制帧。60 秒 ≈ 1.92MB，全程分帧发送，不占内存峰值。

### D3 必须后端代理（硬约束，不是偏好）
浏览器的 `WebSocket` API **无法设置 `Authorization` 头**，而百炼 WS 握手要求 Bearer 鉴权；把 JWT 塞进 query string 又会被代理/访问日志留痕。

所以链路只能是：浏览器 WS → 我们后端 → 百炼 WS。顺带把配额、计量、脱敏、降级都放在这一层，一举多得。

### D4 鉴权用一次性 ticket
浏览器 WS 首帧之前没有可靠的鉴权手段，所以：
`POST /api/v1/interview/sessions/{id}/asr-ticket` → 返回 `ticket`（Redis，30s TTL，**一次一用**，绑定 `userId + sessionId`）→ 前端 `ws://.../ws/interview/asr?ticket=...` → 握手拦截器 `GETDEL` 校验。
ticket 泄露也只在 30 秒内、只能开一条连接，且必须已登录才能签发。

### D5 中间文本只读，定稿才落框
- 中间结果放在作答框**上方**的一层「幽灵文本」里（浅灰、`aria-live="polite"`），不写进 textarea；
- 每句定稿后才 append 到 textarea；
- 理由：中间文本会反复修订，写进 textarea 会让用户的撤销栈和光标位置乱掉，也会让人以为「已经写好了」。

### D6 空格冲突：显式「语音模式」开关
作答框是 `autoFocus` 的 textarea，空格既是说话键又是空格字符，中文输入法还用空格上屏。

| 方案 | 问题 |
|---|---|
| 全局空格 + 聚焦时禁用 | textarea 永远聚焦 → 永远禁用 |
| 语音模式下空格全吞 | 英文打不出空格、输入法不能上屏 |
| **显式语音模式开关（默认开）** | 多一个状态位，但语义清晰、可退出 | ✅ |
| `Alt+Space` 组合键 | 零冲突，但肌肉记忆弱；`Ctrl+Space` 常被输入法占用 |

采用「开关为主 + `Alt+Space` 兜底」。实现要点，缺一个就出 bug：
- 判定用 `event.code === 'Space'`，不用 `key`（输入法/布局差异）；
- `event.repeat` 直接 return（长按会连发 keydown，否则疯狂重连）；
- `event.isComposing` 为 true 时不触发（中文输入法正在上屏）；
- 语音模式下对 textarea 的 `keydown` 做 `preventDefault()`；
- `window.blur` / `visibilitychange` 兜底收尾（失焦后 `keyup` 收不到，会一直录）；
- 提交中或面试官说话时按空格：先 `speech.stop()` 打断 TTS，再开录。

### D7 打断优先：录音 > TTS
按住空格 = 我要说话，TTS 立即停。`echoCancellation: true` 防外放回声，停 0.3s 再开录，避免尾音串进第一帧。

### D8 计量与配额（按连接 + 按 token）
- 建连时先校验并**预占一次**：会话归属 / 状态 RUNNING / 每日次数 / 用户并发连接数（只允许 1 条）/ 租户并发上限；
- 结束时按 `task-finished` 里的 `usage` 写 `ai_usage_record`（`feature=INTERVIEW_ASR`）；
- 单任务硬上限 90 秒（60 秒说话 + 30 秒余量），到点自动 `finish-task` 并关闭；
- 连接建立后 5 秒内没收到音频 → 主动关闭，防止「只开连接不发数据」占资源。

### D9 背压：音频不能丢，只能断
上行帧用有界队列（容量 50 帧 ≈ 5 秒）。队列满说明客户端网络已经跟不上，此时**丢弃音频等于毁掉这次回答**，正确处理是发 `error` 帧并关闭，让用户重说。

### D10 失败一律降级，不弹 modal
见 §6.4。语音挂了必须能继续手打。

---

## 5. 架构与时序

```
浏览器                                 后端                              百炼
 ├ useRecorder (AudioWorklet→16k PCM)
 ├ usePushToTalk (状态机)
 └ AsrSocket ── WS(binary) ──> AsrWebSocketHandler ── WS ──> 实时 ASR
      ▲                              │
      └──── text 帧(partial/sentence/final) ◄──┘
```

```
用户          前端                              后端                        百炼
 │ keydown      │                                  │                          │
 │─────────────>│ speech.stop()                    │                          │
 │              │ POST /asr-ticket ───────────────>│ 校验+签票                │
 │              │<──────────────── {ticket,wsUrl}  │                          │
 │              │ WS 握手 ?ticket=... ─────────────>│ GETDEL 校验              │
 │              │                                  │ run-task ───────────────>│
 │              │<──────────────── ready ───────────│<───────────── task-started│
 │ 说话         │ PCM 帧 每100ms ──────────────────>│ 透传 ───────────────────>│
 │              │<──────────────── partial ─────────│<──────── result(interim) │
 │              │  (幽灵层覆盖同句)                 │                          │
 │              │<──────────────── sentence ────────│<──────── result(end=true)│
 │              │  (追加到草稿)                     │                          │
 │ keyup        │ finish-task 控制帧 ──────────────>│ finish-task ────────────>│
 │              │<──────────────── final(usage) ────│<──────────── task-finished│
 │              │ 关闭连接、光标回 textarea         │ 记 usage、释放并发位      │
```

---

## 6. 前端设计

### 6.1 状态机

```
IDLE ──keydown(Space, 语音模式)──> CONNECTING(取票+握手)
CONNECTING ──ready──> STREAMING(发帧)
STREAMING ──keyup / 60s / blur──> FINISHING(发 finish-task)
FINISHING ──final──> IDLE（草稿已更新）
任意态 ──Esc / 切题 / 提交 / 卸载──> ABORT（关连接，丢弃半句）
```

### 6.2 麦克风预热
点「开始面试」的那次真实点击里，和摄像头、AudioContext 一起申请麦克风，`stream` 复用（开关只切 `track.enabled`）。避免每说一句弹一次权限；被拒时语音模式自动关，流程不中断。

### 6.3 UI

- 作答条左侧麦克风按钮：`onPointerDown` 开录、`onPointerUp` 收尾（`setPointerCapture`），与空格共用同一对 start/stop。
- 录音中：按钮红色脉冲 + 4 根音量条（`AnalyserNode` RMS）+ 计时 `00:07`，文案「正在听… 松手结束」。
- **幽灵层**：作答框内容上方浮一行浅灰文本，显示当前句的中间结果，光标处一个闪烁竖线。定稿后该行消失、文字并入草稿。
- 识别中：麦克风换 spinner，文案「正在整理最后一句」。
- 空闲：底边小字「按住空格说话 · 松手转文字」，首次 3 秒后淡出。
- `phase` 复用现有枚举：说话 → `LISTENING`（「正在听你说」），收尾 → `THINKING`（「正在整理」）。
- 定稿文本插入光标位置，句间补空格；textarea 原生 undo 栈保证 `Ctrl+Z` 可撤。

### 6.4 失败处理

| 情况 | 表现 |
|---|---|
| 权限被拒 / 无设备 | 语音模式自动关 + 一行提示，手打不受影响 |
| 取票失败（401/404/409） | 「语音不可用」+ 自动关语音模式 |
| WS 握手失败（代理拦截） | 同上，并记录一次「WS 不可用」，后续本场不再尝试 |
| 连接建立后 3s 无结果 | 「没听清，再说一次」 |
| 中途断开（网络） | 保留已定稿的句子，中间句丢弃，提示「网络断了」 |
| 60 秒上限 | 自动收尾并提示「单次最长 60 秒」 |
| 429 | 「今日语音额度已用完」 |
| 空结果 | 「没听清，再说一次」 |

---

## 7. 后端设计

### 7.1 新增清单

| 层 | 类 | 职责 |
|---|---|---|
| 端口 | `ai/application/RealtimeAsrSession` / `RealtimeAsrGateway` | 开一个识别任务：`open(opts) → Session`，Session 有 `send(byte[])`、`finish()`、回调 `onPartial/onSentence/onFinished/onFailed` |
| 适配器 | `ai/infrastructure/bailian/BailianRealtimeAsrAdapter` | 连百炼 WS，收发 run-task/continue-task/finish-task，映射事件 |
| 配置 | `ai/infrastructure/bailian/AsrProperties` + `AsrConfiguration` | `panel.ai.asr.*` |
| WS 入口 | `interview/api/AsrWebSocketConfig` / `AsrHandshakeInterceptor` / `AsrWebSocketHandler` | 注册 `/ws/interview/asr`，ticket 校验，上下游对拷 |
| 签票 | `interview/api/AsrTicketController` + `AsrTicketService` | `POST /sessions/{id}/asr-ticket`，Redis 存 30s |
| 应用 | `interview/application/TranscriptionService` | 会话校验、配额预占、并发位、计量落库 |

### 7.2 上游连接方式
用 JDK 自带的 `java.net.http.WebSocket`（`HttpClient.newWebSocketBuilder()`）连百炼，**不需要额外依赖**；服务端只加 `spring-boot-starter-websocket`（它带的 `tomcat-embed-websocket` 提供容器）。
好处：不用引入 WebFlux，也不用手写 Netty 客户端。

### 7.3 转发与资源回收（最容易漏的几项）

| 关注点 | 做法 |
|---|---|
| 握手鉴权 | 拦截器从 query 取 ticket，Redis `GETDEL`，校验 `userId`/`sessionId`/`expiresAt`，把上下文塞进 `attributes` |
| 上游建连 | `run-task` 发出后等 `task-started`，**收到之前不转发音频**（否则首批帧会被丢） |
| 下行映射 | `partial`/`sentence` 直接透传（只裁剪字段，不解析业务语义） |
| 背压 | 有界队列 50 帧；满了发 error 并关连接 |
| 超时 | 单任务 90s 定时器；建连 5s 无音频则关 |
| 清理 | `afterConnectionClosed` / `handleTransportError` 都必须关闭上游 + 释放并发位 + 取消定时器；上游 `task-failed`/断开也要关下游 |
| 并发 | Redis 记 `asr:conn:{userId}`（SETNX + TTL），保证一人一条；租户级再做总量 |
| 脱敏 | 日志只记 `sessionId / 帧数 / 时长 / token / 错误码`，**不记文本** |

### 7.4 错误码映射

| 上游 | 下游帧 | 前端表现 |
|---|---|---|
| `task-failed` + `CLIENT_ERROR` | `{type:"error", code:"UPSTREAM_CLIENT"}` | 「语音服务暂时不可用」 |
| 401/403 | `{type:"error", code:"UPSTREAM_AUTH"}` | 「语音服务未开通」+ 关语音模式 |
| **403 `AllocationQuota.FreeTierOnly`** | `{type:"error", code:"QUOTA_EXHAUSTED"}` | 「语音额度已用完」+ 关语音模式 + 平台告警 |
| 连接超时 | `{type:"error", code:"UPSTREAM_TIMEOUT"}` | 可重试 |
| 额度/并发超限 | 握手阶段 403 + 原因 | 「今日额度已用完」/「有一路语音正在识别」 |

---

## 8. 接口契约

### 8.1 签票

```
POST /api/v1/interview/sessions/{id}/asr-ticket
Authorization: Bearer <jwt>

200 { success, data: {
      ticket: "b7f3…",        // 32 字节随机，一次一用
      wsUrl: "/ws/interview/asr",
      expiresInSeconds: 30,
      sampleRate: 16000,
      format: "pcm"
} }
409 面试已结束 / 当前没有待答题目
429 今日额度已用完 / 并发超限
```

### 8.2 浏览器 ↔ 后端 WS 帧

```
客户端 → 服务端
  binary                 PCM 16k/16bit/单声道，每帧 100ms（3200 字节）
  {"type":"finish"}      松手
  {"type":"cancel"}      Esc / 切题，丢弃

服务端 → 客户端
  {"type":"ready"}
  {"type":"partial","sentenceId":3,"text":"我负责的是"}      // 覆盖展示，不拼接
  {"type":"sentence","sentenceId":3,"text":"我负责的是订单系统。","beginMs":1200,"endMs":4300}
  {"type":"final","durationMs":18400,"usage":{"totalTokens":1150}}
  {"type":"error","code":"UPSTREAM_TIMEOUT","message":"…"}
```

`partial` 与 `sentence` 用同一个 `sentenceId` 对齐；前端只保留「当前句」，收到 `sentence` 就清空当前句。

---

## 9. 配置

```yaml
spring:
  # 新增依赖后自动生效；显式配置只为可读性
panel:
  ai:
    asr:
      enabled: ${PANEL_ASR_ENABLED:true}
      api-key: ${DASHSCOPE_ASR_API_KEY:}          # 留空复用 dashscope.api-key
      ws-url: ${DASHSCOPE_ASR_WS_URL:wss://dashscope.aliyuncs.com/api-ws/v1/inference}
      model: ${DASHSCOPE_ASR_MODEL:qwen-audio-3.1-asr-flash-message}
      sample-rate: 16000
      format: pcm
      vad-model: near_meeting_16k
      max-sentence-silence-ms: 1200
      intermediate-results: true                   # 关键，默认必须显式开
      disfluency-removal: true
      heartbeat: true
      language-hints: zh,en
      hotwords: ${DASHSCOPE_ASR_HOTWORDS:Kubernetes,RAG,QPS,幂等,微服务}
      max-session-seconds: 90
      idle-timeout-seconds: 5
      frame-queue-size: 50
      daily-quota-per-tenant: ${ASR_DAILY_QUOTA:300}
      max-connections-per-user: 1
      max-connections-per-tenant: ${ASR_MAX_CONN_PER_TENANT:20}
```

数据侧：**零 DDL**。`qwen-audio-3.1-asr-flash-message` 的 `usage` 是 token（`input_tokens/output_tokens/total_tokens`），现有 `ai_usage_record` 与 `AiUsageCommand` 可直接用。

---

## 10. 安全与合规

- **ticket 防重放**：30 秒、一次一用、绑定 user+session；握手校验失败直接 403，不建上游连接。
- **防刷**：签票时校验会话归属与状态；建连后 5 秒无音频自动关；一人一条并发；每日次数上限。
- **不落音频**：帧只在内存里过，不写盘、不进对象存储。
- **日志**：只记元数据，不记识别文本（与现有 `SpeechService` 一致，只记长度）。
- **告知**：首次使用一行说明「音频仅用于本次转写，不保存」；百炼侧留存以服务条款为准，不做过度承诺。

---

## 11. 排期

| 阶段 | 内容 | 产出 | 估时 |
|---|---|---|---|
| T0 | 用 Node 脚本直连百炼 WS：run-task → 发 30s PCM → 收中间结果 → finish；确认中间结果频率、断句、`usage`、并发限制 | ✅ 已完成，见 §16 | 0.5d |
| T1 | `RealtimeAsrGateway` + 百炼适配器（事件解析、usage、错误映射） | 单测（mock 上游） | 1d |
| T2 | WS 服务端：配置、ticket 拦截器、Handler、上下游转发、背压、超时、清理 | 本地能跑通「前端模拟器 → 后端 → 百炼」 | 1.5d |
| T3 | 签票接口 + 配额预占 + 并发位 + 用量落库 | 接口可 curl，错误码齐全 | 0.5d |
| T4 | 前端 `useRecorder`：AudioWorklet + 16k 重采样 + 分帧发送 | 能持续出中间结果 | 1d |
| T5 | 前端 `usePushToTalk` + 空格/输入法 + 幽灵文本 UI | 按住说、边说边看 | 1d |
| T6 | 失败矩阵 + 打断 TTS + 降级 | 断网、拒权限、代理拦 WS 都能过 | 0.5d |
| T7 | 端到端冒烟脚本 | 一条命令跑通 | 0.5d |

合计 **6.5 天**。关键路径仍是 T0——模型没开通、代理连不上，后面全白做。

---

## 12. 验收标准

1. 说话 1 秒内出现第一批字；首字 P95 < 500ms。
2. 松手到定稿 P95 < 800ms；60 秒回答不丢字。
3. 中间文本**按句覆盖更新**，不出现「越写越长然后突然重排」。
4. 定稿文本进 textarea，光标在末尾，`Ctrl+Z` 可撤。
5. 录音中按 `Esc` / 切题 / 提交 → 立即断开且不上传剩余帧。
6. 面试官念题时按空格 → TTS 立即停，录音里没有面试官声音。
7. 麦克风被拒 → 不弹错误、语音模式自动关、手打正常。
8. 代理拦 WS 的环境 → 一次失败后本场不再尝试，提示改用键盘。
9. 连续建连超限 → 提示友好，不泄漏内部错误。
10. 识别文本不出现在任何日志（grep 验证）。
11. 断开后重连不会重复计费（每次连接独立 usage，无叠加）。

---

## 13. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 忘记开 `intermediate_result_enabled` | 退化成一次性识别，「边讲边看」失效 | 配置默认 true + 启动日志打印该值 |
| 采样率不是 16k | 直接任务失败 | AudioWorklet 强制重采样 + 单测校验帧长度 |
| 公司代理拦截 WS | 功能不可用 | 一次探测 + 自动/手动降级到手打 |
| 中间文本跳变 | 用户觉得「乱跳」 | 严格按 `sentence_id` 覆盖，不拼接；过渡用 80ms 淡入 |
| 长停顿被切成碎句 | 文本碎、体验差 | `max_sentence_silence=1200`，必要时按 2s 调 |
| 并发连接数被刷 | 上游限流 / 成本 | 一人一条 + 租户总量 + 每日次数 |
| 上游断连 | 丢半句 | 保留已定稿句，提示重说最后一句 |
| 计费口径变化 | 成本不可控 | 按 `task-finished.usage` 落库，异常告警 |
| **免费额度耗尽（「用尽即停」开启）** | 全量 403，语音突然不可用 | 捕获 `AllocationQuota.FreeTierOnly` → 降级 + 告警；用量到 80% 时后台提醒 |

---

## 14. 降级路径（v1.1，可选）

若 WS 在部分网络环境不可用，加一条 HTTP 一次性识别：`POST /sessions/{id}/transcriptions`，body 为整段 WAV（16k PCM，服务端可直接用 `pcm` 格式转给同一族模型），前端松手后上传。复用同一 `TranscriptionService` 的校验与配额，只是网关换成一次性调用。约 1 天。

注意：一次性接口的音频必须是 **16k PCM/WAV**（该族模型 `pcm` 强制 16k），所以前端要保留一份 PCM 缓冲——流式路径本来就有，复用即可。

---

## 15. 附：现有 TTS 的额度归属（与 ASR 对照）

| 项 | 现状 |
|---|---|
| 走哪个额度 | `DASHSCOPE_API_KEY` 所属百炼账号（`qwen-audio-3.1-tts-flash`），**不是**系统租户配额 |
| 单价（北京） | 输入 1.5 元/百万 token，输出 12 元/百万 token；RPM 180 |
| 系统内可见性 | 无——`SpeechService` 不做 `usage.record`，`ai_usage_record` 里没记录 |
| 现有成本控制 | `speech_asset` 缓存 + 只念库里已有文本 + 每场题数有限 |
| 缺口 | 无每日/每月次数上限；AI 模块关掉的租户仍能合成；跨会话题目各合成一次 |

ASR 按本方案接入后会**补齐这一半**（`INTERVIEW_ASR` 有记录）。TTS 与面试 LLM 的计量属于独立小任务，建议同期做掉，否则租户配额仍然只看得到 ASR 这一路。

---

## 16. T0 实测数据（2026-11-08 已跑通）

**环境**：Node 24 直连百炼 WS；测试音频为系统 TTS 合成的中文面试回答，16kHz/16bit/单声道，20.9 秒（约 120 字）。脚本：`scripts/probe-asr.mjs`。

| 指标 | `-message` | `-streaming` |
|---|---|---|
| WS 握手（含 TLS） | 286ms | 243ms |
| `task-started` | 356ms | 313ms |
| 第一个中间结果 | 855ms（**开流后 ~450ms**） | 830ms（**开流后 ~450ms**） |
| 中间结果频率 | **2.4 次/秒**（50 次） | 0.86 次/秒（18 次） |
| 断句数（20.9s） | 2 | 1（整段一句） |
| 松手 → 定稿 | **391ms** | 490ms |
| usage（total） | 306 token | 325 token |
| token 密度 | 14.6 /秒 | 15.5 /秒 |
| 1M token 可转写 | ≈ 19 小时 | ≈ 18 小时 |

**准确率**：两个模型都正确识别 `Java`、`Spring Boot`、`Redis`、`分库分表`、`3000 单`、`800 毫秒 → 200 毫秒`，中文数字归一化正确，句末标点正常。差别只在排版：`-streaming` 给中英/数字之间加了空格（`3000 单`），`-message` 不加（`3000单`），后者更适合直接落进作答框。

**结论**：
1. **`-message` 作为主模型**——首字延迟与 streaming 持平，但上屏频率快 2.8 倍（2.4 次/秒 vs 0.86 次/秒），"边讲边看"的跟手感完全不在一个档；断句也更细。
2. **两个模型同一套协议**：同一个脚本、同一组参数、只改 `model` 就能跑，切换确实是一行配置（此前担心参数有差异，实测证伪）。
3. 首字 ~450ms、定稿 ~400ms，比设计目标（500ms / 800ms）更宽裕。
4. 1M 免费额度能撑约 19 小时音频，开发调试绰绰有余；上线前需切后付费或调低配额。

**测试条件的偏差**：系统 TTS 语速偏快（20.9 秒 120 字，约 345 字/分钟），真人面试通常 200–260 字/分钟，停顿更多。因此真实场景的中间结果会更密、断句数会更多（预计 3–6 句/分钟），`max_sentence_silence=1200ms` 需要在真实录音上再校一次。

---

## 17. 实现清单（2026-11-08 已完成 T1–T6）

### 后端

| 文件 | 作用 |
|---|---|
| `ai/application/RealtimeAsrGateway.java` | 端口：`open() → Session`，回调 `onStarted/onPartial/onSentence/onFinished/onFailed`；约定**不抛异常** |
| `ai/infrastructure/bailian/AsrProperties.java` | `panel.ai.asr.*` 配置 |
| `ai/infrastructure/bailian/AsrConfiguration.java` | 装配适配器，`api-key` 留空时复用 `panel.ai.dashscope.api-key` |
| `ai/infrastructure/bailian/BailianRealtimeAsrAdapter.java` | JDK `java.net.http.WebSocket` 实现 run-task/finish-task，单条 pump 线程发送，串行化 send + 天然背压 |
| `interview/application/AsrTicketService.java` | 30 秒一次性 ticket，绑定 user + session |
| `interview/application/AsrQuotaService.java` | 每日次数 + 每用户并发位（内存，跨天自动清零） |
| `interview/api/AsrTicketController.java` | `POST /api/v1/interview/sessions/{id}/asr-ticket`，权限 `interview:answer` |
| `interview/api/AsrHandshakeInterceptor.java` | 握手验券 + 占并发位，失败回 403/429，不会去连百炼 |
| `interview/api/AsrWebSocketHandler.java` | 上下游对拷、帧协议、用量落库、90 秒看门狗、并发位释放 |
| `interview/api/AsrWebSocketConfig.java` | 注册 `/ws/interview/asr` |
| `SecurityConfig` | 放行 `/ws/**`（鉴权交给 ticket） |
| `pom.xml` | 新增 `spring-boot-starter-websocket` |
| `application.yml` | 新增 `panel.ai.asr.*` 整段 |

### 前端

| 文件 | 作用 |
|---|---|
| `public/pcm-worklet.js` | AudioWorklet：Float32 → Int16，每 1600 采样（100ms）一批 |
| `hooks/useRecorder.ts` | `getUserMedia` → AudioWorklet，`AudioContext({sampleRate:16000})` 交给浏览器重采样，语音图接 0 增益避免回声 |
| `hooks/usePushToTalk.ts` | 状态机 `IDLE→CONNECTING→STREAMING→FINISHING`、空格/输入法/失焦处理、幽灵文本、失败降级 |
| `api/interview.ts` | `asrTicket(id)` |
| `vite.config.ts` | 新增 `/ws` 代理（`ws: true`） |
| `pages/InterviewPage.tsx` | 麦克风按钮（按住说话 + 音量条 + 计时）、幽灵文本层、状态行、Esc 取消 |

### 实现中做的三个调整（与设计稿不同）

1. **不手写重采样**：`new AudioContext({ sampleRate: 16000 })` 让浏览器自己重采样，只有浏览器拒绝时才走兜底线性插值。设计稿里担心的 48k→16k 转换被这条抹掉了。
2. **后端缓冲首帧**：前端 WS 一打开就推音频，而百炼还要握手 + run-task，中间约 200ms 的空窗。`AsrWebSocketHandler` 先攒最多 30 帧，`onStarted` 时补发——否则开头一两个字会丢。这不在原设计里，是实测时序暴露出来的。
3. **配额与 ticket 放内存**：和现有 `TenantRateLimitFilter` 一致，不给语音链路增加 Redis 依赖。多实例部署时要换 Redis（代码里已注明）。

### 验证情况

| 项 | 状态 |
|---|---|
| `mvn compile` | ✅ |
| `mvn test -Dtest=Asr*Test` | ✅ 16 个用例（ticket 一次性/过期/配额/并发位、事件映射、帧缓冲、用量落库） |
| `tsc --noEmit` | ✅ |
| `vite build` | ✅ |
| 百炼直连协议 + 延迟 | ✅ 见 §16 |
| **端到端（真实后端）** | ✅ 取票 → WS 握手 → 推 20.9s PCM → ready/partial/sentence/final，识别文本正确，usage 331 token 回传 |
| 浏览器 UI 手动点 | ⏳ 待人工验证（`http://127.0.0.1:5173`） |

端到端脚本：`node scripts/check-asr-e2e.mjs --session <id> --file tmp/asr-sample.wav`，走的是真实后端而不是直连百炼。

### 实测暴露并修掉的两个 bug

1. **`final` 的 usage 全是 0**：百炼把累计用量放在 `result-generated` 的 `payload.usage` 里，`task-finished` 里没有。适配器原来只在 `task-finished` 读，导致所有识别都不记账。现在每个 result 都更新 `latestUsage`，收尾时兜底。
2. **开头一两个字会丢**：前端 WS 一打开就推帧，而百炼要握手 + run-task，中间约 200ms。`AsrWebSocketHandler` 现在先缓冲最多 30 帧，`onStarted` 时补发。

### 已知缺口：用量在 platform admin 下记不进库

`JpaAiUsageRepository` 要求 `tenant_id` 非空，而这个库里 `sys_user` 只有 `admin`（`tenant_id = NULL`）——所以 `ai_usage_record` 一直是 0 行，不只是 ASR，面试链路和 TTS 也一样。

现在 ASR 的处理是：拿不到租户就只记一行 INFO 日志（`asr usage not persisted (platform actor) tokens=331`），不阻断识别。要真正记账，二选一：

- 给面试用户挂一个租户；
- 或者放开 `JpaAiUsageRepository` 的 `tenant_id` 非空校验（影响面更大，要单独评估）。

在这个前提下，租户配额体系目前**看不到任何用量**，语音防刷只能依赖 `AsrQuotaService` 里那套内存计数（每日 400 次 + 每人 1 条并发）。

### 开发注意

本机 `JAVA_HOME` 指向 **JDK 8**，直接 `mvn` 会因为不支持 `record` 报几百条「需要 class/interface/enum」。构建前要切到 JDK 21：

```powershell
$env:JAVA_HOME='D:\java\jdk-21'; $env:PATH="D:\java\jdk-21\bin;$env:PATH"
```
