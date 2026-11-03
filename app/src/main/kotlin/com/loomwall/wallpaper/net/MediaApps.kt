package com.loomwall.wallpaper.net

/**
 * 播放器包名 → 显示名。
 *
 * 为什么需要这张表：MediaSession 只给包名（com.netease.cloudmusic），
 * 直接显示在壁纸上没人看得懂；映射不到时退回包名，保证"至少有东西可显示"。
 *
 * 只覆盖常见播放器；新增一行就能支持新 App，不需要任何适配代码——
 * 只要是走 Android 标准 MediaSession 的播放器，识别都是自动的。
 */
object MediaApps {

    private val NAMES = mapOf(
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ音乐",
        "com.kugou.android" to "酷狗音乐",
        "cn.kuwo.player" to "酷我音乐",
        "com.xs.fm" to "番茄畅听",
        "com.luna.music" to "汽水音乐",
        "com.ximalaya.ting.android" to "喜马拉雅",
        "com.tencent.weishi" to "微视",
        "tv.danmaku.bili" to "哔哩哔哩",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.google.android.youtube" to "YouTube",
        "com.spotify.music" to "Spotify",
        "com.apple.android.music" to "Apple Music",
        "com.amazon.mp3" to "Amazon Music",
        "com.soundcloud.android" to "SoundCloud",
        "com.deezer.android" to "Deezer",
        "com.miui.player" to "小米音乐",
        "com.oppo.music" to "OPPO 音乐",
        "com.vivo.music" to "vivo 音乐",
        "com.huawei.music" to "华为音乐",
        "com.android.music" to "系统音乐",
        "com.miui.videoplayer" to "小米视频",
        "org.videolan.vlc" to "VLC"
    )

    /** 拿不到映射时返回包名本身（不要返回空串，否则界面上会突然空白）。 */
    fun displayName(packageName: String?): String {
        val pkg = packageName.orEmpty()
        if (pkg.isBlank()) return ""
        return NAMES[pkg] ?: pkg
    }
}
