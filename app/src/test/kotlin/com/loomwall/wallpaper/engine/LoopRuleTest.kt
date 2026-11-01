package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.RuleAction
import com.loomwall.wallpaper.scene.TriggerModel
import com.loomwall.wallpaper.scene.TriggerParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * for / while 循环的执行与护栏。
 *
 * 循环是唯一能让"一次点击"在渲染线程里跑很久的结构，所以这里不只测语义，
 * 更测**停得下来**：次数上限、共享预算、时间片、嵌套层数、循环体动作上限。
 */
class LoopRuleTest {

    private class Recorder : RuleEffects {
        val props = ArrayList<Triple<String, String, Float>>()
        val vars = ArrayList<Float>()

        override fun setVisible(targetId: String, visible: Boolean) {}
        override fun setProperty(
            targetId: String,
            property: String,
            value: Float,
            durationMs: Int,
            easing: String
        ) {
            props.add(Triple(targetId, property, value))
        }
        override fun launchApp(packageName: String) {}
        override fun openUri(uri: String) {}
        override fun scheduleRepeat(ruleId: String, times: Int, intervalMs: Int) {}
        override fun playAnimation(clipId: String, reverse: Boolean) {}
    }

    private fun runtime(json: String, recorder: Recorder = Recorder()): Pair<RuleRuntime, Recorder> {
        val model: TriggerModel = TriggerParser.parse(JSONObject(json))
        return RuleRuntime(
            model = model,
            effects = recorder,
            systemVar = { null },
            textVar = { null },
            randomSource = { 0f }
        ) to recorder
    }

    private fun tapRule(then: String, vars: String = """"i": 0"""): String = """
        {
          "formatVersion": "0.5",
          "vars": { $vars },
          "rules": [
            { "id": "r1", "when": { "trigger": "tap" }, "then": [ $then ] }
          ]
        }
    """.trimIndent()

    // ---------------------------------------------------------------- 语义

