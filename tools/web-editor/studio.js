// 触发器工作台（Studio）v4
//
// 与参考实现（LayerFlow NODE STUDIO）对齐的「能力结构」：
//   · 动作节点 4 个输入：触发(bool) / 顺序(event) / 图层(layer) / 值(any)
//     —— 图层与值既能手填，也能由连线提供（连上就以连线为准）
//   · 循环：触发 + 次数/条件 输入；索引(number) / 循环体 / 完成 输出
//   · IF 分支：触发 + A/B，是/否 双出口（绿/粉），不必先接"比较"就能分支
//   · 手势：多选手势，输出「命中」布尔
//   · 数值映射 / 开关：把变量流换算成可用数值
//
// 修掉的问题：高光不自动关闭 —— 选中是唯一状态，点空白或点别的卡都会重置。
'use strict';

// ---------------------------------------------------------------- 端口类型
const PORT_TYPES = {
  bool:   { label: '布尔', pill: '布', color: '#3ddc97' },
  event:  { label: '事件', pill: '序', color: '#38bdf8' },
  number: { label: '数值', pill: '数', color: '#4a90c2' },
  layer:  { label: '图层', pill: '层', color: '#a78bfa' },
  any:    { label: '任意', pill: '任', color: '#8ea6bd' }
};

function portColor(port) {
  if (port && port.color) return port.color;
  const type = (port && port.type) ? port.type : (typeof port === 'string' ? port : 'any');
  return (PORT_TYPES[type] || PORT_TYPES.any).color;
}
function portTypeName(type) { return (PORT_TYPES[type] || PORT_TYPES.any).label; }
function portPill(type) { return (PORT_TYPES[type] || PORT_TYPES.any).pill; }
function portsCompatible(a, b) {
  if (!a || !b) return false;
  if (a === 'any' || b === 'any') return true;
  return a === b;
}

// ---------------------------------------------------------------- 节点定义
const STUDIO_DEFS = {
  // ===== 源 =====
  // 注意：这里原本还有个「点击图层」节点，已和「手势触发」合并 ——
  // 两者只差一个手势开关（单击），分成两个只会让人纠结选哪个。
  // 旧图里的 tapLayer 节点由 studioDoc() 里的迁移逻辑自动转成 gesture。
  gesture: {
    cat: 'source', name: '手势触发', icon: '🖐', desc: '多选手势匹配 · 输出命中布尔',
    hint: '先选命中目标（不选 = 整块画布），再勾手势；命中任意一个即输出真。',
    inputs: [],
    outputs: [{ id: 'match', label: '命中', type: 'bool' }],
    // 光有手势没有目标，等于"点了不知道点在哪"：整块画布都能触发，容易误触。
    // 所以这里的 target 和「点击图层」一样是一等字段，会编译成 when.target。
    params: { target: '', gestures: ['tap'] },
    inline: ['target', 'gestures']
  },
  sysVar: {
    cat: 'source', name: '系统变量', icon: '🔋', desc: '电量 / 时间 / 内存等',
    hint: '输出数值，接「比较」「数值映射」。',
    inputs: [],
    outputs: [{ id: 'value', label: '数值', type: 'number' }],
    params: { name: 'sys.battery.level' }, inline: ['name']
  },
  varChanged: {
    cat: 'source', name: '变量', icon: '🔢', desc: '读取一个变量的当前值',
    hint: '输出数值；变量在「变量 / 函数」面板里定义。',
    inputs: [],
    outputs: [{ id: 'value', label: '数值', type: 'number' }],
    params: { name: '' }, inline: ['name']
  },
  layerRef: {
    cat: 'source', name: '图层引用', icon: '🧩', desc: '选择一个图层作为引用',
    hint: '输出图层对象，连到动作节点的「图层」端口。',
    inputs: [],
    outputs: [{ id: 'layer', label: '图层', type: 'layer' }],
    params: { target: '' }, inline: ['target']
  },
  session: {
    cat: 'source', name: '出现时', icon: '🌅', desc: '壁纸进入可见状态时触发',
    hint: '回到桌面 / 解锁后触发一次。',
    inputs: [],
    outputs: [{ id: 'fire', label: '命中', type: 'bool' }],
    params: {}, inline: []
  },
  minute: {
    cat: 'source', name: '定时触发', icon: '⏱', desc: '每隔一段时间触发（仅可见时）',
    hint: '壁纸不可见时不唤醒。间隔建议 ≥ 100ms，太小会吃满每帧预算。',
    inputs: [],
    outputs: [{ id: 'fire', label: '命中', type: 'bool' }],
    // 值 + 单位。旧图里的节点没有这两个字段，编译时按 1 分处理（等价于原来的「每分钟」）
    params: { value: '1', unit: 'min' }, inline: ['value', 'unit']
  },

  // ===== 逻辑 =====
  compare: {
    cat: 'logic', name: '比较', icon: '⚖', desc: 'A / B 比较并输出布尔',
    hint: '端口接了就用端口的值，没接用下面的默认值。',
    inputs: [{ id: 'a', label: 'A', type: 'number' }, { id: 'b', label: 'B', type: 'number' }],
    outputs: [{ id: 'result', label: '结果', type: 'bool' }],
    params: { op: '<', a: '0', b: '20' }, inline: ['op', 'a', 'b']
  },
  ifBranch: {
    cat: 'logic', name: 'IF 条件分支', icon: '⑂', desc: 'if (A op B) · 是 / 否 双出口',
    hint: '触发进入；「是 / 否」两个出口各接一条动作链。',
    inputs: [
      { id: 'trigger', label: '触发', type: 'bool' },
      { id: 'a', label: 'A', type: 'number' },
      { id: 'b', label: 'B', type: 'number' }
    ],
    outputs: [
      { id: 'yes', label: '是', type: 'bool', color: '#34d399' },
      { id: 'no', label: '否', type: 'bool', color: '#fb7185' }
    ],
    params: { op: '>', a: '0', b: '20' }, inline: ['op', 'a', 'b']
  },
  logicGate: {
    cat: 'logic', name: '与 / 或', icon: '∧', desc: '两个布尔合成一个',
    hint: '「与」要求都成立，「或」任一成立。',
    inputs: [{ id: 'a', label: 'A', type: 'bool' }, { id: 'b', label: 'B', type: 'bool' }],
    outputs: [{ id: 'result', label: '结果', type: 'bool' }],
    params: { op: 'and' }, inline: ['op']
  },
  notGate: {
    cat: 'logic', name: '取反', icon: '¬', desc: '布尔取反',
    hint: '真假互换。',
    inputs: [{ id: 'in', label: '输入', type: 'bool' }],
    outputs: [{ id: 'result', label: '结果', type: 'bool' }],
    params: {}, inline: []
  },
  mapRange: {
    cat: 'logic', name: '数值映射', icon: '📐', desc: '线性映射到新的范围',
    hint: '例：电量 0~100 映射成透明度 0~1。',
    inputs: [{ id: 'value', label: '输入值', type: 'number' }],
    outputs: [{ id: 'out', label: '映射值', type: 'number' }],
    params: { inMin: '0', inMax: '100', outMin: '0', outMax: '1' }, inline: ['inMax', 'outMax']
  },
  switchNode: {
    cat: 'logic', name: '开关', icon: '🔀', desc: '布尔控制数值通断',
    hint: '控制为真时把「数值」原样输出，否则输出「关断值」。',
    inputs: [{ id: 'gate', label: '控制', type: 'bool' }, { id: 'value', label: '数值', type: 'number' }],
    outputs: [{ id: 'out', label: '输出', type: 'number' }],
    params: { off: '0' }, inline: ['off']
  },
  forLoop: {
    cat: 'logic', name: 'for 循环', icon: '🔁', desc: '按次数重复循环体',
    hint: '循环体接「顺序」；索引是数值输出。引擎侧有迭代上限。',
    inputs: [
      { id: 'trigger', label: '触发', type: 'bool' },
      { id: 'count', label: '次数', type: 'number' }
    ],
    outputs: [
      { id: 'index', label: '索引', type: 'number' },
      { id: 'body', label: '循环体', type: 'bool', color: '#34d399' },
      { id: 'done', label: '完成', type: 'bool', color: '#38bdf8' }
    ],
    params: { count: '3', start: '0', step: '1' }, inline: ['count', 'start']
  },
  whileLoop: {
    cat: 'logic', name: 'while 循环', icon: '♾', desc: '按轮次递增 · 条件成立就继续',
    hint: '条件里用「轮次变量」表示第几轮（从 0 开始）：写 i < 5 就是跑 5 轮。',
    inputs: [
      { id: 'trigger', label: '触发', type: 'bool' },
      { id: 'condition', label: '条件', type: 'bool' }
    ],
    outputs: [
      { id: 'index', label: '索引', type: 'number' },
      { id: 'body', label: '循环体', type: 'bool', color: '#34d399' },
      { id: 'done', label: '完成', type: 'bool', color: '#38bdf8' }
    ],
    // 轮次变量是 while 能不能用的关键：没有它，条件里的 i 永远是 0，
    // 用户写 i > 7 就一次都不跑（真被问到"这个函数是不是有问题"）。
    params: { var: 'i', condition: 'i < 5', max: '5' },
    inline: ['var', 'condition', 'max']
  },

  // ===== 动作 =====
  setProp: {
    cat: 'action', name: '设图层属性', icon: '🎚', desc: '改属性 · 时长>0 就是补间',
    hint: '时长 0 = 瞬移；>0 = 补间：从"现在的显示值"平滑走到目标值，可中途反向、连点不跳。',
    inputs: [
      { id: 'condition', label: '触发', type: 'bool' },
      { id: 'sequence', label: '顺序', type: 'event' },
      { id: 'layer', label: '图层', type: 'layer' },
      { id: 'value', label: '值', type: 'any' }
    ],
    outputs: [{ id: 'done', label: '完成', type: 'event', color: '#38bdf8' }],
    params: { target: '', prop: 'alpha', expr: '1', duration: '0', easing: 'easeOut' },
    // 时长/缓动必须摆在卡上：这就是"补间"的开关，藏在右侧面板里等于没有
    inline: ['target', 'prop', 'expr', 'duration', 'easing']
  },
  /**
   * 数据绑定：系统变量 → 图层属性的连续映射。
   *
   * 为什么做成节点：右侧「绑定」面板要挤下图层/属性/数据源/四个数字，
   * 三层绑定就把右栏撑满了。放进工作台后，同一块画布能同时看清
   * "触发做了什么"和"哪些属性在跟随系统数据"。
   *
   * 与「设图层属性」的区别：那个是事件驱动的一次性动作（可补间），
   * 这个是声明式跟随——数据变一次更新一次，不需要触发器，也不抢动画。
   */
  bind: {
    cat: 'action', name: '数据绑定', icon: '🔗', desc: '系统数据 → 图层属性，连续跟随',
    hint: '两种用法：① 只填数据源和区间 = 线性映射；② 在「公式」里写表达式（如 20 + 60*audio(3)^2），' +
      '此时区间被忽略。公式可用 audio(1..8) / bass() / mid() / treble() / level() / beat()。',
    inputs: [],
    outputs: [],
    params: {
      target: '', prop: 'y', expr: '',
      source: 'sys.battery.level',
      in0: '0', in1: '100', out0: '0', out1: '100'
    },
    inline: ['target', 'prop', 'source', 'expr', 'in0', 'in1', 'out0', 'out1']
  },
  setVis: {
    cat: 'action', name: '显隐', icon: '👁', desc: '显示或隐藏图层 · 可顺序续接',
    hint: '触发进入；适合做菜单开关。',
    inputs: [
      { id: 'condition', label: '触发', type: 'bool' },
      { id: 'sequence', label: '顺序', type: 'event' },
      { id: 'layer', label: '图层', type: 'layer' },
      { id: 'value', label: '值', type: 'any' }
    ],
    outputs: [{ id: 'done', label: '完成', type: 'event', color: '#38bdf8' }],
    params: { target: '', value: 'true' }, inline: ['target', 'value']
  },
  setVar: {
    cat: 'action', name: '设变量', icon: '🔣', desc: '给变量赋值 · 可顺序续接',
    hint: '表达式支持 + - * / 比较与 if(cond,a,b)。',
    inputs: [
      { id: 'condition', label: '触发', type: 'bool' },
      { id: 'sequence', label: '顺序', type: 'event' },
      { id: 'value', label: '值', type: 'any' }
    ],
    outputs: [{ id: 'done', label: '完成', type: 'event', color: '#38bdf8' }],
    params: { name: '', expr: '0' }, inline: ['name', 'expr']
  },
  playAnim: {
    cat: 'action', name: '播放动画', icon: '▶', desc: '触发动画 · 完成后续接',
    hint: '先选图层 → 只列这一层的动画组；反向 = 从尾帧倒播回首帧，收起/展开可共用一段。',
    inputs: [
      { id: 'condition', label: '触发', type: 'bool' },
      { id: 'sequence', label: '顺序', type: 'event' },
      { id: 'layer', label: '图层', type: 'layer' }
    ],
    outputs: [{ id: 'done', label: '完成', type: 'event', color: '#38bdf8' }],
    // 动画组挂在图层下：先定图层，才谈得上"列哪几组"
    params: { target: '', clip: '', reverse: 'false' },
    inline: ['target', 'clip', 'reverse']
  },
  launchApp: {
    cat: 'action', name: '启动应用', icon: '📱', desc: '按包名启动 · 可顺序续接',
    hint: '需要 Android 端启动权限；包名可在「变量 / 函数」里查。',
    inputs: [
      { id: 'condition', label: '触发', type: 'bool' },
      { id: 'sequence', label: '顺序', type: 'event' }
    ],
    outputs: [{ id: 'done', label: '完成', type: 'event', color: '#38bdf8' }],
    params: { package: '' }, inline: ['package']
  },
  openUri: {
    cat: 'action', name: '打开链接', icon: '🔗', desc: '打开网址 / 系统页面 · 可顺序续接',
    hint: '支持 https:// 与 tel: / sms: 这类 scheme。',
    inputs: [
      { id: 'condition', label: '触发', type: 'bool' },
      { id: 'sequence', label: '顺序', type: 'event' }
    ],
    outputs: [{ id: 'done', label: '完成', type: 'event', color: '#38bdf8' }],
    params: { uri: '' }, inline: ['uri']
  },
  callFn: {
    cat: 'action', name: '调用函数', icon: 'ƒ', desc: '调用已定义函数 · 可顺序续接',
    hint: '函数在「变量 / 函数」面板里定义。',
    inputs: [
      { id: 'condition', label: '触发', type: 'bool' },
      { id: 'sequence', label: '顺序', type: 'event' }
    ],
    outputs: [{ id: 'done', label: '完成', type: 'event', color: '#38bdf8' }],
    params: { name: '' }, inline: ['name']
  }
};

