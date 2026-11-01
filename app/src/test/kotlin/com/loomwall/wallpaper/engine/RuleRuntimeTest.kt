package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.TriggerModel
import com.loomwall.wallpaper.scene.TriggerParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触发器运行时测试。
 *
 * 守三件事：
 * 1. 语义正确（点开/点关、时段切换、函数宏）
 * 2. 有界（级联环、动作上限、调用深度都不能死循环）
 * 3. 不越权（只通过 RuleEffects 的三条通道影响画面）
 */
class RuleRuntimeTest {

    /** 记录所有效果，便于断言。 */
    private class Recorder : RuleEffects {
        val visible = LinkedHashMap<String, Boolean>()
        val props = ArrayList<Triple<String, String, Float>>()
        val clips = ArrayList<String>()
        val repeats = ArrayList<Triple<String, Int, Int>>()
        val launches = ArrayList<String>()
        val uris = ArrayList<String>()

        override fun setVisible(targetId: String, visible: Boolean) {
            this.visible[targetId] = visible
        }
        override fun setProperty(
            targetId: String,
            property: String,
            value: Float,
            durationMs: Int,
            easing: String
        ) {
            props.add(Triple(targetId, property, value))
        }
        override fun launchApp(packageName: String) {
            launches.add(packageName)
        }

        override fun openUri(uri: String) {
            uris.add(uri)
        }

        override fun scheduleRepeat(ruleId: String, times: Int, intervalMs: Int) {
            repeats.add(Triple(ruleId, times, intervalMs))
        }

        override fun playAnimation(clipId: String, reverse: Boolean) {
            clips.add(if (reverse) clipId + "（反向）" else clipId)
        }
    }

    private fun build(json: String, recorder: Recorder = Recorder()): Pair<RuleRuntime, Recorder> {
        val model: TriggerModel = TriggerParser.parse(JSONObject(json))
        val runtime = RuleRuntime(
            model = model,
            effects = recorder,
            systemVar = { name ->
                when (name) {
                    "sys.time.hour" -> 20f
                    "sys.time.minute" -> 30f
                    "sys.battery.level" -> 80f
                    else -> null
                }
            },
            textVar = { null },
            randomSource = { 0.5f }
        )
        return runtime to recorder
    }

    // ---------------------------------------------------------------- 开关菜单

