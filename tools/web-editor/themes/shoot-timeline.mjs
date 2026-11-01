// 选中一个通道让关键帧面板有内容，然后截图，用于肉眼确认时间轴版面。
// 用法：node shoot-timeline.mjs [端口] [输出路径]
'use strict';

import { writeFileSync } from 'node:fs';

const port = Number(process.argv[2]) || 9226;
const out = process.argv[3] || '.toolchain/artifacts/timeline_kf.png';

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) { console.error('没有可用标签页'); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true });
  ws.addEventListener('error', rej, { once: true });
});

let nextId = 1;
const pending = new Map();
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { resolve, reject } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? reject(new Error(m.error.message)) : resolve(m.result);
  }
});
const send = (method, params = {}) => {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
};

await send('Runtime.enable');
await send('Page.enable');

const setup = `
(() => {
  const leaf = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children);
  if (leaf) {
    state.sel.rectId = leaf.id;
    state.sel.channel = 1;
  }
  renderInspector();
  if (typeof renderTimeline === 'function') renderTimeline();
  return { selected: state.sel.rectId, channel: state.sel.channel };
})()
`;
const r = await send('Runtime.evaluate', { expression: setup, returnByValue: true });
if (r.exceptionDetails) console.error('准备阶段异常: ' + (r.exceptionDetails.text || ''));
else console.log('已选中: ' + JSON.stringify(r.result.value));

const shot = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(out, Buffer.from(shot.data, 'base64'));
console.log('截图: ' + out);
ws.close();
process.exit(0);