/**
 * 缓动选项：显示中文，存英文。
 *
 * 只写 easeOut / backOut 这种英文名，作者看不出"先快后慢"还是"冲过头再回来"，
 * 而这恰恰是弹射手感的关键，所以标签里直接写效果。
 */
const STUDIO_EASING_CHOICES = [
  ['linear', '线性（匀速）'],
  ['easeIn', '缓入 · 先慢后快'],
  ['easeOut', '缓出 · 先快后慢'],
  ['easeInOut', '缓入缓出 · 两头慢'],
  ['backOut', '回弹 · 冲过头再回来'],
  ['bounceOut', '弹跳 · 落地弹几下']
];

/** 把 easing 键翻成卡面显示用的短名。 */
function studioEasingLabel(key) {
  const hit = STUDIO_EASING_CHOICES.find(([v]) => v === key);
  return hit ? hit[1] : (key || '缓出 · 先快后慢');
}
const STUDIO_CATS = [
  ['source', '数据源', '#4a90c2'],
  ['logic', '逻辑', '#a78bfa'],
  ['action', '动作', '#3ddc97']
];

const GESTURE_OPTIONS = [
  ['tap', '单击'], ['doubleTap', '双击'], ['longPress', '长按'],
  ['swipeLeft', '左滑'], ['swipeRight', '右滑'], ['swipeUp', '上滑'], ['swipeDown', '下滑']
];

// ---------------------------------------------------------------- 状态
const studio = {
  open: false,
  selId: null,
  linking: null,
  zoom: 1,
  offset: { x: 0, y: 0 },
  previewHome: null,
  dragGhost: null,
  search: '',
  libTab: 'node',     // 'node' | 'layer'：左侧标签页
  selEdge: null,     // { from, fromPort, to, toPort }：选中的连线
  sim: { battery: 18, hour: 20, gesture: 'tap', x: 540, y: 320 }
};

function studioDoc() {
  const it = state.doc.interactions;
  if (!it.studio || typeof it.studio !== 'object') it.studio = { nodes: [], edges: [] };
  if (!Array.isArray(it.studio.nodes)) it.studio.nodes = [];
  if (!Array.isArray(it.studio.edges)) it.studio.edges = [];
  // 迁移：tapLayer 已合并进 gesture。旧图打开时自动转换，
  // 否则这些节点会因为 STUDIO_DEFS 里没有对应类型而消失（连线还在，节点没了）。
  it.studio.nodes.forEach(n => {
    if (n.type === 'tapLayer') {
      n.type = 'gesture';
      n.params = { target: (n.params && n.params.target) || '', gestures: ['tap'] };
    }
  });
  return it.studio;
}

let studioSeq = 0;
function studioNextId(prefix) {
  studioSeq += 1;
  return prefix + '_' + Date.now().toString(36).slice(-3) + studioSeq;
}

function studioInputWired(nodeId, portId) {
  return studioDoc().edges.some(e => e.to === nodeId && e.toPort === portId);
}

