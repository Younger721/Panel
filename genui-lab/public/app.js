/* genui-lab / app.js
 * 三件事：流式接收、增量解析、渲染成真实组件
 * 没有任何依赖，全部手写 —— 这样你才看得清每一层在做什么
 */
const $ = (s, r = document) => r.querySelector(s);
const log = $('#log');
const input = $('#input');
const sendBtn = $('#send');
const modeBadge = $('#mode');
const scenarioSel = $('#scenario');
const themeSel = $('#theme');

const state = {
  messages: [],
  busy: false,
};

/* ------------------------------------------------------------------ */
/* 工具                                                                */
/* ------------------------------------------------------------------ */
const esc = (s) =>
  String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const scrollBottom = () => window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });

/* ------------------------------------------------------------------ */
/* 1. 增量 JSON 解析器 —— 整个项目最核心的 40 行                        */
/*    输入是模型不断吐出的半截字符串，输出是"已经完整闭合的 block"        */
/* ------------------------------------------------------------------ */
function takeBlocks(buf, offset) {
  const out = [];
  let i = offset;

  while (i < buf.length) {
    // 跳过 [ , 空白 等非对象起始字符
    while (i < buf.length && buf[i] !== '{') i++;
    if (i >= buf.length) break;

    const start = i;
    let depth = 0;
    let inStr = false;
    let escNext = false;
    let j = i;
    let closed = false;

    for (; j < buf.length; j++) {
      const c = buf[j];
      if (inStr) {
        if (escNext) escNext = false;
        else if (c === '\\') escNext = true;
        else if (c === '"') inStr = false;
      } else {
        if (c === '"') inStr = true;
        else if (c === '{') depth++;
        else if (c === '}') {
          depth--;
          if (depth === 0) { j++; closed = true; break; }
        }
      }
    }

    if (!closed) break; // 还没闭合，等下一个 chunk

    try {
      out.push(JSON.parse(buf.slice(start, j)));
      i = j;
    } catch {
      i = j;
    }
  }
  return { blocks: out, next: i };
}

/* ------------------------------------------------------------------ */
/* 2. 传输层                                                           */
/* ------------------------------------------------------------------ */
async function streamChat(messages, onDelta, onMeta) {
  const r = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ messages, scenario: scenarioSel?.value || 'travel' }),
  });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);

  const reader = r.body.getReader();
  const dec = new TextDecoder();
  let buf = '';

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });

    let cut;
    while ((cut = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, cut);
      buf = buf.slice(cut + 2);

      const line = chunk.split('\n').find((l) => l.startsWith('data:'));
      if (!line) continue;
      const payload = line.slice(5).trim();
      if (payload === '[DONE]') return;

      let j;
      try { j = JSON.parse(payload); } catch { continue; }
      if (j.meta) onMeta?.(j.meta);
      if (j.error) throw new Error(j.error);
      if (j.delta) onDelta(j.delta);
    }
  }
}

/* ------------------------------------------------------------------ */
/* 3. 对话                                                             */
/* ------------------------------------------------------------------ */
// 用户消息：表单回传的原始 payload 太丑，渲染成人看得懂的样子
function renderUserText(t) {
  if (String(t).startsWith('[form]')) {
    const [head, json] = String(t).split('\n');
    let vals = {};
    try { vals = JSON.parse(json || '{}'); } catch { /* 忽略 */ }
    const chips = Object.entries(vals)
      .filter(([, v]) => String(v).trim() !== '')
      .map(([k, v]) => `<span class="kv"><i>${esc(k)}</i>${esc(v)}</span>`)
      .join('');
    return `<div class="form-sub"><span class="tag">表单提交</span>${chips || esc(head)}</div>`;
  }
  return esc(t);
}

function addUser(text) {
  const el = document.createElement('div');
  el.className = 'msg user';
  el.innerHTML = `<div class="bubble">${renderUserText(text)}</div>`;
  log.appendChild(el);
  scrollBottom();
}

function addAI() {
  const el = document.createElement('div');
  el.className = 'msg ai';
  el.innerHTML = `
    <div class="bubble">
      <div class="blocks"></div>
      <details class="rawbox"><summary>看模型的原始输出</summary><pre class="raw"></pre></details>
    </div>`;
  el.dataset.off = '0';
  log.appendChild(el);
  scrollBottom();
  return el;
}

