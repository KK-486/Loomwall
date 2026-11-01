package com.loomwall.wallpaper.preview

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.loomwall.wallpaper.engine.RenderThread
import com.loomwall.wallpaper.engine.SystemInputHub
import com.loomwall.wallpaper.scene.PresetStore

/**
 * 渲染自检页（**仅 debug 构建**）。
 *
 * 存在的唯一理由：在缺少动态壁纸选择器的设备/模拟器上，也能把
 * EglCore + SceneRenderer + RenderThread 这套管线真正跑起来看结果。
 * 它复用的是壁纸服务里完全相同的类和调用顺序，区别只是 Surface 来自 SurfaceView。
 */
class PreviewActivity : Activity() {

    private var renderThread: RenderThread? = null
    private var systemVars: SystemInputHub? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val presetStore = PresetStore(applicationContext)
        val thread = RenderThread(
            sceneSource = { presetStore.loadActive() },
            resourcesDirProvider = { presetStore.activeResourcesDir() },
            context = this
        )
        renderThread = thread
        // 预览页同样需要系统变量，否则文本里的 {sys.*} 占位符无法替换。
        systemVars = SystemInputHub(applicationContext) { thread.requestRender() }

        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "SurfaceView created: ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
                thread.onSurfaceAvailable(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "SurfaceView changed: ${width}x$height")
                thread.onSurfaceSizeChanged()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                thread.onSurfaceAvailable(null)
            }
        })

        setContentView(surfaceView)

        // 直接复用壁纸的 RenderThread：它已经包含“点击 → 命中测试 → 动画 → 逐帧绘制”的完整链路。
        // 这里只把触摸喂进去，用来在没有壁纸选择器、或启动器不转发触摸的环境里验证交互动。
        surfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "触摸按下 ${event.x},${event.y}")
                    downX = event.x
                    downY = event.y
                    downAtMs = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (SystemClock.uptimeMillis() - downAtMs <= 400L &&
                        kotlin.math.abs(event.x - downX) <= 40f &&
                        kotlin.math.abs(event.y - downY) <= 40f
                    ) {
                        thread.onGesture(com.loomwall.wallpaper.scene.Gesture.TAP, event.x, event.y)
                        thread.onTapSurface(event.x, event.y)
                    }
                    true
                }
                else -> true
            }
        }

        thread.onVisibilityChanged(true)
        thread.start()
    }

    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L

    override fun onResume() {
        super.onResume()
        systemVars?.attach()
        renderThread?.onVisibilityChanged(true)
    }

    override fun onPause() {
        renderThread?.onVisibilityChanged(false)
        systemVars?.detach()
        super.onPause()
    }

    override fun onDestroy() {
        systemVars?.detach()
        systemVars = null
        renderThread?.let { thread ->
            thread.quit()
            runCatching { thread.join(2000) }
        }
        renderThread = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LOOW.Preview"
    }
}
