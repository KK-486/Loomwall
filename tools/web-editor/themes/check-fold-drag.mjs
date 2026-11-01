// 验证：折叠后蓝点仍可拖动，且拖动位置生效；同时确认没有白色小十字。
// 用法：node check-fold-drag.mjs [端口]
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
const evalJs = async expression => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || 'eval 失败');
  return r.result.value;
};

// 复位：展开、缩放 1、位置居中
await evalJs(`(() => {
  const p = document.getElementById('preview-panel');
  p.classList.remove('collapsed');
  localStorage.removeItem('lwpkit.shell.v1');
  applyShellState();
  if (typeof resizeCanvas === 'function') resizeCanvas();
  return 1;
})()`);
await new Promise(r => setTimeout(r, 350));

// 1) 折叠后蓝点仍能拖
const dragFolded = await evalJs(`
(async () => {
  const p = document.getElementById('preview-panel');
  const h = document.getElementById('preview-handle');
  const wrap = document.querySelector('.canvas-wrap');

  // 先折叠
  h.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }));
  await new Promise(r => setTimeout(r, 380));
  const collapsed = p.classList.contains('collapsed');
  const before = { left: p.style.left, top: p.style.top };

  // 长按蓝点后拖到左上
  const hb = h.getBoundingClientRect();
  const ev = (type, x, y) => new PointerEvent(type, { bubbles: true, cancelable: true, button: 0, buttons: 1, clientX: x, clientY: y, pointerId: 21, pointerType: 'mouse' });
  h.dispatchEvent(ev('pointerdown', hb.left + 9, hb.top + 9));
  await new Promise(r => setTimeout(r, 320));
  const wb = wrap.getBoundingClientRect();
  window.dispatchEvent(ev('pointermove', wb.left + wb.width * 0.2, wb.top + wb.height * 0.25));
  window.dispatchEvent(ev('pointerup', wb.left + wb.width * 0.2, wb.top + wb.height * 0.25));
  await new Promise(r => setTimeout(r, 80));

  const after = { left: p.style.left, top: p.style.top };
  const st = JSON.parse(localStorage.getItem('lwpkit.shell.v1') || '{}');
  const stillCollapsed = p.classList.contains('collapsed');

  // 还原
  p.classList.remove('collapsed');
  saveShellState({ panelCollapsed: false, panelScale: 1 });
  applyShellState();
  if (typeof resizeCanvas === 'function') resizeCanvas();

  return { collapsed, stillCollapsed, before, after, stored: { x: st.panelX, y: st.panelY } };
})()
`);
console.log('折叠后拖动蓝点');
console.log('  折叠成功: ' + dragFolded.collapsed + '  拖动后仍折叠: ' + dragFolded.stillCollapsed);
console.log('  位置: ' + JSON.stringify(dragFolded.before) + ' → ' + JSON.stringify(dragFolded.after));
console.log('  存储: panelX=' + dragFolded.stored.x + ' panelY=' + dragFolded.stored.y);
const movedOk = dragFolded.after.left !== dragFolded.before.left && Number(dragFolded.stored.x) < 40;
console.log('  ' + (movedOk ? '✓' : '✗') + ' 折叠态下蓝点可拖动且位置生效');

// 2) 没有白色小十字
const glyph = await evalJs(`
(() => {
  const h = document.getElementById('preview-handle');
  const p = document.getElementById('preview-panel');
  p.classList.add('collapsed');
  const after = getComputedStyle(h, '::after').content;
  p.classList.remove('collapsed');
  return { afterContent: after };
})()
`);
console.log('\n折叠态蓝点的 ::after 内容');
console.log('  content = ' + glyph.afterContent);
const noGlyph = !glyph.afterContent || glyph.afterContent === 'none' || glyph.afterContent === 'normal';
console.log('  ' + (noGlyph ? '✓' : '✗') + ' 没有白色小十字');

const ok = dragFolded.collapsed && dragFolded.stillCollapsed && movedOk && noGlyph;
console.log(ok ? '\n✓ 折叠态拖动 + 无十字 全部通过' : '\n✗ 有不符合预期的项');
ws.close();
process.exit(ok ? 0 : 1);
