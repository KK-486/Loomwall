package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.AnimatableProperty
import com.loomwall.wallpaper.scene.Binding
import com.loomwall.wallpaper.scene.NodeOverrides

/**
 * 把「系统变量快照 + 绑定表」换算成一帧的属性覆盖值。
 *
 * 纯函数、无 GL、无 Android 依赖 —— 所以能直接在单元测试里验算。
 * 变量缺失（例如设备读不到）时**跳过该绑定**，元素保持静态值，不会突然跳成 0。
 */
internal object BindingEvaluator {

    private class Accumulator {
        var x: Float? = null
        var y: Float? = null
        var scale: Float? = null
        var rotationZ: Float? = null
        var opacity: Float? = null

        fun set(property: AnimatableProperty, value: Float) {
            when (property) {
                AnimatableProperty.X -> x = value
                AnimatableProperty.Y -> y = value
                AnimatableProperty.SCALE -> scale = value
                AnimatableProperty.ROTATION_Z -> rotationZ = value
                AnimatableProperty.OPACITY -> opacity = value.coerceIn(0f, 1f)
            }
        }

        fun snapshot(): NodeOverrides = NodeOverrides(x, y, scale, rotationZ, opacity)
    }

    fun evaluate(bindings: List<Binding>, vars: Map<String, Float>): Map<String, NodeOverrides> {
        if (bindings.isEmpty() || vars.isEmpty()) return emptyMap()

        val result = HashMap<String, Accumulator>()
        for (binding in bindings) {
            val raw = vars[binding.source] ?: continue
            val value = binding.map(raw)
            result.getOrPut(binding.targetId) { Accumulator() }.set(binding.property, value)
        }
        if (result.isEmpty()) return emptyMap()
        return result.mapValues { it.value.snapshot() }
    }

    /** 合并动画与绑定的覆盖值；**动画优先**（显式编排的动效应该压过环境驱动）。 */
    fun merge(
        animation: Map<String, NodeOverrides>,
        bindings: Map<String, NodeOverrides>
    ): Map<String, NodeOverrides> {
        if (bindings.isEmpty()) return animation
        if (animation.isEmpty()) return bindings

        val merged = HashMap<String, NodeOverrides>(bindings)
        for ((id, anim) in animation) {
            val base = merged[id]
            merged[id] = if (base == null) anim else NodeOverrides(
                x = anim.x ?: base.x,
                y = anim.y ?: base.y,
                scale = anim.scale ?: base.scale,
                rotationZ = anim.rotationZ ?: base.rotationZ,
                opacity = anim.opacity ?: base.opacity
            )
        }
        return merged
    }
}
