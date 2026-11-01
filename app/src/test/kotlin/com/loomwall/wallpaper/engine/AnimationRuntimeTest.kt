package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.AnimationClip
import com.loomwall.wallpaper.scene.AnimationTrack
import com.loomwall.wallpaper.scene.AnimatableProperty
import com.loomwall.wallpaper.scene.Easing
import com.loomwall.wallpaper.scene.Interactions
import com.loomwall.wallpaper.scene.Keyframe
import com.loomwall.wallpaper.scene.LoopMode
import com.loomwall.wallpaper.scene.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动画运行时测试：时间 → 属性值的换算。
 *
 * 这里的用例都是「纯计算」，不涉及 GL，因此可以在电脑上跑；
 * 真机只需要验证“点一下确实动了”。
 */
class AnimationRuntimeTest {

    private fun runtime(
        property: AnimatableProperty = AnimatableProperty.SCALE,
        loop: LoopMode = LoopMode.ONCE,
        durationMs: Int = 1000,
        from: Float = 1f,
        to: Float = 2f,
        clipId: String = "anim",
        targetId: String = "rect_a"
    ): AnimationRuntime {
        val clip = AnimationClip(
            id = clipId,
            targetId = targetId,
            trigger = Trigger(Trigger.Type.TAP),
            tracks = listOf(
                AnimationTrack(
                    property = property,
                    keyframes = listOf(
                        Keyframe(0f, from, Easing.Linear),
                        Keyframe(1f, to, Easing.Linear)
                    ),
                    durationMs = durationMs,
                    loop = loop
                )
            )
        )
        return AnimationRuntime(Interactions("0.1", listOf(clip)))
    }

    @Test
    fun `一次播放按时间线性插值`() {
        val runtime = runtime()
        assertTrue(runtime.onTap("rect_a", nowMs = 0L))
        assertTrue(runtime.isAnimating())

        assertEquals(1f, runtime.evaluate(0L)["rect_a"]!!.scale!!, 1e-3f)
        assertEquals(1.5f, runtime.evaluate(500L)["rect_a"]!!.scale!!, 1e-3f)
        assertEquals(2f, runtime.evaluate(1000L)["rect_a"]!!.scale!!, 1e-3f)
    }

    @Test
    fun `once 播完自动结束并交还控制权`() {
        val runtime = runtime()
        runtime.onTap("rect_a", nowMs = 0L)
        runtime.evaluate(1000L)
        assertFalse("once 播完应停止", runtime.isAnimating())
        assertTrue("结束后不应再有任何覆盖值", runtime.evaluate(1200L).isEmpty())
    }

    @Test
    fun `loop 会循环`() {
        val runtime = runtime(loop = LoopMode.LOOP)
        runtime.onTap("rect_a", nowMs = 0L)
        assertEquals(1.5f, runtime.evaluate(1500L)["rect_a"]!!.scale!!, 1e-2f)
        assertEquals(1f, runtime.evaluate(2000L)["rect_a"]!!.scale!!, 1e-2f)
        assertTrue(runtime.isAnimating())
    }

    @Test
    fun `pingpong 会来回`() {
        val runtime = runtime(loop = LoopMode.PINGPONG)
        runtime.onTap("rect_a", nowMs = 0L)
        // 1.5 个周期 → 处于回程的一半
        assertEquals(1.5f, runtime.evaluate(1500L)["rect_a"]!!.scale!!, 1e-2f)
        assertEquals(1f, runtime.evaluate(2000L)["rect_a"]!!.scale!!, 1e-2f)
        assertEquals(2f, runtime.evaluate(3000L)["rect_a"]!!.scale!!, 1e-2f)
    }

    @Test
    fun `点别的元素不会触发`() {
        val runtime = runtime()
        assertFalse(runtime.onTap("rect_other", nowMs = 0L))
        assertFalse(runtime.isAnimating())
        assertTrue(runtime.evaluate(100L).isEmpty())
    }

    @Test
    fun `重复触发会从头重播`() {
        val runtime = runtime()
        runtime.onTap("rect_a", nowMs = 0L)
        assertEquals(1.5f, runtime.evaluate(500L)["rect_a"]!!.scale!!, 1e-3f)

        runtime.onTap("rect_a", nowMs = 500L)
        assertEquals("重新触发后应回到起点", 1f, runtime.evaluate(500L)["rect_a"]!!.scale!!, 1e-3f)
    }

    @Test
    fun `透明度会被裁剪到 0 到 1`() {
        val runtime = runtime(property = AnimatableProperty.OPACITY, from = 1f, to = 3f)
        runtime.onTap("rect_a", nowMs = 0L)
        assertEquals(1f, runtime.evaluate(1000L)["rect_a"]!!.opacity!!, 1e-3f)
    }

    @Test
    fun `未触发的属性保持为空`() {
        val runtime = runtime()
        runtime.onTap("rect_a", nowMs = 0L)
        val overrides = runtime.evaluate(300L)["rect_a"]!!
        assertNull(overrides.x)
        assertNull(overrides.rotationZ)
    }

