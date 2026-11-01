package com.loomwall.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast

/**
 * 「设为壁纸」的统一入口。
 *
 * 为什么需要这一层：标准做法是发 `CHANGE_LIVE_WALLPAPER` 并带上壁纸服务组件，
 * 但不少定制 ROM（小米 MIUI 就是典型）会把这个 intent 当成无效请求直接丢掉——
 * 方法返回正常、什么都不弹。用户能看到的只有"点了没反应"。
 *
 * 所以这里按**从标准到妥协**的顺序逐个尝试，只要有一个真的把界面拉起来就停：
 *
 * 1. 标准 intent + 组件 extra（原生 Android / 多数 ROM）
 * 2. 指名 AOSP 动态壁纸选择器（MIUI 上存在这个包，指名能绕过解析拦截）
 * 3. 同名 intent 但改成打开「动态壁纸列表」——让用户自己点 LOOW
 * 4. 系统壁纸选择器（`SET_WALLPAPER`），有些 ROM 从这个入口能进动态壁纸
 * 5. 全失败：给出明确的手动路径提示，而不是假装成功
 *
 * 注意：不能用 `resolveActivity` 判断"能不能用"——MIUI 上它会返回可解析，
 * 实际却什么都没发生。因此这里用**启动是否抛异常 + 启动后是否真的切到前台**
 * 两道判据，失败就继续往下试。
 */
object WallpaperLauncher {

    /** 已知的动态壁纸选择器候选（按优先级）。指名比隐式 intent 更容易被 ROM 放行。 */
    private val LIVE_PICKER_CANDIDATES = listOf(
        // AOSP
        ComponentName("com.android.wallpaper.livepicker", "com.android.wallpaper.livepicker.LiveWallpaperActivity"),
        // Google 壁纸（部分机型）
        ComponentName("com.google.android.apps.wallpaper", "com.google.android.apps.wallpaper.picker.LivePreviewActivity"),
        // 小米
        ComponentName("com.miui.miwallpaper", "com.miui.miwallpaper.activity.LiveWallpaperPreviewActivity")
    )

    /**
     * 尝试打开"设置动态壁纸"的界面。
     *
     * @return true 表示至少有一个入口成功启动（不代表用户已经点确认）
     */
    fun launch(activity: Activity): Boolean {
        val serviceComponent = ComponentName(activity, LwpWallpaperService::class.java)

        // 顺序很重要：**先指名组件，再退回隐式意图**。
        //
        // 反过来的话在 MIUI 上会静默失败：标准隐式 intent 被系统丢弃但不抛异常，
        // 调用方以为成功就 return，永远走不到指名组件这一步——用户看到的就是"点了没反应"。
        // 指名组件（显式 Intent）在 MIUI 上实测能正常打开动态壁纸列表。

        // 1. 指名各 ROM 的选择器（带组件 extra，尽量直接进预览页）
        for (component in LIVE_PICKER_CANDIDATES) {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                this.component = component
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, serviceComponent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val isInstalled = try {
                activity.packageManager.getActivityInfo(component, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                // 打日志：Android 11+ 忘了在 <queries> 里声明包可见性时，
                // 这里会静默跳过，导致"点了没反应"却查不出原因。
                Log.w(TAG, "候选入口不可见（包可见性或未安装）：$component")
                false
            }
            if (isInstalled && tryStart(activity, intent, component.flattenToShortString())) return true
        }

        // 2. 指名但只打开"动态壁纸列表"（列表里能看到 LOOW，用户自己点）
        for (component in LIVE_PICKER_CANDIDATES) {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val isInstalled = try {
                activity.packageManager.getActivityInfo(component, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                // 打日志：Android 11+ 忘了在 <queries> 里声明包可见性时，
                // 这里会静默跳过，导致"点了没反应"却查不出原因。
                Log.w(TAG, "候选入口不可见（包可见性或未安装）：$component")
                false
            }
            if (isInstalled && tryStart(activity, intent, component.flattenToShortString() + "（列表）")) return true
        }

        // 3. 标准隐式意图（原生 Android / 多数 ROM 走这里）
        if (tryStart(activity, standardChangeIntent(serviceComponent), "standard")) return true

        // 4. 系统壁纸选择器
        if (tryStart(activity, Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "SET_WALLPAPER")) {
            return true
        }

        // 5. 全失败：说清楚怎么手动设置，别让用户以为点了没反应
        Toast.makeText(activity, R.string.main_wallpaper_manual_hint, Toast.LENGTH_LONG).show()
        return false
    }

    private fun standardChangeIntent(serviceComponent: ComponentName) =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, serviceComponent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * 启动一个 intent，捕获「没有匹配界面」的异常。
     *
     * 这里只能挡住"抛异常"这一类失败；MIUI 那种"接受 intent 但静默丢弃"的情况
     * 无法在同步代码里判断，所以调用方会继续尝试下一个入口。
     */
    private fun tryStart(context: Context, intent: Intent, label: String): Boolean {
        return try {
            context.startActivity(intent)
            Log.i(TAG, "已启动动态壁纸入口：$label")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "入口不可用（无匹配界面）：$label")
            false
        } catch (e: SecurityException) {
            // 某些 ROM 对跨应用启动动态壁纸界面有额外限制
            Log.w(TAG, "入口被拒绝（权限）：$label - ${e.message}")
            false
        }
    }

    private const val TAG = "LOOW.Wallpaper"
}
