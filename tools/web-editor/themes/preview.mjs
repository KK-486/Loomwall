// 把生成好的主题注入编辑器并截图，用于快速看版式还原度。
//
// 为什么不用「导入 JSON」按钮：那要模拟文件选择框，CDP 里很啰嗦；
// 主题 JSON 就在 web-editor 目录下，页面自己 fetch 一次更直接。
//
// 用法：node preview.mjs [主题名] [输出图片]
//   默认 cyberpunk → .toolchain/artifacts/theme-cyberpunk.png
'use strict';

import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const THEME = process.argv[2] || 'cyberpunk';
const OUT = process.argv[3] || '.toolchain/artifacts/theme-' + THEME + '.png';
const URL_BASE = 'http://127.0.0.1:3009/';
const PORT = 9223;

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
];

const sleep = ms => new Promise(r => setTimeout(r, ms));

function findBrowser() {
  for (const p of BROWSERS) if (existsSync(p)) return p;
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

const child = spawn(findBrowser(), [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${mkdtempSync(join(tmpdir(), 'lwpkit-preview-'))}`,
  '--no-first-run',
  '--disable-extensions',
  '--window-size=1280,900',
  '--hide-scrollbars',
  'about:blank'
], { stdio: 'ignore' });

let page = null;
for (let i = 0; i < 60 && !page; i++) {
  await sleep(250);
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    page = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
  } catch { }
}
if (!page) {
  child.kill();
  throw new Error('浏览器调试端口未就绪');
}

const cdp = await Cdp.connect(page.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');

const loaded = new Promise(resolve => {
  const onMsg = ev => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Page.loadEventFired') {
      cdp.ws.removeEventListener('message', onMsg);
      resolve();
    }
  };
  cdp.ws.addEventListener('message', onMsg);
});
await cdp.send('Page.navigate', { url: URL_BASE });
await Promise.race([loaded, sleep(8000)]);
await sleep(600);

// 注入主题：页面自己 fetch，避免把 76KB JSON 塞进 CDP 命令
const inject = `
(async () => {
  const scene = await (await fetch('/themes/${THEME}.scene.json?v=' + Date.now())).json();
  const inter = await (await fetch('/themes/${THEME}.interactions.json?v=' + Date.now())).json();
  state.doc.scene = scene;
  state.doc.interactions = inter;
  renderAll(true);
  draw();
  return { nodes: scene.root.children.length, events: (inter.events || []).length };
})()
`;
const res = await cdp.send('Runtime.evaluate', {
  expression: inject,
  awaitPromise: true,
  returnByValue: true
});
if (res.exceptionDetails) {
  console.error('注入失败: ' + (res.exceptionDetails.text || ''));
  child.kill();
  process.exit(1);
}
console.log('已注入: ' + JSON.stringify(res.result.value));
await sleep(800);

const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
writeFileSync(OUT, Buffer.from(shot.data, 'base64'));
console.log('截图: ' + OUT);

cdp.ws.close();
child.kill();
