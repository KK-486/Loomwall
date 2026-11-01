package com.loomwall.wallpaper.scene

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [Interactions] 写回 interactions.json。
 *
 * 与 [InteractionsParser] 严格对称：解析器能读的，这里必须能写；反之亦然。
 * 写出前不做“顺手优化”（例如合并关键帧），保持编辑器里看到的曲线与文件一一对应，
 * 否则用户会觉得“我明明调过，怎么变了”。
 */
object InteractionsWriter {

    fun toJson(interactions: Interactions): String {
        val root = JSONObject()
        root.put("formatVersion", EngineVersion.INTERACTIONS_FORMAT)

        val animations = JSONArray()
        for (clip in interactions.animations) {
            animations.put(clipToJson(clip))
        }
        root.put("animations", animations)

        if (interactions.bindings.isNotEmpty()) {
            val bindings = JSONArray()
            for (b in interactions.bindings) {
                bindings.put(
                    JSONObject().apply {
                        put("targetId", b.targetId)
                        put("property", b.property.jsonName)
                        put("source", b.source)
                        put("in", JSONArray().put(round3(b.inMin)).put(round3(b.inMax)))
                        put("out", JSONArray().put(round3(b.outMin)).put(round3(b.outMax)))
                    }
                )
            }
            root.put("bindings", bindings)
        }

        if (interactions.events.isNotEmpty()) {
            val events = JSONArray()
            for (e in interactions.events) {
                val action = JSONObject()
                when (val a = e.action) {
                    is EventAction.PlayAnimation -> {
                        action.put("type", a.jsonName)
                        action.put("clipId", a.clipId)
                    }
                    is EventAction.ToggleVisible -> {
                        action.put("type", a.jsonName)
                        action.put("targetId", a.targetId)
                    }
                    is EventAction.LaunchApp -> {
                        action.put("type", a.jsonName)
                        action.put("package", a.packageName)
                    }
                    is EventAction.OpenUri -> {
                        action.put("type", a.jsonName)
                        action.put("uri", a.uri)
                    }
                }
                events.put(
                    JSONObject().apply {
                        put("id", e.id)
                        e.targetId?.let { put("targetId", it) }
                        put("gesture", e.gesture.jsonName)
                        put("action", action)
                    }
                )
            }
            root.put("events", events)
        }
        // 版本随内容升级：写了 events 就至少是 0.2
        root.put("formatVersion", if (interactions.events.isNotEmpty()) EngineVersion.INTERACTIONS_FORMAT else interactions.formatVersion)
        return root.toString(2)
    }

    private fun clipToJson(clip: AnimationClip): JSONObject = JSONObject().apply {
        put("id", clip.id)
        put("targetId", clip.targetId)

        val trigger = JSONObject().apply {
            put("type", clip.trigger.type.jsonName)
            if (clip.trigger.type == Trigger.Type.TAP) {
                clip.trigger.targetId?.let { put("targetId", it) }
            }
        }
        put("trigger", trigger)

        val tracks = JSONArray()
        for (track in clip.tracks) {
            tracks.put(trackToJson(track))
        }
        put("tracks", tracks)
    }

    private fun trackToJson(track: AnimationTrack): JSONObject = JSONObject().apply {
        put("property", track.property.jsonName)
        put("durationMs", track.durationMs)
        put("loop", track.loop.jsonName)

        val keyframes = JSONArray()
        for (frame in track.keyframes) {
            keyframes.put(
                JSONObject().apply {
                    put("t", round3(frame.t))
                    put("value", round3(frame.value))
                    put("easing", Easing.toSpec(frame.easing))
                }
            )
        }
        put("keyframes", keyframes)
    }

    private fun round3(value: Float): Double = kotlin.math.round(value * 1000f) / 1000.0
}
