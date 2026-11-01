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

const server = http.createServer((req, res) => {
  let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
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
