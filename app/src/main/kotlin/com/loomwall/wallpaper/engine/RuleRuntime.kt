package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.Expr
import com.loomwall.wallpaper.scene.ExprContext
import com.loomwall.wallpaper.scene.ExprParser
import com.loomwall.wallpaper.scene.ExprValue
import com.loomwall.wallpaper.scene.Rule
import com.loomwall.wallpaper.scene.RuleAction
import com.loomwall.wallpaper.scene.RuleFunction
import com.loomwall.wallpaper.scene.RuleTrigger
import com.loomwall.wallpaper.scene.TriggerModel
import com.loomwall.wallpaper.scene.TriggerParser

/**
 * 触发器运行时：变量表 + 规则匹配 + 动作执行。
 *
 * 设计原则（见 docs/triggers-design.md）：
 * 1. **事件驱动**，不做每帧轮询；静止时零开销。
 * 2. **有界**：单帧动作上限、变量级联迭代上限、函数调用深度上限，全部硬编码。
 *   超限就停并计数，绝不进入死循环 —— 壁纸卡死会让用户以为手机坏了。
 * 3. **纯内存**：不碰文件/网络/传感器；系统变量由外部以函数注入。
 *
 * 与渲染器的关系：这里只做"该显示谁、属性该是多少"，真正的绘制由 [RuleEffects] 的
 * 实现转交给渲染线程，避免把 GL 逻辑混进来。
 */

/** 运行时对外部世界的全部影响面：只允许这三种。 */
interface RuleEffects {
    /** 设置元素可见性。目标是容器时，整棵子树跟随。 */
    fun setVisible(targetId: String, visible: Boolean)

    /**
     * 设置数值属性（x/y/scale/rotationZ/opacity）。
     * [durationMs] > 0 时做补间动画，= 0 时瞬移。
     */
    fun setProperty(targetId: String, property: String, value: Float, durationMs: Int = 0, easing: String = "easeOut")

    /** 启动应用（包名）。 */
    fun launchApp(packageName: String)

    /** 打开链接/系统页。 */
    fun openUri(uri: String)

    /**
     * 登记一个循环任务：把 [ruleId] 的动作按 [intervalMs] 重放 [times] 次（0 = 无限）。
     * 由渲染线程执行；壁纸不可见时必须停掉。
     */
    fun scheduleRepeat(ruleId: String, times: Int, intervalMs: Int)

    /** 播放动画片段。 */
    fun playAnimation(clipId: String, reverse: Boolean = false)
}

