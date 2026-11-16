package com.loomwall.wallpaper.scene

/**
 * 触摸手势。与 KLWP 的 trigger 列表对齐，但只保留我们真能识别的几种。
 *
 * 命名与 JSON 一致（英文），界面翻译在展示层做。
 */
enum class Gesture(val jsonName: String) {
    TAP("tap"),
    DOUBLE_TAP("doubleTap"),
    LONG_PRESS("longPress"),
    SWIPE_LEFT("swipeLeft"),
    SWIPE_RIGHT("swipeRight"),
    SWIPE_UP("swipeUp"),
    SWIPE_DOWN("swipeDown"),

    /**
     * 按下瞬间（还没抬手）。
     *
     * 加这三个事件是为了表达"必须按住才算数"的死手开关（dead man's switch）：
     * - [PRESS]：手指按下，可以开始放倒计时/进度反馈
     * - [HOLD]：按满 [HOLD_DURATION_MS] 且**手指还没抬**才算成立
     * - [RELEASE]：抬手（或手势被系统取消），此时如果还没到 HOLD，预设应当撤销
     *
     * 只有 PRESS / HOLD / RELEASE 三者配合，预设才能区分"按满"和"中途松手"。
     */
    PRESS("press"),
    HOLD("hold"),
    RELEASE("release");

    companion object {
        /** 按满多久算 HOLD：死手开关要求"一直按住"，这里取 5 秒。 */
        const val HOLD_DURATION_MS = 5000L

        fun fromJson(name: String): Gesture? = entries.firstOrNull { it.jsonName == name }
    }
}

/**
 * 事件动作。
 *
 * 阶段 3 只实现两种真正能被引擎执行的动作：
 * - [PlayAnimation]：播放指定动画（可重放）
 * - [ToggleVisible]：切换某个元素的可见性
 *
 * `launchApp` 这类需要额外权限/清单声明的，等阶段 3 后半再进格式，
 * 避免预设里出现"写了但什么都没发生"的动作。
 */
sealed interface EventAction {
    val jsonName: String

    data class PlayAnimation(val clipId: String) : EventAction {
        override val jsonName: String get() = "playAnimation"
    }

    data class ToggleVisible(val targetId: String) : EventAction {
        override val jsonName: String get() = "toggleVisible"
    }

    /**
     * 启动应用：按包名启动主界面。
     * `package` 必须是已安装应用的包名，找不到时引擎只记录日志、不崩溃。
     */
    data class LaunchApp(val packageName: String) : EventAction {
        override val jsonName: String get() = "launchApp"
    }

    /**
     * 按 URI 启动：`https://` 打开链接、`android-app://包名` 启动应用、
     * `intent://` 走完整 Intent URI。这是「打开链接 / 启动活动」的统一入口。
     */
    data class OpenUri(val uri: String) : EventAction {
        override val jsonName: String get() = "openUri"
    }
}

/**
 * 一条触摸事件：**事件源（手势）→ 动作**。
 *
 * [targetId] 为空表示整块画布都能触发；非空时必须命中该元素的矩形热区。
 */
data class NodeEvent(
    val id: String,
    val targetId: String?,
    val gesture: Gesture,
    val action: EventAction
)
