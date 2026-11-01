package com.loomwall.wallpaper.scene

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 把内存里的 [SceneDocument] 写回 scene.json 文本。
 *
 * 原则：
 * 1. **对称**：[SceneParser] 能读的，[SceneWriter] 必须能写；两边字段一一对应。
 * 2. **可读**：保留缩进，社区用户会用文本编辑器手改预设。
 * 3. **确定**：数值统一保留 1 位小数，避免出现 12.000000000000002 这种噪声。
 *
 * 改字段时三件套同步：EngineVersion / schema / 解析器 + 这里。
 */
object SceneWriter {

    fun toJson(scene: SceneDocument): String {
        val root = JSONObject()
        root.put("id", scene.root.id)
        root.put("type", "container")
        root.put("visible", scene.root.visible)
        root.put("background", scene.root.background.toHex())

        root.put("children", childrenToJson(scene.root.children))

        val doc = JSONObject()
        doc.put("formatVersion", EngineVersion.SCENE_FORMAT)
        doc.put("root", root)
        // 视差参数存在 SceneDocument.parallax 里（不挂在节点模型上），
        // 这里按 id 回填，保证"解析→写出"对称，手机端保存不会丢参数。
        injectParallax(root, scene.parallax)
        injectHitThrough(root, scene.hitThroughIds)
        return doc.toString(2)
    }

