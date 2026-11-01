// 简化测试连接：不建探活 socket（那会让 Node 在某些环境下直接崩溃 0xC0000409），
// 改成按 target 的 title/url 选页 —— CDP 的 /json/list 本来就带 title。
'use strict';

import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

const START = 'const pages = list';
const END = 'const ws = new WebSocket(page.webSocketDebuggerUrl);';

const NEW = `const page =
  list.find(t => t.type === 'page' && t.webSocketDebuggerUrl &&
                 t.url.includes('3009') && (t.title || '').includes('LWPKit')) ||
  list.find(t => t.type === 'page' && t.webSocketDebuggerUrl && t.url.includes('3009')) ||
  list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) { console.error('没有已加载编辑器的标签页'); process.exit(1); }

${END}`;

let n = 0;
for (const f of readdirSync(HERE).filter(n => /^check-.*\.mjs$/.test(n))) {
  const p = join(HERE, f);
  const src = readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
  const a = src.indexOf(START);
  const b = src.indexOf(END);
  if (a < 0 || b < 0) { console.log('· 跳过 ' + f); continue; }
  const out = src.slice(0, a) + NEW + src.slice(b + END.length);
  writeFileSync(p, out.replace(/\n/g, '\r\n'), 'utf8');
  n++;
}
console.log('✓ 简化 ' + n + ' 个 check 脚本的连接逻辑');
