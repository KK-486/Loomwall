package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.TriggerParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交互动 0.11：`setVisible.delayMs`（延迟显隐）。
 *
 * 用途是"关闭面板时先播完缩小淡出，再真正隐藏"。守四件事：
 * 1. 延迟未到不执行；到点执行一次且只执行一次；
 * 2. 同一目标的新任务覆盖旧任务（连续点关闭不会排队堆叠）；
 * 3. 立即显隐会取消该目标的延迟任务（"关→马上开"不会被旧的隐藏顶掉）；
 * 4. [RuleRuntime.hasDelayedWork] 能反映队列状态，渲染线程据此继续出帧。
 */
class DelayedVisibleTest {

    private class Recorder : RuleEffects {
        val visible = ArrayList<Pair<String, Boolean>>()
        override fun setVisible(targetId: String, visible: Boolean) {
            this.visible.add(targetId to visible)
        }
        override fun setProperty(
            targetId: String,
            property: String,
            value: Float,
            durationMs: Int,
            easing: String
        ) {}
        override fun launchApp(packageName: String) {}
        override fun openUri(uri: String) {}
        override fun scheduleRepeat(ruleId: String, times: Int, intervalMs: Int) {}
        override fun playAnimation(clipId: String, reverse: Boolean) {}
        override fun mediaControl(command: String) {}
    }

    private fun runtime(json: String, rec: Recorder): RuleRuntime =
        RuleRuntime(model = TriggerParser.parse(JSONObject(json)), effects = rec)

    private val closeOnly = """
        {
          "vars": {},
          "rules": [
            {
              "id": "close",
              "when": { "trigger": "tap", "target": "hitClose" },
              "then": [ { "type": "setVisible", "target": "panel", "value": false, "delayMs": 220 } ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `delayed hide does not run before its deadline`() {
        val rec = Recorder()
        val rt = runtime(closeOnly, rec)
        rt.onTap("hitClose")
        assertTrue("延迟未到不该立刻隐藏", rec.visible.isEmpty())
        assertTrue(rt.hasDelayedWork())
    }

    @Test
    fun `delayed hide runs once after deadline`() {
        val rec = Recorder()
        val rt = runtime(closeOnly, rec)
        rt.onTap("hitClose")

        rt.pumpDelayed(System.currentTimeMillis() + 100)
        assertTrue("还没到点", rec.visible.isEmpty())

        rt.pumpDelayed(System.currentTimeMillis() + 400)
        assertEquals(1, rec.visible.size)
        assertEquals("panel", rec.visible[0].first)
        assertFalse(rec.visible[0].second)

        // 再泵一次也不该重复执行
        rt.pumpDelayed(System.currentTimeMillis() + 800)
        assertEquals(1, rec.visible.size)
        assertFalse(rt.hasDelayedWork())
    }

    @Test
    fun `newer task replaces older one for the same target`() {
        val rec = Recorder()
        val rt = runtime(closeOnly, rec)
        rt.onTap("hitClose")
        rt.onTap("hitClose")
        rt.pumpDelayed(System.currentTimeMillis() + 400)
        assertEquals("同一个面板只该隐藏一次", 1, rec.visible.size)
    }

    @Test
    fun `immediate setVisible cancels the pending hide`() {
        val rec = Recorder()
        val json = """
            {
              "vars": {},
              "rules": [
                {
                  "id": "close",
                  "when": { "trigger": "tap", "target": "hitClose" },
                  "then": [ { "type": "setVisible", "target": "panel", "value": false, "delayMs": 220 } ]
                },
                {
                  "id": "open",
                  "when": { "trigger": "tap", "target": "hitOpen" },
                  "then": [ { "type": "setVisible", "target": "panel", "value": true } ]
                }
              ]
            }
        """.trimIndent()
        val rt = runtime(json, rec)

        rt.onTap("hitClose")
        assertEquals(0, rec.visible.size)
        rt.onTap("hitOpen")
        assertEquals(1, rec.visible.size)
        assertTrue(rec.visible[0].second)

        // 旧的隐藏任务必须被取消：否则 220ms 后面板会莫名消失
        rt.pumpDelayed(System.currentTimeMillis() + 500)
        assertEquals("隐藏任务不该再执行", 1, rec.visible.size)
        assertFalse(rt.hasDelayedWork())
    }

    @Test
    fun `clearDelayed drops queued work`() {
        val rec = Recorder()
        val rt = runtime(closeOnly, rec)
        rt.onTap("hitClose")
        assertTrue(rt.hasDelayedWork())
        rt.clearDelayed()
        assertFalse(rt.hasDelayedWork())
        rt.pumpDelayed(System.currentTimeMillis() + 1000)
        assertTrue("清空后不该还有隐藏", rec.visible.isEmpty())
    }
}
