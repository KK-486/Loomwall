package com.loomwall.wallpaper.engine

/**
 * 属性补间：把"从 from 到 to"在 [durationMs] 内按缓动插值。
 *
 * 为什么单独放一个文件：它是纯计算，不碰 GL、不碰线程，能被单元测试直接覆盖。
 * 动效一旦算错（比如从 0 开始而不是从当前值开始），用户看到的就是"闪一下"，
 * 靠肉眼看真机很难判断，必须有断言。
 */
data class PropTween(
    val targetId: String,
    val property: String,
    val from: Float,
    val to: Float,
    val startMs: Long,
    val durationMs: Int,
    val easing: String = "easeOut"
) {
    /** 归一化进度 0..1。 */
    fun progress(nowMs: Long): Float {
        if (durationMs <= 0) return 1f
        val raw = (nowMs - startMs).toFloat() / durationMs.toFloat()
        return raw.coerceIn(0f, 1f)
    }

    fun valueAt(nowMs: Long): Float {
        val p = Easing.apply(easing, progress(nowMs))
        return from + (to - from) * p
    }

    fun finished(nowMs: Long): Boolean = progress(nowMs) >= 1f
}

/** 缓动函数集合。名字与编辑器下拉一致，未知名字退回 linear。 */
object Easing {
    val NAMES = listOf("linear", "easeIn", "easeOut", "easeInOut", "backOut", "bounceOut")

    fun apply(name: String, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (name) {
            "easeIn" -> x * x
            "easeOut" -> 1f - (1f - x) * (1f - x)
            "easeInOut" -> if (x < 0.5f) 2f * x * x else 1f - 2f * (1f - x) * (1f - x)
            // 略微冲过头再回弹：适合"弹出"手感，但要保证 t=1 时精确落到目标
            "backOut" -> {
                val c = 1.70158f
                val p = x - 1f
                1f + (c + 1f) * p * p * p + c * p * p
            }
            "bounceOut" -> bounce(x)
            else -> x
        }
    }

    private fun bounce(x: Float): Float {
        val n = 7.5625f
        val d = 2.75f
        return when {
            x < 1f / d -> n * x * x
            x < 2f / d -> {
                val v = x - 1.5f / d
                n * v * v + 0.75f
            }
            x < 2.5f / d -> {
                val v = x - 2.25f / d
                n * v * v + 0.9375f
            }
            else -> {
                val v = x - 2.625f / d
                n * v * v + 0.984375f
            }
        }
    }
}
