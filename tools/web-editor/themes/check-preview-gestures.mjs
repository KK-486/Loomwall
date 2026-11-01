// 验证预览面板手势：双击折叠 / 长按拖动 / 右键缩放 / 顶部中央磁吸。
// 用法：node check-preview-gestures.mjs [端口]
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

// 清空状态，回到默认
await evalJs(`(() => {
  localStorage.removeItem('lwpkit.shell.v1');
  applyShellState();
  return 1;
})()`);

const read = () => evalJs(`
(() => {
  const p = document.getElementById('preview-panel');
  const st = JSON.parse(localStorage.getItem('lwpkit.shell.v1') || '{}');
  return {
    left: p.style.left, top: p.style.top, transform: p.style.transform,
    collapsed: p.classList.contains('collapsed'),
    snapped: p.classList.contains('snapped'),
    stored: st
  };
})()
`);

const before = await read();
console.log('默认状态（应贴刘海下沿、水平居中、缩放 1）');
console.log('  left=' + before.left + '  top=' + before.top + '  transform=' + before.transform);
// 默认落点已从写死的 38% 改成「贴刘海下沿」（由 topAlignedY 计算），
// 所以期望值向页面取，而不是在这里写死一个百分比。
const expectY = await evalJs(`(() => { const p = document.getElementById('preview-panel'); return topAlignedY(p); })()`);
const actualY = parseFloat(before.top);
const defaultOk = before.left === '50%' && Math.abs(actualY - expectY) < 0.5 && /scale\(1\)/.test(before.transform);
console.log('  期望 top ≈ ' + expectY.toFixed(2) + '%');
console.log('  ' + (defaultOk ? '✓' : '✗') + ' 默认落在顶部居中');

// 1) 左键长按 → 拖到左下角
const dragResult = await evalJs(`
(async () => {
  const p = document.getElementById('preview-panel');
  const box = p.getBoundingClientRect();
  const wrap = document.querySelector('.canvas-wrap').getBoundingClientRect();
  const ev = (type, x, y, button = 0) => new PointerEvent(type, {
    bubbles: true, cancelable: true, button, buttons: button === 0 ? 1 : 2, clientX: x, clientY: y, pointerId: 1, pointerType: 'mouse'
  });

  // 在面板中心按下，等过长按时长后再移动
  const cx = box.left + box.width / 2;
  const cy = box.top + box.height / 2;
  p.dispatchEvent(ev('pointerdown', cx, cy));
  await new Promise(r => setTimeout(r, 320));
  // 拖到画布区左下方
  const tx = wrap.left + wrap.width * 0.25;
  const ty = wrap.top + wrap.height * 0.75;
  window.dispatchEvent(ev('pointermove', tx, ty));
  window.dispatchEvent(ev('pointerup', tx, ty));
  await new Promise(r => setTimeout(r, 60));

  const st = JSON.parse(localStorage.getItem('lwpkit.shell.v1') || '{}');
  return { left: p.style.left, top: p.style.top, stored: st, snapped: p.classList.contains('snapped'),
           targetPx: { x: Math.round(tx), y: Math.round(ty) }, wrapBox: { l: Math.round(wrap.left), w: Math.round(wrap.width), t: Math.round(wrap.top), h: Math.round(wrap.height) } };
})()
`);
console.log('\n1) 左键长按后拖到左下');
console.log('  位置: ' + dragResult.left + ' / ' + dragResult.top);
console.log('  存储: panelX=' + dragResult.stored.panelX + ' panelY=' + dragResult.stored.panelY);
const movedOk = !/^50%$/.test(dragResult.left) && dragResult.stored.panelX < 45;
console.log('  ' + (movedOk ? '✓' : '✗') + ' 长按拖动生效');

