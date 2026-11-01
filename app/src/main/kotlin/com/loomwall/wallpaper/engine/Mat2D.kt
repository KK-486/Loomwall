package com.loomwall.wallpaper.engine

import kotlin.math.cos
import kotlin.math.sin

/**
 * 2D 仿射矩阵，用来把「容器内的局部坐标」一步步映射到画布坐标。
 *
 * 之所以需要它：容器可以嵌套，子元素的最终位置取决于所有祖先的位移/缩放/旋转之和。
 * 上机实测过用「两个计数器 + 中心点」凑的做法，一旦嵌套就错，所以老实做矩阵。
 *
 * 存储顺序与写法：
 * ```
 * | a  c  tx |     x' = a*x + c*y + tx
 * | b  d  ty |     y' = b*x + d*y + ty
 * | 0  0  1  |
 * ```
 */
internal class Mat2D(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f
) {
    fun mapX(x: Float, y: Float): Float = a * x + c * y + tx

    fun mapY(x: Float, y: Float): Float = b * x + d * y + ty

    /** 先应用 [inner]，再应用本矩阵。 */
    fun then(inner: Mat2D): Mat2D = Mat2D(
        a = a * inner.a + c * inner.b,
        b = b * inner.a + d * inner.b,
        c = a * inner.c + c * inner.d,
        d = b * inner.c + d * inner.d,
        tx = a * inner.tx + c * inner.ty + tx,
        ty = b * inner.tx + d * inner.ty + ty
    )

    /** 求逆，用于把点击坐标反算回元素局部坐标；退化矩阵返回 null。 */
    fun invert(): Mat2D? {
        val det = a * d - b * c
        if (kotlin.math.abs(det) < 1e-6f) return null
        val inv = 1f / det
        return Mat2D(
            a = d * inv,
            b = -b * inv,
            c = -c * inv,
            d = a * inv,
            tx = (c * ty - d * tx) * inv,
            ty = (b * tx - a * ty) * inv
        )
    }

    companion object {
        val IDENTITY = Mat2D()

        fun translate(x: Float, y: Float): Mat2D = Mat2D(tx = x, ty = y)

        /**
         * 以 [cx],[cy] 为中心缩放 [scale] 倍再旋转 [deg] 度。
         *
         * 公式：`p' = R * (s * p - c) + c`
         * 即先把局部坐标整体放大，再绕放大后的中心旋转——
         * 这和「元素以自己中心缩放」的直觉一致。
         */
        fun scaleRotate(scale: Float, rotationDeg: Float, cx: Float, cy: Float): Mat2D {
            val rad = Math.toRadians(rotationDeg.toDouble())
            val cosV = cos(rad).toFloat()
            val sinV = sin(rad).toFloat()
            return Mat2D(
                a = cosV * scale,
                b = sinV * scale,
                c = -sinV * scale,
                d = cosV * scale,
                tx = cx - cosV * cx + sinV * cy,
                ty = cy - sinV * cx - cosV * cy
            )
        }
    }
}
