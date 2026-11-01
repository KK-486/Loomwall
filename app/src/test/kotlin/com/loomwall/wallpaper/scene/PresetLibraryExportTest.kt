package com.loomwall.wallpaper.scene

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端「导出 JSON」的组装逻辑。
 *
 * 导出的文件要能被网页编辑器的「导入 JSON」直接打开，所以结构必须与
 * 编辑器导出的单文件一致：{ formatVersion, manifest, scene, interactions, resources }，
 * 资源内嵌成 data URL。
 */
class PresetLibraryExportTest {

    private val manifest = """{"formatVersion":"0.2","name":"测试壁纸","author":"tester"}"""
    private val scene = """{"formatVersion":"0.2","root":{"id":"root","type":"container","width":100,"height":100}}"""
    private val interactions = """{"formatVersion":"0.2","animations":[],"events":[]}"""

    @Test
    fun `导出的 JSON 含四个必需字段`() {
        val json = JSONObject(PresetLibrary.buildBundleJson(manifest, scene, interactions, emptyMap()))
        assertTrue(json.has("manifest"))
        assertTrue(json.has("scene"))
        assertTrue(json.has("interactions"))
        assertTrue(json.has("resources"))
        assertEquals("0.3", json.getString("formatVersion"))
    }

    @Test
    fun `资源被打包成 data URL 且保留文件名`() {
        val json = JSONObject(
            PresetLibrary.buildBundleJson(
                manifest, scene, interactions,
                mapOf("_cover.png" to byteArrayOf(1, 2, 3), "photo.jpg" to byteArrayOf(4, 5))
            )
        )
        val resources = json.getJSONObject("resources")
        assertEquals(2, resources.length())
        assertTrue(resources.getString("_cover.png").startsWith("data:image/png;base64,"))
        assertTrue(resources.getString("photo.jpg").startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `名称原样保留`() {
        val json = JSONObject(PresetLibrary.buildBundleJson(manifest, scene, interactions, emptyMap()))
        assertEquals("测试壁纸", json.getJSONObject("manifest").getString("name"))
    }
}
