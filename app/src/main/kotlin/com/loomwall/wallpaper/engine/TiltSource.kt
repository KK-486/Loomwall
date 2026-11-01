package com.loomwall.wallpaper.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * 倾斜数据源：把设备姿态归一化成 -1..1 的倾斜向量，供视差使用。
 *
 * 为什么用重力/加速度而不是陀螺仪：
 * 陀螺仪给的是角速度，积分求角度会持续漂移（几分钟后画面就歪了）；
 * 重力方向是绝对参考，稳定、便宜，而且这个传感器是 Non-wakeup 的。
 * 陀螺仪只适合做"甩动惯性"，属于后续增强，不进第一版。
 *
 * 功耗纪律（项目红线）：
 * - 只在壁纸可见时注册，不可见立刻注销 —— [attach] / [detach] 成对调用；
 * - 用 SENSOR_DELAY_GAME 但**节流**到约 30Hz，超出的采样直接丢；
 * - 变化小于死区时不回调 onChanged，调用方就不会重绘。
 */
object TiltSource {

    private const val TAG = "LOOW.Tilt"
    private const val MIN_INTERVAL_NS = 33_000_000L   // ≈30Hz
    private const val GRAVITY = 9.80665f
    /** 倾斜增益：把常见的 10~20° 手持倾斜放大到接近满量程。 */
    private const val TILT_GAIN = 2.5f

    @Volatile
    private var tiltX = 0f

    @Volatile
    private var tiltY = 0f

    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var lastEventNs = 0L
    private var lastEmitX = 0f
    private var lastEmitY = 0f
    private var smoothing = 0.18f
    private var deadzone = 0.04f
    private var onChanged: (() -> Unit)? = null

    fun setFilter(nextSmoothing: Float, nextDeadzone: Float) {
        smoothing = nextSmoothing.coerceIn(0.02f, 1f)
        deadzone = nextDeadzone.coerceIn(0f, 0.5f)
    }

    fun tilt(): FloatArray = floatArrayOf(tiltX, tiltY)

    fun attach(context: Context, onChange: () -> Unit) {
        if (listener != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        manager = sm
        onChanged = onChange

        // 优先 TYPE_GRAVITY（系统已融合、更平滑），没有就退回加速度计
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            Log.i(TAG, "设备没有重力/加速度传感器，视差保持静态")
            return
        }

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.nanoTime()
                if (now - lastEventNs < MIN_INTERVAL_NS) return
                lastEventNs = now

                // 归一化：重力向量在设备坐标系下的 x/y 分量除以 g
                var rawX = event.values[0] / GRAVITY
                var rawY = event.values[1] / GRAVITY

                // 语义对齐：手机往左倾 → 画面往左。左右倾改变的是 x 分量，
                // 但符号与"左手坐标系"有关，这里取反使得视觉上顺边。
                // 增益：手持倾斜通常只有 10~20°（sin≈0.17~0.34），
                // 1:1 映射会小到看不出来。放大 2.5 倍再钳制，手感接近壁纸引擎。
                rawX *= TILT_GAIN
                rawY *= TILT_GAIN
                // 增益之后必须重新钳制：否则倾斜大时位移会冲到范围外（实测溢出到 -1.34）
                rawX = rawX.coerceIn(-1f, 1f)
                rawY = rawY.coerceIn(-1f, 1f)
                rawX = -rawX
                rawY = -rawY

                // 死区：手部微小抖动不响应
                val tx = if (kotlin.math.abs(rawX) < deadzone) 0f else rawX
                val ty = if (kotlin.math.abs(rawY) < deadzone) 0f else rawY

                // 指数平滑（与编辑器同一种手感）
                tiltX += (tx - tiltX) * smoothing
                tiltY += (ty - tiltY) * smoothing

                // 只有真的变了才通知渲染线程，避免静止时反复重绘
                if (kotlin.math.abs(tiltX - lastEmitX) > 0.002f || kotlin.math.abs(tiltY - lastEmitY) > 0.002f) {
                    lastEmitX = tiltX
                    lastEmitY = tiltY
                    onChanged?.invoke()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        listener = l
        Log.i(TAG, "倾斜传感器已注册：${sensor.name}")
    }

    fun detach() {
        val l = listener ?: return
        manager?.unregisterListener(l)
        listener = null
        manager = null
        // 注销时归零，下次可见从"中立姿态"开始，不会突然跳一下
        tiltX = 0f
        tiltY = 0f
        lastEmitX = 0f
        lastEmitY = 0f
        Log.i(TAG, "倾斜传感器已注销")
    }
}