/**
 * 平移与缩放都写到 playfield 的 transform 上。
 * 之前把 offset 存在 studio.offset 里、渲染时却没用到，所以"拖空白不动"。
 */
function applyStudioStage() {
  const field = document.getElementById('studio-playfield');
  if (!field) return;
  field.style.transform =
    'translate(' + studio.offset.x + 'px, ' + studio.offset.y + 'px) scale(' + studio.zoom + ')';
  const label = document.getElementById('studio-zoom-label');
  if (label) label.textContent = Math.round(studio.zoom * 100) + '%';
}

/** 屏幕坐标 → 画布坐标（从库里拖进来的落点要倒推平移与缩放）。 */
function studioPointFromClient(clientX, clientY) {
  const box = document.getElementById('studio-canvas').getBoundingClientRect();
  return {
    x: (clientX - box.left - studio.offset.x) / studio.zoom,
    y: (clientY - box.top - studio.offset.y) / studio.zoom
  };
}

// ---------------------------------------------------------------- 打开 / 关闭
function openStudio() {
  if (studio.open) return;
  const layer = document.getElementById('studio');
  if (!layer) return;

  const wrap = document.querySelector('.canvas-wrap');
  const slot = document.getElementById('studio-canvas');
  if (wrap && slot) {
    // 记录原父节点**和全部内联样式**：只清空不清零会留下 298px 的残留宽度，
    // 面板回到主编辑器就占满画布，拖动被钳死在 50%，看起来像"长按拖不动"。
    studio.previewHome = {
      parent: wrap.parentElement,
      next: wrap.nextElementSibling,
      style: {
        left: wrap.style.left, top: wrap.style.top,
        width: wrap.style.width, height: wrap.style.height
      }
    };
    slot.appendChild(wrap);
    wrap.classList.add('studio-float');
    wrap.style.width = '298px';
    wrap.style.height = '596px';
    // 立刻量的话拿到的是 0（覆盖层刚 unhide，布局还没算），会被放到屏幕外。
    // 挪到下一帧再定位，并按画布尺寸钳制，保证一定看得见。
    requestAnimationFrame(() => {
      const box = slot.getBoundingClientRect();
      const left = Math.max(16, box.width - 298 - 24);
      wrap.style.left = left + 'px';
      wrap.style.top = '88px';
      addStudioFloatBar(wrap);
    });
  }

  studio.open = true;
  layer.hidden = false;
  document.body.classList.add('studio-on');

  const nameEl = document.getElementById('studio-scene-name');
  if (nameEl) nameEl.textContent = (state.doc.manifest && state.doc.manifest.name) || '未命名';

  renderStudioLibrary();
  renderStudioCanvas();
  renderStudioParams();
  renderStudioLog();
  bindStudioSim();
  applyStudioStage();
  setTimeout(() => { if (typeof resizeCanvas === 'function') resizeCanvas(); }, 60);
}

/** 悬浮预览窗的标题条：拖动它移动整窗（面板内部的手势不受影响）。 */
function addStudioFloatBar(wrap) {
  if (wrap.querySelector('.st-float-bar')) return;
  const bar = el('div', 'st-float-bar');
  bar.appendChild(el('span', 'st-float-title', '预览 · 拖动标题条移动'));
  wrap.appendChild(bar);

  bar.addEventListener('pointerdown', ev => {
    ev.preventDefault();
    ev.stopPropagation();
    const slot = document.getElementById('studio-canvas');
    const startX = ev.clientX;
    const startY = ev.clientY;
    const ox = parseFloat(wrap.style.left) || 0;
    const oy = parseFloat(wrap.style.top) || 0;
    const move = e => {
      const box = slot.getBoundingClientRect();
      const w = wrap.offsetWidth;
      const h = wrap.offsetHeight;
      let left = ox + (e.clientX - startX);
      let top = oy + (e.clientY - startY);
      left = Math.min(Math.max(0, left), Math.max(0, box.width - w));
      top = Math.min(Math.max(0, top), Math.max(0, box.height - h));
      wrap.style.left = left + 'px';
      wrap.style.top = top + 'px';
    };
    const up = () => {
      document.removeEventListener('pointermove', move);
      document.removeEventListener('pointerup', up);
    };
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', up);
  });
}

function removeStudioFloatBar(wrap) {
  const bar = wrap.querySelector('.st-float-bar');
  if (bar) bar.remove();
}
function closeStudio() {
  if (!studio.open) return;
  studio.open = false;
  const layer = document.getElementById('studio');
  if (layer) layer.hidden = true;
  document.body.classList.remove('studio-on');

  const wrap = document.querySelector('.canvas-wrap');
  const home = studio.previewHome;
  if (wrap && home && home.parent) {
    wrap.classList.remove('studio-float');
    removeStudioFloatBar(wrap);
    // 还原成进来之前的内联样式（通常是空），不要一律清空
    const prev = home.style || {};
    wrap.style.left = prev.left || '';
    wrap.style.top = prev.top || '';
    wrap.style.width = prev.width || '';
    wrap.style.height = prev.height || '';
    home.parent.insertBefore(wrap, home.next || null);
  }
  studio.previewHome = null;
  setTimeout(() => { if (typeof resizeCanvas === 'function') resizeCanvas(); }, 60);
}

// ---------------------------------------------------------------- 选中（唯一）
/** 只让一张卡高亮；传 null 表示全部取消（点空白时用）。 */
function selectStudioNode(id) {
  studio.selId = id || null;
  const stage = document.getElementById('studio-playfield');
  if (stage) {
    stage.querySelectorAll('.st-node.sel').forEach(card => {
      if (card.dataset.id !== id) card.classList.remove('sel');
    });
    if (id) {
      const target = stage.querySelector('.st-node[data-id="' + id + '"]');
      if (target) target.classList.add('sel');
    }
  }
  renderStudioParams();

  // 选中的卡片如果指定了目标图层，同步在预览里点出来 ——
  // 否则用户看着"设图层属性"这张卡，还得自己去预览里找它改的是哪一层。
  if (id) {
    const node = studioDoc().nodes.find(n => n.id === id);
    const layer = node && node.params && node.params.target;
    if (layer && typeof flashLayer === 'function') flashLayer(layer, 900);
  }
}

// ---------------------------------------------------------------- 节点库 / 图层库
// 两个标签共用一个滚动区：节点库列逻辑节点，图层库列当前预设里的图层。
// 图层项拖进画布会生成「图层引用」节点（连线后可直接喂给动作节点的「图层」口）。
function studioLayerInfo(id) {
  let found = null;
  const walk = node => {
    if (!node || found) return;
    if (node.id === id) { found = node; return; }
    (node.children || []).forEach(walk);
  };
  walk(state.doc.scene.root);
  return found;
}

function renderStudioLibrary() {
  const box = document.getElementById('studio-library-scroll');
  if (!box) return;
  box.innerHTML = '';
  if (studio.libTab === 'layer') {
    renderStudioLayerList(box);
    return;
  }

  const q = (studio.search || '').trim().toLowerCase();
  let shown = 0;

  for (const [catId, catName, catColor] of STUDIO_CATS) {
    const matched = Object.entries(STUDIO_DEFS)
      .filter(([, def]) => def.cat === catId)
      .filter(([type, def]) => !q ||
        def.name.toLowerCase().includes(q) ||
        def.desc.toLowerCase().includes(q) ||
        type.toLowerCase().includes(q));
    if (!matched.length) continue;

    const group = el('div', 'st-lib-group');
    const head = el('div', 'st-lib-head');
    const dot = el('i', 'st-dot');
    dot.style.background = catColor;
    head.appendChild(dot);
    head.appendChild(el('b', null, catName));
    head.appendChild(el('span', 'st-count', String(matched.length).padStart(2, '0')));
    group.appendChild(head);

    for (const [type, def] of matched) {
      shown++;
      const item = el('div', 'st-lib-item');
      item.dataset.type = type;
      item.appendChild(el('span', 'st-lib-icon', def.icon));
      const copy = el('div', 'st-lib-copy');
      copy.appendChild(el('b', null, def.name));
      copy.appendChild(el('span', null, def.desc));
      item.appendChild(copy);

      item.addEventListener('click', () => {
        if (studio.dragGhost) return;
        addStudioNode(type);
      });
      item.addEventListener('pointerdown', ev => {
        ev.preventDefault();
        startLibraryDrag(ev, type);
      });
      group.appendChild(item);
    }
    box.appendChild(group);
  }

  if (!shown) box.appendChild(el('div', 'hint', '没有匹配的节点'));
  const countEl = document.getElementById('studio-lib-count');
  if (countEl) countEl.textContent = String(shown);
}

