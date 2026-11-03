package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本模板：占位符替换、补零、小数、缺失变量保留原样。
 */
class TextTemplateTest {

    @Test
    fun `占位符被替换成变量值`() {
        val out = TextTemplate.render(
            "{sys.time.hour:00}:{sys.time.minute:00}",
            mapOf(SystemVars.TIME_HOUR to 9f, SystemVars.TIME_MINUTE to 5f)
        )
        assertEquals("09:05", out)
    }

    @Test
    fun `不写格式时整数不带小数点`() {
        val out = TextTemplate.render("电量 {sys.battery.level}%", mapOf(SystemVars.BATTERY_LEVEL to 85f))
        assertEquals("电量 85%", out)
    }

    @Test
    fun `小数格式保留指定位数`() {
        val out = TextTemplate.render("{sys.battery.level:0.0}", mapOf(SystemVars.BATTERY_LEVEL to 85.44f))
        assertEquals("85.4", out)
    }

    @Test
    fun `未知变量名保留原样便于发现写错`() {
        val out = TextTemplate.render("[{sys.not.exist:00}]", emptyMap())
        assertEquals("[{sys.not.exist:00}]", out)
    }

    @Test
    fun `已知变量但没有数据时输出占位符`() {
        // 例如没授权通知使用权、或设备没有电池温度传感器
        assertEquals("[--]", TextTemplate.render("[{sys.time.hour:00}]".replace("sys.time.hour", "sys.media.title"), emptyMap()))
        assertEquals("--°C", TextTemplate.render("{sys.battery.temp:0.0}°C", emptyMap()))
    }

    @Test
    fun `if 分支内的占位符会被继续展开`() {
        val out = TextTemplate.render(
            """{if(sys.battery.charging,{sys.battery.temp:0.0}°C,"未充电")}""",
            mapOf(SystemVars.BATTERY_CHARGING to 1f, SystemVars.BATTERY_TEMP to 31.5f)
        )
        assertEquals("31.5°C", out)
    }

    @Test
    fun `嵌套展开时缺失的内层变量也会变占位符`() {
        val out = TextTemplate.render(
            """{if(sys.battery.charging,{sys.battery.temp:0.0}°C,"未充电")}""",
            mapOf(SystemVars.BATTERY_CHARGING to 1f)
        )
        assertEquals("--°C", out)
    }

    @Test
    fun `双花括号转义`() {
        val out = TextTemplate.render("{{sys.time.hour}} = {sys.time.hour:00}", mapOf(SystemVars.TIME_HOUR to 7f))
        assertEquals("{sys.time.hour} = 07", out)
    }

    @Test
    fun `没有占位符的文本原样返回`() {
        assertEquals("Hello 世界", TextTemplate.render("Hello 世界", emptyMap()))
    }

    @Test
    fun `if 按条件选择分支`() {
        val charging = TextTemplate.render(
            """{if(sys.battery.charging, "充电中", "未充电")}""",
            mapOf(SystemVars.BATTERY_CHARGING to 1f)
        )
        assertEquals("充电中", charging)

        val idle = TextTemplate.render(
            """{if(sys.battery.charging, "充电中", "未充电")}""",
            mapOf(SystemVars.BATTERY_CHARGING to 0f)
        )
        assertEquals("未充电", idle)
    }

    @Test
    fun `if 的数字条件按非零为真`() {
        val out = TextTemplate.render(
            """{if(sys.battery.level, "有电", "没电")}""",
            mapOf(SystemVars.BATTERY_LEVEL to 0f)
        )
        assertEquals("没电", out)
    }

    @Test
    fun `if 的分支里可以有逗号`() {
        val out = TextTemplate.render(
            """{if(sys.battery.charging, "充电中, 请稍候", "未充电")}""",
            mapOf(SystemVars.BATTERY_CHARGING to 1f)
        )
        assertEquals("充电中, 请稍候", out)
    }

    @Test
    fun `if 引用不存在的变量时走假分支`() {
        val out = TextTemplate.render("""{if(sys.notify.count, "A", "B")}""", emptyMap())
        assertEquals("B", out)
    }

    @Test
    fun `文本变量优先于数值变量`() {
        val out = TextTemplate.render(
            "{sys.media.title}",
            emptyMap(),
            texts = mapOf(SystemVars.MEDIA_TITLE to "夜曲")
        )
        assertEquals("夜曲", out)
    }

    @Test
    fun `文本变量可以用在 if 里做非空判断`() {
        val playing = TextTemplate.render(
            """{if(sys.media.title, "播放中", "无")}""",
            emptyMap(),
            texts = mapOf(SystemVars.MEDIA_TITLE to "夜曲")
        )
        assertEquals("播放中", playing)

        val idle = TextTemplate.render("""{if(sys.media.title, "播放中", "无")}""", emptyMap())
        assertEquals("无", idle)
    }
    @Test
    fun `df 函数按格式输出时间`() {
        val fixed = 1704164645000L // 2024-01-02 03:04:05 UTC
        val out = TextTemplate.render("{df(HH:mm)}", emptyMap(), fixed)
        assertTrue("结果应是 HH:mm 形态，实际=$out", Regex("""^\d{2}:\d{2}$""").matches(out))
    }

