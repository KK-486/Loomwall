// 把赛博朋克案例完整恢复到编辑器（scene + interactions + manifest）。
//
// 之前 inject-live.mjs 只写了 state.doc.scene，动画/事件/绑定全丢 ——
// 看起来"案例回来了"，实际上触发器、动画都不见了。这里一次写全三份。
//
// 用法：node restore-case.mjs [端口] [案例目录]
'use strict';

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const port = Number(process.argv[2]) || 9226;
const caseDir = process.argv[3] || join(HERE, 'cyberpunk');

// 单文件包里有完整的 manifest/scene/interactions，优先用它
const bundlePath = join(caseDir, 'cyberpunk.lwpkit.json');
const bundle = JSON.parse(readFileSync(bundlePath, 'utf8'));

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
const evalJs = async expression => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || 'eval 失败');
  return r.result.value;
};

const payload = JSON.stringify({
  manifest: bundle.manifest,
  scene: bundle.scene,
  interactions: bundle.interactions
});

const counts = await evalJs(`
(() => {
  const data = ${payload};
  state.doc.manifest = data.manifest;
  state.doc.scene = data.scene;
  state.doc.interactions = data.interactions;
  state.sel = { rectId: null, channel: -1, keyIndex: -1 };
  renderAll(false);
  if (typeof resetHistory === 'function') resetHistory();
  persistDoc();
  return {
    nodes: state.doc.scene.root.children.length,
    animations: (state.doc.interactions.animations || []).length,
    events: (state.doc.interactions.events || []).length,
    bindings: (state.doc.interactions.bindings || []).length,
    manifest: state.doc.manifest.name
  };
})()
`);

console.log('案例已恢复到编辑器');
console.log('  图层: ' + counts.nodes);
console.log('  动画: ' + counts.animations);
console.log('  事件: ' + counts.events);
console.log('  绑定: ' + counts.bindings);
console.log('  预设名: ' + counts.manifest);

const ok = counts.nodes > 100 && counts.animations > 0 && counts.events > 0 && counts.bindings > 0;
console.log(ok ? '\n✓ 场景 / 动画 / 事件 / 绑定 全部就位' : '\n⚠ 有内容仍然缺失（检查包文件）');
ws.close();
process.exit(ok ? 0 : 1);
