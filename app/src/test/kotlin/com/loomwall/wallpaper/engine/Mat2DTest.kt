package com.loomwall.wallpaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Mat2D] 的语义测试。
 *
 * 这些数字不是随便写的：它们对应渲染器里「容器缩放 → 子元素跟着放大」的真实场景，
 * 容器嵌套一旦算错，子元素就会飘到别处，用截图很难定位，所以在这里钉死。
 */
class Mat2DTest {

    @Test
    fun `单位矩阵不改变坐标`() {
        val m = Mat2D.IDENTITY
        assertEquals(3f, m.mapX(3f, 7f), 0.001f)
        assertEquals(7f, m.mapY(3f, 7f), 0.001f)
    }

    @Test
    fun `平移矩阵按预期偏移`() {
        val m = Mat2D.translate(10f, 20f)
        assertEquals(13f, m.mapX(3f, 7f), 0.001f)
        assertEquals(27f, m.mapY(3f, 7f), 0.001f)
    }

    @Test
    fun `缩放以中心为基准`() {
        // 40x20 的框缩放到 2 倍：中心 (40,20) 不动，左上角变成 (0,0)→(40,20)... 实际应以放大后中心 (40,20) 为基准
        val m = Mat2D.scaleRotate(scale = 2f, rotationDeg = 0f, cx = 40f, cy = 20f)
        assertEquals(0f, m.mapX(0f, 0f), 0.001f)
        assertEquals(0f, m.mapY(0f, 0f), 0.001f)
        assertEquals(80f, m.mapX(40f, 20f), 0.001f)
        assertEquals(40f, m.mapY(40f, 20f), 0.001f)
    }

    @Test
    fun `九十度旋转把宽高换位`() {
        val m = Mat2D.scaleRotate(scale = 1f, rotationDeg = 90f, cx = 5f, cy = 5f)
        // 局部 (10,0) 绕 (5,5) 转 90° 顺时针 → (10,10)
        assertEquals(10f, m.mapX(10f, 0f), 0.001f)
        assertEquals(10f, m.mapY(10f, 0f), 0.001f)
    }

    @Test
    fun `容器缩放会带着子节点走`() {
        // 容器：位置 (10,20)，60x40，缩放 2 倍
        val container = Mat2D
            .translate(10f, 20f)
            .then(Mat2D.scaleRotate(scale = 2f, rotationDeg = 0f, cx = 60f, cy = 40f))

        // 子元素局部坐标 (5,5) → 世界坐标 = 容器原点 + (5,5)*2
        assertEquals(20f, container.mapX(5f, 5f), 0.001f)
        assertEquals(30f, container.mapY(5f, 5f), 0.001f)
    }

    @Test
    fun `嵌套容器的变换可以累乘`() {
        val outer = Mat2D
            .translate(10f, 20f)
            .then(Mat2D.scaleRotate(2f, 0f, 30f, 20f))
        // 内层容器局部位置 (5,5)，自身不缩放
        val inner = outer.then(Mat2D.translate(5f, 5f))

        // 内层原点 = 外层把 (5,5) 映射过去的结果
        assertEquals(20f, inner.mapX(0f, 0f), 0.001f)
        assertEquals(30f, inner.mapY(0f, 0f), 0.001f)
    }

    @Test
    fun `逆矩阵能把世界坐标还原成局部坐标`() {
        val m = Mat2D
            .translate(10f, 20f)
            .then(Mat2D.scaleRotate(2f, 0f, 60f, 40f))

        val worldX = m.mapX(5f, 5f)
        val worldY = m.mapY(5f, 5f)

        val inverse = m.invert()!!
        assertEquals(5f, inverse.mapX(worldX, worldY), 0.001f)
        assertEquals(5f, inverse.mapY(worldX, worldY), 0.001f)
    }

    @Test
    fun `旋转后逆矩阵仍然可还原`() {
        val m = Mat2D
            .translate(12f, 34f)
            .then(Mat2D.scaleRotate(1.5f, 37f, 20f, 10f))

        val inverse = m.invert()!!
        val x = 7f
        val y = 3f
        val wx = m.mapX(x, y)
        val wy = m.mapY(x, y)
        assertEquals(x, inverse.mapX(wx, wy), 0.01f)
        assertEquals(y, inverse.mapY(wx, wy), 0.01f)
    }

    @Test
    fun `退化矩阵没有逆`() {
        // 缩放 0 会压成一条线，求逆必须失败而不是返回垃圾
        assertNull(Mat2D.scaleRotate(0f, 0f, 0f, 0f).invert())
    }
}
