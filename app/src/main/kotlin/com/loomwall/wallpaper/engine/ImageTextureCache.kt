package com.loomwall.wallpaper.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.loomwall.wallpaper.scene.FeatherBlur
import com.loomwall.wallpaper.scene.ShapeKind
import com.loomwall.wallpaper.scene.Shapes
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.io.File

/**
 * 图片纹理缓存：`presets/active/resources/<src>` → GL 纹理。
 *
 * 关键取舍：
 * - **只在渲染线程用**（GL 上下文在那条线程）；
 * - 大图用 `inSampleSize` 缩到 [maxSide] 以内再上传，避免一张 4K 图把显存吃穿；
 * - 同一个文件只解码一次，缓存以绝对路径 + 修改时间做键，重新导入后自然失效。
 */
internal class ImageTextureCache {

    internal class Entry(
        val textureId: Int,
        val widthPx: Int,
        val heightPx: Int,
        val stamp: Long
    )

    private val entries = HashMap<String, Entry>()

    /** 单边上限：超出的图会等比缩小到这个尺寸以内。 */
    // 单边上限：2048 时一张 ARGB 位图 16MB，克隆一份用于羽化就是 32MB，
    // 多图同时羽化会顶爆内存导致整帧黑屏（无异常日志）。1024 在壁纸上肉眼看不出差别。
    /**
     * 解码上限（长边像素）。
     *
     * 原来 1024：一张 1403px 高的立绘图会被 inSampleSize 砍半成 ~700px，
     * 再拉到 1080 宽屏幕上就明显发糊（用户实测反馈）。
     * 2048 能覆盖绝大多数手机壁纸素材，单张显存约 16 MB（ARGB_8888），
     * 预设里通常只有 1~3 张大图，仍在安全范围。
     */
    private val maxSide = 2048

