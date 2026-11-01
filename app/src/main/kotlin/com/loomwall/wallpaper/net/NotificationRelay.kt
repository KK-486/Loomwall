package com.loomwall.wallpaper.net

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.loomwall.wallpaper.engine.ExternalVars

/**
 * 通知监听：拿到「未读通知条数」和「正在播放的媒体标题」。
 *
 * 需要用户在系统里手动开「通知使用权」——这是 Android 唯一的途径，
 * 应用自己无法绕过（也不应该绕过）。
 *
 * 设计约束：
 * - 只在系统回调里计数，不轮询；
 * - 数据写进 [ExternalVars]，壁纸可见时才被消费；
 * - 用户没授权时这个服务根本不会启动，等于零开销。
 */
class NotificationRelay : NotificationListenerService() {

    companion object {
        private const val TAG = "LOOW.Notify"

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
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接")
        connected = true
        refresh()
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "通知监听已断开")
        connected = false
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    /** 重新统计条数 + 读取媒体标题。 */
    private fun refresh() {
        runCatching {
            val count = activeNotifications?.size ?: 0
            ExternalVars.notifyCount = count
            lastCount = count
        }
        ExternalVars.mediaTitle = runCatching { currentMediaTitle() }.getOrDefault("")
        ExternalVars.bump()
    }

    /**
     * 正在播放的媒体标题。
     * 需要通知使用权（`getActiveSessions` 会校验调用方是不是通知监听器）。
     */
    private fun currentMediaTitle(): String {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return ""
        val component = ComponentName(this, NotificationRelay::class.java)
        val sessions: List<MediaController> = manager.getActiveSessions(component)
        for (controller in sessions) {
            val state = controller.playbackState ?: continue
            val playing = state.state == android.media.session.PlaybackState.STATE_PLAYING
            val description = controller.metadata?.description
            val title = description?.title?.toString().orEmpty()
            if (playing && title.isNotBlank()) return title
        }
        // 没有正在播放的，就退一步取最近一个会话的标题（暂停状态也显示歌名）
        for (controller in sessions) {
            val title = controller.metadata?.description?.title?.toString().orEmpty()
            if (title.isNotBlank()) return title
        }
        return ""
    }

}
