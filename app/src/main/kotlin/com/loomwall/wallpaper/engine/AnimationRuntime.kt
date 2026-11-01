package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.AnimationClip
import com.loomwall.wallpaper.scene.AnimatableProperty
import com.loomwall.wallpaper.scene.Interactions
import com.loomwall.wallpaper.scene.LoopMode
import com.loomwall.wallpaper.scene.NodeOverrides
import com.loomwall.wallpaper.scene.Trigger
import kotlin.math.floor

/**
 * 动画运行时（阶段 2a）。
 *
 * 只做一件事：把「时间 + 一条动画」换算成「这一帧各元素该用哪些属性值」。
 * 它是纯函数式的：给定 nowMs，输出确定的覆盖值，不持有 GL、不碰 UI、不自己计时。
 *
 * 阶段 2a 的语义（刻意简化，2c 再扩展）：
 * - 触发 = 点击命中元素；
 * - 同一条动画被再次触发 → **覆盖**（从头重播），不排队、不叠加；
 * - `once` 播完自动移除，元素回到场景里的静态值；
 * - `loop` / `pingpong` 会一直跑，直到进程不可见（由 RenderThread 停止绘制）或被重新触发。
 */
internal class AnimationRuntime(private val interactions: Interactions) {

    private class Running(val clip: AnimationClip, val startMs: Long, val reverse: Boolean = false)

    private class Mutable {
        var x: Float? = null
        var y: Float? = null
        var scale: Float? = null
        var rotationZ: Float? = null
        var opacity: Float? = null

        fun snapshot(): NodeOverrides = NodeOverrides(x, y, scale, rotationZ, opacity)
    }

    private val running = LinkedHashMap<String, Running>()

  /**
   * 定格值：onceHold 播完后把尾帧值留在这里。
   *
   * 单独放一张表而不是留在 running 里，是为了让 isAnimating() 立刻变 false ——
   * 否则"停下来"也还在每帧重绘，直接违反功耗红线。
   */
  private val held = HashMap<String, NodeOverrides>()
    private var alwaysStarted = false

    val hasClips: Boolean
        get() = interactions.animations.isNotEmpty()

    /**
     * 常驻动画（trigger=always）在壁纸可见时自动开始。
     * 只在第一次调用时真正启动，避免每帧重复触发把动画反复重置。
     */
    fun ensureAlwaysStarted(nowMs: Long): Boolean {
        if (alwaysStarted) return false
        alwaysStarted = true
        var started = false
        for (clip in interactions.animations) {
            if (clip.trigger.type != Trigger.Type.ALWAYS) continue
            running[clip.id] = Running(clip, nowMs)
            started = true
        }
        return started
    }

    /**
     * 点击命中某个元素时调用。返回是否有动画被触发。
     * 触发目标取 trigger.targetId（缺省为动画自身的 targetId），与命中元素比较。
     */
    /** 按动画 id 直接播放（触摸事件用）。找不到返回 false。 */
    fun play(clipId: String, nowMs: Long, reverse: Boolean = false): Boolean {
        val clip = interactions.animations.firstOrNull { it.id == clipId } ?: return false
        held.remove(clip.id)
        running[clip.id] = Running(clip, nowMs, reverse)
        return true
    }
    fun onTap(hitId: String, nowMs: Long): Boolean {
        var started = false
        for (clip in interactions.animations) {
            if (clip.trigger.type != Trigger.Type.TAP) continue
            val wants = clip.trigger.targetId ?: clip.targetId
            if (wants != hitId) continue
            running[clip.id] = Running(clip, nowMs)
            started = true
        }
        return started
    }

    fun isAnimating(): Boolean = running.isNotEmpty()

    fun stopAll() {
        running.clear()
        // 定格值也要清：重新可见时应该是"动画从头开始"，而不是接着上次的定格画面
        held.clear()
        alwaysStarted = false
    }

    /** 计算 nowMs 这一帧的覆盖值；同时清理已经播完的 once 动画。 */
    fun evaluate(nowMs: Long): Map<String, NodeOverrides> {
        if (running.isEmpty()) return heldByTarget()

        val collected = HashMap<String, Mutable>()
        val finished = ArrayList<String>(2)

        for ((clipId, run) in running) {
            var stillRunning = false
            var holdNext = false
            for (track in run.clip.tracks) {
                // 空轨道（还没有关键帧）不参与求值：元素保持静态值。
                if (track.keyframes.isEmpty()) continue
                val raw = (nowMs - run.startMs).toFloat() / track.durationMs
                // 反向播放：时间轴从尾到头走；循环与结束判定仍按 raw 计算
                val phase = if (run.reverse) 1f - raw else raw
                val t = loopedTime(phase, track.loop)
                val oneShot = track.loop == LoopMode.ONCE || track.loop == LoopMode.ONCE_HOLD
                val finishedNow = oneShot && raw >= 1f
                if (!finishedNow) stillRunning = true
                if (finishedNow && track.loop == LoopMode.ONCE_HOLD) holdNext = true

                val value = track.sample(t)
                val target = collected.getOrPut(run.clip.targetId) { Mutable() }
                when (track.property) {
                    AnimatableProperty.X -> target.x = value
                    AnimatableProperty.Y -> target.y = value
                    AnimatableProperty.SCALE -> target.scale = value
                    AnimatableProperty.ROTATION_Z -> target.rotationZ = value
                    AnimatableProperty.OPACITY -> target.opacity = value.coerceIn(0f, 1f)
                }
            }
            if (!stillRunning) {
                // onceHold：把尾帧的覆盖值定格下来，动画本身摘掉（不再逐帧重绘）
                if (holdNext) collected[run.clip.targetId]?.let { held[clipId] = it.snapshot() }
                finished += clipId
            }
        }

        for (clipId in finished) running.remove(clipId)

        // 定格值（按目标图层归并）+ 本帧动画值（覆盖同名字段）
        val out = heldByTarget().toMutableMap()
        collected.forEach { (targetId, mutable) ->
            out[targetId] = mergeOverrides(out[targetId], mutable.snapshot())
        }
        return out
    }

    /** 把"键是 clipId"的定格值折成"键是 targetId"的覆盖表，同图层多组时逐字段合并。 */
    private fun heldByTarget(): Map<String, NodeOverrides> {
        if (held.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, NodeOverrides>()
        held.forEach { (clipId, overrides) ->
            val targetId = interactions.animations.firstOrNull { it.id == clipId }?.targetId ?: return@forEach
            out[targetId] = mergeOverrides(out[targetId], overrides)
        }
        return out
    }

    /** 后者覆盖前者的非空字段。 */
    private fun mergeOverrides(base: NodeOverrides?, add: NodeOverrides): NodeOverrides {
        if (base == null) return add
        return NodeOverrides(
            x = add.x ?: base.x,
            y = add.y ?: base.y,
            scale = add.scale ?: base.scale,
            rotationZ = add.rotationZ ?: base.rotationZ,
            opacity = add.opacity ?: base.opacity
        )
    }

    /** 把线性进度换算成循环后的归一化时间。 */
    private fun loopedTime(phase: Float, loop: LoopMode): Float = when (loop) {
        LoopMode.ONCE, LoopMode.ONCE_HOLD -> phase.coerceIn(0f, 1f)
        LoopMode.LOOP -> phase - floor(phase)
        LoopMode.PINGPONG -> {
            // 0→1→0→1…：把进度压到 0..2 的三角波
            val cycle = phase - 2f * floor(phase / 2f)
            if (cycle <= 1f) cycle else 2f - cycle
        }
    }
}