    /** 回填 hitThrough:true（解析能读的，写出必须能写，否则保存一次就丢）。 */
    private fun injectHitThrough(root: JSONObject, ids: Set<String>) {
        if (ids.isEmpty()) return
        fun walk(node: JSONObject) {
            if (node.optString("id", "") in ids) node.put("hitThrough", true)
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                (children.opt(i) as? JSONObject)?.let { walk(it) }
            }
        }
        walk(root)
    }

    /** 把 parallax 映射回填到已经生成好的 JSON 树里（按节点 id 匹配）。 */
    private fun injectParallax(root: JSONObject, map: Map<String, NodeParallax>) {
        if (map.isEmpty()) return
        fun walk(node: JSONObject) {
            val id = node.optString("id", "")
            val parallax = map[id]
            if (parallax != null) {
                if (parallax.depth > 0f) node.put("parallax", round1(parallax.depth))
                node.put("parallaxMode", if (parallax.custom) "custom" else "global")
                if (parallax.custom) {
                    parallax.amp?.let { node.put("parallaxAmp", round1(it)) }
                    if (parallax.angleDeg != 0f) node.put("parallaxAngle", round1(parallax.angleDeg))
                    if (!parallax.enabled) node.put("parallaxEnabled", false)
                }
            }
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                (children.opt(i) as? JSONObject)?.let { walk(it) }
            }
        }
        walk(root)
    }

    /** 递归写出子节点列表（容器可以再套容器）。 */
    private fun childrenToJson(nodes: List<SceneNode>): JSONArray {
        val array = JSONArray()
        for (node in nodes) {
            when (node) {
                is RectNode -> array.put(rectToJson(node))
                is TextNode -> array.put(textToJson(node))
                is ImageNode -> array.put(imageToJson(node))
                is ContainerNode -> array.put(containerToJson(node))
            }
        }
        return array
    }

    /** 嵌套容器：几何 + 变换 + 递归的子节点。 */
    private fun containerToJson(node: ContainerNode): JSONObject = JSONObject().apply {
        put("id", node.id)
        put("type", "container")
        if (!node.visible) put("visible", false)
        put("x", round1(node.x))
        put("y", round1(node.y))
        put("width", round1(node.width))
        put("height", round1(node.height))
        if (node.background.a > 0) put("background", node.background.toHex())
        if (node.opacity != 1f) put("opacity", round2(node.opacity))
        if (node.rotationZ != 0f) put("rotationZ", round1(node.rotationZ))
        if (node.scale != 1f) put("scale", round2(node.scale))
        if (!node.clip) put("clip", false)
        put("children", childrenToJson(node.children))
    }
    private fun imageToJson(node: ImageNode): JSONObject = JSONObject().apply {
        put("id", node.id)
        put("type", "image")
        if (!node.visible) put("visible", false)
        put("x", round1(node.x))
        put("y", round1(node.y))
        put("width", round1(node.width))
        put("height", round1(node.height))
        put("src", node.src)
        if (node.opacity != 1f) put("opacity", round2(node.opacity))
        if (node.rotationZ != 0f) put("rotationZ", round1(node.rotationZ))
        if (node.scale != 1f) put("scale", round2(node.scale))
        if (node.feather > 0f) put("feather", round1(node.feather))
        if (node.cornerRadius > 0f) put("cornerRadius", round1(node.cornerRadius))
        if (node.mask.isNotBlank()) {
            put("mask", node.mask)
            if (node.maskSides != 5) put("maskSides", node.maskSides)
        }
        if (node.maskSrc.isNotBlank()) put("maskSrc", node.maskSrc)
        if (node.maskX != 0f) put("maskX", round1(node.maskX))
        if (node.maskY != 0f) put("maskY", round1(node.maskY))
        if (node.maskW != 100f) put("maskW", round1(node.maskW))
        if (node.maskH != 100f) put("maskH", round1(node.maskH))
    }
    private fun textToJson(node: TextNode): JSONObject = JSONObject().apply {
        put("id", node.id)
        put("type", "text")
        if (!node.visible) put("visible", false)
        put("x", round1(node.x))
        put("y", round1(node.y))
        put("width", round1(node.width))
        put("height", round1(node.height))
        put("text", node.text)
        put("fontSize", round1(node.fontSize))
        if (node.align != TextAlign.LEFT) put("align", node.align.jsonName)
        put("color", node.color.toHex())
        if (node.opacity != 1f) put("opacity", round2(node.opacity))
        if (node.rotationZ != 0f) put("rotationZ", round1(node.rotationZ))
        if (node.scale != 1f) put("scale", round2(node.scale))
        if (node.feather > 0f) put("feather", round1(node.feather))
        if (node.cornerRadius > 0f) put("cornerRadius", round1(node.cornerRadius))
        if (node.mask.isNotBlank()) {
            put("mask", node.mask)
            if (node.maskSides != 5) put("maskSides", node.maskSides)
        }
        if (node.maskSrc.isNotBlank()) put("maskSrc", node.maskSrc)
        if (node.maskX != 0f) put("maskX", round1(node.maskX))
        if (node.maskY != 0f) put("maskY", round1(node.maskY))
        if (node.maskW != 100f) put("maskW", round1(node.maskW))
        if (node.maskH != 100f) put("maskH", round1(node.maskH))
    }
    private fun rectToJson(rect: RectNode): JSONObject = JSONObject().apply {
        put("id", rect.id)
        put("type", "rect")
        put("visible", rect.visible)
        put("x", round1(rect.x))
        put("y", round1(rect.y))
        put("width", round1(rect.width))
        put("height", round1(rect.height))
        put("color", rect.color.toHex())
        put("opacity", round2(rect.opacity))
        // 只在非默认值时写出，保持旧预设的 JSON 干净。
        if (rect.rotationZ != 0f) put("rotationZ", round1(rect.rotationZ))
        if (rect.scale != 1f) put("scale", round2(rect.scale))
        if (rect.shape != ShapeKind.RECT) put("shape", rect.shape.jsonName)
        if (rect.shape == ShapeKind.POLYGON || rect.shape == ShapeKind.STAR) put("sides", rect.sides)
        if (rect.shape == ShapeKind.PATH) {
            put("path", rect.pathData)
            if (rect.viewBox != "0 0 24 24") put("viewBox", rect.viewBox)
            if (rect.strokeWidth > 0f) put("strokeWidth", round1(rect.strokeWidth))
            if (rect.gradient.isNotBlank()) put("gradient", rect.gradient)
            if (rect.feather > 0f) put("feather", round1(rect.feather))
            if (rect.cornerRadius > 0f) put("cornerRadius", round1(rect.cornerRadius))
            if (rect.mask.isNotBlank()) {
                put("mask", rect.mask)
                if (rect.maskSides != 5) put("maskSides", rect.maskSides)
            }
            if (rect.maskSrc.isNotBlank()) put("maskSrc", rect.maskSrc)
            if (rect.maskX != 0f) put("maskX", round1(rect.maskX))
            if (rect.maskY != 0f) put("maskY", round1(rect.maskY))
            if (rect.maskW != 100f) put("maskW", round1(rect.maskW))
            if (rect.maskH != 100f) put("maskH", round1(rect.maskH))
        }
    }

    private fun round1(value: Float): Double = (value * 10f).roundToInt() / 10.0

    private fun round2(value: Float): Double = (value * 100f).roundToInt() / 100.0
}

/**
 * 统一写成 #AARRGGBB，避免 #RRGGBB 与 #AARRGGBB 混用导致读回时语义漂移。
 * 顶格声明，属性面板等 UI 也能直接用。
 */
fun RgbaColor.toHex(): String = String.format(
    Locale.US,
    "#%02X%02X%02X%02X",
    (a.coerceIn(0f, 1f) * 255f).roundToInt(),
    (r.coerceIn(0f, 1f) * 255f).roundToInt(),
    (g.coerceIn(0f, 1f) * 255f).roundToInt(),
    (b.coerceIn(0f, 1f) * 255f).roundToInt()
)
