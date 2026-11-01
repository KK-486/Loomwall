package com.loomwall.wallpaper.editor

import com.loomwall.wallpaper.scene.AnimatableProperty
import com.loomwall.wallpaper.scene.Easing
import com.loomwall.wallpaper.scene.Interactions
import com.loomwall.wallpaper.scene.InteractionsParser
import com.loomwall.wallpaper.scene.InteractionsWriter
import com.loomwall.wallpaper.scene.LoopMode
import com.loomwall.wallpaper.scene.SceneParser
import com.loomwall.wallpaper.scene.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑模型测试（专业面板语义）。
 *
 * 关键变化：新建动画 = **空轨道**（只有 0s 的起始值），不再预置三段关键帧；
 * 关键帧时间不再强制末帧为 1.0；支持播放头定位与"在播放头写关键帧"。
 */
class EditorStateTest {

    private fun scene() = SceneParser.parse(
        """
        {
          "formatVersion": "0.1",
          "root": {
            "id": "root", "type": "container", "background": "#FF000000",
            "children": [
              {"id": "rect_a", "type": "rect", "x": 10, "y": 20, "width": 40, "height": 10,
               "color": "#FF3DDC97", "opacity": 0.8}
            ]
          }
        }
        """.trimIndent()
    )

    private fun state(): EditorState = EditorState(scene(), Interactions.EMPTY)

    @Test
    fun `新建动画是一条只有起始值的空轨道`() {
        val state = state()
        state.select("rect_a")
        val clipId = state.addAnimation(AnimatableProperty.SCALE)
        assertNotNull(clipId)

        val clip = state.selectedClip!!
        assertEquals("rect_a", clip.targetId)
        assertEquals("默认应为常驻触发", Trigger.Type.ALWAYS, clip.trigger.type)
        assertEquals(LoopMode.LOOP, clip.tracks.single().loop)

        val track = clip.tracks.single()
        assertEquals(1, track.keyframes.size)
        assertEquals(0f, track.keyframes.first().t, 1e-6f)
        assertEquals("起始值应等于元素当前静态值", 1f, track.keyframes.first().value, 1e-6f)
        assertEquals(0f, state.playhead, 1e-6f)
    }

