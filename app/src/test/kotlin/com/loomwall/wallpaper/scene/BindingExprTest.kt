package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表达式绑定：函数写法 → 变量名的展开，以及加载期校验。
 *
 * 这些是纯字符串/数值计算，不碰 GL，可以在电脑上验。
 */
class BindingExprTest {

    @Test
    fun `audio 函数展开成变量名`() {
        assertEquals("sys.audio.b3", BindingExpr.expand("audio(3)"))
        assertEquals("sys.audio.b1", BindingExpr.expand("audio( 1 )"))
        assertEquals("20 + 60 * sys.audio.b5", BindingExpr.expand("20 + 60 * audio(5)"))
    }

    @Test
    fun `无参函数展开成变量名`() {
        assertEquals("sys.audio.beat", BindingExpr.expand("beat()"))
        assertEquals("sys.audio.bass * 2", BindingExpr.expand("bass() * 2"))
        assertEquals("sys.audio.level", BindingExpr.expand("level( )"))
    }

    @Test
    fun `不误伤同名前缀的标识符`() {
        // audioVar 不是函数调用，必须原样保留
        assertEquals("audioVar + 1", BindingExpr.expand("audioVar + 1"))
        assertEquals("my.beat + 1", BindingExpr.expand("my.beat + 1"))
    }

    @Test
    fun `非法段号在加载期就报错`() {
        val e1 = runCatching { BindingExpr.validate("audio(0)", "/b/expr") }.exceptionOrNull()
        val e2 = runCatching { BindingExpr.validate("audio(9)", "/b/expr") }.exceptionOrNull()
        assertTrue("audio(0) 应报错", e1 != null)
        assertTrue("audio(9) 应报错", e2 != null)
        assertTrue("错误信息要点明范围", e2!!.message!!.contains("1..8"))
    }

    @Test
    fun `合法表达式能通过校验`() {
        BindingExpr.validate("20 + 60 * audio(3) ^ 2", "/b/expr")
        BindingExpr.validate("min(100, bass() * 300)", "/b/expr")
    }

    @Test
    fun `语法错误会被校验拦住`() {
        val error = runCatching { BindingExpr.validate("20 + * audio(3)", "/b/expr") }.exceptionOrNull()
        assertTrue("语法错误必须报出来", error != null)
    }
}
