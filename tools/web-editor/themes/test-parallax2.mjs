// 视差数值验证：位移量必须与景深成正比（远层小、近层大）。
// 只做数值断言，不截图——比肉眼看截图可靠。
'use strict';

import { spawn } from 'node:child_process';
import { mkdtempSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PORT = 9225;
const sleep = ms => new Promise(r => setTimeout(r, ms));

function browser() {
  for (const p of [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
  ]) if (existsSync(p)) return p;
  throw new Error('找不到浏览器');
}

class Cdp {
  constructor(ws) {
    this.ws = ws; this.id = 1; this.pending = new Map();
    ws.addEventListener('message', ev => {
      const m = JSON.parse(ev.data);
      if (m.id && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id);
        this.pending.delete(m.id);
        m.error ? reject(new Error(m.error.message)) : resolve(m.result);
      }
    });
  }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => {
      ws.addEventListener('open', res, { once: true });
      ws.addEventListener('error', rej, { once: true });
    });
    return new Cdp(ws);
  }
  send(method, params = {}) {
    const id = this.id++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
}

const child = spawn(browser(), [
  '--headless=new', `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${mkdtempSync(join(tmpdir(), 'lwpkit-par2-'))}`,
  '--no-first-run', '--disable-extensions', '--window-size=1280,900', 'about:blank'
], { stdio: 'ignore' });

let page = null;
for (let i = 0; i < 60 && !page; i++) {
  await sleep(250);
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    page = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
  } catch { }
}
if (!page) { child.kill(); throw new Error('调试端口未就绪'); }

const cdp = await Cdp.connect(page.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');
await cdp.send('Page.navigate', { url: 'http://127.0.0.1:3009/' });
await sleep(2500);

const evalJs = async expr => {
  const res = await cdp.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (res.exceptionDetails) throw new Error(res.exceptionDetails.text || 'eval 失败');
  return res.result.value;
};

await evalJs(`
(async () => {
  const scene = await (await fetch('/themes/cyberpunk.scene.json?v=' + Date.now())).json();
  state.doc.scene = scene;
  renderAll(true);
  const pick = scene.root.children.filter(n => n.type === 'rect' && n.width > 20 && n.height > 5).slice(0, 5);
  [0, 25, 50, 75, 100].forEach((d, i) => { pick[i].parallax = d; pick[i].id = 'par_' + d; });
  // 角度测试组：同景深、不同方向角
  [0, 90, 180, 270].forEach((a, i) => {
    const n = JSON.parse(JSON.stringify(pick[4]));
    n.id = 'ang_' + a;
    n.parallax = 100;
    n.parallaxAngle = a;
    n.y = 20 + i * 20;
    scene.root.children.push(n);
  });
  state.parallax.enabled = true;
  state.parallax.amp = 6;
  draw();
  return pick.length;
})()
`);

const readPositions = () => evalJs(`
(() => {
  const out = {};
  for (const n of state.doc.scene.root.children) {
    if (n.id && n.id.startsWith('par_')) {
      const m = nodeMatrices.get(n.id);
      if (m) out[n.parallax] = { e: m.e, f: m.f };
    }
  }
  return out;
})()
`);

await evalJs(`(async () => { state.parallax.targetX = 1; state.parallax.targetY = 1; startParallaxLoop(); await new Promise(r => setTimeout(r, 800)); })()`);
const br = await readPositions();

await evalJs(`(async () => { state.parallax.targetX = -1; state.parallax.targetY = -1; startParallaxLoop(); await new Promise(r => setTimeout(r, 800)); })()`);
const tl = await readPositions();

console.log('景深 → 位移（右下 vs 左上）');
const rows = Object.keys(br).map(d => ({
  depth: Number(d),
  dx: br[d].e - tl[d].e,
  dy: br[d].f - tl[d].f
})).sort((a, b) => a.depth - b.depth);

let ok = true;
for (const r of rows) {
  const expect = (r.depth / 100) * Math.abs(rows[rows.length - 1].dx);
  const pass = Math.abs(Math.abs(r.dx) - expect) < 0.6;
  if (!pass) ok = false;
  console.log(`  景深 ${String(r.depth).padStart(3)}%  dx=${r.dx.toFixed(1).padStart(7)}  dy=${r.dy.toFixed(1).padStart(7)}  ${pass ? '✓' : '✗ 期望 ' + expect.toFixed(1)}`);
}
console.log(ok ? '✓ 位移与景深成正比' : '✗ 比例不符');

// 方向角验证：先取每个节点在"静止"时的矩阵，再倾斜到右侧，比较同一个节点的位移向量。
// 不能拿不同节点互相比 —— 它们的基准坐标不同，差分会把基础位置混进来。
const readAng = () => evalJs(`
(() => {
  const out = {};
  for (const n of state.doc.scene.root.children) {
    if (n.id && n.id.startsWith('ang_')) {
      const m = nodeMatrices.get(n.id);
      if (m) out[n.parallaxAngle] = { e: m.e, f: m.f };
    }
  }
  return out;
})()
`);

await evalJs(`(async () => { state.parallax.targetX = 0; state.parallax.targetY = 0; startParallaxLoop(); await new Promise(r => setTimeout(r, 800)); })()`);
const angRest = await readAng();

await evalJs(`(async () => { state.parallax.targetX = 1; state.parallax.targetY = 0; startParallaxLoop(); await new Promise(r => setTimeout(r, 800)); })()`);
const angPos = await readAng();

console.log('\n方向角 → 位移向量（只往右倾斜，tx=1 ty=0）');
for (const a of [0, 90, 180, 270]) {
  const p = angPos[a], r = angRest[a];
  if (!p || !r) { console.log('  角度 ' + a + ' 缺失'); ok = false; continue; }
  const dx = p.e - r.e, dy = p.f - r.f;
  const deg = (Math.atan2(dy, dx) * 180 / Math.PI + 360) % 360;
  // 角度差取最短弧，偏差 ≤ 5° 视为正确
  const rawDiff = ((deg - a) % 360 + 360) % 360;
  const near = Math.min(rawDiff, 360 - rawDiff) <= 5;
  console.log(`  ${String(a).padStart(3)}°  实际方向 ${deg.toFixed(1).padStart(6)}°  dx=${dx.toFixed(1).padStart(7)} dy=${dy.toFixed(1).padStart(7)}  ${near ? '✓' : '✗'}`);
  if (!near) ok = false;
}
console.log(ok ? '✓ 方向角旋转正确（0/90/180/270）' : '✗ 方向角不符');

cdp.ws.close();
child.kill();
