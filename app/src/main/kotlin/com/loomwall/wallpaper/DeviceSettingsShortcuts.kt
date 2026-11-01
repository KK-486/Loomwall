package com.loomwall.wallpaper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * 「去系统里把限制关掉」的快捷入口。
 *
 * 为什么需要单独一个文件：这类页面**没有统一标准**。同一件"自启动"，
 * MIUI / EMUI / ColorOS / 原生 Android 的组件名各不相同，而且随时可能改名。
 * 所以这里对每个入口都按「先猜最可能的、再退标准页」的顺序尝试，
 * 失败不抛给用户，而是换下一个——用户最终至少能看到系统应用详情页。
 *
 * 判据说明：不能用 `resolveActivity` 判断可用性（部分 ROM 返回可解析但实际打不开），
 * 这里用「启动是否抛异常」作为唯一判据，失败就继续退。
 */
object DeviceSettingsShortcuts {

    private const val TAG = "LOOW.Settings"

    /**
     * 打开「自启动 / 后台启动」管理页。
     *
     * 这是 MIUI 上通知监听被拒绑的根因所在（见 NotificationRelay.connected 的注释）。
     */
    fun openAutostart(activity: Activity): Boolean {
        val packageName = activity.packageName
        val candidates = listOf(
            // MIUI（小米 / Redmi / POCO）
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // MIUI 新版有的机型走这里
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartDetailManagementActivity"
            ),
            // EMUI / HarmonyOS
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // ColorOS / realme
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            // FuntouchOS
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )

        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(activity, intent, "自启动 ${component.flattenToShortString()}")) return true
        }

        // 退一步：直接打开本应用的系统详情页，用户能看到"自启动/省电"入口
        return openAppDetails(activity)
    }

    /**
     * 打开「省电策略 / 电池优化白名单」。
     *
     * 顺序设计：
     * 1. 先请求"忽略电池优化"（原生标准，会弹系统对话框，用户点允许即可）
     * 2. MIUI 的省电策略页
     * 3. 系统的电池优化列表
     * 4. 应用详情页
     */
    fun openPowerWhitelist(activity: Activity): Boolean {
        val packageName = activity.packageName

        // 1. 标准：请求忽略电池优化（需要 manifest 声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
        val requestIgnore = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(activity, requestIgnore, "请求忽略电池优化")) return true

        // 2. MIUI 省电策略
        val miuiPower = listOf(
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
        )
        for (component in miuiPower) {
            val intent = Intent().apply {
                this.component = component
                putExtra("package_name", packageName)
                putExtra("package_label", activity.getString(R.string.app_name))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(activity, intent, "省电策略 ${component.flattenToShortString()}")) return true
        }

        // 3. 系统电池优化列表（所有应用）
        val ignoreList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(activity, ignoreList, "电池优化列表")) return true

        // 4. 应用详情页
        return openAppDetails(activity)
    }

    /** 本应用的系统详情页：所有入口都失效时的兜底，任何 Android 都有。 */
    fun openAppDetails(activity: Activity): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + activity.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(activity, intent, "应用详情")) return true
        Toast.makeText(activity, R.string.main_settings_unavailable, Toast.LENGTH_LONG).show()
        return false
    }

    private fun tryStart(context: Activity, intent: Intent, label: String): Boolean {
        return try {
            context.startActivity(intent)
            Log.i(TAG, "已打开：$label")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "不可用（无匹配页面）：$label")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "被拒绝：$label - ${e.message}")
            false
        }
    }
}