    /**
     * @param featherPercent 羽化带宽度占**元素宽度**的百分比（0..50，0 = 不羽化）。
     *        用百分比而不是像素：同一组参数在任何尺寸的元素上视觉比例一致，
     *        编辑器和手机也就不会出现"看起来不一样"。
     */
    fun textureFor(
        src: String,
        resourcesDir: File?,
        featherPercent: Float = 0f,
        cornerRadiusPercent: Float = 0f,
        mask: String = "",
        maskSides: Int = 5,
        maskSrc: String = "",
        maskX: Float = 0f,
        maskY: Float = 0f,
        maskW: Float = 100f,
        maskH: Float = 100f
    ): Entry? {
        if (resourcesDir == null) return null
        val file = File(resourcesDir, src)
        if (!file.isFile) return null

        val key = file.absolutePath + "|fp" + featherPercent + "|cr" + cornerRadiusPercent + "|m" + mask + maskSides + "|ms" + maskSrc + "|mr" + maskX + "," + maskY + "," + maskW + "," + maskH
        val stamp = file.lastModified()
        entries[key]?.let { if (it.stamp == stamp) return it }

        entries.remove(key)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0) }
        val built = decodeAndUpload(file, stamp, featherPercent, cornerRadiusPercent, mask, maskSides, maskSrc, resourcesDir, maskX, maskY, maskW, maskH) ?: return null
        entries[key] = built
        return built
    }

    fun release() {
        for (entry in entries.values) {
            GLES20.glDeleteTextures(1, intArrayOf(entry.textureId), 0)
        }
        entries.clear()
    }

    private fun decodeAndUpload(file: File, stamp: Long, featherPercent: Float, cornerRadiusPercent: Float, mask: String, maskSides: Int, maskSrc: String, resourcesDir: File?, maskX: Float, maskY: Float, maskW: Float, maskH: Float): Entry? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "图片解码失败（可能不是有效图片）：${file.name}")
            return null
        }

        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }

        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull()

        if (bitmap == null) {
            Log.w(TAG, "图片读取失败：${file.name}")
            return null
        }

        // 羽化：只处理 alpha 通道（PS 的羽化语义），RGB 不动
        var working = bitmap
        if (featherPercent > 0f) {
            // 百分比 → 纹理像素：直接乘纹理宽，与元素在屏幕上的尺寸无关
            val band = (featherPercent / 100f * working.width).toInt()
            if (band > 0) {
                // BitmapFactory 解出来的是不可变位图，setPixels 会抛异常，先复制
                val mutable = working.copy(Bitmap.Config.ARGB_8888, true) ?: working
                if (mutable !== working) working.recycle()
                working = mutable
                val w = working.width
                val h = working.height
                val pixels = IntArray(w * h)
                working.getPixels(pixels, 0, w, 0, 0, w, h)
                // 羽化跟随**蒙版/圆角轮廓**：挂了蒙版时按"像素到轮廓"的距离淡出，
                // 否则按矩形四边（默认逻辑）。
                // 注意：若这里仍用矩形算法、之后再裁蒙版，羽化带会被整体裁掉，
                // 表现就是"设置了羽化但完全看不出来"。
                if (mask.isNotBlank() || cornerRadiusPercent > 0f) {
                    val outline = Shapes.effectivePoints(
                        ShapeKind.RECT, 4, cornerRadiusPercent, mask, maskSides,
                        w.toFloat(), h.toFloat(), maskX, maskY, maskW, maskH
                    )
                    FeatherBlur.applyOutlineFeather(pixels, w, h, outline, band.toFloat())
                } else {
                    FeatherBlur.applyEdgeRamp(pixels, w, h, band)
                }
                working.setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }
        // 蒙版/圆角：把图裁到形状轮廓内（透明区域用 CLEAR 打洞）
        if (working !== bitmap || mask.isNotBlank() || cornerRadiusPercent > 0f) {
            val clipped = runCatching { clipToShape(working, cornerRadiusPercent, mask, maskSides, maskX, maskY, maskW, maskH) }.getOrNull()
            if (clipped != null && clipped !== working) {
                if (!working.isRecycled) working.recycle()
                working = clipped
            }
        }

        // 图片蒙版：用另一张图的 alpha 通道相乘
        if (maskSrc.isNotBlank() && resourcesDir != null) {
            val masked = runCatching { applyAlphaMask(working, File(resourcesDir, maskSrc)) }.getOrNull()
            if (masked != null && masked !== working) {
                if (!working.isRecycled) working.recycle()
                working = masked
            }
        }

        val textureId = upload(working)
        val w = working.width
        val h = working.height
        if (!working.isRecycled) working.recycle()
        if (textureId == 0) return null
        return Entry(textureId, w, h, stamp)
    }

    /** 把位图裁到"蒙版形状 / 圆角矩形"内，返回新位图。 */
    private fun clipToShape(bitmap: Bitmap, cornerRadiusPercent: Float, mask: String, maskSides: Int, maskX: Float, maskY: Float, maskW: Float, maskH: Float): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val maskKind = if (mask.isBlank()) ShapeKind.RECT else ShapeKind.fromJson(mask) ?: return null
        val points = Shapes.effectivePoints(
            ShapeKind.RECT, 4, cornerRadiusPercent, mask, maskSides, w.toFloat(), h.toFloat(),
            maskX, maskY, maskW, maskH
        )
        if (points.size < 6) return null

        val path = android.graphics.Path()
        path.moveTo(points[0], points[1])
        for (i in 1 until points.size / 2) path.lineTo(points[i * 2], points[i * 2 + 1])
        path.close()

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.clipPath(path)

        // 圆角与蒙版叠加：再裁一次圆角矩形（两次 clip 取交集），
        // 这样"星形蒙版 + 圆角"不会互相抵消。
        if (cornerRadiusPercent > 0f && mask.isNotBlank()) {
            val radius = cornerRadiusPercent.coerceIn(0f, 50f) / 100f * w
            val rounded = Shapes.points(ShapeKind.RECT, 4, w.toFloat(), h.toFloat(), radius)
            if (rounded.size >= 6) {
                val rp = android.graphics.Path()
                rp.moveTo(rounded[0], rounded[1])
                for (i in 1 until rounded.size / 2) rp.lineTo(rounded[i * 2], rounded[i * 2 + 1])
                rp.close()
                canvas.clipPath(rp)
            }
        }

        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * 用 [maskFile] 的 alpha 通道裁剪 [bitmap]。
     *
     * 做法：把蒙版图缩放到目标尺寸后取像素 alpha，与目标逐点相乘。
     * 纯 CPU 逐点计算，只在纹理生成时跑一次。
     */
    private fun applyAlphaMask(bitmap: Bitmap, maskFile: File): Bitmap? {
        if (!maskFile.isFile) return null
        val w = bitmap.width
        val h = bitmap.height

        val maskBitmap = BitmapFactory.decodeFile(maskFile.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null

        val scaled = Bitmap.createScaledBitmap(maskBitmap, w, h, true)
        if (scaled !== maskBitmap) maskBitmap.recycle()

        val srcPixels = IntArray(w * h)
        val maskPixels = IntArray(w * h)
        bitmap.getPixels(srcPixels, 0, w, 0, 0, w, h)
        scaled.getPixels(maskPixels, 0, w, 0, 0, w, h)
        scaled.recycle()

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (i in 0 until w * h) {
            val maskAlpha = (maskPixels[i] ushr 24) and 0xFF
            val srcAlpha = (srcPixels[i] ushr 24) and 0xFF
            val alpha = srcAlpha * maskAlpha / 255
            out.setPixel(i % w, i / w, (alpha shl 24) or (srcPixels[i] and 0x00FFFFFF))
        }
        return out
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        // 图片缩放时用线性过滤；边缘夹取避免接缝
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private companion object {
        const val TAG = "LOOW.Image"
    }
}
