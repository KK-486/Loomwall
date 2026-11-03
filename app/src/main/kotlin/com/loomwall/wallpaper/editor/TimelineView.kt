package com.loomwall.wallpaper.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.loomwall.wallpaper.scene.AnimatableProperty

/** 属性在 UI 上的中文名，避免界面里出现英文枚举。 */
fun AnimatableProperty.displayName(): String = when (this) {
    AnimatableProperty.X -> "X 位置"
    AnimatableProperty.Y -> "Y 位置"
    AnimatableProperty.SCALE -> "缩放"
    AnimatableProperty.ROTATION_Z -> "旋转"
    AnimatableProperty.OPACITY -> "透明度"
    AnimatableProperty.WIDTH -> "宽度"
    AnimatableProperty.HEIGHT -> "高度"
}

/**
 * 时间轴：每个属性一条水平轨道，关键帧用菱形表示。
 *
 * 交互（第 2 层编辑器的核心，刻意不用节点连线图）：
 * - 点关键帧 → 选中（下方出现数值与缓动编辑）；
 * - 拖关键帧 → 改时间（首尾钉死在 0 与 1，中间帧被相邻帧夹住）；
 * - 点轨道空白处 → 在该时间插入关键帧。
 */
class TimelineView(context: Context, private val state: EditorState) : View(context) {

    var onKeyframeSelected: ((EditorState.KeyframeRef) -> Unit)? = null
    var onKeyframeMoved: ((EditorState.KeyframeRef, Float) -> Unit)? = null
    var onPlayheadChanged: ((Float) -> Unit)? = null

    private val rowHeight = dp(56f)
    private val labelWidth = dp(76f)
    private val sidePadding = dp(10f)
    private val diamondRadius = dp(8f)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF12161C") }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF171C24") }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A313B")
        strokeWidth = dp(2f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB9C2CF")
        textSize = dp(12f)
    }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF3DDC97") }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFFDB4D") }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFF6B6B") }
    private val playheadPath = Path()
    private val diamond = Path()

    private var dragging: EditorState.KeyframeRef? = null
    private var dragRowIndex = -1
    private var scrubbing = false

    init {
        state.addListener { invalidate() }
        setBackgroundColor(Color.parseColor("#FF0E1218"))
    }

    private val tracks: List<Pair<AnimatableProperty, com.loomwall.wallpaper.scene.AnimationTrack>>
        get() = state.selectedClip?.tracks?.map { it.property to it } ?: emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = tracks.size.coerceAtLeast(1)
        val height = (rowHeight * count).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val clip = state.selectedClip
        if (clip == null) {
            labelPaint.color = Color.parseColor("#FF6B7480")
            canvas.drawText("还没有动画：选中元素后点上方「新建动画」", sidePadding, rowHeight * 0.6f, labelPaint)
            return
        }

        val startX = labelWidth + sidePadding
        val endX = width - sidePadding
        val span = (endX - startX).coerceAtLeast(1f)
        val selected = state.selectedKeyframe

        clip.tracks.forEachIndexed { rowIndex, track ->
            val top = rowIndex * rowHeight

            if (rowIndex % 2 == 1) {
                canvas.drawRect(0f, top, width.toFloat(), top + rowHeight, rowPaint)
            }

            // 轨道基线
            val baseY = top + rowHeight / 2f
            canvas.drawLine(startX, baseY, endX, baseY, baselinePaint)

            // 首尾刻度
            canvas.drawLine(startX, top + dp(10f), startX, top + rowHeight - dp(10f), baselinePaint)
            canvas.drawLine(endX, top + dp(10f), endX, top + rowHeight - dp(10f), baselinePaint)

            labelPaint.color = Color.parseColor("#FFB9C2CF")
            canvas.drawText(track.property.displayName(), sidePadding, baseY + dp(4f), labelPaint)

            track.keyframes.forEachIndexed { index, keyframe ->
                val x = startX + keyframe.t * span
                val isSelected = selected != null &&
                    selected.clipId == clip.id &&
                    selected.property == track.property &&
                    selected.index == index
                drawDiamond(canvas, x, baseY, isSelected)
            }
        }

        // 播放头：竖线 + 顶部小三角，始终画在最上层。
        val playheadX = startX + state.playhead * span
        canvas.drawLine(playheadX, 0f, playheadX, height.toFloat(), playheadPaint)
        playheadPath.reset()
        playheadPath.moveTo(playheadX - dp(7f), 0f)
        playheadPath.lineTo(playheadX + dp(7f), 0f)
        playheadPath.lineTo(playheadX, dp(12f))
        playheadPath.close()
        canvas.drawPath(playheadPath, playheadPaint)
    }

    private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, selected: Boolean) {
        val radius = if (selected) diamondRadius * 1.35f else diamondRadius
        diamond.reset()
        diamond.moveTo(cx, cy - radius)
        diamond.lineTo(cx + radius, cy)
        diamond.lineTo(cx, cy + radius)
        diamond.lineTo(cx - radius, cy)
        diamond.close()
        canvas.drawPath(diamond, if (selected) selectedPaint else keyPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val clip = state.selectedClip ?: return false
        val startX = labelWidth + sidePadding
        val endX = width - sidePadding
        val span = (endX - startX).coerceAtLeast(1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val rowIndex = (event.y / rowHeight).toInt()
                if (rowIndex !in clip.tracks.indices) return false
                val track = clip.tracks[rowIndex]
                val t = ((event.x - startX) / span).coerceIn(0f, 1f)

                // 先找最近的關鍵帧，命中半径按手指友好放宽到 26dp。
                val nearestIndex = track.keyframes.indices.minByOrNull { index ->
                    kotlin.math.abs(startX + track.keyframes[index].t * span - event.x)
                } ?: return false
                val nearestDistance = kotlin.math.abs(startX + track.keyframes[nearestIndex].t * span - event.x)

                if (nearestDistance <= dp(26f)) {
                    val ref = EditorState.KeyframeRef(clip.id, track.property, nearestIndex)
                    dragging = ref
                    dragRowIndex = rowIndex
                    onKeyframeSelected?.invoke(ref)
                } else {
                    // 空白处按下 = 移动播放头（并允许拖动连续 scrubbing），
                    // 加点通过面板上的「在当前时间插帧」按钮完成，避免误触加出一堆帧。
                    scrubbing = true
                    onPlayheadChanged?.invoke(t)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrubbing) {
                    onPlayheadChanged?.invoke(((event.x - startX) / span).coerceIn(0f, 1f))
                    return true
                }
                val ref = dragging ?: return true
                onKeyframeMoved?.invoke(ref, ((event.x - startX) / span).coerceIn(0f, 1f))
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                dragRowIndex = -1
                scrubbing = false
                return true
            }
        }
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
