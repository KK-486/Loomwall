// 三种界面状态的面板结构对比：
//   A. 未选中图层 —— 只有全局开关 + 4 个参数
//   B. 选中图层（跟随全局）—— 全局行尾多出「独立」勾选 + 一行"跟随全局"提示
//   C. 选中图层（独立）—— 多出 本图层启用 / 本层景深 / 本层振幅 / 本层方向
//
// 用法：node check-panel-states.mjs [端口]
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

const labels = () => evalJs(`
(() => {
  const box = document.getElementById('inspector');
  return [...box.querySelectorAll('label')].map(e => (e.textContent || '').trim()).filter(Boolean);
})()
`);

// A. 未选中
await evalJs(`(() => { state.sel.rectId = null; renderInspector(); return 1; })()`);
const a = await labels();
console.log('A. 未选中图层');
console.log('   ' + a.join(' → '));

// B. 选中 + 跟随全局
await evalJs(`
(() => {
  const leaf = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children);
  leaf.parallaxMode = 'global';
  state.sel.rectId = leaf.id;
  renderInspector();
  return 1;
})()
`);
const b = await labels();
console.log('\nB. 选中图层 · 跟随全局');
console.log('   ' + b.join(' → '));

// C. 选中 + 独立
await evalJs(`
(() => {
  const leaf = state.doc.scene.root.children.find(n => n.type === 'rect' && !n.children);
  leaf.parallaxMode = 'custom';
  renderInspector();
  return 1;
})()
`);
const c = await labels();
console.log('\nC. 选中图层 · 独立');
console.log('   ' + c.join(' → '));

// v3 断言：只有一组滑块，靠标题区分当前上下文；独立时多一行「方向」
// 只看视差段落（到「位置」为止），后面的元素属性与本次无关
const head = list => {
  const i = list.indexOf('位置');
  return (i < 0 ? list : list.slice(0, i)).join(',');
};
const okA = head(a) === '视差（全局）,景深,振幅,平滑,死区';
const okB = head(b) === '视差（跟随全局）,景深,振幅,平滑,死区';
const okC = head(c) === '视差（独立）,景深,振幅,平滑,死区,方向';
console.log('\n结论（v3：一组滑块，按上下文切换作用对象）');
console.log('  ' + (okA ? '✓' : '✗') + ' 未选中：视差（全局）+ 4 参数');
console.log('  ' + (okB ? '✓' : '✗') + ' 选中跟随：视差（跟随全局）+ 同样 4 参数，不重复');
console.log('  ' + (okC ? '✓' : '✗') + ' 选中独立：标题切换 + 多一行方向');

ws.close();
process.exit(okA && okB && okC ? 0 : 1);
