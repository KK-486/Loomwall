package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.TriggerParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变量持久化（交互动 0.10 的 `vars.*.persist`）。
 *
 * 守三件事：
 * 1. 标了 persist 的变量，启动时要用落盘值覆盖预设初值，并且**夹回 min..max**
 *    （用户可能改过预设范围，旧文件里的值不能越界）；
 * 2. 没标 persist 的变量不受文件影响（否则界面状态会被"记住"，比如重启停在某个面板里）；
 * 3. 只有标了 persist 的变量变化时才回调 [VarPersistence.save]，避免无谓写盘。
 */
class VarPersistenceTest {

    private class FakeStore(private val stored: Map<String, Float>) : VarPersistence {
        val saved = LinkedHashMap<String, Float>()
        var loads = 0

        override fun load(): Map<String, Float> {
            loads++
            return stored
        }

        override fun save(name: String, value: Float) {
            saved[name] = value
        }
    }

    private fun effects(): RuleEffects = object : RuleEffects {
        override fun setVisible(targetId: String, visible: Boolean) {}
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

    private val json = """
        {
          "vars": {
            "apps": { "value": 1, "min": 1, "max": 25, "persist": true },
            "tab":  { "value": -1, "min": -1, "max": 3 }
          },
          "rules": [
            {
              "id": "plus",
              "when": { "trigger": "tap", "target": "btnPlus" },
              "then": [ { "type": "setVar", "var": "apps", "expr": "apps + 1" } ]
            },
            {
              "id": "open",
              "when": { "trigger": "tap", "target": "btnOpen" },
              "then": [ { "type": "setVar", "var": "tab", "value": 1 } ]
            }
          ]
        }
    """.trimIndent()

    private fun runtime(store: VarPersistence): RuleRuntime = RuleRuntime(
        model = TriggerParser.parse(JSONObject(json)),
        effects = effects(),
        persistence = store
    )

    @Test
    fun `persisted var restores stored value on start`() {
        val store = FakeStore(mapOf("apps" to 12f))
        val rt = runtime(store)
        assertEquals(12f, rt.valueOf("apps"), 0.001f)
        assertEquals(1, store.loads)
    }

    @Test
    fun `stored value is clamped into min max`() {
        val store = FakeStore(mapOf("apps" to 999f))
        val rt = runtime(store)
        assertEquals(25f, rt.valueOf("apps"), 0.001f)
    }

    @Test
    fun `non persisted var ignores the file`() {
        val store = FakeStore(mapOf("apps" to 5f, "tab" to 2f))
        val rt = runtime(store)
        assertEquals(-1f, rt.valueOf("tab"), 0.001f)
    }

    @Test
    fun `only persisted vars are written back`() {
        val store = FakeStore(emptyMap())
        val rt = runtime(store)

        rt.onTap("btnPlus")
        assertEquals(2f, rt.valueOf("apps"), 0.001f)
        assertEquals(2f, store.saved["apps"] ?: -1f, 0.001f)

        rt.onTap("btnOpen")
        assertEquals(1f, rt.valueOf("tab"), 0.001f)
        assertTrue("tab 没标 persist，不该写盘", store.saved["tab"] == null)
    }

    @Test
    fun `no persistence keeps preset defaults`() {
        val rt = RuleRuntime(
            model = TriggerParser.parse(JSONObject(json)),
            effects = effects()
        )
        assertEquals(1f, rt.valueOf("apps"), 0.001f)
        rt.onTap("btnPlus")
        assertEquals(2f, rt.valueOf("apps"), 0.001f)
    }
}