    private val toggleMenu = """
        {
          "vars": { "menu1": 0 },
          "rules": [
            {
              "id": "t",
              "when": { "trigger": "tap", "target": "btnA" },
              "then": [
                { "type": "setVar", "var": "menu1", "expr": "1 - menu1" },
                { "type": "setVisible", "target": "groupA", "expr": "menu1 == 1" }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `tap toggles var and visibility`() {
        val (rt, rec) = build(toggleMenu)
        assertEquals(0f, rt.valueOf("menu1"), 0.001f)

        rt.onTap("btnA")
        assertEquals(1f, rt.valueOf("menu1"), 0.001f)
        assertEquals(true, rec.visible["groupA"])

        rt.onTap("btnA")
        assertEquals(0f, rt.valueOf("menu1"), 0.001f)
        assertEquals(false, rec.visible["groupA"])
    }

    @Test
    fun `tap on other element does nothing`() {
        val (rt, rec) = build(toggleMenu)
        rt.onTap("other")
        assertEquals(0f, rt.valueOf("menu1"), 0.001f)
        assertTrue(rec.visible.isEmpty())
    }

    @Test
    fun `canvas wide tap only runs when nothing specific matched`() {
        val json = """
            {
              "vars": { "open": 0 },
              "rules": [
                { "id": "open", "when": { "trigger": "tap", "target": "btn" },
                  "then": [ { "type": "setVar", "var": "open", "value": 1 } ] },
                { "id": "close", "when": { "trigger": "tap" },
                  "then": [ { "type": "setVar", "var": "open", "value": 0 } ] }
              ]
            }
        """.trimIndent()
        val (rt, _) = build(json)
        // 点按钮：只跑具体规则，不会被"点空白关闭"立刻覆盖
        rt.onTap("btn")
        assertEquals(1f, rt.valueOf("open"), 0.001f)
        // 点空白：跑整块画布规则
        rt.onTap("nothing")
        assertEquals(0f, rt.valueOf("open"), 0.001f)
    }

    // ---------------------------------------------------------------- 时段切换

    @Test
    fun `minute rule uses system variables`() {
        val json = """
            {
              "vars": { "phase": 0 },
              "rules": [
                { "id": "night", "when": { "trigger": "minute", "expr": "sys.time.hour >= 19" },
                  "then": [
                    { "type": "setVar", "var": "phase", "value": 3 },
                    { "type": "setVisible", "target": "bg_night", "value": true },
                    { "type": "setVisible", "target": "bg_day", "value": false }
                  ] }
              ]
            }
        """.trimIndent()
        val (rt, rec) = build(json)
        val executed = rt.onMinute()
        assertEquals(1, executed)
        assertEquals(3f, rt.valueOf("phase"), 0.001f)
        assertEquals(true, rec.visible["bg_night"])
        assertEquals(false, rec.visible["bg_day"])
    }

    @Test
    fun `minute rule skipped when condition false`() {
        val json = """
            {"rules":[
              {"id":"day","when":{"trigger":"minute","expr":"sys.time.hour < 12"},
               "then":[{"type":"setVisible","target":"bg_day","value":true}]}
            ]}
        """.trimIndent()
        val (rt, rec) = build(json)
        assertEquals(0, rt.onMinute())
        assertTrue(rec.visible.isEmpty())
    }

    @Test
    fun `session rule runs on first show`() {
        val json = """
            {"rules":[
              {"id":"init","when":{"trigger":"session"},
               "then":[{"type":"setVisible","target":"hint","value":true}]}
            ]}
        """.trimIndent()
        val (rt, rec) = build(json)
        rt.onSession()
        assertEquals(true, rec.visible["hint"])
    }

    // ---------------------------------------------------------------- 值联动 / 函数

    @Test
    fun `property expression maps value`() {
        val json = """
            {
              "vars": { "progress": 50 },
              "rules": [
                { "id": "fill", "when": { "trigger": "var", "var": "progress" },
                  "then": [ { "type": "setProperty", "target": "bar", "property": "width", "expr": "clamp(progress / 100 * 100, 0, 100)" } ] }
              ]
            }
        """.trimIndent()
        val (rt, rec) = build(json)
        rt.setVar("progress", 75f)
        val last = rec.props.last()
        assertEquals("bar", last.first)
        assertEquals("width", last.second)
        assertEquals(75f, last.third, 0.001f)
    }

    @Test
    fun `function macro substitutes params`() {
        val json = """
            {
              "vars": { "menu1": 0 },
              "functions": {
                "openMenu": {
                  "params": ["v", "g"],
                  "steps": [
                    { "type": "setVar", "var": "{{v}}", "value": 1 },
                    { "type": "setVisible", "target": "{{g}}", "value": true }
                  ]
                }
              },
              "rules": [
                { "id": "r", "when": { "trigger": "tap", "target": "btn" },
                  "then": [ { "type": "call", "call": "openMenu", "args": { "v": "menu1", "g": "group1" } } ] }
              ]
            }
        """.trimIndent()
        val (rt, rec) = build(json)
        rt.onTap("btn")
        assertEquals(1f, rt.valueOf("menu1"), 0.001f)
        assertEquals(true, rec.visible["group1"])
    }

    @Test
    fun `play animation action reaches effects`() {
        val json = """
            {"rules":[{"id":"a","when":{"trigger":"tap","target":"btn"},
              "then":[{"type":"playAnimation","clip":"slide"}]}]}
        """.trimIndent()
        val (rt, rec) = build(json)
        rt.onTap("btn")
        assertEquals(listOf("slide"), rec.clips)
    }

    // ---------------------------------------------------------------- 有界性

    @Test
    fun `cyclic var rules stop instead of looping forever`() {
        // a 变 → 改 b；b 变 → 改 a。典型死循环素材
        val json = """
            {
              "vars": { "a": 0, "b": 0 },
              "rules": [
                { "id": "a2b", "when": { "trigger": "var", "var": "a" },
                  "then": [ { "type": "setVar", "var": "b", "expr": "a + 1" } ] },
                { "id": "b2a", "when": { "trigger": "var", "var": "b" },
                  "then": [ { "type": "setVar", "var": "a", "expr": "b + 1" } ] }
              ]
            }
        """.trimIndent()
        val (rt, _) = build(json)
        // 不抛异常、不挂住就是通过；同时应当留下超限计数
        rt.setVar("a", 1f)
        assertTrue("级联必须被上限截断", rt.budgetExceeded >= 1 || rt.valueOf("a") < 1e6f)
    }

    @Test
    fun `action budget caps a greedy rule`() {
        val actions = (1..16).joinToString(",") {
            """{"type":"setVisible","target":"t$it","value":true}"""
        }
        val rules = (1..10).joinToString(",") { i ->
            """{"id":"r$i","when":{"trigger":"tap","target":"btn"},"then":[$actions]}"""
        }
        val (rt, rec) = build("""{"rules":[$rules]}""")
        rt.onTap("btn")
        // 10 条规则 × 16 动作 = 160，但单帧上限 64
        assertTrue("动作数应被截断在 ${RuleRuntime.MAX_ACTIONS_PER_FRAME} 以内，实际 ${rec.visible.size}",
            rec.visible.size <= RuleRuntime.MAX_ACTIONS_PER_FRAME)
        assertTrue(rt.budgetExceeded >= 1)
    }

    @Test
    fun `deep function nesting is capped`() {
        // 函数互相调用（解析期不禁止，运行期靠深度上限截断）
        val json = """
            {
              "functions": {
                "a": { "params": [], "steps": [ { "type": "call", "call": "b" } ] },
                "b": { "params": [], "steps": [ { "type": "call", "call": "a" } ] }
              },
              "rules": [
                { "id": "r", "when": { "trigger": "tap", "target": "btn" },
                  "then": [ { "type": "call", "call": "a" } ] }
              ]
            }
        """.trimIndent()
        val (rt, _) = build(json)
        rt.onTap("btn")   // 不栈溢出即通过
        assertTrue(rt.budgetExceeded >= 1)
    }

    @Test
    fun `vars initialize from model and are queryable`() {
        val json = """{"vars":{"x":{"value":5,"min":0,"max":10}}}"""
        val (rt, _) = build(json)
        assertEquals(5f, rt.valueOf("x"), 0.001f)
        assertTrue(rt.hasVar("x"))
        assertFalse(rt.hasVar("nope"))
        assertEquals(0f, rt.valueOf("nope"), 0.001f)
    }
}
