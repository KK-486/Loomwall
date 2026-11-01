package com.loomwall.wallpaper.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.loomwall.wallpaper.scene.Easing

/**
 * 缓动的可视化预览：把曲线画出来。
 *
 * 要求（来自项目约定）：缓动用可视化曲线选择器，**不让用户输入贝塞尔控制点**。
 * 所以这里展示曲线形状，选择通过下拉框完成。
 */
class EasingPreviewView(context: Context) : View(context) {

    var easing: Easing = Easing.Linear
        set(value) {
            field = value
            invalidate()
        }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4C8BF5")
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A313B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(56f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = dp(6f)
        val left = pad
        val top = pad
        val right = width - pad
        val bottom = height - pad
        canvas.drawRect(left, top, right, bottom, framePaint)

        path.reset()
        val steps = 48
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = left + t * (right - left)
            // 画布 y 向下，所以 1-缓动值
            val y = bottom - easing.apply(t) * (bottom - top)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, curvePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
