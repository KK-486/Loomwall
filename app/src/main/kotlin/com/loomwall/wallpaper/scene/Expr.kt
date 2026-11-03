package com.loomwall.wallpaper.scene

/**
 * 受控表达式求值器（触发器规则用）。
 *
 * 为什么自己写解析器而不用 `javax.script` / eval：
 * 1. 壁纸在手机上常驻，任意代码无法静态判断功耗与死循环；
 * 2. 平台自带的 Rhino/Nashorn 在 Android 上不可用，引入 JS 引擎等于引入第三方依赖；
 * 3. 自己实现才能做到"未知名字直接报错并带路径"，而不是运行期静默变 NaN。
 *
 * 支持（白名单，全部无副作用）：
 * - 数字、字符串字面量、变量名
 * - `+ - * / %`、比较 `> < >= <= == !=`、逻辑 `and or not`
 * - 括号与一元负号
 * - 函数：clamp min max abs floor ceil round sqrt sin cos mod if random
 *
 * **明确不做**：赋值、循环、递归、自定义函数体、文件/网络/传感器访问。
 */
sealed class Expr {
    data class Num(val value: Float) : Expr()
    data class Str(val value: String) : Expr()
    data class Var(val name: String) : Expr()
    data class Unary(val op: String, val operand: Expr) : Expr()
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
    data class Call(val name: String, val args: List<Expr>) : Expr()
}

/** 求值结果：数字 / 文本 / 布尔（布尔只是 0/1 的糖，便于条件判断）。 */
sealed class ExprValue {
    data class Num(val value: Float) : ExprValue()
    data class Str(val value: String) : ExprValue()
    data class Bool(val value: Boolean) : ExprValue()

    fun asNumber(): Float = when (this) {
        is Num -> value
        is Bool -> if (value) 1f else 0f
        is Str -> value.toFloatOrNull() ?: 0f
    }

    fun asBool(): Boolean = when (this) {
        is Bool -> value
        is Num -> value != 0f
        is Str -> value.isNotEmpty()
    }

    fun asText(): String = when (this) {
        is Num -> if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
        is Bool -> if (value) "1" else "0"
        is Str -> value
    }
}

/** 变量解析上下文：数字变量与文本变量分开，未知变量返回 null。 */
class ExprContext(
    val numberVar: (String) -> Float? = { null },
    val textVar: (String) -> String? = { null },
    /** random() 的注入点：测试时给固定值，运行时用随机。 */
    val randomSource: () -> Float = { kotlin.random.Random.nextFloat() }
)

object ExprParser {

    /** 表达式最大长度：超长一定是误用（比如粘贴了整段 JSON）。 */
    const val MAX_LENGTH = 512

    /** 括号/函数嵌套上限。 */
    const val MAX_DEPTH = 16

    fun parse(source: String, path: String = "/expr"): Expr {
        val text = source.trim()
        if (text.isEmpty()) throw SceneFormatException(path, "表达式为空")
        if (text.length > MAX_LENGTH) {
            throw SceneFormatException(path, "表达式过长（${text.length} > $MAX_LENGTH）")
        }
        val parser = Parser(text, path)
        val expr = parser.parseExpression(0)
        parser.skipSpaces()
        if (!parser.atEnd()) {
            throw SceneFormatException(path, "多余的内容：\"${parser.rest()}\"")
        }
        return expr
    }

