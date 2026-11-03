package com.loomwall.wallpaper.engine

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.loomwall.wallpaper.scene.RectNode
import com.loomwall.wallpaper.scene.NodeOverrides
import com.loomwall.wallpaper.scene.RgbaColor
import com.loomwall.wallpaper.scene.ContainerNode
import com.loomwall.wallpaper.scene.NodeParallax
import com.loomwall.wallpaper.scene.ParallaxSettings
import com.loomwall.wallpaper.scene.resolveParallax
import com.loomwall.wallpaper.scene.SceneDocument
import com.loomwall.wallpaper.scene.SceneNode
import com.loomwall.wallpaper.scene.Shapes
import com.loomwall.wallpaper.scene.SvgPathData
import com.loomwall.wallpaper.scene.ShapeKind
import com.loomwall.wallpaper.scene.ImageNode
import com.loomwall.wallpaper.scene.TextNode
import com.loomwall.wallpaper.scene.TextTemplate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GLES 2.0 场景渲染器（格式 v0.1：背景 + 若干矩形）。
 *
 * 坐标系约定（与 schema 文档一致）：
 * - 原点在左上角，x 向右、y 向下；
 * - 所有长度单位是 **画布宽度的百分比**，所以 x ∈ [0,100]，y ∈ [0, canvasHeightUnits]；
 * - canvasHeightUnits = 100 * 屏幕高 / 屏幕宽，保证任何分辨率下矩形都不会被拉伸。
 *
 * 同一个渲染器同时服务两处：
 * - 壁纸引擎（[RenderThread]，相机固定为整幅画布）；
 * - 编辑器画布（[com.loomwall.wallpaper.editor.EditorCanvasView]，可缩放/平移并有选中框）。
 *
 * 线程约束：只在持有 GL 上下文的线程调用，且必须已 makeCurrent。
 */
internal class SceneRenderer {

    private var program = 0
    private var aPosition = -1
    private var uOrtho = -1
    private var uColor = -1

    // 纹理程序（文本用）：Canvas 画字 → 纹理 → 贴图
    private var textProgram = 0
    private var aTextPosition = -1
    private var aTexCoord = -1
    private var uTextOrtho = -1
    private var uTexture = -1
    private var uTextAlpha = -1

