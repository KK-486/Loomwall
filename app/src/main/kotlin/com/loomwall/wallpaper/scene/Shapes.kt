package com.loomwall.wallpaper.scene

import kotlin.math.cos
import kotlin.math.sin

/**
 * 形状枚举。
 *
 * 说明：格式里仍然沿用 `type: "rect"` + `shape` 字段，而不是新增一堆节点类型——
 * 这样 v0.1/v0.2 的老预设不用迁移，`shape` 缺省就是矩形，语义完全等价。
 */
enum class ShapeKind(val jsonName: String, val label: String) {
    RECT("rect", "矩形"),
    ELLIPSE("ellipse", "椭圆 / 圆形"),
    TRIANGLE("triangle", "三角形"),
    TRAPEZOID("trapezoid", "梯形"),
    POLYGON("polygon", "正多边形"),
    STAR("star", "星形"),
    PATH("path", "SVG 路径");

    companion object {
        fun fromJson(name: String?): ShapeKind? =
            entries.firstOrNull { it.jsonName.equals(name, ignoreCase = true) }
    }
}

/**
 * 形状顶点生成：把形状表示为**围绕中心的一圈点**。
 *
 * 为什么统一成"一圈点"：渲染端只要用 `GL_TRIANGLE_FAN` 从中心展开就能画出所有形状
 * （矩形/椭圆/三角/梯形/正多边形/星形都满足"关于中心星形可见"这一条件），
 * 命中测试用射线法也能复用同一组点，不用为每种形状写两套逻辑。
 *
 * 坐标系：局部坐标，(0,0) 是包围盒左上角，(width,height) 是右下角。
 * 返回扁平数组 `[x0,y0,x1,y1,...]`，顺序为**顺时针**。
 */
object Shapes {

    /** 椭圆用多少段近似：48 段在手机上已经看不出棱角，顶点数也不浪费。 */
    private const val ELLIPSE_SEGMENTS = 48

    /** 星形的内径比例。 */
    private const val STAR_INNER_RATIO = 0.45f

    /** 梯形上边相对宽度的比例。 */
    private const val TRAPEZOID_TOP_RATIO = 0.5f

    /**
     * @param cornerRadius 圆角半径（与 width/height 同单位）。仅 RECT 生效，
     *        0 = 直角。上限取 min(width,height)/2，超过会被夹住（否则弧会翻转）。
     */
    fun points(
        kind: ShapeKind,
        sides: Int,
        width: Float,
        height: Float,
        cornerRadius: Float = 0f
    ): FloatArray {
        // 路径形状由 SvgPathData 单独处理（可能有多条轮廓），这里给个占位矩形
        if (kind == ShapeKind.PATH) {
            return floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
        }
        if (width <= 0f || height <= 0f) return floatArrayOf(0f, 0f)

        return when (kind) {
            ShapeKind.RECT -> {
                val radius = cornerRadius.coerceIn(0f, minOf(width, height) / 2f)
                if (radius <= 0.5f) {
                    floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
                } else {
                    roundedRect(width, height, radius)
                }
            }

            ShapeKind.ELLIPSE -> ellipse(width, height)

            ShapeKind.TRIANGLE -> floatArrayOf(
                width / 2f, 0f,
                width, height,
                0f, height
            )

            ShapeKind.TRAPEZOID -> {
                val inset = width * (1f - TRAPEZOID_TOP_RATIO) / 2f
                floatArrayOf(
                    inset, 0f,
                    width - inset, 0f,
                    width, height,
                    0f, height
                )
            }

            ShapeKind.POLYGON -> regularPolygon(sides.coerceIn(3, 32).toFloat(), width, height)

            ShapeKind.STAR -> star(sides.coerceIn(3, 32), width, height)

            // 路径形状有多条轮廓，由 SvgPathData 负责；这里只用作"包围盒占位"
            ShapeKind.PATH -> floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
        }
    }

    /**
     * 圆角矩形的顶点（顺时针）。
     *
     * 每个角用 [CORNER_SEGMENTS] 段圆弧近似。点数保持较少（4 角 × 6 = 24），
     * 这样三角形扇（GL_TRIANGLE_FAN）的顶点上限不会成为瓶颈。
     */
    private fun roundedRect(width: Float, height: Float, radius: Float): FloatArray {
        val out = ArrayList<Float>(4 * CORNER_SEGMENTS * 2)
        // 四个角的圆心（左上、右上、右下、左下）与起始角度
        val corners = floatArrayOf(
            radius, radius, 180f,
            width - radius, radius, 270f,
            width - radius, height - radius, 0f,
            radius, height - radius, 90f
        )
        for (c in 0 until 4) {
            val cx = corners[c * 3]
            val cy = corners[c * 3 + 1]
            val startDeg = corners[c * 3 + 2]
            for (i in 0..CORNER_SEGMENTS) {
                val deg = startDeg + 90f * i / CORNER_SEGMENTS
                val rad = Math.toRadians(deg.toDouble())
                out.add(cx + (radius * cos(rad)).toFloat())
                out.add(cy + (radius * sin(rad)).toFloat())
            }
        }
        return out.toFloatArray()
    }

