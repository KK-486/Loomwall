package com.loomwall.wallpaper.scene

import org.json.JSONArray
import org.json.JSONObject

/**
 * 触发器规则模型（`interactions 0.3` 新增部分）。
 *
 * 设计要点（见 docs/triggers-design.md）：
 * - **规则表是运行时唯一真相**，编辑器里的连线只是同一份数据的可视化视图。
 * - 一条规则 = 触发源（+ 条件）→ 动作列表。引擎按顺序匹配执行，O(规则数)。
 * - 变量承载状态（如菜单展开/收起），让"点一下开、再点一下关"变成幂等操作，
 *   而不是靠 toggle 的副作用。
 *
 * 为什么不用任意脚本：壁纸在手机上常驻运行，任意代码无法静态判断功耗与死循环风险。
 * 这里的表达式与动作都是白名单，解析失败会报出 JSON 路径而不是静默失败。
 */

/** 变量定义：默认值 + 可选范围（编辑器用来出滑块）。 */
data class RuleVar(
    val name: String,
    val value: Float = 0f,
    val min: Float = 0f,
    val max: Float = 1f
)

/** 触发源。密封类：新增来源只加分支，不改已有结构。 */
sealed class RuleTrigger {
    /** 点击某个元素；[targetId] 为 null 表示整块画布。 */
    data class Tap(val targetId: String?) : RuleTrigger()

    /** 变量变化（含系统变量）。 */
    /**
     * 手势触发：[kind] 取 tap / doubleTap / longPress / swipeLeft / swipeRight / swipeUp / swipeDown。
     * [targetId] 为空表示整块画布都能触发。
     */
    data class Gesture(val kind: String, val targetId: String?) : RuleTrigger()

    data class VarChanged(val varName: String) : RuleTrigger()

    /** 每分钟一次（仅壁纸可见时）。 */
    data object Minute : RuleTrigger()

    /** 每次回到桌面（可见性变 true）。 */
    data object Session : RuleTrigger()
}

/** 动作。 */
sealed class RuleAction {
    /** 设定变量：优先用 [expr]（表达式），否则用 [value]。 */
    data class SetVar(val varName: String, val expr: String?, val value: Float) : RuleAction()

    /** 显示/隐藏：优先用 [expr]（真值），否则用 [value]。 */
    data class SetVisible(val targetId: String, val expr: String?, val value: Boolean) : RuleAction()

    /** 设置属性：表达式求值后写入。 */
    /**
     * 设置数值属性。
     *
     * [durationMs] > 0 时做补间（动画），= 0 时瞬移 —— 这是"有动画地进出"的关键：
     * 弹菜单、滑卡片都需要"从当前位置平移到目标位置"，而不是直接跳到目标。
     */
    data class SetProperty(
        val targetId: String,
        val property: String,
        val expr: String,
        val durationMs: Int = 0,
        val easing: String = "easeOut",
        /** 循环次数；0 = 不循环。配合 [intervalMs] 由渲染线程调度重放。 */
        val repeat: Int = 0,
        /** 循环间隔（毫秒）。 */
        val intervalMs: Int = 0
    ) : RuleAction()

    /** 播放动画片段。 */
    /**
     * 播放动画。
     *
     * [reverse] = true 时从尾帧播回首帧 —— "打开/收起"只需做一条动画，
     * 不必再手工对一遍反向关键帧。
     */
    data class PlayAnimation(val clipId: String, val reverse: Boolean = false) : RuleAction()

    /** 启动应用（包名）。 */
    data class LaunchApp(val packageName: String) : RuleAction()

    /** 打开链接/系统页（uri）。 */
    data class OpenUri(val uri: String) : RuleAction()

    /** 调用另一个函数（宏），参数是"名字 → 字面值"。 */
    data class CallFunction(val name: String, val args: Map<String, String>) : RuleAction()

    /**
     * for 循环：把 [body] 重复执行若干次，循环变量写进变量的样子和普通 `setVar` 一致。
     *
     * 三个上限（[MAX_LOOP_ITERATIONS] / [MAX_LOOP_DEPTH] / [MAX_ACTIONS_PER_LOOP_BODY]）
     * 是硬约束：循环是最容易写出"壁纸卡死"的地方，宁可截断也不能让渲染线程转不回来。
     */
    data class ForLoop(
        val varName: String,
        val fromExpr: String,
        val toExpr: String,
        val stepExpr: String,
        val body: List<RuleAction>
    ) : RuleAction()

