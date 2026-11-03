package com.loomwall.wallpaper.scene

/**
 * 表达式绑定：把 `audio(3)` 这类"函数写法"翻译成引擎认识的变量，再求值。
 *
 * 为什么要这一层：
 * - 绑定模型本身只认变量名（`sys.audio.b3`），而创作者更愿意写 `audio(3)`；
 * - 引擎的表达式求值器（[ExprParser]）不认识 `audio` 这种函数，硬加进去要把
 *   "音频数据从哪来"塞进求值上下文，反而更绕；
 * - 所以做一次**纯文本展开**：`audio(3)` → `sys.audio.b3`，`beat()` → `sys.audio.beat`。
 *   展开后的表达式用现有求值器跑，音频值照常从变量表取。
 *
 * 展开规则很窄（只认下面几张表），不会被误伤：变量名里的 `audio` 不会被替换，
 * 因为要求带括号。
 */
object BindingExpr {

    /** 段数，与 AudioLevelSource.BAND_COUNT 保持一致。 */
    private const val BAND_COUNT = 8

    private val BAND_RE = Regex("""\baudio\s*\(\s*([1-8])\s*\)""", RegexOption.IGNORE_CASE)

    private val NO_ARG_FUNCS = mapOf(
        "bass" to "sys.audio.bass",
        "mid" to "sys.audio.mid",
        "treble" to "sys.audio.treble",
        "level" to "sys.audio.level",
        "beat" to "sys.audio.beat"
    )

    /** 把函数写法展开成变量名；其它内容原样保留。 */
    fun expand(source: String): String {
        var text = BAND_RE.replace(source) { match ->
            "sys.audio.b" + match.groupValues[1]
        }
        for ((fn, variable) in NO_ARG_FUNCS) {
            text = text.replace(Regex("""\b$fn\s*\(\s*\)""", RegexOption.IGNORE_CASE), variable)
        }
        return text
    }

    /**
     * 加载期校验：表达式必须能解析。解析失败抛出带 JSON 路径的异常，
     * 而不是等到渲染时静默算成 0（那样用户只会看到"效果没了"）。
     */
    fun validate(expr: String, path: String) {
        val expanded = expand(expr)
        val unknown = unknownAudioFuncs(expr)
        if (unknown.isNotEmpty()) {
            throw SceneFormatException(
                path,
                "音频函数参数不对：${unknown.joinToString(" / ")}（audio(n) 的 n 只能是 1..$BAND_COUNT）"
            )
        }
        ExprParser.parse(expanded, path)
    }

    /** 找出 `audio(...)` 里不合法的调用（比如 audio(0) / audio(9)）。 */
    private fun unknownAudioFuncs(expr: String): List<String> {
        val bad = ArrayList<String>()
        val anyAudio = Regex("""\baudio\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
        for (match in anyAudio.findAll(expr)) {
            val arg = match.groupValues[1].trim()
            val n = arg.toIntOrNull()
            if (n == null || n !in 1..BAND_COUNT) bad.add("audio($arg)")
        }
        return bad
    }
}
