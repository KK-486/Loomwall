package com.loomwall.wallpaper.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片节点：解析、写回、缺失 src 的报错。
 */
class ImageNodeTest {

    private fun scene(children: String) = """
        {
          "formatVersion": "0.1",
          "root": {
            "id": "root", "type": "container", "background": "#FF000000",
            "children": [$children]
          }
        }
    """.trimIndent()

    @Test
    fun `图片节点可以被解析`() {
        val doc = SceneParser.parse(
            scene(
                """{ "id": "img1", "type": "image", "x": 10, "y": 20, "width": 60, "height": 30,
                     "src": "photo.png", "opacity": 0.8, "rotationZ": 15, "scale": 1.5 }"""
            )
        )
        val image = doc.root.children.filterIsInstance<ImageNode>().single()
        assertEquals("photo.png", image.src)
        assertEquals(60f, image.width, 1e-6f)
        assertEquals(0.8f, image.opacity, 1e-6f)
        assertEquals(15f, image.rotationZ, 1e-6f)
        assertEquals(1.5f, image.scale, 1e-6f)
    }

    @Test
    fun `图片节点可以写回再读回`() {
        val doc = SceneParser.parse(
            scene(
                """{ "id": "img1", "type": "image", "x": 12.5, "y": 33.0, "width": 40, "height": 20,
                     "src": "a.jpg" }"""
            )
        )
        val reparsed = SceneParser.parse(SceneWriter.toJson(doc))
        val image = reparsed.root.children.filterIsInstance<ImageNode>().single()
        assertEquals("a.jpg", image.src)
        assertEquals(12.5f, image.x, 1e-6f)
        assertEquals(20f, image.height, 1e-6f)
    }

    @Test
    fun `缺少 src 会被拒绝`() {
        val error = runCatching {
            SceneParser.parse(
                scene("""{ "id": "img1", "type": "image", "x": 0, "y": 0, "width": 10, "height": 10 }""")
            )
        }.exceptionOrNull()
        assertTrue(error is SceneFormatException)
        assertTrue(error!!.message!!.contains("src"))
    }

    @Test
    fun `图片与文本可以共存`() {
        val doc = SceneParser.parse(
            scene(
                """{ "id": "img1", "type": "image", "x": 0, "y": 0, "width": 10, "height": 10, "src": "a.png" },
                   { "id": "t1", "type": "text", "x": 0, "y": 20, "width": 50, "height": 6,
                     "text": "hi", "fontSize": 4, "color": "#FFFFFFFF" }"""
            )
        )
        assertEquals(1, doc.root.children.filterIsInstance<ImageNode>().size)
        assertEquals(1, doc.root.children.filterIsInstance<TextNode>().size)
    }
}
