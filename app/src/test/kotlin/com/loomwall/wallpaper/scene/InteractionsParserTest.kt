package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * interactions.json 解析测试。
 *
 * 这些用例覆盖的都是“手改预设时最容易犯、又最难看出来的错”：
 * 关键帧时间不递增、最后一帧没到 1.0、同属性两条轨道、时长写成 0。
 */
class InteractionsParserTest {

    private fun interactions(vararg clips: String): String = """
        {
          "formatVersion": "0.1",
          "animations": [ ${clips.joinToString(",")} ]
        }
    """.trimIndent()

    private fun clip(
        property: String = "scale",
        duration: Int = 600,
        loop: String = "once",
        keyframes: String = """{"t":0,"value":1,"easing":"linear"},{"t":1,"value":1.5,"easing":"linear"}""",
        targetId: String = "rect_a"
    ): String = """
        {
          "id": "anim_$property",
          "targetId": "$targetId",
          "trigger": { "type": "tap" },
          "tracks": [ { "property": "$property", "durationMs": $duration, "loop": "$loop", "keyframes": [ $keyframes ] } ]
        }
    """.trimIndent()

    @Test
    fun `仓库里的默认交互动可以被解析`() {
        val json = File("src/main/assets/presets/default/interactions.json").readText()
        val parsed = InteractionsParser.parse(json)

        assertEquals(EngineVersion.INTERACTIONS_FORMAT, parsed.formatVersion)
        assertEquals(2, parsed.animations.size)

        val pulse = parsed.animations.first { it.id == "anim_glow_pulse" }
        assertEquals("rect_glow", pulse.targetId)
        assertEquals(Trigger.Type.TAP, pulse.trigger.type)
        assertEquals(2, pulse.tracks.size)
        assertEquals(AnimatableProperty.SCALE, pulse.tracks[0].property)
        assertEquals(700, pulse.tracks[0].durationMs)
        assertEquals(LoopMode.ONCE, pulse.tracks[0].loop)

        val breathe = parsed.animations.first { it.id == "anim_accent_breathe" }
        assertEquals(LoopMode.PINGPONG, breathe.tracks[0].loop)
    }

    @Test
    fun `最小合法交互动`() {
        val parsed = InteractionsParser.parse(interactions(clip()))
        assertEquals(1, parsed.animations.size)
        assertEquals(AnimatableProperty.SCALE, parsed.animations[0].tracks[0].property)
    }

    @Test
    fun `关键帧时间必须严格递增`() {
        val error = assertThrows(SceneFormatException::class.java) {
            InteractionsParser.parse(
                interactions(
                    clip(keyframes = """{"t":0,"value":1},{"t":0.5,"value":2},{"t":0.5,"value":3},{"t":1,"value":1}""")
                )
            )
        }
        assertTrue(error.message!!.contains("严格递增"))
    }

    @Test
    fun `末帧可以早于 1_0 之后保持末值`() {
        val parsed = InteractionsParser.parse(
            interactions(clip(keyframes = """{"t":0,"value":1},{"t":0.8,"value":2}"""))
        )
        val track = parsed.animations.single().tracks.single()
        assertEquals(0.8f, track.keyframes.last().t, 1e-6f)
        assertEquals("1.0 处应保持末值", 2f, track.sample(1f), 1e-6f)
    }

    @Test
    fun `常驻触发可以被解析`() {
        val json = """
            {
              "formatVersion": "0.1",
              "animations": [ {
                "id": "a", "targetId": "rect_a", "trigger": { "type": "always" },
                "tracks": [ { "property": "scale", "durationMs": 2000, "loop": "loop",
                  "keyframes": [ {"t":0,"value":1}, {"t":1,"value":1.2} ] } ]
              } ]
            }
        """.trimIndent()
        val parsed = InteractionsParser.parse(json)
        assertEquals(Trigger.Type.ALWAYS, parsed.animations.single().trigger.type)
        assertEquals(LoopMode.LOOP, parsed.animations.single().tracks.single().loop)
    }

    @Test
    fun `同一动画里属性不能重复`() {
        val duplicateTrack = """
            {
              "id": "dup", "targetId": "rect_a", "trigger": {"type":"tap"},
              "tracks": [
                { "property": "scale", "durationMs": 500, "keyframes": [ {"t":0,"value":1}, {"t":1,"value":2} ] },
                { "property": "scale", "durationMs": 500, "keyframes": [ {"t":0,"value":1}, {"t":1,"value":3} ] }
              ]
            }
        """.trimIndent()
        val error = assertThrows(SceneFormatException::class.java) {
            InteractionsParser.parse(interactions(duplicateTrack))
        }
        assertTrue(error.message!!.contains("重复"))
    }

