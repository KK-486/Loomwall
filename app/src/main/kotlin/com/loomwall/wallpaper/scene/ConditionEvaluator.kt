package com.loomwall.wallpaper.scene

/**
 * `if(...)` 的条件求值器。
 *
 * 支持的语法（刻意保持够用，不是通用表达式语言）：
 * ```
 * sys.battery.level > 20
 * sys.battery.level >= 20 and sys.battery.charging
 * sys.media.title != ""
 * not sys.battery.charging
 * sys.battery.level < 10 or sys.battery.level == 100
 * ```
 * - 比较：`==`（也接受 `=`）、`!=`（也接受 `<>`）、`>` `>=` `<` `<=`
 * - 逻辑：`and` / `&&`、`or` / `||`、`not` / `!`
 * - 操作数：数字、带引号的字符串、变量名（点分）
 *
 * 设计取舍：不做算术（`a + 1` 这类），也不做括号分组——条件只允许"比较 + 与或非"，
 * 这样既满足日常需求，又不会长成一套编程语言。
 */
internal class ConditionEvaluator(
    private val text: String,
    private val vars: Map<String, Float>,
    private val texts: Map<String, String>
) {
    private var pos = 0

    /** 求值结果；语法有问题时按 false 处理（模板里不该因为条件写错就整行消失）。 */
    fun evaluate(): Boolean = runCatching { parseOr() }.getOrDefault(false)

    private fun parseOr(): Boolean {
        var value = parseAnd()
        while (true) {
            skipSpace()
            if (eatWord("or") || eatOperator("||")) {
                val right = parseAnd()
                value = value || right
            } else {
                return value
            }
        }
    }

    private fun parseAnd(): Boolean {
        var value = parseNot()
        while (true) {
            skipSpace()
            if (eatWord("and") || eatOperator("&&")) {
                val right = parseNot()
                value = value && right
            } else {
                return value
            }
        }
    }

    private fun parseNot(): Boolean {
        skipSpace()
        if (eatWord("not") || (peek() == '!' && peekAt(1) != '=')) {
            pos++
            return !parseNot()
        }
        return parseComparison()
    }

    private fun parseComparison(): Boolean {
        val left = parseOperand()
        skipSpace()
        val op = readOperator() ?: return truthy(left)
        val right = parseOperand()
        return compare(left, op, right)
    }

    // ---- 操作数 ----

    private class Value(val number: Float?, val text: String?) {
        val isNumber: Boolean get() = number != null
    }

    private fun parseOperand(): Value {
        skipSpace()
        if (pos >= text.length) return Value(null, null)
        val ch = text[pos]

        if (ch == '"' || ch == '\'') {
            val quote = ch
            pos++
            val builder = StringBuilder()
            while (pos < text.length && text[pos] != quote) {
                builder.append(text[pos])
                pos++
            }
            if (pos < text.length) pos++ // 跳过收尾引号
            return Value(null, builder.toString())
        }

        // 数字（允许负号与小数）
        if (ch.isDigit() || ch == '-' || ch == '.') {
            val start = pos
            if (ch == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            val raw = text.substring(start, pos)
            return Value(raw.toFloatOrNull(), raw)
        }

        // 变量名
        val start = pos
        while (pos < text.length &&
            (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '.')
        ) {
            pos++
        }
        val name = text.substring(start, pos)
        if (name.isEmpty()) {
            pos++
            return Value(null, null)
        }
        texts[name]?.let { return Value(null, it) }
        return Value(vars[name], null)
    }

    // ---- 比较 ----

    private fun compare(left: Value, op: String, right: Value): Boolean {
        if (left.isNumber && right.isNumber) {
            val a = left.number!!
            val b = right.number!!
            return when (op) {
                "==", "=" -> a == b
                "!=", "<>" -> a != b
                ">" -> a > b
                ">=" -> a >= b
                "<" -> a < b
                "<=" -> a <= b
                else -> false
            }
        }

        // 任意一边不是数字就按字符串比较；顺序运算符对字符串没有意义，返回 false
        val a = left.text ?: left.number?.toString() ?: ""
        val b = right.text ?: right.number?.toString() ?: ""
        return when (op) {
            "==", "=" -> a == b
            "!=", "<>" -> a != b
            else -> false
        }
    }

    /** 裸值：数字非 0、字符串非空、缺失为 false。 */
    private fun truthy(value: Value): Boolean = when {
        value.isNumber -> value.number != 0f
        value.text != null -> value.text!!.isNotEmpty()
        else -> false
    }

    // ---- 扫描辅助 ----

    private fun readOperator(): String? {
        skipSpace()
        for (op in listOf(">=", "<=", "!=", "<>", "==", "=", ">", "<")) {
            if (text.startsWith(op, pos)) {
                pos += op.length
                return op
            }
        }
        return null
    }

    private fun eatOperator(op: String): Boolean {
        skipSpace()
        if (text.startsWith(op, pos)) {
            pos += op.length
            return true
        }
        return false
    }

    private fun eatWord(word: String): Boolean {
        if (!text.startsWith(word, pos, ignoreCase = true)) return false
        val end = pos + word.length
        // 必须是独立单词，避免把变量名里的 and/or 当关键字
        if (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '.')) {
            return false
        }
        pos = end
        return true
    }

    private fun peek(): Char? = if (pos < text.length) text[pos] else null

    private fun peekAt(offset: Int): Char? =
        if (pos + offset < text.length) text[pos + offset] else null

    private fun skipSpace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}