/** 图层库：列出当前预设的全部图层，点击/拖拽都会生成「图层引用」节点。 */
function renderStudioLayerList(box) {
  const ids = (typeof attrLayers === 'function') ? attrLayers() : [];
  const q = (studio.search || '').trim().toLowerCase();
  const matched = ids.filter(id => !q || id.toLowerCase().includes(q));

  const head = el('div', 'st-lib-head');
  const dot = el('i', 'st-dot');
  dot.style.background = '#a78bfa';
  head.appendChild(dot);
  head.appendChild(el('b', null, '图层'));
  head.appendChild(el('span', 'st-count', String(matched.length).padStart(2, '0')));
  box.appendChild(head);

  if (!matched.length) {
    box.appendChild(el('div', 'hint', '当前预设没有图层'));
  }

  matched.forEach(id => {
    const info = studioLayerInfo(id) || {};
    const item = el('div', 'st-lib-item');
    // 当前预览里选中的图层，行上也要有标记（和画布保持同一份状态）
    if (state.sel && state.sel.rectId === id) item.classList.add('on');
    item.dataset.layer = id;
    item.appendChild(el('span', 'st-lib-icon', '🧩'));
    const copy = el('div', 'st-lib-copy');
    copy.appendChild(el('b', null, id));
    const bits = [];
    if (info.type) bits.push(info.type);
    if (info.width && info.height) bits.push(Math.round(info.width) + '×' + Math.round(info.height));
    if (info.visible === false) bits.push('已隐藏');
    copy.appendChild(el('span', null, bits.join(' · ') || '图层引用'));
    item.appendChild(copy);

    // 单击 = 在预览里选中这一层并闪一下边框（"你点的是哪一层"一眼看到）
    // 拖拽 = 生成「图层引用」节点；右边的 ＋ 也是建节点，方便不想拖的人
    item.addEventListener('click', () => {
      if (studio.dragGhost) return;
      const ok = (typeof flashLayer === 'function') ? flashLayer(id, 1400) : false;
      if (!ok) studioLog('预览里找不到图层 ' + id + '（可能已被删除）', 'warn');
      else {
        studioLog('已定位图层：' + id);
        // 行上的选中标记就地更新：不重绘整个列表，免得滚动位置跳回去
        document.querySelectorAll('.st-lib-item').forEach(row => {
          row.classList.toggle('on', row.dataset.layer === id);
        });
      }
    });
    item.addEventListener('pointerdown', ev => {
      ev.preventDefault();
      startLibraryDrag(ev, 'layerRef', id);
    });

    const addBtn = el('button', 'st-lib-add', '＋');
    addBtn.title = '生成「图层引用」节点';
    addBtn.addEventListener('pointerdown', ev => ev.stopPropagation());
    addBtn.addEventListener('click', ev => {
      ev.stopPropagation();
      addStudioNode('layerRef', undefined, undefined, id);
    });
    item.appendChild(addBtn);
    box.appendChild(item);
  });

  const countEl = document.getElementById('studio-lib-count');
  if (countEl) countEl.textContent = String(matched.length);
}
function startLibraryDrag(ev, type, presetTarget) {
  const def = STUDIO_DEFS[type];
  const ghost = el('div', 'st-ghost');
  ghost.innerHTML = '<span>' + def.icon + '</span><b>' + def.name +
    (presetTarget ? ' · ' + presetTarget : '') + '</b>';
  document.body.appendChild(ghost);
  studio.dragGhost = { type, ghost, moved: false, presetTarget: presetTarget };

  const move = e => {
    studio.dragGhost.moved = true;
    ghost.style.left = (e.clientX + 12) + 'px';
    ghost.style.top = (e.clientY + 12) + 'px';
  };
  move(ev);

  const up = e => {
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', up);
    if (ghost.parentNode) ghost.parentNode.removeChild(ghost);
    const canvas = document.getElementById('studio-canvas');
    const rect = canvas ? canvas.getBoundingClientRect() : null;
    const inside = rect && e.clientX >= rect.left && e.clientX <= rect.right &&
                   e.clientY >= rect.top && e.clientY <= rect.bottom;
    const dragged = studio.dragGhost && studio.dragGhost.moved;
    const target = studio.dragGhost && studio.dragGhost.presetTarget;
    studio.dragGhost = null;
    if (inside && dragged) {
      const pt = studioPointFromClient(e.clientX, e.clientY);
      addStudioNode(type, pt.x + 120, pt.y + 50, target);
    }
  };
  document.addEventListener('pointermove', move);
  document.addEventListener('pointerup', up);
}

function addStudioNode(type, px, py, presetTarget) {
  const def = STUDIO_DEFS[type];
  if (!def) return;
  const doc = studioDoc();
  const n = doc.nodes.length;
  const x = Number.isFinite(px) ? px - 120 : 40 + (n % 3) * 290;
  const y = Number.isFinite(py) ? py - 50 : 40 + Math.floor(n / 3) * 200;
  const node = {
    id: studioNextId(type),
    type,
    x: Math.max(0, x),
    y: Math.max(0, y),
    params: JSON.parse(JSON.stringify(def.params))
  };
  // 从图层库拖出来时直接把目标图层填好，省一次手动选择
  if (presetTarget && 'target' in node.params) node.params.target = presetTarget;
  doc.nodes.push(node);
  renderStudioCanvas();
  selectStudioNode(node.id);
  studioLog('添加节点：' + def.name);
}

function removeStudioNode(id) {
  const doc = studioDoc();
  doc.nodes = doc.nodes.filter(n => n.id !== id);
  doc.edges = doc.edges.filter(e => e.from !== id && e.to !== id);
  if (studio.selId === id) studio.selId = null;
  renderStudioCanvas();
  renderStudioParams();
}

// ---------------------------------------------------------------- 画布
function renderStudioCanvas() {
  const stage = document.getElementById('studio-playfield');
  if (!stage) return;
  const doc = studioDoc();
  stage.innerHTML = '';

  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'st-wires');
  stage.appendChild(svg);

  const els = new Map();

  for (const node of doc.nodes) {
    const def = STUDIO_DEFS[node.type];
    if (!def) continue;

    const card = el('div', 'st-node st-cat-' + def.cat);
    card.dataset.id = node.id;
    card.style.left = node.x + 'px';
    card.style.top = node.y + 'px';
    // 平移由 .studio-playfield 的 transform 负责，卡片只记自己的坐标
    if (studio.selId === node.id) card.classList.add('sel');

    const head = el('div', 'st-node-head');
    head.appendChild(el('span', 'st-node-ico', def.icon));
    const titleWrap = el('div', 'st-node-titlewrap');
    titleWrap.appendChild(el('b', null, def.name));
    titleWrap.appendChild(el('span', null, def.desc));
    head.appendChild(titleWrap);
    const menu = el('button', 'st-node-menu', '×');
    menu.title = '删除这个节点';
    menu.onclick = ev => { ev.stopPropagation(); removeStudioNode(node.id); };
    head.appendChild(menu);
    card.appendChild(head);

    const body = el('div', 'st-node-body');

    const ins = def.inputs || [];
    if (!ins.length) {
      body.appendChild(el('div', 'st-port-empty', '无输入'));
    } else {
      ins.forEach(port => {
        const row = el('div', 'st-port-row in');
        const dot = el('span', 'st-port st-port-in');
        dot.dataset.node = node.id;
        dot.dataset.port = port.id;
        dot.dataset.side = 'in';
        dot.dataset.type = port.type;
        dot.style.background = portColor(port);
        dot.title = port.label + ' · ' + portTypeName(port.type);
        if (studioInputWired(node.id, port.id)) dot.classList.add('wired');
        row.appendChild(dot);
        const pill = el('span', 'st-port-pill', portPill(port.type));
        pill.style.color = portColor(port);
        row.appendChild(pill);
        row.appendChild(el('span', 'st-port-label', port.label));
        body.appendChild(row);
      });
    }

    const mid = el('div', 'st-node-mid');
    (def.inline || []).forEach(key => {
      const field = studioInlineField(node, key);
      if (field) mid.appendChild(field);
    });
    if (mid.childNodes.length) body.appendChild(mid);

    const outs = def.outputs || [];
    if (!outs.length) {
      body.appendChild(el('div', 'st-port-empty', '无输出'));
    } else {
      outs.forEach(port => {
        const row = el('div', 'st-port-row out');
        row.appendChild(el('span', 'st-port-label', port.label));
        const pill = el('span', 'st-port-pill', portPill(port.type));
        pill.style.color = portColor(port);
        row.appendChild(pill);
        const dot = el('span', 'st-port st-port-out');
        dot.dataset.node = node.id;
        dot.dataset.port = port.id;
        dot.dataset.side = 'out';
        dot.dataset.type = port.type;
        dot.style.background = portColor(port);
        dot.title = port.label + ' · ' + portTypeName(port.type);
        row.appendChild(dot);
        body.appendChild(row);
      });
    }

    card.appendChild(body);
    if (def.hint) card.appendChild(el('div', 'st-node-foot', 'ⓘ ' + def.hint));

    card.addEventListener('pointerdown', ev => {
      if (ev.target.classList.contains('st-port')) return;
      if (['INPUT', 'SELECT', 'BUTTON', 'OPTION'].includes(ev.target.tagName)) return;
      ev.preventDefault();
      selectStudioNode(node.id);

      const startX = ev.clientX;
      const startY = ev.clientY;
      const ox = node.x;
      const oy = node.y;
      let moved = false;
      const move = e => {
        const dx = e.clientX - startX;
        const dy = e.clientY - startY;
        if (!moved && Math.abs(dx) + Math.abs(dy) < 3) return;
        moved = true;
        node.x = Math.max(0, ox + dx);
        node.y = Math.max(0, oy + dy);
        card.style.left = node.x + 'px';
        card.style.top = node.y + 'px';
        drawStudioWires(svg, els);
        bindStudioWires(svg);
      };
      const up = () => {
        document.removeEventListener('pointermove', move);
        document.removeEventListener('pointerup', up);
        if (moved) persistDoc();
      };
      document.addEventListener('pointermove', move);
      document.addEventListener('pointerup', up);
    });

    stage.appendChild(card);
    els.set(node.id, card);
  }

  drawStudioWires(svg, els);
  bindStudioPorts(stage, svg);
  bindStudioWires(svg);
  studio.svgRef = svg;      // 记住当前 SVG，重绘连线时不用重新查 DOM
  updateStudioStats();
}