    /**
     * while 循环：条件为真就重复执行 [body]。
     *
     * [maxIterations] 是这条规则自己的上限（解析时已收进 [MAX_LOOP_ITERATIONS]）——
     * 条件恒真（比如 `1 == 1`）时会靠它停下来，而不是靠运气。
     */
    data class WhileLoop(
        val condition: String,
        val maxIterations: Int,
        /**
         * 循环变量名：每轮开始前置为当前轮次（从 0 起），条件里可以直接用它
         * （例如 `i < 7` 表示跑 7 轮）。为空表示不提供索引，条件只能看其它变量。
         *
         * 为什么必须有它：只有条件而没有计数器时，用户写 `i > 7` 会永远不成立、
         * 一次都不跑 —— 这不是死循环，但同样让人一头雾水（用户实际反馈过）。
         */
        val varName: String,
        val body: List<RuleAction>
    ) : RuleAction()
}

/** 一条规则。 */
data class Rule(
    val id: String,
    val trigger: RuleTrigger,
    /** 附加条件表达式；空 = 恒真。 */
    val condition: String? = null,
    val actions: List<RuleAction> = emptyList(),
    /** 来自哪个模板（编辑器用来分组/再生成），引擎忽略。 */
    val template: String? = null
)

/** 命名宏：参数 + 一组动作。编译期/运行期按参数替换执行。 */
data class RuleFunction(
    val name: String,
    val params: List<String>,
    val steps: List<RuleAction>
)

/** 整个触发器部分。 */
data class TriggerModel(
    val vars: List<RuleVar> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val functions: List<RuleFunction> = emptyList()
) {
    companion object {
        val EMPTY = TriggerModel()
    }
}

/** 解析 `interactions.json` 里的 vars / rules / functions。缺字段时返回空模型。 */
object TriggerParser {

    /** 单条规则允许的最大动作数：防止一份预设写出几百个动作拖垮一帧。 */
    const val MAX_ACTIONS_PER_RULE = 16

    /** 单个循环体允许的最大动作数（和一条规则的上限保持一致）。 */
    const val MAX_ACTIONS_PER_LOOP_BODY = 16

    /** 循环嵌套层数上限：两层足够表达"每行每列"，再多一定是写错了。 */
    const val MAX_LOOP_DEPTH = 2

    /** 单次触发里循环迭代总数上限，跨所有循环累计。 */
    const val MAX_LOOP_ITERATIONS = 200

    fun parse(root: JSONObject): TriggerModel {
        val vars = parseVars(root.optJSONObject("vars"))
        val functions = parseFunctions(root.optJSONObject("functions"))
        val rules = parseRules(root.opt("rules"), functions.map { it.name }.toSet())
        return TriggerModel(vars, rules, functions)
    }

    private fun parseVars(json: JSONObject?): List<RuleVar> {
        if (json == null) return emptyList()
        val out = ArrayList<RuleVar>(json.length())
        for (name in json.keys()) {
            val raw = json.opt(name)
            val path = "/vars/$name"
            val v = when (raw) {
                is JSONObject -> RuleVar(
                    name = name,
                    value = raw.optDouble("value", 0.0).toFloat(),
                    min = raw.optDouble("min", 0.0).toFloat(),
                    max = raw.optDouble("max", 1.0).toFloat()
                )
                is Number -> RuleVar(name = name, value = raw.toFloat())
                else -> throw SceneFormatException(path, "变量必须是数字或 {value,min,max} 对象")
            }
            if (v.min > v.max) throw SceneFormatException(path, "min 不能大于 max")
            out.add(v)
        }
        return out
    }

    private fun parseFunctions(json: JSONObject?): List<RuleFunction> {
        // 两遍解析：先把所有函数名收集齐，再解析各自的步骤。
        // 否则"函数 A 调用函数 B、但 B 定义在后面"会被误判为"调用了不存在的函数"。
        if (json == null) return emptyList()
        val knownNames = json.keys().asSequence().toSet()
        val out = ArrayList<RuleFunction>(json.length())
        for (name in json.keys()) {
            val obj = json.optJSONObject(name)
                ?: throw SceneFormatException("/functions/$name", "必须是对象")
            val path = "/functions/$name"
            val params = ArrayList<String>()
            obj.optJSONArray("params")?.let { arr ->
                for (i in 0 until arr.length()) params.add(arr.optString(i, ""))
            }
            val stepsJson = obj.optJSONArray("steps")
                ?: throw SceneFormatException("$path/steps", "必须是数组")
            val steps = parseActions(stepsJson, "$path/steps", knownNames)
            out.add(RuleFunction(name, params, steps))
        }
        return out
    }

