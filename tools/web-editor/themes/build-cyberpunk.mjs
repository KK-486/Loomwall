// 生成「危险区域」赛博朋克主题的 scene / interactions JSON。
//
// 为什么用脚本生成而不是手写 JSON：
//   这套主题有 80+ 个节点（网格线、面板、图标、文字、热区），手写既容易漏逗号，
//   也没法快速调版式。生成器把「版式参数」和「重复结构」抽出来，改一个数字就能整体位移。
//
// 坐标约定与引擎一致：x / y / w / h 都是**画布宽度的百分比**，原点左上，y 向下。
// 竖屏 1080x2340 → 画布高度 ≈ 216.56 单位。
'use strict';

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ICON_DIR = join(HERE, '..', 'icons', 'svg');

// ---------- 调色板 ----------
const C = {
  bg: '#FF07040A',
  grid: '#FF1A0710',
  panel: '#FF120A12',
  panelEdge: '#FF3A0F1C',
  red: '#FFFF2E4C',
  redDim: '#FF7A1626',
  redDeep: '#FF4A0F1C',
  redText: '#FFFF8A98',
  dim: '#FF8A5560',
  cyan: '#FF29E0F0',
  yellow: '#FFE8D44D',
  white: '#FFF2E6E8'
};

/** 画布高度（单位）。竖屏 2340/1080*100。 */
const H = 204;

// 顶部安全区：系统状态栏（时间/信号/电量）大约占 9~10 单位，
// 内容必须从这里往下让，否则会和系统 UI 叠在一起。
const TOP = 11;

const nodes = [];
let seq = 0;

/** 生成唯一 id：keeps JSON diff-able（固定前缀 + 序号）。 */
function id(prefix) {
  seq += 1;
  return prefix + '_' + String(seq).padStart(3, '0');
}

/** 画一个矩形（可选圆角/蒙版/渐变/羽化）。 */
function rect(opts) {
  const node = {
    id: opts.id || id('r'),
    type: 'rect',
    visible: opts.visible !== false,
    x: round(opts.x),
    y: round(opts.y + (opts.fullBleed ? 0 : TOP)),
    // 同时接受 w/h 与 width/height：写版式时两种叫法都很自然，
    // 统一在这里归一化，避免出现"传了 width 但取 w 得到 undefined"的坑。
    width: round(opts.w !== undefined ? opts.w : opts.width),
    height: round(opts.h !== undefined ? opts.h : opts.height),
    color: opts.color || C.panel,
    opacity: opts.opacity === undefined ? 1 : opts.opacity
  };
  if (opts.shape) node.shape = opts.shape;
  if (opts.sides) node.sides = opts.sides;
  if (opts.radius) node.cornerRadius = opts.radius;
  if (opts.gradient) node.gradient = opts.gradient;
  if (opts.mask) node.mask = opts.mask;
  if (opts.maskSides) node.maskSides = opts.maskSides;
  if (opts.feather) node.feather = opts.feather;
  if (opts.rotationZ) node.rotationZ = opts.rotationZ;
  if (opts.scale) node.scale = opts.scale;
  nodes.push(node);
  return node.id;
}

/** 画一段文字。 */
function text(opts) {
  const node = {
    id: opts.id || id('t'),
    type: 'text',
    visible: opts.visible !== false,
    x: round(opts.x),
    y: round(opts.y + (opts.fullBleed ? 0 : TOP)),
    // 同时接受 w/h 与 width/height：写版式时两种叫法都很自然，
    // 统一在这里归一化，避免出现"传了 width 但取 w 得到 undefined"的坑。
    width: round(opts.w !== undefined ? opts.w : opts.width),
    height: round(opts.h !== undefined ? opts.h : opts.height),
    text: opts.text,
    fontSize: round(opts.size || 2.2),
    align: opts.align || 'left',
    color: opts.color || C.redText,
    opacity: opts.opacity === undefined ? 1 : opts.opacity
  };
  if (opts.spacing) node.spacing = opts.spacing;
  nodes.push(node);
  return node.id;
}

// ---------- 图标（从图标库读 SVG path）----------
const iconCache = new Map();

