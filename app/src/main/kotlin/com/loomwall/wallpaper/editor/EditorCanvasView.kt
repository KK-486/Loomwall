package com.loomwall.wallpaper.editor

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.loomwall.wallpaper.engine.SceneRenderer
import com.loomwall.wallpaper.scene.AnimatableProperty
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 编辑器画布：GLSurfaceView + 同一套 [SceneRenderer]。
 *
 * 为什么复用壁纸的渲染器而不是另写一个：所见即所得的前提是「画布和桌面用同一段绘制代码」，
 * 否则编辑器里好看、上了桌面变形，这类问题会在阶段 2 之后变得极难查。
 *
 * 交互（阶段 1 范围）：
 * - 单指点击选中矩形，按住拖动移动位置；
 * - 双指缩放画布（1x–4x），以缩放中心为锚点；
 * - 不做旋转/多选/图层树，那些属于后续阶段。
 */
class EditorCanvasView(context: Context, private val state: EditorState) : GLSurfaceView(context) {

    private val glRenderer = EditorRenderer()

    private var zoom = 1f
    /** 可见区域左上角在画布中的坐标（画布单位）。 */
    private var cameraLeft = 0f
    private var cameraTop = 0f
    private var cameraDirty = true

    private var dragPointerId = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f

    // 记录模式下的基准值：手势开始的瞬间取一次，之后按“基准 + 总位移”计算，
    // 避免每帧把增量叠加到已经被改写过的关键帧上（否则会指数级放大）。
    private var recordBaseX = 0f
    private var recordBaseY = 0f
    private var recordBaseScale = 1f
    private var recordBaseRotation = 0f
    private var scaleAccum = 1f
    private var twoFingerBaseAngle = Float.NaN

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (canRecord()) {
                    recordBaseScale = state.valueAtPlayhead(AnimatableProperty.SCALE)
                        ?: state.selectedRect?.scale ?: 1f
                    scaleAccum = 1f
                }
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (canRecord()) {
                    // 双指缩放写「缩放」关键帧，而不是缩放画布视野
                    scaleAccum *= detector.scaleFactor
                    state.writeKeyframe(AnimatableProperty.SCALE, recordBaseScale * scaleAccum)
                } else {
                    applyZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                }
                return true
            }
        }
    )

    init {
        setEGLContextClientVersion(2)
        setRenderer(glRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        state.addListener { requestRender() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestRender()
    }

    // ---- 相机 ----

    private fun visibleWidth(): Float = 100f / zoom

    private fun visibleHeight(): Float {
        val aspect = if (width == 0) 1f else height.toFloat() / width.toFloat()
        return 100f * aspect / zoom
    }

    /** 屏幕像素 → 画布单位。 */
    fun toCanvasUnit(x: Float, y: Float): FloatArray {
        if (width == 0 || height == 0) return floatArrayOf(0f, 0f)
        val cx = cameraLeft + x / width * visibleWidth()
        val cy = cameraTop + y / height * visibleHeight()
        return floatArrayOf(cx, cy)
    }

    private fun applyZoom(factor: Float, focusX: Float, focusY: Float) {
        if (width == 0 || height == 0) return
        val anchor = toCanvasUnit(focusX, focusY)
        zoom = (zoom * factor).coerceIn(1f, 4f)
        // 让锚点处的画布坐标在缩放后仍然落在同一个像素位置。
        cameraLeft = anchor[0] - focusX / width * visibleWidth()
        cameraTop = anchor[1] - focusY / height * visibleHeight()
        clampCamera()
        cameraDirty = true
        requestRender()
    }

    private fun clampCamera() {
        val maxHeight = 100f * (if (width == 0) 1f else height.toFloat() / width)
        cameraLeft = cameraLeft.coerceIn(0f, (100f - visibleWidth()).coerceAtLeast(0f))
        cameraTop = cameraTop.coerceIn(0f, (maxHeight - visibleHeight()).coerceAtLeast(0f))
    }

    fun resetCamera() {
        zoom = 1f
        cameraLeft = 0f
        cameraTop = 0f
        cameraDirty = true
        requestRender()
    }

    // ---- 触摸 ----

    /** 只有当“选中的动画”确实作用于“选中的元素”时，才允许写关键帧。 */
    private fun canRecord(): Boolean {
        val clip = state.selectedClip ?: return false
        return state.recordMode && clip.targetId == state.selectedId
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            dragPointerId = -1
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = toCanvasUnit(event.x, event.y)
                val hit = state.hitTest(point[0], point[1])
                // 命中日志在真机排查“点了没反应”时非常有用（模拟点按被系统过滤、坐标算错都靠它定位）。
                Log.d("LOOW.Canvas", "down canvas=${point[0]},${point[1]} hit=${hit?.id}")
                state.select(hit?.id)
                if (hit != null) {
                    dragPointerId = event.getPointerId(0)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    startTouchX = event.x
                    startTouchY = event.y
                    recordBaseX = state.valueAtPlayhead(AnimatableProperty.X) ?: hit.x
                    recordBaseY = state.valueAtPlayhead(AnimatableProperty.Y) ?: hit.y
                    recordBaseRotation = state.valueAtPlayhead(AnimatableProperty.ROTATION_Z) ?: hit.rotationZ
                    twoFingerBaseAngle = Float.NaN
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 两指落下时记录初始夹角，用于把旋转手势写成「旋转」关键帧
                if (event.pointerCount >= 2 && canRecord()) {
                    twoFingerBaseAngle = angleOf(event)
                    recordBaseRotation = state.valueAtPlayhead(AnimatableProperty.ROTATION_Z)
                        ?: state.selectedRect?.rotationZ ?: 0f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (width == 0 || height == 0) return true

                if (canRecord() && event.pointerCount >= 2 && !twoFingerBaseAngle.isNaN()) {
                    // 双指旋转 → 旋转关键帧
                    val delta = angleOf(event) - twoFingerBaseAngle
                    state.writeKeyframe(
                        AnimatableProperty.ROTATION_Z,
                        recordBaseRotation + delta
                    )
                    return true
                }

                if (dragPointerId == -1) return true
                val index = event.findPointerIndex(dragPointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)

                if (canRecord()) {
                    // 记录模式：把相对按下位置的**总位移**换算成画布单位，写到播放头处的关键帧
                    val totalDx = (x - startTouchX) / width * visibleWidth()
                    val totalDy = (y - startTouchY) / height * visibleHeight()
                    state.writeKeyframe(AnimatableProperty.X, recordBaseX + totalDx)
                    state.writeKeyframe(AnimatableProperty.Y, recordBaseY + totalDy)
                } else {
                    val dx = (x - lastTouchX) / width * visibleWidth()
                    val dy = (y - lastTouchY) / height * visibleHeight()
                    state.updateSelected { it.copy(x = it.x + dx, y = it.y + dy) }
                }
                lastTouchX = x
                lastTouchY = y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragPointerId = -1
                twoFingerBaseAngle = Float.NaN
            }
        }
        return true
    }

    private fun angleOf(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private inner class EditorRenderer : GLSurfaceView.Renderer {

        private val renderer = SceneRenderer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            renderer.onSurfaceCreated(width.coerceAtLeast(1), height.coerceAtLeast(1))
            cameraDirty = true
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            renderer.onSurfaceChanged(w, h)
            cameraDirty = true
        }

        override fun onDrawFrame(gl: GL10?) {
            if (cameraDirty) {
                renderer.setCamera(cameraLeft, cameraTop, zoom)
                cameraDirty = false
            }
            // state 里的 SceneDocument 是不可变快照，跨线程读取安全。
            renderer.draw(state.scene, state.selectedId, state.previewOverrides)
        }
    }
}
