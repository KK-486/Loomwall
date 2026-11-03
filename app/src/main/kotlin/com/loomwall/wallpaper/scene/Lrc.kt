package com.loomwall.wallpaper.scene

/**
 * 一行歌词：时间戳（毫秒）+ 文本。
 *
 * 为什么时间用毫秒而不是 "mm:ss.xx" 字符串：播放进度本来就是毫秒，
 * 每次取当前行都要比较；解析一次存整数，取行时只做二分查找，不在渲染线程做字符串处理。
 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * LRC 解析：把 `[mm:ss.xx]歌词` 转成按时间排序的行。
 *
 * 支持的写法（覆盖网易云/QQ音乐/本地播放器导出的差异）：
 * - `[00:12.34]`、`[00:12.340]`、`[00:12]`
 * - 一行多个时间戳：`[00:10.00][01:20.00]副歌` → 展开成两行同文本
 * - `[offset:+300]` / `[offset:-300]`：全局偏移，**正数表示歌词提前**
 * - `[ti:]`、`[ar:]`、`[al:]` 等元信息标签：忽略
 * - 空文本行保留（表示"这段时间没有歌词"，用于清空显示）
 *
 * 不做的事：翻译（`[00:10.00]原文 / 译文` 那种双行）、逐字歌词（klyric）。
 * 它们各有格式，塞进来会让"简单可预测"变成一堆特例。
 */
object Lrc {

    private val TAG_RE = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * 制作信息行：网易云的 LRC 常把"作词 : X / 作曲 : Y"塞进歌词流，
     * 时间戳通常在开头几秒。不过滤的话，一放歌就显示"作曲 : ..."，像 bug。
     */
    private val CREDIT_RE = Regex(
        """^\s*(作词|作曲|编曲|制作人|监制|混音|母带|录音|吉他|贝斯|鼓|和声|弦乐|发行|出品|策划|统筹|OP|SP|词|曲)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    /** 英文歌词里的同类行：Lyrics by / Composed by / Produced by … */
    private val CREDIT_EN_RE = Regex(
        """^\s*(lyrics?|music|composed?|arranged?|produced?|written)\s+by\b""",
        RegexOption.IGNORE_CASE
    )

    /** 是不是"制作信息"而不是歌词。 */
    fun isCredit(text: String): Boolean =
        text.isNotBlank() && (CREDIT_RE.containsMatchIn(text) || CREDIT_EN_RE.containsMatchIn(text))
    private val OFFSET_RE = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    /** 解析整份 LRC 文本；无法识别的行直接跳过，不抛异常。 */
    fun parse(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()

        var offsetMs = 0L
        OFFSET_RE.find(raw)?.let { offsetMs = it.groupValues[1].toLongOrNull() ?: 0L }

        val out = ArrayList<LyricLine>()
        for (line in raw.split('\n')) {
            val text = line.trim('\r', ' ', '\t')
            if (text.isEmpty()) continue

            // 收集这一行里所有时间戳：[00:10.00][01:20.00]副歌
            val stamps = TAG_RE.findAll(text).toList()
            if (stamps.isEmpty()) continue
            val body = text.substring(stamps.last().range.last + 1).trim()

            for (stamp in stamps) {
                val minute = stamp.groupValues[1].toLongOrNull() ?: continue
                val second = stamp.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = stamp.groupValues[3]
                // 毫秒位数按实际长度换算：.3 → 300ms，.34 → 340ms，.340 → 340ms
                val frac = if (fracRaw.isEmpty()) 0L else {
                    val v = fracRaw.toLongOrNull() ?: 0L
                    when (fracRaw.length) {
                        1 -> v * 100
                        2 -> v * 10
                        else -> v
                    }
                }
                val ms = (minute * 60 + second) * 1000 + frac - offsetMs
                // 制作信息不当作歌词；但保留空行（空行表示"这段没有歌词"，语义不同）
                if (isCredit(body)) continue
                out.add(LyricLine(ms.coerceAtLeast(0L), body))
            }
        }

        out.sortBy { it.timeMs }
        return out
    }

    /**
     * 解析翻译歌词（网易云的 tlyric），返回"时间戳 → 译文"。
     *
     * 为什么按时间戳对齐而不是按行号：译文行数与原文不一定相等（有的一行原文对应多行译文，
     * 有的省略间奏），只有时间戳是可靠的对应关系。取整到毫秒后直接做映射，
     * 查的时候 O(1)，不需要每帧再遍历。
     */
    fun parseTranslations(raw: String): Map<Long, String> {
        if (raw.isBlank()) return emptyMap()
        val map = HashMap<Long, String>()
        for (line in parse(raw)) {
            if (line.text.isNotBlank()) map[line.timeMs] = line.text
        }
        return map
    }

    /**
     * 取"此刻应该显示的那一行"的下标；没有歌词或还没到第一行时返回 -1。
     *
     * 用二分而不是从头扫：长歌词有几百行，而这里每 2 秒会被调用一次，
     * 线性扫描在省电上是没有必要的浪费。
     */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines.first().timeMs) return -1
        var low = 0
        var high = lines.size - 1
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }
}
