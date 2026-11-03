// 触发器工作台 · 编译层
//
// 把画布上的节点图编译成引擎真正能跑的 rules（interactions.json 里的 rules 数组），
// 并在桌面端用「模拟运行」按同样的语义跑一遍，把结果写进执行日志。
//
// 引擎格式（RuleModel.kt 逐字核对过）：
//   {
//     "vars": { "名字": { "value": 0, "min": 0, "max": 1 } },
//     "rules": [{
//       "id": "r1",
//       "when": { "trigger": "gesture", "gesture": "tap", "target": "图层id", "expr": "条件表达式" },
//       "then": [{ "type": "setProperty", "target": "", "property": "opacity", "expr": "1",
//                  "duration": 0, "easing": "easeOut", "repeat": 0, "intervalMs": 0 }]
//     }],
//     "functions": { "名字": { "params": [], "steps": [] } }
//   }
//
// 硬约束（改了会解析失败）：
//   · when.trigger 只能是 tap / gesture / var / minute / session
//   · property 只能是 x / y / scale / rotationZ / opacity（RenderThread.SUPPORTED_RULE_PROPS）
//   · 单条规则最多 16 个动作（TriggerParser.MAX_ACTIONS_PER_RULE）
//   · 表达式只支持 + - * / % 比较 and or not if(cond,a,b) 以及标识符（变量名 / sys.*）
//
// ⚠ 这里所有顶层标识符都必须带 Studio 前缀或全大写且唯一：
//   这些脚本共享同一个全局作用域，一旦和 editor.js 里的 const 重名，
//   editor.js 会抛 "Identifier already declared" 而**整份不执行**（踩过：state 直接 undefined）。
'use strict';

const RULE_PROP = {
  x: 'x', y: 'y', scale: 'scale', rot: 'rotationZ', alpha: 'opacity',
  width: 'width', height: 'height'
};
const MAX_ACTIONS_PER_RULE = 16;
const MAX_LOOP_UNROLL = 20;     // 桌面端 trace 时最多展开多少轮（引擎侧是真循环，不受这个限制）

function studioInEdge(nodeId, port) {
  return studioDoc().edges.find(e => e.to === nodeId && e.toPort === port) || null;
}
function studioOutEdge(nodeId, port) {
  return studioDoc().edges.find(e => e.from === nodeId && e.fromPort === port) || null;
}
function studioNodeById(id) {
  return studioDoc().nodes.find(n => n.id === id) || null;
}

/** 图层输入口优先用连线，其次用参数。 */
function studioLayerOf(node, port) {
  const edge = studioInEdge(node.id, port || 'layer');
  if (edge) {
    const src = studioNodeById(edge.from);
    if (src && src.type === 'layerRef' && src.params.target) return src.params.target;
  }
  return node.params.target || '';
}

// ---------------------------------------------------------------- 表达式生成
/** 数值来源：变量名 / sys.* / 映射表达式 / 字面量。 */
function studioNumberExpr(nodeId, port, fallback) {
  const edge = studioInEdge(nodeId, port);
  if (!edge) return fallback;
  const src = studioNodeById(edge.from);
  if (!src) return fallback;

  if (src.type === 'sysVar' || src.type === 'varChanged') {
    return src.params.name || fallback;
  }
  // for / while 的「索引」输出就是它们自己的轮次变量（引擎每轮写入）
  if (src.type === 'forLoop' || src.type === 'whileLoop') {
    return src.params.var || 'i';
  }
  if (src.type === 'mapRange') {
    const inner = studioNumberExpr(src.id, 'value', src.params.inMin || '0');
    const inMin = studioNum(src.params.inMin, 0);
    const inMax = studioNum(src.params.inMax, 100);
    const outMin = studioNum(src.params.outMin, 0);
    const outMax = studioNum(src.params.outMax, 1);
    if (inMax === inMin) return String(outMin);
    const k = (outMax - outMin) / (inMax - inMin);
    // 引擎没有 map()，直接内联线性公式
    return '(' + outMin + ' + (' + inner + ' - ' + inMin + ') * ' + studioRound(k) + ')';
  }
  if (src.type === 'switchNode') {
    const gate = studioBoolExpr(src.id) || '1';
    const value = studioNumberExpr(src.id, 'value', '0');
    const off = src.params.off || '0';
    return 'if(' + gate + ', ' + value + ', ' + off + ')';
  }
  // 比较类输出的是布尔，不能当数值用
  return fallback;
}

/** 布尔来源：比较 / IF / 与或 / 取反 / 事件节点。 */
function studioBoolExpr(nodeId) {
  const node = studioNodeById(nodeId);
  if (!node) return null;
  const p = node.params || {};

  if (node.type === 'compare' || node.type === 'ifBranch') {
    const a = studioNumberExpr(node.id, 'a', p.a || '0');
    const b = studioNumberExpr(node.id, 'b', p.b || '0');
    return '(' + a + ' ' + (p.op || '==') + ' ' + b + ')';
  }
  if (node.type === 'logicGate') {
    const a = studioBoolExpr(studioInEdge(node.id, 'a') ? studioInEdge(node.id, 'a').from : null) || '1';
    const b = studioBoolExpr(studioInEdge(node.id, 'b') ? studioInEdge(node.id, 'b').from : null) || '1';
    return '(' + a + ' ' + (p.op === 'or' ? 'or' : 'and') + ' ' + b + ')';
  }
  if (node.type === 'notGate') {
    const inner = studioBoolExpr(studioInEdge(node.id, 'in') ? studioInEdge(node.id, 'in').from : null) || '0';
    return 'not (' + inner + ')';
  }
  if (node.type === 'gesture' || node.type === 'session' || node.type === 'minute') {
    return '1';   // 事件本身成立
  }
  return null;
}

