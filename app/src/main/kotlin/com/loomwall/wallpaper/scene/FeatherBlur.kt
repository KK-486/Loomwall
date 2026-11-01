package com.loomwall.wallpaper.scene

/**
 * 羽化用的模糊算法（纯 Kotlin，可在 JVM 上单测）。
 *
 * 为什么自己做而不用 `RenderScript` / `android.graphics.BlurMaskFilter`：
 * - RenderScript 从 API 31 起废弃，且需要额外初始化；
 * - BlurMaskFilter 只作用于"遮罩"，对整张图片的羽化不好控制半径。
 *
 * 这里是三次盒式模糊近似高斯：一次盒模糊是矩形卷积，三次叠加在视觉上已经足够平滑，
 * 复杂度 O(w*h) 与半径无关（用滑动累加），在手机上也很快。
 */
object FeatherBlur {

    /**
     * 对 ARGB 像素做三次盒式模糊。
     *
     * @param pixels 长度必须是 width * height，格式 ARGB_8888（非预乘）
     * @param radius 模糊半径（像素）；<= 0 时不做任何处理
     */
    fun blurArgb(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0 || width <= 0 || height <= 0) return
        if (pixels.size < width * height) return

        val r = radius.coerceAtMost(minOf(width, height) / 2).coerceAtLeast(1)
        repeat(3) {
            boxBlurHorizontal(pixels, width, height, r)
            boxBlurVertical(pixels, width, height, r)
        }
    }

    /**
     * 只模糊 **alpha 通道**，RGB 保持原样。
     *
     * 这是 PS「羽化」的语义：边缘透明度渐变，但图像内容依然清晰。
     * 如果连 RGB 一起模糊，整张图会变成"失焦"，那是模糊滤镜而不是羽化。
     */
    fun blurAlphaOnly(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0 || width <= 0 || height <= 0) return
        if (pixels.size < width * height) return

        val r = radius.coerceAtMost(minOf(width, height) / 2).coerceAtLeast(1)

        // 抽 alpha，做三次盒模糊。**关键：画布外按透明（0）处理**。
        // 如果按边缘像素延拓，整张不透明图片的 alpha 恒为 255，模糊后还是 255，
        // 边缘根本不会淡出（这就是"羽化了但边缘还在"的原因）。
        val alpha = IntArray(width * height) { (pixels[it] ushr 24) and 0xFF }
        repeat(3) {
            boxBlurChannelZeroPad(alpha, width, height, r, 0)
            boxBlurChannelZeroPad(alpha, width, height, r, 1)
        }

        for (i in pixels.indices) {
            pixels[i] = (alpha[i].coerceIn(0, 255) shl 24) or (pixels[i] and 0x00FFFFFF)
        }
    }

    /**
     * PS 式像素羽化：从边缘向内 [bandPx] 个像素做渐变，最外圈 alpha = 0，内部不动。
     *
     * 与"模糊"的区别（这是用户明确要求的语义）：
     * - 模糊：对称扩散，边缘只是变软，最外圈不会真正透明，且会影响内圈
     * - 羽化：只在四周留 [bandPx] 宽的渐变带，band 之外一个像素都不改
     *
     * @param bandPx 羽化带宽度（像素，纹理空间）
     * @param smooth true 用 smoothstep（过渡更自然），false 用线性
     */
    fun applyEdgeRamp(pixels: IntArray, width: Int, height: Int, bandPx: Int, smooth: Boolean = true) {
        if (bandPx <= 0 || width <= 0 || height <= 0) return
        if (pixels.size < width * height) return

        val band = bandPx.coerceAtMost(minOf(width, height) / 2).coerceAtLeast(1)
        for (y in 0 until height) {
            // 该行到上下边缘的距离（取较小者）
            val dy = minOf(y, height - 1 - y)
            for (x in 0 until width) {
                val dx = minOf(x, width - 1 - x)
                val dist = minOf(dx, dy)
                if (dist >= band) continue // band 内不动

                var factor = dist.toFloat() / band
                if (smooth) factor = factor * factor * (3f - 2f * factor)

                val index = y * width + x
                val a = (pixels[index] ushr 24) and 0xFF
                val faded = (a * factor).toInt().coerceIn(0, 255)
                pixels[index] = (faded shl 24) or (pixels[index] and 0x00FFFFFF)
            }
        }
    }
    /**
     * 沿**任意轮廓**羽化：像素到最近轮廓边的距离决定 alpha，而不是到矩形边。
     *
     * 为什么需要它：元素挂了星形/圆形蒙版时，边缘是一条曲线，
     * 按矩形距离羽化会在空白处也产生渐变（看起来像"整块变淡"而不是"沿形状淡出"）。
     *
     * @param points 轮廓顶点（局部像素坐标，闭合，首尾不必重复）
     * @param bandPx 羽化带宽度（像素）
     * @param smooth true = smoothstep 过渡
     */
    fun applyOutlineFeather(
        pixels: IntArray,
        width: Int,
        height: Int,
        points: FloatArray,
        bandPx: Float,
        smooth: Boolean = true
    ) {
        val n = points.size / 2
        if (bandPx <= 0f || width <= 0 || height <= 0 || n < 3) return
        if (pixels.size < width * height) return

        for (y in 0 until height) {
            val py = y + 0.5f
            for (x in 0 until width) {
                val px = x + 0.5f
                val inside = pointInPolygon(points, px, py)
                val index = y * width + x
                if (!inside) {
                    pixels[index] = pixels[index] and 0x00FFFFFF // 轮廓外全透明
                    continue
                }

                val dist = distanceToOutline(points, px, py)
                if (dist >= bandPx) continue
                var factor = dist / bandPx
                if (smooth) factor = factor * factor * (3f - 2f * factor)

                val a = (pixels[index] ushr 24) and 0xFF
                val faded = (a * factor).toInt().coerceIn(0, 255)
                pixels[index] = (faded shl 24) or (pixels[index] and 0x00FFFFFF)
            }
        }
    }

    private fun pointInPolygon(points: FloatArray, x: Float, y: Float): Boolean {
        val n = points.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[i * 2]
            val yi = points[i * 2 + 1]
            val xj = points[j * 2]
            val yj = points[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun distanceToOutline(points: FloatArray, x: Float, y: Float): Float {
        val n = points.size / 2
        var best = Float.MAX_VALUE
        var j = n - 1
        for (i in 0 until n) {
            val ax = points[i * 2]
            val ay = points[i * 2 + 1]
            val bx = points[j * 2]
            val by = points[j * 2 + 1]
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq <= 1e-6f) 0f else (((x - ax) * dx + (y - ay) * dy) / lenSq).coerceIn(0f, 1f)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = kotlin.math.hypot((x - px).toDouble(), (y - py).toDouble()).toFloat()
            if (d < best) best = d
            j = i
        }
        return best
    }
    /** 单通道盒模糊，越界当 0（画布外透明）。direction: 0 = 横, 1 = 纵 */
    private fun boxBlurChannelZeroPad(channel: IntArray, width: Int, height: Int, radius: Int, direction: Int) {
        val window = radius * 2 + 1
        if (direction == 0) {
            val temp = IntArray(width)
            for (y in 0 until height) {
                val rowStart = y * width
                var sum = 0
                for (i in -radius..radius) {
                    val x = i
                    if (x in 0 until width) sum += channel[rowStart + x]
                }
                for (x in 0 until width) {
                    temp[x] = sum / window
                    val outX = x - radius
                    val inX = x + radius + 1
                    if (outX in 0 until width) sum -= channel[rowStart + outX]
                    if (inX in 0 until width) sum += channel[rowStart + inX]
                }
                System.arraycopy(temp, 0, channel, rowStart, width)
            }
        } else {
            val temp = IntArray(height)
            for (x in 0 until width) {
                var sum = 0
                for (i in -radius..radius) {
                    if (i in 0 until height) sum += channel[i * width + x]
                }
                for (y in 0 until height) {
                    temp[y] = sum / window
                    val outY = y - radius
                    val inY = y + radius + 1
                    if (outY in 0 until height) sum -= channel[outY * width + x]
                    if (inY in 0 until height) sum += channel[inY * width + x]
                }
                for (y in 0 until height) channel[y * width + x] = temp[y]
            }
        }
    }
    /** direction: 0 = 横向, 1 = 纵向 */
    private fun boxBlurChannel(channel: IntArray, width: Int, height: Int, radius: Int, direction: Int) {
        val window = radius * 2 + 1
        if (direction == 0) {
            val temp = IntArray(width)
            for (y in 0 until height) {
                val rowStart = y * width
                var sum = 0
                for (i in -radius..radius) sum += channel[rowStart + i.coerceIn(0, width - 1)]
                for (x in 0 until width) {
                    temp[x] = sum / window
                    val out = channel[rowStart + (x - radius).coerceIn(0, width - 1)]
                    val inc = channel[rowStart + (x + radius + 1).coerceIn(0, width - 1)]
                    sum += inc - out
                }
                System.arraycopy(temp, 0, channel, rowStart, width)
            }
        } else {
            val temp = IntArray(height)
            for (x in 0 until width) {
                var sum = 0
                for (i in -radius..radius) sum += channel[i.coerceIn(0, height - 1) * width + x]
                for (y in 0 until height) {
                    temp[y] = sum / window
                    val out = channel[(y - radius).coerceIn(0, height - 1) * width + x]
                    val inc = channel[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                    sum += inc - out
                }
                for (y in 0 until height) channel[y * width + x] = temp[y]
            }
        }
    }

    private fun boxBlurHorizontal(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        val temp = IntArray(width)
        for (y in 0 until height) {
            val rowStart = y * width
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0

            // 先填满左侧窗口（边缘用首像素延拓）
            for (i in -radius..radius) {
                val p = pixels[rowStart + i.coerceIn(0, width - 1)]
                sumA += (p ushr 24) and 0xFF
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }

            for (x in 0 until width) {
                temp[x] = ((sumA / window) shl 24) or
                    ((sumR / window) shl 16) or
                    ((sumG / window) shl 8) or
                    (sumB / window)

                val outIndex = (x - radius).coerceIn(0, width - 1)
                val inIndex = (x + radius + 1).coerceIn(0, width - 1)
                val out = pixels[rowStart + outIndex]
                val inc = pixels[rowStart + inIndex]

                sumA += ((inc ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                sumR += ((inc ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                sumG += ((inc ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                sumB += (inc and 0xFF) - (out and 0xFF)
            }

            System.arraycopy(temp, 0, pixels, rowStart, width)
        }
    }

    private fun boxBlurVertical(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        val temp = IntArray(height)
        for (x in 0 until width) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0

            for (i in -radius..radius) {
                val p = pixels[i.coerceIn(0, height - 1) * width + x]
                sumA += (p ushr 24) and 0xFF
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }

            for (y in 0 until height) {
                temp[y] = ((sumA / window) shl 24) or
                    ((sumR / window) shl 16) or
                    ((sumG / window) shl 8) or
                    (sumB / window)

                val outIndex = (y - radius).coerceIn(0, height - 1)
                val inIndex = (y + radius + 1).coerceIn(0, height - 1)
                val out = pixels[outIndex * width + x]
                val inc = pixels[inIndex * width + x]

                sumA += ((inc ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                sumR += ((inc ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                sumG += ((inc ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                sumB += (inc and 0xFF) - (out and 0xFF)
            }

            for (y in 0 until height) {
                pixels[y * width + x] = temp[y]
            }
        }
    }
}
