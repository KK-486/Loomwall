/*
 * LWPKit 开发桥：静态服务 + adb 推送。
 *
 * 为什么需要它：浏览器不能直接调用 adb。这个 Node 服务做两件事——
 *   1. 把 tools/web-editor 当静态站点发出去（和之前 python -m http.server 一样，端口不变）
 *   2. 提供 /api/status（识别手机）与 /api/push（把当前预设直接写进手机）
 *
 * 约束：本项目零第三方依赖，所以只用 Node 内置模块。
 * 另外所有外部命令的输出都**重定向到文件**再读，不使用管道（受限环境下管道会失败）。
 */
'use strict';

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..');
const WEB_DIR = path.join(ROOT, 'tools', 'web-editor');
const ADB = path.join(ROOT, '.toolchain', 'android-sdk', 'platform-tools', 'adb.exe');
const PKG = 'com.loomwall.wallpaper';
const REMOTE_TMP = '/data/local/tmp/lwpkit-import';
const PORT = Number(process.env.LWPKIT_PORT || 3009);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
};

/** 执行一条 shell 命令，输出写到临时文件再读回来（避开管道限制）。 */
function runShell(command, timeoutMs = 20000) {
  const outFile = path.join(os.tmpdir(), `lwpkit-${process.pid}-${Date.now()}.txt`);
  const line = `${command} > "${outFile}" 2>&1`;
  try {
    execSync(line, { stdio: 'ignore', shell: 'cmd.exe', timeout: timeoutMs, windowsHide: true });
  } catch (error) {
    // 命令本身失败没关系，输出文件里会有原因
  }
  let text = '';
  try {
    text = fs.readFileSync(outFile, 'utf8');
  } catch {
    text = '';
  }
  try {
    fs.unlinkSync(outFile);
  } catch {
    /* 忽略 */
  }
  return text.trim();
}

function adbAvailable() {
  return fs.existsSync(ADB);
}

/** 解析 `adb devices`。 */
function listDevices() {
  if (!adbAvailable()) return [];
  const out = runShell(`"${ADB}" devices`);
  return out
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('*'))
    .map((line) => {
      const parts = line.split(/\s+/);
      return { serial: parts[0], state: parts[1] || '' };
    })
    .filter((d) => d.serial && d.state === 'device');
}

function deviceModel(serial) {
  const model = runShell(`"${ADB}" -s ${serial} shell getprop ro.product.model`, 8000).trim();
  const version = runShell(`"${ADB}" -s ${serial} shell getprop ro.build.version.release`, 8000).trim();
  return { model, android: version };
}

/** 带缓存的设备查询：页面会轮询，别每次都敲 adb。 */
let deviceCache = { at: 0, devices: [], adbOk: adbAvailable() };
function status() {
  const now = Date.now();
  if (now - deviceCache.at < 2000) return deviceCache;
  const devices = listDevices();
  if (devices.length) {
    const extra = deviceModel(devices[0].serial);
    devices[0] = { ...devices[0], ...extra };
  }
  deviceCache = { at: now, devices, adbOk: adbAvailable() };
  return deviceCache;
}

const INSTALL_SCRIPT = `set -e
cd files
rm -rf presets/active/resources
mkdir -p presets/active/resources
cp ${REMOTE_TMP}/scene.json presets/active/scene.json
cp ${REMOTE_TMP}/interactions.json presets/active/interactions.json
cp ${REMOTE_TMP}/manifest.json presets/active/manifest.json
if [ -d ${REMOTE_TMP}/resources ]; then
  cp ${REMOTE_TMP}/resources/* presets/active/resources/ 2>/dev/null || true
fi
ls -l presets/active
echo PUSH_DONE
`;

