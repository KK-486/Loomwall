/*
 * LWPKit 预设编辑器（网页版）
 *
 * 设计取向：**照 PR / AE 的关键帧模型**，而不是"先选属性再启用"的通道清单。
 * - 位置 = 一个属性（内部写盘时拆成 x / y 两条轨道，格式不变）
 * - 每个属性旁边一个 ◇ 开关：点了就在播放头落一帧并进入关键帧模式
 * - 进入关键帧模式后，拖画布 / 改数值 → 自动在播放头写帧（PR 的自动关键帧）
 *
 * 坐标系与手机端完全一致：x / y / 宽 / 高 都是「画布宽度的百分比」，原点左上角，y 向下。
 * 颜色 #AARRGGBB；关键帧 t 归一化 0..1、严格递增，末帧可早于 1（之后保持末值）。
 */
'use strict';

const PHONE_ASPECT = 1080 / 2340;

const DEFAULT_SCENE = {
  formatVersion: '0.1',
  root: {
    id: 'root', type: 'container', visible: true, background: '#FF0B0F14',
    children: [
      { id: 'rect_glow', type: 'rect', visible: true, x: 20, y: 26, width: 60, height: 18, color: '#FF3DDC97', opacity: 0.85 },
      { id: 'rect_accent', type: 'rect', visible: true, x: 20, y: 46, width: 32, height: 8, color: '#FF4C8BF5', opacity: 1 },
      { id: 'rect_bar', type: 'rect', visible: true, x: 20, y: 58, width: 48, height: 3, color: '#FF8A94A6', opacity: 0.6 }
    ]
  }
};

const DEFAULT_INTERACTIONS = {
  formatVersion: '0.1',
  animations: [
    {
      id: 'anim_glow_pulse', targetId: 'rect_glow', trigger: { type: 'tap' },
      tracks: [{
        property: 'rotationZ', durationMs: 700, loop: 'once',
        keyframes: [
          { t: 0, value: 0, easing: 'cubicOut' },
          { t: 0.5, value: 18, easing: 'cubicInOut' },
          { t: 1, value: 0, easing: 'linear' }
        ]
      }]
    },
    {
      id: 'anim_accent_breathe', targetId: 'rect_accent', trigger: { type: 'always' },
      tracks: [{
        property: 'opacity', durationMs: 1200, loop: 'pingpong',
        keyframes: [
          { t: 0, value: 1, easing: 'easeInOut' },
          { t: 1, value: 0.25, easing: 'easeInOut' }
        ]
      }]
    }
  ]
};

const DEFAULT_MANIFEST = {
  formatVersion: '0.2', engineMinVersion: '0.3.0',
  name: '网页编辑器预设', author: 'LWPKit', license: 'Apache-2.0'
};

/** 界面上的「属性」= 一个通道；位置包含 x、y 两条底层轨道。 */
const CHANNELS = [
  { key: 'position', label: '位置', props: ['x', 'y'] },
  { key: 'size', label: '宽高', props: ['width', 'height'] },
  { key: 'scale', label: '缩放', props: ['scale'] },
  { key: 'rotation', label: '旋转', props: ['rotationZ'] },
  { key: 'opacity', label: '透明度', props: ['opacity'] }
];
const ALL_PROPS = CHANNELS.flatMap(c => c.props);
const PROPERTY_RANGES = {
  x: [-200, 400], y: [-200, 600], scale: [0.05, 10], rotationZ: [-720, 720], opacity: [0, 1],
  // 宽高是盒子尺寸（画布单位），允许比屏幕更宽/更高，所以上限给到 300
  width: [0, 300], height: [0, 300]
};

const EASING_NAMES = ['linear', 'ease', 'easeIn', 'easeOut', 'easeInOut', 'cubicIn', 'cubicOut', 'cubicInOut'];
const EASING_LABELS = {
  linear: '线性', ease: '标准', easeIn: '缓入', easeOut: '缓出', easeInOut: '缓入缓出',
  cubicIn: '三次缓入', cubicOut: '三次缓出', cubicInOut: '三次缓入缓出'
};
const EASING_BEZIER = {
  ease: [0.25, 0.1, 0.25, 1], easeIn: [0.42, 0, 1, 1], easeOut: [0, 0, 0.58, 1],
  easeInOut: [0.42, 0, 0.58, 1], cubicIn: [0.32, 0, 0.67, 0],
  cubicOut: [0.33, 1, 0.68, 1], cubicInOut: [0.65, 0, 0.35, 1]
};

const state = {
  doc: {
    manifest: clone(DEFAULT_MANIFEST),
    scene: clone(DEFAULT_SCENE),
    interactions: clone(DEFAULT_INTERACTIONS),
    // 资源表：key -> data URL（图片等二进制，导出时随预设包一起走）
    resources: {}
  },
  sel: { rectId: null, channel: -1, keyIndex: -1 },
  playhead: 0,
  playing: false,
  drag: null,
  // 视差预览（编辑器侧模拟）：鼠标位置 → 倾斜量 → 各层按景深偏移。
  // 引擎端暂不接传感器（阶段闸门），先在这里把振幅和阻尼调准。
  parallax: { enabled: true, amp: 3.2, tx: 0, ty: 0, targetX: 0, targetY: 0 },
  errors: [],
  // 变量/函数面板状态。必须初始化：漏掉 varSearch 会让首次渲染抛 TypeError，
  // 结果就是面板空白、交互面板也不显示。
  varCategory: 'all',
  varSearch: '',
  iconSearch: '',
  layerDragId: null,
  shapeSearch: '',
  activeTextarea: null
};

/** 已解码的图片缓存：src -> HTMLImageElement */
const imageCache = new Map();

const canvas = document.getElementById('stage');
const ctx = canvas.getContext('2d');

// ---------------------------------------------------------------- 基础工具

function clone(v) { return JSON.parse(JSON.stringify(v)); }
function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }
function fmt(v, d = 1) { return Number(v).toFixed(d); }
function round1(v) { return Math.round(v * 10) / 10; }
function round2(v) { return Math.round(v * 100) / 100; }

function rects() { return state.doc.scene.root.children; }
function isImage(node) { return node && node.type === 'image'; }
function isText(node) { return node && node.type === 'text'; }
/** 形状枚举与中文名（与 Android 侧 ShapeKind 对齐）。 */
const SHAPES = [
  ['rect', '矩形'],
  ['ellipse', '椭圆 / 正圆'],
  ['triangle', '三角形'],
  ['trapezoid', '梯形'],
  ['polygon', '正多边形'],
  ['star', '星形']
];

/**
 * 生成形状顶点（局部坐标，0..w / 0..h）。
 *
 * 必须与 Android 的 Shapes.kt 保持一致：手机端用三角形扇渲染，
 * 两边顶点不同就会出现"编辑器是圆、手机是方"这种诡异问题。
 */
function shapePoints(node) {
  return shapePointsBox(
    node.shape || 'rect',
    node.sides || 5,
    worldToPx(node.width),
    worldToPx(node.height)
  );
}

/**
 * 形状顶点（显式宽高）。
 *
 * 从 shapePoints 拆出来是为了让形状库的缩略图复用同一套算法——
 * 缩略图和画布不一致的话，用户点"圆形"却得到别的形状，很难排查。
 */
function shapePointsBox(kind, sidesRaw, w, h) {
  const sides = clamp(Math.round(sidesRaw || 5), 3, 32);

  if (w <= 0 || h <= 0) return [[0, 0]];

  if (kind === 'rect') return [[0, 0], [w, 0], [w, h], [0, h]];
  if (kind === 'triangle') return [[w / 2, 0], [w, h], [0, h]];
  if (kind === 'trapezoid') {
    const inset = w * 0.25;
    return [[inset, 0], [w - inset, 0], [w, h], [0, h]];
  }
  if (kind === 'ellipse') {
    const out = [];
    const segments = 48;
    for (let i = 0; i < segments; i++) {
      const a = -Math.PI / 2 + (i * 2 * Math.PI) / segments;
      out.push([w / 2 + (w / 2) * Math.cos(a), h / 2 + (h / 2) * Math.sin(a)]);
    }
    return out;
  }
  if (kind === 'star') {
    const out = [];
    for (let i = 0; i < sides * 2; i++) {
      const ratio = i % 2 === 0 ? 1 : 0.45;
      const a = -Math.PI / 2 + (i * Math.PI) / sides;
      out.push([w / 2 + (w / 2) * ratio * Math.cos(a), h / 2 + (h / 2) * ratio * Math.sin(a)]);
    }
    return out;
  }
  // polygon
  const out = [];
  for (let i = 0; i < sides; i++) {
    const a = -Math.PI / 2 + (i * 2 * Math.PI) / sides;
    out.push([w / 2 + (w / 2) * Math.cos(a), h / 2 + (h / 2) * Math.sin(a)]);
  }
  return out;
}

/**
 * 形状库条目。
 *
 * 同一个 shape 可以有多个常用预设（比如正多边形的 5/6/8 边），
 * 单独列出来比让用户改数字更好点。
 */
const SHAPE_LIBRARY = [
  { kind: 'rect', sides: 4, label: '矩形', w: 60, h: 30 },
  { kind: 'ellipse', sides: 0, label: '正圆', w: 40, h: 40, circleLock: true },
  { kind: 'ellipse', sides: 0, label: '椭圆（宽扁）', w: 56, h: 30, circleLock: false },
  { kind: 'triangle', sides: 0, label: '三角形', w: 44, h: 36 },
  { kind: 'trapezoid', sides: 0, label: '梯形', w: 52, h: 30 },
  { kind: 'polygon', sides: 5, label: '五边形', w: 40, h: 40 },
  { kind: 'polygon', sides: 6, label: '六边形', w: 40, h: 40 },
  { kind: 'polygon', sides: 8, label: '八边形', w: 40, h: 40 },
  { kind: 'star', sides: 5, label: '五角星', w: 44, h: 44 },
  { kind: 'star', sides: 6, label: '六角星', w: 44, h: 44 }
];

/** 生成形状缩略图（内联 SVG，颜色跟随面板前景色）。 */
function shapeThumb(item, size) {
  const points = shapePointsBox(item.kind, item.sides, size, size);
  const polygon = points.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">` +
    `<polygon points="${polygon}" fill="currentColor" /></svg>`;
}

async function renderShapeGrid() {
  const grid = document.getElementById('shape-grid');
  if (!grid) return;
  grid.innerHTML = '';

  const query = String(state.shapeSearch || '').trim().toLowerCase();
  const list = SHAPE_LIBRARY.filter(item => !query || item.label.includes(query) || item.kind.includes(query));

  if (!list.length) {
    grid.appendChild(el('div', 'hint', '没有匹配的形状'));
    return;
  }

  for (const item of list) {
    const cell = el('button', 'icon-cell');
    cell.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:6px;padding:10px 4px;background:#171d26;border:1px solid #232b36;border-radius:8px;cursor:pointer;color:#dbe6f3;';
    cell.innerHTML = shapeThumb(item, 34);
    cell.appendChild(el('span', 'badge', item.label));
    cell.title = `${item.label}（${item.kind}${item.sides ? ' / ' + item.sides : ''}）`;
    cell.onclick = () => insertShape(item);
    grid.appendChild(cell);
  }
}

function openShapePicker() {
  const picker = document.getElementById('shape-picker');
  if (!picker) return;
  picker.style.display = 'flex';
  renderShapeGrid();
}

function closeShapePicker() {
  const picker = document.getElementById('shape-picker');
  if (picker) picker.style.display = 'none';
}

/** 按库里的预设插入一个形状元素（进容器时按容器尺寸居中）。 */
function insertShape(item) {
  const node = {
    id: uniqueId('shape'),
    type: 'rect',
    visible: true,
    x: 20, y: 20,
    width: item.w, height: item.h,
    color: '#FF4C8BF5',
    opacity: 1,
    shape: item.kind
  };
  if (item.sides) node.sides = item.sides;
  if (item.kind === 'ellipse') node.circleLock = item.circleLock === true;

  const container = selectedRect();
  if (isContainer(container)) {
    const scale = Math.min(
      (container.width * 0.8) / node.width,
      (container.height * 0.8) / node.height,
      1
    );
    node.width = round1(node.width * scale);
    node.height = round1(node.height * scale);
    node.x = round1((container.width - node.width) / 2);
    node.y = round1((container.height - node.height) / 2);
  }
  insertTargetList().push(node);

  state.sel.rectId = node.id;
  state.sel.channel = -1;
  closeShapePicker();
  renderAll();
  status('已添加形状：' + item.label);
}
// ---------------------------------------------------------------- SVG

/**
 * 把 SVG 文本编译成 PNG data URL。
 *
 * 为什么要编译：手机端 BitmapFactory 只认位图（PNG/JPEG），SVG 传过去也解不了。
 * 这里用浏览器自己的 SVG 渲染器光栅化，得到的就是手机能直接用的资源。
 *
 * @param size 输出边长（正方形画布）
 */
async function compileSvg(svgText, size) {
  const text = String(svgText || '').trim();
  if (!text) throw new Error('SVG 代码为空');
  if (!/<svg[\s>]/i.test(text)) throw new Error('看起来不是 SVG：缺少 <svg> 标签');

  // 没有 width/height 时补上，否则部分浏览器按 0 尺寸光栅化
  let normalized = text;
  if (!/\swidth\s*=/.test(normalized) || !/\sheight\s*=/.test(normalized)) {
    const viewBox = normalized.match(/viewBox\s*=\s*["']([^"']+)["']/i);
    let width = 24;
    let height = 24;
    if (viewBox) {
      const parts = viewBox[1].trim().split(/[\s,]+/).map(Number);
      if (parts.length === 4 && parts[2] > 0 && parts[3] > 0) {
        width = parts[2];
        height = parts[3];
      }
    }
    normalized = normalized.replace(/<svg/i, `<svg width="${width}" height="${height}"`);
  }

  const url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(normalized);
  const img = new Image();
  await new Promise((resolve, reject) => {
    img.onload = resolve;
    img.onerror = () => reject(new Error('SVG 解析失败：检查标签是否闭合、属性引号是否配对'));
    img.src = url;
  });

  const canvasEl = document.createElement('canvas');
  canvasEl.width = size;
  canvasEl.height = size;
  const context = canvasEl.getContext('2d');
  context.clearRect(0, 0, size, size);

  // 等比缩放居中（宽高比不等于 1 时不要拉变形）
  const ratio = Math.min(size / (img.naturalWidth || size), size / (img.naturalHeight || size));
  const drawW = (img.naturalWidth || size) * ratio;
  const drawH = (img.naturalHeight || size) * ratio;
  context.drawImage(img, (size - drawW) / 2, (size - drawH) / 2, drawW, drawH);

  return canvasEl.toDataURL('image/png');
}

/**
 * 从 SVG 文本里抠出路径数据。
 *
 * 返回 null 表示这份 SVG 没用 `<path>`（可能全是 circle/rect 等基础图形），
 * 那种情况退回"编译成 PNG"，保证任何 SVG 都能用。
 */
