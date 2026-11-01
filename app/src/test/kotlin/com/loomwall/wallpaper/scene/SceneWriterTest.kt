package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 序列化与解析必须严格对称：
 * 编辑器保存的预设，壁纸引擎必须能原样读回；否则用户改完一切正常、重启后桌面变了样。
 */
class SceneWriterTest {

    private fun defaultScene(): SceneDocument =
        SceneParser.parse(File("src/main/assets/presets/default/scene.json").readText())

    @Test
    fun `写出再读回与原场景等价`() {
        val original = defaultScene()
        val reparsed = SceneParser.parse(SceneWriter.toJson(original))

        // 写出时统一升级到当前格式版本（0.2 起支持嵌套容器）；
        // 旧预设仍是 0.1，但保存一次就会变成 0.2，这是预期行为。
        assertEquals(EngineVersion.SCENE_FORMAT, reparsed.formatVersion)
        assertEquals(original.root.background.toHex(), reparsed.root.background.toHex())
        assertEquals(original.rectCount(), reparsed.rectCount())

        val before = original.root.children.filterIsInstance<RectNode>()
        val after = reparsed.root.children.filterIsInstance<RectNode>()
        before.zip(after).forEach { (a, b) ->
            assertEquals(a.id, b.id)
            assertEquals(a.x, b.x, 0.05f)
            assertEquals(a.y, b.y, 0.05f)
            assertEquals(a.width, b.width, 0.05f)
            assertEquals(a.height, b.height, 0.05f)
            assertEquals(a.opacity, b.opacity, 0.01f)
            assertEquals(a.visible, b.visible)
            assertEquals(a.color.toHex(), b.color.toHex())
        }
    }

    @Test
    fun `颜色统一写成 AARRGGBB`() {
        val json = SceneWriter.toJson(defaultScene())
        assertTrue("应包含 #AARRGGBB 形式的颜色：\n$json", Regex("\"color\": \"#[0-9A-F]{8}\"").containsMatchIn(json))
        assertTrue(Regex("\"background\": \"#[0-9A-F]{8}\"").containsMatchIn(json))
    }

    @Test
    fun `数值不会写出浮点噪声`() {
        val json = SceneWriter.toJson(defaultScene())
        assertTrue("不应出现超长小数：\n$json", !Regex("\\d+\\.\\d{4,}").containsMatchIn(json))
    }

    @Test
    fun `写出的 JSON 仍是合法的 0_1 场景`() {
        val scene = SceneParser.parse(SceneWriter.toJson(defaultScene()))
        assertEquals(EngineVersion.SCENE_FORMAT, scene.formatVersion)
        assertEquals(3, scene.rectCount())
    }
}
