package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视差语义测试。
 *
 * 这里守的是"谁听谁的"——这是唯一容易写错、又最容易被用户当成 bug 的地方：
 * 全局关掉时跟随者必须停，独立层必须还能动；跟随者的景深只能来自全局。
 */
class ParallaxTest {

    private val global = ParallaxSettings(enabled = true, depth = 50f, amp = 6f, smoothing = 0.2f, deadzone = 0f)

    @Test
    fun `follows global when node has no parallax fields`() {
        val r = resolveParallax(null, global)
        assertTrue(r.enabled)
        assertEquals(50f, r.depth, 0.001f)
        assertEquals(6f, r.amp, 0.001f)
        assertEquals(0f, r.angleDeg, 0.001f)
    }

    @Test
    fun `global switch turns followers off`() {
        val r = resolveParallax(null, global.copy(enabled = false))
        assertFalse(r.enabled)
        val off = r.offsetUnits(1f, 0f)
        assertEquals(0f, off[0], 0.001f)
        assertEquals(0f, off[1], 0.001f)
    }

    @Test
    fun `custom layer ignores global switch`() {
        val node = NodeParallax(custom = true, depth = 80f, amp = 3f, angleDeg = 0f)
        val r = resolveParallax(node, global.copy(enabled = false))
        assertTrue("独立图层不受全局开关影响", r.enabled)
        val off = r.offsetUnits(1f, 0f)
        assertEquals(3f * 80f / 100f, off[0], 0.001f)
    }

    @Test
    fun `custom layer uses its own amp but inherits amp when absent`() {
        val inherited = resolveParallax(NodeParallax(custom = true, depth = 50f, amp = null), global)
        assertEquals(6f, inherited.amp, 0.001f)
        val own = resolveParallax(NodeParallax(custom = true, depth = 50f, amp = 2f), global)
        assertEquals(2f, own.amp, 0.001f)
    }

    @Test
    fun `follower depth comes from global not from node`() {
        // 跟随模式下即使节点写了 parallax 也不该用它
        val node = NodeParallax(custom = false, depth = 999f)
        val r = resolveParallax(node, global)
        assertEquals(50f, r.depth, 0.001f)
    }

    @Test
    fun `offset rotates with angle`() {
        val base = NodeParallax(custom = true, depth = 100f, amp = 10f, angleDeg = 0f)
        val o0 = resolveParallax(base, global).offsetUnits(1f, 0f)
        assertEquals(10f, o0[0], 0.01f)
        assertEquals(0f, o0[1], 0.01f)

        val o90 = resolveParallax(base.copy(angleDeg = 90f), global).offsetUnits(1f, 0f)
        assertEquals(0f, o90[0], 0.01f)
        assertEquals(10f, o90[1], 0.01f)

        val o180 = resolveParallax(base.copy(angleDeg = 180f), global).offsetUnits(1f, 0f)
        assertEquals(-10f, o180[0], 0.01f)

        val o270 = resolveParallax(base.copy(angleDeg = 270f), global).offsetUnits(1f, 0f)
        assertEquals(-10f, o270[1], 0.01f)
    }

    @Test
    fun `zero depth means no movement`() {
        val r = resolveParallax(NodeParallax(custom = true, depth = 0f, amp = 6f), global)
        val off = r.offsetUnits(1f, 1f)
        assertEquals(0f, off[0], 0.001f)
        assertEquals(0f, off[1], 0.001f)
    }

    @Test
    fun `node parallax parses only when fields present`() {
        assertEquals(null, NodeParallax.fromJson(org.json.JSONObject("""{"id":"a"}""")))
        val parsed = NodeParallax.fromJson(
            org.json.JSONObject("""{"id":"a","parallax":60,"parallaxMode":"custom","parallaxAmp":3,"parallaxAngle":-90}""")
        )!!
        assertTrue(parsed.custom)
        assertEquals(60f, parsed.depth, 0.001f)
        assertEquals(3f, parsed.amp!!, 0.001f)
        // 负角度归一化到 0..360
        assertEquals(270f, parsed.angleDeg, 0.001f)
    }

    @Test
    fun `settings parse with defaults and clamps`() {
        assertEquals(ParallaxSettings.DEFAULT, ParallaxSettings.fromJson(null))
        val parsed = ParallaxSettings.fromJson("""{"enabled":false,"depth":-5,"amp":99,"smoothing":0.001}""")
        assertFalse(parsed.enabled)
        assertEquals(0f, parsed.depth, 0.001f)
        assertEquals(12f, parsed.amp, 0.001f)
        assertEquals(0.02f, parsed.smoothing, 0.001f)
    }
}
