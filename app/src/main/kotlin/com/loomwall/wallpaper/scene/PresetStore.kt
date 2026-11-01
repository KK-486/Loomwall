package com.loomwall.wallpaper.scene

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 预设读取入口。
 *
 * 查找顺序：
 * 1. `filesDir/presets/active/` —— 编辑器保存的预设。
 * 2. `assets/presets/default/` —— 内置默认预设，保证首次安装就有东西可看。
 *
 * 一个预设包可以包含：
 * - `manifest.json`（必需）：包版本、引擎最低版本、名称等；
 * - `scene.json`（必需）：元素树；
 * - `interactions.json`（阶段 2 起可选）：动画等交互定义。
 *
 * 兼容性检查绝不静默失败：版本不在支持范围内、交互动引用了不存在的元素，都返回可读原因。
 */
class PresetStore(private val context: Context) {

    data class Preset(
        val name: String,
        val author: String,
        val source: String,
        val packageFormatVersion: String,
        val engineMinVersion: String,
        val scene: SceneDocument,
        val interactions: Interactions,
        /** 全局视差设置（manifest.parallax），缺省用引擎默认值。 */
        val parallax: ParallaxSettings = ParallaxSettings.DEFAULT
    )

    sealed interface LoadResult {
        data class Success(val preset: Preset) : LoadResult
        data class Failure(val reason: String) : LoadResult
    }

    fun loadActive(): LoadResult {
        return try {
            val activeDir = activePresetDir()
            val sceneFile = File(activeDir, "scene.json")
            if (sceneFile.isFile) {
                buildPreset(
                    source = "用户预设 · ${activeDir.absolutePath}",
                    manifestJson = File(activeDir, "manifest.json").takeIf { it.isFile }?.readText(),
                    sceneJson = sceneFile.readText(),
                    interactionsJson = File(activeDir, "interactions.json")
                        .takeIf { it.isFile }?.readText()
                )
            } else {
                buildPreset(
                    source = "内置默认预设 · assets/presets/default",
                    manifestJson = readAsset("presets/default/manifest.json"),
                    sceneJson = readAsset("presets/default/scene.json"),
                    interactionsJson = readAssetOrNull("presets/default/interactions.json")
                )
            }
        } catch (e: SceneFormatException) {
            LoadResult.Failure(e.message ?: "格式错误")
        } catch (e: IOException) {
            LoadResult.Failure("读取预设失败：${e.message}")
        } catch (t: Throwable) {
            LoadResult.Failure("加载预设时出现未预期异常：${t.javaClass.simpleName}：${t.message}")
        }
    }

    fun activePresetDir(): File = File(context.filesDir, "presets/active")

    /** 图片等二进制资源的目录：`presets/active/resources`。 */
    fun activeResourcesDir(): File = File(activePresetDir(), "resources")

    private fun buildPreset(
        source: String,
        manifestJson: String?,
        sceneJson: String,
        interactionsJson: String?
    ): LoadResult {
        val manifestText = manifestJson
            ?: return LoadResult.Failure("缺少 manifest.json：预设包必须包含 manifest.json（见 schema 文档）")

        val manifest = try {
            JSONObject(manifestText)
        } catch (e: JSONException) {
            return LoadResult.Failure("manifest.json 不是合法 JSON：${e.message}")
        }

        val packageFormat = manifest.optString("formatVersion", "")
        if (packageFormat.isEmpty()) {
            return LoadResult.Failure("manifest.json 缺少 formatVersion 字段")
        }
        if (packageFormat !in EngineVersion.SUPPORTED_PACKAGE_FORMATS) {
            return LoadResult.Failure(
                "预设包版本 $packageFormat 不受支持（当前引擎支持 ${EngineVersion.SUPPORTED_PACKAGE_FORMATS.joinToString(" / ")}）"
            )
        }

        val engineMinVersion = manifest.optString("engineMinVersion", "0.0.0")
        if (EngineVersion.compare(engineMinVersion, EngineVersion.CURRENT) > 0) {
            return LoadResult.Failure(
                "此预设要求引擎 ≥ $engineMinVersion，当前引擎为 ${EngineVersion.CURRENT}，请升级应用"
            )
        }

        // 全局视差设置：manifest.parallax（可选）。缺省值在 ParallaxSettings.DEFAULT。
        val parallaxSettings = ParallaxSettings.fromJson(
            manifest.optJSONObject("parallax")?.toString()
        )

        val scene = try {
            SceneParser.parse(sceneJson)
        } catch (e: SceneFormatException) {
            return LoadResult.Failure("${e.message}（来源：$source）")
        }

        val interactions = if (interactionsJson == null) {
            Interactions.EMPTY
        } else {
            try {
                InteractionsParser.parse(interactionsJson)
            } catch (e: SceneFormatException) {
                return LoadResult.Failure("${e.message}（来源：$source 的 interactions.json）")
            }
        }

        // 交互动不允许引用不存在的元素：这种错一旦静默放过，用户只会看到“点了没反应”。
        // 必须**递归**收集：容器里的子元素也可以是动画/事件的目标，
        // 只用 root.children 会把它们判成"不存在"，导致整包加载失败（黑屏）。
        val nodeIds = scene.flatten().map { it.id }.toSet()
        for (clip in interactions.animations) {
            if (clip.targetId !in nodeIds) {
                return LoadResult.Failure(
                    "动画 \"${clip.id}\" 的目标元素 \"${clip.targetId}\" 不存在（可用：${nodeIds.joinToString(" / ")}）"
                )
            }
            val triggerTarget = clip.trigger.targetId
            if (triggerTarget != null && triggerTarget !in nodeIds) {
                return LoadResult.Failure(
                    "动画 \"${clip.id}\" 的触发元素 \"$triggerTarget\" 不存在"
                )
            }
        }

        Log.i(
            TAG,
            "预设加载成功：name=${manifest.optString("name", "未命名")} rects=${scene.rectCount()} " +
                "animations=${interactions.animations.size} source=$source"
        )

        return LoadResult.Success(
            Preset(
                name = manifest.optString("name", "未命名预设"),
                author = manifest.optString("author", "未署名"),
                source = source,
                packageFormatVersion = packageFormat,
                engineMinVersion = engineMinVersion,
                scene = scene,
                interactions = interactions,
                parallax = parallaxSettings
            )
        )
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun readAssetOrNull(path: String): String? = try {
        readAsset(path)
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val TAG = "LOOW.PresetStore"
    }
}