    @Test
    fun `df 参数为空时默认 HH_mm`() {
        val out = TextTemplate.render("{df()}", emptyMap(), 1704164645000L)
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(out))
    }

    @Test
    fun `未知函数保留原样不崩溃`() {
        val out = TextTemplate.render("{unknownFn(1)}", emptyMap())
        assertEquals("{unknownFn(1)}", out)
    }

    @Test
    fun `if 可以套 if`() {
        val template = """{if(sys.battery.charging, {if(sys.media.title, "充电+播放", "充电")}, "未充电")}"""

        // 充电 + 有歌 → 充电+播放
        assertEquals(
            "充电+播放",
            TextTemplate.render(
                template,
                mapOf(SystemVars.BATTERY_CHARGING to 1f),
                texts = mapOf(SystemVars.MEDIA_TITLE to "夜曲")
            )
        )

        // 充电 + 没歌 → 充电
        assertEquals(
            "充电",
            TextTemplate.render(template, mapOf(SystemVars.BATTERY_CHARGING to 1f))
        )

        // 没充电 → 未充电（内层不参与）
        assertEquals(
            "未充电",
            TextTemplate.render(
                template,
                mapOf(SystemVars.BATTERY_CHARGING to 0f),
                texts = mapOf(SystemVars.MEDIA_TITLE to "夜曲")
            )
        )
    }

    @Test
    fun `三层嵌套也能展开`() {
        val template =
            """{if(sys.battery.charging, {if(sys.media.title, {if(sys.battery.level, "满电播放", "播放")}, "静音")}, "未充电")}"""
        val out = TextTemplate.render(
            template,
            mapOf(
                SystemVars.BATTERY_CHARGING to 1f,
                SystemVars.BATTERY_LEVEL to 88f
            ),
            texts = mapOf(SystemVars.MEDIA_TITLE to "夜曲")
        )
        assertEquals("满电播放", out)
    }

    @Test
    fun `嵌套 if 的参数里带逗号也不会切错`() {
        val out = TextTemplate.render(
            """{if(sys.battery.charging, {if(sys.media.title, "歌名: 夜曲, 播放中", "静音")}, "未充电")}""",
            mapOf(SystemVars.BATTERY_CHARGING to 1f),
            texts = mapOf(SystemVars.MEDIA_TITLE to "夜曲")
        )
        assertEquals("歌名: 夜曲, 播放中", out)
    }
    @Test
    fun `if 支持大于小于比较`() {
        val tpl = """{if(sys.battery.level > 20, "够用", "低电")}"""
        assertEquals("够用", TextTemplate.render(tpl, mapOf(SystemVars.BATTERY_LEVEL to 85f)))
        assertEquals("低电", TextTemplate.render(tpl, mapOf(SystemVars.BATTERY_LEVEL to 10f)))
    }

    @Test
    fun `if 支持大于等于与等于（含单个等号）`() {
        assertEquals("满", TextTemplate.render("""{if(sys.battery.level >= 100, "满", "未满")}""", mapOf(SystemVars.BATTERY_LEVEL to 100f)))
        assertEquals("满", TextTemplate.render("""{if(sys.battery.level == 100, "满", "未满")}""", mapOf(SystemVars.BATTERY_LEVEL to 100f)))
        assertEquals("满", TextTemplate.render("""{if(sys.battery.level = 100, "满", "未满")}""", mapOf(SystemVars.BATTERY_LEVEL to 100f)))
        assertEquals("未满", TextTemplate.render("""{if(sys.battery.level == 100, "满", "未满")}""", mapOf(SystemVars.BATTERY_LEVEL to 99f)))
    }

    @Test
    fun `if 支持不等于与字符串比较`() {
        val tpl = """{if(sys.media.title != "", "有歌", "没歌")}"""
        assertEquals("有歌", TextTemplate.render(tpl, emptyMap(), texts = mapOf(SystemVars.MEDIA_TITLE to "夜曲")))
        assertEquals("没歌", TextTemplate.render(tpl, emptyMap()))
    }

    @Test
    fun `if 支持 and 和 or`() {
        val andTpl = """{if(sys.battery.charging and sys.media.title, "边充边听", "其他")}"""
        assertEquals(
            "边充边听",
            TextTemplate.render(andTpl, mapOf(SystemVars.BATTERY_CHARGING to 1f), texts = mapOf(SystemVars.MEDIA_TITLE to "夜曲"))
        )
        assertEquals("其他", TextTemplate.render(andTpl, mapOf(SystemVars.BATTERY_CHARGING to 1f)))

        val orTpl = """{if(sys.battery.level < 20 or sys.battery.charging, "注意", "正常")}"""
        assertEquals("注意", TextTemplate.render(orTpl, mapOf(SystemVars.BATTERY_LEVEL to 50f, SystemVars.BATTERY_CHARGING to 1f)))
        assertEquals("正常", TextTemplate.render(orTpl, mapOf(SystemVars.BATTERY_LEVEL to 50f, SystemVars.BATTERY_CHARGING to 0f)))
    }

    @Test
    fun `if 支持 not 与无空格写法`() {
        assertEquals("没充电", TextTemplate.render("""{if(not sys.battery.charging, "没充电", "在充电")}""", mapOf(SystemVars.BATTERY_CHARGING to 0f)))
        assertEquals("低电", TextTemplate.render("""{if(sys.battery.level<20, "低电", "正常")}""", mapOf(SystemVars.BATTERY_LEVEL to 5f)))
    }

    @Test
    fun `条件里嵌别的 if 也能一起工作`() {
        val tpl = """{if(sys.battery.level > 50, {if(sys.battery.charging, "高电+充电", "高电")}, "低电")}"""
        assertEquals(
            "高电+充电",
            TextTemplate.render(tpl, mapOf(SystemVars.BATTERY_LEVEL to 88f, SystemVars.BATTERY_CHARGING to 1f))
        )
        assertEquals("低电", TextTemplate.render(tpl, mapOf(SystemVars.BATTERY_LEVEL to 12f)))
    }
    @Test
    fun `多行文本与占位符共存`() {
        val out = TextTemplate.render(
            "第一行\n电量 {sys.battery.level:0}%",
            mapOf(SystemVars.BATTERY_LEVEL to 42f)
        )
        assertEquals("第一行\n电量 42%", out)
    }

    @Test
    fun `可以列出未解析的变量名`() {
        val missing = TextTemplate.unresolvedTemplateNames(
            "{sys.time.hour} {sys.battery.level}",
            mapOf(SystemVars.TIME_HOUR to 1f)
        )
        assertEquals(listOf(SystemVars.BATTERY_LEVEL), missing)
    }

    @Test
    fun `文本节点可以被解析并写回`() {
        val json = """
            {
              "formatVersion": "0.1",
              "root": {
                "id": "root", "type": "container", "background": "#FF000000",
                "children": [
                  { "id": "t1", "type": "text", "x": 10, "y": 20, "width": 60, "height": 10,
                    "text": "{sys.time.hour:00}:00", "fontSize": 8, "align": "center",
                    "color": "#FFFFFFFF" }
                ]
              }
            }
        """.trimIndent()

        val doc = SceneParser.parse(json)
        val text = doc.root.children.filterIsInstance<TextNode>().single()
        assertEquals(TextAlign.CENTER, text.align)
        assertEquals(8f, text.fontSize, 1e-6f)

        val rewritten = SceneWriter.toJson(doc)
        val reparsed = SceneParser.parse(rewritten)
        val again = reparsed.root.children.filterIsInstance<TextNode>().single()
        assertEquals(text.text, again.text)
        assertEquals(text.align, again.align)
    }

    @Test
    fun `未知对齐方式会被拒绝`() {
        val json = """
            {
              "formatVersion": "0.1",
              "root": {
                "id": "root", "type": "container",
                "children": [
                  { "id": "t1", "type": "text", "x": 0, "y": 0, "width": 10, "height": 10,
                    "text": "x", "fontSize": 4, "align": "middle", "color": "#FFFFFFFF" }
                ]
              }
            }
        """.trimIndent()
        val error = runCatching { SceneParser.parse(json) }.exceptionOrNull()
        assertTrue(error is SceneFormatException)
        assertTrue(error!!.message!!.contains("align"))
    }
