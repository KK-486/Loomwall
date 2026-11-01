// 往**已经打开的**调试窗口里补注入（不新开浏览器）。
//
// 为什么需要这个：open-live.mjs 可能抢在新标签页加载完成之前注入，导致
// fetch 相对路径拿不到 base URL 而失败。这里先导航、等 load 完成、再注入。
//
// 用法：node inject-live.mjs [端口]
'use strict';

const PORT = Number(process.argv[2]) || 9226;
const URL_BASE = 'http://127.0.0.1:3009/';
const sleep = ms => new Promise(r => setTimeout(r, ms));

const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) {
  console.error('没有可用标签页');
  process.exit(1);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true });
  ws.addEventListener('error', rej, { once: true });
});

let nextId = 1;
const pending = new Map();
const events = [];
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { resolve, reject } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? reject(new Error(m.error.message)) : resolve(m.result);
  } else if (m.method) {
    events.push(m.method);
  }
});
const send = (method, params = {}) => {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
};

await send('Page.enable');
await send('Runtime.enable');
await send('Page.navigate', { url: URL_BASE });

// 等到页面里真的有 state 对象（编辑器脚本执行完）
let ready = false;
for (let i = 0; i < 40 && !ready; i++) {
  await sleep(300);
  const r = await send('Runtime.evaluate', {
    expression: 'typeof state !== "undefined" && !!state.doc && !!state.doc.scene',
    returnByValue: true
  });
  ready = r.result.value === true;
}
if (!ready) {
  console.error('编辑器没加载起来（state/canvas 不存在）');
  process.exit(1);
}

const res = await send('Runtime.evaluate', {
  expression: `
(async () => {
  const scene = await (await fetch('/themes/cyberpunk.scene.json?v=' + Date.now())).json();
  state.doc.scene = scene;
  // 几何分景深：越靠下越"近"
  for (const n of scene.root.children) {
    if (n.id === 'bg' || n.id === 'bgGlow') { n.parallax = 0; continue; }
    if (n.height > 150) { n.parallax = 0; continue; }
    // 每层独立景深 + 独立方向角，制造交叉错位
    if (n.y > 180) { n.parallax = 92; n.parallaxAngle = 0; continue; }        // 底部三键：顺边
    if (n.y > 140) { n.parallax = 70; n.parallaxAngle = 180; continue; }      // 区域设置：反相
    if (n.y > 115) { n.parallax = 58; n.parallaxAngle = 90; continue; }       // BIO DATA：垂直
    if (n.y > 60) { n.parallax = 46; n.parallaxAngle = 0; continue; }         // 快捷方式：顺边
    if (n.y > 30) { n.parallax = 30; n.parallaxAngle = 180; continue; }       // 安全等级：反相
    n.parallax = 18; n.parallaxAngle = 0;                                     // 顶部：轻顺边
  }
  state.parallax.enabled = true;
  state.parallax.amp = 4.5;
  renderAll(true);
  draw();
  return {
    nodes: scene.root.children.length,
    withDepth: scene.root.children.filter(n => n.parallax > 0).length
  };
})()
`,
  awaitPromise: true,
  returnByValue: true
});

if (res.exceptionDetails) {
  console.error('注入失败: ' + (res.exceptionDetails.text || ''));
  process.exit(1);
}
console.log('注入成功: ' + JSON.stringify(res.result.value));
ws.close();
process.exit(0);
