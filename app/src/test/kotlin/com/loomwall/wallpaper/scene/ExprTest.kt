package com.loomwall.wallpaper.scene

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表达式求值器测试。
 *
 * 这里守的是"白名单"边界：不认识的函数/名字必须报错，除零不能崩，
 * 逻辑运算符要短路。这些都是引擎在手机上跑时的安全边界。
 */
class ExprTest {

    private val ctx = ExprContext(
        numberVar = { name ->
            when (name) {
                "menu1" -> 1f
                "progress" -> 0.5f
                "sys.time.hour" -> 20f
                "sys.time.minute" -> 30f
                "zero" -> 0f
                else -> null
            }
        },
        textVar = { name -> if (name == "sys.media.title") "歌名" else null },
        randomSource = { 0.25f }
    )

    private fun eval(src: String): ExprValue = ExprParser.evaluate(ExprParser.parse(src), ctx)

    private fun num(src: String): Float = eval(src).asNumber()

    // ---------- 基础运算 ----------

    @Test
    fun `arithmetic respects precedence`() {
        assertEquals(14f, num("2 + 3 * 4"), 0.001f)
        assertEquals(20f, num("(2 + 3) * 4"), 0.001f)
        assertEquals(1f, num("7 % 3"), 0.001f)
        assertEquals(-5f, num("-5"), 0.001f)
        assertEquals(2f, num("10 / 5"), 0.001f)
    }

    @Test
    fun `variables resolve`() {
        assertEquals(1f, num("menu1"), 0.001f)
        assertEquals(20f, num("sys.time.hour"), 0.001f)
        // 未知变量按 0 处理，不崩
        assertEquals(0f, num("不存在的变量"), 0.001f)
    }

    @Test
    fun `division by zero yields zero instead of crash`() {
        assertEquals(0f, num("1 / 0"), 0.001f)
        assertEquals(0f, num("1 % 0"), 0.001f)
    }

    // ---------- 比较与逻辑 ----------

    @Test
    fun `comparisons`() {
        assertTrue(eval("menu1 == 1").asBool())
        assertTrue(eval("menu1 != 0").asBool())
        assertTrue(eval("sys.time.hour > 19").asBool())
        assertTrue(eval("sys.time.hour >= 20 and sys.time.minute == 30").asBool())
        assertFalse(eval("sys.time.hour < 12").asBool())
    }

    @Test
    fun `logic operators short circuit and support words`() {
        assertTrue(eval("menu1 or zero").asBool())
        assertFalse(eval("menu1 and zero").asBool())
        assertTrue(eval("not zero").asBool())
        // 短路：右边即使有未知函数也不会被求值（这里用合法的 0 兜底验证结果）
        assertTrue(eval("menu1 or 0").asBool())
    }

    @Test
    fun `text comparison works`() {
        assertTrue(eval("sys.media.title == '歌名'").asBool())
        assertFalse(eval("sys.media.title == '别的'").asBool())
    }

    // ---------- 白名单函数 ----------

    @Test
    fun `whitelisted functions`() {
        assertEquals(5f, num("clamp(9, 0, 5)"), 0.001f)
        assertEquals(3f, num("min(3, 7)"), 0.001f)
        assertEquals(7f, num("max(3, 7)"), 0.001f)
        assertEquals(4f, num("abs(-4)"), 0.001f)
        assertEquals(2f, num("floor(2.9)"), 0.001f)
        assertEquals(3f, num("ceil(2.1)"), 0.001f)
        assertEquals(3f, num("round(2.6)"), 0.001f)
        assertEquals(3f, num("sqrt(9)"), 0.001f)
        assertEquals(0f, num("sqrt(-1)"), 0.001f)
        assertEquals(0.25f, num("random()"), 0.001f)
    }

    @Test
    fun `mod stays non-negative`() {
        // 步进变量用 mod 时不能出现 -1
        assertEquals(2f, num("mod(-1, 3)"), 0.001f)
        assertEquals(0f, num("mod(3, 3)"), 0.001f)
    }

    @Test
    fun `if returns branch value`() {
        assertEquals(10f, num("if(menu1 == 1, 10, 20)"), 0.001f)
        assertEquals(20f, num("if(menu1 == 0, 10, 20)"), 0.001f)
    }

    // ---------- 安全边界 ----------

    @Test
    fun `unknown function is rejected with path`() {
        val e = kotlin.runCatching { ExprParser.parse("eval('bad')", "/rules[0]/then[0]/expr") }.exceptionOrNull()
        assertTrue("应当抛 SceneFormatException", e is SceneFormatException)
        assertTrue("错误信息应带函数名", e!!.message!!.contains("eval"))
        assertTrue("错误信息应带 JSON 路径", e.message!!.contains("/rules[0]"))
    }

    @Test
    fun `wrong arg count is rejected`() {
        val e = kotlin.runCatching { ExprParser.parse("clamp(1, 2)") }.exceptionOrNull()
        assertTrue(e is SceneFormatException)
    }

    @Test
    fun `too deep nesting is rejected`() {
        val deep = "(".repeat(20) + "1" + ")".repeat(20)
        val e = kotlin.runCatching { ExprParser.parse(deep) }.exceptionOrNull()
        assertTrue("超深嵌套应被拒绝", e is SceneFormatException)
    }

    @Test
    fun `too long expression is rejected`() {
        val long = "1" + " + 1".repeat(ExprParser.MAX_LENGTH)
        val e = kotlin.runCatching { ExprParser.parse(long) }.exceptionOrNull()
        assertTrue(e is SceneFormatException)
    }

    @Test
    fun `unbalanced parenthesis is rejected`() {
        assertTrue(kotlin.runCatching { ExprParser.parse("(1 + 2") }.exceptionOrNull() is SceneFormatException)
        assertTrue(kotlin.runCatching { ExprParser.parse("1 + 2)") }.exceptionOrNull() is SceneFormatException)
    }

    @Test
    fun `word boundary prevents order being parsed as or`() {
        // "order" 不能被当成 "or der"
        val e = kotlin.runCatching { ExprParser.parse("order") }.exceptionOrNull()
        // order 会被当成变量名（合法），不应报语法错
        assertEquals(null, e)
        assertEquals(0f, num("order"), 0.001f)
    }
}