    private fun parseRules(raw: Any?, functionNames: Set<String>): List<Rule> {
        if (raw == null) return emptyList()
        if (raw !is JSONArray) throw SceneFormatException("/rules", "必须是数组")
        val out = ArrayList<Rule>(raw.length())
        for (i in 0 until raw.length()) {
            val obj = raw.optJSONObject(i)
                ?: throw SceneFormatException("/rules[$i]", "必须是对象")
            out.add(parseRule(obj, "/rules[$i]", functionNames))
        }
        return out
    }

    private fun parseRule(obj: JSONObject, path: String, functionNames: Set<String>): Rule {
        val id = obj.optString("id", "").ifBlank {
            throw SceneFormatException("$path/id", "规则必须有 id")
        }
        val whenObj = obj.optJSONObject("when")
            ?: throw SceneFormatException("$path/when", "规则必须有 when")
        val trigger = parseTrigger(whenObj, "$path/when")
        val condition = whenObj.optString("expr", "").ifBlank { null }
        val thenArr = obj.optJSONArray("then")
            ?: throw SceneFormatException("$path/then", "规则必须有 then 数组")
        if (thenArr.length() > MAX_ACTIONS_PER_RULE) {
            throw SceneFormatException(
                "$path/then",
                "单条规则最多 $MAX_ACTIONS_PER_RULE 个动作（当前 ${thenArr.length()}）"
            )
        }
        val actions = parseActions(thenArr, "$path/then", functionNames)
        return Rule(
            id = id,
            trigger = trigger,
            condition = condition,
            actions = actions,
            template = obj.optString("template", "").ifBlank { null }
        )
    }

    private fun parseTrigger(triggerJson: JSONObject, path: String): RuleTrigger {
        // 注意：参数不能叫 when —— 那是 Kotlin 关键字，会让整个 when 表达式解析失败（踩过一次）
        return when (val type = triggerJson.optString("trigger", "")) {
            "tap" -> RuleTrigger.Tap(triggerJson.optString("target", "").ifBlank { null })
            "gesture" -> {
                val kind = triggerJson.optString("gesture", "").ifBlank {
                    throw SceneFormatException("$path/gesture", "gesture 触发必须给手势名")
                }
                RuleTrigger.Gesture(kind, triggerJson.optString("target", "").ifBlank { null })
            }
            "var" -> {
                val name = triggerJson.optString("var", "")
                if (name.isBlank()) throw SceneFormatException("$path/var", "var 触发必须给变量名")
                RuleTrigger.VarChanged(name)
            }
            "minute" -> RuleTrigger.Minute
            "session" -> RuleTrigger.Session
            else -> throw SceneFormatException(
                "$path/trigger",
                "未知触发类型 \"$type\"（支持 tap / gesture / var / minute / session）"
            )
        }
    }

