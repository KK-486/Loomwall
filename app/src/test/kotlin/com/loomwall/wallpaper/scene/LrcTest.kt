package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRC 解析：格式差异 + 时间戳换算 + 取当前行。
 *
 * 这些都是纯字符串/数组计算，和网络、播放器无关，所以能在电脑上跑。
 */
class LrcTest {

    @Test
    fun `解析基本行并按时间排序`() {
        val raw = """
            [00:10.00]第二句
            [00:05.50]第一句
            [00:20.00]第三句
        """.trimIndent()
        val lines = Lrc.parse(raw)
        assertEquals(3, lines.size)
        assertEquals(5500L, lines[0].timeMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(10000L, lines[1].timeMs)
        assertEquals(20000L, lines[2].timeMs)
    }

    @Test
    fun `一行多个时间戳会展开成多行`() {
        val lines = Lrc.parse("[00:10.00][01:20.00]副歌")
        assertEquals(2, lines.size)
        assertEquals(listOf(10000L, 80000L), lines.map { it.timeMs })
        assertTrue(lines.all { it.text == "副歌" })
    }

    @Test
    fun `毫秒位数按长度换算`() {
        // LRC 惯例：点后面两位是"百分秒"，.34 = 340ms
        assertEquals(12340L, Lrc.parse("[00:12.34]x")[0].timeMs)
        assertEquals(12340L, Lrc.parse("[00:12.340]x")[0].timeMs)
        assertEquals(12300L, Lrc.parse("[00:12.3]x")[0].timeMs)
        assertEquals(12000L, Lrc.parse("[00:12]x")[0].timeMs)
        assertEquals(1000L, Lrc.parse("[0:01.00]x")[0].timeMs)
    }

    @Test
    fun `offset 为正时歌词整体提前`() {
        // 约定：正 offset = 歌词比时间戳更早出现（time = 时间戳 - offset）
        val lines = Lrc.parse("[offset:+500]\n[00:01.00]一")
        assertEquals(500L, lines[0].timeMs)
    }

    @Test
    fun `offset 为负时歌词整体延后且不会变负`() {
        assertEquals("负 offset = 延后", 400L, Lrc.parse("[offset:-300]\n[00:00.10]一")[0].timeMs)
        assertEquals("算出来是负数要夹到 0", 0L, Lrc.parse("[offset:+5000]\n[00:00.10]一")[0].timeMs)
    }

    @Test
    fun `元信息标签与空行被忽略`() {
        val raw = """
            [ti:某首歌]
            [ar:某歌手]
            [al:某专辑]
            [by:某人]

            [00:01.00]歌词
        """.trimIndent()
        val lines = Lrc.parse(raw)
        assertEquals(1, lines.size)
        assertEquals("歌词", lines[0].text)
    }

    @Test
    fun `空文本行保留为清空信号`() {
        val lines = Lrc.parse("[00:01.00]有词\n[00:05.00]\n[00:09.00]又有词")
        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `取当前行在边界上正确`() {
        val lines = Lrc.parse("[00:01.00]一\n[00:05.00]二\n[00:09.00]三")
        assertEquals("还没到第一行", -1, Lrc.indexAt(lines, 500L))
        assertEquals(0, Lrc.indexAt(lines, 1000L))
        assertEquals(0, Lrc.indexAt(lines, 4999L))
        assertEquals(1, Lrc.indexAt(lines, 5000L))
        assertEquals(2, Lrc.indexAt(lines, 999999L))
    }

    @Test
    fun `译文按时间戳对齐而不按行号`() {
        // 原文 3 行，译文少一行（间奏被省略）——按行号对齐会整体错位
        val main = "[00:01.00]一\n[00:05.00]二\n[00:09.00]三"
        val trans = "[00:01.00]one\n[00:09.00]three"
        val map = Lrc.parseTranslations(trans)
        val lines = Lrc.parse(main)
        assertEquals(2, map.size)
        assertEquals("one", map[lines[0].timeMs])
        assertEquals("three", map[lines[2].timeMs])
        assertEquals("中间那行没有译文", null, map[lines[1].timeMs])
    }

    @Test
    fun `空译文不会崩`() {
        assertEquals(0, Lrc.parseTranslations("").size)
        assertEquals(0, Lrc.parseTranslations("   ").size)
    }

    @Test
    fun `制作信息行会被过滤掉`() {
        val raw = """
            [00:00.000] 制作人 : Ayase
            [00:00.357] 作词 : Ayase
            [00:00.714] 作曲 : Ayase
            [00:01.071] 编曲 : Ayase
            [00:01.430] 沈むように溶けてゆくように
            [00:08.831] Lyrics by Someone
        """.trimIndent()
        val lines = Lrc.parse(raw)
        assertEquals("只剩真正的歌词行", 1, lines.size)
        assertEquals("沈むように溶けてゆくように", lines[0].text)
    }

    @Test
    fun `正文里出现曲字不算制作信息`() {
        val lines = Lrc.parse("[00:10.00]この曲が好き\n[00:20.00]作曲 : 谁")
        assertEquals(1, lines.size)
        assertEquals("この曲が好き", lines[0].text)
    }

    @Test
    fun `空歌词不会崩`() {
        assertEquals(0, Lrc.parse("").size)
        assertEquals(0, Lrc.parse("   \n  ").size)
        assertEquals(-1, Lrc.indexAt(emptyList(), 1000L))
        assertEquals("纯数字行不当作歌词", 0, Lrc.parse("12345").size)
    }
}
