// 验证：刘海磁吸（碰到才吸）+ 蓝点防越界。
// 用法：node check-notch-snap.mjs [端口]
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

// 复位
await evalJs(`(() => {
  localStorage.removeItem('lwpkit.shell.v1');
  applyShellState();
  if (typeof resizeCanvas === 'function') resizeCanvas();
  return 1;
})()`);

const notchInfo = await evalJs(`
(() => {
  const n = document.getElementById('preview-notch');
  const p = document.getElementById('preview-panel');
  const nb = n.getBoundingClientRect();
  const pb = p.getBoundingClientRect();
  return {
    notch: { left: Math.round(nb.left), right: Math.round(nb.right), top: Math.round(nb.top), bottom: Math.round(nb.bottom), h: Math.round(nb.height) },
    panel: { left: Math.round(pb.left), right: Math.round(pb.right), top: Math.round(pb.top), bottom: Math.round(pb.bottom) },
    transform: p.style.transform
  };
})()
`);
console.log('刘海: ' + JSON.stringify(notchInfo.notch));
console.log('预览(默认): ' + JSON.stringify(notchInfo.panel));
console.log('  缩放: ' + notchInfo.transform);
const defaultBelowNotch = notchInfo.panel.top >= notchInfo.notch.bottom - 2;
console.log('  ' + (defaultBelowNotch ? '✓' : '✗') + ' 默认位置在刘海下方（不遮挡）');

// 1) 拖到角落：不应磁吸，且蓝点不出界
const corner = await evalJs(`
(async () => {
  const p = document.getElementById('preview-panel');
  const wrap = document.querySelector('.canvas-wrap');
  const wb = wrap.getBoundingClientRect();
  const pb = p.getBoundingClientRect();
  const ev = (type, x, y) => new PointerEvent(type, { bubbles: true, cancelable: true, button: 0, buttons: 1, clientX: x, clientY: y, pointerId: 1, pointerType: 'mouse' });
  p.dispatchEvent(ev('pointerdown', pb.left + pb.width / 2, pb.top + pb.height / 2));
  await new Promise(r => setTimeout(r, 320));
  // 猛拖到右上角外面
  window.dispatchEvent(ev('pointermove', wb.right + 300, wb.top - 300));
  window.dispatchEvent(ev('pointerup', wb.right + 300, wb.top - 300));
  await new Promise(r => setTimeout(r, 80));

  const h = document.getElementById('preview-handle').getBoundingClientRect();
  const wb2 = wrap.getBoundingClientRect();
  const pb2 = p.getBoundingClientRect();
  return {
    handle: { right: Math.round(h.right), top: Math.round(h.top) },
    wrap: { right: Math.round(wb2.right), top: Math.round(wb2.top) },
    panelTop: Math.round(pb2.top),
    snappedClass: document.querySelector('.canvas-wrap').classList.contains('snap-ready'),
    stored: JSON.parse(localStorage.getItem('lwpkit.shell.v1') || '{}')
  };
})()
`);
console.log('\n1) 往右上角猛拖（远超边界）');
console.log('  蓝点: right=' + corner.handle.right + ' top=' + corner.handle.top);
console.log('  容器: right=' + corner.wrap.right + ' top=' + corner.wrap.top);
const handleInside = corner.handle.right <= corner.wrap.right + 1 && corner.handle.top >= corner.wrap.top - 1;
console.log('  ' + (handleInside ? '✓' : '✗') + ' 蓝点仍在容器内（可按）');
const noSnapAtCorner = corner.snappedClass === false;
console.log('  ' + (noSnapAtCorner ? '✓' : '✗') + ' 角落拖动不误触发磁吸');

// 2) 拖到碰到刘海：应磁吸居中，缩放回 1
const snap = await evalJs(`
(async () => {
  const p = document.getElementById('preview-panel');
  const wrap = document.querySelector('.canvas-wrap');
  const wb = wrap.getBoundingClientRect();
  const nb = document.getElementById('preview-notch').getBoundingClientRect();
  // 先缩小，验证磁吸会把比例拉回 100%
  saveShellState({ panelScale: 0.5 });
  applyPanelTransform(p);

  const pb = p.getBoundingClientRect();
  const ev = (type, x, y) => new PointerEvent(type, { bubbles: true, cancelable: true, button: 0, buttons: 1, clientX: x, clientY: y, pointerId: 2, pointerType: 'mouse' });
  p.dispatchEvent(ev('pointerdown', pb.left + pb.width / 2, pb.top + pb.height / 2));
  await new Promise(r => setTimeout(r, 320));
  // 拖到让"上边缘"压到刘海下沿
  // 半高必须用"缩放后的实际高度"：offsetHeight 是未缩放值，
  // 用它算会以为面板已经贴上刘海（之前就是这里误判导致假失败）。
  const visualH = p.getBoundingClientRect().height;
  const targetY = nb.bottom + visualH / 2 - 6;
  window.dispatchEvent(ev('pointermove', wb.left + wb.width / 2, targetY));
  await new Promise(r => setTimeout(r, 30));
  const snappedClass = wrap.classList.contains('snap-ready');
  window.dispatchEvent(ev('pointerup', wb.left + wb.width / 2, targetY));
  await new Promise(r => setTimeout(r, 80));

  const pb2 = p.getBoundingClientRect();
  const nb2 = document.getElementById('preview-notch').getBoundingClientRect();
  const scaleNow = Number((p.style.transform.match(/scale\\(([\\d.]+)\\)/) || [])[1]);
  return {
    snappedClass,
    scaleNow,
    panelCenterX: Math.round(pb2.left + pb2.width / 2),
    wrapCenterX: Math.round(wb.left + wb.width / 2),
    panelTop: Math.round(pb2.top),
    notchBottom: Math.round(nb2.bottom),
    stored: JSON.parse(localStorage.getItem('lwpkit.shell.v1') || '{}')
  };
})()
`);
console.log('\n2) 缩小到 0.5 后拖到碰住刘海');
console.log('  磁吸高亮: ' + snap.snappedClass);
console.log('  缩放: ' + snap.scaleNow + '（应回到 1）');
console.log('  面板中心X=' + snap.panelCenterX + '  容器中心X=' + snap.wrapCenterX);
console.log('  面板上沿=' + snap.panelTop + '  刘海下沿=' + snap.notchBottom);
const centerOk = Math.abs(snap.panelCenterX - snap.wrapCenterX) <= 2;
const flushOk = snap.panelTop >= snap.notchBottom - 2 && snap.panelTop <= snap.notchBottom + 6;
const scaleResetOk = snap.scaleNow === 1;
console.log('  ' + (centerOk ? '✓' : '✗') + ' 水平居中');
console.log('  ' + (flushOk ? '✓' : '✗') + ' 上沿紧贴刘海');
console.log('  ' + (scaleResetOk ? '✓' : '✗') + ' 比例恢复 100%');

const ok = defaultBelowNotch && handleInside && noSnapAtCorner && centerOk && flushOk && scaleResetOk;
console.log(ok ? '\n✓ 刘海磁吸 + 防越界 全部通过' : '\n✗ 有不符合预期的项');
ws.close();
process.exit(ok ? 0 : 1);
