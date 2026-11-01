package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触摸事件：手势 → 动作的解析、写回与错误提示。
 */
class EventsTest {

    private fun interactionsJson(events: String) = """
        {
          "formatVersion": "0.2",
          "animations": [],
          "events": [$events]
        }
    """.trimIndent()

    @Test
    fun `播放动画事件可以被解析`() {
        val parsed = InteractionsParser.parse(
            interactionsJson(
                """{ "id": "ev1", "targetId": "rect_a", "gesture": "tap",
                     "action": { "type": "playAnimation", "clipId": "anim_1" } }"""
            )
        )
        val event = parsed.events.single()
        assertEquals(Gesture.TAP, event.gesture)
        assertEquals("rect_a", event.targetId)
        assertTrue(event.action is EventAction.PlayAnimation)
        assertEquals("anim_1", (event.action as EventAction.PlayAnimation).clipId)
    }

    @Test
    fun `不带目标的事件作用于整块画布`() {
        val parsed = InteractionsParser.parse(
            interactionsJson(
                """{ "id": "ev2", "gesture": "doubleTap",
                     "action": { "type": "toggleVisible", "targetId": "rect_b" } }"""
            )
        )
        val event = parsed.events.single()
        assertNull("没有 targetId 就是全屏事件", event.targetId)
        assertEquals(Gesture.DOUBLE_TAP, event.gesture)
        assertEquals("rect_b", (event.action as EventAction.ToggleVisible).targetId)
    }

    @Test
    fun `未知手势会被拒绝并列出可用值`() {
        val error = runCatching {
            InteractionsParser.parse(
                interactionsJson(
                    """{ "id": "ev3", "gesture": "tripleTap",
                         "action": { "type": "playAnimation", "clipId": "a" } }"""
                )
            )
        }.exceptionOrNull()
        assertTrue(error is SceneFormatException)
        assertTrue(error!!.message!!.contains("swipeLeft"))
    }

    @Test
    fun `未知动作会被拒绝`() {
        val error = runCatching {
            InteractionsParser.parse(
                interactionsJson(
                    """{ "id": "ev4", "gesture": "tap",
                         "action": { "type": "playMusic", "track": "x" } }"""
                )
            )
        }.exceptionOrNull()
        assertTrue(error is SceneFormatException)
        assertTrue(error!!.message!!.contains("launchApp"))
    }

    @Test
    fun `事件可以写回再读回`() {
        val original = Interactions(
            formatVersion = EngineVersion.INTERACTIONS_FORMAT,
            animations = emptyList(),
            events = listOf(
                NodeEvent("ev1", "rect_a", Gesture.LONG_PRESS, EventAction.PlayAnimation("anim_x")),
                NodeEvent("ev2", null, Gesture.SWIPE_LEFT, EventAction.ToggleVisible("rect_b"))
            )
        )
        val json = InteractionsWriter.toJson(original)
        val reparsed = InteractionsParser.parse(json)
        assertEquals(2, reparsed.events.size)
        assertEquals(Gesture.LONG_PRESS, reparsed.events[0].gesture)
        assertEquals("anim_x", (reparsed.events[0].action as EventAction.PlayAnimation).clipId)
        assertNull(reparsed.events[1].targetId)
    }

    @Test
    fun `启动应用与打开链接可以被解析`() {
        val parsed = InteractionsParser.parse(
            interactionsJson(
                """{ "id": "ev5", "gesture": "tap",
                     "action": { "type": "launchApp", "package": "com.tencent.mm" } },
                   { "id": "ev6", "gesture": "longPress",
                     "action": { "type": "openUri", "uri": "https://example.com" } }"""
            )
        )
        assertEquals(2, parsed.events.size)
        assertEquals("com.tencent.mm", (parsed.events[0].action as EventAction.LaunchApp).packageName)
        assertEquals("https://example.com", (parsed.events[1].action as EventAction.OpenUri).uri)
    }

    @Test
    fun `四种动作都能写回再读回`() {
        val original = Interactions(
            formatVersion = EngineVersion.INTERACTIONS_FORMAT,
            animations = emptyList(),
            events = listOf(
                NodeEvent("e1", "rect_a", Gesture.TAP, EventAction.PlayAnimation("anim_1")),
                NodeEvent("e2", "rect_b", Gesture.LONG_PRESS, EventAction.ToggleVisible("rect_c")),
                NodeEvent("e3", "rect_d", Gesture.DOUBLE_TAP, EventAction.LaunchApp("com.tencent.mm")),
                NodeEvent("e4", null, Gesture.SWIPE_UP, EventAction.OpenUri("android-app://com.android.settings"))
            )
        )
        val reparsed = InteractionsParser.parse(InteractionsWriter.toJson(original))
        assertEquals(4, reparsed.events.size)
        assertEquals(original.events.map { it.gesture }, reparsed.events.map { it.gesture })
        assertEquals(
            original.events.map { it.action.javaClass },
            reparsed.events.map { it.action.javaClass }
        )
    }

    @Test
    fun `仍然接受 0_1 版本且没有事件`() {
        val parsed = InteractionsParser.parse(
            """{ "formatVersion": "0.1", "animations": [] }"""
        )
        assertTrue(parsed.events.isEmpty())
    }
}