function iconPath(name) {
  if (iconCache.has(name)) return iconCache.get(name);
  const raw = readFileSync(join(ICON_DIR, name + '.svg'), 'utf8');
  const m = raw.match(/<path[^>]*\sd="([^"]+)"/);
  if (!m) throw new Error('图标没有 path：' + name);
  // viewBox 可能是 0 0 24 24，也可能是品牌图标自己的尺寸
  const vb = raw.match(/viewBox="([^"]+)"/);
  const d = m[1];
  const viewBox = vb ? vb[1] : '0 0 24 24';
  iconCache.set(name, { d, viewBox });
  return iconCache.get(name);
}

/** 画一个图标（path 形状 + 描边）。 */
function icon(opts) {
  const { d, viewBox } = iconPath(opts.name);
  const node = {
    id: opts.id || id('i'),
    type: 'rect',
    visible: opts.visible !== false,
    x: round(opts.x),
    y: round(opts.y + (opts.fullBleed ? 0 : TOP)),
    width: round(opts.size),
    height: round(opts.size),
    shape: 'path',
    // 引擎的 JSON 键是 path（对应 RectNode.pathData），不是 pathData
    path: d,
    viewBox,
    strokeWidth: opts.stroke === undefined ? 1.8 : opts.stroke,
    color: opts.color || C.red,
    opacity: opts.opacity === undefined ? 1 : opts.opacity
  };
  nodes.push(node);
  return node.id;
}

function round(v) {
  return Math.round(v * 100) / 100;
}

// ================= 背景 =================
rect({ id: 'bg', x: 0, y: 0, width: 100, height: H, color: C.bg, fullBleed: true });
rect({
  id: 'bgGlow',
  fullBleed: true,
  x: -10,
  y: 40,
  width: 120,
  height: 90,
  color: '#00000000',
  gradient: 'radial:0:0.5#' + C.redDeep.slice(2) + ';1#0007040A00',
  opacity: 0.55
});

// 细网格：横向每 6 单位一条，纵向每 6 单位一条（用低透明度避免抢视觉）
for (let y = 6; y < H; y += 6) {
  rect({ x: 0, y, width: 100, height: 0.12, color: C.grid, opacity: 0.9, fullBleed: true });
}
for (let x = 6; x < 100; x += 6) {
  rect({ x, y: 0, width: 0.12, height: H, color: C.grid, opacity: 0.9, fullBleed: true });
}

// 边框角标（四角 L 形，赛博朋克常见装饰）
const corner = (x, y, dx, dy) => {
  rect({ x, y, width: dx === 1 ? 8 : 0.35, height: 0.35, color: C.redDim });
  rect({ x, y, width: 0.35, height: dy === 1 ? 8 : 0.35, color: C.redDim });
};
corner(2, 2, 1, 1);
corner(97.6, 2, -1, 1);
corner(2, H - 10, 1, -1);
corner(97.6, H - 10, -1, -1);

// ================= 顶部状态条 =================
text({ x: 3, y: 3.4, width: 26, height: 3, text: '[ 电力状态 ]', size: 1.9, color: C.dim });
icon({ name: 'battery', x: 3, y: 7, size: 5, stroke: 2.1, color: C.red });
// 电池条：轨道 + 填充 + 会移动的遮挡块。
// 遮挡块 x 绑定 sys.battery.level（见 interactions.bindings），电量越低遮挡越多。
rect({ x: 9.5, y: 8.2, width: 20.5, height: 2.4, color: C.redDim, opacity: 0.35 });
rect({ x: 9.5, y: 8.2, width: 20.5, height: 2.4, color: C.red });
rect({ id: 'batteryCover', x: 30, y: 8.2, width: 20.5, height: 2.4, color: C.bg });
text({ x: 9.5, y: 11.4, width: 42, height: 3, text: '{sys.battery.level:00}%  ▸ {sys.battery.temp:0.0}°C  ▸ {sys.battery.charging:0}', size: 1.7, color: C.dim });
text({ x: 40, y: 3.4, width: 30, height: 3, text: '{sys.time.hour:00} : {sys.time.minute:00}', size: 3.4, color: C.redText, align: 'center' });
text({ x: 44, y: 7.6, width: 22, height: 2.4, text: 'NETRUNNER // ONLINE', size: 1.4, color: C.dim, align: 'center' });

