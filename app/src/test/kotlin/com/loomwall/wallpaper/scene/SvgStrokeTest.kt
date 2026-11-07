package com.loomwall.wallpaper.scene

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG 线框图标（fill="none" + stroke）必须能"解析 → 写出"完整往返。
 *
 * 为什么值得单测：这条链路断过——编辑器插入 SVG 时把 strokeWidth 丢了，
 * 线框星形变成实心块，在浅色背景上甚至完全看不见（用户反馈"效果不一样"）。
 * 只要 store 端能往返，编辑器那边丢字段就会立刻被测出来。
 */
class SvgStrokeTest {

    private val sceneJson = """
        {
          "formatVersion": "0.3",
          "root": {
            "id": "root", "type": "container", "x": 0, "y": 0, "width": 100, "height": 216.67,
            "children": [
              {
                "id": "star", "type": "rect", "shape": "path",
                "x": 10, "y": 10, "width": 30, "height": 30,
                "color": "#FFFFFFFF", "strokeWidth": 2,
                "path": "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z",
                "viewBox": "0 0 24 24"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `解析时保留 strokeWidth`() {
        val doc = SceneParser.parse(sceneJson)
        val node = doc.root.children.filterIsInstance<RectNode>().single()
        assertEquals(ShapeKind.PATH, node.shape)
        assertEquals(2f, node.strokeWidth, 0.001f)
        assertTrue("路径数据不能为空", node.pathData.isNotBlank())
    }

    @Test
    fun `写出时也要带上 strokeWidth`() {
        val doc = SceneParser.parse(sceneJson)
        val written = JSONObject(SceneWriter.toJson(doc))
        val child = written.getJSONObject("root").getJSONArray("children").getJSONObject(0)
        assertEquals(2.0, child.getDouble("strokeWidth"), 0.001)
        // viewBox 等于默认值 "0 0 24 24" 时不写（保持 JSON 干净），重新解析应还原成默认
        val again = SceneParser.parse(written.toString())
        val againNode = again.root.children.filterIsInstance<RectNode>().single()
        assertEquals("0 0 24 24", againNode.viewBox)
    }

    @Test
    fun `没有描边的路径不写 strokeWidth 字段`() {
        val filled = sceneJson.replace("\"strokeWidth\": 2,", "")
        val doc = SceneParser.parse(filled)
        val written = JSONObject(SceneWriter.toJson(doc))
        val child = written.getJSONObject("root").getJSONArray("children").getJSONObject(0)
        assertTrue("填充路径不应该写 strokeWidth", !child.has("strokeWidth"))
    }
}
