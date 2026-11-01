package com.loomwall.wallpaper.scene

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SVG `path` 的 `d` 属性解析器。
 *
 * 目标：把路径变成**若干条折线轮廓**（扁平 FloatArray，单位已归一化到包围盒 0..1），
 * 这样渲染端和命中测试都只需要处理多边形，不用理解贝塞尔曲线；而编辑器侧直接用
 * 浏览器原生的 Path2D 画同一条 `d`，两边视觉一致。
 *
 * 刻意不做的部分：
 * - 不做完整的 SVG 实现（不处理 transform / 渐变 / 滤镜 / 文字）；
 * - 圆弧 A 用折线近似（标准做法），不追求数学精确；
 * - 填充规则固定 even-odd（SVG 默认 nonzero，但图标里两者结果通常一致，even-odd 更稳）。
 */
object SvgPathData {

    /** 每条曲线压平成多少段：16 段在手机分辨率下已经看不出折线。 */
    private const val CURVE_SEGMENTS = 16

    /**
     * 解析结果。
     *
     * @param contours 每条轮廓的点序列（偶数下标是 x，奇数下标是 y），坐标已归一化到 0..1
     * @param closed 与 contours 对应的闭合标记（Z 结尾为 true）
     */
    class Parsed(val contours: List<FloatArray>, val closed: List<Boolean>) {
        val isEmpty: Boolean get() = contours.isEmpty()
    }

    /**
     * @param d SVG path 的 d 属性
     * @param viewWidth viewBox 宽（归一化基准）
     * @param viewHeight viewBox 高
     */
    fun parse(d: String, viewWidth: Float = 24f, viewHeight: Float = 24f): Parsed {
        val sx = if (viewWidth > 0f) 1f / viewWidth else 1f
        val sy = if (viewHeight > 0f) 1f / viewHeight else 1f

        val contours = ArrayList<FloatArray>()
        val closedFlags = ArrayList<Boolean>()

        var current = ArrayList<Float>(64)
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var lastCommand = ' '
        // 上一个控制点（S/T 的反射用）
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var lastQuadX = 0f
        var lastQuadY = 0f

        fun flush(close: Boolean) {
            if (current.size >= 6) { // 至少 3 个点才算轮廓
                contours.add(current.toFloatArray())
                closedFlags.add(close)
            }
            current = ArrayList(64)
        }

        fun addPoint(x: Float, y: Float) {
            current.add(x * sx)
            current.add(y * sy)
        }

        val scanner = Scanner(d)
        while (scanner.hasMore()) {
            val cmd = scanner.nextCommand(lastCommand)
            val rel = cmd.isLowerCase()
            val upper = cmd.uppercaseChar()
            lastCommand = cmd

            when (upper) {
                'M' -> {
                    val x = scanner.number() + if (rel) cx else 0f
                    val y = scanner.number() + if (rel) cy else 0f
                    flush(false)
                    addPoint(x, y)
                    cx = x
                    cy = y
                    startX = x
                    startY = y
                    // M 之后的隐式坐标按 L 处理
                    while (scanner.peekIsNumber()) {
                        val lx = scanner.number() + if (rel) cx else 0f
                        val ly = scanner.number() + if (rel) cy else 0f
                        addPoint(lx, ly)
                        cx = lx
                        cy = ly
                    }
                }

                'L' -> {
                    do {
                        val x = scanner.number() + if (rel) cx else 0f
                        val y = scanner.number() + if (rel) cy else 0f
                        addPoint(x, y)
                        cx = x
                        cy = y
                    } while (scanner.peekIsNumber())
                }

                'H' -> {
                    do {
                        val x = scanner.number() + if (rel) cx else 0f
                        addPoint(x, cy)
                        cx = x
                    } while (scanner.peekIsNumber())
                }

                'V' -> {
                    do {
                        val y = scanner.number() + if (rel) cy else 0f
                        addPoint(cx, y)
                        cy = y
                    } while (scanner.peekIsNumber())
                }

                'C' -> {
                    do {
                        val x1 = scanner.number() + if (rel) cx else 0f
                        val y1 = scanner.number() + if (rel) cy else 0f
                        val x2 = scanner.number() + if (rel) cx else 0f
                        val y2 = scanner.number() + if (rel) cy else 0f
                        val x = scanner.number() + if (rel) cx else 0f
                        val y = scanner.number() + if (rel) cy else 0f
                        cubicTo(cx, cy, x1, y1, x2, y2, x, y) { px, py -> addPoint(px, py) }
                        lastCtrlX = x2
                        lastCtrlY = y2
                        cx = x
                        cy = y
                    } while (scanner.peekIsNumber())
                }

                'S' -> {
                    do {
                        // 第一个控制点 = 上一个控制点关于当前点的反射
                        val reflectX = 2 * cx - lastCtrlX
                        val reflectY = 2 * cy - lastCtrlY
                        val x2 = scanner.number() + if (rel) cx else 0f
                        val y2 = scanner.number() + if (rel) cy else 0f
                        val x = scanner.number() + if (rel) cx else 0f
                        val y = scanner.number() + if (rel) cy else 0f
                        cubicTo(cx, cy, reflectX, reflectY, x2, y2, x, y) { px, py -> addPoint(px, py) }
                        lastCtrlX = x2
                        lastCtrlY = y2
                        cx = x
                        cy = y
                    } while (scanner.peekIsNumber())
                }

                'Q' -> {
                    do {
                        val qx = scanner.number() + if (rel) cx else 0f
                        val qy = scanner.number() + if (rel) cy else 0f
                        val x = scanner.number() + if (rel) cx else 0f
                        val y = scanner.number() + if (rel) cy else 0f
                        quadTo(cx, cy, qx, qy, x, y) { px, py -> addPoint(px, py) }
                        lastQuadX = qx
                        lastQuadY = qy
                        cx = x
                        cy = y
                    } while (scanner.peekIsNumber())
                }

                'T' -> {
                    do {
                        val reflectX = 2 * cx - lastQuadX
                        val reflectY = 2 * cy - lastQuadY
                        val x = scanner.number() + if (rel) cx else 0f
                        val y = scanner.number() + if (rel) cy else 0f
                        quadTo(cx, cy, reflectX, reflectY, x, y) { px, py -> addPoint(px, py) }
                        lastQuadX = reflectX
                        lastQuadY = reflectY
                        cx = x
                        cy = y
                    } while (scanner.peekIsNumber())
                }

                'A' -> {
                    do {
                        val rx = scanner.number()
                        val ry = scanner.number()
                        val rotation = scanner.number()
                        val largeArc = scanner.flag()
                        val sweep = scanner.flag()
                        val x = scanner.number() + if (rel) cx else 0f
                        val y = scanner.number() + if (rel) cy else 0f
                        arcTo(cx, cy, rx, ry, rotation, largeArc, sweep, x, y) { px, py -> addPoint(px, py) }
                        cx = x
                        cy = y
                    } while (scanner.peekIsNumber())
                }

                'Z' -> {
                    flush(true)
                    cx = startX
                    cy = startY
                }
            }
        }
        flush(false)

        return Parsed(contours, closedFlags)
    }

