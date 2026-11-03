/*
 * 变量 / 函数目录 —— **唯一真相**。
 *
 * 面板、文档与引擎的变量表都从这里来：加一个函数 = 这里加一行 + 引擎里实现一个 case。
 *
 * 字段：
 *   key        标识（变量名或函数名）
 *   kind       'var' 变量 | 'fn' 函数
 *   category   分类（面板上的筛选标签）
 *   signature  调用形态，面板与文档直接展示
 *   placeholder 插入到文本里的示例
 *   desc       一句话说明
 *   implemented 引擎是否已实现；false 的会被标灰并禁止插入
 */
const CATALOG = [
  // ---- 时间 ----
  {
    key: 'sys.time.hour', kind: 'var', category: '时间', signature: 'sys.time.hour',
    placeholder: '{sys.time.hour:00}', desc: '当前小时（0-23），:00 表示补零到两位',
    implemented: true,
  },
  {
    key: 'sys.time.minute', kind: 'var', category: '时间', signature: 'sys.time.minute',
    placeholder: '{sys.time.minute:00}', desc: '当前分钟（0-59）',
    implemented: true,
  },
  {
    key: 'df', kind: 'fn', category: '时间', signature: 'df(HH:mm)',
    placeholder: '{df(HH:mm)}',
    desc: '按格式输出当前日期时间：HH:mm / MM月dd日 / EEE 等',
    implemented: true,
  },

  // ---- 电池 ----
  {
    key: 'sys.battery.level', kind: 'var', category: '电池', signature: 'sys.battery.level',
    placeholder: '{sys.battery.level:0}', desc: '电量百分比（0-100），:0 表示取整',
    implemented: true,
  },
  {
    key: 'sys.battery.charging', kind: 'var', category: '电池', signature: 'sys.battery.charging',
    placeholder: '{sys.battery.charging:0}', desc: '是否在充电：1 是 / 0 否',
    implemented: true,
  },
  {
    key: 'sys.battery.temp', kind: 'var', category: '电池', signature: 'sys.battery.temp',
    placeholder: '{sys.battery.temp:0.0}°C', desc: '电池温度（摄氏度）',
    implemented: true,
  },
  {
    key: 'sys.power.connected', kind: 'var', category: '电池', signature: 'sys.power.connected',
    placeholder: '{sys.power.connected:0}', desc: '是否接着电源：1 / 0',
    implemented: true,
  },

  // ---- 屏幕 ----
  {
    key: 'sys.screen.on', kind: 'var', category: '屏幕', signature: 'sys.screen.on',
    placeholder: '{sys.screen.on:0}', desc: '屏幕是否点亮：1 / 0',
    implemented: true,
  },

  // ---- 网络 ----
  {
    key: 'sys.net.connected', kind: 'var', category: '网络', signature: 'sys.net.connected',
    placeholder: '{sys.net.connected:0}', desc: '是否联网：1 / 0',
    implemented: true,
  },

  // ---- 性能 ----
  {
    key: 'sys.mem.usedPct', kind: 'var', category: '性能', signature: 'sys.mem.usedPct',
    placeholder: '内存 {sys.mem.usedPct:0}%', desc: '内存占用百分比',
    implemented: true,
  },
  {
    key: 'sys.mem.freeMb', kind: 'var', category: '性能', signature: 'sys.mem.freeMb',
    placeholder: '可用 {sys.mem.freeMb:0}MB', desc: '可用内存（MB）',
    implemented: true,
  },
  {
    key: 'sys.storage.usedPct', kind: 'var', category: '性能', signature: 'sys.storage.usedPct',
    placeholder: '存储 {sys.storage.usedPct:0}%', desc: '存储占用百分比',
    implemented: true,
  },
  {
    key: 'sys.storage.freeMb', kind: 'var', category: '性能', signature: 'sys.storage.freeMb',
    placeholder: '余 {sys.storage.freeMb:0}MB', desc: '可用存储（MB）',
    implemented: true,
  },
  {
    key: 'sys.render.fps', kind: 'var', category: '性能', signature: 'sys.render.fps',
    placeholder: '{sys.render.fps:0} FPS', desc: '实际渲染帧率',
    implemented: true,
  },

  // ---- 规划中（面板可见但禁止插入，避免做出“永远为空”的模板）----
  {
    key: 'sys.notify.count', kind: 'var', category: '系统', signature: 'sys.notify.count',
    placeholder: '{sys.notify.count:0}', desc: '未读通知条数（需在系统里开启通知使用权）',
    implemented: true,
  },
  {
    key: 'sys.media.title', kind: 'var', category: '系统', signature: 'sys.media.title',
    placeholder: '{sys.media.title}', desc: '当前播放的歌曲名（需通知使用权）',
    implemented: true,
  },
  {
    key: 'step.today', kind: 'var', category: '系统', signature: 'step.today',
    placeholder: '{step.today:0}', desc: '今日步数（需身体活动权限）',
    implemented: true,
  },
  {
    key: 'if', kind: 'fn', category: '条件', signature: 'if(条件, 真, 假)',
    placeholder: '{if(sys.battery.level > 20, "电量充足", "电量偏低")}',
    desc: '条件判断：条件非 0 取“真”，否则取“假”',
    implemented: true,
  },
  {
    key: 'si', kind: 'fn', category: '屏幕', signature: 'si(width|height)',
    placeholder: '{si(width)}', desc: '屏幕尺寸（画布单位）',
    implemented: false,
  },

  // ---- 音乐（通知使用权 + 在线匹配）----
  {
    key: 'sys.media.artist', kind: 'var', category: '音乐', signature: 'sys.media.artist',
    placeholder: '{sys.media.artist}', desc: '歌手', implemented: true,
  },
  {
    key: 'sys.media.album', kind: 'var', category: '音乐', signature: 'sys.media.album',
    placeholder: '{sys.media.album}', desc: '专辑', implemented: true,
  },
  {
    key: 'sys.media.app', kind: 'var', category: '音乐', signature: 'sys.media.app',
    placeholder: '{sys.media.app}', desc: '当前播放器（网易云音乐 / QQ音乐…，自动识别）', implemented: true,
  },
  {
    key: 'sys.media.lyric', kind: 'var', category: '音乐', signature: 'sys.media.lyric',
    placeholder: '{sys.media.lyric}',
    desc: '当前歌词行（按歌名自动匹配，空字符串表示这句没有歌词）', implemented: true,
  },
  {
    key: 'sys.media.lyricTrans', kind: 'var', category: '音乐', signature: 'sys.media.lyricTrans',
    placeholder: '{sys.media.lyricTrans}',
    desc: '当前歌词的译文（不是每首歌都有）', implemented: true,
  },
  {
    key: 'sys.media.lyricNext', kind: 'var', category: '音乐', signature: 'sys.media.lyricNext',
    placeholder: '{sys.media.lyricNext}', desc: '下一句歌词', implemented: true,
  },
  {
    key: 'sys.media.progress', kind: 'var', category: '音乐', signature: 'sys.media.progress',
    placeholder: '{sys.media.progress:0}', desc: '播放进度百分比（0-100）', implemented: true,
  },
  {
    key: 'sys.media.position', kind: 'var', category: '音乐', signature: 'sys.media.position',
    placeholder: '{sys.media.position:0}', desc: '播放位置（秒）', implemented: true,
  },
  {
    key: 'sys.media.duration', kind: 'var', category: '音乐', signature: 'sys.media.duration',
    placeholder: '{sys.media.duration:0}', desc: '歌曲总长（秒）', implemented: true,
  },
  {
    key: 'sys.media.playing', kind: 'var', category: '音乐', signature: 'sys.media.playing',
    placeholder: '{sys.media.playing:0}', desc: '是否正在播放（0/1）', implemented: true,
  },

  // ---- 天气（需定位权限，或在 App 里手动设置城市）----
  {
    key: 'sys.weather.temp', kind: 'var', category: '天气', signature: 'sys.weather.temp',
    placeholder: '{sys.weather.temp:0}', desc: '气温 °C（每 30 分钟更新）', implemented: true,
  },
  {
    key: 'sys.weather.feelsLike', kind: 'var', category: '天气', signature: 'sys.weather.feelsLike',
    placeholder: '{sys.weather.feelsLike:0}', desc: '体感温度 °C', implemented: true,
  },
  {
    key: 'sys.weather.humidity', kind: 'var', category: '天气', signature: 'sys.weather.humidity',
    placeholder: '{sys.weather.humidity:0}', desc: '相对湿度 %', implemented: true,
  },
  {
    key: 'sys.weather.text', kind: 'var', category: '天气', signature: 'sys.weather.text',
    placeholder: '{sys.weather.text}', desc: '中文天气描述（晴 / 多云 / 中雨…）', implemented: true,
  },
  {
    key: 'sys.weather.city', kind: 'var', category: '天气', signature: 'sys.weather.city',
    placeholder: '{sys.weather.city}', desc: '城市名（定位反查或手动设置）', implemented: true,
  },
  {
    key: 'sys.weather.district', kind: 'var', category: '天气', signature: 'sys.weather.district',
    placeholder: '{sys.weather.district}', desc: '区 / 县名（由定位反查，例如「清城区」）', implemented: true,
  },
  {
    key: 'sys.weather.wind', kind: 'var', category: '天气', signature: 'sys.weather.wind',
    placeholder: '{sys.weather.wind:0}', desc: '风速 km/h', implemented: true,
  },
  {
    key: 'sys.weather.precip', kind: 'var', category: '天气', signature: 'sys.weather.precip',
    placeholder: '{sys.weather.precip:0.0}', desc: '降水量 mm', implemented: true,
  },
  {
    key: 'sys.weather.isDay', kind: 'var', category: '天气', signature: 'sys.weather.isDay',
    placeholder: '{sys.weather.isDay:0}', desc: '是否白天（1/0），可用来切换昼夜配色', implemented: true,
  },
  {
    key: 'sys.weather.code', kind: 'var', category: '天气', signature: 'sys.weather.code',
    placeholder: '{sys.weather.code:0}',
    desc: 'WMO 代码（0 晴 / 2 多云 / 61 小雨 / 95 雷雨），适合用 if() 分派图标', implemented: true,
  },
  {
    key: 'sys.weather.updated', kind: 'var', category: '天气', signature: 'sys.weather.updated',
    placeholder: '{sys.weather.updated:0}', desc: '距上次更新的分钟数（999 = 从未拿到）', implemented: true,
  },

  // ---- 音频可视化（需要录音权限）----
  {
    key: 'audio', kind: 'fn', category: '音频', signature: 'audio(n)',
    placeholder: '{audio(3):0.00}',
    desc: '第 n 段频谱能量 0..1（n = 1..8，1 是最低频）。换不同的 n、绑不同属性就是不同形态',
    implemented: true,
  },
  {
    key: 'bass', kind: 'fn', category: '音频', signature: 'bass()',
    placeholder: '{bass():0.00}', desc: '低频能量 0..1（鼓点最敏感）', implemented: true,
  },
  {
    key: 'mid', kind: 'fn', category: '音频', signature: 'mid()',
    placeholder: '{mid():0.00}', desc: '中频能量 0..1（人声主要落在这一段）', implemented: true,
  },
  {
    key: 'treble', kind: 'fn', category: '音频', signature: 'treble()',
    placeholder: '{treble():0.00}', desc: '高频能量 0..1', implemented: true,
  },
  {
    key: 'level', kind: 'fn', category: '音频', signature: 'level()',
    placeholder: '{level():0.00}', desc: '总音量 0..1（含所有频段）', implemented: true,
  },
  {
    key: 'beat', kind: 'fn', category: '音频', signature: 'beat()',
    placeholder: '{beat():0.00}', desc: '节拍脉冲 0..1：每检出一拍置 1，然后衰减', implemented: true,
  },

];
