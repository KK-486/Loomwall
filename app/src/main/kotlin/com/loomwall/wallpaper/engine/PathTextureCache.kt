package com.loomwall.wallpaper.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.loomwall.wallpaper.scene.RectNode
import com.loomwall.wallpaper.scene.GradientSpec
import com.loomwall.wallpaper.scene.Shapes
import com.loomwall.wallpaper.scene.ShapeKind
import com.loomwall.wallpaper.scene.SvgPathData

/**
 * SVG 路径 → 纹理缓存。
 *
 * 为什么不做"真三角化"：路径可能有多条轮廓、带镂空、自相交，
 * 用 even-odd 填充规则绘制是**唯一稳妥**的做法，而 GLES2 没有现成的填充规则。
 * 所以这里用系统的 Canvas 把路径光栅化到 512px 位图，再当贴图用：
 * - 支持任意轮廓/镂空/奇偶填充（Canvas 自带）
 * - 与文本共用同一套纹理管线，质量在壁纸尺寸下看不出损失
 * - 代价是每份路径占一张纹理；缓存会按 LRU 淘汰
 *
 * 缓存键包含颜色与尺寸：改颜色/尺寸后必须重新光栅化。
 */
internal class PathTextureCache {

    internal class Entry(
        val textureId: Int,
        val width: Int,
        val height: Int,
        var stamp: Long
    )

    private val entries = HashMap<String, Entry>()
    private var stampCounter = 0L

    /** 光栅化边长上限：512 已经能覆盖 4K 壁纸上的图标元素。 */
    private val maxSide = 512

    fun textureFor(node: RectNode, surfaceWidth: Int): Entry? {
        // 只有 path 形状需要 d；矩形/椭圆/多边形由 Shapes.points 生成轮廓\n        if (node.shape == ShapeKind.PATH && node.pathData.isBlank()) return null

        val aspect = if (node.width > 0f) node.height / node.width else 1f
        val targetW: Int
        val targetH: Int
        if (aspect >= 1f) {
            targetH = maxSide
            targetW = (maxSide / aspect).toInt().coerceAtLeast(1)
        } else {
            targetW = maxSide
            targetH = (maxSide * aspect).toInt().coerceAtLeast(1)
        }

        val colorHex = buildString {
            append((node.color.r * 255).toInt()).append((node.color.g * 255).toInt())
            append((node.color.b * 255).toInt()).append((node.color.a * 255).toInt())
        }
        // 缓存键必须包含**所有影响像素的参数**：蒙版形状/顶点数/矩形位置尺寸、
        // 圆角、羽化都在里面。漏掉任何一个，改参数后画面就会一直用旧纹理
        // （表现为"手机端没同步"）。
        val key = "${node.id}|${targetW}x${targetH}|$colorHex|${node.strokeWidth}|${node.shape}|${node.sides}|" +
            "${node.gradient}|${node.feather}|${node.cornerRadius}|" +
            "${node.mask}|${node.maskSides}|${node.maskX},${node.maskY},${node.maskW},${node.maskH}|" +
            "${node.pathData.hashCode()}"

        entries[key]?.let {
            it.stamp = ++stampCounter
            return it
        }

        val entry = runCatching { rasterize(node, key, targetW, targetH, surfaceWidth) }
            .onFailure { Log.i(TAG, "SVG 路径光栅化失败：${it.message}") }
            .getOrNull() ?: return null

        entries[key] = entry
        evictIfNeeded()
        return entry
    }

