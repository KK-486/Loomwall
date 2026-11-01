# 内置图标库

两套图标，风格互补：

| 类型 | 来源 | 协议 | 风格 |
|---|---|---|---|
| 系统/工具（28 个） | [Tabler Icons](https://tabler.io/icons) | MIT | 24×24 细线描边 |
| 品牌/社交（30 个） | [Simple Icons](https://simpleicons.org) | CC0 1.0 | 实心单色 |

文件在 `svg/`，`catalog.json` 里有名称、中文说明与 `kind`（`system` / `brand`）。

> **品牌图标的商标声明**：图标文件本身是 CC0（公有领域），但微信、QQ、抖音、哔哩哔哩等
> **商标归各自公司所有**。用于"指向该应用"属于合理使用；不要用来暗示官方关联或背书。

## 为什么只存 SVG

手机端 `BitmapFactory` 只认 PNG/JPEG，但**电脑上不需要预渲染**：
网页编辑器在「插入图标」时用 canvas 把 SVG 光栅化成 256×256 的 PNG，
写进预设的 `resources`，推送到手机就是 PNG。

## 增删图标

编辑 `tools/icons/build-icons.js` 顶部的 `ICONS`（系统）或 `BRANDS`（品牌）表，
名字分别取自 https://tabler.io/icons 与 https://simpleicons.org，然后：

```
node tools/icons/build-icons.js
```

脚本会跳过拉不到的名字并打印警告。

## 颜色

图标用 `currentColor` 上色，插入时替换成所选颜色（白 / 深 / 主题黄）。