    private val textVertexData = FloatArray(8)
    private val texCoordData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private val textVertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(VERTEX_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private val texCoordBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(VERTEX_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private val textCache = TextTextureCache()
    private val imageCache = ImageTextureCache()
    private val pathCache = PathTextureCache()

    /** 预设资源目录（`presets/active/resources`），由 RenderThread 在加载场景时设置。 */
    var resourcesDir: java.io.File? = null

    /** 触摸事件切换过的可见性（以渲染器为准，避免影响场景文档本身）。 */
    /**
     * 运行期可见性覆盖（三态）：null 表示跟随场景，true 强制显示，false 强制隐藏。
     *
     * 为什么不能只维护"隐藏集合"：场景里 visible:false 的节点，规则要能把它**显示出来**。
     * 只用隐藏集合时，setVisible(true) 只是"不再隐藏"，节点自身的 false 仍然生效 ——
     * 于是"能隐藏、不能显示"，用户看到的就是"点了没反应"。
     */
    private val visibilityOverride = HashMap<String, Boolean>()

    private val ortho = FloatArray(16)
    private val vertexData = FloatArray(MAX_SHAPE_FLOATS)
    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(VERTEX_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private var canvasHeightUnits = 100f
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // 相机：画布单位下的平移与缩放。壁纸永远是 (0, 0, 1)。
    private var cameraPanX = 0f
    private var cameraPanY = 0f
    private var cameraZoom = 1f

    var isReady: Boolean = false
        private set

    fun onSurfaceCreated(width: Int, height: Int) {
        if (program == 0) {
            program = buildProgram()
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            uOrtho = GLES20.glGetUniformLocation(program, "uOrtho")
            uColor = GLES20.glGetUniformLocation(program, "uColor")
            if (aPosition < 0 || uOrtho < 0 || uColor < 0) {
                throw IllegalStateException("着色器变量未正确绑定：aPosition=$aPosition uOrtho=$uOrtho uColor=$uColor")
            }
            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            isReady = true
        }
        if (textProgram == 0) {
            textProgram = buildTextProgram()
            aTextPosition = GLES20.glGetAttribLocation(textProgram, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(textProgram, "aTexCoord")
            uTextOrtho = GLES20.glGetUniformLocation(textProgram, "uOrtho")
            uTexture = GLES20.glGetUniformLocation(textProgram, "uTexture")
            uTextAlpha = GLES20.glGetUniformLocation(textProgram, "uAlpha")
            if (aTextPosition < 0 || aTexCoord < 0 || uTextOrtho < 0 || uTexture < 0 || uTextAlpha < 0) {
                throw IllegalStateException(
                    "文本着色器变量未绑定：aPosition=$aTextPosition aTexCoord=$aTexCoord " +
                        "uOrtho=$uTextOrtho uTexture=$uTexture uAlpha=$uTextAlpha"
                )
            }
            texCoordBuffer.position(0)
            texCoordBuffer.put(texCoordData)
            texCoordBuffer.position(0)
        }
        onSurfaceChanged(width, height)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        canvasHeightUnits = if (width > 0) 100f * height.toFloat() / width.toFloat() else 100f
        updateProjection()
    }

    /** 画布单位下的相机：left/top 是可见区域左上角在画布中的坐标，zoom 为缩放倍数。 */
    fun setCamera(left: Float, top: Float, zoom: Float) {
        cameraPanX = left
        cameraPanY = top
        cameraZoom = if (zoom <= 0f) 1f else zoom
        updateProjection()
    }

    fun resetCamera() {
        setCamera(0f, 0f, 1f)
    }

    val canvasHeight: Float
        get() = canvasHeightUnits

    val zoom: Float
        get() = cameraZoom

    fun draw(
        scene: SceneDocument?,
        selectedId: String? = null,
        overrides: Map<String, NodeOverrides> = emptyMap(),
        /** 系统变量快照：文本里的 {占位符} 用它替换。 */
        vars: Map<String, Float> = emptyMap(),
        /** 文本型变量（歌曲名等）。 */
        texts: Map<String, String> = emptyMap(),
        /** 全局视差设置（manifest.parallax）。 */
        parallaxSettings: ParallaxSettings = ParallaxSettings.DEFAULT,
        /** 当前倾斜向量，-1..1；视差关掉时传 (0,0)。 */
        tilt: FloatArray = ZERO_TILT
    ) {
        if (!isReady) return

        val background = scene?.root?.background ?: FALLBACK_BACKGROUND
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(background.r, background.g, background.b, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (scene == null || !scene.root.visible) return
        // 记下当前根节点：toggleVisible 需要知道节点的原始可见性
        rootNodeForSearch = scene.root

        // 视差上下文：这一帧所有节点共用的倾斜量与全局设置
        frameTiltX = tilt.getOrElse(0) { 0f }
        frameTiltY = tilt.getOrElse(1) { 0f }
        frameParallax = parallaxSettings
        frameParallaxMap = scene.parallax

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // 清掉上一帧可能残留的裁剪区
        scissorStack.clear()
        failedNodes.clear()
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        // 递归绘制：容器把自己的变换乘进矩阵，子节点因此跟着整组移动/缩放/旋转。
        drawNode(scene.root, Mat2D.IDENTITY, overrides, vars, texts)

        // 选中框最后画，避免被后续元素盖住。
        if (selectedId != null) {
            val selected = findNode(scene.root, selectedId)
            val world = worldMatrixOf(scene.root, selectedId, overrides)
            if (selected != null && world != null) {
                useSolidProgram()
                // 选中框贴着真实形状：选圆形/星形时框也是圆/星
                val shape = if (selected is RectNode) selected.shape else ShapeKind.RECT
                val sides = if (selected is RectNode) selected.sides else 4
                val selOverride = overrides[selectedId]
                val selW = effWidth(selected, selOverride)
                val selH = effHeight(selected, selOverride)
                val points = if (selected is RectNode) {
                    Shapes.effectivePoints(
                        selected.shape, selected.sides, selected.cornerRadius,
                        selected.mask, selected.maskSides,
                        selW, selH,
                        selected.maskX, selected.maskY, selected.maskW, selected.maskH
                    )
                } else {
                    Shapes.points(shape, sides, selW, selH)
                }
                val loopCount = stageLoop(world, points)
                GLES20.glUniform4f(uColor, 1f, 0.86f, 0.3f, 0.95f)
                GLES20.glLineWidth(3f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, loopCount)
                GLES20.glLineWidth(1f)
            }
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ---- 节点树遍历 ----

    /**
     * 绘制一个节点，**任何单节点异常都不允许让整帧黑屏**。
     *
     * 为什么需要这层：坏素材（尺寸异常、内存不足、参数自相矛盾）之前会直接把
     * 整帧 throw 掉，画面全黑、日志只有一行堆栈，极难定位。现在改成跳过该节点
     * 并记一次日志，其它节点照常绘制。
     */
    private fun drawNode(
        node: SceneNode,
        parent: Mat2D,
        overrides: Map<String, NodeOverrides>,
        vars: Map<String, Float>,
        texts: Map<String, String>
    ) {
        val scissorDepth = scissorStack.size
        try {
            drawNodeInner(node, parent, overrides, vars, texts)
        } catch (e: Throwable) {
            // 裁剪栈必须恢复到进入时的深度，否则后续节点的裁剪区域全错
            while (scissorStack.size > scissorDepth) popScissor()
            if (failedNodes.add(node.id)) {
                Log.e(TAG, "节点绘制失败，已跳过：${node.id}", e)
            }
        }
    }

    /**
     * 世界矩阵 = 父矩阵 → 视差位移 → 节点自身变换。
     *
     * 视差放在节点自身变换**之前**：这样位移是在父坐标系里平移，
     * 不受该节点自己的缩放/旋转影响，和网页编辑器的绘制顺序一致。
     * 位移单位是画布单位（与 x/y 同一套），所以编辑器里看到的幅度=手机上的幅度。
     */
    private fun withParallax(parent: Mat2D, node: SceneNode, override: NodeOverrides?): Mat2D {
        val layer = frameParallaxMap[node.id]
        val parallaxMoved = if (frameTiltX != 0f || frameTiltY != 0f) {
            val resolved = resolveParallax(layer, frameParallax)
            val off = resolved.offsetUnits(frameTiltX, frameTiltY)
            if (off[0] != 0f || off[1] != 0f) parent.then(Mat2D.translate(off[0], off[1])) else parent
        } else {
            parent
        }
        return parallaxMoved.then(nodeMatrix(node, override))
    }

    private fun drawNodeInner(
        node: SceneNode,
        parent: Mat2D,
        overrides: Map<String, NodeOverrides>,
        vars: Map<String, Float>,
        texts: Map<String, String>
    ) {
        if (!effectivelyVisible(node)) return
        val override = overrides[node.id]

        when (node) {
            is ContainerNode -> {
                // 根容器本身没有变换，子节点直接就是画布坐标
                val world = if (node.isRoot) parent else withParallax(parent, node, override)
                if (!node.isRoot && node.background.a > 0f) {
                    useSolidProgram()
                    val alpha = effectiveAlpha(node.opacity, override?.opacity)
                    val c = node.background.modulatedBy(alpha)
                    val boxW = effWidth(node, override)
                    val boxH = effHeight(node, override)
                    val points = Shapes.points(ShapeKind.RECT, 4, boxW, boxH)
                    val fanCount = stageFan(world, points, boxW / 2f, boxH / 2f)
                    GLES20.glUniform4f(uColor, c.r, c.g, c.b, c.a)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, fanCount)
                }
                // 裁剪子元素：等价 CSS 的 overflow: hidden。clip=false 可关掉。
                val clipped = !node.isRoot && node.clip
                if (clipped) pushScissor(world, effWidth(node, override), effHeight(node, override))
                for (child in node.children) drawNode(child, world, overrides, vars, texts)
                if (clipped) popScissor()
            }

            is RectNode -> {
                if (node.shape == ShapeKind.PATH || node.gradient.isNotBlank() || node.feather > 0f || node.cornerRadius > 0f || node.mask.isNotBlank()) {
                    // SVG 路径可能有多条轮廓、带镂空，交给光栅化贴图（Canvas 的 even-odd 填充）。
                    // 三角扇画不了这种形状，硬画只会得到包围盒方块。
                    val texture = pathCache.textureFor(node, surfaceWidth)
                    if (texture != null) {
                        useTextProgram()
                        stageTexturedQuad(
                            withParallax(parent, node, override),
                            effWidth(node, override),
                            effHeight(node, override)
                        )
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.textureId)
                        GLES20.glUniform1i(uTexture, 0)
                        val alpha = effectiveAlpha(node.opacity, override?.opacity)
                        GLES20.glUniform1f(uTextAlpha, alpha)
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                    }
                    return
                }
                useSolidProgram()
                val world = withParallax(parent, node, override)
                // 形状统一按"中心 + 一圈边界点"画三角形扇：
                // 矩形/椭圆/三角/梯形/多边形/星形都满足关于中心可见，扇面覆盖即正确
                val boxW = effWidth(node, override)
                val boxH = effHeight(node, override)
                val points = Shapes.points(node.shape, node.sides, boxW, boxH)
                val fanCount = stageFan(world, points, boxW / 2f, boxH / 2f)
                val alpha = effectiveAlpha(node.opacity, override?.opacity)
                val c = node.color.modulatedBy(alpha)
                GLES20.glUniform4f(uColor, c.r, c.g, c.b, c.a)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, fanCount)
            }

            is TextNode -> {
                useTextProgram()
                drawTextNode(node, override, vars, texts, withParallax(parent, node, override))
            }

            is ImageNode -> {
                useTextProgram()
                drawImageNode(node, override, withParallax(parent, node, override))
            }
        }
    }

    /** 节点在**父坐标系**里的变换：先平移到位置，再绕自身（缩放后的）中心缩放+旋转。 */
    /**
     * 元素最终透明度。
     *
     * **覆盖语义，不是相乘**：规则/动画给出的 opacity 直接替换节点自身的值。
     * 相乘的写法看起来更"保守"，但会让"把本来是透明的元素显示出来"永远失败
     *（0 × 任何值 = 0），真机表现就是"点了没反应"——这个 bug 踩过一次。
     */
    internal fun effectiveAlpha(nodeOpacity: Float, overrideOpacity: Float?): Float =
        (overrideOpacity ?: nodeOpacity).coerceIn(0f, 1f)

    private fun nodeMatrix(node: SceneNode, override: NodeOverrides?): Mat2D {
        val geometry = nodeGeometry(node)
        val x = override?.x ?: geometry[0]
        val y = override?.y ?: geometry[1]
        // 宽高可用动画覆盖：中心点随盒子变化，缩放/旋转仍绕盒子中心
        val width = override?.width ?: geometry[2]
        val height = override?.height ?: geometry[3]
        val scale = (override?.scale ?: geometry[4]).coerceAtLeast(0.01f)
        val rotation = override?.rotationZ ?: geometry[5]

        // 局部坐标是 (0..width, 0..height)，缩放后中心在 (width*scale/2, height*scale/2)
        val centerX = width * scale / 2f
        val centerY = height * scale / 2f
        return Mat2D.translate(x, y).then(Mat2D.scaleRotate(scale, rotation, centerX, centerY))
    }

    /** 返回 [x, y, width, height, scale, rotationZ]。 */
    private fun nodeGeometry(node: SceneNode): FloatArray = when (node) {
        is RectNode -> floatArrayOf(node.x, node.y, node.width, node.height, node.scale, node.rotationZ)
        is TextNode -> floatArrayOf(node.x, node.y, node.width, node.height, node.scale, node.rotationZ)
        is ImageNode -> floatArrayOf(node.x, node.y, node.width, node.height, node.scale, node.rotationZ)
        is ContainerNode -> floatArrayOf(node.x, node.y, node.width, node.height, node.scale, node.rotationZ)
    }

    private fun nodeWidth(node: SceneNode): Float = nodeGeometry(node)[2]

    private fun nodeHeight(node: SceneNode): Float = nodeGeometry(node)[3]

    /**
     * 本帧实际使用的宽 / 高：动画或绑定给了覆盖值就用覆盖值。
     *
     * 为什么要统一从这里取：盒子尺寸同时决定矩阵中心、三角化顶点、裁剪矩形和贴图四边形，
     * 只要有一处仍读静态值，宽高动画就会"图变形但命中区不变"或"裁剪框不动"。
     */
    private fun effWidth(node: SceneNode, override: NodeOverrides?): Float =
        override?.width ?: nodeGeometry(node)[2]

    private fun effHeight(node: SceneNode, override: NodeOverrides?): Float =
        override?.height ?: nodeGeometry(node)[3]

    private fun findNode(node: SceneNode, id: String): SceneNode? {
        if (node.id == id) return node
        if (node is ContainerNode) {
            for (child in node.children) findNode(child, id)?.let { return it }
        }
        return null
    }

    /** 选中框要画在元素真正的世界位置上，所以先求出它的累计矩阵。 */
    private fun worldMatrixOf(
        node: SceneNode,
        id: String,
        overrides: Map<String, NodeOverrides>
    ): Mat2D? = worldMatrixOf(node, id, overrides, Mat2D.IDENTITY)

    private fun worldMatrixOf(
        node: SceneNode,
        id: String,
        overrides: Map<String, NodeOverrides>,
        parent: Mat2D
    ): Mat2D? {
        val world = if (node is ContainerNode && node.isRoot) parent else parent.then(nodeMatrix(node, overrides[node.id]))
        if (node.id == id) return world
        if (node is ContainerNode) {
            for (child in node.children) {
                worldMatrixOf(child, id, overrides, world)?.let { return it }
            }
        }
        return null
    }

    // ---- 裁剪（glScissor） ----

    /**
     * 裁剪区栈，每层存 [left, top, right, bottom]（像素，原点在屏幕左上）。
     *
     * 用 glScissor 而不是模板缓冲：代价最低。旋转容器的裁剪取**包围盒**，
     * 这是已知近似（要精确得用模板缓冲，等真有人需要再说）。
     */
    private val scissorStack = ArrayList<IntArray>()

    /** 记录已经报过一次错的节点，避免每帧刷日志。场景重载时清空。 */
    // 本帧的视差上下文（渲染线程独占，无需同步）
    private var frameTiltX = 0f
    private var frameTiltY = 0f
    private var frameParallax = ParallaxSettings.DEFAULT
    private var frameParallaxMap: Map<String, NodeParallax> = emptyMap()

    private val failedNodes = HashSet<String>()

    private fun pushScissor(world: Mat2D, width: Float, height: Float) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val xs = floatArrayOf(0f, width, 0f, width)
        val ys = floatArrayOf(0f, 0f, height, height)
        for (i in 0 until 4) {
            val px = world.mapX(xs[i], ys[i])
            val py = world.mapY(xs[i], ys[i])
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }

        // 画布单位 → 像素（与 updateProjection 的 ortho 参数一致）
        val visibleWidth = 100f / cameraZoom
        val visibleHeight = canvasHeightUnits / cameraZoom
        var l = ((minX - cameraPanX) / visibleWidth * surfaceWidth).toInt()
        var r = ((maxX - cameraPanX) / visibleWidth * surfaceWidth).toInt()
        var t = ((minY - cameraPanY) / visibleHeight * surfaceHeight).toInt()
        var b = ((maxY - cameraPanY) / visibleHeight * surfaceHeight).toInt()

        val parent = scissorStack.lastOrNull()
        if (parent != null) {
            l = maxOf(l, parent[0])
            t = maxOf(t, parent[1])
            r = minOf(r, parent[2])
            b = minOf(b, parent[3])
        }
        if (r < l) r = l
        if (b < t) b = t

        scissorStack.add(intArrayOf(l, t, r, b))
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        // GL 的 scissor 原点在左下角，所以要翻转 y
        GLES20.glScissor(l, surfaceHeight - b, r - l, b - t)
    }

    private fun popScissor() {
        if (scissorStack.isNotEmpty()) scissorStack.removeAt(scissorStack.size - 1)
        val parent = scissorStack.lastOrNull()
        if (parent == null) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        } else {
            GLES20.glScissor(
                parent[0],
                surfaceHeight - parent[3],
                parent[2] - parent[0],
                parent[3] - parent[1]
            )
        }
    }

    /**
     * 命中测试：把点击坐标用逆矩阵反算回元素局部坐标，再看是否落在 0..w / 0..h 内。
     *
     * 后画的在上层，所以子节点**倒序**遍历，命中即返回。
     */
    fun hitTest(
        scene: SceneDocument?,
        x: Float,
        y: Float,
        overrides: Map<String, NodeOverrides> = emptyMap()
    ): String? {
        if (scene == null || !scene.root.visible) return null
        return hitNode(scene.root, Mat2D.IDENTITY, x, y, overrides, scene.hitThroughIds)
    }

    private fun hitNode(
        node: SceneNode,
        parent: Mat2D,
        x: Float,
        y: Float,
        overrides: Map<String, NodeOverrides>,
        hitThrough: Set<String>
    ): String? {
        if (!effectivelyVisible(node)) return null
        val override = overrides[node.id]
        // 穿透节点自身不参与命中（容器已在上面单独处理，会继续遍历子节点）
        if (node !is ContainerNode && node.id in hitThrough) return null
        val world = if (node is ContainerNode && node.isRoot) {
            parent
        } else {
            withParallax(parent, node, override)
        }

        if (node is ContainerNode) {
            // 穿透容器：自身不命中，但子节点照常可点（遮罩层整体穿透、内部按钮仍可点）
            for (i in node.children.indices.reversed()) {
                hitNode(node.children[i], world, x, y, overrides, hitThrough)?.let { return it }
            }
            return null
        }

        val inverse = world.invert() ?: return null
        val localX = inverse.mapX(x, y)
        val localY = inverse.mapY(x, y)
        val width = effWidth(node, override)
        val height = effHeight(node, override)
        // 形状元素按真实轮廓判定：否则点圆形的空白角落也会触发
        val shape = if (node is RectNode) node.shape else ShapeKind.RECT
        val sides = if (node is RectNode) node.sides else 4
        val inside = if (shape == ShapeKind.RECT) {
            localX >= 0f && localY >= 0f && localX <= width && localY <= height
        } else {
            Shapes.contains(Shapes.points(shape, sides, width, height), localX, localY)
        }
        return if (inside) node.id else null
    }


    private fun useSolidProgram() {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uOrtho, 1, false, ortho, 0)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
    }

    private fun useTextProgram() {
        GLES20.glUseProgram(textProgram)
        GLES20.glUniformMatrix4fv(uTextOrtho, 1, false, ortho, 0)
        textVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTextPosition)
        GLES20.glVertexAttribPointer(aTextPosition, 2, GLES20.GL_FLOAT, false, 0, textVertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
    }

    /** 文本：先让 Canvas 把字画成纹理，再按普通元素的变换贴上去。 */
    private fun drawTextNode(
        node: TextNode,
        override: NodeOverrides?,
        vars: Map<String, Float>,
        texts: Map<String, String>,
        world: Mat2D
    ) {
        val rendered = TextTemplate.render(node.text, vars, texts = texts)
        val texture = textCache.textureFor(node, rendered, surfaceWidth) ?: return

        stageTexturedQuad(world, effWidth(node, override), effHeight(node, override))

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.textureId)
        GLES20.glUniform1i(uTexture, 0)
        val alpha = effectiveAlpha(node.opacity, override?.opacity)
        GLES20.glUniform1f(uTextAlpha, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** 图片：解码成纹理后按普通元素变换贴上屏幕。 */
    private fun drawImageNode(node: ImageNode, override: NodeOverrides?, world: Mat2D) {
                // feather 是"占元素宽度的百分比"，直接交给缓存换算成纹理像素
                val texture = imageCache.textureFor(
            node.src, resourcesDir, node.feather,
            node.cornerRadius, node.mask, node.maskSides, node.maskSrc,
            node.maskX, node.maskY, node.maskW, node.maskH
        ) ?: return

        stageTexturedQuad(world, effWidth(node, override), effHeight(node, override))

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1f(uTextAlpha, effectiveAlpha(node.opacity, override?.opacity))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * 把元素在局部空间的四个角（0,0）(w,0) (0,h) (w,h) 经 [world] 变换后写进纹理顶点缓冲。
     * 顺序对应 TRIANGLE_STRIP：左上、右上、左下、右下。
     */
    private fun stageTexturedQuad(world: Mat2D, w: Float, h: Float) {
        textVertexData[0] = world.mapX(0f, 0f)
        textVertexData[1] = world.mapY(0f, 0f)
        textVertexData[2] = world.mapX(w, 0f)
        textVertexData[3] = world.mapY(w, 0f)
        textVertexData[4] = world.mapX(0f, h)
        textVertexData[5] = world.mapY(0f, h)
        textVertexData[6] = world.mapX(w, h)
        textVertexData[7] = world.mapY(w, h)

        textVertexBuffer.position(0)
        textVertexBuffer.put(textVertexData)
        textVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aTextPosition, 2, GLES20.GL_FLOAT, false, 0, textVertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
    }

    /**
     * 把形状边界点变换到画布坐标，并在**最前面插入中心点**。
     *
     * 用 GL_TRIANGLE_FAN 从中心展开，所有形状（矩形/椭圆/三角/梯形/多边形/星形）
     * 都关于中心可见，扇面覆盖即正确，不需要三角化。
     * 返回顶点数（中心 + 边界）。
     */
    private fun stageFan(world: Mat2D, points: FloatArray, centerX: Float, centerY: Float): Int {
        // 留一位给"重复的首点"：GL_TRIANGLE_FAN 不会自动闭合，
        // 少了它，最后一个边界点与第一个之间会留一个楔形缺口（整圆缺一角的那个 bug）。
        val n = minOf(points.size / 2, MAX_SHAPE_VERTICES - 2)
        vertexData[0] = world.mapX(centerX, centerY)
        vertexData[1] = world.mapY(centerX, centerY)
        for (i in 0 until n) {
            vertexData[(i + 1) * 2] = world.mapX(points[i * 2], points[i * 2 + 1])
            vertexData[(i + 1) * 2 + 1] = world.mapY(points[i * 2], points[i * 2 + 1])
        }
        // 末点 = 首点，闭合扇形
        vertexData[(n + 1) * 2] = world.mapX(points[0], points[1])
        vertexData[(n + 1) * 2 + 1] = world.mapY(points[0], points[1])

        vertexBuffer.position(0)
        vertexBuffer.put(vertexData, 0, (n + 2) * 2)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        return n + 2
    }

    /** 只写边界点，用于画选中框（LINE_LOOP）。返回顶点数。 */
    private fun stageLoop(world: Mat2D, points: FloatArray): Int {
        val n = minOf(points.size / 2, MAX_SHAPE_VERTICES)
        for (i in 0 until n) {
            vertexData[i * 2] = world.mapX(points[i * 2], points[i * 2 + 1])
            vertexData[i * 2 + 1] = world.mapY(points[i * 2], points[i * 2 + 1])
        }

        vertexBuffer.position(0)
        vertexBuffer.put(vertexData, 0, n * 2)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        return n
    }

    /**
     * 设置元素可见性（幂等）。
     *
     * 与 [toggleVisible] 的区别：触发器规则里同一个状态可能被重复设置，
     * 用 toggle 会出现"点两次又回来了"的抖动，所以规则系统只认 set。
     */
    fun setNodeVisible(nodeId: String, visible: Boolean) {
        visibilityOverride[nodeId] = visible
    }

    /**
     * 清空"规则隐藏"集合。
     *
     * 必须在预设重载时调用：这个集合是运行期状态，跨预设会残留 ——
     * 结果是一份明明写着 visible:true 的新预设，里头的节点仍被当成隐藏（真机上表现为"菜单存在但看不见"）。
     */
    fun clearHiddenNodes() {
        visibilityOverride.clear()
    }

    /** 当前被规则隐藏的节点（测试与状态栏用）。 */
    fun hiddenNodeIds(): Set<String> = visibilityOverride.filterValues { !it }.keys.toSet()

    /** 在全树里按 id 找节点（toggle 需要知道它的原始可见性）。 */
    private fun findNodeById(nodeId: String): SceneNode? {
        fun walk(node: SceneNode): SceneNode? {
            if (node.id == nodeId) return node
            if (node is ContainerNode) {
                for (child in node.children) walk(child)?.let { return it }
            }
            return null
        }
        return walk(rootNodeForSearch ?: return null)
    }

    /** 当前场景根（由 renderer 在 loadScene 时记录，供查找用）。 */
    private var rootNodeForSearch: SceneNode? = null

    /** 读取节点在场景里的静态属性值（补间的起点要用它）。 */
    fun staticPropertyOf(nodeId: String, property: String): Float? {
        val node = findNodeById(nodeId) ?: return null
        return when (node) {
            is RectNode -> staticPropOf(node.x, node.y, node.width, node.height, node.scale, node.rotationZ, node.opacity, property)
            is TextNode -> staticPropOf(node.x, node.y, node.width, node.height, node.scale, node.rotationZ, node.opacity, property)
            is ImageNode -> staticPropOf(node.x, node.y, node.width, node.height, node.scale, node.rotationZ, node.opacity, property)
            is ContainerNode -> staticPropOf(node.x, node.y, node.width, node.height, node.scale, node.rotationZ, node.opacity, property)
        }
    }

    /** 四种节点字段名一致，取值逻辑只写一份，避免漏加属性时各处行为不一致。 */
    private fun staticPropOf(
        x: Float, y: Float, width: Float, height: Float,
        scale: Float, rotationZ: Float, opacity: Float, property: String
    ): Float? = when (property) {
        "x" -> x
        "y" -> y
        "width" -> width
        "height" -> height
        "scale" -> scale
        "rotationZ" -> rotationZ
        "opacity" -> opacity
        else -> null
    }

    /** 节点当前是否应当被绘制/命中：运行期覆盖优先，其次跟随场景。 */
    private fun effectivelyVisible(node: SceneNode): Boolean =
        visibilityOverride[node.id] ?: node.visible

    /** 切换元素可见性；返回切换后的状态（true = 现在可见）。 */
    fun toggleVisible(nodeId: String): Boolean {
        // 以当前有效状态取反，并写入覆盖 —— 场景里原本隐藏的节点也能被 toggle 出来
        val current = visibilityOverride[nodeId]
            ?: findNodeById(nodeId)?.visible
            ?: true
        visibilityOverride[nodeId] = !current
        return !current
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textProgram != 0) {
            GLES20.glDeleteProgram(textProgram)
            textProgram = 0
        }
        textCache.release()
        imageCache.release()
        pathCache.release()
        isReady = false
    }

    private fun updateProjection() {
        // 缩放以画布宽度百分比为单位，因此可见高度也要按同一比例收缩。
        val visibleWidth = 100f / cameraZoom
        val visibleHeight = canvasHeightUnits / cameraZoom
        val left = cameraPanX
        val top = cameraPanY
        Matrix.orthoM(ortho, 0, left, left + visibleWidth, top + visibleHeight, top, -1f, 1f)
    }


    private fun buildProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val newProgram = GLES20.glCreateProgram()
        if (newProgram == 0) throw IllegalStateException("glCreateProgram 失败")

        GLES20.glAttachShader(newProgram, vertexShader)
        GLES20.glAttachShader(newProgram, fragmentShader)
        GLES20.glLinkProgram(newProgram)

        val status = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, status, 0)
        val log = GLES20.glGetProgramInfoLog(newProgram)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        if (status[0] == 0) {
            GLES20.glDeleteProgram(newProgram)
            throw IllegalStateException("着色器链接失败：$log")
        }
        if (log.isNotBlank()) Log.w(TAG, "着色器链接警告：$log")
        return newProgram
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw IllegalStateException("glCreateShader 失败（type=$type）")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("着色器编译失败：$log")
        }
        return shader
    }

    private fun buildTextProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, TEXT_VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, TEXT_FRAGMENT_SHADER)
        val newProgram = GLES20.glCreateProgram()
        if (newProgram == 0) throw IllegalStateException("glCreateProgram 失败（文本）")

        GLES20.glAttachShader(newProgram, vertexShader)
        GLES20.glAttachShader(newProgram, fragmentShader)
        GLES20.glLinkProgram(newProgram)

        val status = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, status, 0)
        val log = GLES20.glGetProgramInfoLog(newProgram)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        if (status[0] == 0) {
            GLES20.glDeleteProgram(newProgram)
            throw IllegalStateException("文本着色器链接失败：$log")
        }
        if (log.isNotBlank()) Log.w(TAG, "文本着色器链接警告：$log")
        return newProgram
    }

    private companion object {
        const val TAG = "LOOW.Renderer"
        /** 形状最多 48+1 个顶点（椭圆 48 段），左右留余量。 */
        const val MAX_SHAPE_VERTICES = 65
        const val MAX_SHAPE_FLOATS = MAX_SHAPE_VERTICES * 2
        const val VERTEX_BYTES = MAX_SHAPE_FLOATS * 4

        val FALLBACK_BACKGROUND = RgbaColor(0.04f, 0.05f, 0.07f, 1f)

        const val VERTEX_SHADER = """
            uniform mat4 uOrtho;
            attribute vec2 aPosition;
            void main() {
                gl_Position = uOrtho * vec4(aPosition, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        const val TEXT_VERTEX_SHADER = """
            uniform mat4 uOrtho;
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uOrtho * vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        // Canvas 产出的 bitmap 是预乘 alpha，这里先还原成直通 alpha 再交给 SRC_ALPHA 混合，
        // 否则文字边缘会发黑。
        const val TEXT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                float a = c.a * uAlpha;
                gl_FragColor = vec4(c.rgb / max(c.a, 0.0039), a);
            }
        """
    }
}

/** 中立姿态：设备没有倾斜时的倾斜向量。 */
private val ZERO_TILT = floatArrayOf(0f, 0f)
