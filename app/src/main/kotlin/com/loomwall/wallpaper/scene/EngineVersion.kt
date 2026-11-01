package com.loomwall.wallpaper.scene

/**
 * 引擎与格式的版本常量。
 *
 * 规则（来自项目约定）：任何对 scene.json / interactions.json 结构的修改，
 * 必须同时改这三处 —— 常量、【schema/】下的 JSON Schema、解析器与兼容检查。
 *
 * 版本关系：
 * - [SCENE_FORMAT]：scene.json 的结构版本。加可选字段不算破坏性变更，保持 0.1。
 * - [INTERACTIONS_FORMAT]：interactions.json 的结构版本，阶段 2 引入。
 * - [PACKAGE_FORMAT]：预设包（manifest.json）的版本。新增 interactions.json 属于包级变化，升到 0.2；
 *   引擎同时接受 0.1（只有 scene）与 0.2（scene + 可能存在的 interactions）。
 */
object EngineVersion {

    /** 当前引擎版本，用于和预设的 engineMinVersion 比较。 */
    const val CURRENT = "0.3.0"

    /** scene.json 结构版本。 */
    const val SCENE_FORMAT = "0.2"

    /** interactions.json 结构版本。 */
    const val INTERACTIONS_FORMAT = "0.6"

    /**
     * 解析器接受的交互动格式版本。
     *
     * 0.2 起加入 `events`（触摸事件），但仍接受 0.1：
     * 旧预设没有 events，语义完全等价，没必要逼社区作者改版本号。
     */
    /**
     * 0.3 新增 vars / rules / functions；0.4 给动作加了 repeat / intervalMs；
     * 0.5 新增 for / while 循环动作（do 数组 + 迭代上限）。0.1~0.4 继续可用：
     * 没有循环的旧预设语义完全不变，没必要逼作者改版本号。
     */
    val ACCEPTED_INTERACTIONS_FORMATS = setOf("0.1", "0.2", "0.3", "0.4", "0.5", INTERACTIONS_FORMAT)

    /**
     * 解析器接受的 scene 格式版本。
     *
     * 0.2 起允许 `container` 作为 children 出现（<div> 式分组）；
     * 0.1 没有这个能力，但读起来语义完全一致，所以继续接受旧版本号。
     */
    val ACCEPTED_SCENE_FORMATS = setOf("0.1", SCENE_FORMAT)

    /** 当前预设包（manifest.formatVersion）版本。 */
    const val PACKAGE_FORMAT = "0.2"

    /** 引擎仍能读的旧包版本，用于向后兼容。 */
    val SUPPORTED_PACKAGE_FORMATS = setOf("0.1", "0.2")

    /**
     * 语义化版本比较：a < b 返回负数，相等返回 0，a > b 返回正数。
     * 非数字段按 0 处理，不抛异常（预设来自社区，必须容错）。
     */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }
}
