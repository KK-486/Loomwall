package com.loomwall.wallpaper.engine

import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.loomwall.wallpaper.scene.EventAction
import com.loomwall.wallpaper.scene.Gesture
import com.loomwall.wallpaper.scene.NodeEvent
import com.loomwall.wallpaper.scene.PresetStore
import com.loomwall.wallpaper.scene.RectNode
import com.loomwall.wallpaper.scene.SceneDocument

/**
 * 唯一的 GL 线程。
 *
 * 关键设计（对应项目功耗红线）：
 * - **按需绘制**：没有动画时不会跑 60fps 空转；只有 `needsRender` 为 true 才画一帧。
 * - **不可见即彻底停手**：`visible = false` 时销毁窗口表面（GPU 无呈现目标），线程阻塞在 `wait()` 上，
 *   既不绘制也不轮询。屏幕点亮、壁纸重新可见时才恢复。
 * - **只在渲染线程碰 GL**：EGL 上下文绑定在本线程，其他线程只改受锁保护的状态标志。
 * - **场景在渲染线程加载**：避免在主线程做文件 IO。
 */
internal class RenderThread(
    private val sceneSource: () -> PresetStore.LoadResult,
    /** 预设资源目录（图片走这里）。 */
    private val resourcesDirProvider: () -> java.io.File = { java.io.File("/nonexistent") },
    /** 每 2 秒回报一次实际帧率，供 sys.render.fps 变量使用。 */
    private val onFps: (Float) -> Unit = {},
    /** 场景是否含系统变量绑定；服务据此决定要不要订阅系统广播。 */
    private val onBindingsChanged: (Boolean) -> Unit = {},
    /** 启动应用 / 打开链接：真正的 startActivity 由服务（有 Context）执行。 */
    private val onLaunch: (com.loomwall.wallpaper.scene.EventAction) -> Unit = {},
    /** 注册传感器需要 Context（只用于取 SensorManager，不持有长生命周期引用以外的东西）。 */
    private val context: android.content.Context
) : Thread("LOOW-Render") {

    private enum class Decision { DRAW, SUSPEND, QUIT }

    private val lock = Object()

    private var fpsWindowStart = 0L
    private var fpsFrames = 0

    // ---- 以下字段全部由 lock 保护 ----
    private var quit = false
    private var visible = false
    private var pendingSurface: Surface? = null
    private var needsRender = true
    private val pendingGestures = ArrayList<Triple<Gesture, Float, Float>>()
    private var glAttached = false
    private var reloadRequested = false
    private var pendingTapX = -1f
    private var pendingTapY = -1f

    // ---- 仅渲染线程访问 ----
    private val egl = EglCore()
    private val renderer = SceneRenderer()
    private var scene: SceneDocument? = null
    private var sceneLoaded = false
    private var runtime: AnimationRuntime? = null
    private var bindings: List<com.loomwall.wallpaper.scene.Binding> = emptyList()
    private var events: List<com.loomwall.wallpaper.scene.NodeEvent> = emptyList()
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0
    // 当前预设的全局视差设置（每次加载预设后同步）
    @Volatile
    private var parallaxSettings = com.loomwall.wallpaper.scene.ParallaxSettings.DEFAULT

    /**
     * 视差显示值（渲染线程独占）。
     *
     * 传感器按 30Hz 送值，如果"来一个事件画一帧"，画面就是 30fps，且帧间隔与 vsync
     * 对不齐，看起来像 25fps 卡顿（用户实测：手机卡、浏览器预览顺）。
     * 这里保留一个显示值，每帧朝传感器目标做时间相关插值：输入仍是 30Hz（省电不变），
     * 输出跟着 vsync 平滑过渡，收敛后立即停止绘制。
     */
    /** 音频插值用的上一帧时间戳（渲染线程独占）。 */
    private var lastAudioTickNs = 0L

    private var tiltShownX = 0f
    private var tiltShownY = 0f
    private var lastFrameNs = 0L

    /** 触发器运行时：vars/rules/functions 的求值器。为空表示预设没有规则。 */
    /**
     * 活跃的属性补间（动画）。每帧推进，进度结束后移除。
     *
     * 为什么放在渲染线程而不是 RuleRuntime：补间需要"每帧"推进，
     * 而规则只在事件发生时跑一次；放这里才能保证帧循环里被消费。
     */
    /**
     * 规则能动的属性。
     *
     * width / height 是几何量：纯色形状重新三角化、贴图类元素按新盒子拉伸，
     * 都不需要重排布局，所以可以进白名单（此前误判为"成本高"而排除）。
     */
    private val SUPPORTED_RULE_PROPS = setOf("x", "y", "scale", "rotationZ", "opacity", "width", "height")

    private val tweens = ArrayList<PropTween>()

    /** 循环任务：按规则 id 定期重放动作。只在壁纸可见时推进。 */
    private class RepeatJob(
        val ruleId: String,
        var remaining: Int,
        val intervalMs: Int,
        var nextAt: Long
    )

    private val repeatJobs = ArrayList<RepeatJob>()

    /** 往 override 里写一个属性，返回新的 override。 */
    private fun withProp(
        base: com.loomwall.wallpaper.scene.NodeOverrides,
        property: String,
        value: Float
    ): com.loomwall.wallpaper.scene.NodeOverrides = when (property) {
        "x" -> base.copy(x = value)
        "y" -> base.copy(y = value)
        "scale" -> base.copy(scale = value)
        "rotationZ" -> base.copy(rotationZ = value)
        "opacity" -> base.copy(opacity = value)
        "width" -> base.copy(width = value.coerceAtLeast(0f))
        "height" -> base.copy(height = value.coerceAtLeast(0f))
        else -> base
    }

    /** 某属性"此刻显示的值"：优先运行期覆盖，其次场景静态值。 */
    private fun currentPropValue(targetId: String, property: String): Float {
        val over = ruleProps[targetId]
        val live = when (property) {
            "x" -> over?.x
            "y" -> over?.y
            "scale" -> over?.scale
            "rotationZ" -> over?.rotationZ
            "opacity" -> over?.opacity
            else -> null
        }
        if (live != null) return live
        return renderer.staticPropertyOf(targetId, property) ?: 0f
    }

    /**
     * 推进所有补间。每帧调用；有活跃补间就保持渲染。
     *
     * 起点取"开始那一刻的显示值"，所以快速连点（开了立刻关）也不会跳变。
     */
    /**
     * 推进循环任务：到点就重放那条规则。
     *
     * 只在壁纸可见时推进；不可见（visible=false）时由调用方跳过，任务保留但不执行。
     */
    private fun advanceRepeats(nowMs: Long) {
        if (repeatJobs.isEmpty()) return
        if (!synchronized(lock) { visible }) return
        var ran = false
        val iterator = repeatJobs.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (nowMs < job.nextAt) continue
            ruleRuntime?.replay(job.ruleId)
            if (job.remaining != Int.MAX_VALUE) job.remaining -= 1
            if (job.remaining <= 0) {
                iterator.remove()
                Log.d(TAG, "循环结束：${job.ruleId}")
            } else {
                job.nextAt = nowMs + job.intervalMs
            }
            ran = true
        }
        if (ran) synchronized(lock) { needsRender = true }
    }

    /** 下一个循环任务还有多久到点（给等待超时用）。 */
    private fun nextRepeatDelay(nowMs: Long): Long {
        if (repeatJobs.isEmpty()) return Long.MAX_VALUE
        return repeatJobs.minOf { it.nextAt - nowMs }.coerceAtLeast(0L)
    }

    private fun advanceTweens(nowMs: Long) {
        if (tweens.isEmpty()) return
        val iterator = tweens.iterator()
        while (iterator.hasNext()) {
            val tween = iterator.next()
            val value = tween.valueAt(nowMs)
            val base = ruleProps[tween.targetId]
                ?: com.loomwall.wallpaper.scene.NodeOverrides()
            ruleProps[tween.targetId] = withProp(base, tween.property, value)
            if (tween.finished(nowMs)) {
                iterator.remove()
                Log.d(TAG, "补间结束：${tween.targetId}.${tween.property} = $value")
            }
        }
        synchronized(lock) { needsRender = true }
    }

    private var ruleRuntime: RuleRuntime? = null

    /** 规则产生的属性覆盖（与动画、绑定合并）。 */
    private val ruleProps = HashMap<String, com.loomwall.wallpaper.scene.NodeOverrides>()

    /** 规则隐藏的节点（交给 renderer 的 hiddenNodes）。 */
    private val ruleHidden = HashSet<String>()

    /** 上一次处理过的分钟序号；用于分钟 tick 去重。 */
    private var lastMinuteStamp = -1L

    /**
     * 调试用固定倾斜值：预设资源目录下放 tilt-debug.txt（内容 "x y"，如 "0.9 0"）即生效。
     * 存在的意义：把"渲染链路是否正确"和"传感器是否有数据"分开验证 ——
     * 手机上没效果时，先注入一个已知倾斜值看画面会不会动，能立刻定位是哪一段的问题。
     */
    private var debugTilt: FloatArray? = null

    /** 读资源目录下的 tilt-debug.txt（"x y"），没有就返回 null。只在加载预设时读一次。 */
    private fun readDebugTilt(dir: java.io.File): FloatArray? = try {
        val f = java.io.File(dir, "tilt-debug.txt")
        if (!f.isFile) null else {
            val parts = f.readText().trim().split(" ", "\t", ",").filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val x = parts[0].toFloatOrNull()
                val y = parts[1].toFloatOrNull()
                if (x != null && y != null) {
                    Log.i(TAG, "使用调试倾斜值：$x / $y")
                    floatArrayOf(x, y)
                } else null
            } else null
        }
    } catch (e: Throwable) {
        Log.w(TAG, "读取 tilt-debug.txt 失败：${e.message}")
        null
    }

    /** 倾斜变化时只要标记重绘即可（渲染线程自己取最新值）。 */
    private fun requestRenderForTilt() {
        synchronized(lock) {
            needsRender = true
            lock.notifyAll()
        }
    }

    fun onVisibilityChanged(isVisible: Boolean) {
        synchronized(lock) {
            visible = isVisible
            if (isVisible) {
                needsRender = true
                // 编辑器保存后用户会回桌面：重新可见时重读预设，省掉进程间通信。
                reloadRequested = true
            }
            lock.notifyAll()
        }
        // 视差数据源跟随可见性开关：不可见立刻注销，符合"不可见零唤醒"的红线
        if (isVisible) {
            // 回到桌面：执行 session 规则（"进入时随机"这类模板靠它）
            synchronized(lock) {
                ruleRuntime?.let { rt ->
                    val executed = rt.onSession()
                    if (executed > 0) needsRender = true
                }
            }
            TiltSource.setFilter(parallaxSettings.smoothing, parallaxSettings.deadzone)
            TiltSource.attach(context) { requestRenderForTilt() }
        } else {
            TiltSource.detach()
        }
        Log.i(TAG, "可见性变化：visible=$isVisible")
    }

    fun onSurfaceAvailable(surface: Surface?) {
        synchronized(lock) {
            pendingSurface = surface
            needsRender = true
            lock.notifyAll()
        }
    }

    fun onSurfaceSizeChanged() {
        synchronized(lock) {
            needsRender = true
            lock.notifyAll()
        }
    }

    fun requestRender() {
        synchronized(lock) {
            needsRender = true
            lock.notifyAll()
        }
    }

    /** 壁纸收到点击：只记录坐标，命中测试与播放放到 GL 线程做（不碰场景数据）。 */
    fun onTapSurface(xPx: Float, yPx: Float) {
        synchronized(lock) {
            pendingTapX = xPx
            pendingTapY = yPx
            needsRender = true
            lock.notifyAll()
        }
    }

    fun quit() {
        synchronized(lock) {
            quit = true
            lock.notifyAll()
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        try {
            while (true) {
                when (nextDecision()) {
                    Decision.QUIT -> break
                    Decision.DRAW -> {
                        val attached = drawFrame()
                        synchronized(lock) { glAttached = attached }
                    }
                    Decision.SUSPEND -> {
                        // 不可见时连动画一起丢弃，避免恢复可见时“从中间跳出来”。
                        runtime?.stopAll()
                        suspendGl()
                        synchronized(lock) { glAttached = false }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "渲染线程异常终止", t)
        } finally {
            releaseGl()
            Log.i(TAG, "渲染线程退出")
        }
    }

    // ---- 调度 ----

    private fun nextDecision(): Decision = synchronized(lock) {
        while (!quit && !hasWorkLocked()) {
            // 循环任务到点也要醒来：与整分钟唤醒取更早的那个
            val minuteTimeout = minuteWakeTimeoutLocked()
            val repeatDelay = nextRepeatDelay(SystemClock.uptimeMillis())
            val timeout = when {
                repeatDelay == Long.MAX_VALUE -> minuteTimeout
                minuteTimeout == 0L -> repeatDelay.coerceAtMost(600_000L)
                else -> minOf(minuteTimeout, repeatDelay.coerceAtMost(600_000L))
            }
            if (timeout > 0) {
                lock.wait(timeout)
                // 超时醒来后如果跨过了整分钟，请求一帧交给 handleMinuteTick
                if (!quit && minuteChangedSinceLastTick()) needsRender = true
            } else {
                lock.wait()
            }
        }
        when {
            quit -> Decision.QUIT
            visible && pendingSurface != null && needsRender -> Decision.DRAW
            else -> Decision.SUSPEND
        }
    }

    /**
     * 有 minute 规则且可见时，返回距离下一个整分钟还有多久（毫秒）；否则 0 = 无限等待。
     *
     * 为什么需要：渲染循环平时无限 wait（静止不耗电），但时间类规则必须每分钟被叫醒一次。
     * 只在可见且确实有 minute 规则时给超时，不可见时依旧零唤醒。
     */
    private fun minuteWakeTimeoutLocked(): Long {
        if (!visible || minuteRuleCountCache == 0) return 0
        val now = System.currentTimeMillis()
        val toNextMinute = 60_000L - (now % 60_000L)
        return toNextMinute + 80L
    }

    /** 当前分钟序号是否与上次处理的分钟不同。 */
    private fun minuteChangedSinceLastTick(): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val stamp = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60L +
            calendar.get(java.util.Calendar.MINUTE)
        return stamp != lastMinuteStamp
    }

    private fun hasWorkLocked(): Boolean {
        if (quit) return true
        val canDraw = visible && pendingSurface != null
        // 只在「确实需要渲染」时工作。注意这里刻意不看 glAttached：
        // 绘制失败后 glAttached 为 false，如果条件里带 !glAttached 就会变成
        // 无间断重试的死循环（真机上复现过：同一秒几十条失败日志）。
        if (canDraw && needsRender) return true
        // 不可见却还挂着 GL 表面：需要释放，这是一次性的收尾工作。
        if (!canDraw && glAttached) return true
        return false
    }

    // ---- 绘制 ----

    private fun drawFrame(): Boolean {
        val surface = synchronized(lock) { pendingSurface } ?: return false
        try {
            if (!egl.isInitialized) {
                egl.initialize()
                Log.i(TAG, "EGL 已初始化（GLES 2.0）")
            }

            val attachedNow = !egl.isAttachedTo(surface)
            if (attachedNow) {
                egl.detach()
                egl.attachTo(surface)
            }

            // 顺序不能调换：任何 GL 调用（编译着色器、传顶点、绘制）都要求当前上下文，
            // 否则 glCreateShader 直接返回 0。真机上就是这么炸的，别改成先建资源后 makeCurrent。
            if (!egl.makeCurrent()) {
                Log.e(TAG, "eglMakeCurrent 失败，本帧放弃")
                synchronized(lock) { needsRender = false }
                return false
            }

            val w = egl.width
            val h = egl.height
            if (attachedNow || !renderer.isReady) {
                renderer.onSurfaceCreated(w, h)
                Log.i(
                    TAG,
                    "窗口表面就绪：${w}x$h，画布高度 ${if (w > 0) 100f * h / w else 100f} 单位"
                )
            } else if (w != lastSurfaceWidth || h != lastSurfaceHeight) {
                renderer.onSurfaceChanged(w, h)
            }
            lastSurfaceWidth = w
            lastSurfaceHeight = h

            val reload = synchronized(lock) {
                val requested = reloadRequested
                reloadRequested = false
                requested
            }
            if (reload) sceneLoaded = false

            ensureSceneLoaded()
            handlePendingTap()
            handlePendingGestures()
            handleMinuteTick()
            // 补间推进：放在取 nowMs 之前一拍，用同一时间基准推进本帧
            advanceTweens(SystemClock.uptimeMillis())
            advanceRepeats(SystemClock.uptimeMillis())
            val nowMs = SystemClock.uptimeMillis()
            // 常驻动画在壁纸可见后自动开始；点击动画等命中再开始。
            runtime?.ensureAlwaysStarted(nowMs)
            val animationOverrides = runtime?.evaluate(nowMs) ?: emptyMap()

            // 音频可视化：采样只有约 20Hz，直接画会"一格一格跳"。
            // 这里每帧把显示值朝目标推进一次，20Hz 的数据在 90fps 上就是连续的。
            val audioDt = run {
                val nowNs = System.nanoTime()
                val d = if (lastAudioTickNs == 0L) 0f
                else ((nowNs - lastAudioTickNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                lastAudioTickNs = nowNs
                d
            }
            val audioBands = AudioLevelSource.tick(audioDt)
            // 有采集时给这一帧打一份带频谱值的变量表；没有采集就复用快照，不产生分配
            val varsNow = if (audioBands != null) {
                val merged = HashMap(VarsSnapshot.get())
                for (i in 0 until 8) merged["sys.audio.b" + (i + 1)] = audioBands[i]
                merged
            } else {
                VarsSnapshot.get()
            }

            // 系统变量绑定（电量/内存/时间等）：动画优先，绑定兜底
            val boundOverrides = BindingEvaluator.evaluate(bindings, varsNow)
            val overrides = BindingEvaluator.merge(animationOverrides, boundOverrides)
            // 规则覆盖：与动画/绑定合并（动画优先，绑定次之，规则垫底）
            val withRules = if (ruleProps.isEmpty()) {
                overrides
            } else {
                com.loomwall.wallpaper.engine.BindingEvaluator.merge(ruleProps, overrides)
            }
            // 全局开关只管"跟随全局"的图层，不能把倾斜值一起清零：
            // 图层自己开了视差时要照常响应，否则单层视差在手机上永远不动。
            val anyOwnParallax = scene?.parallax?.values?.any { it.custom && it.enabled } == true
            val tiltTarget = debugTilt
                ?: (if (parallaxSettings.enabled || anyOwnParallax) TiltSource.tilt() else ZERO_TILT)

            // 帧级插值：把 30Hz 的传感器输入摊到每一帧上，避免"一个事件一帧"的阶梯感。
            // 用时间常数而不是固定步长，掉帧或首帧都不会突变。
            val frameNs = System.nanoTime()
            val dtSec = if (lastFrameNs == 0L) 0f
            else ((frameNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
            lastFrameNs = frameNs
            val coeff = if (dtSec <= 0f) 1f
            else (1.0 - kotlin.math.exp(-dtSec / TILT_FOLLOW_TAU_SEC)).toFloat()
            tiltShownX += (tiltTarget[0] - tiltShownX) * coeff
            tiltShownY += (tiltTarget[1] - tiltShownY) * coeff
            val tiltSettling = kotlin.math.abs(tiltTarget[0] - tiltShownX) > TILT_EPSILON ||
                kotlin.math.abs(tiltTarget[1] - tiltShownY) > TILT_EPSILON
            // 调试注入时直接给值，不做跟随（否则改文件看不到即时效果）
            val tilt = if (debugTilt != null) tiltTarget
            else floatArrayOf(tiltShownX, tiltShownY)

            renderer.draw(
                scene, null, withRules, varsNow, VarsSnapshot.getTexts(),
                parallaxSettings = parallaxSettings,
                tilt = tilt
            )

            if (!egl.swapBuffers()) {
                Log.w(TAG, "eglSwapBuffers 失败，释放窗口表面等待重建")
                safelyDetach()
                synchronized(lock) { needsRender = false }
                return false
            }

            // 顺带统计实际帧率（只有真的在画的时候才有意义）
            fpsFrames++
            if (fpsWindowStart == 0L) fpsWindowStart = nowMs
            val fpsElapsed = nowMs - fpsWindowStart
            if (fpsElapsed >= 2000) {
                runCatching { onFps(fpsFrames * 1000f / fpsElapsed) }
                fpsWindowStart = nowMs
                fpsFrames = 0
            }

            synchronized(lock) {
                // 动画还在跑、或视差还没跟上目标值，就继续请求下一帧；
                // 都收敛后立即停止绘制，这是功耗红线的落地。
                needsRender = runtime?.isAnimating() == true || tiltSettling ||
                    AudioLevelSource.isSettling
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "绘制失败：${t.message}", t)
            safelyDetach()
            synchronized(lock) { needsRender = false }
            return false
        }
    }

    /** 不可见时调用：解绑窗口表面，保留上下文以备快速恢复。 */
    private fun suspendGl() {
        if (!egl.isInitialized) return
        if (!egl.hasAttachedSurface) return
        egl.detach()
        Log.i(TAG, "壁纸不可见：已释放窗口表面，停止一切 GPU 绘制")
    }

    private fun releaseGl() {
        runCatching {
            renderer.release()
        }
        runCatching { egl.release() }
        scene = null
        sceneLoaded = false
    }

    private fun safelyDetach() {
        runCatching { egl.detach() }
    }

    /**
     * 处理一次点击：像素坐标 → 画布单位 → 命中最上层元素 → 触发动画。
     * 只在 GL 线程执行，因此可以直接读 scene / runtime，不需要额外加锁。
     */
    /** 服务线程识别出的手势先入队，动作在渲染线程执行（保证 GL 与场景状态只被一条线程碰）。 */
    /** 外部（手机端接收服务）导入新预设后，要求立刻重读磁盘。 */
    fun requestReload() {
        synchronized(lock) {
            reloadRequested = true
            sceneLoaded = false
            needsRender = true
            lock.notifyAll()
        }
    }

    fun onGesture(gesture: Gesture, x: Float, y: Float) {
        synchronized(lock) {
            pendingGestures.add(Triple(gesture, x, y))
            needsRender = true
            lock.notifyAll()
        }
    }

    private fun handlePendingGestures() {
        val batch = synchronized(lock) {
            if (pendingGestures.isEmpty()) return
            val copy = pendingGestures.toList()
            pendingGestures.clear()
            copy
        }
        val now = SystemClock.uptimeMillis()
        for ((gesture, x, y) in batch) {
            val hitId = hitNodeId(x, y)
            Log.d(TAG, "手势 $gesture 命中=$hitId 事件数=${events.size}")
            for (event in events) {
                if (event.gesture != gesture) continue
                if (event.targetId != null && event.targetId != hitId) continue
                executeAction(event)
            }
            // 触发器规则：手势路径才是现在真正的点击入口，
            // 之前只在旧的 handlePendingTap 里调用，导致"编辑器里配的规则在手机上点了没反应"。
            // 所有手势都能触发规则（tap / doubleTap / longPress / swipe*）
            run {
                val ruleCount = ruleRuntime?.onGesture(gesture.jsonName, hitId) ?: 0
                if (ruleCount > 0) {
Log.i(TAG, "规则执行 " + ruleCount + " 条（命中=" + (hitId ?: "(画布)") + "）")
                    synchronized(lock) { needsRender = true }
                }
            }
            // 兼容阶段 2 的写法：动画自带的 tap 触发器仍然生效
            if (gesture == Gesture.TAP && hitId != null) {
                runtime?.onTap(hitId, now)
            }
        }
    }

    private fun executeAction(event: NodeEvent) {
        when (val action = event.action) {
            is EventAction.PlayAnimation -> {
                val started = runtime?.play(action.clipId, SystemClock.uptimeMillis()) == true
                if (started) {
                    synchronized(lock) { needsRender = true }
                } else {
                    Log.w(TAG, "事件 ${event.id} 指向不存在的动画 ${action.clipId}")
                }
            }
            is EventAction.ToggleVisible -> {
                val visible = renderer.toggleVisible(action.targetId)
                Log.d(TAG, "事件 ${event.id}：${action.targetId} 可见性 → $visible")
                synchronized(lock) { needsRender = true }
            }
            is EventAction.LaunchApp, is EventAction.OpenUri -> {
                Log.d(TAG, "事件 ${event.id}：请求启动 ${action.jsonName}")
                runCatching { onLaunch(action) }
                    .onFailure { Log.w(TAG, "启动失败：${it.message}") }
            }
        }
    }
    /**
     * 像素坐标 → 命中的元素 id。
     * 与 handlePendingTap 用同一套换算：画布单位 = 像素 / 表面宽度 * 100。
     */
    private fun hitNodeId(xPx: Float, yPx: Float): String? {
        val doc = scene ?: return null
        val surfaceWidth = egl.width
        if (surfaceWidth <= 0) return null
        val canvasX = xPx / surfaceWidth * 100f
        val canvasY = yPx / surfaceWidth * 100f
        // 命中判定交给渲染器：它知道每个元素的累计变换（容器嵌套后不能再只比 x/y/w/h）。
        // 必须带上"当前显示值"的覆盖：动画/规则/绑定把图层挪走后，
        // 命中区要跟着走，否则会出现"图层掉下去了，要点原来那块空白"的问题。
        val hitOverrides = BindingEvaluator.merge(
            runtime?.evaluate(SystemClock.uptimeMillis()) ?: emptyMap(),
            BindingEvaluator.merge(
                BindingEvaluator.evaluate(bindings, VarsSnapshot.get()),
                ruleProps
            )
        )
        return renderer.hitTest(doc, canvasX, canvasY, hitOverrides)
    }

    /**
     * 分钟 tick。
     *
     * 为什么不在 onVisibilityChanged 里开定时器：唤醒路径越多越容易漏注销。
     * 这里只做"这一帧顺便看看分钟变没变"—— 而帧只会在有动画/有交互时出现，
     * 所以静止壁纸不会因此被唤醒。带 minute 规则的预设靠 [requestRenderForMinute]
     * 在整分钟时被叫醒一次。
     */
    private fun handleMinuteTick() {
        val rt = ruleRuntime ?: return
        if (!synchronized(lock) { visible }) return
        val calendar = java.util.Calendar.getInstance()
        val stamp = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60L +
            calendar.get(java.util.Calendar.MINUTE)
        if (stamp == lastMinuteStamp) return
        lastMinuteStamp = stamp
        val executed = rt.onMinute()
        if (executed > 0) {
            synchronized(lock) { needsRender = true }
            Log.i(TAG, "分钟 tick：执行 $executed 条规则")
        }
    }

    /** 规则系统里是否有 minute 规则（决定要不要按整分钟唤醒）。 */
    private fun hasMinuteRules(): Boolean =
        ruleRuntime?.let { rt -> synchronized(lock) { rt } } != null && minuteRuleCount() > 0

    private fun minuteRuleCount(): Int = minuteRuleCountCache

    private var minuteRuleCountCache = 0

    private fun handlePendingTap() {
        val xPx = synchronized(lock) { pendingTapX }
        val yPx = synchronized(lock) { pendingTapY }
        if (xPx < 0f || yPx < 0f) return
        synchronized(lock) {
            pendingTapX = -1f
            pendingTapY = -1f
        }

        val doc = scene ?: return
        val animations = runtime ?: return
        val surfaceWidth = egl.width
        if (surfaceWidth <= 0) return

        // 画布单位是「宽度的百分比」，所以 x、y 都用宽度做分母。
        val canvasX = xPx / surfaceWidth * 100f
        val canvasY = yPx / surfaceWidth * 100f

        val hit = doc.root.children
            .filterIsInstance<RectNode>()
            .asReversed()
            .firstOrNull { rect ->
                rect.visible &&
                    canvasX >= rect.x && canvasX <= rect.x + rect.width &&
                    canvasY >= rect.y && canvasY <= rect.y + rect.height
            }

        if (hit == null) {
            Log.i(TAG, "点击未命中元素（画布坐标 $canvasX, $canvasY）")
            return
        }

        val nowMs = SystemClock.uptimeMillis()
        val started = animations.onTap(hit.id, nowMs)
        // 规则先跑：它可能改变后续动画的目标可见性
        val ruleCount = ruleRuntime?.onTap(hit.id) ?: 0
        if (ruleCount > 0) {
            synchronized(lock) { needsRender = true }
        }
        Log.i(TAG, "点击命中 ${hit.id}，触发动画=$started，规则=$ruleCount")
    }

    /**
     * 这个预设是否引用了音频变量（sys.audio.*）。
     *
     * 为什么要判断：Visualizer 需要录音权限，并且会以约 30Hz 持续回调。
     * 绝大多数壁纸不做音频可视化，不能为此让每个预设都白白付出这份功耗。
     * 检查范围：绑定数据源、文本模板、规则/触发器表达式；只在加载预设时跑一次。
     */
    /**
     * 预设是否用到天气变量（文本里写 `{sys.weather.temp}`，或绑定源是 sys.weather.*）。
     *
     * 为什么要判断：天气要联网、要定位，没人用的时候一次请求都不该发。
     */
    private fun usesWeatherVars(preset: com.loomwall.wallpaper.scene.PresetStore.Preset): Boolean {
        if (preset.interactions.bindings.any { it.expr?.contains("sys.weather.") == true }) return true
        if (preset.interactions.bindings.any { it.source.startsWith("sys.weather.") }) return true
        if (preset.interactions.toString().contains("sys.weather.")) return true

        var found = false
        fun scan(node: com.loomwall.wallpaper.scene.SceneNode) {
            if (found) return
            if (node is com.loomwall.wallpaper.scene.TextNode && node.text.contains("sys.weather.")) {
                found = true
                return
            }
            if (node is com.loomwall.wallpaper.scene.ContainerNode) {
                for (child in node.children) scan(child)
            }
        }
        scan(preset.scene.root)
        return found
    }

    private fun usesAudioVars(preset: com.loomwall.wallpaper.scene.PresetStore.Preset): Boolean {
        if (preset.interactions.bindings.any { it.source.startsWith("sys.audio.") }) return true
        if (preset.interactions.toString().contains("sys.audio.")) return true
        // 表达式绑定里写的是函数（audio(3) / beat()），展开前后都不带 sys.audio. 前缀，
        // 只看字符串会漏掉整类预设——漏掉就等于频谱永远是 0（用户实测：柱子不动）
        if (preset.interactions.bindings.any { binding ->
                val expr = binding.expr
                expr != null && usesAudioInText(expr)
            }
        ) return true

        var found = false
        fun scanNode(node: com.loomwall.wallpaper.scene.SceneNode) {
            if (found) return
            if (node is com.loomwall.wallpaper.scene.TextNode) {
                // 两种写法都要认：
                //   1) 变量式 {sys.audio.b3}
                //   2) 函数式 {audio(3)} / {beat()} —— 只在文本里写函数时也必须开启采集，
                //      否则数据永远是 0，用户会以为"函数没用"
                if (usesAudioInText(node.text)) {
                    found = true
                    return
                }
            }
            if (node is com.loomwall.wallpaper.scene.ContainerNode) {
                for (child in node.children) scanNode(child)
            }
        }
        scanNode(preset.scene.root)
        return found
    }

    /**
     * 文本/表达式里是否用到了音频变量或函数。
     *
     * 注意两种写法都要认：
     * - 文本模板：`{sys.audio.b3}` 或 `{audio(3)}`（带花括号）
     * - 绑定表达式：`0.8 + 39.2 * audio(1)^0.6`（**没有花括号**）
     * 之前只查 `{函数(`，于是表达式绑定的预设音频采集永远不开，柱子在手机上不动。
     */
    private fun usesAudioInText(text: String): Boolean {
        if (text.contains("sys.audio.")) return true
        for (name in AUDIO_FUNCS) {
            if (Regex("""\b$name\s*\(""").containsMatchIn(text)) return true
        }
        return false
    }

    private fun ensureSceneLoaded() {
        if (sceneLoaded) return
        sceneLoaded = true
        when (val result = sceneSource()) {
            is PresetStore.LoadResult.Success -> {
                scene = result.preset.scene
                bindings = result.preset.interactions.bindings
                events = result.preset.interactions.events
                // 音频采集很贵（录音权限 + 30Hz 回调）：只有这个预设真的引用 sys.audio.* 才开
                AudioLevelSource.setNeeded(usesAudioVars(result.preset))
                // 天气要联网：同样只在预设真的引用 sys.weather.* 时才启用
                com.loomwall.wallpaper.net.WeatherSource.setNeeded(usesWeatherVars(result.preset))
                // 规则隐藏状态属于运行期，跨预设必须清空（否则新预设里 visible:true 的节点仍不显示）
                renderer.clearHiddenNodes()
                repeatJobs.clear()
                ruleHidden.clear()
                ruleProps.clear()
                renderer.resourcesDir = resourcesDirProvider()
                    // 视差：全局设置随预设走，滤波参数同步给传感器
                    parallaxSettings = result.preset.parallax

                    // 触发器：只有预设真的带规则时才建运行时（老预设零开销）
                    val triggerModel = result.preset.interactions.triggers
                    Log.i(
                        TAG,
                        "触发器已加载：变量 ${triggerModel.vars.size} 个，规则 ${triggerModel.rules.size} 条，" +
                            "函数 ${triggerModel.functions.size} 个"
                    )
                    // 逐条规则日志：让"点了没反应"能自己说话（最多 20 条，避免刷屏）
                    triggerModel.rules.take(20).forEach { rule ->
                        val whenText = when (val trg = rule.trigger) {
                            is com.loomwall.wallpaper.scene.RuleTrigger.Tap -> "tap " + (trg.targetId ?: "(画布)")
                            is com.loomwall.wallpaper.scene.RuleTrigger.Gesture ->
                                "gesture " + trg.kind + " " + (trg.targetId ?: "(画布)")
                            is com.loomwall.wallpaper.scene.RuleTrigger.VarChanged -> "var " + trg.varName
                            is com.loomwall.wallpaper.scene.RuleTrigger.Minute -> "minute " + (rule.condition ?: "")
                            is com.loomwall.wallpaper.scene.RuleTrigger.Session -> "session"
                        }
                        val actions = rule.actions.joinToString(", ") { a ->
                            when (a) {
                                is com.loomwall.wallpaper.scene.RuleAction.SetVar -> "setVar " + a.varName
                                is com.loomwall.wallpaper.scene.RuleAction.SetVisible -> "setVisible " + a.targetId
                                is com.loomwall.wallpaper.scene.RuleAction.SetProperty ->
                                    "set " + a.targetId + "." + a.property
                                is com.loomwall.wallpaper.scene.RuleAction.PlayAnimation -> "play " + a.clipId + (if (a.reverse) "（反向）" else "")
                                is com.loomwall.wallpaper.scene.RuleAction.LaunchApp -> "launch " + a.packageName
                                is com.loomwall.wallpaper.scene.RuleAction.OpenUri -> "uri " + a.uri
                                is com.loomwall.wallpaper.scene.RuleAction.CallFunction -> "call " + a.name
                                is com.loomwall.wallpaper.scene.RuleAction.ForLoop ->
                                    "for " + a.varName + "=" + a.fromExpr + ".." + a.toExpr +
                                        "（体 " + a.body.size + " 个动作）"
                                is com.loomwall.wallpaper.scene.RuleAction.WhileLoop ->
                                    "while " + a.condition + "（最多 " + a.maxIterations +
                                        " 次，体 " + a.body.size + " 个动作）"
                            }
                        }
                        Log.i(TAG, "  规则 " + rule.id + "：" + whenText + " → " + actions)

                        // 目标与触发元素相同 = 点一下把自己藏起来，基本肯定是模板参数没填
                        val trgId = (rule.trigger as? com.loomwall.wallpaper.scene.RuleTrigger.Tap)?.targetId
                        val selfTarget = trgId != null && rule.actions.any { a ->
                            a is com.loomwall.wallpaper.scene.RuleAction.SetVisible && a.targetId == trgId
                        }
                        if (selfTarget) {
                            Log.w(
                                TAG,
                                "  规则 " + rule.id + " 的目标与触发元素相同（" + trgId +
                                    "），点击会把它自己隐藏 —— 检查模板的「菜单组」参数"
                            )
                        }
                    }

                    // 统计 minute 规则数：决定渲染循环要不要按整分钟唤醒
                    // 预设刚加载完、壁纸本来就可见时，onVisibilityChanged 不会再触发一次，
                    // 于是「出现时」规则会漏跑（补间动画看起来"没生效"就是这个原因）。
                    if (synchronized(lock) { visible }) {
                        ruleRuntime?.onSession()
                        synchronized(lock) { needsRender = true }
                    }
                    minuteRuleCountCache = triggerModel.rules.count {
                        it.trigger is com.loomwall.wallpaper.scene.RuleTrigger.Minute
                    }
                    ruleRuntime = if (triggerModel.rules.isEmpty() && triggerModel.vars.isEmpty()) {
                        null
                    } else {
                        RuleRuntime(
                            model = triggerModel,
                            effects = object : RuleEffects {
                                override fun setVisible(targetId: String, visible: Boolean) {
                                    // 双重记账：ruleHidden 用于自省/测试，
                                    // setNodeVisible 才是真正让渲染器跳过该节点的调用（漏了它规则就等于没生效）
                                    if (visible) ruleHidden.remove(targetId) else ruleHidden.add(targetId)
                                    renderer.setNodeVisible(targetId, visible)
                                    synchronized(lock) { needsRender = true }
                                }

                                override fun setProperty(
                                    targetId: String,
                                    property: String,
                                    value: Float,
                                    durationMs: Int,
                                    easing: String
                                ) {
                                    if (!SUPPORTED_RULE_PROPS.contains(property)) {
                                        Log.w(TAG, "规则想设置不支持的属性：$property（$targetId）")
                                        return
                                    }
                                    if (durationMs <= 0) {
                                        // 瞬移：保留旧行为，兼容已有预设
                                        val current = ruleProps[targetId]
                                            ?: com.loomwall.wallpaper.scene.NodeOverrides()
                                        ruleProps[targetId] = withProp(current, property, value)
                                        synchronized(lock) { needsRender = true }
                                        return
                                    }
                                    // 补间：从"当前显示值"开始，到目标值结束
                                    val from = currentPropValue(targetId, property)
                                    tweens.add(
                                        PropTween(
                                            targetId = targetId,
                                            property = property,
                                            from = from,
                                            to = value,
                                            startMs = SystemClock.uptimeMillis(),
                                            durationMs = durationMs,
                                            easing = easing
                                        )
                                    )
                                    Log.d(TAG, "补间：$targetId.$property $from → $value（${durationMs}ms $easing）")
                                    synchronized(lock) { needsRender = true }
                                }

                                // 启动应用/打开链接：直接复用旧 events 的执行通道，
                                // 避免两套"启动"逻辑各自处理权限与失败。
                                override fun launchApp(packageName: String) {
                                    runCatching {
                                        onLaunch(
                                            com.loomwall.wallpaper.scene.EventAction.LaunchApp(packageName)
                                        )
                                    }.onFailure { Log.w(TAG, "规则启动应用失败：${it.message}") }
                                }

                                override fun openUri(uri: String) {
                                    runCatching {
                                        onLaunch(
                                            com.loomwall.wallpaper.scene.EventAction.OpenUri(uri)
                                        )
                                    }.onFailure { Log.w(TAG, "规则打开链接失败：${it.message}") }
                                }

                                override fun scheduleRepeat(ruleId: String, times: Int, intervalMs: Int) {
                                    if (ruleId.isBlank()) return
                                    val interval = intervalMs.coerceIn(16, 600_000)
                                    // 同一条规则只保留一个循环任务，重复触发时重置
                                    repeatJobs.removeAll { it.ruleId == ruleId }
                                    repeatJobs.add(
                                        RepeatJob(
                                            ruleId = ruleId,
                                            remaining = if (times <= 0) Int.MAX_VALUE else times - 1,
                                            intervalMs = interval,
                                            nextAt = SystemClock.uptimeMillis() + interval
                                        )
                                    )
                                    Log.d(TAG, "循环：$ruleId 每 ${interval}ms 重放 ${if (times <= 0) "无限" else times} 次")
                                    synchronized(lock) { needsRender = true }
                                }

                                override fun playAnimation(clipId: String, reverse: Boolean) {
                                    runtime?.play(clipId, SystemClock.uptimeMillis(), reverse)
                                }
                            },
                            systemVar = { name ->
                                // 系统变量由 SystemInputHub 快照提供；未知返回 null
                                VarsSnapshot.get()[name]
                            },
                            textVar = { name -> VarsSnapshot.getTexts()[name] }
                        )
                    }
                    debugTilt = readDebugTilt(resourcesDirProvider())
                    TiltSource.setFilter(parallaxSettings.smoothing, parallaxSettings.deadzone)
                // 没有绑定也没有文本占位符的预设，不应该产生任何系统广播订阅（功耗红线）。
                // 文本里出现 {sys.*} 就需要时间/电量变量，因此同样要订阅。
                val hasTemplateText = result.preset.scene.root.children
                    .filterIsInstance<com.loomwall.wallpaper.scene.TextNode>()
                    .any { it.text.contains("{sys.") }
                runCatching { onBindingsChanged(bindings.isNotEmpty() || hasTemplateText) }
                val loaded = AnimationRuntime(result.preset.interactions)
                runtime = if (loaded.hasClips) loaded else null
                Log.i(
                    TAG,
                    "场景已加载：${result.preset.name}（${result.preset.source}），" +
                        "动画 ${result.preset.interactions.animations.size} 条"
                )
            }
            is PresetStore.LoadResult.Failure -> {
                scene = null
                runtime = null
                Log.e(TAG, "预设加载失败：${result.reason}")
            }
        }
    }

    private companion object {
        const val TAG = "LOOW.Render"

        /** 文本模板里的音频函数名（与 TextTemplate.callFunction 保持一致）。 */
        val AUDIO_FUNCS = listOf("audio", "band", "bass", "mid", "treble", "level", "beat")

        /** 视差显示值收敛的时间常数（秒）：越小越"跟手"，越大越平滑。45ms 兼顾两者。 */
        const val TILT_FOLLOW_TAU_SEC = 0.045

        /** 与目标的差值小于此值视为已收敛，停止为视差单独续帧（省电）。 */
        const val TILT_EPSILON = 0.0008f
    }
}

/** 中立姿态：设备没有倾斜时的倾斜向量。 */
private val ZERO_TILT = floatArrayOf(0f, 0f)