    private class Parser(val text: String, val path: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length
        fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
        fun rest(): String = text.substring(pos)
        private fun peek(): Char? = if (pos < text.length) text[pos] else null
        private fun error(msg: String): Nothing =
            throw SceneFormatException(path, "$msg（位置 $pos：\"${rest().take(16)}\"）")

        fun parseExpression(depth: Int): Expr {
            if (depth > MAX_DEPTH) error("嵌套过深（超过 $MAX_DEPTH 层）")
            return parseOr(depth)
        }

        private fun parseOr(depth: Int): Expr {
            var left = parseAnd(depth)
            while (true) {
                skipSpaces()
                if (matchWord("or")) left = Expr.Binary("or", left, parseAnd(depth)) else break
            }
            return left
        }

        private fun parseAnd(depth: Int): Expr {
            var left = parseComparison(depth)
            while (true) {
                skipSpaces()
                if (matchWord("and")) left = Expr.Binary("and", left, parseComparison(depth)) else break
            }
            return left
        }

        private fun parseComparison(depth: Int): Expr {
            var left = parseAdditive(depth)
            while (true) {
                skipSpaces()
                val op = matchAny(">=", "<=", "==", "!=", ">", "<") ?: break
                left = Expr.Binary(op, left, parseAdditive(depth))
            }
            return left
        }

        private fun parseAdditive(depth: Int): Expr {
            var left = parseMultiplicative(depth)
            while (true) {
                skipSpaces()
                val op = matchAny("+", "-") ?: break
                left = Expr.Binary(op, left, parseMultiplicative(depth))
            }
            return left
        }

        private fun parseMultiplicative(depth: Int): Expr {
            var left = parseUnary(depth)
            while (true) {
                skipSpaces()
                val op = matchAny("*", "/", "%") ?: break
                left = Expr.Binary(op, left, parseUnary(depth))
            }
            return left
        }

        private fun parseUnary(depth: Int): Expr {
            if (depth > MAX_DEPTH) error("嵌套过深（超过 $MAX_DEPTH 层）")
            skipSpaces()
            if (peek() == '-') {
                pos++
                return Expr.Unary("-", parseUnary(depth + 1))
            }
            if (matchWord("not")) {
                return Expr.Unary("not", parseUnary(depth + 1))
            }
            return parsePower(depth)
        }

        /**
         * 幂运算：右结合、优先级高于乘除（与数学写法一致）。
         *
         * 为什么需要它：音频可视化的常见需求是"做平方响应"（低频更灵敏），
         * 写成 `audio(2)^2` 比 `audio(2)*audio(2)` 直观得多。
         */
        private fun parsePower(depth: Int): Expr {
            val base = parsePrimary(depth)
            skipSpaces()
            if (peek() == '^') {
                pos++
                return Expr.Binary("pow", base, parsePower(depth + 1))
            }
            return base
        }

        private fun parsePrimary(depth: Int): Expr {
            skipSpaces()
            val c = peek() ?: error("表达式意外结束")
            if (c == '(') {
                pos++
                val inner = parseExpression(depth + 1)
                skipSpaces()
                if (peek() != ')') error("缺少右括号")
                pos++
                return inner
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c == '"' || c == '\'') return parseString()
            if (c.isLetter() || c == '_') return parseIdentOrCall(depth)
            error("无法识别的字符 '$c'")
        }

        private fun parseNumber(): Expr {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            val raw = text.substring(start, pos)
            val value = raw.toFloatOrNull() ?: error("数字格式错误：\"$raw\"")
            return Expr.Num(value)
        }

        private fun parseString(): Expr {
            val quote = text[pos]
            pos++
            val start = pos
            while (pos < text.length && text[pos] != quote) pos++
            if (pos >= text.length) error("字符串没有结束引号")
            val value = text.substring(start, pos)
            pos++
            return Expr.Str(value)
        }

        private fun parseIdentOrCall(depth: Int): Expr {
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '.')) pos++
            val name = text.substring(start, pos)
            skipSpaces()
            if (peek() != '(') return Expr.Var(name)

