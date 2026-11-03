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
        var width: Float? = null
        var height: Float? = null

        fun set(property: AnimatableProperty, value: Float) {
            when (property) {
                AnimatableProperty.X -> x = value
                AnimatableProperty.Y -> y = value
                AnimatableProperty.SCALE -> scale = value
                AnimatableProperty.ROTATION_Z -> rotationZ = value
                AnimatableProperty.OPACITY -> opacity = value.coerceIn(0f, 1f)
                AnimatableProperty.WIDTH -> width = value.coerceAtLeast(0f)
                AnimatableProperty.HEIGHT -> height = value.coerceAtLeast(0f)
            }
        }

        fun snapshot(): NodeOverrides = NodeOverrides(
            x = x, y = y, scale = scale, rotationZ = rotationZ, opacity = opacity,
            width = width, height = height
        )
    }

    fun evaluate(bindings: List<Binding>, vars: Map<String, Float>): Map<String, NodeOverrides> {
        if (bindings.isEmpty() || vars.isEmpty()) return emptyMap()

        val result = HashMap<String, Accumulator>()
        for (binding in bindings) {
            val value = if (!binding.expr.isNullOrBlank()) {
                // 表达式绑定：不依赖 source，直接把整条公式算出来
                evalExpr(binding.expr, vars) ?: continue
            } else {
                val raw = vars[binding.source] ?: continue
                binding.map(raw)
            }
            result.getOrPut(binding.targetId) { Accumulator() }.set(binding.property, value)
        }
        if (result.isEmpty()) return emptyMap()
        return result.mapValues { it.value.snapshot() }
    }

    /**
     * 求值一条绑定表达式。
     *
     * 每帧都会调用，所以这里**不做缓存**（表达式很短，解析开销可忽略），
     * 但解析错误必须吞掉并返回 null：一个写错的表达式不应该让整张壁纸停止渲染。
     * 加载期已经用 [com.loomwall.wallpaper.scene.BindingExpr.validate] 拦过一遍。
     */
    private fun evalExpr(expr: String, vars: Map<String, Float>): Float? = runCatching {
        val expanded = com.loomwall.wallpaper.scene.BindingExpr.expand(expr)
        val parsed = com.loomwall.wallpaper.scene.ExprParser.parse(expanded)
        val ctx = com.loomwall.wallpaper.scene.ExprContext(numberVar = { name -> vars[name] })
        when (val value = com.loomwall.wallpaper.scene.ExprParser.evaluate(parsed, ctx)) {
            is com.loomwall.wallpaper.scene.ExprValue.Num -> value.value
            else -> null
        }
    }.getOrNull()

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
                opacity = anim.opacity ?: base.opacity,
                // 宽高必须一起合并：漏掉就会"动画改了宽度，一叠加绑定就跳回原尺寸"
                width = anim.width ?: base.width,
                height = anim.height ?: base.height
            )
        }
        return merged
    }
}
