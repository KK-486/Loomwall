package com.loomwall.wallpaper.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.loomwall.wallpaper.R
import com.loomwall.wallpaper.engine.AnimationRuntime
import com.loomwall.wallpaper.scene.AnimatableProperty
import com.loomwall.wallpaper.scene.AnimationTrack
import com.loomwall.wallpaper.scene.Easing
import com.loomwall.wallpaper.scene.Interactions
import com.loomwall.wallpaper.scene.LoopMode
import com.loomwall.wallpaper.scene.Trigger
import java.util.Locale

/**
 * 第 2 层：动画面板（专业向）。
 *
 * 与上一版的区别：
 * - 「新建动画」不再是模板，而是**建一条空轨道**（0s 只有起始值＝当前静态值）；
 * - 顶部有**触发方式**（常驻 / 点击）与**记录开关**；
 * - 时间轴有**播放头**，可以点/拖定位；「在当前时间插帧」在播放头处加关键帧；
 * - 开着记录开关时，在画布上拖动/缩放/旋转元素会直接写成关键帧（见 EditorCanvasView）。
 *
 * 效果模板（脉冲/呼吸/抖动等）规划为后续独立功能，不混进这里。
 */
class AnimationPanel(context: Context, private val state: EditorState) : ScrollView(context) {

