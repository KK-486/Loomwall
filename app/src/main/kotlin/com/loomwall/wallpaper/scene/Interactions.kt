package com.loomwall.wallpaper.scene

/**
 * `interactions.json` 的数据模型（格式 v0.1，阶段 2a 只做动画）。
 *
 * 统一的表达模型（来自项目约定）：**事件源 → 条件/修饰 → 动作**。
 * 阶段 2 允许的事件源只有「触摸点击」；滚动偏移、传感器、广播分别属于阶段 3/4，
 * 这里把 Trigger 设计成密封类型，后续加分支即可，不需要改已有结构。
 */
data class Interactions(
    val formatVersion: String,
    val animations: List<AnimationClip>,
    /** 系统变量绑定（阶段 4）：把电量/内存/时间等映射到属性上。 */
    val bindings: List<Binding> = emptyList(),
    /** 触摸事件：手势 → 动作。 */
    val events: List<NodeEvent> = emptyList(),
    /**
     * 触发器模型（格式 0.3）：变量 + 规则 + 函数。
     *
     * 与 [events] 并存的理由：events 是"点击→动作"的直线通道（2a 时的最小实现），
     * rules 需要**条件**和**变量状态**才能表达"点一下开、再点一下关"这类逻辑。
     * 旧预设继续走 events，新预设走 rules，引擎两条都认。
     */
    val triggers: TriggerModel = TriggerModel.EMPTY
) {
    companion object {
        val EMPTY = Interactions(EngineVersion.INTERACTIONS_FORMAT, emptyList())
    }
}

/** 一条动画：作用于某个元素，可含多条并行轨道。 */
data class AnimationClip(
    val id: String,
    val targetId: String,
    val trigger: Trigger,
    val tracks: List<AnimationTrack>
)

/**
 * 触发方式。`type` 决定动画**什么时候开始跑**，与动画内容无关：
 * - [Type.ALWAYS]：常驻。壁纸可见时自动开始（呼吸、漂浮、缓慢旋转这类基本盘）。
 * - [Type.TAP]：点击命中 [targetId] 时开始。
 *
 * 阶段 2 只有这两种；滚动偏移、系统状态分别属于阶段 3/4，加分支即可。
 */
data class Trigger(
    val type: Type,
    /** 需要命中的元素；为空表示命中动画自身的 targetId（仅 TAP 有意义）。 */
    val targetId: String? = null
) {
    enum class Type(val jsonName: String) {
        ALWAYS("always"),
        TAP("tap"),

        /**
         * 函数触发：既不自动播、也不被点击触发，只有规则/函数里 `playAnimation` 点到才播。
         *
         * 为什么需要它：TAP 触发器同时意味着"点这个图层会播"，
         * 于是"点 A 播 B 的动画"没法表达；FUNC 让动画只是一段可被调用的片段。
         */
        FUNC("func");

        companion object {
            fun fromJson(name: String): Type? = entries.firstOrNull { it.jsonName == name }
        }
    }
}

/** 可动画属性。新增属性必须同步 schema、解析器与渲染器。 */
enum class AnimatableProperty(val jsonName: String) {
    X("x"),
    Y("y"),
    SCALE("scale"),
    ROTATION_Z("rotationZ"),
    OPACITY("opacity");

    companion object {
        fun fromJson(name: String): AnimatableProperty? = entries.firstOrNull { it.jsonName == name }
    }
}

enum class LoopMode(val jsonName: String) {
    ONCE("once"),
    LOOP("loop"),
    PINGPONG("pingpong"),

    /**
     * 播放一次并停在结尾。
     *
     * ONCE 播完会撤掉覆盖值、元素回到静态值（"弹一下回原位"）；
     * 但"展开后保持展开"必须停在尾帧 —— 所以要留一份定格值，
     * 同时不能再逐帧重绘（功耗红线），实现上是"存定格值 + 摘掉动画"。
     */
    ONCE_HOLD("onceHold");

    companion object {
        fun fromJson(name: String): LoopMode? = entries.firstOrNull { it.jsonName == name }
    }
}

data class Keyframe(
    /** 归一化时间 0..1，必须递增，最后一段应到 1.0。 */
    val t: Float,
    val value: Float,
    /** 本关键帧到下一帧之间的缓动。 */
    val easing: Easing
)

data class AnimationTrack(
    val property: AnimatableProperty,
    val keyframes: List<Keyframe>,
    val durationMs: Int,
    val loop: LoopMode
) {
    /**
     * 采样：t 已经是处理过循环后的归一化时间。
     * 保证返回值连续、单调可控（值域由关键帧决定，不做额外裁剪——x/y 允许超出画布）。
     */
    fun sample(t: Float): Float {
        if (keyframes.isEmpty()) return 0f
        if (keyframes.size == 1) return keyframes.first().value

        val clamped = t.coerceIn(0f, 1f)
        if (clamped <= keyframes.first().t) return keyframes.first().value
        if (clamped >= keyframes.last().t) return keyframes.last().value

        for (index in 0 until keyframes.size - 1) {
            val current = keyframes[index]
            val next = keyframes[index + 1]
            if (clamped <= next.t) {
                val span = (next.t - current.t).takeIf { it > 0f } ?: return next.value
                val local = (clamped - current.t) / span
                val eased = current.easing.apply(local)
                return current.value + (next.value - current.value) * eased
            }
        }
        return keyframes.last().value
    }
}

/** 某一帧内某个元素需要覆盖的属性；未覆盖的字段为 null，表示沿用场景里的静态值。 */
data class NodeOverrides(
    val x: Float? = null,
    val y: Float? = null,
    val scale: Float? = null,
    val rotationZ: Float? = null,
    val opacity: Float? = null
)