// 右上角雷达圆
icon({ name: 'map-pin', x: 84, y: 4.4, size: 3.2, stroke: 1.6, color: C.red });
rect({ x: 83, y: 3, width: 13, height: 13, color: '#00000000', shape: 'ellipse', gradient: 'radial:0:0.5#FF2E4C55;1#00000000' });
rect({ x: 83.6, y: 3.6, width: 11.8, height: 11.8, color: '#00000000', shape: 'ellipse', opacity: 0.9, feather: 0 });
rect({ x: 88.75, y: 8.75, width: 1.5, height: 1.5, color: C.red, shape: 'ellipse' });
text({ x: 83, y: 16.4, width: 14, height: 2.4, text: '地图 · 夜之城', size: 1.4, color: C.dim, align: 'center' });

// ================= 分隔装饰条 =================
rect({ x: 3, y: 19.4, width: 94, height: 0.3, color: C.redDim, opacity: 0.9 });
for (let i = 0; i < 24; i++) {
  rect({ x: 3 + i * 3.95, y: 20.2, width: 2.2, height: 0.9, color: C.red, opacity: i % 3 === 0 ? 0.85 : 0.28 });
  rect({ x: 3 + i * 3.95, y: 20.2, width: 2.2, height: 0.9, color: C.red, opacity: i % 3 === 0 ? 0.85 : 0.28 });
}
// 流动高光：x 从 -8 扫到 100，配合 loop 循环 → 视觉上"扫描条在流动"
const scanHighlightId = rect({ id: 'scanHighlight', x: 3, y: 20.1, width: 6, height: 1.1, color: '#FFFFE0E4', opacity: 0.85 });
const dangerTextId = text({ id: 'dangerTag', x: 78, y: 19.9, width: 20, height: 2.6, text: 'ZONE:DANGER', size: 1.6, color: C.red, align: 'right' });

// ================= ACCESS GRANTED 主横幅 =================
rect({ x: 3, y: 23.5, width: 94, height: 12, color: '#FF1A0A12' });
rect({ x: 3, y: 23.5, width: 0.6, height: 12, color: C.red });
text({ x: 5.5, y: 24.6, width: 40, height: 2.2, text: '// NETWORK CODE', size: 1.5, color: C.dim });
const accessTextId = text({ id: 'accessGranted', x: 5.5, y: 26.6, width: 60, height: 5, text: 'ACCESS GRANTED', size: 4.6, color: C.white });
text({ x: 5.5, y: 31.6, width: 60, height: 2.4, text: 'Connection with network was established', size: 1.5, color: C.dim });
rect({ x: 78, y: 26.4, width: 16, height: 5.6, color: '#00000000', shape: 'rect', cornerRadius: 8, opacity: 0.9 });
text({ x: 78, y: 27.4, width: 16, height: 3, text: 'LINK 99%', size: 2.1, color: C.red, align: 'center' });

// ================= 安全等级 =================
text({ x: 3, y: 37.4, width: 30, height: 2.4, text: 'SECURITY LEVEL', size: 1.6, color: C.dim });
const levels = ['T1', 'T2', 'T3', 'T4'];
levels.forEach((label, i) => {
  const x = 3 + i * 12.5;
  const active = i === 2;
  rect({ x, y: 39.6, width: 11.5, height: 6.4, color: active ? C.redDim : '#FF160B12', opacity: active ? 0.85 : 1 });
  rect({ x, y: 39.6, width: 11.5, height: 0.4, color: active ? C.red : C.redDeep });
  text({ x, y: 40.6, width: 11.5, height: 2.2, text: 'LEVEL', size: 1.3, color: active ? C.white : C.dim, align: 'center' });
  text({ x, y: 42.4, width: 11.5, height: 3, text: label, size: 2.6, color: active ? C.red : C.dim, align: 'center' });
});
text({ x: 54, y: 37.4, width: 44, height: 2.4, text: 'ASSASSINATION', size: 1.6, color: C.dim });
text({ x: 54, y: 40.2, width: 44, height: 4, text: '64 / 100', size: 3.2, color: C.redText });
rect({ x: 54, y: 44.6, width: 43, height: 1, color: C.redDeep, opacity: 0.5 });
rect({ x: 54, y: 44.6, width: 27.5, height: 1, color: C.red });

