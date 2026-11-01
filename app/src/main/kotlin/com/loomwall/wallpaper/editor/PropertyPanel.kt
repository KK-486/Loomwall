package com.loomwall.wallpaper.editor

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.loomwall.wallpaper.R
import com.loomwall.wallpaper.scene.RgbaColor
import com.loomwall.wallpaper.scene.toHex
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 第 1 层属性面板：位置、尺寸、透明度、颜色、背景色。
 *
 * 交互原则：
 * - 数值框在**失焦或按下完成**时才写回，避免每敲一个字符就重建场景；
 * - 颜色非法时不静默吞掉，直接回滚到当前值（用户在别处能看到正确色值）；
 * - 没有选中元素时，元素字段整体禁用，只留背景色可改。
 */
class PropertyPanel(context: Context, private val state: EditorState) : ScrollView(context) {

    private var syncing = false

    private val titleView = TextView(context).apply {
        textSize = 14f
        setPadding(0, 0, 0, dp(4))
    }

    private val xField = numberField()
    private val yField = numberField()
    private val widthField = numberField()
    private val heightField = numberField()
    private val opacityBar = SeekBar(context).apply { max = 100 }
    private val colorField = numberField(signed = false)
    private val backgroundField = numberField(signed = false)

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(16))
    }

    init {
        addView(content)
        content.addView(titleView)
        content.addView(
            labelled(
                R.string.editor_field_position,
                pairRow(xField, yField)
            )
        )
        content.addView(labelled(R.string.editor_field_size, pairRow(widthField, heightField)))
        content.addView(labelled(R.string.editor_field_opacity, opacityBar))
        content.addView(labelled(R.string.editor_field_color, colorField))
        content.addView(labelled(R.string.editor_field_background, backgroundField))

        wireInputs()
        state.addListener { refresh() }
        refresh()
    }

    // ---- 构建 ----

    private fun numberField(signed: Boolean = true): EditText = EditText(context).apply {
        inputType = if (signed) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        } else {
            // 颜色用文本输入（#AARRGGBB），不能用数字键盘。
            InputType.TYPE_CLASS_TEXT
        }
        imeOptions = EditorInfo.IME_ACTION_DONE
        textSize = 14f
        isSingleLine = true
    }

    private fun pairRow(first: View, second: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun labelled(labelRes: Int, field: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(
                TextView(context).apply {
                    text = context.getString(labelRes)
                    textSize = 12f
                    alpha = 0.7f
                }
            )
            addView(field)
        }

    // ---- 写回 ----

    private fun wireInputs() {
        bindNumber(xField) { value -> state.updateSelected { it.copy(x = value) } }
        bindNumber(yField) { value -> state.updateSelected { it.copy(y = value) } }
        bindNumber(widthField) { value ->
            state.updateSelected { it.copy(width = value.coerceAtLeast(0.5f)) }
        }
        bindNumber(heightField) { value ->
            state.updateSelected { it.copy(height = value.coerceAtLeast(0.5f)) }
        }

        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                state.updateSelected { it.copy(opacity = progress / 100f) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        bindColor(colorField) { color -> state.updateSelected { it.copy(color = color) } }
        bindColor(backgroundField) { color -> state.updateBackground(color) }
    }

    private fun bindNumber(field: EditText, apply: (Float) -> Unit) {
        val commit = {
            if (!syncing) {
                field.text.toString().trim().toFloatOrNull()?.let(apply)
            }
        }
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit()
                true
            } else {
                false
            }
        }
    }

    private fun bindColor(field: EditText, apply: (RgbaColor) -> Unit) {
        val commit = {
            if (!syncing) {
                val raw = field.text.toString().trim()
                try {
                    apply(RgbaColor.parseHex(raw))
                } catch (e: IllegalArgumentException) {
                    // 非法颜色：回滚显示，不把错误值写进场景。
                    refresh()
                }
            }
        }
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit()
                true
            } else {
                false
            }
        }
    }

    // ---- 刷新 ----

    private fun refresh() {
        syncing = true
        try {
            val rect = state.selectedRect
            titleView.text = if (rect == null) {
                context.getString(R.string.editor_nothing_selected)
            } else {
                context.getString(R.string.editor_selected, rect.id)
            }

            val hasSelection = rect != null
            xField.isEnabled = hasSelection
            yField.isEnabled = hasSelection
            widthField.isEnabled = hasSelection
            heightField.isEnabled = hasSelection
            opacityBar.isEnabled = hasSelection
            colorField.isEnabled = hasSelection

            if (rect != null) {
                xField.setText(format(rect.x))
                yField.setText(format(rect.y))
                widthField.setText(format(rect.width))
                heightField.setText(format(rect.height))
                opacityBar.progress = (rect.opacity * 100f).roundToInt().coerceIn(0, 100)
                colorField.setText(rect.color.toHex())
            } else {
                xField.setText("")
                yField.setText("")
                widthField.setText("")
                heightField.setText("")
                colorField.setText("")
            }
            backgroundField.setText(state.scene.root.background.toHex())
        } finally {
            syncing = false
        }
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
