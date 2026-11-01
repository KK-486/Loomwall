package com.loomwall.wallpaper.scene

import org.json.JSONException
import org.json.JSONObject

/**
 * scene.json 解析器（格式 v0.1）。
 *
 * 设计原则：
 * 1. **不静默失败**。任何结构问题都抛 [SceneFormatException]，并带上 JSON 路径，
 *    便于以后编辑器直接把用户定位到出错字段。
 * 2. **严格但容错**。未知字段忽略（向前兼容），未知节点类型报错（避免预设“看起来能用其实没渲染”）。
 * 3. 与 `schema/scene.schema.json` 一一对应。改这里必须改 schema，反之亦然。
 */
object SceneParser {

    fun parse(json: String): SceneDocument {
        val rootJson = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw SceneFormatException("/", "不是合法的 JSON 对象：${e.message}")
        }

        val formatVersion = requireString(rootJson, "formatVersion", "")
        if (formatVersion !in EngineVersion.ACCEPTED_SCENE_FORMATS) {
            throw SceneFormatException(
                "/formatVersion",
                "不支持的场景格式 \"$formatVersion\"；本引擎支持 " +
                    EngineVersion.ACCEPTED_SCENE_FORMATS.joinToString("、") { "\"$it\"" }
            )
        }

        val rootObject = rootJson.opt("root") as? JSONObject
            ?: throw SceneFormatException("/root", "缺少根容器对象")

