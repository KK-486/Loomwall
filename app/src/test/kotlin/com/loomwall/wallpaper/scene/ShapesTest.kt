package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 形状顶点生成的测试。
 *
 * 这些断言钉的是"形状必须画在自己的包围盒里、且关于中心星形可见"这条约定——
 * 渲染端用三角形扇从中心展开，一旦某个点跑到包围盒外或者顺序错乱，
 * 扇面就会出现撕裂或者填充错位，而这类问题在截图里很难判断是谁的锅。
 */
class ShapesTest {

    @Test
    fun `矩形是四个角`() {
        val points = Shapes.points(ShapeKind.RECT, 4, 100f, 50f)
        assertEquals(8, points.size)
        assertEquals(0f, points[0], 0.01f)
        assertEquals(0f, points[1], 0.01f)
        assertEquals(100f, points[2], 0.01f)   // 右上角 x
        assertEquals(100f, points[4], 0.01f)   // 右下角 x
        assertEquals(50f, points[5], 0.01f)    // 右下角 y
        assertEquals(0f, points[6], 0.01f)     // 左下角 x
        assertEquals(50f, points[7], 0.01f)    // 左下角 y
    }

    @Test
    fun `三角形三个顶点且顶点在中间`() {
        val points = Shapes.points(ShapeKind.TRIANGLE, 5, 100f, 50f)
        assertEquals(6, points.size)
        assertEquals(50f, points[0], 0.01f)
        assertEquals(0f, points[1], 0.01f)
    }

    @Test
    fun `梯形上边比下边短`() {
        val points = Shapes.points(ShapeKind.TRAPEZOID, 5, 100f, 50f)
        assertEquals(8, points.size)
        val topLeftX = points[0]
        val topRightX = points[2]
        assertTrue("上边要缩进", topLeftX > 0f)
        assertTrue("上边要比下边短", (topRightX - topLeftX) < 100f)
    }

    @Test
    fun `椭圆所有点都落在椭圆上`() {
        val points = Shapes.points(ShapeKind.ELLIPSE, 5, 200f, 100f)
        assertEquals(48 * 2, points.size)
        val cx = 100f
        val cy = 50f
        val rx = 100f
        val ry = 50f
        for (i in 0 until points.size / 2) {
            val dx = (points[i * 2] - cx) / rx
            val dy = (points[i * 2 + 1] - cy) / ry
            // 归一化后应落在单位圆上
            assertEquals(1.0, hypot(dx.toDouble(), dy.toDouble()), 0.01)
        }
    }

    @Test
    fun `正多边形顶点数正确且等距分布`() {
        val points = Shapes.points(ShapeKind.POLYGON, 6, 100f, 100f)
        assertEquals(12, points.size)
        val radii = (0 until 6).map { i ->
            hypot((points[i * 2] - 50f).toDouble(), (points[i * 2 + 1] - 50f).toDouble())
        }
        for (r in radii) assertEquals(50.0, r, 0.5)
    }

    @Test
    fun `星形内外半径交替`() {
        val points = Shapes.points(ShapeKind.STAR, 5, 100f, 100f)
        assertEquals(20, points.size) // 5 角 → 10 个点
        val radii = (0 until 10).map { i ->
            hypot((points[i * 2] - 50f).toDouble(), (points[i * 2 + 1] - 50f).toDouble())
        }
        // 偶数下标是外顶点，奇数下标是内顶点
        assertTrue("外顶点应比内顶点远", radii[0] > radii[1])
        assertEquals(50.0, radii[0], 0.5)
        assertEquals(22.5, radii[1], 0.5)
    }

    @Test
    fun `顶点数被夹在 3 到 32`() {
        assertEquals(3 * 2, Shapes.points(ShapeKind.POLYGON, 1, 10f, 10f).size)
        assertEquals(32 * 2, Shapes.points(ShapeKind.POLYGON, 999, 10f, 10f).size)
    }

    @Test
    fun `圆角矩形顶点都在包围盒内`() {
        val points = Shapes.points(ShapeKind.RECT, 4, 100f, 60f, cornerRadius = 20f)
        assertTrue("应产生多段圆弧顶点", points.size > 20)
        for (i in points.indices step 2) {
            val x = points[i]
            val y = points[i + 1]
            assertTrue("x 越界：$x", x >= -0.01f && x <= 100.01f)
            assertTrue("y 越界：$y", y >= -0.01f && y <= 60.01f)
        }
    }

    @Test
    fun `圆角顶点贴着四角圆弧`() {
        val w = 100f
        val h = 100f
        val r = 20f
        val points = Shapes.points(ShapeKind.RECT, 4, w, h, cornerRadius = r)

        // 每个角到对应圆心的距离应恒等于 r
        val centers = listOf(
            Pair(r, r),
            Pair(w - r, r),
            Pair(w - r, h - r),
            Pair(r, h - r)
        )
        for (i in points.indices step 2) {
            val x = points[i]
            val y = points[i + 1]
            val nearest = centers.minByOrNull { (cx, cy) ->
                val dx = x - cx
                val dy = y - cy
                dx * dx + dy * dy
            }!!
            val dx = (x - nearest.first).toDouble()
            val dy = (y - nearest.second).toDouble()
            assertEquals("顶点应落在圆弧上", r.toDouble(), kotlin.math.hypot(dx, dy), 0.6)
        }
    }

