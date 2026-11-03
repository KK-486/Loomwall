package com.loomwall.wallpaper.scene

/**
 * 文本模板：把 `{变量:格式}` 占位符替换成当前值。
 *
 * 语法：
 * - `{sys.time.hour}`            → 9
 * - `{sys.time.hour:00}`         → 09（补零到两位）
 * - `{sys.battery.level:0}`      → 85（取整）
 * - `{sys.battery.level:0.0}`    → 85.0（一位小数）
 * - `}}` 转义成 `}`，`{{` 转义成 `{`
 *
 * 设计取舍：
 * - **纯函数**，不碰 Android，可在单测里验算；
 * - 变量缺失时保留原占位符（便于作者一眼看出自己写错了），而不是悄悄变成空串；
 * - 不做表达式求值（项目明确不做图灵完备的文本表达式）。
 */
object TextTemplate {

    // 注意：结尾的 `}` 必须转义。桌面 JVM 的 java.util.regex 容忍裸 `}`，
    // 但 Android 的 ICU 实现会直接抛 PatternSyntaxException —— 所以这里必须写 `\}`。
    // 结构：{{ / }} 转义，或 {名称} / {名称:格式} / {函数(参数)}
    private val PATTERN = Regex("""\{\{|\}\}|\{([A-Za-z_][\w.]*)(?:\(([^)]*)\))?(?::([0-9.]+))?\}""")

    fun render(
        template: String,
        vars: Map<String, Float>,
        /** 用于 df(...) 这类需要“现在几点”的函数；单测里可固定成任意时刻。 */
        nowMillis: Long = System.currentTimeMillis(),
        /** 文本型变量（歌曲名等），优先于数值变量查找。 */
        texts: Map<String, String> = emptyMap(),
        /** 已知的变量名；用来区分「名字写错」和「当前没有数据」。 */
        knownNames: Set<String> = SystemVars.LABELS.keys
    ): String {
        if (!template.contains('{')) return template

        // **递归展开**：if(...) 的分支里可以再写占位符，例如
        // {if(sys.battery.charging,{sys.battery.temp:0.0}°C,"未充电")}
        // 单遍替换做不到这一点（分支文本是替换后才拼进去的），所以要反复替换到稳定。
        // 加次数上限，避免自引用模板把渲染线程卡死。
        //
        // 转义括号先换成哨兵：否则 {{x}} 第一轮变成字面 {x}，第二轮又会被当成占位符替换掉。
        var current = template.replace("{{", OPEN_LITERAL).replace("}}", CLOSE_LITERAL)
        repeat(MAX_PASSES) {
            val next = renderOnce(current, vars, nowMillis, texts, knownNames)
            if (next == current) return restoreLiterals(next)
            current = next
        }
        return restoreLiterals(current)
    }

    private fun restoreLiterals(text: String): String =
        text.replace(OPEN_LITERAL, "{").replace(CLOSE_LITERAL, "}")

    /**
     * 扫一遍模板，把每个 `{...}` 求值后拼接。
     *
     * 为什么不用正则：正则的 `[^)]*` 遇到 `if(a, if(b,1,0), 2)` 这种会在第一个 `)` 截断。
     * 所以这里手写扫描——**按括号深度和引号状态找配对的花括号**，
     * 这样函数参数里再套占位符、再套函数都不会被提前切断。
     */
    private fun renderOnce(
        template: String,
        vars: Map<String, Float>,
        nowMillis: Long,
        texts: Map<String, String>,
        knownNames: Set<String>
    ): String {
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch != '{') {
                out.append(ch)
                i++
                continue
            }

            // 用符号栈找配对：{ 与 ( 都入栈，遇到对应的右括号出栈。
            // 不能用“括号深度 + 花括号深度”两个计数器——函数括号内再出现嵌套 {…} 时两者会互相干扰。
            val closeIndex = findMatching(template, i)
            if (closeIndex < 0) {
                // 没有闭合，原样输出，不让一个漏写的括号吃掉整段文本
                out.append(ch)
                i++
                continue
            }

