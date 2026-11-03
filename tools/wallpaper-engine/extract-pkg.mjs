// 解析 Wallpaper Engine scene.pkg 并导出内容。
//
// 结构（从字节反推 + 解包验证）：
//   u32   ?            （= 8，疑似表头长度/版本相关）
//   char4 "PKGV"
//   char4 版本字符串    （"0024"）
//   u32   条目数
//   条目表：每条 = [u32 名长][名字 UTF-8][u32 数据偏移][u32 数据长度]
// 条目数据区的偏移是**相对 dataBase**（条目表结束的位置），不是绝对文件偏移。
//
// 用法：
//   node extract-pkg.mjs <scene.pkg> [输出目录]     解包全部
//   node extract-pkg.mjs <scene.pkg> --list          只列条目
'use strict';

import { openSync, readSync, closeSync, statSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, extname } from 'node:path';

const pkgPath = process.argv[2];
const outDir = process.argv[3] && !process.argv[3].startsWith('--') ? process.argv[3] : null;
const listOnly = process.argv.includes('--list');

if (!pkgPath) {
  console.error('用法: node extract-pkg.mjs <scene.pkg> [输出目录|--list]');
  process.exit(2);
}

const fd = openSync(pkgPath, 'r');
const fileSize = statSync(pkgPath).size;

function readAt(offset, length) {
  const buf = Buffer.alloc(length);
  readSync(fd, buf, 0, length, offset);
  return buf;
}

// ---- 头 ----
const header = readAt(0, 12);
const magic = header.subarray(4, 8).toString('latin1');
if (magic !== 'PKGV') {
  console.error('不是 PKGV 容器，magic=' + JSON.stringify(magic));
  process.exit(1);
}
const version = header.subarray(8, 12).toString('latin1');
const count = readAt(12, 4).readUInt32LE(0);
console.log('容器: PKGV 版本 ' + version + '，条目数 ' + count);

// ---- 条目表 ----
const entries = [];
let cursor = 16;
for (let i = 0; i < count; i++) {
  const lenBuf = readAt(cursor, 4);
  const nameLen = lenBuf.readUInt32LE(0);
  cursor += 4;
  if (nameLen === 0 || nameLen > 4096) {
    console.error('条目 ' + i + ' 名长异常: ' + nameLen + '（位置 ' + (cursor - 4) + '）');
    break;
  }
  const name = readAt(cursor, nameLen).toString('utf8');
  cursor += nameLen;
  const meta = readAt(cursor, 8);
  const offset = meta.readUInt32LE(0);
  const length = meta.readUInt32LE(4);
  cursor += 8;
  entries.push({ name, offset, length });
}

// 数据区起点 = 条目表结束位置（所有 offset 都相对这里）
const dataBase = cursor;
for (const e of entries) e.offset += dataBase;

console.log('解析到条目 ' + entries.length + ' 条');
const bad = entries.filter(e => e.offset + e.length > fileSize || e.length === 0);
if (bad.length) console.log('⚠ 可疑条目 ' + bad.length + ' 条（偏移/长度越界）');

// ---- 按扩展名统计 ----
const byExt = new Map();
for (const e of entries) {
  const ext = (extname(e.name) || '(无)').toLowerCase();
  const rec = byExt.get(ext) || { n: 0, bytes: 0 };
  rec.n++;
  rec.bytes += e.length;
  byExt.set(ext, rec);
}
console.log('\n[内容构成]');
for (const [ext, rec] of [...byExt.entries()].sort((a, b) => b[1].bytes - a[1].bytes)) {
  console.log('  ' + ext.padEnd(10) + String(rec.n).padStart(4) + ' 个   ' + (rec.bytes / 1024 / 1024).toFixed(2) + ' MB');
}

// ---- 顶层目录分布 ----
const byTop = new Map();
for (const e of entries) {
  const top = e.name.includes('/') ? e.name.split('/')[0] + '/' : '(根)';
  byTop.set(top, (byTop.get(top) || 0) + 1);
}
console.log('\n[顶层分布]');
for (const [top, n] of byTop) console.log('  ' + top.padEnd(16) + n + ' 个');

if (listOnly || !outDir) {
  console.log('\n[前 40 条]');
  entries.slice(0, 40).forEach(e => console.log('  ' + String(e.length).padStart(8) + '  ' + e.name));
  closeSync(fd);
  console.log('\n（加输出目录参数即可解包）');
  process.exit(0);
}

// ---- 解包 ----
mkdirSync(outDir, { recursive: true });
let written = 0;
for (const e of entries) {
  if (e.offset + e.length > fileSize) continue;
  const target = join(outDir, e.name);
  mkdirSync(dirname(target), { recursive: true });
  const data = readAt(e.offset, e.length);
  writeFileSync(target, data);
  written++;
}
closeSync(fd);
console.log('\n已解包 ' + written + ' 个文件到 ' + outDir);
