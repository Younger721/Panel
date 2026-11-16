// genui-lab / server.mjs
// 零依赖 Node 服务器：静态文件 + /api/chat 流式代理 + 内置 mock
// 启动：node server.mjs         (无 key，走 mock)
//       API_KEY=sk-xxx node server.mjs  (真模型)
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUB = path.join(__dirname, 'public');
const PORT = Number(process.env.PORT || 8787);
const API_KEY = process.env.API_KEY || '';
const API_BASE = (process.env.API_BASE || 'https://api.deepseek.com/v1').replace(/\/+$/, '');
const MODEL = process.env.MODEL || 'deepseek-chat';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

/* ------------------------------------------------------------------ */
/* 协议：11 种 block，所有场景共用                                      */
/* ------------------------------------------------------------------ */
const BLOCK_SCHEMA = `可用 block：

1. {"type":"card","title":"标题","body":"一段说明","actions":[{"label":"按钮文字","intent":"点击后要追问模型的话"}]}
2. {"type":"table","title":"可选","columns":["列1","列2"],"rows":[["a","b"],["c","d"]]}
3. {"type":"chart","title":"可选","kind":"bar|line|pie","data":[{"label":"A","value":12}]}
4. {"type":"form","title":"可选","fields":[{"name":"id","label":"名称","kind":"number|text|select","options":["a","b"]}],"submit":"提交后发给模型的话"}
5. {"type":"timeline","title":"可选","items":[{"time":"09:00","title":"事项","detail":"细节"}]}
6. {"type":"calc","expr":"表达式，只能含数字与 + - * / ( ) . %"}
7. {"type":"map","title":"可选","markers":[{"label":"地点","lat":30.25,"lng":120.16}]}
8. {"type":"game","kind":"2048"}
9. {"type":"note","level":"info|warn|ok","text":"一行提示"}
10. {"type":"kanban","title":"可选","columns":[{"name":"待办","cards":[{"title":"事项","tag":"标签"}]}]}
11. {"type":"quiz","title":"可选","question":"题干","options":["A","B","C"],"answer":1,"explain":"答对后的解释"}`;

/* ------------------------------------------------------------------ */
/* 场景：同一套协议，换一段规则就是另一个产品                            */
/* ------------------------------------------------------------------ */
const SCENARIOS = {
  travel: {
    label: '旅行规划',
    rules: `当前场景：旅行规划。
- 涉及地点、城市、路线 → map
- 安排几天去哪 → timeline
- 预算分配 → chart(pie)，明细 → table
- 需要先知道天数/预算/偏好 → form
- 用户要玩游戏 → game
- 旅行相关但可以出题（历史、地理小知识）→ 谨慎使用 quiz`,
  },
  finance: {
    label: '财务报表',
    rules: `当前场景：财务报表分析。
- 你的说话对象是财务/经营者，语气克制，不要感叹号。
- 任何数字必须能对上：先 table 列明细，再 chart 展示结构，最后 card 给一句结论。
- 用户问"多少/总共/占比多少" → calc 或 chart，不要口算。
- 不要用 map、game、timeline、quiz。
- 明细表列名要专业：科目、本期、上期、变动、变动率。
- 变动超过 20% 时，用 note(warn) 提示。`,
  },
  fitness: {
    label: '训练计划',
    rules: `当前场景：训练与饮食计划。
- 一周安排 → timeline；单次训练的动作组数 → table
- 热量/蛋白/碳水分配 → chart(pie)
- 需要知道体重/目标/每周天数 → form
- 用户问"我要吃多少""消耗多少" → calc
- 涉及"今天该练什么"这种问诊 → quiz 用在动作要点自测
- 不要用 map、game`,
  },
};

