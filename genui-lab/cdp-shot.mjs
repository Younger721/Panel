// 用 CDP 精确截图：等 .block 渲染出来再拍
// 用法: node cdp-shot.mjs <url> <out.png> [minBlocks]
import fs from 'node:fs/promises';

const url = process.argv[2] || 'http://127.0.0.1:8787/';
const out = process.argv[3] || 'shot.png';
const minBlocks = Number(process.argv[4] || 1);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function findPage() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
      const page = list.find((t) => t.type === 'page');
      if (page) return page;
    } catch {}
    await sleep(250);
  }
  throw new Error('CDP not reachable on 9222');
}

const page = await findPage();
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
});

await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true });
  ws.addEventListener('error', rej, { once: true });
});

const send = (method, params = {}) =>
  new Promise((resolve) => {
    const mid = ++id;
    pending.set(mid, resolve);
    ws.send(JSON.stringify({ id: mid, method, params }));
  });

const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  return r?.result?.result?.value;
};

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', {
  width: 900, height: 1900, deviceScaleFactor: 1, mobile: false,
});
await send('Page.navigate', { url });

let n = 0;
for (let i = 0; i < 120; i++) {
  await sleep(250);
  n = (await evaluate(`document.querySelectorAll('.block').length`)) || 0;
  const busy = await evaluate(`document.querySelector('#send')?.disabled`);
  if (n >= minBlocks && !busy) break;
}

const err = await evaluate(`window.__err || null`);
console.log(`blocks=${n} err=${err}`);

const shot = await send('Page.captureScreenshot', {
  format: 'png',
  captureBeyondViewport: true,
  clip: { x: 0, y: 0, width: 900, height: 1900, scale: 1 },
});
await fs.writeFile(out, Buffer.from(shot.result.data, 'base64'));
console.log('saved', out);
ws.close();
