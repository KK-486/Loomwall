package com.loomwall.wallpaper.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 透明度覆盖语义测试。
 *
 * 背景（真机 bug）：曾经写成 `node.opacity * override.opacity`，
 * 于是"规则想把一个 opacity=0 的图层显示出来"永远得到 0 × 1 = 0 —— 表现为"点了没反应"。
 * 用户的原话是"展开就可以，默认关闭就不行"，正好对应 1×1=1 与 0×任何=0。
 *
 * 所以这条语义必须被锁死：**覆盖是替换，不是相乘**。
 */
class EffectiveAlphaTest {

    private val renderer = SceneRenderer()

    @Test
    fun `没有覆盖时用节点自身透明度`() {
        assertEquals(1f, renderer.effectiveAlpha(1f, null), 0.001f)
        assertEquals(0.35f, renderer.effectiveAlpha(0.35f, null), 0.001f)
        assertEquals(0f, renderer.effectiveAlpha(0f, null), 0.001f)
    }

    @Test
    fun `覆盖可以显示本来是透明的元素`() {
        // 这是 bug 的核心场景：节点 0，规则要它 1 → 必须是 1（相乘会得 0）
        assertEquals(1f, renderer.effectiveAlpha(0f, 1f), 0.001f)
        assertEquals(0.8f, renderer.effectiveAlpha(0f, 0.8f), 0.001f)
    }

    @Test
    fun `覆盖可以隐藏本来可见的元素`() {
        assertEquals(0f, renderer.effectiveAlpha(1f, 0f), 0.001f)
        assertEquals(0.2f, renderer.effectiveAlpha(1f, 0.2f), 0.001f)
    }

    @Test
    fun `覆盖值超出范围会被夹住`() {
        assertEquals(1f, renderer.effectiveAlpha(0.5f, 3f), 0.001f)
        assertEquals(0f, renderer.effectiveAlpha(0.5f, -2f), 0.001f)
    }
}
