// 生成 docs/property-ids.txt：纯文本 ID 对照表，给用户直接看/复制。
// 中文按双宽度计算，保证等宽字体下表格对齐。
'use strict';

import { writeFileSync } from 'node:fs';

const rows = [
  ['01', 'x', '位置 X', 'x', '数值（画布单位）'],
  ['02', 'y', '位置 Y', 'y', '数值（画布单位）'],
  ['03', 'w', '宽', 'width', '数值（画布单位）'],
  ['04', 'h', '高', 'height', '数值（画布单位）'],
  ['05', 'scale', '缩放', 'scale', '0.05 ~ 10'],
  ['06', 'rot', '旋转', 'rotationZ', '-720 ~ 720（度）'],
  ['07', 'alpha', '透明度', 'opacity', '0 ~ 1'],
  ['08', 'radius', '圆角', 'cornerRadius', '占宽度百分比 0 ~ 100'],
  ['09', 'feather', '羽化', 'feather', '占宽度百分比 0 ~ 50'],
  ['10', 'shape', '形状', 'shape', 'rect/ellipse/triangle/trapezoid/polygon/star/path'],
  ['11', 'sides', '边数', 'sides', '3 ~ 32（多边形/星形）'],
  ['12', 'color', '颜色', 'color', '#AARRGGBB'],
  ['13', 'colorA', '颜色透明度', 'color 前两位', '0 ~ 1（只改 A，不动 RGB）'],
  ['14', 'grad', '渐变', 'gradient', '空 或 类型|角度|色标,...'],
  ['15', 'visible', '显隐', 'visible', 'true / false'],
  ['16', 'hit', '点击穿透', 'hitThrough', 'true = 不参与命中'],
  ['17', 'clip', '裁剪溢出', 'clip', 'true / false（容器）'],
  ['18', 'bg', '容器背景', 'background', '#AARRGGBB（容器）'],
  ['19', 'bgA', '容器背景透明度', 'background 前两位', '0 ~ 1'],
  ['20', 'txt', '文本内容', 'text', '字符串（文本层）'],
  ['21', 'font', '字号', 'fontSize', '数值（画布单位）'],
  ['22', 'align', '对齐', 'align', 'left / center / right'],
  ['23', 'src', '图片资源', 'src', '资源表的 key（图片层）'],
  ['24', 'mask', '蒙版形状', 'mask', '空/rect/ellipse/triangle/trapezoid/polygon/star'],
  ['25', 'maskSides', '蒙版边数', 'maskSides', '3 ~ 32'],
  ['26', 'maskX', '蒙版 X', 'maskX', '相对图层中心偏移（宽度百分比）'],
  ['27', 'maskY', '蒙版 Y', 'maskY', '同上'],
  ['28', 'maskW', '蒙版宽', 'maskW', '占图层宽度百分比，默认 100'],
  ['29', 'maskH', '蒙版高', 'maskH', '占图层高度百分比，默认 100'],
  ['30', 'para', '视差独立', 'parallaxMode', 'global（跟随）/ custom（独立）'],
  ['31', 'paraDepth', '景深', 'parallax', '0 ~ 100'],
  ['32', 'paraAmp', '振幅', 'parallaxAmp', '0 ~ 12'],
  ['33', 'paraAngle', '方向', 'parallaxAngle', '度']
];

const canvasRows = [
  ['canvas.paraOn', '视差总开关', 'manifest.parallax.enabled', 'true / false'],
  ['canvas.depth', '全局景深', 'manifest.parallax.depth', '0 ~ 100'],
  ['canvas.amp', '全局振幅', 'manifest.parallax.amp', '0 ~ 12'],
  ['canvas.smooth', '平滑', 'manifest.parallax.smoothing', '0.04 ~ 0.5'],
  ['canvas.dead', '死区', 'manifest.parallax.deadzone', '0 ~ 0.3'],
  ['canvas.bg', '画布背景', 'scene.root.background', '#AARRGGBB'],
  ['canvas.bgA', '画布背景透明度', '同上前两位 A', '0 ~ 1']
];

const animRows = [
  ['x / y', '位置', '-200 ~ 400 / -200 ~ 600'],
  ['scale', '缩放', '0.05 ~ 10'],
  ['rot', '旋转', '-720 ~ 720'],
  ['alpha', '透明度', '0 ~ 1']
];

