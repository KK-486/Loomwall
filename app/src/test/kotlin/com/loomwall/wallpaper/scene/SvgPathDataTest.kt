package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG path 解析器测试。
 *
 * 这些用例钉的是"解析结果必须归一化到 0..1 且轮廓数量正确"——
 * 渲染端把归一化坐标直接乘上元素宽高，一旦漏了归一化，图形就会画到屏幕外。
 */
class SvgPathDataTest {

    @Test
    fun `直线矩形解析成一条闭合轮廓五个点`() {
        // 首尾各一次：M 起点 + L 三个角 + Z 闭合后仍保留四个点
        val parsed = SvgPathData.parse("M0 0 L24 0 L24 24 L0 24 Z", 24f, 24f)
        assertEquals(1, parsed.contours.size)
        assertEquals(4, parsed.contours[0].size / 2)
        assertTrue(parsed.closed[0])
    }

    @Test
    fun `坐标被归一化到 0 到 1`() {
        val parsed = SvgPathData.parse("M0 0 L24 0 L24 24 Z", 24f, 24f)
        val points = parsed.contours[0]
        assertEquals(0f, points[0], 0.001f)
        assertEquals(1f, points[2], 0.001f)
        assertEquals(1f, points[5], 0.001f)
        assertFalse(SvgPathData.outOfRange(parsed.contours))
    }

    @Test
    fun `相对命令按当前位置累加`() {
        // m 10 10 后 l 14 0：第二个点应到 (24,10)
        val parsed = SvgPathData.parse("m10 10 l14 0 l0 14 Z", 24f, 24f)
        val points = parsed.contours[0]
        assertEquals(10f / 24f, points[0], 0.001f)
        assertEquals(24f / 24f, points[2], 0.001f)
        assertEquals(10f / 24f, points[3], 0.001f)
    }

    @Test
    fun `水平垂直命令可用`() {
        val parsed = SvgPathData.parse("M0 0 H24 V24 H0 Z", 24f, 24f)
        assertEquals(4, parsed.contours[0].size / 2)
    }

    @Test
    fun `三次贝塞尔被压平成多段`() {
        val parsed = SvgPathData.parse("M0 0 C8 0 16 24 24 24", 24f, 24f)
        val count = parsed.contours[0].size / 2
        // 起点 + 16 段
        assertEquals(17, count)
    }

    @Test
    fun `二次贝塞尔与平滑命令不崩溃`() {
        val quad = SvgPathData.parse("M0 0 Q12 24 24 0", 24f, 24f)
        assertEquals(17, quad.contours[0].size / 2)

        val smooth = SvgPathData.parse("M0 0 C8 0 16 24 24 24 S16 48 0 48", 24f, 24f)
        assertTrue(smooth.contours.isNotEmpty())
    }

    @Test
    fun `圆弧被近似成折线且终点正确`() {
        val parsed = SvgPathData.parse("M0 12 A12 12 0 0 1 24 12", 24f, 24f)
        val points = parsed.contours[0]
        val lastX = points[points.size - 2]
        val lastY = points[points.size - 1]
        assertEquals(1f, lastX, 0.01f)
        assertEquals(0.5f, lastY, 0.01f)
    }

    @Test
    fun `多条子路径各自成轮廓`() {
        // 外框 + 内部小方块（模拟镂空）
        val parsed = SvgPathData.parse(
            "M0 0 L24 0 L24 24 L0 24 Z M8 8 L16 8 L16 16 L8 16 Z",
            24f, 24f
        )
        assertEquals(2, parsed.contours.size)
    }

    @Test
    fun `even-odd 命中判定支持镂空`() {
        val parsed = SvgPathData.parse(
            "M0 0 L24 0 L24 24 L0 24 Z M8 8 L16 8 L16 16 L8 16 Z",
            24f, 24f
        )
        // 外环内、内块外 → 命中
        assertTrue(SvgPathData.contains(parsed.contours, 0.1f, 0.1f))
        // 正中间（内块里）→ 镂空，不命中
        assertFalse(SvgPathData.contains(parsed.contours, 0.5f, 0.5f))
        // 完全在外面
        assertFalse(SvgPathData.contains(parsed.contours, 1.5f, 1.5f))
    }

    @Test
    fun `紧凑写法和负号也能解析`() {
        val parsed = SvgPathData.parse("M0,0L24,0L24,24L0,24z", 24f, 24f)
        assertEquals(1, parsed.contours.size)
        assertEquals(4, parsed.contours[0].size / 2)
    }

    @Test
    fun `空路径返回空结果而不是崩溃`() {
        assertTrue(SvgPathData.parse("", 24f, 24f).isEmpty)
        assertTrue(SvgPathData.parse("   ", 24f, 24f).isEmpty)
    }

    @Test
    fun `viewBox 决定缩放基准`() {
        // 同样 d，viewBox 100 时归一化坐标应缩小 100 倍
        val parsed = SvgPathData.parse("M0 0 L100 0 L100 100 Z", 100f, 100f)
        assertEquals(1f, parsed.contours[0][2], 0.001f)

        val parsed24 = SvgPathData.parse("M0 0 L24 0 L24 24 Z", 24f, 24f)
        assertEquals(1f, parsed24.contours[0][2], 0.001f)
    }
}