function studioInlineField(node, key) {
  const def = STUDIO_DEFS[node.type];
  const wrap = el('div', 'st-inline');
  wrap.appendChild(el('span', 'st-inline-label', studioParamLabel(node.type, key)));

  const layers = (typeof attrLayers === 'function') ? attrLayers() : [];
  const vars = Object.keys(state.doc.interactions.vars || {});
  // 动画组按图层过滤：播放动画先定图层，只列这一层的组（组名 + 轨道摘要）
  const allClips = (typeof window.clips === 'function') ? window.clips() : [];
  const clipTarget = node.params.target || '';
  const clipList = clipTarget ? allClips.filter(c => c.targetId === clipTarget) : allClips;

  // 数据绑定节点的数据源：列出常用系统变量（存 sys.* 全名）
  if (key === 'source') {
    const SOURCES = [
      ['sys.battery.level', '电量 %（0-100）'],
      ['sys.battery.temp', '电池温度 °C'],
      ['sys.battery.charging', '是否充电（0/1）'],
      ['sys.notify.count', '未读通知数'],
      ['sys.mem.usedPct', '内存占用 %'],
      ['sys.storage.usedPct', '存储占用 %'],
      ['sys.time.hour', '小时（0-23）'],
      ['sys.time.minute', '分钟（0-59）'],
      ['sys.media.progress', '音乐进度 %（0-100）'],
      ['sys.media.position', '播放位置（秒）'],
      ['sys.media.duration', '歌曲总长（秒）'],
      ['sys.media.playing', '是否正在播放（0/1）'],
      ['sys.audio.level', '音频总音量（0-1）'],
      ['sys.audio.bass', '低频（0-1）'],
      ['sys.audio.mid', '中频（0-1）'],
      ['sys.audio.treble', '高频（0-1）'],
      ['sys.audio.beat', '节拍脉冲（0-1）'],
      ['sys.audio.b1', '频谱 1（最低频，0-1）'],
      ['sys.audio.b2', '频谱 2'],
      ['sys.audio.b3', '频谱 3'],
      ['sys.audio.b4', '频谱 4'],
      ['sys.audio.b5', '频谱 5'],
      ['sys.audio.b6', '频谱 6'],
      ['sys.audio.b7', '频谱 7'],
      ['sys.audio.b8', '频谱 8（最高频）'],
      ['sys.weather.temp', '气温 °C'],
      ['sys.weather.humidity', '湿度 %'],
      ['sys.weather.wind', '风速 km/h'],
      ['sys.weather.isDay', '是否白天（0/1）']
    ];
    const select = document.createElement('select');
    const keys = SOURCES.map(item => item[0]);
    const list = keys.includes(node.params.source) ? SOURCES : [[node.params.source, node.params.source]].concat(SOURCES);
    list.forEach(([value, label]) => select.appendChild(new Option(label + '  ' + value, value)));
    select.value = node.params.source || 'sys.battery.level';
    select.onchange = () => { node.params.source = select.value; };
    // 必须在这里直接 return：函数后半段才 let input;，在这一支赋值会撞 TDZ
    const holder = el('div', 'st-inline-col');
    holder.appendChild(select);
    wrap.appendChild(holder);
    return wrap;
  }
  if (key === 'gestures') {
    const holder = el('div', 'st-inline-col');
    const grid = el('div', 'st-chips-grid');
    const chosen = Array.isArray(node.params.gestures) ? node.params.gestures : [];
    GESTURE_OPTIONS.forEach(([value, label]) => {
      const chip = el('button', 'st-gchip' + (chosen.includes(value) ? ' on' : ''), label);
      chip.onclick = ev => {
        ev.stopPropagation();
        const list = Array.isArray(node.params.gestures) ? node.params.gestures.slice() : [];
        const idx = list.indexOf(value);
        if (idx >= 0) list.splice(idx, 1); else list.push(value);
        node.params.gestures = list;
        persistDoc();
        renderStudioCanvas();
        renderStudioParams();
      };
      grid.appendChild(chip);
    });
    holder.appendChild(grid);
    holder.appendChild(el('div', 'st-inline-note', '已选 ' + chosen.length + ' 个'));
    wrap.appendChild(holder);
    wrap.classList.add('col');
    return wrap;
  }

  let input;
  if (key === 'target' && layers.length > 0) {
    input = document.createElement('select');
    input.appendChild(new Option('选择图层…', ''));
    layers.forEach(l => input.appendChild(new Option(l, l)));
    input.value = node.params[key] || '';
  } else if (key === 'name' && node.type === 'varChanged' && vars.length) {
    input = document.createElement('select');
    input.appendChild(new Option('选择变量…', ''));
    vars.forEach(v => input.appendChild(new Option(v, v)));
    input.value = node.params[key] || '';
  } else if (key === 'clip') {
    // 动画组按图层过滤：先定图层，只列这一层的组，并带上组名与轨道摘要
    input = document.createElement('select');
    const emptyHint = clipTarget
      ? (clipList.length ? '选择动画组…' : '该图层还没有动画组')
      : '先选图层…';
    input.appendChild(new Option(emptyHint, ''));
    clipList.forEach((c, i) => {
      const name = c.name || ('动画组 ' + (i + 1));
      const props = (c.tracks || []).map(t => t.property).join('/');
      const loop = (c.tracks && c.tracks[0] && c.tracks[0].loop) || '';
      const suffix = props ? '  · ' + props + (loop ? ' ' + loop : '') : '  · 空组';
      input.appendChild(new Option(name + suffix, c.id));
    });
    input.value = node.params[key] || '';
  } else if (key === 'unit') {
    // 定时单位：存英文键（ms/s/min），显示中文
    input = document.createElement('select');
    [['ms', '毫秒'], ['s', '秒'], ['min', '分']].forEach(([v, label]) => input.appendChild(new Option(label, v)));
    input.value = node.params[key] || 'min';
  } else if (key === 'prop') {
    input = document.createElement('select');
    [['x', 'x（横向位置）'], ['y', 'y（纵向位置）'], ['scale', '缩放'], ['rot', '旋转'], ['alpha', '透明度']]
      .forEach(([value, label]) => input.appendChild(new Option(label, value)));
    input.value = node.params[key] || 'alpha';
  } else if (key === 'easing') {
    // 卡片上必须是下拉：手打 "easeOut" 既不友好，拼错还会静默退回线性
    input = document.createElement('select');
    STUDIO_EASING_CHOICES.forEach(([value, label]) => input.appendChild(new Option(label, value)));
    input.value = node.params[key] || 'easeOut';
    // 时长 0 表示瞬移，没有过程可缓：置灰并说明原因，避免误以为缓动没生效
    if (studioNum(node.params.duration, 0) <= 0) {
      input.disabled = true;
      input.title = '时长是 0（瞬移），缓动不参与计算；把时长改成 200~400 就会生效';
      input.classList.add('is-off');
    }
  } else if (key === 'op') {
    input = document.createElement('select');
    const opts = node.type === 'logicGate' ? [['and', '与'], ['or', '或']]
      : [['==', '='], ['!=', '≠'], ['>', '>'], ['>=', '≥'], ['<', '<'], ['<=', '≤']];
    opts.forEach(([v, t]) => input.appendChild(new Option(t, v)));
    input.value = node.params[key] || opts[0][0];
  } else if (key === 'reverse') {
    // 反向 = 动画从尾帧倒着播回首帧（播放动画专属），不要和"显示/隐藏"混在一起
    input = document.createElement('select');
    input.appendChild(new Option('正向（首帧→尾帧）', 'false'));
    input.appendChild(new Option('反向（尾帧→首帧）', 'true'));
    input.value = node.params[key] || 'false';
  } else if (key === 'value' && node.type === 'setVis') {
    // 只有「显隐」节点的值才是 显示/隐藏；定时器的值必须走数字输入框
    input = document.createElement('select');
    input.appendChild(new Option('显示', 'true'));
    input.appendChild(new Option('隐藏', 'false'));
    input.value = node.params[key] || 'false';
  } else {
    input = document.createElement('input');
    input.type = 'text';
    input.value = node.params[key] === undefined ? '' : node.params[key];
    input.placeholder = key === 'duration' ? '0 = 瞬移'
      : (def.params[key] === '' ? '空' : String(def.params[key]));
  }
  const commit = () => {
    const prev = node.params[key];
    node.params[key] = input.value;
    persistDoc();
    renderStudioParams();
    // 改「图层」会影响同卡片里「动画」下拉的内容（动画组是按图层列的），
    // 所以这里必须重画卡片，否则下拉还停在上一层。
    if (key === 'target' && prev !== input.value) renderStudioCanvas();
    // 时长在 0 与非 0 之间切换时，「缓动」的可用状态要跟着变
    if (key === 'duration' && (studioNum(prev, 0) <= 0) !== (studioNum(input.value, 0) <= 0)) {
      renderStudioCanvas();
    }
  };
  input.onchange = commit;
  if (input.tagName === 'INPUT') input.oninput = commit;
  wrap.appendChild(input);
  return wrap;
}