    @Test
    fun `停止后不再产生覆盖值`() {
        val runtime = runtime(loop = LoopMode.LOOP)
        runtime.onTap("rect_a", nowMs = 0L)
        runtime.stopAll()
        assertFalse(runtime.isAnimating())
        assertTrue(runtime.evaluate(100L).isEmpty())
    }

    @Test
    fun `常驻动画会自动开始且不会重复重启`() {
        val clip = AnimationClip(
            id = "always", targetId = "rect_a",
            trigger = Trigger(Trigger.Type.ALWAYS),
            tracks = listOf(
                AnimationTrack(
                    AnimatableProperty.SCALE,
                    listOf(Keyframe(0f, 1f, Easing.Linear), Keyframe(1f, 2f, Easing.Linear)),
                    1000, LoopMode.LOOP
                )
            )
        )
        val runtime = AnimationRuntime(Interactions("0.1", listOf(clip)))

        assertTrue(runtime.ensureAlwaysStarted(0L))
        assertEquals(1.5f, runtime.evaluate(500L)["rect_a"]!!.scale!!, 1e-3f)
        assertFalse("第二次调用不应重启", runtime.ensureAlwaysStarted(600L))
        assertEquals("时间应继续推进", 1.6f, runtime.evaluate(600L)["rect_a"]!!.scale!!, 1e-3f)

        runtime.stopAll()
        assertTrue("stopAll 之后可以重新启动", runtime.ensureAlwaysStarted(0L))
    }

    @Test
    fun `点击触发不会被常驻启动`() {
        val runtime = runtime()
        assertFalse(runtime.ensureAlwaysStarted(0L))
        assertFalse(runtime.isAnimating())
    }

    @Test
    fun `没有动画时提前返回`() {
        val empty = AnimationRuntime(Interactions.EMPTY)
        assertFalse(empty.hasClips)
        assertFalse(empty.isAnimating())
        assertTrue(empty.evaluate(0L).isEmpty())
    }
    @Test
    fun `func 触发的动画不会被点击触发，只能被 play 调起`() {
        val clip = AnimationClip(
            id = "anim_func",
            targetId = "rect_a",
            trigger = Trigger(Trigger.Type.FUNC),
            tracks = listOf(
                AnimationTrack(
                    property = AnimatableProperty.X,
                    keyframes = listOf(Keyframe(0f, 0f, Easing.Linear), Keyframe(1f, 100f, Easing.Linear)),
                    durationMs = 1000,
                    loop = LoopMode.ONCE_HOLD
                )
            )
        )
        val runtime = AnimationRuntime(Interactions("0.6", listOf(clip)))

        // 点这个图层：不应触发（TAP 才响应点击）
        assertFalse("func 触发不应响应点击", runtime.onTap("rect_a", 0L))
        assertFalse(runtime.isAnimating())

        // 明确调用才播
        assertTrue(runtime.play("anim_func", 0L))
        assertTrue(runtime.isAnimating())
        assertEquals(50f, runtime.evaluate(500L)["rect_a"]!!.x!!, 1e-3f)
    }

    @Test
    fun `onceHold 播完停在尾帧且不再逐帧重绘`() {
        val clip = AnimationClip(
            id = "anim_hold",
            targetId = "rect_a",
            trigger = Trigger(Trigger.Type.TAP),
            tracks = listOf(
                AnimationTrack(
                    property = AnimatableProperty.X,
                    keyframes = listOf(Keyframe(0f, 0f, Easing.Linear), Keyframe(1f, 120f, Easing.Linear)),
                    durationMs = 1000,
                    loop = LoopMode.ONCE_HOLD
                )
            )
        )
        val runtime = AnimationRuntime(Interactions("0.6", listOf(clip)))
        runtime.onTap("rect_a", 0L)

        // 播到尾帧：值到位
        assertEquals(120f, runtime.evaluate(1000L)["rect_a"]!!.x!!, 1e-3f)
        // 动画已摘掉（不再请求下一帧），但值仍然定格在 120
        assertFalse("onceHold 结束后不应继续动画", runtime.isAnimating())
        assertEquals(120f, runtime.evaluate(5000L)["rect_a"]!!.x!!, 1e-3f)

        // 对比：普通 once 播完会撤掉覆盖值，回到静态值
        val plain = AnimationRuntime(
            Interactions(
                "0.6",
                listOf(clip.copy(id = "anim_once", trigger = Trigger(Trigger.Type.TAP), tracks = listOf(
                    clip.tracks[0].copy(loop = LoopMode.ONCE)
                )))
            )
        )
        plain.onTap("rect_a", 0L)
        plain.evaluate(1000L)
        assertNull("普通 once 播完应撤掉覆盖值", plain.evaluate(5000L)["rect_a"])
    }
}
