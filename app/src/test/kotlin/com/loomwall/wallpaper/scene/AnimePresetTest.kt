package com.loomwall.wallpaper.scene

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用仓库里那份「二次元 · 蕾姆」预设做端到端解析验证。
 *
 * 为什么值得单独一个测试：这份预设是产品视频要用的，里面同时用到了
 * 渐变、羽化、path 形状、图片资源、表达式绑定、always/session 触发——
 * 任何一处解析不支持，手机上就会整份回退成默认壁纸（用户只会看到"没生效"）。
 */
class AnimePresetTest {

    @Test
    fun `仓库里的二次元预设能被完整解析`() {
        val file = File("壁纸效果/二次元·蕾姆.lwpkit.json")
        if (!file.exists()) return          // 没有这份文件时跳过（别人 clone 时不会失败）

        val root = JSONObject(file.readText())
        val scene = SceneParser.parse(root.getJSONObject("scene").toString())
        assertTrue("场景里应该有节点", scene.root.children.size > 20)

        val interactions = InteractionsParser.parse(root.getJSONObject("interactions").toString())
        assertTrue("应该有动画", interactions.animations.isNotEmpty())
        assertTrue("应该有表达式绑定", interactions.bindings.any { !it.expr.isNullOrBlank() })

        val images = scene.root.children.filterIsInstance<ImageNode>()
        assertTrue("应该有图片节点", images.isNotEmpty())
        assertTrue("图片资源名不能为空", images.all { it.src.isNotBlank() })

        val resources = root.optJSONObject("resources")
        assertTrue("资源表里要带上图片", resources != null && resources.length() >= 2)
    }
}
