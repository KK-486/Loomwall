/*
 * 图标来源：Tabler Icons（MIT），经 Iconify API 拉取。
 *
 * 只保存 SVG：体积小、颜色可改。**不需要在电脑上预渲染 PNG**——
 * 网页编辑器在「插入图标」时用 canvas 把 SVG 光栅化成 PNG，
 * 这样存进预设的资源就是手机端能直接解码的 PNG。
 *
 * 用法：node tools/icons/build-icons.js
 */
'use strict';

const https = require('https');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const OUT = path.join(ROOT, 'tools', 'web-editor', 'icons');

/**
 * 品牌图标：Simple Icons（CC0 1.0，图标文件本身公有领域）。
 * 注意：商标归各自公司所有；用于"指向该应用"是合理使用，不能暗示官方关联。
 */
const BRANDS = {
  wechat: '微信',
  qq: 'QQ',
  tiktok: '抖音 / TikTok',
  bilibili: '哔哩哔哩',
  kuaishou: '快手',
  sinaweibo: '微博',
  zhihu: '知乎',
  xiaohongshu: '小红书',
  neteasecloudmusic: '网易云音乐',
  alipay: '支付宝',
  taobao: '淘宝',
  telegram: 'Telegram',
  discord: 'Discord',
  youtube: 'YouTube',
  spotify: 'Spotify',
  instagram: 'Instagram',
  facebook: 'Facebook',
  github: 'GitHub',
  googlechrome: 'Chrome',
  whatsapp: 'WhatsApp',
  x: 'X / Twitter',
  netflix: 'Netflix',
  steam: 'Steam',
  twitch: 'Twitch',
  reddit: 'Reddit',
  linkedin: 'LinkedIn',
  pinterest: 'Pinterest',
  soundcloud: 'SoundCloud',
  applemusic: 'Apple Music',
  googleplay: 'Google Play',
};
/** 系统/工具图标（Tabler 细线风格，和壁纸 UI 更搭）。 */
const ICONS = {
  home: '主页',
  moon: '夜间模式',
  sun: '白天模式',
  headphones: '耳机',
  calendar: '日历',
  clock: '时钟',
  camera: '相机',
  photo: '图库',
  world: '浏览器',
  settings: '设置',
  music: '音乐',
  phone: '电话',
  message: '消息',
  mail: '邮件',
  user: '用户',
  search: '搜索',
  bell: '通知',
  battery: '电池',
  wifi: 'Wi-Fi',
  bluetooth: '蓝牙',
  heart: '收藏',
  star: '星标',
  folder: '文件',
  'map-pin': '定位',
  calculator: '计算器',
  cloud: '云',
  link: '链接',
  lock: '锁屏',
};

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    https
      .get(url, (res) => {
        if (res.statusCode !== 200) {
          reject(new Error(`HTTP ${res.statusCode}`));
          res.resume();
          return;
        }
        let text = '';
        res.on('data', (chunk) => (text += chunk));
        res.on('end', () => {
          try {
            resolve(JSON.parse(text));
          } catch (e) {
            reject(new Error(`响应不是 JSON：${e.message}`));
          }
        });
      })
      .on('error', reject);
  });
}

async function main() {
  fs.mkdirSync(path.join(OUT, 'svg'), { recursive: true });

  // 清掉上一版遗留的网格文件（早期尝试用无头浏览器渲染，已废弃）
  for (const stale of fs.readdirSync(OUT)) {
    if (stale.startsWith('grid-') || stale.startsWith('_grid-') || stale === 'layout.json') {
      runCatching(() => fs.unlinkSync(path.join(OUT, stale)));
    }
  }

  const catalog = [];
  let saved = 0;

  for (const [kind, prefix, source, table] of [
    ['system', 'tabler', 'https://tabler.io/icons', ICONS],
    ['brand', 'simple-icons', 'https://simpleicons.org', BRANDS],
  ]) {
    const names = Object.keys(table);
    console.log(`拉取 ${kind} 图标 ${names.length} 个（${prefix}）…`);
    let icons = {};
    try {
      const data = await fetchJson(`https://api.iconify.design/${prefix}.json?icons=${names.join(',')}`);
      icons = data.icons || {};
    } catch (error) {
      console.warn(`  ! ${prefix} 拉取失败：${error.message}`);
      continue;
    }

    for (const [name, label] of Object.entries(table)) {
      const icon = icons[name];
      if (!icon) {
        console.warn(`  ! 拉不到 ${prefix}:${name}`);
        continue;
      }
      const w = icon.width || 24;
      const h = icon.height || 24;
      // currentColor 留给页面替换成目标颜色（Simple Icons 是 fill，Tabler 是 stroke）
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}">${icon.body}</svg>`;
      const fileName = kind === 'brand' ? `brand-${name}` : name;
      fs.writeFileSync(path.join(OUT, 'svg', `${fileName}.svg`), svg, 'utf8');
      catalog.push({ name: fileName, label, kind, viewBox: `0 0 ${w} ${h}`, source });
      saved++;
    }
  }

  fs.writeFileSync(
    path.join(OUT, 'catalog.json'),
    JSON.stringify({ licenses: ['MIT (Tabler Icons)', 'CC0 1.0 (Simple Icons)'], icons: catalog }, null, 2),
    'utf8'
  );
  console.log(`  已保存 ${saved} 个 SVG + catalog.json → ${OUT}`);
}

function runCatching(fn) {
  try {
    fn();
  } catch {
    /* 删除失败不影响构建 */
  }
}

main().catch((error) => {
  console.error('构建失败：', error.message);
  process.exit(1);
});
