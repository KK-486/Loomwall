package com.loomwall.wallpaper.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 通知摘要规则。
 *
 * 守四件事：
 * 1. 优先级：微信 < QQ < 短信 < 其它（数字越小越靠前）；
 * 2. 过滤：本应用 / 常驻通知 / 分组汇总 / 空内容一律不显示；
 * 3. 排序稳定：同优先级按时间倒序，超出上限截断；
 * 4. 时间标签与截断规则固定（否则「通知栏对得上」这件事会飘）。
 */
class NotifyDigestTest {

    private val self = "com.loomwall.wallpaper"

    private fun item(pkg: String, title: String, text: String, at: Long, rankApp: String = pkg) =
        NotifyDigest.build(
            packageName = pkg,
            appLabel = rankApp,
            title = title,
            text = text,
            postTime = at,
            nowMs = at + 30_000L,
            isOngoing = false,
            isGroupSummary = false,
            selfPackage = self
        )

    @Test
    fun `wechat qq sms rank ahead of others`() {
        assertEquals(0, NotifyDigest.rankOf("com.tencent.mm", self))
        assertEquals(1, NotifyDigest.rankOf("com.tencent.mobileqq", self))
        assertEquals(2, NotifyDigest.rankOf("com.android.mms", self))
        assertEquals(3, NotifyDigest.rankOf("com.taobao.taobao", self))
    }

    @Test
    fun `own package is skipped`() {
        assertEquals(-1, NotifyDigest.rankOf(self, self))
        assertNull(item(self, "LOOW", "自己弹的通知", 1_000L))
    }

    @Test
    fun `ongoing and group summary are skipped`() {
        val ongoing = NotifyDigest.build(
            packageName = "com.android.mms", appLabel = "信息", title = "正在下载", text = "12%",
            postTime = 1_000L, nowMs = 2_000L, isOngoing = true, isGroupSummary = false, selfPackage = self
        )
        assertNull(ongoing)

        val summary = NotifyDigest.build(
            packageName = "com.tencent.mm", appLabel = "微信", title = "微信", text = "3 条新消息",
            postTime = 1_000L, nowMs = 2_000L, isOngoing = false, isGroupSummary = true, selfPackage = self
        )
        assertNull(summary)
    }

    @Test
    fun `empty content is skipped`() {
        assertNull(item("com.tencent.mm", "", "", 1_000L))
        assertNull(item("com.tencent.mm", "   ", "\n", 1_000L))
    }

    @Test
    fun `digest sorts by priority then newest first`() {
        val base = 1_000_000L
        val items = listOfNotNull(
            item("com.taobao.taobao", "淘宝", "你的包裹已发货", base + 5000L),
            item("com.tencent.mm", "张三", "晚上吃饭吗", base + 1000L),
            item("com.android.mms", "10086", "流量提醒", base + 3000L),
            item("com.tencent.mobileqq", "李四", "在吗", base + 2000L)
        )
        val out = NotifyDigest.digest(items, 3)
        assertEquals(3, out.size)
        assertEquals("张三", out[0].title)     // 微信最优先
        assertEquals("李四", out[1].title)     // QQ 次之
        assertEquals("10086", out[2].title)    // 短信第三
    }

    @Test
    fun `digest keeps newest first within same rank`() {
        val base = 2_000_000L
        val items = listOfNotNull(
            item("com.tencent.mm", "旧消息", "a", base + 1000L),
            item("com.tencent.mm", "新消息", "b", base + 9000L)
        )
        val out = NotifyDigest.digest(items, 2)
        assertEquals("新消息", out[0].title)
        assertEquals("旧消息", out[1].title)
    }

    @Test
    fun `time label follows the documented buckets`() {
        // 用真实量级的时间戳：1970 年的 now 减去 3 小时会变成负值，那不是被测语义
        val now = 1_700_000_000_000L
        assertEquals("刚刚", NotifyDigest.timeLabel(now - 5_000L, now))
        assertEquals("12 分", NotifyDigest.timeLabel(now - 12 * 60_000L, now))
        // 超过一小时给 HH:mm，长度固定 5
        assertEquals(5, NotifyDigest.timeLabel(now - 3 * 3_600_000L, now).length)
        assertEquals("", NotifyDigest.timeLabel(0L, now))
    }

    @Test
    fun `text is flattened and truncated`() {
        assertEquals("第一行 第二行", NotifyDigest.singleLine("第一行\n第二行"))
        assertEquals("a b", NotifyDigest.singleLine("a   \n  b"))
        val long = "字".repeat(60)
        val cut = NotifyDigest.truncate(long, 10)
        assertEquals(10, cut.length)
        assertEquals(true, cut.endsWith("…"))
    }

    // ---- 通知栏里"看不见"的通知必须过滤掉（否则面板会冒出一堆用户没见过的服务通知）----

    @Test
    fun `notClearable ongoing service notification is skipped`() {
        val item = NotifyDigest.build(
            packageName = "com.xiaomi.mi_connect_service", appLabel = "小米互联通信服务",
            title = "小米互联通信服务", text = "正在运行",
            postTime = 1_000L, nowMs = 2_000L,
            isOngoing = false, isGroupSummary = false, selfPackage = self,
            isClearable = false
        )
        assertNull(item)
    }

    @Test
    fun `foreground service notification is skipped`() {
        val item = NotifyDigest.build(
            packageName = "com.miui.systemui", appLabel = "跨屏协同服务",
            title = "跨屏协同服务", text = "正在运行",
            postTime = 1_000L, nowMs = 2_000L,
            isOngoing = false, isGroupSummary = false, selfPackage = self,
            isForegroundService = true
        )
        assertNull(item)
    }

    @Test
    fun `importance min notification is skipped`() {
        val item = NotifyDigest.build(
            packageName = "com.android.mms", appLabel = "信息",
            title = "10086", text = "流量提醒",
            postTime = 1_000L, nowMs = 2_000L,
            isOngoing = false, isGroupSummary = false, selfPackage = self,
            importance = 1
        )
        assertNull(item)
    }

    @Test
    fun `media notification is skipped`() {
        val item = NotifyDigest.build(
            packageName = "com.netease.cloudmusic", appLabel = "网易云音乐",
            title = "Never See Me Again", text = "Gustavo",
            postTime = 1_000L, nowMs = 2_000L,
            isOngoing = false, isGroupSummary = false, selfPackage = self,
            isMedia = true
        )
        assertNull(item)
    }

    @Test
    fun `normal chat message still passes`() {
        val item = NotifyDigest.build(
            packageName = "com.tencent.mm", appLabel = "微信",
            title = "张三", text = "晚上一起吃饭吗？",
            postTime = 1_000L, nowMs = 2_000L,
            isOngoing = false, isGroupSummary = false, selfPackage = self,
            importance = 4
        )
        assertEquals("张三", item?.title)
    }
}