    @Test
    fun `在播放头写关键帧会自动建轨道并保留起点`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.X)
        state.setPlayhead(0.4f)

        assertTrue(state.writeKeyframe(AnimatableProperty.X, 30f))

        val track = state.selectedClip!!.tracks.single { it.property == AnimatableProperty.X }
        assertEquals(2, track.keyframes.size)
        assertEquals(0f, track.keyframes[0].t, 1e-6f)
        assertEquals(10f, track.keyframes[0].value, 1e-6f)
        assertEquals(0.4f, track.keyframes[1].t, 1e-6f)
        assertEquals(30f, track.keyframes[1].value, 1e-6f)
    }

    @Test
    fun `连续写同一时刻只更新不新增`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.X)
        state.setPlayhead(0.5f)

        state.writeKeyframe(AnimatableProperty.X, 20f)
        state.writeKeyframe(AnimatableProperty.X, 25f)

        val track = state.selectedClip!!.tracks.single()
        assertEquals(2, track.keyframes.size)
        assertEquals(25f, track.keyframes.last().value, 1e-6f)
    }

    @Test
    fun `没有选中元素时不能新建动画`() {
        val state = state()
        assertNull(state.addAnimation(AnimatableProperty.SCALE))
        assertTrue(state.interactions.animations.isEmpty())
    }

    @Test
    fun `播放头被夹在 0 到 1`() {
        val state = state()
        state.setPlayhead(1.8f)
        assertEquals(1f, state.playhead, 1e-6f)
        state.setPlayhead(-0.5f)
        assertEquals(0f, state.playhead, 1e-6f)
    }

    @Test
    fun `触发方式可以在常驻与点击之间切换`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.SCALE)
        state.setClipTrigger(Trigger.Type.TAP)
        assertEquals(Trigger.Type.TAP, state.selectedClip!!.trigger.type)
        assertEquals("点击触发需要目标", "rect_a", state.selectedClip!!.trigger.targetId)

        state.setClipTrigger(Trigger.Type.ALWAYS)
        assertEquals(Trigger.Type.ALWAYS, state.selectedClip!!.trigger.type)
        assertNull("常驻不需要命中目标", state.selectedClip!!.trigger.targetId)
    }

    @Test
    fun `末帧可以早于 1 秒之后保持末值`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.X)
        state.setPlayhead(0.3f)
        state.writeKeyframe(AnimatableProperty.X, 40f)

        val track = state.selectedClip!!.tracks.single()
        assertEquals(0.3f, track.keyframes.last().t, 1e-6f)
        assertEquals("1.0 处应保持末值", 40f, track.sample(1f), 1e-6f)
    }

    @Test
    fun `首帧不能被拖动末帧可以`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.X)
        state.setPlayhead(0.5f)
        state.writeKeyframe(AnimatableProperty.X, 30f)
        val clipId = state.selectedClip!!.id

        state.moveKeyframe(EditorState.KeyframeRef(clipId, AnimatableProperty.X, 0), 0.3f)
        assertEquals("首帧钉在 0", 0f, state.selectedClip!!.tracks.single().keyframes.first().t, 1e-6f)

        state.moveKeyframe(EditorState.KeyframeRef(clipId, AnimatableProperty.X, 1), 0.8f)
        assertEquals(0.8f, state.selectedClip!!.tracks.single().keyframes.last().t, 1e-6f)
    }

    @Test
    fun `关键帧不能删到少于两个`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.X)
        val clipId = state.selectedClip!!.id

        assertFalse(
            "只有一帧时不能删",
            state.removeKeyframe(EditorState.KeyframeRef(clipId, AnimatableProperty.X, 0))
        )

        state.setPlayhead(0.5f)
        state.writeKeyframe(AnimatableProperty.X, 30f)
        assertTrue(state.removeKeyframe(EditorState.KeyframeRef(clipId, AnimatableProperty.X, 1)))
        assertEquals(1, state.selectedClip!!.tracks.single().keyframes.size)
    }

    @Test
    fun `透明度关键帧被裁剪到 0 到 1`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.OPACITY)
        state.setPlayhead(0.5f)
        state.writeKeyframe(AnimatableProperty.OPACITY, 3.5f)

        val value = state.selectedClip!!.tracks.single().keyframes.last().value
        assertEquals(1f, value, 1e-6f)
    }

    @Test
    fun `删除元素会一并删掉指向它的动画`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.SCALE)
        assertEquals(1, state.interactions.animations.size)

        state.deleteSelected()
        assertTrue("悬空动画必须在删除元素时清理", state.interactions.animations.isEmpty())
    }

    @Test
    fun `编辑后的交互动可以写回再读回`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.ROTATION_Z)
        state.setPlayhead(0.5f)
        state.writeKeyframe(AnimatableProperty.ROTATION_Z, 45f)
        state.setClipLoop(LoopMode.PINGPONG)
        state.setClipDuration(900)
        state.setClipTrigger(Trigger.Type.ALWAYS)

        val json = InteractionsWriter.toJson(state.interactions)
        val reparsed = InteractionsParser.parse(json)

        assertEquals(1, reparsed.animations.size)
        val clip = reparsed.animations.single()
        assertEquals("rect_a", clip.targetId)
        assertEquals(Trigger.Type.ALWAYS, clip.trigger.type)
        assertEquals(900, clip.tracks.single().durationMs)
        assertEquals(LoopMode.PINGPONG, clip.tracks.single().loop)
        assertEquals(2, clip.tracks.single().keyframes.size)
        assertEquals(45f, clip.tracks.single().sample(0.5f), 1e-3f)
    }

    @Test
    fun `缓动改动能落到关键帧上`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.SCALE)
        state.setPlayhead(0.5f)
        state.writeKeyframe(AnimatableProperty.SCALE, 2f)
        val clipId = state.selectedClip!!.id

        state.setKeyframeEasing(EditorState.KeyframeRef(clipId, AnimatableProperty.SCALE, 0), Easing.CUBIC_IN)
        assertEquals(Easing.CUBIC_IN, state.selectedClip!!.tracks.single().keyframes[0].easing)
    }

    @Test
    fun `同一属性不会重复添加轨道`() {
        val state = state()
        state.select("rect_a")
        state.addAnimation(AnimatableProperty.SCALE)
        assertFalse(state.addTrack(AnimatableProperty.SCALE))
        assertTrue(state.addTrack(AnimatableProperty.ROTATION_Z))
        assertEquals(2, state.selectedClip!!.tracks.size)
    }
}
