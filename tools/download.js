// 下载便携工具链压缩包（JDK / Android cmdline-tools / Gradle / platform-tools）。
//
// 为什么用 Node 下载：Windows 的 schannel 在部分受限环境里拿不到凭证
// （curl 报 SEC_E_NO_CREDENTIALS），而 Node 自带 TLS 栈可以正常访问。
// 多源回退：Adoptium 的 API 会重定向到 GitHub，国内网络常失败，因此给了镜像备选。
'use strict';
const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

// 本文件位于 <repo>/tools/，工具链目录固定为 <repo>/.toolchain/
const ROOT = path.join(__dirname, '..', '.toolchain');
const DL = path.join(ROOT, 'dl');

const ITEMS = [
  {
    name: 'jdk17',
    file: 'jdk17-win-x64.zip',
    urls: [
      'https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip',
      'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip',
      'https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip',
    ],
  },
  {
    name: 'cmdline-tools',
    file: 'commandlinetools-win.zip',
    urls: [
      'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
    ],
  },
  {
    name: 'gradle',
    file: 'gradle-8.7-bin.zip',
    urls: ['https://services.gradle.org/distributions/gradle-8.7-bin.zip'],
  },
  {
    name: 'platform-tools',
    file: 'platform-tools-win.zip',
    urls: ['https://dl.google.com/android/repository/platform-tools-latest-windows.zip'],
  },
];

function fmt(n) {
  if (n > 1024 * 1024 * 1024) return (n / 1073741824).toFixed(2) + 'GB';
  if (n > 1024 * 1024) return (n / 1048576).toFixed(1) + 'MB';
  if (n > 1024) return (n / 1024).toFixed(0) + 'KB';
  return n + 'B';
}

function fetchTo(url, dest, depth = 0) {
  return new Promise((resolve, reject) => {
    if (depth > 8) return reject(new Error('too many redirects'));
    const lib = url.startsWith('http:') ? http : https;
    const req = lib.get(
      url,
      { headers: { 'User-Agent': 'dsh-toolchain-download', Accept: '*/*' }, timeout: 60000 },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          const next = new URL(res.headers.location, url).toString();
          res.resume();
          return resolve(fetchTo(next, dest, depth + 1));
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error('HTTP ' + res.statusCode));
        }
        const total = Number(res.headers['content-length'] || 0);
        const tmp = dest + '.part';
        const out = fs.createWriteStream(tmp);
        let got = 0;
        let lastPct = -10;
        res.on('data', (c) => {
          got += c.length;
          if (total) {
            const pct = Math.floor((got / total) * 100);
            if (pct >= lastPct + 10) {
              lastPct = pct;
              console.log(`PROGRESS ${path.basename(dest)} ${pct}% ${fmt(got)}/${fmt(total)}`);
            }
          }
        });
        res.pipe(out);
        out.on('finish', () => {
          out.close(() => {
            fs.renameSync(tmp, dest);
            resolve({ bytes: got, total });
          });
        });
        out.on('error', reject);
        res.on('error', reject);
      }
    );
    req.on('timeout', () => req.destroy(new Error('socket timeout')));
    req.on('error', reject);
  });
}

(async () => {
  fs.mkdirSync(DL, { recursive: true });
  console.log('DL_DIR ' + DL);
  for (const item of ITEMS) {
    const dest = path.join(DL, item.file);
    if (fs.existsSync(dest) && fs.statSync(dest).size > 1024 * 1024) {
      console.log(`SKIP ${item.name} (${fmt(fs.statSync(dest).size)} already present)`);
      continue;
    }
    const urls = item.urls || [item.url];
    let ok = false;
    for (const url of urls) {
      console.log(`START ${item.name} ${url}`);
      const t0 = Date.now();
      try {
        const r = await fetchTo(url, dest);
        const secs = ((Date.now() - t0) / 1000).toFixed(1);
        console.log(`DONE ${item.name} ${fmt(r.bytes)} in ${secs}s -> ${dest}`);
        ok = true;
        break;
      } catch (e) {
        console.log(`FAIL ${item.name} :: ${e.message} (trying next source)`);
      }
    }
    if (!ok) {
      console.log(`GIVE_UP ${item.name}`);
      process.exitCode = 1;
    }
  }
  const total = fs.readdirSync(DL).reduce((a, f) => a + fs.statSync(path.join(DL, f)).size, 0);
  console.log('TOTAL_DOWNLOADED ' + fmt(total));
  console.log('DONE_ALL');
})();
