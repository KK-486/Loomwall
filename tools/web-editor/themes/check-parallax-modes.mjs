// 验证视差面板：顺序、两档模式（跟随全局 / 单独设置）、以及数值是否真的按模式生效。
// 用法：node check-parallax-modes.mjs [端口]
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

const readPanel = () => evalJs(`
(() => {
  const box = document.getElementById('inspector');
  const labels = [...box.querySelectorAll('label')].map(e => (e.textContent || '').trim()).filter(Boolean);
  const badges = [...box.querySelectorAll('.badge')].map(e => (e.textContent || '').trim()).filter(Boolean);
  return { labels, badges };
})()
`);

// 1) 默认（跟随全局）下的面板
const followState = await evalJs(`
(() => {
  const leaf = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children);
  leaf.parallaxMode = 'global';
  leaf.parallax = 60;
  state.sel.rectId = leaf.id;
  renderInspector();
  return { id: leaf.id, mode: leaf.parallaxMode };
})()
`);
const followPanel = await readPanel();
console.log('【跟随全局】节点 ' + followState.id);
console.log('  面板顺序: ' + followPanel.labels.join(' → '));
console.log('  状态徽标: ' + followPanel.badges.join(' / '));
console.log('  含振幅/方向（应为空）: ' + (followPanel.labels.includes('振幅') || followPanel.labels.includes('方向') ? '✗ 出现了' : '✓ 未出现'));

// 2) 切到单独设置
const customState = await evalJs(`
(() => {
  const leaf = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children);
  leaf.parallaxMode = 'custom';
  renderInspector();
  return { mode: leaf.parallaxMode };
})()
`);
const customPanel = await readPanel();
console.log('\n【单独设置】');
console.log('  面板顺序: ' + customPanel.labels.join(' → '));
console.log('  状态徽标: ' + customPanel.badges.join(' / '));
console.log('  含振幅: ' + (customPanel.labels.includes('振幅') ? '✓' : '✗'));
console.log('  含方向: ' + (customPanel.labels.includes('方向') ? '✓' : '✗'));

// 3) 数值验证：同一倾斜下，global 用全局振幅&0°，custom 用自身振幅&角度
const measure = () => evalJs(`
(async () => {
  state.parallax.targetX = 1;
  state.parallax.targetY = 0;
  startParallaxLoop();
  await new Promise(r => setTimeout(r, 800));
  const leaf = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children);
  const m = nodeMatrices.get(leaf.id);
  return { e: m.e, f: m.f, tx: state.parallax.tx };
})()
`);

// 基线必须真的回到静止姿态再取，否则两次都在同一倾斜位置，差值恒为 0（测试假象）。
const restPos = () => evalJs(`
(async () => {
  state.parallax.targetX = 0;
  state.parallax.targetY = 0;
  state.parallax.tx = 0;
  state.parallax.ty = 0;
  startParallaxLoop();
  await new Promise(r => setTimeout(r, 120));
  draw();
  const l = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children);
  const m = nodeMatrices.get(l.id);
  return { e: m.e, f: m.f };
})()
`);

await evalJs(`(() => { const l = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children); l.parallax = 50; l.parallaxMode = 'global'; l.parallaxAmp = 12; l.parallaxAngle = 180; draw(); return 1; })()`);
const restGlobal = await restPos();
const movedGlobal = await measure();
const dGlobal = { x: movedGlobal.e - restGlobal.e, y: movedGlobal.f - restGlobal.f };

await evalJs(`(() => { const l = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children); l.parallaxMode = 'custom'; draw(); return 1; })()`);
const restCustom = await restPos();
const movedCustom = await measure();
const dCustom = { x: movedCustom.e - restCustom.e, y: movedCustom.f - restCustom.f };

console.log('\n位移对比（景深 50%，只往右倾斜）');
console.log(`  跟随全局: dx=${dGlobal.x.toFixed(1)} dy=${dGlobal.y.toFixed(1)}  （应为正、且不受本层 parallaxAmp/Angle 影响）`);
console.log(`  单独设置: dx=${dCustom.x.toFixed(1)} dy=${dCustom.y.toFixed(1)}  （本层振幅 12、角度 180 → 应为负且更大）`);
const okGlobal = dGlobal.x > 0 && Math.abs(dGlobal.y) < 0.6;
const okCustom = dCustom.x < 0 && Math.abs(dCustom.x) > Math.abs(dGlobal.x);
console.log('  ' + (okGlobal ? '✓' : '✗') + ' 跟随全局按全局参数走');
console.log('  ' + (okCustom ? '✓' : '✗') + ' 单独设置按本层参数走');

ws.close();
process.exit(okGlobal && okCustom ? 0 : 1);
