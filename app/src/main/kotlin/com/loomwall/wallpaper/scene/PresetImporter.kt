package com.loomwall.wallpaper.scene

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * 预设导入器：把网页端导出的 JSON 落盘到 `presets/active`，壁纸重新可见时会自动重读。
 *
 * 支持三种输入，避免用户还要记住"该导出哪种"：
 * 1. **单文件包** `{ manifest, scene, interactions, resources }` —— 网页「导出预设（单文件）」
 * 2. 单个 `scene.json`（可以是完整的包目录里的那份）
 * 3. 单个 `interactions.json`
 *
 * 关键纪律：**先解析校验，再落盘**。宁可报错，也不要把坏预设写进去，
 * 否则桌面上会变成一片黑，用户还不知道为什么。
 */
object PresetImporter {

    data class Result(val ok: Boolean, val message: String)

    fun import(context: Context, jsonText: String): Result {
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return Result(false, "不是合法 JSON：${e.message}")
        }

        val manifestJson = root.optJSONObject("manifest")?.toString()
        val sceneJson = when {
            root.optJSONObject("scene") != null -> root.optJSONObject("scene")!!.toString()
            root.has("root") -> root.toString()
            else -> null
        }
        val interactionsJson = when {
            root.optJSONObject("interactions") != null -> root.optJSONObject("interactions")!!.toString()
            root.has("animations") || root.has("events") -> root.toString()
            else -> null
        }

        if (sceneJson == null) {
            return Result(false, "找不到 scene：请用「导出预设（单文件）」，或选择 scene.json")
        }

        // 1) 先校验：解析失败直接返回，不落盘
        runCatching { SceneParser.parse(sceneJson) }
            .onFailure { return Result(false, "scene 解析失败：${it.message}") }
        if (interactionsJson != null) {
            runCatching { InteractionsParser.parse(interactionsJson) }
                .onFailure { return Result(false, "interactions 解析失败：${it.message}") }
        }

        // 2) 再写盘
        val dir = File(context.filesDir, "presets/active")
        if (!dir.exists() && !dir.mkdirs()) {
            return Result(false, "无法创建预设目录")
        }

        val name = runCatching {
            (manifestJson?.let { JSONObject(it).optString("name") } ?: "").ifBlank { "导入的预设" }
        }.getOrDefault("导入的预设")

        return runCatching {
            File(dir, "scene.json").writeText(sceneJson)
            File(dir, "interactions.json").writeText(
                interactionsJson ?: """{"formatVersion":"0.2","animations":[],"events":[]}"""
            )
            File(dir, "manifest.json").writeText(
                manifestJson ?: """
                    {"formatVersion":"0.2","engineMinVersion":"${EngineVersion.CURRENT}",
                     "name":"$name","author":"web","license":"Apache-2.0"}
                """.trimIndent()
            )
            val resourceCount = writeResources(dir, root.optJSONObject("resources"))
            Result(true, "已导入「$name」${if (resourceCount > 0) "，资源 $resourceCount 个" else ""}")
        }.getOrElse { Result(false, "写入失败：${it.message}") }
    }

    /** 把 data URL 资源还原成文件（图片渲染还没做，先把文件准备好）。 */
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
                File(resDir, key).writeBytes(Base64.decode(base64, Base64.DEFAULT))
                count++
            }
        }
        return count
    }
}