// ---------------------------------------------------------------- 连线
function bindStudioPorts(stage, svg) {
  stage.querySelectorAll('.st-port').forEach(port => {
    port.addEventListener('pointerdown', ev => {
      ev.preventDefault();
      ev.stopPropagation();
      if (port.dataset.side === 'out') {
        studio.linking = {
          fromId: port.dataset.node,
          fromPort: port.dataset.port,
          type: port.dataset.type
        };
        highlightCompatible(port.dataset.node, port.dataset.type);
        startStudioLinkDrag(ev, svg, port);
      } else if (studio.linking) {
        tryFinishLink(port);
      } else {
        studioLog('先按住右侧彩色圆点，再拖到左侧同色圆点', 'warn');
      }
    });
  });
}

/** 连线的点选与右键删除。命中区是透明加粗线，所以 1.6px 的细线也能点中。 */
/**
 * 重绘连线并**重新绑定**监听。
 *
 * drawStudioWires 会清空 SVG 子节点，旧的命中线连同事件监听一起消失；
 * 只重画不重绑的话，第一次点选之后所有连线就再也点不动了（踩过）。
 * 任何"只动连线"的场景都必须走这个入口。
 */
function redrawStudioWires(svg) {
  if (!svg) return;
  const stage = document.getElementById('studio-playfield');
  if (!stage) return;
  const els = new Map();
  stage.querySelectorAll('.st-node').forEach(card => els.set(card.dataset.id, card));
  drawStudioWires(svg, els);
  bindStudioWires(svg);
}
function bindStudioWires(svg) {
  if (!svg) return;
  svg.querySelectorAll('.st-wire-hit').forEach(hit => {
    const edge = {
      from: hit.dataset.from,
      fromPort: hit.dataset.fromPort,
      to: hit.dataset.to,
      toPort: hit.dataset.toPort
    };
    hit.addEventListener('pointerdown', ev => {
      ev.stopPropagation();          // 别让画布当成"拖空白"
    });
    hit.addEventListener('click', ev => {
      ev.stopPropagation();
      selectStudioEdge(edge);
      studioLog('已选中连线：' + edge.fromPort + ' → ' + edge.toPort + '（Delete 删除）');
    });
    hit.addEventListener('contextmenu', ev => {
      ev.preventDefault();
      ev.stopPropagation();
      selectStudioEdge(edge);
      showStudioEdgeMenu(ev.clientX, ev.clientY, edge);
    });
  });
}
function highlightCompatible(fromNodeId, fromType) {
  const stage = document.getElementById('studio-playfield');
  if (!stage) return;
  const type = fromType || (studio.linking && studio.linking.type);
  stage.querySelectorAll('.st-port-in').forEach(port => {
    const ok = portsCompatible(type, port.dataset.type) && port.dataset.node !== fromNodeId;
    port.classList.toggle('compatible', !!ok);
    port.classList.toggle('blocked', !ok);
  });
  stage.querySelectorAll('.st-port-out').forEach(port => {
    port.classList.toggle('blocked', port.dataset.node !== fromNodeId);
  });
}

function clearHighlight() {
  const stage = document.getElementById('studio-playfield');
  if (!stage) return;
  stage.querySelectorAll('.st-port').forEach(p => p.classList.remove('compatible', 'blocked', 'ok'));
}

function startStudioLinkDrag(ev, svg, portEl) {
  const rect = svg.getBoundingClientRect();
  const pr = portEl.getBoundingClientRect();
  const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
  line.setAttribute('class', 'st-wire-temp');
  line.setAttribute('stroke', portEl.style.background || '#38bdf8');
  svg.appendChild(line);
  const x1 = pr.left + pr.width / 2 - rect.left;
  const y1 = pr.top + pr.height / 2 - rect.top;
  const move = e => {
    line.setAttribute('x1', x1);
    line.setAttribute('y1', y1);
    line.setAttribute('x2', e.clientX - rect.left);
    line.setAttribute('y2', e.clientY - rect.top);
  };
  move(ev);
  const up = e => {
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', up);
    if (line.parentNode) line.parentNode.removeChild(line);
    const hit = document.elementFromPoint(e.clientX, e.clientY);
    const target = hit && hit.closest ? hit.closest('.st-port-in') : null;
    if (target) tryFinishLink(target);
    else {
      studio.linking = null;
      clearHighlight();
      studioLog('没有落在输入端口上，再拖一次', 'warn');
    }
  };
  document.addEventListener('pointermove', move);
  document.addEventListener('pointerup', up);
}

function tryFinishLink(portEl) {
  const link = studio.linking;
  studio.linking = null;
  clearHighlight();
  if (!link) return;
  const toType = portEl.dataset.type;
  if (!portsCompatible(link.type, toType)) {
    studioLog('类型不匹配：' + portTypeName(link.type) + ' 接不到 ' + portTypeName(toType), 'error');
    return;
  }
  const toId = portEl.dataset.node;
  const toPort = portEl.dataset.port;
  if (toId === link.fromId) {
    studioLog('不能连到自己', 'warn');
    return;
  }
  const doc = studioDoc();
  // 一个输入口只接一条线：新连线替换旧的
  doc.edges = doc.edges.filter(e => !(e.to === toId && e.toPort === toPort));
  doc.edges.push({ from: link.fromId, fromPort: link.fromPort, to: toId, toPort: toPort });
  renderStudioCanvas();
  studioLog('已连线：' + portTypeName(link.type));
}

function drawStudioWires(svg, els) {
  if (!svg) return;
  while (svg.firstChild) svg.removeChild(svg.firstChild);
  const rect = svg.getBoundingClientRect();
  const doc = studioDoc();
  doc.edges.forEach(edge => {
    const a = els.get(edge.from);
    const b = els.get(edge.to);
    if (!a || !b) return;
    const ap = a.querySelector('.st-port-out[data-port="' + edge.fromPort + '"]');
    const bp = b.querySelector('.st-port-in[data-port="' + edge.toPort + '"]');
    if (!ap || !bp) return;
    const ar = ap.getBoundingClientRect();
    const br = bp.getBoundingClientRect();
    const x1 = ar.left + ar.width / 2 - rect.left;
    const y1 = ar.top + ar.height / 2 - rect.top;
    const x2 = br.left + br.width / 2 - rect.left;
    const y2 = br.top + br.height / 2 - rect.top;
    const mid = Math.max(34, Math.abs(x2 - x1) * 0.45);
    const d = 'M ' + x1 + ' ' + y1 + ' C ' + (x1 + mid) + ' ' + y1 + ', ' +
      (x2 - mid) + ' ' + y2 + ', ' + x2 + ' ' + y2;
    const color = ap.style.background || portColor(ap.dataset.type);
    const selected = studio.selEdge &&
      studio.selEdge.from === edge.from && studio.selEdge.fromPort === edge.fromPort &&
      studio.selEdge.to === edge.to && studio.selEdge.toPort === edge.toPort;

    // 命中线先画：透明加粗（1.6px 细线点不中），且必须排在可见线前面，
    // CSS 的 .st-wire-hit:hover + .st-wire 悬停变粗才管用。
    const hit = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    hit.setAttribute('d', d);
    hit.setAttribute('class', 'st-wire-hit');
    hit.dataset.from = edge.from;
    hit.dataset.fromPort = edge.fromPort;
    hit.dataset.to = edge.to;
    hit.dataset.toPort = edge.toPort;
    svg.appendChild(hit);

    // 可见线
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', d);
    path.setAttribute('class', 'st-wire' + (selected ? ' sel' : ''));
    path.setAttribute('stroke', color);
    path.style.color = color;
    svg.appendChild(path);
  });
}

function edgeKey(edge) {
  return edge.from + '|' + edge.fromPort + '>' + edge.to + '|' + edge.toPort;
}

