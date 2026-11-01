package com.loomwall.wallpaper.editor

import com.loomwall.wallpaper.scene.LoopMode
import com.loomwall.wallpaper.scene.Trigger

/**
 * UI 上的中文名。
 *
 * 原则：**界面上不出现英文枚举**，但 JSON 里仍写英文规范名（always / once / pingpong…），
 * 保证预设可以跨工具、跨版本交换。翻译只发生在展示层。
 */
fun LoopMode.displayName(): String = when (this) {
    LoopMode.ONCE -> "播放一次"
    LoopMode.LOOP -> "循环"
    LoopMode.PINGPONG -> "往返"
    LoopMode.ONCE_HOLD -> "播放一次并停在结尾"
}

fun Trigger.Type.displayName(): String = when (this) {
    Trigger.Type.ALWAYS -> "常驻（可见即播）"
    Trigger.Type.TAP -> "点击触发"
    Trigger.Type.FUNC -> "函数触发（被规则调用时播放）"
}