// 2) 拖回顶部中央 → 应磁吸并复位缩放
const snapResult = await evalJs(`
(async () => {
  const p = document.getElementById('preview-panel');
  const wrap = document.querySelector('.canvas-wrap').getBoundingClientRect();
  // 先人为缩小，验证磁吸会把缩放拉回 1
  saveShellState({ panelScale: 0.6 });
  p.style.transform = 'translate(-50%, -50%) scale(0.6)';

  const ev = (type, x, y, button = 0) => new PointerEvent(type, {
    bubbles: true, cancelable: true, button, buttons: button === 0 ? 1 : 2, clientX: x, clientY: y, pointerId: 1, pointerType: 'mouse'
  });
  const box = p.getBoundingClientRect();
  p.dispatchEvent(ev('pointerdown', box.left + box.width / 2, box.top + box.height / 2));
  await new Promise(r => setTimeout(r, 320));
  const tx = wrap.left + wrap.width / 2;
  const ty = wrap.top + wrap.height * 0.38;
  window.dispatchEvent(ev('pointermove', tx, ty));
  window.dispatchEvent(ev('pointerup', tx, ty));
  await new Promise(r => setTimeout(r, 60));

  const st = JSON.parse(localStorage.getItem('lwpkit.shell.v1') || '{}');
  return { left: p.style.left, top: p.style.top, transform: p.style.transform,
           snappedClass: p.classList.contains('snapped'), storedScale: st.panelScale };
})()
`);
console.log('\n2) 拖到顶部中央但不碰刘海（先缩到 0.6 倍）');
console.log('  left=' + snapResult.left + '  top=' + snapResult.top);
console.log('  transform=' + snapResult.transform + '  磁吸高亮=' + snapResult.snappedClass);
// 磁吸规则已改成"必须碰到刘海才吸"，本用例只验证"没碰到就不吸"；
// 碰到刘海的完整行为由 check-notch-snap.mjs 覆盖。
const snapOk = snapResult.storedScale === 0.6 && snapResult.snappedClass === false;
console.log('  ' + (snapOk ? '✓' : '✗') + ' 没碰到刘海时保持原缩放、不触发磁吸');

// 3) 右键长按上下拖动 → 缩放
const scaleResult = await evalJs(`
(async () => {
  const p = document.getElementById('preview-panel');
  const box = p.getBoundingClientRect();
  const ev = (type, x, y, button, buttons) => new PointerEvent(type, {
    bubbles: true, cancelable: true, button, buttons, clientX: x, clientY: y, pointerId: 2, pointerType: 'mouse'
  });
  const cx = box.left + box.width / 2;
  const cy = box.top + box.height / 2;
  p.dispatchEvent(ev('pointerdown', cx, cy, 2, 2));
  await new Promise(r => setTimeout(r, 60));
  window.dispatchEvent(ev('pointermove', cx, cy - 120, 2, 2));   // 往上拖 = 放大
  window.dispatchEvent(ev('pointerup', cx, cy - 120, 2, 0));
  await new Promise(r => setTimeout(r, 60));
  const st = JSON.parse(localStorage.getItem('lwpkit.shell.v1') || '{}');
  const scaleNow = Number((p.style.transform.match(/scale\\(([\\d.]+)\\)/) || [])[1]);
  return { transform: p.style.transform, storedScale: st.panelScale, scaleNow };
})()
`);
console.log('\n3) 右键长按往上拖 120px');
console.log('  transform=' + scaleResult.transform);
console.log('  存储缩放=' + scaleResult.storedScale);
const scaleOk = scaleResult.scaleNow > 1 && scaleResult.storedScale > 1;
console.log('  ' + (scaleOk ? '✓' : '✗') + ' 放大生效');

// 4) 双击折叠 / 展开
const foldResult = await evalJs(`
(() => {
  const p = document.getElementById('preview-panel');
  p.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
  const first = p.classList.contains('collapsed');
  p.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
  return { first, second: p.classList.contains('collapsed') };
})()
`);
console.log('\n4) 双击');
console.log('  折叠=' + foldResult.first + '  再双击折叠=' + foldResult.second);
const foldOk = foldResult.first === true && foldResult.second === false;
console.log('  ' + (foldOk ? '✓' : '✗') + ' 折叠 / 展开');

const ok = defaultOk && movedOk && snapOk && scaleOk && foldOk;
console.log(ok ? '\n✓ 手势全部通过' : '\n✗ 有不符合预期的项');
ws.close();
process.exit(ok ? 0 : 1);
