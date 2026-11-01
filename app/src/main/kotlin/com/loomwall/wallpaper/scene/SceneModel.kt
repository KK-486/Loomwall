package com.loomwall.wallpaper.scene

/** 归一化 RGBA，各分量取值 0..1。 */
data class RgbaColor(val r: Float, val g: Float, val b: Float, val a: Float) {

    fun modulatedBy(opacity: Float): RgbaColor = copy(a = (a * opacity).coerceIn(0f, 1f))

    companion object {
        /** 解析 #RRGGBB 或 #AARRGGBB。失败抛 [IllegalArgumentException]，由解析器包装成带路径的错误。 */
        fun parseHex(hex: String): RgbaColor {
            val raw = hex.trim().removePrefix("#")
            val argb: Long = when (raw.length) {
                6 -> 0xFF000000L or parseLong(raw)
                8 -> parseLong(raw)
                else -> throw IllegalArgumentException(
                    "颜色必须是 #RRGGBB 或 #AARRGGBB，收到 \"$hex\""
                )
            }
            return RgbaColor(
                r = ((argb shr 16) and 0xFF).toFloat() / 255f,
                g = ((argb shr 8) and 0xFF).toFloat() / 255f,
                b = (argb and 0xFF).toFloat() / 255f,
                a = ((argb shr 24) and 0xFF).toFloat() / 255f
            )
        }

        private fun parseLong(raw: String): Long =
            raw.toLongOrNull(16) ?: throw IllegalArgumentException("颜色含有非十六进制字符：\"#$raw\"")
    }
}

/**
 * 场景节点。v0.1 只有两种节点：根容器与矩形。
 * 新增节点类型时必须同步更新 schema 与 [SceneParser]。
 */
sealed interface SceneNode {
    val id: String
    val visible: Boolean
}

/**
 * 矩形。x / y / width / height 的单位是 **画布宽度的百分比**（画布左上角为原点，y 向下）。
 * 这样同一份预设在任何分辨率与宽高比下都不会被拉伸。
 *
 * rotationZ 与 scale 是阶段 2 新增的**可选**字段（默认 0 度 / 1 倍），
 * 加可选字段不改变 scene 格式版本，旧预设照旧可读。
 */
data class RectNode(
    override val id: String,
    override val visible: Boolean,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: RgbaColor,
    val opacity: Float,
    val rotationZ: Float = 0f,
    val scale: Float = 1f,
    /**
     * 形状类型。缺省矩形，因此老预设（只写 type=rect）语义完全不变。
     * 所有形状都画在同一个包围盒里，x/y/width/height 的语义一致。
     */
    val shape: ShapeKind = ShapeKind.RECT,
    /** polygon / star 的顶点数（3..32）。其他形状忽略。 */
    val sides: Int = 5,
    /** shape = PATH 时的 SVG `d` 属性内容。 */
    val pathData: String = "",
    /** shape = PATH 时的 viewBox，用于把路径归一化到包围盒。 */
    val viewBox: String = "0 0 24 24",
    /**
     * shape = PATH 时的描边宽度（viewBox 单位）。
     * 0 表示填充；> 0 表示只描边——线框图标（fill="none"）必须走这条路径，
     * 否则会把轮廓填成一坨实心块。
     */
    val strokeWidth: Float = 0f,
    /**
     * 渐变填充，空串表示纯色。
     *
     * 语法：`类型:角度:位置#颜色;位置#颜色`
     * - 类型：`linear` / `radial`
     * - 角度：线性渐变的方向（度，0 = 从左到右）；径向渐变忽略
     * - 颜色：`#AARRGGBB`
     *
     * 例：`linear:90:0#FF3366FF;1#FF0B0D10`
     *
     * 为什么用字符串而不是嵌套对象：它是纯展示属性，字符串让解析/写出/schema
     * 三处都只多一行，也不必为停靠点引入新的数据结构。
     */
    val gradient: String = "",
    /**
     * 羽化半径（画布单位）。0 = 不羽化。
     * 边缘模糊是靠"光栅化时把 alpha 模糊掉"实现的，所以有羽化的元素会走贴图管线。
     */
    val feather: Float = 0f,
    /** 圆角半径，占**元素宽度**的百分比（0..50）；仅 shape=rect 生效。 */
    val cornerRadius: Float = 0f,
    /**
     * 蒙版：用另一个形状裁剪本元素，空串 = 不裁剪。
     * 取值是 ShapeKind 的 jsonName（rect / ellipse / triangle / trapezoid / polygon / star）。
     */
    val mask: String = "",
    /** 蒙版为 polygon/star 时的顶点数。 */
    val maskSides: Int = 5,
    /** 蒙版矩形：相对图层左上角的位置与尺寸（百分比，默认铺满 0/0/100/100）。 */
    val maskX: Float = 0f,
    val maskY: Float = 0f,
    val maskW: Float = 100f,
    val maskH: Float = 100f,
    /**
     * 图片蒙版：用该资源的 **alpha 通道** 裁剪本元素（空串 = 不用）。
     * 与 mask（形状蒙版）互斥，maskSrc 优先。
     */
    val maskSrc: String = ""
) : SceneNode

/**
 * 图片节点。
 *
 * 资源来自预设目录：`presets/active/resources/<src>`。
 * 渲染方式和文本一致：Bitmap → GL 纹理 → 走变换管线做位移/旋转/缩放/透明度。
 */
