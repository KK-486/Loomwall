// 验证撤销/重做：覆盖"拖动位置""属性变换""粘贴"三类改动，以及 Ctrl+Z / Ctrl+Y 的行为。
// 用法：node check-undo-redo.mjs [端口]
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

// ---- 测试卫生：跑完必须还原真实文档（否则会把用户案例覆盖成测试场景）----
const __snapshotDoc = () => send('Runtime.evaluate', {
  expression: '(() => { window.__testDocBackup = JSON.stringify(state.doc); return 1; })()',
  returnByValue: true
});

const __restoreDoc = async () => {
  try {
    await send('Runtime.evaluate', {
      expression: `(() => {
        if (!window.__testDocBackup) return 0;
        const d = JSON.parse(window.__testDocBackup);
        state.doc.manifest = d.manifest;
        state.doc.scene = d.scene;
        state.doc.interactions = d.interactions;
        state.sel = { rectId: null, channel: -1, keyIndex: -1 };
        renderAll(false);
        if (typeof resetHistory === 'function') resetHistory();
        persistDoc();
        return 1;
      })()`,
      returnByValue: true
    });
  } catch { /* 还原失败不掩盖测试结论 */ }
};

const evalJs = async expression => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) {
    const d = r.exceptionDetails;
    throw new Error((d.exception && d.exception.description) || d.text || 'eval 失败');
  }
  return r.result.value;
};

const sleep = ms => new Promise(r => setTimeout(r, ms));

// 干净场景 + 基线
await evalJs(`
(() => {
  state.doc.scene.root.children = [
    { id: 'box', type: 'rect', visible: true, x: 10, y: 10, width: 30, height: 20, color: '#FF3366FF', opacity: 1 }
  ];
  state.sel.rectId = 'box';
  renderAll(false);
  resetHistory();
  return 1;
})()
`);

await __snapshotDoc();

const snap = () => evalJs(`(() => {
  const n = state.doc.scene.root.children.find(x => x.id === 'box');
  return {
    x: n ? n.x : null,
    width: n ? n.width : null,
    count: state.doc.scene.root.children.length,
    ids: state.doc.scene.root.children.map(c => c.id),
    undo: history.undo.length,
    redo: history.redo.length
  };
})()`);

console.log('初始: ' + JSON.stringify(await snap()));

// 1) 拖动位置（连续多次改动，应合并成一步）
await evalJs(`
(() => {
  const n = state.doc.scene.root.children.find(x => x.id === 'box');
  for (let i = 1; i <= 12; i++) { n.x = 10 + i; scheduleDraw(); }
  return n.x;
})()
`);
await sleep(500);
const afterDrag = await snap();
console.log('\n1) 拖动位置后');
console.log('   x=' + afterDrag.x + '  撤销栈=' + afterDrag.undo + '（应只有 1 步）');
const dragOk = afterDrag.x === 22 && afterDrag.undo === 1;
console.log('   ' + (dragOk ? '✓' : '✗') + ' 连续拖动合并为一步');

// 2) Ctrl+Z 撤销
await evalJs(`
(() => {
  const ev = new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true });
  document.body.dispatchEvent(ev);
  return 1;
})()
`);
await sleep(150);
const afterUndo = await snap();
console.log('\n2) Ctrl+Z 之后');
console.log('   x=' + afterUndo.x + '  撤销栈=' + afterUndo.undo + '  重做栈=' + afterUndo.redo);
const undoOk = afterUndo.x === 10 && afterUndo.redo === 1;
console.log('   ' + (undoOk ? '✓' : '✗') + ' 位置回到拖动前');

// 3) Ctrl+Y 重做
await evalJs(`
(() => {
  const ev = new KeyboardEvent('keydown', { key: 'y', ctrlKey: true, bubbles: true });
  document.body.dispatchEvent(ev);
  return 1;
})()
`);
await sleep(150);
const afterRedo = await snap();
console.log('\n3) Ctrl+Y 之后');
console.log('   x=' + afterRedo.x + '  撤销栈=' + afterRedo.undo + '  重做栈=' + afterRedo.redo);
const redoOk = afterRedo.x === 22 && afterRedo.redo === 0;
console.log('   ' + (redoOk ? '✓' : '✗') + ' 位置回到拖动后');

// 4) 属性变换（改宽度）+ 粘贴，两步分别可撤销
await evalJs(`
(() => {
  const n = state.doc.scene.root.children.find(x => x.id === 'box');
  n.width = 55;
  scheduleDraw();
  return n.width;
})()
`);
await sleep(450);
await evalJs(`
(() => {
  state.sel.rectId = 'box';
  copySelectedLayer();
  pasteLayer();
  return 1;
})()
`);
await sleep(450);
const afterPaste = await snap();
console.log('\n4) 改宽度 + 粘贴后');
console.log('   宽度=' + afterPaste.width + '  图层数=' + afterPaste.count + '  ids=' + afterPaste.ids.join(','));
console.log('   撤销栈=' + afterPaste.undo + '（应为 3：拖动、改宽、粘贴）');
const pasteOk = afterPaste.count === 2 && afterPaste.width === 55 && afterPaste.undo === 3;
console.log('   ' + (pasteOk ? '✓' : '✗') + ' 粘贴后列表立即更新（无需拖动）');

// 5) 连按两次 Ctrl+Z 应依次回退：粘贴 → 宽度
await evalJs(`
(() => {
  for (let i = 0; i < 2; i++) {
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true }));
  }
  return 1;
})()
`);
await sleep(200);
const afterTwoUndo = await snap();
console.log('\n5) 连按两次 Ctrl+Z');
console.log('   宽度=' + afterTwoUndo.width + '  图层数=' + afterTwoUndo.count + '  ids=' + afterTwoUndo.ids.join(','));
const twoOk = afterTwoUndo.count === 1 && afterTwoUndo.width === 30;
console.log('   ' + (twoOk ? '✓' : '✗') + ' 依次回退粘贴与宽度');

const allOk = dragOk && undoOk && redoOk && pasteOk && twoOk;
console.log(allOk ? '\n✓ 撤销/重做全部通过' : '\n✗ 有不符合预期的项');
await __restoreDoc();
ws.close();
process.exit(allOk ? 0 : 1);