function extractSvgPath(text) {
  const source = String(text || '');
  const viewBoxMatch = source.match(/viewBox\s*=\s*["']([^"']+)["']/i);
  const viewBox = viewBoxMatch ? viewBoxMatch[1].trim() : '';

  const ds = [];
  const regex = /\sd\s*=\s*["']([^"']+)["']/gi;
  let match;
  while ((match = regex.exec(source)) !== null) {
    if (match[1] && match[1].trim()) ds.push(match[1].trim());
  }
  if (!ds.length) return null;

  // 没有 viewBox 时按 width/height 推一个，保证归一化基准正确
  let box = viewBox;
  if (!box) {
    const w = source.match(/\swidth\s*=\s*["']([\d.]+)/i);
    const h = source.match(/\sheight\s*=\s*["']([\d.]+)/i);
    box = `0 0 ${w ? w[1] : 24} ${h ? h[1] : 24}`;
  }
  // 线框图标：fill="none" + stroke，必须按描边画，否则轮廓会被填成实心块
  const fillNone = /fill\s*=\s*["']none["']/i.test(source);
  const strokeMatch = source.match(/\sstroke\s*=\s*["']([^"']+)["']/i);
  const widthMatch = source.match(/stroke-width\s*=\s*["']([\d.]+)/i);

  return {
    d: ds.join(' '),
    viewBox: box,
    strokeWidth: fillNone ? (widthMatch ? Number(widthMatch[1]) : 2) : 0,
    strokeColor: strokeMatch ? normalizeHexColor(strokeMatch[1]) : ''
  };
}
/**
 * 把选中的文件读进代码框并立刻编译。
 *
 * 刻意做得很"吵闹"：文件名、是否含 <path>、viewBox 都显示出来。
 * 之前选完文件如果解析失败是完全静默的，用户只会觉得"点了没反应"。
 */
/**
 * 判断这份 SVG 能不能安全地走「矢量路径」。
 *
 * 矢量模式的前提是：**单条 <path>、无 transform、有 viewBox**。
 * 一旦是多路径（比如 VTracer 描摹出的插画：几百条 path + 各自 translate），
 * 把所有 d 拼成一条再用 even-odd 填充只会得到一堆黑洞——
 * 这种情况必须退回"整张编译成 PNG"，由浏览器负责完整的 SVG 语义。
 */
function analyzeSvg(text) {
  const source = String(text || '');
  const pathCount = (source.match(/<path[\s>]/gi) || []).length;
  const hasTransform = /transform\s*=/i.test(source);
  const hasOtherShapes = /<(circle|rect|polygon|ellipse|line|polyline)[\s>]/i.test(source);
  const hasGroup = /<g[\s>]/i.test(source);
  const hasPaintServers = /clipPath|<mask|linearGradient|radialGradient|filter\s*=/i.test(source);

  const viewBoxMatch = source.match(/viewBox\s*=\s*["']([^"']+)["']/i);
  const widthMatch = source.match(/\swidth\s*=\s*["']([\d.]+)/i);
  const heightMatch = source.match(/\sheight\s*=\s*["']([\d.]+)/i);
  const width = widthMatch ? Number(widthMatch[1]) : 0;
  const height = heightMatch ? Number(heightMatch[1]) : 0;

  let aspect = 1;
  if (viewBoxMatch) {
    const parts = viewBoxMatch[1].trim().split(/[\s,]+/).map(Number);
    if (parts.length === 4 && parts[2] > 0 && parts[3] > 0) aspect = parts[3] / parts[2];
  } else if (width > 0 && height > 0) {
    aspect = height / width;
  }

  const dTotal = (source.match(/\sd\s*=\s*["']([^"']+)["']/gi) || []).join('').length;

  let reason = '';
  if (pathCount === 0) reason = '没有 <path>（可能是基础图形或位图）';
  else if (pathCount > 1) reason = `有 ${pathCount} 条 <path>，拼接后填充规则会互相抵消`;
  else if (hasTransform) reason = '带 transform，矢量模式不解析变换会画偏';
  else if (hasGroup) reason = '带 <g> 分组，矢量模式不解析分组';
  else if (hasOtherShapes) reason = '混有基础图形（circle/rect 等），矢量模式只认 <path>';
  else if (hasPaintServers) reason = '带渐变/裁剪/滤镜，矢量模式不支持';
  else if (dTotal > 4000) reason = `路径数据 ${dTotal} 字符，过于复杂`;

  return {
    pathCount,
    aspect: aspect > 0 ? aspect : 1,
    canVector: reason === '',
    reason
  };
}
function loadSvgFile(file) {
  if (!file) return;
  svgMessage(`正在读取 ${file.name}（${Math.max(1, Math.round(file.size / 1024))} KB）…`, true);

  const reader = new FileReader();
  reader.onerror = () => svgMessage('✗ 读取文件失败：' + ((reader.error && reader.error.message) || '未知错误'), false);
  reader.onload = () => {
    // 去 BOM：否则 <svg 前面有不可见字符，正则匹配不到
    const text = String(reader.result || '').replace(/^\uFEFF/, '');
    if (!text.trim()) {
      svgMessage('✗ 文件是空的', false);
      return;
    }
    document.getElementById('svg-code').value = text;

    const extracted = extractSvgPath(text);
    if (!extracted) {
      svgMessage(`${file.name}：没有 <path>（基础图形或位图），插入时会编译成 PNG`, true);
    } else {
      svgMessage(`${file.name}：路径 ${extracted.d.length} 字符，viewBox ${extracted.viewBox}`, true);
    }
    previewSvg();
  };
  reader.readAsText(file);
}

/** 拖拽 SVG 文件到代码框也能导入。 */
function wireSvgDropZone() {
  const area = document.getElementById('svg-code');
  if (!area) return;
  area.addEventListener('dragover', ev => {
    ev.preventDefault();
    area.style.borderColor = '#3ddc97';
  });
  area.addEventListener('dragleave', () => { area.style.borderColor = '#232b36'; });
  area.addEventListener('drop', ev => {
    ev.preventDefault();
    area.style.borderColor = '#232b36';
    const file = ev.dataTransfer && ev.dataTransfer.files && ev.dataTransfer.files[0];
    if (file) loadSvgFile(file);
  });
}
/** #abc / #aabbcc / #aabbccdd → #AABBCCDD；识别不了就返回空串。 */
function normalizeHexColor(value) {
  const raw = String(value || '').trim();
  const m = raw.match(/^#([0-9a-fA-F]{3,8})$/);
  if (!m) return '';
  let hex = m[1];
  if (hex.length === 3) hex = hex.split('').map(c => c + c).join('');
  if (hex.length === 6) hex += 'FF';
  if (hex.length !== 8) return '';
  return '#' + hex.toUpperCase();
}

function openSvgPanel() {
  const panel = document.getElementById('svg-picker');
  if (!panel) return;
  panel.style.display = 'flex';
  document.getElementById('svg-msg').textContent = '';
}

function closeSvgPanel() {
  const panel = document.getElementById('svg-picker');
  if (panel) panel.style.display = 'none';
}

/** 插入模式：auto = 能矢量就矢量；vector = 强制矢量；raster = 一律位图。 */
function svgMode() {
  const sel = document.getElementById('svg-mode');
  return sel ? sel.value : 'auto';
}

function svgSize() {
  const sel = document.getElementById('svg-size');
  return sel ? Number(sel.value) || 256 : 256;
}

function svgMessage(text, ok) {
  const box = document.getElementById('svg-msg');
  if (!box) return;
  box.textContent = text;
  box.style.color = ok ? '#7ee2a8' : '#ff9f9f';
}

/** 编译并在右侧预览（不插入）。 */
async function previewSvg() {
  const code = document.getElementById('svg-code').value;
  try {
    const png = await compileSvg(code, svgSize());
    const preview = document.getElementById('svg-preview');
    preview.innerHTML = '';
    const img = document.createElement('img');
    img.src = png;
    img.style.cssText = 'max-width:100%;max-height:100%;image-rendering:auto;';
    preview.appendChild(img);
    svgMessage(`✓ 编译成功（${svgSize()}×${svgSize()}）`, true);
    return png;
  } catch (error) {
    svgMessage('✗ ' + error.message, false);
    return null;
  }
}

/**
 * 插入 SVG。
 *
 * 优先做成**矢量路径图层**（shape = path，只存 d + viewBox，任意缩放不糊）；
 * 只有当 SVG 里没有 <path>（例如全是 circle/rect）时才退回编译成 PNG。
 */
async function insertSvg() {
  const code = document.getElementById('svg-code').value;
  const analysis = analyzeSvg(code);
  const mode = svgMode(); // auto / vector / raster

  const useVector = mode === 'vector' || (mode === 'auto' && analysis.canVector);
  const extracted = useVector ? extractSvgPath(code) : null;

  if (useVector && !extracted) {
    svgMessage('✗ 矢量模式要求 SVG 里有 <path>', false);
    return;
  }

  if (extracted) {
    const node = {
      id: uniqueId('svg'),
      type: 'rect',
      visible: true,
      x: 25, y: 25,
      width: 30, height: 30,
      color: '#FFFFFFFF',
      opacity: 1,
      shape: 'path',
      path: extracted.d,
      viewBox: extracted.viewBox
    };
    placeInTarget(node, 1);
    insertTargetList().push(node);
    state.sel.rectId = node.id;
    state.sel.channel = -1;
    closeSvgPanel();
    renderAll();
    status('已插入矢量路径（viewBox ' + extracted.viewBox + '）');
    return;
  }

  // 兜底：没有 <path>，编译成 PNG
  const png = await previewSvg();
  if (!png) {
    svgMessage('✗ 这个 SVG 里没有 <path>，也没能编译成位图', false);
    return;
  }
  let n = 1;
  while (state.doc.resources[`svg_${n}.png`]) n++;
  const key = `svg_${n}.png`;
  state.doc.resources[key] = png;

  const size = svgSize();
  const node = {
    id: uniqueId('svgimg'),
    type: 'image',
    visible: true,
    x: 25, y: 25,
    width: size / 10,
    // 按原始比例，别把插画拉成正方形
    height: round1((size / 10) * (analysis.aspect || 1)),
    opacity: 1, src: key, color: '#FFFFFFFF'
  };
  placeInTarget(node, 1);
  insertTargetList().push(node);
  state.sel.rectId = node.id;
  state.sel.channel = -1;
  closeSvgPanel();
  renderAll();
  const why = mode === 'raster' ? '按你的选择' : (analysis.reason || '没有 <path>');
  status(`已编译为 ${size}px PNG（${why}）`);
}
/**
 * 解析渐变字段：`linear:90:0#FF3366FF;1#FF0B0D10`。
 * 与 Android 的 GradientSpec 同一套语法。
 */
function parseGradient(raw) {
  const text = String(raw || '').trim();
  if (!text) return null;
  const first = text.indexOf(':');
  const second = text.indexOf(':', first + 1);
  if (first < 0 || second < 0) return null;

  const type = text.slice(0, first).trim().toLowerCase();
  if (type !== 'linear' && type !== 'radial') return null;
  const angle = Number(text.slice(first + 1, second)) || 0;

  const stops = text.slice(second + 1).split(';').map(chunk => {
    const item = chunk.trim();
    const hash = item.indexOf('#');
    if (hash <= 0) return null;
    const pos = Number(item.slice(0, hash));
    if (!isFinite(pos)) return null;
    return { pos: Math.min(1, Math.max(0, pos)), color: '#' + item.slice(hash + 1).trim() };
  }).filter(Boolean);

  if (stops.length < 2) return null;
  return { type, angle, stops: stops.sort((a, b) => a.pos - b.pos) };
}

function encodeGradient(type, angle, stops) {
  const body = stops.map(s => `${Math.round(s.pos * 100) / 100}${s.color}`).join(';');
  return `${type}:${angle}:${body}`;
}

/** 按节点尺寸生成 CanvasGradient（没有渐变返回 null）。 */
function gradientFillFor(node, wPx, hPx) {
  const spec = parseGradient(node.gradient);
  if (!spec) return null;

  if (spec.type === 'radial') {
    const g = ctx.createRadialGradient(wPx / 2, hPx / 2, 0, wPx / 2, hPx / 2, Math.max(wPx, hPx) / 2);
    spec.stops.forEach(s => g.addColorStop(s.pos, cssColor(s.color)));
    return g;
  }

  const rad = (spec.angle * Math.PI) / 180;
  const dx = Math.cos(rad);
  const dy = Math.sin(rad);
  const len = Math.abs(dx) * wPx / 2 + Math.abs(dy) * hPx / 2;
  const g = ctx.createLinearGradient(
    wPx / 2 - dx * len, hPx / 2 - dy * len,
    wPx / 2 + dx * len, hPx / 2 + dy * len
  );
  spec.stops.forEach(s => g.addColorStop(s.pos, cssColor(s.color)));
  return g;
}

/**
 * 把内部的 `#AARRGGBB` 转成浏览器认识的 `rgba(r,g,b,a)`。
 *
 * 关键差异：CSS 的 8 位十六进制是 `#RRGGBBAA`（alpha 在后），内部格式是 `#AARRGGBB`（alpha 在前）。
 * 直接把手里的字符串塞给 addColorStop 会被按 RGBA 解读，颜色整体错位——
 * 绿会变粉、白会变青（真机实测就是这个现象）。
 */
function cssColor(value) {
  const c = parseColor(value);
  const a = c.a === undefined ? 1 : c.a;
  return `rgba(${Math.round(c.r)},${Math.round(c.g)},${Math.round(c.b)},${Math.round(a * 1000) / 1000})`;
}
/**
 * PS 式羽化：只柔化**边缘 alpha**，内容保持清晰。
 *
 * 做法是两遍渲染：
 * 1. 把内容原样画到离屏画布 A；
 * 2. 把同一内容用 blur 滤镜画到离屏画布 B（当作遮罩）；
 * 3. A 用 destination-in 合成 B —— A 的 RGB 保留，只有 alpha 边缘被柔化。
 *
 * 直接 ctx.filter = blur 会把颜色一起糊掉，那是"模糊滤镜"不是"羽化"。
 */
/** 取 viewBox 的宽或高，解析失败按 24。 */
function viewBoxSize(node, which) {
  const parts = String(node.viewBox || '').trim().split(/[\s,]+/).map(Number);
  if (parts.length === 4 && parts[2] > 0 && parts[3] > 0) {
    return which === 'w' ? parts[2] : parts[3];
  }
  return 24;
}

/**
 * PS 式像素羽化：从四周向内 [bandPx] 像素做渐变，最外圈 alpha = 0，内部不动。
 *
 * 与"模糊"的区别（用户明确要的语义）：模糊是对称扩散、边缘不会真正透明；
 * 羽化只在四周留一条渐变带，带内单调淡出，带外一个像素都不改。
 *
 * 算法与 Android 的 FeatherBlur.applyEdgeRamp 完全一致（smoothstep），
 * 所以编辑器里看到的和手机上跑出来的一致。
 */


function drawWithAlphaFeather(wPx, hPx, bandPx, drawContent, outlinePoints) {
  const w = Math.max(1, Math.round(wPx));
  const h = Math.max(1, Math.round(hPx));

  const content = document.createElement("canvas");
  content.width = w;
  content.height = h;
  const cctx = content.getContext("2d");
  drawContent(cctx);

  const band = Math.max(1, Math.round(bandPx));
  const image = cctx.getImageData(0, 0, w, h);
  const data = image.data;

  // 有轮廓（蒙版/圆角）时沿轮廓羽化，否则沿矩形边缘（默认逻辑）
  const outline = outlinePoints && outlinePoints.length >= 3 ? outlinePoints : null;

  for (let y = 0; y < h; y++) {
    const py = y + 0.5;
    for (let x = 0; x < w; x++) {
      const px = x + 0.5;
      const index = (y * w + x) * 4 + 3;

      if (outline) {
        if (!pointInPolygon(outline, px, py)) {
          data[index] = 0;
          continue;
        }
        const dist = distanceToOutline(outline, px, py);
        if (dist >= band) continue;
        let f = dist / band;
        f = f * f * (3 - 2 * f);
        data[index] = Math.round(data[index] * f);
        continue;
      }

      const dist = Math.min(x, w - 1 - x, y, h - 1 - y);
      if (dist >= band) continue;
      let f = dist / band;
      f = f * f * (3 - 2 * f);
      data[index] = Math.round(data[index] * f);
    }
  }

  cctx.putImageData(image, 0, 0);
  ctx.drawImage(content, 0, 0);
}

/** 射线法：点是否在多边形内。 */
function pointInPolygon(pts, x, y) {
  let inside = false;
  const n = pts.length;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = pts[i][0], yi = pts[i][1];
    const xj = pts[j][0], yj = pts[j][1];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-9) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

/** 点到多边形边界的最短距离。 */
function distanceToOutline(pts, x, y) {
  let best = Infinity;
  const n = pts.length;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const ax = pts[i][0], ay = pts[i][1];
    const bx = pts[j][0], by = pts[j][1];
    const dx = bx - ax, dy = by - ay;
    const lenSq = dx * dx + dy * dy;
    const t = lenSq <= 1e-6 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / lenSq));
    const px = ax + t * dx, py = ay + t * dy;
    const d = Math.hypot(x - px, y - py);
    if (d < best) best = d;
  }
  return best;
}
/**
 * 用另一张图的 alpha 裁剪当前图层（图片蒙版）。
 *
 * 内容画进离屏画布 → 蒙版图按元素尺寸缩放后用 destination-in 合成 →
 * 结果贴回主画布。与手机端"逐点相乘 alpha"视觉一致。
 */
function drawWithImageMask(wPx, hPx, maskImg, drawContent) {
  const w = Math.max(1, Math.round(wPx));
  const h = Math.max(1, Math.round(hPx));

  const content = document.createElement('canvas');
  content.width = w;
  content.height = h;
  const cctx = content.getContext('2d');
  drawContent(cctx);

  cctx.globalCompositeOperation = 'destination-in';
  cctx.drawImage(maskImg, 0, 0, w, h);
  cctx.globalCompositeOperation = 'source-over';

  ctx.drawImage(content, 0, 0);
}

/**
 * 元素的实际轮廓（编辑器侧）：蒙版优先，否则自身形状 + 圆角。
 *
 * 与 Android 的 Shapes.effectivePoints 规则一致；
 * 圆角半径按"占元素宽度的百分比"换算成像素。
 */
function effectivePointsOf(node, wPx, hPx) {
  const mask = node.mask || '';
  const radius = ((Number(node.cornerRadius) || 0) / 100) * wPx;

  // 蒙版是图层内的**独立对象**：有自己的位置与尺寸（占图层宽高的百分比）。
  // 默认 0/0/100/100 = 铺满图层，所以老预设行为不变。
  if (mask) {
    const maskX = Number(node.maskX) || 0;
    const maskY = Number(node.maskY) || 0;
    const maskW = Number(node.maskW) || 100;
    const maskH = Number(node.maskH) || 100;

    const mw = (Math.max(1, maskW) / 100) * wPx;
    const mh = (Math.max(1, maskH) / 100) * hPx;
    // maskX/maskY 是相对**容器中心**的偏移：默认 0 时蒙版居中（与引擎一致）
    const ox = (wPx - mw) / 2 + (maskX / 100) * wPx;
    const oy = (hPx - mh) / 2 + (maskY / 100) * hPx;

    let pts;
    if (mask === 'rect' && radius > 0.5) {
      pts = roundedRectPoints(mw, mh, Math.min(radius, Math.min(mw, mh) / 2));
    } else {
      pts = shapePointsBox(mask, node.maskSides || 5, mw, mh);
    }

    // 平移到蒙版位置；非矩形再圆角化（与 Android 的 effectivePoints 一致）
    const shifted = pts.map(([px, py]) => [px + ox, py + oy]);
    return radius > 0.5 && mask !== 'rect' ? roundPolygonPoints(shifted, radius) : shifted;
  }

  // 无蒙版 → 自身形状
  const kind = node.shape || 'rect';
  const sides = node.sides || 5;

  if (kind === 'rect' && radius > 0.5) {
    return roundedRectPoints(wPx, hPx, Math.min(radius, Math.min(wPx, hPx) / 2));
  }

  const base = shapePointsBox(kind, sides, wPx, hPx);
  // 非矩形加圆角：把顶点圆滑化（与 Android 的 Shapes.roundPolygon 同一公式）
  return radius > 0.5 ? roundPolygonPoints(base, radius) : base;
}

/**
 * 多边形圆角化：每个顶点用二次贝塞尔替掉（采样 5 段）。
 * 与 Android 的 Shapes.roundPolygon 用同一公式，两端结果一致。
 */
function roundPolygonPoints(points, radius) {
  const n = points.length;
  if (n < 3 || radius <= 0.5) return points;

  const out = [];
  for (let i = 0; i < n; i++) {
    const [vx, vy] = points[i];
    const [ax, ay] = points[(i - 1 + n) % n];
    const [bx, by] = points[(i + 1) % n];

    const vax = ax - vx, vay = ay - vy;
    const vbx = bx - vx, vby = by - vy;
    const lenA = Math.hypot(vax, vay);
    const lenB = Math.hypot(vbx, vby);
    if (lenA < 1e-3 || lenB < 1e-3) { out.push([vx, vy]); continue; }

    const uax = vax / lenA, uay = vay / lenA;
    const ubx = vbx / lenB, uby = vby / lenB;

    const dot = Math.max(-1, Math.min(1, uax * ubx + uay * uby));
    const theta = Math.acos(dot);
    if (theta < 1e-3 || theta > Math.PI - 1e-3) { out.push([vx, vy]); continue; }

    const tanHalf = Math.tan(theta / 2);
    const sinHalf = Math.sin(theta / 2);
    let tangent = radius / tanHalf;
    tangent = Math.max(0.01, Math.min(tangent, Math.min(lenA, lenB) / 2));

    const p1x = vx + uax * tangent, p1y = vy + uay * tangent;
    const p2x = vx + ubx * tangent, p2y = vy + uby * tangent;

    // 圆心：沿角平分线，距离 = tangent * tanHalf / sinHalf
    let bisX = uax + ubx, bisY = uay + uby;
    const bisLen = Math.hypot(bisX, bisY);
    if (bisLen < 1e-4) { out.push([vx, vy]); continue; }
    bisX /= bisLen; bisY /= bisLen;
    const centerDist = (tangent * tanHalf) / sinHalf;
    const cx = vx + bisX * centerDist;
    const cy = vy + bisY * centerDist;

    const arcR = Math.hypot(p1x - cx, p1y - cy);
    const startAngle = Math.atan2(p1y - cy, p1x - cx);
    const endAngle = Math.atan2(p2y - cy, p2x - cx);
    let sweep = endAngle - startAngle;
    while (sweep > Math.PI) sweep -= 2 * Math.PI;
    while (sweep < -Math.PI) sweep += 2 * Math.PI;

    // 按弧长自适应分段（每 6° 一段，3..32），大半径才不会露出折面
    const segments = Math.max(3, Math.min(32, Math.ceil(Math.abs(sweep) / (Math.PI / 30))));
    for (let s = 0; s <= segments; s++) {
      const a = startAngle + (sweep * s) / segments;
      out.push([cx + arcR * Math.cos(a), cy + arcR * Math.sin(a)]);
    }
  }
  return out;
}
/** 圆角矩形顶点（每角 6 段），与 Android 的 roundedRect 对应。 */
function roundedRectPoints(w, h, r) {
  const out = [];
  const corners = [
    [r, r, 180],
    [w - r, r, 270],
    [w - r, h - r, 0],
    [r, h - r, 90]
  ];
  const seg = 6;
  for (const [cx, cy, startDeg] of corners) {
    for (let i = 0; i <= seg; i++) {
      const deg = startDeg + (90 * i) / seg;
      const rad = (deg * Math.PI) / 180;
      out.push([cx + r * Math.cos(rad), cy + r * Math.sin(rad)]);
    }
  }
  return out;
}
/**
 * 蒙版 / 圆角 / 图片蒙版 / 羽化 四个控件（文字图层复用）。
 *
 * 抽成函数是因为形状和文字都要这几行；字段语义与引擎完全一致：
 * cornerRadius 与 feather 都是"占元素宽度的百分比"。
 */
function maskFeatherControls(node, field) {
  field('圆角', [
    sliderWithInput({
      min: 0, max: 50, step: 0.5,
      value: node.cornerRadius || 0,
      unit: '%',
      display: v => Math.round(v * 10) / 10,
      toValue: v => v,
      onInput: v => { node.cornerRadius = Math.max(0, v); scheduleDraw(); },
      onCommit: () => renderAll(true)
    })
  ]);

  const maskSelect = document.createElement('select');
  [['', '无（不裁剪）'], ['rect', '矩形'], ['ellipse', '圆形'], ['triangle', '三角'],
   ['trapezoid', '梯形'], ['polygon', '多边形'], ['star', '星形']].forEach(([value, label]) => {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    maskSelect.appendChild(option);
  });
  maskSelect.value = node.mask || '';
  maskSelect.onchange = () => {
    if (maskSelect.value) node.mask = maskSelect.value;
    else delete node.mask;
    renderAll(true);
    scheduleDraw();
  };
  const extra = [maskSelect];
  if (node.mask === 'polygon' || node.mask === 'star') {
    extra.push(numberField(node.maskSides || 5, v => {
      node.maskSides = clamp(Math.round(v), 3, 32);
      renderAll(true);
      scheduleDraw();
    }));
  }
  field('蒙版', extra);

  // 蒙版是图层内的独立对象：位置与尺寸都相对图层框（百分比）
  if (node.mask) {
    node.maskX = Number(node.maskX) || 0;
    node.maskY = Number(node.maskY) || 0;
    node.maskW = Number(node.maskW) || 100;
    node.maskH = Number(node.maskH) || 100;

    const maskPercent = (key, label, max) => field(label, [
      sliderWithInput({
        min: 0, max, step: 0.5,
        value: node[key],
        unit: '%',
        display: v => Math.round(v * 10) / 10,
        toValue: v => v,
        onInput: v => { node[key] = Math.max(0, v); scheduleDraw(); },
        onCommit: () => renderAll(true)
      })
    ]);

    maskPercent('maskX', '蒙版 X', 100);
    maskPercent('maskY', '蒙版 Y', 100);
    maskPercent('maskW', '蒙版宽', 200);
    maskPercent('maskH', '蒙版高', 200);
  }


  field('羽化', [
    sliderWithInput({
      min: 0, max: 50, step: 0.5,
      value: node.feather || 0,
      unit: '%',
      display: v => Math.round(v * 10) / 10,
      toValue: v => v,
      onInput: v => { node.feather = Math.max(0, v); scheduleDraw(); },
      onCommit: () => renderAll(true)
    })
  ]);

}
/** 把形状画到当前（已缩放的）局部坐标系。 */
/**
 * 单个图层的视差参数：景深（幅度）+ 方向（高级）。
 *
 * 为什么方向默认 0：全局同向是最不容易出错的做法 —— 编辑器不知道作者的版式，
 * 随便错位可能让画面看起来"撕开"。方向只在确定需要交叉层次时才动，
 * 所以收进 <details> 折叠，避免误触。
 */
/**
 * 「视差（本图层）」区块。
 *
 * 两档模式，符合"其它默认跟随全局，个别单独调"的直觉：
 *   - 跟随全局（默认）：只用本层景深，振幅/方向都用全局设置（全局恒为同向）
 *   - 单独设置：本层有自己的振幅与方向角，可做交叉错位
 *
 * 景深在两种模式下都存在 —— 它本来就是"这一层有多远"的唯一表达。
 */
/* parallaxLayerControls 已废弃：v3 合并进 renderParallaxGlobals */

function fillShapePath(node) {
  if (node.shape === 'path') {
    fillVectorPath(node);
    return;
  }
  // 蒙版优先；否则自身形状 + 圆角（与引擎端同一套规则）
  const points = effectivePointsOf(node, worldToPx(node.width), worldToPx(node.height));
  ctx.beginPath();
  points.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
  ctx.closePath();
  ctx.fill();
}

/**
 * 画 SVG 矢量路径。
 *
 * 把 d 原样交给浏览器原生 Path2D（它自己处理贝塞尔/圆弧/even-odd），
 * 再用 ctx.scale 把 viewBox 单位缩放到元素框——编辑器和手机看的是同一份数据，
 * 不会出现"编辑器是心形、手机是方块"。
 */
function fillVectorPath(node) {
  if (!node.path || typeof Path2D === 'undefined') return;
  let vw = 24;
  let vh = 24;
  const parts = String(node.viewBox || '').trim().split(/[\s,]+/).map(Number);
  if (parts.length === 4 && parts[2] > 0 && parts[3] > 0) {
    vw = parts[2];
    vh = parts[3];
  }
  // 宽高关键帧：预览里也得跟着变，否则编辑器"看不到"自己调的效果
  const wPx = worldToPx((overridesCached()[node.id] || {}).width !== undefined ? (overridesCached()[node.id] || {}).width : node.width);
  const hPx = worldToPx((overridesCached()[node.id] || {}).height !== undefined ? (overridesCached()[node.id] || {}).height : node.height);
  const path = new Path2D(node.path);
  ctx.save();
  ctx.scale(wPx / vw, hPx / vh);
  if (node.strokeWidth > 0) {
    // 线框图标：描边。lineWidth 用 viewBox 单位，已被上面的 scale 换算
    ctx.strokeStyle = ctx.fillStyle;
    ctx.lineWidth = node.strokeWidth;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.stroke(path);
  } else {
    ctx.fill(path, 'evenodd');
  }
  ctx.restore();
}
function isContainer(node) { return node && node.type === 'container'; }
function srcOf(node) { return node && node.src ? state.doc.resources[node.src] : null; }

/** 递归拍平所有节点（绘制顺序）。 */
function allNodes() {
  const out = [];
  const walk = nodes => {
    for (const n of nodes) {
      out.push(n);
      if (isContainer(n)) walk(n.children || []);
    }
  };
  walk(rects());
  return out;
}

function findNode(id) {
  if (!id) return null;
  return allNodes().find(n => n.id === id) || null;
}

/** 节点所在的 children 数组（根或某个容器的）。 */
function parentListOf(id) {
  const search = nodes => {
    if (nodes.some(n => n.id === id)) return nodes;
    for (const n of nodes) {
      if (isContainer(n)) {
        const found = search(n.children || []);
        if (found) return found;
      }
    }
    return null;
  };
  return search(rects());
}

/** 父容器节点；根下的元素返回 null。 */
function parentOf(id) {
  const list = parentListOf(id);
  if (!list || list === rects()) return null;
  return allNodes().find(n => isContainer(n) && (n.children || []) === list) || null;
}

/** 新元素插到哪里：选中容器就插进容器，否则插到根。 */
function insertTargetList() {
  const selected = selectedRect();
  return isContainer(selected) ? selected.children : rects();
}

/**
 * 往容器里放新元素时，按**容器尺寸**算默认位置和大小。
 *
 * 之前用的是画布默认值（x=25,y=40），容器只有 60×30 时一加就跑到容器外面，
 * 看起来像"没进容器"。这里改成居中并夹到容器内。
 *
 * @param node 新节点（会被就地修改）
 * @param aspect 高/宽比；图片传原始比例，其他传 null
 */
function placeInTarget(node, aspect) {
  const container = selectedRect();
  if (!isContainer(container)) return; // 加在根上就保持画布默认值

  const padding = container.width * 0.1;
  const maxW = Math.max(1, container.width - padding * 2);
  const maxH = Math.max(1, container.height - padding * 2);

  let w = Math.min(node.width, maxW);
  let h = aspect ? w * aspect : Math.min(node.height, maxH);
  if (h > maxH) {
    h = maxH;
    if (aspect) w = h / aspect;
  }

  node.width = round1(w);
  node.height = round1(h);
  node.x = round1((container.width - node.width) / 2);
  node.y = round1((container.height - node.height) / 2);
}
function uniqueId(prefix) {
  let i = 1;
  while (findNode(`${prefix}_${i}`)) i++;
  return `${prefix}_${i}`;
}


/** 文本行高系数：字号 × 1.25。 */
const TEXT_LINE_HEIGHT = 1.25;

/** 文本高度由字号和行数决定；改内容/字号后同步，否则命中框和选中框会错位。 */
function syncTextBox(node) {
  const lines = String(node.text || '').split('\n').length;
  node.height = round1(Math.max(node.fontSize, lines * node.fontSize * TEXT_LINE_HEIGHT));
}

/** 画文本图层：字号跟随缩放，支持左/中/右对齐与多行。 */
function drawTextNode(node, t, color) {
  const W = canvas.width;
  // 画布显示**解析后的值**（{sys.mem.usedPct:00} → 60），和手机端一致。
  // 输入框里仍然是原始模板文本，方便继续编辑。
  const lines = renderTemplate(node.text || '').split('\n');
  const fontPx = (node.fontSize / 100) * W * (t.scale || 1);
  ctx.save();
  ctx.translate(worldToPx(t.x + t.w / 2), worldToPx(t.y + t.h / 2));
  ctx.rotate((t.rotation || 0) * Math.PI / 180);
  ctx.globalAlpha = clamp(t.opacity, 0, 1) * color.a;
  ctx.fillStyle = `rgb(${color.r},${color.g},${color.b})`;
  ctx.font = `${fontPx}px system-ui, "Microsoft YaHei", sans-serif`;
  ctx.textBaseline = 'top';
  const boxW = worldToPx(t.w);
  const lineH = fontPx * TEXT_LINE_HEIGHT;
  const totalH = lineH * lines.length;
  lines.forEach((line, i) => {
    const w = ctx.measureText(line).width;
    let px = -boxW / 2;
    if (node.align === 'center') px = -w / 2;
    else if (node.align === 'right') px = boxW / 2 - w;
    ctx.fillText(line, px, -totalH / 2 + i * lineH);
  });
  ctx.restore();
}

/** 取（并按需解码）图片元素；解码完成后会自动重绘。 */
function imageOf(node) {
  const url = srcOf(node);
  if (!url) return null;
  let img = imageCache.get(node.src);
  if (!img) {
    img = new Image();
    img.onload = () => scheduleDraw();
    img.src = url;
    imageCache.set(node.src, img);
  }
  return img.complete && img.naturalWidth ? img : (img.naturalWidth ? img : null);
}
function clips() { return state.doc.interactions.animations; }
function selectedRect() { return findNode(state.sel.rectId); }
/**
 * 某个图层的全部动画组。
 *
 * 引擎侧一直允许「一个图层挂多条动画」（只校验 clip id 唯一），
 * 但编辑器早期用 find 只取第一条，等于把多组能力藏起来了。
 * 现在按 targetId 取全部，顺序即创建顺序。
 */
function clipsFor(rectId) {
  if (!rectId) return [];
  return clips().filter(c => c.targetId === rectId);
}

/**
 * 当前选中的那条（当前组）。
 * state.sel.groupId 记着用户点的组；切图层后它可能不属于这个图层，此时回退到第一组。
 */
function clipFor(rect) {
  if (!rect) return null;
  const all = clipsFor(rect.id);
  if (!all.length) return null;
  const picked = all.find(c => c.id === state.sel.groupId);
  if (picked) return picked;
  state.sel.groupId = all[0].id;
  return all[0];
}
function selectedClip() { return clipFor(selectedRect()); }
function trackFor(clip, prop) { return clip ? clip.tracks.find(t => t.property === prop) || null : null; }
function channelByIndex(i) { return CHANNELS[i] || null; }

function staticValue(rect, prop) {
  if (prop === 'scale') return rect.scale === undefined ? 1 : rect.scale;
  if (prop === 'rotationZ') return rect.rotationZ || 0;
  if (prop === 'opacity') return rect.opacity === undefined ? 1 : rect.opacity;
  // 宽高的"静态值"就是节点自身尺寸，不能像 x/y 那样按 0 兜底
  if (prop === 'width' || prop === 'height') return rect[prop] === undefined ? 0 : rect[prop];
  return rect[prop] || 0;
}

/**
 * 元素在**当前播放头**处的有效缩放。
 *
 * 为什么需要它：显示尺寸 = 基准尺寸 × 缩放。用户在画布上拖边框时看到的是显示尺寸，
 * 如果直接写进基准尺寸，缩放不为 100% 时就会突然放大或缩小。
 */
function effectiveScale(rect) {
  let s = rect.scale === undefined ? 1 : rect.scale;
  const clip = clipFor(rect);
  const track = clip ? trackFor(clip, 'scale') : null;
  if (clip && track && track.keyframes.length) {
    const dur = clip.tracks[0] && clip.tracks[0].durationMs ? clip.tracks[0].durationMs : 1000;
    const phase = (state.playhead * dur) / Math.max(1, track.durationMs);
    const v = sampleTrack(track, loopedTime(phase, track.loop));
    if (typeof v === 'number') s = v;
  }
  return Math.max(0.01, s);
}

/** 把视觉尺寸换算成基准尺寸。 */
function visualToBase(value, scale) {
  return round1(Math.max(0.5, value / Math.max(0.01, scale)));
}
function parseColor(hex) {
  const raw = String(hex || '#FF000000').replace('#', '').trim();
  if (raw.length === 6) return { a: 1, r: parseInt(raw.slice(0, 2), 16), g: parseInt(raw.slice(2, 4), 16), b: parseInt(raw.slice(4, 6), 16) };
  if (raw.length === 8) return { a: parseInt(raw.slice(0, 2), 16) / 255, r: parseInt(raw.slice(2, 4), 16), g: parseInt(raw.slice(4, 6), 16), b: parseInt(raw.slice(6, 8), 16) };
  return { a: 1, r: 0, g: 0, b: 0 };
}
function colorToHex(c) {
  const h = v => clamp(Math.round(v), 0, 255).toString(16).padStart(2, '0').toUpperCase();
  return '#' + h(c.a * 255) + h(c.r) + h(c.g) + h(c.b);
}
function colorToInput(hex) {
  const c = parseColor(hex);
  return '#' + [c.r, c.g, c.b].map(v => clamp(Math.round(v), 0, 255).toString(16).padStart(2, '0')).join('');
}

// ---------------------------------------------------------------- 缓动与求值

function bezierEase(spec, u) {
  if (!spec || spec === 'linear') return u;
  const p = EASING_BEZIER[spec] || [0.42, 0, 0.58, 1];
  const [x1, y1, x2, y2] = p;
  const bx = t => 3 * (1 - t) * (1 - t) * t * x1 + 3 * (1 - t) * t * t * x2 + t * t * t;
  const by = t => 3 * (1 - t) * (1 - t) * t * y1 + 3 * (1 - t) * t * t * y2 + t * t * t;
  let lo = 0, hi = 1, mid = u;
  for (let i = 0; i < 20; i++) { mid = (lo + hi) / 2; if (bx(mid) < u) lo = mid; else hi = mid; }
  return clamp(by(mid), 0, 1);
}

function sampleTrack(track, t) {
  const kfs = track.keyframes;
  if (!kfs.length) return null;
  if (kfs.length === 1) return kfs[0].value;
  if (t <= kfs[0].t) return kfs[0].value;
  if (t >= kfs[kfs.length - 1].t) return kfs[kfs.length - 1].value;
  for (let i = 0; i < kfs.length - 1; i++) {
    const a = kfs[i], b = kfs[i + 1];
    if (t <= b.t) {
      const span = (b.t - a.t) || 1;
      const eased = bezierEase(a.easing, (t - a.t) / span);
      return a.value + (b.value - a.value) * eased;
    }
  }
  return kfs[kfs.length - 1].value;
}

function loopedTime(phase, loop) {
  if (loop === 'loop') return phase - Math.floor(phase);
  if (loop === 'pingpong') { const c = phase - 2 * Math.floor(phase / 2); return c <= 1 ? c : 2 - c; }
  return clamp(phase, 0, 1);
}

/** 某个通道在给定 t 的当前值（有轨道且有关键帧就采样，否则取静态值）。 */
function channelValueAt(clip, rect, channel, t) {
  const out = {};
  for (const prop of channel.props) {
    const track = trackFor(clip, prop);
    if (track && track.keyframes.length) out[prop] = sampleTrack(track, t);
    else out[prop] = staticValue(rect, prop);
  }
  return out;
}

function channelArmed(clip, channel) {
  if (!clip) return false;
  return channel.props.some(p => {
    const t = trackFor(clip, p);
    return !!(t && t.keyframes.length);
  });
}

function channelTrack(clip, channel) {
  for (const p of channel.props) {
    const t = trackFor(clip, p);
    if (t && t.keyframes.length) return t;
  }
  return trackFor(clip, channel.props[0]);
}

// ---------------------------------------------------------------- 关键帧写入（PR 的自动关键帧）

function writeKeyframe(track, t, value, easing) {
  const idx = track.keyframes.findIndex(k => Math.abs(k.t - t) < 0.015);
  if (idx >= 0) track.keyframes[idx].value = value;
  else {
    track.keyframes.push({ t: round2(t), value, easing: easing || 'linear' });
    sortTrack(track);
  }
}

/** 把整个通道在当前播放头落帧；值来自 values（缺省用静态值）。 */
function writeChannelAt(clip, rect, channel, values) {
  const t = round2(state.playhead);
  for (const prop of channel.props) {
    let track = trackFor(clip, prop);
    if (!track) {
      track = {
        property: prop,
        durationMs: clip.tracks[0]?.durationMs || 2000,
        loop: clip.tracks[0]?.loop || 'loop',
        keyframes: []
      };
      clip.tracks.push(track);
    }
    const v = values && values[prop] !== undefined ? values[prop] : staticValue(rect, prop);
    writeKeyframe(track, t, round1(v));
  }
}

/** ◇ 开关：开=落帧并进入关键帧模式；关=把当前值固化成静态值并移除轨道。 */
function toggleChannel(channelIndex) {
  const rect = selectedRect();
  if (!rect) { status('先选中一个元素'); return; }
  const clip = ensureClip(rect);
  const channel = CHANNELS[channelIndex];
  if (!channel) return;

  if (channelArmed(clip, channel)) {
    const current = channelValueAt(clip, rect, channel, state.playhead);
    for (const prop of channel.props) {
      const track = trackFor(clip, prop);
      if (track) {
        rect[prop] = round1(current[prop]);
        clip.tracks = clip.tracks.filter(t => t !== track);
      }
    }
  } else {
    writeChannelAt(clip, rect, channel, null);
  }
  state.sel.channel = channelIndex;
  state.sel.keyIndex = 0;
  renderAll();
}

/** 新建一个动画组并设为当前组。组名可改，默认「动画组 N」。 */
function newClipGroup(rect, name) {
  if (!rect) return null;
  let i = 1;
  while (clips().some(c => c.id === `anim_${rect.id}_${i}`)) i++;
  const clip = {
    id: `anim_${rect.id}_${i}`,
    name: name || `动画组 ${clipsFor(rect.id).length + 1}`,
    targetId: rect.id,
    trigger: { type: 'tap' },
    tracks: []
  };
  clips().push(clip);
  state.sel.groupId = clip.id;
  return clip;
}

function ensureClip(rect) {
  return clipFor(rect) || newClipGroup(rect);
}

/** 删除当前动画组（同一图层只剩一组时也允许删，删完轨道就空了）。 */
function deleteClipGroup(clip) {
  if (!clip) return false;
  const all = clips();
  const idx = all.findIndex(c => c.id === clip.id);
  if (idx < 0) return false;
  all.splice(idx, 1);
  state.sel.groupId = null;
  return true;
}

function sortTrack(track) {
  track.keyframes.sort((a, b) => a.t - b.t);
  const out = [];
  for (const kf of track.keyframes) {
    if (!out.length || kf.t - out[out.length - 1].t > 0.001) out.push(kf);
    else out[out.length - 1] = kf;
  }
  track.keyframes = out;
}

// ---------------------------------------------------------------- 画布

function resizeCanvas() {
  // 预览面板现在是浮动定位元素（宽高由 canvas 自己撑开），
  // 所以可用空间必须取外层 .canvas-wrap；取 parentElement 会拿到被压扁的面板尺寸，
  // 结果画布缩成很小一块（真实踩过）。
  const wrap = canvas.closest('.canvas-wrap') || canvas.parentElement;
  const availW = Math.max(200, wrap.clientWidth - 28);
  // 给顶部刘海留出高度：否则面板几乎占满整高，防越界钳制会把面板锁在中间，
  // 永远碰不到刘海（磁吸就永远不触发）。
  const notchH = (typeof notchHeight === 'function') ? notchHeight() : 15;
  const availH = Math.max(200, wrap.clientHeight - notchH - 34);
  let h = availH, w = h * PHONE_ASPECT;
  if (w > availW) { w = availW; h = w / PHONE_ASPECT; }
  // 超采样：显示尺寸随窗口变，但位图宽度固定 ~900px。
  // 之前位图按 CSS 尺寸 1:1 出图，面板一缩小分辨率就跟着掉，看着发糊。
  const dpr = window.devicePixelRatio || 1;
  const renderScale = Math.max(dpr, Math.min(3, 900 / Math.max(1, w)));
  canvas.style.width = w + 'px';
  canvas.style.height = h + 'px';
  canvas.width = Math.round(w * renderScale);
  canvas.height = Math.round(h * renderScale);
  draw();
}

/** 画布单位 → 像素：单位是"占画布宽度的百分比"，1 单位 = 画布宽 / 100。 */
function worldToPx(u) { return (u / 100) * canvas.width; }

/**
 * 画布位图 / 屏幕显示的比值（超采样倍率）。
 *
 * 为什么必须有它：canvas.width 固定约 900，而屏幕上的宽度可能是 465 —— 比值约 1.94。
 * 手柄尺寸、命中半径如果直接按画布像素写，落到屏幕上就只有一半，
 * 表现就是"手柄小得看不见、边长拖不动、蒙版点不中"。
 * 所以凡是"希望用户摸到多大"的数字，都要先乘这个比值换算成画布像素。
 */
function canvasPixelRatio() {
  const box = canvas.getBoundingClientRect();
  return box.width > 0 ? canvas.width / box.width : 1;
}

/** 屏幕像素 → 画布像素。 */
function screenToCanvasPx(px) { return px * canvasPixelRatio(); }

/**
 * 当前播放头下，选中动画的属性覆盖值。
 *
 * 返回 **{ 元素id: { 属性: 值 } }** —— 必须按元素分组。
 * 之前返回扁平 { 属性: 值 }，再配合一个恒真的判断，把选中动画套到了所有图层上，
 * 造成"绿条的关键帧也会驱动蓝条"。
 */
// 模拟面板里能改的系统值 → 绑定预览用。其余给固定占位，只为让画布能看出趋势。
function simValueOf(source) {
  const sim = state.sim || {};
  switch (source) {
    case 'sys.battery.level': return Number(sim.battery ?? 100);
    case 'sys.time.hour': return Number(sim.hour ?? 12);
    case 'sys.time.minute': return Number(sim.minute ?? 0);
    case 'sys.battery.temp': return Number(sim.temp ?? 32);
    case 'sys.notify.count': return Number(sim.notify ?? 3);
    // 引擎里的真名是 usedPct；旧名字保留，避免老预设预览突然变 0
    case 'sys.mem.usedPct':
    case 'sys.mem.used': return Number(sim.mem ?? 60);
    case 'sys.storage.usedPct':
    case 'sys.storage.used': return Number(sim.storage ?? 50);
    default: {
      // 其余系统变量（音乐进度、音频频谱等）用统一的预览值表兜底。
      // 之前这里直接 return 0：绑定音频后画布上柱子永远是平的，看起来像"绑定没生效"。
      const v = previewVar(source);
      return v === undefined ? 0 : Number(v);
    }
  }
}

/** 绑定 → overrides（与引擎同样的线性映射：in 区间 → out 区间） */
function bindingOverrides() {
  const out = {};
  // 预览开关：关掉后图层回到手输静态值，可以自由拖动；
  // 开着时绑定的轴由数据算出，拖动会被覆盖回去（预期行为）
  const toggle = document.getElementById('bind-preview');
  if (toggle && !toggle.checked) return out;
  // 绑定的唯一真相是「触发器工作台」里的节点；旧字段只在没有节点时兜底。
  // 之前这里只读 doc.bindings，而导入时绑定已被迁移成节点 —— 于是画布上柱子全平，
  // 但导出到手机又是对的（用户实测：编辑器看不到效果、真机正常）。
  let list = (state.doc.interactions.bindings || []);
  if (typeof compileStudioGraph === 'function') {
    const nodes = (state.doc.interactions.studio && state.doc.interactions.studio.nodes) || [];
    if (nodes.some(n => n && n.type === 'bind')) {
      const compiled = compileStudioGraph();
      list = (compiled.model.bindings || []);
    }
  }
  for (const b of list) {
    if (!b || b.enabled === false) continue;

    // 表达式绑定：直接算公式（scope 用预览值表，和线性绑定共用同一套模拟数据）
    if (b.expr) {
      let value = null;
      try {
        if (typeof evalStudioExpr === 'function') {
          const scope = {};
          for (const name of Object.keys(PREVIEW_VARS)) {
            const v = previewVar(name);
            if (typeof v === 'number') scope[name] = v;
          }
          scope['sys.mem.usedPct'] = Number((state.sim || {}).mem ?? 60);
          scope['sys.storage.usedPct'] = Number((state.sim || {}).storage ?? 50);
          value = Number(evalStudioExpr(b.expr, scope));
        }
      } catch {
        value = null;
      }
      if (value === null || !Number.isFinite(value)) continue;
      (out[b.targetId] = out[b.targetId] || {})[b.property] = value;
      continue;
    }

    const inA = Array.isArray(b.in) ? b.in : [Number(b.inMin ?? 0), Number(b.inMax ?? 100)];
    const outA = Array.isArray(b.out) ? b.out : [Number(b.outMin ?? 0), Number(b.outMax ?? 100)];
    const raw = simValueOf(b.source);
    const span = inA[1] - inA[0];
    // 输入退化时取 outA[0]，和引擎一致，避免除零
    const t = span === 0 ? 0 : Math.max(0, Math.min(1, (raw - inA[0]) / span));
    const value = outA[0] + (outA[1] - outA[0]) * t;
    if (!out[b.targetId]) out[b.targetId] = {};
    out[b.targetId][b.property] = value;
  }
  return out;
}

function currentOverrides() {
  const merged = bindingOverrides();
  const rect = selectedRect();
  const clip = clipFor(rect);
  if (!clip) return merged;

  const values = {};
  const dur = clip.tracks[0]?.durationMs || 1000;
  for (const track of clip.tracks) {
    if (!track.keyframes.length) continue;
    const phase = (state.playhead * dur) / Math.max(1, track.durationMs);
    const v = sampleTrack(track, loopedTime(phase, track.loop));
    if (v !== null) values[track.property] = v;
  }
  // 动画优先于绑定（和引擎的合并顺序一致）
  merged[clip.targetId] = Object.assign({}, values, merged[clip.targetId] || {});
  return merged;
}

/**
 * 每帧/每次命中测试只算一遍覆盖值。
 *
 * 为什么要缓存：currentOverrides() 内部会 compileStudioGraph()，节点有几十个时，
 * 每帧每节点各调一次等于每帧编译几十遍工作台——既卡，又会让同帧内不同元素读到不同的值。
 */
let overridesCache = null;
function overridesCached() {
  if (!overridesCache) overridesCache = currentOverrides();
  return overridesCache;
}
function invalidateOverrides() { overridesCache = null; }

function overridesFor(rect, all) {
  return (all && all[rect.id]) || {};
}

function rectTransform(rect, overrides) {
  const o = overrides || {};
  const scale = o.scale !== undefined ? o.scale : (rect.scale === undefined ? 1 : rect.scale);
  const rotation = o.rotationZ !== undefined ? o.rotationZ : (rect.rotationZ || 0);
  const x = o.x !== undefined ? o.x : rect.x;
  const y = o.y !== undefined ? o.y : rect.y;
  const opacity = o.opacity !== undefined ? o.opacity : (rect.opacity === undefined ? 1 : rect.opacity);
  // 宽高覆盖：盒子和缩放相乘，与引擎端 nodeMatrix 的口径一致
  const w = (o.width !== undefined ? o.width : rect.width) * scale;
  const h = (o.height !== undefined ? o.height : rect.height) * scale;
  return { x, y, w, h, rotation, opacity };
}

function cornersOf(t) {
  const cx = t.x + t.w / 2, cy = t.y + t.h / 2;
  const rad = t.rotation * Math.PI / 180, cos = Math.cos(rad), sin = Math.sin(rad);
  const hw = t.w / 2, hh = t.h / 2;
  return [[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]].map(([dx, dy]) => {
    const ux = cx + dx * cos - dy * sin, uy = cy + dx * sin + dy * cos;
    return { x: worldToPx(ux), y: worldToPx(uy), ux, uy };
  });
}

/**
 * 每个节点绘制时的累计变换矩阵（id → DOMMatrix）。
 *
 * 为什么记它：容器可以嵌套，命中测试和拖动换算都需要把屏幕坐标反算回元素局部坐标，
 * 而 canvas 的变换栈不对外暴露。绘制时顺手存下来是最省事也最不容易出错的做法。
 */
const nodeMatrices = new Map();

/**
 * 用矩阵变换一个点。
 *
 * 为什么要兼容写法：浏览器都有 DOMPoint，但无头自检环境没有；
 * 手算分支同时让这段逻辑可以被单测覆盖。
 */
function mapPoint(matrix, x, y) {
  if (!matrix) return { x, y };
  if (typeof DOMPoint !== 'undefined') return new DOMPoint(x, y).matrixTransform(matrix);
  return {
    x: matrix.a * x + matrix.c * y + matrix.e,
    y: matrix.b * x + matrix.d * y + matrix.f
  };
}

function draw() {
  invalidateOverrides();
  const W = canvas.width, H = canvas.height;
  const bg = parseColor(state.doc.scene.root.background);

  // 帧级 save/restore：任何节点漏掉 restore（裁剪/变换泄漏）都不会污染下一帧。
  // 这类 bug 表现为"拖某个元素后整块画布错乱"，靠肉眼看堆栈很难定位，
  // 加这一层兜底比事后排查便宜得多。
  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, W, H);
  ctx.fillStyle = `rgba(${bg.r},${bg.g},${bg.b},1)`;
  ctx.fillRect(0, 0, W, H);

  nodeMatrices.clear();
  for (const node of rects()) drawNode(node);

  const rect = selectedRect();
  if (rect) {
    // 选中框直接套用**节点绘制时的矩阵**，再描自己的 0..w/0..h。
    // 用角点坐标反算容易差半个身位（绘制时的平移/缩放链很长），
    // 复用同一个矩阵才能保证框和像素严格重合。
    const m = nodeMatrices.get(rect.id);
    if (m) {
      const boxW = worldToPx(rect.width);
      const boxH = worldToPx(rect.height);
      const scaleFactor = Math.max(0.001, Math.hypot(m.a, m.b));

      ctx.save();
      ctx.setTransform(m.a, m.b, m.c, m.d, m.e, m.f);
      ctx.strokeStyle = '#ffdb4d';
      ctx.lineWidth = Math.max(1.6 * canvasPixelRatio(), W / 600) / scaleFactor;
      ctx.strokeRect(0, 0, boxW, boxH);

      // 手柄按"屏幕上 10px"来画：乘超采样比，否则高分辨率位图里会缩成小点
      const handle = (10 * canvasPixelRatio()) / scaleFactor;
      ctx.fillStyle = '#ffdb4d';
      [[0, 0], [boxW, 0], [boxW, boxH], [0, boxH]].forEach(([hx, hy]) => {
        ctx.fillRect(hx - handle / 2, hy - handle / 2, handle, handle);
      });
      ctx.restore();

      // 闪烁提示框：工作台里点图层库时，先让它在预览上"亮一下"，
      // 用同一套矩阵画，才能和图层像素严格对齐。
      const flash = state.flashLayer;
      if (flash && flash.id === rect.id && Date.now() < flash.until) {
        const pulse = Math.abs(Math.sin((Date.now() - flash.start) / 110));
        ctx.save();
        ctx.setTransform(m.a, m.b, m.c, m.d, m.e, m.f);
        ctx.strokeStyle = 'rgba(120, 230, 255, ' + (0.45 + 0.55 * pulse).toFixed(3) + ')';
        ctx.lineWidth = (2.4 + 1.6 * pulse) * canvasPixelRatio() / scaleFactor;
        const pad = (5 * canvasPixelRatio()) / scaleFactor;
        ctx.strokeRect(-pad, -pad, boxW + pad * 2, boxH + pad * 2);
        ctx.restore();
      }

      // 蒙版轮廓用**蓝色**画：它现在是图层内的独立对象（有自己的位置/尺寸），
      // 黄色是图层边界，蓝色是蒙版边界，两者不再重合。
      if (rect.mask) {
        const maskPts = effectivePointsOf(rect, boxW, boxH);
        if (maskPts && maskPts.length >= 3) {
          ctx.save();
          ctx.setTransform(m.a, m.b, m.c, m.d, m.e, m.f);
          ctx.strokeStyle = "#4da3ff";
          ctx.lineWidth = Math.max(1.4 * canvasPixelRatio(), W / 700) / scaleFactor;
          ctx.setLineDash([Math.max(4 * canvasPixelRatio(), W / 200) / scaleFactor,
                           Math.max(3 * canvasPixelRatio(), W / 300) / scaleFactor]);
          ctx.beginPath();
          maskPts.forEach(([mx, my], i) => (i ? ctx.lineTo(mx, my) : ctx.moveTo(mx, my)));
          ctx.closePath();
          ctx.stroke();
                    // 拖动按钮：蓝色实心圆点，按住即可移动蒙版
          const cxm = maskPts.reduce((s, p) => s + p[0], 0) / maskPts.length;
          const cym = maskPts.reduce((s, p) => s + p[1], 0) / maskPts.length;
          ctx.setLineDash([]);
          ctx.fillStyle = "#4da3ff";
          // 蒙版中心点：屏幕上 8px 半径，外加一圈浅色描边，方便看清也方便点
          const dot = (8 * canvasPixelRatio()) / scaleFactor;
          ctx.beginPath();
          ctx.arc(cxm, cym, dot, 0, Math.PI * 2);
          ctx.fill();
          ctx.strokeStyle = '#cfe6ff';
          ctx.lineWidth = Math.max(1.4 * canvasPixelRatio(), W / 700) / scaleFactor;
          ctx.stroke();
          ctx.restore();
        }
      }
    } else {
      const pts = cornersOf(rectTransform(rect, overridesFor(rect, overridesCached())));
      ctx.save();
      ctx.strokeStyle = '#ffdb4d';
      ctx.lineWidth = Math.max(1.6 * canvasPixelRatio(), W / 600);
      ctx.beginPath();
      pts.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
      ctx.closePath();
      ctx.stroke();
      const r = (10 * canvasPixelRatio());
      ctx.fillStyle = '#ffdb4d';
      for (const p of pts) ctx.fillRect(p.x - r / 2, p.y - r / 2, r, r);
      ctx.restore();
    }
  }

  // 与 draw() 开头的 ctx.save() 配对
  ctx.restore();
}

/** 节点四角在画布上的像素坐标（用绘制时记录的累计矩阵）。 */
function nodeCornersPx(node) {
  const m = node ? nodeMatrices.get(node.id) : null;
  if (!m) return null;
  const ovN = overridesCached()[node.id] || {};
  const wPx = worldToPx(ovN.width !== undefined ? ovN.width : node.width);
  const hPx = worldToPx(ovN.height !== undefined ? ovN.height : node.height);
  return [[0, 0], [wPx, 0], [wPx, hPx], [0, hPx]].map(([x, y]) => {
    const p = mapPoint(m, x, y);
    return { x: p.x, y: p.y };
  });
}

/**
 * 递归绘制一个节点。
 *
 * 坐标系约定：进入节点后，**原点就是它的左上角，单位是像素（未缩放）**，
 * 缩放/旋转已经由 canvas 变换栈承担。这样子节点只要按自己的 x/y 画，
 * 就自动继承了所有祖先的变换——和 HTML 里 div 套 div 的行为一致。
 */
// ================= 视差全局参数 =================
// 存在 manifest.parallax 里：manifest 是自由字段包，引擎只读已知 key，
// 所以这些"编辑器手感参数"能跟着预设一起走，不影响手机端渲染。
// 视差全局默认值。depth = 跟随者的统一景深。
const PARALLAX_DEFAULTS = { enabled: true, depth: 40, amp: 4.5, smoothing: 0.18, deadzone: 0.04 };

function parallaxSettings() {
  const saved = (state.doc.manifest && state.doc.manifest.parallax) || {};
  const num = (key) => (Number.isFinite(saved[key]) ? saved[key] : PARALLAX_DEFAULTS[key]);
  return {
    enabled: saved.enabled === undefined ? PARALLAX_DEFAULTS.enabled : !!saved.enabled,
    depth: num('depth'),
    amp: num('amp'),
    smoothing: num('smoothing'),
    deadzone: num('deadzone')
  };
}

function setParallaxSetting(key, value) {
  state.doc.manifest.parallax = Object.assign({}, parallaxSettings(), { [key]: value });
}

/**
 * 计算某个图层本帧的视差参数。
 *
 * 抽成函数是为了让"谁听谁的"只有一处定义：
 *   - 跟随全局：enabled/depth/amp 全取全局，方向恒 0（同向）
 *   - 独立：全部取本层自己的值，包括自己的启用开关
 */
/**
 * 计算某图层本帧的视差参数。
 *
 * 规则（v3，面板只有一组滑块，避免重复层级）：
 *   - 未勾选「独立」：完全跟随全局（开关、景深、振幅、方向同向）
 *   - 勾选「独立」：用本层的景深/振幅/方向；本层要"不动"就把景深或振幅调 0
 */
function layerParallax(node) {
  const g = parallaxSettings();
  if (node.parallaxMode === 'custom') {
    return {
      enabled: node.parallaxEnabled !== false,
      depth: Number(node.parallax) || 0,
      amp: Number.isFinite(node.parallaxAmp) ? node.parallaxAmp : g.amp,
      angle: Number(node.parallaxAngle) || 0,
      source: 'custom'
    };
  }
  return { enabled: g.enabled, depth: g.depth, amp: g.amp, angle: 0, source: 'global' };
}

// ================= 视差预览 =================
// 鼠标 → 倾斜目标是"瞬时"的，直接用会抖；这里做指数平滑逼近，
// 手感接近弹簧阻尼，且只在数值真的变化时才重绘（静止时零开销）。
let parallaxRaf = 0;
function startParallaxLoop() {
  if (parallaxRaf) return;
  const step = () => {
    const p = state.parallax;
    // 平滑系数与死区来自 manifest.parallax（属性面板可调）：
    // 系数越大越跟手；死区用来忽略手部轻微抖动，同时减少无意义重绘。
    const cfg = parallaxSettings();
    const rx = Math.abs(p.targetX) < cfg.deadzone ? 0 : p.targetX;
    const ry = Math.abs(p.targetY) < cfg.deadzone ? 0 : p.targetY;
    const nx = p.tx + (rx - p.tx) * cfg.smoothing;
    const ny = p.ty + (ry - p.ty) * cfg.smoothing;
    const settled = Math.abs(nx - p.tx) < 0.0005 && Math.abs(ny - p.ty) < 0.0005;
    if (settled) {
      p.tx = Math.abs(p.targetX) < cfg.deadzone ? 0 : p.targetX;
      p.ty = Math.abs(p.targetY) < cfg.deadzone ? 0 : p.targetY;
      parallaxRaf = 0;
      scheduleDraw();
      return;
    }
    p.tx = nx;
    p.ty = ny;
    scheduleDraw();
    parallaxRaf = requestAnimationFrame(step);
  };
  parallaxRaf = requestAnimationFrame(step);
}

canvas.addEventListener('pointerleave', () => {
  // 离开画布就回中：和 Wallpaper Engine 一样，鼠标移开背景回到静止姿态
  state.parallax.targetX = 0;
  state.parallax.targetY = 0;
  startParallaxLoop();
});

function drawNode(node) {
  // 与引擎一致：只有显式 false 才算隐藏。手写/纯 JSON 预设常常不写 visible，
  // 这里若按 truthy 判断会把整份预设画成空白（用户实测：手机正常、编辑器全黑）
  if (node.visible === false) return;

  const ov = overridesFor(node, overridesCached());
  const x = ov.x !== undefined ? ov.x : node.x;
  const y = ov.y !== undefined ? ov.y : node.y;
  const scale = ov.scale !== undefined ? ov.scale : (node.scale === undefined ? 1 : node.scale);
  const rotation = ov.rotationZ !== undefined ? ov.rotationZ : (node.rotationZ || 0);
  const opacity = ov.opacity !== undefined ? ov.opacity : (node.opacity === undefined ? 1 : node.opacity);

  const ovN = overridesCached()[node.id] || {};
  const wPx = worldToPx(ovN.width !== undefined ? ovN.width : node.width);
  const hPx = worldToPx(ovN.height !== undefined ? ovN.height : node.height);

  ctx.save();
  // 羽化半径（像素）：按"占元素宽度的比例"换算，与引擎端同一套语义。
  // 真正应用在叶子节点绘制时（drawWithAlphaFeather）——不能用 ctx.filter 整体模糊，
  // 那会把颜色一起糊掉，得到的是"模糊滤镜"而不是 PS 的羽化。
  const feather = Number(node.feather) || 0;
  // 羽化 = 占**元素宽度**的百分比：与元素尺寸、画布尺寸都无关，
  // 所以编辑器和手机看到的比例必然一致（这是"像素"语义做不到的）。
  const featherPx = feather > 0 ? (feather / 100) * wPx : 0;
  ctx.translate(worldToPx(x), worldToPx(y));
  // 视差位移：depth 0..100 → 相对振幅。远层 depth 小、近层 depth 大。
  // 放在 translate(x,y) 之后 = 纯屏幕空间平移，不受缩放/旋转影响。
{
    const lp = layerParallax(node);
    if (lp.enabled && lp.depth > 0) {
      const k = (lp.amp * lp.depth) / 100;
      const rad = (lp.angle * Math.PI) / 180;
      const cosA = Math.cos(rad);
      const sinA = Math.sin(rad);
      const ox = (state.parallax.tx * cosA - state.parallax.ty * sinA) * k;
      const oy = (state.parallax.tx * sinA + state.parallax.ty * cosA) * k;
      ctx.translate(worldToPx(ox), worldToPx(oy));
    }
  }
  ctx.translate((wPx * scale) / 2, (hPx * scale) / 2);
  ctx.rotate((rotation * Math.PI) / 180);
  ctx.scale(scale, scale);
  ctx.translate(-wPx / 2, -hPx / 2);

  nodeMatrices.set(node.id, ctx.getTransform());

  // 蒙版必须被图层框裁住：蒙版可以被拖到框外（maskX/maskY 非零），
  // 但内容不能跟着溢出——手机端纹理就是图层框大小，物理上画不出框外，
  // 编辑器不加这个裁剪就会出现"蒙版跑到框外还看得见"。
  if (!isContainer(node) && node.mask) {
    ctx.beginPath();
    ctx.rect(0, 0, wPx, hPx);
    ctx.clip();
  }

  if (isContainer(node)) {
    const bg = parseColor(node.background || '#00000000');
    if (bg.a > 0) {
      ctx.globalAlpha = clamp(opacity, 0, 1) * bg.a;
      ctx.fillStyle = `rgb(${bg.r},${bg.g},${bg.b})`;
      ctx.fillRect(0, 0, wPx, hPx);
    }
    // 裁剪：子元素超出容器边界就消失（等价于 CSS 的 overflow: hidden）。
    // clip === false 时可以关掉，适合发光/阴影这类故意要溢出的效果。
    const shouldClip = node.clip !== false;
    if (shouldClip) {
      ctx.beginPath();
      ctx.rect(0, 0, wPx, hPx);
      ctx.clip();
    }
    for (const child of node.children || []) drawNode(child);
  } else if (isText(node)) {
    // 文字与图片/形状走同一套：轮廓裁剪 + 沿轮廓羽化
    const textMaskImg = node.maskSrc ? imageOf({ src: node.maskSrc }) : null;
    const textClip = (node.mask && node.mask !== '') || (Number(node.cornerRadius) || 0) > 0;
    const textOutline = effectivePointsOf(node, wPx, hPx);

    if (featherPx > 0) {
      // 羽化要读回像素按到轮廓的距离削减——直接复用图片那套算法
      drawWithAlphaFeather(wPx, hPx, featherPx, c => {
        drawTextLocal(node, opacity, wPx, hPx, c);
        if (textClip) {
          c.globalCompositeOperation = 'destination-in';
          c.beginPath();
          textOutline.forEach(([cx, cy], i) => (i ? c.lineTo(cx, cy) : c.moveTo(cx, cy)));
          c.closePath();
          c.fill();
          c.globalCompositeOperation = 'source-over';
        }
        if (textMaskImg) {
          c.globalCompositeOperation = 'destination-in';
          c.drawImage(textMaskImg, 0, 0, Math.round(wPx), Math.round(hPx));
          c.globalCompositeOperation = 'source-over';
        }
      }, textOutline);
    } else if (textClip || textMaskImg) {
      ctx.save();
      if (textClip) {
        ctx.beginPath();
        textOutline.forEach(([cx, cy], i) => (i ? ctx.lineTo(cx, cy) : ctx.moveTo(cx, cy)));
        ctx.closePath();
        ctx.clip();
      }
      drawTextLocal(node, opacity, wPx, hPx);
      ctx.restore();
    } else {
      drawTextLocal(node, opacity, wPx, hPx);
    }
  } else if (isImage(node)) {
    const img = imageOf(node);
    ctx.globalAlpha = clamp(opacity, 0, 1);
    if (img) {
      // 蒙版 / 圆角：先按轮廓裁剪，再绘制（羽化在裁剪内做）
      const needsClip = (node.mask && node.mask !== '') || (Number(node.cornerRadius) || 0) > 0;
      if (needsClip) {
        const pts = effectivePointsOf(node, wPx, hPx);
        ctx.save();
        ctx.beginPath();
        pts.forEach(([cx, cy], i) => (i ? ctx.lineTo(cx, cy) : ctx.moveTo(cx, cy)));
        ctx.closePath();
        ctx.clip();
      }
      const maskImg = node.maskSrc ? imageOf({ src: node.maskSrc }) : null;
      if (featherPx > 0) {
        // PS 式羽化：内容画进离屏，再按边缘渐变削 alpha
        drawWithAlphaFeather(wPx, hPx, featherPx, c => c.drawImage(img, 0, 0, wPx, hPx), effectivePointsOf(node, wPx, hPx));
      } else if (maskImg) {
        // 图片蒙版：用蒙版图的 alpha 裁剪
        drawWithImageMask(wPx, hPx, maskImg, c => c.drawImage(img, 0, 0, wPx, hPx));
      } else {
        ctx.drawImage(img, 0, 0, wPx, hPx);
      }
      // 必须和上面的 ctx.save() 配对：不 restore 的话裁剪会泄漏到后续所有节点，
      // 表现就是"拖动容器后整个画布错乱"（这张图之前就踩了这个坑）。
      if (needsClip) ctx.restore();
    } else {
      // 资源缺失时画个占位框，避免“看不见也不知道为什么”
      ctx.strokeStyle = '#ff6b6b';
      ctx.lineWidth = 2;
      ctx.strokeRect(0, 0, wPx, hPx);
    }
  } else {
    const c = parseColor(node.color || '#FFFFFFFF');
    const gradient = gradientFillFor(node, wPx, hPx);
    const paintShape = target => {
      const prevCtx = ctx;
      // 允许把绘制重定向到离屏画布（羽化时用），否则就画在主画布上
      const use = target || ctx;
      use.globalAlpha = prevCtx === use ? clamp(opacity, 0, 1) * (gradient ? 1 : c.a) : 1;
      use.fillStyle = gradient || `rgb(${c.r},${c.g},${c.b})`;
      if (node.shape === 'path') {
        use.save();
        use.scale(wPx / Math.max(1, viewBoxSize(node, 'w')), hPx / Math.max(1, viewBoxSize(node, 'h')));
        if (node.strokeWidth > 0) {
          use.strokeStyle = use.fillStyle;
          use.lineWidth = node.strokeWidth;
          use.lineCap = 'round';
          use.lineJoin = 'round';
          use.stroke(new Path2D(node.path));
        } else {
          use.fill(new Path2D(node.path), 'evenodd');
        }
        use.restore();
        return;
      }
      // 必须走 effectivePointsOf（蒙版 + 圆角），否则纯色形状会忽略蒙版
      const points = effectivePointsOf(node, wPx, hPx);
      use.beginPath();
      points.forEach(([px, py], i) => (i ? use.lineTo(px, py) : use.moveTo(px, py)));
      use.closePath();
      use.fill();
    };

    if (featherPx > 0) {
      // 形状羽化同样只柔化边缘 alpha
      drawWithAlphaFeather(wPx, hPx, featherPx, offCtx => paintShape(offCtx), effectivePointsOf(node, wPx, hPx));
    } else {
      paintShape(null);
    }
  }

  ctx.restore();
}

/** 文本在当前（已缩放的）局部坐标里绘制。 */

// ================= 模板预览 =================
// 画布上直接显示解析结果（"60" 而不是 "{sys.mem.usedPct:00}"），
// 规则与引擎的 scene/TextTemplate.kt 保持一致：
//   {变量}        直接输出（数字整数化）
//   {变量:00}     整数补零到 2 位
//   {变量:0.0}    保留 1 位小数
//   {df(HH:mm)}   日期格式化
//   {if(条件,真,假)}
// 未识别的占位符**原样保留**：这样写错变量名时画布上能立刻看出来，
// 而不是被悄悄替换成空字符串（那是更难查的错）。
const PREVIEW_TEXT_VARS = {
  'sys.media.title': '夜に駆ける',
  'sys.media.artist': 'YOASOBI',
  'sys.media.album': 'THE BOOK',
  'sys.media.app': '网易云音乐',
  'sys.media.lyric': '沈むように溶けてゆくように',
  'sys.media.lyricNext': '二人だけの空が広がる夜に',
  'sys.media.lyricTrans': '每当夜幕降临，只有我们两人的天空',
  'sys.weather.text': '多云',
  'sys.weather.city': '清远市',
  'sys.weather.district': '清城区'
};

const PREVIEW_VARS = {
  'sys.time.hour': () => new Date().getHours(),
  'sys.time.minute': () => new Date().getMinutes(),
  'sys.battery.level': 82,
  'sys.battery.charging': 1,
  'sys.battery.temp': 33.5,
  'sys.power.connected': 1,
  'sys.screen.on': 1,
  'sys.net.connected': 1,
  'sys.mem.usedPct': 60,
  'sys.mem.freeMb': 2900,
  'sys.storage.usedPct': 48,
  'sys.storage.freeMb': 11800,
  'sys.render.fps': 60,
  'sys.notify.count': 3,
  'sys.media.playing': 1,
  'sys.media.position': 48,
  'sys.media.duration': 259,
  'sys.media.progress': 18.5,
  // 音频可视化样例值：刻意做成"低频高、高频低"的走势，和真实频谱接近
  'sys.audio.level': 0.62,
  'sys.audio.bass': 0.78,
  'sys.audio.mid': 0.45,
  'sys.audio.treble': 0.22,
  'sys.audio.beat': 0.35,
  'sys.weather.temp': 24,
  'sys.weather.feelsLike': 25.5,
  'sys.weather.humidity': 62,
  'sys.weather.code': 2,
  'sys.weather.wind': 6,
  'sys.weather.precip': 0,
  'sys.weather.isDay': 1,
  'sys.weather.updated': 3,
  'sys.audio.b1': 0.82, 'sys.audio.b2': 0.74, 'sys.audio.b3': 0.58, 'sys.audio.b4': 0.46,
  'sys.audio.b5': 0.34, 'sys.audio.b6': 0.26, 'sys.audio.b7': 0.18, 'sys.audio.b8': 0.12,
  'step.today': 1234
};

function previewVar(name) {
  const v = PREVIEW_VARS[name];
  if (v === undefined) return undefined;
  return typeof v === 'function' ? v() : v;
}

/** 数字格式化，与引擎 formatValue 一致。 */
function formatTemplateValue(value, format) {
  if (!format) {
    return Number.isInteger(value) ? String(value) : value.toFixed(1);
  }
  if (format.includes('.')) {
    const decimals = Math.min(3, format.substringAfterDot().length);
    return value.toFixed(decimals);
  }
  return String(Math.trunc(value)).padStart(format.length, '0');
}

// 小工具：避免额外依赖
if (!String.prototype.substringAfterDot) {
  // eslint-disable-next-line no-extend-native
  String.prototype.substringAfterDot = function () {
    const i = this.indexOf('.');
    return i < 0 ? '' : this.slice(i + 1);
  };
}

/** 按顶层逗号切分（跳过引号与括号内部）。 */
function splitTemplateArgs(text) {
  const out = [];
  let depth = 0;
  let quote = null;
  let buf = '';
  for (const ch of text) {
    if (quote) {
      buf += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") { quote = ch; buf += ch; continue; }
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) { out.push(buf.trim()); buf = ''; continue; }
    buf += ch;
  }
  if (buf.trim()) out.push(buf.trim());
  return out;
}

function unquoteTemplate(value) {
  const t = String(value).trim();
  if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) {
    return t.slice(1, -1);
  }
  return t;
}

/** 条件求值：支持 > < >= <= == != = and or not 与裸变量。 */
function evalTemplateCondition(expr) {
  const text = String(expr).trim();
  if (!text) return false;
  if (/\s+or\s+/i.test(text)) return text.split(/\s+or\s+/i).some(evalTemplateCondition);
  if (/\s+and\s+/i.test(text)) return text.split(/\s+and\s+/i).every(evalTemplateCondition);
  if (/^not\s+/i.test(text)) return !evalTemplateCondition(text.replace(/^not\s+/i, ''));

  const m = text.match(/^(.+?)\s*(>=|<=|==|!=|=|>|<)\s*(.+)$/);
  const operand = raw => {
    const t = raw.trim();
    if (/^[-+]?[\d.]+$/.test(t)) return Number(t);
    if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) return t.slice(1, -1);
    const v = previewVar(t);
    if (v !== undefined) return v;
    if (PREVIEW_TEXT_VARS[t] !== undefined) return PREVIEW_TEXT_VARS[t];
    return 0;
  };
  if (!m) return !!operand(text);
  const left = operand(m[1]);
  const right = operand(m[3]);
  switch (m[2]) {
    case '>': return left > right;
    case '<': return left < right;
    case '>=': return left >= right;
    case '<=': return left <= right;
    case '!=': return left !== right;
    default: return left === right;
  }
}

/** 展开一遍 {…}。返回 { text, changed }。 */
function renderTemplateOnce(input) {
  let out = '';
  let changed = false;
  let i = 0;
  while (i < input.length) {
    if (input[i] !== '{') { out += input[i++]; continue; }
    // 找配对的 }（要跳过嵌套括号，if(...) 里还有逗号与括号）
    let depth = 0;
    let j = i;
    for (; j < input.length; j++) {
      if (input[j] === '(') depth++;
      else if (input[j] === ')') depth--;
      else if (input[j] === '}' && depth <= 0) break;
    }
    if (j >= input.length) { out += input.slice(i); break; }
    const inner = input.slice(i + 1, j);
    out += evalTemplateExpr(inner);
    changed = true;
    i = j + 1;
  }
  return { text: out, changed };
}

function evalTemplateExpr(inner) {
  const text = String(inner).trim();
  if (!text) return '';

  // 函数调用：df(…) / if(…)
  const fn = text.match(/^([a-zA-Z_][\w]*)\s*\(([\s\S]*)\)$/);
  if (fn) {
    const name = fn[1].toLowerCase();
    const args = fn[2];
    if (name === 'df') {
      const pattern = args.trim() || 'HH:mm';
      return formatPreviewDate(pattern);
    }
    if (name === 'if') {
      const parts = splitTemplateArgs(args);
      if (parts.length < 3) return '{' + inner + '}';
      return unquoteTemplate(evalTemplateCondition(parts[0]) ? parts[1] : parts[2]);
    }
    return '{' + inner + '}';
  }

  // 变量：name 或 name:fmt
  const colon = text.indexOf(':');
  const name = (colon >= 0 ? text.slice(0, colon) : text).trim();
  const format = colon >= 0 ? text.slice(colon + 1) : '';

  const numerical = previewVar(name);
  if (numerical !== undefined) return formatTemplateValue(Number(numerical), format);
  if (PREVIEW_TEXT_VARS[name] !== undefined) return PREVIEW_TEXT_VARS[name];

  // 未知变量：原样保留，方便发现写错的名字
  return '{' + inner + '}';
}

/** 简单的日期格式化：支持 HH mm ss yyyy MM dd。 */
function formatPreviewDate(pattern) {
  const d = new Date();
  const pad = n => String(n).padStart(2, '0');
  return pattern
    .replace(/yyyy/g, String(d.getFullYear()))
    .replace(/MM/g, pad(d.getMonth() + 1))
    .replace(/dd/g, pad(d.getDate()))
    .replace(/HH/g, pad(d.getHours()))
    .replace(/mm/g, pad(d.getMinutes()))
    .replace(/ss/g, pad(d.getSeconds()));
}

/** 递归展开（最多 6 遍，和引擎的 MAX_PASSES 一致），防止自引用死循环。 */
function renderTemplate(input) {
  let text = String(input == null ? '' : input);
  for (let pass = 0; pass < 6; pass++) {
    const r = renderTemplateOnce(text);
    text = r.text;
    if (!r.changed) break;
    if (!text.includes('{')) break;
  }
  return text;
}

function drawTextLocal(node, opacity, wPx, hPx, target) {
  // target 允许把文字画到离屏画布（羽化/蒙版需要），默认画在主画布
  const use = target || ctx;
  const color = parseColor(node.color || '#FFFFFFFF');
  const fontPx = (node.fontSize / 100) * canvas.width;
  // 与 drawTextNode 一致：画布显示解析后的值（{sys.x:00} → 60），输入框保留原始模板
  const lines = renderTemplate(node.text || '').split('\n');
  const lineH = fontPx * TEXT_LINE_HEIGHT;
  const totalH = lineH * lines.length;

  use.globalAlpha = clamp(opacity, 0, 1) * color.a;
  use.fillStyle = `rgb(${color.r},${color.g},${color.b})`;
  use.font = `${fontPx}px system-ui, "Microsoft YaHei", sans-serif`;
  use.textBaseline = 'top';

  lines.forEach((line, i) => {
    const w = use.measureText(line).width;
    let px = 0;
    if (node.align === 'center') px = (wPx - w) / 2;
    else if (node.align === 'right') px = wPx - w;
    use.fillText(line, px, (hPx - totalH) / 2 + i * lineH);
  });
}

/**
 * 蒙版中心在画布上的像素位置（用于蓝色拖动按钮的命中测试）。
 *
 * 蒙版坐标语义：maskW/H 是占图层宽高的百分比，maskX/Y 是**相对容器中心**的偏移。
 */
function maskCenterOnCanvas(node) {
  if (!node || !node.mask) return null;
  const m = nodeMatrices.get(node.id);
  if (!m) return null;

  const ovN = overridesCached()[node.id] || {};
  const wPx = worldToPx(ovN.width !== undefined ? ovN.width : node.width);
  const hPx = worldToPx(ovN.height !== undefined ? ovN.height : node.height);
  const mw = ((Number(node.maskW) || 100) / 100) * wPx;
  const mh = ((Number(node.maskH) || 100) / 100) * hPx;
  const left = (wPx - mw) / 2 + ((Number(node.maskX) || 0) / 100) * wPx;
  const top = (hPx - mh) / 2 + ((Number(node.maskY) || 0) / 100) * hPx;

  return mapPoint(m, left + mw / 2, top + mh / 2);
}
/** 屏幕坐标是否落在节点内（用它的累计矩阵反算）。 */
function insideNode(node, px, py) {
  const m = nodeMatrices.get(node.id);
  if (!m) return false;
  const p = mapPoint(m.inverse(), px, py);
  const ovN = overridesCached()[node.id] || {};
  const wPx = worldToPx(ovN.width !== undefined ? ovN.width : node.width);
  const hPx = worldToPx(ovN.height !== undefined ? ovN.height : node.height);
  return p.x >= 0 && p.y >= 0 && p.x <= wPx && p.y <= hPx;
}

function hitRect(px, py) {
  invalidateOverrides();
  let hit = null;

  // 后画的在上层：同层倒序；容器先问子节点，再问自己
  const test = node => {
    // 点击穿透：该节点自身不命中，但子节点照常可点（与引擎 hitThrough 语义一致）
    if (node.hitThrough && !isContainer(node) && insideNode(node, px, py)) {
      // 叶子穿透节点：让位给下层，不设 hit
      return false;
    }
    if (node.visible === false) return false;
    if (isContainer(node)) {
      const children = node.children || [];
      for (let i = children.length - 1; i >= 0; i--) {
        if (test(children[i])) return true;
      }
    }
    if (insideNode(node, px, py)) {
      hit = node;
      return true;
    }
    return false;
  };

  const top = rects();
  for (let i = top.length - 1; i >= 0; i--) {
    if (test(top[i])) break;
  }
  return hit;
}

/**
 * 把屏幕位移换算到父容器的局部坐标系（含缩放与旋转）。
 *
 * 为什么必须换算：容器缩放 2 倍时，鼠标移动 10px，子元素在容器内只该移动 5 单位；
 * 不减这一层，拖动手感会变成"鼠标跑得比元素快"。
 */
function toLocalDelta(node, dxPx, dyPx) {
  const parent = parentOf(node ? node.id : null);
  const m = parent ? nodeMatrices.get(parent.id) : null;
  if (!m) return { dx: dxPx, dy: dyPx };
  const inv = m.inverse();
  const a = mapPoint(inv, 0, 0);
  const b = mapPoint(inv, dxPx, dyPx);
  return { dx: b.x - a.x, dy: b.y - a.y };
}
/** 父容器的累计缩放，拖动时用来把屏幕位移换算成容器内的单位。 */
function parentScaleOf(node) {
  const parent = parentOf(node.id);
  if (!parent) return 1;
  const m = nodeMatrices.get(parent.id);
  if (!m) return 1;
  return Math.hypot(m.a, m.b) || 1;
}

function hitHandle(px, py) {
  const rect = selectedRect();
  if (!rect) return -1;
  const pts = nodeCornersPx(rect) || cornersOf(rectTransform(rect, overridesFor(rect, overridesCached())));
  // 命中半径按屏幕 16px 算：手柄视觉上是 10px，再留 6px 容错，
  // 手指/触控板都不至于必须精准点在方块正中。
  // 不要写成 Math.max(…, canvas.width/90)：2.6 倍超采样下那只剩 10 屏幕像素。
  const r = 16 * canvasPixelRatio();
  let best = -1;
  let bestDist = Infinity;
  for (let i = 0; i < pts.length; i++) {
    const d = Math.hypot(px - pts[i].x, py - pts[i].y);
    if (d <= r && d < bestDist) { best = i; bestDist = d; }
  }
  return best;
}

// ---------------------------------------------------------------- 指针

let drawPending = false;
// ================= 本地持久化 =================
// 为什么要存：编辑器状态只在内存里，一次误刷新就全没了（已经真实发生过）。
// 存在 localStorage：Document 级别的草稿，和「导出文件」互补。
// 资源（图片 data URL）可能很大，超配额就只存结构，不阻塞编辑。
const DOC_KEY = 'lwpkit.doc.v1';
let persistTimer = 0;

/**
 * 给没有 name 的动画组补上「动画组 N」。
 *
 * 早期数据只有 id（anim_glow_pulse 之类），界面上按位置编号会随过滤变化，
 * 所以进内存时定一次名字，之后就是真字段，改名、排序都不受影响。
 */
function ensureClipNames() {
  const byLayer = new Map();
  (clips() || []).forEach(clip => {
    const used = byLayer.get(clip.targetId) || 0;
    byLayer.set(clip.targetId, used + 1);
    if (!clip.name || !String(clip.name).trim()) clip.name = '动画组 ' + (used + 1);
  });
}

// ---------------------------------------------------------------- 旧版绑定迁移
//
// 早期绑定只在右侧面板里配置，数据是 interactions.bindings。
// 现在统一改由工作台的「数据绑定」节点管理，所以导入旧文件时要把每条绑定转成一个节点，
// 否则：① 面板已隐藏，用户看不到那几条绑定 ② 一旦新增节点，导出会用节点覆盖 bindings，直接丢数据。
const ENGINE_PROP_TO_UI = {
  x: 'x', y: 'y', scale: 'scale', rotationZ: 'rot', opacity: 'alpha',
  width: 'width', height: 'height'
};

function migrateLegacyBindings(doc) {
  if (!doc || !doc.interactions) return 0;
  const it = doc.interactions;
  const legacy = Array.isArray(it.bindings) ? it.bindings.slice() : [];
  if (!legacy.length) return 0;

  it.studio = it.studio || { nodes: [], edges: [] };
  it.studio.nodes = it.studio.nodes || [];
  it.studio.edges = it.studio.edges || [];

  let created = 0;
  legacy.forEach((b, index) => {
    const target = String(b.targetId || '');
    if (!target) return;
    const prop = ENGINE_PROP_TO_UI[b.property] || 'y';
    const source = String(b.source || 'sys.battery.level');
    // 表达式绑定（0.8）：迁移时必须把公式带过来，否则节点变成线性模式，
    // 预览会按 in/out 硬算（实测：温度条直接顶到 100），导出也会丢掉公式。
    const expr = String(b.expr || '').trim();
    // 已经存在等价节点就不重复建
    const exists = it.studio.nodes.some(n =>
      n.type === 'bind' && n.params &&
      n.params.target === target && n.params.prop === prop &&
      (expr ? String(n.params.expr || '').trim() === expr : n.params.source === source)
    );
    if (exists) return;
    const inA = Array.isArray(b.in) ? b.in : [0, 100];
    const outA = Array.isArray(b.out) ? b.out : [0, 100];
    it.studio.nodes.push({
      id: 'bind_legacy_' + index + '_' + Math.random().toString(36).slice(2, 6),
      type: 'bind',
      x: 60 + (index % 2) * 300,
      y: 60 + Math.floor(index / 2) * 190,
      params: expr
        ? { target, prop, expr }
        : {
            target,
            prop,
            source,
            in0: String(inA[0]), in1: String(inA[1]),
            out0: String(outA[0]), out1: String(outA[1])
          }
    });
    created += 1;
  });

  // 清空旧字段：之后只以节点为准，避免两处并存
  it.bindings = [];
  if (created > 0) {
    status('已把 ' + created + ' 条旧绑定转成触发器节点（右键工作台可查看）');
  }
  return created;
}

function persistDoc() {
  clearTimeout(persistTimer);
  persistTimer = setTimeout(() => {
    // 资源（图片 data URL）单独存 IndexedDB：
    // localStorage 只有 ~5MB，带几张图就超配额；以前超了会静默退化成 resources:{}，
    // 于是刷新后图片全丢（用户实测："刷新后一片空白"）。
    saveResourcesToIdb(state.doc.resources || {});
    try {
      localStorage.setItem(DOC_KEY, JSON.stringify({
        manifest: state.doc.manifest,
        scene: state.doc.scene,
        interactions: state.doc.interactions,
        resources: {}
      }));
    } catch (err) {
      // 连结构都存不下（极少见）：不阻塞编辑，但要让用户知道
      console.warn('草稿保存失败（结构也超配额）', err);
    }
  }, 400);
}

// ---------------------------------------------------------------- 资源持久化（IndexedDB）
//
// 图片以 data URL 存在内存里，一张 1080p 截图就上 MB，localStorage 放不下。
// IndexedDB 配额按磁盘比例给（通常几百 MB），适合放资源。
const IDB_NAME = 'lwpkit-editor';
const IDB_STORE = 'resources';
const IDB_KEY = 'current';

function idbOpen() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(IDB_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(IDB_STORE)) {
        request.result.createObjectStore(IDB_STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function idbPut(key, value) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, 'readwrite');
    tx.objectStore(IDB_STORE).put(value, key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

async function idbGet(key) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, 'readonly');
    const request = tx.objectStore(IDB_STORE).get(key);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function saveResourcesToIdb(resources) {
  runCatchingIdb(() => idbPut(IDB_KEY, resources || {}), '资源保存到 IndexedDB 失败');
}

function runCatchingIdb(task, label) {
  Promise.resolve()
    .then(task)
    .catch(error => console.warn(label, error));
}

/** 启动时把资源补回内存（异步），补完再重绘一次。 */
function restoreResourcesFromIdb() {
  runCatchingIdb(async () => {
    const saved = await idbGet(IDB_KEY);
    if (!saved || typeof saved !== 'object') return;
    const current = state.doc.resources || {};
    const merged = Object.assign({}, saved, current);
    if (Object.keys(merged).length === Object.keys(current).length) {
      // 内存里已经有（本次会话导入的），不用覆盖
      return;
    }
    state.doc.resources = merged;
    if (typeof imageCache !== 'undefined' && imageCache.clear) imageCache.clear();
    renderAll(true);
    status('已从浏览器缓存恢复图片资源（' + Object.keys(merged).length + ' 个）');
  }, '资源恢复失败');
}

function loadPersistedDoc() {
  try {
    const raw = localStorage.getItem(DOC_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || !parsed.scene || !parsed.scene.root) return null;
    return parsed;
  } catch {
    return null;
  }
}


function scheduleDraw() {
  // 每次要重绘通常意味着文档变了：落盘 + 记一步撤销（两者内部都有去抖）
  persistDoc();
  rememberHistory();
  if (drawPending) return;
  drawPending = true;
  requestAnimationFrame(() => { drawPending = false; draw(); });
}

// ---------------------------------------------------------------- 属性 ID 接口
// 规范见 docs/property-ids.md：总 ID = 图层名，子 ID = 短属性名，用点连接。
// 界面上不显示，只给写函数时用；这样"引用某个属性"不必记 JSON 字段全名。
const ATTR_KEYS = {
  x: 'x', y: 'y', w: 'width', h: 'height',
  scale: 'scale', rot: 'rotationZ', alpha: 'opacity',
  radius: 'cornerRadius', feather: 'feather',
  shape: 'shape', sides: 'sides',
  color: 'color', colorA: 'color', grad: 'gradient',
  visible: 'visible', hit: 'hitThrough', clip: 'clip',
  bg: 'background', bgA: 'background',
  txt: 'text', font: 'fontSize', align: 'align', src: 'src',
  mask: 'mask', maskSides: 'maskSides',
  maskX: 'maskX', maskY: 'maskY', maskW: 'maskW', maskH: 'maskH',
  para: 'parallaxMode', paraDepth: 'parallax', paraAmp: 'parallaxAmp', paraAngle: 'parallaxAngle'
};

/** 画布级参数的子 ID（不挂在某个图层上）。 */
const CANVAS_ATTRS = {
  paraOn: { path: ['manifest', 'parallax', 'enabled'] },
  depth: { path: ['manifest', 'parallax', 'depth'] },
  amp: { path: ['manifest', 'parallax', 'amp'] },
  smooth: { path: ['manifest', 'parallax', 'smoothing'] },
  dead: { path: ['manifest', 'parallax', 'deadzone'] },
  bg: { path: ['scene', 'root', 'background'] },
  bgA: { path: ['scene', 'root', 'background'], alpha: true }
};

/** 拼出完整 ID：LWP.id('rect_glow','alpha') → "rect_glow.alpha" */
function attrId(layerId, prop) { return layerId + '.' + prop; }

/** 按 id 找图层节点（深度优先，容器里的也算）。 */
function attrNode(layerId) {
  const walk = node => {
    if (!node) return null;
    if (node.id === layerId) return node;
    if (Array.isArray(node.children)) {
      for (const child of node.children) {
        const hit = walk(child);
        if (hit) return hit;
      }
    }
    return null;
  };
  return walk(state.doc.scene.root);
}

/**
 * 读颜色串里的 alpha。
 *
 * 内部格式是 `#AARRGGBB`（alpha **在前**，Android 惯例），
 * 不是 CSS 的 `#RRGGBBAA`。这里踩过一次：读写位置错了会让颜色整体变色。
 */
function colorAlphaOf(raw) {
  const s = String(raw || '').trim();
  if (/^#[0-9a-fA-F]{8}$/.test(s)) return parseInt(s.slice(1, 3), 16) / 255;
  return 1;
}

/** 改颜色串的 alpha（前两位），保留 RGB；6 位补成 8 位。 */
function withColorAlpha(raw, alpha) {
  const s = String(raw || '').trim();
  const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255).toString(16).padStart(2, '0').toUpperCase();
  if (/^#[0-9a-fA-F]{8}$/.test(s)) return '#' + a + s.slice(3).toUpperCase();
  if (/^#[0-9a-fA-F]{6}$/.test(s)) return '#' + a + s.slice(1).toUpperCase();
  return s;
}

function readCanvasAttr(prop) {
  const spec = CANVAS_ATTRS[prop];
  if (!spec) return undefined;
  let cur = state.doc;
  for (const key of spec.path) { if (cur == null) return undefined; cur = cur[key]; }
  return spec.alpha ? colorAlphaOf(cur) : cur;
}

function writeCanvasAttr(prop, value) {
  const spec = CANVAS_ATTRS[prop];
  if (!spec) return false;
  if (spec.alpha) {
    // 背景的 alpha 要连同 RGB 一起写回，否则会把颜色抹掉
    let cur = state.doc;
    for (const key of spec.path.slice(0, -1)) cur = cur[key];
    const last = spec.path[spec.path.length - 1];
    cur[last] = withColorAlpha(cur[last], value);
  } else {
    let cur = state.doc;
    for (const key of spec.path.slice(0, -1)) {
      if (cur[key] == null) cur[key] = {};
      cur = cur[key];
    }
    cur[spec.path[spec.path.length - 1]] = value;
  }
  scheduleDraw();
  return true;
}

/** 读属性：LWP.attr('rect_glow','alpha')。支持 "rect_glow.alpha" 整串写法。 */
function attrRead(layerId, prop) {
  if (prop === undefined && typeof layerId === 'string' && layerId.includes('.')) {
    [layerId, prop] = layerId.split('.');
  }
  if (layerId === 'canvas') return readCanvasAttr(prop);
  const node = attrNode(layerId);
  if (!node) return undefined;
  const key = ATTR_KEYS[prop];
  if (!key) return undefined;
  if (prop === 'colorA') return colorAlphaOf(node.color);
  if (prop === 'bgA') return colorAlphaOf(node.background);
  return node[key];
}

/** 写属性：LWP.set('rect_glow','alpha',0.5)。写完整 ID 也行。 */
function attrWrite(layerId, prop, value) {
  if (value === undefined && typeof layerId === 'string' && layerId.includes('.')) {
    const parts = layerId.split('.');
    value = prop;
    prop = parts.pop();
    layerId = parts.join('.');
  }
  if (layerId === 'canvas') return writeCanvasAttr(prop, value);
  const node = attrNode(layerId);
  if (!node) return false;
  const key = ATTR_KEYS[prop];
  if (!key) return false;
  if (prop === 'colorA') node.color = withColorAlpha(node.color, value);
  else if (prop === 'bgA') node.background = withColorAlpha(node.background, value);
  else node[key] = value;
  scheduleDraw();
  return true;
}

/** 全部图层 id（含容器嵌套），方便表格化处理。 */
function attrLayers() {
  const out = [];
  const walk = node => {
    if (!node) return;
    if (node.id && node.id !== 'root') out.push(node.id);
    (node.children || []).forEach(walk);
  };
  walk(state.doc.scene.root);
  return out;
}

window.LWP = {
  id: attrId,
  attr: attrRead,
  set: attrWrite,
  node: attrNode,
  layers: attrLayers,
  keys: () => Object.keys(ATTR_KEYS),
  canvasKeys: () => Object.keys(CANVAS_ATTRS)
};

function canvasPos(ev) {
  const box = canvas.getBoundingClientRect();
  return {
    x: (ev.clientX - box.left) * (canvas.width / box.width),
    y: (ev.clientY - box.top) * (canvas.height / box.height)
  };
}

/**
 * 屏幕上这个点是否压在某个图层上。
 *
 * 用途：区分"拖图层"和"拖整个预览面板"。
 * 画布在面板内部，两者共用 pointerdown —— 不区分的话，
 * 按住图层一会儿再拖，面板的长按计时器也会启动，结果图层和整块预览一起动。
 */
function canvasHitAtClient(clientX, clientY) {
  const box = canvas.getBoundingClientRect();
  if (clientX < box.left || clientX > box.right || clientY < box.top || clientY > box.bottom) return null;
  const x = (clientX - box.left) * (canvas.width / box.width);
  const y = (clientY - box.top) * (canvas.height / box.height);
  try { return hitRect(x, y); } catch { return null; }
}

canvas.addEventListener('pointerdown', ev => {
  const p = canvasPos(ev);
  canvas.setPointerCapture(ev.pointerId);

  // 蒙版蓝点优先：它压在图层上且体积小，先判它才不会误触图层拖动
  const sel0 = selectedRect();
  const maskCenter = maskCenterOnCanvas(sel0);
  if (maskCenter && Math.hypot(p.x - maskCenter.x, p.y - maskCenter.y) <= Math.max(18 * canvasPixelRatio(), 13)) {
    state.drag = {
      mode: 'mask',
      nodeId: sel0.id,
      start: p,
      originX: Number(sel0.maskX) || 0,
      originY: Number(sel0.maskY) || 0,
      wPx: worldToPx(sel0.width),
      hPx: worldToPx(sel0.height)
    };
    return;
  }

  const handle = hitHandle(p.x, p.y);
  if (handle >= 0) {
    // 正圆拖手柄必须等比，否则一拖就成椭圆，「正圆」勾选形同虚设
    state.drag = { mode: 'resize', handle, keepAspect: isCircleLocked(selectedRect()) };
    return;
  }

  const rect = hitRect(p.x, p.y);
  if (!rect) {
    // 点画布空白处：取消选中，并收起触发链贴板
    state.sel.rectId = null;
    state.sel.channel = -1;
    state.drag = null;
    if (typeof hideRuleDetail === 'function') hideRuleDetail();
    renderAll();
    return;
  }

  state.sel.rectId = rect.id;
  const clip = clipFor(rect);
  const positionArmed = clip ? channelArmed(clip, CHANNELS[0]) : false;
  const cur = clip ? channelValueAt(clip, rect, CHANNELS[0], state.playhead) : { x: rect.x, y: rect.y };

  state.drag = {
    mode: 'move',
    start: p,
    originX: cur.x,
    originY: cur.y,
    armed: positionArmed
  };
  renderAll();
});

canvas.addEventListener('pointermove', ev => {
  // 视差预览：不拖拽时，鼠标相对画布中心的位置 → 倾斜量（-1..1）。
  // 与 Wallpaper Engine 的交互一致：鼠标往右下，近层往右下偏得多，远层几乎不动。
  if (!state.drag) {
    const p = canvasPos(ev);
    const cx = canvas.width / 2;
    const cy = canvas.height / 2;
    const halfW = Math.max(1, canvas.width / 2);
    const halfH = Math.max(1, canvas.height / 2);
    state.parallax.targetX = clamp((p.x - cx) / halfW, -1, 1);
    state.parallax.targetY = clamp((p.y - cy) / halfH, -1, 1);
    startParallaxLoop();
  }
  if (!state.drag) return;
  const p = canvasPos(ev);
  const unitPerPx = 100 / canvas.width;
  const rect = selectedRect();
  if (!rect) return;

  if (state.drag.mode === 'mask') {
    const node = findNode(state.drag.nodeId);
    const m = node && nodeMatrices.get(node.id);
    if (node && m) {
      // 画布位移 → 图层局部单位：用节点矩阵反算，容器/缩放/旋转都自动正确
      const a = mapPoint(m.inverse(), state.drag.start.x, state.drag.start.y);
      const b = mapPoint(m.inverse(), p.x, p.y);
      node.maskX = round1(state.drag.originX + ((b.x - a.x) / state.drag.wPx) * 100);
      node.maskY = round1(state.drag.originY + ((b.y - a.y) / state.drag.hPx) * 100);
      scheduleDraw();
    }
    return;
  }

  if (state.drag.mode === 'resize') {
    resizeRect(rect, state.drag.handle, p);
    scheduleDraw();
    return;
  }

  // 元素在容器里时，屏幕位移要经父容器的矩阵换算成容器内的单位
  const delta = toLocalDelta(rect, p.x - state.drag.start.x, p.y - state.drag.start.y);
  const dx = delta.dx * unitPerPx;
  const dy = delta.dy * unitPerPx;
  const nx = round1(state.drag.originX + dx);
  const ny = round1(state.drag.originY + dy);

  if (state.drag.armed) {
    // 位置已进入关键帧模式：拖到哪，就在播放头写一帧
    const clip = clipFor(rect);
    writeChannelAt(clip, rect, CHANNELS[0], { x: nx, y: ny });
  } else {
    rect.x = nx;
    rect.y = ny;
  }
  scheduleDraw();
});

canvas.addEventListener('pointerup', () => { state.drag = null; renderAll(); });
canvas.addEventListener('pointercancel', () => { state.drag = null; });

/**
 * 是否锁定等比（正圆）。
 *
 * 用**显式标志**而不是"宽高相等"推导：推导式会导致取消勾选后宽高仍相等，
 * 重新渲染又被判成正圆，开关看起来关不掉。
 * 这个字段只服务编辑器交互，导出时会被剥掉（手机端只看宽高）。
 */
function isCircleLocked(node) {
  if (!node || (node.shape || 'rect') !== 'ellipse') return false;
  return node.circleLock === true;
}

function resizeRect(rect, handle, p) {
  // 关键：锚点必须用**画面上看到的**几何来算。
  // 之前这里传的是 {}（静态值），而缩放却读动画后的值，
  // 一旦缩放或位置打了关键帧，两套坐标系就对不上，拖动会围绕中心反复缩放。
  const drawn = overridesFor(rect, overridesCached());
  const t = rectTransform(rect, drawn);
  const pts = cornersOf(t).map(v => ({ x: v.ux, y: v.uy }));
  const anchor = pts[[2, 3, 0, 1][handle]];
  const rad = -t.rotation * Math.PI / 180;
  // 指针必须先换算到**父容器坐标系**：rectTransform/cornersOf 算出的都是父空间里的几何，
  // 直接拿画布坐标去减，容器一有位移/缩放锚点就全错，表现为"一拖边缘尺寸暴涨"。
  const parentNode = parentOf(rect.id);
  const parentMatrix = parentNode ? nodeMatrices.get(parentNode.id) : null;
  const localPoint = parentMatrix
    ? mapPoint(parentMatrix.inverse(), p.x, p.y)
    : { x: p.x, y: p.y };
  const px = localPoint.x * 100 / canvas.width, py = localPoint.y * 100 / canvas.width;
  const dx = px - anchor.x, dy = py - anchor.y;
  let lx = dx * Math.cos(rad) - dy * Math.sin(rad);
  let ly = dx * Math.sin(rad) + dy * Math.cos(rad);

  // 正圆：两轴取同一个值（跟着更远的那个方向走），保证拖手柄不变形
  if (state.drag && state.drag.keepAspect) {
    const size = Math.max(Math.abs(lx), Math.abs(ly));
    lx = lx < 0 ? -size : size;
    ly = ly < 0 ? -size : size;
  }

  // lx / ly 是**视觉**尺寸；写回基准尺寸必须用它实际使用的缩放。
  const scale = drawn.scale !== undefined ? drawn.scale : effectiveScale(rect);
  const visualW = Math.max(0.5, Math.abs(lx));
  const visualH = Math.max(0.5, Math.abs(ly));
  const baseW = visualToBase(visualW, scale);
  const baseH = visualToBase(visualH, scale);

  const cos = Math.cos(-rad), sin = Math.sin(-rad);
  const cx = anchor.x + (lx / 2) * cos - (ly / 2) * sin;
  const cy = anchor.y + (lx / 2) * sin + (ly / 2) * cos;

  rect.width = baseW;
  rect.height = baseH;

  // rect.x/y 是**视觉框**的左上角，所以要用视觉尺寸的一半来推算。
  const newX = round1(cx - visualW / 2);
  const newY = round1(cy - visualH / 2);

  // 位置通道如果已经打了关键帧，改静态值是没有视觉效果的（动画会覆盖它），
  // 必须把新位置写成当前播放头处的关键帧，否则拖动时锚点会飘。
  const clip = clipFor(rect);
  if (clip && channelArmed(clip, CHANNELS[0])) {
    writeChannelAt(clip, rect, CHANNELS[0], { x: newX, y: newY });
  } else {
    rect.x = newX;
    rect.y = newY;
  }
}

// ---------------------------------------------------------------- 面板

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
}

/**
 * 定位到某个图层：选中 + 重画 + 图层列表滚动到可见。
 *
 * 为什么单独抽出来：触发链贴板里点"触发者/被影响图层"时要跳回左栏，
 * 直接设 rectId 只会选中，列表可能还停在别处，用户找不到。
 */
function locateLayer(id) {
  if (!id || !rects().some(r => r.id === id)) return false;
  state.sel.rectId = id;
  state.sel.channel = -1;
  renderAll();
  const row = document.querySelector('#layer-list .row[data-id="' + id + '"]');
  if (row) {
    row.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    row.classList.add('flash');
    setTimeout(() => row.classList.remove('flash'), 900);
  }
  return true;
}

function renderLayers() {
  const box = document.getElementById('layer-list');
  box.innerHTML = '';
  if (!rects().length) { box.appendChild(el('div', 'hint', '还没有元素')); return; }

  // 递归显示，容器里的元素缩进——和 HTML 的嵌套关系对应
  const walk = (nodes, depth) => {
    nodes.forEach(r => {
      const row = el('div', 'row' + (r.id === state.sel.rectId ? ' sel' : ''));
      row.dataset.id = r.id;   // 供"定位到某个图层"使用（贴板点图层名会滚动到这里）
      row.style.paddingLeft = `${6 + depth * 14}px`;
      row.draggable = true;
      row.title = '拖动可调整层级；拖到容器中间可放进容器';

      const dot = el('span', 'dot');
      if (isImage(r)) {
        const url = srcOf(r);
        dot.style.background = url ? `center/cover url(${url})` : '#ff6b6b';
      } else if (isContainer(r)) {
        dot.style.background = colorToInput(r.background || '#00000000');
        dot.style.border = '1px solid #5a6472';
      } else {
        dot.style.background = colorToInput(r.color);
      }
      row.appendChild(dot);
      row.appendChild(el('span', 'name', r.id));

      const anim = clipFor(r) && clipFor(r).tracks.some(t => t.keyframes.length) ? '◆' : '';
      const kind = isText(r) ? '文本 ' : isImage(r) ? '图片 ' : isContainer(r) ? '容器 ' : '';
      row.appendChild(el('span', 'meta', kind + anim));

      row.onclick = () => {
        state.sel.rectId = r.id;
        state.sel.channel = -1;
        highlightSelectedLayer(r.id);
        renderAll(true);
      };
      wireLayerDrag(row, r);
      row.ondblclick = ev => { ev.stopPropagation(); startRename(row, r); };
      box.appendChild(row);

      if (isContainer(r) && (r.children || []).length) walk(r.children, depth + 1);
    });
  };
  walk(rects(), 0);
}



// ================= 撤销 / 重做（快照式） =================
// 每次改动后把文档序列化存一份；撤销就是"回退到上一份快照"。
// 只存 scene + interactions + manifest：资源（图片 data URL）体积大且很少变，
// 存进去会把内存吃光，所以图片的增删不在撤销范围内（会提示）。
const HISTORY_LIMIT = 60;
const HISTORY_DEBOUNCE_MS = 350;

const history = {
  undo: [],
  redo: [],
  last: null,
  timer: 0,
  restoring: false
};

function snapshotDoc() {
  try {
    return JSON.stringify({
      manifest: state.doc.manifest,
      scene: state.doc.scene,
      interactions: state.doc.interactions
    });
  } catch {
    return null;
  }
}

function applySnapshot(json) {
  let parsed;
  try {
    parsed = JSON.parse(json);
  } catch {
    return false;
  }
  history.restoring = true;
  state.doc.manifest = parsed.manifest || clone(DEFAULT_MANIFEST);
  state.doc.scene = parsed.scene;
  state.doc.interactions = parsed.interactions || clone(DEFAULT_INTERACTIONS);
  // 选中的图层可能已经不存在（撤销掉了），清掉避免面板指向幽灵节点
  if (state.sel.rectId && !findNode(state.sel.rectId)) {
    state.sel = { rectId: null, channel: -1, keyIndex: -1 };
  }
  renderAll(false);
  draw();
  history.restoring = false;
  return true;
}

/**
 * 记录一次改动。
 *
 * 去抖的意义：拖拽/拖滑块会连续触发几十次，如果每次都入栈，
 * 一次拖动要按几十下 Ctrl+Z 才能退回去。350ms 静默后才合并成一步。
 */
function rememberHistory() {
  if (history.restoring) return;
  clearTimeout(history.timer);
  history.timer = setTimeout(() => {
    const snap = snapshotDoc();
    if (!snap) return;
    if (snap === history.last) return;
    if (history.last !== null) {
      history.undo.push(history.last);
      if (history.undo.length > HISTORY_LIMIT) history.undo.shift();
    }
    history.redo.length = 0;
    history.last = snap;
  }, HISTORY_DEBOUNCE_MS);
}

function undoEdit() {
  if (state.drag) return false;            // 拖拽中不撤销，避免和拖拽状态打架
  rememberHistory();                        // 先落定当前状态
  clearTimeout(history.timer);
  const snap = snapshotDoc();
  if (snap && snap !== history.last) {
    // 有未落定的改动：先把它压栈，再回退
    if (history.last !== null) {
      history.undo.push(history.last);
      if (history.undo.length > HISTORY_LIMIT) history.undo.shift();
    }
    history.last = snap;
  }
  const prev = history.undo.pop();
  if (!prev) {
    status('没有可撤销的操作了');
    return false;
  }
  history.redo.push(history.last);
  history.last = prev;
  if (!applySnapshot(prev)) {
    status('撤销失败：快照损坏');
    return false;
  }
  status('已撤销');
  return true;
}

function redoEdit() {
  const next = history.redo.pop();
  if (!next) {
    status('没有可重做的操作了');
    return false;
  }
  history.undo.push(history.last);
  history.last = next;
  if (!applySnapshot(next)) {
    status('重做失败：快照损坏');
    return false;
  }
  status('已重做');
  return true;
}

function resetHistory() {
  clearTimeout(history.timer);
  history.undo.length = 0;
  history.redo.length = 0;
  history.last = snapshotDoc();
}

window.addEventListener('keydown', ev => {
  if (!(ev.ctrlKey || ev.metaKey)) return;
  if (isTypingTarget(ev.target)) return;    // 输入框里交给浏览器处理文本撤销
  const key = String(ev.key || '').toLowerCase();
  if (key === 'z') {
    // Ctrl+Shift+Z 也当重做（很多编辑器的习惯）
    const did = ev.shiftKey ? redoEdit() : undoEdit();
    if (did) ev.preventDefault();
  } else if (key === 'y') {
    if (redoEdit()) ev.preventDefault();
  }
});



// ================= 触发器模式（独立工作区） =================
// 与传统"标签页"的区别：进入后时间轴标签条整体隐藏，底部完全交给触发器面板；
// 退出时恢复原样。模式状态仍存在 shell 的 bottomMode 里，刷新后保持。
/**
 * 底部模式切换后重排。
 *
 * 为什么必须做：切到触发器模式时底部标签条消失、时间轴整体收起，
 * .canvas-wrap 高度变大；不重算的话画布还是旧尺寸，而面板的百分比位置按旧高度算，
 * 结果预览会顶出画布上边界（实测出界）。这里等一帧让布局稳定，再重算并按新尺寸钳制。
 */
function relayoutAfterModeChange() {
  const redo = () => {
    if (typeof resizeCanvas === 'function') resizeCanvas();
    const panel = document.getElementById('preview-panel');
    if (panel && typeof applyPanelTransform === 'function') applyPanelTransform(panel);
  };
  redo();
  requestAnimationFrame(redo);
  setTimeout(redo, 120);
}
// ================= 底部双模式 + 可移动/可缩放预览 =================
// 手势约定（作用在预览面板上）：
//   左键双击        → 折叠 / 展开
//   左键长按 260ms  → 拖动位置（蓝色圆点同样可拖）
//   右键长按        → 缩放（按住右键上下拖；期间屏蔽右键菜单）
//   边缘碰到顶部刘海 → 磁吸居中 + 缩放复位 1.0
//
// 两条硬约束：
//   1) 蓝点在面板右上角**外面**（外伸 9px），所以位置必须按"面板 + 蓝点"整体钳制，
//      否则蓝点会被推到容器外，用户就再也按不到它。
//   2) 磁吸用矩形相交判断（"碰到"），不要求精确停在像素上 —— 手拖做不到。
const SHELL_KEY = 'lwpkit.shell.v1';
const PANEL_MIN_SCALE = 0.35;
const PANEL_MAX_SCALE = 1.6;
const HANDLE_MARGIN = 14;    // 蓝点外伸 9px + 一点余量

function shellState() {
  try {
    return JSON.parse(localStorage.getItem(SHELL_KEY) || '{}') || {};
  } catch {
    return {};
  }
}

function saveShellState(patch) {
  const next = Object.assign(shellState(), patch);
  try { localStorage.setItem(SHELL_KEY, JSON.stringify(next)); } catch { /* 忽略 */ }
}

/** 刘海占据的纵向高度（用来算"贴着刘海下沿"的落点）。 */
function notchHeight() {
  const notch = document.getElementById('preview-notch');
  return notch ? notch.offsetHeight : 15;
}

/**
 * 面板"顶部居中、贴着刘海下沿"时的纵向中心百分比。
 *
 * 不能用固定数字：手机预览高宽比 1:2.17，面板高度接近整块可用高度，
 * 中心点写死会让上边缘跑到容器外（预览顶部被裁掉）。按顶边反算才对。
 */
function topAlignedY(panel) {
  const wrap = document.querySelector('.canvas-wrap');
  if (!wrap || !panel) return 50;
  const h = panel.offsetHeight || 400;
  const wrapH = wrap.clientHeight || 600;
  const top = notchHeight() + 2;   // 紧贴刘海下沿
  return Math.min(92, ((top + h / 2) / wrapH) * 100);
}

/** 把"中心点百分比 + 缩放"写进面板样式，同时做越界钳制。 */
function applyPanelTransform(panel, overrideX, overrideY) {
  const wrap = document.querySelector('.canvas-wrap');
  const st = shellState();
  const scale = Number.isFinite(st.panelScale) ? st.panelScale : 1;
  let px = Number.isFinite(overrideX) ? overrideX : (Number.isFinite(st.panelX) ? st.panelX : 50);
  let py = Number.isFinite(overrideY) ? overrideY : (Number.isFinite(st.panelY) ? st.panelY : topAlignedY(panel));

  if (wrap && panel) {
    const wrapW = wrap.clientWidth || 600;
    const wrapH = wrap.clientHeight || 800;
    // 折叠后画布视觉尺寸只剩几像素，但 offsetWidth 仍是布局尺寸；
    // 直接用布局尺寸钳制会让蓝点在折叠态几乎不能移动（用户报告过）。
    const collapsed = panel.classList.contains('collapsed');
    const effW = collapsed ? 26 : panel.offsetWidth * scale;
    const effH = collapsed ? 26 : panel.offsetHeight * scale;
    const halfW = effW / 2 + HANDLE_MARGIN;
    const halfH = effH / 2 + HANDLE_MARGIN;
    const minX = (halfW / wrapW) * 100;
    const maxX = 100 - minX;
    const minY = (halfH / wrapH) * 100;
    const maxY = 100 - minY;
    // 面板比容器还大时 min>max，取中点；否则正常钳制
    px = minX <= maxX ? Math.min(maxX, Math.max(minX, px)) : 50;
    py = minY <= maxY ? Math.min(maxY, Math.max(minY, py)) : 50;
  }

  panel.style.left = px + '%';
  panel.style.top = py + '%';
  panel.style.transform = 'translate(-50%, -50%) scale(' + scale + ')';
  panel.style.transformOrigin = 'center center';
  return { px, py, scale };
}

function applyShellState() {
  const st = shellState();
  const panel = document.getElementById('preview-panel');
  if (panel) {
    applyPanelTransform(panel);
    panel.classList.toggle('collapsed', !!st.panelCollapsed);
  }
  setBottomMode('timeline', true);
}

function setBottomMode(mode, silent) {
  // 触发器工作区已移除：底部只有时间轴一种形态。
  // 保留函数名是为了不改动调用点（恢复草稿外壳状态时会调到它）。
  const timeline = document.getElementById('timeline-mode');
  if (timeline) timeline.style.display = 'flex';
  if (!silent) {
    saveShellState({ bottomMode: 'timeline' });
    if (typeof renderTimeline === 'function') renderTimeline();
  }
}
/** 面板（按当前缩放算出的矩形）是否与刘海相交 —— 相交即"碰到了"。 */
function touchingNotch(panel, scale) {
  const notch = document.getElementById('preview-notch');
  if (!notch) return false;
  const nb = notch.getBoundingClientRect();
  const pb = panel.getBoundingClientRect();
  // 用即将生效的缩放估算：宽高按 scale 换算，中心不变
  const w = (panel.offsetWidth * scale);
  const h = (panel.offsetHeight * scale);
  const cx = pb.left + pb.width / 2;
  const cy = pb.top + pb.height / 2;
  const left = cx - w / 2;
  const right = cx + w / 2;
  const top = cy - h / 2;
  const bottom = cy + h / 2;
  // 纵向允许 2px 容差，横向必须和刘海有交集（否则拖到角落也会误吸）
  return right > nb.left && left < nb.right && top <= nb.bottom + 2 && bottom > nb.top;
}

function setupGraphShell() {
  const tabT = document.getElementById('tab-timeline');
  if (tabT) tabT.onclick = () => setBottomMode('timeline');

  const panel = document.getElementById('preview-panel');
  const handle = document.getElementById('preview-handle');
  const wrap = document.querySelector('.canvas-wrap');
  if (!panel || !wrap) return;

  let pressTimer = 0;
  let foldTimer = 0;
  let mode = null;           // 'move' | 'scale' | null
  let startClient = null;
  let startScale = 1;

  const endGesture = () => {
    mode = null;
    panel.classList.remove('dragging');
    wrap.classList.remove('snap-ready');
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('pointerup', onUp);
    window.removeEventListener('pointercancel', onUp);
  };

  const onMove = ev => {
    if (!mode) return;
    const box = wrap.getBoundingClientRect();

    if (mode === 'move') {
      const cx = ev.clientX - startClient.dx;
      const cy = ev.clientY - startClient.dy;
      let px = ((cx - box.left) / box.width) * 100;
      let py = ((cy - box.top) / box.height) * 100;

      // 先把位置写进去（内部做越界钳制，保证蓝点不出界）
      applyPanelTransform(panel, px, py);
      const applied = applyPanelTransform(panel, px, py);

      if (touchingNotch(panel, startScale)) {
        // 碰到刘海：居中 + 恢复 100% 比例
        wrap.classList.add('snap-ready');
        saveShellState({ panelScale: 1, panelX: 50, panelY: Math.round(topAlignedY(panel) * 10) / 10 });
        applyPanelTransform(panel, 50, topAlignedY(panel));
        return;
      }

      wrap.classList.remove('snap-ready');
      saveShellState({ panelX: Math.round(applied.px * 10) / 10, panelY: Math.round(applied.py * 10) / 10 });
      return;
    }

    if (mode === 'scale') {
      // 往上拖放大、往下拖缩小
      const delta = (startClient.startY - ev.clientY) / 240;
      const next = clamp(startScale + delta, PANEL_MIN_SCALE, PANEL_MAX_SCALE);
      saveShellState({ panelScale: Math.round(next * 100) / 100 });
      applyPanelTransform(panel);
    }
  };

  const onUp = () => {
    if (mode === 'move' && typeof resizeCanvas === 'function') resizeCanvas();
    endGesture();
  };

  const beginGesture = (ev, nextMode, fromHandle) => {
    const box = panel.getBoundingClientRect();
    startScale = Number(shellState().panelScale) || 1;
    startClient = {
      dx: ev.clientX - (box.left + box.width / 2),
      dy: ev.clientY - (box.top + box.height / 2),
      startY: ev.clientY
    };
    // 折叠态下画布几乎不可见，从面板本体拖动没有意义；
    // 但蓝点必须还能拖 —— 否则收回后就没法把它挪回去了（用户报告过）。
    if (panel.classList.contains('collapsed') && !fromHandle) return;
    mode = nextMode;
    panel.classList.add('dragging');
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
  };

  // ---- 左键：长按拖动 ----
  panel.addEventListener('pointerdown', ev => {
    if (ev.button !== 0) return;
    if (ev.target === handle) return;      // 圆点有自己的一套
    // 画布上按到图层 = 要拖图层，不能再给"拖整块预览"计时
    if (ev.target === canvas && canvasHitAtClient(ev.clientX, ev.clientY)) return;
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => beginGesture(ev, 'move'), 260);
    const cancel = () => { clearTimeout(pressTimer); window.removeEventListener('pointerup', cancel); };
    window.addEventListener('pointerup', cancel);
  });

  // ---- 蓝色圆点：长按拖动 ----
  if (handle) {
    handle.addEventListener('pointerdown', ev => {
      if (ev.button !== 0) return;
      ev.preventDefault();
      ev.stopPropagation();
      clearTimeout(pressTimer);
      pressTimer = setTimeout(() => beginGesture(ev, 'move', true), 260);
      const cancel = () => { clearTimeout(pressTimer); window.removeEventListener('pointerup', cancel); };
      window.addEventListener('pointerup', cancel);
    });
  }

  // ---- 右键长按：缩放 ----
  // 右键菜单必须**整块画布区**都拦掉：只拦面板的话，右键落在面板外一点就会弹出
  // 浏览器菜单（实测面板外为 0 拦截）。用户是用右键拖来缩放，菜单会打断手势。
  const blockNativeMenu = ev => ev.preventDefault();
  wrap.addEventListener('contextmenu', blockNativeMenu);
  panel.addEventListener('contextmenu', blockNativeMenu);
  panel.addEventListener('pointerdown', ev => {
    if (ev.button !== 2) return;
    ev.preventDefault();
    beginGesture(ev, 'scale');
  });

  // ---- 左键双击：折叠 / 展开 ----
  const toggleCollapse = () => {
    const collapsed = !panel.classList.contains('collapsed');
    panel.classList.toggle('collapsed', collapsed);
    saveShellState({ panelCollapsed: collapsed });
    // 动画结束后再重算画布尺寸：折叠中重算会让 canvas 尺寸变化，动画看起来会跳
    clearTimeout(foldTimer);
    foldTimer = setTimeout(() => {
      if (typeof resizeCanvas === 'function') resizeCanvas();
    }, 260);
  };

  // 面板本体双击：同样折叠（保留，方便在大面板上直接双击）
  panel.addEventListener('dblclick', ev => {
    if (mode) return;                       // 拖动刚结束的手抖不触发
    ev.stopPropagation();
    toggleCollapse();
  });

  // 蓝点双击：折叠 / 展开（用户指定入口）
  if (handle) {
    handle.addEventListener('dblclick', ev => {
      ev.stopPropagation();
      ev.preventDefault();
      toggleCollapse();
    });
  }

  applyShellState();
}

// ================= 图层复制 / 粘贴 =================
// 复制放在内存里的剪贴板（state.clipboard）；同时尽量写一份到系统剪贴板，
// 这样"复制"这个动作符合直觉（虽然跨标签页粘贴不走系统剪贴板）。
const COPY_SUFFIX = /（副本(\d+)）$/;

/** 剥掉尾部的「（副本N）」，用于再次复制时不叠加。 */
function baseLayerId(id) {
  return String(id || '').replace(COPY_SUFFIX, '');
}

/** 生成一个未被占用的副本 id：base（副本N）。 */
function nextCopyId(base, taken) {
  let n = 1;
  for (;;) {
    const candidate = base + '（副本' + n + '）';
    if (!findNode(candidate) && !taken.has(candidate)) return candidate;
    n++;
  }
}

/**
 * 深拷贝节点并换掉所有 id（含子孙）。
 *
 * taken 是本次粘贴已经用掉的 id 集合：不能只查 findNode，因为新节点还没插进树里，
 * 连续粘贴时互相之间也会撞名。
 */
function cloneNodeWithNewIds(node, taken) {
  const copy = JSON.parse(JSON.stringify(node));
  copy.id = nextCopyId(baseLayerId(node.id), taken);
  taken.add(copy.id);
  if (Array.isArray(copy.children)) {
    copy.children = copy.children.map(child => cloneNodeWithNewIds(child, taken));
  }
  return copy;
}

const MAX_CLIPBOARD_TEXT = 400000;

function copySelectedLayer() {
  const node = selectedRect();
  if (!node) {
    status('先选中一个图层再复制');
    return false;
  }
  state.clipboard = {
    node: JSON.parse(JSON.stringify(node)),
    sourceId: node.id
  };
  // 系统剪贴板只是"顺手"，失败不影响功能（浏览器可能拒绝写）
  try {
    const text = JSON.stringify(state.clipboard.node);
    if (text.length < MAX_CLIPBOARD_TEXT && navigator.clipboard) {
      navigator.clipboard.writeText(text).catch(() => {});
    }
  } catch { /* 忽略 */ }
  status('已复制图层：' + node.id + '（Ctrl+V 粘贴为副本）');
  return true;
}

function pasteLayer() {
  const clip = state.clipboard;
  if (!clip || !clip.node) {
    status('剪贴板是空的：先选中图层按 Ctrl+C');
    return false;
  }
  const taken = new Set();
  const copy = cloneNodeWithNewIds(clip.node, taken);

  // 插到原图层后面（原图层可能已被删除，那就放到根容器末尾）
  const targetList = parentListOf(clip.sourceId) || rects();
  // 插到"同一原始图层的最后一个副本"之后：
  // 否则连续粘贴会变成 副本2、副本1 的倒序（第一次插在原图层后面，第二次又插在它后面）。
  const baseOfSource = baseLayerId(clip.sourceId);
  let insertAt = targetList.findIndex(n => n.id === clip.sourceId);
  for (let i = 0; i < targetList.length; i++) {
    if (baseLayerId(targetList[i].id) === baseOfSource) insertAt = i;
  }
  if (insertAt >= 0) targetList.splice(insertAt + 1, 0, copy);
  else targetList.push(copy);

  state.sel.rectId = copy.id;
  state.sel.channel = -1;
  state.sel.keyIndex = -1;
  // 必须 renderAll(false)：true 表示"保留现有图层列表"，
  // 粘贴新增了节点，不重建列表就看不到副本（要拖动一下才刷新）。
  renderAll(false);
  scheduleDraw();
  status('已粘贴：' + copy.id);
  return true;
}

/** 键盘焦点在输入类控件里时不要抢按键。 */
function isTypingTarget(target) {
  if (!target) return false;
  const tag = String(target.tagName || '').toLowerCase();
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return true;
  return !!target.isContentEditable;
}

window.addEventListener('keydown', ev => {
  if (!(ev.ctrlKey || ev.metaKey)) return;
  if (isTypingTarget(ev.target)) return;
  const key = String(ev.key || '').toLowerCase();
  // Ctrl+Shift+D 之类的多余组合不处理，只认标准复制粘贴
  if (ev.altKey) return;
  if (key === 'c') {
    if (copySelectedLayer()) ev.preventDefault();
  } else if (key === 'v') {
    if (pasteLayer()) ev.preventDefault();
  }
});

// ---------------------------------------------------------------- 图层拖拽排序

/** nodeId 是否是 ancestor 的子孙（防止把容器拖进自己内部形成环）。 */
function isDescendantOf(ancestor, nodeId) {
  if (!isContainer(ancestor)) return false;
  for (const child of ancestor.children || []) {
    if (child.id === nodeId) return true;
    if (isDescendantOf(child, nodeId)) return true;
  }
  return false;
}

/** 行内位置决定落点：上 1/3 插到前面、下 1/3 插到后面、中间（容器）放进里面。 */
function dropModeFor(row, node, clientY) {
  const box = row.getBoundingClientRect();
  const ratio = (clientY - box.top) / Math.max(1, box.height);
  if (isContainer(node) && ratio > 0.3 && ratio < 0.7) return 'inside';
  return ratio < 0.5 ? 'before' : 'after';
}

function clearDropIndicators() {
  document.querySelectorAll('#layer-list .row').forEach(r => {
    r.style.outline = '';
    r.style.boxShadow = '';
  });
}

function setDropIndicator(row, mode) {
  clearDropIndicators();
  if (mode === 'inside') {
    row.style.outline = '2px solid #3ddc97';
    row.style.outlineOffset = '-2px';
  } else {
    row.style.boxShadow = mode === 'before' ? 'inset 0 2px 0 #3ddc97' : 'inset 0 -2px 0 #3ddc97';
  }
}

/**
 * 移动节点到新位置（同层排序或跨容器）。
 *
 * 跨父级时要把位置换算到新父容器坐标系，否则元素会突然跳到别处。
 */
function moveNode(dragId, targetId, mode) {
  if (!dragId || dragId === targetId) return;
  const node = findNode(dragId);
  const target = findNode(targetId);
  if (!node || !target) return;

  if (isContainer(node) && isDescendantOf(node, targetId)) {
    status('不能把容器拖进它自己里面');
    return;
  }
  if (mode === 'inside' && !isContainer(target)) return;

  const oldParent = parentOf(dragId);
  const newParent = mode === 'inside' ? target : parentOf(targetId);

  if (oldParent !== newParent) {
    // 矩阵来自上一帧；节点刚建好/刚被删过时可能是空的，
    // 换算会被跳过 → 子节点留着绝对坐标 → 落到容器外被裁掉（"加进去就消失"）。
    // 先补一帧再取，取不到再用祖先偏移兜底。
    let world = worldPointOf(node);
    if (!world) {
      draw();
      world = worldPointOf(node);
    }
    if (world) {
      const matrix = newParent ? nodeMatrices.get(newParent.id) : null;
      const local = matrix
        ? new DOMPoint(world.x, world.y).matrixTransform(matrix.inverse())
        : world;
      node.x = round1(local.x * 100 / canvas.width);
      node.y = round1(local.y * 100 / canvas.width);
    }
  }

  const from = parentListOf(dragId);
  if (!from) return;
  from.splice(from.indexOf(node), 1);

  let toList;
  let index;
  if (mode === 'inside') {
    if (!Array.isArray(target.children)) target.children = [];
    toList = target.children;
    index = toList.length;
  } else {
    toList = parentListOf(targetId);
    if (!toList) return;
    index = toList.indexOf(target) + (mode === 'after' ? 1 : 0);
  }
  toList.splice(index, 0, node);

  state.sel.rectId = node.id;
  state.sel.channel = -1;
  renderAll();
  status(`已移动 ${node.id}` + (mode === 'inside' ? ` 到 ${target.id} 里面` : ''));
}

/** 节点左上角在画布上的像素坐标（用上一帧记录的父容器矩阵）。 */
function worldPointOf(node) {
  const parent = parentOf(node.id);
  const matrix = parent ? nodeMatrices.get(parent.id) : null;
  if (!matrix) return null;
  return mapPoint(matrix, worldToPx(node.x), worldToPx(node.y));
}

/**
 * 重命名图层（双击图层名）。
 *
 * 不只是改个显示名：id 是动画/事件/绑定的引用键，所以必须把所有引用一起改，
 * 否则改完名字动画就失效了。
 */
/**
 * @return true = 编辑结束（改名成功，或名字没变）；false = 输入不合法，应停在输入框里。
 *
 * 之前所有分支都不返回值，调用方 `if (renameNode(...))` 永远为假，
 * 于是输入框被反复 focus —— 双击后不改名就退不出去（用户实测）。
 */
function renameNode(node, nextId) {
  const id = String(nextId || '').trim();
  if (!id) {
    status('名字不能为空');
    return false;
  }
  if (id === node.id) {
    // 没改：重建一次列表把输入框换回文字，编辑结束
    renderAll();
    return true;
  }

  if (!/^[A-Za-z_][\w.\-]*$/.test(id)) {
    status('名字只能用字母、数字、下划线、点、短横线，且以字母或下划线开头');
    return false;
  }
  if (findNode(id)) {
    status('已经有同名图层：' + id);
    return false;
  }

  const oldId = node.id;
  node.id = id;

  // 动画的目标与触发元素
  for (const clip of clips()) {
    if (clip.targetId === oldId) clip.targetId = id;
    if (clip.trigger && clip.trigger.targetId === oldId) clip.trigger.targetId = id;
  }
  // 触摸事件
  for (const ev of (state.doc.interactions.events || [])) {
    if (ev.targetId === oldId) ev.targetId = id;
    if (ev.action && ev.action.targetId === oldId) ev.action.targetId = id;
  }
  // 变量绑定
  for (const b of (state.doc.interactions.bindings || [])) {
    if (b.targetId === oldId) b.targetId = id;
  }

  if (state.sel.rectId === oldId) state.sel.rectId = id;
  renderAll();
  status(`已重命名 ${oldId} → ${id}`);
  return true;
}

/** 双击图层名 → 行内输入框改名。 */
function startRename(row, node) {
  const nameSpan = row.querySelector('.name');
  if (!nameSpan) return;

  const input = document.createElement('input');
  input.value = node.id;
  input.style.cssText = 'flex:1;min-width:0;font:inherit;background:#0e1218;color:#e6edf6;border:1px solid #3ddc97;border-radius:4px;padding:1px 4px;';
  nameSpan.replaceWith(input);
  input.focus();
  input.select();

  let done = false;
  const finish = commit => {
    if (done) return;
    if (!commit) {
      done = true;
      renderAll();
      return;
    }
    if (renameNode(node, input.value)) {
      done = true; // 成功：renderAll 已重建列表，输入框随之消失
    } else {
      // 名字不合法或重名：**不要结束编辑**，否则用户改对了也提交不了
      input.focus();
      input.select();
    }
  };
  input.onkeydown = ev => {
    if (ev.key === 'Enter') finish(true);
    else if (ev.key === 'Escape') finish(false);
    ev.stopPropagation();
  };
  input.onblur = () => finish(true);
}
function wireLayerDrag(row, node) {
  row.addEventListener('dragstart', ev => {
    state.layerDragId = node.id;
    ev.dataTransfer.effectAllowed = 'move';
    // Firefox 不 setData 就不会真的开始拖动
    ev.dataTransfer.setData('text/plain', node.id);
  });

  row.addEventListener('dragend', () => {
    state.layerDragId = null;
    clearDropIndicators();
  });

  row.addEventListener('dragover', ev => {
    const dragId = state.layerDragId;
    if (!dragId || dragId === node.id) return;
    const dragged = findNode(dragId);
    if (dragged && isContainer(dragged) && isDescendantOf(dragged, node.id)) return;
    ev.preventDefault();
    ev.dataTransfer.dropEffect = 'move';
    setDropIndicator(row, dropModeFor(row, node, ev.clientY));
  });

  row.addEventListener('dragleave', () => {
    row.style.outline = '';
    row.style.boxShadow = '';
  });

  row.addEventListener('drop', ev => {
    ev.preventDefault();
    ev.stopPropagation();
    const dragId = state.layerDragId || ev.dataTransfer.getData('text/plain');
    const mode = dropModeFor(row, node, ev.clientY);
    clearDropIndicators();
    moveNode(dragId, node.id, mode);
  });
}

/** 属性面板最上面的「视差（全局）」区块：参数写进 manifest.parallax。 */
/**
 * 「视差（全局）」区块：画面级开关与手感参数，写进 manifest.parallax。
 *
 * 布局统一走 field()：标签在左、控件在右，和下方各属性行对齐 ——
 * 之前用自定义 flex 拼行，结果数字框飘位、按钮换行，属于自找麻烦。
 */
/** 「视差（全局）」区块：总开关 + 统一参数；选中图层时行尾多一个「独立」勾选。 */
/**
 * 视差面板（v3）：一组滑块 + 一个模式勾选。
 *
 * 为什么不把"全局"和"本层"分成两块：两者的滑块语义完全一样（景深/振幅/平滑/死区），
 * 分成两块只会让人以为要调两遍，还占屏幕。这里按上下文切换"改的是谁"，
 * 并在标题上写清楚当前模式。
 */
function renderParallaxGlobals(box, field, rect) {
  const cfg = parallaxSettings();
  const independent = !!rect && rect.parallaxMode === 'custom';

  // 标题行：未选中给全局开关；选中给「独立」勾选（它同时就是这层的视差开关）
  if (!rect) {
    const master = document.createElement('input');
    master.type = 'checkbox';
    master.checked = !!cfg.enabled;
    master.title = '总开关：控制所有跟随全局的图层；独立图层不受它影响';
    master.onchange = () => { setParallaxSetting('enabled', master.checked); scheduleDraw(); };
    field('视差（全局）', [master]);
  } else {
    const ind = document.createElement('input');
    ind.type = 'checkbox';
    ind.checked = independent;
    ind.title = '勾选后这一层用自己的参数（改下面的滑块即为本层设置）；不勾则跟随全局';
    ind.onchange = () => {
      rect.parallaxMode = ind.checked ? 'custom' : 'global';
      if (ind.checked) {
        // 首次独立时继承全局现值，避免"一勾就归零"
        if (!Number.isFinite(rect.parallax)) rect.parallax = cfg.depth;
        if (!Number.isFinite(rect.parallaxAmp)) rect.parallaxAmp = cfg.amp;
        if (!Number.isFinite(rect.parallaxAngle)) rect.parallaxAngle = 0;
      }
      renderInspector();
      scheduleDraw();
    };
    const tag = el('span', null, '独立');
    tag.style.fontSize = '11px';
    tag.style.opacity = '.75';
    tag.style.marginRight = '8px';
    field(independent ? '视差（独立）' : '视差（跟随全局）', [tag, ind]);
  }

  // 单个滑块：值从哪读、写到哪，由 source 决定
  // locked：选中了图层但没勾「独立」时，这两个滑块改的是**全局值**，
  // 会让所有跟随全局的图层一起变——所以锁住，避免"只想调这一层却动了全局"。
  const slider = (label, min, max, step, unit, get, set, locked) => {
    const row = sliderWithInput({
      min, max, step,
      value: get(),
      unit,
      display: v => Math.round(v * 100) / 100,
      toValue: v => v,
      onInput: v => { set(v); scheduleDraw(); },
      onCommit: () => scheduleDraw()
    });
    if (locked) {
      row.querySelectorAll('input').forEach(i => { i.disabled = true; i.title = '要单独调这一层，请先勾选「独立」'; });
      row.style.opacity = '.45';
    }
    field(label, [row]);
  };

  // 有图层选中但没独立时：这两项显示全局值但不可改
  const layerLocked = !!rect && !independent;

  slider('景深', 0, 100, 1, '%',
    () => (independent ? Number(rect.parallax) || 0 : cfg.depth),
    v => { if (independent) rect.parallax = Math.round(v); else setParallaxSetting('depth', v); },
    layerLocked);

  slider('振幅', 0, 12, 0.5, '%',
    () => (independent ? (Number.isFinite(rect.parallaxAmp) ? rect.parallaxAmp : cfg.amp) : cfg.amp),
    v => { if (independent) rect.parallaxAmp = v; else setParallaxSetting('amp', v); },
    layerLocked);

  if (layerLocked) {
    const tip = el('div', null, '这两项现在是全局值（灰色不可改）。要让这一层单独晃动，先勾上面的「独立」。');
    tip.style.cssText = 'font-size:11px;color:#9a9aad;padding:2px 0 6px';
    box.appendChild(tip);
  }

  // 平滑与死区是"帧级手感"，永远作用于全局（独立图层也共用）
  slider('平滑', 0.04, 0.5, 0.02, '',
    () => cfg.smoothing,
    v => setParallaxSetting('smoothing', v));

  slider('死区', 0, 0.3, 0.01, '',
    () => cfg.deadzone,
    v => setParallaxSetting('deadzone', v));

  if (!independent) return;

  const invert = document.createElement('button');
  invert.textContent = '反相';
  invert.title = '方向 +180°：这层与其它层反向移动';
  invert.onclick = () => {
    rect.parallaxAngle = (((Number(rect.parallaxAngle) || 0) + 180) % 360 + 360) % 360;
    renderInspector();
    scheduleDraw();
  };
  field('方向', [sliderWithInput({
    min: 0, max: 360, step: 5,
    value: ((Number(rect.parallaxAngle) || 0) % 360 + 360) % 360,
    unit: '°',
    display: v => Math.round(v),
    toValue: v => v,
    onInput: v => { rect.parallaxAngle = Math.round(v) % 360; scheduleDraw(); },
    onCommit: () => scheduleDraw()
  }), invert]);
}

function renderInspector() {
  const box = document.getElementById('inspector');
  box.innerHTML = '';

  const field = (label, nodes, extraClass) => {
    const f = el('div', 'field' + (extraClass ? ' ' + extraClass : ''));
    f.appendChild(el('label', null, label));
    const pair = el('div', 'pair');
    nodes.forEach(n => pair.appendChild(n));
    f.appendChild(pair);
    box.appendChild(f);
    return f;
  };

  // 视差相关的设置全部集中在最上面：全局参数 + 当前图层参数。
  // 放在一起是因为它们互相影响（改全局会影响所有"跟随全局"的层），
  // 分散到面板首尾反而难对照。
  const rect = selectedRect();

  // 全局段 + （选中时）该图层的"独立"勾选与独立参数，都在这一处渲染
  renderParallaxGlobals(box, field, rect);

  if (!rect) { box.appendChild(el('div', 'hint', '点画布上的矩形，或左栏图层')); return; }

  const clip = clipFor(rect);

  // 每个属性：[重置 ↺] [关键帧 ◇] [滑条] [数值输入]
  // 数值输入保留，滑条用于快速拖；两者写的是同一个值。
  CHANNELS.forEach((channel, index) => {
    const armed = channelArmed(clip, channel);
    const current = channelValueAt(clip, rect, channel, state.playhead);

    const reset = el('button', 'icon-btn', '↺');
    reset.title = '重置为默认值（' + RESET_LABELS[channel.key] + '）';
    reset.onclick = () => resetChannel(rect, channel);

    const diamond = el('button', 'icon-btn', armed ? '◆' : '◇');
    diamond.title = armed ? '关闭关键帧（把当前值固定为静态）' : '在播放头落一帧并开启关键帧';
    if (armed) diamond.style.cssText = 'background:#2f6b4f;border-color:#3ddc97;';
    diamond.onclick = () => toggleChannel(index);

    const nodes = [reset, diamond];

    if (channel.key === 'position') {
      nodes.push(
        numberControl(current.x, v => changeValue(rect, channel, 'x', v, true), () => renderTimeline(), 1),
        numberControl(current.y, v => changeValue(rect, channel, 'y', v, true), () => renderTimeline(), 1)
      );
    } else if (channel.key === 'size') {
      nodes.push(
        numberControl(current.width, v => changeValue(rect, channel, 'width', v, true), () => renderTimeline(), 1),
        numberControl(current.height, v => changeValue(rect, channel, 'height', v, true), () => renderTimeline(), 1)
      );
    } else if (channel.key === 'scale') {
      // 滑条与数字共用：显示 0–500%，写盘是 0–5 的倍数
      nodes.push(sliderWithInput({
        min: 0, max: 500, step: 1, value: current.scale * 100, unit: '%',
        display: v => Math.round(v),
        toValue: v => v / 100,
        onInput: v => changeValue(rect, channel, 'scale', v, true),
        onCommit: () => renderTimeline()
      }));
    } else if (channel.key === 'rotation') {
      nodes.push(sliderWithInput({
        min: -500, max: 500, step: 1, value: current.rotationZ, unit: '°',
        display: v => Math.round(v),
        toValue: v => v,
        onInput: v => changeValue(rect, channel, 'rotationZ', v, true),
        onCommit: () => renderTimeline()
      }));
    } else {
      nodes.push(sliderWithInput({
        min: 0, max: 100, step: 1, value: current.opacity * 100, unit: '%',
        display: v => Math.round(v),
        toValue: v => v / 100,
        onInput: v => changeValue(rect, channel, 'opacity', v, true),
        onCommit: () => renderTimeline()
      }));
    }

    field(channel.label, nodes);
  });

  // 非动画属性
  if (isText(rect)) {
    // 文本专属：内容、字号、对齐。宽度仍然可改（决定左/中/右对齐的基准框）
    const area = document.createElement('textarea');
    area.value = String(rect.text || '');
    area.rows = 2;
    area.style.cssText = 'flex:1;min-width:0;font:inherit;background:#171d26;color:#c9d3e0;border:1px solid #232b36;border-radius:6px;padding:4px;';
    area.oninput = () => {
      rect.text = area.value;
      syncTextBox(rect);
      scheduleDraw();
    };
    field('内容', [area]);

    field('字号 / 对齐', [
      sliderWithInput({
        min: 1, max: 60, step: 0.5, value: rect.fontSize, unit: '',
        display: v => Math.round(v * 10) / 10,
        toValue: v => v,
        onInput: v => { rect.fontSize = v; syncTextBox(rect); scheduleDraw(); },
        onCommit: () => renderAll()
      }),
      (() => {
        const sel = document.createElement('select');
        [['left', '左'], ['center', '中'], ['right', '右']].forEach(([v, label]) => {
          const o = document.createElement('option');
          o.value = v;
          o.textContent = label;
          sel.appendChild(o);
        });
        sel.value = rect.align || 'left';
        sel.style.cssText = 'flex:0 0 54px;';
        sel.onchange = () => { rect.align = sel.value; scheduleDraw(); };
        return sel;
      })()
    ]);

    field('宽度', [numberField(round1(rect.width * effectiveScale(rect)), v => { rect.width = visualToBase(v, effectiveScale(rect)); })]);

    // 文字图层同样支持 蒙版 / 圆角 / 羽化（与图片、形状同一套语义）
    maskFeatherControls(rect, field);
  } else {
    // 椭圆/正圆是同一个 shape，只靠宽高比区分。
    // 勾上「正圆」就把宽高锁在一起，避免改完边长变成一个"看不出是圆"的椭圆。
    const isEllipse = (rect.shape || 'rect') === 'ellipse';
    const lockCircle = isEllipse && Math.abs(rect.width - rect.height) < 0.5;
    const applyWidth = v => {
      const base = visualToBase(v, effectiveScale(rect));
      rect.width = base;
      if (lockCircle) rect.height = base;
    };
    const applyHeight = v => {
      const base = visualToBase(v, effectiveScale(rect));
      rect.height = base;
      if (lockCircle) rect.width = base;
    };
    // 圆角 / 蒙版 / 羽化：与文字图层共用同一套控件（形状、图片都走这里）
    maskFeatherControls(rect, field);

    if ((rect.shape || '') === 'path') {
      field('描边', [numberField(rect.strokeWidth || 0, v => {
        rect.strokeWidth = Math.max(0, v);
        renderAll(true);
        draw();
      }), el('span', 'badge', (rect.strokeWidth || 0) > 0 ? '描边' : '填充')]);
    }
    field('宽 / 高', [
      // 显示**实际大小**（基准 × 缩放），和画布上看到的一致
      numberField(round1(rect.width * effectiveScale(rect)), applyWidth),
      numberField(round1(rect.height * effectiveScale(rect)), applyHeight)
    ]);

    if (isEllipse) {
      const circleBox = document.createElement('input');
      circleBox.type = 'checkbox';
      circleBox.checked = lockCircle;
      circleBox.onchange = () => {
        rect.circleLock = circleBox.checked;
        // 勾上时把高度拉平成宽度；取消时**不动几何**，只是解锁等比
        if (rect.circleLock) rect.height = rect.width;
        renderAll(true);
        draw();
      };
      field('正圆', [circleBox, el('span', 'badge', lockCircle ? '等比锁定' : '可自由变形')]);
    }
  }

  // 形状选择（矩形/椭圆/三角/梯形/多边形/星形），图片、文本与容器没有形状
  if (!isImage(rect) && !isContainer(rect) && !isText(rect)) {
    const shapeSelect = document.createElement('select');
    SHAPES.forEach(([value, label]) => {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = label;
      shapeSelect.appendChild(option);
    });
    shapeSelect.value = rect.shape || 'rect';
    shapeSelect.onchange = () => {
      rect.shape = shapeSelect.value;
      if (rect.shape !== 'polygon' && rect.shape !== 'star') delete rect.sides;
      if (rect.shape !== 'ellipse') delete rect.circleLock;
      renderAll(true);
      draw();
    };
    const extras = [shapeSelect];
    if (rect.shape === 'polygon' || rect.shape === 'star') {
      extras.push(numberField(rect.sides || 5, v => {
        rect.sides = clamp(Math.round(v), 3, 32);
        renderAll(true);
        draw();
      }));
    }
    field('形状', extras);
  }

  const color = document.createElement('input');
  color.type = 'color';
  if (isImage(rect)) {
    field('资源', [el('span', 'badge', rect.src || '（缺失）')], 'sep');
  } else if (isContainer(rect)) {
    // 容器用 background（不是 color）：这两个字段以前没分支，
    // 导致容器的颜色框空着、改了也不生效。
    color.value = colorToInput(rect.background || '#00000000');
    color.oninput = () => {
      rect.background = mergeRgbInto(color.value, rect.background);
      renderLayers();
      draw();
    };
    field('容器背景', [color, alphaField(rect, 'background')], 'sep');

    // 裁剪开关：等价 CSS overflow: hidden
    const clipBox = document.createElement('input');
    clipBox.type = 'checkbox';
    clipBox.checked = rect.clip !== false;
    clipBox.onchange = () => { rect.clip = clipBox.checked; draw(); };
    field('裁剪溢出', [clipBox]);

    field('子元素数', [el('span', 'badge', String((rect.children || []).length))]);
  } else {
    color.value = colorToInput(rect.color);
    color.oninput = () => { rect.color = mergeRgbInto(color.value, rect.color); renderLayers(); draw(); };
    field('颜色', [color, alphaField(rect, 'color')], 'sep');
  }

  // 渐变：类型 + 角度 + 两个色标（形状/路径才有意义）
  if (!isImage(rect) && !isContainer(rect) && !isText(rect)) {
    const spec = parseGradient(rect.gradient);
    const typeSelect = document.createElement('select');
    [['', '无（纯色）'], ['linear', '线性'], ['radial', '径向']].forEach(([value, label]) => {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = label;
      typeSelect.appendChild(option);
    });
    typeSelect.value = spec ? spec.type : '';

    typeSelect.onchange = () => {
      const nextType = typeSelect.value;
      if (!nextType) {
        delete rect.gradient;
      } else {
        const angle = spec ? spec.angle : 90;
        const base = colorToHex(parseColor(rect.color || '#FF4C8BF5'));
        const start = spec ? spec.stops[0].color : base;
        const end = spec ? spec.stops[spec.stops.length - 1].color : '#FF0B0D10';
        rect.gradient = encodeGradient(nextType, angle, [
          { pos: 0, color: start },
          { pos: 1, color: end }
        ]);
      }
      renderAll(true);
      draw();
    };
    field('渐变', [typeSelect], 'sep');

    if (spec) {
      // 每个控件都**从当前数据重新解析**，不能用渲染时捕获的 spec。
      // 否则改完色标 A 再改 B 时，会用旧的 spec 重建，把 A 的修改覆盖回去
      // —— 表现就是"颜色在互相窜用"。
      const fresh = () => parseGradient(rect.gradient) || spec;

      const write = (mutate) => {
        const now = fresh();
        const stops = now.stops.map(s => ({ pos: s.pos, color: s.color }));
        mutate(stops, now);
        rect.gradient = encodeGradient(now.type, now.angle, stops);
        draw();
      };

      const angleSlider = sliderWithInput({
        min: 0, max: 360, step: 1,
        value: spec.angle,
        unit: '°',
        display: v => Math.round(v),
        toValue: v => v,
        onInput: v => {
          write((stops, now) => { now.angle = Math.round(v); });
        },
        onCommit: () => renderAll(true)
      });
      angleSlider.title = '线性渐变方向：0 = 左→右，90 = 上→下';
      field('角度', [angleSlider]);

      // 两个色标：颜色 + 不透明度（不透明度决定渐变是否"透出背景"）
      const stopRow = (index, label) => {
        const now = fresh();
        const stop = now.stops[index];
        const colorInput = document.createElement('input');
        colorInput.type = 'color';
        colorInput.value = colorToInput(stop.color);
        colorInput.oninput = () => {
          write((stops) => { stops[index].color = mergeRgbInto(colorInput.value, stops[index].color); });
        };

        const alpha = sliderWithInput({
          min: 0, max: 100, step: 1,
          value: Math.round(colorAlphaOf(stop.color) * 100),
          unit: '%',
          display: v => Math.round(v),
          toValue: v => v,
          onInput: v => {
            write((stops) => { stops[index].color = colorWithAlpha(stops[index].color, v / 100); });
          },
          onCommit: () => renderAll(true)
        });

        field(label, [colorInput, alpha]);
      };

      stopRow(0, '起色');
      stopRow(spec.stops.length - 1, '止色');
    }
  }

  const bg = document.createElement('input');
  bg.type = 'color';
  bg.value = colorToInput(state.doc.scene.root.background);
  bg.oninput = () => { state.doc.scene.root.background = mergeRgbInto(bg.value, state.doc.scene.root.background); draw(); };
    const bgAlpha = sliderWithInput({
    min: 0, max: 100, step: 1,
    value: Math.round(colorAlphaOf(state.doc.scene.root.background) * 100),
    unit: '%',
    display: v => Math.round(v),
    toValue: v => v,
    onInput: v => {
      state.doc.scene.root.background = colorWithAlpha(state.doc.scene.root.background, v / 100);
      draw();
    },
    onCommit: () => renderAll(true)
  });
  field('画布背景', [bg, bgAlpha], 'sep');
}

/** 只换 RGB，保留原有 alpha（取色器只能给 #RRGGBB）。 */
function mergeRgbInto(hexFromInput, colorValue) {
  const rgb = parseColor(hexFromInput);
  const old = parseColor(colorValue || '#FFFFFFFF');
  const a = old.a === undefined ? 1 : old.a;
  return colorToHex({ r: rgb.r, g: rgb.g, b: rgb.b, a });
}

/** 只换 alpha，保留 RGB。 */
function colorWithAlpha(colorValue, alpha) {
  const c = parseColor(colorValue || '#FFFFFFFF');
  return colorToHex({ r: c.r, g: c.g, b: c.b, a: clamp(alpha, 0, 1) });
}
/** 取一个颜色字符串的 alpha 分量（0..1）；解析失败返回 1。 */
function colorAlphaOf(colorValue) {
  const c = parseColor(colorValue || '#FFFFFFFF');
  return c.a === undefined ? 1 : c.a;
}

/**
 * 透明度滑条（0..100%），改的是颜色里的 alpha 分量。
 *
 * 只有 6 位 #RRGGBB 的取色器无法表达半透明，所以单独给一个。
 */
function alphaField(rect, prop) {
  return sliderWithInput({
    min: 0, max: 100, step: 1,
    value: Math.round(colorAlphaOf(rect[prop]) * 100),
    unit: '%',
    display: v => Math.round(v),
    toValue: v => v,
    onInput: v => {
      rect[prop] = colorWithAlpha(rect[prop], v / 100);
      scheduleDraw();
    },
    onCommit: () => renderAll()
  });
}

/**
 * 滑条 + 可编辑数字，**一个控件**。
 * 数字框就是显示值：拖滑条数字跟着变，点数字可以直接敲。避免“读数与输入框各说各话”。
 */
function sliderWithInput(opts) {
  const wrap = document.createElement('div');
  wrap.style.cssText = 'display:flex;align-items:center;gap:5px;flex:1;min-width:0;width:100%;';

  const range = document.createElement('input');
  range.type = 'range';
  range.min = String(opts.min);
  range.max = String(opts.max);
  range.step = String(opts.step);
  range.style.cssText = 'flex:1 1 auto;min-width:60px;';
  // 精度由 step 决定：step=0.02 → 2 位小数。
  // 之前这里硬编码 Math.round()，导致 0.18 / 0.04 这类小数显示成 0，
  // 而且一拖滑块就把真值写成 0（视差的平滑/死区就这样被清掉过）。
  const stepDigits = (String(opts.step).split(".")[1] || "").length;
  const fmt = v => {
    const n = Number(v);
    if (!Number.isFinite(n)) return "0";
    return String(Number(n.toFixed(Math.min(4, stepDigits))));
  };
  range.value = String(opts.value);

  const num = document.createElement('input');
  num.type = 'number';
  num.step = String(opts.step);
  num.title = '可直接输入数值';
  num.style.cssText = 'flex:0 0 62px;width:62px;';
  num.value = fmt(opts.value);

  const unit = opts.unit ? el('span', 'badge', opts.unit) : null;
  if (unit) unit.style.cssText = 'flex:0 0 auto;';

  const apply = (displayValue, fromRange) => {
    const clamped = clamp(displayValue, opts.min, opts.max);
    if (fromRange) num.value = fmt(clamped);
    else range.value = String(clamped);
    opts.onInput(opts.toValue(clamped));
  };

  range.oninput = () => apply(Number(range.value), true);
  range.onchange = () => { if (opts.onCommit) opts.onCommit(); };

  num.oninput = () => {
    const v = Number(num.value);
    if (Number.isFinite(v)) apply(v, false);
  };
  num.onchange = () => { if (opts.onCommit) opts.onCommit(); };

  wrap.appendChild(range);
  wrap.appendChild(num);
  if (unit) wrap.appendChild(unit);
  return wrap;
}

const CHANNEL_DEFAULTS = { position: { x: 0, y: 0 }, scale: { scale: 1 }, rotation: { rotationZ: 0 }, opacity: { opacity: 1 } };
// 宽高的"默认"是节点自身尺寸，在 resetChannel 里单独处理，这里只给标签
const RESET_LABELS = { position: '0, 0', scale: '100%', rotation: '0°', opacity: '100%', size: '原尺寸' };

/** 重置为默认值；已进入关键帧模式时写在当前播放头，否则改静态值。 */
function resetChannel(rect, channel) {
  // 宽高重置回"元素原本的尺寸"，比固定数字有意义
  const defaults = channel.key === 'size'
    ? { width: rect.width, height: rect.height }
    : CHANNEL_DEFAULTS[channel.key];
  for (const prop of channel.props) {
    changeValue(rect, channel, prop, defaults[prop], true);
  }
  renderAll();
}

/** 数值输入框（保留直接输入的能力）。 */
function numberControl(value, onInput, onCommit, step, suffix) {
  const input = document.createElement('input');
  input.type = 'number';
  input.step = String(step || 1);
  input.value = String(round1(value));
  input.style.flex = '1 1 0';
  input.style.minWidth = '0';
  input.oninput = () => {
    const v = Number(input.value);
    if (Number.isFinite(v)) onInput(v);
  };
  input.onchange = () => { if (onCommit) onCommit(); };
  return input;
}

function numberField(value, apply) {
  const input = document.createElement('input');
  input.type = 'number';
  input.step = '0.5';
  input.value = String(round1(value));
  input.oninput = () => {
    const v = Number(input.value);
    if (Number.isFinite(v)) { apply(v); draw(); }
  };
  return input;
}

/** 改一个属性的值：已进入关键帧模式 → 写帧；否则改静态值。 */
function changeValue(rect, channel, prop, value, deferTimeline) {
  const clip = clipFor(rect);
  const armed = channelArmed(clip, channel);
  const range = PROPERTY_RANGES[prop] || [-1e4, 1e4];
  const clamped = clamp(value, range[0], range[1]);

  if (!armed) {
    rect[prop] = round1(clamped);
    scheduleDraw();
    return;
  }

  const current = channelValueAt(clip, rect, channel, state.playhead);
  current[prop] = clamped;
  writeChannelAt(clip, rect, channel, current);
  scheduleDraw();
  if (!deferTimeline) renderTimeline();
}

/**
 * 时间轴的「动画组」下拉。
 *
 * 为什么必须先选组：一个图层的动画组是互相独立的轨道集合，
 * 不指明组的话"插帧 / 删帧 / 播放"到底改哪一组就是猜的（用户原话）。
 */
function renderClipGroupSelect(rect) {
  const sel = document.getElementById('clip-group');
  if (!sel) return;
  sel.innerHTML = '';
  const all = clipsFor(rect && rect.id);
  if (!all.length) {
    sel.appendChild(new Option(rect ? '（还没有动画组）' : '（未选中图层）', ''));
    sel.disabled = true;
    return;
  }
  sel.disabled = false;
  all.forEach((clip, index) => {
    const summary = clip.tracks && clip.tracks.length
      ? clip.tracks.map(t => {
          const ch = CHANNELS.find(c => c.props.includes(t.property));
          return ch ? ch.label : t.property;
        }).join('/')
      : '空组';
    sel.appendChild(new Option((clip.name || `动画组 ${index + 1}`) + ' · ' + summary, clip.id));
  });
  if (clipFor(rect)) sel.value = clipFor(rect).id;
}
function renderTimeline() {
  const body = document.getElementById('tl-body');
  body.innerHTML = '';
  const rect = selectedRect();
  const clip = clipFor(rect);
  document.getElementById('tl-info').textContent = `播放头 ${fmt(state.playhead, 2)}`;
  renderClipGroupSelect(rect);

  if (!clip) {
    body.appendChild(el('div', 'hint', rect
      ? '这个图层还没有动画组：点左边「＋组」新建一个，再点右上角 ◇ 开启属性关键帧'
      : '选中元素后点右上角 ◇ 开启某个属性的关键帧'));
    return;
  }

  document.getElementById('trigger').value = clip.trigger.type;
  document.getElementById('loop').value = clip.tracks[0]?.loop || 'loop';
  document.getElementById('duration').value = String(clip.tracks[0]?.durationMs || 2000);

  let hasArmed = false;

  CHANNELS.forEach((channel, chIndex) => {
    const armed = channelArmed(clip, channel);
    if (!armed) return;
    hasArmed = true;

    const row = el('div', 'tl-track');
    row.onclick = () => { state.sel.channel = chIndex; state.sel.keyIndex = -1; renderTimeline(); renderKeyframeInspector(); };
    const label = el('div', 'tl-label', channel.label + (chIndex === state.sel.channel ? ' •' : ''));
    row.appendChild(label);
    const lane = el('div', 'tl-lane');

    const track = channelTrack(clip, channel);
    track.keyframes.forEach((kf, kIndex) => {
      const dot = el('div', 'kf' + (chIndex === state.sel.channel && kIndex === state.sel.keyIndex ? ' sel' : ''));
      dot.style.left = (kf.t * 100) + '%';
      dot.title = `t=${fmt(kf.t, 2)}`;
      dot.onpointerdown = ev => {
        ev.stopPropagation();
        state.sel.channel = chIndex;
        state.sel.keyIndex = kIndex;
        const box = lane.getBoundingClientRect();
        const move = e => {
          const t = clamp((e.clientX - box.left) / box.width, 0, 1);
          for (const prop of channel.props) {
            const tr = trackFor(clip, prop);
            if (!tr) continue;
            const idx = state.sel.keyIndex;
            if (tr.keyframes[idx]) tr.keyframes[idx].t = t;
            sortTrack(tr);
          }
          state.sel.keyIndex = track.keyframes.findIndex(k => Math.abs(k.t - t) < 1e-3);
          scheduleDraw();
          renderTimeline();
        };
        const up = () => {
          window.removeEventListener('pointermove', move);
          window.removeEventListener('pointerup', up);
          renderAll();
        };
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', up);
        renderKeyframeInspector();
      };
      lane.appendChild(dot);
    });

    const head = el('div', 'playhead');
    head.style.left = (state.playhead * 100) + '%';
    lane.appendChild(head);

    lane.onpointerdown = ev => {
      const box = lane.getBoundingClientRect();
      const scrub = clientX => {
        state.playhead = clamp((clientX - box.left) / box.width, 0, 1);
        state.sel.channel = chIndex;
        renderTimeline();
        scheduleDraw();
      };
      scrub(ev.clientX);
      const move = e => scrub(e.clientX);
      const up = () => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        renderAll();
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
    };

    row.appendChild(lane);
    body.appendChild(row);
  });

  if (!hasArmed) {
    body.appendChild(el('div', 'hint', '还没有任何属性在打关键帧：在右栏点属性左边的 ◇ 即可开始'));
  }
}

function renderKeyframeInspector() {
  const box = document.getElementById('kf-inspector');
  box.innerHTML = '';
  const rect = selectedRect();
  const clip = clipFor(rect);
  const channel = channelByIndex(state.sel.channel);
  if (!rect || !clip || !channel) { box.appendChild(el('div', 'hint', '点时间轴上的菱形选中关键帧')); return; }

  const track = channelTrack(clip, channel);
  const kf = track?.keyframes[state.sel.keyIndex];
  if (!kf) { box.appendChild(el('div', 'hint', '点时间轴上的菱形选中关键帧')); return; }

  const field = (label, nodes) => {
    const f = el('div', 'field');
    f.appendChild(el('label', null, label));
    const pair = el('div', 'pair');
    nodes.forEach(n => pair.appendChild(n));
    f.appendChild(pair);
    box.appendChild(f);
  };

  const timeInput = document.createElement('input');
  timeInput.type = 'number'; timeInput.step = '0.01'; timeInput.min = '0'; timeInput.max = '1';
  timeInput.value = String(round2(kf.t));
  timeInput.onchange = () => {
    const t = clamp(Number(timeInput.value) || 0, 0, 1);
    for (const prop of channel.props) {
      const tr = trackFor(clip, prop);
      if (tr && tr.keyframes[state.sel.keyIndex]) tr.keyframes[state.sel.keyIndex].t = t;
      if (tr) sortTrack(tr);
    }
    state.sel.keyIndex = track.keyframes.findIndex(k => Math.abs(k.t - t) < 1e-3);
    renderAll();
  };
  field('时间', [timeInput]);

  const easing = document.createElement('select');
  EASING_NAMES.forEach(n => {
    const o = document.createElement('option');
    o.value = n; o.textContent = EASING_LABELS[n];
    easing.appendChild(o);
  });
  easing.value = kf.easing || 'linear';
  easing.onchange = () => {
    for (const prop of channel.props) {
      const tr = trackFor(clip, prop);
      if (tr && tr.keyframes[state.sel.keyIndex]) tr.keyframes[state.sel.keyIndex].easing = easing.value;
    }
    renderTimeline();
    drawCurvePreview();
  };
  field('缓动', [easing]);

  const curve = document.createElement('canvas');
  curve.width = 260; curve.height = 56;
  curve.style.cssText = 'width:100%;height:56px;background:#0e1218;border:1px solid #232b36;border-radius:4px;';
  box.appendChild(curve);
  const drawCurvePreview = () => {
    const c = curve.getContext('2d');
    c.clearRect(0, 0, curve.width, curve.height);
    c.strokeStyle = '#4c8bf5'; c.lineWidth = 2;
    c.beginPath();
    for (let i = 0; i <= 48; i++) {
      const u = i / 48;
      const y = curve.height - bezierEase(kf.easing || 'linear', u) * (curve.height - 8) - 4;
      const x = 4 + u * (curve.width - 8);
      i ? c.lineTo(x, y) : c.moveTo(x, y);
    }
    c.stroke();
  };
  drawCurvePreview();
}

// ---------------------------------------------------------------- 命令

function addRect() {
  const id = uniqueId('rect');
  const node = { id, type: 'rect', visible: true, x: 25, y: 40, width: 50, height: 12, color: '#FF4C8BF5', opacity: 1 };
  // 选中容器时插进容器里（子节点坐标相对容器左上角），并按容器尺寸居中、夹到容器内
  placeInTarget(node, null);
  insertTargetList().push(node);
  state.sel.rectId = id;
  state.sel.channel = -1;
  renderAll();
}

/**
 * 添加容器（类似 HTML 的 `<div>`）。
 *
 * 容器自己有框（可给背景色、可整体缩放/旋转），之后往里加的元素坐标都相对容器左上角，
 * 整组跟着容器一起动。选中容器时再点「＋」加的东西会直接进到它里面。
 */
/** 直接加一个指定形状的元素（菜单里的圆形/三角形走这里）。 */
function addShape(kind) {
  addRect();
  const node = selectedRect();
  if (!node) return;
  node.shape = kind;
  if (kind === 'ellipse' || kind === 'triangle') {
    // 圆给成正方形，三角形保持宽扁，看起来更像"图形"而不是"压扁的漂移"
    if (kind === 'ellipse') node.height = node.width;
  }
  renderAll();
  status('已添加 ' + (SHAPES.find(s => s[0] === kind) || [kind, kind])[1]);
}

function addContainer() {
  const id = uniqueId('group');
  const node = {
    id,
    type: 'container',
    visible: true,
    x: 15, y: 30, width: 60, height: 30,
    background: '#331E88E5',
    opacity: 1,
    children: []
  };
  placeInTarget(node, null);
  insertTargetList().push(node);
  state.sel.rectId = id;
  state.sel.channel = -1;
  renderAll();
  status('已添加容器 ' + id + '：选中它再点「＋」加的元素会放进容器里');
}

/** 添加文本图层。 */
function addText() {
  const id = uniqueId('text');
  const node = {
    id,
    type: 'text',
    visible: true,
    x: 10, y: 12, width: 70, height: 8,
    text: '文本',
    fontSize: 6,
    color: '#FFFFFFFF',
    align: 'left',
    opacity: 1
  };
  syncTextBox(node);
  placeInTarget(node, null);
  insertTargetList().push(node);
  state.sel.rectId = node.id;
  state.sel.channel = -1;
  renderAll();
}
function addImageFromFile(file) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    const dataUrl = String(reader.result);
    let key = file.name.replace(/[^\w.\-]/g, '_');
    let n = 1;
    while (state.doc.resources[key]) key = `${n++}_${key}`;
    state.doc.resources[key] = dataUrl;

    const probe = new Image();
    probe.onload = () => {
      const aspect = probe.naturalHeight / probe.naturalWidth;
      let i = 1;
      while (rects().some(r => r.id === `image_${i}`)) i++;
      const width = 60;
      const node = {
        id: `image_${i}`,
        type: 'image',
        visible: true,
        x: 20,
        y: 20,
        width,
        height: round1(width * aspect),
        opacity: 1,
        src: key,
        color: '#FFFFFFFF'
      };
      // 放进容器时按容器尺寸等比缩放，避免图片比容器还大、看起来"跑到外面"
      placeInTarget(node, aspect);
      insertTargetList().push(node);
      state.sel.rectId = `image_${i}`;
      state.sel.channel = -1;
      imageCache.set(key, probe);
      renderAll();
    };
    probe.src = dataUrl;
  };
  reader.readAsDataURL(file);
}

function delRect() {
  const rect = selectedRect();
  if (!rect) return;

  // 从它真正所在的列表里摘掉（可能在某个容器里，不一定是根）
  const list = parentListOf(rect.id);
  if (list) list.splice(list.indexOf(rect), 1);

  // 容器连同所有子孙的动画一起删，避免留下指向不存在元素的动画
  const doomed = new Set();
  const collect = node => {
    doomed.add(node.id);
    if (isContainer(node)) (node.children || []).forEach(collect);
  };
  collect(rect);
  state.doc.interactions.animations = clips().filter(c => !doomed.has(c.targetId));

  state.sel.rectId = null;
  state.sel.channel = -1;
  renderAll();
}



function addKeyframeAtPlayhead() {
  const rect = selectedRect();
  const clip = clipFor(rect);
  const channel = channelByIndex(state.sel.channel);
  if (!rect || !clip || !channel) { status('先在右栏用 ◇ 选一个属性'); return; }
  const cur = channelValueAt(clip, rect, channel, state.playhead);
  writeChannelAt(clip, rect, channel, cur);
  renderAll();
}

function delKeyframe() {
  const rect = selectedRect();
  const clip = clipFor(rect);
  const channel = channelByIndex(state.sel.channel);
  if (!rect || !clip || !channel) return;
  const track = channelTrack(clip, channel);
  if (!track || track.keyframes.length <= 1) { status('至少保留一个关键帧'); return; }
  track.keyframes.splice(state.sel.keyIndex, 1);
  for (const prop of channel.props) {
    const tr = trackFor(clip, prop);
    if (tr && tr !== track) tr.keyframes.splice(state.sel.keyIndex, 1);
  }
  state.sel.keyIndex = -1;
  renderAll();
}

// ---------------------------------------------------------------- 播放

let rafId = 0, playStart = 0, playFrom = 0;

function togglePlay() {
  const clip = clipFor(selectedRect());
  if (!clip) { status('选中带动画的元素才能播放'); return; }
  state.playing = !state.playing;
  const btn = document.getElementById('btn-play');
  btn.classList.toggle('on', state.playing);
  btn.textContent = state.playing ? '⏸ 暂停' : '▶ 播放';
  if (!state.playing) { cancelAnimationFrame(rafId); return; }
  playFrom = state.playhead >= 0.999 ? 0 : state.playhead;
  playStart = performance.now();
  tick();
}

function tick() {
  if (!state.playing) return;
  const clip = clipFor(selectedRect());
  if (!clip) return;
  const dur = Math.max(1, clip.tracks[0]?.durationMs || 1000);
  const loop = clip.tracks[0]?.loop || 'once';
  let p = playFrom + (performance.now() - playStart) / dur;
  if (p > 1) {
    if (loop === 'loop' || loop === 'pingpong') p = p % 1;
    else {
      p = 1; state.playing = false;
      const btn = document.getElementById('btn-play');
      btn.classList.remove('on'); btn.textContent = '▶ 播放';
    }
  }
  state.playhead = p;
  renderTimeline();
  draw();
  if (state.playing) rafId = requestAnimationFrame(tick);
}

// ---------------------------------------------------------------- 导入导出

function download(name, text) {
  const blob = new Blob([text], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}


// ================= 触发器规则校验 =================
// 引擎在加载时也会校验（带 JSON 路径），但用户不应该等到"发到手机才报错"。
// 这里做静态检查：目标/变量/函数是否存在、动作类型是否支持、变量是否有环。
const RULE_ACTION_TYPES = ['setVar', 'setVisible', 'setProperty', 'playAnimation', 'launchApp', 'openUri', 'call'];

function validateRules() {
  const out = [];
  const it = state.doc.interactions || {};
  const vars = it.vars || {};
  const rules = Array.isArray(it.rules) ? it.rules : [];
  const functions = it.functions || {};
  const nodeIds = new Set(allNodes().map(n => n.id));
  const clipIds = new Set((it.animations || []).map(c => c.id));

  const seen = new Set();
  rules.forEach((r, i) => {
    const where = `规则「${r.id || '#' + i}」`;
    if (!r.id) out.push(`${where}：缺少 id`);
    else if (seen.has(r.id)) out.push(`${where}：id 重复`);
    else seen.add(r.id);

    const when = r.when || {};
    if (when.trigger === 'tap' && when.target && !nodeIds.has(when.target)) {
      out.push(`${where}：触发目标「${when.target}」不存在`);
    }
    if (when.trigger === 'var' && when.var && !(when.var in vars)) {
      out.push(`${where}：监听的变量「${when.var}」未定义`);
    }
    // gesture 是引擎支持的通用手势触发（tap/doubleTap/longPress/swipe*），编辑器早先漏了
  if (!['tap', 'var', 'minute', 'session', 'gesture'].includes(when.trigger)) {
      out.push(`${where}：未知触发类型「${when.trigger}」`);
    }

    (r.then || []).forEach((a, k) => {
      const at = `${where} 第 ${k + 1} 个动作`;
      if (!RULE_ACTION_TYPES.includes(a.type)) {
        out.push(`${at}：未知类型「${a.type}」`);
        return;
      }
      if ((a.type === 'setVisible' || a.type === 'setProperty') && a.target && !nodeIds.has(a.target)) {
        out.push(`${at}：目标「${a.target}」不存在`);
      }
      if (a.type === 'setVar' && a.var && !(a.var in vars)) {
        out.push(`${at}：变量「${a.var}」未定义（先在变量区创建）`);
      }
      if (a.type === 'playAnimation' && a.clip && !clipIds.has(a.clip)) {
        out.push(`${at}：动画「${a.clip}」不存在`);
      }
      if (a.type === 'call' && a.call && !(a.call in functions)) {
        out.push(`${at}：函数「${a.call}」未定义`);
      }
    });
  });

  // 变量环：A 的变化规则里改 B，B 的变化规则里改 A —— 引擎会截断，但预设本身有问题
  const edges = new Map();
  rules.forEach(r => {
    if (!r.when || r.when.trigger !== 'var' || !r.when.var) return;
    const targets = (r.then || []).filter(a => a.type === 'setVar' && a.var).map(a => a.var);
    if (!edges.has(r.when.var)) edges.set(r.when.var, new Set());
    targets.forEach(t => edges.get(r.when.var).add(t));
  });
  const cyclic = new Set();
  const walk = (start, node, path) => {
    if (path.has(node)) {
      if (node === start) cyclic.add(start);
      return;
    }
    path.add(node);
    for (const next of (edges.get(node) || [])) walk(start, next, path);
    path.delete(node);
  };
  for (const start of edges.keys()) walk(start, start, new Set());
  if (cyclic.size) {
    out.push(`变量存在循环依赖：${[...cyclic].join(' ↔ ')}（引擎会截断在 8 轮，但建议改成单向）`);
  }

  return out;
}

/**
 * 关键帧自愈：同一轨道里 t 相同（或倒退）的帧会让引擎解析失败
 * （错误提示里的 "必须大于 0" 其实是"必须大于上一个 t"）。
 * 导出前统一排序 + 去重，避免坏数据推到手机。
 */
function healKeyframes() {
  const clips = clips() || [];
  let fixed = 0;
  clips.forEach(clip => (clip.tracks || []).forEach(track => {
    const list = (track.keyframes || []).slice();
    if (list.length < 2) return;
    list.sort((a, b) => a.t - b.t);
    const out = [];
    for (const kf of list) {
      const prev = out[out.length - 1];
      if (prev && Math.abs(kf.t - prev.t) < 0.015) {
        prev.value = kf.value;           // 同位置：保留后写入的值
        prev.easing = kf.easing || prev.easing;
        fixed++;
      } else {
        out.push({ ...kf, t: Math.max(0, Math.min(1, kf.t)) });
      }
    }
    track.keyframes = out;
  }));
  return fixed;
}
function pruneForExport() {
  // 导出前补齐动画组名字：默认文档没有 name，缺了它下拉里就只能显示 id
  if (typeof ensureClipNames === 'function') ensureClipNames();
  // 导出前把没有任何关键帧的空轨道去掉（手机端会忽略它，但没必要写进文件）
  const doc = clone(state.doc);

  // 触发器工作台里搭的图 → 编译成 rules 写进预设。
  // 只在真的有节点时才编译，避免空画布把已有手写规则覆盖成空数组。
  // studioManaged：曾经用工作台搭过触发器。只认这个标记而不是 nodes.length，
  // 否则节点被删空时这里会跳过编译，rules 里留下旧规则（用户实测：删了但手机上还在跑）。
  const studioManaged = doc.interactions.studioManaged === true ||
    !!(doc.interactions.studio && Array.isArray(doc.interactions.studio.nodes) &&
       doc.interactions.studio.nodes.length);
  if (typeof compileStudioGraph === 'function' && studioManaged) {
    try {
      const compiled = compileStudioGraph();
      // 绑定与规则互不依赖：即使某条规则有错（例如引用了未定义变量），
      // 「数据绑定」节点也必须照常写进预设，否则用户会莫名丢效果。
      if (Array.isArray(compiled.model.bindings) && compiled.model.bindings.length) {
        doc.interactions.bindings = compiled.model.bindings;
      }
      if (compiled.errors && compiled.errors.length) {
        console.warn('[导出] 触发器编译有错误，已跳过写入 rules：', compiled.errors);
      } else {
        doc.interactions.rules = compiled.model.rules;
        doc.interactions.vars = compiled.model.vars;
        console.log('[导出] 触发器已编译：' + compiled.model.rules.length + ' 条规则');
      }
    } catch (err) {
      console.warn('[导出] 触发器编译失败，已跳过：', err);
    }
  }

  // 有规则/变量/函数就把交互动格式写成 0.3，否则手机日志里会显示 0.1（显得版本没升级）
  const hasTriggers = !!(
    (doc.interactions.vars && Object.keys(doc.interactions.vars).length) ||
    (Array.isArray(doc.interactions.rules) && doc.interactions.rules.length) ||
    (doc.interactions.functions && Object.keys(doc.interactions.functions).length)
  );
  // 0.5 才支持 for / while：只有真的用到循环才升版本，避免旧版 App 读不了
  const usesLoops = (doc.interactions.rules || []).some(r => (r.then || []).some(a => a && (a.type === 'for' || a.type === 'while')));
  // 0.6 才支持「函数触发」与「播放一次并停在结尾」：用到才升版本
  const clipsAll = doc.interactions.animations || [];
  const usesHold = clipsAll.some(c => (c.tracks || []).some(t => t && t.loop === 'onceHold'));
  const usesFuncTrigger = clipsAll.some(c => c && c.trigger && c.trigger.type === 'func');
  // 0.7 才支持宽高动画：只有真的用了宽高轨道才标 0.7。
  // 其余情况（含 onceHold / 函数触发）仍标 0.6 —— 老版本 App 能继续读，
  // 不要因为编辑器升级就把所有预设的兼容性一起抬高。
  const usesSizeTrack = clipsAll.some(c =>
    (c.tracks || []).some(t => t && (t.property === 'width' || t.property === 'height')));
  if (usesSizeTrack) {
    doc.interactions.formatVersion = '0.7';
  } else if (usesHold || usesFuncTrigger) {
    doc.interactions.formatVersion = '0.6';
  } else if (hasTriggers) {
    doc.interactions.formatVersion = usesLoops ? '0.5' : '0.4';
  }

  // 关系图的节点偏移是纯编辑期数据，不能进预设（手机上用不到，还会白占体积）
  if (doc.interactions) delete doc.interactions.graphOffsets;
  // 手动连线的草稿节点同样属于编辑期数据
  if (doc.interactions) delete doc.interactions.drafts;
  // 工作台的节点图（nodes/edges）必须保留：
  // 之前这里把它删了，只留编译好的 rules —— 手机上没问题，
  // 但用户把导出的单文件重新导入编辑器时，触发器就"消失"了（无法再编辑）。
  // 引擎会忽略不认识的字段，所以留着不影响手机端，只是体积多一点。
  if (doc.interactions && doc.interactions.studio) {
    doc.interactions.studio.nodes = doc.interactions.studio.nodes || [];
    doc.interactions.studio.edges = doc.interactions.studio.edges || [];
  }

  doc.interactions.animations.forEach(c => {
    c.tracks = c.tracks.filter(t => t.keyframes && t.keyframes.length);
  });
  doc.interactions.animations = doc.interactions.animations.filter(c => c.tracks.length);

  // 容器是 0.2 才有的能力，导出统一用 0.2（引擎同时接受 0.1）
  doc.scene.formatVersion = '0.2';

  // circleLock 是编辑器的等比开关，手机端只看宽高；不剥掉会污染预设 JSON
  const stripEditorFields = nodes => {
    for (const n of nodes || []) {
      delete n.circleLock;
      if (isContainer(n)) stripEditorFields(n.children);
    }
  };
  stripEditorFields(doc.scene.root.children);

  // 路径节点只保留 d + viewBox，别把编辑器临时字段带出去
  const cleanPaths = nodes => {
    for (const n of nodes || []) {
      if (n.shape === 'path') {
        if (!n.path) { delete n.shape; }
        if (n.viewBox === undefined) n.viewBox = '0 0 24 24';
      } else {
        delete n.path;
        delete n.viewBox;
      }
      if (isContainer(n)) cleanPaths(n.children);
    }
  };
  cleanPaths(doc.scene.root.children);

  return doc;
}

/** 组装单文件包（导出与发送共用）。 */
/**
 * 导出前抓一张「第一页效果图」当封面。
 *
 * 手机壁纸库的卡片要能一眼看出是哪张壁纸，用资源里最大的那张图（往往是角色立绘局部）
 * 完全看不出整体效果。这里直接用编辑器自己的渲染结果，所见即所得。
 *
 * 抓图时临时清掉选中态，否则会把选中框、控制点一起拍进去。
 */
function captureCover() {
  try {
    const savedSel = state.sel;
    state.sel = { rectId: null, channel: -1, keyIndex: -1 };
    draw();
    const url = canvas.toDataURL('image/png');
    state.sel = savedSel;
    draw();
    return url;
  } catch (error) {
    console.warn('[封面] 抓取失败，退回到用资源图当封面', error);
    return null;
  }
}

function buildBundle() {
  const doc = pruneForExport();
  const resources = Object.assign({}, state.doc.resources || {});
  // 封面也作为资源一起推过去：手机端优先用它当卡片图
  const cover = captureCover();
  if (cover) resources['_cover.png'] = cover;
  return {
    formatVersion: '0.3',
    manifest: doc.manifest,
    scene: doc.scene,
    interactions: doc.interactions,
    resources,
  };
}

/**
 * 把当前预设直接 POST 给手机上的 LWPKit。
 *
 * 手机端有个接收服务（默认 8765），网页不需要任何桥接；
 * 手机 App 收到后会立刻落盘并广播让壁纸重载，用户切回桌面就是新效果。
 */
/**
 * 把用户填的东西整理成 `host:port`。
 *
 * 容错点（都是真实踩过的坑）：
 * - 手输时把 `192.168.5.10:0` 抄成 `192.168.5.10.0`
 * - 手机端曾显示过 `:0`（端口没绑定完就读了）
 * 所以：只取前四段 IPv4，端口不是有效数字或为 0 时一律用默认 8765。
 */
function normalizePhoneAddr(raw) {
  let s = String(raw || '').trim().replace(/^https?:\/\//i, '').replace(/\/.*$/, '');
  if (!s) return '';
  const ip = s.match(/(\d{1,3}(?:\.\d{1,3}){3})/);
  const host = ip ? ip[1] : s.split(':')[0];
  const portMatch = s.match(/:(\d{1,5})\s*$/);
  let port = portMatch ? Number(portMatch[1]) : 0;
  if (!port || port < 1 || port > 65535) port = 8765;
  return host ? `${host}:${port}` : '';
}

/** 依次尝试的地址：用户填的 → USB 端口转发后的本机地址。 */
function phoneCandidates() {
  const input = document.getElementById('phone-addr');
  const typed = normalizePhoneAddr(input && input.value);
  const list = [];
  if (typed) list.push(typed);
  if (!list.includes('127.0.0.1:8765')) list.push('127.0.0.1:8765');
  return list;
}

function setPhoneState(text, ok) {
  const chip = document.getElementById('phone-state');
  if (!chip) return;
  chip.textContent = text;
  chip.style.color = ok ? '#7ee2a8' : '#ff9f9f';
}

/** GET /ping 握手；连不上返回 null。 */
async function pingPhone(base) {
  // 没有 fetch / AbortController 的环境（老浏览器、无头自检）直接当作连不上，不要抛异常
  if (typeof fetch !== 'function') return null;
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timer = setTimeout(() => { if (controller) controller.abort(); }, 2500);
  const options = { cache: 'no-store' };
  if (controller) options.signal = controller.signal;
  try {
    const response = await fetch(`http://${base}/ping`, options);
    const data = await response.json();
    return data && data.ok ? data : null;
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/** 自动握手：挨个试候选地址，找到一个能通的就记住它。 */
async function autoHandshake() {
  for (const base of phoneCandidates()) {
    const info = await pingPhone(base);
    if (info) {
      state.phoneBase = base;
      const input = document.getElementById('phone-addr');
      if (input) input.value = base;
      try { localStorage.setItem('lwpkit.phoneAddr', base); } catch { /* 忽略 */ }
      setPhoneState(`已连接 ${base}`, true);
      return base;
    }
  }
  state.phoneBase = null;
  setPhoneState('未连接手机', false);
  return null;
}

/**
 * 保存对话框：命名 + 位置（下载到电脑 / 发送到手机）。
 *
 * 为什么要弹窗而不是两个按钮各管一半：用户经常"先导出一份留档，再推一份到手机"，
 * 名字与去向应该一次说清楚；重复推送同名预设时手机端会更新同一张卡片，不会堆卡片。
 */
function askSaveTarget(defaultTarget) {
  return new Promise(resolve => {
    const modal = document.getElementById('save-modal');
    const nameInput = document.getElementById('save-name');
    if (!modal || !nameInput) {
      resolve({ name: (state.doc.manifest && state.doc.manifest.name) || '我的壁纸', target: defaultTarget });
      return;
    }
    const radios = Array.from(modal.querySelectorAll('input[name="save-target"]'));
    nameInput.value = (state.doc.manifest && state.doc.manifest.name) || '我的壁纸';
    radios.forEach(r => { r.checked = r.value === defaultTarget; });
    modal.hidden = false;
    setTimeout(() => { nameInput.focus(); nameInput.select(); }, 30);

    const okBtn = document.getElementById('save-ok');
    const cancelBtn = document.getElementById('save-cancel');
    const backdrop = document.getElementById('save-modal-backdrop');

    const finish = (value) => {
      modal.hidden = true;
      okBtn.removeEventListener('click', onOk);
      cancelBtn.removeEventListener('click', onCancel);
      backdrop.removeEventListener('click', onCancel);
      document.removeEventListener('keydown', onKey);
      resolve(value);
    };
    const onOk = () => {
      const name = (nameInput.value || '').trim() || '我的壁纸';
      if (!state.doc.manifest) state.doc.manifest = {};
      state.doc.manifest.name = name;
      const target = (radios.find(r => r.checked) || {}).value || defaultTarget;
      finish({ name, target });
    };
    const onCancel = () => finish(null);
    const onKey = (event) => {
      if (event.key === 'Enter') { event.preventDefault(); onOk(); }
      if (event.key === 'Escape') { event.preventDefault(); onCancel(); }
    };

    okBtn.addEventListener('click', onOk);
    cancelBtn.addEventListener('click', onCancel);
    backdrop.addEventListener('click', onCancel);
    document.addEventListener('keydown', onKey);
  });
}

/** 统一的保存入口：先校验，再问名字与去向。 */
async function savePreset(defaultTarget) {
  if (validate().length) { status('校验未通过，先修错误'); return; }
  const choice = await askSaveTarget(defaultTarget);
  if (!choice) { status('已取消'); return; }
  if (choice.target === 'phone') { await sendBundleToPhone(); return; }
  const bundle = buildBundle();
  download(choice.name + '.lwpkit.json', JSON.stringify(bundle, null, 2));
  status('已下载 ' + choice.name + '.lwpkit.json');
}

async function sendBundleToPhone() {
  const bundle = buildBundle();
  const button = document.getElementById('btn-send-phone');
  if (button) { button.disabled = true; button.textContent = '发送中…'; }

  const done = (text, ok) => {
    status(text);
    setPhoneState(ok ? '已连接' : '发送失败', ok);
    if (button) { button.disabled = false; button.textContent = '发送到手机'; }
  };

  // 先握手，不盲目 POST：能明确告诉用户是"没连上"还是"手机拒绝了"
  const base = await autoHandshake();
  if (!base) {
    done('✗ 连不上手机。三条路：①手机 App 停在主界面（会显示地址）②电脑手机同一 Wi-Fi ③数据线时先双击 tools\\usb-forward.cmd', false);
    return;
  }

  try {
    const response = await fetch(`http://${base}/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(bundle),
    });
    const text = await response.text();
    let payload = null;
    try { payload = JSON.parse(text); } catch { /* 走兜底 */ }
    if (payload && typeof payload.ok === 'boolean') {
      done(payload.ok ? `✓ ${payload.message}` : `✗ ${payload.message}`, payload.ok);
    } else {
      done(`✗ 手机返回了意外内容（HTTP ${response.status}）`, false);
    }
  } catch (error) {
    done(`✗ 发送中断：${error.message}`, false);
  }
}

function sendToPhone() { savePreset('phone'); }
function exportBundle() { savePreset('file'); }

function export3() {
  if (validate().length) { status('校验未通过，先修错误'); return; }
  const doc = pruneForExport();
  download('manifest.json', JSON.stringify(doc.manifest, null, 2));
  setTimeout(() => download('scene.json', JSON.stringify(doc.scene, null, 2)), 120);
  setTimeout(() => download('interactions.json', JSON.stringify(doc.interactions, null, 2)), 240);
}

/**
 * 补齐"引擎有缺省值、编辑器各条路径却依赖"的字段。
 *
 * 为什么需要：手写或第三方导出的预设经常只写必要字段（没有 visible/scale/align 等）。
 * 引擎按缺省值渲染没问题，但编辑器里"图层列表、绘制、命中、属性面板"各自读字段，
 * 少一个就会表现成空白画布 / 点不中（用户实测：手机上正常，导入编辑器全黑）。
 */
function normalizeScene(scene) {
  if (!scene || !scene.root) return;
  const blow = (n) => {
    if (!n) return;
    if (n.visible === undefined) n.visible = true;
    if (n.scale === undefined) n.scale = 1;
    if (n.rotationZ === undefined) n.rotationZ = 0;
    if (n.opacity === undefined) n.opacity = 1;
    if (n.type === 'text') {
      if (n.align === undefined) n.align = 'left';
      if (n.fontSize === undefined) n.fontSize = 6;
    } else if (n.type === 'rect') {
      if (n.shape === undefined) n.shape = 'rect';
      if (n.cornerRadius === undefined) n.cornerRadius = 0;
      if (n.feather === undefined) n.feather = 0;
    } else if (n.type === 'image') {
      if (n.cornerRadius === undefined) n.cornerRadius = 0;
      if (n.feather === undefined) n.feather = 0;
    } else if (n.type === 'container') {
      if (n.clip === undefined) n.clip = !n.id || n.id !== 'root';
    }
    (n.children || []).forEach(blow);
  };
  blow(scene.root);
}

function importJson(text) {
  let data;
  try { data = JSON.parse(text); } catch { status('不是合法 JSON'); return; }
  if (data && data.scene) {
    state.doc.manifest = data.manifest || state.doc.manifest;
    state.doc.scene = data.scene;
    state.doc.interactions = data.interactions || { formatVersion: '0.1', animations: [] };
    state.doc.resources = data.resources || {};
    imageCache.clear();
  } else if (data && data.root) state.doc.scene = data;
  else if (data && data.animations) state.doc.interactions = data;
  else if (data && data.engineMinVersion) state.doc.manifest = data;
  else { status('无法识别：需要预设包 / scene / interactions / manifest'); return; }

  normalizeScene(state.doc.scene);

  // 旧文件的 interactions.bindings 要转成「数据绑定」节点，否则面板已隐藏、用户再也看不到
  const migrated = migrateLegacyBindings(state.doc);

  ensureClipNames();
  state.sel = { rectId: null, channel: -1, keyIndex: -1 };
  validate();
  renderAll();
  status(migrated > 0 ? ('已导入，并把 ' + migrated + ' 条旧绑定转成了触发器节点') : '已导入');
}

// ---------------------------------------------------------------- 校验

function validate() {
  const errors = [];
  const ids = new Set();
  for (const r of allNodes()) {
    if (!r.id) errors.push('有元素缺少 id');
    if (ids.has(r.id)) errors.push(`元素 id 重复：${r.id}`);
    ids.add(r.id);
    if (!(r.width > 0) || !(r.height > 0)) errors.push(`${r.id}：宽高必须大于 0`);
    const colorPattern = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
    if (isContainer(r)) {
      // 容器用 background（允许全透明 #00000000）
      if (r.background && !colorPattern.test(r.background)) errors.push(`${r.id}：容器背景色格式错误`);
    } else if (r.type !== 'image' && !colorPattern.test(r.color || '')) {
      errors.push(`${r.id}：颜色格式错误`);
    }
    if (r.opacity !== undefined && (r.opacity < 0 || r.opacity > 1)) errors.push(`${r.id}：透明度需在 0..1`);
  }
  for (const c of clips()) {
    if (!ids.has(c.targetId)) errors.push(`动画 ${c.id} 的目标 ${c.targetId} 不存在`);
    const seen = new Set();
    for (const t of c.tracks || []) {
      if (!ALL_PROPS.includes(t.property)) errors.push(`动画 ${c.id}：未知属性 ${t.property}`);
      if (seen.has(t.property)) errors.push(`动画 ${c.id}：属性 ${t.property} 重复`);
      seen.add(t.property);
      if (!(t.durationMs > 0)) errors.push(`动画 ${c.id}：时长必须大于 0`);
      let prev = -1;
      for (const k of t.keyframes || []) {
        if (k.t <= prev) errors.push(`动画 ${c.id}/${t.property}：关键帧时间必须严格递增`);
        if (k.t < 0 || k.t > 1) errors.push(`动画 ${c.id}/${t.property}：时间需在 0..1`);
        prev = k.t;
      }
    }
  }
  // 触发器规则的静态检查必须在渲染与 return 之前并入，否则报错既不显示也不返回
  const ruleErrors = validateRules();
  ruleErrors.forEach(e => errors.push(e));

  state.errors = errors;
  renderStatusBox();
  return errors;
}

// ---------------------------------------------------------------- 入口

// ---------------------------------------------------------------- 变量 / 函数面板

/** 拿到当前应该接收插入的文本控件。 */
function currentTextTarget() {
  const area = state.activeTextarea;
  if (area && document.activeElement === area) return area;
  return null;
}

function updateInsertTarget() {
  const label = document.getElementById('insert-target');
  if (!label) return;
  const rect = selectedRect();
  if (isText(rect)) label.textContent = `插入目标：${rect.id}`;
  else label.textContent = '插入目标：未选中文本';
}

/** 把占位符插到光标处；没有聚焦的输入框就追加到选中文本图层末尾。 */
function insertPlaceholder(text) {
  const area = currentTextTarget();
  if (area) {
    const start = area.selectionStart ?? area.value.length;
    const end = area.selectionEnd ?? start;
    area.value = area.value.slice(0, start) + text + area.value.slice(end);
    const caret = start + text.length;
    area.selectionStart = caret;
    area.selectionEnd = caret;
    area.dispatchEvent(new Event('input'));
    area.focus();
    return true;
  }

  const rect = selectedRect();
  if (isText(rect)) {
    rect.text = (rect.text || '') + text;
    syncTextBox(rect);
    renderAll();
    return true;
  }

  status('先在画布上选中一个文本图层，或点开它的「内容」输入框');
  return false;
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    status(`已复制：${text}`);
  } catch {
    status('复制失败：浏览器拒绝了剪贴板访问');
  }
}

function renderPalette() {
  const list = document.getElementById('var-list');
  const cats = document.getElementById('var-cats');
  if (!list || typeof CATALOG === 'undefined') return;

  updateInsertTarget();

  const categories = ['all', ...new Set(CATALOG.map(x => x.category))];
  cats.innerHTML = '';
  categories.forEach(cat => {
    const chip = el('button', 'cat-chip' + ((state.varCategory || 'all') === cat ? ' on' : ''), cat === 'all' ? '全部' : cat);
    chip.onclick = () => {
      state.varCategory = cat;
      renderPalette();
    };
    cats.appendChild(chip);
  });

  const q = String(state.varSearch || '').trim().toLowerCase();
  const items = CATALOG.filter(item => {
    if ((state.varCategory || 'all') !== 'all' && item.category !== state.varCategory) return false;
    if (!q) return true;
    return (item.key + item.desc + item.signature + item.category + item.placeholder)
      .toLowerCase()
      .includes(q);
  });

  list.innerHTML = '';
  if (!items.length) {
    list.appendChild(el('div', 'hint', '没有匹配的变量或函数'));
    return;
  }

  for (const item of items) {
    const card = el('div', 'fn-card' + (item.implemented ? '' : ' disabled'));

    const head = el('div', 'fn-head');
    head.appendChild(el('b', null, item.key));
    head.appendChild(el('span', 'fn-chip', item.category));
    head.appendChild(el('span', 'spacer'));
    head.appendChild(el('span', 'badge', item.signature));
    card.appendChild(head);

    card.appendChild(el('div', 'fn-desc', item.desc + (item.implemented ? '' : '（引擎尚未实现）')));
    card.appendChild(el('code', 'fn-code', item.placeholder));

    const actions = el('div', 'fn-actions');
    const insert = el('button', 'primary', '插入');
    insert.disabled = !item.implemented;
    insert.onclick = () => insertPlaceholder(item.placeholder);
    const copy = el('button', null, '复制');
    copy.onclick = () => copyText(item.placeholder);
    actions.appendChild(insert);
    actions.appendChild(copy);
    card.appendChild(actions);

    list.appendChild(card);
  }
}
// ---------------------------------------------------------------- 图标库

let iconCatalogCache = null;

/** 图标目录（首次打开时拉一次）。 */
async function loadIconCatalog() {
  if (iconCatalogCache) return iconCatalogCache;
  const response = await fetch('icons/catalog.json', { cache: 'no-store' });
  iconCatalogCache = await response.json();
  return iconCatalogCache;
}

function iconColor() {
  const sel = document.getElementById('icon-color');
  return (sel && sel.value) || '#FFFFFF';
}

/** 把 SVG 光栅化成 PNG data URL。手机端 BitmapFactory 只认 PNG，所以必须走这一步。 */
async function svgToPngDataUrl(svgText, size) {
  const img = new Image();
  const url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svgText);
  await new Promise((resolve, reject) => {
    img.onload = resolve;
    img.onerror = () => reject(new Error('SVG 解码失败'));
    img.src = url;
  });
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext('2d');
  context.clearRect(0, 0, size, size);
  context.drawImage(img, 0, 0, size, size);
  return canvas.toDataURL('image/png');
}

/** 插入一个内置图标：转成 PNG 资源 + 新建图片图层。 */
async function insertIcon(name, label) {
  const color = iconColor();
  try {
    const response = await fetch(`icons/svg/${name}.svg`, { cache: 'no-store' });
    // Tabler 是 stroke="currentColor"，Simple Icons 是 fill="currentColor"；
    // 再给根节点补一个 fill，没写 fill 的路径也能继承到颜色
    const svg = (await response.text())
      .replace(/currentColor/g, color)
      .replace('<svg ', `<svg fill="${color}" `);
    const png = await svgToPngDataUrl(svg, 256);

    const key = `icon_${name}_${color.replace('#', '').toLowerCase()}.png`;
    state.doc.resources[key] = png;

    let i = 1;
    while (rects().some(r => r.id === `icon_${i}`)) i++;
    insertTargetList().push({
      id: `icon_${i}`,
      type: 'image',
      visible: true,
      x: 30,
      y: 30,
      width: 18,
      height: 18,
      src: key,
      opacity: 1,
    });
    state.sel.rectId = `icon_${i}`;
    state.sel.channel = -1;
    closeIconPicker();
    renderAll();
    status(`已插入图标：${label || name}（${color}）`);
  } catch (error) {
    status(`插入图标失败：${error.message}`);
  }
}

async function renderIconGrid() {
  const grid = document.getElementById('icon-grid');
  if (!grid) return;
  grid.innerHTML = '';

  const catalog = await loadIconCatalog();
  const query = (state.iconSearch || '').trim().toLowerCase();
  const kindSel = document.getElementById('icon-kind');
  const kind = (kindSel && kindSel.value) || 'all';
  const list = catalog.icons.filter(item => {
    if (kind !== 'all' && (item.kind || 'system') !== kind) return false;
    return !query || item.name.toLowerCase().includes(query) || (item.label || '').includes(query);
  });

  if (!list.length) {
    grid.appendChild(el('div', 'hint', '没有匹配的图标'));
    return;
  }

  const color = iconColor();
  for (const item of list) {
    const cell = el('button', 'icon-cell');
    cell.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:4px;padding:8px 4px;background:#171d26;border:1px solid #232b36;border-radius:8px;cursor:pointer;';
    const img = document.createElement('img');
    img.src = `icons/svg/${item.name}.svg`;
    img.width = 32;
    img.height = 32;
    // SVG 里是 currentColor（默认黑），深色面板上要反色才看得见
    img.style.cssText = 'filter:' + (color === '#2B2B2B' ? 'none' : 'invert(1)') + ';';
    cell.appendChild(img);
    cell.appendChild(el('span', 'badge', item.label || item.name));
    cell.title = `${item.label || item.name}（${item.name}）`;
    cell.onclick = () => insertIcon(item.name, item.label);
    grid.appendChild(cell);
  }
}

function openIconPicker() {
  const picker = document.getElementById('icon-picker');
  if (!picker) return;
  picker.style.display = 'flex';
  renderIconGrid();
}

function closeIconPicker() {
  const picker = document.getElementById('icon-picker');
  if (picker) picker.style.display = 'none';
}
// ---------------------------------------------------------------- 交互（触摸事件）

const GESTURES = [
  ['tap', '单击'],
  ['doubleTap', '双击'],
  ['longPress', '长按'],
  ['swipeLeft', '左滑'],
  ['swipeRight', '右滑'],
  ['swipeUp', '上滑'],
  ['swipeDown', '下滑'],
];

/**
 * 动作目录。implementation 标记很重要：
 * 只有引擎真的会执行的动作才允许选，其余标灰 —— 避免做出"点了没反应"的模板。
 */
const ACTIONS = [
  { value: 'playAnimation', label: '播放动画', implemented: true },
  { value: 'toggleVisible', label: '切换可见', implemented: true },
  { value: 'launchApp', label: '启动应用程序', implemented: true },
  { value: 'openUri', label: '打开链接 / 启动活动', implemented: true },
  { value: 'switchGlobal', label: '全局开关切换', implemented: false },
  { value: 'launchShortcut', label: '启动快捷方式', implemented: false },
  { value: 'mediaControl', label: '音乐控制', implemented: false },
  { value: 'volumeControl', label: '音量调节', implemented: false },
  { value: 'triggerFlow', label: '触发器流程', implemented: false },
];

/** 常用应用快捷按钮：点一下就把包名/URI 填进动作里。 */
const QUICK_APPS = [
  { label: '微信', type: 'launchApp', package: 'com.tencent.mm' },
  { label: '支付宝', type: 'launchApp', package: 'com.eg.android.AlipayGphone' },
  { label: '设置', type: 'launchApp', package: 'com.android.settings' },
  { label: '相机', type: 'launchApp', package: 'com.android.camera' },
  { label: '图库', type: 'launchApp', package: 'com.miui.gallery' },
  { label: '音乐', type: 'launchApp', package: 'com.miui.player' },
  { label: '时钟', type: 'launchApp', package: 'com.android.deskclock' },
  { label: '计算器', type: 'launchApp', package: 'com.miui.calculator' },
  { label: '浏览器', type: 'openUri', uri: 'https://www.baidu.com' },
  { label: '电话', type: 'openUri', uri: 'tel:' },
];

function eventsOf() {
  const doc = state.doc.interactions;
  if (!Array.isArray(doc.events)) doc.events = [];
  return doc.events;
}

function defaultActionFor(type, rect) {
  const clip = clipFor(rect);
  if (type === 'playAnimation') {
    return { type, clipId: clip ? clip.id : (clips()[0] ? clips()[0].id : '') };
  }
  if (type === 'toggleVisible') {
    return { type, targetId: rect ? rect.id : (rects()[0] ? rects()[0].id : '') };
  }
  if (type === 'launchApp') return { type, package: 'com.tencent.mm' };
  if (type === 'openUri') return { type, uri: 'https://' };
  return { type };
}

function addEvent(kind) {
  const rect = selectedRect();
  const list = eventsOf();
  let i = 1;
  while (list.some(e => e.id === `ev_${i}`)) i++;
  const type = kind || 'playAnimation';
  list.push({
    id: `ev_${i}`,
    targetId: rect ? rect.id : undefined,
    gesture: 'tap',
    action: defaultActionFor(type, rect),
  });
  renderAll();
}

function eventSummary(event) {
  const gesture = (GESTURES.find(g => g[0] === event.gesture) || ['', event.gesture])[1];
  const action = event.action || {};
  const label = (ACTIONS.find(a => a.value === action.type) || { label: action.type }).label;
  if (action.type === 'playAnimation') return `${gesture} → ${label} ${action.clipId}`;
  if (action.type === 'toggleVisible') return `${gesture} → ${label} ${action.targetId}`;
  if (action.type === 'launchApp') return `${gesture} → ${label} ${action.package}`;
  if (action.type === 'openUri') return `${gesture} → ${label} ${action.uri}`;
  return `${gesture} → ${label}`;
}

function fieldRow(label, node) {
  const row = el('div', 'field');
  row.appendChild(el('label', null, label));
  row.appendChild(node);
  return row;
}

function textInput(value, onChange, placeholder) {
  const input = document.createElement('input');
  input.type = 'text';
  input.value = value || '';
  if (placeholder) input.placeholder = placeholder;
  input.oninput = () => onChange(input.value);
  return input;
}

function renderEventParams(event, card) {
  const action = event.action || {};

  if (action.type === 'playAnimation') {
    const sel = document.createElement('select');
    clips().forEach(c => {
      const o = document.createElement('option');
      o.value = c.id;
      o.textContent = `${c.id}（${c.targetId}）`;
      sel.appendChild(o);
    });
    if (!clips().length) {
      const o = document.createElement('option');
      o.value = '';
      o.textContent = '（还没有动画）';
      sel.appendChild(o);
    }
    sel.value = action.clipId || '';
    sel.onchange = () => { action.clipId = sel.value; renderEvents(); };
    card.appendChild(fieldRow('动画', sel));
    return;
  }

  if (action.type === 'toggleVisible') {
    const sel = document.createElement('select');
    rects().forEach(r => {
      const o = document.createElement('option');
      o.value = r.id;
      o.textContent = r.id;
      sel.appendChild(o);
    });
    sel.value = action.targetId || '';
    sel.onchange = () => { action.targetId = sel.value; renderEvents(); };
    card.appendChild(fieldRow('目标', sel));
    return;
  }

  if (action.type === 'launchApp') {
    const grid = el('div', 'app-grid');
    QUICK_APPS.forEach(app => {
      const btn = el('button', 'small', app.label);
      btn.onclick = () => {
        if (app.type === 'launchApp') {
          event.action = { type: 'launchApp', package: app.package };
        } else {
          event.action = { type: 'openUri', uri: app.uri };
        }
        renderAll();
      };
      grid.appendChild(btn);
    });
    card.appendChild(grid);
    card.appendChild(fieldRow('包名', textInput(action.package, v => {
      action.package = v.trim();
      renderEvents();
    }, 'com.tencent.mm')));
    return;
  }

  if (action.type === 'openUri') {
    card.appendChild(fieldRow('URI', textInput(action.uri, v => {
      action.uri = v.trim();
      renderEvents();
    }, 'https:// 或 android-app://包名')));
    return;
  }
}

function renderEvents() {
  // 容器 id 在 HTML 里叫 event-list，这里两个都认：
  // 之前只找 #events，找不到就 return，于是"点添加没反应"——数据进去了、界面不刷新。
  const box = document.getElementById('event-list') || document.getElementById('events');
  if (!box) return;
  box.innerHTML = '';

  // 点击穿透：全屏遮罩/装饰层必须勾上，否则会把所有点击吃掉（菜单点不动）
  const sel = selectedRect();
  if (sel) {
    const row = el('div', 'row');
    const label = el('label', null, '点击穿透（不参与命中）');
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = !!sel.hitThrough;
    cb.onchange = () => {
      if (cb.checked) sel.hitThrough = true; else delete sel.hitThrough;
      scheduleDraw();
      status(cb.checked ? '已设为点击穿透：该层不再拦截点击' : '已取消点击穿透');
    };
    row.appendChild(cb);
    row.appendChild(label);
    box.appendChild(row);
  }

  renderEventList(box);
}

function renderEventList(box) {
  box.innerHTML = '';

  const rect = selectedRect();
  const list = eventsOf();
  const visible = list.filter(e => !e.targetId || (rect && e.targetId === rect.id));

  if (!visible.length) {
    box.appendChild(el('div', 'hint', rect
      ? '这个元素还没有触摸事件。点上面的按钮添加。'
      : '选中一个元素后可给它加触摸事件；不选元素则是全屏事件。'));
    return;
  }

  for (const event of visible) {
    const card = el('div', 'fn-card');

    const head = el('div', 'fn-head');
    head.appendChild(el('b', null, event.id));
    head.appendChild(el('span', 'fn-chip', event.targetId ? event.targetId : '全屏'));
    head.appendChild(el('span', 'spacer'));
    const del = el('button', 'danger', '删除');
    del.onclick = () => {
      state.doc.interactions.events = list.filter(e => e !== event);
      renderAll();
    };
    head.appendChild(del);
    card.appendChild(head);

    const gestureSel = document.createElement('select');
    GESTURES.forEach(([value, label]) => {
      const o = document.createElement('option');
      o.value = value;
      o.textContent = label;
      gestureSel.appendChild(o);
    });
    gestureSel.value = event.gesture;
    gestureSel.onchange = () => { event.gesture = gestureSel.value; renderEvents(); };
    card.appendChild(fieldRow('手势', gestureSel));

    const actionSel = document.createElement('select');
    ACTIONS.forEach(a => {
      const o = document.createElement('option');
      o.value = a.value;
      o.textContent = a.implemented ? a.label : `${a.label}（暂未实现）`;
      o.disabled = !a.implemented;
      actionSel.appendChild(o);
    });
    actionSel.value = event.action?.type || 'playAnimation';
    actionSel.onchange = () => {
      event.action = defaultActionFor(actionSel.value, rect);
      renderAll();
    };
    card.appendChild(fieldRow('动作', actionSel));

    renderEventParams(event, card);
    card.appendChild(el('div', 'fn-desc', eventSummary(event)));
    box.appendChild(card);
  }
}
/** 只更新图层列表的选中高亮，不重建 DOM。 */
function highlightSelectedLayer(selectedId) {
  document.querySelectorAll('#layer-list .row').forEach(row => {
    const name = row.querySelector('.name');
    const rowId = name ? name.textContent : null;
    row.classList.toggle('sel', rowId === selectedId);
  });
}

/**
 * 重绘界面。
 *
 * keepLayers = true 时不重建左侧图层列表：单击选中若重建 DOM，
 * 第一次点击的节点就被销毁，浏览器永远等不到"双击同一元素"，改名会失效。
 */
// ---------------------------------------------------------------- 绑定（数据 → 图层）
//
// 与"规则 + 定时触发"的区别：绑定是**声明式映射**，引擎在变量变化时直接算出属性值，
// 不需要轮询、没有 1 秒延迟、数据不变时不重绘。适合指针、进度条、太阳轨迹这类
// "连续跟随数值"的效果；离散的状态切换仍然用规则。
//
// 只支持可动画的五个属性（与 RenderThread.SUPPORTED_RULE_PROPS 一致）。

const BIND_PROPS = [
  ['x', 'x（横向位置）'], ['y', 'y（纵向位置）'],
  ['scale', '缩放'], ['rotationZ', '旋转'], ['opacity', '透明度'],
  ['width', '宽度'], ['height', '高度']
];
// 数据源：值是引擎变量名，标签是中文说明
const BIND_VARS = [
  ['sys.battery.level', '电量 %（0-100）'],
  ['sys.battery.temp', '电池温度 °C（约 20-45）'],
  ['sys.battery.charging', '是否充电（0/1）'],
  ['sys.notify.count', '未读通知数（0 起）'],
  // 引擎里这两个变量的真名是 usedPct；旧版编辑器写的是 sys.mem.used / sys.storage.used，
  // 绑定会静默失效，这里一并修正
  ['sys.mem.usedPct', '内存占用 %（0-100）'],
  ['sys.storage.usedPct', '存储占用 %（0-100）'],
  ['sys.time.hour', '小时（0-23）'],
  ['sys.time.minute', '分钟（0-59）'],
  ['sys.media.progress', '音乐进度 %（0-100）'],
  ['sys.media.position', '播放位置（秒）'],
  ['sys.media.duration', '歌曲总长（秒）'],
  ['sys.media.playing', '是否正在播放（0/1）'],
  ['sys.audio.level', '音频总音量（0-1）'],
  ['sys.audio.bass', '低频能量（0-1，鼓点最敏感）'],
  ['sys.audio.mid', '中频能量（0-1，人声）'],
  ['sys.audio.treble', '高频能量（0-1）'],
  ['sys.audio.beat', '节拍脉冲（0-1，每拍置 1 后衰减）'],
  ['sys.audio.b1', '频谱 1（最低频）'],
  ['sys.audio.b2', '频谱 2'],
  ['sys.audio.b3', '频谱 3'],
  ['sys.audio.b4', '频谱 4'],
  ['sys.audio.b5', '频谱 5'],
  ['sys.audio.b6', '频谱 6'],
  ['sys.audio.b7', '频谱 7'],
  ['sys.audio.b8', '频谱 8（最高频）'],
  ['sys.weather.temp', '气温 °C（约 -20..40）'],
  ['sys.weather.feelsLike', '体感温度 °C'],
  ['sys.weather.humidity', '湿度 %（0-100）'],
  ['sys.weather.wind', '风速 km/h'],
  ['sys.weather.precip', '降水量 mm'],
  ['sys.weather.isDay', '是否白天（0/1）'],
  ['sys.weather.code', '天气代码（WMO）'],
  ['sys.weather.updated', '距上次更新分钟数']
];

function bindings() {
  // 兼容早期扁平字段（inMin/inMax/outMin/outMax）→ 数组，保证老数据能继续用
  (state.doc.interactions.bindings || []).forEach(b => {
    if (Array.isArray(b.in) && Array.isArray(b.out)) return;
    const a = [Number(b.inMin ?? 0), Number(b.inMax ?? 100)];
    const c = [Number(b.outMin ?? 0), Number(b.outMax ?? 100)];
    b.in = a; b.out = c;
    delete b.inMin; delete b.inMax; delete b.outMin; delete b.outMax;
  });
  const it = state.doc.interactions;
  if (!Array.isArray(it.bindings)) it.bindings = [];
  return it.bindings;
}

function addBinding() {
  const rect = selectedRect() || allNodes()[0];
  if (!rect) { status('先选中一个元素'); return; }
  bindings().push({
    id: 'b_' + Date.now().toString(36),
    targetId: rect.id,
    property: 'y',
    source: 'sys.battery.level',
    // 引擎字段：in/out 都是 [min,max] 数组（InteractionsParser.parsePair）
    in: [0, 100],
    out: [70, 0],
    enabled: true
  });
  renderAll();
  status('已新建绑定：' + rect.id);
}

function renderBindings() {
  const box = document.getElementById('binding-list');
  if (!box) return;
  const addBtn = document.getElementById('btn-add-binding');
  if (addBtn && !addBtn.dataset.wired) {
    addBtn.dataset.wired = '1';
    addBtn.addEventListener('click', addBinding);
  }

  // 工作台里已经有「数据绑定」节点时，右侧这块只作只读参考：
  // 两处同时改同一个属性会互相覆盖，导出以节点为准。
  const hasBindNodes = ((state.doc.interactions.studio || {}).nodes || []).some(n => n.type === 'bind');
  if (addBtn) addBtn.disabled = hasBindNodes;

  const list = bindings();
  box.innerHTML = '';
  if (hasBindNodes) {
    const note = document.createElement('div');
    note.style.cssText = 'font-size:11px;color:#d9b26a;padding:2px 0 6px';
    note.textContent = '已在触发器里用「数据绑定」节点管理（这里只读，导出以节点为准）。';
    box.appendChild(note);
  }
  if (!list.length) {
    const tip = document.createElement('div');
    tip.style.cssText = 'color:#8b8b9a;font-size:12px;padding:2px 0';
    tip.textContent = hasBindNodes ? '在触发器工作台里拖一个「数据绑定」节点来配置。'
      : '还没有绑定。选中元素后点「+ 新建绑定」。';
    box.appendChild(tip);
    return;
  }

  list.forEach((b, index) => {
    const card = document.createElement('div');
    card.style.cssText = 'border:1px solid #2c2c38;border-radius:8px;padding:6px;margin-bottom:6px;background:#15151d';

    // 第一行：图层 + 属性
    const row1 = document.createElement('div');
    row1.style.cssText = 'display:flex;gap:6px;align-items:center';
    const layerSel = document.createElement('select');
    layerSel.style.cssText = 'flex:1;min-width:0';
    allNodes().forEach(n => layerSel.appendChild(new Option(n.id, n.id)));
    layerSel.value = b.targetId;
    layerSel.onchange = () => { b.targetId = layerSel.value; renderAll(); };
    const propSel = document.createElement('select');
    BIND_PROPS.forEach(([v, label]) => propSel.appendChild(new Option(label, v)));
    propSel.value = b.property;
    propSel.onchange = () => { b.property = propSel.value; renderAll(); };
    const del = document.createElement('button');
    del.textContent = '删';
    del.onclick = () => { list.splice(index, 1); renderAll(); };
    row1.append(layerSel, propSel, del);

    // 第二行：数据源
    const row2 = document.createElement('div');
    row2.style.cssText = 'display:flex;gap:6px;align-items:center;margin-top:4px';
    const srcSel = document.createElement('select');
    srcSel.style.cssText = 'flex:1;min-width:0';
    const keys = BIND_VARS.map(x => x[0]);
    const known = keys.includes(b.source) ? BIND_VARS : [[b.source, b.source]].concat(BIND_VARS);
    known.forEach(([v, label]) => srcSel.appendChild(new Option(label + '  ' + v, v)));
    srcSel.value = b.source;
    srcSel.onchange = () => { b.source = srcSel.value; renderAll(); };
    row2.append(srcSel);

    // 第三行：输入/输出范围
    const row3 = document.createElement('div');
    row3.style.cssText = 'display:flex;gap:4px;align-items:center;margin-top:4px;font-size:11px;color:#9a9aad';
    const num = (val, onInput) => {
      const i = document.createElement('input');
      i.type = 'number';
      i.value = val;
      i.style.cssText = 'width:56px';
      i.oninput = () => { const n = Number(i.value); if (Number.isFinite(n)) onInput(n); };
      return i;
    };
    row3.append(
      document.createTextNode('输入'),
      num(b.in[0], v => { b.in[0] = v; }),
      num(b.in[1], v => { b.in[1] = v; }),
      document.createTextNode('输出'),
      num(b.out[0], v => { b.out[0] = v; }),
      num(b.out[1], v => { b.out[1] = v; })
    );

    card.append(row1, row2, row3);
    box.appendChild(card);
  });
}

function renderAll(keepLayers) {
  if (!keepLayers) renderLayers();
  renderInspector();
  renderTimeline();
  renderKeyframeInspector();
  renderPalette();
  renderEvents();
  renderBindings();
  validate();
  document.getElementById('stat').textContent =
    `${rects().length} 个元素 · ${clips().length} 条动画`;
  draw();

}

/**
 * 显示一条临时提示。
 *
 * 之前直接写进 #errors，但每次 renderAll/validate 都会清空它 ——
 * 「发送到手机」的成功提示常常一闪就没了（实测踩到）。
 * 现在记在 state.statusMsg 里，几秒内的校验渲染会把它一起画出来。
 */
/**
 * 选中某个图层并让它在预览上闪一下。
 *
 * 工作台的图层库靠它把"你点的是哪一层"直接指出来 ——
 * 只写名字的话，用户还得自己在预览里找一遍。
 */
function flashLayer(id, ms) {
  if (!id) return false;
  const node = findNode(id);
  if (!node) return false;
  state.sel = { rectId: id, channel: -1, keyIndex: -1 };
  state.flashLayer = { id, start: Date.now(), until: Date.now() + (ms || 1200) };
  // 画布是按需重绘的：闪的过程中手动多刷几帧，否则只会亮一下就停
  const tick = () => {
    const f = state.flashLayer;
    if (!f || Date.now() > f.until) {
      state.flashLayer = null;
      if (typeof draw === 'function') draw();
      return;
    }
    if (typeof draw === 'function') draw();
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
  if (typeof renderAll === 'function') renderAll(true);
  return true;
}
function status(msg) {
  state.statusMsg = { text: msg, until: Date.now() + 6000 };
  renderStatusBox();
  clearTimeout(status.timer);
  status.timer = setTimeout(renderStatusBox, 6200);
}

/** 重画 #errors：校验错误 + 还没过期的临时提示。 */
function renderStatusBox() {
  const box = document.getElementById('errors');
  if (!box) return;
  box.innerHTML = '';
  const errors = state.errors || [];
  if (!errors.length) box.appendChild(el('div', 'hint', '✓ 通过校验'));
  else errors.forEach(e => box.appendChild(el('div', null, '· ' + e)));
  const msg = state.statusMsg;
  if (msg && Date.now() < msg.until) {
    box.appendChild(el('div', 'status-line', msg.text));
  }
}



document.getElementById('var-search').oninput = ev => {
  state.varSearch = ev.target.value;
  renderPalette();
};


// ---------------------------------------------------------------- 动画组：选组 / 新建 / 改名 / 删除
document.getElementById('clip-group').onchange = ev => {
  state.sel.groupId = ev.target.value || null;
  state.sel.channel = -1;
  state.sel.keyIndex = -1;
  renderTimeline();
  renderKeyframeInspector();
  renderAll(true);
};

document.getElementById('btn-group-new').onclick = () => {
  const rect = selectedRect();
  if (!rect) { status('先选中一个图层，再新建动画组'); return; }
  const clip = newClipGroup(rect);
  state.sel.channel = -1;
  state.sel.keyIndex = -1;
  persistDoc();
  renderTimeline();
  status('已新建「' + (clip.name || '动画组') + '」，接着点右上角 ◇ 开启属性关键帧');
};

document.getElementById('btn-group-rename').onclick = () => {
  const rect = selectedRect();
  const clip = clipFor(rect);
  if (!clip) { status('这个图层还没有动画组'); return; }
  const next = prompt('动画组名称', clip.name || '动画组');
  if (next === null) return;
  clip.name = String(next).trim().slice(0, 40) || clip.name;
  persistDoc();
  renderTimeline();
  status('已改名：' + clip.name);
};

document.getElementById('btn-group-del').onclick = () => {
  const rect = selectedRect();
  const clip = clipFor(rect);
  if (!clip) { status('这个图层还没有动画组'); return; }
  if (!confirm('删除动画组「' + (clip.name || clip.id) + '」？组里的关键帧会一起删掉。')) return;
  deleteClipGroup(clip);
  persistDoc();
  renderTimeline();
  renderAll(true);
  status('已删除动画组');
};
document.getElementById('btn-new').onclick = () => {
  state.doc = { manifest: clone(DEFAULT_MANIFEST), scene: clone(DEFAULT_SCENE), interactions: clone(DEFAULT_INTERACTIONS), resources: {} };
  imageCache.clear();
  state.sel = { rectId: null, channel: -1, keyIndex: -1 };
  renderAll();
};
document.getElementById('btn-import').onclick = () => document.getElementById('file').click();
document.getElementById('file').onchange = ev => {
  const file = ev.target.files?.[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => importJson(String(reader.result));
  reader.readAsText(file);
  ev.target.value = '';
};
document.getElementById('btn-export-bundle').onclick = exportBundle;
document.getElementById('btn-send-phone').onclick = sendToPhone;
// 撤销历史的基线：必须在草稿恢复之前/之后各一次，保证第一下 Ctrl+Z 能回到"打开时"的样子。
// 画板外壳（双模式 + 可移动预览）
setupGraphShell();

// 触发器工作台（覆盖层）：按钮在顶栏，数据在 interactions.studio
if (typeof setupStudio === 'function') setupStudio();
// 编译层：节点图 → 引擎规则 + 模拟运行（定义在 studio-compile.js）
if (typeof setupStudioCompile === 'function') setupStudioCompile();


// 启动：有草稿就先恢复草稿（防误刷新丢画布），没有才用默认文档。
// 放在文件末尾执行，保证 renderAll 等函数已定义。
// 清缓存入口：地址栏加 ?reset=1 会先清掉编辑器在 localStorage 里的全部痕迹，再自刷新。
// 为什么要它：触发规则/草稿的旧存档出问题时，用户很难手动清干净；
// 也不能直接删代码，所以给一个可重复使用的"干净启动"开关。
(function resetCacheIfRequested() {
  const flag = new URLSearchParams(location.search);
  if (flag.get('reset') !== '1') return;
  try {
    Object.keys(localStorage)
      .filter(k => k.startsWith('lwpkit.'))
      .forEach(k => localStorage.removeItem(k));
    const clean = new URL(location.href);
    clean.searchParams.delete('reset');
    location.replace(clean.toString());
    return;
  } catch (e) {
    console.warn('清缓存失败', e);
  }
})();

(function restoreDraft() {
  const draft = loadPersistedDoc();
  if (!draft) return;
  state.doc = {
    manifest: draft.manifest || clone(DEFAULT_MANIFEST),
    scene: draft.scene,
    interactions: draft.interactions || clone(DEFAULT_INTERACTIONS),
    resources: draft.resources || {}
  };
  state.sel = { rectId: null, channel: -1, keyIndex: -1 };
  ensureClipNames();
  renderAll();
  // 资源不在 localStorage 里（体积太大），从 IndexedDB 异步补回来
  migrateLegacyBindings(state.doc);   // migrateLegacyBindings after restore
  restoreResourcesFromIdb();
  const box = document.getElementById('errors');
  if (box) {
    box.innerHTML = '';
    box.appendChild(el('div', null, '已恢复上次的编辑草稿（浏览器本地保存）'));
  }
})();

(function restorePhoneAddr() {
  try {
    const saved = localStorage.getItem('lwpkit.phoneAddr');
    const input = document.getElementById('phone-addr');
    if (saved && input) input.value = normalizePhoneAddr(saved);
  } catch { /* 忽略 */ }
  // 打开页面就握一次手，能连上就直接标绿，省得用户盲点
  autoHandshake();
})();
document.getElementById('btn-export-files').onclick = export3;
document.getElementById('btn-add-layer').onclick = (ev) => {
  ev.stopPropagation();
  const menu = document.getElementById('add-menu');
  if (menu) menu.classList.toggle('open');
};
document.getElementById('add-menu').onclick = (ev) => {
  const kind = ev.target && ev.target.getAttribute ? ev.target.getAttribute('data-add') : null;
  if (!kind) return;
  document.getElementById('add-menu').classList.remove('open');
  if (kind === 'shape') openShapePicker();
  else if (kind === 'container') addContainer();
  else if (kind === 'text') addText();
  else if (kind === 'image') document.getElementById('image-file').click();
  else if (kind === 'icon') openIconPicker();
  else if (kind === 'svg') openSvgPanel();
};
document.addEventListener('click', (ev) => {
  const menu = document.getElementById('add-menu');
  if (!menu || !menu.classList.contains('open')) return;
  if (ev.target && ev.target.id === 'btn-add-layer') return;
  menu.classList.remove('open');
});

 

document.getElementById('icon-close').onclick = closeIconPicker;
document.getElementById('shape-close').onclick = closeShapePicker;
document.getElementById('shape-search').oninput = ev => {
  state.shapeSearch = ev.target.value;
  renderShapeGrid();
};
document.getElementById('svg-close').onclick = closeSvgPanel;
wireSvgDropZone();
document.getElementById('svg-compile').onclick = () => previewSvg();
document.getElementById('svg-insert').onclick = () => insertSvg();
document.getElementById('svg-size').onchange = () => { if (document.getElementById('svg-preview').children.length) previewSvg(); };
document.getElementById('mask-file').onchange = ev => {
  const file = ev.target.files && ev.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    const dataUrl = String(reader.result || '');
    if (!dataUrl) return;
    let key = 'mask_' + file.name.replace(/[^\w.\-]/g, '_');
    let n = 1;
    while (state.doc.resources[key] && state.doc.resources[key] !== dataUrl) key = `${n++}_mask_${file.name}`;
    state.doc.resources[key] = dataUrl;

    const node = selectedRect();
    if (!node) { status('先选中一个图层再选蒙版图'); return; }
    node.maskSrc = key;
    imageCache.delete(key); // 让新图重新解码
    renderAll(true);
    draw();
    status('已设置图片蒙版：' + key);
  };
  reader.readAsDataURL(file);
  ev.target.value = '';
};
document.getElementById('svg-file').onchange = ev => {
  const file = ev.target.files && ev.target.files[0];
  loadSvgFile(file);
  // 允许重复选同一个文件（否则 change 事件不会再触发）
  ev.target.value = '';
};
document.getElementById('svg-picker').onclick = ev => {
  if (ev.target && ev.target.id === 'svg-picker') closeSvgPanel();
};
document.getElementById('shape-picker').onclick = ev => {
  if (ev.target && ev.target.id === 'shape-picker') closeShapePicker();
};
document.getElementById('icon-color').onchange = () => renderIconGrid();
document.getElementById('icon-kind').onchange = () => renderIconGrid();
document.getElementById('icon-search').oninput = ev => {
  state.iconSearch = ev.target.value;
  renderIconGrid();
};
document.getElementById('icon-picker').onclick = ev => {
  // 点遮罩关闭，点卡片不关
  if (ev.target && ev.target.id === 'icon-picker') closeIconPicker();
};
document.getElementById('btn-add-toggle').onclick = () => addEvent('toggleVisible');
document.getElementById('btn-add-launch').onclick = () => addEvent('launchApp');
document.getElementById('btn-add-uri').onclick = () => addEvent('openUri');
document.getElementById('image-file').onchange = ev => {
  const file = ev.target.files?.[0];
  ev.target.value = '';
  if (file) addImageFromFile(file);
};
document.getElementById('btn-del-layer').onclick = delRect;
document.getElementById('btn-add-kf').onclick = addKeyframeAtPlayhead;
document.getElementById('btn-del-kf').onclick = delKeyframe;
document.getElementById('btn-play').onclick = togglePlay;

document.getElementById('trigger').onchange = ev => {
  const clip = clipFor(selectedRect());
  if (!clip) return;
  clip.trigger = { type: ev.target.value };
  if (ev.target.value === 'tap') clip.trigger.targetId = clip.targetId;
  renderAll();
};
document.getElementById('loop').onchange = ev => {
  const clip = clipFor(selectedRect());
  if (!clip) return;
  clip.tracks.forEach(t => (t.loop = ev.target.value));
  renderAll();
};
document.getElementById('duration').onchange = ev => {
  const clip = clipFor(selectedRect());
  if (!clip) return;
  const v = Math.max(1, Number(ev.target.value) || 1000);
  clip.tracks.forEach(t => (t.durationMs = v));
  renderAll();
};

window.addEventListener('resize', () => {
  resizeCanvas();
  // 画布尺寸变了，面板的百分比中心点要重算：否则会停在画布外面（顶部为负）
  const panel = document.getElementById('preview-panel');
  if (panel && typeof applyPanelTransform === 'function') applyPanelTransform(panel);
});

// 版本标记：刷新后看一眼这里，就能确认浏览器是不是真的拿到了新脚本。
const BUILD_TAG = '2026-11-03-v101weather2';
(function () {
  const node = document.getElementById('build');
  if (node) node.textContent = 'build ' + BUILD_TAG;
})();

// 页面级兜底：任何未捕获异常都显示在顶部，省得去开控制台。
window.addEventListener('error', (event) => {
  const box = document.getElementById('fatal');
  if (!box) return;
  box.style.display = 'block';
  box.textContent = `[脚本错误] ${event.message}\n${event.filename || ''}:${event.lineno || 0}:${event.colno || 0}` +
    (event.error && event.error.stack ? `\n${event.error.stack}` : '');
});

try {
  resizeCanvas();
  renderAll();
  // 首次布局完成后再夹一次：面板高度要等画布出图才稳定，
  // 早算会把中心点夹到很小，看起来像"预览跑出画布"。
  const settle = () => {
    const panel = document.getElementById('preview-panel');
    if (panel && typeof applyPanelTransform === 'function') applyPanelTransform(panel);
  };
  settle();
  requestAnimationFrame(settle);
  setTimeout(settle, 150);
} catch (error) {
  const box = document.getElementById('fatal');
  if (box) {
    box.style.display = 'block';
    box.textContent = `[初始化失败] ${error && error.message ? error.message : error}\n${error && error.stack ? error.stack : ''}`;
  }
  console.error(error);
}
