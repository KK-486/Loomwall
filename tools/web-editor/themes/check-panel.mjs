// 读属性面板的 DOM，确认「视差（全局）」区块真的渲染出来了。
// 用法：node check-panel.mjs [端口]
'use strict';

const port = Number(process.argv[2]) || 9226;
const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page =
  list.find(t => t.type === 'page' && t.webSocketDebuggerUrl &&
                 t.url.includes('3009') && (t.title || '').includes('LWPKit')) ||
  list.find(t => t.type === 'page' && t.webSocketDebuggerUrl && t.url.includes('3009')) ||
  list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) { console.error('没有已加载编辑器的标签页'); process.exit(1); }

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

const expr = `
(() => {
  const labels = [...document.querySelectorAll('#inspector label, #inspector span')]
    .map(e => (e.textContent || '').trim())
    .filter(Boolean);
  const inputs = [...document.querySelectorAll('#inspector input')].map(i => ({
    type: i.type, min: i.min, max: i.max, step: i.step, value: i.value
  }));
  return {
    build: (document.getElementById('build') || {}).textContent || '',
    hasGlobal: labels.includes('视差（全局）'),
    labels,
    inputs,
    manifestParallax: state.doc.manifest.parallax || null
  };
})()
`;

const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
if (r.exceptionDetails) {
  console.error('读取失败: ' + (r.exceptionDetails.text || ''));
  process.exit(1);
}
const v = r.result.value;
console.log('构建标签: ' + v.build);
console.log('含「视差（全局）」: ' + (v.hasGlobal ? '✓' : '✗'));
console.log('面板文字: ' + v.labels.join(' | '));
console.log('控件数: ' + v.inputs.length);
v.inputs.forEach(i => console.log(`  ${i.type} min=${i.min || '-'} max=${i.max || '-'} step=${i.step || '-'} value=${i.value}`));
console.log('manifest.parallax: ' + JSON.stringify(v.manifestParallax));
ws.close();
process.exit(0);
