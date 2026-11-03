package com.loomwall.wallpaper.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.loomwall.wallpaper.engine.ExternalVars
import com.loomwall.wallpaper.scene.WeatherCode
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 天气数据源（Open-Meteo，无需 API Key）。
 *
 * ## 功耗纪律（项目红线）
 * - 只有「预设确实用到 sys.weather.*」+「壁纸可见」两个条件同时成立才联网；
 * - 联网是**单次**请求，不轮询；可见期间每 30 分钟补一次，不可见立刻取消定时器；
 * - 定位只读 `getLastKnownLocation`（不主动开启 GPS 扫描）。
 *
 * ## 失败策略
 * 任何失败（没权限 / 没网 / 接口变了）都**保留上一次的数据**并只打日志，
 * 绝不把界面清零 —— 天气是装饰信息，不该让壁纸看起来"坏了"。
 *
 * ## 精确到区
 * 天气本身是格子数据，市与区差别通常不到 1°C；用户要的"区"是**地名精度**。
 * Open-Meteo 的地理编码只认市（"清远市"），区级名字查不到，所以区名一律来自
 * Android 的 [android.location.Geocoder] 反向解析（`subLocality`）。
 */
object WeatherSource {

    private const val TAG = "LOOW.Weather"
    private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    private const val HTTP_TIMEOUT_MS = 8_000
    private const val CACHE_FILE = "weather-cache.json"
    private const val DEBUG_CITY_FILE = "weather-city.txt"
    private const val DEBUG_REFRESH_FILE = "weather-refresh.txt"
    private const val PREFS = "loow"
    private const val PREF_CITY = "weather_city"

    /**
     * 最后已知位置的最大可接受年龄。
     *
     * 卡这个是为了避免"另一个城市的旧定位"被当成当前位置（换城市后天气显示成外地）。
     * 24 小时是折中：手机不常开定位时也能用，又不至于拿一周前的坐标。
     */
    private const val MAX_LOCATION_AGE_MS = 24 * 60 * 60 * 1000L

    /** 一个地点的解析结果：市 + 区 + 坐标。 */
    private data class Place(
        val city: String,
        val district: String,
        val lat: Double,
        val lon: Double
    )

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    @Volatile private var needed = false
    @Volatile private var visible = false
    @Volatile private var city = ""
    @Volatile private var fetching = false
    private var lastFetchMs = 0L
    private var lastPlace = ""

    /** 本次拉取是否用了 weather-city.txt 的调试城市（用于完成后删除该文件）。 */
    @Volatile private var debugCityUsed = ""

    private val refreshTask = Runnable {
        if (visible && needed) fetch(force = false)
        scheduleNext()
    }

