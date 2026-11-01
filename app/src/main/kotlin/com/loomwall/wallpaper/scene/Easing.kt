package com.loomwall.wallpaper.scene

/**
 * 缓动曲线。
 *
 * 设计取舍：
 * - 只实现**三次贝塞尔**一种数学形式，加上几个命名预设。KLWP 那种自由表达式系统明确不做，
 *   但 cubic-bezier 本身是确定、可验证、可序列化的，符合“Schema 必须可校验”的要求。
 * - 解析失败不抛异常到上层：调用方（解析器）负责包装成带 JSON 路径的错误。
 */
sealed interface Easing {

    /** 输入归一化时间 0..1，输出缓动后的 0..1。 */
    fun apply(t: Float): Float

    /** 线性：用于不需要缓动的段。 */
    data object Linear : Easing {
        override fun apply(t: Float): Float = t.coerceIn(0f, 1f)
    }

    /**
     * 三次贝塞尔：控制点 (x1,y1)、(x2,y2)，端点固定 (0,0) 与 (1,1)。
     * 用二分法反解 x → t，再算 y。20 次迭代足以达到亚像素精度，且不会被极端控制点卡死。
     */
    data class CubicBezier(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    ) : Easing {

        override fun apply(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f

            var low = 0f
            var high = 1f
            var guess = x
            repeat(BISECTION_STEPS) {
                guess = (low + high) / 2f
                val curveX = bezier(guess, x1, x2)
                if (curveX < x) low = guess else high = guess
            }
            return bezier(guess, y1, y2).coerceIn(0f, 1f)
        }

        private fun bezier(u: Float, p1: Float, p2: Float): Float {
            val v = 1f - u
            return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u
        }

        private companion object {
            const val BISECTION_STEPS = 20
        }
    }

    companion object {

        // 常用预设（与 CSS 的 ease 系列保持一致，方便用户凭直觉选择）
        val EASE = CubicBezier(0.25f, 0.1f, 0.25f, 1f)
        val EASE_IN = CubicBezier(0.42f, 0f, 1f, 1f)
        val EASE_OUT = CubicBezier(0f, 0f, 0.58f, 1f)
        val EASE_IN_OUT = CubicBezier(0.42f, 0f, 0.58f, 1f)
        val CUBIC_IN = CubicBezier(0.32f, 0f, 0.67f, 0f)
        val CUBIC_OUT = CubicBezier(0.33f, 1f, 0.68f, 1f)
        val CUBIC_IN_OUT = CubicBezier(0.65f, 0f, 0.35f, 1f)

        private val NAMED: Map<String, Easing> = mapOf(
            "linear" to Linear,
            "ease" to EASE,
            "easeIn" to EASE_IN,
            "easeOut" to EASE_OUT,
            "easeInOut" to EASE_IN_OUT,
            "cubicIn" to CUBIC_IN,
            "cubicOut" to CUBIC_OUT,
            "cubicInOut" to CUBIC_IN_OUT
        )

        val names: List<String> = NAMED.keys.toList()

        /** UI 上显示的中文名。JSON 里仍然写英文规范名，保证预设可移植。 */
        private val DISPLAY: Map<String, String> = mapOf(
            "linear" to "线性",
            "ease" to "标准",
            "easeIn" to "缓入",
            "easeOut" to "缓出",
            "easeInOut" to "缓入缓出",
            "cubicIn" to "三次缓入",
            "cubicOut" to "三次缓出",
            "cubicInOut" to "三次缓入缓出"
        )

        fun displayName(spec: String): String = DISPLAY[spec] ?: spec

        /**
         * 支持 `"cubicInOut"` 这类命名，也支持 `"cubic-bezier(0.3,0,0.4,1)"`。
         * 非法输入抛 IllegalArgumentException，由调用方包装成带路径的格式错误。
         */
        fun parse(spec: String): Easing {
            val trimmed = spec.trim()
            NAMED[trimmed]?.let { return it }

            val bezierPrefix = "cubic-bezier("
            if (trimmed.startsWith(bezierPrefix) && trimmed.endsWith(")")) {
                val inner = trimmed.substring(bezierPrefix.length, trimmed.length - 1)
                val parts = inner.split(',').map { it.trim() }
                if (parts.size != 4) {
                    throw IllegalArgumentException("cubic-bezier 需要 4 个参数，收到 ${parts.size} 个：\"$spec\"")
                }
                val values = parts.map { part ->
                    part.toFloatOrNull()
                        ?: throw IllegalArgumentException("cubic-bezier 参数必须是数字：\"$part\"")
                }
                if (values.any { it < 0f || it > 1f }) {
                    throw IllegalArgumentException("cubic-bezier 参数必须在 0..1 之间：\"$spec\"")
                }
                return CubicBezier(values[0], values[1], values[2], values[3])
            }

            throw IllegalArgumentException(
                "未知缓动 \"$spec\"（可用：${names.joinToString(" / ")}，或 cubic-bezier(a,b,c,d)）"
            )
        }

        /** 写回 JSON 时用的规范名；贝塞尔曲线一律写成 cubic-bezier(...) 形式。 */
        fun toSpec(easing: Easing): String = when (easing) {
            is Linear -> "linear"
            is CubicBezier -> {
                val named = NAMED.entries.firstOrNull { it.value == easing }?.key
                named ?: "cubic-bezier(${fmt(easing.x1)},${fmt(easing.y1)},${fmt(easing.x2)},${fmt(easing.y2)})"
            }
        }

        private fun fmt(value: Float): String {
            val rounded = kotlin.math.round(value * 1000f) / 1000f
            return if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
        }
    }
}
