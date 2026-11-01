package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 渐变描述解析测试。
 *
 * 解析错一个字符，界面上就是"一片纯色"，看不出是渐变没生效还是颜色填错，
 * 所以把语法边界在这里钉死。
 */
class GradientSpecTest {

    @Test
    fun `解析线性渐变`() {
        val spec = GradientSpec.parse("linear:90:0#FF3366FF;1#FF0B0D10")
        assertNotNull(spec)
        assertEquals(GradientSpec.Type.LINEAR, spec!!.type)
        assertEquals(90f, spec.angleDeg, 0.01f)
        assertEquals(2, spec.stops.size)
        assertEquals(0f, spec.stops[0].position, 0.001f)
        assertEquals(1f, spec.stops[1].position, 0.001f)
        assertEquals(0x33 / 255f, spec.stops[0].color.r, 0.01f)
    }

    @Test
    fun `解析径向渐变`() {
        val spec = GradientSpec.parse("radial:0:0#FFFFFFFF;1#00000000")
        assertEquals(GradientSpec.Type.RADIAL, spec!!.type)
        assertEquals(1f, spec.stops[0].color.a, 0.01f)
        assertEquals(0f, spec.stops[1].color.a, 0.01f)
    }

    @Test
    fun `色标按位置排序`() {
        val spec = GradientSpec.parse("linear:0:1#FF0000;0#00FF00")
        assertEquals(2, spec!!.stops.size)
        assertEquals(0f, spec.stops[0].position, 0.001f)
        assertEquals(1f, spec.stops[1].position, 0.001f)
    }

    @Test
    fun `位置被夹在 0 到 1`() {
        val spec = GradientSpec.parse("linear:0:-5#FF0000;9#00FF00")
        assertEquals(0f, spec!!.stops[0].position, 0.001f)
        assertEquals(1f, spec.stops[1].position, 0.001f)
    }

    @Test
    fun `六位颜色按不透明处理`() {
        val spec = GradientSpec.parse("linear:0:0#3366FF;1#0B0D10")
        assertEquals(1f, spec!!.stops[0].color.a, 0.01f)
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(GradientSpec.parse(""))
        assertNull(GradientSpec.parse("   "))
        assertNull(GradientSpec.parse("linear"))          // 缺角度与色标
        assertNull(GradientSpec.parse("linear:90"))       // 缺色标
        assertNull(GradientSpec.parse("diagonal:0:0#FF0000;1#00FF00")) // 未知类型
        assertNull(GradientSpec.parse("linear:0:0#FF0000"))            // 只有 1 个色标
        assertNull(GradientSpec.parse("linear:0:0nocolor;1#00FF00"))   // 颜色非法
    }

    @Test
    fun `编码后可再解析回来`() {
        val spec = GradientSpec.parse("linear:45:0#FF3366FF;1#FF0B0D10")!!
        val encoded = GradientSpec.encode(spec.type, spec.angleDeg, spec.stops)
        val again = GradientSpec.parse(encoded)!!
        assertEquals(spec.type, again.type)
        assertEquals(spec.angleDeg, again.angleDeg, 0.01f)
        assertEquals(spec.stops.size, again.stops.size)
        assertEquals(spec.stops[1].color.b, again.stops[1].color.b, 0.01f)
    }
}
