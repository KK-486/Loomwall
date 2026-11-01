// 检查右键会不会弹出浏览器菜单：直接派发 contextmenu 事件，看是否被 preventDefault。
// 用法：node check-contextmenu.mjs [端口]
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

const result = await evalJs(`
(() => {
  const panel = document.getElementById('preview-panel');
  const canvas = document.getElementById('stage');
  const notch = document.getElementById('preview-notch');
  const wrap = document.querySelector('.canvas-wrap');

  // dispatchEvent 返回 false 表示有监听器调用了 preventDefault
  const fire = el => {
    const ev = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 });
    el.dispatchEvent(ev);
    return { prevented: ev.defaultPrevented, target: el.id || el.className };
  };

  return {
    panel: fire(panel),
    canvas: fire(canvas),
    notch: fire(notch),
    wrap: fire(wrap),
    hasPanelListener: typeof panel.oncontextmenu === 'function'
  };
})()
`);

console.log('contextmenu 是否被拦截');
for (const key of ['panel', 'canvas', 'notch', 'wrap']) {
  const r = result[key];
  console.log('  ' + key.padEnd(7) + ' prevented=' + r.prevented + '   ' + (r.prevented ? '✓ 不会弹菜单' : '✗ 会弹菜单'));
}

const ok = result.panel.prevented && result.canvas.prevented && result.notch.prevented && result.wrap.prevented;
console.log(ok ? '\n✓ 右键菜单已被全部拦住' : '\n✗ 还有元素会弹菜单');
ws.close();
process.exit(ok ? 0 : 1);
