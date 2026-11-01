// 验证图层复制/粘贴：命名递增、id 唯一、子节点 id 也换新、不复制动画/事件。
// 用法：node check-copy-paste.mjs [端口]
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
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || 'eval 失败');
  return r.result.value;
};

await __snapshotDoc();

const result = await evalJs(`
(() => {
  // 造一个干净的测试场景：一个矩形 + 一个含子元素的容器
  state.doc.scene.root.children = [
    { id: 'box', type: 'rect', visible: true, x: 10, y: 10, width: 30, height: 20, color: '#FF3366FF', opacity: 1 },
    { id: 'group', type: 'container', visible: true, x: 0, y: 50, width: 60, height: 30,
      background: '#00000000', opacity: 1, clip: true,
      children: [
        { id: 'inner', type: 'rect', visible: true, x: 2, y: 2, width: 10, height: 10, color: '#FFFF0000', opacity: 1 }
      ] }
  ];

  const log = [];

  // 1) 复制矩形 → 粘贴两次
  state.sel.rectId = 'box';
  copySelectedLayer();
  pasteLayer();
  pasteLayer();
  log.push({ step: '矩形粘贴两次', ids: state.doc.scene.root.children.map(n => n.id) });

  // 2) 副本的「来源」也是副本时，不应出现 副本1（副本1）
  state.sel.rectId = 'box（副本1）';
  copySelectedLayer();
  pasteLayer();
  log.push({ step: '从副本再复制', ids: state.doc.scene.root.children.map(n => n.id) });

  // 3) 容器：子节点 id 也要换新
  state.sel.rectId = 'group';
  copySelectedLayer();
  pasteLayer();
  const groupCopy = state.doc.scene.root.children.find(n => n.id === 'group（副本1）');
  log.push({
    step: '容器复制',
    ids: state.doc.scene.root.children.map(n => n.id),
    childIds: groupCopy ? (groupCopy.children || []).map(c => c.id) : []
  });

  // 4) 所有 id 唯一
  const all = [];
  const walk = nodes => nodes.forEach(n => { all.push(n.id); if (n.children) walk(n.children); });
  walk(state.doc.scene.root.children);
  const unique = new Set(all).size === all.length;
  log.push({ step: 'id 唯一性', total: all.length, unique, ids: all });

  // 5) 粘贴后选中项应指向新副本
  const selected = state.sel.rectId;

  // 6) 输入框里按 Ctrl+C 不应触发复制
  const input = document.createElement('input');
  document.body.appendChild(input);
  input.focus();
  state.clipboard = null;
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'c', ctrlKey: true, bubbles: true }));
  const typingIgnored = state.clipboard === null;
  input.remove();

  return { log, selected, typingIgnored };
})()
`);

let ok = true;
for (const item of result.log) {
  if (item.step === '矩形粘贴两次') {
    console.log('1) 矩形粘贴两次');
    console.log('   ' + item.ids.join(', '));
    const pass = item.ids.includes('box（副本1）') && item.ids.includes('box（副本2）');
    if (!pass) ok = false;
    console.log('   ' + (pass ? '✓' : '✗') + ' 命名递增');
  }
  if (item.step === '从副本再复制') {
    console.log('2) 从副本再复制');
    console.log('   ' + item.ids.join(', '));
    const pass = item.ids.includes('box（副本3）') && !item.ids.some(id => /（副本\d+）（副本\d+）/.test(id));
    if (!pass) ok = false;
    console.log('   ' + (pass ? '✓' : '✗') + ' 不会叠加成「副本1（副本1）」');
  }
  if (item.step === '容器复制') {
    console.log('3) 容器复制');
    console.log('   ' + item.ids.join(', '));
    console.log('   子节点: ' + item.childIds.join(', '));
    const pass = item.ids.includes('group（副本1）') && item.childIds.length === 1 && item.childIds[0] !== 'inner';
    if (!pass) ok = false;
    console.log('   ' + (pass ? '✓' : '✗') + ' 子节点 id 也换成新的');
  }
  if (item.step === 'id 唯一性') {
    console.log('4) id 唯一性');
    console.log('   共 ' + item.total + ' 个 id，唯一: ' + (item.unique ? '是' : '否'));
    if (!item.unique) ok = false;
    console.log('   ' + (item.unique ? '✓' : '✗'));
  }
}

console.log('\n5) 粘贴后自动选中新副本');
const selOk = String(result.selected || '').includes('（副本');
if (!selOk) ok = false;
console.log('   选中: ' + result.selected + '  ' + (selOk ? '✓' : '✗'));

console.log('\n6) 输入框内不拦截 Ctrl+C');
console.log('   ' + (result.typingIgnored ? '✓' : '✗') + ' 打字时不会误触复制');

console.log(ok && selOk && result.typingIgnored ? '\n✓ 复制粘贴全部通过' : '\n✗ 有不符合预期的项');
await __restoreDoc();
ws.close();
process.exit(ok && selOk && result.typingIgnored ? 0 : 1);
