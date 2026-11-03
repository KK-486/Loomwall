package com.loomwall.wallpaper.editor

import com.loomwall.wallpaper.scene.AnimatableProperty
import com.loomwall.wallpaper.scene.AnimationClip
import com.loomwall.wallpaper.scene.AnimationTrack
import com.loomwall.wallpaper.scene.Easing
import com.loomwall.wallpaper.scene.Interactions
import com.loomwall.wallpaper.scene.Keyframe
import com.loomwall.wallpaper.scene.LoopMode
import com.loomwall.wallpaper.scene.NodeOverrides
import com.loomwall.wallpaper.scene.RectNode
import com.loomwall.wallpaper.scene.RgbaColor
import com.loomwall.wallpaper.scene.SceneDocument
import com.loomwall.wallpaper.scene.Trigger

/**
 * 编辑器的全部可变状态：场景 + 交互动 + 选中项 + 预览覆盖值。
 *
 * 设计取舍：
 * - 场景与交互动都保持**不可变**（data class + copy），每次改动换新对象，
 *   GL 线程读到的永远是完整快照，不需要给数据加锁。
 * - 唯一可变的是“选中了什么”和“预览用的覆盖值”，且只在主线程改。
 * - 关键帧的所有编辑都经过 [normalize]，保证三条不变量：
 *   ① 按 t 升序；② t 严格递增无重复；③ 最后一个关键帧的 t 恒为 1.0。
 *   不变量由测试兜底（EditorStateTest），UI 层就可以写得很薄。
 */
class EditorState(initial: SceneDocument, initialInteractions: Interactions = Interactions.EMPTY) {

    /** 引用某个关键帧：动画 + 属性 + 下标。下标在 normalize 之后会重排，UI 每次都要重新取。 */
    data class KeyframeRef(val clipId: String, val property: AnimatableProperty, val index: Int)

    var scene: SceneDocument = initial
        private set

    var interactions: Interactions = initialInteractions
        private set

    var selectedId: String? = null
        private set

    var selectedClipId: String? = initialInteractions.animations.firstOrNull()?.id
        private set

    var selectedKeyframe: KeyframeRef? = null
        private set

    /** 预览动画时，这一帧要覆盖的属性。空闲时为空。 */
    var previewOverrides: Map<String, NodeOverrides> = emptyMap()
        private set

    /** 时间轴播放头位置（0..1）。画布记录关键帧时就是写在这一刻。 */
    var playhead: Float = 0f
        private set

    /** 记录模式：开启后，画布上的拖动/缩放/旋转写成关键帧，而不是改静态属性。 */
    var recordMode: Boolean = false
        private set

    private val listeners = mutableListOf<() -> Unit>()

    val selectedRect: RectNode?
        get() = rects().firstOrNull { it.id == selectedId }

    val selectedClip: AnimationClip?
        get() = interactions.animations.firstOrNull { it.id == selectedClipId }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun select(id: String?) {
        if (selectedId == id) return
        selectedId = id
        notifyChanged()
    }

    fun selectClip(id: String?) {
        if (selectedClipId == id) return
        selectedClipId = id
        selectedKeyframe = null
        notifyChanged()
    }

    fun selectKeyframe(ref: KeyframeRef?) {
        if (selectedKeyframe == ref) return
        selectedKeyframe = ref
        notifyChanged()
    }

    fun replaceScene(document: SceneDocument) {
        scene = document
        selectedId = null
        notifyChanged()
    }

    fun replaceInteractions(value: Interactions) {
        interactions = value
        selectedClipId = value.animations.firstOrNull()?.id
        selectedKeyframe = null
        notifyChanged()
    }

    fun setPreviewOverrides(value: Map<String, NodeOverrides>) {
        previewOverrides = value
        notifyChanged()
    }

    // ---- 场景编辑（阶段 1） ----

    fun updateSelected(transform: (RectNode) -> RectNode) {
        val id = selectedId ?: return
        var changed = false
        val children = scene.root.children.map { node ->
            if (node is RectNode && node.id == id) {
                changed = true
                transform(node)
            } else {
                node
            }
        }
        if (!changed) return
        scene = scene.copy(root = scene.root.copy(children = children))
        notifyChanged()
    }

    fun updateBackground(color: RgbaColor) {
        scene = scene.copy(root = scene.root.copy(background = color))
        notifyChanged()
    }

    fun addRect(): String {
        val id = nextRectId()
        val rect = RectNode(
            id = id,
            visible = true,
            x = 25f,
            y = 40f,
            width = 50f,
            height = 12f,
            color = RgbaColor(0.30f, 0.55f, 0.96f, 1f),
            opacity = 1f
        )
        scene = scene.copy(root = scene.root.copy(children = scene.root.children + rect))
        selectedId = id
        notifyChanged()
        return id
    }