function drain(buf, box) {
  const from = Number(box.dataset.off || 0);
  const { blocks, next } = takeBlocks(buf, from);
  box.dataset.off = String(next);
  if (!blocks.length) return;

  const host = box.querySelector('.blocks');
  for (const b of blocks) {
    try {
      host.appendChild(renderBlock(b));
    } catch (err) {
      const p = document.createElement('div');
      p.className = 'note warn';
      p.textContent = `无法渲染的 block: ${JSON.stringify(b).slice(0, 120)}`;
      host.appendChild(p);
    }
  }
  scrollBottom();
}

async function send(text) {
  const value = String(text ?? '').trim();
  if (!value || state.busy) return;

  state.busy = true;
  sendBtn.disabled = true;
  log.querySelector('.hello')?.remove();

  addUser(value);
  state.messages.push({ role: 'user', content: value });

  const box = addAI();
  const raw = box.querySelector('.raw');
  let buf = '';

  try {
    await streamChat(
      state.messages,
      (delta) => {
        buf += delta;
        raw.textContent = buf;
        drain(buf, box);
      },
      (meta) => {
        modeBadge.textContent = meta.mode === 'live' ? `live · ${meta.model}` : 'mock';
        modeBadge.classList.toggle('live', meta.mode === 'live');
      },
    );
    state.messages.push({ role: 'assistant', content: buf });
  } catch (err) {
    const p = document.createElement('div');
    p.className = 'note warn';
    p.textContent = `出错：${err.message}`;
    box.querySelector('.blocks').appendChild(p);
  } finally {
    state.busy = false;
    sendBtn.disabled = false;
    raw.innerHTML = highlightJson(buf);
    scrollBottom();
  }
}

// 给"原始输出"上个色，让人一眼看出这是结构化数据
function highlightJson(src) {
  const e = esc(src);
  return e
    .replace(/(&quot;type&quot;)\s*:\s*(&quot;[\w]+&quot;)/g, '<b>$1</b>:<i>$2</i>')
    .replace(/(&quot;[\w-]+&quot;)(\s*:)/g, '<span class="k">$1</span>$2');
}

/* ------------------------------------------------------------------ */
/* 4. 渲染器                                                           */
/* ------------------------------------------------------------------ */
function renderBlock(b) {
  switch (b.type) {
    case 'card': return renderCard(b);
    case 'table': return renderTable(b);
    case 'chart': return renderChart(b);
    case 'form': return renderForm(b);
    case 'timeline': return renderTimeline(b);
    case 'calc': return renderCalc(b);
    case 'map': return renderMap(b);
    case 'game': return renderGame(b);
    case 'note': return renderNote(b);
    case 'kanban': return renderKanban(b);
    case 'quiz': return renderQuiz(b);
    default: {
      const d = document.createElement('div');
      d.className = 'note warn';
      d.textContent = `未知 block 类型：${b.type}`;
      return d;
    }
  }
}

function wrap(html, cls = '') {
  const d = document.createElement('div');
  d.className = `block ${cls}`.trim();
  d.innerHTML = html;
  return d;
}

/* --- card --- */
function renderCard(b) {
  const actions = (b.actions || [])
    .map((a) => `<button class="chip" data-intent="${esc(a.intent || a.label)}">${esc(a.label)}</button>`)
    .join('');
  return wrap(
    `<h3>${esc(b.title || '')}</h3>
     <p>${esc(b.body || '')}</p>
     ${actions ? `<div class="actions">${actions}</div>` : ''}`,
    'card',
  );
}

