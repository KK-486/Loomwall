package com.loomwall.wallpaper.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.app.ActivityManager
import android.util.Log
import com.loomwall.wallpaper.scene.SystemVars
import java.util.Calendar

/**
 * 系统变量的**快照**：写方是 [SystemInputHub]（主线程），读方是渲染线程。
 * 用不可变 map 整体替换，读方永远拿到一致的一帧，不需要加锁。
 */
internal object VarsSnapshot {
    @Volatile
    private var values: Map<String, Float> = emptyMap()

    /** 文本型变量（歌曲名等）：数值表放不下，单独一张。 */
    @Volatile
    private var texts: Map<String, String> = emptyMap()

    fun publish(next: Map<String, Float>, nextTexts: Map<String, String> = texts) {
        values = next
        texts = nextTexts
    }

    fun get(): Map<String, Float> = values

    fun getTexts(): Map<String, String> = texts
}

/**
 * 系统数据源采集器。
 *
 * 功耗纪律（项目红线）：
 * - 只在**壁纸可见**时 attach，不可见立刻 detach：注销广播、取消定时器；
 * - 时间用**对齐到整分钟**的定时器，不是每秒轮询；
 * - 内存/存储这类"读一次就有值"的，每分钟刷新一次足够；
 * - 传感器（阶段 4 后续）必须按绑定按需订阅，这里先不做。
 */
