// 验证视差 v2 的语义（这才是关键：谁听谁的）
//
// 断言表：
//   全局开 + 跟随   → 有位移，位移由全局"统一景深"决定
//   全局关 + 跟随   → 无位移
//   全局关 + 独立开 → 有位移（不受全局开关影响）
//   全局开 + 独立关 → 无位移
//   全局统一景深改变 → 跟随者位移成比例变化（验证"景深不来自图层"）
//
// 用法：node check-parallax-v2.mjs [端口]
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

// 准备：选一个测试图层，清掉全局设置，用一个已知姿态
await evalJs(`
(() => {
  state.doc.manifest.parallax = { enabled: true, depth: 50, amp: 6, smoothing: 0.3, deadzone: 0 };
  const leaf = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children);
  leaf.parallaxMode = 'global';
  delete leaf.parallax;
  delete leaf.parallaxAmp;
  delete leaf.parallaxAngle;
  delete leaf.parallaxEnabled;
  state.__testNode = leaf.id;
  return 1;
})()
`);

/** 在指定倾斜下测量该图层的位移（相对静止姿态）。 */
async function measure(setup) {
  return evalJs(`
(async () => {
  ${setup}
  const id = state.__testNode;
  state.parallax.tx = 0; state.parallax.ty = 0;
  state.parallax.targetX = 0; state.parallax.targetY = 0;
  draw();
  const rest = nodeMatrices.get(id);
  const restE = rest.e, restF = rest.f;

  state.parallax.targetX = 1; state.parallax.targetY = 0;
  state.parallax.tx = 1; state.parallax.ty = 0;
  draw();
  const m = nodeMatrices.get(id);
  return { dx: +(m.e - restE).toFixed(2), dy: +(m.f - restF).toFixed(2) };
})()
`);
}

const cases = [
  ['全局开 + 跟随全局', `setParallaxSetting('enabled', true); const n = state.doc.scene.root.children.find(x => x.id === state.__testNode); n.parallaxMode = 'global';`, v => Math.abs(v.dx) > 0.5],
  ['全局关 + 跟随全局', `setParallaxSetting('enabled', false); const n = state.doc.scene.root.children.find(x => x.id === state.__testNode); n.parallaxMode = 'global';`, v => Math.abs(v.dx) < 0.01],
  ['全局关 + 独立且启用', `setParallaxSetting('enabled', false); const n = state.doc.scene.root.children.find(x => x.id === state.__testNode); n.parallaxMode = 'custom'; n.parallaxEnabled = true; n.parallax = 60; n.parallaxAmp = 6; n.parallaxAngle = 0;`, v => Math.abs(v.dx) > 0.5],
  ['全局开 + 独立但关闭', `setParallaxSetting('enabled', true); const n = state.doc.scene.root.children.find(x => x.id === state.__testNode); n.parallaxMode = 'custom'; n.parallaxEnabled = false; n.parallax = 60;`, v => Math.abs(v.dx) < 0.01]
];

console.log('语义验证（倾斜 tx=1，只往右）');
let ok = true;
for (const [name, setup, expect] of cases) {
  const v = await measure(setup);
  const pass = expect(v);
  if (!pass) ok = false;
  console.log(`  ${pass ? '✓' : '✗'} ${name.padEnd(22)} dx=${String(v.dx).padStart(7)} dy=${String(v.dy).padStart(5)}`);
}

// 统一景深：跟随者的位移必须随全局 depth 成比例，和图层自身 parallax 无关
const followDepth = async depth => {
  await evalJs(`
(() => {
  setParallaxSetting('enabled', true);
  setParallaxSetting('depth', ${depth});
  const n = state.doc.scene.root.children.find(x => x.id === state.__testNode);
  n.parallaxMode = 'global';
  n.parallax = 999;   // 故意设一个离谱的层内景深，验证它被忽略
  return 1;
})()
`);
  return measure('/* 已就绪 */');
};
const d20 = await followDepth(20);
const d40 = await followDepth(40);
const ratio = d40.dx / d20.dx;
const ratioOk = Math.abs(ratio - 2) < 0.05;
console.log(`\n统一景深（跟随者，层内 parallax 故意设成 999 验证被忽略）`);
console.log(`  depth=20 → dx=${d20.dx}   depth=40 → dx=${d40.dx}   比值=${ratio.toFixed(2)} ${ratioOk ? '✓' : '✗'}`);
if (!ratioOk) ok = false;

console.log(ok ? '\n✓ v2 语义全部符合预期' : '\n✗ 有不符合预期的项');
ws.close();
process.exit(ok ? 0 : 1);
