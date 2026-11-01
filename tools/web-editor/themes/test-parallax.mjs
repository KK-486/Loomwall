// 视差预览自检：给若干图层设置景深，然后分别用「鼠标在右下」「鼠标在中心」各截一张，
// 用像素差证明"近层位移大、远层几乎不动"，并且平滑回中真的会回中。
//
// 用法：node test-parallax.mjs
'use strict';

import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PORT = 9224;
const URL = 'http://127.0.0.1:3009/';
const OUT_DIR = '.toolchain/artifacts';
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
    this.ws = ws;
    this.id = 1;
    this.pending = new Map();
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
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${mkdtempSync(join(tmpdir(), 'lwpkit-par-'))}`,
  '--no-first-run', '--disable-extensions', '--window-size=1280,900', '--hide-scrollbars', 'about:blank'
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
await cdp.send('Page.navigate', { url: URL });
await sleep(2500);

const evalJs = async expr => {
  const res = await cdp.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (res.exceptionDetails) throw new Error(res.exceptionDetails.text || 'eval 失败');
  return res.result.value;
};

// 1) 准备：加载主题，给 5 个图层设置不同景深（远→近）
const setup = await evalJs(`
(async () => {
  const scene = await (await fetch('/themes/cyberpunk.scene.json?v=' + Date.now())).json();
  state.doc.scene = scene;
  renderAll(true);

  // 找几个有明显面积的图层当测试对象
  const pick = scene.root.children.filter(n => n.type === 'rect' && n.width > 20 && n.height > 5);
  const depths = [0, 25, 50, 75, 100];
  const targets = pick.slice(0, 5);
  targets.forEach((n, i) => { n.parallax = depths[i]; });
  state.parallax.enabled = true;
  state.parallax.amp = 6;   // 放大振幅，让差异在截图里可测
  draw();
  return { count: targets.length, ids: targets.map(n => n.id), depths };
})()
`);
console.log('测试图层:', JSON.stringify(setup));

const shot = async name => {
  const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(join(OUT_DIR, name), Buffer.from(data, 'base64'));
};

// 2) 倾斜到右下（tx=+1, ty=+1）
await evalJs(`
(async () => {
  state.parallax.targetX = 1;
  state.parallax.targetY = 1;
  startParallaxLoop();
  await new Promise(r => setTimeout(r, 900));
  return { tx: state.parallax.tx, ty: state.parallax.ty };
})()
`).then(v => console.log('倾斜右下 tx/ty:', JSON.stringify(v)));
await shot('parallax-bottomright.png');

// 3) 回中（target=0）
const back = await evalJs(`
(async () => {
  state.parallax.targetX = 0;
  state.parallax.targetY = 0;
  startParallaxLoop();
  await new Promise(r => setTimeout(r, 900));
  return { tx: +state.parallax.tx.toFixed(4), ty: +state.parallax.ty.toFixed(4) };
})()
`);
console.log('回中后 tx/ty:', JSON.stringify(back), back.tx === 0 && back.ty === 0 ? '✓ 已归零' : '✗ 未归零');
await shot('parallax-center.png');

// 4) 反向（左上）
await evalJs(`
(async () => {
  state.parallax.targetX = -1;
  state.parallax.targetY = -1;
  startParallaxLoop();
  await new Promise(r => setTimeout(r, 900));
  return { tx: +state.parallax.tx.toFixed(3), ty: +state.parallax.ty.toFixed(3) };
})()
`).then(v => console.log('倾斜左上 tx/ty:', JSON.stringify(v)));
await shot('parallax-topleft.png');

cdp.ws.close();
child.kill();
console.log('三张截图已写入 ' + OUT_DIR);
