// 打开一个**可见的** Chrome 窗口（带远程调试端口），把赛博朋克主题和若干景深注入进去，
// 让人可以直接用鼠标体验视差手感。
//
// 与 preview.mjs 的区别：那个是 headless 截图用，跑完就关；这个是给人用的，
// 所以不 headless、不 kill、并把注入结果留在页面里。
//
// 用法：node open-live.mjs [端口]
'use strict';

import { spawn } from 'node:child_process';
import { mkdtempSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PORT = Number(process.argv[2]) || 9226;
const URL_BASE = 'http://127.0.0.1:3009/';
const sleep = ms => new Promise(r => setTimeout(r, ms));

function browser() {
  for (const p of [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ]) if (existsSync(p)) return p;
  throw new Error('找不到 Chrome/Edge');
}

// 关键：detached + unref，脚本退出后浏览器窗口继续存在
const child = spawn(browser(), [
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${mkdtempSync(join(tmpdir(), 'lwpkit-live-'))}`,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-extensions',
  '--start-maximized',
  '--new-window',
  URL_BASE
], { stdio: 'ignore', detached: true });
child.unref();

// 等调试端口就绪
let page = null;
for (let i = 0; i < 80 && !page; i++) {
  await sleep(250);
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    page = list.find(t => t.type === 'page' && t.url.includes('127.0.0.1:3009'));
  } catch { }
}
if (!page) {
  console.error('调试端口未就绪，浏览器可能没起来');
  process.exit(1);
}

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

// 注入主题 + 景深分层：远层 0、面板 35、快捷列表 62、底部按钮 90
const injection = await send('Runtime.evaluate', {
  expression: `
(async () => {
  const scene = await (await fetch('/themes/cyberpunk.scene.json?v=' + Date.now())).json();
  state.doc.scene = scene;
  // 按几何位置分景深：越靠下、越"近"，景深越大
  for (const n of scene.root.children) {
    if (n.id === 'bg' || n.id === 'bgGlow') { n.parallax = 0; continue; }
    if (n.height > 150) { n.parallax = 0; continue; }            // 铺底层
    if (n.y > 180) { n.parallax = 92; continue; }                 // 底部三键
    if (n.y > 140) { n.parallax = 70; continue; }                 // 区域设置
    if (n.y > 115) { n.parallax = 58; continue; }                 // BIO DATA
    if (n.y > 60) { n.parallax = 46; continue; }                  // 快捷方式 / 系统卡
    if (n.y > 30) { n.parallax = 30; continue; }                  // 安全等级
    n.parallax = 18;                                              // 顶部状态
  }
  state.parallax.enabled = true;
  state.parallax.amp = 4.5;
  renderAll(true);
  draw();
  const withDepth = scene.root.children.filter(n => n.parallax > 0).length;
  return { nodes: scene.root.children.length, withDepth };
})()
`,
  awaitPromise: true,
  returnByValue: true
});

if (injection.exceptionDetails) {
  console.error('注入失败: ' + (injection.exceptionDetails.text || ''));
} else {
  console.log('已注入: ' + JSON.stringify(injection.result.value));
}

console.log('浏览器窗口已打开（PID ' + child.pid + '），调试端口 ' + PORT);
console.log('地址: ' + URL_BASE);
ws.close();
process.exit(0);
