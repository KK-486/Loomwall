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
}
