// 用 Chrome DevTools Protocol 驱动浏览器：截图 / 执行 JS / 点击 / 输入。
//
// 为什么自己写而不装 Playwright：
//   1. 零依赖（Node 22+ 自带 WebSocket 与 fetch），不需要联网下载几百 MB 浏览器；
//   2. 目的很窄——把编辑器打开、截图、改几个输入值看渲染结果；
//   3. 用系统已装的 Edge/Chrome，行为与用户实际看到的一致。
//
// 用法：
//   node cdp.mjs shot out.png [url]            截图
//   node cdp.mjs eval "<js 表达式>" [url]       在页面里求值并打印结果
//   node cdp.mjs click <x> <y> [url]           点击画布坐标（CSS 像素）
//   node cdp.mjs drag <x1> <y1> <x2> <y2> [url]  按下-移动-抬起（拖拽）
//
// 环境变量：
//   CDP_BROWSER  浏览器可执行文件路径（默认自动找 Edge/Chrome）
//   CDP_PORT     调试端口（默认 9222）
//   CDP_HEADLESS 设 0 则显示浏览器窗口（调试用）
'use strict';

import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const DEFAULT_URL = 'http://127.0.0.1:3009/';

const BROWSER_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  process.env.LOCALAPPDATA + '\\Google\\Chrome\\Application\\chrome.exe'
];

function findBrowser() {
  if (process.env.CDP_BROWSER && existsSync(process.env.CDP_BROWSER)) return process.env.CDP_BROWSER;
  for (const p of BROWSER_CANDIDATES) {
    if (p && existsSync(p)) return p;
  }
  throw new Error('找不到 Edge/Chrome，可用 CDP_BROWSER 指定路径');
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

/** 极简 CDP 客户端：发命令、等对应 id 的回复。 */
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.nextId = 1;
    this.pending = new Map();
    ws.addEventListener('message', ev => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
      }
    });
  }

  static async connect(wsUrl) {
    const ws = new WebSocket(wsUrl);
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve, { once: true });
      ws.addEventListener('error', reject, { once: true });
    });
    return new Cdp(ws);
  }

  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  close() {
    try { this.ws.close(); } catch { }
  }
}

async function launch(url) {
  const port = Number(process.env.CDP_PORT) || 9222;
  const headless = process.env.CDP_HEADLESS !== '0';
  const profile = mkdtempSync(join(tmpdir(), 'lwpkit-cdp-'));

  const args = [
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--window-size=1280,900',
    '--hide-scrollbars',
    'about:blank'
  ];
  if (headless) args.unshift('--headless=new');

  const child = spawn(findBrowser(), args, { stdio: 'ignore', detached: false });

  // 等调试端口就绪
  let page = null;
  for (let i = 0; i < 60; i++) {
    await sleep(250);
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/list`);
      const targets = await res.json();
      page = targets.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) break;
    } catch { /* 还没起来 */ }
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
      const msg = JSON.parse(ev.data);
      if (msg.method === 'Page.loadEventFired') {
        cdp.ws.removeEventListener('message', onMsg);
        resolve();
      }
    };
    cdp.ws.addEventListener('message', onMsg);
  });

  await cdp.send('Page.navigate', { url: url || DEFAULT_URL });
  await Promise.race([loaded, sleep(8000)]);
  await sleep(500); // 让首帧绘制完成

  return { cdp, child };
}

async function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  if (!cmd) {
    console.error('用法: node cdp.mjs <shot|eval|click|drag> ...');
    process.exit(2);
  }

  // 最后一个参数若以 http 开头则视为 URL
  let url = DEFAULT_URL;
  if (rest.length && String(rest[rest.length - 1]).startsWith('http')) {
    url = rest.pop();
  }

  const { cdp, child } = await launch(url);
  try {
    if (cmd === 'shot') {
      const out = rest[0] || 'shot.png';
      const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' });
      writeFileSync(out, Buffer.from(data, 'base64'));
      console.log('已保存 ' + out);
    } else if (cmd === 'eval') {
      const expression = rest.join(' ') || '1';
      const res = await cdp.send('Runtime.evaluate', {
        expression,
        returnByValue: true,
        awaitPromise: true
      });
      if (res.exceptionDetails) {
        console.error('求值异常: ' + (res.exceptionDetails.text || ''));
        process.exitCode = 1;
      } else {
        console.log(JSON.stringify(res.result.value, null, 2));
      }
    } else if (cmd === 'click' || cmd === 'drag') {
      const nums = rest.map(Number);
      const mk = (type, x, y) => cdp.send('Input.dispatchMouseEvent', {
        type, x, y, button: 'left', buttons: type === 'mouseMoved' ? 1 : 1, clickCount: 1
      });
      if (cmd === 'click') {
        const [x, y] = nums;
        await mk('mousePressed', x, y);
        await sleep(30);
        await mk('mouseReleased', x, y);
      } else {
        const [x1, y1, x2, y2] = nums;
        await mk('mousePressed', x1, y1);
        const steps = 12;
        for (let i = 1; i <= steps; i++) {
          await sleep(16);
          await mk('mouseMoved', x1 + ((x2 - x1) * i) / steps, y1 + ((y2 - y1) * i) / steps);
        }
        await mk('mouseReleased', x2, y2);
      }
      console.log('已完成 ' + cmd);
    } else {
      throw new Error('未知命令: ' + cmd);
    }
  } finally {
    cdp.close();
    child.kill();
  }
}

main().catch(e => {
  console.error('失败: ' + e.message);
  process.exit(1);
});