    @Test
    fun `for 循环按次数执行循环体`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "for", "var": "i", "from": "1", "to": "4", "step": "1",
                  "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "i * 10" } ] }
                """.trimIndent()
            )
        )
        val executed = rt.onTap(null)
        assertEquals(1, executed)
        assertEquals("循环体应执行 4 次", 4, rec.props.size)
        assertEquals(listOf(10f, 20f, 30f, 40f), rec.props.map { it.third })
    }

    @Test
    fun `for 循环支持负步长`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "for", "var": "i", "from": "3", "to": "1", "step": "-1",
                  "do": [ { "type": "setProperty", "target": "box", "property": "y", "expr": "i" } ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertEquals(listOf(3f, 2f, 1f), rec.props.map { it.third })
    }

    @Test
    fun `for 循环步长为 0 时按 1 处理而不是死循环`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "for", "var": "i", "from": "1", "to": "3", "step": "0",
                  "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "i" } ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertEquals("步长 0 应退化为 1，跑 3 次", 3, rec.props.size)
    }

    @Test
    fun `while 循环条件为假时一次都不跑`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "while", "expr": "0 > 1", "max": 10,
                  "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "1" } ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertEquals(0, rec.props.size)
    }

    @Test
    fun `while 循环靠变量退出`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "setVar", "var": "n", "expr": "0" },
                { "type": "while", "expr": "n < 3", "max": 10,
                  "do": [
                    { "type": "setProperty", "target": "box", "property": "x", "expr": "n" },
                    { "type": "setVar", "var": "n", "expr": "n + 1" }
                  ] }
                """.trimIndent(),
                vars = """"n": 0"""
            )
        )
        rt.onTap(null)
        assertEquals(3, rec.props.size)
        assertEquals(listOf(0f, 1f, 2f), rec.props.map { it.third })
    }

    // ---------------------------------------------------------------- 护栏

    @Test
    fun `while 条件恒真时被 max 截断`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "while", "expr": "1 == 1", "max": 5,
                  "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "1" } ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertEquals("条件恒真也只能跑 max 次", 5, rec.props.size)
    }

    @Test
    fun `for 次数超过 200 时被截断`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "for", "var": "i", "from": "1", "to": "9999", "step": "1",
                  "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "i" } ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertTrue("不应超过 200 次，实际 ${rec.props.size}", rec.props.size <= 200)
        assertTrue("应触发预算提示", rt.budgetExceeded > 0)
    }

    @Test
    fun `嵌套两层循环的总迭代受共享预算限制`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "for", "var": "i", "from": "1", "to": "20", "step": "1",
                  "do": [
                    { "type": "for", "var": "j", "from": "1", "to": "20", "step": "1",
                      "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "i * j" } ] }
                  ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertTrue("20×20 应被截断，实际 ${rec.props.size}", rec.props.size < 400)
        assertTrue(rt.budgetExceeded > 0)
    }

    @Test
    fun `嵌套超过两层解析阶段就报错`() {
        try {
            TriggerParser.parse(
                JSONObject(
                    tapRule(
                        """
                        { "type": "for", "var": "i", "from": "1", "to": "2", "do": [
                          { "type": "for", "var": "j", "from": "1", "to": "2", "do": [
                            { "type": "for", "var": "k", "from": "1", "to": "2", "do": [
                              { "type": "setProperty", "target": "b", "property": "x", "expr": "1" }
                            ] }
                          ] }
                        ] }
                        """.trimIndent()
                    )
                )
            )
            fail("三层循环应当被拒绝")
        } catch (e: Throwable) {
            assertTrue("错误信息应提到嵌套", (e.message ?: "").contains("嵌套"))
        }
    }

    @Test
    fun `循环体动作超过 16 个解析阶段就报错`() {
        val actions = (1..17).joinToString(",") {
            """{ "type": "setProperty", "target": "b", "property": "x", "expr": "$it" }"""
        }
        try {
            TriggerParser.parse(
                JSONObject(
                    tapRule("""{ "type": "for", "var": "i", "from": "1", "to": "2", "do": [ $actions ] }""")
                )
            )
            fail("17 个动作的循环体应当被拒绝")
        } catch (e: Throwable) {
            assertTrue("错误信息应提到动作数", (e.message ?: "").contains("动作"))
        }
    }

    @Test
    fun `while 循环自带轮次变量，条件可直接用 i`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "while", "var": "i", "expr": "i < 3", "max": 10,
                  "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "i" } ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertEquals("i<3 应跑 3 轮", 3, rec.props.size)
        assertEquals(listOf(0f, 1f, 2f), rec.props.map { it.third })
    }

    @Test
    fun `while 条件写反时一次都不跑而不是死循环`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "while", "var": "i", "expr": "i > 7", "max": 5,
                  "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "i" } ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertEquals("i>7 从 0 开始恒假，应 0 轮", 0, rec.props.size)
    }

    @Test
    fun `while 不写 var 时仍按 max 截断`() {
        val (rt, rec) = runtime(
            tapRule(
                """
                { "type": "while", "expr": "1 == 1", "max": 4,
                  "do": [ { "type": "setProperty", "target": "box", "property": "x", "expr": "1" } ] }
                """.trimIndent()
            )
        )
        rt.onTap(null)
        assertEquals(4, rec.props.size)
    }
    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析出 ForLoop 与 WhileLoop 结构`() {
        val model = TriggerParser.parse(
            JSONObject(
                tapRule(
                    """
                    { "type": "for", "var": "i", "from": "1", "to": "3", "step": "2",
                      "do": [ { "type": "setVar", "var": "x", "expr": "i" } ] },
                    { "type": "while", "expr": "x < 10", "max": 7,
                      "do": [ { "type": "setVar", "var": "x", "expr": "x + 1" } ] }
                    """.trimIndent()
                )
            )
        )
        val actions = model.rules.first().actions
        val forLoop = actions[0] as? RuleAction.ForLoop
        assertNotNull("第一个动作应是 ForLoop", forLoop)
        assertEquals("i", forLoop!!.varName)
        assertEquals("2", forLoop.stepExpr)
        assertEquals(1, forLoop.body.size)

        val whileLoop = actions[1] as? RuleAction.WhileLoop
        assertNotNull("第二个动作应是 WhileLoop", whileLoop)
        assertEquals("x < 10", whileLoop!!.condition)
        assertEquals(7, whileLoop.maxIterations)
    }

    @Test
    fun `while 不写 max 时有默认上限`() {
        val model = TriggerParser.parse(
            JSONObject(
                tapRule(
                    """{ "type": "while", "expr": "1 == 1", "do": [ { "type": "setVar", "var": "x", "expr": "1" } ] }"""
                )
            )
        )
        val loop = model.rules.first().actions.first() as RuleAction.WhileLoop
        assertTrue("默认上限应是有限值", loop.maxIterations in 1..200)
    }
}