// ================= 左列：快捷方式 =================
rect({ x: 3, y: 49, width: 45, height: 4.6, color: C.redDeep, opacity: 0.75 });
text({ x: 4.5, y: 50, width: 40, height: 2.6, text: '快捷方式  //  QUICK ACCESS', size: 1.9, color: C.white });
const shortcutHitRows = [];
const shortcuts = [
  { icon: 'brand-sinaweibo', name: '微博', sub: 'HOT TOPICS', value: '12.4K', pkg: 'com.sina.weibo' },
  { icon: 'brand-bilibili', name: '哔哩哔哩', sub: 'NEO-CHINA', value: '104', pkg: 'tv.danmaku.bili' },
  { icon: 'brand-wechat', name: '微信', sub: 'CHAT CHANNEL', value: '5', pkg: 'com.tencent.mm' },
  { icon: 'brand-tiktok', name: '抖音', sub: 'SHORT VIDEO', value: '2.9K', pkg: 'com.ss.android.ugc.aweme' },
  { icon: 'brand-xiaohongshu', name: '小红书', sub: 'LIFE LOG', value: '8', pkg: 'com.xingin.xhs' }
];

shortcuts.forEach((item, i) => {
  const y = 55 + i * 10.4;
  rect({ x: 3, y, width: 45, height: 9.4, color: C.panel, opacity: 0.9 });
  rect({ x: 3, y, width: 0.35, height: 9.4, color: C.red, opacity: 0.8 });
  icon({ name: item.icon, x: 5.4, y: y + 1.9, size: 5.2, stroke: 0, color: C.redText });
  text({ x: 12.6, y: y + 1.4, width: 24, height: 3, text: item.name, size: 2.5, color: C.white });
  text({ x: 12.6, y: y + 4.7, width: 24, height: 2.2, text: item.sub, size: 1.4, color: C.dim });
  text({ x: 33, y: y + 2.6, width: 13, height: 3, text: item.value, size: 2.2, color: C.red, align: 'right' });
  shortcutHitRows.push({ x: 3, y, w: 45, h: 9.4, pkg: item.pkg, name: item.name });
});

// BIO DATA 卡
rect({ x: 3, y: 108.4, width: 45, height: 13.6, color: C.panel, opacity: 0.92 });
rect({ x: 3, y: 108.4, width: 45, height: 0.35, color: C.redDeep });
text({ x: 4.5, y: 109.4, width: 30, height: 2.4, text: 'BIO DATA  //  体征', size: 1.6, color: C.dim });
// 图标只能用图标库里真实存在的（list: tools/web-editor/icons/catalog.json）
const bio = [
  { label: '内存', value: '{sys.mem.usedPct:00}%', icon: 'heart' },
  { label: '存储', value: '{sys.storage.usedPct:00}%', icon: 'cloud' },
  { label: '通知', value: '{sys.notify.count}', icon: 'bell' },
  { label: '帧率', value: '{sys.render.fps}', icon: 'moon' }
];
bio.forEach((b, i) => {
  const x = 5 + i * 10.8;
  // 图标中心对齐：图标左边缘 x、宽 3.4 → 中心 x+1.7。
  // 文字块宽 9、居中，左边缘 = 中心 - 4.5，这样数值和标签都正好落在图标正下方。
  const textX = x + 1.7 - 4.5;
  icon({ name: iconExists(b.icon) ? b.icon : "heart", x, y: 112.6, size: 3.4, stroke: 1.8, color: C.red, opacity: 0.9 });
  text({ x: textX, y: 116.2, width: 9, height: 2.6, text: b.value, size: 2.2, color: C.white, align: "center" });
  text({ x: textX, y: 118.8, width: 9, height: 2, text: b.label, size: 1.2, color: C.dim, align: "center" });
});

