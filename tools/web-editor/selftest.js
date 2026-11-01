/*
 * 网页编辑器的无浏览器自检：
 * 用一套极简假 DOM 把 catalog.js + editor.js 真正执行一遍，
 * 目的是在不开浏览器的情况下抓到"首次渲染就抛异常"这类问题。
 *
 * 用法：node tools/web-editor/selftest.js
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const dir = __dirname;

function makeContext2d() {
  const target = { canvas: { width: 1080, height: 2340 } };
  return new Proxy(target, {
    get(t, prop) {
      if (prop === 'measureText') return () => ({ width: 12 });
      if (prop in t) return t[prop];
      return () => undefined;
    },
    set(t, prop, value) {
      t[prop] = value;
      return true;
    },
  });
}

function makeElement(tag) {
  const target = {
    tagName: String(tag || 'div').toUpperCase(),
    childNodes: [],
    style: {},
    dataset: {},
    classList: {
      add() {},
      remove() {},
      toggle() {},
      contains: () => false,
    },
    _innerHTML: '',
    textContent: '',
    value: '',
    checked: false,
    disabled: false,
    rows: 1,
    title: '',
    width: 1080,
    height: 2340,
    selectionStart: 0,
    selectionEnd: 0,
    parentElement: { clientWidth: 1200, clientHeight: 800 },
  };

  const el = new Proxy(target, {
    get(t, prop) {
      if (prop === 'innerHTML') return t._innerHTML;
      if (prop in t) return t[prop];
      switch (prop) {
        case 'appendChild':
          return (child) => {
            t.childNodes.push(child);
            return child;
          };
        case 'removeChild':
          return (child) => child;
        case 'insertBefore':
          return (child) => child;
        case 'addEventListener':
        case 'removeEventListener':
        case 'setAttribute':
        case 'removeAttribute':
        case 'focus':
        case 'blur':
        case 'click':
        case 'setPointerCapture':
        case 'releasePointerCapture':
        case 'dispatchEvent':
        case 'getBoundingClientRect':
          if (prop === 'getBoundingClientRect') return () => ({ left: 0, top: 0, width: 1080, height: 2340 });
          return () => undefined;
        case 'getContext':
          return () => makeContext2d();
        case 'getElementsByTagName':
          return () => [];
        default:
          return undefined;
      }
    },
    set(t, prop, value) {
      if (prop === 'innerHTML') {
        t._innerHTML = value;
        t.childNodes.length = 0;
        return true;
      }
      t[prop] = value;
      return true;
    },
  });

  return el;
}

const elementCache = new Map();

const documentStub = {
  getElementById(id) {
    if (!elementCache.has(id)) elementCache.set(id, makeElement('div'));
    return elementCache.get(id);
  },
  createElement(tag) {
    return makeElement(tag);
  },
  createTextNode(text) {
    return { textContent: text };
  },
  addEventListener() {},
  removeEventListener() {},
  querySelector() {
    return makeElement('div');
  },
  body: makeElement('body'),
  documentElement: makeElement('html'),
};

const windowStub = {
  devicePixelRatio: 1,
  innerWidth: 1600,
  innerHeight: 900,
  addEventListener() {},
  removeEventListener() {},
  requestAnimationFrame(cb) {
    // 不真的循环，只跑一帧，避免自检变成死循环
    if (typeof cb === 'function') cb(0);
    return 1;
  },
  cancelAnimationFrame() {},
  navigator: { clipboard: { writeText: () => Promise.resolve() } },
  Image: class {
    constructor() {
      this.naturalWidth = 10;
      this.naturalHeight = 10;
      this.complete = true;
    }
    set src(_v) {
      if (typeof this.onload === 'function') this.onload();
    }
  },
  FileReader: class {
    readAsText() {
      if (typeof this.onload === 'function') this.onload();
    }
    readAsDataURL() {
      if (typeof this.onload === 'function') this.onload();
    }
  },
  URL: { createObjectURL: () => 'blob:stub', revokeObjectURL() {} },
  Blob: class {},
  alert() {},
};

const sandbox = {
  document: documentStub,
  window: windowStub,
  navigator: windowStub.navigator,
  Image: windowStub.Image,
  FileReader: windowStub.FileReader,
  URL: windowStub.URL,
  Blob: windowStub.Blob,
  requestAnimationFrame: windowStub.requestAnimationFrame,
  cancelAnimationFrame: windowStub.cancelAnimationFrame,
  performance: { now: () => 0 },
  console,
  setTimeout: (fn) => {
    if (typeof fn === 'function') fn();
    return 0;
  },
  clearTimeout() {},
  Math,
  JSON,
  Date,
  Number,
  String,
  Array,
  Object,
  Set,
  Map,
  Promise,
  // 让脚本里的 window.addEventListener 之类能工作
  globalThis: null,

};
sandbox.globalThis = sandbox;
sandbox.window.document = documentStub;

vm.createContext(sandbox);

function runScript(file, wrap) {
  const code = fs.readFileSync(path.join(dir, file), 'utf8');
  const source = wrap ? `(function(){${code}\n;globalThis.CATALOG = typeof CATALOG !== 'undefined' ? CATALOG : globalThis.CATALOG;})()` : code;
  vm.runInContext(source, sandbox, { filename: file });
}

const problems = [];
const document = sandbox.document;

try {
  runScript('catalog.js', true);
} catch (e) {
  problems.push(`catalog.js 执行失败：${e && e.stack ? e.stack.split('\n').slice(0, 3).join('\n') : e}`);
}

const catalog = sandbox.CATALOG;
if (!Array.isArray(catalog)) {
  problems.push('CATALOG 没有被正确导出');
} else {
  const bad = catalog.filter((x) => !x.key || !x.category || !x.placeholder);
  if (bad.length) problems.push(`目录里有 ${bad.length} 条缺字段：${bad.map((b) => b.key || '(无 key)').join(', ')}`);
}

try {
  runScript('editor.js', false);
} catch (e) {
  const stack = e && e.stack ? e.stack.split('\n').slice(0, 4).join('\n    ') : String(e);
  problems.push(`editor.js 执行失败：\n    ${stack}`);
}

// 断言：渲染之后列表里必须真的有内容
const varList = document.getElementById('var-list');
if (!varList || varList.childNodes.length === 0) {
  problems.push('#var-list 渲染后为空（变量卡片一条都没插进去）');
}
const eventList = document.getElementById('event-list');
if (!eventList || eventList.childNodes.length === 0) {
  problems.push('#event-list 渲染后为空（至少应该有一条提示）');
}

// 断言：点「添加」真的能把事件加进去，并且列表会更新
// `const state` 是词法绑定，不会挂到 globalThis，所以要在同一上下文里求值来读它
vm.runInContext('globalThis.__probe = () => state.doc.interactions.events.length;', sandbox);

if (typeof sandbox.addEvent === 'function') {
  const before = sandbox.__probe();
  try {
    sandbox.addEvent('launchApp');
  } catch (e) {
    problems.push(`addEvent 抛异常：${e && e.message ? e.message : e}`);
  }
  const after = sandbox.__probe();
  if (after <= before) problems.push(`addEvent 没有新增事件（${before} → ${after}）`);

  // 加完之后卡片要出现（模板里没有元素时也不该崩）
  if (eventList && eventList.childNodes.length === 0) problems.push('addEvent 之后 #event-list 仍然为空');
} else {
  problems.push('addEvent 没有暴露到全局（脚本可能在初始化阶段就中断了）');
}

/*
 * 缩放与尺寸的耦合回归测试：
 * 拖边框时鼠标给出的是**视觉**尺寸，写回基准尺寸必须除以缩放，
 * 且 rect.x/y 必须以视觉尺寸的一半来定位，否则 scale≠100% 会跳变/偏移。
 */
