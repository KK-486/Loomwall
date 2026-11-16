package com.loomwall.wallpaper.engine

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 变量持久化。
 *
 * 为什么需要：`setVar` 的变量值和 scene.json 里的节点属性一样，都是"用户操作的结果"。
 * 但变量在内存里，进程一重启就回到预设初值 —— 对"软件容器"这种把数量记在变量里的功能，
 * 重启就等于把用户展开的格子全部收回去（还留着图标，但看不见），体验上是坏的。
 *
 * 设计约束（对应功耗红线与渲染线程规则）：
 * - **不在渲染线程做文件 IO**：`save()` 只写内存缓存 + 置脏标记，落盘交给单线程后台执行器；
 *   连续点击会合并成一次写（写之前先看脏标记）。
 * - **失败只影响持久化**：读写异常一律吞掉并记日志，绝不能让壁纸崩掉或卡住。
 * - 文件很小（几十字节），写在 `presets/active/var-state.json`，随预设目录一起被替换。
 */
interface VarPersistence {
    /** 启动时读一次：变量名 → 值。没有文件或损坏时返回空表。 */
    fun load(): Map<String, Float>

    /** 变量变化时调用，实现方自己决定何时真正落盘。 */
    fun save(name: String, value: Float)
}

/**
 * 文件实现：`<预设目录>/var-state.json`。
 *
 * @param dir 预设目录（`filesDir/presets/active`）；目录不存在时静默禁用持久化。
 */
class VarFileStore(
    private val dir: File,
    fileName: String = "var-state.json"
) : VarPersistence {

    private val file = File(dir, fileName)
    private val lock = Any()
    private val cache = LinkedHashMap<String, Float>()
    private var dirty = false

    /** 单线程执行器：所有文件写入都在这里串行发生，渲染线程只碰内存。 */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LOOW.VarStore").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    override fun load(): Map<String, Float> = synchronized(lock) {
        // 关键：重载场景时会再调一次 load()。如果这时还有没落盘的改动，
        // 直接 cache.clear() 就等于把用户刚点的值丢掉（表现为"重载后回到 0"）。
        // 所以先把脏数据刷到文件，再按文件重建缓存。
        runCatching { flushIfDirty() }
        cache.clear()
        if (!file.isFile) return emptyMap()
        runCatching {
            val obj = JSONObject(file.readText())
            for (key in obj.keys()) {
                cache[key] = obj.optDouble(key, 0.0).toFloat()
            }
        }.onFailure { Log.w(TAG, "变量状态读取失败：${it.message}") }
        LinkedHashMap(cache)
    }

    override fun save(name: String, value: Float) {
        if (closed) return
        synchronized(lock) {
            if (cache[name] == value) return
            cache[name] = value
            dirty = true
        }
        // 合并写：连续几次点击只会排队一次实际落盘
        writer.execute { flushIfDirty() }
    }

    private fun flushIfDirty() {
        val snapshot: JSONObject
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            snapshot = JSONObject()
            for ((k, v) in cache) snapshot.put(k, v.toDouble())
        }
        runCatching {
            dir.mkdirs()
            // 先写临时文件再改名：系统在写一半时杀进程也不会留下坏 JSON
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(snapshot.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(snapshot.toString())
                tmp.delete()
            }
            Log.d(TAG, "变量状态已写入：" + snapshot.toString())
        }.onFailure { Log.w(TAG, "变量状态写入失败：${it.message}") }
    }

    /** 壁纸销毁时调用：把没写完的补上并停掉线程。 */
    fun close() {
        if (closed) return
        closed = true
        runCatching { flushIfDirty() }
        writer.shutdown()
    }

    private companion object {
        const val TAG = "LOOW.VarStore"
        const val FILE_NAME = "var-state.json"
    }
}