// 区域设置
rect({ x: 3, y: 124.4, width: 45, height: 4.4, color: C.redDeep, opacity: 0.75 });
text({ x: 4.5, y: 125.2, width: 40, height: 2.4, text: '// 区域设置  ZONE SETTINGS', size: 1.7, color: C.white });
const zones = [
  { label: '安全区状态', color: '#FF2ED47A', mark: '✓' },
  { label: '公共区状态', color: C.cyan, mark: '✓' },
  { label: '限制区状态', color: C.yellow, mark: '!' },
  { label: '危险区状态', color: C.red, mark: '▲' }
];
zones.forEach((z, i) => {
  const y = 130 + i * 7.6;
  rect({ x: 3, y, width: 45, height: 6.6, color: C.panel, opacity: 0.9 });
  rect({ x: 3, y, width: 0.5, height: 6.6, color: z.color });
  text({ x: 5.5, y: y + 1.9, width: 30, height: 2.6, text: z.label, size: 2.1, color: C.white });
  text({ x: 36, y: y + 1.5, width: 10, height: 3, text: z.mark, size: 2.6, color: z.color, align: 'right' });
});

// ================= 右列：温度 + 动作列表 =================
rect({ x: 52, y: 49, width: 45, height: 4.6, color: C.redDeep, opacity: 0.75 });
text({ x: 53.5, y: 50, width: 40, height: 2.6, text: '// 系统   SYSTEM NODE', size: 1.9, color: C.white });
rect({ x: 52, y: 55, width: 20.5, height: 26, color: C.panel, opacity: 0.95 });
text({ x: 53.5, y: 56.6, width: 18, height: 2.2, text: 'MEMORY', size: 1.4, color: C.dim });
text({ x: 53.5, y: 59.4, width: 18, height: 8, text: '{sys.mem.usedPct:00}', size: 8.6, color: C.white });
text({ x: 65, y: 58.4, width: 8, height: 3, text: '%MEM', size: 2.2, color: C.redText });
icon({ name: 'cloud', x: 66.5, y: 62.4, size: 4.6, stroke: 1.8, color: C.yellow });
text({ x: 53.5, y: 69.4, width: 18, height: 2.2, text: '可用 {sys.mem.freeMb:0} MB', size: 1.4, color: C.dim });
text({ x: 53.5, y: 72.2, width: 18, height: 2.6, text: '存储 {sys.storage.usedPct:00}% 已用', size: 1.7, color: C.redText });
text({ x: 53.5, y: 76.4, width: 20, height: 2.4, text: '剩余 {sys.storage.freeMb:0} MB', size: 1.3, color: C.dim });

const actionHitRows = [];
const actions = [
  { icon: 'search', name: '数据库搜索', sub: 'SEARCH DATABASE', pkg: 'com.android.browser' },
  { icon: 'camera', name: '相机', sub: 'ACTIVATE SENSOR', pkg: 'com.android.camera' },
  { icon: 'brand-alipay', name: '支付', sub: 'ACTIVATE INBOX', pkg: 'com.eg.android.AlipayGphone' },
  { icon: 'message', name: '信息', sub: 'ACTIVATE MESSAGE', pkg: 'com.android.mms' },
  { icon: 'phone', name: '电话', sub: 'ACTIVATE CALL', pkg: 'com.android.dialer' },
  { icon: 'world', name: '快捷方式', sub: 'ACTIVATE SENSOR', pkg: 'com.android.settings' }
];
actions.forEach((a, i) => {
  const y = 55 + i * 9.9;
  const iconName = iconExists(a.icon) ? a.icon : 'settings';
  rect({ x: 77, y, width: 20, height: 9, color: C.panel, opacity: 0.92 });
  actionHitRows.push({ x: 77, y, w: 20, h: 9, pkg: a.pkg, name: a.name });
  // 动作行底板已在上方用 actionRowId 创建（右移到 77 留出与温度卡的间隙）
  rect({ x: 75.5, y, width: 0.35, height: 9, color: C.redDeep });
  icon({ name: iconName, x: 79, y: y + 2.1, size: 5.2, stroke: 2.2, color: C.red });
  text({ x: 85.5, y: y + 1.6, width: 11.5, height: 2.8, text: a.name, size: 1.9, color: C.white });
  text({ x: 85.5, y: y + 4.6, width: 11.5, height: 2, text: a.sub, size: 1.1, color: C.dim });
});