    private fun rasterize(node: RectNode, key: String, width: Int, height: Int, surfaceWidth: Int): Entry? {
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        var maskClip: Path? = null

        if (node.shape == ShapeKind.PATH) {
            val parsed = SvgPathData.parse(node.pathData, viewBoxWidth(node), viewBoxHeight(node))
            if (parsed.isEmpty) return null
            for (contour in parsed.contours) {
                val count = contour.size / 2
                if (count < 2) continue
                path.moveTo(contour[0] * width, contour[1] * height)
                for (i in 1 until count) {
                    path.lineTo(contour[i * 2] * width, contour[i * 2 + 1] * height)
                }
                path.close()
            }
        } else if (node.mask.isNotBlank() && node.cornerRadius > 0f) {
            // 蒙版 + 圆角叠加：蒙版当裁剪区，圆角矩形当填充形状（两者取交集）
            val radius = node.cornerRadius.coerceIn(0f, 50f) / 100f * width
            val rounded = Shapes.points(ShapeKind.RECT, 4, width.toFloat(), height.toFloat(), radius)
            if (rounded.size >= 6) {
                path.moveTo(rounded[0], rounded[1])
                for (i in 1 until rounded.size / 2) path.lineTo(rounded[i * 2], rounded[i * 2 + 1])
                path.close()
            }
            val maskPoints = Shapes.effectivePoints(
                node.shape, node.sides, 0f, node.mask, node.maskSides,
                width.toFloat(), height.toFloat(), node.maskX, node.maskY, node.maskW, node.maskH
            )
            if (maskPoints.size >= 6) {
                maskClip = Path()
                maskClip?.moveTo(maskPoints[0], maskPoints[1])
                for (i in 1 until maskPoints.size / 2) maskClip?.lineTo(maskPoints[i * 2], maskPoints[i * 2 + 1])
                maskClip?.close()
            }
        } else {
            // 基础形状：用和实心渲染完全相同的顶点（保证"渐变版"和"纯色版"轮廓一致）
            val points = Shapes.effectivePoints(
                node.shape, node.sides, node.cornerRadius, node.mask, node.maskSides,
                width.toFloat(), height.toFloat(), node.maskX, node.maskY, node.maskW, node.maskH
            )
            val count = points.size / 2
            if (count < 3) return null
            path.moveTo(points[0], points[1])
            for (i in 1 until count) path.lineTo(points[i * 2], points[i * 2 + 1])
            path.close()
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 羽化：对路径的 alpha 做模糊。半径按 viewBox→像素的比例换算，
        // 这样元素放大时羽化宽度也跟着放大，视觉比例不变。
        if (node.feather > 0f && node.width > 0f) {
            // feather 是"占元素宽度的百分比"，直接换算成位图像素
            val radiusPx = node.feather / 100f * width
            if (radiusPx > 0.5f) {
                paint.maskFilter = android.graphics.BlurMaskFilter(
                    radiusPx,
                    android.graphics.BlurMaskFilter.Blur.NORMAL
                )
            }
        }
        // 渐变：优先于纯色。渐变总是用填充（描边+渐变没有实际意义）
        val gradient = GradientSpec.parse(node.gradient)
        if (gradient != null) {
            paint.style = Paint.Style.FILL
            paint.shader = buildShader(gradient, width, height)
            maskClip?.let { canvas.clipPath(it) }
        canvas.drawPath(path, paint)
            val textureId = uploadTexture(bitmap)
            bitmap.recycle()
            return if (textureId == 0) null else Entry(textureId, width, height, ++stampCounter)
        }
        // 线框图标（fill="none" + stroke）必须描边，否则轮廓会被填成实心块
        if (node.strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = node.strokeWidth * (width / viewBoxWidth(node))
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
        } else {
            paint.style = Paint.Style.FILL
        }
        paint.color = android.graphics.Color.argb(
            (node.color.a * 255).toInt().coerceIn(0, 255),
            (node.color.r * 255).toInt().coerceIn(0, 255),
            (node.color.g * 255).toInt().coerceIn(0, 255),
            (node.color.b * 255).toInt().coerceIn(0, 255)
        )

        maskClip?.let { canvas.clipPath(it) }
        canvas.drawPath(path, paint)

        val textureId = uploadTexture(bitmap)
        bitmap.recycle()
        if (textureId == 0) return null
        return Entry(textureId, width, height, ++stampCounter)
    }

    /** 把渐变描述变成 Android 的 Shader（线性按角度，径向以中心为圆心）。 */
    private fun buildShader(spec: GradientSpec.Spec, width: Int, height: Int): android.graphics.Shader {
        val positions = spec.stops.map { it.position }.toFloatArray()
        val colors = spec.stops.map { stop ->
            android.graphics.Color.argb(
                (stop.color.a * 255).toInt().coerceIn(0, 255),
                (stop.color.r * 255).toInt().coerceIn(0, 255),
                (stop.color.g * 255).toInt().coerceIn(0, 255),
                (stop.color.b * 255).toInt().coerceIn(0, 255)
            )
        }.toIntArray()

        return if (spec.type == GradientSpec.Type.RADIAL) {
            android.graphics.RadialGradient(
                width / 2f, height / 2f,
                maxOf(width, height) / 2f,
                colors, positions,
                android.graphics.Shader.TileMode.CLAMP
            )
        } else {
            // 角度 0 = 从左到右；沿该方向横跨整个包围盒
            val rad = Math.toRadians(spec.angleDeg.toDouble())
            val dx = kotlin.math.cos(rad).toFloat()
            val dy = kotlin.math.sin(rad).toFloat()
            val halfW = width / 2f
            val halfH = height / 2f
            val len = kotlin.math.abs(dx) * halfW + kotlin.math.abs(dy) * halfH
            val cx = halfW
            val cy = halfH
            android.graphics.LinearGradient(
                cx - dx * len, cy - dy * len,
                cx + dx * len, cy + dy * len,
                colors, positions,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
    }

    private fun viewBoxWidth(node: RectNode): Float = viewBoxParts(node)[2]

    private fun viewBoxHeight(node: RectNode): Float = viewBoxParts(node)[3]

    private fun viewBoxParts(node: RectNode): FloatArray {
        val parts = node.viewBox.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        return if (parts.size == 4 && parts[2] > 0f && parts[3] > 0f) {
            floatArrayOf(parts[0], parts[1], parts[2], parts[3])
        } else {
            floatArrayOf(0f, 0f, 24f, 24f)
        }
    }

    private fun uploadTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /** 超过 32 张就淘汰最久没用的（壁纸里路径元素通常个位数）。 */
    private fun evictIfNeeded() {
        if (entries.size <= 32) return
        val oldest = entries.entries.minByOrNull { it.value.stamp } ?: return
        GLES20.glDeleteTextures(1, intArrayOf(oldest.value.textureId), 0)
        entries.remove(oldest.key)
    }

    fun release() {
        for (entry in entries.values) {
            GLES20.glDeleteTextures(1, intArrayOf(entry.textureId), 0)
        }
        entries.clear()
    }

    private companion object {
        const val TAG = "LOOW.PathTex"
    }
}
