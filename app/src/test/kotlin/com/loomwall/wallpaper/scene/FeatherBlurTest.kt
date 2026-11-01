package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 羽化模糊测试。
 *
 * 羽化最容易出两类错：模糊半径算错（几乎看不出效果）和 alpha 溢出（边缘发黑）。
 * 这里用可控的小图钉住"模糊确实扩散了 alpha、且总透明度大致守恒"。
 */
class FeatherBlurTest {

    private fun grid(size: Int, inside: (Int, Int) -> Boolean): IntArray =
        IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (inside(x, y)) 0xFFFFFFFF.toInt() else 0x00000000
        }

    @Test
    fun `羽化带外的最外圈完全透明`() {
        val size = 40
        val pixels = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        FeatherBlur.applyEdgeRamp(pixels, size, size, 8)

        // 四条边上的像素 alpha 必须是 0
        for (i in 0 until size) {
            assertEquals("上边缘 x=$i 应为透明", 0, (pixels[i] ushr 24) and 0xFF)
            assertEquals("下边缘 x=$i 应为透明", 0, (pixels[(size - 1) * size + i] ushr 24) and 0xFF)
            assertEquals("左边缘 y=$i 应为透明", 0, (pixels[i * size] ushr 24) and 0xFF)
            assertEquals("右边缘 y=$i 应为透明", 0, (pixels[i * size + size - 1] ushr 24) and 0xFF)
        }
    }

    @Test
    fun `羽化带之外一个像素都不改`() {
        val size = 40
        val band = 6
        val pixels = IntArray(size * size) { 0xFF3366FF.toInt() }
        val before = pixels.copyOf()
        FeatherBlur.applyEdgeRamp(pixels, size, size, band)

        for (y in band until size - band) {
            for (x in band until size - band) {
                assertEquals("($x,$y) 不该被修改", before[y * size + x], pixels[y * size + x])
            }
        }
    }

    @Test
    fun `带内 alpha 单调向内递增`() {
        val size = 40
        val pixels = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        FeatherBlur.applyEdgeRamp(pixels, size, size, 10)

        // 从左右方向看中间那一行的 alpha 变化
        val row = size / 2
        var previous = -1
        for (x in 0 until size / 2) {
            val a = (pixels[row * size + x] ushr 24) and 0xFF
            assertTrue("alpha 应随距离增大而不减：x=$x a=$a prev=$previous", a >= previous)
            previous = a
        }
    }

    @Test
    fun `羽化带不会超过尺寸的一半`() {
        val size = 20
        val pixels = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        FeatherBlur.applyEdgeRamp(pixels, size, size, 999)

        // 中心点仍然有一定不透明度（没有被整张抹掉）
        val center = (pixels[(size / 2) * size + size / 2] ushr 24) and 0xFF
        assertTrue("中心 alpha 应大于 0，实际 $center", center > 0)
    }

    @Test
    fun `羽化带为 0 或负数时不做任何事`() {
        val pixels = IntArray(16) { 0xFFFFFFFF.toInt() }
        val before = pixels.copyOf()
        FeatherBlur.applyEdgeRamp(pixels, 4, 4, 0)
        FeatherBlur.applyEdgeRamp(pixels, 4, 4, -5)
        assertTrue(pixels.contentEquals(before))
    }

    @Test
    fun `羽化保留 RGB 只改 alpha`() {
        val size = 20
        val pixels = IntArray(size * size) { 0xFF3366FF.toInt() }
        FeatherBlur.applyEdgeRamp(pixels, size, size, 5)
        for (p in pixels) {
            assertEquals(0x3366FF, p and 0x00FFFFFF)
        }
    }
    @Test
    fun `半径为 0 时不改动像素`() {
        val pixels = grid(8) { x, y -> x in 2..5 && y in 2..5 }
        val copy = pixels.copyOf()
        FeatherBlur.blurArgb(pixels, 8, 8, 0)
        assertTrue(pixels.contentEquals(copy))
    }

    @Test
    fun `模糊后边缘出现半透明像素`() {
        val size = 16
        val pixels = grid(size) { x, y -> x in 6..9 && y in 6..9 }
        FeatherBlur.blurArgb(pixels, size, size, 2)

        var semiTransparent = 0
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            if (a in 1..254) semiTransparent++
        }
        assertTrue("应该出现半透明过渡像素，实际 $semiTransparent 个", semiTransparent > 8)
    }

    @Test
    fun `中心仍然接近不透明`() {
        val size = 16
        val pixels = grid(size) { x, y -> x in 5..10 && y in 5..10 }
        FeatherBlur.blurArgb(pixels, size, size, 1)

        val center = pixels[8 * size + 8]
        val alpha = (center ushr 24) and 0xFF
        assertTrue("中心 alpha 应该接近 255，实际 $alpha", alpha > 200)
    }

    @Test
    fun `透明区域保持透明`() {
        val size = 16
        val pixels = grid(size) { x, y -> x in 6..9 && y in 6..9 }
        FeatherBlur.blurArgb(pixels, size, size, 2)

        val corner = pixels[0]
        assertEquals(0, (corner ushr 24) and 0xFF)
    }

    @Test
    fun `alpha 总量大致守恒`() {
        val size = 16
        fun totalAlpha(px: IntArray) = px.sumOf { (it ushr 24) and 0xFF }
        val pixels = grid(size) { x, y -> x in 4..11 && y in 4..11 }
        val before = totalAlpha(pixels)
        FeatherBlur.blurArgb(pixels, size, size, 3)
        val after = totalAlpha(pixels)
        // 三次盒模糊会在边界截断少量能量，允许 40% 偏差
        assertTrue("alpha 总量应大致守恒：$before → $after", after > before * 0.6 && after < before * 1.4)
    }

    @Test
    fun `半径超过图片尺寸也不崩溃`() {
        val size = 8
        val pixels = grid(size) { _, _ -> true }
        FeatherBlur.blurArgb(pixels, size, size, 100)
        // 全不透明的图模糊后仍应全不透明
        for (p in pixels) assertEquals(255, (p ushr 24) and 0xFF)
    }

    @Test
    fun `尺寸或半径非法时安全返回`() {
        val pixels = IntArray(4) { 0xFFFFFFFF.toInt() }
        FeatherBlur.blurArgb(pixels, 0, 0, 2)
        FeatherBlur.blurArgb(IntArray(0), 0, 0, 2)
        FeatherBlur.blurArgb(pixels, 2, 2, -3)
        assertEquals(0xFFFFFFFF.toInt(), pixels[0])
    }
}
