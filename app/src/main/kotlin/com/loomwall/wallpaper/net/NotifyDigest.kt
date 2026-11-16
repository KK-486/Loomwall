package com.loomwall.wallpaper.net

/**
 * 通知摘要：把「通知栏里现在有什么」压成壁纸能显示的几行文字。
 *
 * 为什么要单独一个纯逻辑文件：
 * 1. 过滤/排序/时间格式化是**规则**，不是 IO，抽出来才能在纯 JVM 里单测；
 * 2. 壁纸和通知栏必须**对得上**——用户手动清空通知栏后要立刻变空，
 *    所以一切以 `activeNotifications` 的当前快照为准，不自己攒历史。
 *
 * 隐私约定：这里只做内存加工，不落盘、不联网。
 */
object NotifyDigest {

    /** 一条可以显示的通知。 */
    data class Item(
        /** 应用显示名，如「微信」。 */
        val app: String,
        /** 发送人或标题。 */
        val title: String,
        /** 正文，已压成单行并截断。 */
        val text: String,
        /** 时间标签：「刚刚」「12 分」「21:04」。 */
        val time: String,
        /** 优先级：数字越小越靠前。 */
        val rank: Int,
        /** 原始投递时间，用于同优先级排序。 */
        val postTime: Long
    )

    /** NotificationManager.IMPORTANCE_MIN：系统不显示在通知栏，等于"不存在"。 */
    private const val IMPORTANCE_MIN = 1

    /** 显示行数上限：面板只留得下这么多行。 */
    const val MAX_LINES = 4

    /** 正文最大字符数：超出截断并加省略号。 */
    private const val MAX_TEXT = 42

    /** 微信 / QQ / 短信优先，其余排在后面。 */
    private const val RANK_WECHAT = 0
    private const val RANK_QQ = 1
    private const val RANK_SMS = 2
    private const val RANK_OTHER = 3

    private val SMS_PACKAGES = setOf(
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.miui.smsextra",
        "com.samsung.android.messaging"
    )

    /** 应用包名 → 优先级；返回 -1 表示这条通知不该显示。 */
    fun rankOf(packageName: String, selfPackage: String): Int {
        val pkg = packageName.lowercase()
        if (pkg == selfPackage.lowercase()) return -1
        return when {
            pkg == "com.tencent.mm" -> RANK_WECHAT
            pkg == "com.tencent.mobileqq" || pkg == "com.tencent.tim" -> RANK_QQ
            pkg in SMS_PACKAGES -> RANK_SMS
            else -> RANK_OTHER
        }
    }

    /**
     * 组装一条可显示的通知；返回 null 表示跳过。
     *
     * 跳过的情况：
     * - 本应用自己的通知（壁纸不该把自己弹的东西再显示一遍）
     * - 常驻通知（前台服务、下载进度）——它们永远在，会把真正的消息挤掉
     * - 分组汇总（`FLAG_GROUP_SUMMARY`）——内容通常是「3 条新消息」，没有信息量
     * - 正文和标题都为空
     */
    fun build(
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        postTime: Long,
        nowMs: Long,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        selfPackage: String,
        /** 用户能不能把它划掉。false = 常驻项（"正在运行"、前台服务），通知栏里本来就不显示。 */
        isClearable: Boolean = true,
        /** 是否前台服务通知（FLAG_FOREGROUND_SERVICE）。 */
        isForegroundService: Boolean = false,
        /** 通知重要度（Ranking.importance）：1 = IMPORTANCE_MIN，系统连通知栏都不画。 */
        importance: Int = 3,
        /** 是否媒体通知（category=transport）。壁纸自己就有音乐面板，不需要再列一遍。 */
        isMedia: Boolean = false
    ): Item? {
        val rank = rankOf(packageName, selfPackage)
        if (rank < 0) return null
        if (isOngoing || isGroupSummary) return null
        // 以下四类是"通知栏里看不见但 activeNotifications 里有"的常客：
        // 小米互联/跨屏协同之类的常驻服务、前台服务、IMPORTANCE_MIN 的静默通知、媒体通知。
        // 不过滤掉的话，面板上会出现一堆用户根本没在通知栏见过的东西。
        if (!isClearable) return null
        if (isForegroundService) return null
        if (importance <= IMPORTANCE_MIN) return null
        if (isMedia) return null

        val cleanTitle = singleLine(title)
        val cleanText = truncate(singleLine(text), MAX_TEXT)
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) return null

        return Item(
            app = appLabel.ifBlank { packageName },
            title = cleanTitle,
            text = cleanText,
            time = timeLabel(postTime, nowMs),
            rank = rank,
            postTime = postTime
        )
    }

    /**
     * 排序 + 截断。
     *
     * 微信/QQ/短信按时间倒序排在前面，其余按时间倒序跟在后面——
     * 用户要求「优先微信 QQ 短信」，所以打字时它们永远在可视的前几行。
     */
    fun digest(items: List<Item>, limit: Int = MAX_LINES): List<Item> =
        items.sortedWith(compareBy({ it.rank }, { -it.postTime })).take(limit)

    /** 时间标签：1 分钟内「刚刚」，1 小时内「N 分」，其余给 HH:mm。 */
    fun timeLabel(postTime: Long, nowMs: Long): String {
        if (postTime <= 0L) return ""
        val delta = nowMs - postTime
        if (delta < 60_000L) return "刚刚"
        if (delta < 3_600_000L) return (delta / 60_000L).toString() + " 分"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = postTime
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    /** 压成单行：换行与连续空白都替成一个空格。 */
    fun singleLine(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s{2,}"), " ").trim()

    /** 按字符截断并补省略号。 */
    fun truncate(raw: String, max: Int): String {
        if (raw.length <= max) return raw
        if (max <= 1) return raw.take(max)
        return raw.take(max - 1) + "…"
    }
}