    // ---- 曲线压平 ----

    private inline fun cubicTo(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        emit: (Float, Float) -> Unit
    ) {
        for (i in 1..CURVE_SEGMENTS) {
            val t = i.toFloat() / CURVE_SEGMENTS
            val mt = 1 - t
            val a = mt * mt * mt
            val b = 3 * mt * mt * t
            val c = 3 * mt * t * t
            val d = t * t * t
            emit(
                a * x0 + b * x1 + c * x2 + d * x3,
                a * y0 + b * y1 + c * y2 + d * y3
            )
        }
    }

    private inline fun quadTo(
        x0: Float, y0: Float,
        qx: Float, qy: Float,
        x1: Float, y1: Float,
        emit: (Float, Float) -> Unit
    ) {
        for (i in 1..CURVE_SEGMENTS) {
            val t = i.toFloat() / CURVE_SEGMENTS
            val mt = 1 - t
            val a = mt * mt
            val b = 2 * mt * t
            val c = t * t
            emit(a * x0 + b * qx + c * x1, a * y0 + b * qy + c * y1)
        }
    }

    /**
     * 圆弧近似：按 SVG 规范换算成圆心参数后采样。
     * 半径非法（0）时退化成直线。
     */
    private fun arcTo(
        x0: Float, y0: Float,
        rxIn: Float, ryIn: Float,
        rotationDeg: Float,
        largeArc: Boolean,
        sweep: Boolean,
        x1: Float, y1: Float,
        emit: (Float, Float) -> Unit
    ) {
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx == 0f || ry == 0f) {
            emit(x1, y1)
            return
        }

