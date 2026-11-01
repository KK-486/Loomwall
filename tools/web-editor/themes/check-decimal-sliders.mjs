// 验证小数滑块（平滑 0.18 / 死区 0.04）显示与写入是否都正确。
//
// 这个是真实 bug 的回归测试：sliderWithInput 曾经硬编码 Math.round()，
// 导致小数显示成 0，而且拖动时会把真值写成 0。
//
// 用法：node check-decimal-sliders.mjs [端口]
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

// 1) 未选中图层：读全局四个滑块的实际显示值
const before = await evalJs(`
(() => {
  state.sel.rectId = null;
  state.doc.manifest.parallax = { enabled: true, depth: 45, amp: 7, smoothing: 0.18, deadzone: 0.04 };
  renderInspector();
  const inputs = [...document.querySelectorAll('#inspector input[type=number]')];
  const ranges = [...document.querySelectorAll('#inspector input[type=range]')];
  return {
    numbers: inputs.slice(0, 4).map(i => i.value),
    ranges: ranges.slice(0, 4).map(i => i.value)
  };
})()
`);
console.log('显示值（应为 45 / 7 / 0.18 / 0.04）');
console.log('  数字框: ' + before.numbers.join('  '));
console.log('  滑条:   ' + before.ranges.join('  '));

// 2) 模拟拖动平滑滑块到 0.3，检查写入值与显示
const after = await evalJs(`
(() => {
  const ranges = [...document.querySelectorAll('#inspector input[type=range]')];
  const smooth = ranges[2];
  smooth.value = '0.3';
  smooth.dispatchEvent(new Event('input', { bubbles: true }));
  const nums = [...document.querySelectorAll('#inspector input[type=number]')];
  return {
    stored: state.doc.manifest.parallax.smoothing,
    shown: nums[2].value,
    deadzoneStored: state.doc.manifest.parallax.deadzone,
    deadzoneShown: nums[3].value
  };
})()
`);
console.log('\n拖动平滑到 0.30 后');
console.log('  存储值: ' + after.stored + '   显示: ' + after.shown);
console.log('  死区未被破坏: 存储 ' + after.deadzoneStored + ' 显示 ' + after.deadzoneShown);

const okNumbers = before.numbers[2] === '0.18' && before.numbers[3] === '0.04';
const okDrag = after.stored === 0.3 && after.shown === '0.3' && after.deadzoneStored === 0.04;
console.log('\n结论');
console.log('  ' + (okNumbers ? '✓' : '✗') + ' 小数能正常显示');
console.log('  ' + (okDrag ? '✓' : '✗') + ' 拖动不会把小数抹成 0，其它滑块不受影响');

ws.close();
process.exit(okNumbers && okDrag ? 0 : 1);
