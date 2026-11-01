package com.loomwall.wallpaper.editor

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * 让子视图保持「真实壁纸比例」的容器。
 *
 * 为什么需要它：画布必须和桌面上的壁纸同比例，否则编辑器里看到的是壁纸的局部，
 * 用户调完位置回到桌面就会看到完全不同的构图——这类“所见非所得”是最伤编辑器的体验。
 *
 * 做法：按 [aspect]（宽/高）把子视图按宽或高之一撑满，居中，剩余区域留黑边。
 */
class EditorAspectLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 宽 / 高。默认 9:19.5，具体数值由 Activity 按真机屏幕设置。 */
    var aspect: Float = 9f / 19.5f
        set(value) {
            if (value > 0f && value != field) {
                field = value
                requestLayout()
            }
        }

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        var childWidth = availableWidth
        var childHeight = (childWidth / aspect).toInt()
        if (childHeight > availableHeight) {
            childHeight = availableHeight
            childWidth = (childHeight * aspect).toInt()
        }

        val childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(childWidthSpec, childHeightSpec)
        }
        setMeasuredDimension(availableWidth, availableHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) {
            val child: View = getChildAt(index)
            val childLeft = (width - child.measuredWidth) / 2
            val childTop = (height - child.measuredHeight) / 2
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
        }
    }
}
