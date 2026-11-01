// 验证「关键帧」面板已经搬到时间轴轨道区右侧：
//   1. #kf-inspector 的祖先里有 .timeline
//   2. 右栏（.col.right）里不再有 keyframe 面板
//   3. 面板与轨道区在横向并排（几何位置比较）
//
// 用法：node check-timeline-layout.mjs [端口]
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
  const kf = document.getElementById('kf-inspector');
  const tl = document.querySelector('.timeline');
  const body = document.getElementById('tl-body');
  const right = document.querySelector('.col.right');
  const inTimeline = !!(kf && tl && tl.contains(kf));
  const inRightCol = !!(kf && right && right.contains(kf));
  const kfBox = kf ? kf.getBoundingClientRect() : null;
  const bodyBox = body ? body.getBoundingClientRect() : null;
  return {
    hasKf: !!kf,
    inTimeline,
    inRightCol,
    kfLeft: kfBox ? Math.round(kfBox.left) : null,
    kfWidth: kfBox ? Math.round(kfBox.width) : null,
    tlBodyRight: bodyBox ? Math.round(bodyBox.right) : null,
    sideBySide: !!(kfBox && bodyBox && kfBox.left >= bodyBox.right - 2),
    kfHeight: kfBox ? Math.round(kfBox.height) : null,
    rightColHasKfText: right ? /^关键帧$/.test((right.querySelector('.hd') || {}).textContent || '') : false
  };
})()
`;

const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
if (r.exceptionDetails) { console.error('读取失败: ' + (r.exceptionDetails.text || '')); process.exit(1); }
const v = r.result.value;

console.log('关键帧面板位置检查');
console.log('  #kf-inspector 存在: ' + (v.hasKf ? '✓' : '✗'));
console.log('  位于 .timeline 内: ' + (v.inTimeline ? '✓' : '✗'));
console.log('  仍在右栏内:       ' + (v.inRightCol ? '✗ 是' : '✓ 否'));
console.log('  与轨道区并排:     ' + (v.sideBySide ? '✓' : '✗') + `  （轨道右边缘 ${v.tlBodyRight}px，面板左边缘 ${v.kfLeft}px）`);
console.log('  面板宽度/高度:    ' + v.kfWidth + ' × ' + v.kfHeight + ' px');

const ok = v.hasKf && v.inTimeline && !v.inRightCol && v.sideBySide;
console.log(ok ? '\n✓ 已放到轨道区右侧' : '\n✗ 位置不符合预期');
ws.close();
process.exit(ok ? 0 : 1);