function studioNum(v, fallback) {
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * 找出表达式里引擎不认识的名字。
 *
 * 为什么要查：引擎对未知标识符**静默取 0**（`Expr` 求值时找不到变量就当 0.0），
 * 于是 `group_2.y = BOT + H * (...)` 会变成 `y = 0`，图层直接贴到屏幕顶部。
 * 用户完全看不出是自己写错了，只会觉得"又飞了"。
 *
 * 认识的：sys.* 系统变量、已定义变量、设变量节点定义的名字、函数、布尔关键字。
 */
function studioUnknownIdentifiers(expr) {
  const text = String(expr || '');
  if (!text.trim()) return [];

  const FUNCS = new Set(['if', 'min', 'max', 'mod', 'abs', 'floor', 'ceil', 'round', 'sqrt', 'sin', 'cos']);
  const AUDIO_FUNC_NAMES = new Set(['audio', 'band', 'bass', 'mid', 'treble', 'level', 'beat']);
  const KEYWORDS = new Set(['and', 'or', 'not', 'true', 'false']);

  const known = new Set();
  // 已在变量表里的
  const declared = (typeof state !== 'undefined' && state.doc && state.doc.interactions &&
    state.doc.interactions.vars) || {};
  Object.keys(declared).forEach(k => known.add(k));
  // 图里「设变量」节点定义的名字（可能还没写进变量表）
  const studio = (typeof state !== 'undefined' && state.doc && state.doc.interactions &&
    state.doc.interactions.studio) || { nodes: [] };
  (studio.nodes || []).forEach(n => {
    if (n.type === 'setVar' && n.params && n.params.name) known.add(String(n.params.name).trim());
  });

  const unknown = [];
  const re = /[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    const name = m[0];
    // sys. 开头的是系统变量，由引擎提供
    if (name.startsWith('sys.')) continue;
    // 音频函数写法（audio(3) / beat() …）：由 expandAudioFuncs 展开，不算未定义
    if (AUDIO_FUNC_NAMES.has(name)) continue;
    if (FUNCS.has(name) || KEYWORDS.has(name)) continue;
    if (known.has(name)) continue;
    // 形如 a.b 的（非 sys.）：按未定义处理，避免拼错系统变量名
    if (!unknown.includes(name)) unknown.push(name);
  }
  return unknown;
}

function studioRound(n) {
  return Math.round(n * 10000) / 10000;
}

// ---------------------------------------------------------------- 动作节点 → 引擎动作
function studioActionOf(node, ctx) {
  const p = node.params || {};
  switch (node.type) {
    case 'setProp': {
      const target = studioLayerOf(node, 'layer');
      if (!target) { ctx.warnings.push('「设图层属性」没有目标图层，已跳过'); return null; }
      const property = RULE_PROP[p.prop] || 'opacity';
      const rawExpr = String(p.expr || '1');
      // 表达式里用了引擎不认识的标识符时，引擎会静默当作 0 —— 表现为"图层莫名其妙跑到 0 位置"。
      // 必须在编译期报错，不能让它悄悄上线。
      const unknownIds = studioUnknownIdentifiers(rawExpr);
      if (unknownIds.length) {
        ctx.errors.push('「设图层属性」的表达式里有未定义的名字：' + unknownIds.join('、') +
          '（引擎会当成 0，图层会被拉到 0 位置）。可用：sys.* 系统变量、已定义的变量、' +
          '以及 if/min/max/mod/abs/floor/ceil/round/sqrt/sin/cos');
      }
      const expr = studioNumberExpr(node.id, 'value', rawExpr);
      const easing = p.easing || 'easeOut';
      const duration = Math.max(0, Math.round(studioNum(p.duration, 0)));
      return {
        type: 'setProperty',
        target,
        property,
        expr,
        duration: Math.min(duration, 5000),
        easing
      };
    }
    case 'setVis': {
      const target = studioLayerOf(node, 'layer');
      if (!target) { ctx.warnings.push('「显隐」没有目标图层，已跳过'); return null; }
      return { type: 'setVisible', target, value: p.value !== 'false' };
    }
    case 'setVar': {
      if (!p.name) { ctx.warnings.push('「设变量」没有变量名，已跳过'); return null; }
      const expr = studioNumberExpr(node.id, 'value', String(p.expr || '0'));
      return { type: 'setVar', var: p.name, expr };
    }
    case 'playAnim': {
      if (!p.clip) { ctx.warnings.push('「播放动画」没有选动画组，已跳过'); return null; }
      // 引擎播放时用的是 clip 自带的 targetId；卡片上的「图层」只用于筛选动画组。
      // 两者不一致说明选错了组，动画会动到别的图层上，必须提醒。
      const clipDoc = (ctx.animations || []).find(c => c.id === p.clip);
      if (clipDoc && p.target && clipDoc.targetId !== p.target) {
        ctx.warnings.push('动画组「' + (clipDoc.name || clipDoc.id) + '」属于图层 ' +
          clipDoc.targetId + '，与卡片上选的 ' + p.target + ' 不一致，播放时会动 ' + clipDoc.targetId);
      }
      return { type: 'playAnimation', clip: p.clip, reverse: p.reverse === 'true' };
    }
    case 'launchApp': {
      if (!p.package) { ctx.warnings.push('「启动应用」没有包名，已跳过'); return null; }
      return { type: 'launchApp', package: p.package };
    }
    case 'openUri': {
      if (!p.uri) { ctx.warnings.push('「打开链接」没有链接，已跳过'); return null; }
      return { type: 'openUri', uri: p.uri };
    }
    case 'callFn': {
      if (!p.name) { ctx.warnings.push('「调用函数」没有函数名，已跳过'); return null; }
      return { type: 'call', call: p.name };
    }
    default:
      return null;
  }
}

// ---------------------------------------------------------------- 沿连线收集动作
/**
 * 从某个输出口出发，沿「完成 → 顺序 / 触发」把动作串起来。
 * 返回 { actions, cond }：cond 为该分支的附加条件（引擎里对应 when.expr）。
 */
function studioWalkActions(fromNodeId, fromPort, ctx, depth) {
  const out = { actions: [], cond: null, open: false };
  if ((depth || 0) > 24) { ctx.warnings.push('连线层级过深，已截断'); return out; }

  const edge = studioOutEdge(fromNodeId, fromPort);
  if (!edge) { out.open = true; return out; }

  const node = studioNodeById(edge.to);
  if (!node) return out;

  // 逻辑节点：先把条件取出来，再沿它的输出继续
  if (node.type === 'compare' || node.type === 'ifBranch' || node.type === 'logicGate' || node.type === 'notGate') {
    const cond = studioBoolExpr(node.id);
    if (node.type === 'compare' || node.type === 'logicGate' || node.type === 'notGate') {
      const next = studioOutEdge(node.id, node.type === 'compare' ? 'result' : 'result');
      if (!next) { out.open = true; return out; }
      const deeper = studioWalkActions(node.id, 'result', ctx, (depth || 0) + 1);
      deeper.cond = deeper.cond ? '(' + cond + ') and (' + deeper.cond + ')' : cond;
      return deeper;
    }
    // IF 分支：两个出口各自成组
    const yes = studioWalkActions(node.id, 'yes', ctx, (depth || 0) + 1);
    const no = studioWalkActions(node.id, 'no', ctx, (depth || 0) + 1);
    const groups = [];
    if (yes.actions.length) groups.push({ cond: yes.cond ? '(' + cond + ') and (' + yes.cond + ')' : cond, actions: yes.actions });
    if (no.actions.length) groups.push({ cond: 'not (' + cond + ')', actions: no.actions });
    out.groups = groups;
    out.open = yes.open && no.open;
    return out;
  }

    // 循环：编译成引擎的 for / while 动作（interactions 0.5 起支持），不在编辑器里展开
    if (node.type === 'forLoop' || node.type === 'whileLoop') {
      ctx.loopDepth = (ctx.loopDepth || 0) + 1;
      if (ctx.loopDepth > 2) {
        ctx.errors.push('循环最多嵌套 2 层（引擎限制）');
        ctx.loopDepth -= 1;
        return out;
      }

      const p = node.params || {};
      const body = studioWalkActions(node.id, 'body', ctx, (depth || 0) + 1);

      let loopAction;
      if (node.type === 'forLoop') {
        // 画布上填的是「次数 / 起始 / 步长」，引擎要的是 from..to（含端点），这里换算：
        // 次数 3、起始 1、步长 1 → from 1, to 3
        const start = studioNum(p.start, 0);
        const step = studioNum(p.step, 1) || 1;
        const count = Math.max(0, Math.round(studioNum(p.count, 1)));
        const to = start + (count - 1) * step;
        loopAction = {
          type: 'for',
          var: p.var || 'i',
          from: String(start),
          to: String(to),
          step: String(step),
          do: body.actions
        };
      } else {
        const max = Math.min(200, Math.max(1, Math.round(studioNum(p.max, 20)) || 20));
        // while 必须带轮次变量：引擎每轮把它置成当前轮次，条件才能"随轮次变化"。
        // 没有它的话条件里的 i 恒为 0，用户写 i > 7 就一次都不跑。
        loopAction = {
          type: 'while',
          var: String(p.var || 'i'),
          expr: String(p.condition || '0'),
          max: max,
          do: body.actions
        };
      }

      if (node.type === 'whileLoop') {
        const loopVar = String(p.var || 'i').trim();
        const cond = String(p.condition || '');
        if (loopVar && cond) {
          const esc = loopVar.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
          const usesVar = new RegExp('(^|[^A-Za-z0-9_.])' + esc + '([^A-Za-z0-9_.]|$)').test(cond);
          if (!usesVar) {
            ctx.warnings.push('while 条件里没用到轮次变量「' + loopVar +
              '」，条件不会随轮次变化，很可能一次都不跑（或直接跑满「最多」）');
          } else if (new RegExp('^\\s*' + esc + '\\s*>\\s*\\d').test(cond)) {
            // 轮次从 0 开始单调加一，`i > N`（N≥0）在第一轮就不成立 → 永远 0 轮。
            // 这是"写法反了"，不是运行期问题，编译期就该点出来。
            ctx.warnings.push('轮次从 0 开始递增，「' + cond.trim() +
              '」第一次就不成立，循环一次都不会执行；想跑 N 轮请写「' + loopVar + ' < N」');
          }
        }
      }

      if (!body.actions.length) {
        ctx.warnings.push((node.type === 'forLoop' ? 'for' : 'while') + ' 循环体是空的，运行时什么都不做');
      }
      if (body.actions.length > 16) {
        ctx.errors.push('循环体动作数 ' + body.actions.length + ' 超过引擎上限 16');
      }

      out.actions = [loopAction];
      out.cond = body.cond;
      const after = studioWalkActions(node.id, 'done', ctx, (depth || 0) + 1);
      if (after.actions.length) out.actions.push(...after.actions);
      out.open = body.open;
      ctx.loopDepth -= 1;
      return out;
    }

  // 动作节点
  const action = studioActionOf(node, ctx);
  if (action) out.actions.push(action);
  const next = studioWalkActions(node.id, 'done', ctx, (depth || 0) + 1);
  if (next.actions.length) out.actions.push(...next.actions);
  out.open = next.open;
  return out;
}

// ---------------------------------------------------------------- 编译入口
function compileStudioGraph() {
  const doc = studioDoc();
  const ctx = { warnings: [], errors: [] };
  const nodes = doc.nodes.slice();
  const edges = doc.edges.slice();

  const sources = nodes.filter(n =>
    n.type === 'gesture' || n.type === 'session' || n.type === 'minute');
  const rules = [];

  // 没有被任何线连出去也没连进来的节点：提示但不算错
  nodes.forEach(n => {
    const hasIn = edges.some(e => e.to === n.id);
    const hasOut = edges.some(e => e.from === n.id);
    // 数据绑定节点本来就是独立节点（不需要连线），不参与这条警告
    if (!hasIn && !hasOut && !sources.includes(n) && n.type !== 'bind') {
      ctx.warnings.push('节点「' + (STUDIO_DEFS[n.type] || {}).name + '」没有连线，不参与编译');
    }
  });

  sources.forEach(source => {
    // 触发方式：手势节点可能勾了多个手势，一个手势一条规则
    let triggers = [];
    if (source.type === 'gesture') {
      const list = Array.isArray(source.params.gestures) ? source.params.gestures : ['tap'];
      triggers = list.map(kind => ({
        trigger: 'gesture',
        gesture: kind,
        target: source.params.target || undefined
      }));
      if (!list.length) ctx.warnings.push('「手势触发」一个手势都没勾，已按单击处理');
      // 没选目标 = 整块画布都能触发，桌面上很容易误触，提示一句
      if (!source.params.target) {
        ctx.warnings.push('「手势触发」没选命中图层，整块画布都会响应（容易误触）');
      }
      if (triggers.length === 0) triggers = [{ trigger: 'gesture', gesture: 'tap' }];
    } else if (source.type === 'session') {
      triggers = [{ trigger: 'session' }];
    } else if (source.type === 'minute') {
      triggers = [{ trigger: 'minute' }];
    }

    const outPort = source.type === 'gesture' ? 'match' : 'fire';
    const walked = studioWalkActions(source.id, outPort, ctx, 0);

    // IF 分支会给出多组（是 / 否），每组单独成一条规则
    const groups = walked.groups && walked.groups.length
      ? walked.groups
      : [{ cond: walked.cond, actions: walked.actions }];

    if (!groups.some(g => g.actions.length)) {
      if (walked.open) ctx.warnings.push('触发节点「' + (STUDIO_DEFS[source.type] || {}).name + '」还没有接动作');
      return;
    }

    triggers.forEach((trig, ti) => {
      groups.forEach((group, gi) => {
        if (!group.actions.length) return;
        if (group.actions.length > MAX_ACTIONS_PER_RULE) {
          ctx.errors.push('规则动作数 ' + group.actions.length + ' 超过引擎上限 ' + MAX_ACTIONS_PER_RULE +
            '（触发：' + (STUDIO_DEFS[source.type] || {}).name + '），请拆分成多条规则');
        }
        const when = Object.assign({}, trig);
        if (group.cond) when.expr = group.cond;

        // 「定时触发」：引擎没有"任意间隔"的触发器，用动作级重复实现——
        // session 让规则在可见时启动一次，第一个动作负责无限重放，间隔就是用户填的值。
        // 旧节点没有 value/unit，按 1 分处理（等价于原来的"每分钟"）。
        let actions = group.actions;
        let whenFixed = when;
        if (source.type === 'minute') {
          const unitFactor = { ms: 1, s: 1000, min: 60000 }[String(source.params.unit || 'min')] || 60000;
          const raw = studioNum(source.params.value, 1);
          const intervalMs = Math.max(100, Math.round((raw > 0 ? raw : 1) * unitFactor));
          if (intervalMs < 100) ctx.warnings.push('定时间隔小于 100ms，已按 100ms 处理');
          whenFixed = { trigger: 'session' };
          actions = actions.map((a, i) => (i === 0
            ? Object.assign({}, a, { repeat: 2147483647, intervalMs })
            : a));
        }

        rules.push({
          // 手势 × 分支是笛卡尔积，id 必须同时带上两者，否则会重名（引擎要求 id 唯一）
          id: 'r_' + source.id + '_g' + ti + '_b' + gi,
          when: whenFixed,
          then: actions
        });
      });
    });
  });

  // 只有「数据绑定」节点是合法配置：绑定是声明式跟随，本来就不需要触发器
  const hasBindNodes = ((state.doc.interactions.studio || {}).nodes || []).some(n => n.type === 'bind');
  if (!sources.length && !hasBindNodes) {
    ctx.errors.push('画布上还没有触发节点（手势 / 点击图层 / 出现时 / 每分钟）');
  }

  // 变量表沿用「变量 / 函数」面板里的定义，只补充没有被引用的空缺
  const vars = {};
  const existing = state.doc.interactions.vars || {};
  Object.keys(existing).forEach(name => {
    const raw = existing[name];
    vars[name] = (raw && typeof raw === 'object')
      ? { value: Number(raw.value) || 0, min: Number(raw.min) || 0, max: Number(raw.max ?? 1) }
      : { value: Number(raw) || 0, min: 0, max: 1 };
  });
  // 规则里 setVar 用到的变量如果没定义，补一个默认值，免得引擎里取不到
  rules.forEach(rule => rule.then.forEach(a => {
    if (a.type === 'setVar' && !(a.var in vars)) vars[a.var] = { value: 0, min: 0, max: 1 };
  }));

  const functions = state.doc.interactions.functions || {};

  // 数据绑定节点 → interactions.bindings
  // 绑定是"声明式跟随"，不需要触发器；每个节点独立产出一条映射。
  const bindings = [];
  ((state.doc.interactions.studio || {}).nodes || []).forEach(node => {
    if (node.type !== 'bind') return;
    const target = studioLayerOf(node, 'layer');
    if (!target) { ctx.warnings.push('「数据绑定」没有选图层，已跳过'); return; }
    const property = RULE_PROP[node.params.prop] || 'opacity';
    // 公式非空 = 表达式模式（不再单独要一个"模式"字段：少一个手输项，也少一类填错）
    const expr = String(node.params.expr || '').trim();

    if (expr) {
      if (!expr) {
        ctx.errors.push('「数据绑定」选了表达式模式但公式是空的（节点 ' + node.id + '）');
        return;
      }
      // 加载期就把错拦下来：引擎遇到坏表达式会跳过这条绑定（不崩，但也不动）
      const unknown = studioUnknownIdentifiers(expandAudioFuncs(expr));
      if (unknown.length) {
        ctx.errors.push('「数据绑定」公式里有不认识的名字：' + unknown.join(', ') + '（节点 ' + node.id + '）');
        return;
      }
      let probe = null;
      try {
        probe = evalStudioExpr(expr, {});
      } catch (e) {
        ctx.errors.push('「数据绑定」公式语法错误：' + e.message + '（节点 ' + node.id + '）');
        return;
      }
      void probe;
      bindings.push({
        id: 'b_' + node.id,
        targetId: target,
        property,
        source: '',
        expr,
        enabled: true
      });
      return;
    }

    const in0 = studioNum(node.params.in0, 0);
    const in1 = studioNum(node.params.in1, 100);
    if (in0 === in1) {
      ctx.errors.push('「数据绑定」输入区间上下限不能相等（节点 ' + node.id + '）');
      return;
    }
    bindings.push({
      id: 'b_' + node.id,
      targetId: target,
      property,
      source: String(node.params.source || 'sys.battery.level'),
      in: [in0, in1],
      out: [studioNum(node.params.out0, 0), studioNum(node.params.out1, 100)],
      enabled: true
    });
  });

  const model = { vars, rules, functions };
  if (bindings.length) model.bindings = bindings;
  validateStudioModel(model).forEach(e => ctx.errors.push('自检：' + e));

  return {
    model,
    stats: {
      rules: rules.length,
      actions: rules.reduce((sum, r) => sum + r.then.length, 0),
      warnings: ctx.warnings.length,
      errors: ctx.errors.length
    },
    warnings: ctx.warnings,
    errors: ctx.errors
  };
}

// ---------------------------------------------------------------- 迷你表达式求值
// 只支持引擎那套子集：数字、标识符、+ - * / %、比较、and / or / not、括号、if(a,b,c)。
// 自己写而不是用 eval，是为了和引擎行为一致（引擎也是白名单解析）。
/**
 * 把 `audio(3)` / `beat()` 这类函数写法展开成变量名，与引擎的 BindingExpr 一致。
 * 展开后求值器只需要认识普通标识符。
 */
function expandAudioFuncs(source) {
  let text = String(source || '');
  text = text.replace(/\baudio\s*\(\s*([1-8])\s*\)/gi, (_, n) => 'sys.audio.b' + n);
  for (const fn of ['bass', 'mid', 'treble', 'level', 'beat']) {
    text = text.replace(new RegExp('\\b' + fn + '\\s*\\(\\s*\\)', 'gi'), 'sys.audio.' + fn);
  }
  return text;
}

function evalStudioExpr(rawSource, scope) {
  const tokens = [];
  let i = 0;
  const s = expandAudioFuncs(rawSource);
  const isDigit = c => c >= '0' && c <= '9';
  const isIdentStart = c => /[A-Za-z_]/.test(c);
  const isIdent = c => /[A-Za-z0-9_.]/.test(c);

  while (i < s.length) {
    const c = s[i];
    if (c === ' ' || c === '\t') { i += 1; continue; }
    if (isDigit(c) || (c === '.' && isDigit(s[i + 1]))) {
      let j = i;
      while (j < s.length && (isDigit(s[j]) || s[j] === '.')) j += 1;
      tokens.push({ t: 'num', v: parseFloat(s.slice(i, j)) });
      i = j;
      continue;
    }
    if (isIdentStart(c)) {
      let j = i;
      while (j < s.length && isIdent(s[j])) j += 1;
      tokens.push({ t: 'id', v: s.slice(i, j) });
      i = j;
      continue;
    }
    const two = s.slice(i, i + 2);
    if (two === '>=' || two === '<=' || two === '==' || two === '!=' || two === '&&' || two === '||') {
      tokens.push({ t: 'op', v: two === '&&' ? 'and' : two === '||' ? 'or' : two });
      i += 2;
      continue;
    }
    if ('+-*/%^<>()!,'.includes(c)) {
      tokens.push({ t: 'op', v: c });
      i += 1;
      continue;
    }
    throw new Error('表达式里有不认识的字符：' + c);
  }

  let pos = 0;
  const peek = () => tokens[pos];
  const eat = (v) => {
    const t = tokens[pos];
    if (t && t.v === v && (t.t === 'op' || t.t === 'id')) { pos += 1; return true; }
    return false;
  };

  function parseOr() {
    let left = parseAnd();
    while (peek() && peek().t === 'id' && peek().v === 'or') { pos += 1; left = toBool(left) || toBool(parseAnd()); }
    return left;
  }
  function parseAnd() {
    let left = parseCmp();
    while (peek() && peek().t === 'id' && peek().v === 'and') { pos += 1; left = toBool(left) && toBool(parseCmp()); }
    return left;
  }
  function parseCmp() {
    let left = parseAdd();
    while (peek() && peek().t === 'op' && ['>=', '<=', '==', '!=', '>', '<'].includes(peek().v)) {
      const op = tokens[pos++].v;
      const right = parseAdd();
      const a = toNum(left);
      const b = toNum(right);
      left = op === '>' ? a > b : op === '<' ? a < b : op === '>=' ? a >= b :
             op === '<=' ? a <= b : op === '==' ? a === b : a !== b;
    }
    return left;
  }
  function parseAdd() {
    let left = parseMul();
    while (peek() && peek().t === 'op' && (peek().v === '+' || peek().v === '-')) {
      const op = tokens[pos++].v;
      const right = parseMul();
      left = op === '+' ? toNum(left) + toNum(right) : toNum(left) - toNum(right);
    }
    return left;
  }
  function parseMul() {
    let left = parseUnary();
    while (peek() && peek().t === 'op' && ['*', '/', '%'].includes(peek().v)) {
      const op = tokens[pos++].v;
      const right = parseUnary();
      const a = toNum(left);
      const b = toNum(right);
      left = op === '*' ? a * b : op === '/' ? (b === 0 ? 0 : a / b) : (b === 0 ? 0 : a % b);
    }
    return left;
  }
  function parseUnary() {
    if (peek() && peek().t === 'op' && peek().v === '-') { pos += 1; return -toNum(parseUnary()); }
    if (peek() && peek().t === 'op' && peek().v === '!') { pos += 1; return !toBool(parseUnary()); }
    if (peek() && peek().t === 'id' && peek().v === 'not') { pos += 1; return !toBool(parseUnary()); }
    return parsePow();
  }
  // 幂：右结合，优先级高于乘除（与引擎一致）
  function parsePow() {
    const base = parsePrimary();
    if (peek() && peek().t === 'op' && peek().v === '^') {
      pos += 1;
      return Math.pow(toNum(base), toNum(parsePow()));
    }
    return base;
  }
  function parsePrimary() {
    const t = peek();
    if (!t) throw new Error('表达式不完整');
    if (t.t === 'num') { pos += 1; return t.v; }
    if (t.t === 'id') {
      pos += 1;
      if (t.v === 'if' && peek() && peek().v === '(') {
        pos += 1;                                  // (
        const cond = parseOr();
        if (!eat(',')) throw new Error('if() 缺少逗号');
        const a = parseOr();
        if (!eat(',')) throw new Error('if() 缺少逗号');
        const b = parseOr();
        if (!eat(')')) throw new Error('if() 缺少右括号');
        return toBool(cond) ? a : b;
      }
      if (t.v === 'true') return 1;
      if (t.v === 'false') return 0;
      if (Object.prototype.hasOwnProperty.call(scope, t.v)) return scope[t.v];
      return 0;      // 未定义标识符按 0 处理，和引擎一致
    }
    if (t.t === 'op' && t.v === '(') {
      pos += 1;
      const v = parseOr();
      if (!eat(')')) throw new Error('缺少右括号');
      return v;
    }
    throw new Error('表达式语法错误：' + JSON.stringify(t.v));
  }

  const value = parseOr();
  if (pos < tokens.length) throw new Error('表达式尾部还有多余内容');
  return value;
}

function toNum(v) {
  if (typeof v === 'boolean') return v ? 1 : 0;
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}
function toBool(v) {
  return typeof v === 'boolean' ? v : toNum(v) !== 0;
}

// ---------------------------------------------------------------- 产物自检
// 照着 TriggerParser 的校验逐条比一遍（触发白名单 / 动作白名单 / 属性白名单 /
// 缓动名 / 必填字段 / 单规则 16 动作），把"推上手机才报错"提前到编辑期。
const STUDIO_EASING_NAMES = ['linear', 'easeIn', 'easeOut', 'easeInOut', 'backOut', 'bounceOut'];
const TRIGGER_TYPES = ['tap', 'gesture', 'var', 'minute', 'session'];
const GESTURE_KINDS = ['tap', 'doubleTap', 'longPress', 'swipeLeft', 'swipeRight', 'swipeUp', 'swipeDown'];
const ACTION_TYPES = ['setVar', 'setVisible', 'setProperty', 'playAnimation', 'launchApp', 'openUri', 'call', 'for', 'while'];
const PROPERTY_NAMES = ['x', 'y', 'scale', 'rotationZ', 'opacity'];

function validateStudioModel(model) {
  const errors = [];
  (model.rules || []).forEach((rule, i) => {
    const at = '/rules[' + i + ']';
    if (!rule.id) errors.push(at + '/id：规则必须有 id');
    const when = rule.when || {};
    if (TRIGGER_TYPES.indexOf(when.trigger) < 0) errors.push(at + '/when/trigger：未知触发 ' + when.trigger);
    if (when.trigger === 'gesture' && GESTURE_KINDS.indexOf(when.gesture) < 0) {
      errors.push(at + '/when/gesture：未知手势 ' + when.gesture);
    }
    if (when.trigger === 'var' && !when.var) errors.push(at + '/when/var：缺少变量名');
    if (!Array.isArray(rule.then)) { errors.push(at + '/then：必须是数组'); return; }
    if (rule.then.length > MAX_ACTIONS_PER_RULE) {
      errors.push(at + '/then：' + rule.then.length + ' 个动作，超过上限 ' + MAX_ACTIONS_PER_RULE);
    }
    const checkActions = (list, basePath, depth) => list.forEach((a, k) => {
      const ap = basePath + '[' + k + ']';
      if (ACTION_TYPES.indexOf(a.type) < 0) errors.push(ap + '/type：未知动作 ' + a.type);
      if (a.type === 'setProperty') {
        if (!a.target) errors.push(ap + '/target：缺少目标');
        if (PROPERTY_NAMES.indexOf(a.property) < 0) errors.push(ap + '/property：引擎不支持 ' + a.property);
        if (STUDIO_EASING_NAMES.indexOf(a.easing) < 0) errors.push(ap + '/easing：未知缓动 ' + a.easing);
        if (typeof a.expr !== 'string' || !a.expr) errors.push(ap + '/expr：缺少表达式');
      }
      if (a.type === 'setVisible' && !a.target) errors.push(ap + '/target：缺少目标');
      if (a.type === 'setVar' && !a.var) errors.push(ap + '/var：缺少变量名');
      if (a.type === 'playAnimation' && !a.clip) errors.push(ap + '/clip：缺少动画 id');
      if (a.type === 'launchApp' && !a.package) errors.push(ap + '/package：缺少包名');
      if (a.type === 'openUri' && !a.uri) errors.push(ap + '/uri：缺少链接');
      if (a.type === 'call' && !a.call) errors.push(ap + '/call：缺少函数名');
      if (a.type === 'for') {
        if (!a.var) errors.push(ap + '/var：for 循环缺少循环变量名');
        if (typeof a.from !== 'string' || typeof a.to !== 'string') {
          errors.push(ap + '/from,to：必须是表达式字符串');
        }
        if (a.step !== undefined && typeof a.step !== 'string') errors.push(ap + '/step：必须是表达式字符串');
      }
      if (a.type === 'while') {
        if (typeof a.expr !== 'string' || !a.expr) errors.push(ap + '/expr：while 循环缺少条件');
        if (!(a.max >= 1 && a.max <= 200)) errors.push(ap + '/max：应在 1..200（引擎上限）');
      }
      if (a.type === 'for' || a.type === 'while') {
        if (!Array.isArray(a.do)) { errors.push(ap + '/do：循环体必须是数组'); return; }
        if (a.do.length > 16) errors.push(ap + '/do：循环体 ' + a.do.length + ' 个动作，超过上限 16');
        if (depth >= 2) errors.push(ap + '/do：循环最多嵌套 2 层');
        checkActions(a.do, ap + '/do', depth + 1);
      }
    });
      checkActions(rule.then, at + '/then', 0);
  });
  return errors;
}
// ---------------------------------------------------------------- 模拟运行（逐节点明细）
//
// 为什么不直接"跑编译结果"：编译产物是给引擎的最终形态（规则 + 动作数组），
// 丢失了中间过程（哪个条件为真、哪个分支被跳过）。用户要看的是"每个节点发生了什么"，
// 所以这里沿节点图走一遍并逐节点记录；走完之后再和编译结果对一次账，确认两边一致。

/** 桌面端模拟的系统变量取值：全部来自右栏「全局模拟输入」。 */
function studioSimScope(extraVars) {
  const scope = Object.assign({}, extraVars || {});
  scope['sys.battery.level'] = studio.sim.battery;
  scope['sys.battery.charging'] = 0;
  scope['sys.time.hour'] = studio.sim.hour;
  scope['sys.time.minute'] = 0;
  scope['touch.x'] = studio.sim.x;
  scope['touch.y'] = studio.sim.y;
  scope['sys.mem.usedPct'] = 42;
  scope['sys.screen.on'] = 1;
  return scope;
}
const studioRun = {
  seq: 0,
  at: '',
  nodes: 0,
  edges: 0,
  steps: [],
  summary: '',
  errors: [],
  warnings: []
};

function studioRunPush(node, detail, status) {
  const def = STUDIO_DEFS[node.type] || {};
  studioRun.steps.push({
    idx: studioRun.steps.length + 1,
    icon: def.icon || '·',
    name: def.name || node.type,
    cat: def.cat || 'logic',
    detail: detail,
    status: status      // ok / act / skip / fail
  });
}

function studioRunPushRaw(icon, name, cat, detail, status) {
  studioRun.steps.push({
    idx: studioRun.steps.length + 1,
    icon, name, cat, detail, status
  });
}

/** 把表达式求值成显示用的文字（求不出来就原样返回表达式）。 */
function studioEvalText(exprText, scope) {
  if (!exprText) return '—';
  try {
    const v = evalStudioExpr(exprText, scope);
    const n = toNum(v);
    return String(Math.round(n * 1000) / 1000);
  } catch {
    return exprText;
  }
}

/** 这个节点的上游是不是数据源（sysVar / 变量 / 图层引用）：是就先记一条"数据源"步骤。 */
function studioTraceInputs(node, scope, seen) {
  (STUDIO_DEFS[node.type] && STUDIO_DEFS[node.type].inputs || []).forEach(port => {
    const edge = studioInEdge(node.id, port.id);
    if (!edge) return;
    const src = studioNodeById(edge.from);
    if (!src || seen.has(src.id)) return;
    if (src.type === 'sysVar' || src.type === 'varChanged') {
      seen.add(src.id);
      const name = src.params.name || '（未选）';
      const value = studioEvalText(name, scope);
      studioRunPush(src, '[数据源] ' + name + ' = ' + value, 'ok');
    } else if (src.type === 'layerRef') {
      seen.add(src.id);
      studioRunPush(src, '[数据源] 图层 = ' + (src.params.target || '（未选）'), 'ok');
    }
  });
}

function runStudioSim() {
  const compiled = compileStudioGraph();

  studioRun.seq += 1;
  studioRun.at = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  studioRun.steps = [];
  studioRun.errors = compiled.errors.slice();
  studioRun.warnings = compiled.warnings.slice();

  const doc = studioDoc();
  studioRun.nodes = doc.nodes.length;
  studioRun.edges = doc.edges.length;

  const vars = {};
  Object.keys(compiled.model.vars || {}).forEach(name => {
    vars[name] = toNum(compiled.model.vars[name].value);
  });
  const scope = studioSimScope(vars);
  const layerState = {};
  const seenSources = new Set();
  let executed = 0;
  let skipped = 0;

  // 单步动作：返回给日志看的说明文字
  const applyAction = (node, action) => {
    const p = node.params || {};
    if (!action) return ['[动作] ' + (STUDIO_DEFS[node.type] || {}).name + '：参数不全，已跳过', 'skip'];
    switch (action.type) {
      case 'setProperty': {
        const key = action.target + '.' + action.property;
        const before = layerState[key];
        const value = toNum(evalStudioExpr(action.expr, scope));
        layerState[key] = Math.round(value * 1000) / 1000;
        return ['[动作] setProperty ' + key + '：' + (before === undefined ? '默认' : before) + ' → ' +
          layerState[key] + (action.duration > 0 ? '（' + action.duration + 'ms ' + action.easing + '）' : '（瞬时）'), 'act'];
      }
      case 'setVisible':
        layerState[action.target + '.visible'] = action.value;
        return ['[动作] setVisible ' + action.target + ' → ' + (action.value ? '显示' : '隐藏'), 'act'];
      case 'setVar': {
        const before = scope[action.var] === undefined ? 0 : scope[action.var];
        const value = toNum(evalStudioExpr(action.expr, scope));
        scope[action.var] = value;
        vars[action.var] = value;
        return ['[动作] setVar ' + action.var + '：' + before + ' → ' + value, 'act'];
      }
      case 'playAnimation':
        return ['[动作] playAnimation ' + action.clip + (action.reverse ? '（反向：尾帧→首帧）' : '（正向）'), 'act'];
      case 'launchApp':
        return ['[动作] launchApp ' + action.package + '（桌面端不执行，仅记录）', 'act'];
      case 'openUri':
        return ['[动作] openUri ' + action.uri + '（桌面端不执行，仅记录）', 'act'];
      case 'call':
        return ['[动作] call ' + action.call + '（函数体未在本图展开）', 'act'];
      default:
        return ['[动作] ' + action.type, 'act'];
    }
  };

  const describeAction = (node) => {
    const p = node.params || {};
    switch (node.type) {
      case 'setProp': return 'setProperty ' + (studioLayerOf(node, 'layer') || '?') + '.' + (RULE_PROP[p.prop] || 'opacity');
      case 'setVis': return 'setVisible ' + (studioLayerOf(node, 'layer') || '?');
      case 'setVar': return 'setVar ' + (p.name || '?');
      case 'playAnim': return 'playAnimation ' + (p.clip || '?');
      case 'launchApp': return 'launchApp ' + (p.package || '?');
      case 'openUri': return 'openUri ' + (p.uri || '?');
      case 'callFn': return 'call ' + (p.name || '?');
      default: return node.type;
    }
  };

  // 沿连线走：active = 上游是否成立
  const traceFrom = (nodeId, port, active, reason, depth) => {
    if ((depth || 0) > 24) return;
    const edge = studioOutEdge(nodeId, port);
    if (!edge) {
      // 只有触发源"完全没接下一步"才值得提示；分支出口空着是正常的
      if ((depth || 0) === 0) {
        studioRunPushRaw('◦', '未连接', 'logic', '[线索] 「' + port + '」出口还没有接动作', 'skip');
      }
      return;
    }
    const node = studioNodeById(edge.to);
    if (!node) return;

    // ---- 逻辑节点 ----
    if (node.type === 'compare' || node.type === 'ifBranch') {
      studioTraceInputs(node, scope, seenSources);
      const aExpr = studioNumberExpr(node.id, 'a', node.params.a || '0');
      const bExpr = studioNumberExpr(node.id, 'b', node.params.b || '0');
      const aText = studioEvalText(aExpr, scope);
      const bText = studioEvalText(bExpr, scope);
      const expr = studioBoolExpr(node.id);
      let value = false;
      let failed = null;
      try { value = toBool(evalStudioExpr(expr, scope)); }
      catch (err) { failed = err.message; }

      if (failed) {
        studioRunPush(node, '[逻辑] 条件解析失败：' + expr + '（' + failed + '）', 'fail');
        traceFrom(node.id, 'result', false, '条件解析失败', (depth || 0) + 1);
        return;
      }

      const base = '[逻辑] IF ' + aText + ' ' + (node.params.op || '==') + ' ' + bText + ' → ' +
        (value ? 'true' : 'false');
      if (node.type === 'compare') {
        studioRunPush(node, base, 'ok');
        traceFrom(node.id, 'result', active && value, value ? '' : '条件不成立', (depth || 0) + 1);
      } else {
        studioRunPush(node, base + ' · 是 ' + value + ' / 否 ' + (!value), 'ok');
        traceFrom(node.id, 'yes', active && value, value ? '' : '「是」分支未选中', (depth || 0) + 1);
        traceFrom(node.id, 'no', active && !value, value ? '「否」分支未选中' : '', (depth || 0) + 1);
      }
      return;
    }

    if (node.type === 'logicGate' || node.type === 'notGate') {
      studioTraceInputs(node, scope, seenSources);
      const expr = studioBoolExpr(node.id);
      let value = false;
      try { value = toBool(evalStudioExpr(expr, scope)); } catch { value = false; }
      studioRunPush(node, '[逻辑] ' + expr + ' → ' + (value ? 'true' : 'false'), 'ok');
      traceFrom(node.id, 'result', active && value, value ? '' : '条件不成立', (depth || 0) + 1);
      return;
    }

    // ---- 循环 ----
    if (node.type === 'forLoop' || node.type === 'whileLoop') {
      const p = node.params || {};
      const times = node.type === 'forLoop'
        ? Math.max(1, Math.round(studioNum(p.count, 1)))
        : Math.max(1, Math.round(studioNum(p.max, 1)));
      const capped = Math.min(times, MAX_LOOP_UNROLL);
      studioRunPush(node, '[逻辑] ' + (node.type === 'forLoop' ? 'for' : 'while') + ' 循环 ×' + times +
        ' · 索引从 ' + (p.start || '0') + ' 开始' +
        (capped < times ? '（日志只展开前 ' + capped + ' 轮，引擎按 ' + times + ' 轮执行）' : ''), 'ok');
      for (let i = 0; i < capped; i += 1) {
        scope['i'] = i;
        traceFrom(node.id, 'body', active, reason, (depth || 0) + 1);
      }
      traceFrom(node.id, 'done', active, reason, (depth || 0) + 1);
      return;
    }

    // ---- 动作节点 ----
    const action = studioActionOf(node, { warnings: [] });
    if (!active) {
      skipped += 1;
      studioRunPush(node, '[跳过] ' + (reason || '上游条件为 false') + '，未执行 ' + describeAction(node), 'skip');
      traceFrom(node.id, 'done', false, reason, (depth || 0) + 1);
      return;
    }
    const [detail, status] = applyAction(node, action);
    studioRunPush(node, detail, status);
    if (status === 'act') executed += 1;
    traceFrom(node.id, 'done', true, '', (depth || 0) + 1);
  };

  // ---- 触发源 ----
  const sources = doc.nodes.filter(n =>
    n.type === 'gesture' || n.type === 'session' || n.type === 'minute');

  if (!sources.length) {
    studioRunPushRaw('⚠', '没有触发节点', 'source', '画布上至少需要一个「手势触发 / 出现时 / 每分钟」', 'fail');
  }

  sources.forEach(src => {
    const p = src.params || {};
    let ok = false;
    let detail = '';
    if (src.type === 'gesture') {
      const kinds = Array.isArray(p.gestures) ? p.gestures : [];
      ok = kinds.indexOf(studio.sim.gesture) >= 0;
      detail = '[数据源] 手势 = ' + kinds.join(' / ') + ' · 目标 ' + (p.target || '整块画布') +
        '（模拟手势：' + studio.sim.gesture + '）→ ' + (ok ? '命中' : '未命中');
    } else if (src.type === 'session') {
      ok = true;
      detail = '[数据源] 出现时（模拟触发）→ 命中';
    } else {
      ok = true;
      detail = '[数据源] 每分钟（模拟触发）→ 命中';
    }
    studioRunPush(src, detail, ok ? 'ok' : 'skip');
    traceFrom(src.id, src.type === 'gesture' ? 'match' : 'fire', ok, '触发未命中', 0);
  });

  studioRun.summary = studioRun.steps.length
    ? ('执行 ' + executed + ' 个动作' + (skipped ? ' · 跳过 ' + skipped + ' 处' : '') +
       ' · 编译后 ' + compiled.model.rules.length + ' 条规则 / ' + compiled.stats.actions + ' 个动作')
    : '没有任何节点被执行';

  // 告警与错误也进日志，排在明细前面
  studioRun.errors.forEach(e => studioRunPushRaw('✗', '编译错误', 'logic', e, 'fail'));
  studioRun.warnings.forEach(w => studioRunPushRaw('!', '提醒', 'logic', w, 'skip'));

  renderStudioLog();
  return compiled;
}

/** 渲染成逐节点明细；没有执行记录时返回 false，让原来的纯文本日志接手。 */
function renderStudioRunLog(box) {
  if (!studioRun.steps || !studioRun.steps.length) return false;
  box.innerHTML = '';

  const head = el('div', 'st-run-head');
  head.appendChild(el('span', 'st-run-badge', 'RUN ' + String(studioRun.seq).padStart(2, '0')));
  head.appendChild(el('span', 'st-run-time', studioRun.at));
  head.appendChild(el('span', 'spacer'));
  head.appendChild(el('span', 'st-run-topo', 'TOPO · ' + studioRun.nodes + 'N / ' + studioRun.edges + 'E'));
  box.appendChild(head);

  studioRun.steps.forEach(step => {
    const row = el('div', 'st-run-step ' + (step.status || 'ok'));
    row.appendChild(el('span', 'st-run-idx', String(step.idx).padStart(2, '0')));
    row.appendChild(el('span', 'st-run-ico', step.icon));

    const copy = el('div', 'st-run-copy');
    const title = el('div', 'st-run-title');
    title.appendChild(el('span', 'st-run-name', step.name));
    if (step.cat) {
      const pill = el('span', 'st-run-cat cat-' + step.cat,
        step.cat === 'source' ? '数据源' : step.cat === 'action' ? '动作' : '逻辑');
      title.appendChild(pill);
    }
    copy.appendChild(title);
    copy.appendChild(el('div', 'st-run-detail', step.detail));
    row.appendChild(copy);
    box.appendChild(row);
  });

  box.appendChild(el('div', 'st-run-summary', studioRun.summary));
  box.scrollTop = 0;
  return true;
}


// ---------------------------------------------------------------- JSON 预览
let studioLastCompiled = null;

function showStudioJson() {
  const compiled = compileStudioGraph();
  studioLastCompiled = compiled;
  const modal = document.getElementById('studio-json-modal');
  const pre = document.getElementById('studio-json-body');
  const meta = document.getElementById('studio-json-meta');
  if (!modal || !pre) return;
  // 只显示 rules 会让人误以为"什么都没编译出来"——绑定、变量也要一起展示，
  // 否则用「数据绑定」节点做的效果在预览里完全看不见（用户实测反馈）。
  const payload = { rules: compiled.model.rules };
  const bindings = compiled.model.bindings || [];
  if (bindings.length) payload.bindings = bindings;
  const varKeys = Object.keys(compiled.model.vars || {});
  if (varKeys.length) payload.vars = compiled.model.vars;
  const json = JSON.stringify(payload, null, 2);
  pre.textContent = json;
  if (meta) {
    meta.textContent = compiled.stats.rules + ' 条规则 · ' + compiled.stats.actions + ' 个动作 · ' +
      bindings.length + ' 条绑定' +
      (compiled.stats.warnings ? ' · ' + compiled.stats.warnings + ' 提醒' : '') +
      (compiled.stats.errors ? ' · ' + compiled.stats.errors + ' 错误' : '');
  }
  modal.hidden = false;
}

function hideStudioJson() {
  const modal = document.getElementById('studio-json-modal');
  if (modal) modal.hidden = true;
}

function setupStudioCompile() {
  const run = document.getElementById('btn-studio-run');
  if (run) run.onclick = () => runStudioSim();

  const jsonBtn = document.getElementById('btn-studio-json');
  if (jsonBtn) jsonBtn.onclick = () => showStudioJson();

  const close = document.getElementById('studio-json-close');
  if (close) close.onclick = () => hideStudioJson();
  const backdrop = document.getElementById('studio-json-backdrop');
  if (backdrop) backdrop.onclick = () => hideStudioJson();

  const copy = document.getElementById('studio-json-copy');
  if (copy) copy.onclick = async () => {
    const body = document.getElementById('studio-json-body');
    if (!body) return;
    try {
      await navigator.clipboard.writeText(body.textContent);
      studioLog('规则 JSON 已复制到剪贴板');
    } catch {
      studioLog('复制失败，请手动全选复制', 'warn');
    }
  };

  document.addEventListener('keydown', ev => {
    if (ev.key === 'Escape') hideStudioJson();
  });
}