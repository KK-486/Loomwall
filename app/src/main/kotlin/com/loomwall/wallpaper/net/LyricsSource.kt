package com.loomwall.wallpaper.net

import android.content.Context
import android.util.Log
import com.loomwall.wallpaper.engine.ExternalVars
import com.loomwall.wallpaper.scene.Lrc
import com.loomwall.wallpaper.scene.LyricLine
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 在线歌词：按"歌名 + 歌手"匹配网易云的 LRC（原文 + 译文），再按播放进度取当前行。
 *
 * 为什么不去读播放器自己的歌词：
 * MediaSession 协议里根本没有歌词字段，网易云/QQ音乐/酷狗都不会把它放进元数据。
 * 想显示歌词只有两条路——自己准备 LRC，或者按歌名去公网匹配。这里做后者。
 *
 * 翻译（tlyric）：
 * 网易云的歌词接口会把翻译单独放在 `tlyric.lyric` 里，时间戳与原文一一对应。
 * 我们按时间戳对齐，**不按行号**——行数经常不等（间奏、重复段落会被省略）。
 *
 * 稳定性与合规：
 * - 用的是公开的非官方接口，随时可能限流或改版，所以**任何失败都只是"没有歌词"**，
 *   绝不影响壁纸渲染（上层只看到空字符串）；
 * - 每首歌只请求一次，成功后落盘缓存（filesDir/lyrics），重播不再联网；
 * - 所有网络 IO 都在单线程池里，渲染线程永远不会等它。
 */
object LyricsSource {

    private const val TAG = "LOOW.Lyrics"
    private const val TIMEOUT_MS = 8000
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"

    /** 当前加载成功的歌词行。 */
    @Volatile
    private var lines: List<LyricLine> = emptyList()

    /** 时间戳 → 译文。 */
    @Volatile
    private var translations: Map<Long, String> = emptyMap()

    /** 当前歌词对应的"歌名|歌手"，用于去重（同一首歌不重复请求）。 */
    @Volatile
    private var loadedKey: String = ""

