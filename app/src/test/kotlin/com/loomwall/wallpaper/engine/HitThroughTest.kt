package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.SceneParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 命中穿透测试。
 *
 * 这条能力是「菜单/遮罩」类 UI 的基础：全屏遮罩若参与命中，会把所有点击吃掉，
 * 表现为"菜单点不动、遮罩关不掉"。所以这里直接对 hitTest 断言，不依赖 GL。
 */
class HitThroughTest {

    /**
     * 遮罩盖住按钮，且遮罩 hitThrough。
     * 期望：点在按钮区域命中的是按钮，而不是遮罩。
     */
    @Test
    fun `hitThrough overlay does not swallow taps`() {
        val scene = SceneParser.parse(
            """
            {
              "formatVersion": "0.2",
              "root": {
                "id": "root", "type": "container", "visible": true,
                "children": [
                  { "id": "btn", "type": "rect", "visible": true,
                    "x": 10, "y": 10, "width": 30, "height": 10, "color": "#FF00FF00" },
                  { "id": "overlay", "type": "rect", "visible": true, "hitThrough": true,
                    "x": 0, "y": 0, "width": 100, "height": 200, "color": "#99000000" }
                ]
              }
            }
            """.trimIndent()
        )
        val renderer = SceneRenderer()
        // 遮罩在最上层，但穿透 → 命中下层按钮
        assertEquals("btn", renderer.hitTest(scene, 20f, 15f))
    }

    /** 没有穿透标记时，最上层遮罩照常命中它自己（保持原有语义）。 */
    @Test
    fun `overlay without hitThrough wins`() {
        val scene = SceneParser.parse(
            """
            {
              "formatVersion": "0.2",
              "root": {
                "id": "root", "type": "container", "visible": true,
                "children": [
                  { "id": "btn", "type": "rect", "visible": true,
                    "x": 10, "y": 10, "width": 30, "height": 10, "color": "#FF00FF00" },
                  { "id": "overlay", "type": "rect", "visible": true,
                    "x": 0, "y": 0, "width": 100, "height": 200, "color": "#99000000" }
                ]
              }
            }
            """.trimIndent()
        )
        val renderer = SceneRenderer()
        assertEquals("overlay", renderer.hitTest(scene, 20f, 15f))
    }

    /** 容器整体穿透时，内部按钮仍然可点（遮罩里放按钮的常见写法）。 */
    @Test
    fun `hitThrough container still allows children`() {
        val scene = SceneParser.parse(
            """
            {
              "formatVersion": "0.2",
              "root": {
                "id": "root", "type": "container", "visible": true,
                "children": [
                  { "id": "panel", "type": "container", "visible": true, "hitThrough": true,
                    "x": 0, "y": 0, "width": 100, "height": 100, "background": "#99000000",
                    "children": [
                      { "id": "inner_btn", "type": "rect", "visible": true,
                        "x": 10, "y": 10, "width": 20, "height": 8, "color": "#FFFF0000" }
                    ] }
                ]
              }
            }
            """.trimIndent()
        )
        val renderer = SceneRenderer()
        assertEquals("inner_btn", renderer.hitTest(scene, 15f, 14f))
    }
}