package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 场景格式 v0.1 的解析器测试。
 *
 * 重点不是覆盖率，而是把「格式契约」钉死：
 * - 仓库里真正会被打包的默认预设必须能通过解析；
 * - 每一种常见的手改错误都必须给出带 JSON 路径的可读错误，而不是静默降级。
 *
 * 运行：build.cmd testDebugUnitTest
 */
class SceneParserTest {

    /** 直接读会被打包进 APK 的默认预设，避免测试与真实资产漂移。 */
    private fun defaultSceneJson(): String =
        File("src/main/assets/presets/default/scene.json").readText()

    private fun sceneWith(childJson: String): String = """
        {
          "formatVersion": "0.1",
          "root": {
            "id": "root",
            "type": "container",
            "background": "#FF000000",
            "children": [ $childJson ]
          }
        }
    """.trimIndent()


    @Test
    fun `默认预设可以被解析`() {
        val scene = SceneParser.parse(defaultSceneJson())

        assertEquals("0.1", scene.formatVersion)
        assertEquals(3, scene.rectCount())

        val first = scene.root.children.first() as RectNode
        assertEquals("rect_glow", first.id)
        assertEquals(20f, first.x, 0.001f)
        assertEquals(26f, first.y, 0.001f)
        assertEquals(60f, first.width, 0.001f)
        assertEquals(18f, first.height, 0.001f)
        assertEquals(0.85f, first.opacity, 0.001f)
        assertTrue(first.visible)
        // #FF3DDC97：不透明绿
        assertEquals(1f, first.color.a, 0.001f)
        assertEquals(0x3D / 255f, first.color.r, 0.01f)
    }


    @Test
    fun `省略 opacity 与 visible 时取默认值`() {
        val scene = SceneParser.parse(
            sceneWith(
                """
                {"type": "rect", "id": "a", "x": 0, "y": 0, "width": 10, "height": 10, "color": "#FFFFFF"}
                """.trimIndent()
            )
        )
        val rect = scene.root.children.single() as RectNode
        assertEquals(1f, rect.opacity, 0f)
        assertTrue(rect.visible)
        assertEquals(1f, rect.color.a, 0f)
    }


    @Test
    fun `不支持的格式版本要报错`() {
        val e = assertThrows(SceneFormatException::class.java) {
            SceneParser.parse("""{"formatVersion": "9.9", "root": {"children": []}}""")
        }
        assertNotNull(e.jsonPath)
        assertEquals("/formatVersion", e.jsonPath)
        assertTrue(e.message!!.contains("9.9"))
    }


    @Test
    fun `宽度必须大于零`() {
        val e = assertThrows(SceneFormatException::class.java) {
            SceneParser.parse(
                sceneWith(
                    """{"type": "rect", "id": "a", "x": 0, "y": 0, "width": 0, "height": 10, "color": "#FFFFFF"}"""
                )
            )
        }
        assertEquals("/root/children[0]/width", e.jsonPath)
    }


    @Test
    fun `颜色格式错误要报出具体字段`() {
        val e = assertThrows(SceneFormatException::class.java) {
            SceneParser.parse(
                sceneWith(
                    """{"type": "rect", "id": "a", "x": 0, "y": 0, "width": 10, "height": 10, "color": "green"}"""
                )
            )
        }
        assertEquals("/root/children[0]/color", e.jsonPath)
    }


    @Test
    fun `opacity 超出范围要报错`() {
        val e = assertThrows(SceneFormatException::class.java) {
            SceneParser.parse(
                sceneWith(
                    """{"type": "rect", "id": "a", "x": 0, "y": 0, "width": 10, "height": 10, "color": "#FFFFFF", "opacity": 2}"""
                )
            )
        }
        assertEquals("/root/children[0]/opacity", e.jsonPath)
    }


    @Test
    fun `重复的 id 要报错`() {
        val e = assertThrows(SceneFormatException::class.java) {
            SceneParser.parse(
                sceneWith(
                    """{"type": "rect", "id": "same", "x": 0, "y": 0, "width": 10, "height": 10, "color": "#FFFFFF"}"""
                ).replace(
                    "\"children\": [",
                    "\"children\": [{\"type\": \"rect\", \"id\": \"same\", \"x\": 0, \"y\": 0, \"width\": 5, \"height\": 5, \"color\": \"#FFFFFF\"},"
                )
            )
        }
        assertTrue(e.message!!.contains("重复"))
    }


    @Test
    fun `未知节点类型要明确拒绝`() {
        val e = assertThrows(SceneFormatException::class.java) {
            SceneParser.parse(
                sceneWith("""{"type": "circle", "id": "c", "x": 0, "y": 0, "width": 10, "height": 10, "color": "#FFFFFF"}""")
            )
        }
        assertTrue(e.message!!.contains("circle"))
    }


    @Test
    fun `缺失必填字段要指出字段名`() {
        val e = assertThrows(SceneFormatException::class.java) {
            SceneParser.parse(
                sceneWith("""{"type": "rect", "id": "a", "x": 0, "y": 0, "height": 10, "color": "#FFFFFF"}""")
            )
        }
        assertTrue(e.message!!.contains("width"))
    }


    @Test
    fun `不是合法 JSON 时给出可读错误`() {
        val e = assertThrows(SceneFormatException::class.java) {
            SceneParser.parse("{ this is not json }")
        }
        assertEquals("/", e.jsonPath)
    }
    @Test
    fun `嵌套容器可以解析并带出几何`() {
        val json = """
            {
              "formatVersion": "0.2",
              "root": {
                "id": "root", "type": "container", "background": "#FF000000",
                "children": [
                  {
                    "id": "group_a", "type": "container",
                    "x": 10, "y": 20, "width": 50, "height": 30,
                    "rotationZ": 5, "scale": 1.5, "opacity": 0.8,
                    "background": "#33FF0000",
                    "children": [
                      { "id": "inner_rect", "type": "rect", "x": 2, "y": 3, "width": 10, "height": 10, "color": "#FFFFFFFF" }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val doc = SceneParser.parse(json)
        val group = doc.root.children.single() as ContainerNode
        assertEquals("group_a", group.id)
        assertEquals(10f, group.x, 0.01f)
        assertEquals(50f, group.width, 0.01f)
        assertEquals(5f, group.rotationZ, 0.01f)
        assertEquals(1.5f, group.scale, 0.01f)
        assertEquals(false, group.isRoot)
        assertEquals("inner_rect", (group.children.single() as RectNode).id)
    }

    @Test
    fun `嵌套容器写出后能再读回`() {
        val json = """
            {
              "formatVersion": "0.2",
              "root": {
                "id": "root", "type": "container", "background": "#FF000000",
                "children": [
                  { "id": "g", "type": "container", "x": 5, "y": 6, "width": 40, "height": 20,
                    "children": [
                      { "id": "r", "type": "rect", "x": 1, "y": 1, "width": 8, "height": 8, "color": "#FF00FF00" }
                    ] }
                ]
              }
            }
        """.trimIndent()

        val doc = SceneParser.parse(json)
        val again = SceneParser.parse(SceneWriter.toJson(doc))
        val group = again.root.children.single() as ContainerNode
        assertEquals(5f, group.x, 0.01f)
        assertEquals("r", (group.children.single() as RectNode).id)
    }

}