class SystemInputHub(
    private val context: Context,
    private val onChanged: () -> Unit
) {
    // 主线程写电量/内存等；渲染线程会写 fps —— 用并发容器避免竞争
    private val values = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val handler = Handler(Looper.getMainLooper())

    private var attached = false
    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastMinute = -1
    private var stepListener: android.hardware.SensorEventListener? = null
    private var stepManager: android.hardware.SensorManager? = null

    private val minuteTick = object : Runnable {
        override fun run() {
            if (!attached) return
            refreshMediaTitle()
            refreshTime()
            refreshMemory()
            refreshStorage()
            publish()
            // 对齐到下一个整分钟，避免漂移
            handler.postDelayed(this, delayToNextMinute())
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        registerReceivers()
        refreshAll()
        publish()
        handler.postDelayed(minuteTick, delayToNextMinute())
        ExternalVars.onChanged = { handler.post { publish() } }
        startStepCounter()
        Log.i(TAG, "系统变量已订阅（壁纸可见）")
    }

    fun detach() {
        if (!attached) return
        attached = false
        handler.removeCallbacks(minuteTick)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        networkCallback?.let { runCatching { networkManager()?.unregisterNetworkCallback(it) } }
        networkCallback = null
        ExternalVars.onChanged = null
        stopStepCounter()
        Log.i(TAG, "系统变量已全部注销（壁纸不可见）")
    }

    /** 渲染线程回报实际帧率（由 RenderThread 每 2 秒算一次）。 */
    fun notifyFps(fps: Float) {
        if (!attached) return
        values[SystemVars.RENDER_FPS] = fps
        // 只通知重绘，不在这里重复刷新其它变量
        onChanged()
    }

    // ---- 采集 ----

    private fun refreshAll() {
        refreshMediaTitle()
        refreshTime()
        refreshBattery()
        refreshScreen()
        refreshNetwork()
        refreshMemory()
        refreshStorage()
    }

    private fun refreshTime() {
        val c = Calendar.getInstance()
        values[SystemVars.TIME_HOUR] = c.get(Calendar.HOUR_OF_DAY).toFloat()
        values[SystemVars.TIME_MINUTE] = c.get(Calendar.MINUTE).toFloat()
        lastMinute = c.get(Calendar.MINUTE)
    }

    @Suppress("DEPRECATION")
    private fun refreshBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            values[SystemVars.BATTERY_LEVEL] = level * 100f / scale
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tempTenths != Int.MIN_VALUE) {
            values[SystemVars.BATTERY_TEMP] = tempTenths / 10f
        }
        values[SystemVars.BATTERY_CHARGING] =
            if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            ) 1f else 0f
    }

    private fun refreshScreen() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        values[SystemVars.SCREEN_ON] = if (pm?.isInteractive == true) 1f else 0f
    }

    private fun refreshNetwork() {
        // 没有 ACCESS_NETWORK_STATE 时 getActiveNetwork 会抛 SecurityException。
        // 变量读不到就不进表（绑定自动跳过），绝不能让壁纸因此崩溃。
        runCatching {
            val connected = networkManager()?.activeNetwork != null
            values[SystemVars.NET_CONNECTED] = if (connected) 1f else 0f
        }.onFailure { Log.w(TAG, "读取网络状态失败：${it.message}") }
    }

    private fun refreshMemory() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem > 0) {
            values[SystemVars.MEM_USED_PCT] = (info.totalMem - info.availMem) * 100f / info.totalMem
        }
        values[SystemVars.MEM_FREE_MB] = info.availMem / (1024f * 1024f)
    }

    private fun refreshStorage() {
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            if (total > 0) {
                values[SystemVars.STORAGE_USED_PCT] = (total - free) * 100f / total
            }
            values[SystemVars.STORAGE_FREE_MB] = free / (1024f * 1024f)
        }
    }

    // ---- 媒体 ----

    /**
     * 读取正在播放的媒体标题。
     *
     * 为什么不让 NotificationRelay 单独负责：切歌/播放/暂停**不会**产生通知回调，
     * 只在通知事件里刷新会一直拿到旧值（实测「播着歌却显示空」就是这个原因）。
     * 这里直接用 MediaSessionManager 查，只要有通知使用权就能读。
     */
    private fun refreshMediaTitle() {
        val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
        if (manager == null) {
            ExternalVars.mediaTitle = ""
            return
        }
        val component = android.content.ComponentName(context, com.loomwall.wallpaper.net.NotificationRelay::class.java)
        val title = runCatching {
            val sessions = manager.getActiveSessions(component)
            // 优先取正在播放的
            val playing = sessions.firstOrNull {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING &&
                    !it.metadata?.description?.title.isNullOrBlank()
            }
            (playing ?: sessions.firstOrNull { !it.metadata?.description?.title.isNullOrBlank() })
                ?.metadata?.description?.title?.toString()
        }.getOrNull().orEmpty()

        if (title != ExternalVars.mediaTitle) {
            ExternalVars.mediaTitle = title
            Log.d(TAG, "媒体标题：${if (title.isEmpty()) "(无)" else title}")
        }
    }
    // ---- 计步 ----

    /** TYPE_STEP_COUNTER 给的是"开机以来总步数"，要按天取差值。 */
    private fun startStepCounter() {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        val sensor = manager.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) ?: return
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val total = event.values.firstOrNull() ?: return
                val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(java.util.Date())
                val prefs = context.getSharedPreferences("lwpkit_steps", Context.MODE_PRIVATE)
                val savedDay = prefs.getString("day", null)
                var baseline = prefs.getFloat("baseline", -1f)
                if (savedDay != today || baseline < 0f) {
                    // 日期变了（或第一次）：把当前总步数当作今天的起点
                    baseline = if (savedDay == today && baseline >= 0f) baseline else total
                    prefs.edit().putString("day", today).putFloat("baseline", baseline).apply()
                }
                ExternalVars.stepsToday = (total - baseline).coerceAtLeast(0f)
                publish()
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        runCatching {
            manager.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            stepListener = listener
            stepManager = manager
        }.onFailure { Log.w(TAG, "计步传感器不可用：${it.message}") }
    }

    private fun stopStepCounter() {
        stepManager?.let { manager ->
            stepListener?.let { runCatching { manager.unregisterListener(it) } }
        }
        stepListener = null
        stepManager = null
    }
    // ---- 广播 ----

    private fun registerReceivers() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> refreshBattery()
                    Intent.ACTION_POWER_CONNECTED -> values[SystemVars.POWER_CONNECTED] = 1f
                    Intent.ACTION_POWER_DISCONNECTED -> values[SystemVars.POWER_CONNECTED] = 0f
                    Intent.ACTION_SCREEN_ON -> values[SystemVars.SCREEN_ON] = 1f
                    Intent.ACTION_SCREEN_OFF -> values[SystemVars.SCREEN_ON] = 0f
                }
                publish()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { context.registerReceiver(r, filter) }
            .onSuccess { receiver = r }
            .onFailure { Log.w(TAG, "注册广播失败：${it.message}") }

        runCatching {
            val cm = networkManager() ?: return@runCatching
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    values[SystemVars.NET_CONNECTED] = 1f
                    publish()
                }

                override fun onLost(network: Network) {
                    values[SystemVars.NET_CONNECTED] = 0f
                    publish()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }.onFailure { Log.w(TAG, "监听网络失败：${it.message}") }
    }

    private fun networkManager(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun publish() {
        val snapshot = HashMap(values)
        // 通知 / 媒体 / 步数由别的组件写入，这里合并进快照
        snapshot[SystemVars.NOTIFY_COUNT] = ExternalVars.notifyCount.toFloat()
        val steps = ExternalVars.stepsToday
        if (steps != null) snapshot[SystemVars.STEPS_TODAY] = steps

        // 媒体数值：进度条 / 播放状态可以直接绑定，不必在模板里写表达式
        snapshot[SystemVars.MEDIA_PLAYING] = if (ExternalVars.mediaPlaying) 1f else 0f
        snapshot[SystemVars.MEDIA_POSITION] = ExternalVars.mediaPositionMs / 1000f
        snapshot[SystemVars.MEDIA_DURATION] = ExternalVars.mediaDurationMs / 1000f
        snapshot[SystemVars.MEDIA_PROGRESS] = if (ExternalVars.mediaDurationMs > 0L) {
            (ExternalVars.mediaPositionMs.toFloat() * 100f / ExternalVars.mediaDurationMs)
                .coerceIn(0f, 100f)
        } else 0f

        // 音频可视化：没有采集时这些值恒为 0，变量仍然可读（预设不会因此报错）
        snapshot[SystemVars.AUDIO_LEVEL] = ExternalVars.audioLevel
        snapshot[SystemVars.AUDIO_BASS] = ExternalVars.audioBass
        snapshot[SystemVars.AUDIO_MID] = ExternalVars.audioMid
        snapshot[SystemVars.AUDIO_TREBLE] = ExternalVars.audioTreble
        snapshot[SystemVars.AUDIO_BEAT] = ExternalVars.audioBeat

        // 天气：没拿到过数据（weatherUpdatedMs == 0）时也照常发布，
        // 让文本显示空字符串、数值显示 0，而不是整段占位符不替换
        snapshot[SystemVars.WEATHER_TEMP] = ExternalVars.weatherTemp
        snapshot[SystemVars.WEATHER_FEELS_LIKE] = ExternalVars.weatherFeelsLike
        snapshot[SystemVars.WEATHER_HUMIDITY] = ExternalVars.weatherHumidity
        snapshot[SystemVars.WEATHER_CODE] = ExternalVars.weatherCode
        snapshot[SystemVars.WEATHER_WIND] = ExternalVars.weatherWind
        snapshot[SystemVars.WEATHER_PRECIP] = ExternalVars.weatherPrecip
        snapshot[SystemVars.WEATHER_IS_DAY] = ExternalVars.weatherIsDay
        snapshot[SystemVars.WEATHER_UPDATED] = if (ExternalVars.weatherUpdatedMs == 0L) 999f
        else ((System.currentTimeMillis() - ExternalVars.weatherUpdatedMs) / 60_000L).toFloat()
        val bands = ExternalVars.audioBands
        for (i in 0 until 8) {
            // 命名 b1..b8：给创作者看的下标从 1 开始更自然
            snapshot["sys.audio.b" + (i + 1)] = bands[i]
        }

        val texts = HashMap<String, String>()
        fun putText(name: String, value: String) {
            if (value.isNotBlank()) texts[name] = value
        }
        putText(SystemVars.MEDIA_TITLE, ExternalVars.mediaTitle)
        putText(SystemVars.MEDIA_ARTIST, ExternalVars.mediaArtist)
        putText(SystemVars.MEDIA_ALBUM, ExternalVars.mediaAlbum)
        putText(SystemVars.MEDIA_APP, ExternalVars.mediaApp)
        // 歌词允许为空：空串表示"这段没有歌词"，模板里该显示空白而不是留着上一句
        texts[SystemVars.WEATHER_TEXT] = ExternalVars.weatherText
        texts[SystemVars.WEATHER_CITY] = ExternalVars.weatherCity
        texts[SystemVars.WEATHER_DISTRICT] = ExternalVars.weatherDistrict
        texts[SystemVars.MEDIA_LYRIC] = ExternalVars.mediaLyric
        texts[SystemVars.MEDIA_LYRIC_TRANS] = ExternalVars.mediaLyricTrans
        texts[SystemVars.MEDIA_LYRIC_NEXT] = ExternalVars.mediaLyricNext

        VarsSnapshot.publish(snapshot, texts)
        onChanged()
    }

    /** 距离下一整分钟的毫秒数，保证时间变量在整分时更新。 */
    private fun delayToNextMinute(): Long {
        val c = Calendar.getInstance()
        val secondsLeft = 60 - c.get(Calendar.SECOND)
        return secondsLeft * 1000L - c.get(Calendar.MILLISECOND) + 50L
    }

    private companion object {
        const val TAG = "LOOW.SystemVars"
    }
}