class RuleRuntime(
    private val model: TriggerModel,
    private val effects: RuleEffects,
    /** 系统变量读取：sys.time.hour / sys.battery.level …，未知返回 null。 */
    private val systemVar: (String) -> Float? = { null },
    private val textVar: (String) -> String? = { null },
    private val randomSource: () -> Float = { kotlin.random.Random.nextFloat() }
) {

    /** 变量当前值。key 不带任何前缀，规则里直接写名字。 */
    private val vars = LinkedHashMap<String, Float>()

    /** 解析后的条件/表达式缓存：规则数量固定，一次性解析，运行期不重复解析。 */
    private val conditions = HashMap<String, Expr?>()
    private val actionExprs = HashMap<String, Expr?>()
    private val functions: Map<String, RuleFunction> = model.functions.associateBy { it.name }

    /** 单帧内已执行的动作数（跨规则累计），用来兜住"一条点击触发上百个动作"的极端预设。 */
    private var actionsThisFrame = 0

    /** 本轮触发里循环累计迭代次数：所有循环共享，防"每层都跑满"的组合爆炸。 */
    private var loopIterationsThisFrame = 0

    /** 本轮触发的执行截止时刻（纳秒）。循环是唯一可能长时间占住线程的结构，必须有时间片。 */
    private var frameDeadlineNs = 0L

    /** 每次外部触发都重置动作预算、循环预算与时间片。 */
    private fun beginTrigger() {
        actionsThisFrame = 0
        loopIterationsThisFrame = 0
        frameDeadlineNs = System.nanoTime() + FRAME_BUDGET_NS
    }

    /** 当前正在执行的规则 id（循环任务重放时用）。 */
    private var currentRuleId: String = ""

    /** 正在重放循环任务：重放时不能再登记新的循环，否则永远停不下来。 */
    private var replaying = false

    /** 上一次统计到的超限次数，便于测试与日志。 */
    var budgetExceeded = 0
        private set

    init {
        model.vars.forEach { vars[it.name] = it.value }
    }

    companion object {
        /** 单帧动作上限。 */
        const val MAX_ACTIONS_PER_FRAME = 64

        /** 变量级联（A 变 → 触发 B 变 → …）的最大迭代轮数。 */
        const val MAX_CASCADE_PASSES = 8

        /** 函数调用嵌套上限。 */
        const val MAX_CALL_DEPTH = 4

        /**
         * 单次触发的执行时间片（16ms = 一帧 @60Hz）。
         *
         * 循环是唯一能在一帧里跑很久的结构：没有时间片的话，一条 for 里塞几层嵌套
         * 就够让渲染线程掉帧。超时即放弃剩余迭代并记一次 budgetExceeded，画面下一帧照常刷新。
         *
         * 取整帧而不是半帧：首次触发要付出类加载/JIT 的冷启动成本，
         * 半帧（8ms）在冷启动时会让正常循环被误判超时（实测只跑 1 轮就停）。
         */
        const val FRAME_BUDGET_NS = 16_000_000L

        /** 单个循环的最大迭代次数（跨循环共享的预算也用它）。 */
        const val MAX_LOOP_ITERATIONS_PER_LOOP = 200
    }

    // ---------------------------------------------------------------- 查询

    fun valueOf(name: String): Float = vars[name] ?: 0f

    fun hasVar(name: String): Boolean = vars.containsKey(name)

    fun varSnapshot(): Map<String, Float> = LinkedHashMap(vars)

    /** 规则总数（测试与状态栏用）。 */
    val ruleCount: Int get() = model.rules.size

    // ---------------------------------------------------------------- 触发入口

    /**
     * 点击事件。
     *
     * 匹配顺序：先执行"指定该元素"的规则；只有当没有任何具体目标规则命中时，
     * 才执行"整块画布"的规则（target 为 null）。
     * 否则「弹出面板」这类模板会先开后立刻被空白点击规则关掉。
     */
    fun onTap(targetId: String?): Int = onGesture("tap", targetId)

    /**
     * 手势分发：tap / doubleTap / longPress / swipeLeft / swipeRight / swipeUp / swipeDown。
     *
     * 命中优先级与 tap 一致：先跑"指定了目标"的规则，都没有命中才跑"整块画布"的规则。
     */
    fun onGesture(kind: String, targetId: String?): Int {
        beginTrigger()
        val exact = model.rules.filter {
            val t = it.trigger
            // tap 既可能是旧模型 RuleTrigger.Tap，也可能是通用手势 —— 两种都要认，
            // 否则老预设的点击规则会全部失效（这一条被单测抓到过）
            val isTap = t is RuleTrigger.Tap && kind == "tap"
            val isGesture = t is RuleTrigger.Gesture && t.kind == kind
            if (!isTap && !isGesture) return@filter false
            when (t) {
                is RuleTrigger.Tap -> t.targetId != null && t.targetId == targetId
                is RuleTrigger.Gesture -> t.targetId != null && t.targetId == targetId
                else -> false
            }
        }
        var executed = if (exact.isNotEmpty()) runRules(exact) else 0
        if (executed == 0) {
            val canvasWide = model.rules.filter {
                val t = it.trigger
                val isTap = t is RuleTrigger.Tap && kind == "tap"
                val isGesture = t is RuleTrigger.Gesture && t.kind == kind
                if (!isTap && !isGesture) return@filter false
                when (t) {
                    is RuleTrigger.Tap -> t.targetId == null
                    is RuleTrigger.Gesture -> t.targetId == null
                    else -> false
                }
            }
            executed += runRules(canvasWide)
        }
        flushCascade()
        return executed
    }

    /** 供渲染线程重放某条规则的动作用（循环）。返回执行的动作数。 */
    fun replay(ruleId: String): Int {
        val rule = model.rules.firstOrNull { it.id == ruleId } ?: return 0
        beginTrigger()
        currentRuleId = ruleId
        replaying = true
        val executed = runRules(listOf(rule))
        replaying = false
        flushCascade()
        return executed
    }

    /** 每分钟 tick。hour/minute 由外部读取系统时间传入，避免这里依赖时钟。 */
    fun onMinute(): Int {
        beginTrigger()
        val executed = runRules(model.rules.filter { it.trigger is RuleTrigger.Minute })
        flushCascade()
        return executed
    }

    /** 每次壁纸变可见。 */
    fun onSession(): Int {
        beginTrigger()
        val executed = runRules(model.rules.filter { it.trigger is RuleTrigger.Session })
        flushCascade()
        return executed
    }

    /** 外部直接改变量（编辑器变量监视/测试用），会触发级联。 */
    fun setVar(name: String, value: Float) {
        beginTrigger()
        applySetVar(name, value)
        flushCascade()
    }

    // ---------------------------------------------------------------- 执行

    private fun runRules(rules: List<Rule>): Int {
        var executed = 0
        for (rule in rules) {
            currentRuleId = rule.id
            if (!conditionHolds(rule)) continue
            executeActions(rule.actions, emptyMap(), 0)
            executed++
        }
        return executed
    }

    private fun conditionHolds(rule: Rule): Boolean {
        val exprText = rule.condition ?: return true
        val key = "cond:" + rule.id
        val expr = conditions.getOrPut(key) { parseOrNull(exprText, "/rules/${rule.id}/when/expr") }
        if (expr == null) return false
        return ExprParser.evaluate(expr, context()).asBool()
    }

    /**
     * 执行动作列表。
     *
     * @param args 函数调用时的参数替换表（`{{name}}` → 字面值）
     */
    private fun executeActions(
        actions: List<RuleAction>,
        args: Map<String, String>,
        depth: Int,
        loopDepth: Int = 0
    ) {
        for (action in actions) {
            if (actionsThisFrame >= MAX_ACTIONS_PER_FRAME) {
                budgetExceeded++
                return
            }
            actionsThisFrame++
            when (action) {
                is RuleAction.SetVar -> {
                    val name = substitute(action.varName, args)
                    val value = action.expr?.let { evalText(it, "/expr") } ?: action.value
                    applySetVar(name, value)
                }
                is RuleAction.SetVisible -> {
                    val target = substitute(action.targetId, args)
                    val visible = action.expr?.let { evalText(it, "/expr") > 0.5f } ?: action.value
                    effects.setVisible(target, visible)
                }
                is RuleAction.SetProperty -> {
                    val target = substitute(action.targetId, args)
                    effects.setProperty(
                        target,
                        action.property,
                        evalText(action.expr, "/expr"),
                        action.durationMs,
                        action.easing
                    )
                    if (!replaying && (action.repeat > 0 || action.intervalMs > 0)) {
                        effects.scheduleRepeat(currentRuleId, action.repeat, action.intervalMs)
                    }
                }
                is RuleAction.PlayAnimation -> effects.playAnimation(action.clipId, action.reverse)
                is RuleAction.LaunchApp -> effects.launchApp(action.packageName)
                is RuleAction.OpenUri -> effects.openUri(action.uri)
                is RuleAction.CallFunction -> callFunction(action, depth, loopDepth)
                is RuleAction.ForLoop -> runForLoop(action, args, depth, loopDepth)
                is RuleAction.WhileLoop -> runWhileLoop(action, args, depth, loopDepth)
            }
        }
    }

    private fun callFunction(action: RuleAction.CallFunction, depth: Int, loopDepth: Int = 0) {
        if (depth >= MAX_CALL_DEPTH) {
            budgetExceeded++
            return
        }
        val fn = functions[action.name] ?: return
        // 参数替换：函数体里写 {{param}}，调用时换成本次传入的字面值
        val bound = LinkedHashMap<String, String>()
        for ((k, v) in action.args) bound[k] = substitute(v, emptyMap())
        executeActions(fn.steps, bound, depth + 1, loopDepth)
    }

    /**
     * for 循环：把循环变量写成变量，然后重复执行循环体。
     *
     * 三重刹车，任何一道生效都会立刻停下并记一次超预算：
     * 1. 本循环自己的迭代次数不超过 MAX_LOOP_ITERATIONS
     * 2. 本轮触发里所有循环的累计迭代不超过 MAX_LOOP_ITERATIONS
     * 3. 8ms 时间片用满
     * 另外动作数到 MAX_ACTIONS_PER_FRAME 也会停（executeActions 里已有判断）。
     */
    private fun runForLoop(body: RuleAction.ForLoop, args: Map<String, String>, depth: Int, loopDepth: Int) {
        val name = substitute(body.varName, args)
        val from = evalText(substitute(body.fromExpr, args), "/for/from").toInt()
        val to = evalText(substitute(body.toExpr, args), "/for/to").toInt()
        val rawStep = evalText(substitute(body.stepExpr, args), "/for/step").toInt()
        // step = 0 会让循环永远不前进，直接当成 1
        val step = if (rawStep == 0) 1 else rawStep

        var i = from
        var guard = 0
        while (if (step > 0) i <= to else i >= to) {
            if (guard >= RuleRuntime.MAX_LOOP_ITERATIONS_PER_LOOP) {
                budgetExceeded++
                break
            }
            if (!loopBudgetOk()) break
            guard++
            loopIterationsThisFrame++
            // 循环变量直接写进变量表：和 setVar 一样会触发级联，语义统一
            applySetVar(name, i.toFloat())
            executeActions(body.body, args, depth, loopDepth + 1)
            if (actionsThisFrame >= MAX_ACTIONS_PER_FRAME) {
                budgetExceeded++
                break
            }
            i += step
        }
    }

    /**
     * while 循环：每轮重新求值条件。
     *
     * 条件恒真（`1 == 1`）时靠 [RuleAction.WhileLoop.maxIterations] 与共享预算停下，
     * 不依赖规则作者写对终止条件。
     */
    private fun runWhileLoop(body: RuleAction.WhileLoop, args: Map<String, String>, depth: Int, loopDepth: Int) {
        val limit = body.maxIterations.coerceIn(1, RuleRuntime.MAX_LOOP_ITERATIONS_PER_LOOP)
        val varName = substitute(body.varName, args)
        var guard = 0
        while (guard < limit) {
            if (!loopBudgetOk()) break
            // 轮次变量在求条件之前写入：条件里可以直接写 `i < 7`
            if (varName.isNotBlank()) vars[varName] = guard.toFloat()
            val cond = evalText(substitute(body.condition, args), "/while/expr") > 0.5f
            if (!cond) break
            guard++
            loopIterationsThisFrame++
            executeActions(body.body, args, depth, loopDepth + 1)
            if (actionsThisFrame >= MAX_ACTIONS_PER_FRAME) {
                budgetExceeded++
                break
            }
        }
        // 循环变量本身的变化不该每一轮都触发级联（那会放大规则执行量），
        // 退出后统一记一次，让 var 触发器只收到最终值。
        if (varName.isNotBlank()) {
            vars[varName] = (guard - 1).coerceAtLeast(0).toFloat()
            pendingVarChanges.add(varName)
        }
    }

    /** 循环还能不能继续：共享迭代预算 + 时间片。超了就记一次超预算。 */
    private fun loopBudgetOk(): Boolean {
        if (loopIterationsThisFrame >= RuleRuntime.MAX_LOOP_ITERATIONS_PER_LOOP) {
            budgetExceeded++
            return false
        }
        // 第一轮永远放行：冷启动（类加载/JIT）本身就可能吃掉一帧的时间片，
        // 不放行会让"第一次点击时循环完全不生效"，比掉一帧难查得多
        if (loopIterationsThisFrame > 0 && System.nanoTime() > frameDeadlineNs) {
            budgetExceeded++
            return false
        }
        return true
    }

    /** `{{name}}` → 参数值；没有替换则原样返回。 */
    private fun substitute(text: String, args: Map<String, String>): String {
        if (args.isEmpty() || !text.contains("{{")) return text
        var out = text
        for ((k, v) in args) {
            out = out.replace("{{$k}}", v)
        }
        return out
    }

    /** 写变量；同名写入不产生"变化"。 */
    private fun applySetVar(name: String, value: Float) {
        vars[name] = value
        pendingVarChanges.add(name)
    }

    /** 待处理的变量变化：级联到 var 触发器规则。 */
    private val pendingVarChanges = LinkedHashSet<String>()

    /**
     * 处理变量级联。
     *
     * 为什么需要上限：规则 A 改 x、规则 x 又改 A，天然会绕圈。
     * 这里最多迭代 [MAX_CASCADE_PASSES] 轮，超出后停止并计数（不崩、不死循环）。
     */
    private fun flushCascade() {
        var pass = 0
        while (pendingVarChanges.isNotEmpty() && pass < MAX_CASCADE_PASSES) {
            val names = pendingVarChanges.toList()
            pendingVarChanges.clear()
            for (name in names) {
                val matched = model.rules.filter {
                    val t = it.trigger
                    t is RuleTrigger.VarChanged && t.varName == name
                }
                if (matched.isNotEmpty()) runRules(matched)
            }
            pass++
        }
        if (pendingVarChanges.isNotEmpty()) {
            // 达到上限仍有待处理变化：说明存在环或过深依赖
            budgetExceeded++
            pendingVarChanges.clear()
        }
    }

    // ---------------------------------------------------------------- 求值

    private fun evalText(exprText: String, path: String): Float {
        val expr = actionExprs.getOrPut("expr:" + exprText) { parseOrNull(exprText, path) }
        if (expr == null) return 0f
        return ExprParser.evaluate(expr, context()).asNumber()
    }

    private fun parseOrNull(text: String, path: String): Expr? = try {
        ExprParser.parse(text, path)
    } catch (e: Throwable) {
        // 解析失败在加载阶段已被校验过；这里兜底为 null（该规则不生效），不让壁纸崩掉
        null
    }

    private fun context() = ExprContext(
        numberVar = { name ->
            // 先看自定义变量，再看系统变量
            if (vars.containsKey(name)) vars[name] else systemVar(name)
        },
        textVar = { name -> textVar(name) },
        randomSource = randomSource
    )
}