        val phi = Math.toRadians(rotationDeg.toDouble())
        val cosPhi = cos(phi).toFloat()
        val sinPhi = sin(phi).toFloat()

        val dx2 = (x0 - x1) / 2f
        val dy2 = (y0 - y1) / 2f
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        // 半径过小则按规范放大
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1f) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }

        val sign = if (largeArc != sweep) 1f else -1f
        val numerator = max(0f, rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p)
        val denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val coefficient = if (denominator == 0f) 0f else sign * sqrt(numerator / denominator)
        val cxp = coefficient * (rx * y1p / ry)
        val cyp = coefficient * (-ry * x1p / rx)
        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2f
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2f

        fun angle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
            val dot = ux * vx + uy * vy
            val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            if (len == 0f) return 0f
            var a = Math.toDegrees(kotlin.math.acos((dot / len).coerceIn(-1f, 1f)).toDouble()).toFloat()
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }

        val theta1 = angle(1f, 0f, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 360f
        if (sweep && delta < 0) delta += 360f

        val steps = ceil(abs(delta) / 15.0).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val a = Math.toRadians((theta1 + delta * i / steps).toDouble())
            val px = cx + rx * cos(a).toFloat() * cosPhi - ry * sin(a).toFloat() * sinPhi
            val py = cy + rx * cos(a).toFloat() * sinPhi + ry * sin(a).toFloat() * cosPhi
            emit(px, py)
        }
    }

    // ---- 命中测试 ----

    /**
     * 点是否落在路径内（even-odd 射线法，对多条轮廓一起算，天然支持镂空）。
     *
     * @param contours 已归一化到 0..1 的轮廓
     * @param x,y 同样是 0..1 的局部坐标
     */
    fun contains(contours: List<FloatArray>, x: Float, y: Float): Boolean {
        var inside = false
        for (points in contours) {
            val n = points.size / 2
            if (n < 3) continue
            var j = n - 1
            for (i in 0 until n) {
                val xi = points[i * 2]
                val yi = points[i * 2 + 1]
                val xj = points[j * 2]
                val yj = points[j * 2 + 1]
                if ((yi > y) != (yj > y) &&
                    x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi
                ) {
                    inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    /** 极简词法：数字、命令字母、逗号分隔，容忍任意空白与紧凑写法。 */
    private class Scanner(private val text: String) {
        private var pos = 0

        fun hasMore(): Boolean {
            skipSeparators()
            return pos < text.length
        }

        fun peekIsNumber(): Boolean {
            skipSeparators()
            if (pos >= text.length) return false
            val c = text[pos]
            return c.isDigit() || c == '-' || c == '+' || c == '.'
        }

        fun nextCommand(previous: Char): Char {
            skipSeparators()
            if (pos >= text.length) return previous
            val c = text[pos]
            if (c.isLetter()) {
                pos++
                return c
            }
            // 隐式重复上一个命令
            return if (previous == ' ') 'M' else previous
        }

        fun flag(): Boolean {
            skipSeparators()
            if (pos >= text.length) return false
            val c = text[pos]
            pos++
            return c == '1'
        }

        fun number(): Float {
            skipSeparators()
            val start = pos
            if (pos < text.length && (text[pos] == '-' || text[pos] == '+')) pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                val save = pos
                pos++
                if (pos < text.length && (text[pos] == '-' || text[pos] == '+')) pos++
                var digits = 0
                while (pos < text.length && text[pos].isDigit()) {
                    pos++
                    digits++
                }
                if (digits == 0) pos = save
            }
            val slice = text.substring(start, pos)
            return slice.toFloatOrNull() ?: 0f
        }

        private fun skipSeparators() {
            while (pos < text.length) {
                val c = text[pos]
                if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }
    }

    /** 供渲染端取包围盒（归一化后恒为 0..1，这里只做校验用）。 */
    fun bounds(contours: List<FloatArray>): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (points in contours) {
            for (i in points.indices step 2) {
                val x = points[i]
                val y = points[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (minX > maxX) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    fun outOfRange(contours: List<FloatArray>, tolerance: Float = 0.01f): Boolean {
        for (points in contours) {
            for (v in points) {
                if (v < -tolerance || v > 1f + tolerance) return true
            }
        }
        return false
    }

    @Suppress("unused")
    private fun unusedAtan2(y: Float, x: Float): Float = atan2(y, x)
}
