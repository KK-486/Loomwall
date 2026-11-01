package com.loomwall.wallpaper.engine

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface

/**
 * 极简 EGL 封装。
 *
 * 为什么不用 GLSurfaceView：壁纸必须自己管理 EGL 生命周期，
 * 才能做到「不可见时彻底释放绘图表面」，这是本项目的功耗红线。
 *
 * 线程约束：本类的所有方法都只在渲染线程上调用。
 */
internal class EglCore {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var attachedSurface: Surface? = null

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    val isInitialized: Boolean
        get() = display != EGL14.EGL_NO_DISPLAY

    val hasAttachedSurface: Boolean
        get() = windowSurface != EGL14.EGL_NO_SURFACE

    fun initialize() {
        check(!isInitialized) { "EGL 已经初始化过" }

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("eglGetDisplay 失败：设备没有可用的 EGL 显示")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            display = EGL14.EGL_NO_DISPLAY
            throw IllegalStateException("eglInitialize 失败：EGL 1.4 不可用")
        }

        // GLES 2.0 起步（兼容性优先）；不要求深度缓冲，场景是 2D 图层。
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val chosen = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        if (!chosen || numConfigs[0] == 0 || configs[0] == null) {
            throw IllegalStateException("找不到满足 RGBA8888 + GLES2 的 EGL 配置")
        }
        config = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext 失败（GLES2 上下文不可用）")
        }
    }

    fun isAttachedTo(surface: Surface?): Boolean =
        surface != null && windowSurface != EGL14.EGL_NO_SURFACE && surface === attachedSurface

    fun attachTo(surface: Surface) {
        val cfg = config ?: throw IllegalStateException("EGL 尚未初始化，不能创建窗口表面")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, cfg, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface 失败（表面可能已失效）")
        }

        windowSurface = eglSurface
        attachedSurface = surface

        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        width = w[0]
        height = h[0]
    }

    fun makeCurrent(): Boolean =
        isInitialized && hasAttachedSurface && EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)

    fun swapBuffers(): Boolean =
        isInitialized && hasAttachedSurface && EGL14.eglSwapBuffers(display, windowSurface)

    /**
     * 解绑并销毁窗口表面，但保留上下文。
     * 壁纸进入不可见状态时调用：GPU 不再有任何待呈现的目标，也不会被唤醒重绘。
     */
    fun detach() {
        if (isInitialized && hasAttachedSurface) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, windowSurface)
        }
        windowSurface = EGL14.EGL_NO_SURFACE
        attachedSurface = null
        width = 0
        height = 0
    }

    fun release() {
        detach()
        if (isInitialized) {
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
        config = null
    }
}