/* --- table --- */
function renderTable(b) {
  const head = (b.columns || []).map((c) => `<th>${esc(c)}</th>`).join('');
  const rows = (b.rows || [])
    .map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`)
    .join('');
  return wrap(
    `${b.title ? `<h4>${esc(b.title)}</h4>` : ''}
     <div class="scroll"><table><thead><tr>${head}</tr></thead><tbody>${rows}</tbody></table></div>`,
    'table',
  );
}

/* --- chart：纯 CSS/SVG，无图表库 --- */
function renderChart(b) {
  const data = (b.data || []).filter((d) => Number.isFinite(Number(d.value)));
  if (!data.length) return wrap('<p>无数据</p>');

  let body = '';
  if (b.kind === 'pie') {
    const total = data.reduce((a, d) => a + Number(d.value), 0) || 1;
    let acc = 0;
    const stops = data
      .map((d, i) => {
        const from = (acc / total) * 100;
        acc += Number(d.value);
        const to = (acc / total) * 100;
        return `var(--c${i % 6}) ${from}% ${to}%`;
      })
      .join(', ');
    body = `<div class="pie-wrap">
      <div class="pie" style="background:conic-gradient(${stops})"></div>
      <ul class="legend">${data
        .map((d, i) => `<li><i style="background:var(--c${i % 6})"></i>${esc(d.label)}<b>${esc(d.value)}</b></li>`)
        .join('')}</ul>
    </div>`;
  } else if (b.kind === 'line') {
    const vals = data.map((d) => Number(d.value));
    const max = Math.max(...vals, 1);
    const min = Math.min(...vals, 0);
    const W = 320, H = 110, P = 8;
    const pts = vals
      .map((v, i) => {
        const x = P + (i * (W - 2 * P)) / Math.max(vals.length - 1, 1);
        const y = H - P - ((v - min) / (max - min || 1)) * (H - 2 * P);
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
    body = `<svg class="line" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
      <polyline points="${pts}" fill="none" stroke="var(--accent)" stroke-width="2.5" stroke-linejoin="round" />
    </svg>
    <div class="axis">${data.map((d) => `<span>${esc(d.label)}</span>`).join('')}</div>`;
  } else {
    const max = Math.max(...data.map((d) => Number(d.value)), 1);
    body = `<div class="bars">${data
      .map(
        (d) => `<div class="bar-row">
          <span class="bar-label">${esc(d.label)}</span>
          <div class="bar-track"><div class="bar-fill" style="width:${((Number(d.value) / max) * 100).toFixed(1)}%"></div></div>
          <span class="bar-value">${esc(d.value)}</span>
        </div>`,
      )
      .join('')}</div>`;
  }

  return wrap(`${b.title ? `<h4>${esc(b.title)}</h4>` : ''}${body}`, 'chart');
}

/* --- form：提交后把结构化结果回传给模型 --- */
function renderForm(b) {
  const el = wrap(
    `${b.title ? `<h4>${esc(b.title)}</h4>` : ''}
     <div class="fields">${(b.fields || [])
       .map((f) => {
         const name = esc(f.name);
         const label = esc(f.label || f.name);
         if (f.kind === 'select') {
           return `<label><span>${label}</span><select name="${name}">
             ${(f.options || []).map((o) => `<option value="${esc(o)}">${esc(o)}</option>`).join('')}
           </select></label>`;
         }
         const t = f.kind === 'number' ? 'number' : 'text';
         return `<label><span>${label}</span><input name="${name}" type="${t}" /></label>`;
       })
       .join('')}
     </div>
     <button class="primary">${esc(b.submit || '提交')}</button>`,
    'form',
  );

  el.querySelector('button.primary').addEventListener('click', () => {
    const values = {};
    el.querySelectorAll('input,select').forEach((n) => {
      if (n.name) values[n.name] = n.value;
    });
    const payload = `[form] ${b.submit || '已提交'}\n${JSON.stringify(values)}`;
    el.classList.add('done');
    send(payload);
  });

  return el;
}

/* --- timeline --- */
function renderTimeline(b) {
  const items = (b.items || [])
    .map(
      (it) => `<li>
        <div class="t-time">${esc(it.time || '')}</div>
        <div class="t-body"><b>${esc(it.title || '')}</b>${it.detail ? `<em>${esc(it.detail)}</em>` : ''}</div>
      </li>`,
    )
    .join('');
  return wrap(`${b.title ? `<h4>${esc(b.title)}</h4>` : ''}<ul class="timeline">${items}</ul>`, 'timeline');
}

/* --- calc：前端直接算，模型只负责给表达式 --- */
function renderCalc(b) {
  const expr = String(b.expr || '').trim();
  let result = '表达式不合法';
  if (/^[\d\s+\-*/().%]+$/.test(expr)) {
    try {
      // eslint-disable-next-line no-new-func
      const v = Function(`"use strict"; return (${expr});`)();
      if (typeof v === 'number' && Number.isFinite(v)) result = v.toLocaleString('zh-CN');
    } catch { /* 保持不合法 */ }
  }
  return wrap(
    `<div class="calc-expr">${esc(expr)}</div>
     <div class="calc-eq">=</div>
     <div class="calc-val">${esc(result)}</div>`,
    'calc',
  );
}

/* --- map：离线散点图，按经纬度归一化 --- */
function renderMap(b) {
  const ms = (b.markers || []).filter((m) => Number.isFinite(Number(m.lat)) && Number.isFinite(Number(m.lng)));
  if (!ms.length) return wrap('<p>无坐标</p>');

  const lats = ms.map((m) => Number(m.lat));
  const lngs = ms.map((m) => Number(m.lng));
  const pad = 0.12;
  let minLat = Math.min(...lats), maxLat = Math.max(...lats);
  let minLng = Math.min(...lngs), maxLng = Math.max(...lngs);
  if (maxLat - minLat < 0.01) { minLat -= 0.01; maxLat += 0.01; }
  if (maxLng - minLng < 0.01) { minLng -= 0.01; maxLng += 0.01; }

  const W = 320, H = 180;
  const x = (lng) => ((lng - minLng) / (maxLng - minLng)) * (W * (1 - 2 * pad)) + W * pad;
  const y = (lat) => H - (((lat - minLat) / (maxLat - minLat)) * (H * (1 - 2 * pad)) + H * pad);

  const dots = ms
    .map((m) => {
      const cx = x(Number(m.lng));
      const cy = y(Number(m.lat));
      const right = cx < W * 0.62;
      const tx = right ? cx + 8 : cx - 8;
      return `<g>
        <circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="4.5" />
        <text x="${tx.toFixed(1)}" y="${(cy + 4).toFixed(1)}" text-anchor="${right ? 'start' : 'end'}">${esc(m.label)}</text>
      </g>`;
    })
    .join('');

  return wrap(
    `${b.title ? `<h4>${esc(b.title)}</h4>` : ''}
     <svg class="map" viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet">
       <rect x="0" y="0" width="${W}" height="${H}" rx="10" class="map-bg" />
       ${[1, 2, 3].map((i) => `<line x1="0" x2="${W}" y1="${(H / 4) * i}" y2="${(H / 4) * i}" class="map-grid"/>`).join('')}
       ${[1, 2, 3].map((i) => `<line y1="0" y2="${H}" x1="${(W / 4) * i}" x2="${(W / 4) * i}" class="map-grid"/>`).join('')}
       ${dots}
     </svg>`,
    'map-box',
  );
}

/* --- note --- */
function renderNote(b) {
  const level = b.level || 'info';
  return wrap(`<span class="nd"></span>${esc(b.text || '')}`, `note ${level}`);
}

/* --- kanban --- */
function renderKanban(b) {
  const cols = (b.columns || [])
    .map(
      (c) => `<div class="kcol">
        <div class="kcol-head">${esc(c.name)}<span>${(c.cards || []).length}</span></div>
        ${(c.cards || [])
          .map(
            (card) => `<div class="kcard">${esc(card.title)}${card.tag ? `<i>${esc(card.tag)}</i>` : ''}</div>`,
          )
          .join('')}
      </div>`,
    )
    .join('');
  return wrap(`${b.title ? `<h4>${esc(b.title)}</h4>` : ''}<div class="kanban">${cols}</div>`, 'kanban-box');
}

/* --- quiz：可作答，答完把结果回传给模型 --- */
function renderQuiz(b) {
  const options = b.options || [];
  const answer = Number(b.answer);

  const el = wrap(
    `${b.title ? `<h4>${esc(b.title)}</h4>` : ''}
     <div class="q-text">${esc(b.question || '')}</div>
     <div class="q-opts">${options
       .map((o, i) => `<button class="q-opt" data-i="${i}">${esc(o)}</button>`)
       .join('')}</div>
     <div class="q-explain"></div>`,
    'quiz',
  );

  let answered = false;
  el.querySelector('.q-opts').addEventListener('click', (e) => {
    const btn = e.target.closest('.q-opt');
    if (!btn || answered) return;
    answered = true;

    const pick = Number(btn.dataset.i);
    const right = pick === answer;

    el.querySelectorAll('.q-opt').forEach((n, i) => {
      n.classList.add(i === answer ? 'right' : i === pick ? 'wrong' : 'dim');
    });

    const ex = el.querySelector('.q-explain');
    ex.textContent = (right ? '✅ 对了。' : '❌ 不对。') + (b.explain || '');
    ex.classList.add(right ? 'ok' : 'warn');

    send(`[quiz] 这题我选了「${options[pick]}」，正确答案是「${options[answer]}」`);
  });

  return el;
}

/* ------------------------------------------------------------------ */
/* 5. 2048 —— AI 说"来个游戏"时，真的给一个能玩的                     */
/* ------------------------------------------------------------------ */
function renderGame(b) {
  const N = 4;
  const el = wrap(
    `<div class="g-head"><span>2048</span><b class="score">0</b></div>
     <div class="g-grid" tabindex="0"></div>
     <div class="g-hint">点击格子后按 方向键 / WASD</div>`,
    'game',
  );

  const gridEl = el.querySelector('.g-grid');
  const scoreEl = el.querySelector('.score');
  let grid, score;

  const empty = () => {
    const a = [];
    for (let r = 0; r < N; r++) for (let c = 0; c < N; c++) if (!grid[r][c]) a.push([r, c]);
    return a;
  };
  const add = () => {
    const e = empty();
    if (!e.length) return;
    const [r, c] = e[(Math.random() * e.length) | 0];
    grid[r][c] = Math.random() < 0.9 ? 2 : 4;
  };
  const paint = () => {
    gridEl.innerHTML = '';
    for (let r = 0; r < N; r++) {
      for (let c = 0; c < N; c++) {
        const v = grid[r][c];
        const d = document.createElement('div');
        d.className = 'cell';
        d.textContent = v || '';
        if (v) {
          const k = Math.log2(v);
          d.style.background = `hsl(${205 - k * 16} 62% ${Math.max(24, 60 - k * 4)}%)`;
          d.style.color = k > 6 ? '#0b0d12' : '#fff';
        }
        gridEl.appendChild(d);
      }
    }
    scoreEl.textContent = score;
  };
  const slide = (row) => {
    const a = row.filter((v) => v);
    const out = [];
    for (let i = 0; i < a.length; i++) {
      if (a[i] === a[i + 1]) {
        out.push(a[i] * 2);
        score += a[i] * 2;
        i++;
      } else out.push(a[i]);
    }
    while (out.length < N) out.push(0);
    return out;
  };
  const move = (dir) => {
    const before = JSON.stringify(grid);
    if (dir === 'left' || dir === 'right') {
      grid = grid.map((row) => (dir === 'left' ? slide(row) : slide([...row].reverse()).reverse()));
    } else {
      for (let c = 0; c < N; c++) {
        let col = grid.map((row) => row[c]);
        col = dir === 'down' ? slide([...col].reverse()).reverse() : slide(col);
        col.forEach((v, r) => { grid[r][c] = v; });
      }
    }
    if (JSON.stringify(grid) !== before) {
      add();
      paint();
    }
  };

  grid = Array.from({ length: N }, () => Array(N).fill(0));
  score = 0;
  add();
  add();
  paint();

  gridEl.addEventListener('keydown', (e) => {
    const map = {
      ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down',
      a: 'left', d: 'right', w: 'up', s: 'down',
    };
    const dir = map[e.key];
    if (!dir) return;
    e.preventDefault();
    move(dir);
  });
  gridEl.addEventListener('click', () => gridEl.focus());

  return el;
}

/* ------------------------------------------------------------------ */
/* 6. 交互：按钮 intent 回传、快捷问题                                  */
/* ------------------------------------------------------------------ */
log.addEventListener('click', (e) => {
  const chip = e.target.closest('[data-intent]');
  if (chip) {
    send(chip.dataset.intent);
    return;
  }
  const preset = e.target.closest('[data-preset]');
  if (preset) send(preset.dataset.preset);
});

const PRESETS = {
  travel: ['杭州两日游怎么安排', '三个方案哪个便宜', '1234*5678 等于多少', '来个游戏', '给我出个题'],
  finance: ['本月经营情况', '看环比', '毛利率多少'],
  fitness: ['这周怎么练', '我每天该吃多少热量', '考我动作要点'],
};

function mountPresets() {
  const host = $('#presets');
  if (!host) return;
  const list = PRESETS[scenarioSel?.value] || PRESETS.travel;
  host.innerHTML = list.map((p) => `<button class="chip" data-preset="${esc(p)}">${esc(p)}</button>`).join('');
}

/* ------------------------------------------------------------------ */
/* 7. 启动                                                             */
/* ------------------------------------------------------------------ */
// 主题
const bootParams = new URLSearchParams(location.search);
const savedTheme = bootParams.get('theme') || localStorage.getItem('genui-theme') || 'dusk';
document.documentElement.dataset.theme = savedTheme;
themeSel.value = savedTheme;
themeSel.addEventListener('change', () => {
  document.documentElement.dataset.theme = themeSel.value;
  localStorage.setItem('genui-theme', themeSel.value);
});

// 场景
scenarioSel.value = bootParams.get('scenario') || localStorage.getItem('genui-scenario') || 'travel';
scenarioSel.addEventListener('change', () => {
  localStorage.setItem('genui-scenario', scenarioSel.value);
  resetLog();
});

function resetLog() {
  state.messages = [];
  log.innerHTML = `<section class="hello"><h1>问点什么</h1><div class="presets" id="presets"></div></section>`;
  mountPresets();
}

mountPresets();

// 方便截图/演示：/?q=杭州两日游怎么安排 自动发一条
const bootQ = new URLSearchParams(location.search).get('q');
if (bootQ) setTimeout(() => send(bootQ), 150);

sendBtn.addEventListener('click', () => {
  const v = input.value;
  input.value = '';
  send(v);
});

input.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.isComposing) {
    const v = input.value;
    input.value = '';
    send(v);
  }
});

$('#clear').addEventListener('click', resetLog);