function buildSystemPrompt(key) {
  const s = SCENARIOS[key] || SCENARIOS.travel;
  return `你是「界面生成器」，不是聊天机器人。

你的每一次回答必须、且只能是**一个 JSON 数组**，数组元素是下列 11 种 block 之一。
禁止输出任何自然语言、禁止 markdown 代码块、禁止解释、禁止前后缀文字。

${BLOCK_SCHEMA}

选择规则（严格遵守）：
- 问"多少 / 等于 / 计算" → calc
- 对比、排行、占比、预算分配 → chart
- 计划、行程、步骤、路线 → timeline
- 需要用户先给参数才能给结果 → form
- 罗列数据、结果、清单 → table
- 总结、结论、解释 → card
- 地点、城市、路线 → map
- 用户明确要玩游戏 → game
- 任务分派、进度、看板 → kanban
- 出题、测验、自测 → quiz
- 任何"已更新 / 已提交" → note(ok) + 更新后的 block

风格要求：
- 一个回答通常 1~3 个 block，不要堆砌。
- 能可视化就绝不用纯文字。card 只用在对结果的总结。
- 中文回答，简洁。

${s.rules}`;
}

/* ------------------------------------------------------------------ */
/* HTTP                                                                */
/* ------------------------------------------------------------------ */
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (req.method === 'POST' && url.pathname === '/api/chat') return await handleChat(req, res);
    if (req.method === 'GET') return await serveStatic(url.pathname, res);
    res.writeHead(405, { 'content-type': 'text/plain' }).end('Method Not Allowed');
  } catch (err) {
    console.error('[server]', err);
    if (!res.headersSent) res.writeHead(500, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: String(err && err.message || err) }));
  }
});

async function serveStatic(pathname, res) {
  let rel = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const file = path.resolve(PUB, rel);
  if (!file.startsWith(path.resolve(PUB))) {
    res.writeHead(403).end('Forbidden');
    return;
  }
  try {
    const buf = await fs.readFile(file);
    res.writeHead(200, {
      'content-type': MIME[path.extname(file)] || 'application/octet-stream',
      'cache-control': 'no-store',
    }).end(buf);
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain' }).end('Not Found');
  }
}

async function handleChat(req, res) {
  const body = await readJson(req);
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const scenario = SCENARIOS[body.scenario] ? body.scenario : 'travel';

  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache, no-transform',
    connection: 'keep-alive',
    'x-accel-buffering': 'no',
  });

  const mode = API_KEY && !body.mock ? 'live' : 'mock';
  res.write(`data: ${JSON.stringify({ meta: { mode, model: mode === 'live' ? MODEL : 'mock', scenario } })}\n\n`);

  if (mode === 'mock') {
    await streamMock(messages, res, scenario);
  } else {
    await streamLive(messages, res, scenario);
  }
  res.write('data: [DONE]\n\n');
  res.end();
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); }
      catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

