// 验证「双向闭环」：点表单提交 -> 发新消息 -> 重渲染
import fs from 'node:fs/promises';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const page = list.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
});
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
const send = (method, params = {}) => new Promise((res) => {
  const mid = ++id; pending.set(mid, res);
  ws.send(JSON.stringify({ id: mid, method, params }));
});
const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  if (r?.result?.exceptionDetails) return { __err: r.result.exceptionDetails.text };
  return r?.result?.result?.value;
};

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 900, height: 2100, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url: 'http://127.0.0.1:8787/?q=' + encodeURIComponent('杭州两日游怎么安排') });

for (let i = 0; i < 80; i++) {
  await sleep(200);
  const n = await evaluate(`document.querySelectorAll('.msg.ai').length`);
  const b = await evaluate(`document.querySelectorAll('.msg.ai:first-of-type .block').length`);
  if (n >= 1 && b >= 5) break;
}
console.log('第1轮 block 数:', await evaluate(`document.querySelectorAll('.msg.ai .block').length`));

// 点第一个表单的提交按钮
const clicked = await evaluate(`(() => {
  const btn = document.querySelector('.form button.primary');
  if (!btn) return 'no-form-button';
  btn.click();
  return 'clicked:' + btn.textContent.trim();
})()`);
console.log('点击结果:', clicked);

for (let i = 0; i < 100; i++) {
  await sleep(200);
  const n = await evaluate(`document.querySelectorAll('.msg.ai').length`);
  const busy = await evaluate(`document.querySelector('#send')?.disabled`);
  if (n >= 2 && !busy) break;
}
console.log('两轮后 block 总数:', await evaluate(`document.querySelectorAll('.msg.ai .block').length`));
console.log('第二轮类型:', await evaluate(
  `[...document.querySelectorAll('.msg.ai')][1] ? [...document.querySelectorAll('.msg.ai')][1].querySelectorAll('.block').length : 0`
));

// 长截图时 sticky header 会重复出现，截图前先固定住
await evaluate(`(() => { const h = document.querySelector('header.top'); if (h) h.style.position = 'static'; })()`);

const shot = await send('Page.captureScreenshot', {
  format: 'png', captureBeyondViewport: true,
  clip: { x: 0, y: 0, width: 900, height: 2400, scale: 1 },
});
await fs.writeFile('preview.png', Buffer.from(shot.result.data, 'base64'));
console.log('saved preview.png');
ws.close();
