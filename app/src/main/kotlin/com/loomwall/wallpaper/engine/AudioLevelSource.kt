package com.loomwall.wallpaper.engine

import android.content.Context
import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 音频能量源：把系统输出的频谱压缩成 4 个 0..1 的电平 + 一个节拍脉冲。
 *
 * 为什么要先验证再信它：`Visualizer` 抓的是**全局输出混音**（sessionId = 0），
 * Android 9 之后这条路被收紧了——别的 App 正在占用音频捕获时会直接返回
 * `ERROR_INVALID_OPERATION`，部分 ROM 干脆一直为空。所以：
 * **任何失败都只是"没有数据"**，变量保持 0，壁纸照常渲染，绝不能让可视化把壁纸拖崩。
 *
 * 功耗（本项目红线）：
 * - 只有「壁纸可见」且「当前预设真的用到了 sys.audio.* 」才创建 Visualizer；
 * - 任一条件不满足立刻 release，不留回调；
 * - 上限 30Hz（回调自带节流），静止或无音乐时电平会自然衰减到 0。
 */
internal object AudioLevelSource {

    private const val TAG = "LOOW.Audio"
    private const val CAPTURE_SIZE = 1024

    /** 频谱段数：8 段足够画出"有起伏"的条，再多在手机壁纸上看不出差别。 */
    private const val BAND_COUNT = 8

    /** 预设是否引用音频变量（由 RenderThread 在加载场景后设置）。 */
    @Volatile
    private var needed: Boolean = false

    /** 壁纸是否可见。 */
    @Volatile
    private var visible: Boolean = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private var running = false

    /** 节拍检测用的慢速基线。 */
    private var slowBass = 0f
    private var lastBeatMs = 0L

    /**
     * 显示值：每帧朝 [ExternalVars.audioBands]（20Hz 的目标值）插值。
     *
     * 为什么需要它：采样只有 20Hz，而屏幕 90Hz。直接把 20Hz 的值画出来，
     * 就是"一格一格跳"（用户说的像 25fps）。这里用时间相关的插值，
     * 让 20Hz 的数据在 90fps 上平滑过渡。
     */
    private val display = FloatArray(BAND_COUNT)

    /** 显示值是否还没追上目标（渲染线程据此决定要不要续帧）。 */
    @Volatile
    var isSettling: Boolean = false
        private set

    /** 收到的 FFT 帧数（仅用于日志） */
    private var fftCount = 0

    /** 上一次的能量：静音门要看"刚才有没有声音"，不能只看当前帧 */
    private var lastBumpEnergy = 0f

    /** 预设加载后调用：决定要不要为这个预设开音频采集。 */
    /**
     * 渲染线程每帧调用：把显示值往目标推进，返回当前应绘制的 8 段能量。
     *
     * 时间常数 45ms：足够跟手，又能把 20Hz 的台阶抹平。
     * 返回 null 表示当前没有采集（调用方直接用快照里的值，避免无谓的分配）。
     */
    fun tick(dtSec: Float): FloatArray? {
        if (!running) {
            isSettling = false
            return null
        }
        val target = ExternalVars.audioBands
        val coeff = if (dtSec <= 0f) 1f
        else (1.0 - Math.exp(-dtSec / 0.045)).toFloat()
        var moving = false
        for (i in 0 until BAND_COUNT) {
            val delta = target[i] - display[i]
            display[i] += delta * coeff
            if (Math.abs(delta) > 0.004f) moving = true
        }
        isSettling = moving
        return display
    }

    fun setNeeded(needsAudio: Boolean) {
        // 每次都打一行：排查"可视化没反应"时，先要能确认预设到底有没有被识别成"用到音频"
        Log.i(TAG, "setNeeded($needsAudio)（当前 needed=$needed visible=$visible）")
        if (needed == needsAudio) return
        needed = needsAudio
        sync()
    }

    /**
     * 用户刚授权录音权限后调用：上一次 start() 因为没权限失败了，
     * 这里重试一次，否则要等壁纸下次可见才会重建。
     */
    fun retry() {
        if (running) return
        sync()
    }

    fun setVisible(isVisible: Boolean) {
        if (visible == isVisible) return
        visible = isVisible
        sync()
    }