    fun deleteSelected(): Boolean {
        val id = selectedId ?: return false
        scene = scene.copy(
            root = scene.root.copy(children = scene.root.children.filterNot { it.id == id })
        )
        // 同时删掉指向这个元素的动画，避免留下悬空引用（加载时会被拒）。
        interactions = Interactions(
            interactions.formatVersion,
            interactions.animations.filterNot { it.targetId == id }
        )
        selectedId = null
        selectedKeyframe = null
        notifyChanged()
        return true
    }

    fun hitTest(canvasX: Float, canvasY: Float): RectNode? =
        rects().asReversed().firstOrNull { rect ->
            rect.visible &&
                canvasX >= rect.x && canvasX <= rect.x + rect.width &&
                canvasY >= rect.y && canvasY <= rect.y + rect.height
        }

    fun rects(): List<RectNode> = scene.root.children.filterIsInstance<RectNode>()

    // ---- 动画编辑（阶段 2b） ----

    /**
     * 为当前选中元素新建一条动画。
     *
     * 专业面板的语义：**建一条空轨道**，只在 0s 放一个「起始值＝当前静态值」的关键帧，
     * 不预置任何变化。用户接下来通过
     * ①时间轴双击加帧、②移动播放头后在画布上拖动元素，来自己搭出动画。
     *
     * 默认触发是「常驻」（壁纸可见即播），因为壁纸最需要的是呼吸/漂浮这类自动动效；
     * 想改成点击，动画面板里有触发下拉。
     */
    fun addAnimation(property: AnimatableProperty, durationMs: Int = 2000): String? {
        val rect = selectedRect ?: return null
        val clipId = nextClipId(rect.id, property)
        val track = AnimationTrack(
            property = property,
            keyframes = listOf(Keyframe(0f, baseValue(rect, property), Easing.Linear)),
            durationMs = durationMs,
            loop = LoopMode.LOOP
        )
        val clip = AnimationClip(
            id = clipId,
            targetId = rect.id,
            trigger = Trigger(Trigger.Type.ALWAYS),
            tracks = listOf(track)
        )
        interactions = interactions.copy(animations = interactions.animations + clip)
        selectedClipId = clipId
        selectedKeyframe = KeyframeRef(clipId, property, 0)
        playhead = 0f
        notifyChanged()
        return clipId
    }

    fun removeSelectedClip() {
        val id = selectedClipId ?: return
        interactions = interactions.copy(animations = interactions.animations.filterNot { it.id == id })
        selectedClipId = interactions.animations.firstOrNull()?.id
        selectedKeyframe = null
        notifyChanged()
    }

    fun setPlayhead(t: Float) {
        val clamped = t.coerceIn(0f, 1f)
        if (clamped == playhead) return
        playhead = clamped
        notifyChanged()
    }

    fun setRecordMode(enabled: Boolean) {
        if (recordMode == enabled) return
        recordMode = enabled
        notifyChanged()
    }

    fun setClipTrigger(type: Trigger.Type) {
        val clip = selectedClip ?: return
        val target = if (type == Trigger.Type.TAP) clip.targetId else null
        replaceClip(clip.copy(trigger = Trigger(type, target)))
        notifyChanged()
    }

    /**
     * 在**当前播放头位置**写入某属性的关键帧。
     *
     * 专业编辑器的行为（对齐 AE）：该属性还没有轨道时自动建一条——
     * 0s 用元素当时的静态值当起点，播放头处写新值；已有轨道就直接插/改那一刻的帧。
     */
    fun writeKeyframe(property: AnimatableProperty, value: Float): Boolean {
        val clip = selectedClip ?: return false
        val clamped = clampValue(property, value)
        val t = playhead
        val track = clip.tracks.firstOrNull { it.property == property }

        if (track == null) {
            val rect = scene.root.children.filterIsInstance<RectNode>()
                .firstOrNull { it.id == clip.targetId } ?: return false
            val start = Keyframe(0f, baseValue(rect, property), Easing.Linear)
            val frames = if (t <= 0.001f) {
                listOf(Keyframe(0f, clamped, Easing.Linear))
            } else {
                listOf(start, Keyframe(t, clamped, Easing.Linear))
            }
            val newTrack = AnimationTrack(
                property = property,
                keyframes = frames,
                durationMs = clip.tracks.firstOrNull()?.durationMs ?: 2000,
                loop = clip.tracks.firstOrNull()?.loop ?: LoopMode.LOOP
            )
            replaceClip(clip.copy(tracks = clip.tracks + newTrack))
            selectedKeyframe = KeyframeRef(clip.id, property, normalizedIndexOf(clip.id, property, t))
            notifyChanged()
            return true
        }

        val existing = track.keyframes.indexOfFirst { kotlin.math.abs(it.t - t) < MIN_GAP }
        val frames = track.keyframes.toMutableList()
        if (existing >= 0) {
            frames[existing] = frames[existing].copy(value = clamped)
        } else {
            val easing = track.keyframes.lastOrNull { it.t <= t }?.easing ?: Easing.Linear
            frames += Keyframe(t, clamped, easing)
        }
        replaceTrack(clip, normalize(track.copy(keyframes = frames)))
        selectedKeyframe = KeyframeRef(clip.id, property, normalizedIndexOf(clip.id, property, t))
        notifyChanged()
        return true
    }

