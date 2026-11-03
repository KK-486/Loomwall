package com.loomwall.wallpaper.engine

import com.loomwall.wallpaper.scene.AnimatableProperty
import com.loomwall.wallpaper.scene.Binding
import com.loomwall.wallpaper.scene.Interactions
import com.loomwall.wallpaper.scene.InteractionsParser
import com.loomwall.wallpaper.scene.InteractionsWriter
import com.loomwall.wallpaper.scene.NodeOverrides
import com.loomwall.wallpaper.scene.SystemVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统变量绑定：区间映射 + 变量缺失时的降级 + 与动画的合并优先级。
 */
class BindingEvaluatorTest {

    private fun binding(
        source: String = SystemVars.BATTERY_LEVEL,
        property: AnimatableProperty = AnimatableProperty.SCALE,
        inMin: Float = 0f,
        inMax: Float = 100f,
        outMin: Float = 0.5f,
        outMax: Float = 1.5f
    ) = Binding("rect_a", property, source, inMin, inMax, outMin, outMax)

    @Test
    fun `区间映射包含端点并夹紧越界值`() {
        val b = binding()
        assertEquals(0.5f, b.map(0f), 1e-6f)
        assertEquals(1.0f, b.map(50f), 1e-6f)
        assertEquals(1.5f, b.map(100f), 1e-6f)
        assertEquals("超出上界要夹住", 1.5f, b.map(250f), 1e-6f)
        assertEquals("低于下界要夹住", 0.5f, b.map(-30f), 1e-6f)
    }

    @Test
    fun `输入区间退化时不会除零`() {
        val b = binding(inMin = 50f, inMax = 50f, outMin = 2f, outMax = 3f)
        assertEquals(2f, b.map(50f), 1e-6f)
    }

    @Test
    fun `绑定表被换算成按元素分组的覆盖值`() {
        val result = BindingEvaluator.evaluate(
            listOf(
                binding(source = SystemVars.BATTERY_LEVEL, property = AnimatableProperty.SCALE),
                binding(source = SystemVars.MEM_USED_PCT, property = AnimatableProperty.OPACITY, outMin = 0f, outMax = 1f)
            ),
            mapOf(SystemVars.BATTERY_LEVEL to 50f, SystemVars.MEM_USED_PCT to 40f)
        )
        val overrides = result["rect_a"]!!
        assertEquals(1.0f, overrides.scale!!, 1e-6f)
        // in [0,100] → out [0,1]，40% 内存映射成 0.4 的透明度
        assertEquals(0.4f, overrides.opacity!!, 1e-6f)
    }

    @Test
    fun `变量缺失时跳过该绑定元素保持静态`() {
        val result = BindingEvaluator.evaluate(listOf(binding()), emptyMap())
        assertTrue("读不到变量就不应该产生覆盖值", result.isEmpty())
    }

    @Test
    fun `透明度绑定会被夹到 0 到 1`() {
        val result = BindingEvaluator.evaluate(
            listOf(binding(property = AnimatableProperty.OPACITY, outMin = 0f, outMax = 5f)),
            mapOf(SystemVars.BATTERY_LEVEL to 100f)
        )
        assertEquals(1f, result["rect_a"]!!.opacity!!, 1e-6f)
    }

    @Test
    fun `动画覆盖值优先于绑定`() {
        val anim = mapOf("rect_a" to NodeOverrides(scale = 2f))
        val bind = mapOf("rect_a" to NodeOverrides(scale = 0.7f, rotationZ = 10f))
        val merged = BindingEvaluator.merge(anim, bind)["rect_a"]!!
        assertEquals("动画优先", 2f, merged.scale!!, 1e-6f)
        assertEquals("动画没有的字段由绑定补上", 10f, merged.rotationZ!!, 1e-6f)
    }

    @Test
    fun `绑定可以写回并读回`() {
        val interactions = Interactions(
            formatVersion = "0.1",
            animations = emptyList(),
            bindings = listOf(binding())
        )
        val json = InteractionsWriter.toJson(interactions)
        val reparsed = InteractionsParser.parse(json)
        val b = reparsed.bindings.single()
        assertEquals("rect_a", b.targetId)
        assertEquals(SystemVars.BATTERY_LEVEL, b.source)
        assertEquals(0.5f, b.outMin, 1e-6f)
        assertEquals(1.5f, b.outMax, 1e-6f)
    }

