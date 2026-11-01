package com.loomwall.wallpaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补间与缓动测试。
 *
 * 为什么必须测：动效算错的典型表现是"闪一下"或"跳到终点"，肉眼在真机上很难判断是
 * 缓动问题还是渲染问题。把数学部分锁死，剩下的才是接线问题。
 */
class TweenTest {

    @Test
    fun `线性补间在中点取中值`() {
        val tween = PropTween("a", "x", 0f, 100f, startMs = 1000, durationMs = 400, easing = "linear")
        assertEquals(0f, tween.valueAt(1000), 0.001f)
        assertEquals(50f, tween.valueAt(1200), 0.01f)
        assertEquals(100f, tween.valueAt(1400), 0.001f)
        // 超时后停在目标值，不能继续跑
        assertEquals(100f, tween.valueAt(9999), 0.001f)
    }

    @Test
    fun `任何缓动在终点都精确落在目标`() {
        Easing.NAMES.forEach { name ->
            val tween = PropTween("a", "y", 10f, 42f, startMs = 0, durationMs = 300, easing = name)
            assertEquals("$name 终点", 42f, tween.valueAt(300), 0.001f)
            assertEquals("$name 起点", 10f, tween.valueAt(0), 0.001f)
        }
    }

    @Test
    fun `easeOut 前段比 linear 快`() {
        val linear = Easing.apply("linear", 0.25f)
        val easeOut = Easing.apply("easeOut", 0.25f)
        assertTrue("easeOut 应先到达", easeOut > linear)
    }

    @Test
    fun `backOut 允许冲过头但不超过目标的 2 倍`() {
        val probe = (0..20).map { Easing.apply("backOut", it / 20f) }
        assertTrue("应出现超过 1 的中间值", probe.any { it > 1f })
        assertTrue("不应离谱", probe.max() < 2f)
    }

    @Test
    fun `时长 0 视为立即完成`() {
        val tween = PropTween("a", "opacity", 0f, 1f, startMs = 5, durationMs = 0)
        assertTrue(tween.finished(5))
        assertEquals(1f, tween.valueAt(5), 0.001f)
    }

    @Test
    fun `负进度不会把值带反`() {
        val tween = PropTween("a", "x", 5f, 15f, startMs = 1000, durationMs = 100)
        assertEquals(5f, tween.valueAt(900), 0.001f)
    }
}