    /** 每个圆角用几段近似。 */
    private const val CORNER_SEGMENTS = 6

    /**
     * 元素的实际轮廓：**有蒙版就用蒙版的形状**，否则用自身形状（带圆角）。
     *
     * 渲染、命中测试、光栅化都走这一个入口，避免三处各算一套导致
     * "编辑器是圆角、手机是直角"这类分歧。
     *
     * @param cornerRadiusPercent 圆角占元素宽度的百分比（0..50）
     * @param mask 蒙版形状的 jsonName，空串表示不裁剪
     */
    fun effectivePoints(
        kind: ShapeKind,
        sides: Int,
        cornerRadiusPercent: Float,
        mask: String,
        maskSides: Int,
        width: Float,
        height: Float,
        maskXPercent: Float = 0f,
        maskYPercent: Float = 0f,
        maskWPercent: Float = 100f,
        maskHPercent: Float = 100f
    ): FloatArray {
        val radius = cornerRadiusPercent.coerceIn(0f, 50f) / 100f * width
        val maskKind = if (mask.isBlank()) null else ShapeKind.fromJson(mask)

        // 蒙版是图层内的**独立对象**：有自己的位置和尺寸，默认铺满图层。
        if (maskKind != null) {
            val mw = (maskWPercent.coerceAtLeast(1f) / 100f) * width
            val mh = (maskHPercent.coerceAtLeast(1f) / 100f) * height
            // maskX/maskY 是**相对容器中心**的偏移：默认 0 时蒙版居中。
            // 之前是从左上角算，缩放蒙版后会偏到左上，不符合直觉。
            val ox = (width - mw) / 2f + (maskXPercent / 100f) * width
            val oy = (height - mh) / 2f + (maskYPercent / 100f) * height
            val maskPts: FloatArray = if (maskKind == ShapeKind.RECT && radius > 0.5f) {
                points(ShapeKind.RECT, 4, mw, mh, radius)
            } else {
                points(maskKind, maskSides, mw, mh)
            }
            // 平移到蒙版位置，并按需圆角化
            val shifted = FloatArray(maskPts.size)
            for (i in maskPts.indices step 2) {
                shifted[i] = maskPts[i] + ox
                shifted[i + 1] = maskPts[i + 1] + oy
            }
            // 圆角：矩形蒙版已在 points() 里做过，其它形状需要顶点圆滑化
            return if (radius > 0.5f && maskKind != ShapeKind.RECT) roundPolygon(shifted, radius) else shifted
        }

        // 无蒙版 → 自身形状
        val baseKind = kind
        val baseSides = sides
        val base = points(baseKind, baseSides, width, height, if (baseKind == ShapeKind.RECT) radius else 0f)

        // 非矩形（三角/梯形/多边形/星形）的圆角：把每个顶点替换成圆滑过渡，
        // 这样"星形蒙版 + 圆角"是让星尖变钝，而不是在星形外套一个圆角矩形。
        if (radius > 0.5f && baseKind != ShapeKind.RECT) {
            return roundPolygon(base, radius)
        }
        return base
    }

