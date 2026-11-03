// 分析 Wallpaper Engine 的 scene.json：统计图层类型、用到的效果、材质与着色器。
//
// 目的：把"这个壁纸到底做了什么"变成可读的清单，用来判断我们的引擎能复刻多少。
//
// 用法：node analyze-scene.mjs <scene.json 路径>
'use strict';

import { readFileSync } from 'node:fs';

const path = process.argv[2];
if (!path) {
  console.error('用法: node analyze-scene.mjs <scene.json>');
  process.exit(2);
}
const doc = JSON.parse(readFileSync(path, 'utf8'));

const objects = Array.isArray(doc.objects) ? doc.objects : [];
const cameras = doc.camera ? [doc.camera] : [];
console.log('对象总数: ' + objects.length + '，相机: ' + cameras.length);

// ---- 类型 / 效果 / 材质 统计 ----
const typeCount = new Map();
const effectCount = new Map();
const materialCount = new Map();
const shaderCount = new Map();
const animCount = new Map();

function count(map, key) {
  if (!key) return;
  map.set(key, (map.get(key) || 0) + 1);
}

function walk(obj) {
  count(typeCount, obj.image || obj.text || obj.particle || obj.model || obj.light ? 'solid' : 'group');
  if (obj.image) count(materialCount, obj.image);
  if (obj.text) count(materialCount, obj.text);
  if (obj.model) count(materialCount, obj.model);
  if (obj.particle) count(materialCount, obj.particle);
  if (obj.effects) {
    for (const ef of obj.effects) {
      count(effectCount, ef.name || ef.file);
      if (ef.file) count(shaderCount, ef.file);
    }
  }
  if (obj.animationlayers) {
    for (const a of obj.animationlayers) {
      count(animCount, a.name || (a.options && a.options.name) || 'unnamed');
    }
  }
  for (const child of (obj.particles || [])) {
    if (child && child.name) count(materialCount, 'particle:' + child.name);
  }
}
objects.forEach(walk);

const printMap = (title, map, limit = 20) => {
  console.log('\n[' + title + ']');
  if (!map.size) {
    console.log('  （无）');
    return;
  }
  [...map.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .forEach(([k, v]) => console.log('  ' + String(v).padStart(4) + '  ' + k));
};

printMap('图层/材质来源', materialCount);
printMap('效果 (effects)', effectCount);
printMap('动画层类型 (animationlayers)', animCount);

// ---- 通用属性（可自定义项）----
if (doc.general && doc.general.properties) {
  const props = Object.entries(doc.general.properties);
  console.log('\n[可自定义属性] 共 ' + props.length + ' 项');
  for (const [key, v] of props.slice(0, 25)) {
    const bits = [];
    if (v.type) bits.push(v.type);
    if (v.value !== undefined && typeof v.value !== 'object') bits.push('默认=' + v.value);
    if (v.min !== undefined) bits.push('范围 ' + v.min + '..' + v.max);
    console.log('  ' + key.padEnd(24) + bits.join('  '));
  }
}

// ---- 顶层对象概览（名称 / 尺寸 / 是否有内容）----
console.log('\n[顶层对象前 20 个]');
objects.slice(0, 20).forEach((o, i) => {
  const kind = o.image ? '图片' : o.text ? '文本' : o.particle ? '粒子' : o.model ? '模型' : o.light ? '灯光' : '组';
  const size = o.size ? `${Math.round(o.size[0])}x${Math.round(o.size[1])}` : '-';
  const effects = (o.effects || []).map(e => e.name || e.file).join(',');
  console.log('  ' + String(i).padStart(3) + '  ' + kind.padEnd(4) + ' ' + size.padEnd(11) +
    ' name=' + JSON.stringify(o.name || '') + (effects ? '  effects=' + effects : ''));
});
