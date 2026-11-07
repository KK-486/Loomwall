package com.loomwall.wallpaper.scene

/**
 * 按宽度折行（纯逻辑，不依赖 Android）。
 *
 * 为什么不直接用 `StaticLayout`：文本最终是"画进固定尺寸 Bitmap 再贴到 GL"，
 * 这里真正需要的只是"给我几行字符串"；而且折行规则必须在 JVM 单测里能跑
 *（用假的测量函数），否则"编辑器与真机折行不一致"这类问题只能靠肉眼发现。
 *
 * 与引擎的约定：
 * - `maxLines <= 0` 表示**保持旧行为**（只按 `\n` 手动分行），老预设完全不受影响；
 * - `maxLines >= 1` 表示按宽度自动折行，超出部分截断并以 [ELLIPSIS] 结尾。
 */
object TextWrap {

    const val ELLIPSIS = "…"

    /** 行数上限：再多也不是"文字层"，而是一整屏正文了。 */
    const val MAX_LINES = 20

    /**
     * @param maxWidthPx 折行宽度（像素）。<= 0 时退回手动分行。
     * @param maxLines 最多几行；<= 0 表示不限行、不折行（旧行为）。
     * @param measure 测量一串文本的宽度；引擎传 `paint::measureText`，测试传假实现。
     */
    fun wrap(
        text: String,
        maxWidthPx: Float,
        maxLines: Int,
        measure: (String) -> Float
    ): List<String> {
        val limit = maxLines.coerceIn(0, MAX_LINES)
        if (limit <= 0 || maxWidthPx <= 0f) return text.split('\n')

        val out = ArrayList<String>(limit)
        for (paragraph in text.split('\n')) {
            if (out.size >= limit) break
            fillParagraph(paragraph, maxWidthPx, limit, measure, out)
        }
        if (out.isEmpty()) out.add("")
        return out
    }

    private fun fillParagraph(
        paragraph: String,
        maxWidthPx: Float,
        limit: Int,
        measure: (String) -> Float,
        out: MutableList<String>
    ) {
        var rest = paragraph.trim()
        if (rest.isEmpty()) {
            out.add("")
            return
        }
        while (true) {
            val budget = limit - out.size
            if (budget <= 0) return
            if (measure(rest) <= maxWidthPx) {
                out.add(rest)
                return
            }
            // 只剩最后一行：截断加省略号，不再继续折
            if (budget == 1) {
                out.add(ellipsize(rest, maxWidthPx, measure))
                return
            }
            val cut = breakIndex(rest, maxWidthPx, measure)
            if (cut <= 0) {
                out.add(ellipsize(rest, maxWidthPx, measure))
                return
            }
            out.add(rest.substring(0, cut).trimEnd())
            rest = rest.substring(cut).trimStart()
            if (rest.isEmpty()) return
        }
    }

    /** 能放下的最长前缀；若断点落在拉丁词中间，退回到最近的空格。 */
    private fun breakIndex(s: String, maxWidthPx: Float, measure: (String) -> Float): Int {
        var fit = 0
        while (fit < s.length) {
            val next = fit + Character.charCount(s.codePointAt(fit))
            if (measure(s.substring(0, next)) > maxWidthPx) break
            fit = next
        }
        if (fit <= 0) return 0
        // CJK 没有空格，这个循环不会命中；英文则尽量不在词中间断开
        for (i in fit - 1 downTo fit / 2) {
            if (s[i] == ' ') return i + 1
        }
        return fit
    }

    private fun ellipsize(s: String, maxWidthPx: Float, measure: (String) -> Float): String {
        var end = s.length
        while (end > 0) {
            val candidate = s.substring(0, end).trimEnd() + ELLIPSIS
            if (measure(candidate) <= maxWidthPx) return candidate
            // 按码点回退，避免把 emoji / 代理对截成半个字符
            end -= Character.charCount(s.codePointBefore(end))
        }
        return ELLIPSIS
    }
}