// ================= 底部三键 =================
const buttonHitRows = [];
const buttons = [
  { label: '区域切换', sub: 'ZONE SWITCH', action: 'zone' },
  { label: '模式切换', sub: 'MODE SWITCH', action: 'mode' },
  { label: '入侵', sub: 'INTRUDE', action: 'intrude' }
];
buttons.forEach((b, i) => {
  const x = 3 + i * 32;
  rect({ x, y: 184, width: 30, height: 11, color: C.panel, opacity: 0.95 });
  buttonHitRows.push({ x, y: 184, w: 30, h: 11, action: b.action });
  rect({ x, y: 184, width: 30, height: 0.4, color: C.red });
  rect({ x: x + 0.8, y: 185.2, width: 28.4, height: 9.6, color: '#00000000', opacity: 0.6 });
  text({ x, y: 185.6, width: 30, height: 2.2, text: '0' + (i + 1), size: 1.4, color: C.dim, align: 'center' });
  text({ x, y: 187.8, width: 30, height: 3.2, text: b.label, size: 2.4, color: C.white, align: 'center' });
  text({ x, y: 191.2, width: 30, height: 2, text: b.sub, size: 1.2, color: C.dim, align: 'center' });
});
text({ x: 3, y: 199.6, width: 94, height: 2.4, text: '菜单  //  SYSTEM READY', size: 1.5, color: C.dim, align: 'center' });

// 危险区高亮层（默认隐藏，点「区域切换」显隐）
rect({ id: 'layerZoneDanger', x: 0, y: 0, width: 100, height: H, color: '#33FF2E4C', visible: false, opacity: 0.35, fullBleed: true });
text({ id: 'zoneDangerLabel', x: 30, y: 150, width: 40, height: 8, text: 'DANGER ZONE', size: 5, color: C.red, align: 'center', visible: false, opacity: 0.9 });

// 模式切换覆盖层（青色主题，默认隐藏）
rect({ id: 'layerThemeCyan', x: 0, y: 0, width: 100, height: H, color: '#2629E0F0', visible: false, opacity: 0.5, fullBleed: true });

// 入侵面板（默认隐藏）
rect({ id: 'layerIntrude', x: 10, y: 92, width: 80, height: 26, color: '#FF0B0410', visible: false });
rect({ id: 'layerIntrudeBar', x: 10, y: 92, width: 80, height: 0.5, color: C.cyan, visible: false });
text({ id: 'layerIntrudeText', x: 12, y: 98, width: 76, height: 4, text: '// INTRUSION IN PROGRESS', size: 3, color: C.cyan, visible: false });
text({ id: 'layerIntrudeSub', x: 12, y: 103, width: 76, height: 3, text: 'BREACHING FIREWALL…', size: 1.8, color: C.dim, visible: false });

// ---------- 工具：图标是否存在 ----------
function iconExists(name) {
  try {
    readFileSync(join(ICON_DIR, name + '.svg'));
    return true;
  } catch {
    return false;
  }
}

// ---------- 组装 ----------
const scene = {
  formatVersion: '0.2',
  root: {
    id: 'root',
    type: 'container',
    visible: true,
    background: C.bg,
    children: nodes
  }
};

// ================= 点击垫 =================
// hitTest 只返回**最上层**命中的节点：行底板会被行内的文字/图标盖住，
// 所以给每个可点区域补一个透明垫片（放在节点树最后 = 最上层），点击才稳定。
const hitPads = [];

shortcutHitRows.forEach((row, i) => {
  const padId = 'hit_short_' + i;
  rect({ id: padId, x: row.x, y: row.y, width: row.w, height: row.h, color: '#00000000' });
  hitPads.push({ id: padId, type: 'launchApp', value: row.pkg, label: row.name });
});

actionHitRows.forEach((row, i) => {
  const padId = 'hit_action_' + i;
  rect({ id: padId, x: row.x, y: row.y, width: row.w, height: row.h, color: '#00000000' });
  hitPads.push({ id: padId, type: 'launchApp', value: row.pkg, label: row.name });
});

