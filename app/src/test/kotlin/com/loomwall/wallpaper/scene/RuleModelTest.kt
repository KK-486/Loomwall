package com.loomwall.wallpaper.scene

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触发器规则解析测试。
 *
 * 重点：错误必须"带路径、说清楚原因"，不能静默通过 ——
 * 社区预设是别人写的，出错时用户只会看到"壁纸不生效"，路径是唯一的线索。
 */
class RuleModelTest {

    private fun parse(json: String): TriggerModel = TriggerParser.parse(JSONObject(json))

    @Test
    fun `empty document yields empty model`() {
        val m = parse("""{"formatVersion":"0.3","animations":[]}""")
        assertTrue(m.vars.isEmpty())
        assertTrue(m.rules.isEmpty())
        assertTrue(m.functions.isEmpty())
    }

    @Test
    fun `vars parse number and object form`() {
        val m = parse(
            """{"vars":{"menuA":0,"phase":{"value":2,"min":0,"max":3}}}"""
        )
        assertEquals(2, m.vars.size)
        val menu = m.vars.first { it.name == "menuA" }
        assertEquals(0f, menu.value, 0.001f)
        val phase = m.vars.first { it.name == "phase" }
        assertEquals(2f, phase.value, 0.001f)
        assertEquals(3f, phase.max, 0.001f)
    }

    @Test
    fun `var with min greater than max is rejected`() {
        val e = kotlin.runCatching {
            parse("""{"vars":{"x":{"value":0,"min":5,"max":1}}}""")
        }.exceptionOrNull()
        assertTrue(e is SceneFormatException)
        assertTrue(e!!.message!!.contains("/vars/x"))
    }

    @Test
    fun `tap rule with actions parses`() {
        val m = parse(
            """
            {"rules":[
              {"id":"r1","when":{"trigger":"tap","target":"btnA"},
               "then":[
                 {"type":"setVar","var":"menuA","expr":"1 - menuA"},
                 {"type":"setVisible","target":"menuA_group","expr":"menuA == 1"}
               ]}
            ]}
            """
        )
        assertEquals(1, m.rules.size)
        val r = m.rules[0]
        assertTrue(r.trigger is RuleTrigger.Tap)
        assertEquals("btnA", (r.trigger as RuleTrigger.Tap).targetId)
        assertEquals(2, r.actions.size)
        assertTrue(r.actions[0] is RuleAction.SetVar)
        assertTrue(r.actions[1] is RuleAction.SetVisible)
    }

    @Test
    fun `minute trigger carries condition`() {
        val m = parse(
            """
            {"rules":[
              {"id":"r2","when":{"trigger":"minute","expr":"sys.time.hour >= 19"},
               "then":[{"type":"setVisible","target":"bg_night","value":true}]}
            ]}
            """
        )
        assertTrue(m.rules[0].trigger is RuleTrigger.Minute)
        assertEquals("sys.time.hour >= 19", m.rules[0].condition)
    }

    @Test
    fun `tap with null target means whole canvas`() {
        val m = parse(
            """{"rules":[{"id":"r3","when":{"trigger":"tap"},"then":[{"type":"setVar","var":"x","value":0}]}]}"""
        )
        assertNull((m.rules[0].trigger as RuleTrigger.Tap).targetId)
    }

    @Test
    fun `unknown trigger type is rejected with path`() {
        val e = kotlin.runCatching {
            parse("""{"rules":[{"id":"r4","when":{"trigger":"shake"},"then":[]}]}""")
        }.exceptionOrNull()
        assertTrue(e is SceneFormatException)
        assertTrue(e!!.message!!.contains("shake"))
        assertTrue(e.message!!.contains("/rules[0]/when/trigger"))
    }

    @Test
    fun `rule without id is rejected`() {
        val e = kotlin.runCatching {
            parse("""{"rules":[{"when":{"trigger":"minute"},"then":[]}]}""")
        }.exceptionOrNull()
        assertTrue(e is SceneFormatException)
        assertTrue(e!!.message!!.contains("/rules[0]/id"))
    }

    @Test
    fun `too many actions is rejected`() {
        val actions = (1..TriggerParser.MAX_ACTIONS_PER_RULE + 1)
            .joinToString(",") { """{"type":"setVar","var":"v$it","value":0}""" }
        val e = kotlin.runCatching {
            parse("""{"rules":[{"id":"r5","when":{"trigger":"minute"},"then":[$actions]}]}""")
        }.exceptionOrNull()
        assertTrue(e is SceneFormatException)
        assertTrue(e!!.message!!.contains("最多"))
    }

    @Test
    fun `function call requires existing function`() {
        val ok = parse(
            """
            {"functions":{"openMenu":{"params":["g"],"steps":[{"type":"setVisible","target":"{{g}}","value":true}]}},
             "rules":[{"id":"r6","when":{"trigger":"tap"},"then":[{"type":"call","call":"openMenu","args":{"g":"menuA"}}]}]}
            """
        )
        assertTrue(ok.rules[0].actions[0] is RuleAction.CallFunction)
        assertEquals(1, ok.functions.size)

        val e = kotlin.runCatching {
            parse("""{"rules":[{"id":"r7","when":{"trigger":"tap"},"then":[{"type":"call","call":"missing"}]}]}""")
        }.exceptionOrNull()
        assertTrue(e is SceneFormatException)
        assertTrue(e!!.message!!.contains("missing"))
    }

    @Test
    fun `unknown action type is rejected`() {
        val e = kotlin.runCatching {
            parse("""{"rules":[{"id":"r8","when":{"trigger":"tap"},"then":[{"type":"runScript","code":"x"}]}]}""")
        }.exceptionOrNull()
        assertTrue(e is SceneFormatException)
        assertTrue(e!!.message!!.contains("runScript"))
    }

    @Test
    fun `template field is preserved`() {
        val m = parse(
            """{"rules":[{"id":"r9","when":{"trigger":"tap","target":"a"},"then":[],"template":"toggle_menu"}]}"""
        )
        assertEquals("toggle_menu", m.rules[0].template)
    }
}
