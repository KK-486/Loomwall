// 画板外壳：验证"没有触发器之后"的样子。
//
// 旧版这个用例测的是"时间轴 / 触发器 两个底部页签切换"，
// 触发器面板已整体移除，所以改成断言：底部只有时间轴、没有触发器相关元素、
// 预览面板与蓝点都在、画布能出图、保留的两个面板还在。
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
  if (r.exceptionDetails) {
    const d = r.exceptionDetails;
    throw new Error((d.exception && d.exception.description) || d.text || 'eval 失败');
  }
  return r.result.value;
};

const info = await evalJs(`(() => {
  const timeline = document.getElementById('timeline-mode');
  const canvas = document.getElementById('stage');
  const panel = document.getElementById('preview-panel');
  const box = canvas.getBoundingClientRect();
  return {
    build: typeof BUILD_TAG !== 'undefined' ? BUILD_TAG : null,
    timelineDisplay: timeline ? getComputedStyle(timeline).display : 'missing',
    canvasSize: [Math.round(box.width), Math.round(box.height)],
    hasHandle: !!document.getElementById('preview-handle'),
    panelCollapsed: panel ? panel.classList.contains('collapsed') : null,
    triggerElements: ['btn-trigger-mode', 'btn-graph-view', 'graph-board', 'graph-stage']
      .filter(id => document.getElementById(id)),
    bottomTabs: !!document.querySelector('.bottom-tabs'),
    eventList: !!document.getElementById('event-list'),
    varList: !!document.getElementById('var-list')
  };
})()`);

let ok = true;
const say = (cond, text) => { console.log('  ' + (cond ? '✓' : '✗') + ' ' + text); if (!cond) ok = false; };

console.log('外壳状态: ' + JSON.stringify(info));
say(info.timelineDisplay === 'flex', '底部时间轴可见');
say(info.canvasSize[0] > 100 && info.canvasSize[1] > 100, '画布出了尺寸（' + info.canvasSize.join('×') + '）');
say(info.hasHandle === true, '预览蓝点在');
say(info.triggerElements.length === 0, '没有触发器/关系图相关元素');
say(info.bottomTabs === false, '没有底部页签行');
say(info.eventList === true, '交互（触摸事件）列表还在');
say(info.varList === true, '变量/函数面板还在');

console.log(ok ? '\n外壳全部通过' : '\n有不符合预期的项');
ws.close();
process.exit(ok ? 0 : 1);
