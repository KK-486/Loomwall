// 开发期用的静态服务器：直接读磁盘目录，改完刷新即可看到（无缓存）。
//
// 为什么不直接用 `python -m http.server`：这台机器上的 python 是
// Microsoft Store 的 App Execution Alias，在"隐藏窗口/后台"启动时会立即退出，
// 用 pythonw 又因为 stderr 无效导致每个请求抛异常被掐断。
// Node 版本行为确定，且项目本机已有 Node。
//
// 用法：node tools/web-editor/serve.js [端口]
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const PORT = Number(process.argv[2]) || 3009;
const HOST = '127.0.0.1';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2'
};

function send(res, status, type, body) {
  res.writeHead(status, {
    'Content-Type': type,
    'Content-Length': Buffer.byteLength(body),
    // 开发工具：禁缓存，改完刷新立即生效
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

// ---------------------------------------------------------------- ADB（USB 连接）
// 浏览器不能起 adb，所以由本地服务代跑：forward 之后编辑器直连 127.0.0.1:PORT
const { execFileSync } = require('node:child_process');

function findAdb() {
  const cands = [
    process.env.ADB,
    process.env.ANDROID_HOME && path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe'),
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
    path.resolve(__dirname, '..', '..', '.toolchain', 'android-sdk', 'platform-tools', 'adb.exe'),
    'adb',
  ].filter(Boolean);
  for (const c of cands) {
    try {
      execFileSync(c, ['version'], { stdio: 'ignore', timeout: 4000 });
      return c;
    } catch { /* 试下一个 */ }
  }
  return null;
}

function adbRun(adb, args) {
  try {
    const out = execFileSync(adb, args, { encoding: 'utf8', timeout: 12000, windowsHide: true });
    return { ok: true, out };
  } catch (err) {
    return { ok: false, error: (err && (err.stdout || err.message)) || 'adb 执行失败' };
  }
}

function handleAdb(res, urlPath, query) {
  const json = (obj) => send(res, 200, 'application/json; charset=utf-8', JSON.stringify(obj));
  const adb = findAdb();
  if (!adb) {
    json({ ok: false, error: '没找到 adb.exe（可在 tools/bootstrap.cmd 里装 SDK，或把 adb 加进 PATH）' });
    return true;
  }
  if (urlPath === '/adb/devices') {
    const r = adbRun(adb, ['devices', '-l']);
    if (!r.ok) { json({ ok: false, error: r.error }); return true; }
    const devices = r.out.split(/\r?\n/).slice(1)
      .map(l => l.trim()).filter(Boolean)
      .map(l => {
        const parts = l.split(/\s+/);
        return { serial: parts[0], state: parts[1] || '', model: (l.match(/model:(\S+)/) || [])[1] || '' };
      });
    json({ ok: true, adb, devices });
    return true;
  }
  if (urlPath === '/adb/connect') {
    const port = Number(query.get('port') || 18765);
    const r = adbRun(adb, ['devices', '-l']);
    if (!r.ok) { json({ ok: false, error: r.error }); return true; }
    const ready = r.out.split(/\r?\n/).slice(1).map(l => l.trim()).filter(Boolean)
      .map(l => l.split(/\s+/)).filter(p => p[1] === 'device');
    if (!ready.length) {
      const unauthorized = r.out.includes('unauthorized');
      json({ ok: false, error: unauthorized ? '手机未授权 USB 调试：在手机上点「允许」' : '没检测到 USB 设备（数据线插了吗？USB 调试开了吗？）' });
      return true;
    }
    const serial = ready[0][0];
    const fw = adbRun(adb, ['-s', serial, 'forward', 'tcp:' + port, 'tcp:8765']);
    if (!fw.ok) { json({ ok: false, error: fw.error }); return true; }
    json({ ok: true, adb, serial, base: '127.0.0.1:' + port });
    return true;
  }
  if (urlPath === '/adb/disconnect') {
    const port = Number(query.get('port') || 18765);
    adbRun(adb, ['forward', '--remove', 'tcp:' + port]);
    json({ ok: true });
    return true;
  }
  return false;
}

const server = http.createServer((req, res) => {
  let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
  const query = new URLSearchParams((req.url || '').split('?')[1] || '');
  if (urlPath.startsWith('/adb/') && handleAdb(res, urlPath, query)) return;
  if (urlPath === '/' || urlPath === '') urlPath = '/index.html';

  const filePath = path.join(ROOT, urlPath);
  // 目录穿越保护：解析后的路径必须仍在 ROOT 之下
  if (!filePath.startsWith(ROOT)) {
    send(res, 403, 'text/plain; charset=utf-8', '403 Forbidden');
    return;
  }

  fs.readFile(filePath, (err, buf) => {
    if (err) {
      // 未知路径回落到首页，避免刷新深层链接 404
      fs.readFile(path.join(ROOT, 'index.html'), (err2, index) => {
        if (err2) {
          send(res, 404, 'text/plain; charset=utf-8', '404 Not Found');
          return;
        }
        send(res, 200, MIME['.html'], index);
      });
      return;
    }
    send(res, 200, MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream', buf);
  });
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error('端口 ' + PORT + ' 已被占用（可能已有一份服务在跑）');
  } else {
    console.error('启动失败： ' + e.message);
  }
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  console.log('LWPKit 编辑器（开发模式，读磁盘）');
  console.log('  地址: http://' + HOST + ':' + PORT + '/');
  console.log('  目录: ' + ROOT);
});