            out.append(evaluate(template.substring(i + 1, closeIndex), vars, nowMillis, texts, knownNames))
            i = closeIndex + 1
        }
        return out.toString()
    }

    /** 求值单个占位符内容（不含两边的花括号）。 */
    private fun evaluate(
        body: String,
        vars: Map<String, Float>,
        nowMillis: Long,
        texts: Map<String, String>,
        knownNames: Set<String>
    ): String {
        val raw = "{$body}"
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return raw

        // 名字：允许 a.b.c 这种点分变量名
        var index = 0
        while (index < trimmed.length &&
            (trimmed[index].isLetterOrDigit() || trimmed[index] == '_' || trimmed[index] == '.')
        ) {
            index++
        }
        val name = trimmed.substring(0, index)
        if (name.isEmpty()) return raw

        var rest = trimmed.substring(index)
        var args: String? = null
        if (rest.startsWith("(")) {
            // 括号内的内容原样交给函数（可能还含嵌套占位符）
            val close = findMatching(rest, 0)
            if (close < 0) return raw
            args = rest.substring(1, close)
            rest = rest.substring(close + 1)
        }
        val format = if (rest.startsWith(":")) rest.substring(1) else ""

        if (args != null) {
            return callFunction(name, args, nowMillis, vars, texts, format) ?: raw
        }
        texts[name]?.let { return it }
        val value = vars[name]
        return when {
            value != null -> formatValue(value, format)
            // 名字认识、但现在没有数据（未授权 / 设备没这个传感器）→ 占位符
            name in knownNames -> MISSING
            // 完全不认识的名字：保留原样，方便作者发现写错了
            else -> raw
        }
    }

    /** 返回与开头 '(' 配对的 ')' 下标；没有就返回 -1（忽略引号内的括号）。 */
    /**
     * 从 [openIndex] 处的左括号开始，找到配对的右括号下标；找不到返回 -1。
     * 引号内的括号不算，`{` 与 `(` 可以任意嵌套。
     */
    private fun findMatching(text: String, openIndex: Int): Int {
        val stack = ArrayDeque<Char>()
        stack.addLast(if (text[openIndex] == '{') '}' else ')')
        var quote: Char? = null
        var i = openIndex + 1
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
                i++
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '{' -> stack.addLast('}')
                '(' -> stack.addLast(')')
                '}', ')' -> {
                    if (stack.isNotEmpty() && stack.last() == c) {
                        stack.removeLast()
                        if (stack.isEmpty()) return i
                    }
                    // 不匹配的右括号按普通字符处理，避免整体错位
                }
            }
            i++
        }
        return -1
    }

    /**
     * 已实现的文本函数。
     *
     * 刻意保持极小：项目明确不做图灵完备的表达式系统，
     * 只提供「格式化」这一类纯函数，避免预设里出现逻辑编程。
     */
    private fun callFunction(
        name: String,
        args: String,
        nowMillis: Long,
        vars: Map<String, Float>,
        texts: Map<String, String>,
        format: String = ""
    ): String? = when (name) {
        DF -> {
            val pattern = args.ifBlank { "HH:mm" }
            runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    .format(java.util.Date(nowMillis))
            }.getOrNull()
        }
        IF -> evalIf(args, vars, texts)
        // ---- 音频可视化：把 8 段频谱/三频段/节拍暴露成函数，任意文本都能取用 ----
        // 用法：{audio(3)} 第 3 段；{audio(3):0.00} 保留两位；{beat()} 节拍脉冲
        "audio", "band" -> {
            val index = args.trim().toIntOrNull()?.coerceIn(1, AUDIO_BANDS)
            if (index == null) null else formatValue(vars["sys.audio.b$index"] ?: 0f, format)
        }
        "level" -> formatValue(vars["sys.audio.level"] ?: 0f, format)
        "bass" -> formatValue(vars["sys.audio.bass"] ?: 0f, format)
        "mid" -> formatValue(vars["sys.audio.mid"] ?: 0f, format)
        "treble" -> formatValue(vars["sys.audio.treble"] ?: 0f, format)
        "beat" -> formatValue(vars["sys.audio.beat"] ?: 0f, format)
        else -> null
    }

    /** 频谱段数，与引擎 AudioLevelSource.BAND_COUNT 保持一致。 */
    private const val AUDIO_BANDS = 8

    /**
     * `{if(条件, 真, 假)}` —— 三参数条件，取第一个分支。
     *
     * 条件交给 [ConditionEvaluator]，支持：
     * - 比较：`> < >= <= == !=`（`=` 等同 `==`）
     * - 逻辑：`and` / `or` / `not`
     * - 操作数：数字、带引号字符串、变量名
     * 也兼容原来的「裸变量按非 0 / 非空为真」。
     */
    private fun evalIf(args: String, vars: Map<String, Float>, texts: Map<String, String>): String? {
        val parts = splitArgs(args)
        if (parts.size < 3) return null
        val matched = ConditionEvaluator(parts[0], vars, texts).evaluate()
        return unquote(if (matched) parts[1].trim() else parts[2].trim())
    }

    /**
     * 按逗号切参数。必须同时跟踪**引号**和**括号/花括号**：
     * `if(a, if(b, "x", "y"), "z")` 里第二个逗号在括号内部，不能当分隔符。
     */
    private fun splitArgs(args: String): List<String> {
        val result = ArrayList<String>()
        val builder = StringBuilder()
        var quote: Char? = null
        var depth = 0
        for (ch in args) {
            when {
                quote != null -> {
                    builder.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    builder.append(ch)
                }
                ch == '(' || ch == '{' -> {
                    depth++
                    builder.append(ch)
                }
                ch == ')' || ch == '}' -> {
                    if (depth > 0) depth--
                    builder.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    result.add(builder.toString())
                    builder.setLength(0)
                }
                else -> builder.append(ch)
            }
        }
        result.add(builder.toString())
        return result
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length >= 2 &&
            ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'")))
        ) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    /** 结果里是否还残留占位符（用于编辑器提示“变量名写错了”）。 */
    fun unresolvedTemplateNames(template: String, vars: Map<String, Float>): List<String> {
        val missing = ArrayList<String>()
        PATTERN.findAll(template).forEach { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty() && !vars.containsKey(name)) missing += name
        }
        return missing
    }

    private fun formatValue(value: Float, format: String): String = when {
        format.isEmpty() -> trimNumber(value)
        format.contains('.') -> {
            val decimals = format.substringAfter('.').length.coerceAtMost(3)
            String.format(java.util.Locale.US, "%.${decimals}f", value)
        }
        else -> {
            val width = format.length
            val intValue = value.toInt()
            intValue.toString().padStart(width, '0')
        }
    }

    private const val DF = "df"
    private const val IF = "if"

    /** 变量存在但当前没有数据时的占位（未授权、设备无该传感器等）。 */
    private const val MISSING = "--"

    /** 最多展开几遍，防止自引用模板死循环。 */
    private const val MAX_PASSES = 6

    /** 转义括号的临时哨兵（私有区字符，正常文本不会出现）。 */
    private const val OPEN_LITERAL = "\u0001"
    private const val CLOSE_LITERAL = "\u0002"

    private fun trimNumber(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)
}
