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
    val outMax: Float,
    /**
     * 表达式绑定（可选）。非空时**忽略 in/out 线性映射**，直接用它算出目标值。
     *
     * 为什么需要：线性映射只能做"0..1 → 你给的区间"，做不了平方响应、
     * 反相、钳制在中间、多个频段取最大这类曲线。表达式里可以直接写
     * audio(1..8) / bass() / mid() / treble() / level() / beat()，
     * 它们会先被展开成 sys.audio.* 变量再求值（见 BindingExpr）。
     */
    val expr: String? = null
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
    const val MEDIA_ARTIST = "sys.media.artist"
    const val MEDIA_ALBUM = "sys.media.album"
    const val MEDIA_APP = "sys.media.app"
    const val MEDIA_PLAYING = "sys.media.playing"
    const val MEDIA_POSITION = "sys.media.position"
    const val MEDIA_DURATION = "sys.media.duration"
    const val MEDIA_PROGRESS = "sys.media.progress"
    const val MEDIA_LYRIC = "sys.media.lyric"
    const val MEDIA_LYRIC_TRANS = "sys.media.lyricTrans"
    const val MEDIA_LYRIC_NEXT = "sys.media.lyricNext"

    // 音频可视化（需要录音权限；只在壁纸可见且预设用到时才采集）
    const val AUDIO_LEVEL = "sys.audio.level"
    const val AUDIO_BASS = "sys.audio.bass"
    const val AUDIO_MID = "sys.audio.mid"
    const val AUDIO_TREBLE = "sys.audio.treble"
    const val AUDIO_BEAT = "sys.audio.beat"

    /** 8 段频谱：sys.audio.b1 … sys.audio.b8（低频 → 高频）。 */
    const val AUDIO_BAND_1 = "sys.audio.b1"
    const val AUDIO_BAND_8 = "sys.audio.b8"
    const val STEPS_TODAY = "step.today"

    // ---- 天气（需定位权限或手动设置城市；30 分钟更新一次）----
    const val WEATHER_TEMP = "sys.weather.temp"
    const val WEATHER_FEELS_LIKE = "sys.weather.feelsLike"
    const val WEATHER_HUMIDITY = "sys.weather.humidity"
    const val WEATHER_CODE = "sys.weather.code"
    const val WEATHER_WIND = "sys.weather.wind"
    const val WEATHER_PRECIP = "sys.weather.precip"
    const val WEATHER_IS_DAY = "sys.weather.isDay"
    const val WEATHER_UPDATED = "sys.weather.updated"
    const val WEATHER_TEXT = "sys.weather.text"
    const val WEATHER_CITY = "sys.weather.city"
    const val WEATHER_DISTRICT = "sys.weather.district"

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
        MEDIA_ARTIST to "歌手",
        MEDIA_ALBUM to "专辑",
        MEDIA_APP to "当前播放器（网易云音乐…）",
        MEDIA_PLAYING to "是否正在播放（0/1）",
        MEDIA_POSITION to "播放位置（秒）",
        MEDIA_DURATION to "歌曲总长（秒）",
        MEDIA_PROGRESS to "播放进度（0-100）",
        MEDIA_LYRIC to "当前歌词",
        MEDIA_LYRIC_TRANS to "当前歌词译文",
        MEDIA_LYRIC_NEXT to "下一句歌词",
        AUDIO_LEVEL to "音频总音量（0-1）",
        AUDIO_BASS to "低频能量（0-1）",
        AUDIO_MID to "中频能量（0-1）",
        AUDIO_TREBLE to "高频能量（0-1）",
        AUDIO_BEAT to "节拍脉冲（0-1，检出一拍置 1）",
        AUDIO_BAND_1 to "频谱 1（低频，0-1）",
        "sys.audio.b2" to "频谱 2（0-1）",
        "sys.audio.b3" to "频谱 3（0-1）",
        "sys.audio.b4" to "频谱 4（0-1）",
        "sys.audio.b5" to "频谱 5（0-1）",
        "sys.audio.b6" to "频谱 6（0-1）",
        "sys.audio.b7" to "频谱 7（0-1）",
        AUDIO_BAND_8 to "频谱 8（高频，0-1）",
        STEPS_TODAY to "今日步数",
        WEATHER_TEMP to "气温（°C）",
        WEATHER_FEELS_LIKE to "体感温度（°C）",
        WEATHER_HUMIDITY to "湿度（%）",
        WEATHER_CODE to "天气代码（WMO，0 晴 / 61 小雨 / 95 雷雨）",
        WEATHER_WIND to "风速（km/h）",
        WEATHER_PRECIP to "降水量（mm）",
        WEATHER_IS_DAY to "是否白天（1/0）",
        WEATHER_UPDATED to "距上次更新的分钟数",
        WEATHER_TEXT to "天气描述（晴 / 多云 / 中雨…）",
        WEATHER_CITY to "城市名（市）",
        WEATHER_DISTRICT to "区 / 县名"
    )

    fun isKnown(name: String): Boolean = LABELS.containsKey(name)

    /** 只能用在文本里的变量（字符串型），不能绑定到数值属性。 */
    val TEXT_ONLY: Set<String> = setOf(
        MEDIA_TITLE, MEDIA_ARTIST, MEDIA_ALBUM, MEDIA_APP,
        MEDIA_LYRIC, MEDIA_LYRIC_TRANS, MEDIA_LYRIC_NEXT,
        WEATHER_TEXT, WEATHER_CITY, WEATHER_DISTRICT
    )

    /** 可以绑定到属性的数值变量。 */
    fun isNumeric(name: String): Boolean = isKnown(name) && name !in TEXT_ONLY
}
