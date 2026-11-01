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
];