data class ImageNode(
    override val id: String,
    override val visible: Boolean,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** resources 目录下的文件名（导入时由预设包还原）。 */
    val src: String,
    val opacity: Float,
    val rotationZ: Float = 0f,
    val scale: Float = 1f,
    /** 羽化半径（画布单位）。0 = 不羽化；> 0 时纹理生成后会把边缘模糊掉。 */
    val feather: Float = 0f,
    /** 圆角半径，占**元素宽度**的百分比（0..50）；仅 shape=rect 生效。 */
    val cornerRadius: Float = 0f,
    /**
     * 蒙版：用另一个形状裁剪本元素，空串 = 不裁剪。
     * 取值是 ShapeKind 的 jsonName（rect / ellipse / triangle / trapezoid / polygon / star）。
     */
    val mask: String = "",
    /** 蒙版为 polygon/star 时的顶点数。 */
    val maskSides: Int = 5,
    /** 蒙版矩形：相对图层左上角的位置与尺寸（百分比，默认铺满 0/0/100/100）。 */
    val maskX: Float = 0f,
    val maskY: Float = 0f,
    val maskW: Float = 100f,
    val maskH: Float = 100f,
    /**
     * 图片蒙版：用该资源的 **alpha 通道** 裁剪本元素（空串 = 不用）。
     * 与 mask（形状蒙版）互斥，maskSrc 优先。
     */
    val maskSrc: String = ""
) : SceneNode
/**
 * 容器节点，行为类似 HTML 的 `<div>`：把若干元素打包成一组。
 *
 * 两种用法：
 * - **根容器**：`isRoot = true`，没有几何，子节点坐标就是画布坐标；
 * - **嵌套容器**：有 x/y/width/height 与变换，**子节点坐标相对容器左上角**，
 *   容器的位移/缩放/旋转会带着整组走（就像给 div 加 transform）。
 *
 * 不裁剪子元素（overflow 恒为 visible），避免引入裁剪带来的边界情况。
 */
data class ContainerNode(
    override val id: String,
    override val visible: Boolean,
    val background: RgbaColor,
    val children: List<SceneNode>,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val opacity: Float = 1f,
    val rotationZ: Float = 0f,
    val scale: Float = 1f,
    /** 根容器不参与变换，子节点直接用画布坐标。 */
    val isRoot: Boolean = false,
    /** 是否把子元素裁剪在容器框内（等价 CSS 的 overflow: hidden）。 */
    val clip: Boolean = true
) : SceneNode

/** 文本对齐：以节点的 x..x+width 为基准框。 */
enum class TextAlign(val jsonName: String) {
    LEFT("left"), CENTER("center"), RIGHT("right");

    companion object {
        fun fromJson(name: String): TextAlign? = entries.firstOrNull { it.jsonName == name }
    }
}

/**
 * 文本节点（阶段 4 增强）。
 *
 * 渲染方式：用 Android Canvas 把文字画进 Bitmap → 上传成 GL 纹理 → 交给现有的变换管线
 * 做位移/旋转/缩放/透明度。这样字体、换行、对齐、emoji 全部由系统负责，
 * 不需要我们自己写字体光栅化。
 *
 * `text` 支持占位符，例如 `"{sys.time.hour:00}:{sys.time.minute:00}"`，
 * 由 [TextTemplate] 在每次重绘时替换成当前值。
 */
data class TextNode(
    override val id: String,
    override val visible: Boolean,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val text: String,
    /** 字号，单位与坐标一致（画布宽度百分比）。 */
    val fontSize: Float,
    val align: TextAlign,
    val color: RgbaColor,
    val opacity: Float,
    val rotationZ: Float = 0f,
    val scale: Float = 1f,
    /** 羽化带宽度，占元素宽度的百分比（0..50）。 */
    val feather: Float = 0f,
    /** 圆角半径，占元素宽度的百分比（0..50）。 */
    val cornerRadius: Float = 0f,
    /** 形状蒙版（空 = 不裁剪）。 */
    val mask: String = "",
    val maskSides: Int = 5,
    /** 蒙版矩形：相对图层的位置与尺寸（百分比）。 */
    val maskX: Float = 0f,
    val maskY: Float = 0f,
    val maskW: Float = 100f,
    val maskH: Float = 100f,
    /** 图片 alpha 蒙版（资源名，空 = 不用）。 */
    val maskSrc: String = ""
) : SceneNode

data class SceneDocument(
    val formatVersion: String,
    val root: ContainerNode,
    /**
     * 每个节点的视差参数（按节点 id）。用独立映射而不是给每个节点类加字段：
     * 视差对矩形/图片/文字/容器都适用，加字段要改 4 个类，映射只改一处。
     */
    val parallax: Map<String, NodeParallax> = emptyMap(),

    /**
     * 命中穿透的节点 id 集合。
     *
     * 语义：这些节点**不参与点击命中**，但它们的子节点仍然可以命中
     *（遮罩层整体穿透、里面的按钮照常可点）。
     * 用集合而不是给节点加字段，理由同 [parallax]：只改一处。
     */
    val hitThroughIds: Set<String> = emptySet()
) {
    fun rectCount(): Int = root.children.count { it is RectNode }

    /** 递归拍平所有节点（含嵌套容器里的），便于查找。 */
    fun flatten(): List<SceneNode> {
        val out = ArrayList<SceneNode>()
        fun walk(nodes: List<SceneNode>) {
            for (node in nodes) {
                out += node
                if (node is ContainerNode) walk(node.children)
            }
        }
        walk(root.children)
        return out
    }
}

/**
 * 场景格式错误。带 JSON 路径，便于在编辑器里定位到具体字段，
 * 而不是给用户一句无法定位的“解析失败”。
 */
class SceneFormatException(val jsonPath: String, detail: String) :
    Exception("$jsonPath：$detail")
