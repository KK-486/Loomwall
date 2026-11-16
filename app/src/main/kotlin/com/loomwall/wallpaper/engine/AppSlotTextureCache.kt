package com.loomwall.wallpaper.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.loomwall.wallpaper.scene.RectNode
import com.loomwall.wallpaper.scene.RgbaColor
import java.io.File

/**
 * 软件容器（shape = appslot）的纹理缓存。
 *
 * 为什么整块光栅化成纹理，而不是用 GL 直接画：
 * - 虚线需要 `DashPathEffect`，GLES2 没有；
 * - 图标要按比例塞进方框、名字要省略号截断，用 Canvas 写一遍最省事；
 * - 生成一次后就是一张贴图，渲染期零计算（省电，也符合"不碰 PackageManager"的约定）。
 *
 * 缓存键包含所有影响像素的参数：改颜色/线宽/图标/名字都会重新生成。
 */
internal class AppSlotTextureCache {

    internal class Entry(
        val textureId: Int,
        val width: Int,
        val height: Int,
        var stamp: Long
    )

    private val entries = HashMap<String, Entry>()
    private var stampCounter = 0L

    /** 光栅化上限：512 已足够覆盖手机上任意尺寸的容器。 */
    private val maxSide = 512

    /**
     * @param resourcesDir 预设资源目录（图标 PNG 在这里）；可为空
     */
    fun textureFor(node: RectNode, resourcesDir: File?): Entry? {
        if (node.width <= 0f || node.height <= 0f) return null

        val aspect = node.height / node.width
        val targetW: Int
        val targetH: Int
        if (aspect >= 1f) {
            targetH = maxSide
            targetW = (maxSide / aspect).toInt().coerceAtLeast(1)
        } else {
            targetW = maxSide
            targetH = (maxSide * aspect).toInt().coerceAtLeast(1)
        }

        val iconFile = if (node.appIcon.isNotBlank() && resourcesDir != null) {
            File(resourcesDir, node.appIcon)
        } else {
            null
        }
        // 图标文件的时间戳也进缓存键：换了图标文件必须重画
        val iconStamp = iconFile?.takeIf { it.isFile }?.lastModified() ?: 0L

        val key = "${node.id}|${targetW}x${targetH}|${rgbaKey(node.borderColor)}|${node.borderWidth}|" +
            "${node.dash}|${node.cornerRadius}|${rgbaKey(node.plusColor)}|" +
            "${node.appPkg}|${node.appLabel}|${node.appIcon}|$iconStamp"

        entries[key]?.let {
            it.stamp = ++stampCounter
            return it
        }

        val entry = runCatching { rasterize(node, targetW, targetH, iconFile) }
            .onFailure { Log.i(TAG, "软件容器光栅化失败：${it.message}") }
            .getOrNull() ?: return null

        entries[key] = entry
        evictIfNeeded()
        return entry
    }

    private fun rasterize(node: RectNode, width: Int, height: Int, iconFile: File?): Entry? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 画布单位 → 像素：以宽度为基准，线宽/虚线长度都随之缩放
        val scale = width / node.width

        val icon = iconFile?.takeIf { it.isFile }?.let { file ->
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }

        // 已绑定：图标按容器尺寸铺满，不画虚线框（虚线只在"空容器"时出现）
        if (icon != null) {
            val pad = minOf(width, height) * 0.04f
            val side = minOf(width, height) - pad * 2
            val dst = RectF(
                (width - side) / 2f,
                (height - side) / 2f,
                (width + side) / 2f,
                (height + side) / 2f
            )
            canvas.drawBitmap(icon, null, dst, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = true
            })
            val textureId = uploadTexture(bitmap)
            bitmap.recycle()
            if (textureId == 0) return null
            return Entry(textureId, width, height, ++stampCounter)
        }

        // 未绑定：虚线框 + 中间加号
        val inset = (node.borderWidth * scale) / 2f
        val rect = RectF(inset, inset, width - inset, height - inset)
        val radius = node.width * (node.cornerRadius / 100f) * scale

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (node.borderWidth * scale).coerceAtLeast(2f)
            color = node.borderColor.toArgb()
            strokeCap = Paint.Cap.ROUND
        }
        if (node.dash > 0f) {
            val dashPx = (node.dash * scale).coerceAtLeast(4f)
            paint.pathEffect = DashPathEffect(floatArrayOf(dashPx, dashPx * 0.85f), 0f)
        }
        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.drawPath(path, paint)

        // 未绑定：中间一个加号
        val arm = minOf(width, height) * 0.16f
        val thick = (minOf(width, height) * 0.055f).coerceAtLeast(3f)
        val cx = width / 2f
        val cy = height / 2f
        val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = node.plusColor.toArgb()
        }
        val pill = RectF(cx - thick / 2f, cy - arm / 2f, cx + thick / 2f, cy + arm / 2f)
        val r = thick / 2f
        canvas.drawRoundRect(pill, r, r, plus)
        val pillH = RectF(cx - arm / 2f, cy - thick / 2f, cx + arm / 2f, cy + thick / 2f)
        canvas.drawRoundRect(pillH, r, r, plus)

        val textureId = uploadTexture(bitmap)
        bitmap.recycle()
        if (textureId == 0) return null
        return Entry(textureId, width, height, ++stampCounter)
    }

    /** 按像素宽度截断并加省略号，避免名字出框。 */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val dots = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + dots) > maxWidth) {
            end--
        }
        return if (end <= 0) dots else text.substring(0, end) + dots
    }

    private fun rgbaKey(color: RgbaColor): String =
        "${(color.a * 255).toInt()},${(color.r * 255).toInt()},${(color.g * 255).toInt()},${(color.b * 255).toInt()}"

    private fun RgbaColor.toArgb(): Int = Color.argb(
        (a * 255).toInt().coerceIn(0, 255),
        (r * 255).toInt().coerceIn(0, 255),
        (g * 255).toInt().coerceIn(0, 255),
        (b * 255).toInt().coerceIn(0, 255)
    )

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

    private fun evictIfNeeded() {
        if (entries.size <= 24) return
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
        const val TAG = "LOOW.AppSlot"
    }
}
