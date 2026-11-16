#!/usr/bin/env python3
"""校验 LWPKit 预设（scene.json + interactions.json + resources/）。

用途：预设作者、贡献者在提交前自检，避免把「能解析但画不出来」的预设发出去。

用法：
    python tools/validate_preset.py app/src/main/assets/presets/default
    python tools/validate_preset.py path/to/预设目录
    python tools/validate_preset.py path/to/xxx.lwpkit.json      # 直接校验打包好的单文件
    python tools/validate_preset.py --selftest                   # 只检查 schema 是否跟上了引擎

可选参数：
    --strict   把「警告」也算失败（默认警告不影响退出码）

退出码：0 = 通过（可能有警告），1 = 有错误。

支持的格式：
    场景   0.1 / 0.2 / 0.3（node 类型：rect / container / text / image）
    交互动 0.1 ~ 0.9（animations / bindings / vars / functions / rules）

设计原则：
- 只依赖标准库，Python 3.8+ 直接能跑；
- 错误信息带 JSON 路径，和引擎的 SceneFormatException 口径一致；
- 版本号从 Kotlin 常量读，避免脚本里再抄一份（之前就因为路径写死成旧包名
  com/lwpkit/wallpaper，读不到常量、永远只认 0.1，把 0.2/0.3 的预设全部误判成「不支持」）。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

COLOR_RE = re.compile(r"^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
GRADIENT_RE = re.compile(r"^(linear|radial):")
VIEWBOX_RE = re.compile(r"^-?\d+(\.\d+)?[ ,]+-?\d+(\.\d+)?[ ,]+\d+(\.\d+)?[ ,]+\d+(\.\d+)?$")

NODE_TYPES = {"rect", "container", "text", "image"}
SHAPES = {"rect", "ellipse", "triangle", "trapezoid", "polygon", "star", "path", "appslot"}
ALIGNS = {"left", "center", "right"}
TRACK_PROPS = {"x", "y", "width", "height", "scale", "rotationZ", "opacity"}
LOOPS = {"once", "onceHold", "loop", "pingpong"}
BINDING_PROPS = {"x", "y", "width", "height", "scale", "rotationZ", "opacity"}
GESTURES = {"tap", "doubleTap", "longPress", "swipeLeft", "swipeRight", "swipeUp", "swipeDown", "press", "hold", "release"}
ALLOWED_VAR_KEYS = {"value", "min", "max", "persist"}
RULE_TRIGGERS = {"tap", "gesture", "var", "minute", "session", "drag"}
MEDIA_COMMANDS = {"playPause", "next", "prev", "stop"}
ACTION_TYPES = {
    "setVar", "setVisible", "setProperty", "playAnimation",
    "launchApp", "openUri", "mediaControl", "call", "for", "while",
    # 交互动 0.12：系统控制（亮度/音量真控制；面板与设置页只能"打开"）
    "brightness", "volume", "panel", "settings", "seek",
}
ACTION_PROPERTIES = {"x", "y", "width", "height", "scale", "rotationZ", "opacity"}
# setVisible 的可选延迟（交互动 0.11）：关闭动画播完再隐藏
VISIBLE_EXTRA_KEYS = {"delayMs"}

# 引擎里的硬上限（和 RuleModel / InteractionsParser 保持一致）
# 交互动 0.12：系统控制动作（面板/设置页只能"打开"，开关本身系统不给第三方改）
SYSTEM_ACTIONS = {"brightness", "volume", "panel", "settings"}
MAX_ACTIONS_PER_RULE = 16
MAX_ACTIONS_PER_LOOP = 16
MAX_LOOP_DEPTH = 2
MAX_RULES = 64
MAX_TEXT_LINES = 20


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, msg: str) -> None:
        self.errors.append(msg)

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)

    def ok(self) -> bool:
        return not self.errors


# ---------------------------------------------------------------- 版本来源

def kotlin_constants() -> dict[str, str]:
    """从 EngineVersion.kt 读版本常量（单一事实来源）。

    manifest 的 formatVersion 是**包格式**（PACKAGE_FORMAT），和 scene/interactions 的
    格式版本是三条独立的线 —— 之前脚本把它们混成一个，才把 0.2 的预设误判成过期。
    """
    out: dict[str, str] = {}
    kt = (Path(__file__).resolve().parent.parent
          / "app/src/main/kotlin/com/loomwall/wallpaper/scene/EngineVersion.kt")
    if not kt.is_file():
        return out
    text = kt.read_text(encoding="utf-8")
    for key in ("CURRENT", "SCENE_FORMAT", "INTERACTIONS_FORMAT", "PACKAGE_FORMAT"):
        m = re.search(rf'\b{key}\s*=\s*"([^"]+)"', text)
        if m:
            out[key] = m.group(1)

    def read_set(name: str) -> set[str]:
        m = re.search(rf"{name}\s*=\s*setOf\(([^)]*)\)", text)
        if not m:
            return set()
        raw = m.group(1)
        items = set(re.findall(r'"([^"]+)"', raw))
        for token in re.findall(r"\b([A-Z_]+)\b", raw):
            if token in out:
                items.add(out[token])
        return items

    out["_scene_formats"] = ",".join(sorted(read_set("ACCEPTED_SCENE_FORMATS")))
    out["_interaction_formats"] = ",".join(sorted(read_set("ACCEPTED_INTERACTIONS_FORMATS")))
    out["_package_formats"] = ",".join(sorted(read_set("SUPPORTED_PACKAGE_FORMATS")))
    return out


def vtuple(v: str) -> tuple[int, ...]:
    parts = []
    for chunk in str(v).split("."):
        try:
            parts.append(int(chunk))
        except ValueError:
            parts.append(0)
    return tuple(parts)


def vle(a: str, b: str) -> bool:
    """a <= b（按点分数字比较，长度不同补 0）。"""
    ta, tb = vtuple(a), vtuple(b)
    n = max(len(ta), len(tb))
    return ta + (0,) * (n - len(ta)) <= tb + (0,) * (n - len(tb))


# ---------------------------------------------------------------- 基础断言

def check_color(rep: Report, value, where: str) -> None:
    if not isinstance(value, str) or not COLOR_RE.match(value):
        rep.error(f"{where}: 颜色必须是 #RRGGBB 或 #AARRGGBB，收到 {value!r}")


def check_number(rep: Report, value, where: str, *, minimum=None, maximum=None,
                 positive=False, integer=False) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        rep.error(f"{where}: 必须是数字，收到 {value!r}")
        return
    if integer and isinstance(value, float) and not value.is_integer():
        rep.error(f"{where}: 必须是整数，收到 {value}")
    if positive and value <= 0:
        rep.error(f"{where}: 必须大于 0，收到 {value}")
    if minimum is not None and value < minimum:
        rep.error(f"{where}: 不能小于 {minimum}，收到 {value}")
    if maximum is not None and value > maximum:
        rep.error(f"{where}: 不能大于 {maximum}，收到 {value}")


def require_keys(rep: Report, obj: dict, where: str, keys: tuple[str, ...]) -> None:
    for key in keys:
        if key not in obj:
            rep.error(f"{where}: 缺少必填字段 {key}")


# ---------------------------------------------------------------- 场景

class SceneChecker:
    def __init__(self, rep: Report, resources: set[str]) -> None:
        self.rep = rep
        self.resources = resources
        self.ids: set[str] = set()
        self.node_by_id: dict[str, str] = {}
        self.count = {"rect": 0, "container": 0, "text": 0, "image": 0}

    def node(self, n: dict, where: str) -> None:
        rep = self.rep
        if not isinstance(n, dict):
            rep.error(f"{where}: 必须是 JSON 对象")
            return
        node_id = n.get("id")
        if not isinstance(node_id, str) or not node_id:
            rep.error(f"{where}/id: 必须是非空字符串，收到 {node_id!r}")
        elif node_id in self.ids:
            rep.error(f"{where}/id: 重复的 id {node_id!r}")
        else:
            self.ids.add(node_id)

        ntype = n.get("type")
        if ntype not in NODE_TYPES:
            rep.error(f"{where}/type: 不支持的类型 {ntype!r}（可用：{'/'.join(sorted(NODE_TYPES))}）")
            return
        self.count[ntype] += 1
        if isinstance(node_id, str):
            self.node_by_id[node_id] = ntype

        if "visible" in n and not isinstance(n["visible"], bool):
            rep.error(f"{where}/visible: 必须是 true / false，收到 {n['visible']!r}")
        if "opacity" in n:
            check_number(rep, n["opacity"], f"{where}/opacity", minimum=0, maximum=1)
        if "rotationZ" in n:
            check_number(rep, n["rotationZ"], f"{where}/rotationZ", minimum=-3600, maximum=3600)
        if "scale" in n:
            check_number(rep, n["scale"], f"{where}/scale", minimum=0)
        if "hitThrough" in n and not isinstance(n["hitThrough"], bool):
            rep.error(f"{where}/hitThrough: 必须是 true / false")
        if "parallax" in n:
            # 0.10 起是 0~100 的景深数值（旧版为布尔开关，继续兼容）
            v = n["parallax"]
            if isinstance(v, bool):
                pass
            elif isinstance(v, (int, float)) and 0 <= v <= 100:
                pass
            else:
                rep.error(f"{where}/parallax: 必须是 0~100 的数值（或旧版 true/false）")
        if "parallaxMode" in n and n["parallaxMode"] not in ("global", "custom"):
            rep.error(f"{where}/parallaxMode: 只能是 global / custom")
        if "parallaxAmp" in n:
            check_number(rep, n["parallaxAmp"], f"{where}/parallaxAmp", minimum=0, maximum=12)
        if "parallaxAngle" in n:
            check_number(rep, n["parallaxAngle"], f"{where}/parallaxAngle", minimum=-360, maximum=360)

        if ntype == "container":
            self.container(n, where)
        elif ntype == "image":
            self.image(n, where)
        elif ntype == "text":
            self.text(n, where)
        else:
            self.rect(n, where)

    def _geometry(self, n: dict, where: str, *, required: bool) -> None:
        rep = self.rep
        for key in ("x", "y"):
            if key in n:
                check_number(rep, n[key], f"{where}/{key}")
            elif required:
                rep.error(f"{where}: 缺少必填字段 {key}")
        for key in ("width", "height"):
            if key in n:
                check_number(rep, n[key], f"{where}/{key}", positive=True)
            elif required:
                rep.error(f"{where}: 缺少必填字段 {key}")

    def container(self, n: dict, where: str) -> None:
        if "background" in n:
            check_color(self.rep, n["background"], f"{where}/background")
        if "clip" in n and not isinstance(n["clip"], bool):
            self.rep.error(f"{where}/clip: 必须是 true / false")
        # 根容器没有几何（子节点直接就是画布坐标），嵌套容器必须有
        is_root = where == "root"
        self._geometry(n, where, required=not is_root)
        children = n.get("children")
        if not isinstance(children, list):
            self.rep.error(f"{where}/children: 必须是数组")
            return
        for i, child in enumerate(children):
            self.node(child, f"{where}/children[{i}]")

    def image(self, n: dict, where: str) -> None:
        require_keys(self.rep, n, where, ("x", "y", "width", "height", "src"))
        self._geometry(n, where, required=True)
        src = n.get("src")
        if not isinstance(src, str) or not src:
            self.rep.error(f"{where}/src: 必须是非空字符串")
        elif self.resources and src not in self.resources:
            self.rep.error(f"{where}/src: 资源 {src!r} 不在 resources/ 里"
                           f"（现有：{', '.join(sorted(self.resources))}）")
        if "cornerRadius" in n:
            check_number(self.rep, n["cornerRadius"], f"{where}/cornerRadius", minimum=0, maximum=100)

    def text(self, n: dict, where: str) -> None:
        require_keys(self.rep, n, where, ("x", "y", "width", "height", "text", "fontSize", "color"))
        self._geometry(n, where, required=True)
        if not isinstance(n.get("text"), str):
            self.rep.error(f"{where}/text: 必须是字符串")
        if "fontSize" in n:
            check_number(self.rep, n["fontSize"], f"{where}/fontSize", positive=True, maximum=200)
        if "color" in n:
            check_color(self.rep, n["color"], f"{where}/color")
        if "align" in n and n["align"] not in ALIGNS:
            self.rep.error(f"{where}/align: 必须是 {'/'.join(sorted(ALIGNS))}，收到 {n['align']!r}")
        if "maxLines" in n:
            check_number(self.rep, n["maxLines"], f"{where}/maxLines",
                         minimum=0, maximum=MAX_TEXT_LINES, integer=True)

    def rect(self, n: dict, where: str) -> None:
        rep = self.rep
        require_keys(rep, n, where, ("x", "y", "width", "height", "color"))
        self._geometry(n, where, required=True)
        if "color" in n:
            check_color(rep, n["color"], f"{where}/color")
        shape = n.get("shape", "rect")
        if shape not in SHAPES:
            rep.error(f"{where}/shape: 必须是 {'/'.join(sorted(SHAPES))}，收到 {shape!r}")
        if shape == "path":
            path = n.get("path")
            if not isinstance(path, str) or not path.strip():
                rep.error(f"{where}/path: shape=path 时必须提供 path 数据")
            vb = n.get("viewBox")
            if vb is not None and (not isinstance(vb, str) or not VIEWBOX_RE.match(vb)):
                rep.error(f"{where}/viewBox: 必须是 \"0 0 W H\" 形式，收到 {vb!r}")
            if "strokeWidth" in n:
                check_number(rep, n["strokeWidth"], f"{where}/strokeWidth", minimum=0, maximum=100)
        elif "path" in n:
            rep.warn(f"{where}/path: shape 不是 path，该字段会被忽略")
        if shape in ("polygon", "star") and "sides" in n:
            check_number(rep, n["sides"], f"{where}/sides", minimum=3, maximum=64, integer=True)
        if "cornerRadius" in n:
            check_number(rep, n["cornerRadius"], f"{where}/cornerRadius", minimum=0, maximum=100)
        if "feather" in n:
            check_number(rep, n["feather"], f"{where}/feather", minimum=0, maximum=100)
        if "gradient" in n:
            g = n["gradient"]
            if not isinstance(g, str) or not GRADIENT_RE.match(g):
                rep.error(f"{where}/gradient: 必须形如 linear:角度:0#AARRGGBB;1#AARRGGBB，收到 {g!r}")


def check_manifest(rep: Report, manifest: dict, package_formats: set[str], current_engine: str) -> None:
    fmt = manifest.get("formatVersion")
    if not isinstance(fmt, str):
        rep.error("manifest.json: 缺少 formatVersion")
    elif package_formats and fmt not in package_formats:
        rep.error(f"manifest.json: 包格式 {fmt!r} 不受支持"
                  f"（可用：{', '.join(sorted(package_formats))}）")
    engine_min = manifest.get("engineMinVersion")
    if not isinstance(engine_min, str):
        rep.error("manifest.json: engineMinVersion 必须存在且为字符串")
    elif current_engine and not vle(engine_min, current_engine):
        rep.error(f"manifest.json: 要求引擎 >= {engine_min}，当前引擎 {current_engine}")
    name = manifest.get("name")
    if not isinstance(name, str) or not name.strip():
        rep.warn("manifest.json: name 为空，壁纸库里会显示 UUID")
    units = manifest.get("canvasHeightUnits")
    if units is not None:
        check_number(rep, units, "manifest.json/canvasHeightUnits", positive=True)


# ---------------------------------------------------------------- 交互动

class InteractionsChecker:
    def __init__(self, rep: Report, node_types: dict[str, str]) -> None:
        self.rep = rep
        self.nodes = node_types
        self.clips: set[str] = set()
        self.vars: set[str] = set()

    def check(self, it: dict, fmt: str, accepted: set[str] | None = None) -> None:
        rep = self.rep
        declared = it.get("formatVersion")
        if not isinstance(declared, str):
            rep.error("interactions.json: 缺少 formatVersion")
        elif accepted and declared not in accepted:
            rep.error(f"interactions.json: 格式 {declared!r} 不受支持"
                      f"（可用：{', '.join(sorted(accepted))}）")
        elif not accepted and not vle(declared, fmt):
            rep.error(f"interactions.json: formatVersion={declared!r} 比引擎支持的 {fmt!r} 新")

        self.read_vars(it.get("vars"))
        self.read_animations(it.get("animations"))
        self.read_bindings(it.get("bindings"))
        self.read_functions(it.get("functions"))
        self.read_rules(it.get("rules"))

    def read_vars(self, raw) -> None:
        if raw is None:
            return
        if not isinstance(raw, dict):
            self.rep.error("interactions/vars: 必须是对象（名字 → 数字或 {value,min,max}）")
            return
        for name, v in raw.items():
            where = f"interactions/vars/{name}"
            if isinstance(v, bool) or isinstance(v, str):
                self.rep.error(f"{where}: 必须是数字或 {{value,min,max}}，收到 {v!r}")
                continue
            if isinstance(v, dict):
                for key in ("value", "min", "max"):
                    if key in v:
                        check_number(self.rep, v[key], f"{where}/{key}")
                if "min" in v and "max" in v and v["min"] > v["max"]:
                    self.rep.error(f"{where}: min 不能大于 max")
            self.vars.add(name)

    def read_animations(self, raw) -> None:
        if raw is None:
            return
        if not isinstance(raw, list):
            self.rep.error("interactions/animations: 必须是数组")
            return
        for i, a in enumerate(raw):
            where = f"interactions/animations[{i}]"
            if not isinstance(a, dict):
                self.rep.error(f"{where}: 必须是 JSON 对象")
                continue
            require_keys(self.rep, a, where, ("id", "targetId", "trigger", "tracks"))
            aid = a.get("id")
            if isinstance(aid, str):
                if aid in self.clips:
                    self.rep.error(f"{where}/id: 动画 id 重复 {aid!r}")
                self.clips.add(aid)
            target = a.get("targetId")
            if isinstance(target, str) and self.nodes and target not in self.nodes:
                # 引擎遇到这种动画会整条丢弃，属于常见事故，直接报错
                self.rep.error(f"{where}/targetId: 元素 {target!r} 不存在（动画会被整条丢弃）")
            trig = a.get("trigger")
            if isinstance(trig, dict):
                ttype = trig.get("type")
                if ttype not in ("always", "tap", "func"):
                    self.rep.error(f"{where}/trigger/type: 必须是 always/tap/func，收到 {ttype!r}")
                tid = trig.get("targetId")
                if isinstance(tid, str) and self.nodes and tid not in self.nodes:
                    self.rep.error(f"{where}/trigger/targetId: 元素 {tid!r} 不存在")
            else:
                self.rep.error(f"{where}/trigger: 必须是对象")
            tracks = a.get("tracks")
            if not isinstance(tracks, list):
                self.rep.error(f"{where}/tracks: 必须是数组")
                continue
            for j, t in enumerate(tracks):
                self.track(t, f"{where}/tracks[{j}]")

    def track(self, t, where: str) -> None:
        rep = self.rep
        if not isinstance(t, dict):
            rep.error(f"{where}: 必须是 JSON 对象")
            return
        require_keys(rep, t, where, ("property", "durationMs", "keyframes"))
        if t.get("property") not in TRACK_PROPS:
            rep.error(f"{where}/property: 必须是 {'/'.join(sorted(TRACK_PROPS))}，收到 {t.get('property')!r}")
        if "durationMs" in t:
            check_number(rep, t["durationMs"], f"{where}/durationMs", positive=True, maximum=600000)
        if "loop" in t and t["loop"] not in LOOPS:
            rep.error(f"{where}/loop: 必须是 {'/'.join(sorted(LOOPS))}，收到 {t['loop']!r}")
        kfs = t.get("keyframes")
        if not isinstance(kfs, list):
            rep.error(f"{where}/keyframes: 必须是数组")
            return
        if not kfs:
            rep.warn(f"{where}/keyframes: 空轨道，引擎会忽略")
        last = -1.0
        for k, kf in enumerate(kfs):
            kw = f"{where}/keyframes[{k}]"
            if not isinstance(kf, dict):
                rep.error(f"{kw}: 必须是 JSON 对象")
                continue
            require_keys(rep, kf, kw, ("t", "value"))
            if "t" in kf:
                check_number(rep, kf["t"], f"{kw}/t", minimum=0, maximum=1)
                if isinstance(kf["t"], (int, float)):
                    if kf["t"] < last:
                        rep.warn(f"{kw}/t: 关键帧没有按 t 升序，引擎会自己排序")
                    last = kf["t"]
            if "value" in kf:
                check_number(rep, kf["value"], f"{kw}/value")

    def read_bindings(self, raw) -> None:
        if raw is None:
            return
        if not isinstance(raw, list):
            self.rep.error("interactions/bindings: 必须是数组")
            return
        for i, b in enumerate(raw):
            where = f"interactions/bindings[{i}]"
            if not isinstance(b, dict):
                self.rep.error(f"{where}: 必须是 JSON 对象")
                continue
            require_keys(self.rep, b, where, ("id", "targetId", "property"))
            target = b.get("targetId")
            if isinstance(target, str) and self.nodes and target not in self.nodes:
                self.rep.error(f"{where}/targetId: 元素 {target!r} 不存在")
            if b.get("property") not in BINDING_PROPS:
                self.rep.error(f"{where}/property: 必须是 {'/'.join(sorted(BINDING_PROPS))}，"
                               f"收到 {b.get('property')!r}")
            has_expr = isinstance(b.get("expr"), str) and b["expr"].strip() != ""
            if not has_expr:
                if not isinstance(b.get("source"), str) or not b.get("source").strip():
                    self.rep.error(f"{where}: 既没有 expr，也没有 source（绑定不会生效）")
                for key in ("in", "out"):
                    if key not in b:
                        self.rep.warn(f"{where}/{key}: 线性绑定建议写全 in/out")

    def read_functions(self, raw) -> None:
        if raw is None:
            return
        if not isinstance(raw, dict):
            self.rep.error("interactions/functions: 必须是对象（名字 → {params, steps}）")
            return
        for name, f in raw.items():
            where = f"interactions/functions/{name}"
            if not isinstance(f, dict):
                self.rep.error(f"{where}: 必须是 JSON 对象")
                continue
            steps = f.get("steps")
            if not isinstance(steps, list):
                self.rep.error(f"{where}/steps: 必须是数组")
                continue
            self.actions(steps, f"{where}/steps", depth=0)

    def read_rules(self, raw) -> None:
        if raw is None:
            return
        if not isinstance(raw, list):
            self.rep.error("interactions/rules: 必须是数组")
            return
        if len(raw) > MAX_RULES:
            self.rep.error(f"interactions/rules: 规则数 {len(raw)} 超过上限 {MAX_RULES}")
        for i, r in enumerate(raw):
            where = f"interactions/rules[{i}]"
            if not isinstance(r, dict):
                self.rep.error(f"{where}: 必须是 JSON 对象")
                continue
            require_keys(self.rep, r, where, ("id", "when", "then"))
            when = r.get("when")
            if not isinstance(when, dict):
                self.rep.error(f"{where}/when: 必须是对象")
                continue
            trigger = when.get("trigger")
            if trigger not in RULE_TRIGGERS:
                self.rep.error(f"{where}/when/trigger: 必须是 {'/'.join(sorted(RULE_TRIGGERS))}，"
                               f"收到 {trigger!r}")
            target = when.get("target")
            if isinstance(target, str) and target and self.nodes and target not in self.nodes:
                self.rep.error(f"{where}/when/target: 元素 {target!r} 不存在（这条规则不会触发）")
            if trigger == "gesture":
                g = when.get("gesture")
                if g not in GESTURES:
                    self.rep.error(f"{where}/when/gesture: 必须是 {'/'.join(sorted(GESTURES))}，"
                                   f"收到 {g!r}")
            if trigger == "var":
                name = when.get("var")
                if not isinstance(name, str) or not name:
                    self.rep.error(f"{where}/when/var: var 触发必须写变量名")
                elif self.vars and name not in self.vars:
                    self.rep.warn(f"{where}/when/var: 变量 {name!r} 没有在 vars 里声明")
            expr = when.get("expr")
            if expr is not None and not isinstance(expr, str):
                self.rep.error(f"{where}/when/expr: 条件必须是字符串表达式")
            then = r.get("then")
            if not isinstance(then, list):
                self.rep.error(f"{where}/then: 必须是数组")
                continue
            if len(then) > MAX_ACTIONS_PER_RULE:
                self.rep.error(f"{where}/then: 动作数 {len(then)} 超过上限 {MAX_ACTIONS_PER_RULE}")
            self.actions(then, f"{where}/then", depth=0)

    def actions(self, arr: list, where: str, depth: int) -> None:
        rep = self.rep
        if depth > MAX_LOOP_DEPTH:
            rep.error(f"{where}: 循环嵌套超过 {MAX_LOOP_DEPTH} 层")
            return
        for i, a in enumerate(arr):
            aw = f"{where}[{i}]"
            if not isinstance(a, dict):
                rep.error(f"{aw}: 必须是 JSON 对象")
                continue
            atype = a.get("type")
            if atype not in ACTION_TYPES:
                rep.error(f"{aw}/type: 不支持的动作 {atype!r}"
                          f"（可用：{'/'.join(sorted(ACTION_TYPES))}）")
                continue
            if atype == "setVar":
                if not isinstance(a.get("var"), str) or not a.get("var").strip():
                    rep.error(f"{aw}/var: setVar 必须写变量名")
                elif self.vars and a["var"] not in self.vars:
                    rep.warn(f"{aw}/var: 变量 {a['var']!r} 没有在 vars 里声明")
                if "value" not in a and not (isinstance(a.get("expr"), str) and a["expr"].strip()):
                    rep.error(f"{aw}: setVar 需要 value 或 expr")
            elif atype == "setVisible":
                self.check_target(a, aw)
                if "value" not in a and not (isinstance(a.get("expr"), str) and a["expr"].strip()):
                    rep.error(f"{aw}: setVisible 需要 value 或 expr")
            elif atype == "setProperty":
                self.check_target(a, aw)
                prop = a.get("property")
                if prop not in ACTION_PROPERTIES:
                    rep.error(f"{aw}/property: 必须是 {'/'.join(sorted(ACTION_PROPERTIES))}，"
                              f"收到 {prop!r}")
                if not isinstance(a.get("expr"), str) or not a["expr"].strip():
                    rep.error(f"{aw}/expr: setProperty 必须写表达式")
                if "duration" in a:
                    check_number(rep, a["duration"], f"{aw}/duration",
                                 minimum=0, maximum=5000, integer=True)
                if "durationMs" in a:
                    rep.error(f"{aw}: 字段名应为 duration（毫秒），不是 durationMs")
            elif atype == "playAnimation":
                clip = a.get("clip", a.get("target"))
                if not isinstance(clip, str) or not clip:
                    rep.error(f"{aw}/clip: 必须写动画 id")
                elif self.clips and clip not in self.clips:
                    rep.error(f"{aw}/clip: 动画 {clip!r} 不存在（这条动作会静默失效）")
                if "reverse" in a and not isinstance(a["reverse"], bool):
                    rep.error(f"{aw}/reverse: 必须是 true / false")
            elif atype == "launchApp":
                pkg = a.get("package", a.get("target"))
                if not isinstance(pkg, str) or not pkg.strip():
                    rep.error(f"{aw}/package: 必须写包名")
            elif atype == "openUri":
                uri = a.get("uri")
                if not isinstance(uri, str) or not uri.strip():
                    rep.error(f"{aw}/uri: 必须写链接")
            elif atype == "mediaControl":
                cmd = a.get("command", a.get("media"))
                if cmd not in MEDIA_COMMANDS:
                    rep.error(f"{aw}/command: 必须是 {'/'.join(sorted(MEDIA_COMMANDS))}，收到 {cmd!r}")
            elif atype == "call":
                name = a.get("call")
                if not isinstance(name, str) or not name.strip():
                    rep.error(f"{aw}/call: 必须写函数名")
            elif atype in ("for", "while"):
                body = a.get("do")
                if not isinstance(body, list):
                    rep.error(f"{aw}/do: {atype} 循环必须有 do 数组")
                    continue
                if len(body) > MAX_ACTIONS_PER_LOOP:
                    rep.error(f"{aw}/do: 循环体动作数 {len(body)} 超过上限 {MAX_ACTIONS_PER_LOOP}")
                keys = ("from", "to", "step") if atype == "for" else ("max",)
                for key in keys:
                    if key in a and not isinstance(a[key], (str, int, float)):
                        rep.error(f"{aw}/{key}: 类型不对，收到 {a[key]!r}")
                self.actions(body, f"{aw}/do", depth + 1)

    def check_target(self, a: dict, where: str) -> None:
        target = a.get("target")
        if not isinstance(target, str) or not target.strip():
            self.rep.error(f"{where}/target: 必须写目标元素 id")
        elif self.nodes and target not in self.nodes:
            self.rep.error(f"{where}/target: 元素 {target!r} 不存在（这条动作会静默失效）")


# ---------------------------------------------------------------- 载入

def load_preset(arg: str, rep: Report):
    """返回 (manifest, scene, interactions, 资源名集合)。支持目录或 .lwpkit.json。"""
    path = Path(arg)
    if path.is_dir():
        def read(name: str):
            f = path / name
            if not f.is_file():
                return None
            try:
                return json.loads(f.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                rep.error(f"{name} 不是合法 JSON：{exc}")
                return None

        manifest = read("manifest.json")
        if manifest is None:
            rep.error(f"缺少文件：{path / 'manifest.json'}")
        scene = read("scene.json")
        if scene is None:
            rep.error(f"缺少文件：{path / 'scene.json'}")
        interactions = read("interactions.json")
        res_dir = path / "resources"
        resources = {p.name for p in res_dir.iterdir() if p.is_file()} if res_dir.is_dir() else set()
        return manifest, scene, interactions, resources

    if path.is_file() and path.suffix == ".json":
        try:
            bundle = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            rep.error(f"{path.name} 不是合法 JSON：{exc}")
            return None, None, None, set()
        if not isinstance(bundle, dict) or "scene" not in bundle:
            rep.error(f"{path.name}: 不是预设包（缺少 scene）")
            return None, None, None, set()
        resources = set((bundle.get("resources") or {}).keys())
        return bundle.get("manifest"), bundle.get("scene"), bundle.get("interactions"), resources

    rep.error(f"路径不存在或不是预设：{arg}")
    return None, None, None, set()


# ---------------------------------------------------------------- 自检

def selftest() -> int:
    """确认 schema 跟得上引擎：至少覆盖 gesture 触发、mediaControl、setProperty.duration。"""
    root = Path(__file__).resolve().parent.parent
    rep = Report()
    inter = root / "schema/interactions.schema.json"
    scene = root / "schema/scene.schema.json"
    for f in (inter, scene):
        if not f.is_file():
            rep.error(f"缺少 {f}")
    if not rep.ok():
        print(f"[FAIL] schema 自检：{len(rep.errors)} 个问题")
        for e in rep.errors:
            print(f"  - {e}")
        return 1

    ij = json.loads(inter.read_text(encoding="utf-8"))
    sj = json.loads(scene.read_text(encoding="utf-8"))
    consts = kotlin_constants()

    action = ij["$defs"]["action"]["properties"]
    for key in ("duration", "easing", "reverse"):
        if key not in action:
            rep.error(f"interactions.schema.json: action 缺少 {key}（引擎已支持）")
    rule_when = ij["$defs"]["rule"]["properties"]["when"]["properties"]
    if "gesture" not in rule_when:
        rep.error("interactions.schema.json: rule.when 缺少 gesture 触发")
    elif "swipeLeft" not in rule_when["gesture"].get("enum", []):
        rep.error("interactions.schema.json: rule.when.gesture 枚举缺少 swipeLeft")
    if "mediaControl" not in action["type"].get("enum", []):
        rep.error("interactions.schema.json: action.type 枚举缺少 mediaControl")

    inter_fmt = ij["properties"]["formatVersion"].get("enum", [])
    want_inter = consts.get("INTERACTIONS_FORMAT")
    if want_inter and want_inter not in inter_fmt:
        rep.error(f"interactions.schema.json: formatVersion 枚举缺少引擎当前的 {want_inter}")
    scene_fmt = sj["properties"]["formatVersion"].get("enum", [])
    want_scene = consts.get("SCENE_FORMAT")
    if want_scene and want_scene not in scene_fmt:
        rep.error(f"scene.schema.json: formatVersion 枚举缺少引擎当前的 {want_scene}")

    if rep.ok():
        print(f"[OK] schema 自检通过：场景 {want_scene or '?'}，交互动 {want_inter or '?'}")
        return 0
    print(f"[FAIL] schema 自检：{len(rep.errors)} 个问题")
    for e in rep.errors:
        print(f"  - {e}")
    return 1


# ---------------------------------------------------------------- 入口

def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()

    strict = "--strict" in argv
    args = [a for a in argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 1

    rep = Report()
    consts = kotlin_constants()
    scene_fmt = consts.get("SCENE_FORMAT", "0.3")
    inter_fmt = consts.get("INTERACTIONS_FORMAT", "0.9")
    engine = consts.get("CURRENT", "0.0.0")
    scene_formats = {x for x in consts.get("_scene_formats", "").split(",") if x}
    inter_formats = {x for x in consts.get("_interaction_formats", "").split(",") if x}
    package_formats = {x for x in consts.get("_package_formats", "").split(",") if x}

    manifest, scene, interactions, resources = load_preset(args[0], rep)
    checker = SceneChecker(rep, resources)

    if manifest is not None:
        check_manifest(rep, manifest, package_formats, engine)

    if scene is not None:
        fmt = scene.get("formatVersion")
        if not isinstance(fmt, str):
            rep.error("scene.json: 缺少 formatVersion")
        elif scene_formats and fmt not in scene_formats:
            rep.error(f"scene.json: 格式 {fmt!r} 不受支持"
                      f"（可用：{', '.join(sorted(scene_formats))}）")
        root = scene.get("root")
        if not isinstance(root, dict):
            rep.error("scene.json: 缺少 root 容器对象")
        else:
            checker.container(root, "root")

    if interactions is not None:
        InteractionsChecker(rep, checker.node_by_id).check(interactions, inter_fmt, inter_formats)

    name = (manifest or {}).get("name") or Path(args[0]).name

    if not rep.ok() or (strict and rep.warnings):
        print(f"[FAIL] 预设 {name}：{len(rep.errors)} 个错误，{len(rep.warnings)} 个警告")
        for e in rep.errors:
            print(f"  - {e}")
        for w in rep.warnings:
            print(f"  ! {w}")
        return 1

    c = checker.count
    nodes = sum(c.values())
    print(f"[OK] 预设 {name} 通过校验：{nodes} 个节点"
          f"（rect {c['rect']} / text {c['text']} / image {c['image']} / container {c['container']}），"
          f"场景 {scene_fmt}，引擎 >= {(manifest or {}).get('engineMinVersion', '?')}")
    if interactions is not None:
        print(f"     交互动：{len(interactions.get('animations') or [])} 条动画，"
              f"{len(interactions.get('rules') or [])} 条规则")
    for w in rep.warnings:
        print(f"  ! {w}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
