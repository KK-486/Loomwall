package com.loomwall.wallpaper.engine

/**
 * 由**其他组件**（通知监听服务、传感器）写入的变量。
 *
 * 为什么单独放一个对象：这些数据不是壁纸服务自己采集的，
 * 而是 NotificationListenerService 等组件推过来的，双方只需要一个线程安全的落地处。
 * 壁纸不可见时没人消费，但这些值最多几字节，不构成功耗问题。
 */
internal object ExternalVars {
    /** 当前未读通知条数。 */
    @Volatile
    var notifyCount: Int = 0

    /** 正在播放的媒体标题；空串表示没有。 */
    @Volatile
    var mediaTitle: String = ""

    /** 今日步数；null 表示还没拿到样本（没有传感器的设备）。 */
    @Volatile
    var stepsToday: Float? = null

    /** 数据变化时通知订阅方（壁纸可见时才有人订阅）。 */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun bump() {
        onChanged?.invoke()
    }
}