/* ------------------------------------------------------------------ */
/* 真模型：OpenAI 兼容 /chat/completions 透传                           */
/* ------------------------------------------------------------------ */
async function streamLive(messages, res, scenario) {
  const r = await fetch(`${API_BASE}/chat/completions`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${API_KEY}`,
    },
    body: JSON.stringify({
      model: MODEL,
      stream: true,
      temperature: 0.6,
      messages: [{ role: 'system', content: buildSystemPrompt(scenario) }, ...messages.slice(-12)],
    }),
  });

  if (!r.ok || !r.body) {
    const text = await r.text().catch(() => '');
    res.write(`data: ${JSON.stringify({ error: `upstream ${r.status}: ${text.slice(0, 300)}` })}\n\n`);
    return;
  }

  const decoder = new TextDecoder();
  let buf = '';
  for await (const chunk of r.body) {
    buf += decoder.decode(chunk, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (payload === '[DONE]') return;
      try {
        const j = JSON.parse(payload);
        const delta = j.choices?.[0]?.delta?.content;
        if (delta) res.write(`data: ${JSON.stringify({ delta })}\n\n`);
      } catch { /* 忽略心跳行 */ }
    }
  }
}

/* ------------------------------------------------------------------ */
/* Mock：没有任何 key 也能完整演示，包括表单回传的闭环                     */
/* ------------------------------------------------------------------ */
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function streamMock(messages, res, scenario) {
  const last = [...messages].reverse().find((m) => m.role === 'user');
  const text = String(last?.content ?? '');
  const blocks = pickMock(text, scenario);
  const json = JSON.stringify(blocks);
  const step = 18;
  for (let i = 0; i < json.length; i += step) {
    res.write(`data: ${JSON.stringify({ delta: json.slice(i, i + step) })}\n\n`);
    await sleep(16);
  }
}

function pickMock(text, scenario) {
  if (scenario === 'finance') return pickFinance(text);
  if (scenario === 'fitness') return pickFitness(text);
  return pickTravel(text);
}

function pickTravel(text) {
  const t = text.toLowerCase();

  if (/看板|任务|进度|分派|项目/.test(t)) {
    return [
      { type: 'note', level: 'info', text: '从三条任务线拆出来的看板' },
      { type: 'kanban', title: '本周迭代', columns: [
        { name: '待办', cards: [
          { title: '接入登录态校验', tag: '后端' },
          { title: '首页骨架屏', tag: '前端' },
        ] },
        { name: '进行中', cards: [
          { title: '流式解析容错', tag: '核心' },
          { title: '生成式 UI 协议 v0.2', tag: '设计' },
        ] },
        { name: '已完成', cards: [
          { title: '增量 JSON 解析器', tag: '核心' },
          { title: '2048 组件', tag: '彩蛋' },
        ] },
      ] },
    ];
  }

  if (/出题|出个题|测验|考我|quiz|测试我|做个题/.test(t)) {
    return [
      { type: 'quiz', title: '生成式 UI 小测', question: '流式解析半截 JSON 时，最容易被忽略的状态是什么？',
        options: ['括号深度', '字符串内的引号与转义', '数组下标', '编码格式'], answer: 1,
        explain: '模型输出里经常有转义引号，不区分"是否在字符串内"，括号计数立刻错位。' },
      { type: 'note', level: 'info', text: '选完会立刻告诉你对错，答完会把结果回传给模型。' },
    ];
  }

  if (t.startsWith('[form]')) {
    return [
      { type: 'note', level: 'ok', text: '已按你的选择重算' },
      { type: 'timeline', title: '杭州 3 天行程', items: [
        { time: 'D1 上午', title: '西溪湿地', detail: '慢逛 3 小时，避开人流' },
        { time: 'D1 下午', title: '龙井村', detail: '喝一杯当年的明前' },
        { time: 'D2 全天', title: '西湖环线', detail: '断桥 → 白堤 → 孤山 → 苏堤' },
        { time: 'D3 上午', title: '灵隐寺', detail: '早课人少' },
      ] },
      { type: 'chart', title: '预算分配（元）', kind: 'pie', data: [
        { label: '住宿', value: 900 }, { label: '餐饮', value: 600 },
        { label: '交通', value: 300 }, { label: '门票', value: 180 },
      ] },
      { type: 'form', title: '继续调整', fields: [
        { name: 'days', label: '天数', kind: 'select', options: ['1', '2', '3', '4'] },
        { name: 'budget', label: '总预算', kind: 'number' },
      ], submit: '按新天数重排杭州行程' },
    ];
  }

  if (/游戏|2048|玩/.test(t)) {
    return [{ type: 'game', kind: '2048' }];
  }

  if (/多少|等于|计算|算一下|\d+\s*[+\-*/]\s*\d+/.test(t)) {
    const expr = (text.match(/[\d\s+\-*/().%]+/) || ['1+1'])[0].trim();
    return [
      { type: 'calc', expr: expr },
      { type: 'card', title: '顺便说一句', body: '这条结果是前端直接算的，模型只负责生成表达式。' },
    ];
  }

  if (/对比|排行|占比|哪个|便宜|贵|预算|成本/.test(t)) {
    return [
      { type: 'chart', title: '三个方案的月成本（元）', kind: 'bar', data: [
        { label: '自建 GPU', value: 4200 },
        { label: '云 API', value: 1150 },
        { label: '混合', value: 2300 },
      ] },
      { type: 'table', title: '明细', columns: ['方案', '延迟', '可扩展'], rows: [
        ['自建 GPU', '低', '差'], ['云 API', '中', '好'], ['混合', '低', '好'],
      ] },
    ];
  }

  if (/杭州|旅行|行程|规划|周末|旅游/.test(t)) {
    return [
      { type: 'card', title: '先给你一个两天的骨架', body: '下面按"少走路、多吃饭"排，预算按两人算。', actions: [
        { label: '改成三天', intent: '把杭州行程改成三天' },
        { label: '只看预算', intent: '只看杭州两天的预算分配' },
      ] },
      { type: 'timeline', title: '杭州 2 天', items: [
        { time: 'D1 09:00', title: '西溪湿地', detail: '从周家村入口进，人少' },
        { time: 'D1 14:00', title: '龙井村', detail: '沿九溪十八涧走下来' },
        { time: 'D1 19:00', title: '河坊街', detail: '吃定胜糕和葱包桧' },
        { time: 'D2 08:30', title: '西湖环线', detail: '断桥 → 白堤 → 孤山 → 苏堤' },
        { time: 'D2 15:00', title: '灵隐寺', detail: '下午人少，可上飞来峰' },
      ] },
      { type: 'chart', title: '预算分配（元）', kind: 'pie', data: [
        { label: '住宿', value: 700 }, { label: '餐饮', value: 450 },
        { label: '交通', value: 200 }, { label: '门票', value: 120 },
      ] },
      { type: 'map', title: '几个点的大致位置', markers: [
        { label: '西溪湿地', lat: 30.27, lng: 120.07 },
        { label: '龙井村', lat: 30.22, lng: 120.12 },
        { label: '河坊街', lat: 30.24, lng: 120.17 },
        { label: '西湖', lat: 30.25, lng: 120.15 },
        { label: '灵隐寺', lat: 30.24, lng: 120.10 },
      ] },
      { type: 'form', title: '告诉我要调整什么', fields: [
        { name: 'days', label: '天数', kind: 'select', options: ['1', '2', '3', '4'] },
        { name: 'budget', label: '总预算', kind: 'number' },
        { name: 'style', label: '偏好', kind: 'select', options: ['文艺', '美食', '亲子', '躺平'] },
      ], submit: '按我的选择重排杭州行程' },
    ];
  }

  return [
    { type: 'card', title: '我只会画界面', body: '试着问我：杭州两日游怎么安排 / 三个方案哪个便宜 / 1234*5678 等于多少 / 来个游戏' },
    { type: 'note', level: 'info', text: '当前是 mock 模式，设置 API_KEY 后接真模型。' },
  ];
}

/* ---- 场景 mock：财务报表 ---- */
function pickFinance(text) {
  const t = text.toLowerCase();

  if (/环比|趋势|对比|走势|上期/.test(t)) {
    return [
      {
        type: 'table', title: '本月主要科目（万元）',
        columns: ['科目', '本期', '上期', '变动', '变动率'],
        rows: [
          ['营业收入', '1,842', '1,596', '+246', '+15.4%'],
          ['营业成本', '1,120', '982', '+138', '+14.1%'],
          ['销售费用', '268', '231', '+37', '+16.0%'],
          ['管理费用', '142', '156', '-14', '-9.0%'],
        ],
      },
      {
        type: 'chart', title: '收入趋势（万元）', kind: 'line',
        data: [
          { label: '7月', value: 1520 }, { label: '8月', value: 1608 },
          { label: '9月', value: 1560 }, { label: '10月', value: 1712 },
          { label: '11月', value: 1842 },
        ],
      },
      { type: 'note', level: 'warn', text: '销售费用 +16.0%，快于收入 +15.4%，获客成本需要单独看。' },
    ];
  }

  if (/毛利率|利润|多少|计算|占比/.test(t)) {
    return [
      { type: 'calc', expr: '(1842-1120)/1842*100' },
      { type: 'card', title: '毛利率', body: '本期 39.2%，上期 38.5%，提升 0.7 个百分点。' },
      {
        type: 'chart', title: '费用结构（万元）', kind: 'pie',
        data: [
          { label: '营业成本', value: 1120 }, { label: '销售费用', value: 268 },
          { label: '管理费用', value: 142 }, { label: '研发费用', value: 186 },
        ],
      },
    ];
  }

  return [
    {
      type: 'card', title: '本月经营概览', body: '收入在涨，但费用涨得更快，利润质量要拆开看。',
      actions: [
        { label: '看环比', intent: '看本月和上期的环比对比' },
        { label: '算毛利率', intent: '毛利率是多少' },
      ],
    },
    {
      type: 'table', title: '关键指标', columns: ['指标', '本期', '上期', '变动率'],
      rows: [['营业收入', '1,842', '1,596', '+15.4%'], ['净利润', '268', '241', '+11.2%']],
    },
    {
      type: 'chart', title: '收入 vs 成本（万元）', kind: 'bar',
      data: [{ label: '收入', value: 1842 }, { label: '成本', value: 1120 }, { label: '净利', value: 268 }],
    },
  ];
}

/* ---- 场景 mock：训练计划 ---- */
function pickFitness(text) {
  const t = text.toLowerCase();

  if (t.startsWith('[form]')) {
    return [
      { type: 'note', level: 'ok', text: '已按你的目标重排' },
      {
        type: 'timeline', title: '每周四天（减脂向）', items: [
          { time: '周一', title: '推 + 20 分钟有氧', detail: '卧推 4×8 / 肩推 3×10' },
          { time: '周二', title: '低强度有氧', detail: '快走 40 分钟，心率 120' },
          { time: '周四', title: '拉', detail: '硬拉 3×5 / 划船 4×10' },
          { time: '周六', title: '腿 + 核心', detail: '深蹲 4×6 / 平板 3×45s' },
        ],
      },
      {
        type: 'chart', title: '每日热量分配（kcal）', kind: 'pie',
        data: [{ label: '蛋白质', value: 480 }, { label: '碳水', value: 960 }, { label: '脂肪', value: 540 }],
      },
    ];
  }

  if (/吃|热量|蛋白|饮食|卡路里/.test(t)) {
    return [
      { type: 'calc', expr: '75*1.6*4' },
      {
        type: 'chart', title: '每日热量分配（kcal）', kind: 'pie',
        data: [{ label: '蛋白质', value: 480 }, { label: '碳水', value: 960 }, { label: '脂肪', value: 540 }],
      },
      { type: 'note', level: 'info', text: '体重 75kg、蛋白 1.6g/kg，三大项合计约 1980 kcal。' },
    ];
  }

  if (/要点|考我|动作|注意/.test(t)) {
    return [
      {
        type: 'quiz', title: '动作要点自测', question: '深蹲时膝盖内扣，最优先应该调整什么？',
        options: ['加大重量', '降低重量并强化臀中肌', '换硬拉代替', '加快下放速度'], answer: 1,
        explain: '膝内扣多是髋外展肌力不足，先降重量把动作模式纠正过来。',
      },
    ];
  }

  return [
    {
      type: 'card', title: '这周先练三天', body: '推 / 拉 / 腿各一天，中间插两天低强度有氧。',
      actions: [
        { label: '改成四天', intent: '把训练改成每周四天' },
        { label: '算每日热量', intent: '我每天该吃多少热量' },
      ],
    },
    {
      type: 'timeline', title: '一周安排', items: [
        { time: '周一', title: '推（胸肩三头）', detail: '卧推 4×8 / 肩推 3×10 / 绳索下压 3×12' },
        { time: '周三', title: '拉（背二头）', detail: '硬拉 3×5 / 划船 4×10 / 弯举 3×12' },
        { time: '周五', title: '腿', detail: '深蹲 4×6 / 罗马尼亚硬拉 3×10 / 提踵 4×15' },
        { time: '周二·周四', title: '低强度有氧', detail: '快走 30 分钟，心率 120 左右' },
      ],
    },
    {
      type: 'form', title: '调整一下', fields: [
        { name: 'days', label: '每周天数', kind: 'select', options: ['3', '4', '5'] },
        { name: 'goal', label: '目标', kind: 'select', options: ['减脂', '增肌', '保持'] },
      ], submit: '按我的目标重排训练计划',
    },
  ];
}

server.listen(PORT, () => {
  console.log(`\n  genui-lab  →  http://127.0.0.1:${PORT}`);
  console.log(`  mode       →  ${API_KEY ? `live (${MODEL} @ ${API_BASE})` : 'mock（无需 key）'}\n`);
});
