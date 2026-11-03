package com.loomwall.wallpaper

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.loomwall.wallpaper.scene.PresetLibrary
import java.util.concurrent.Executors

/**
 * 主页：壁纸库。
 *
 * ```
 * ┌──────────────────────────┐
 * │ ☰  LOOW                  │  ← 顶栏
 * ├──────────────────────────┤
 * │ ┌────────┐ ┌────────┐    │
 * │ │ 卡片 1 │ │ 卡片 2 │    │  ← 两列卡片，点一下设为壁纸
 * │ └────────┘ └────────┘    │
 * └──────────────────────────┘
 * ```
 *
 * 左上角 ☰ 拉出左侧抽屉：获取权限 / 设为动态壁纸 / 导入壁纸 / 关于。
 *
 * 为什么不用 RecyclerView、DrawerLayout：项目坚持零第三方依赖（连 AndroidX 都不引），
 * 抽屉是"自己位移的 LinearLayout"，卡片是"自己算两列"的线性布局。预设数量是个位数，
 * 不需要回收复用。
 *
 * 手机端不提供编辑：小屏幕上盖一层编辑器体验很差，编辑统一走电脑浏览器
 * （tools/web-editor 或独立 exe），设计完点「发送到手机」即可。
 */
class MainActivity : Activity() {

    private lateinit var library: PresetLibrary
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var rootView: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var grid: LinearLayout
    private lateinit var addressView: TextView
    /** 顶栏要按状态栏高度加内边距，所以留个引用 */
    private lateinit var topBar: View
    private var drawerWidth = 0
    private var drawerOpen = false

    private var receiver: com.loomwall.wallpaper.net.PresetHttpServer? = null
    /** 导出 JSON 时记住是哪一套，等系统文件选择器回来再写 */
    private var pendingExportId: String? = null

