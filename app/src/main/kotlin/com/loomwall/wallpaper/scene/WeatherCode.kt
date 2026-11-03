package com.loomwall.wallpaper.scene

import org.json.JSONObject

/**
 * 天气数据的**纯逻辑**部分：WMO 天气代码 → 中文、当前天气 JSON → 数据对象。
 *
 * 为什么单独拆出来：这部分不碰 Android，可以在电脑上用 JUnit 直接验；
 * 真机上"定位不对/网络不通"和"解析写错"是两类问题，混在一起很难查。
 *
 * 数据来自 Open-Meteo（无需 API Key）。它的 `weather_code` 用的是 WMO 4677 代码表。
 */
object WeatherCode {

    /** 解析出来的当前天气。温度单位 °C，风速 km/h，降水 mm。 */
    data class Current(
        val temp: Float,
        val feelsLike: Float,
        val humidity: Float,
        val code: Int,
        val wind: Float,
        val precip: Float,
        val isDay: Boolean
    )

    /**
     * WMO 代码 → 中文描述。
     *
     * 只做"给人看的一句话"，不做图标：壁纸里的图标应该由创作者用图形元素拼，
     * 硬编码一套图标字体反而限制风格。
     */
    fun describe(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51 -> "小毛雨"
        53 -> "毛毛雨"
        55 -> "大毛雨"
        56, 57 -> "冻毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "米雪"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85 -> "小阵雪"
        86 -> "强阵雪"
        95 -> "雷雨"
        96, 99 -> "雷雨伴冰雹"
        else -> "未知"
    }

    /**
     * 解析 Open-Meteo 的 `current` 段。
     *
     * 容忍字段缺失（用 §默认值），因为不同版本的接口字段会增减；
     * 但**整体解析失败返回 null**，让上层保留旧数据而不是把界面清零。
     */
    fun parseCurrent(json: String): Current? = runCatching {
        val root = JSONObject(json)
        val current = root.optJSONObject("current") ?: return null
        Current(
            temp = current.optDouble("temperature_2m", 0.0).toFloat(),
            feelsLike = current.optDouble("apparent_temperature", current.optDouble("temperature_2m", 0.0)).toFloat(),
            humidity = current.optDouble("relative_humidity_2m", 0.0).toFloat(),
            code = current.optInt("weather_code", -1),
            wind = current.optDouble("wind_speed_10m", 0.0).toFloat(),
            precip = current.optDouble("precipitation", 0.0).toFloat(),
            isDay = current.optInt("is_day", 1) == 1
        )
    }.getOrNull()

    /** 地理编码的一条命中：名称、坐标，以及接口给的行政区名（可能为空）。 */
    data class GeoHit(
        val name: String,
        val lat: Double,
        val lon: Double,
        val admin1: String = "",
        val admin2: String = ""
    )

    /**
     * 从地理编码接口的返回里取第一个结果。
     *
     * 顺带带出 `admin1/admin2`：查"清城区"时接口会给 `admin2 = 清远市`，
     * 这样即使接口本身不做区级匹配，也能拼出"清远市清城区"这种完整地名。
     */
    fun parseGeocode(json: String): GeoHit? = runCatching {
        val root = JSONObject(json)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.optJSONObject(0) ?: return null
        GeoHit(
            name = first.optString("name", "").ifBlank { "当前位置" },
            lat = first.optDouble("latitude", 0.0),
            lon = first.optDouble("longitude", 0.0),
            admin1 = first.optString("admin1", ""),
            admin2 = first.optString("admin2", "")
        )
    }.getOrNull()
}
