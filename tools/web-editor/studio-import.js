/**
 * 把 `interactions.rules` 反向导入工作台。
 *
 * 为什么需要：工作台读的是 `interactions.studio`（节点图），而预设里的规则是 `rules`
 * （规则表）。一个用脚本/手写生成的预设拖进编辑器后，工作台是空的 —— 规则明明在跑，
 * 却看不见也改不了。这里把规则表翻译成等价的节点图，让"已有预设"也能在编辑器里继续编辑。
 *
 * 设计约束：
 * - **纯函数**：不碰 DOM、不读全局状态，输入 (rules, vars) 输出 {nodes, edges, skipped}，
 *   便于在 Node 里直接跑测试，也便于以后换成任何界面。
 * - **只翻译语义明确的子集**：遇到不认识的动作就跳过并记到 `skipped` 里，
 *   由调用方提示用户"这几条没导入"，而不是静默丢掉或者报错中断。
 * - 节点 id 用 `imp_` 前缀，避免和用户手搭的节点撞号。
 */

/** 触发节点类型 → 规则里的 when */
const IMPORTER_TRIGGER_NODES = {
  // 规则里的 tap 与 gesture(tap) 在引擎里等价，工作台只有一个「手势触发」节点
  tap: 'gesture',
  gesture: 'gesture',
  var: 'varTrigger',
  session: 'session',
  minute: 'minute',
  // 拖动触发（滑条）：拖动时先把 0..100 的比例写进 var，再跑规则
  drag: 'drag'
};

/** 动作类型 → 工作台节点类型 */
const IMPORTER_ACTION_NODES = {
  setVar: 'setVar',
  setVisible: 'setVis',
  setProperty: 'setProp',
  playAnimation: 'playAnim',
  launchApp: 'launchApp',
  openUri: 'openUri',
  mediaControl: 'mediaControl',
  call: 'callFn',
  // 0.13 新增：亮度 / 音量 / 歌词进度 / 系统面板 / 系统设置页
  brightness: 'brightness',
  volume: 'volume',
  seek: 'seek',
  panel: 'panel',
  settings: 'settings'
};

function studioFromRules(rules, vars) {
  const nodes = [];
  const edges = [];
  const skipped = [];
  let seq = 0;
  const nextId = (kind) => 'imp_' + kind + '_' + (seq++);

  (rules || []).forEach((rule, index) => {
    const when = (rule && rule.when) || {};
    const kind = when.trigger;
    const triggerType = IMPORTER_TRIGGER_NODES[kind];
    if (!triggerType) {
      skipped.push({ rule: rule && rule.id || ('#' + index), reason: '不支持的触发方式：' + kind });
      return;
    }

    const actions = Array.isArray(rule.then) ? rule.then : [];
    const known = actions.filter(a => a && IMPORTER_ACTION_NODES[a.type]);
    actions.forEach(a => {
      if (!a || !IMPORTER_ACTION_NODES[a.type]) {
        skipped.push({ rule: rule && rule.id || ('#' + index), reason: '不支持的动作：' + (a && a.type) });
      }
    });
    if (!known.length) return;

    // ---- 触发节点
    const trigger = { id: nextId('src'), type: triggerType, x: 0, y: 0, params: {}, note: rule.id || '' };
    if (triggerType === 'gesture') {
      trigger.params = {
        target: when.target || '',
        // tap 触发的规则没有 gesture 字段，按单击处理
        gestures: [when.gesture || (kind === 'tap' ? 'tap' : 'tap')]
      };
    } else if (triggerType === 'varTrigger') {
      trigger.params = { var: when.var || '' };
    } else if (triggerType === 'drag') {
      // target 必填；var 留空时引擎按 drag_<target> 派生
      trigger.params = {
        target: when.target || '',
        var: when.var || '',
        axis: when.axis === 'y' ? 'y' : 'x'
      };
    }
    nodes.push(trigger);

    // ---- 动作节点串成链：done → 下一个的 condition
    let prev = trigger;
    let prevPort = triggerType === 'gesture' ? 'match' : 'fire';
    known.forEach((action) => {
      const type = IMPORTER_ACTION_NODES[action.type];
      const node = { id: nextId('act'), type, x: 0, y: 0, params: {} };
      switch (action.type) {
        case 'setVar':
          node.params = { name: action.var || '', expr: action.expr != null ? String(action.expr) : String(action.value != null ? action.value : 0) };
          break;
        case 'setVisible':
          node.params = {
            target: action.target || '',
            value: action.expr != null ? String(action.expr) : String(action.value !== false)
          };
          if (action.delayMs > 0) node.params.delay = String(action.delayMs);
          break;
        case 'setProperty':
          node.params = {
            target: action.target || '',
            prop: action.property || 'opacity',
            expr: action.expr != null ? String(action.expr) : String(action.value != null ? action.value : 0),
            duration: String(action.duration != null ? action.duration : 0),
            easing: action.easing || 'easeOut'
          };
          break;
        case 'playAnimation':
          node.params = { target: action.target || '', clip: action.clip || '', reverse: String(action.reverse === true) };
          break;
        case 'launchApp':
          node.params = { package: action.package || '' };
          break;
        case 'openUri':
          node.params = { uri: action.uri || '' };
          break;
        case 'call':
          // 调用函数：原来既没有映射也没有分支，导入后节点直接消失，
          // 图上看不出"这里调用过一个函数"
          node.params = { name: action.call || '' };
          break;
        case 'mediaControl':
          node.params = { command: action.command || 'playPause' };
          break;
        case 'brightness':
        case 'volume':
        case 'seek':
          // 三者都是"一条表达式"，引擎按数值求值
          node.params = {
            expr: action.expr != null ? String(action.expr)
              : String(action.value != null ? action.value : 0)
          };
          break;
        case 'panel':
          node.params = { panel: action.panel || 'wifi' };
          break;
        case 'settings':
          node.params = { page: action.page || 'wifi' };
          break;
        default:
          break;
      }
      nodes.push(node);
      edges.push({ from: prev.id, fromPort: prevPort, to: node.id, toPort: 'condition' });
      prev = node;
      prevPort = 'done';
    });
  });

  return { nodes, edges, skipped };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { studioFromRules, IMPORTER_TRIGGER_NODES, IMPORTER_ACTION_NODES };
}