buttonHitRows.forEach((row, i) => {
  const padId = 'hit_btn_' + i;
  rect({ id: padId, x: row.x, y: row.y, width: row.w, height: row.h, color: '#00000000' });
  hitPads.push({ id: padId, type: 'button', value: row.action, label: row.action });
});

const animations = [
  // ACCESS GRANTED 呼吸：透明度 1 ↔ 0.55，pingpong 来回
  {
    id: 'anim_breath',
    targetId: 'accessGranted',
    trigger: { type: 'always' },
    tracks: [{
      property: 'opacity', durationMs: 2600, loop: 'pingpong',
      keyframes: [
        { t: 0, value: 1, easing: 'easeInOut' },
        { t: 1, value: 0.55, easing: 'easeInOut' }
      ]
    }]
  },
  // 扫描条流动：高光块横向扫过（x 从 3 → 100），线性 loop
  {
    id: 'anim_scan',
    targetId: 'scanHighlight',
    trigger: { type: 'always' },
    tracks: [{
      property: 'x', durationMs: 3200, loop: 'loop',
      keyframes: [
        { t: 0, value: 3, easing: 'linear' },
        { t: 1, value: 100, easing: 'linear' }
      ]
    }]
  },
  // DANGER 闪烁：透明度 0.15 ↔ 1，pingpong
  {
    id: 'anim_danger',
    targetId: 'dangerTag',
    trigger: { type: 'always' },
    tracks: [{
      property: 'opacity', durationMs: 900, loop: 'pingpong',
      keyframes: [
        { t: 0, value: 1, easing: 'easeInOut' },
        { t: 1, value: 0.15, easing: 'easeInOut' }
      ]
    }]
  }
];

const events = [];

// 快捷方式 / 动作列表：点击跳转对应 App
for (const pad of hitPads) {
  if (pad.type === 'launchApp') {
    events.push({
      id: 'ev_' + pad.id,
      targetId: pad.id,
      gesture: 'tap',
      action: { type: 'launchApp', package: pad.value }
    });
  }
}

// 底部三键（一个点击只带一个动作，需要多个效果就拆多条事件）
const btnPads = hitPads.filter(p => p.type === 'button');
const padIdFor = action => (btnPads.find(p => p.value === action) || {}).id;

const zonePad = padIdFor('zone');
if (zonePad) {
  events.push({ id: 'ev_zone_layer', targetId: zonePad, gesture: 'tap', action: { type: 'toggleVisible', targetId: 'layerZoneDanger' } });
  events.push({ id: 'ev_zone_label', targetId: zonePad, gesture: 'tap', action: { type: 'toggleVisible', targetId: 'zoneDangerLabel' } });
}

const modePad = padIdFor('mode');
if (modePad) {
  events.push({ id: 'ev_mode', targetId: modePad, gesture: 'tap', action: { type: 'toggleVisible', targetId: 'layerThemeCyan' } });
}

const intrudePad = padIdFor('intrude');
if (intrudePad) {
  for (const layer of ['layerIntrude', 'layerIntrudeBar', 'layerIntrudeText', 'layerIntrudeSub']) {
    events.push({ id: 'ev_intrude_' + layer, targetId: intrudePad, gesture: 'tap', action: { type: 'toggleVisible', targetId: layer } });
  }
}

const bindings = [
  // 电量 → 电池条遮挡块的横向位置：0% 全遮、100% 全露（xp 方向由引擎插值）
  {
    targetId: 'batteryCover',
    property: 'x',
    source: 'sys.battery.level',
    in: [0, 100],
    out: [9.5, 30]
  }
];

const interactions = {
  formatVersion: '0.2',
  animations,
  bindings,
  events
};

const outDir = HERE;
mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, 'cyberpunk.scene.json'), JSON.stringify(scene, null, 2), 'utf8');
writeFileSync(join(outDir, 'cyberpunk.interactions.json'), JSON.stringify(interactions, null, 2), 'utf8');

console.log('节点数: ' + nodes.length);
console.log('已写出 cyberpunk.scene.json / cyberpunk.interactions.json');