    /** 当前播放头处某属性的有效值（用于画布拖动时计算增量）。 */
    fun valueAtPlayhead(property: AnimatableProperty): Float? {
        val clip = selectedClip ?: return null
        val track = clip.tracks.firstOrNull { it.property == property } ?: return null
        return track.sample(playhead)
    }

    /** 给当前动画再挂一条属性轨道（同一属性不能重复），同样是空轨道。 */
    fun addTrack(property: AnimatableProperty): Boolean {
        val clip = selectedClip ?: return false
        if (clip.tracks.any { it.property == property }) return false
        val rect = scene.root.children.filterIsInstance<RectNode>()
            .firstOrNull { it.id == clip.targetId } ?: return false

        val track = AnimationTrack(
            property = property,
            keyframes = listOf(Keyframe(0f, baseValue(rect, property), Easing.Linear)),
            durationMs = clip.tracks.firstOrNull()?.durationMs ?: 2000,
            loop = clip.tracks.firstOrNull()?.loop ?: LoopMode.LOOP
        )
        replaceClip(clip.copy(tracks = clip.tracks + track))
        selectedKeyframe = KeyframeRef(clip.id, property, 0)
        notifyChanged()
        return true
    }

    fun removeTrack(property: AnimatableProperty) {
        val clip = selectedClip ?: return
        val remaining = clip.tracks.filterNot { it.property == property }
        if (remaining.isEmpty()) {
            removeSelectedClip()
            return
        }
        replaceClip(clip.copy(tracks = remaining))
        selectedKeyframe = null
        notifyChanged()
    }

    /** 在给定时间插入关键帧，值取当前插值结果，保证插入后曲线形状不变。 */
    fun addKeyframe(property: AnimatableProperty, t: Float): Boolean {
        val clip = selectedClip ?: return false
        val track = clip.tracks.firstOrNull { it.property == property } ?: return false
        val clamped = t.coerceIn(0f, 1f)
        if (track.keyframes.any { kotlin.math.abs(it.t - clamped) < 0.02f }) return false

        val value = track.sample(clamped)
        val easing = track.keyframes
            .lastOrNull { it.t <= clamped }?.easing ?: Easing.Linear
        val updated = track.copy(keyframes = track.keyframes + Keyframe(clamped, value, easing))
        replaceTrack(clip, normalize(updated))
        val index = normalizedIndexOf(clip.id, property, clamped)
        selectedKeyframe = KeyframeRef(clip.id, property, index)
        notifyChanged()
        return true
    }

    fun removeKeyframe(ref: KeyframeRef): Boolean {
        val clip = interactions.animations.firstOrNull { it.id == ref.clipId } ?: return false
        val track = clip.tracks.firstOrNull { it.property == ref.property } ?: return false
        // 允许删到只剩 1 个关键帧（空轨道语义：只有起始值，不产生动画）。
        // 再删就没有任何时间信息了，拒绝。
        if (track.keyframes.size <= 1) return false
        if (ref.index !in track.keyframes.indices) return false

        val remaining = track.keyframes.filterIndexed { index, _ -> index != ref.index }
        replaceTrack(clip, normalize(track.copy(keyframes = remaining)))
        selectedKeyframe = null
        notifyChanged()
        return true
    }

    fun moveKeyframe(ref: KeyframeRef, newT: Float) {
        val clip = interactions.animations.firstOrNull { it.id == ref.clipId } ?: return
        val track = clip.tracks.firstOrNull { it.property == ref.property } ?: return
        if (ref.index !in track.keyframes.indices) return

        // 第一帧钉在 0（时间轴起点），其余帧被相邻帧夹住；末帧可以被拖到 1.0 之前。
        if (ref.index == 0) return

        val lower = track.keyframes[ref.index - 1].t + MIN_GAP
        val upper = if (ref.index == track.keyframes.lastIndex) {
            1f
        } else {
            track.keyframes[ref.index + 1].t - MIN_GAP
        }
        val clamped = newT.coerceIn(lower, upper.coerceAtLeast(lower))
        val updated = track.keyframes.toMutableList()
        updated[ref.index] = updated[ref.index].copy(t = clamped)
        replaceTrack(clip, track.copy(keyframes = updated))
        notifyChanged()
    }

