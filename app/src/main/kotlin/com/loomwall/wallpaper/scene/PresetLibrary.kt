package com.loomwall.wallpaper.scene

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * 壁纸库：管理「多套预设」，每套一个目录，点一下就切换成当前壁纸。
 *
 * 目录约定：
 * ```
 * files/presets/
 *   active/          ← 壁纸服务只读这里（当前生效的那套）
 *   library/<id>/    ← 库里的每一套（scene / interactions / manifest / resources）
 * ```
 *
 * 为什么要有 library：以前导入是直接覆盖 active，用户换一套就丢一套。
 * 主页要像壁纸库那样多卡片浏览，就必须"先入库，再激活"。
 *
 * 线程：所有函数都可能做文件 IO，调用方不要放在主线程遍历大资源；
 * 列表只读 manifest 与目录，代价很小（单个 JSON 几 KB）。
 */
class PresetLibrary(private val context: Context) {

    /** 库里的一项。`dir` 为 null 表示内置预设（在 assets 里）。 */
    data class Entry(
        val id: String,
        val name: String,
        val author: String,
        val elements: Int,
        val fromBuiltIn: Boolean,
        val dir: File?,
        val updatedAt: Long,
        val cover: File?,
        val interactions: String
    ) {
        /** 卡片副标题：元素数 + 动画/触发器数量 */
        fun summary(): String {
            val parts = ArrayList<String>(3)
            if (elements > 0) parts.add("$elements 个元素")
            if (interactions.isNotBlank()) parts.add(interactions)
            return if (parts.isEmpty()) "预设" else parts.joinToString(" · ")
        }
    }

    data class Result(val ok: Boolean, val message: String, val entry: Entry? = null)

    private val libraryRoot: File get() = File(context.filesDir, "presets/library")

    /** 当前生效的预设 id（记录在一个小文件里，避免再引入 SharedPreferences 依赖）。 */
    fun activeId(): String? = runCatching {
        File(context.filesDir, "presets/active.id").takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
    }.getOrNull()

    private fun setActiveId(id: String) {
        runCatching { File(context.filesDir, "presets/active.id").writeText(id) }
    }

