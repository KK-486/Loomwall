// 选中一个图层（模拟点击图层列表），然后读属性面板里的文字，
// 确认「视差景深」「视差方向（高级）」真的出现且各控件存在。
//
// 用法：node check-layer-parallax.mjs [端口]
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
  const scene = state.doc.scene;
  // 选一个叶子图层（形状/图片），再单独测一次容器
  const leaf = scene.root.children.find(n => n.type === 'rect' && n.parentId === undefined && !n.children);
  const container = scene.root.children.find(n => n.children && n.children.length);
  const read = (id) => {
    state.sel.rectId = id;
    renderInspector();
    const box = document.getElementById('inspector');
    return {
      id,
      labels: [...box.querySelectorAll('label, summary, span')].map(e => (e.textContent || '').trim()).filter(Boolean),
      details: [...box.querySelectorAll('details')].length,
      inputs: [...box.querySelectorAll('input')].length
    };
  };
  return { leaf: leaf ? read(leaf.id) : null, container: container ? read(container.id) : null };
})()
`;

const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
if (r.exceptionDetails) {
  console.error('读取失败: ' + (r.exceptionDetails.text || ''));
  process.exit(1);
}
const v = r.result.value;
for (const key of ['leaf', 'container']) {
  const t = v[key];
  if (!t) { console.log(key + ': 没有可用节点'); continue; }
  console.log(`\n[${key}] 节点 ${t.id}`);
  console.log('  含视差景深: ' + (t.labels.includes('视差景深') ? '✓' : '✗'));
  console.log('  含方向折叠: ' + (t.labels.some(l => l.includes('视差方向')) ? '✓' : '✗'));
  console.log('  details 数: ' + t.details + '  input 数: ' + t.inputs);
  console.log('  面板文字: ' + t.labels.join(' | '));
}
ws.close();
process.exit(0);