            pos++  // 吃掉 '('
            val args = ArrayList<Expr>()
            skipSpaces()
            if (peek() == ')') {
                pos++
            } else {
                while (true) {
                    args.add(parseExpression(depth + 1))
                    skipSpaces()
                    val c2 = peek() ?: error("函数 \"$name\" 的参数没有闭合")
                    if (c2 == ',') { pos++; continue }
                    if (c2 == ')') { pos++; break }
                    error("函数 \"$name\" 的参数分隔符错误")
                }
            }
            requireKnownFunction(name, args.size)
            return Expr.Call(name.lowercase(), args)
        }

        private fun requireKnownFunction(name: String, argCount: Int) {
            val lower = name.lowercase()
            val expected: IntRange = when (lower) {
                "clamp" -> 3..3
                "if" -> 3..3
                "min", "max", "mod" -> 2..2
                "abs", "floor", "ceil", "round", "sqrt", "sin", "cos" -> 1..1
                "random" -> 0..0
                else -> error("未知函数 \"$name\"（支持 clamp/min/max/abs/floor/ceil/round/sqrt/sin/cos/mod/if/random）")
            }
            if (argCount !in expected) {
                error("函数 \"$name\" 需要 $expected 个参数，实际 $argCount 个")
            }
        }

        fun matchWord(word: String): Boolean {
            skipSpaces()
            if (!text.startsWith(word, pos)) return false
            val after = pos + word.length
            // 避免把 order 当成 or：词后必须是边界
            if (after < text.length && (text[after].isLetterOrDigit() || text[after] == '_')) return false
            pos = after
            return true
        }

        fun matchAny(vararg ops: String): String? {
            for (op in ops) {
                if (text.startsWith(op, pos)) {
                    pos += op.length
                    return op
                }
            }
            return null
        }
    }

    /** 求值。除零 / 未知变量都不会崩：除零得 0，未知变量由调用方决定语义（这里得 0）。 */
    fun evaluate(expr: Expr, ctx: ExprContext = ExprContext()): ExprValue {
        return when (expr) {
            is Expr.Num -> ExprValue.Num(expr.value)
            is Expr.Str -> ExprValue.Str(expr.value)
            is Expr.Var -> {
                val asNumber = ctx.numberVar(expr.name)
                if (asNumber != null) {
                    ExprValue.Num(asNumber)
                } else {
                    val asText = ctx.textVar(expr.name)
                    if (asText != null) ExprValue.Str(asText) else ExprValue.Num(0f)
                }
            }
            is Expr.Unary -> when (expr.op) {
                "-" -> ExprValue.Num(-evaluate(expr.operand, ctx).asNumber())
                "not" -> ExprValue.Bool(!evaluate(expr.operand, ctx).asBool())
                else -> ExprValue.Num(0f)
            }
            is Expr.Binary -> {
                // and / or 需要短路，先单独处理
                when (expr.op) {
                    "and" -> return ExprValue.Bool(
                        evaluate(expr.left, ctx).asBool() && evaluate(expr.right, ctx).asBool()
                    )
                    "or" -> return ExprValue.Bool(
                        evaluate(expr.left, ctx).asBool() || evaluate(expr.right, ctx).asBool()
                    )
                }
                val l = evaluate(expr.left, ctx)
                val r = evaluate(expr.right, ctx)
                when (expr.op) {
                    "pow" -> ExprValue.Num(
                        Math.pow(l.asNumber().toDouble(), r.asNumber().toDouble()).toFloat()
                    )
                    "+" -> ExprValue.Num(l.asNumber() + r.asNumber())
                    "-" -> ExprValue.Num(l.asNumber() - r.asNumber())
                    "*" -> ExprValue.Num(l.asNumber() * r.asNumber())
                    "/" -> {
                        val d = r.asNumber()
                        ExprValue.Num(if (d == 0f) 0f else l.asNumber() / d)
                    }
                    "%" -> {
                        val d = r.asNumber()
                        ExprValue.Num(if (d == 0f) 0f else l.asNumber() % d)
                    }
                    ">" -> ExprValue.Bool(l.asNumber() > r.asNumber())
                    "<" -> ExprValue.Bool(l.asNumber() < r.asNumber())
                    ">=" -> ExprValue.Bool(l.asNumber() >= r.asNumber())
                    "<=" -> ExprValue.Bool(l.asNumber() <= r.asNumber())
                    "==" -> if (l is ExprValue.Str || r is ExprValue.Str) {
                        ExprValue.Bool(l.asText() == r.asText())
                    } else {
                        ExprValue.Bool(l.asNumber() == r.asNumber())
                    }
                    "!=" -> if (l is ExprValue.Str || r is ExprValue.Str) {
                        ExprValue.Bool(l.asText() != r.asText())
                    } else {
                        ExprValue.Bool(l.asNumber() != r.asNumber())
                    }
                    else -> ExprValue.Num(0f)
                }
            }
            is Expr.Call -> evaluateCall(expr, ctx)
        }
    }

    private fun evaluateCall(call: Expr.Call, ctx: ExprContext): ExprValue {
        val args = call.args.map { evaluate(it, ctx) }
        fun n(i: Int) = args[i].asNumber()
        return when (call.name) {
            "clamp" -> ExprValue.Num(n(0).coerceIn(n(1), n(2)))
            "min" -> ExprValue.Num(kotlin.math.min(n(0), n(1)))
            "max" -> ExprValue.Num(kotlin.math.max(n(0), n(1)))
            "abs" -> ExprValue.Num(kotlin.math.abs(n(0)))
            "floor" -> ExprValue.Num(kotlin.math.floor(n(0)))
            "ceil" -> ExprValue.Num(kotlin.math.ceil(n(0)))
            "round" -> ExprValue.Num(kotlin.math.round(n(0)))
            "sqrt" -> ExprValue.Num(if (n(0) < 0f) 0f else kotlin.math.sqrt(n(0)))
            "sin" -> ExprValue.Num(kotlin.math.sin(n(0)))
            "cos" -> ExprValue.Num(kotlin.math.cos(n(0)))
            "mod" -> {
                val d = n(1)
                // 负数取模按"始终非负"处理，避免 步进 变量出现 -1
                ExprValue.Num(if (d == 0f) 0f else ((n(0) % d) + d) % d)
            }
            "if" -> if (args[0].asBool()) args[1] else args[2]
            "random" -> ExprValue.Num(ctx.randomSource())
            else -> ExprValue.Num(0f)
        }
    }
}
