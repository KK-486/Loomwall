package com.loomwall.wallpaper.scene

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * interactions.json 解析器（格式 v0.1，阶段 2a：动画）。
 *
 * 校验原则与 scene 解析一致：错误带 JSON 路径、不静默失败。
 * 额外规则（动画特有的坑）：
 * - 关键帧按 t 递增，且**不得重复 t**（重复会让区间跨度为 0，插值无定义）；
 * - 最后一帧必须是 t=1.0，否则动画会提前停在半路；
 * - durationMs 必须 > 0，否则除零；
 * - 轨道属性不可重复（同一属性两条轨道会互相覆盖）。
 */
object InteractionsParser {

    fun parse(json: String): Interactions {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw SceneFormatException("/", "不是合法的 JSON 对象：${e.message}")
        }

        val formatVersion = requireString(root, "formatVersion", "")
        if (formatVersion !in EngineVersion.ACCEPTED_INTERACTIONS_FORMATS) {
            throw SceneFormatException(
                "/formatVersion",
                "不支持的交互动格式 \"$formatVersion\"；本引擎支持 \"${EngineVersion.INTERACTIONS_FORMAT}\""
            )
        }

        val animationsJson = root.opt("animations")
        if (animationsJson == null) {
            return Interactions(formatVersion, emptyList(), triggers = TriggerParser.parse(root))
        }
        if (animationsJson !is JSONArray) {
            throw SceneFormatException("/animations", "必须是数组")
        }

        val clips = ArrayList<AnimationClip>(animationsJson.length())
        val ids = mutableSetOf<String>()
        for (index in 0 until animationsJson.length()) {
            val item = animationsJson.opt(index) as? JSONObject
                ?: throw SceneFormatException("/animations[$index]", "必须是 JSON 对象")
            val clip = parseClip(item, "/animations[$index]")
            if (!ids.add(clip.id)) {
                throw SceneFormatException("/animations[$index]/id", "动画 id 重复：\"${clip.id}\"")
            }
            clips += clip
        }
        val bindingsArray = root.opt("bindings")
        val bindings = ArrayList<Binding>()
        if (bindingsArray != null) {
            if (bindingsArray !is JSONArray) throw SceneFormatException("/bindings", "必须是数组")
            for (index in 0 until bindingsArray.length()) {
                val item = bindingsArray.opt(index) as? JSONObject
                    ?: throw SceneFormatException("/bindings[$index]", "必须是 JSON 对象")
                bindings += parseBinding(item, "/bindings[$index]")
            }
        }

        val eventsArray = root.opt("events")
        val events = ArrayList<NodeEvent>()
        if (eventsArray != null) {
            if (eventsArray !is JSONArray) throw SceneFormatException("/events", "必须是数组")
            for (index in 0 until eventsArray.length()) {
                val item = eventsArray.opt(index) as? JSONObject
                    ?: throw SceneFormatException("/events[$index]", "必须是 JSON 对象")
                events += parseEvent(item, "/events[$index]")
            }
        }