    @Test
    fun `圆角半径被夹到半宽`() {
        // 半径给到 999 也不能超过 min(w,h)/2，否则弧会翻转
        val points = Shapes.points(ShapeKind.RECT, 4, 100f, 40f, cornerRadius = 999f)
        for (i in points.indices step 2) {
            val x = points[i]
            val y = points[i + 1]
            assertTrue(x >= -0.01f && x <= 100.01f)
            assertTrue(y >= -0.01f && y <= 40.01f)
        }
    }

    @Test
    fun `圆角为 0 时仍是四角`() {
        val points = Shapes.points(ShapeKind.RECT, 4, 100f, 50f, cornerRadius = 0f)
        assertEquals(8, points.size)
    }

    @Test
    fun `多边形圆角会增加顶点且不进包围盒`() {
        val base = Shapes.points(ShapeKind.STAR, 5, 100f, 100f)
        val rounded = Shapes.roundPolygon(base, 6f)
        assertTrue("圆角后顶点应更多", rounded.size > base.size)
        for (i in rounded.indices step 2) {
            val x = rounded[i]
            val y = rounded[i + 1]
            assertTrue("x 越界：$x", x >= -0.5f && x <= 100.5f)
            assertTrue("y 越界：$y", y >= -0.5f && y <= 100.5f)
        }
    }

    @Test
    fun `圆角后原始尖角被削掉`() {
        val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val rounded = Shapes.roundPolygon(square, 20f)
        // 原始顶点 (0,0) 不应再出现在结果里
        var hasSharpCorner = false
        for (i in rounded.indices step 2) {
            if (kotlin.math.abs(rounded[i]) < 0.01f && kotlin.math.abs(rounded[i + 1]) < 0.01f) {
                hasSharpCorner = true
            }
        }
        assertFalse("尖角应被圆弧替代", hasSharpCorner)
    }

    @Test
    fun `圆角半径为 0 时原样返回`() {
        val base = Shapes.points(ShapeKind.POLYGON, 6, 80f, 80f)
        val same = Shapes.roundPolygon(base, 0f)
        assertEquals(base.size, same.size)
    }

    @Test
    fun `蒙版加圆角会走多边形圆角`() {
        val plain = Shapes.effectivePoints(ShapeKind.RECT, 4, 0f, "star", 5, 100f, 100f)
        val rounded = Shapes.effectivePoints(ShapeKind.RECT, 4, 20f, "star", 5, 100f, 100f)
        assertTrue("星形蒙版 + 圆角应产生更多顶点", rounded.size > plain.size)
    }
    @Test
    fun `蒙版优先于自身形状`() {
        // 矩形元素挂椭圆蒙版 → 顶点数量应按椭圆算（48 段）
        val points = Shapes.effectivePoints(
            kind = ShapeKind.RECT,
            sides = 4,
            cornerRadiusPercent = 0f,
            mask = "ellipse",
            maskSides = 5,
            width = 100f,
            height = 100f
        )
        assertEquals(48 * 2, points.size)
    }

    @Test
    fun `没有蒙版时圆角生效`() {
        val withRadius = Shapes.effectivePoints(
            ShapeKind.RECT, 4, 20f, "", 5, 100f, 100f
        )
        val without = Shapes.effectivePoints(
            ShapeKind.RECT, 4, 0f, "", 5, 100f, 100f
        )
        assertTrue(withRadius.size > without.size)
        assertEquals(8, without.size)
    }

    @Test
    fun `蒙版名非法时退回自身形状`() {
        val points = Shapes.effectivePoints(
            ShapeKind.RECT, 4, 0f, "not-a-shape", 5, 100f, 50f
        )
        assertEquals(8, points.size)
    }
    @Test
    fun `矩形命中判定`() {
        val points = Shapes.points(ShapeKind.RECT, 4, 100f, 50f)
        assertTrue(Shapes.contains(points, 50f, 25f))
        assertFalse(Shapes.contains(points, 120f, 25f))
    }

    @Test
    fun `圆形的角落不算命中`() {
        val points = Shapes.points(ShapeKind.ELLIPSE, 4, 100f, 100f)
        // 中心命中
        assertTrue(Shapes.contains(points, 50f, 50f))
        // 左上角在包围盒内但在圆外
        assertFalse(Shapes.contains(points, 3f, 3f))
    }

    @Test
    fun `三角形的上方两角不算命中`() {
        val points = Shapes.points(ShapeKind.TRIANGLE, 3, 100f, 100f)
        assertTrue(Shapes.contains(points, 50f, 80f))
        // 左上角在包围盒里，但三角形不覆盖那里
        assertFalse(Shapes.contains(points, 2f, 2f))
    }

    @Test
    fun `宽高为 0 时退化成单点不崩溃`() {
        val points = Shapes.points(ShapeKind.ELLIPSE, 5, 0f, 0f)
        assertEquals(2, points.size)
    }
}
