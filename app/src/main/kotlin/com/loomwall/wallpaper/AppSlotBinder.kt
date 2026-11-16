package com.loomwall.wallpaper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 把「软件容器」和一个应用绑定/解绑，并写回预设。
 *
 * 为什么要同时写两份：预设库（library/<id>/）是数据源，壁纸读的是 active/ 的副本。
 * 只改 active 的话，下次切换预设或重新套用就会丢绑定。
 *
 * 图标以 PNG 形式落进 resources/：渲染时只读文件，不再查 PackageManager，
 * 省电，也避免手机上"每次画一帧都做一次 binder 调用"。
 */
internal object AppSlotBinder {

    private const val TAG = "LOOW.AppSlot"
    private const val RELOAD_ACTION = "com.loomwall.wallpaper.action.RELOAD"

    /** 绑定结果：成功 / 失败原因。 */
    data class Result(val ok: Boolean, val message: String)

    /** 读当前 active 预设里某个容器的绑定信息；找不到返回 null。 */
    fun currentBinding(context: Context, nodeId: String): JSONObject? {
        val sceneFile = File(activeDir(context), "scene.json")
        if (!sceneFile.isFile) return null
        return runCatching {
            val doc = JSONObject(sceneFile.readText())
            findNode(doc.optJSONObject("root") ?: doc, nodeId)
        }.getOrNull()
    }

    /**
     * 绑定应用。
     *
     * @param icon 已缩小到合适尺寸的图标位图（调用方负责回收）
     */
    fun bind(context: Context, nodeId: String, pkg: String, label: String, icon: Bitmap?): Result {
        val iconName = "app_" + pkg.replace('.', '_').replace('-', '_') + ".png"
        return write(context, nodeId) { node ->
            node.put("appPkg", pkg)
            node.put("appLabel", label)
            node.put("appIcon", iconName)
        }.also { result ->
            if (result.ok && icon != null) {
                writeIcon(context, iconName, icon)
            }
        }
    }

    /** 取消绑定：清掉包名/名字/图标字段，容器回到"虚线框 + 加号"。 */
    fun unbind(context: Context, nodeId: String): Result = write(context, nodeId) { node ->
        node.remove("appPkg")
        node.remove("appLabel")
        node.remove("appIcon")
    }

    // ---------------------------------------------------------------- 内部

    private fun activeDir(context: Context) = File(context.filesDir, "presets/active")

    private fun libraryDir(context: Context): File? {
        val id = File(context.filesDir, "presets/active.id").takeIf { it.isFile }?.readText()?.trim()
        if (id.isNullOrBlank()) return null
        val dir = File(context.filesDir, "presets/library/$id")
        return if (dir.isDirectory) dir else null
    }

    /** 对两处 scene.json 各改一次；只有全部成功才广播重载。 */
    private fun write(context: Context, nodeId: String, mutate: (JSONObject) -> Unit): Result {
        val targets = ArrayList<File>()
        targets.add(File(activeDir(context), "scene.json"))
        libraryDir(context)?.let { targets.add(File(it, "scene.json")) }

        var changed = 0
        for (file in targets) {
            if (!file.isFile) continue
            runCatching {
                val doc = JSONObject(file.readText())
                // scene.json 形如 { formatVersion, root: {...} }：节点在 root 下面
                val root = doc.optJSONObject("root") ?: doc
                val node = findNode(root, nodeId)
                    ?: throw IllegalStateException("预设里找不到节点 $nodeId")
                if (node.optString("shape") != "appslot") {
                    throw IllegalStateException("$nodeId 不是软件容器（shape=${node.optString("shape")}）")
                }
                mutate(node)
                // 写回外层文档：只写 root 会把 formatVersion 丢掉，预设随即加载失败
                file.writeText(doc.toString())
                changed++
            }.onFailure { Log.w(TAG, "写回失败 ${file.absolutePath}：${it.message}") }
        }

        if (changed == 0) return Result(false, "没写进任何预设文件")

        // 资源目录也保持两边一致
        runCatching {
            libraryDir(context)?.let { lib ->
                val src = File(activeDir(context), "resources")
                if (src.isDirectory) {
                    src.copyRecursively(File(lib, "resources"), overwrite = true)
                }
            }
        }

        context.sendBroadcast(
            Intent(RELOAD_ACTION).setPackage(context.packageName)
        )
        return Result(true, "已保存")
    }

    private fun writeIcon(context: Context, name: String, bitmap: Bitmap) {
        val dirs = ArrayList<File>()
        dirs.add(File(activeDir(context), "resources"))
        libraryDir(context)?.let { dirs.add(File(it, "resources")) }
        for (dir in dirs) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, name)).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }.onFailure { Log.w(TAG, "写图标失败 ${dir.absolutePath}：${it.message}") }
        }
    }

    /** 递归找 id 命中的节点（scene 里节点是嵌套的）。 */
    private fun findNode(parent: JSONObject, id: String): JSONObject? {
        if (parent.optString("id") == id) return parent
        val children: JSONArray = parent.optJSONArray("children") ?: return null
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            val found = findNode(child, id)
            if (found != null) return found
        }
        return null
    }

    /** Drawable → 方图，供列表与写盘共用。 */
    fun bitmapOf(drawable: Drawable?, size: Int): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            return Bitmap.createScaledBitmap(src, size, size, true)
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return out
    }
}
