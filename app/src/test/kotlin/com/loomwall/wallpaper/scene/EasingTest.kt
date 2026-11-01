package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓动曲线测试：端点、单调性、解析与写回。
 *
 * 单调性不是形式主义：如果某条缓动在中间回落，动画会“抖动”，
 * 而这类问题在编辑器里肉眼很难立刻发现，必须用测试钉死。
 */
class EasingTest {

    @Test
    fun `线性缓动原样返回`() {
        assertEquals(0f, Easing.Linear.apply(0f), 1e-6f)
        assertEquals(0.5f, Easing.Linear.apply(0.5f), 1e-6f)
        assertEquals(1f, Easing.Linear.apply(1f), 1e-6f)
    }

    @Test
    fun `所有命名缓动端点固定且单调不减`() {
        for (name in Easing.names) {
            val easing = Easing.parse(name)
            assertEquals("$name 起点必须为 0", 0f, easing.apply(0f), 1e-4f)
            assertEquals("$name 终点必须为 1", 1f, easing.apply(1f), 1e-4f)

            var previous = -1f
            var t = 0f
            while (t <= 1f) {
                val value = easing.apply(t)
                assertTrue("$name 在 t=$t 处出现回落（$previous → $value）", value >= previous - 1e-4f)
                previous = value
                t += 0.02f
            }
        }
    }

    @Test
    fun `cubic-bezier 字符串可以解析`() {
        val easing = Easing.parse("cubic-bezier(0.3, 0, 0.4, 1)")
        assertTrue(easing is Easing.CubicBezier)
        assertEquals(0f, easing.apply(0f), 1e-4f)
        assertEquals(1f, easing.apply(1f), 1e-4f)
    }

    @Test
    fun `未知缓动要报错并列出可用名称`() {
        val error = assertThrows(IllegalArgumentException::class.java) { Easing.parse("wobble") }
        assertTrue(error.message!!.contains("wobble"))
        assertTrue(error.message!!.contains("linear"))
    }

    @Test
    fun `贝塞尔参数越界要报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            Easing.parse("cubic-bezier(1.5, 0, 0.4, 1)")
        }
    }

    @Test
    fun `写回时使用规范名`() {
        assertEquals("linear", Easing.toSpec(Easing.Linear))
        assertEquals("cubicOut", Easing.toSpec(Easing.CUBIC_OUT))
        val custom = Easing.CubicBezier(0.3f, 0f, 0.4f, 1f)
        assertTrue(Easing.toSpec(custom).startsWith("cubic-bezier("))
    }

    @Test
    fun `写回的字符串可以被重新解析且曲线一致`() {
        for (name in Easing.names) {
            val easing = Easing.parse(name)
            val reparsed = Easing.parse(Easing.toSpec(easing))
            for (t in listOf(0.1f, 0.37f, 0.5f, 0.83f)) {
                assertEquals("$name 在 t=$t 处曲线不一致", easing.apply(t), reparsed.apply(t), 1e-3f)
            }
        }
    }
}
