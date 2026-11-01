package com.loomwall.wallpaper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.loomwall.wallpaper.editor.AnimationPanel
import com.loomwall.wallpaper.editor.EditorAspectLayout
import com.loomwall.wallpaper.editor.EditorCanvasView
import com.loomwall.wallpaper.editor.EditorState
import com.loomwall.wallpaper.editor.LayerPanel
import com.loomwall.wallpaper.editor.PropertyPanel
import com.loomwall.wallpaper.scene.PresetRepository

/**
 * 编辑器（阶段 1 属性编辑 + 阶段 2b 动画时间轴），**固定横屏**。
 *
 * 布局参考桌面版 Wallpaper Engine 的三栏结构：
 * - 左栏：动作按钮（2×2）+ 图层列表 + 动画列表；
 * - 中间：手机画面预览（所见即所得，按真机比例）；
 * - 右栏：属性 / 动画时间轴（选中东西的细节）。
 *
 * 为什么把按钮放左栏而不是顶部：横屏时屏幕高度最宝贵，顶部一行按钮会从预览里切走约 100px，
 * 而预览受高度限制、宽度算下来本身就窄，越挤越没法看。
 */
class EditorActivity : Activity() {

    private lateinit var repository: PresetRepository
    private lateinit var state: EditorState
    private lateinit var canvas: EditorCanvasView
    private lateinit var propertyPanel: PropertyPanel
    private lateinit var animationPanel: AnimationPanel
    private lateinit var rightHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = PresetRepository(applicationContext)
        when (val loaded = repository.loadForEditing()) {
            is PresetRepository.Result.Ok ->
                state = EditorState(loaded.snapshot.scene, loaded.snapshot.interactions)
            is PresetRepository.Result.Err -> {
                toast(getString(R.string.editor_load_failed, loaded.reason), long = true)
                finish()
                return
            }
        }

        canvas = EditorCanvasView(this, state)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        body.addView(
            buildLeftColumn(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f)
        )
        body.addView(
            buildCanvasColumn(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.45f)
        )
        body.addView(
            buildRightColumn(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.55f)
        )

        // 注意：不要在这里碰 window.insetsController —— 此时 DecorView 还没创建，
        // 真机上会 NPE 直接闪退（踩过一次）。
        return body
    }

    /** 左栏：动作按钮（2×2）+ 图层/动画列表。 */
    private fun buildLeftColumn(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildToolbar())
        column.addView(
            LayerPanel(this, state),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return column
    }

    /** 动作按钮：2×2 网格，放在左栏顶部，避免占用预览高度。 */
    private fun buildToolbar(): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(2))
        }

        grid.addView(
            toolRow(
                R.string.editor_save to { saveScene(goHome = false) },
                R.string.editor_apply to { saveScene(goHome = true) }
            )
        )
        grid.addView(
            toolRow(
                R.string.editor_reset_camera to { canvas.resetCamera() },
                R.string.editor_reset to { resetToBuiltIn() }
            )
        )
        return grid
    }

    private fun toolRow(first: Pair<Int, () -> Unit>, second: Pair<Int, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(toolButton(first.first, first.second), LinearLayout.LayoutParams(0, dp(34), 1f))
            addView(toolButton(second.first, second.second), LinearLayout.LayoutParams(0, dp(34), 1f))
        }

    private fun toolButton(labelRes: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = getString(labelRes)
            textSize = 10f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
        }

    private fun buildCanvasColumn(): View = EditorAspectLayout(this).apply {
        aspect = portraitAspect()
        addView(
            canvas,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun buildRightColumn(): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(8), dp(2))
        }
        header.addView(tab(R.string.editor_panel_property) { switchMode(animation = false) })
        header.addView(tab(R.string.editor_panel_animation) { switchMode(animation = true) })

        propertyPanel = PropertyPanel(this, state)
        animationPanel = AnimationPanel(this, state).apply { visibility = View.GONE }

        rightHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(propertyPanel)
            addView(animationPanel)
        }

        column.addView(header)
        column.addView(
            rightHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return column
    }

    private fun tab(labelRes: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = getString(labelRes)
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = dp(30)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

    private fun switchMode(animation: Boolean) {
        propertyPanel.visibility = if (animation) View.GONE else View.VISIBLE
        animationPanel.visibility = if (animation) View.VISIBLE else View.GONE
    }

    private fun saveScene(goHome: Boolean) {
        repository.save(state.scene, state.interactions, getString(R.string.editor_preset_name))
            .onSuccess {
                toast(getString(R.string.editor_saved))
                if (goHome) {
                    startActivity(
                        Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                }
            }
            .onFailure {
                toast(getString(R.string.editor_save_failed, it.message ?: "未知错误"), long = true)
            }
    }

    private fun resetToBuiltIn() {
        repository.resetToBuiltIn()
            .onSuccess {
                val result = repository.loadForEditing()
                if (result is PresetRepository.Result.Ok) {
                    state.replaceScene(result.snapshot.scene)
                    state.replaceInteractions(result.snapshot.interactions)
                    canvas.resetCamera()
                    toast(getString(R.string.editor_reset_done))
                }
            }
            .onFailure {
                toast(getString(R.string.editor_save_failed, it.message ?: ""), long = true)
            }
    }

    /**
     * 壁纸画面是「竖屏」比例。编辑器横屏时 `getRealMetrics` 给的是 2340×1080，
     * 直接相除会得到 2.16（横着的画面），必须取短边/长边才是 0.4615。
     */
    @Suppress("DEPRECATION")
    private fun portraitAspect(): Float {
        val width: Int
        val height: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        if (width <= 0 || height <= 0) return 9f / 19.5f
        return minOf(width, height).toFloat() / maxOf(width, height).toFloat()
    }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
