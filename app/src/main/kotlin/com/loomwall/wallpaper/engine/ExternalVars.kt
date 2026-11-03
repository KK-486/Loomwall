package com.loomwall.wallpaper.engine

/**
 * 由**其他组件**（通知监听服务、传感器）写入的变量。
 *
 * 为什么单独放一个对象：这些数据不是壁纸服务自己采集的，
 * 而是 NotificationListenerService 等组件推过来的，双方只需要一个线程安全的落地处。
 * 壁纸不可见时没人消费，但这些值最多几字节，不构成功耗问题。
 *
 * 媒体字段的更新时机：
 * - 元数据（标题/歌手/专辑/播放器）只在 MediaSession 回调里写，事件驱动、零轮询；
 * - 进度（positionMs）在播放中每 2 秒补一次，壁纸不可见时立刻停止（见 NotificationRelay）。
 */
internal object ExternalVars {
    /** 当前未读通知条数。 */
    @Volatile
    var notifyCount: Int = 0

    /** 正在播放的媒体标题；空串表示没有。 */
    @Volatile
    var mediaTitle: String = ""

    /** 歌手。 */
    @Volatile
    var mediaArtist: String = ""

    /** 专辑。 */
    @Volatile
    var mediaAlbum: String = ""

    /**
     * 当前播放器的显示名（"网易云音乐"）。
     * 由包名映射而来；映射不到时退化为包名本身，绝不返回空导致界面上什么都看不到。
     */
    @Volatile
    var mediaApp: String = ""

    /** 是否正在播放。 */
    @Volatile
    var mediaPlaying: Boolean = false

    /** 播放位置（毫秒）。 */
    @Volatile
    var mediaPositionMs: Long = 0L

    /** 总时长（毫秒）；未知时为 0。 */
    @Volatile
    var mediaDurationMs: Long = 0L

    /** 当前歌词行；空串表示无歌词或已到间隙。 */
    @Volatile
    var mediaLyric: String = ""

    /** 当前歌词的中文译文；没有译文时为空串。 */
    @Volatile
    var mediaLyricTrans: String = ""

    /** 下一句歌词；用于"双重显示"的排版。 */
    @Volatile
    var mediaLyricNext: String = ""

    // ---- 音频响应（可视化）----
    // 0..1 的归一化值；0 表示"没有数据/没有声音"，不会出现 NaN。
    @Volatile
    var audioLevel: Float = 0f

    @Volatile
    var audioBass: Float = 0f

    @Volatile
    var audioMid: Float = 0f

    @Volatile
    var audioTreble: Float = 0f

    /** 节拍脉冲：检出一次置 1，随后每帧衰减。适合驱动"跳动"效果。 */
    @Volatile
    var audioBeat: Float = 0f

    /**
     * 8 段频谱能量（从低频到高频，0..1）。
     *
     * 为什么用数组而不是 8 个字段：这几段永远一起读写、顺序固定，数组最直接。
     * 元素就地覆盖、不产生分配；读取方只用来画图，读到半新半旧也无害。
     */
    @Volatile
    var audioBands: FloatArray = FloatArray(8)

    /** 今日步数；null 表示还没拿到样本（没有传感器的设备）。 */
    @Volatile
    var stepsToday: Float? = null

    // ---- 天气（net/WeatherSource 写入；未授权/未联网时保持上一次的值）----

    /** 气温 °C */
    var weatherTemp: Float = 0f

    /** 体感温度 °C */
    var weatherFeelsLike: Float = 0f

    /** 相对湿度 % */
    var weatherHumidity: Float = 0f

    /** WMO 天气代码（0 晴 / 61 小雨 / 95 雷雨…，见 WeatherCode.describe） */
    var weatherCode: Float = -1f

    /** 风速 km/h */
    var weatherWind: Float = 0f

    /** 降水量 mm */
    var weatherPrecip: Float = 0f

    /** 是否白天（1/0） */
    var weatherIsDay: Float = 1f

    /** 上次成功更新的时间戳（毫秒）；0 表示从未拿到过 */
    var weatherUpdatedMs: Long = 0L

    /** 中文描述（"晴" / "中雨"） */
    var weatherText: String = ""

    /** 城市名（人工设置或反查，例如"清远市"） */
    var weatherCity: String = ""

    /** 区/县名（反查得到，例如"清城区"；拿不到时为空串） */
    var weatherDistrict: String = ""

    /** 数据变化时通知订阅方（壁纸可见时才有人订阅）。 */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun bump() {
        onChanged?.invoke()
    }
}
