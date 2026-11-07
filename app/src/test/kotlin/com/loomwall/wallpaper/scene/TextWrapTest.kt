package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本折行规则。
 *
 * 测量函数用"每字符固定宽度"的假实现：折行算法本身与字体无关，
 * 用假测量才能把"第几行断在哪"写成确定的断言（真字体下宽度随字形变化，断言会脆）。
 */
class TextWrapTest {

    /** 假设每个字符 10px 宽。 */
    private val measure: (String) -> Float = { it.length * 10f }

    private fun wrap(text: String, maxWidthPx: Float, maxLines: Int) =
        TextWrap.wrap(text, maxWidthPx, maxLines, measure)

    @Test
    fun `maxLines 为 0 时保持旧行为只按换行符切分`() {
        val lines = wrap("第一行\n第二行", 10f, 0)
        assertEquals(listOf("第一行", "第二行"), lines)
    }

    @Test
    fun `短文本不折行`() {
        assertEquals(listOf("你好"), wrap("你好", 100f, 3))
    }

    @Test
    fun `中文按宽度折行`() {
        // 宽度 50px = 5 个字符
        val lines = wrap("一二三四五六七八九十", 50f, 5)
        assertEquals(listOf("一二三四五", "六七八九十"), lines)
    }

    @Test
    fun `超出最大行数时最后一行加省略号`() {
        val lines = wrap("一二三四五六七八九十一二三四五", 50f, 2)
        assertEquals(2, lines.size)
        assertTrue("最后一行应以省略号结尾：${lines.last()}", lines.last().endsWith(TextWrap.ELLIPSIS))
        assertTrue("每行都不能超宽", lines.all { it.length * 10f <= 50f })
    }

    @Test
    fun `空间只够两个字时保留首字加省略号`() {
        val lines = wrap("一二三四五", 20f, 1)
        assertEquals(1, lines.size)
        assertEquals("一" + TextWrap.ELLIPSIS, lines[0])
    }

    @Test
    fun `英文优先在空格处断开`() {
        // 宽度 70px = 7 字符："hello world" → "hello" / "world"
        val lines = wrap("hello world", 70f, 3)
        assertEquals(listOf("hello", "world"), lines)
    }

    @Test
    fun `手动换行与自动折行可以共存`() {
        val lines = wrap("一二三四五六\n七八", 50f, 4)
        assertEquals(listOf("一二三四五", "六", "七八"), lines)
    }

    @Test
    fun `空文本返回一个空行`() {
        assertEquals(listOf(""), wrap("", 100f, 3))
    }

    @Test
    fun `宽度非法时退回手动分行`() {
        assertEquals(listOf("一二"), wrap("一二", 0f, 3))
    }
}