    fun setKeyframeValue(ref: KeyframeRef, value: Float) {
        val clip = interactions.animations.firstOrNull { it.id == ref.clipId } ?: return
        val track = clip.tracks.firstOrNull { it.property == ref.property } ?: return
        if (ref.index !in track.keyframes.indices) return
        val updated = track.keyframes.toMutableList()
        updated[ref.index] = updated[ref.index].copy(t = track.keyframes[ref.index].t, value = clampValue(ref.property, value))
        replaceTrack(clip, track.copy(keyframes = updated))
        notifyChanged()
    }

    fun setKeyframeEasing(ref: KeyframeRef, easing: Easing) {
        val clip = interactions.animations.firstOrNull { it.id == ref.clipId } ?: return
        val track = clip.tracks.firstOrNull { it.property == ref.property } ?: return
        if (ref.index !in track.keyframes.indices) return
        val updated = track.keyframes.toMutableList()
        updated[ref.index] = updated[ref.index].copy(easing = easing)
        replaceTrack(clip, track.copy(keyframes = updated))
        notifyChanged()
    }

    fun setClipLoop(loop: LoopMode) {
        val clip = selectedClip ?: return
        replaceClip(clip.copy(tracks = clip.tracks.map { it.copy(loop = loop) }))
        notifyChanged()
    }

    fun setClipDuration(durationMs: Int) {
        val clip = selectedClip ?: return
        replaceClip(clip.copy(tracks = clip.tracks.map { it.copy(durationMs = durationMs.coerceAtLeast(120)) }))
        notifyChanged()
    }

    // ---- 内部 ----

    private fun replaceClip(updated: AnimationClip) {
        interactions = interactions.copy(
            animations = interactions.animations.map { if (it.id == updated.id) updated else it }
        )
    }

    private fun replaceTrack(clip: AnimationClip, track: AnimationTrack) {
        replaceClip(
            clip.copy(tracks = clip.tracks.map { if (it.property == track.property) track else it })
        )
    }

    private fun normalizedIndexOf(clipId: String, property: AnimatableProperty, t: Float): Int {
        val clip = interactions.animations.firstOrNull { it.id == clipId } ?: return 0
        val track = clip.tracks.firstOrNull { it.property == property } ?: return 0
        return track.keyframes.indexOfFirst { kotlin.math.abs(it.t - t) < 1e-4f }.coerceAtLeast(0)
    }

    /**
     * 只做两件事：按时间排序、去掉过近的重复点。
     *
     * 刻意**不再强制末帧 t=1.0**：专业编辑里“前 30% 移动、之后保持”是常见做法，
     * 强制末帧会逼用户为了合规再补一个重复值的帧。
     * 末帧之后的时间由 [AnimationTrack.sample] 自然保持末值。
     */
    private fun normalize(track: AnimationTrack): AnimationTrack {
        val sorted = track.keyframes.sortedBy { it.t }
        val result = ArrayList<Keyframe>(sorted.size)
        for (frame in sorted) {
            if (result.isEmpty() || frame.t - result.last().t >= MIN_GAP) {
                result += frame
            } else {
                result[result.lastIndex] = frame
            }
        }
        return track.copy(keyframes = result)
    }

    private fun baseValue(rect: RectNode, property: AnimatableProperty): Float = when (property) {
        AnimatableProperty.X -> rect.x
        AnimatableProperty.Y -> rect.y
        AnimatableProperty.SCALE -> rect.scale
        AnimatableProperty.ROTATION_Z -> rect.rotationZ
        AnimatableProperty.OPACITY -> rect.opacity
        AnimatableProperty.WIDTH -> rect.width
        AnimatableProperty.HEIGHT -> rect.height
    }

    private fun clampValue(property: AnimatableProperty, value: Float): Float = when (property) {
        AnimatableProperty.OPACITY -> value.coerceIn(0f, 1f)
        AnimatableProperty.SCALE -> value.coerceIn(0.05f, 10f)
        // 宽高不能为负：负尺寸会让三角化方向反转
        AnimatableProperty.WIDTH, AnimatableProperty.HEIGHT -> value.coerceAtLeast(0f)
        else -> value
    }

    private fun nextRectId(): String {
        var index = scene.root.children.size + 1
        while (scene.root.children.any { it.id == "rect_$index" }) index++
        return "rect_$index"
    }

    private fun nextClipId(targetId: String, property: AnimatableProperty): String {
        var index = interactions.animations.size + 1
        while (interactions.animations.any { it.id == "anim_${targetId}_${property.jsonName}_$index" }) index++
        return "anim_${targetId}_${property.jsonName}_$index"
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    private companion object {
        const val MIN_GAP = 0.02f
    }
}