    private val presetChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadCards()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = PresetLibrary(this)
        buildUi()
        startReceiver()
        requestStepPermission()
        // 调试入口：adb 禁用了注入点击，没法用 input tap 打开抽屉；
        // 传 extra 就能让抽屉启动时自动展开，方便截图核对（不影响正常使用）
        //   adb shell am start -n com.loomwall.wallpaper/.MainActivity --ez open_drawer true
        if (intent?.getBooleanExtra("open_drawer", false) == true) {
            main.postDelayed({ if (!drawerOpen) openDrawer() }, 260)
        }
    }

    override fun onResume() {
        super.onResume()
        reloadCards()
        // 每次回前台都核一次"壁纸是不是自己"，日志里能直接看出检测依据
        isOurWallpaperActive()
        runCatching {
            val filter = IntentFilter(PresetLibrary.ACTION_PRESET_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(presetChanged, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(presetChanged, filter)
            }
        }
    }

    override fun onPause() {
        runCatching { unregisterReceiver(presetChanged) }
        super.onPause()
    }

    override fun onDestroy() {
        receiver?.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        rootView = FrameLayout(this)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBar = buildTopBar()
        content.addView(topBar)
        // 卡片区必须占满剩余高度，否则底部会露出系统窗口底色（白块）
        content.addView(buildGallery(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        rootView.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        rootView.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        drawer = buildDrawer()
        drawerWidth = dp(280)
        rootView.addView(
            drawer,
            FrameLayout.LayoutParams(drawerWidth, MATCH).apply { gravity = Gravity.START }
        )
        drawer.translationX = -drawerWidth.toFloat()

        setContentView(rootView)
        applySystemBarInsets()
    }

    /**
     * 处理状态栏遮住顶部内容的问题。
     *
     * targetSdk 35 会触发 edge-to-edge：窗口铺满整屏，内容画到状态栏下面，
     * 于是「☰ / LOOW」和状态栏的文字、图标叠在一起，点也点不到（用户实测）。
     * 这里手动把状态栏高度补成顶栏的上内边距，抽屉同步下移。
     */
    private fun applySystemBarInsets() {
        window.statusBarColor = Color.parseColor("#0E0F14")
        window.navigationBarColor = Color.parseColor("#0E0F14")

        rootView.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            if (topBar.paddingTop != dp(10) + top) {
                topBar.setPadding(dp(8), dp(10) + top, dp(16), dp(10))
            }
            if (drawer.paddingTop != dp(28) + top) {
                drawer.setPadding(0, dp(28) + top, 0, 0)
            }
            insets
        }
        rootView.requestApplyInsets()
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#0E0F14"))
        }
        val menu = TextView(this).apply {
            text = "☰"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { toggleDrawer() }
        }
        val title = TextView(this).apply {
            text = "LOOW"
            textSize = 20f
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, 0, 0)
        }
        val subtitle = TextView(this).apply {
            text = "壁纸库"
            textSize = 12f
            setTextColor(Color.parseColor("#8B8FA3"))
            setPadding(dp(8), dp(4), 0, 0)
        }
        bar.addView(menu)
        bar.addView(title)
        bar.addView(subtitle)
        return bar
    }

    private fun buildGallery(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0E0F14")) }
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(24))
        }
        scroll.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
        return scroll
    }

    private fun buildDrawer(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#15161D"))
            setPadding(0, dp(28), 0, 0)
        }
        panel.addView(TextView(this).apply {
            text = "LOOW"
            textSize = 22f
            letterSpacing = 0.1f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(24), dp(8), dp(24), dp(2))
        })
        panel.addView(TextView(this).apply {
            text = "开源动态壁纸"
            textSize = 12f
            setTextColor(Color.parseColor("#8B8FA3"))
            setPadding(dp(24), 0, dp(24), dp(18))
        })

        panel.addView(drawerItem("获取权限") { closeDrawer(); showPermissionDialog() })
        panel.addView(drawerItem("设为动态壁纸") {
            closeDrawer()
            if (!WallpaperLauncher.launch(this)) {
                toast("系统没有可用的动态壁纸入口，请手动到 设置 → 壁纸")
            }
        })
        panel.addView(drawerItem("导入壁纸") { closeDrawer(); pickPresetFile() })
        panel.addView(drawerItem("天气城市") { closeDrawer(); showWeatherCityDialog() })
        panel.addView(drawerItem("关于") { closeDrawer(); showAbout() })

        // 电脑端「发送到手机」要填这个地址，放抽屉里随时能看到（之前完全没提示，用户只能猜）
        panel.addView(View(this), LinearLayout.LayoutParams(MATCH, 0).apply { weight = 1f })
        addressView = TextView(this).apply {
            text = "正在启动接收服务…"
            textSize = 11f
            setTextColor(Color.parseColor("#8B8FA3"))
            setPadding(dp(24), dp(8), dp(24), dp(4))
            setLineSpacing(0f, 1.25f)
        }
        panel.addView(addressView)
        panel.addView(TextView(this).apply {
            text = "同一 Wi-Fi 下，电脑编辑器里填这个地址"
            textSize = 10f
            setTextColor(Color.parseColor("#5E6272"))
            setPadding(dp(24), 0, dp(24), dp(24))
        })

        panel.addView(TextView(this).apply {
            // 版本号从安装包读，别写死：改一次 build.gradle 就得手动改这里，迟早对不上
            text = "版本 ${appVersionName()} · 场景格式 ${com.loomwall.wallpaper.scene.EngineVersion.SCENE_FORMAT}"
            textSize = 11f
            setTextColor(Color.parseColor("#5E6272"))
            setPadding(dp(24), dp(20), dp(24), 0)
        })
        return panel
    }

    private fun drawerItem(label: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.parseColor("#E6E7EE"))
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener { onClick() }
        }

    // ---------------------------------------------------------------- 抽屉动画

    private fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    private fun openDrawer() {
        drawerOpen = true
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        drawer.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer.animate().translationX(-drawerWidth.toFloat()).setDuration(180).start()
        scrim.animate().alpha(0f).setDuration(180).withEndAction {
            scrim.visibility = View.GONE
        }.start()
    }

    // ---------------------------------------------------------------- 壁纸卡片

    private fun reloadCards() {
        io.execute {
            // 首次启动（或清数据后）：active 目录还没有内容，壁纸会是黑的。
            // 自动把内置示例铺上去，保证桌面立刻有东西可看。
            val activeDir = java.io.File(filesDir, "presets/active")
            val needsSeed = !activeDir.exists() ||
                activeDir.listFiles()?.none { it.name == "scene.json" } != false
            if (needsSeed) {
                runCatching { library.activate(PresetLibrary.BUILT_IN_ID) }
            }
            val entries = runCatching { library.list() }.getOrDefault(emptyList())
            // active.id 是"当前用的是哪套"的标记。老版本升级上来时这个文件不存在，
            // 但 active 目录已经有内容（壁纸服务自己铺过内置预设），此时按第一张卡片标记，
            // 否则界面上永远没有"使用中"角标。
            val activeId = library.activeId() ?: entries.firstOrNull()?.id
            main.post { renderCards(entries, activeId) }
        }
    }

    private fun renderCards(entries: List<PresetLibrary.Entry>, activeId: String?) {
        grid.removeAllViews()
        if (entries.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "还没有壁纸。点左上角 ☰ → 导入壁纸。"
                setTextColor(Color.parseColor("#8B8FA3"))
                setPadding(dp(8), dp(24), dp(8), 0)
            })
            return
        }
        var index = 0
        while (index < entries.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            for (column in 0 until 2) {
                if (index >= entries.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    break
                }
                val entry = entries[index++]
                row.addView(buildCard(entry, entry.id == activeId), LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // 底部提示：告诉用户壁纸从哪来（手机端不提供编辑）
        grid.addView(TextView(this).apply {
            text = "在电脑浏览器里设计壁纸，点「发送到手机」后会自动出现在这里。"
            textSize = 11f
            setTextColor(Color.parseColor("#5E6272"))
            setPadding(dp(6), dp(14), dp(6), 0)
        })
    }

    private fun buildCard(entry: PresetLibrary.Entry, active: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1B1C24"))
                if (active) setStroke(dp(2), Color.parseColor("#7AA2F7"))
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }

        val cover = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                colors = intArrayOf(colorOf(entry.name), darkerOf(entry.name))
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }
        val coverFile = entry.cover
        if (coverFile != null && coverFile.exists()) {
            cover.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(decodeSampled(coverFile.absolutePath, dp(360), dp(260)))
                },
                FrameLayout.LayoutParams(MATCH, MATCH)
            )
        } else {
            cover.addView(TextView(this).apply {
                text = entry.name.take(1)
                textSize = 40f
                setTextColor(Color.parseColor("#B9C0D8"))
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        if (active) {
            cover.addView(TextView(this).apply {
                text = "使用中"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(Color.parseColor("#7AA2F7"))
                }
            }, FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(8), dp(8), 0)
            })
        }
        card.addView(cover, LinearLayout.LayoutParams(MATCH, dp(190)))

        card.addView(TextView(this).apply {
            text = entry.name
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(8), dp(4), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(TextView(this).apply {
            text = entry.summary()
            textSize = 11f
            setTextColor(Color.parseColor("#8B8FA3"))
            setPadding(dp(4), dp(2), dp(4), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        card.setOnClickListener { applyEntry(entry) }
        card.setOnLongClickListener {
            if (entry.fromBuiltIn) toast("内置预设不能删除") else confirmDelete(entry)
            true
        }
        return card
    }

    private fun applyEntry(entry: PresetLibrary.Entry) {
        toast("正在切换「${entry.name}」…")
        io.execute {
            val result = library.activate(entry.id)
            if (result.ok) {
                // 让壁纸进程立刻重读 active 目录；不发这条广播就得退出重进才生效
                PresetLibrary.notifyChanged(applicationContext)
            }
            main.post {
                // 切换只负责"换预设"，绝不主动跳系统界面。
                // 原因：MIUI 上 WallpaperManager.wallpaperInfo 会返回 null，判断不可靠，
                // 一跳就是每次换壁纸都弹系统列表（用户实测，非常烦）。
                // 所以统一给一句话指路，用户需要时才自己去抽屉点「设为动态壁纸」。
                if (result.ok) {
                    toast("已切换到「${entry.name}」。若桌面没变化，点左上角 ☰ → 设为动态壁纸")
                } else {
                    toast(result.message)
                }
            }
        }
    }

    /**
     * 当前桌面用的壁纸是不是本应用。
     *
     * 之前用 `serviceName == "$packageName/$packageName.LwpWallpaperService"` 比较，
     * 但 WallpaperInfo.getServiceName() 返回的是**短格式**（`包名/.LwpWallpaperService`），
     * 字符串永远对不上 → 每次换壁纸都误判成"还没设置"，弹框反复出现。
     * 直接比包名最稳。
     */
    private fun isOurWallpaperActive(): Boolean = runCatching {
        val info = try {
            android.app.WallpaperManager.getInstance(this).wallpaperInfo
        } catch (error: Throwable) {
            android.util.Log.w("LOOW.Main", "读取 wallpaperInfo 失败：${error.message}")
            null
        }
        val byInfo = info != null && info.packageName == packageName
        // MIUI 上 wallpaperInfo 可能是 null；壁纸引擎被系统创建过就说明它是当前壁纸
        val byService = getSharedPreferences("loow", MODE_PRIVATE).getBoolean("wallpaper_applied", false)
        android.util.Log.i(
            "LOOW.Main",
            "壁纸检测：info=${info?.packageName ?: "null"} byInfo=$byInfo byService=$byService"
        )
        byInfo || byService
    }.getOrDefault(false)

    /** 已提示过"首次设为壁纸"就不再反复弹（用户明确反馈太烦）。 */
    private fun shouldPromptWallpaperSetup(): Boolean {
        if (isOurWallpaperActive()) return false
        val prefs = getSharedPreferences("loow", MODE_PRIVATE)
        return !prefs.getBoolean("wallpaper_prompt_dismissed", false)
    }

    private fun markWallpaperPromptDismissed() {
        getSharedPreferences("loow", MODE_PRIVATE).edit()
            .putBoolean("wallpaper_prompt_dismissed", true).apply()
    }

    private fun confirmDelete(entry: PresetLibrary.Entry) {
        // 长按菜单：导出 JSON（可再导回编辑器继续改）/ 删除
        val actions = if (entry.fromBuiltIn) arrayOf("导出 JSON") else arrayOf("导出 JSON", "删除")
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "导出 JSON" -> exportEntry(entry)
                    "删除" -> deleteEntry(entry)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteEntry(entry: PresetLibrary.Entry) {
        AlertDialog.Builder(this)
            .setTitle("删除壁纸")
            .setMessage("确定删除「${entry.name}」？")
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    val result = library.delete(entry.id)
                    if (result.ok) PresetLibrary.notifyChanged(applicationContext)
                    main.post {
                        toast(result.message)
                        reloadCards()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 导出单文件 JSON：系统文件选择器决定存到哪（下载目录 / 网盘等）。 */
    private fun exportEntry(entry: PresetLibrary.Entry) {
        pendingExportId = entry.id
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, entry.name + ".lwpkit.json")
        }
        runCatching { startActivityForResult(intent, REQUEST_EXPORT) }
            .onFailure {
                pendingExportId = null
                toast("没有可用的文件保存器")
            }
    }

    private fun writeExport(uri: android.net.Uri) {
        val id = pendingExportId ?: return
        pendingExportId = null
        io.execute {
            val json = runCatching { library.exportJson(id) }.getOrNull()
            val ok = if (json == null) false else runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                } != null
            }.getOrDefault(false)
            main.post {
                toast(if (ok) "已导出 JSON" else "导出失败")
            }
        }
    }

    // ---------------------------------------------------------------- 侧边栏动作

    private fun showPermissionDialog() {
        val canNotify = hasNotificationAccess()
        val canAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val canLocation = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val items = arrayOf(
            (if (canNotify) "✓ " else "○ ") + "通知使用权（通知数 / 歌曲名 / 歌词匹配）",
            (if (canAudio) "✓ " else "○ ") + "录音权限（音频可视化，只需一次）",
            (if (canLocation) "✓ " else "○ ") + "定位权限（天气；不授权也能用「天气城市」手动指定）",
            "○ 自启动（MIUI 必开，否则壁纸会被杀）",
            "○ 省电策略设为「无限制」",
            "○ 身体活动（今日步数）"
        )
        AlertDialog.Builder(this)
            .setTitle("获取权限")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runCatching {
                        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    }.onFailure { toast("无法打开通知使用权设置") }
                    1 -> requestPermissions(
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        REQUEST_AUDIO
                    )
                    2 -> requestPermissions(
                        arrayOf(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        ),
                        REQUEST_LOCATION
                    )
                    3 -> DeviceSettingsShortcuts.openAutostart(this)
                    4 -> DeviceSettingsShortcuts.openPowerWhitelist(this)
                    5 -> requestStepPermission()
                }
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    /**
     * 设置天气城市。
     *
     * 为什么需要手动填：MIUI 上定位权限经常被"仅本次允许"或系统层拦截，
     * 而天气是纯装饰信息，用户能直接说"我在杭州"就没有必要卡在权限上。
     * 留空 = 跟随定位。
     */
    private fun showWeatherCityDialog() {
        val prefs = getSharedPreferences("loow", MODE_PRIVATE)
        val input = android.widget.EditText(this).apply {
            setText(prefs.getString("weather_city", ""))
            hint = "例如：杭州（留空 = 跟随定位）"
            setSingleLine(true)
        }
        val pad = resources.displayMetrics.density * 20
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad.toInt(), pad.toInt() / 2, pad.toInt(), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("天气城市")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val city = input.text.toString().trim()
                com.loomwall.wallpaper.net.WeatherSource.setCity(city)
                com.loomwall.wallpaper.net.WeatherSource.refreshNow()
                toast(if (city.isBlank()) "已改为跟随定位" else "已设为 $city，稍后生效")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("LOOW")
            .setMessage(
                "开源 Android 动态壁纸引擎与编辑器。\n\n" +
                    "手机端只负责浏览与切换壁纸；\n" +
                    "编辑请在电脑浏览器里进行（tools/web-editor 或 LOOW-Editor.exe），\n" +
                    "设计好后点「发送到手机」，壁纸会自动出现在这里。\n\n" +
                    "版本 ${appVersionName()} · 场景格式 ${com.loomwall.wallpaper.scene.EngineVersion.SCENE_FORMAT}\n" +
                    "© 2026 KK-486 · Apache License 2.0"
            )
            .setPositiveButton("好", null)
            .show()
    }

    /** 安装包的真实版本号（versionName），读不到时返回 "?"。 */
    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun pickPresetFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_IMPORT) }
            .onFailure { toast("没有可用的文件选择器") }
    }

    @Deprecated("系统原生 API，项目没有 AndroidX")
    /**
     * 录音权限结果：授权成功就立刻让音频源重试一次。
     *
     * 为什么需要这一步：壁纸进程可能早就在跑，且已经因为没权限失败过一次；
     * 不主动重试的话，要等壁纸下次「不可见→可见」才会重建采集链路，用户会以为没生效。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                com.loomwall.wallpaper.net.WeatherSource.refreshNow()
                toast("已授权定位，稍后更新天气")
            } else {
                toast("没给定位权限，可以在「天气城市」里手动填写")
            }
            return
        }
        if (requestCode != REQUEST_AUDIO) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            com.loomwall.wallpaper.engine.AudioLevelSource.retry()
            toast("录音权限已开启，音频可视化会在壁纸可见时生效")
        } else {
            toast("未授权：音频可视化不会工作（其他功能不受影响）")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            REQUEST_EXPORT -> {
                if (resultCode == RESULT_OK && uri != null) writeExport(uri) else pendingExportId = null
            }
            REQUEST_IMPORT -> {
                if (resultCode != RESULT_OK || uri == null) return
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    toast("读取失败：无法打开这个文件")
                    return
                }
                io.execute {
                    val result = library.import(text)
                    if (result.ok) PresetLibrary.notifyChanged(applicationContext)
                    main.post {
                        toast(result.message)
                        reloadCards()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 接收服务 / 权限

    private fun startReceiver() {
        val address = lanAddress()
        runCatching {
            receiver = com.loomwall.wallpaper.net.PresetHttpServer(
                context = applicationContext,
                // 回调在 HTTP 工作线程上执行，弹 Toast 必须回到主线程，
                // 否则会抛 "Can't toast on a thread that has not called Looper.prepare()"，
                // 连响应都发不出去（表现为浏览器端 6 MB 传到一半就断）
                onImported = { ok, message -> if (ok) main.post { toast(message) } }
            ).also { it.start() }
        }.onFailure {
            toast("接收服务启动失败：${it.message}")
        }
        val port = com.loomwall.wallpaper.net.PresetHttpServer.DEFAULT_PORT
        val text = if (address != null) {
            "电脑连接地址\nhttp://$address:$port"
        } else {
            "没有检测到局域网地址\n请先连接 Wi-Fi"
        }
        addressView.text = text
        android.util.Log.i("LOOW.Main", "接收地址：$address:$port")
    }

    /** 取本机局域网 IPv4（Wallpaper 与编辑器在同一 Wi-Fi 下才连得通）。 */
    private fun lanAddress(): String? = runCatching {
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun requestStepPermission() {
        if (Build.VERSION.SDK_INT < 29) return
        val granted = checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), REQUEST_STEPS)
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { it.contains(packageName) }
    }

    // ---------------------------------------------------------------- 工具

    private fun decodeSampled(path: String, reqW: Int, reqH: Int): android.graphics.Bitmap? =
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > reqW * 2 && bounds.outHeight / sample > reqH * 2) sample *= 2
            android.graphics.BitmapFactory.decodeFile(
                path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()

    private fun colorOf(seed: String): Int {
        val palette = intArrayOf(
            0xFF2B3A67.toInt(), 0xFF3D2C5B.toInt(), 0xFF1F4E5F.toInt(),
            0xFF4A2C3A.toInt(), 0xFF2C4A3B.toInt(), 0xFF453A22.toInt()
        )
        return palette[(seed.hashCode() and 0x7FFFFFFF) % palette.size]
    }

    private fun darkerOf(seed: String): Int {
        val c = colorOf(seed)
        val r = ((c shr 16 and 0xFF) * 0.55f).toInt()
        val g = ((c shr 8 and 0xFF) * 0.55f).toInt()
        val b = ((c and 0xFF) * 0.55f).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_IMPORT = 1001
        private const val REQUEST_STEPS = 1002
        private const val REQUEST_EXPORT = 1003
    private const val REQUEST_AUDIO = 1004
    private const val REQUEST_LOCATION = 1005
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