    /** 正在请求的 key，避免拖动进度时重复发请求。 */
    @Volatile
    private var loadingKey: String = ""

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "loow-lyrics").apply { isDaemon = true }
    }

    /** 歌名/歌手变化时调用；内部自行判断要不要真的去联网。 */
    fun onTrackChanged(context: Context, title: String, artist: String) {
        val key = (title.trim() + "|" + artist.trim()).lowercase()
        if (title.isBlank()) {
            clear()
            return
        }
        if (key == loadedKey || key == loadingKey) return

        // 换歌先清空，避免新歌开头显示上一首的歌词
        lines = emptyList()
        translations = emptyMap()
        loadedKey = ""
        ExternalVars.mediaLyric = ""
        ExternalVars.mediaLyricTrans = ""
        ExternalVars.mediaLyricNext = ""
        loadingKey = key

        val appContext = context.applicationContext
        io.execute {
            try {
                val file = cacheFile(appContext, key)
                val cached = if (file.exists()) file.readText() else null
                // 缓存文件用 "=====TRANS=====" 分隔原文与译文，避免再维护第二个文件
                val parts = cached?.split(TRANS_SEPARATOR)
                val lrcText = parts?.getOrNull(0)
                val transText = parts?.getOrNull(1)
                if (lrcText.isNullOrBlank()) {
                    val fetched = fetchLyrics(title, artist)
                    if (fetched != null) {
                        val (main, trans) = fetched
                        runCatching {
                            file.writeText(main + TRANS_SEPARATOR + trans)
                        }
                        lines = Lrc.parse(main)
                        translations = Lrc.parseTranslations(trans)
                    }
                } else {
                    lines = Lrc.parse(lrcText)
                    translations = Lrc.parseTranslations(transText.orEmpty())
                }
                loadedKey = key
                Log.i(
                    TAG,
                    "歌词就绪：${lines.size} 行、译文 ${translations.size} 行「$title」"
                )
            } catch (t: Throwable) {
                Log.w(TAG, "取歌词失败：${t.message}")
                lines = emptyList()
                translations = emptyMap()
                loadedKey = key
            } finally {
                loadingKey = ""
                // 位置可能已经在播放了，立刻算一次当前行
                updatePosition(ExternalVars.mediaPositionMs)
            }
        }
    }

    /**
     * 播放进度变化时调用：算出当前行/译文/下一句写进 ExternalVars。
     *
     * @return 是否有任何显示内容发生变化（调用方据此决定要不要请求重绘）
     */
    fun updatePosition(positionMs: Long): Boolean {
        val list = lines
        if (list.isEmpty()) return false
        val index = Lrc.indexAt(list, positionMs)
        val current = if (index >= 0) list[index].text else ""
        val trans = if (index >= 0) translations[list[index].timeMs].orEmpty() else ""
        val next = if (index + 1 in list.indices) list[index + 1].text else ""

        var changed = false
        if (current != ExternalVars.mediaLyric) {
            ExternalVars.mediaLyric = current
            changed = true
            // 带时间戳的换行日志：用来核对"歌词是否准点跳"，而不是凭感觉
            Log.i(TAG, "换行 @${positionMs}ms → ${current.take(24)}")
        }
        if (trans != ExternalVars.mediaLyricTrans) {
            ExternalVars.mediaLyricTrans = trans
            changed = true
        }
        if (next != ExternalVars.mediaLyricNext) {
            ExternalVars.mediaLyricNext = next
            changed = true
        }
        return changed
    }

    /**
     * 下一句歌词的时间点（毫秒）；没有下一句或没有歌词时返回 -1。
     *
     * 用途：调度器把下一次刷新的时间定在"下一句开始的那一刻"，
     * 而不是傻等固定间隔——歌词几乎不会迟到，同时唤醒次数最少。
     */
    fun nextLineTimeMs(positionMs: Long): Long {
        val list = lines
        if (list.isEmpty()) return -1L
        val index = Lrc.indexAt(list, positionMs)
        val nextIndex = index + 1
        return if (nextIndex in list.indices) list[nextIndex].timeMs else -1L
    }

    fun clear() {
        lines = emptyList()
        translations = emptyMap()
        loadedKey = ""
        loadingKey = ""
        ExternalVars.mediaLyric = ""
        ExternalVars.mediaLyricTrans = ""
        ExternalVars.mediaLyricNext = ""
    }

    // ---- 网络 ----

    private fun fetchLyrics(title: String, artist: String): Pair<String, String>? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val songId = searchSongId(query) ?: return null
        return fetchLyricText(songId)
    }

    private fun searchSongId(query: String): Long? {
        val url = "https://music.163.com/api/search/get/web?s=" +
            URLEncoder.encode(query, "UTF-8") + "&type=1&limit=5"
        val json = get(url) ?: return null
        val songs = runCatching {
            JSONObject(json).getJSONObject("result").getJSONArray("songs")
        }.getOrNull() ?: return null
        if (songs.length() == 0) return null
        return songs.getJSONObject(0).optLong("id", 0L).takeIf { it > 0 }
    }

    private fun fetchLyricText(songId: Long): Pair<String, String>? {
        // lv=原文 / tv=译文；拿不到译文时返回空串，不影响原文显示
        val url = "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1"
        val json = get(url) ?: return null
        return runCatching {
            val root = JSONObject(json)
            val main = root.getJSONObject("lrc").optString("lyric", "")
            val trans = runCatching {
                root.getJSONObject("tlyric").optString("lyric", "")
            }.getOrDefault("")
            main to trans
        }.getOrNull()?.takeIf { it.first.isNotBlank() }
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://music.163.com/")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode}: $url")
                return null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "请求失败：${t.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 用 key 的哈希做文件名：歌名里可能有 `/`、`?` 这类不能进路径的字符。 */
    private fun cacheFile(context: Context, key: String): File {
        val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
        return File(dir, key.hashCode().toUInt().toString(16) + ".lrc")
    }

    /** 缓存文件里分隔原文/译文的标记（普通 LRC 不会出现这一行）。 */
    private const val TRANS_SEPARATOR = "\n=====TRANS=====\n"
}
