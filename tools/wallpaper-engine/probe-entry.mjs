// 定位某个条目的真实数据：验证 offset 是绝对偏移还是相对 dataBase，以及是否被压缩。
//
// 做法：两个候选位置各 dump 一段，再在附近找 '{' 尝试 JSON 解析，
// 用"能不能解析成 JSON"作为判定依据，而不是猜。
//
// 用法：node probe-entry.mjs <scene.pkg> [条目名，默认 scene.json]
'use strict';

import { openSync, readSync, closeSync, statSync } from 'node:fs';

const pkgPath = process.argv[2];
const wantName = process.argv[3] || 'scene.json';
if (!pkgPath) {
  console.error('用法: node probe-entry.mjs <scene.pkg> [条目名]');
  process.exit(2);
}

const fd = openSync(pkgPath, 'r');
const fileSize = statSync(pkgPath).size;
const readAt = (off, len) => {
  const b = Buffer.alloc(Math.min(len, fileSize - off));
  readSync(fd, b, 0, b.length, off);
  return b;
};

const header = readAt(0, 16);
const count = header.readUInt32LE(12);
const entries = [];
let cursor = 16;
for (let i = 0; i < count; i++) {
  const nameLen = readAt(cursor, 4).readUInt32LE(0);
  cursor += 4;
  const name = readAt(cursor, nameLen).toString('utf8');
  cursor += nameLen;
  const meta = readAt(cursor, 8);
  cursor += 8;
  entries.push({ name, offset: meta.readUInt32LE(0), length: meta.readUInt32LE(4) });
}
const dataBase = cursor;
console.log('条目数 ' + entries.length + '，条目表结束于 ' + dataBase + '，文件 ' + fileSize + ' 字节');

const entry = entries.find(e => e.name === wantName);
if (!entry) {
  console.error('找不到条目 ' + wantName);
  process.exit(1);
}
console.log('目标: ' + entry.name + '  offset=' + entry.offset + '  length=' + entry.length);

const ascii = b => b.toString('latin1').replace(/[^\x20-\x7e]/g, '.');
const hex = b => b.subarray(0, 48).toString('hex').replace(/(.{32})/g, '$1 ');

function inspect(label, absolute) {
  if (absolute < 0 || absolute >= fileSize) {
    console.log('\n[' + label + '] 越界');
    return null;
  }
  const buf = readAt(absolute, Math.min(entry.length, 512));
  console.log('\n[' + label + '] 绝对位置 ' + absolute);
  console.log('  hex:   ' + hex(buf));
  console.log('  ascii: ' + ascii(buf.subarray(0, 120)));
  const firstBrace = buf.indexOf(0x7b);
  console.log('  首个 "{" 在块内偏移: ' + firstBrace);
  return { buf, absolute };
}

const absCandidate = inspect('候选 A：offset 视为绝对偏移', entry.offset);
const relCandidate = inspect('候选 B：offset 相对 dataBase', dataBase + entry.offset);

// 尝试在候选位置找到可解析的 JSON
function tryJson(cand, label) {
  if (!cand) return;
  const full = readAt(cand.absolute, entry.length);
  for (let start = 0; start < Math.min(4096, full.length); start++) {
    if (full[start] !== 0x7b) continue;
    const slice = full.subarray(start).toString('utf8');
    // 只试前若干处，避免大文件里逐个 '{' 解析
    try {
      const parsed = JSON.parse(slice);
      console.log('  → ' + label + ' 在块内 ' + start + ' 处解析成功（顶层键: ' +
        Object.keys(parsed).slice(0, 6).join(', ') + '）');
      return parsed;
    } catch { /* 继续找 */ }
    if (start > 0) break; // 只试第一个 '{'
  }
  console.log('  → ' + label + ' 无法解析为 JSON（可能是压缩数据）');
}

console.log('\n[JSON 可解析性]');
const a = tryJson(absCandidate, '候选 A');
if (!a) tryJson(relCandidate, '候选 B');
closeSync(fd);