// ---- 音频函数（0.7 之后新增的可视化数据源）----

    private fun audioVars(): Map<String, Float> = mapOf(
        "sys.audio.level" to 0.6f,
        "sys.audio.bass" to 0.8f,
        "sys.audio.mid" to 0.45f,
        "sys.audio.treble" to 0.2f,
        "sys.audio.beat" to 1f,
        "sys.audio.b1" to 0.9f,
        "sys.audio.b3" to 0.35f
    )

    @Test
    fun `音频函数能取出频谱与节拍`() {
        val vars = audioVars()
        assertEquals("0.900", TextTemplate.render("{audio(1):0.000}", vars))
        assertEquals("0.350", TextTemplate.render("{band(3):0.000}", vars))
        assertEquals("0.800", TextTemplate.render("{bass():0.000}", vars))
        assertEquals("1.000", TextTemplate.render("{beat():0.000}", vars))
    }

    @Test
    fun `音频函数越界或缺失时给 0 而不是报错`() {
        val vars = audioVars()
        assertEquals("0.000", TextTemplate.render("{audio(99):0.000}", vars))
        assertEquals("0.000", TextTemplate.render("{audio(5):0.000}", vars))
        assertEquals("无法识别就原样保留", "{audio(x)}", TextTemplate.render("{audio(x)}", vars))
    }
}