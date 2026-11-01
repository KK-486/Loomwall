package com.loomwall.wallpaper.scene

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 编辑器侧的预设读写。
 *
 * 存储位置与壁纸引擎一致：`filesDir/presets/active/`。
 * 壁纸引擎在重新可见时会重读这个目录，所以“编辑器保存 → 回桌面”就能看到修改，
 * 不需要额外的进程间通信（阶段 2 不引入广播/服务绑定）。
 */
class PresetRepository(private val context: Context) {

    data class Snapshot(
        val scene: SceneDocument,
        val interactions: Interactions,
        val name: String,
        val fromBuiltIn: Boolean
    )

    sealed interface Result {
        data class Ok(val snapshot: Snapshot) : Result
        data class Err(val reason: String) : Result
    }

    private val store = PresetStore(context)

    fun activeDir(): File = store.activePresetDir()

    /** 读取可供编辑的场景与交互动：优先用户预设，其次内置默认预设。 */
    fun loadForEditing(): Result = when (val result = store.loadActive()) {
        is PresetStore.LoadResult.Success -> Result.Ok(
            Snapshot(
                scene = result.preset.scene,
                interactions = result.preset.interactions,
                name = result.preset.name,
                fromBuiltIn = result.preset.source.startsWith("内置")
            )
        )
        is PresetStore.LoadResult.Failure -> Result.Err(result.reason)
    }

    /**
     * 保存到 active 目录：manifest.json + scene.json + interactions.json。
     * 先写临时文件再重命名，避免写到一半被壁纸引擎读到半个 JSON。
     */
    fun save(scene: SceneDocument, interactions: Interactions, name: String): kotlin.Result<Unit> =
        runCatching {
            val dir = activeDir()
            if (!dir.exists() && !dir.mkdirs()) {
                error("无法创建目录：${dir.absolutePath}")
            }

            val manifest = JSONObject().apply {
                put("formatVersion", EngineVersion.PACKAGE_FORMAT)
                put("engineMinVersion", EngineVersion.CURRENT)
                put("name", name)
                put("author", "LOOW 编辑器")
                put("license", "Apache-2.0")
            }

            writeAtomically(File(dir, "manifest.json"), manifest.toString(2))
            writeAtomically(File(dir, "scene.json"), SceneWriter.toJson(scene))
            writeAtomically(File(dir, "interactions.json"), InteractionsWriter.toJson(interactions))

            Log.i(
                TAG,
                "预设已保存：${dir.absolutePath}（${scene.rectCount()} 个矩形，" +
                    "${interactions.animations.size} 条动画）"
            )
        }

    /** 丢弃用户预设，回到内置默认。 */
    fun resetToBuiltIn(): kotlin.Result<Unit> = runCatching {
        val dir = activeDir()
        File(dir, "scene.json").delete()
        File(dir, "manifest.json").delete()
        File(dir, "interactions.json").delete()
        Log.i(TAG, "已清除用户预设，回退到内置默认")
    }

    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (target.exists() && !target.delete()) {
            // 删不掉就退化为直接覆盖，至少不让用户白改一场。
            target.writeText(content)
            tmp.delete()
            return
        }
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "LOOW.Editor"
    }
}
