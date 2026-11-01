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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向播放测试。
 *
 * 为什么必须测：反向如果算错（时间轴没翻、或结束判定用反了），
 * 真机表现就是"点了没反应"或"卡在最后一帧"，很难看出是哪一步。
 */
class ReverseAnimationTest {

    private fun clip(
        id: String = "open",
        target: String = "panel",
        from: Float = 0f,
        to: Float = 100f,
        duration: Int = 1000,
        loop: LoopMode = LoopMode.ONCE
    ): AnimationClip = AnimationClip(
        id = id,
        targetId = target,
        trigger = Trigger(Trigger.Type.ALWAYS, null),
        tracks = listOf(
            AnimationTrack(
                property = AnimatableProperty.Y,
                keyframes = listOf(
                    Keyframe(0f, from, Easing.parse("linear")),
                    Keyframe(1f, to, Easing.parse("linear"))
                ),
                durationMs = duration,
                loop = loop
            )
        )
    )

    private fun runtime(vararg clips: AnimationClip) =
        AnimationRuntime(Interactions("0.2", animations = clips.toList()))

    @Test
    fun `正向播放从起点走向终点`() {
        val rt = runtime(clip())
        rt.play("open", nowMs = 0, reverse = false)
        assertEquals(0f, rt.evaluate(0)["panel"]?.y!!, 0.5f)
        assertEquals(50f, rt.evaluate(500)["panel"]?.y!!, 0.5f)
        assertEquals(100f, rt.evaluate(1000)["panel"]?.y!!, 0.5f)
    }

    @Test
    fun `反向播放从尾帧回到首帧`() {
        val rt = runtime(clip())
        rt.play("open", nowMs = 0, reverse = true)
        assertEquals(100f, rt.evaluate(0)["panel"]?.y!!, 0.5f)
        assertEquals(50f, rt.evaluate(500)["panel"]?.y!!, 0.5f)
        assertEquals(0f, rt.evaluate(1000)["panel"]?.y!!, 0.5f)
    }

    @Test
    fun `反向播放结束后动画被移除`() {
        val rt = runtime(clip())
        rt.play("open", nowMs = 0, reverse = true)
        rt.evaluate(1200)
        assertNull(rt.evaluate(1300)["panel"])
    }

    @Test
    fun `反向播放中途再触发会从头重播`() {
        val rt = runtime(clip())
        rt.play("open", nowMs = 0, reverse = true)
        rt.evaluate(400)
        rt.play("open", nowMs = 500, reverse = true)
        assertEquals(100f, rt.evaluate(500)["panel"]?.y!!, 0.5f)
    }

    @Test
    fun `不存在的动画 id 返回 false`() {
        val rt = runtime(clip())
        assertTrue(!rt.play("nope", nowMs = 0))
    }

    @Test
    fun `循环动画反向也能取到值`() {
        val rt = runtime(clip(loop = LoopMode.LOOP))
        rt.play("open", nowMs = 0, reverse = true)
        assertNotNull(rt.evaluate(250)["panel"])
    }
}
