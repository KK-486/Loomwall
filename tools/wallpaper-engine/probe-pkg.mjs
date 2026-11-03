// 探测 Wallpaper Engine 的 scene.pkg 容器结构。
//
// 目的：不靠"记忆里的格式文档"，直接从字节上验证。
// 做法：打印文件头、找关键字符串（scene.json / .tex / PKGV），
// 再围绕 "scene.json" 的偏移前后 dump 一段，用来反推条目表的布局。
//
// 用法：node probe-pkg.mjs <pkg 路径>
'use strict';

import { readFileSync, openSync, readSync, closeSync, statSync } from 'node:fs';

const path = process.argv[2];
if (!path) {
  console.error('用法: node probe-pkg.mjs <pkg 路径>');
  process.exit(2);
}

const size = statSync(path).size;
console.log('文件: ' + path);
console.log('大小: ' + (size / 1024 / 1024).toFixed(2) + ' MB');

// 只读文件头与一段样本，别把 260MB 全读进内存
const fd = openSync(path, 'r');
const head = Buffer.alloc(256);
readSync(fd, head, 0, head.length, 0);

const ascii = b => b.toString('latin1').replace(/[^\x20-\x7e]/g, '.');
console.log('\n[文件头 256 字节]');
console.log('  hex: ' + head.subarray(0, 64).toString('hex').replace(/(.{32})/g, '$1\n       '));
console.log('  ascii: ' + ascii(head.subarray(0, 64)));

const magic = head.subarray(0, 4).toString('latin1');
const version = head.readUInt32LE(4);
console.log('\n[头部猜测]');
console.log('  magic = ' + JSON.stringify(magic) + (magic === 'PKGV' ? ' ✓ 符合 PKGV 容器' : ' ✗ 不是预期的 PKGV'));
console.log('  version(offset 4, u32 LE) = ' + version);
console.log('  offset 8  u32 = ' + head.readUInt32LE(8) + ' / u64 = ' + head.readBigUInt64LE(8).toString());
console.log('  offset 16 u32 = ' + head.readUInt32LE(16) + ' / u64 = ' + head.readBigUInt64LE(16).toString());

// 全文件扫描关键字符串（分块读，避免一次吃掉内存）
const needles = ['scene.json', '.tex', 'PKGV', 'project.json', '.mp4', '.webm', 'shaders/'];
const found = new Map(needles.map(n => [n, []]));
const CHUNK = 4 * 1024 * 1024;
let pos = 0;
let carry = Buffer.alloc(0);
while (pos < size) {
  const len = Math.min(CHUNK, size - pos);
  const buf = Buffer.allocUnsafe(len);
  readSync(fd, buf, 0, len, pos);
  const window = Buffer.concat([carry, buf]);
  for (const needle of needles) {
    let idx = 0;
    while (true) {
      idx = window.indexOf(needle, idx);
      if (idx < 0) break;
      const absolute = pos - carry.length + idx;
      if (found.get(needle).length < 8) found.get(needle).push(absolute);
      idx += 1;
    }
  }
  carry = window.subarray(Math.max(0, window.length - 64));
  pos += len;
}
closeSync(fd);

console.log('\n[关键字符串出现位置（最多列 8 处）]');
for (const [needle, list] of found) {
  if (!list.length) {
    console.log('  ' + needle.padEnd(14) + ' — 未找到');
    continue;
  }
  console.log('  ' + needle.padEnd(14) + ' ' + list.join(', '));
}

// 围绕 scene.json 出现处 dump，用来反推条目表结构
const sceneHits = found.get('scene.json');
if (sceneHits.length) {
  const at = sceneHits[0];
  const fd2 = openSync(path, 'r');
  const start = Math.max(0, at - 160);
  const buf = Buffer.alloc(360);
  readSync(fd2, buf, 0, buf.length, start);
  closeSync(fd2);
  console.log('\n[scene.json 附近字节 · 起点 ' + start + ']');
  console.log('  ascii: ' + ascii(buf));
  console.log('  hex:   ' + buf.toString('hex').replace(/(.{64})/g, '$1\n         '));
}