function selectStudioEdge(edge) {
  studio.selEdge = edge || null;
  if (edge) studio.selId = null;   // 选线就取消选卡片，避免两个高光同时亮
  const stage = document.getElementById('studio-playfield');
  if (stage) {
    stage.querySelectorAll('.st-node.sel').forEach(c => c.classList.remove('sel'));
    if (edge) {
      const card = stage.querySelector('.st-node[data-id="' + edge.to + '"]');
      if (card) card.classList.add('sel');   // 保持上下文可见
    }
  }
  // 连线选中态要重绘（SVG 的 class 在绘制时决定），必须走带重绑的入口
  if (stage) redrawStudioWires(stage.querySelector('.st-wires'));
  renderStudioParams();
  patchStudioBadge();
}

function patchStudioBadge() {
  const badge = document.getElementById('studio-param-badge');
  if (!badge) return;
  if (studio.selEdge) badge.textContent = '已选连线';
  else if (studio.selId) {
    const node = studioDoc().nodes.find(n => n.id === studio.selId);
    const def = node && STUDIO_DEFS[node.type];
    badge.textContent = def ? def.name : '未选中';
  } else badge.textContent = '未选中';
}

function removeStudioEdge(edge) {
  const doc = studioDoc();
  const key = edgeKey(edge);
  doc.edges = doc.edges.filter(e => edgeKey(e) !== key);
  if (studio.selEdge && edgeKey(studio.selEdge) === key) studio.selEdge = null;
  renderStudioCanvas();
  persistDoc();
  studioLog('已删除连线');
}

/** 连线右键菜单：只有一项「删除这条连线」，点别处自动收起。 */
function showStudioEdgeMenu(clientX, clientY, edge) {
  hideStudioEdgeMenu();
  const menu = el('div', 'st-edge-menu');
  menu.id = 'studio-edge-menu';
  const item = el('button', 'st-edge-menu-item', '🗑 删除这条连线');
  item.onclick = ev => {
    ev.stopPropagation();
    removeStudioEdge(edge);
    hideStudioEdgeMenu();
  };
  menu.appendChild(item);
  menu.style.left = clientX + 'px';
  menu.style.top = clientY + 'px';
  document.body.appendChild(menu);

  const close = ev => {
    if (menu.contains(ev.target)) return;
    hideStudioEdgeMenu();
  };
  setTimeout(() => {
    document.addEventListener('pointerdown', close, { once: false });
    studio.edgeMenuCloser = close;
  }, 0);
}

function hideStudioEdgeMenu() {
  const menu = document.getElementById('studio-edge-menu');
  if (menu) menu.remove();
  if (studio.edgeMenuCloser) {
    document.removeEventListener('pointerdown', studio.edgeMenuCloser);
    studio.edgeMenuCloser = null;
  }
}

function updateStudioStats() {
  const doc = studioDoc();
  const node = document.getElementById('studio-stats');
  if (node) node.textContent = doc.nodes.length + ' 节点 · ' + doc.edges.length + ' 连线';
}

// ---------------------------------------------------------------- 参数面板
function renderStudioParams() {
  const box = document.getElementById('studio-params');
  if (!box) return;
  box.innerHTML = '';
  const doc = studioDoc();
  const node = doc.nodes.find(n => n.id === studio.selId);
  const badge = document.getElementById('studio-param-badge');

  if (!node) {
    if (badge) badge.textContent = '未选中';
    box.appendChild(el('div', 'hint', '选中画布上的节点，这里显示它的全部参数'));
    return;
  }

  const def = STUDIO_DEFS[node.type];
  if (badge) badge.textContent = def.name;

  const head = el('div', 'st-node-head');
  head.appendChild(el('span', 'st-node-ico', def.icon));
  const tw = el('div', 'st-node-titlewrap');
  tw.appendChild(el('b', null, def.name));
  tw.appendChild(el('span', null, def.desc));
  head.appendChild(tw);
  box.appendChild(head);

  box.appendChild(el('div', 'st-param-desc',
    (def.inputs || []).length + ' 入 / ' + (def.outputs || []).length + ' 出 · ' +
    ((def.inputs || []).map(p => portTypeName(p.type)).join(' / ') || '无输入')));

  const layerIds = (typeof attrLayers === 'function') ? attrLayers() : [];
  const varNames = Object.keys(state.doc.interactions.vars || {});
  // 动画组按图层过滤：播放动画先定图层，只列这一层的组（组名 + 轨道摘要）
  const allClips = (typeof window.clips === 'function') ? window.clips() : [];
  const clipTarget = node.params.target || '';
  const clipList = clipTarget ? allClips.filter(c => c.targetId === clipTarget) : allClips;
  const fnNames = Object.keys(state.doc.interactions.functions || {});

  for (const [key, defVal] of Object.entries(def.params || {})) {
    const row = el('div', 'st-param-row');
    row.appendChild(el('label', null, studioParamLabel(node.type, key)));

    if (key === 'gestures') {
      const grid = el('div', 'st-chips-grid');
      const chosen = Array.isArray(node.params.gestures) ? node.params.gestures : [];
      GESTURE_OPTIONS.forEach(([value, label]) => {
        const chip = el('button', 'st-gchip' + (chosen.includes(value) ? ' on' : ''), label);
        chip.onclick = () => {
          const list = Array.isArray(node.params.gestures) ? node.params.gestures.slice() : [];
          const idx = list.indexOf(value);
          if (idx >= 0) list.splice(idx, 1); else list.push(value);
          node.params.gestures = list;
          persistDoc();
          renderStudioCanvas();
          renderStudioParams();
        };
        grid.appendChild(chip);
      });
      row.appendChild(grid);
      box.appendChild(row);
      continue;
    }

    let input;
    if (key === 'target' && layerIds.length) {
      input = document.createElement('select');
      input.appendChild(new Option('（选择图层）', ''));
      layerIds.forEach(l => input.appendChild(new Option(l, l)));
      input.value = node.params[key] || '';
    } else if (key === 'name' && node.type === 'varChanged' && varNames.length) {
      input = document.createElement('select');
      input.appendChild(new Option('（选择变量）', ''));
      varNames.forEach(v => input.appendChild(new Option(v, v)));
      input.value = node.params[key] || '';
    } else if (key === 'name' && node.type === 'callFn' && fnNames.length) {
      input = document.createElement('select');
      input.appendChild(new Option('（选择函数）', ''));
      fnNames.forEach(f => input.appendChild(new Option(f, f)));
      input.value = node.params[key] || '';
    } else if (key === 'clip') {
      input = document.createElement('select');
      const emptyHint = clipTarget
        ? (clipList.length ? '（选择动画组）' : '该图层还没有动画组')
        : '（先选图层）';
      input.appendChild(new Option(emptyHint, ''));
      clipList.forEach((c, i) => {
        const name = c.name || ('动画组 ' + (i + 1));
        const props = (c.tracks || []).map(t => t.property).join('/');
        const loop = (c.tracks && c.tracks[0] && c.tracks[0].loop) || '';
        input.appendChild(new Option(name + (props ? '  · ' + props + (loop ? ' ' + loop : '') : '  · 空组'), c.id));
      });
      input.value = node.params[key] || '';
    } else if (key === 'prop') {
      input = document.createElement('select');
      ['x', 'y', 'scale', 'rot', 'alpha'].forEach(p => input.appendChild(new Option(p, p)));
      input.value = node.params[key] || 'alpha';
    } else if (key === 'easing') {
      input = document.createElement('select');
      STUDIO_EASING_CHOICES.forEach(([value, label]) => input.appendChild(new Option(label, value)));
      input.value = node.params[key] || 'easeOut';
      if (studioNum(node.params.duration, 0) <= 0) {
        input.disabled = true;
        input.title = '时长是 0（瞬移），缓动不参与计算';
        input.classList.add('is-off');
      }
    } else if (key === 'op' && node.type === 'logicGate') {
      input = document.createElement('select');
      input.appendChild(new Option('与（都成立）', 'and'));
      input.appendChild(new Option('或（任一成立）', 'or'));
      input.value = node.params[key] || 'and';
    } else if (key === 'op') {
      input = document.createElement('select');
      ['==', '!=', '>', '>=', '<', '<='].forEach(o => input.appendChild(new Option(o, o)));
      input.value = node.params[key] || '==';
    } else if (key === 'reverse') {
      input = document.createElement('select');
      input.appendChild(new Option('正向（首帧→尾帧）', 'false'));
      input.appendChild(new Option('反向（尾帧→首帧）', 'true'));
      input.value = node.params[key] || 'false';
    } else if (key === 'value' && node.type === 'setVis') {
      input = document.createElement('select');
      input.appendChild(new Option('显示 / 是', 'true'));
      input.appendChild(new Option('隐藏 / 否', 'false'));
      input.value = node.params[key] || 'false';
    } else {
      input = document.createElement('input');
      input.type = 'text';
      input.value = node.params[key] === undefined ? defVal : node.params[key];
      input.placeholder = defVal === '' ? '（空）' : String(defVal);
    }
    input.onchange = () => {
      node.params[key] = input.value;
      renderStudioCanvas();
      persistDoc();
    };
    if (input.tagName === 'INPUT') input.oninput = input.onchange;
    row.appendChild(input);
    box.appendChild(row);
  }

  const legend = el('div', 'st-legend');
  legend.appendChild(el('b', null, '端口类型'));
  Object.entries(PORT_TYPES).forEach(([type, meta]) => {
    const item = el('span', 'st-legend-item');
    const dot = el('i', 'st-legend-dot');
    dot.style.background = meta.color;
    item.appendChild(dot);
    item.appendChild(el('span', null, meta.label));
    legend.appendChild(item);
  });
  box.appendChild(legend);
}