    private fun sync() {
        if (needed && visible) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        val context = appContext ?: run {
            running = false
            return
        }
        // 没权限就别去撞 Visualizer 了：它会抛异常，日志里只会是难懂的 error -3
        val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "没有录音权限，音频可视化保持关闭（App 抽屉 → 获取权限 → 录音权限）")
            running = false
            return
        }
        try {
            val v = Visualizer(0)
            v.captureSize = CAPTURE_SIZE
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        if (waveform == null) return
                        onLevel(rms(waveform))
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) {
                        if (fft == null) return
                        onFft(fft)
                    }
                },
                // 用满采样率（通常是 20Hz）：砍半会变成 10Hz，肉眼就是"一格一格的"
                Visualizer.getMaxCaptureRate(),
                true,
                true
            )
            // 构造出来的 Visualizer 不一定是"已启用"：部分 ROM 默认关着，
            // 不显式打开就一次回调都没有（真机日志里就是 enabled=false 静默无数据）
            if (!v.enabled) runCatching { v.enabled = true }
            visualizer = v
            Log.i(TAG, "Visualizer 创建成功（enabled=${v.enabled}，采样率=${v.samplingRate}Hz，捕获=${v.captureSize}）")
            if (!v.enabled) {
                // 有些设备刚建好时底层未就绪，稍等再试一次
                Log.w(TAG, "首次启用失败，600ms 后重试")
                handler.postDelayed({
                    runCatching {
                        visualizer?.enabled = true
                        Log.i(TAG, "重试后 enabled=${visualizer?.enabled}")
                    }.onFailure { Log.w(TAG, "重试失败：${it.message}") }
                }, 600L)
            }
        } catch (t: Throwable) {
            // 常见于 Android 9+ 的并发捕获限制，或 ROM 直接禁用
            Log.w(TAG, "Visualizer 不可用：${t.javaClass.simpleName} ${t.message}")
            running = false
            publish(0f, 0f, 0f, 0f)
        }
    }

    private fun stop() {
        if (!running && visualizer == null) return
        running = false
        runCatching { visualizer?.release() }
        visualizer = null
        slowBass = 0f
        java.util.Arrays.fill(display, 0f)
        isSettling = false
        publish(0f, 0f, 0f, 0f)
        Log.i(TAG, "音频采集已停止")
    }

    fun release() {
        needed = false
        visible = false
        stop()
    }

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    @Volatile
    private var appContext: Context? = null

    // ---- 数据换算 ----

    /** 波形 → 总音量（0..1）。 */
    private fun onLevel(level: Float) {
        val smooth = if (level > ExternalVars.audioLevel) level else {
            ExternalVars.audioLevel * 0.82f + level * 0.18f
        }
        ExternalVars.audioLevel = smooth.coerceIn(0f, 1f)
        ExternalVars.bump()
    }

    /**
     * FFT → 低/中/高能量。
     *
     * 频段怎么切：Visualizer 的 FFT 是线性频率轴，采样率通常 44.1kHz，
     * 数组长度 = captureSize。取前 1/16 当低频（< ~1.4kHz 里的鼓与贝斯）、
     * 1/16~3/16 中频（人声主要区）、3/16~1/2 高频。
     * 不做精确 Hz 划分，因为不同设备采样率不同，按比例切更稳。
     */
    private fun onFft(fft: ByteArray) {
        // Visualizer 的 FFT 是"实数/虚数交替"的 int8，值本身就是幅值
        val bins = fft.size / 2
        if (bins <= BAND_COUNT * 4) return
        val maxBin = bins / 2

        // 8 段对数划分：低频窄、高频宽，画出来比"左边两根一直动"更接近人耳
        val bands = ExternalVars.audioBands
        for (index in 0 until BAND_COUNT) {
            val from = bandEdge(index, maxBin)
            val to = bandEdge(index + 1, maxBin).coerceAtLeast(from + 1)
            bands[index] = blend(bands[index], band(fft, from, to))
        }

        // 三频段聚合：低=前两段，中=中间三段，高=后三段
        ExternalVars.audioBass = max(bands[0], bands[1])
        ExternalVars.audioMid = (bands[2] + bands[3] + bands[4]) / 3f
        ExternalVars.audioTreble = (bands[5] + bands[6] + bands[7]) / 3f

        // 节拍：低频能量明显高于慢速基线时判为一拍（并限制最短间隔，避免连击）
        val now = android.os.SystemClock.elapsedRealtime()
        slowBass = slowBass * 0.94f + ExternalVars.audioBass * 0.06f
        val hit = ExternalVars.audioBass > 0.16f &&
            ExternalVars.audioBass > slowBass * 1.35f &&
            now - lastBeatMs > 220L
        if (hit) {
            lastBeatMs = now
            ExternalVars.audioBeat = 1f
        } else {
            // 衰减：没有新拍子时自然落下
            ExternalVars.audioBeat = (ExternalVars.audioBeat - 0.08f).coerceAtLeast(0f)
        }
        // 静音时不要每帧都唤醒渲染：能量归零后让显示值自然收敛，之后就不再请求重绘。
        // 注意要留一帧"从有声变无声"的触发，否则最后一帧会停在有高度的状态。
        val energy = bands.maxOrNull() ?: 0f
        val shouldWake = energy > 0.008f || lastBumpEnergy > 0.008f || ExternalVars.audioBeat > 0.02f
        lastBumpEnergy = energy

        // 每约 2 秒打一次采样值：没有这行日志时，无法区分"没有回调"和"回调了但全是 0"
        fftCount++
        if (fftCount % 60 == 0) {
            Log.d(
                TAG,
                "FFT#" + fftCount + " bands=" + bands.joinToString(",") { String.format("%.2f", it) }
            )
        }
        if (shouldWake) ExternalVars.bump()
    }

    /**
     * 第 index 段的下边界（FFT bin）。
     *
     * 在 2 → maxBin/2 之间等比切 8 份。线性切分会把绝大多数能量挤进前两段
     * （音乐能量集中在中低频），画出来就是"永远只有左边在动"。
     */
    private fun bandEdge(index: Int, maxBin: Int): Int {
        if (index <= 0) return 1
        if (index >= BAND_COUNT) return maxBin
        val ratio = index.toDouble() / BAND_COUNT
        val edge = 2.0 * Math.pow(maxBin / 2.0, ratio)
        return edge.toInt().coerceIn(1, maxBin)
    }

    private fun band(fft: ByteArray, from: Int, to: Int): Float {
        var sum = 0.0
        var count = 0
        var i = from * 2
        val end = (to * 2).coerceAtMost(fft.size)
        while (i < end) {
            // 实部在偶数位，虚部在奇数位；取 |x| 近似幅值（二者都按 int8 存）
            val re = fft[i].toInt()
            val im = if (i + 1 < fft.size) fft[i + 1].toInt() else 0
            sum += sqrt((re * re + im * im).toDouble())
            count++
            i += 2
        }
        if (count == 0) return 0f
        // 255 是单点理论上限；再开方压一下动态范围，视觉上更线性
        val avg = (sum / count / 255.0).toFloat().coerceIn(0f, 1f)
        return sqrt(avg)
    }

    /** 上升快、下降慢：做出"跳起来再落下"的手感，而不是抖成一团。 */
    private fun blend(previous: Float, next: Float): Float =
        if (next > previous) previous + (next - previous) * 0.65f
        else previous * 0.86f + next * 0.14f

    private fun rms(waveform: ByteArray): Float {
        if (waveform.isEmpty()) return 0f
        var sum = 0.0
        for (b in waveform) {
            val v = b.toInt() / 128.0
            sum += v * v
        }
        return (sqrt(sum / waveform.size)).toFloat().coerceIn(0f, 1f)
    }

    private fun publish(level: Float, bass: Float, mid: Float, treble: Float) {
        ExternalVars.audioLevel = level
        ExternalVars.audioBass = bass
        ExternalVars.audioMid = mid
        ExternalVars.audioTreble = treble
        ExternalVars.audioBeat = 0f
        ExternalVars.bump()
    }

    /** 供调试：当前是否真的在采集。 */
    val isRunning: Boolean get() = running
}
