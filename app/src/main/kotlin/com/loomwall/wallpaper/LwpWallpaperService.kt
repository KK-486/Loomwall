package com.loomwall.wallpaper

import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.loomwall.wallpaper.engine.RenderThread
import com.loomwall.wallpaper.engine.SystemInputHub
import com.loomwall.wallpaper.scene.EngineVersion
import com.loomwall.wallpaper.scene.Gesture
import com.loomwall.wallpaper.scene.PresetStore
import kotlin.math.abs

/**
 * 壁纸宿主。
 *
 * 阶段 0 的职责只有三件：把 Surface 交给渲染线程、跟随可见性启停、不让它崩。
 * 明确不做：编辑器 UI、动画、触摸、系统广播（阶段 1–4 依次接入）。
 *
 * 注意：`WallpaperService.Engine` 自己就是 SurfaceHolder 回调的接收方，
 * 它把 `surfaceCreated/Changed/Destroyed` 转成下面的 `onSurface*` 方法。
 * 所以这里不需要（也不应该）再 `addCallback(this)`——那样会多收一份重复回调。
 */
class LwpWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SceneEngine()

    private inner class SceneEngine : Engine() {

        private val presetStore: PresetStore by lazy { PresetStore(applicationContext) }
        private var renderThread: RenderThread? = null
        private var systemVars: SystemInputHub? = null
        private var visible = false
        private var hasBindings = false
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        private var downX = 0f
        private var downY = 0f
        private var downAtMs = 0L
        private var lastTapAtMs = 0L
        private var longPressFired = false
        private var reloadReceiver: android.content.BroadcastReceiver? = null

        /**
         * 轻点判定：时间短、位移小。
         * 刻意不做完整手势识别——滑动属于阶段 3 的滚动视差，这里只区分“点”和“不是点”。
         *
         * 注意 `Engine.onTouchEvent` 的返回类型是 void（不是 Boolean），
         * 所以这里不能写 `Boolean`，否则编译不过。
         */
        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAtMs = SystemClock.uptimeMillis()
                    longPressFired = false
                    // 这条日志用来判断“启动器到底有没有把触摸转发给壁纸”。
                    // MIUI 桌面实测不转发，看到这条日志才说明交互动有戏。
                    Log.d(TAG, "touch down ${event.x},${event.y}")
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val duration = SystemClock.uptimeMillis() - downAtMs
                    val slop = tapSlop()
                    val dx = event.x - downX
                    val dy = event.y - downY

                    when {
                        // 长按已经触发过，抬手不再产生点击
                        longPressFired -> Unit

                        // 位移超过阈值 → 滑动
                        abs(dx) > slop || abs(dy) > slop -> {
                            val gesture = if (abs(dx) >= abs(dy)) {
                                if (dx < 0) Gesture.SWIPE_LEFT else Gesture.SWIPE_RIGHT
                            } else {
                                if (dy < 0) Gesture.SWIPE_UP else Gesture.SWIPE_DOWN
                            }
                            Log.d(TAG, "手势：滑动 $gesture")
                            renderThread?.onGesture(gesture, downX, downY)
                        }

                        // 双击窗口内的第二次点击
                        duration <= TAP_TIMEOUT_MS &&
                            SystemClock.uptimeMillis() - lastTapAtMs <= DOUBLE_TAP_MS -> {
                            Log.d(TAG, "手势：双击")
                            renderThread?.onGesture(Gesture.DOUBLE_TAP, event.x, event.y)
                            lastTapAtMs = 0L
                        }

                        duration <= TAP_TIMEOUT_MS -> {
                            Log.d(TAG, "手势：单击")
                            lastTapAtMs = SystemClock.uptimeMillis()
                            renderThread?.onGesture(Gesture.TAP, event.x, event.y)
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressFired = false
                }
            }
        }

        /** 按住不动超过 [LONG_PRESS_MS] 就发长按；移动或抬手都会取消。 */
        private val longPressRunnable = object : Runnable {
            override fun run() {
                longPressFired = true
                Log.d(TAG, "手势：长按")
                renderThread?.onGesture(Gesture.LONG_PRESS, downX, downY)
            }
        }

        /**
         * 从壁纸里启动应用/打开链接。
         *
         * 注意：动态壁纸属于后台进程，Android 10+ 对「后台启动 Activity」有限制，
         * 但**用户直接触摸壁纸**属于允许的场景，实测可用。仍要 catch 住失败，
         * 不能让一次启动失败把壁纸搞崩。
         */
        private fun launchFromWallpaper(action: com.loomwall.wallpaper.scene.EventAction) {
            val intent = when (action) {
                is com.loomwall.wallpaper.scene.EventAction.LaunchApp -> {
                    applicationContext.packageManager.getLaunchIntentForPackage(action.packageName)
                }
                is com.loomwall.wallpaper.scene.EventAction.OpenUri -> {
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(action.uri)
                    )
                }
                else -> null
            }
            if (intent == null) {
                Log.w(TAG, "找不到可启动的目标：$action")
                return
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { applicationContext.startActivity(intent) }
                .onFailure { Log.w(TAG, "启动被系统拒绝：${it.message}") }
        }
        private fun tapSlop(): Float =
            24f * this@LwpWallpaperService.resources.displayMetrics.density

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.i(TAG, "引擎创建（引擎版本 ${EngineVersion.CURRENT}，场景格式 ${EngineVersion.SCENE_FORMAT}）")

            /*
             * 记一个"本应用确实被系统设成壁纸"的标记。
             *
             * 为什么需要：MIUI 上 `WallpaperManager.getWallpaperInfo()` 可能返回 null，
             * 主页据此判断就会一直认为"还没设置壁纸"，于是每次换壁纸都跳系统选择器
             * （用户实测反馈）。壁纸引擎被创建这件事本身就证明它是当前壁纸。
             */
            runCatching {
                getSharedPreferences("loow", MODE_PRIVATE).edit()
                    .putBoolean("wallpaper_applied", true).apply()
            }

            // 阶段 2 起接收触摸：只用于“点击触发动画”。
            // 滚动偏移（onOffsetsChanged）仍属于阶段 3，这里不碰。
            setTouchEventsEnabled(true)

            // 手机端接收服务导入新预设后，发一条同应用广播让壁纸立刻重读，用户不用回桌面等。
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    Log.i(TAG, "收到重载请求，重新读取预设")
                    renderThread?.requestReload()
                }
            }
            val filter = android.content.IntentFilter(ACTION_RELOAD)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            reloadReceiver = receiver
            renderThread = RenderThread(
                sceneSource = { presetStore.loadActive() },
                resourcesDirProvider = { presetStore.activeResourcesDir() },
                onFps = { fps -> systemVars?.notifyFps(fps) },
                onLaunch = { action -> launchFromWallpaper(action) },
                context = applicationContext,
                onBindingsChanged = { has ->
                    // 回调来自渲染线程，切回主线程再订阅/注销
                    mainHandler.post {
                        hasBindings = has
                        updateSystemVars()
                    }
                }
            ).also {
                it.start()
            }
            // 系统变量：变量变化只做“请求重绘”，真正的读快照发生在渲染线程。
            systemVars = SystemInputHub(applicationContext) { renderThread?.requestRender() }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            renderThread?.onVisibilityChanged(visible)
            // 功耗红线：不可见时连系统广播/定时器一起注销，不留任何唤醒源。
            updateSystemVars()
        }

        /**
         * 只有「可见 + 预设里真的有绑定」时，才订阅系统数据源。
         * 没有绑定的预设（现阶段绝大多数）**一个广播都不注册**。
         */
        private fun updateSystemVars() {
            val shouldAttach = visible && hasBindings
            if (shouldAttach) systemVars?.attach() else systemVars?.detach()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderThread?.onSurfaceAvailable(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderThread?.onSurfaceSizeChanged()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            renderThread?.onSurfaceAvailable(null)
        }

        override fun onDestroy() {
            Log.i(TAG, "引擎销毁")
            reloadReceiver?.let { runCatching { unregisterReceiver(it) } }
            reloadReceiver = null
            systemVars?.detach()
            systemVars = null
            renderThread?.let { thread ->
                thread.quit()
                runCatching { thread.join(2000) }
            }
            renderThread = null
            super.onDestroy()
        }
    }

    companion object {
        /** 导入新预设后广播，让运行中的壁纸立刻重读磁盘。 */
        const val ACTION_RELOAD = "com.loomwall.wallpaper.action.RELOAD"
        const val TAG = "LOOW.Service"

        const val TAP_TIMEOUT_MS = 400L

        /** 按住多久算长按。 */
        const val LONG_PRESS_MS = 500L

        /** 两次点击间隔小于该值算双击。 */
        const val DOUBLE_TAP_MS = 280L
    }
}
