#!/usr/bin/env python3
"""校验 LWPKit 预设包（manifest.json + scene.json）是否符合 scene 格式 v0.1。

用途：预设作者和贡献者在提交前自检，避免把「能解析但画不出来」的预设发出去。
无需第三方依赖，Python 3.8+ 即可。

用法：
    python tools/validate_preset.py app/src/main/assets/presets/default
    python tools/validate_preset.py path/to/preset_name

退出码：0 = 通过，1 = 有错误。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

COLOR_RE = re.compile(r"^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
SUPPORTED_FORMAT = "0.1"
SUPPORTED_NODE_TYPES = {"rect"}


class Bag:
    def __init__(self) -> None:
        self.errors: list[str] = []

    def error(self, msg: str) -> None:
        self.errors.append(msg)


def load_json(path: Path, bag: Bag) -> dict | None:
    if not path.is_file():
        bag.error(f"缺少文件：{path}")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        bag.error(f"{path.name} 不是合法 JSON：{exc}")
        return None


def engine_version() -> tuple[str, str]:
    """从 Kotlin 常量里读引擎版本与支持的场景格式，保持单一事实来源。"""
    kt = Path(__file__).resolve().parent.parent / (
        "app/src/main/kotlin/com/lwpkit/wallpaper/scene/EngineVersion.kt"
    )
    current, fmt = "0.1.0", SUPPORTED_FORMAT
    if kt.is_file():
        text = kt.read_text(encoding="utf-8")
        m = re.search(r'CURRENT\s*=\s*"([^"]+)"', text)
        if m:
            current = m.group(1)
        m = re.search(r'SCENE_FORMAT\s*=\s*"([^"]+)"', text)
        if m:
            fmt = m.group(1)
    return current, fmt


def version_tuple(v: str) -> tuple[int, ...]:
    parts: list[int] = []
    for chunk in v.split("."):
        try:
            parts.append(int(chunk))
        except ValueError:
            parts.append(0)
    return tuple(parts)


def check_color(value, where: str, bag: Bag) -> None:
    if not isinstance(value, str) or not COLOR_RE.match(value):
        bag.error(f"{where}: 颜色必须是 #RRGGBB 或 #AARRGGBB，收到 {value!r}")


def check_number(value, where: str, bag: Bag, *, positive: bool = False,
                 min_value: float | None = None, max_value: float | None = None) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        bag.error(f"{where}: 必须是数字，收到 {value!r}")
        return
    if positive and value <= 0:
        bag.error(f"{where}: 必须大于 0，收到 {value}")
    if min_value is not None and value < min_value:
        bag.error(f"{where}: 不能小于 {min_value}，收到 {value}")
    if max_value is not None and value > max_value:
        bag.error(f"{where}: 不能大于 {max_value}，收到 {value}")


def check_rect(node: dict, where: str, ids: set[str], bag: Bag) -> None:
    for key in ("id", "x", "y", "width", "height", "color"):
        if key not in node:
            bag.error(f"{where}: 缺少必填字段 {key}")
    node_id = node.get("id")
    if isinstance(node_id, str):
        if not node_id:
            bag.error(f"{where}/id: 不能为空字符串")
        elif node_id in ids:
            bag.error(f"{where}/id: id 重复 {node_id!r}")
        else:
            ids.add(node_id)
    else:
        bag.error(f"{where}/id: 必须是字符串，收到 {node_id!r}")

    for key in ("x", "y"):
        if key in node:
            check_number(node[key], f"{where}/{key}", bag)
    for key in ("width", "height"):
        if key in node:
            check_number(node[key], f"{where}/{key}", bag, positive=True)
    if "color" in node:
        check_color(node["color"], f"{where}/color", bag)
    if "opacity" in node:
        check_number(node["opacity"], f"{where}/opacity", bag, min_value=0, max_value=1)
    if "visible" in node and not isinstance(node["visible"], bool):
        bag.error(f"{where}/visible: 必须是 true / false，收到 {node['visible']!r}")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 1

    preset_dir = Path(argv[1])
    bag = Bag()
    current_engine, supported_format = engine_version()

    manifest = load_json(preset_dir / "manifest.json", bag)
    scene = load_json(preset_dir / "scene.json", bag)

    if manifest is not None:
        fmt = manifest.get("formatVersion")
        if fmt is None:
            bag.error("manifest.json: 缺少 formatVersion")
        elif fmt != supported_format:
            bag.error(f"manifest.json: formatVersion={fmt!r}，本引擎只支持 {supported_format!r}")

        engine_min = manifest.get("engineMinVersion")
        if not isinstance(engine_min, str):
            bag.error("manifest.json: engineMinVersion 必须存在且为字符串")
        elif version_tuple(engine_min) > version_tuple(current_engine):
            bag.error(
                f"manifest.json: 要求引擎 >= {engine_min}，当前引擎 {current_engine}"
            )

    if scene is not None:
        if scene.get("formatVersion") != supported_format:
            bag.error(
                f"scene.json: formatVersion={scene.get('formatVersion')!r}，"
                f"本引擎只支持 {supported_format!r}"
            )
        if manifest is not None and scene.get("formatVersion") != manifest.get("formatVersion"):
            bag.error("manifest.json 与 scene.json 的 formatVersion 不一致")

        root = scene.get("root")
        if not isinstance(root, dict):
            bag.error("scene.json: 缺少 root 容器对象")
        else:
            if "background" in root:
                check_color(root["background"], "root/background", bag)
            if "visible" in root and not isinstance(root["visible"], bool):
                bag.error("root/visible: 必须是 true / false")
            children = root.get("children")
            if not isinstance(children, list):
                bag.error("root/children: 必须是数组")
            else:
                ids: set[str] = set()
                if isinstance(root.get("id"), str):
                    ids.add(root["id"])
                for index, node in enumerate(children):
                    where = f"root/children[{index}]"
                    if not isinstance(node, dict):
                        bag.error(f"{where}: 必须是 JSON 对象")
                        continue
                    node_type = node.get("type")
                    if node_type not in SUPPORTED_NODE_TYPES:
                        bag.error(
                            f"{where}/type: 不支持 {node_type!r}（v0.1 只支持 rect）"
                        )
                        continue
                    check_rect(node, where, ids, bag)

    name = (manifest or {}).get("name", preset_dir.name)
    if bag.errors:
        print(f"[FAIL] 预设 {name}：{len(bag.errors)} 个问题")
        for err in bag.errors:
            print(f"  - {err}")
        return 1

    rects = len(((scene or {}).get("root") or {}).get("children") or [])
    print(f"[OK] 预设 {name} 通过校验：{rects} 个矩形，格式 {supported_format}，"
          f"引擎 >= {(manifest or {}).get('engineMinVersion')}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