        val ids = mutableSetOf<String>()
        val container = parseContainer(rootObject, "/root", defaultId = "root", ids = ids, isRoot = true)
        return SceneDocument(formatVersion, container, collectParallax(rootJson), collectHitThrough(rootJson))
    }

    /**
     * 收集每个节点的视差参数。
     *
     * 为什么单独走一遍 JSON 而不是在 parseRect/parseImage 里顺手读：
     * 视差对所有节点类型（含容器）都适用，逐类改会造成 4 处重复；
     * 而且"没有视差字段"的节点根本不该产生任何额外开销 —— 这里只收非 null 的。
     */
    /** 收集 hitThrough:true 的节点 id。与 parallax 同样走原始 JSON 递归。 */
    private fun collectHitThrough(root: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(node: JSONObject) {
            if (node.optBoolean("hitThrough", false)) {
                val id = node.optString("id", "")
                if (id.isNotBlank()) out.add(id)
            }
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                (children.opt(i) as? JSONObject)?.let { walk(it) }
            }
        }
        (root.optJSONObject("root") as? JSONObject)?.let { walk(it) }
        return out
    }

    private fun collectParallax(root: JSONObject): Map<String, NodeParallax> {
        val out = HashMap<String, NodeParallax>()
        fun walk(node: JSONObject) {
            val id = node.optString("id", "")
            if (id.isNotBlank()) {
                NodeParallax.fromJson(node)?.let { out[id] = it }
            }
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                (children.opt(i) as? JSONObject)?.let { walk(it) }
            }
        }
        (root.optJSONObject("root") as? JSONObject)?.let { walk(it) }
        return out
    }

    private fun parseContainer(
        json: JSONObject,
        path: String,
        defaultId: String,
        ids: MutableSet<String>,
        isRoot: Boolean
    ): ContainerNode {
        val id = optionalString(json, "id", defaultId, path)
        if (!ids.add(id)) throw SceneFormatException("$path/id", "id 重复：\"$id\"")

        val visible = optionalBoolean(json, "visible", true, path)
        val background = parseColorOrNull(json, "background", path) ?: DEFAULT_BACKGROUND

        val childrenJson = json.opt("children") as? org.json.JSONArray
            ?: throw SceneFormatException("$path/children", "缺少 children 数组")

        val children = ArrayList<SceneNode>(childrenJson.length())
        for (i in 0 until childrenJson.length()) {
            val item = childrenJson.opt(i) as? JSONObject
                ?: throw SceneFormatException("$path/children[$i]", "必须是 JSON 对象")
            children += parseNode(item, "$path/children[$i]", ids)
        }

        // 根容器没有几何（子节点就是画布坐标）；嵌套容器必须有位置和尺寸，
        // 它的子节点坐标**相对容器左上角**，这样整组一起移动/缩放/旋转。
        if (isRoot) {
            return ContainerNode(
                id = id,
                visible = visible,
                background = background,
                children = children,
                isRoot = true
            )
        }

        val x = requireNumber(json, "x", path)
        val y = requireNumber(json, "y", path)
        val width = requireNumber(json, "width", path)
        val height = requireNumber(json, "height", path)
        if (width <= 0.0) throw SceneFormatException("$path/width", "必须大于 0，收到 $width")
        if (height <= 0.0) throw SceneFormatException("$path/height", "必须大于 0，收到 $height")

        val opacity = requireNumber(json, "opacity", path, required = false, default = 1.0)
        if (opacity < 0.0 || opacity > 1.0) {
            throw SceneFormatException("$path/opacity", "必须在 0..1 之间，收到 $opacity")
        }
        val rotationZ = requireNumber(json, "rotationZ", path, required = false, default = 0.0).toFloat()
        val scale = requireNumber(json, "scale", path, required = false, default = 1.0).toFloat()
        if (scale < 0.0) throw SceneFormatException("$path/scale", "不能为负数，收到 $scale")
        val clip = optionalBoolean(json, "clip", true, path)

        return ContainerNode(
            id = id,
            visible = visible,
            background = background,
            children = children,
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
            opacity = opacity.toFloat(),
            rotationZ = rotationZ,
            scale = scale,
            isRoot = false,
            clip = clip
        )
    }

    private fun parseNode(json: JSONObject, path: String, ids: MutableSet<String>): SceneNode {
        val type = requireString(json, "type", path)
        return when (type) {
            "rect" -> parseRect(json, path, ids)
            "text" -> parseText(json, path, ids)
            "image" -> parseImage(json, path, ids)
            "container" -> parseContainer(
                json = json,
                path = path,
                defaultId = "group",
                ids = ids,
                isRoot = false
            )
            else -> throw SceneFormatException(
                "$path/type",
                "未知的节点类型 \"$type\"（当前支持：rect / text / image）"
            )
        }
    }

    private fun parseImage(json: JSONObject, path: String, ids: MutableSet<String>): ImageNode {
        val id = requireString(json, "id", path)
        if (id.isBlank()) throw SceneFormatException("$path/id", "不能为空字符串")
        if (!ids.add(id)) throw SceneFormatException("$path/id", "id 重复：\"$id\"")

        val x = requireNumber(json, "x", path)
        val y = requireNumber(json, "y", path)
        val width = requireNumber(json, "width", path)
        val height = requireNumber(json, "height", path)
        if (width <= 0.0) throw SceneFormatException("$path/width", "必须大于 0，收到 $width")
        if (height <= 0.0) throw SceneFormatException("$path/height", "必须大于 0，收到 $height")

        val src = requireString(json, "src", path)
        if (src.isBlank()) throw SceneFormatException("$path/src", "不能为空")

        val opacityRaw = requireNumber(json, "opacity", path, required = false, default = 1.0)
        if (opacityRaw < 0.0 || opacityRaw > 1.0) {
            throw SceneFormatException("$path/opacity", "必须在 0..1 之间，收到 $opacityRaw")
        }
        val visible = optionalBoolean(json, "visible", true, path)
        val rotationZ = requireNumber(json, "rotationZ", path, required = false, default = 0.0).toFloat()
        val scale = requireNumber(json, "scale", path, required = false, default = 1.0).toFloat()
        if (scale < 0.0) throw SceneFormatException("$path/scale", "不能为负数，收到 $scale")

        val imageCornerRadius = requireNumber(json, "cornerRadius", path, required = false, default = 0.0).toFloat()
        if (imageCornerRadius < 0f || imageCornerRadius > 50f) {
            throw SceneFormatException("$path/cornerRadius", "必须在 0..50 之间，收到 $imageCornerRadius")
        }
        val imageMask = optionalString(json, "mask", "", path)
        if (imageMask.isNotBlank() && ShapeKind.fromJson(imageMask) == null) {
            throw SceneFormatException("$path/mask", "未知蒙版形状 \"$imageMask\"")
        }
        val imageMaskSides = requireNumber(json, "maskSides", path, required = false, default = 5.0).toInt().coerceIn(3, 32)
        val imageMaskSrc = optionalString(json, "maskSrc", "", path)
        val imageMaskX = requireNumber(json, "maskX", path, required = false, default = 0.0).toFloat()
        val imageMaskY = requireNumber(json, "maskY", path, required = false, default = 0.0).toFloat()
        val imageMaskW = requireNumber(json, "maskW", path, required = false, default = 100.0).toFloat()
        val imageMaskH = requireNumber(json, "maskH", path, required = false, default = 100.0).toFloat()
        val imageFeather = requireNumber(json, "feather", path, required = false, default = 0.0).toFloat()
        if (imageFeather < 0f) throw SceneFormatException("$path/feather", "不能为负数，收到 $imageFeather")

        return ImageNode(
            id = id,
            visible = visible,
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
            src = src,
            feather = imageFeather,
            cornerRadius = imageCornerRadius,
            mask = imageMask,
            maskSides = imageMaskSides,
            maskSrc = imageMaskSrc, maskX = imageMaskX, maskY = imageMaskY, maskW = imageMaskW, maskH = imageMaskH,
            opacity = opacityRaw.toFloat(),
            rotationZ = rotationZ,
            scale = scale
        )
    }
    private fun parseText(json: JSONObject, path: String, ids: MutableSet<String>): TextNode {
        val id = requireString(json, "id", path)
        if (id.isBlank()) throw SceneFormatException("$path/id", "不能为空字符串")
        if (!ids.add(id)) throw SceneFormatException("$path/id", "id 重复：\"$id\"")

        val x = requireNumber(json, "x", path)
        val y = requireNumber(json, "y", path)
        val width = requireNumber(json, "width", path)
        val height = requireNumber(json, "height", path)
        if (width <= 0.0) throw SceneFormatException("$path/width", "必须大于 0，收到 $width")
        if (height <= 0.0) throw SceneFormatException("$path/height", "必须大于 0，收到 $height")

        val text = requireString(json, "text", path)
        val fontSize = requireNumber(json, "fontSize", path)
        if (fontSize <= 0.0) {
            throw SceneFormatException("$path/fontSize", "必须大于 0，收到 $fontSize")
        }

        val color = parseColorOrNull(json, "color", path)
            ?: throw SceneFormatException("$path/color", "缺少必填字段")
        val opacityRaw = requireNumber(json, "opacity", path, required = false, default = 1.0)
        if (opacityRaw < 0.0 || opacityRaw > 1.0) {
            throw SceneFormatException("$path/opacity", "必须在 0..1 之间，收到 $opacityRaw")
        }

        val alignName = optionalString(json, "align", "left", path)
        val align = TextAlign.fromJson(alignName)
            ?: throw SceneFormatException("$path/align", "未知对齐 \"$alignName\"（left/center/right）")

        val visible = optionalBoolean(json, "visible", true, path)
        val rotationZ = requireNumber(json, "rotationZ", path, required = false, default = 0.0).toFloat()
        val scale = requireNumber(json, "scale", path, required = false, default = 1.0).toFloat()
        if (scale < 0.0) throw SceneFormatException("$path/scale", "不能为负数，收到 $scale")

        val textFeather = requireNumber(json, "feather", path, required = false, default = 0.0).toFloat()
        val textCorner = requireNumber(json, "cornerRadius", path, required = false, default = 0.0).toFloat()
        val textMask = optionalString(json, "mask", "", path)
        if (textMask.isNotBlank() && ShapeKind.fromJson(textMask) == null) {
            throw SceneFormatException("$path/mask", "未知蒙版形状 \"$textMask\"")
        }
        val textMaskSides = requireNumber(json, "maskSides", path, required = false, default = 5.0).toInt().coerceIn(3, 32)
        val textMaskSrc = optionalString(json, "maskSrc", "", path)
        val textMaskX = requireNumber(json, "maskX", path, required = false, default = 0.0).toFloat()
        val textMaskY = requireNumber(json, "maskY", path, required = false, default = 0.0).toFloat()
        val textMaskW = requireNumber(json, "maskW", path, required = false, default = 100.0).toFloat()
        val textMaskH = requireNumber(json, "maskH", path, required = false, default = 100.0).toFloat()

        return TextNode(
            id = id,
            visible = visible,
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
            text = text,
            fontSize = fontSize.toFloat(),
            align = align,
            color = color,
            opacity = opacityRaw.toFloat(),
            rotationZ = rotationZ,
            scale = scale,
            feather = textFeather,
            cornerRadius = textCorner,
            mask = textMask,
            maskSides = textMaskSides,
            maskSrc = textMaskSrc, maskX = textMaskX, maskY = textMaskY, maskW = textMaskW, maskH = textMaskH
        )
    }

    private fun parseRect(json: JSONObject, path: String, ids: MutableSet<String>): RectNode {
        val id = requireString(json, "id", path)
        if (id.isBlank()) throw SceneFormatException("$path/id", "不能为空字符串")
        if (!ids.add(id)) throw SceneFormatException("$path/id", "id 重复：\"$id\"")

        val x = requireNumber(json, "x", path)
        val y = requireNumber(json, "y", path)
        val width = requireNumber(json, "width", path)
        val height = requireNumber(json, "height", path)

        if (width <= 0.0) throw SceneFormatException("$path/width", "必须大于 0，收到 $width")
        if (height <= 0.0) throw SceneFormatException("$path/height", "必须大于 0，收到 $height")

        val color = parseColorOrNull(json, "color", path)
            ?: throw SceneFormatException("$path/color", "缺少必填字段")

        val opacityRaw = requireNumber(json, "opacity", path, required = false, default = 1.0)
        if (opacityRaw < 0.0 || opacityRaw > 1.0) {
            throw SceneFormatException("$path/opacity", "必须在 0..1 之间，收到 $opacityRaw")
        }

        val visible = optionalBoolean(json, "visible", true, path)

        // 阶段 2 新增的可选字段：不写就是 0 度 / 1 倍，旧预设不受影响。
        val rotationZ = requireNumber(json, "rotationZ", path, required = false, default = 0.0).toFloat()
        val scale = requireNumber(json, "scale", path, required = false, default = 1.0)
        // 允许 0：网页端缩放滑条可以拉到 0%（元素不可见）。
        // 渲染器内部会夹到 0.01 以避免退化矩阵。
        if (scale < 0.0) {
            throw SceneFormatException("$path/scale", "不能为负数，收到 $scale")
        }

        // 形状：缺省矩形，老预设（只写 type=rect）语义完全不变。
        val shapeName = optionalString(json, "shape", "rect", path)
        val shape = ShapeKind.fromJson(shapeName)
            ?: throw SceneFormatException(
                "$path/shape",
                "未知形状 \"$shapeName\"（可用：${ShapeKind.entries.joinToString(" / ") { it.jsonName }}）"
            )
        val pathData = optionalString(json, "path", "", path)
        val feather = requireNumber(json, "feather", path, required = false, default = 0.0).toFloat()
        if (feather < 0f) throw SceneFormatException("$path/feather", "不能为负数，收到 $feather")
        val gradient = optionalString(json, "gradient", "", path)
        val cornerRadius = requireNumber(json, "cornerRadius", path, required = false, default = 0.0).toFloat()
        if (cornerRadius < 0f || cornerRadius > 50f) {
            throw SceneFormatException("$path/cornerRadius", "必须在 0..50 之间，收到 $cornerRadius")
        }
        val mask = optionalString(json, "mask", "", path)
        if (mask.isNotBlank() && ShapeKind.fromJson(mask) == null) {
            throw SceneFormatException(
                "$path/mask",
                "未知蒙版形状 \"$mask\"（可用：${ShapeKind.entries.joinToString(" / ") { it.jsonName }}）"
            )
        }
        val maskSides = requireNumber(json, "maskSides", path, required = false, default = 5.0).toInt().coerceIn(3, 32)
        val maskSrc = optionalString(json, "maskSrc", "", path)
        val maskX = requireNumber(json, "maskX", path, required = false, default = 0.0).toFloat()
        val maskY = requireNumber(json, "maskY", path, required = false, default = 0.0).toFloat()
        val maskW = requireNumber(json, "maskW", path, required = false, default = 100.0).toFloat()
        val maskH = requireNumber(json, "maskH", path, required = false, default = 100.0).toFloat()
        if (gradient.isNotBlank() && !gradient.contains("#")) {
            throw SceneFormatException("$path/gradient", "格式应为 类型:角度:位置#颜色;位置#颜色，收到 \"$gradient\"")
        }
        val strokeWidth = requireNumber(json, "strokeWidth", path, required = false, default = 0.0).toFloat()
        if (strokeWidth < 0f) {
            throw SceneFormatException("$path/strokeWidth", "不能为负数，收到 $strokeWidth")
        }
        val viewBox = optionalString(json, "viewBox", "0 0 24 24", path)
        if (shape == ShapeKind.PATH) {
            if (pathData.isBlank()) {
                throw SceneFormatException("$path/path", "shape 为 path 时必须提供 SVG 路径数据（d 属性）")
            }
            val view = parseViewBox(viewBox)
            val parsed = SvgPathData.parse(pathData, view[2], view[3])
            if (parsed.isEmpty) {
                throw SceneFormatException("$path/path", "SVG 路径解析后没有任何轮廓，检查 d 属性是否完整")
            }
        }

        val sidesRaw = requireNumber(json, "sides", path, required = false, default = 5.0)
        val sides = sidesRaw.toInt()
        if (sides < 3 || sides > 32) {
            throw SceneFormatException("$path/sides", "必须在 3..32 之间，收到 $sides")
        }

        return RectNode(
            id = id,
            visible = visible,
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
            color = color,
            opacity = opacityRaw.toFloat(),
            rotationZ = rotationZ,
            scale = scale.toFloat(),
            shape = shape,
            sides = sides,
            pathData = pathData,
            viewBox = viewBox,
            strokeWidth = strokeWidth,
            gradient = gradient,
            feather = feather,
            cornerRadius = cornerRadius,
            mask = mask,
            maskSides = maskSides,
            maskSrc = maskSrc, maskX = maskX, maskY = maskY, maskW = maskW, maskH = maskH
        )
    }

    /** 解析 viewBox="minX minY width height"，缺省或非法时返回 0 0 24 24。 */
    private fun parseViewBox(text: String): FloatArray {
        val parts = text.trim().split(Regex("[\\s,]+"))
            .mapNotNull { it.toFloatOrNull() }
        return if (parts.size == 4 && parts[2] > 0f && parts[3] > 0f) {
            floatArrayOf(parts[0], parts[1], parts[2], parts[3])
        } else {
            floatArrayOf(0f, 0f, 24f, 24f)
        }
    }

    // ---- 字段读取：所有类型错误都转成带路径的 SceneFormatException ----

    private fun requireString(json: JSONObject, key: String, path: String): String {
        if (!json.has(key) || json.isNull(key)) {
            throw SceneFormatException("$path/$key", "缺少必填字段")
        }
        return json.opt(key) as? String
            ?: throw SceneFormatException("$path/$key", "必须是字符串")
    }

    private fun optionalString(json: JSONObject, key: String, default: String, path: String): String {
        if (!json.has(key) || json.isNull(key)) return default
        return json.opt(key) as? String
            ?: throw SceneFormatException("$path/$key", "必须是字符串")
    }

    private fun optionalBoolean(json: JSONObject, key: String, default: Boolean, path: String): Boolean {
        if (!json.has(key) || json.isNull(key)) return default
        return json.opt(key) as? Boolean
            ?: throw SceneFormatException("$path/$key", "必须是 true / false")
    }

    private fun requireNumber(
        json: JSONObject,
        key: String,
        path: String,
        required: Boolean = true,
        default: Double = 0.0
    ): Double {
        if (!json.has(key) || json.isNull(key)) {
            if (required) throw SceneFormatException("$path/$key", "缺少必填字段")
            return default
        }
        val value = (json.opt(key) as? Number)?.toDouble()
            ?: throw SceneFormatException("$path/$key", "必须是数字")
        if (value.isNaN() || value.isInfinite()) {
            throw SceneFormatException("$path/$key", "必须是有限数字")
        }
        return value
    }

    private fun parseColorOrNull(json: JSONObject, key: String, path: String): RgbaColor? {
        if (!json.has(key) || json.isNull(key)) return null
        val raw = json.opt(key) as? String
            ?: throw SceneFormatException(
                "$path/$key",
                "必须是 \"#RRGGBB\" 或 \"#AARRGGBB\" 形式的字符串"
            )
        return try {
            RgbaColor.parseHex(raw)
        } catch (e: IllegalArgumentException) {
            throw SceneFormatException("$path/$key", e.message ?: "颜色格式错误")
        }
    }

    private val DEFAULT_BACKGROUND = RgbaColor.parseHex("#FF0B0D10")
}
