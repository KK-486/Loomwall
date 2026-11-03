package com.loomwall.wallpaper.net

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.loomwall.wallpaper.engine.ExternalVars

/**
 * 通知监听 + 媒体会话：未读通知条数、正在播放的音乐（歌名/歌手/专辑/播放器/进度）。
 *
 * 需要用户在系统里手动开「通知使用权」——这是 Android 唯一的途径，
 * 应用自己无法绕过（也不应该绕过）。
 *
 * 数据来源与刷新策略：
 * - 通知条数：系统回调里统计，不轮询；
 * - 媒体元数据：[MediaController.registerCallback] 事件驱动，切歌/暂停立刻更新；
 * - 播放进度：**只在壁纸可见且正在播放时**每 2 秒补一次，其余时间完全停止——
 *   进度条需要连续值，但"看不见的壁纸"不该为此耗电。
 */
class NotificationRelay : NotificationListenerService() {

    companion object {
        private const val TAG = "LOOW.Notify"

        /** 刷新间隔下限：歌词边界很密时（一句只有几百毫秒）不要疯狂唤醒。 */
        private const val MIN_TICK_MS = 200L

        /** 刷新间隔上限：没有歌词或长间奏时，进度条也至少每秒走一次。 */
        private const val MAX_TICK_MS = 1_000L

        /**
         * 监听器是否真的被系统绑定上了。
         *
         * 为什么需要这个：**"授权"和"绑定"是两件事**。用户可能已经在
         * 「通知使用权」里打开开关（isNotificationListenerAccessGranted 返回 true），
         * 但定制 ROM 的自启动管理会拒绝 bind，于是回调一个都不触发、计数永远是 0。
         * 只看权限开关会得出"已授权却没用"的错误结论，必须看这个真实连接状态。
         */
        @Volatile
        var connected: Boolean = false
            private set

        /** 最近一次统计到的通知条数（-1 表示还没成功统计过）。 */
        @Volatile
        var lastCount: Int = -1
            private set

        /**
         * 壁纸是否可见。由壁纸服务在 onVisibilityChanged 里设置。
         *
         * 为什么不直接一直轮询进度：壁纸不可见时没人看，多一次唤醒就是纯浪费。
         * 这个开关只影响"进度轮询"，元数据仍是事件驱动。
         */
        @Volatile
        private var wallpaperVisible: Boolean = false

        /** 可见性变化时通知已连接的那个实例（同进程，静态回调即可）。 */
        @Volatile
        private var visibilityListener: ((Boolean) -> Unit)? = null

        /**
         * 壁纸服务调用：可见性变化时开/停进度轮询。
         * 用进程内静态回调而不是跨进程通信——两者本来就在同一个进程里。
         */
        fun setWallpaperVisible(visible: Boolean) {
            wallpaperVisible = visible
            Log.i(TAG, "壁纸可见性 → $visible（监听器${if (visibilityListener != null) "已" else "未"}连接）")
            visibilityListener?.invoke(visible)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val controllers = LinkedHashMap<String, MediaController>()

    /** 上次播报的"歌名|歌手"，用来判断是否换歌（换歌才去拉歌词）。 */
    private var lastTrackKey = ""

    private val positionTick = object : Runnable {
        override fun run() {
            val changed = publishPosition()
            // 位置每次都变，所以"在播"就等于"要重绘"；这一步曾经漏掉，
            // 结果歌词要等下一个通知事件才刷新（用户实测：唱完两三句才跳）。
            if (changed) ExternalVars.bump()
            scheduleNextTick()
        }
    }

    /**
     * 排下一次刷新。
     *
     * 不固定 2 秒：把下一次定在「下一句歌词开始」的那一刻，歌词不会迟到；
     * 同时用 200ms～1s 夹住，保证进度条也在动、又不会高频唤醒。
     */
    private fun scheduleNextTick() {
        handler.removeCallbacks(positionTick)
        if (!wallpaperVisible) {
            Log.d(TAG, "调度跳过：壁纸不可见")
            return
        }
        val chosen = controllers.values.firstOrNull { isPlaying(it) } ?: run {
            val detail = controllers.values.joinToString("; ") { c ->
                (c.packageName ?: "?") + "=" + (c.playbackState?.state?.toString() ?: "null")
            }
            Log.d(TAG, "调度跳过：没有正在播放的会话 → $detail")
            return
        }
        val pos = ExternalVars.mediaPositionMs
        val nextLine = LyricsSource.nextLineTimeMs(pos)
        val delay = if (nextLine > pos) {
            (nextLine - pos).coerceIn(MIN_TICK_MS, MAX_TICK_MS)
        } else {
            MAX_TICK_MS
        }
        handler.postDelayed(positionTick, delay)
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接")
        connected = true
        // 注册可见性回调：壁纸可见时才开始补播放进度
        visibilityListener = { visible -> onWallpaperVisibilityChanged(visible) }
        refresh()
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "通知监听已断开")
        connected = false
        visibilityListener = null
        teardownControllers()
    }

    override fun onDestroy() {
        connected = false
        visibilityListener = null
        teardownControllers()
        handler.removeCallbacks(positionTick)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 媒体通知通常随切歌更新，用它作为"重新对齐会话"的廉价触发点
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    /** 重新统计条数、重建媒体会话订阅。 */
    private fun refresh() {
        runCatching {
            val count = activeNotifications?.size ?: 0
            ExternalVars.notifyCount = count
            lastCount = count
        }
        runCatching { syncSessions() }
        ExternalVars.bump()
    }

    /** 取当前活跃的媒体会话并订阅回调；会话变化时重建。 */
    private fun syncSessions() {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, NotificationRelay::class.java)
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrNull() ?: return

        val alive = sessions.mapNotNull { it.packageName }.toSet()
        // 先摘掉已经不存在的
        val iterator = controllers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in alive) {
                runCatching { entry.value.unregisterCallback(mediaCallback) }
                iterator.remove()
            }
        }
        // 再补上新增的
        for (controller in sessions) {
            val key = controller.packageName ?: continue
            if (controllers.containsKey(key)) continue
            runCatching { controller.registerCallback(mediaCallback, handler) }
            controllers[key] = controller
        }
        // 立刻播报一次当前状态（否则要等用户操作才更新）
        publishMetadata()
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishMetadata()
            ExternalVars.bump()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "播放状态回调：state=${state?.state}")
            publishMetadata()
            if (publishPosition()) ExternalVars.bump()
            scheduleNextTick()
        }

        override fun onSessionDestroyed() {
            runCatching { syncSessions() }
        }
    }

    /** 从"正在播放"的会话读元数据；没有在播的，退一步取最近有标题的会话。 */
    private fun publishMetadata() {
        val playing = controllers.values.firstOrNull { isPlaying(it) }
        val chosen = playing ?: controllers.values.firstOrNull { titleOf(it).isNotBlank() } ?: run {
            ExternalVars.mediaPlaying = false
            return
        }

        val title = titleOf(chosen)
        val artist = chosen.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: chosen.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val album = chosen.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = chosen.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        ExternalVars.mediaTitle = title
        ExternalVars.mediaArtist = artist
        ExternalVars.mediaAlbum = album
        ExternalVars.mediaApp = MediaApps.displayName(chosen.packageName)
        ExternalVars.mediaPlaying = isPlaying(chosen)
        ExternalVars.mediaDurationMs = duration.coerceAtLeast(0L)

        // 换歌才去匹配歌词：同一首歌反复刷新既费流量也没意义
        val key = (title + "|" + artist).lowercase()
        if (key != lastTrackKey && title.isNotBlank()) {
            lastTrackKey = key
            LyricsSource.onTrackChanged(applicationContext, title, artist)
        }
        if (publishPosition()) ExternalVars.bump()
        // 关键：调度链必须有人"点火"。壁纸可能先可见、监听器后连接，
        // 那次可见性事件早就过去了，靠它启动会永远等不到第一次刷新。
        scheduleNextTick()
    }

    /**
     * 读一次播放位置并推进歌词。
     *
     * @return 是否发生了变化（位置动了 / 歌词换了），调用方据此决定要不要重绘
     */
    private fun publishPosition(): Boolean {
        val playing = controllers.values.firstOrNull { isPlaying(it) }
        val chosen = playing ?: controllers.values.firstOrNull { titleOf(it).isNotBlank() } ?: return false
        val state = chosen.playbackState ?: return false

        // PlaybackState.position 是"上次更新时的位置"，播放中要用流逝时间外推
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            if (elapsed > 0) position += (elapsed * state.playbackSpeed).toLong()
        }
        val duration = ExternalVars.mediaDurationMs
        if (duration > 0) position = position.coerceIn(0L, duration)
        if (position < 0) position = 0L

        val previous = ExternalVars.mediaPositionMs
        ExternalVars.mediaPositionMs = position
        // 歌词换行也算"变化"：进度只挪了几十毫秒但整句换掉了，必须重绘
        val lyricChanged = LyricsSource.updatePosition(position)
        return lyricChanged || kotlin.math.abs(position - previous) >= 100L
    }

    private fun isPlaying(controller: MediaController): Boolean =
        controller.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun titleOf(controller: MediaController): String =
        controller.metadata?.description?.title?.toString().orEmpty()

    private fun teardownControllers() {
        handler.removeCallbacks(positionTick)
        for (controller in controllers.values) {
            runCatching { controller.unregisterCallback(mediaCallback) }
        }
        controllers.clear()
        lastTrackKey = ""
        LyricsSource.clear()
    }

    /**
     * 壁纸可见性变化时由壁纸服务调用：可见才开始补进度，不可见立刻停。
     * 用伴生对象的静态开关而不是跨进程通信——两者在同一个进程里。
     */
    fun onWallpaperVisibilityChanged(visible: Boolean) {
        wallpaperVisible = visible
        handler.removeCallbacks(positionTick)
        if (visible) {
            publishMetadata()
            if (publishPosition()) ExternalVars.bump()
            scheduleNextTick()
        }
    }
}