vm.runInContext(`
  state.doc.scene.root.children = [
    { id: 'r1', type: 'rect', visible: true, x: 10, y: 10, width: 50, height: 10,
      color: '#FFFFFFFF', opacity: 1, scale: 2 }
  ];
  state.sel.rectId = 'r1';
  const r = state.doc.scene.root.children[0];

  // 视觉框：左上 (10,10)，视觉尺寸 100x20（因为 scale=2）
  // 手柄 2 = 右下角。拖到视觉 (110,30)：视觉尺寸不变，基准应保持 50x10
  const W = canvas.width;
  resizeRect(r, 2, { x: 110 / 100 * W, y: 30 / 100 * W });
  globalThis.__case1 = { w: r.width, h: r.height, x: r.x, y: r.y };

  // 再把右下角拖到视觉 (210, 70)：视觉 200x60，基准应为 100x30
  resizeRect(r, 2, { x: 210 / 100 * W, y: 70 / 100 * W });
  globalThis.__case2 = { w: r.width, h: r.height, x: r.x, y: r.y };
`, sandbox);

const c1 = sandbox.__case1;
const c2 = sandbox.__case2;
if (!c1 || !c2) {
  problems.push('缩放回归测试没有跑出结果');
} else {
  if (Math.abs(c1.w - 50) > 0.6 || Math.abs(c1.h - 10) > 0.6) {
    problems.push(`拖到原视觉角应保持基准 50x10，实际 ${c1.w}x${c1.h}`);
  }
  if (Math.abs(c1.x - 10) > 0.6 || Math.abs(c1.y - 10) > 0.6) {
    problems.push(`拖到原视觉角位置不该动，实际 x=${c1.x} y=${c1.y}`);
  }
  if (Math.abs(c2.w - 100) > 1.5 || Math.abs(c2.h - 30) > 1.5) {
    problems.push(`视觉 200x60 在 scale=2 下应得到基准 100x30，实际 ${c2.w}x${c2.h}`);
  }
  // 视觉尺寸 = 基准 × 缩放，且左上角要落在锚点上
  const visualW = c2.w * 2;
  if (Math.abs(visualW - 200) > 2) problems.push(`视觉宽度不对：${visualW}`);
  if (Math.abs(c2.x - 10) > 1) problems.push(`左上角应固定在锚点 10，实际 ${c2.x}`);
}

