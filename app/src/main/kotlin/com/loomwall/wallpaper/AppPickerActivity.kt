package com.loomwall.wallpaper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.util.Locale

/**
 * 软件容器 → 选择应用。
 *
 * 入口有两个：
 * - 点未绑定的容器（editMode=false）：直接选一个应用
 * - 长按已绑定的容器（editMode=true）：先问「切换软件 / 取消绑定」，选切换再进列表
 *
 * 为什么用 Activity 而不是在壁纸里弹窗：动态壁纸是后台进程，
 * 弹 Dialog 需要 Activity 上下文；这里用透明背景的对话框主题，视觉上仍然像"浮在桌面上的一个框"。
 */
class AppPickerActivity : Activity() {

    companion object {
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_EDIT_MODE = "edit_mode"
    }

    private class AppEntry(
        val pkg: String,
        val label: String,
        val icon: Drawable?
    )

    private lateinit var nodeId: String
    private var editMode = false
    private var all = ArrayList<AppEntry>()
    private var shown = ArrayList<AppEntry>()
    private var selectedPkg: String? = null
    private lateinit var listView: ListView
    private lateinit var listAdapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nodeId = intent?.getStringExtra(EXTRA_NODE_ID) ?: run { finish(); return }
        editMode = intent?.getBooleanExtra(EXTRA_EDIT_MODE, false) ?: false

        // 当前绑定（预选 + 判断要不要显示"取消绑定"）
        val current = runCatching { AppSlotBinder.currentBinding(this, nodeId) }.getOrNull()
        val boundPkg = current?.optString("appPkg").orEmpty()
        if (boundPkg.isNotBlank()) selectedPkg = boundPkg

        if (editMode && boundPkg.isNotBlank()) {
            AlertDialog.Builder(this)
                .setTitle("软件容器")
                .setItems(arrayOf("切换软件", "取消绑定")) { _, which ->
                    if (which == 0) buildUi() else {
                        doUnbind()
                    }
                }
                .setNegativeButton("取消") { _, _ -> goHomeAndFinish() }
                .setOnCancelListener { goHomeAndFinish() }
                .show()
            return
        }

        buildUi()
    }

    // ---------------------------------------------------------------- 界面

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 不透明：避免透过它看到壁纸/上一个 Activity，造成"跳页"的错觉
            setBackgroundColor(Color.parseColor("#FF14161C"))
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "选择应用"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "点一下选中，再点「保存」；长按容器可以取消绑定"
            textSize = 12f
            setTextColor(Color.parseColor("#8B8FA3"))
            setPadding(0, dp(4), 0, dp(10))
        })

        val search = EditText(this).apply {
            hint = "搜索应用"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#5E6272"))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { filter(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        loadApps()
        shown = ArrayList(all)
        listAdapter = Adapter()
        listView = ListView(this).apply {
            adapter = this@AppPickerActivity.listAdapter
            setOnItemClickListener { _, _, position, _ ->
                selectedPkg = shown[position].pkg
                listAdapter.notifyDataSetChanged()
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { goHomeAndFinish() }
        })
        if (!selectedPkg.isNullOrBlank()) {
            buttons.addView(Button(this).apply {
                text = "取消绑定"
                setOnClickListener { doUnbind() }
            })
        }
        buttons.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { doSave() }
        })
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun filter(text: String) {
        val key = text.trim()
        shown = if (key.isEmpty()) {
            ArrayList(all)
        } else {
            val lower = key.lowercase()
            ArrayList(all.filter { it.label.lowercase().contains(lower) || it.pkg.contains(lower) })
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val collator = Collator.getInstance(Locale.CHINA)
        val seen = HashSet<String>()
        val list = ArrayList<AppEntry>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            list.add(AppEntry(pkg, label, icon))
        }
        list.sortWith { a, b -> collator.compare(a.label, b.label) }
        all = list

        // 已绑定的应用如果没在启动器列表里（比如被隐藏），补一条，避免"看起来没选"
        val bound = selectedPkg
        if (!bound.isNullOrBlank() && list.none { it.pkg == bound }) {
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(bound, 0)).toString()
            }.getOrDefault(bound)
            val icon = runCatching { pm.getApplicationIcon(bound) }.getOrNull()
            list.add(0, AppEntry(bound, label, icon))
        }
    }

    // ---------------------------------------------------------------- 动作

    private fun doSave() {
        val pkg = selectedPkg
        if (pkg.isNullOrBlank()) {
            Toast.makeText(this, "先选一个应用", Toast.LENGTH_SHORT).show()
            return
        }
        val entry = all.firstOrNull { it.pkg == pkg }
        val label = entry?.label ?: pkg
        val bitmap = AppSlotBinder.bitmapOf(entry?.icon, 144)
        val result = AppSlotBinder.bind(this, nodeId, pkg, label, bitmap)
        bitmap?.recycle()
        Toast.makeText(this, if (result.ok) "已绑定：$label" else result.message, Toast.LENGTH_SHORT).show()
        goHomeAndFinish()
    }

    /**
     * 收尾：回桌面再移除本任务。
     *
     * 为什么必须显式回桌面：选择器是独立任务，finish 后系统会**露出它下面那个任务**。
     * 如果用户刚刚用过 LOOW（主界面还压在下面），就会"跳回 LOOW"——
     * 这正是用户反馈的"点添加就跳渲染质检/反复跳回 LOOW"。
     */
    private fun goHomeAndFinish() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finishAndRemoveTask()
    }

    private fun doUnbind() {
        val result = AppSlotBinder.unbind(this, nodeId)
        Toast.makeText(this, if (result.ok) "已取消绑定" else result.message, Toast.LENGTH_SHORT).show()
        goHomeAndFinish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 列表

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
            }
            val entry = shown[position]
            row.addView(ImageView(this@AppPickerActivity).apply {
                setImageDrawable(entry.icon)
            }, LinearLayout.LayoutParams(dp(36), dp(36)))

            val texts = LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            texts.addView(TextView(this@AppPickerActivity).apply {
                text = entry.label
                textSize = 15f
                setTextColor(Color.WHITE)
            })
            texts.addView(TextView(this@AppPickerActivity).apply {
                text = entry.pkg
                textSize = 10f
                setTextColor(Color.parseColor("#6E7488"))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (entry.pkg == selectedPkg) {
                row.setBackgroundColor(Color.parseColor("#2A3A5C"))
                row.addView(TextView(this@AppPickerActivity).apply {
                    text = "✓"
                    textSize = 18f
                    setTextColor(Color.parseColor("#7EE2A8"))
                    setPadding(dp(8), 0, dp(4), 0)
                })
            }
            return row
        }
    }
}