    private fun parseActions(
        arr: JSONArray,
        path: String,
        functionNames: Set<String>,
        loopDepth: Int = 0
    ): List<RuleAction> {
        val out = ArrayList<RuleAction>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
                ?: throw SceneFormatException("$path[$i]", "动作必须是对象")
            out.add(parseAction(obj, "$path[$i]", functionNames, loopDepth))
        }
        return out
    }

    private fun parseAction(
        obj: JSONObject,
        path: String,
        functionNames: Set<String>,
        loopDepth: Int = 0
    ): RuleAction {
        return when (val type = obj.optString("type", "")) {
            "setVar" -> {
                val name = obj.optString("var", "")
                if (name.isBlank()) throw SceneFormatException("$path/var", "setVar 必须给变量名")
                RuleAction.SetVar(
                    varName = name,
                    expr = obj.optString("expr", "").ifBlank { null },
                    value = obj.optDouble("value", 0.0).toFloat()
                )
            }
            "setVisible" -> {
                val target = obj.optString("target", "")
                if (target.isBlank()) throw SceneFormatException("$path/target", "setVisible 必须给目标 id")
                RuleAction.SetVisible(
                    targetId = target,
                    expr = obj.optString("expr", "").ifBlank { null },
                    value = obj.optBoolean("value", true)
                )
            }
            "setProperty" -> {
                // 动画时长与缓动：0 表示瞬移（兼容旧预设）
                val duration = obj.optInt("duration", 0).coerceIn(0, 5000)
                val target = obj.optString("target", "")
                val prop = obj.optString("property", "")
                if (target.isBlank()) throw SceneFormatException("$path/target", "setProperty 必须给目标 id")
                if (prop.isBlank()) throw SceneFormatException("$path/property", "setProperty 必须给属性名")
                val easing = obj.optString("easing", "easeOut")
                if (easing !in com.loomwall.wallpaper.engine.Easing.NAMES) {
                    throw SceneFormatException("$path/easing", "未知缓动 \"$easing\"（支持 " +
                        com.loomwall.wallpaper.engine.Easing.NAMES.joinToString(" / ") + "）")
                }
                RuleAction.SetProperty(
                    target,
                    prop,
                    obj.optString("expr", ""),
                    duration,
                    easing,
                    obj.optInt("repeat", 0).coerceIn(0, 999),
                    obj.optInt("intervalMs", 0).coerceIn(0, 600000)
                )
            }
            "playAnimation" -> {
                val clip = obj.optString("clip", obj.optString("target", ""))
                if (clip.isBlank()) throw SceneFormatException("$path/clip", "playAnimation 必须给动画 id")
                RuleAction.PlayAnimation(clip, obj.optBoolean("reverse", false))
            }
            "launchApp" -> {
                val pkg = obj.optString("package", obj.optString("target", ""))
                if (pkg.isBlank()) throw SceneFormatException("$path/package", "launchApp 必须给包名")
                RuleAction.LaunchApp(pkg)
            }
            "openUri" -> {
                val uri = obj.optString("uri", "")
                if (uri.isBlank()) throw SceneFormatException("$path/uri", "openUri 必须给链接")
                RuleAction.OpenUri(uri)
            }
            "call" -> {
                val name = obj.optString("call", "")
                if (name.isBlank()) throw SceneFormatException("$path/call", "call 必须给函数名")
                if (name !in functionNames) {
                    throw SceneFormatException("$path/call", "调用了不存在的函数 \"$name\"")
                }
                val args = LinkedHashMap<String, String>()
                obj.optJSONObject("args")?.let { a ->
                    for (k in a.keys()) args[k] = a.optString(k, "")
                }
                RuleAction.CallFunction(name, args)
            }
            "for" -> {
                // 循环体自己又是一组动作，所以解析前先挡住过深嵌套
                if (loopDepth >= MAX_LOOP_DEPTH) {
                    throw SceneFormatException(path, "循环最多嵌套 $MAX_LOOP_DEPTH 层")
                }
                val varName = obj.optString("var", "")
                if (varName.isBlank()) throw SceneFormatException("$path/var", "for 循环必须给循环变量名")
                val from = obj.optString("from", "0").ifBlank { "0" }
                val to = obj.optString("to", "0").ifBlank { "0" }
                val step = obj.optString("step", "1").ifBlank { "1" }
                RuleAction.ForLoop(
                    varName = varName,
                    fromExpr = from,
                    toExpr = to,
                    stepExpr = step,
                    body = parseLoopBody(obj, path, functionNames, loopDepth)
                )
            }
            "while" -> {
                if (loopDepth >= MAX_LOOP_DEPTH) {
                    throw SceneFormatException(path, "循环最多嵌套 $MAX_LOOP_DEPTH 层")
                }
                val expr = obj.optString("expr", "")
                if (expr.isBlank()) throw SceneFormatException("$path/expr", "while 循环必须给条件表达式")
                // 默认给一个有限上限：忘了写 max 也不会变成死循环
                val max = obj.optInt("max", 20).coerceIn(1, MAX_LOOP_ITERATIONS)
                RuleAction.WhileLoop(
                    condition = expr,
                    maxIterations = max,
                    varName = obj.optString("var", ""),
                    body = parseLoopBody(obj, path, functionNames, loopDepth)
                )
            }
            else -> throw SceneFormatException(
                "$path/type",
                "未知动作类型 \"$type\"（支持 setVar / setVisible / setProperty / playAnimation / " +
                    "launchApp / openUri / call / for / while）"
            )
        }
    }

    /** 循环体：`do` 数组，动作数受 [MAX_ACTIONS_PER_LOOP_BODY] 限制。 */
    private fun parseLoopBody(
        obj: JSONObject,
        path: String,
        functionNames: Set<String>,
        loopDepth: Int
    ): List<RuleAction> {
        val arr = obj.optJSONArray("do")
            ?: throw SceneFormatException("$path/do", "循环必须有 do 数组")
        if (arr.length() > MAX_ACTIONS_PER_LOOP_BODY) {
            throw SceneFormatException(
                "$path/do",
                "循环体最多 $MAX_ACTIONS_PER_LOOP_BODY 个动作（当前 ${arr.length()}）"
            )
        }
        return parseActions(arr, "$path/do", functionNames, loopDepth + 1)
    }
}