        return Interactions(formatVersion, clips, bindings, events, TriggerParser.parse(root))
    }

    private fun parseEvent(json: JSONObject, path: String): NodeEvent {
        val id = requireString(json, "id", path)
        val gestureName = requireString(json, "gesture", path)
        val gesture = Gesture.fromJson(gestureName)
            ?: throw SceneFormatException(
                "$path/gesture",
                "未知手势 \"$gestureName\"（可用：${Gesture.entries.joinToString(" / ") { it.jsonName }}）"
            )
        val targetId = (json.opt("targetId") as? String)?.takeIf { it.isNotBlank() }

        val actionJson = json.opt("action") as? JSONObject
            ?: throw SceneFormatException("$path/action", "缺少动作对象")
        val actionType = requireString(actionJson, "type", "$path/action")
        val action = when (actionType) {
            "playAnimation" -> EventAction.PlayAnimation(
                requireString(actionJson, "clipId", "$path/action")
            )
            "toggleVisible" -> EventAction.ToggleVisible(
                requireString(actionJson, "targetId", "$path/action")
            )
            "launchApp" -> EventAction.LaunchApp(
                requireString(actionJson, "package", "$path/action")
            )
            "openUri" -> EventAction.OpenUri(
                requireString(actionJson, "uri", "$path/action")
            )
            else -> throw SceneFormatException(
                "$path/action/type",
                "未知动作 \"$actionType\"（当前支持：playAnimation / toggleVisible / launchApp / openUri）"
            )
        }

        return NodeEvent(id = id, targetId = targetId, gesture = gesture, action = action)
    }

    private fun parseBinding(json: JSONObject, path: String): Binding {
        val targetId = requireString(json, "targetId", path)
        val propertyName = requireString(json, "property", path)
        val property = AnimatableProperty.fromJson(propertyName)
            ?: throw SceneFormatException("$path/property", "未知属性 \"$propertyName\"")
        val source = requireString(json, "source", path)
        if (!SystemVars.isNumeric(source)) {
            throw SceneFormatException(
                "$path/source",
                "未知或不可绑定的变量 \"$source\"（可绑定：${SystemVars.LABELS.keys.filter { SystemVars.isNumeric(it) }.joinToString(" / ")}）"
            )
        }
        val inRange = parsePair(json, "in", path)
        val outRange = parsePair(json, "out", path)
        return Binding(
            targetId = targetId,
            property = property,
            source = source,
            inMin = inRange[0],
            inMax = inRange[1],
            outMin = outRange[0],
            outMax = outRange[1]
        )
    }

    private fun parsePair(json: JSONObject, key: String, path: String): FloatArray {
        val array = json.opt(key) as? JSONArray
            ?: throw SceneFormatException("$path/$key", "缺少 $key 数组（形如 [0, 100]）")
        if (array.length() != 2) {
            throw SceneFormatException("$path/$key", "必须是两个数字，收到 ${array.length()} 个")
        }
        return FloatArray(2) { i ->
            (array.opt(i) as? Number)?.toFloat()
                ?: throw SceneFormatException("$path/$key[$i]", "必须是数字")
        }
    }

    private fun parseClip(json: JSONObject, path: String): AnimationClip {
        val id = requireString(json, "id", path)
        if (id.isBlank()) throw SceneFormatException("$path/id", "不能为空字符串")

        val targetId = requireString(json, "targetId", path)
        if (targetId.isBlank()) throw SceneFormatException("$path/targetId", "不能为空字符串")

        val trigger = parseTrigger(json.opt("trigger") as? JSONObject, path)
        val tracksJson = json.opt("tracks") as? JSONArray
            ?: throw SceneFormatException("$path/tracks", "缺少 tracks 数组")
        if (tracksJson.length() == 0) {
            throw SceneFormatException("$path/tracks", "至少要有一条轨道")
        }

        val tracks = ArrayList<AnimationTrack>(tracksJson.length())
        val properties = mutableSetOf<AnimatableProperty>()
        for (index in 0 until tracksJson.length()) {
            val item = tracksJson.opt(index) as? JSONObject
                ?: throw SceneFormatException("$path/tracks[$index]", "必须是 JSON 对象")
            val track = parseTrack(item, "$path/tracks[$index]")
            if (!properties.add(track.property)) {
                throw SceneFormatException(
                    "$path/tracks[$index]/property",
                    "同一动画里属性重复：\"${track.property.jsonName}\"（两条轨道会互相覆盖）"
                )
            }
            tracks += track
        }

        return AnimationClip(id = id, targetId = targetId, trigger = trigger, tracks = tracks)
    }

    private fun parseTrigger(json: JSONObject?, path: String): Trigger {
        if (json == null) {
            throw SceneFormatException("$path/trigger", "缺少触发方式对象")
        }
        val type = requireString(json, "type", "$path/trigger")
        val parsed = Trigger.Type.fromJson(type)
            ?: throw SceneFormatException(
                "$path/trigger/type",
                "未知触发方式 \"$type\"（阶段 2 支持：always 常驻 / tap 点击；滚动与系统状态属于阶段 3 及以后）"
            )
        if (parsed == Trigger.Type.ALWAYS) {
            // 常驻动画不需要命中目标，写了也忽略，但明确报错容易误导社区作者，这里静默容忍。
            return Trigger(type = parsed, targetId = null)
        }
        return Trigger(
            type = parsed,
            targetId = (json.opt("targetId") as? String)?.takeIf { it.isNotBlank() }
        )
    }

    private fun parseTrack(json: JSONObject, path: String): AnimationTrack {
        val propertyName = requireString(json, "property", path)
        val property = AnimatableProperty.fromJson(propertyName)
            ?: throw SceneFormatException(
                "$path/property",
                "未知属性 \"$propertyName\"（可用：${AnimatableProperty.entries.joinToString(" / ") { it.jsonName }}）"
            )

        val durationMs = requireNumber(json, "durationMs", path).toInt()
        if (durationMs <= 0) {
            throw SceneFormatException("$path/durationMs", "必须大于 0，收到 $durationMs")
        }

        val loopName = optionalString(json, "loop", LoopMode.ONCE.jsonName, path)
        val loop = LoopMode.fromJson(loopName)
            ?: throw SceneFormatException(
                "$path/loop",
                "未知循环模式 \"$loopName\"（可用：once / loop / pingpong）"
            )

        val keyframesJson = json.opt("keyframes") as? JSONArray
            ?: throw SceneFormatException("$path/keyframes", "缺少 keyframes 数组")
        // 允许 0 个关键帧：这表示「属性已启用但还没打帧」（PR/AE 的语义），
        // 运行时跳过这种轨道，元素保持静态值。

        val keyframes = ArrayList<Keyframe>(keyframesJson.length())
        var previousT = -1f
        for (index in 0 until keyframesJson.length()) {
            val item = keyframesJson.opt(index) as? JSONObject
                ?: throw SceneFormatException("$path/keyframes[$index]", "必须是 JSON 对象")
            val framePath = "$path/keyframes[$index]"

            val t = requireNumber(item, "t", framePath).toFloat()
            if (t < 0f || t > 1f) {
                throw SceneFormatException("$framePath/t", "必须在 0..1 之间，收到 $t")
            }
            if (t <= previousT) {
                throw SceneFormatException("$framePath/t", "必须严格递增，$t 未大于上一个关键帧的 $previousT")
            }
            previousT = t

            val value = requireNumber(item, "value", framePath).toFloat()
            val easingName = optionalString(item, "easing", "linear", framePath)
            val easing = try {
                Easing.parse(easingName)
            } catch (e: IllegalArgumentException) {
                throw SceneFormatException("$framePath/easing", e.message ?: "缓动无法解析")
            }

            keyframes += Keyframe(t = t, value = value, easing = easing)
        }

        if (keyframes.isNotEmpty()) {
            val last = keyframes.last()
            // 不要求末帧必须是 1.0：允许“前段变化、后段保持”，末帧之后由采样逻辑保持末值。
            // 但仍然要求时间严格递增（上一段循环已校验），否则区间跨度为 0、插值无定义。
            if (last.t <= 0f) {
                throw SceneFormatException("$path/keyframes", "关键帧时间必须大于 0（当前 ${last.t}）")
            }
        }

        return AnimationTrack(property = property, keyframes = keyframes, durationMs = durationMs, loop = loop)
    }

    // ---- 字段读取（与 SceneParser 保持同样的错误风格） ----

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

    private fun requireNumber(json: JSONObject, key: String, path: String): Double {
        if (!json.has(key) || json.isNull(key)) {
            throw SceneFormatException("$path/$key", "缺少必填字段")
        }
        val value = (json.opt(key) as? Number)?.toDouble()
            ?: throw SceneFormatException("$path/$key", "必须是数字")
        if (value.isNaN() || value.isInfinite()) {
            throw SceneFormatException("$path/$key", "必须是有限数字")
        }
        return value
    }
}