    @Test
    fun `未知变量会被解析器拒绝并提示可用清单`() {
        val json = """
            {
              "formatVersion": "0.1",
              "animations": [],
              "bindings": [
                { "targetId": "rect_a", "property": "scale",
                  "source": "sys.cpu.freq", "in": [0, 1], "out": [1, 2] }
              ]
            }
        """.trimIndent()
        val error = runCatching { InteractionsParser.parse(json) }.exceptionOrNull()
        assertTrue("未知变量必须报错", error != null)
        assertTrue(
            "错误里要列出可用的变量名",
            error!!.message!!.contains("sys.battery.level")
        )
    }

    // ---- 宽高作为可动画/可绑定属性（0.7 格式新增）----

    @Test
    fun `宽高可以被绑定驱动`() {
        val bindings = listOf(
            binding(property = AnimatableProperty.WIDTH, outMin = 20f, outMax = 88f),
            binding(property = AnimatableProperty.HEIGHT, outMin = 10f, outMax = 40f)
        )
        val result = BindingEvaluator.evaluate(bindings, mapOf(SystemVars.BATTERY_LEVEL to 50f))
        val ov = result["rect_a"]!!
        assertEquals("宽度按区间映射", 54f, ov.width!!, 1e-4f)
        assertEquals("高度按区间映射", 25f, ov.height!!, 1e-4f)
    }

    @Test
    fun `负的宽高会被夹到零而不是反向`() {
        val bindings = listOf(
            binding(property = AnimatableProperty.WIDTH, outMin = -30f, outMax = -10f)
        )
        val result = BindingEvaluator.evaluate(bindings, mapOf(SystemVars.BATTERY_LEVEL to 0f))
        assertEquals("负宽度会让三角化方向反转，必须夹到 0", 0f, result["rect_a"]!!.width!!, 1e-6f)
    }

    @Test
    fun `动画与绑定合并时宽高不会丢`() {
        val anim = mapOf("rect_a" to NodeOverrides(width = 60f))
        val bind = mapOf("rect_a" to NodeOverrides(height = 30f))
        val merged = BindingEvaluator.merge(anim, bind)
        assertEquals("动画的宽度要保留", 60f, merged["rect_a"]!!.width!!, 1e-6f)
        assertEquals("绑定的高度要合并进来", 30f, merged["rect_a"]!!.height!!, 1e-6f)
    }

    @Test
    fun `表达式绑定按公式算值并忽略 in out`() {
        val b = Binding(
            targetId = "bar", property = AnimatableProperty.HEIGHT,
            source = "", inMin = 0f, inMax = 1f, outMin = 0f, outMax = 1f,
            expr = "20 + 60 * audio(3)"
        )
        val result = BindingEvaluator.evaluate(listOf(b), mapOf("sys.audio.b3" to 0.5f))
        assertEquals(50f, result["bar"]!!.height!!, 1e-3f)
    }

    @Test
    fun `表达式里能用三频段与节拍`() {
        val b = Binding(
            targetId = "dot", property = AnimatableProperty.SCALE,
            source = "", inMin = 0f, inMax = 1f, outMin = 1f, outMax = 1f,
            expr = "1 + beat() * 0.4 + bass() * 0.1"
        )
        val result = BindingEvaluator.evaluate(
            listOf(b), mapOf("sys.audio.beat" to 1f, "sys.audio.bass" to 0.5f)
        )
        assertEquals(1.45f, result["dot"]!!.scale!!, 1e-3f)
    }

    @Test
    fun `表达式写错时不影响其它绑定也不崩`() {
        val broken = Binding(
            targetId = "a", property = AnimatableProperty.OPACITY,
            source = "", inMin = 0f, inMax = 1f, outMin = 0f, outMax = 1f,
            expr = "oops( +"
        )
        val fine = Binding(
            targetId = "b", property = AnimatableProperty.OPACITY,
            source = "", inMin = 0f, inMax = 1f, outMin = 0f, outMax = 1f,
            expr = "0.5"
        )
        val result = BindingEvaluator.evaluate(listOf(broken, fine), mapOf("x" to 1f))
        assertEquals("坏的那条直接跳过", null, result["a"])
        assertEquals(0.5f, result["b"]!!.opacity!!, 1e-6f)
    }

    @Test
    fun `width 与 height 能被解析成对应枚举`() {
        assertEquals(AnimatableProperty.WIDTH, AnimatableProperty.fromJson("width"))
        assertEquals(AnimatableProperty.HEIGHT, AnimatableProperty.fromJson("height"))
        assertEquals("旧属性不受影响", AnimatableProperty.OPACITY, AnimatableProperty.fromJson("opacity"))
    }
}