/** 把单文件预设包拆成目录，然后推进手机。 */
function pushBundle(bundle) {
  if (!adbAvailable()) return { ok: false, message: '找不到 adb（.toolchain 里没有 platform-tools）' };
  const devices = listDevices();
  if (!devices.length) return { ok: false, message: '没有检测到已连接且已授权的手机' };

  if (!bundle || !bundle.scene) return { ok: false, message: '预设里没有 scene' };

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lwpkit-push-'));
  try {
    fs.writeFileSync(path.join(tmp, 'scene.json'), JSON.stringify(bundle.scene, null, 2), 'utf8');
    fs.writeFileSync(
      path.join(tmp, 'interactions.json'),
      JSON.stringify(bundle.interactions || { formatVersion: '0.2', animations: [], events: [] }, null, 2),
      'utf8'
    );
    fs.writeFileSync(
      path.join(tmp, 'manifest.json'),
      JSON.stringify(bundle.manifest || { formatVersion: '0.2', engineMinVersion: '0.3.0', name: 'web', author: 'web', license: 'Apache-2.0' }, null, 2),
      'utf8'
    );

    // 图片等资源：data URL → 真实文件
    const resources = bundle.resources || {};
    const keys = Object.keys(resources);
    if (keys.length) {
      const resDir = path.join(tmp, 'resources');
      fs.mkdirSync(resDir, { recursive: true });
      for (const key of keys) {
        const raw = String(resources[key] || '');
        const base64 = raw.includes('base64,') ? raw.split('base64,')[1] : '';
        if (!base64) continue;
        fs.writeFileSync(path.join(resDir, key.replace(/[\\/:*?"<>|]/g, '_')), Buffer.from(base64, 'base64'));
      }
    }

    const serial = devices[0].serial;
    const scriptPath = path.join(tmp, 'install.sh');
    fs.writeFileSync(scriptPath, INSTALL_SCRIPT, 'utf8');

    const pushOut = runShell(`"${ADB}" -s ${serial} push "${tmp}" ${REMOTE_TMP}`, 60000);
    runShell(`"${ADB}" -s ${serial} push "${scriptPath}" /data/local/tmp/lwpkit-install.sh`, 20000);
    const installOut = runShell(
      `"${ADB}" -s ${serial} shell "run-as ${PKG} sh /data/local/tmp/lwpkit-install.sh"`,
      60000
    );

    const ok = installOut.includes('PUSH_DONE');
    return {
      ok,
      message: ok
        ? `已推送到 ${serial}（资源 ${keys.length} 个），回桌面即生效`
        : `安装失败：${(installOut || pushOut).split('\n').slice(-4).join(' / ')}`,
      log: installOut,
    };
  } finally {
    try {
      fs.rmSync(tmp, { recursive: true, force: true });
    } catch {
      /* 忽略 */
    }
  }
}

function serveStatic(req, res) {
  const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
  const rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
  const file = path.join(WEB_DIR, rel);
  if (!file.startsWith(WEB_DIR)) {
    res.writeHead(403).end('forbidden');
    return;
  }
  fs.readFile(file, (error, data) => {
    if (error) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      // 开发期一律不缓存，免得改了脚本刷新还是旧的
      'Cache-Control': 'no-store',
    });
    res.end(data);
  });
}

function readBody(req) {
  return new Promise((resolve) => {
    let text = '';
    req.on('data', (chunk) => {
      text += chunk;
      if (text.length > 64 * 1024 * 1024) req.destroy();
    });
    req.on('end', () => resolve(text));
    req.on('error', () => resolve(''));
  });
}

const server = http.createServer(async (req, res) => {
  const url = (req.url || '/').split('?')[0];
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'content-type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204).end();
    return;
  }

  if (url === '/api/status') {
    const s = status();
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(s));
    return;
  }

  if (url === '/api/push' && req.method === 'POST') {
    const body = await readBody(req);
    let bundle = null;
    try {
      bundle = JSON.parse(body);
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, message: '请求体不是合法 JSON' }));
      return;
    }
    const result = pushBundle(bundle);
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(result));
    return;
  }

  serveStatic(req, res);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`LWPKit 开发桥已启动：http://127.0.0.1:${PORT}/`);
  console.log(`adb: ${adbAvailable() ? ADB : '未找到'}`);
  const s = status();
  console.log(s.devices.length ? `已连接：${s.devices[0].serial} ${s.devices[0].model || ''}` : '当前没有连接的手机');
});