    @Test
    fun `时长必须大于零`() {
        val error = assertThrows(SceneFormatException::class.java) {
            InteractionsParser.parse(interactions(clip(duration = 0)))
        }
        assertTrue(error.message!!.contains("大于 0"))
    }

    @Test
    fun `未知属性要列出可用值`() {
        val error = assertThrows(SceneFormatException::class.java) {
            InteractionsParser.parse(interactions(clip(property = "blur")))
        }
        assertTrue(error.message!!.contains("blur"))
        assertTrue(error.message!!.contains("rotationZ"))
    }

    @Test
    fun `阶段 2 拒绝非点击触发`() {
        val json = """
            {
              "formatVersion": "0.1",
              "animations": [ {
                "id": "a", "targetId": "rect_a", "trigger": { "type": "scroll" },
                "tracks": [ { "property": "scale", "durationMs": 500,
                  "keyframes": [ {"t":0,"value":1}, {"t":1,"value":2} ] } ]
              } ]
            }
        """.trimIndent()
        val error = assertThrows(SceneFormatException::class.java) { InteractionsParser.parse(json) }
        assertTrue(error.message!!.contains("tap"))
        assertTrue(error.message!!.contains("阶段 3"))
    }

    @Test
    fun `未知缓动要带出字段路径`() {
        val error = assertThrows(SceneFormatException::class.java) {
            InteractionsParser.parse(
                interactions(clip(keyframes = """{"t":0,"value":1,"easing":"boing"},{"t":1,"value":2}"""))
            )
        }
        assertEquals("/animations[0]/tracks[0]/keyframes[0]/easing", error.jsonPath)
    }

    @Test
    fun `交互动格式版本不匹配要报错`() {
        val error = assertThrows(SceneFormatException::class.java) {
            InteractionsParser.parse("""{"formatVersion": "9.9", "animations": []}""")
        }
        assertEquals("/formatVersion", error.jsonPath)
    }

    @Test
    fun `格式 0_3 的 vars 与 rules 会被解析出来`() {
        val json = """
            {
              "formatVersion": "0.3",
              "animations": [],
              "vars": { "menu1": 0, "phase": { "value": 2, "min": 0, "max": 3 } },
              "rules": [
                {
                  "id": "toggle_1",
                  "when": { "trigger": "tap", "target": "btnA" },
                  "then": [
                    { "type": "setVar", "var": "menu1", "expr": "1 - menu1" },
                    { "type": "setVisible", "target": "groupA", "expr": "menu1 == 1" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val doc = InteractionsParser.parse(json)
        assertEquals(2, doc.triggers.vars.size)
        assertEquals(1, doc.triggers.rules.size)
        assertEquals("toggle_1", doc.triggers.rules[0].id)
        assertEquals(2, doc.triggers.rules[0].actions.size)
    }

    @Test
    fun `旧格式 0_2 仍然可读（没有 triggers 字段）`() {
        val doc = InteractionsParser.parse("""{"formatVersion": "0.2", "animations": []}""")
        assertTrue(doc.triggers.rules.isEmpty())
        assertTrue(doc.triggers.vars.isEmpty())
    }
    @Test
    fun `解析 func 触发与 onceHold 循环`() {
        val json = """
            {
              "formatVersion": "0.6",
              "animations": [
                {
                  "id": "anim_call", "targetId": "rect_a", "trigger": { "type": "func" },
                  "tracks": [
                    { "property": "x", "durationMs": 300, "loop": "onceHold",
                      "keyframes": [ { "t": 0, "value": 0 }, { "t": 1, "value": 40 } ] }
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsed = InteractionsParser.parse(json)
        val clip = parsed.animations.single()
        assertEquals(Trigger.Type.FUNC, clip.trigger.type)
        assertEquals(LoopMode.ONCE_HOLD, clip.tracks.single().loop)
    }

    @Test
    fun `func 与 onceHold 的拼写错误会被拒绝`() {
        val json = """
            {
              "formatVersion": "0.6",
              "animations": [
                { "id": "a", "targetId": "rect_a", "trigger": { "type": "function" }, "tracks": [] }
              ]
            }
        """.trimIndent()
        try {
            InteractionsParser.parse(json)
            org.junit.Assert.fail("应该拒绝未知触发类型")
        } catch (expected: SceneFormatException) {
            assertTrue(expected.message!!.contains("function"))
        }
    }
}