    /**
     * 列出全部预设：内置 + 库内。
     * 内置永远排在第一位（用户删光了也还有东西可点）。
     */
    fun list(): List<Entry> {
        val out = ArrayList<Entry>()
        builtInEntry()?.let { out.add(it) }
        val root = libraryRoot
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
        for (dir in dirs) {
            runCatching { entryOf(dir) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /** 内置预设：直接从 assets 读，不需要复制到 files。 */
    private fun builtInEntry(): Entry? = runCatching {
        val assets = context.assets
        val sceneText = assets.open("presets/default/scene.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val interactionsText = runCatching {
            assets.open("presets/default/interactions.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull().orEmpty()
        val name = runCatching {
            assets.open("presets/default/manifest.json").use {
                JSONObject(it.readBytes().toString(Charsets.UTF_8)).optString("name")
            }
        }.getOrNull().orEmpty().ifBlank { "内置示例" }
        val elements = runCatching { SceneParser.parse(sceneText).let { countNodes(it.root) } }.getOrDefault(0)
        Entry(
            id = BUILT_IN_ID,
            name = name,
            author = "LOOW",
            elements = elements,
            fromBuiltIn = true,
            dir = null,
            updatedAt = 0L,
            cover = null,
            interactions = describeInteractions(interactionsText)
        )
    }.getOrNull()

    private fun entryOf(dir: File): Entry {
        val manifest = File(dir, "manifest.json").takeIf { it.exists() }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val name = manifest?.optString("name")?.ifBlank { null } ?: dir.name
        val author = manifest?.optString("author")?.ifBlank { null } ?: "未知作者"
        val sceneFile = File(dir, "scene.json")
        val interactionsText = File(dir, "interactions.json").takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        val elements = if (sceneFile.exists()) {
            runCatching { SceneParser.parse(sceneFile.readText()).let { countNodes(it.root) } }.getOrDefault(0)
        } else 0
        return Entry(
            id = dir.name,
            name = name,
            author = author,
            elements = elements,
            fromBuiltIn = false,
            dir = dir,
            updatedAt = dir.lastModified(),
            cover = firstImage(File(dir, "resources")),
            interactions = describeInteractions(interactionsText)
        )
    }

    /** 卡片封面：优先用编辑器导出的预览图 `_cover.png`，其次取资源里最大的一张图。 */
    private fun firstImage(resDir: File?): File? {
        if (resDir == null || !resDir.isDirectory) return null
        val cover = File(resDir, COVER_NAME)
        if (cover.exists() && cover.length() > 0) return cover
        val names = resDir.list() ?: return null
        val candidates = names.filter {
            val lower = it.lowercase()
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
        }
        return candidates
            .map { File(resDir, it) }
            .maxByOrNull { it.length() }
    }

    /** 把某一套设为当前壁纸：清空 active，再整目录复制过去。 */
    fun activate(id: String): Result {
        val activeDir = File(context.filesDir, "presets/active")
        return runCatching {
            if (!activeDir.exists() && !activeDir.mkdirs()) {
                return Result(false, "无法创建 active 目录")
            }
            if (id == BUILT_IN_ID) {
                // 内置：从 assets 拷到 active，保持"壁纸只读 active"的单一入口
                activeDir.deleteRecursively()
                activeDir.mkdirs()
                copyAssetsFolder("presets/default", activeDir)
            } else {
                val entry = entryOf(File(libraryRoot, id))
                val src = entry.dir ?: return Result(false, "预设不存在：$id")
                activeDir.deleteRecursively()
                activeDir.mkdirs()
                src.copyRecursively(activeDir, overwrite = true)
            }
            setActiveId(id)
            Result(true, "已切换", null)
        }.getOrElse { Result(false, "切换失败：${it.message}") }
    }

    /**
     * 导入：解析校验 → 写入 `library/<id>/` → 自动激活。
     *
     * 校验放在落盘之前：宁可不写，也不要把坏预设顶到桌面上（会黑屏）。
     */
    fun import(jsonText: String): Result {
        val root = runCatching { JSONObject(jsonText) }.getOrElse {
            return Result(false, "不是合法 JSON：${it.message}")
        }

        val manifestJson = root.optJSONObject("manifest")?.toString()
        val sceneJson = when {
            root.optJSONObject("scene") != null -> root.optJSONObject("scene")!!.toString()
            root.has("root") -> root.toString()
            else -> null
        } ?: return Result(false, "找不到 scene：请用「导出预设（单文件）」")

        val interactionsJson = when {
            root.optJSONObject("interactions") != null -> root.optJSONObject("interactions")!!.toString()
            root.has("animations") || root.has("events") -> root.toString()
            else -> null
        }

        runCatching { SceneParser.parse(sceneJson) }
            .onFailure { return Result(false, "scene 解析失败：${it.message}") }
        if (interactionsJson != null) {
            runCatching { InteractionsParser.parse(interactionsJson) }
                .onFailure { return Result(false, "interactions 解析失败：${it.message}") }
        }

        val name = runCatching {
            (manifestJson?.let { JSONObject(it).optString("name") } ?: "").ifBlank { "导入的预设" }
        }.getOrDefault("导入的预设")

        // 同一份 JSON 反复导入不该堆出一排卡片：
        // 先按内容哈希找完全相同的，再按同名找（名字相同但内容更新时算"更新"）。
        val fingerprint = fingerprintOf(sceneJson, interactionsJson.orEmpty())
        val sameContent = findEntry { dir ->
            val s = File(dir, "scene.json")
            val i = File(dir, "interactions.json")
            if (!s.exists()) return@findEntry false
            fingerprintOf(
                runCatching { s.readText() }.getOrDefault(""),
                runCatching { i.takeIf { it.exists() }?.readText() ?: "" }.getOrDefault("")
            ) == fingerprint
        }
        val sameName = if (sameContent == null) findEntry { dir ->
            runCatching { JSONObject(File(dir, "manifest.json").readText()).optString("name") }.getOrNull() == name
        } else null
        val reuse = sameContent ?: sameName

        val id = reuse?.name ?: ("p" + System.currentTimeMillis().toString(36))
        val dir = File(libraryRoot, id)
        return runCatching {
            if (reuse == null) {
                if (!dir.mkdirs()) return Result(false, "无法创建预设目录")
            } else {
                // 复用的目录要清干净，避免旧资源残留在卡片封面上
                dir.deleteRecursively()
                dir.mkdirs()
            }
            File(dir, "scene.json").writeText(sceneJson)
            File(dir, "interactions.json").writeText(
                interactionsJson ?: """{"formatVersion":"0.2","animations":[],"events":[]}"""
            )
            File(dir, "manifest.json").writeText(
                manifestJson ?: """
                    {"formatVersion":"0.2","engineMinVersion":"${EngineVersion.CURRENT}",
                     "name":"$name","author":"本地导入","license":"Apache-2.0"}
                """.trimIndent()
            )
            val resCount = writeResources(dir, root.optJSONObject("resources"))
            activate(id)
            val entry = entryOf(dir)
            val verb = if (reuse != null) "已更新" else "已保存"
            Result(true, "$verb「$name」${if (resCount > 0) "，资源 $resCount 个" else ""}", entry)
        }.getOrElse { Result(false, "写入失败：${it.message}") }
    }

    private fun findEntry(predicate: (File) -> Boolean): File? =
        libraryRoot.listFiles { f -> f.isDirectory }?.firstOrNull(predicate)

    /** 内容指纹：只认场景与交互，忽略 manifest（名字/作者变了不该影响"是不是同一套"）。 */
    private fun fingerprintOf(scene: String, interactions: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((scene + "\u0000" + interactions).toByteArray())
        bytes.joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun delete(id: String): Result {
        if (id == BUILT_IN_ID) return Result(false, "内置预设不能删除")
        val dir = File(libraryRoot, id)
        if (!dir.exists()) return Result(false, "预设不存在")
        val wasActive = activeId() == id
        dir.deleteRecursively()
        // 删掉的正是在用的那套：回退到内置，避免桌面空掉
        if (wasActive) activate(BUILT_IN_ID)
        return Result(true, "已删除")
    }

    /**
     * 导出成网页端能直接导入的单文件 JSON。
     *
     * 结构 `{ formatVersion, manifest, scene, interactions, resources }`，
     * 资源以 `data:image/png;base64,...` 内嵌——和编辑器的「导出预设（单文件）」完全一致，
     * 所以导出的文件既能存档，也能再导回编辑器继续改。
     */
    fun exportJson(id: String): String? {
        val manifest: String
        val scene: String
        val interactions: String
        var resourcesDir: File? = null

        try {
            if (id == BUILT_IN_ID) {
                manifest = context.assets.open("presets/default/manifest.json")
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                scene = context.assets.open("presets/default/scene.json")
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                interactions = runCatching {
                    context.assets.open("presets/default/interactions.json")
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull().orEmpty().ifBlank { """{"formatVersion":"0.2","animations":[],"events":[]}""" }
            } else {
                val dir = File(libraryRoot, id)
                if (!dir.exists()) return null
                manifest = File(dir, "manifest.json").readText()
                scene = File(dir, "scene.json").readText()
                interactions = File(dir, "interactions.json").takeIf { it.exists() }?.readText()
                    ?: """{"formatVersion":"0.2","animations":[],"events":[]}"""
                resourcesDir = File(dir, "resources")
            }

            return buildBundleJson(manifest, scene, interactions, readResources(resourcesDir))
        } catch (error: Throwable) {
            android.util.Log.w("LOOW.Library", "导出失败：${error.message}")
            return null
        }
    }

    private fun readResources(dir: File?): Map<String, ByteArray> {
        if (dir == null || !dir.isDirectory) return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        dir.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { out[file.name] = file.readBytes() }
        }
        return out
    }

    private fun writeResources(dir: File, resources: JSONObject?): Int {
        if (resources == null) return 0
        val resDir = File(dir, "resources")
        if (!resDir.exists() && !resDir.mkdirs()) return 0
        var count = 0
        for (key in resources.keys()) {
            val raw = resources.optString(key)
            val base64 = raw.substringAfter("base64,", "")
            if (base64.isBlank()) continue
            runCatching {
                File(resDir, key).writeBytes(java.util.Base64.getDecoder().decode(base64))
                count++
            }
        }
        return count
    }

    private fun copyAssetsFolder(assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // 是文件
            runCatching {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return
        }
        if (!target.exists()) target.mkdirs()
        for (child in children) copyAssetsFolder("$assetPath/$child", File(target, child))
    }

    private fun countNodes(node: SceneNode): Int {
        // children 只在容器上有（SceneNode 是 sealed interface，矩形/文本/图片没有子节点）
        var total = 1
        if (node is ContainerNode) {
            for (child in node.children) total += countNodes(child)
        }
        return total
    }

    private fun describeInteractions(text: String): String {
        if (text.isBlank()) return ""
        return runCatching {
            val json = JSONObject(text)
            val anims = json.optJSONArray("animations")?.length() ?: 0
            val rules = json.optJSONArray("rules")?.length() ?: 0
            val binds = json.optJSONArray("bindings")?.length() ?: 0
            val parts = ArrayList<String>(3)
            if (anims > 0) parts.add("动画 $anims")
            if (rules > 0) parts.add("规则 $rules")
            if (binds > 0) parts.add("绑定 $binds")
            parts.joinToString(" · ")
        }.getOrDefault("")
    }

    companion object {
        const val BUILT_IN_ID = "builtin"
        /** 编辑器导出时塞进资源里的预览图，用作卡片封面 */
        const val COVER_NAME = "_cover.png"
        /** 主页据此刷新卡片列表 */
        const val ACTION_PRESET_CHANGED = "com.loomwall.wallpaper.PRESET_CHANGED"
        /** 壁纸服务监听的重载动作（与 LwpWallpaperService.ACTION_RELOAD 保持一致） */
        const val ACTION_RELOAD = "com.loomwall.wallpaper.action.RELOAD"

        /**
         * 通知"预设变了"：一条给主页刷新卡片，一条给壁纸进程重载场景。
         *
         * 两个动作必须都发：之前只发了 PRESET_CHANGED，而壁纸服务监听的是 RELOAD，
         * 于是切换预设后桌面不刷新，必须退出重进 App 才生效（用户实测反馈）。
         */
        /**
         * 组装单文件 JSON（纯函数，便于单测）：资源按扩展名补 MIME，内嵌成 data URL。
         */
        internal fun buildBundleJson(
            manifest: String,
            scene: String,
            interactions: String,
            resources: Map<String, ByteArray>
        ): String {
            val resJson = JSONObject()
            resources.forEach { (name, bytes) ->
                val lower = name.lowercase()
                val mime = when {
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                    lower.endsWith(".webp") -> "image/webp"
                    else -> "image/png"
                }
                resJson.put(name, "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes))
            }
            return JSONObject().apply {
                put("formatVersion", "0.3")
                put("manifest", JSONObject(manifest))
                put("scene", JSONObject(scene))
                put("interactions", JSONObject(interactions))
                put("resources", resJson)
            }.toString()
        }


        fun notifyChanged(context: Context) {
            runCatching {
                context.sendBroadcast(android.content.Intent(ACTION_PRESET_CHANGED).setPackage(context.packageName))
                context.sendBroadcast(android.content.Intent(ACTION_RELOAD).setPackage(context.packageName))
            }
        }
    }
}
