package com.loomwall.wallpaper.scene

/**
 * 变量绑定：把某个系统变量的值，映射到元素的某个属性上。
 *
 * 这是「事件源 → 条件/修饰 → 动作」模型里最轻的一环：**纯声明式**，
 * 预设里不出现任何代码，只有变量名与区间。
 *
 * 例：电量 0..100 映射到缩放 0.5..1.2
 * ```json
 * { "targetId": "rect_battery", "property": "scale",
 *   "source": "sys.battery.level", "in": [0, 100], "out": [0.5, 1.2] }
 * ```
 */
data class Binding(
    val targetId: String,
    val property: AnimatableProperty,
    val source: String,
    val inMin: Float,
    val inMax: Float,
    val outMin: Float,
    val outMax: Float
) {
    /** 把变量值映射到输出区间；输入区间退化时直接返回 outMin，避免除零。 */
    fun map(value: Float): Float {
        val span = inMax - inMin
        val t = if (span == 0f) 0f else ((value - inMin) / span).coerceIn(0f, 1f)
        return outMin + (outMax - outMin) * t
    }
}

/**
 * 引擎支持的变量名清单。
 *
 * 约定：读不到的变量**不进表**（而不是塞 0），这样绑定会保持元素静态值，
 * 不会出现"电量显示成 0"这种误导。
 */
object SystemVars {
    // 时间（分钟级刷新：秒级需要 1 秒唤醒一次，功耗不划算）
    const val TIME_HOUR = "sys.time.hour"
    const val TIME_MINUTE = "sys.time.minute"

    // 电池与电源
    const val BATTERY_LEVEL = "sys.battery.level"
    const val BATTERY_CHARGING = "sys.battery.charging"
    const val BATTERY_TEMP = "sys.battery.temp"
    const val POWER_CONNECTED = "sys.power.connected"

    // 屏幕
    const val SCREEN_ON = "sys.screen.on"

    // 网络
    const val NET_CONNECTED = "sys.net.connected"

    // 性能监视器
    const val MEM_USED_PCT = "sys.mem.usedPct"
    const val MEM_FREE_MB = "sys.mem.freeMb"
    const val STORAGE_USED_PCT = "sys.storage.usedPct"
    const val STORAGE_FREE_MB = "sys.storage.freeMb"
    const val RENDER_FPS = "sys.render.fps"

    // 需要用户授权的数据源
    const val NOTIFY_COUNT = "sys.notify.count"
    const val MEDIA_TITLE = "sys.media.title"
    const val STEPS_TODAY = "step.today"

    /** UI 用：变量名 → 中文说明。 */
    val LABELS: Map<String, String> = linkedMapOf(
        TIME_HOUR to "小时（0-23）",
        TIME_MINUTE to "分钟（0-59）",
        BATTERY_LEVEL to "电量（%）",
        BATTERY_CHARGING to "是否充电（0/1）",
        BATTERY_TEMP to "电池温度（℃）",
        POWER_CONNECTED to "是否接电源（0/1）",
        SCREEN_ON to "屏幕是否点亮（0/1）",
        NET_CONNECTED to "是否联网（0/1）",
        MEM_USED_PCT to "内存占用（%）",
        MEM_FREE_MB to "可用内存（MB）",
        STORAGE_USED_PCT to "存储占用（%）",
        STORAGE_FREE_MB to "可用存储（MB）",
        RENDER_FPS to "实际帧率",
        NOTIFY_COUNT to "未读通知条数",
        MEDIA_TITLE to "正在播放的歌曲",
        STEPS_TODAY to "今日步数"
    )

    fun isKnown(name: String): Boolean = LABELS.containsKey(name)

    /** 只能用在文本里的变量（字符串型），不能绑定到数值属性。 */
    val TEXT_ONLY: Set<String> = setOf(MEDIA_TITLE)

    /** 可以绑定到属性的数值变量。 */
    fun isNumeric(name: String): Boolean = isKnown(name) && name !in TEXT_ONLY
}
