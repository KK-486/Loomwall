package com.loomwall.wallpaper.scene

import kotlin.math.cos
import kotlin.math.sin

/**
 * 视差（陀螺仪/重力驱动的分层偏移）。
 *
 * 语义与网页编辑器完全一致 —— 这是刻意的：编辑器里调好的景深/角度参数，
 * 到手机上必须原样生效，否则"所见即所得"就是假的。
 *
 * 两级结构：
 * - [ParallaxSettings] 全局：总开关 + 统一景深 + 振幅 + 平滑 + 死区（存在 manifest.parallax）
 * - [NodeParallax]    单层：模式（跟随/独立）+ 自己的景深/角度/振幅
 *
 * 规则：
 * - 跟随全局（默认）：是否生效、景深、振幅全取全局；方向恒 0°（同向）
 * - 独立：用自己的景深/振幅/角度；本层要"不动"就把景深或振幅调 0
 */
data class ParallaxSettings(
    val enabled: Boolean = true,
    val depth: Float = 40f,
    val amp: Float = 4.5f,
    val smoothing: Float = 0.18f,
    val deadzone: Float = 0.04f
) {
    companion object {
        val DEFAULT = ParallaxSettings()

        fun fromJson(json: String?): ParallaxSettings {
            if (json.isNullOrBlank()) return DEFAULT
            return runCatching {
                val o = org.json.JSONObject(json)
                ParallaxSettings(
                    enabled = if (o.has("enabled")) o.optBoolean("enabled", true) else DEFAULT.enabled,
                    depth = o.optDouble("depth", DEFAULT.depth.toDouble()).toFloat().coerceIn(0f, 100f),
                    amp = o.optDouble("amp", DEFAULT.amp.toDouble()).toFloat().coerceIn(0f, 12f),
                    smoothing = o.optDouble("smoothing", DEFAULT.smoothing.toDouble()).toFloat().coerceIn(0.02f, 1f),
                    deadzone = o.optDouble("deadzone", DEFAULT.deadzone.toDouble()).toFloat().coerceIn(0f, 0.5f)
                )
            }.getOrDefault(DEFAULT)
        }
    }
}

/** 单个节点的视差参数（只写进 JSON 的字段，缺省即跟随全局）。 */
data class NodeParallax(
    val custom: Boolean = false,
    val depth: Float = 0f,
    val amp: Float? = null,
    val angleDeg: Float = 0f,
    val enabled: Boolean = true
) {
    companion object {
        /** 从节点 JSON 里读；完全没有视差字段时返回 null（零开销路径）。 */
        fun fromJson(o: org.json.JSONObject): NodeParallax? {
            if (!o.has("parallax") && !o.has("parallaxAngle") && !o.has("parallaxMode")) return null
            val mode = o.optString("parallaxMode", "global")
            return NodeParallax(
                custom = mode == "custom",
                depth = o.optDouble("parallax", 0.0).toFloat().coerceIn(0f, 100f),
                amp = if (o.has("parallaxAmp")) o.optDouble("parallaxAmp", 0.0).toFloat().coerceIn(0f, 12f) else null,
                angleDeg = o.optDouble("parallaxAngle", 0.0).toFloat().let { ((it % 360f) + 360f) % 360f },
                enabled = if (o.has("parallaxEnabled")) o.optBoolean("parallaxEnabled", true) else true
            )
        }
    }
}

/** 解析后的单层生效参数。 */
data class ResolvedParallax(
    val enabled: Boolean,
    val depth: Float,
    val amp: Float,
    val angleDeg: Float
) {
    /** 这一层本帧的位移（画布单位）。tiltX/tiltY 已归一化到 -1..1。 */
    fun offsetUnits(tiltX: Float, tiltY: Float): FloatArray {
        if (!enabled || depth <= 0f || amp <= 0f) return ZERO
        val k = amp * depth / 100f
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        return floatArrayOf((tiltX * c - tiltY * s) * k, (tiltX * s + tiltY * c) * k)
    }

    companion object {
        private val ZERO = floatArrayOf(0f, 0f)
    }
}

/** 按"跟随全局 / 独立"规则算出某节点本帧的生效参数。 */
fun resolveParallax(node: NodeParallax?, settings: ParallaxSettings): ResolvedParallax {
    if (node == null) {
        // 没有任何视差字段的节点：跟全局（这正是"其它默认跟随全局"）
        return ResolvedParallax(settings.enabled, settings.depth, settings.amp, 0f)
    }
    return if (node.custom) {
        ResolvedParallax(
            enabled = node.enabled && node.depth > 0f,
            depth = node.depth,
            amp = node.amp ?: settings.amp,
            angleDeg = node.angleDeg
        )
    } else {
        ResolvedParallax(settings.enabled, settings.depth, settings.amp, 0f)
    }
}
