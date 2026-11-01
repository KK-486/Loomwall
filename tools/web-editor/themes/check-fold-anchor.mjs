// 验证折叠动画的基准点：收起时画布应向"蓝点方向"收缩，展开时从蓝点向外长。
// 用法：node check-fold-anchor.mjs [端口]
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

// 复位到展开态
await evalJs(`(() => {
  const p = document.getElementById('preview-panel');
  p.classList.remove('collapsed');
  saveShellState({ panelCollapsed: false, panelScale: 1 });
  if (typeof resizeCanvas === 'function') resizeCanvas();
  return 1;
})()`);
await new Promise(r => setTimeout(r, 320));

const geom = await evalJs(`
(() => {
  const p = document.getElementById('preview-panel');
  const c = document.getElementById('stage');
  const h = document.getElementById('preview-handle');
  const cb = c.getBoundingClientRect();
  const hb = h.getBoundingClientRect();
  return {
    origin: getComputedStyle(c).transformOrigin,
    transition: getComputedStyle(c).transitionProperty + ' ' + getComputedStyle(c).transitionDuration,
    canvasCenter: { x: Math.round(cb.left + cb.width / 2), y: Math.round(cb.top + cb.height / 2) },
    handleCenter: { x: Math.round(hb.left + hb.width / 2), y: Math.round(hb.top + hb.height / 2) },
    canvasTopRight: { x: Math.round(cb.right), y: Math.round(cb.top) },
    canvasSize: { w: c.offsetWidth, h: c.offsetHeight },
    scaledRect: { w: Math.round(cb.width), h: Math.round(cb.height) }
  };
})()
`);

console.log('画布 transform-origin: ' + geom.origin);
console.log('过渡属性: ' + geom.transition);
console.log('蓝点中心: ' + JSON.stringify(geom.handleCenter));
console.log('画布右上角: ' + JSON.stringify(geom.canvasTopRight));

// origin 应是 "画布右上角 + 9px 往外"，即比右上角略右上
// transform-origin 的计算值是相对**元素自身盒子**的像素，不是页面坐标。
// 所以应该和"画布宽度 + 9px"以及"-9px"比，拿页面坐标比会得出荒谬误差（我第一版就这么错了）。
const canvasW = geom.canvasSize.w;
const m = geom.origin.match(/(-?[\d.]+)px\s+(-?[\d.]+)px/);
let originOk = false;
let msg = '';
if (!m) {
  msg = '无法解析 transform-origin';
} else {
  // computed 值通常已换算成像素
  const ox = Number(m[1]);
  const oy = Number(m[2]);
  const expectX = canvasW + 9;
  const expectY = -9;
  const dx = Math.abs(ox - expectX);
  const dy = Math.abs(oy - expectY);
  originOk = dx <= 3 && dy <= 3;
  msg = `origin=(${ox}, ${oy})  期望≈(${expectX}, ${expectY})  误差=${dx.toFixed(1)}/${dy.toFixed(1)}px`;
}
console.log('  ' + (originOk ? '✓' : '✗') + ' 缩放基准点钉在蓝点圆心  ' + msg);

// 折叠后：画布视觉尺寸应收缩到接近 0，且蓝点位置基本不动
const folded = await evalJs(`
(async () => {
  const c = document.getElementById('stage');
  const h = document.getElementById('preview-handle');
  const before = h.getBoundingClientRect();
  const collapsedBefore = document.getElementById('preview-panel').classList.contains('collapsed');
  document.getElementById('preview-handle').dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }));
  await new Promise(r => setTimeout(r, 400));
  const cb = c.getBoundingClientRect();
  const hb = h.getBoundingClientRect();
  return {
    collapsedBefore,
    collapsedAfter: document.getElementById('preview-panel').classList.contains('collapsed'),
    canvasW: Math.round(cb.width), canvasH: Math.round(cb.height),
    handleMoved: Math.round(Math.hypot(hb.left - before.left, hb.top - before.top)),
    scale: getComputedStyle(c).transform
  };
})()
`);
console.log('\n双击蓝点后');
console.log('  折叠状态: ' + folded.collapsedBefore + ' → ' + folded.collapsedAfter);
console.log('  画布视觉尺寸: ' + folded.canvasW + ' x ' + folded.canvasH + '   (transform=' + folded.scale + ')');
console.log('  蓝点位移: ' + folded.handleMoved + 'px');
const foldOk = folded.collapsedAfter === true && folded.canvasW <= 20 && folded.handleMoved <= 2;
console.log('  ' + (foldOk ? '✓' : '✗') + ' 收缩到蓝点处，且蓝点没动（还能再点）');

// 再双击展开
const expanded = await evalJs(`
(async () => {
  const c = document.getElementById('stage');
  document.getElementById('preview-handle').dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }));
  await new Promise(r => setTimeout(r, 400));
  const cb = c.getBoundingClientRect();
  return {
    collapsed: document.getElementById('preview-panel').classList.contains('collapsed'),
    canvasW: Math.round(cb.width), canvasH: Math.round(cb.height)
  };
})()
`);
console.log('\n再次双击蓝点');
console.log('  折叠状态: ' + expanded.collapsed + '   画布: ' + expanded.canvasW + ' x ' + expanded.canvasH);
const expandOk = expanded.collapsed === false && expanded.canvasW > 200;
console.log('  ' + (expandOk ? '✓' : '✗') + ' 以蓝点为基准向外展开');

// 折叠态下：面板本体长按不拖动（蓝点例外，见 check-fold-drag.mjs）
const dragWhenFolded = await evalJs(`
(async () => {
  const h = document.getElementById('preview-handle');
  h.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }));
  await new Promise(r => setTimeout(r, 350));
  const p = document.getElementById('preview-panel');
  const box = document.getElementById('stage').getBoundingClientRect();
  const ev = (type, x, y) => new PointerEvent(type, { bubbles: true, cancelable: true, button: 0, buttons: 1, clientX: x, clientY: y, pointerId: 9, pointerType: 'mouse' });
  h.dispatchEvent(ev('pointerdown', box.left + 5, box.top + 5));
  await new Promise(r => setTimeout(r, 320));
  window.dispatchEvent(ev('pointermove', box.left + 300, box.top + 400));
  window.dispatchEvent(ev('pointerup', box.left + 300, box.top + 400));
  await new Promise(r => setTimeout(r, 60));
  const after = { left: p.style.left, top: p.style.top, dragging: p.classList.contains('dragging') };
  // 还原成展开
  p.classList.remove('collapsed');
  saveShellState({ panelCollapsed: false, panelScale: 1 });
  if (typeof resizeCanvas === 'function') resizeCanvas();
  return after;
})()
`);
console.log('\n折叠态下长按拖动');
console.log('  dragging=' + dragWhenFolded.dragging + '  位置=' + dragWhenFolded.left + '/' + dragWhenFolded.top);
const noDrag = dragWhenFolded.dragging === false;
console.log('  ' + (noDrag ? '✓' : '✗') + ' 折叠时不响应拖动');

const ok = originOk && foldOk && expandOk && noDrag;
console.log(ok ? '\n✓ 折叠锚点全部通过' : '\n✗ 有不符合预期的项');
ws.close();
process.exit(ok ? 0 : 1);