/** 汉字按 2 个字宽计算，保证等宽字体下对齐。 */
function width(s) {
  let w = 0;
  for (const ch of String(s)) w += /[\u3000-\u9fff\uff00-\uffef]/.test(ch) ? 2 : 1;
  return w;
}
function pad(s, n) {
  const str = String(s);
  return str + ' '.repeat(Math.max(0, n - width(str)));
}
function table(headers, data, widths) {
  const sep = widths.map(w => '-'.repeat(w)).join('  ');
  const lines = [headers.map((h, i) => pad(h, widths[i])).join('  '), sep];
  for (const row of data) lines.push(row.map((c, i) => pad(c, widths[i])).join('  '));
  return lines.join('\n');
}

const text = `
LWPKit 图层属性 ID 对照表
================================================================

命名规则
----------------------------------------------------------------
  完整 ID = <图层ID>.<子ID>

  · 图层ID 就是左栏图层列表里的那个 id，例如 rect_glow / mf_center / t_254
  · 子ID 见下表，全部小写，用英文句点连接
  · 界面上不显示这些 ID，它们只用于写函数时引用属性

  示例：
    rect_glow.x           该层的 X 位置
    rect_glow.alpha       该层的透明度
    rect_glow.maskW       该层蒙版的宽度
    mf_center.paraDepth   该层的视差景深


一、图层属性子ID（按属性面板从上到下）
================================================================
${table(
  ['序号', '子ID', '中文名', 'JSON字段', '类型 / 范围'],
  rows,
  [6, 12, 18, 22, 46]
)}


二、画布级参数（不带图层名）
================================================================
${table(
  ['子ID', '中文名', 'JSON位置', '范围'],
  canvasRows,
  [16, 18, 30, 20]
)}


三、动画通道（时间轴用，只认这 5 个）
================================================================
${table(['子ID', '面板名', '范围'], animRows, [10, 10, 26])}


四、函数里怎么用
================================================================
  LWP.id('rect_glow', 'alpha')        拼 ID     -> "rect_glow.alpha"
  LWP.attr('rect_glow', 'alpha')      读值      -> 0.85
  LWP.set('rect_glow', 'alpha', 0.5)  写值      -> 自动重绘并落盘
  LWP.set('rect_glow.alpha', 0.5)     整串写法也行
  LWP.node('rect_glow')               拿到图层对象
  LWP.layers()                        所有图层 id（含容器内的）
  LWP.keys()                          全部子 ID 列表

  例子：所有图层透明度减半
    LWP.layers().forEach(l =>
      LWP.set(l, 'alpha', LWP.attr(l, 'alpha') * 0.5));

  例子：蒙版宽度跟着图层宽度
    LWP.set('rect_bar', 'maskW',
      LWP.attr('rect_bar', 'w') / 2);


五、注意：颜色是 #AARRGGBB，不是 #RRGGBBAA
================================================================
  本项目的颜色串 alpha 在【前两位】，这是 Android / AWT 的惯例，
  和 CSS 的 #RRGGBBAA 相反。写函数时别搞混，否则颜色会整体变色。

  正确： #FF3DDC97   ->  A=FF(不透明)  R=3D  G=DC  B=97
  错误： #3DDC97FF   ->  会被读成 A=3D（半透明）＋怪色

  所以只改透明度请用 colorA：
    LWP.set('rect_glow', 'colorA', 0.4)
    #FF3DDC97  ->  #663DDC97   （RGB 不变，只改了 A）


六、为什么这样排序
================================================================
  1. 先位置、再尺寸、后外观，和属性面板一致，也符合"在哪、多大、好不好看"
  2. 短名优先：x / y / w / h 敲得最快，函数里出现频率最高
  3. 同族同前缀：蒙版全是 mask*，视差全是 para*，输入前缀就能联想
  4. 与 JSON 字段尽量同名：只砍啰嗦后缀（opacity->alpha、rotationZ->rot）
  5. 关键帧 / 缓动 / 循环不进属性 ID，它们属于动画通道

================================================================
  生成时间：${new Date().toISOString().slice(0, 19).replace('T', ' ')}
  对应版本：2026-10-29-attr2
`;

writeFileSync('D:/deepseek项目/壁纸CODE/docs/property-ids.txt', '\ufeff' + text.replace(/\n/g, '\r\n'), 'utf8');
console.log('✓ docs/property-ids.txt');
console.log('  行数: ' + text.split('\n').length);
console.log('  字符: ' + text.length);