    fun attachContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        city = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_CITY, "").orEmpty()
        loadCache()
        Log.i(TAG, "已挂载（城市设置=\"${city.ifBlank { "跟随定位" }}\"，缓存=${if (lastFetchMs > 0) "有" else "无"}）")
    }

    /** 预设是否用到天气变量：由渲染线程在加载场景后告知。 */
    fun setNeeded(value: Boolean) {
        if (needed != value) Log.i(TAG, "setNeeded($value)")
        needed = value
        // 值没变也 sync 一次：这样"重新加载壁纸"能重新检查调试/刷新钩子，不用等 30 分钟
        sync()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        Log.i(TAG, "setVisible($value)")
        sync()
    }

    /** 用户手动设城市：空字符串表示跟随定位。 */
    fun setCity(value: String) {
        val next = value.trim()
        val changed = next != city
        city = next
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_CITY, city).apply()
        // 城市变了就先把旧城市的数据清掉：新城市拉取失败时不会挂着别人的天气
        if (changed) clear()
        lastFetchMs = 0L
        if (visible && needed) fetch(force = true)
    }

    /**
     * 清空天气数据（内存 + 缓存）。
     *
     * 换城市时必须调用：否则新城市拉取失败时，界面还挂着旧城市的气温，
     * 看起来就像"定位错了"（实际是旧数据没清）。
     */
    fun clear() {
        ExternalVars.weatherTemp = 0f
        ExternalVars.weatherFeelsLike = 0f
        ExternalVars.weatherHumidity = 0f
        ExternalVars.weatherCode = -1f
        ExternalVars.weatherWind = 0f
        ExternalVars.weatherPrecip = 0f
        ExternalVars.weatherIsDay = 1f
        ExternalVars.weatherUpdatedMs = 0L
        ExternalVars.weatherText = ""
        ExternalVars.weatherCity = ""
        ExternalVars.weatherDistrict = ""
        lastFetchMs = 0L
        lastPlace = ""
        appContext?.let { ctx -> runCatching { File(ctx.filesDir, CACHE_FILE).delete() } }
        ExternalVars.bump()
        Log.i(TAG, "已清空天气数据与缓存")
    }

    fun refreshNow() {
        if (visible) fetch(force = true)
    }

    /** 当前设置的城市（空 = 跟随定位）。 */
    fun currentCity(): String = city

    // ---------------------------------------------------------------- 调度

    private fun sync() {
        handler.removeCallbacks(refreshTask)
        if (!visible || !needed) return

        // 调试钩子：files/weather-refresh.txt 存在就强制拉一次，用完删除。
        // 用途：验证"定位/联网/解析"链路时不必等 30 分钟，也不用重启进程。
        val force = appContext?.let { ctx ->
            val file = File(ctx.filesDir, DEBUG_REFRESH_FILE)
            if (file.exists()) {
                runCatching { file.delete() }
                true
            } else false
        } ?: false
        if (force) {
            Log.i(TAG, "发现 $DEBUG_REFRESH_FILE，强制刷新一次")
            fetch(force = true)
            scheduleNext()
            return
        }

        val stale = System.currentTimeMillis() - lastFetchMs > REFRESH_INTERVAL_MS
        if (stale) fetch(force = false)
        scheduleNext()
    }

    /**
     * 可见期间每 30 分钟补一次。
     *
     * 这里是**唯一**的定时器，且只在 `visible && needed` 时为真；
     * 不可见时 [sync] 会把它移除（红线要求：不可见不允许有定时器）。
     */
    private fun scheduleNext() {
        handler.removeCallbacks(refreshTask)
        if (visible && needed) handler.postDelayed(refreshTask, REFRESH_INTERVAL_MS)
    }

    private fun fetch(force: Boolean) {
        if (fetching) return
        if (!force && System.currentTimeMillis() - lastFetchMs < REFRESH_INTERVAL_MS) return
        fetching = true
        val worker = Thread {
            try {
                doFetch()
            } catch (t: Throwable) {
                Log.w(TAG, "拉取失败：${t.javaClass.simpleName} ${t.message}（保留上次数据）")
            } finally {
                fetching = false
            }
        }
        worker.name = "loow-weather"
        worker.start()
    }

    private fun doFetch() {
        val ctx = appContext ?: return
        val place = locate(ctx)
        if (place == null) {
            Log.w(TAG, "拿不到位置：没有定位权限且没设置城市（可在 App 抽屉里设置天气城市）")
            return
        }
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${"%.4f".format(place.lat)}&longitude=${"%.4f".format(place.lon)}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day," +
            "precipitation,weather_code,wind_speed_10m&timezone=auto"

        val body = httpGet(url) ?: run {
            Log.w(TAG, "天气接口没有返回内容（网络不可用？）")
            return
        }
        val current = WeatherCode.parseCurrent(body) ?: run {
            Log.w(TAG, "天气 JSON 解析失败（接口字段变了？）")
            return
        }

        lastPlace = place.city
        lastFetchMs = System.currentTimeMillis()

        ExternalVars.weatherTemp = current.temp
        ExternalVars.weatherFeelsLike = current.feelsLike
        ExternalVars.weatherHumidity = current.humidity
        ExternalVars.weatherCode = current.code.toFloat()
        ExternalVars.weatherWind = current.wind
        ExternalVars.weatherPrecip = current.precip
        ExternalVars.weatherIsDay = if (current.isDay) 1f else 0f
        ExternalVars.weatherUpdatedMs = lastFetchMs
        ExternalVars.weatherText = WeatherCode.describe(current.code)
        ExternalVars.weatherCity = place.city
        ExternalVars.weatherDistrict = place.district
        ExternalVars.bump()

        saveCache()

        // 调试城市是**一次性**的：用完删掉，避免把某个城市永久钉死在设备上
        if (debugCityUsed.isNotBlank()) {
            runCatching { File(ctx.filesDir, DEBUG_CITY_FILE).delete() }
            Log.i(TAG, "调试城市文件已删除（一次性生效）")
            debugCityUsed = ""
        }

        val where = if (place.district.isBlank()) place.city else "${place.city}${place.district}"
        Log.i(
            TAG,
            "更新成功：$where ${"%.1f".format(current.temp)}°C ${ExternalVars.weatherText}" +
                "（湿度 ${current.humidity.toInt()}%，风 ${"%.0f".format(current.wind)}km/h）"
        )
    }

    // ---------------------------------------------------------------- 定位

    /** 解析当前位置：优先人工设置的城市，其次最后已知位置。 */
    private fun locate(ctx: Context): Place? {
        // 调试钩子（与 tilt-debug.txt 同一套思路）：放 weather-city.txt 可跳过定位权限，
        // 单独验证"地理编码 + 联网 + 解析"链路。
        val debugCity = runCatching {
            val f = File(ctx.filesDir, DEBUG_CITY_FILE)
            if (f.exists()) f.readText().trim() else ""
        }.getOrDefault("")
        if (debugCity.isNotBlank()) {
            debugCityUsed = debugCity
            Log.i(TAG, "调试城市：$debugCity（来自 $DEBUG_CITY_FILE）")
        }
        val effectiveCity = if (debugCity.isNotBlank()) debugCity else city

        if (effectiveCity.isNotBlank()) {
            geocode(ctx, effectiveCity)?.let { return it }
            Log.w(TAG, "城市 \"$effectiveCity\" 没有查到坐标，回退到定位")
        }

        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        // 只读"最后已知位置"：这两次调用不触发新的定位扫描，几乎零耗电
        val candidates = listOfNotNull(
            runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
            runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        )
        val now = System.currentTimeMillis()
        val fresh = candidates.filter { now - it.time <= MAX_LOCATION_AGE_MS }
        val stale = candidates.filter { now - it.time > MAX_LOCATION_AGE_MS }
        if (fresh.isEmpty()) {
            if (stale.isNotEmpty()) {
                val hours = (now - stale.maxOf { it.time }) / 3_600_000L
                Log.w(TAG, "定位太旧（${hours} 小时前），本次不采用；请设置天气城市或重新定位")
            }
            return null
        }
        val best = fresh.maxByOrNull { it.time } ?: return null
        Log.i(TAG, "使用定位：provider=${best.provider}，${(now - best.time) / 60_000L} 分钟前")

        val (cityName, districtName) = reversePlace(ctx, best.latitude, best.longitude)
        return Place(
            city = cityName.ifBlank { "当前位置" },
            district = districtName,
            lat = best.latitude,
            lon = best.longitude
        )
    }

    /**
     * 坐标 → (市, 区)。
     *
     * 中文地址里：`locality` 是地级市（清远市），`subLocality` 是区/县（清城区）。
     * 有些机型只给 `subAdminArea`，所以按"粗 → 细"依次兜底。
     */
    private fun reversePlace(ctx: Context, lat: Double, lon: Double): Pair<String, String> {
        val address = runCatching {
            @Suppress("DEPRECATION")
            val list = android.location.Geocoder(ctx, java.util.Locale.CHINA)
                .getFromLocation(lat, lon, 1)
            list?.firstOrNull()
        }.getOrNull() ?: return Pair("", "")

        val cityName = listOfNotNull(address.locality, address.subAdminArea, address.adminArea)
            .firstOrNull { it.isNotBlank() } ?: ""
        val districtName = listOfNotNull(address.subLocality, address.thoroughfare)
            .firstOrNull { it.isNotBlank() && it != cityName } ?: ""
        return Pair(cityName, districtName)
    }

    /**
     * 城市名 → 坐标（Open-Meteo 地理编码，同样不需要 Key），再反查出区名。
     *
     * 中文地名必须带行政后缀才查得到：接口对 "清远" 返回空，对 "清远市" 才命中。
     * 所以按「原名 → 原名+市/区/县 → 去掉后缀」依次试，第一个命中即用。
     */
    private fun geocode(ctx: Context, name: String): Place? {
        for (candidate in cityCandidates(name)) {
            val url = "https://geocoding-api.open-meteo.com/v1/search?" +
                "name=${URLEncoder.encode(candidate, "UTF-8")}&count=1&language=zh&format=json"
            val body = httpGet(url) ?: continue
            val hit = WeatherCode.parseGeocode(body) ?: continue
            if (candidate != name) Log.i(TAG, "城市名补全后命中：$candidate → ${hit.name}")

            // 区名：优先用反向地理编码（接口没有区级数据），拿不到就留空
            val cityName = hit.name.ifBlank { candidate }
            val district = reversePlace(ctx, hit.lat, hit.lon).second
            return Place(city = cityName, district = district, lat = hit.lat, lon = hit.lon)
        }
        return null
    }

    /** 一次查询要试的城市名候选。 */
    private fun cityCandidates(name: String): List<String> {
        val base = name.trim()
        if (base.isEmpty()) return emptyList()
        val list = LinkedHashSet<String>()
        list.add(base)
        for (suffix in listOf("市", "区", "县")) {
            if (!base.endsWith(suffix)) list.add(base + suffix)
        }
        if (base.length > 2 && base.last() in listOf('市', '区', '县')) {
            list.add(base.dropLast(1))
        }
        return list.toList()
    }

    // ---------------------------------------------------------------- 网络与缓存

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            setRequestProperty("User-Agent", "Loomwall/1.0 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode}：$url")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /**
     * 缓存写盘：壁纸进程被系统杀掉后重启，能先显示上次的天气，而不是空一片。
     * 只存"已解析的值"，不存原始 JSON —— 格式变化时不会因为旧缓存解析失败。
     */
    private fun saveCache() {
        val ctx = appContext ?: return
        runCatching {
            val obj = JSONObject().apply {
                put("ts", lastFetchMs)
                put("place", lastPlace)
                put("city", ExternalVars.weatherCity)
                put("district", ExternalVars.weatherDistrict)
                put("temp", ExternalVars.weatherTemp.toDouble())
                put("feels", ExternalVars.weatherFeelsLike.toDouble())
                put("humidity", ExternalVars.weatherHumidity.toDouble())
                put("code", ExternalVars.weatherCode.toDouble())
                put("wind", ExternalVars.weatherWind.toDouble())
                put("precip", ExternalVars.weatherPrecip.toDouble())
                put("isDay", ExternalVars.weatherIsDay.toDouble())
                put("text", ExternalVars.weatherText)
            }
            File(ctx.filesDir, CACHE_FILE).writeText(obj.toString())
        }.onFailure { Log.w(TAG, "缓存写入失败：${it.message}") }
    }

    private fun loadCache() {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, CACHE_FILE)
        if (!file.exists()) return
        runCatching {
            val obj = JSONObject(file.readText())
            lastFetchMs = obj.optLong("ts", 0L)
            lastPlace = obj.optString("place", "")
            ExternalVars.weatherTemp = obj.optDouble("temp", 0.0).toFloat()
            ExternalVars.weatherFeelsLike = obj.optDouble("feels", 0.0).toFloat()
            ExternalVars.weatherHumidity = obj.optDouble("humidity", 0.0).toFloat()
            ExternalVars.weatherCode = obj.optDouble("code", -1.0).toFloat()
            ExternalVars.weatherWind = obj.optDouble("wind", 0.0).toFloat()
            ExternalVars.weatherPrecip = obj.optDouble("precip", 0.0).toFloat()
            ExternalVars.weatherIsDay = obj.optDouble("isDay", 1.0).toFloat()
            ExternalVars.weatherUpdatedMs = lastFetchMs
            ExternalVars.weatherText = obj.optString("text", "")
            ExternalVars.weatherCity = obj.optString("city", lastPlace)
            ExternalVars.weatherDistrict = obj.optString("district", "")
        }.onFailure { Log.w(TAG, "缓存读取失败：${it.message}") }
    }
}
