package com.loomwall.wallpaper.editor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loomwall.wallpaper.R
import java.util.Locale

/**
 * 左栏：图层 + 动画列表（横屏布局）。
 *
 * 参考 Wallpaper Engine 的桌面编辑器：左边是「有什么东西」，中间是画面，右边是「选中东西的属性」。
 * 手机上横过来之后，一屏能同时看到这三块，不用来回切换页面。
 */
class LayerPanel(context: Context, private val state: EditorState) : ScrollView(context) {

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(56))
    }

    init {
        addView(content)
        state.addListener { rebuild() }
        rebuild()
    }

    private fun rebuild() {
        content.removeAllViews()

        content.addView(sectionTitle(context.getString(R.string.editor_layers)))

        val rects = state.rects()
        if (rects.isEmpty()) {
            content.addView(hint(context.getString(R.string.editor_no_layers)))
        } else {
            rects.forEach { rect ->
                content.addView(
                    listRow(
                        label = rect.id,
                        detail = String.format(
                            Locale.US,
                            "x%.1f y%.1f %.1f×%.1f",
                            rect.x, rect.y, rect.width, rect.height
                        ),
                        selected = rect.id == state.selectedId,
                        colorDot = rect.color
                    ) { state.select(rect.id) }
                )
            }
        }

        content.addView(
            buttonRow(
                R.string.editor_add_rect to { state.addRect() },
                R.string.editor_delete to {
                    if (state.selectedId != null) state.deleteSelected()
                }
            )
        )

        content.addView(sectionTitle(context.getString(R.string.editor_animations)))

        val clips = state.interactions.animations
        if (clips.isEmpty()) {
            content.addView(hint(context.getString(R.string.editor_no_animation)))
        } else {
            clips.forEach { clip ->
                content.addView(
                    listRow(
                        label = clip.id,
                        detail = clip.trigger.type.displayName() + " · " + clip.tracks.joinToString("+") { it.property.displayName() },
                        selected = clip.id == state.selectedClipId,
                        colorDot = null
                    ) { state.selectClip(clip.id) }
                )
            }
        }

        content.addView(
            buttonRow(
                R.string.editor_delete_animation to { state.removeSelectedClip() },
                R.string.editor_reset_camera to { /* 占位：保持按钮区高度一致 */ }
            )
        )
    }

    private fun sectionTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        alpha = 0.85f
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun hint(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        alpha = 0.55f
        setPadding(0, 0, 0, dp(6))
    }

    private fun listRow(
        label: String,
        detail: String,
        selected: Boolean,
        colorDot: com.loomwall.wallpaper.scene.RgbaColor?,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(
            if (selected) android.graphics.Color.parseColor("#FF1E2A24")
            else android.graphics.Color.TRANSPARENT
        )
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true
        setOnClickListener { onClick() }

        if (colorDot != null) {
            addView(
                View(context).apply {
                    setBackgroundColor(
                        android.graphics.Color.argb(
                            (colorDot.a * 255).toInt(),
                            (colorDot.r * 255).toInt(),
                            (colorDot.g * 255).toInt(),
                            (colorDot.b * 255).toInt()
                        )
                    )
                },
                LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) }
            )
        }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 12f
                })
                addView(TextView(context).apply {
                    text = detail
                    textSize = 10f
                    alpha = 0.55f
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun buttonRow(first: Pair<Int, () -> Unit>, second: Pair<Int, () -> Unit>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            // 必须写全 LinearLayout.LayoutParams：本类是 ScrollView 子类，
            // 直接写 LayoutParams 会解析成 FrameLayout 的（第三个参数是 gravity:int），编译不过。
            addView(
                smallButton(first.first, first.second),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                smallButton(second.first, second.second),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

    private fun smallButton(labelRes: Int, onClick: () -> Unit) = Button(context).apply {
        text = context.getString(labelRes)
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