    private var syncing = false

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(14))
    }

    private val propertySpinner = Spinner(context)
    private val triggerSpinner = Spinner(context)
    private val easingSpinner = Spinner(context)
    private val loopSpinner = Spinner(context)

    private val recordButton = Button(context).apply {
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), dp(2), dp(8), dp(2))
        setOnClickListener {
            state.setRecordMode(!state.recordMode)
            updateRecordButton()
        }
    }

    private val ruleHint = TextView(context).apply {
        textSize = 12f
        alpha = 0.75f
        setPadding(0, dp(8), 0, dp(2))
    }

    private val playheadLabel = TextView(context).apply {
        textSize = 12f
        alpha = 0.8f
        setPadding(0, 0, 0, dp(4))
    }

    private val timeline = TimelineView(context, state)
    private val easingPreview = EasingPreviewView(context)

    private val timeValue = TextView(context).apply { textSize = 12f; alpha = 0.8f }
    private val frameValue = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED
        imeOptions = EditorInfo.IME_ACTION_DONE
        textSize = 13f
        isSingleLine = true
    }
    private val durationField = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        imeOptions = EditorInfo.IME_ACTION_DONE
        textSize = 13f
        isSingleLine = true
    }

    private val handler = Handler(Looper.getMainLooper())
    private var previewRuntime: AnimationRuntime? = null
    private val previewTick = object : Runnable {
        override fun run() {
            val runtime = previewRuntime ?: return
            state.setPreviewOverrides(runtime.evaluate(SystemClock.uptimeMillis()))
            if (runtime.isAnimating()) {
                handler.postDelayed(this, 16L)
            } else {
                previewRuntime = null
                state.setPreviewOverrides(emptyMap())
            }
        }
    }

    init {
        addView(content)
        content.addView(sectionLabel(R.string.editor_animation_section))

        content.addView(row(triggerSpinner, recordButton))
        content.addView(row(propertySpinner, smallButton(R.string.editor_new_animation) { createAnimation() }))
        content.addView(
            row(
                smallButton(R.string.editor_add_track) { addTrack() },
                smallButton(R.string.editor_delete_animation) { deleteAnimation() }
            )
        )
        content.addView(smallButton(R.string.editor_insert_keyframe) { insertKeyframeAtPlayhead() })

        content.addView(ruleHint)
        content.addView(playheadLabel)
        content.addView(timeline)
        content.addView(keyframeEditor())
        content.addView(durationRow())
        content.addView(smallButton(R.string.editor_preview) { preview() })

        timeline.onKeyframeSelected = { ref -> state.selectKeyframe(ref) }
        timeline.onKeyframeMoved = { ref, t -> state.moveKeyframe(ref, t) }
        timeline.onPlayheadChanged = { t -> state.setPlayhead(t) }

        triggerSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            Trigger.Type.entries.map { it.displayName() }
        )
        triggerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (syncing) return
                state.setClipTrigger(Trigger.Type.entries[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        propertySpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            AnimatableProperty.entries.map { it.displayName() }
        )

        easingSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            Easing.names.map { Easing.displayName(it) }
        )
        easingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (syncing) return
                val ref = state.selectedKeyframe ?: return
                state.setKeyframeEasing(ref, Easing.parse(Easing.names[position]))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        loopSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            LoopMode.entries.map { it.displayName() }
        )
        loopSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (syncing) return
                state.setClipLoop(LoopMode.entries[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        updateRecordButton()
        state.addListener { sync() }
        sync()
    }

    override fun onDetachedFromWindow() {
        stopPreview()
        super.onDetachedFromWindow()
    }

    // ---- 构建 ----

    private fun sectionLabel(res: Int) = TextView(context).apply {
        text = context.getString(res)
        textSize = 13f
        setPadding(0, dp(2), 0, dp(6))
        alpha = 0.9f
    }

    private fun keyframeEditor(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(timeValue)
        addView(row(frameValue, easingSpinner))
        addView(easingPreview)
        addView(smallButton(R.string.editor_delete_keyframe) { deleteKeyframe() })
    }

    private fun durationRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, 0)
        addView(
            TextView(context).apply {
                text = context.getString(R.string.editor_duration)
                textSize = 12f
                alpha = 0.7f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(durationField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(loopSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun row(first: View, second: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun smallButton(labelRes: Int, onClick: () -> Unit) = Button(context).apply {
        text = context.getString(labelRes)
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), dp(2), dp(8), dp(2))
        setOnClickListener { onClick() }
    }

    private fun updateRecordButton() {
        recordButton.text = context.getString(
            if (state.recordMode) R.string.editor_record_on else R.string.editor_record_off
        )
        recordButton.setBackgroundColor(
            if (state.recordMode) android.graphics.Color.parseColor("#FF2F6B4F")
            else android.graphics.Color.parseColor("#FF2A313B")
        )
    }

    // ---- 行为 ----

    private fun createAnimation() {
        val property = AnimatableProperty.entries[propertySpinner.selectedItemPosition]
        val clipId = state.addAnimation(property)
        if (clipId == null) {
            toast(context.getString(R.string.editor_pick_element_first))
        } else {
            toast(context.getString(R.string.editor_animation_created, clipId))
        }
    }

    private fun deleteAnimation() {
        if (state.selectedClip == null) return
        state.removeSelectedClip()
        toast(context.getString(R.string.editor_animation_deleted))
    }

    private fun addTrack() {
        val property = AnimatableProperty.entries[propertySpinner.selectedItemPosition]
        if (!state.addTrack(property)) {
            toast(context.getString(R.string.editor_track_duplicate))
        }
    }

    private fun insertKeyframeAtPlayhead() {
        val clip = state.selectedClip
        if (clip == null) {
            toast(context.getString(R.string.editor_no_animation))
            return
        }
        val property = state.selectedKeyframe?.property
            ?: clip.tracks.firstOrNull()?.property
        if (property == null) return

        val track = clip.tracks.firstOrNull { it.property == property }
        val value = track?.sample(state.playhead) ?: return
        if (state.writeKeyframe(property, value)) {
            toast(context.getString(R.string.editor_keyframe_inserted))
        } else {
            toast(context.getString(R.string.editor_keyframe_too_close))
        }
    }

    private fun deleteKeyframe() {
        val ref = state.selectedKeyframe ?: return
        if (!state.removeKeyframe(ref)) {
            toast(context.getString(R.string.editor_keyframe_minimum))
        }
    }

    private fun preview() {
        val clip = state.selectedClip ?: return
        stopPreview()
        val runtime = AnimationRuntime(Interactions(Interactions.EMPTY.formatVersion, listOf(clip)))
        previewRuntime = runtime
        val now = SystemClock.uptimeMillis()
        if (clip.trigger.type == Trigger.Type.ALWAYS) {
            runtime.ensureAlwaysStarted(now)
        } else {
            runtime.onTap(clip.targetId, now)
        }
        handler.post(previewTick)
    }

    private fun stopPreview() {
        handler.removeCallbacks(previewTick)
        previewRuntime = null
        state.setPreviewOverrides(emptyMap())
    }

    // ---- 刷新 ----

    private fun sync() {
        syncing = true
        try {
            val clip = state.selectedClip
            val triggerIndex = Trigger.Type.entries.indexOf(clip?.trigger?.type ?: Trigger.Type.ALWAYS)
            if (triggerIndex >= 0) triggerSpinner.setSelection(triggerIndex, false)
            updateRecordButton()

            ruleHint.text = if (clip == null) {
                context.getString(R.string.editor_no_animation)
            } else {
                context.getString(
                    R.string.editor_rule_hint,
                    clip.trigger.type.displayName(),
                    clip.targetId,
                    clip.id
                )
            }
            playheadLabel.text = context.getString(
                R.string.editor_playhead,
                String.format(Locale.US, "%.2f", state.playhead),
                String.format(Locale.US, "%.0f", state.playhead * (clip?.tracks?.firstOrNull()?.durationMs ?: 0) / 1000f * 1000f)
            )

            val ref = state.selectedKeyframe
            val track = clip?.tracks?.firstOrNull { it.property == ref?.property }
            val frame = ref?.let { track?.keyframes?.getOrNull(it.index) }

            if (frame != null && ref != null) {
                timeValue.text = context.getString(
                    R.string.editor_keyframe_time,
                    String.format(Locale.US, "%.2f", frame.t)
                )
                if (!frameValue.hasFocus()) {
                    frameValue.setText(String.format(Locale.US, "%.1f", frame.value))
                }
                frameValue.isEnabled = true
                val easingIndex = Easing.names.indexOfFirst { Easing.parse(it) == frame.easing }
                if (easingIndex >= 0) easingSpinner.setSelection(easingIndex, false)
                easingPreview.easing = frame.easing
                easingPreview.visibility = View.VISIBLE
            } else {
                timeValue.text = context.getString(R.string.editor_pick_keyframe)
                frameValue.isEnabled = false
                easingPreview.visibility = View.GONE
            }

            clip?.let {
                if (!durationField.hasFocus()) {
                    durationField.setText(it.tracks.firstOrNull()?.durationMs?.toString() ?: "")
                }
                val loopIndex = LoopMode.entries.indexOf(it.tracks.firstOrNull()?.loop ?: LoopMode.ONCE)
                if (loopIndex >= 0) loopSpinner.setSelection(loopIndex, false)
            }

            timeline.invalidate()
            timeline.requestLayout()

            frameValue.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) commitValue()
            }
            frameValue.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitValue()
                    true
                } else {
                    false
                }
            }
            durationField.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) commitDuration()
            }
        } finally {
            syncing = false
        }
    }

    private fun commitValue() {
        if (syncing) return
        val ref = state.selectedKeyframe ?: return
        val value = frameValue.text.toString().trim().toFloatOrNull() ?: return
        state.setKeyframeValue(ref, value)
    }

    private fun commitDuration() {
        if (syncing) return
        val duration = durationField.text.toString().trim().toIntOrNull() ?: return
        state.setClipDuration(duration)
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
