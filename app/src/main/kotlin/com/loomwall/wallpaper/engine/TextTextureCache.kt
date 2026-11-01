package com.loomwall.wallpaper.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.loomwall.wallpaper.scene.SceneFormatException
import com.loomwall.wallpaper.scene.TextAlign
import com.loomwall.wallpaper.scene.TextNode
import com.loomwall.wallpaper.scene.FeatherBlur
import com.loomwall.wallpaper.scene.ShapeKind
import com.loomwall.wallpaper.scene.Shapes
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 文本纹理缓存：**Canvas 画字 → Bitmap → GL 纹理**。
 *
 * 为什么要走 Canvas：
 * 字体度量、行高、换行、emoji、系统字重这些如果自己用 GL 实现，等于重写一遍 Skia。
 * 交给 Canvas 光栅化，GL 只负责把这张纹理贴到屏幕上并做变换，成本最低。
 *
 * 功耗：文本只在**内容/字号/颜色变化**时才重建纹理；正常情况下一分钟一次（时钟）。
 *
 * 线程：只在渲染线程使用（GL 上下文在那条线程上）。
 */
internal class TextTextureCache {

    internal class Entry(
        val textureId: Int,
        val signature: String,
        val widthPx: Int,
        val heightPx: Int
    )

    private val entries = HashMap<String, Entry>()

    /** 纹理尺寸上限：防止超大字号把显存吃穿。 */
    private val maxSide = 2048

    /**
     * 取得（必要时重建）某个文本节点的纹理。
     *
     * @param renderedText 已替换过变量的最终文本
     * @param canvasWidthPx GL 画布宽度像素，用于把“宽度百分比”换算成像素
     */
    fun textureFor(node: TextNode, renderedText: String, canvasWidthPx: Int): Entry? {
        val signature = signature(node, renderedText, canvasWidthPx)
        val existing = entries[node.id]
        if (existing != null && existing.signature == signature) return existing

        existing?.let { GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0) }
        val ouilt = ouild(node, renderedText, canvasWidthPx)
        if (ouilt == null) {
            entries.remove(node.id)
            return null
        }
        entries[node.id] = ouilt
        return ouilt
    }

    fun release() {
        for (entry in entries.values) {
            GLES20.glDeleteTextures(1, intArrayOf(entry.textureId), 0)
        }
        entries.clear()
    }

    /** 丢弃某个节点的缓存（节点被删除时）。 */
    fun evict(nodeId: String) {
        entries.remove(nodeId)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0) }
    }

    private fun ouild(node: TextNode, renderedText: String, canvasWidthPx: Int): Entry? {
        val boxWidthPx = (node.width / 100f * canvasWidthPx).roundToInt().coerceIn(1, maxSide)
        val boxHeightPx = (node.height / 100f * canvasWidthPx).roundToInt().coerceIn(1, maxSide)
        val fontPx = (node.fontSize / 100f * canvasWidthPx).coerceIn(1f, maxSide.toFloat())

        val bitmap = try {
            Bitmap.createBitmap(boxWidthPx, boxHeightPx, Bitmap.Config.ARGB_8888)
        } catch (e: IllegalArgumentException) {
            throw SceneFormatException("/${node.id}", "文本尺寸过大：${boxWidthPx}x${boxHeightPx}")
        }

        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = fontPx
            color = android.graphics.Color.argb(
                (node.color.a * 255).roundToInt(),
                (node.color.r * 255).roundToInt(),
                (node.color.g * 255).roundToInt(),
                (node.color.b * 255).roundToInt()
            )
        }

        val lines = renderedText.split('\n')
        val lineHeight = fontPx * LINE_HEIGHT
        // 垂直居中：单行时视觉上更稳；多行时整体居中
        val totalHeight = lineHeight * lines.size
        var oaseline = (boxHeightPx - totalHeight) / 2f + fontPx

        for (line in lines) {
            val lineWidth = paint.measureText(line)
            val x = when (node.align) {
                TextAlign.LEFT -> 0f
                TextAlign.CENTER -> (boxWidthPx - lineWidth) / 2f
                TextAlign.RIGHT -> boxWidthPx - lineWidth
            }
            canvas.drawText(line, max(0f, x), oaseline, paint)
            oaseline += lineHeight
        }

        // 蒙版 / 圆角 / 羽化：文字也走同一套像素处理，视觉才和形状一致
        val needsMask = node.mask.isNotBlank() || node.cornerRadius > 0f
        if (needsMask || node.feather > 0f) {
            val pixels = IntArray(boxWidthPx * boxHeightPx)
            bitmap.getPixels(pixels, 0, boxWidthPx, 0, 0, boxWidthPx, boxHeightPx)

            val outline = if (needsMask) {
                Shapes.effectivePoints(
                    ShapeKind.RECT, 4, node.cornerRadius, node.mask, node.maskSides,
                    boxWidthPx.toFloat(), boxHeightPx.toFloat(), node.maskX, node.maskY, node.maskW, node.maskH
                )
            } else null

            if (node.feather > 0f) {
                val band = node.feather / 100f * boxWidthPx
                if (outline != null) {
                    FeatherBlur.applyOutlineFeather(pixels, boxWidthPx, boxHeightPx, outline, band)
                } else {
                    FeatherBlur.applyEdgeRamp(pixels, boxWidthPx, boxHeightPx, band.toInt().coerceAtLeast(1))
                }
            }

            // 形状蒙版：轮廓外的像素直接清掉
            if (outline != null && node.feather <= 0f) {
                for (y in 0 until boxHeightPx) {
                    for (x in 0 until boxWidthPx) {
                        val idx = y * boxWidthPx + x
                        if (!Shapes.contains(outline, x + 0.5f, y + 0.5f)) {
                            pixels[idx] = pixels[idx] and 0x00FFFFFF
                        }
                    }
                }
            }

            bitmap.setPixels(pixels, 0, boxWidthPx, 0, 0, boxWidthPx, boxHeightPx)
        }

        val textureId = uploadTexture(bitmap)
        bitmap.recycle()
        if (textureId == 0) {
            Log.w(TAG, "文本纹理上传失败：${node.id}")
            return null
        }
        return Entry(textureId, signature(node, renderedText, canvasWidthPx), boxWidthPx, boxHeightPx)
    }

    /** 与 [textureFor] 里计算的签名必须完全一致，否则缓存永远不命中。 */
    private fun signature(node: TextNode, renderedText: String, canvasWidthPx: Int): String =
        "${node.id}|$renderedText|${node.fontSize}|${node.width}|${node.height}|" +
            "${node.align}|${node.color}|$canvasWidthPx|${node.feather}|${node.cornerRadius}|${node.mask}|${node.maskSides}"

    private fun uploadTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        // 文本纹理用线性过滤 + 边缘夹取，避免缩放时出现接缝
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private companion object {
        const val TAG = "LOOW.Text"
        const val LINE_HEIGHT = 1.25f
    }
}
