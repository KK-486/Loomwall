package com.loomwall.wallpaper.scene

/**
 * 渐变描述解析。
 *
 * 语法：`类型:角度:位置#颜色;位置#颜色`
 * 例：`linear:90:0#FF3366FF;1#FF0B0D10`
 *
 * 单独抽成纯函数是为了能单测：解析错一个字符，渲染出来就是一片纯色，
 * 这种问题在截图里极难定位。
 */
object GradientSpec {

    enum class Type { LINEAR, RADIAL }

    data class Stop(val position: Float, val color: RgbaColor)

    data class Spec(val type: Type, val angleDeg: Float, val stops: List<Stop>)

    fun parse(raw: String): Spec? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val parts = text.split(':', limit = 3)
        if (parts.size < 3) return null

        val type = when (parts[0].trim().lowercase()) {
            "linear" -> Type.LINEAR
            "radial" -> Type.RADIAL
            else -> return null
        }
        val angle = parts[1].trim().toFloatOrNull() ?: 0f

        val stops = ArrayList<Stop>()
        for (chunk in parts[2].split(';')) {
            val item = chunk.trim()
            if (item.isEmpty()) continue
            val hash = item.indexOf('#')
            if (hash <= 0) continue
            val position = item.substring(0, hash).trim().toFloatOrNull() ?: continue
            val color = parseColor(item.substring(hash)) ?: continue
            stops.add(Stop(position.coerceIn(0f, 1f), color))
        }
        if (stops.size < 2) return null

        return Spec(type, angle, stops.sortedBy { it.position })
    }

    fun encode(type: Type, angleDeg: Float, stops: List<Stop>): String {
        val typeName = if (type == Type.LINEAR) "linear" else "radial"
        val body = stops.joinToString(";") { stop ->
            "${round2(stop.position)}${toHex(stop.color)}"
        }
        return "$typeName:$angleDeg:$body"
    }

    /** `#AARRGGBB` / `#RRGGBB` → 颜色。 */
    private fun parseColor(text: String): RgbaColor? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 8) {
            val a = ((value shr 24) and 0xFF).toInt()
            val r = ((value shr 16) and 0xFF).toInt()
            val g = ((value shr 8) and 0xFF).toInt()
            val b = (value and 0xFF).toInt()
            RgbaColor(r / 255f, g / 255f, b / 255f, a / 255f)
        } else {
            val r = ((value shr 16) and 0xFF).toInt()
            val g = ((value shr 8) and 0xFF).toInt()
            val b = (value and 0xFF).toInt()
            RgbaColor(r / 255f, g / 255f, b / 255f, 1f)
        }
    }

    private fun toHex(color: RgbaColor): String {
        fun part(v: Float): String =
            (v * 255f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
        return "#" + part(color.a) + part(color.r) + part(color.g) + part(color.b)
    }

    private fun round2(v: Float): String {
        val rounded = Math.round(v * 100f) / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }
}