    /**
     * 多边形圆角化：把每个顶点替换成一段二次贝塞尔（采样成折线）。
     *
     * 切点距离按 `r / tan(θ/2)` 计算（θ 为两条邻边的夹角），并夹到相邻边长的一半，
     * 避免短边处切点越界导致自交。
     *
     * @param radius 圆角半径（与顶点同单位）
     */
    fun roundPolygon(pointsIn: FloatArray, radius: Float): FloatArray {
        val n = pointsIn.size / 2
        if (n < 3 || radius <= 0.5f) return pointsIn

        val out = ArrayList<Float>(n * 8)

        for (i in 0 until n) {
            val vx = pointsIn[i * 2]
            val vy = pointsIn[i * 2 + 1]
            val ax = pointsIn[((i - 1 + n) % n) * 2]
            val ay = pointsIn[((i - 1 + n) % n) * 2 + 1]
            val bx = pointsIn[((i + 1) % n) * 2]
            val by = pointsIn[((i + 1) % n) * 2 + 1]

            val vax = ax - vx
            val vay = ay - vy
            val vbx = bx - vx
            val vby = by - vy
            val lenA = kotlin.math.hypot(vax.toDouble(), vay.toDouble()).toFloat()
            val lenB = kotlin.math.hypot(vbx.toDouble(), vby.toDouble()).toFloat()
            if (lenA < 1e-3f || lenB < 1e-3f) {
                out.add(vx)
                out.add(vy)
                continue
            }

            val uax = vax / lenA
            val uay = vay / lenA
            val ubx = vbx / lenB
            val uby = vby / lenB

            // 两条邻边的夹角（0..π）
            val dot = (uax * ubx + uay * uby).coerceIn(-1f, 1f)
            val theta = kotlin.math.acos(dot.toDouble())

            // 近乎共线：不需要圆角，直接保留顶点
            if (theta < 1e-3 || theta > Math.PI - 1e-3) {
                out.add(vx)
                out.add(vy)
                continue
            }

            val tanHalf = kotlin.math.tan(theta / 2.0)
            val sinHalf = kotlin.math.sin(theta / 2.0)
            var tangent = (radius / tanHalf).toFloat()
            tangent = tangent.coerceIn(0.01f, minOf(lenA, lenB) / 2f)

            // 切点
            val p1x = vx + uax * tangent
            val p1y = vy + uay * tangent
            val p2x = vx + ubx * tangent
            val p2y = vy + uby * tangent

            // 圆心：沿角平分线，距离 = r / sin(θ/2)
            var bisX = uax + ubx
            var bisY = uay + uby
            val bisLen = kotlin.math.hypot(bisX.toDouble(), bisY.toDouble()).toFloat()
            if (bisLen < 1e-4f) {
                out.add(vx)
                out.add(vy)
                continue
            }
            bisX /= bisLen
            bisY /= bisLen
            val centerDist = (tangent * tanHalf / sinHalf).toFloat()
            val cx = vx + bisX * centerDist
            val cy = vy + bisY * centerDist

            // 圆心到切点的实际半径（因切点被夹过，这里用实际值）
            val arcR = kotlin.math.hypot((p1x - cx).toDouble(), (p1y - cy).toDouble()).toFloat()

            var startAngle = kotlin.math.atan2((p1y - cy).toDouble(), (p1x - cx).toDouble())
            var endAngle = kotlin.math.atan2((p2y - cy).toDouble(), (p2x - cx).toDouble())
            var sweep = endAngle - startAngle
            while (sweep > Math.PI) sweep -= 2 * Math.PI
            while (sweep < -Math.PI) sweep += 2 * Math.PI

            // 按弧长自适应分段：约每 6° 一段，至少 3 段。
            // 固定段数在大半径时会露出折面（用户反馈的"明显平面"）。
            val segments = (kotlin.math.abs(sweep) / (Math.PI / 30)).toInt().coerceIn(3, 32)
            for (s in 0..segments) {
                val a = startAngle + sweep * s / segments
                out.add((cx + arcR * kotlin.math.cos(a)).toFloat())
                out.add((cy + arcR * kotlin.math.sin(a)).toFloat())
            }
        }
        return out.toFloatArray()
    }
    /** 圆角每段用几个采样点。 */
    private const val ROUND_SEGMENTS = 5

    private fun ellipse(width: Float, height: Float): FloatArray {
        val cx = width / 2f
        val cy = height / 2f
        val rx = width / 2f
        val ry = height / 2f
        val out = FloatArray(ELLIPSE_SEGMENTS * 2)
        // 从正上方开始分配，视觉上"北极"是一个顶点，圆形和圆角看起来更规整
        for (i in 0 until ELLIPSE_SEGMENTS) {
            val angle = -Math.PI / 2 + i * 2 * Math.PI / ELLIPSE_SEGMENTS
            out[i * 2] = cx + (rx * cos(angle)).toFloat()
            out[i * 2 + 1] = cy + (ry * sin(angle)).toFloat()
        }
        return out
    }

    private fun regularPolygon(n: Float, width: Float, height: Float): FloatArray {
        val count = n.toInt()
        val cx = width / 2f
        val cy = height / 2f
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            val angle = -Math.PI / 2 + i * 2 * Math.PI / count
            out[i * 2] = cx + (width / 2f * cos(angle)).toFloat()
            out[i * 2 + 1] = cy + (height / 2f * sin(angle)).toFloat()
        }
        return out
    }

    private fun star(points: Int, width: Float, height: Float): FloatArray {
        val cx = width / 2f
        val cy = height / 2f
        val vertexCount = points * 2
        val out = FloatArray(vertexCount * 2)
        for (i in 0 until vertexCount) {
            val ratio = if (i % 2 == 0) 1f else STAR_INNER_RATIO
            val angle = -Math.PI / 2 + i * Math.PI / points
            out[i * 2] = cx + (width / 2f * ratio * cos(angle)).toFloat()
            out[i * 2 + 1] = cy + (height / 2f * ratio * sin(angle)).toFloat()
        }
        return out
    }

    /**
     * 点是否落在多边形内（射线法）。
     *
     * 用于命中测试：圆形/三角形/星形如果还按包围盒判定，点空白处也会触发，
     * 手感会很怪。
     */
    fun contains(points: FloatArray, x: Float, y: Float): Boolean {
        val n = points.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[i * 2]
            val yi = points[i * 2 + 1]
            val xj = points[j * 2]
            val yj = points[j * 2 + 1]
            val intersects = (yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }
}