/*
 * 关键帧场景回归：缩放/位置打帧后拖边框，锚点必须钉住，不能围绕中心反复缩放。
 */
vm.runInContext(`
  state.doc.scene.root.children = [
    { id: 'r2', type: 'rect', visible: true, x: 10, y: 10, width: 50, height: 10,
      color: '#FFFFFFFF', opacity: 1 }
  ];
  state.doc.interactions.animations = [{
    id: 'anim_r2', targetId: 'r2', trigger: { type: 'always' },
    tracks: [
      { property: 'scale', durationMs: 1000, loop: 'once',
        keyframes: [{ t: 0, value: 1, easing: 'linear' }, { t: 1, value: 2, easing: 'linear' }] },
      { property: 'x', durationMs: 1000, loop: 'once',
        keyframes: [{ t: 0, value: 40, easing: 'linear' }, { t: 1, value: 40, easing: 'linear' }] },
      { t: 0, property: 'y', durationMs: 1000, loop: 'once',
        keyframes: [{ t: 0, value: 10, easing: 'linear' }, { t: 1, value: 10, easing: 'linear' }] }
    ]
  }];
  state.sel.rectId = 'r2';
  state.playhead = 1;  // 此刻 scale=2、x=40，视觉框 = x40 y10 100x20（和静态 x=10 不同）

  const rr = state.doc.scene.root.children[0];
  const W2 = canvas.width;
  const box = () => {
    const ov = overridesFor(rr, currentOverrides());
    const tt = rectTransform(rr, ov);
    return { x: tt.x, y: tt.y, w: tt.w, h: tt.h, baseW: rr.width, baseH: rr.height };
  };

  globalThis.__kfBefore = box();

  // 拖到当前视觉右下角 (140,30)：什么都不该变
  resizeRect(rr, 2, { x: 140 / 100 * W2, y: 30 / 100 * W2 });
  globalThis.__kfSame = box();

  // 拖到视觉 (240,70)：视觉 200x60 → 基准 100x30，左上角仍在 (40,10)
  resizeRect(rr, 2, { x: 240 / 100 * W2, y: 70 / 100 * W2 });
  globalThis.__kfBig = box();
`, sandbox);

const k1 = sandbox.__kfSame;
const k2 = sandbox.__kfBig;
if (!k1 || !k2) {
  problems.push('关键帧缩放回归测试没有跑出结果');
} else {
  if (Math.abs(k1.w - 100) > 1) problems.push(`打帧后拖回原角：视觉宽度应保持 100，实际 ${k1.w}`);
  if (Math.abs(k1.baseW - 50) > 1) problems.push(`打帧后拖回原角：基准宽度应保持 50，实际 ${k1.baseW}`);
  if (Math.abs(k1.x - 40) > 1) problems.push(`打帧后拖回原角：左上角应保持 40，实际 ${k1.x}`);

  if (Math.abs(k2.w - 200) > 2) problems.push(`打帧后拖到视觉 200：实际视觉宽度 ${k2.w}`);
  if (Math.abs(k2.baseW - 100) > 2) problems.push(`scale=2 时基准宽度应为 100，实际 ${k2.baseW}`);
  if (Math.abs(k2.x - 40) > 1.5) problems.push(`锚点左上角应钉在 40，实际 ${k2.x}`);
  if (Math.abs(k2.y - 10) > 1.5) problems.push(`锚点左上角应钉在 10，实际 ${k2.y}`);
  if (Math.abs(k2.h - 60) > 2) problems.push(`打帧后拖到视觉 60 高：实际 ${k2.h}`);
}

/* 地址归一化：把真机踩过的坑都固化成用例 */
const norm = sandbox.normalizePhoneAddr;
if (typeof norm !== 'function') {
  problems.push('normalizePhoneAddr 没有暴露到全局');
} else {
  const cases = [
    ['192.168.5.10.0', '192.168.5.10:8765'],   // 冒号被手输成点
    ['192.168.5.10:0', '192.168.5.10:8765'],   // 手机曾显示 :0
    ['http://192.168.5.10:8765/', '192.168.5.10:8765'],
    ['127.0.0.1', '127.0.0.1:8765'],
    ['192.168.5.10:9999', '192.168.5.10:9999'],
  ];
  for (const [input, expected] of cases) {
    const got = norm(input);
    if (got !== expected) problems.push(`地址归一化 ${input} → ${got}（应为 ${expected}）`);
  }
}

if (problems.length === 0) {
  console.log('✓ 自检通过：目录与编辑器脚本都能完整跑一遍初始渲染');
} else {
  console.log('✗ 自检发现问题：');
  for (const p of problems) console.log('  · ' + p);
  process.exitCode = 1;
}