function studioParamLabel(type, key) {
  const map = {
    target: '图层', kind: '手势', gestures: '手势（可多选）',
    name: type === 'sysVar' ? '变量' : (type === 'callFn' ? '函数' : '变量'),
    op: '运算符', a: 'A', b: 'B', condition: '条件', count: '次数',
    // for 里 var 是「循环变量」，while 里它是「轮次变量」（每轮自动加一，条件里直接用）
    var: type === 'whileLoop' ? '轮次变量' : '循环变量',
    unit: '单位',
    start: '起始', step: '步长', max: '最多',
    prop: '属性', expr: '值', duration: '时长(ms)', easing: '缓动',
    value: '值', clip: '动画', reverse: '反向', package: '包名', uri: '链接',
    inMin: '输入最小', inMax: '输入最大', outMin: '输出最小', outMax: '输出最大',
    off: '关断输出'
  };
  return map[key] || key;
}

// ---------------------------------------------------------------- 日志
const studioLogLines = [];
function studioLog(text, kind) {
  studioLogLines.push({
    t: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    text, kind: kind || 'info'
  });
  if (studioLogLines.length > 200) studioLogLines.shift();
  renderStudioLog();
}

function renderStudioLog() {
  const box = document.getElementById('studio-log');
  const badge = document.getElementById('studio-log-badge');
  if (badge) badge.textContent = studioLogLines.length ? 'ACTIVE' : 'IDLE';
  if (!box) return;
  box.innerHTML = '';
  // 有结构化执行记录时优先渲染成逐节点明细（定义在 studio-compile.js）
  if (typeof renderStudioRunLog === 'function' && renderStudioRunLog(box)) return;
  if (!studioLogLines.length) {
    box.appendChild(el('div', 'hint',
      '还没有执行记录。点击「模拟运行」后，这里会按执行顺序显示每个节点的输入、输出与动作结果。（编译层下一期接入）'));
    return;
  }
  studioLogLines.forEach(line => {
    const row = el('div', 'st-log-row ' + (line.kind || 'info'));
    row.appendChild(el('span', 'st-log-time', line.t));
    row.appendChild(el('span', 'st-log-text', line.text));
    box.appendChild(row);
  });
  box.scrollTop = box.scrollHeight;
}

// ---------------------------------------------------------------- 模拟输入
function bindStudioSim() {
  const battery = document.getElementById('studio-sim-battery');
  const batteryVal = document.getElementById('studio-sim-battery-val');
  if (battery && !battery.dataset.bound) {
    battery.dataset.bound = '1';
    battery.oninput = () => {
      studio.sim.battery = Number(battery.value);
      if (batteryVal) batteryVal.textContent = studio.sim.battery + '%';
    };
  }
  const hour = document.getElementById('studio-sim-hour');
  if (hour && !hour.dataset.bound) {
    hour.dataset.bound = '1';
    hour.oninput = () => { studio.sim.hour = Number(hour.value); };
  }
  const gesture = document.getElementById('studio-sim-gesture');
  if (gesture && !gesture.dataset.bound) {
    gesture.dataset.bound = '1';
    gesture.onchange = () => { studio.sim.gesture = gesture.value; };
  }
  const sx = document.getElementById('studio-sim-x');
  const sy = document.getElementById('studio-sim-y');
  if (sx && !sx.dataset.bound) {
    sx.dataset.bound = '1';
    sx.oninput = () => { studio.sim.x = Number(sx.value); };
  }
  if (sy && !sy.dataset.bound) {
    sy.dataset.bound = '1';
    sy.oninput = () => { studio.sim.y = Number(sy.value); };
  }
}

// ---------------------------------------------------------------- 绑定
function setupStudio() {
  const open = document.getElementById('btn-studio');
  if (open) open.onclick = () => openStudio();
  const back = document.getElementById('btn-studio-back');
  if (back) back.onclick = () => closeStudio();

  const clear = document.getElementById('btn-studio-clear');
  if (clear) clear.onclick = () => {
    const doc = studioDoc();
    doc.nodes = [];
    doc.edges = [];
    studio.selId = null;
    renderStudioCanvas();
    renderStudioParams();
    studioLog('画布已清空');
  };

  const resetView = document.getElementById('btn-studio-reset-view');
  if (resetView) resetView.onclick = () => {
    studio.zoom = 1;
    studio.offset = { x: 0, y: 0 };
    applyStudioStage();
    renderStudioCanvas();
  };

  const run = document.getElementById('btn-studio-run');
  if (run) run.onclick = () => {
    const doc = studioDoc();
    studioLog('模拟运行：' + doc.nodes.length + ' 个节点 / ' + doc.edges.length + ' 条连线', 'warn');
    studioLog('编译层还没接入，下一期会按拓扑顺序执行并回填结果', 'warn');
  };

  // 节点 / 图层 标签切换
  document.querySelectorAll('[data-lib-tab]').forEach(btn => {
    btn.onclick = () => {
      const tab = btn.dataset.libTab;
      if (studio.libTab === tab) return;
      studio.libTab = tab;
      document.querySelectorAll('[data-lib-tab]').forEach(b => {
        b.classList.toggle('on', b.dataset.libTab === tab);
      });
      renderStudioLibrary();
    };
  });

  const search = document.getElementById('studio-search');
  if (search) search.oninput = () => {
    studio.search = search.value;
    renderStudioLibrary();
  };

  const applyZoom = delta => {
    studio.zoom = Math.max(0.4, Math.min(2, studio.zoom + delta));
    applyStudioStage();
  };
  document.querySelectorAll('[data-studio-zoom]').forEach(btn => {
    btn.onclick = () => applyZoom(btn.dataset.studioZoom === 'in' ? 0.15 : -0.15);
  });

  const canvasBox = document.getElementById('studio-canvas');
  if (canvasBox) {
    canvasBox.addEventListener('wheel', ev => {
      if (!studio.open) return;
      ev.preventDefault();
      applyZoom(ev.deltaY > 0 ? -0.1 : 0.1);
    }, { passive: false });
  }

  const field = document.getElementById('studio-playfield');
  if (field) {
    // 点空白：取消高光 + 收起参数面板。这就是"点空白关高光"的入口。
    field.addEventListener('pointerdown', ev => {
      if (ev.target !== field) return;
      // 清连线选中要重绘 SVG（class 只在绘制时写），否则高亮会留在线上
      if (studio.selEdge) selectStudioEdge(null);
      selectStudioNode(null);
      const startX = ev.clientX;
      const startY = ev.clientY;
      const ox = studio.offset.x;
      const oy = studio.offset.y;
      const move = e => {
        studio.offset.x = ox + (e.clientX - startX);
        studio.offset.y = oy + (e.clientY - startY);
        applyStudioStage();
      };
      const up = () => {
        document.removeEventListener('pointermove', move);
        document.removeEventListener('pointerup', up);
      };
      document.addEventListener('pointermove', move);
      document.addEventListener('pointerup', up);
    });
  }

  document.addEventListener('keydown', ev => {
    if (!studio.open) return;
    if (ev.key === 'Escape') closeStudio();
    if (ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) {
      const runBtn = document.getElementById('btn-studio-run');
      if (runBtn) runBtn.click();
      ev.preventDefault();
    }
    // 焦点在输入类控件里时，Delete / Backspace 是"删字符"，不能拿去删节点。
    // 复用 editor.js 里的判断（本文件先加载，但这里在运行期才调用，没问题）。
    // 同时看 ev.target 和 document.activeElement：
    // 有些按键事件的 target 会被重定向到 document（合成事件、部分浏览器行为），
    // 只看 target 会漏判 —— 用户当时确实在输入框里打字。
    const looksTyping = (node) => {
      if (!node) return false;
      const tag = String(node.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select') return true;
      return !!node.isContentEditable;
    };
    const typing = (typeof isTypingTarget === 'function' && isTypingTarget(ev.target)) ||
                   looksTyping(ev.target) || looksTyping(document.activeElement);
    if (typing) return;

    if (ev.key === 'Delete' || ev.key === 'Backspace') {
      if (studio.selEdge) {
        removeStudioEdge(studio.selEdge);
        ev.preventDefault();
      } else if (studio.selId) {
        removeStudioNode(studio.selId);
        ev.preventDefault();
      }
    }
  });
}