// 网页编辑器的测试总入口：**跑所有 UI 测试，并保证不动用户的文档**。
//
// 为什么需要总入口：单个测试脚本各自加守卫太脆弱（我加过一次，还是漏了 ——
// 有的用例在守卫之后重建场景，还原顺序一错就把案例冲掉）。
// 这里统一在开跑前序列化文档、全部跑完后无条件还原，任何用例都影响不到。
//
// 用法：node run-web-tests.mjs [端口]
'use strict';

import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const port = Number(process.argv[2]) || 9226;

const TESTS = [
  'check-shell.mjs',
  'check-preview-gestures.mjs',
  'check-notch-snap.mjs',
  'check-contextmenu.mjs',
  'check-fold-anchor.mjs',
  'check-fold-drag.mjs',
  'check-copy-paste.mjs',
  'check-undo-redo.mjs',
  'check-decimal-sliders.mjs',
];

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
// 选"编辑器页"而不是"第一个页"：调试端口上可能还挂着权限弹窗/空白页，
// 连错页会让整个套件报 "state is not defined"（真踩过）。
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

/**
 * 把页面重新加载到干净状态。
 *
 * 用例之间会通过 DOM 内联样式互相污染（例如预览面板被改成 298px 宽、
 * 关闭时没还原），只复位 doc 和 shell 状态清不掉 —— 表现为"单独跑通过、
 * 整套跑失败"。整页刷新最彻底，代价是每个用例多等一次加载。
 */
const reloadPage = async () => {
  await send('Page.enable');
  await send('Page.navigate', { url: page.url.split('?')[0] + '?reset=1' });
  // 等编辑器真正就绪，而不是死等固定时长
  for (let i = 0; i < 60; i += 1) {
    await new Promise(res => setTimeout(res, 150));
    try {
      const ready = await send('Runtime.evaluate', {
        expression: 'typeof state !== "undefined" && !!document.getElementById("preview-panel")',
        returnByValue: true
      });
      if (ready.result && ready.result.value) return true;
    } catch { /* 导航中求值会失败，重试即可 */ }
  }
  return false;
};
const evalJs = async expression => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || 'eval 失败');
  return r.result.value;
};

const docStats = () => evalJs(`(() => ({
  nodes: state.doc.scene.root.children.length,
  animations: (state.doc.interactions.animations || []).length,
  events: (state.doc.interactions.events || []).length,
  manifest: state.doc.manifest.name
}))()`);

// 开跑前的文档统计（用于跑完对比）
const before = await docStats();
console.log('开跑前文档: ' + JSON.stringify(before));

// 开跑前的文档在 Node 侧留一份：每个用例前会刷新页面，
// 页面里的备份变量会随之消失，必须由外部重新注入。
const SUITE_DOC_JSON = await evalJs(`JSON.stringify({
  manifest: state.doc.manifest,
  scene: state.doc.scene,
  interactions: state.doc.interactions
})`);

/** 重载页面后把开跑前的文档塞回去，用例才有稳定的起点。 */
const applySuiteDoc = () => evalJs(`(() => {
  const d = ${SUITE_DOC_JSON};
  state.doc.manifest = d.manifest;
  state.doc.scene = d.scene;
  state.doc.interactions = d.interactions;
  renderAll(false);
  persistDoc();
  return 1;
})()`);
const runTest = name => new Promise(resolve => {
  const child = spawn(process.execPath, [join(HERE, name), String(port)], { stdio: 'pipe' });
  let out = '';
  child.stdout.on('data', d => { out += d.toString(); });
  child.stderr.on('data', d => { out += d.toString(); });
  child.on('close', code => resolve({ name, code, out }));
});

// 每用例快照：用例之间互不污染，失败原因才准
const snapshot = () => evalJs(`(() => {
  window.__caseDocBackup = JSON.stringify({
    manifest: state.doc.manifest,
    scene: state.doc.scene,
    interactions: state.doc.interactions
  });
  return 1;
})()`);


const restore = () => evalJs(`(() => {
  if (!window.__caseDocBackup) return 0;
  const d = JSON.parse(window.__caseDocBackup);
  state.doc.manifest = d.manifest;
  state.doc.scene = d.scene;
  state.doc.interactions = d.interactions;
  // 视图状态复位：面板折叠/缩放/位置、选中、历史 —— 这些不在 doc 里，
  // 不复位就会出现"单独跑通过、整套跑失败"的顺序依赖（踩过）。
  const panel = document.getElementById('preview-panel');
  if (panel) {
    panel.classList.remove('collapsed');
    panel.classList.remove('dragging');
    applyPanelTransform(panel, 50, 50);
  }
  saveShellState({ panelCollapsed: false, panelScale: 1, panelX: 50, panelY: 50 });
  if (typeof resizeCanvas === 'function') resizeCanvas();
  state.sel = { rectId: null, channel: -1, keyIndex: -1 };
  state.drag = null;
  renderAll(false);
  if (typeof resetHistory === 'function') resetHistory();
  persistDoc();
  return 1;
})()`);

const results = [];
for (const name of TESTS) {
  // 每个用例前把页面重新加载：用例之间通过 DOM 内联样式互相污染过（面板宽度残留 298px），
  // 只复位 doc 和 shell 状态不够，整页刷新最干净。
  await reloadPage();
  await applySuiteDoc();
  await snapshot();
  const r = await runTest(name);
  await restore();
  results.push(r);
  const mark = r.code === 0 ? '✓' : '✗';
  console.log('  ' + mark + ' ' + name.padEnd(26) + (r.code === 0 ? '通过' : '失败 (exit ' + r.code + ')'));
  if (r.code !== 0) {
    const tail = r.out.trim().split('\n').slice(-6).join('\n      ');
    console.log('      ' + tail);
  }
}

// 无论成败：无条件还原
await evalJs(`(() => {
  const d = ${SUITE_DOC_JSON};
  state.doc.manifest = d.manifest;
  state.doc.scene = d.scene;
  state.doc.interactions = d.interactions;
  state.sel = { rectId: null, channel: -1, keyIndex: -1 };
  renderAll(false);
  if (typeof resetHistory === 'function') resetHistory();
  persistDoc();
  return 1;
})()`);

const after = await docStats();
console.log('\n跑完后文档: ' + JSON.stringify(after));
const docIntact = after.nodes === before.nodes &&
  after.animations === before.animations &&
  after.events === before.events;
console.log('  ' + (docIntact ? '✓' : '✗') + ' 文档未被测试污染');

const passed = results.filter(r => r.code === 0).length;
console.log('\n结果: ' + passed + '/' + results.length + ' 通过');
ws.close();
process.exit(passed === results.length && docIntact ? 0 : 1);
