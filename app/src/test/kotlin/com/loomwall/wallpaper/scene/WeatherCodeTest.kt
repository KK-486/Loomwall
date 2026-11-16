package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气的纯逻辑：WMO 代码映射 + JSON 解析。
 *
 * 真机上"定位不对/网络不通"和"解析写错"是两类完全不同的问题，
 * 这里把后者变成可单测的部分。
 */
class WeatherCodeTest {

    @Test
    fun `常见天气代码有中文描述`() {
        assertEquals("晴", WeatherCode.describe(0))
        assertEquals("多云", WeatherCode.describe(2))
        assertEquals("阴", WeatherCode.describe(3))
        assertEquals("中雨", WeatherCode.describe(63))
        assertEquals("雷雨", WeatherCode.describe(95))
        assertEquals("雷雨伴冰雹", WeatherCode.describe(99))
    }

    @Test
    fun `未知代码不会崩`() {
        assertEquals("未知", WeatherCode.describe(1234))
        assertEquals("未知", WeatherCode.describe(-1))
    }

    @Test
    fun `解析 Open-Meteo 的 current 段`() {
        val json = """
            {"latitude":30.29,"longitude":120.16,"timezone":"Asia/Shanghai",
             "current":{"time":"2026-11-03T10:45","interval":900,
               "temperature_2m":16.8,"relative_humidity_2m":80,
               "apparent_temperature":17.4,"is_day":1,
               "precipitation":0.0,"weather_code":3,"wind_speed_10m":3.5}}
        """.trimIndent()
        val current = WeatherCode.parseCurrent(json)!!
        assertEquals(16.8f, current.temp, 1e-4f)
        assertEquals(17.4f, current.feelsLike, 1e-4f)
        assertEquals(80f, current.humidity, 1e-4f)
        assertEquals(3, current.code)
        assertEquals(3.5f, current.wind, 1e-4f)
        assertTrue(current.isDay)
    }

    @Test
    fun `缺字段时给默认值而不是抛异常`() {
        val json = """{"current":{"temperature_2m":20.0}}"""
        val current = WeatherCode.parseCurrent(json)!!
        assertEquals(20f, current.temp, 1e-4f)
        assertEquals("体感缺省时退化成气温", 20f, current.feelsLike, 1e-4f)
        assertEquals(0f, current.humidity, 1e-4f)
        assertEquals(-1, current.code)
    }

    @Test
    fun `没有 current 段时返回 null 让上层保留旧数据`() {
        assertNull(WeatherCode.parseCurrent("""{"latitude":1}"""))
        assertNull(WeatherCode.parseCurrent("不是 JSON"))
    }

    @Test
    fun `解析地理编码结果`() {
        val json = """
            {"results":[{"id":1808926,"name":"杭州","latitude":30.29365,
             "longitude":120.16142,"country":"中国","admin1":"浙江省"}]}
        """.trimIndent()
        val hit = WeatherCode.parseGeocode(json)!!
        assertEquals("杭州", hit.name)
        assertEquals(30.29365, hit.lat, 1e-6)
        assertEquals(120.16142, hit.lon, 1e-6)
        assertEquals("浙江省", hit.admin1)
    }

    @Test
    fun `地理编码查不到时返回 null`() {
        assertNull(WeatherCode.parseGeocode("""{"generationtime_ms":0.1}"""))
        assertNull(WeatherCode.parseGeocode("""{"results":[]}"""))
    }

    /**
     * 多日预报解析：接口返回 6 天（昨天 + 今天 + 未来 4 天），
     * 温度/降水/日出日落都要对上号，不能错位。
     */
    @Test
    fun `解析多日预报`() {
        val json = """
            {"daily":{
              "time":["2026-11-10","2026-11-11","2026-11-12","2026-11-13","2026-11-14","2026-11-15"],
              "weather_code":[3,3,3,82,63,1],
              "temperature_2m_max":[20.1,22.2,25.7,24.9,27.4,27.6],
              "temperature_2m_min":[12.1,12.5,13.5,17.7,17.8,17.4],
              "precipitation_sum":[0,0,0,42.5,4,0],
              "precipitation_probability_max":[0,0,0,90,89,41],
              "sunrise":["2026-11-10T06:38","2026-11-11T06:39","2026-11-12T06:40",
                          "2026-11-13T06:40","2026-11-14T06:41","2026-11-15T06:41"],
              "sunset":["2026-11-10T17:44","2026-11-11T17:43","2026-11-12T17:43",
                          "2026-11-13T17:43","2026-11-14T17:42","2026-11-15T17:42"]
            }}
        """.trimIndent()
        val days = WeatherCode.parseDaily(json)!!
        assertEquals(6, days.size)
        // 索引 1 = 今天
        assertEquals(22.2f, days[1].max, 0.01f)
        assertEquals(12.5f, days[1].min, 0.01f)
        assertEquals("06:39", days[1].sunrise)
        assertEquals("17:43", days[1].sunset)
        // 索引 3 = 后天：90% 降雨、42.5mm
        assertEquals(90f, days[3].rain, 0.01f)
        assertEquals(42.5f, days[3].precip, 0.01f)
        assertEquals("强阵雨", WeatherCode.describe(days[3].code))
    }

    @Test
    fun `多日预报缺字段时返回 null 而不是崩`() {
        assertNull(WeatherCode.parseDaily("""{"current":{}}"""))
        assertNull(WeatherCode.parseDaily("不是 JSON"))
    }

    /** 未来 6 小时降水：取最大概率与累计降水，用于"降雨提醒"。 */
    @Test
    fun `解析未来降水`() {
        val json = """
            {"hourly":{"time":["2026-11-11T04:00","2026-11-11T05:00","2026-11-11T06:00"],
                       "precipitation_probability":[10,75,30],
                       "precipitation":[0.0,2.4,0.6]}}
        """.trimIndent()
        val rain = WeatherCode.parseHourly(json, 6)!!
        assertEquals(75f, rain.maxProb, 0.01f)
        assertEquals(3.0f, rain.sumMm, 0.01f)
        assertNull(WeatherCode.parseHourly("""{"daily":{}}"""))
    }
}
