# AGENTS.md

给在此仓库工作的 AI 助手与贡献者的常驻约束。**这些是硬性规则，不是建议。**
完整背景见 [CODE.txt](CODE.txt)，交付物说明见 [README.md](README.md)。

## 1. 阶段闸门（最高优先级）

项目当前处于**阶段 2**（阶段 0/1 已完成，2a 动画引擎已交付；2b 时间轴 UI、2c 打断策略未做）。每个阶段严格串行，不允许跳跃或提前实现：

| 阶段 | 允许引入的东西 |
|---|---|
| 0 ✅ | 壁纸服务、EGL/GLES2 渲染、scene 格式与解析、内置预设 |
| 1 ✅ | 画布、属性面板、预设保存/加载 |
| 2 🔄 当前 | 关键帧/缓动/点击触发已完成（2a）；时间轴 UI（2b）、打断策略（2c）未做 |
| 3 | 点击动作、启动应用、全局变量、屏幕滚动视差、触摸坐标进着色器 |
| 4 | 电池/电源/屏幕/网络/时间/传感器变量 |
| 5 | 分享流程、社区验证 |

在阶段 2 里出现滚动视差、启动应用、全局变量、系统广播或传感器 = 错误，即使它能跑。

> **例外已登记**：视差（倾斜）驱动属于阶段 4 内容，但已按用户要求在阶段 2 期间提前交付。
> 详见 §9「阶段 4 提前实现登记」。除该登记项外，本闸门仍然有效。

## 2. 格式改动必须三件套同步

改动 `scene.json` / 未来 `interactions.json` 的结构时，同一次提交必须同时更新：

1. `scene/EngineVersion.kt` 里的版本常量；
2. `schema/*.schema.json`；
3. `SceneParser` / `PresetStore` 的解析与**兼容性检查**（含旧预设的迁移路径）。

只改其中一处视为未完成。

## 3. 事件总线纪律（2c / 阶段 3 生效，现在就要守住设计）

> 现状（2a）：点击事件由 `LwpWallpaperService.onTouchEvent` 直接交给 `RenderThread` 的
> `onTapSurface`，只走「命中 → 播放动画」这一条最短路径。**统一事件总线在 2c 引入**；
> 届时点击、滚动、变量变化等所有触发源都必须汇入同一条总线，这是最需要提前守住的设计。

- 不允许在 `onTouchEvent` / `BroadcastReceiver` / `SensorEventListener` 里直接修改元素属性。
- 回调只做两件事：写线程安全的状态，发规范化事件。
- 一切交互表达为「事件源 → 条件/修饰 → 动作」。

## 4. 渲染线程规则

- GL 调用只发生在 `RenderThread`（EGL 上下文绑定在该线程）。
- 不允许在渲染循环里做文件 IO、JSON 解析或加锁等待外部事件。
- 共享状态用 `synchronized(lock)` 保护，粒度到字段，不做长临界区。
- 没有变化就不要画帧：静止场景不允许出现 60fps 空转。

## 5. 功耗红线（每次改动都要回答）

> 壁纸不可见时，这段代码是否产生了任何唤醒、计时器、传感器回调或绘制？

`onVisibilityChanged(false)` 之后必须做到：无绘制、无传感器、无定时器、无广播接收。
新引入任何系统数据源，都必须有对应的注销路径。

## 6. 代码约定

- Kotlin，包名 `com.loomwall.wallpaper`（占位名，改名时同步 `applicationId`、`namespace`、目录）。
- **零第三方依赖**是当前策略：场景解析用平台自带 `org.json`。新增依赖必须满足
  Apache 2.0 / MIT、活跃维护、无强制商业条款，且不得作为核心必需项引入闭源 SDK。
- 注释写**为什么**，不写「这里给 x 赋值为 1」。中文注释可以，源文件统一 UTF-8。
- `.cmd` 脚本必须 **ASCII-only + CRLF**：cmd.exe 按 OEM 代码页解析批处理，UTF-8 中文会破坏解析。
- 失败要可定位：解析错误带 JSON 路径，不兼容要说明原因，不静默失败。

## 7. 构建与校验

```cmd
build.cmd                          :: debug APK
build.cmd clean assembleDebug      :: 干净重建
powershell -File tools\run-tests.ps1   :: 不受管道限制的单元测试入口
```

工具链在 `.toolchain/`（不入库），用 `tools\bootstrap.cmd` 可重建。
构建成功不等于功能正确：涉及渲染的改动，必须说明「在什么设备/模拟器上看到了什么」。

## 8. 明确的非目标

- 不做商业化：无订阅、广告、内购；赞助不换特权。
- 不做 iOS。
- 不在手机上做节点连线式逻辑图编辑器（用规则行 + 时间轴）。
- 不在阶段 0 引入音频播放、天气、位置。

## 9. 阶段 4 提前实现登记

为了让后来的人看懂「代码里有传感器，但文档说阶段 2」这件事**是有意为之**，不是漏改：

| 项 | 内容 |
|---|---|
| 功能 | 倾斜视差（分层位移）：`scene/Parallax.kt` + `engine/TiltSource.kt` + `SceneRenderer.withParallax` |
| 原因 | 用户要求先在桌面端按"壁纸引擎"手感做出可调效果，再同步到手机 |
| 阶段归属 | 阶段 4（电池/电源/屏幕/网络/时间/**传感器**）|
| 交付时间 | 阶段 2 期间（2026-10-27） |

**已按阶段 4 标准完成的部分**

- 功耗：仅 `onVisibilityChanged(true)` 时注册传感器；可见性为 false 立刻注销并归零；30Hz 节流（`MIN_INTERVAL_NS`）；死区过滤；倾斜变化小于阈值不重绘。
- 数据源：用 `TYPE_GRAVITY`（系统融合、Non-wakeup），**不用陀螺仪** —— 角速度积分会漂移。
- 格式三件套：`scene.schema.json` 增加 `parallax / parallaxAngle / parallaxMode / parallaxAmp`；`SceneParser` 解析；`SceneWriter` 回填（保证"解析→写出"对称）。
- 测试：`scene/ParallaxTest.kt`（9 个用例，覆盖"全局关掉时独立层仍生效""跟随者景深只认全局"等语义）。

**遗留的架构债（阶段 3 落地时必须处理）**

> 现状：`TiltSource` 的回调直接调用 `RenderThread.requestRenderForTilt()`，
> **没有经过统一事件总线**。这与 §3 的"一切交互表达为事件源 → 条件 → 动作"不一致。

阶段 3 引入总线后，需要把倾斜回调改为：向总线发 `TiltChanged(x, y)` 规范化事件 → 总线转成
`ResolvedParallax` 覆盖 → 渲染器消费。改动范围约 30 行，不触及格式。

**调试入口**：预设资源目录放 `tilt-debug.txt`（内容 `x y`，如 `0.9 0`）可注入固定倾斜值，
用于把"渲染链路是否正确"和"传感器是否有数据"分开定位。不需要时删除文件即可，不占运行开销。

## 10. 阶段 4/5 提前实现登记：媒体与歌词

同样属于"代码跑在阶段 2，但功能归属靠后阶段"的情况，登记在案以免被当成越界实现：

| 项 | 内容 |
|---|---|
| 功能 | 正在播放的音乐（歌名/歌手/专辑/播放器/播放状态/进度）+ 在线歌词（当前行/下一句） |
| 相关代码 | `net/NotificationRelay.kt`（媒体会话订阅）、`net/MediaApps.kt`（包名→显示名）、`net/LyricsSource.kt`（联网匹配 LRC）、`scene/Lrc.kt`（解析）、`engine/ExternalVars.kt`（落地） |
| 阶段归属 | 阶段 4（系统状态源） |
| 交付时间 | 阶段 2 期间（2026-11-03），用户明确要求先做音乐 |

**权限与开关**

- 元数据依赖「通知使用权」（`NotificationListenerService`），这是 Android 唯一途径；
  没授权时服务不会被绑定，等于零开销。
- 歌词是**可选联网**：按"歌名 + 歌手"匹配网易云公开接口，成功一次即落盘缓存
  （`filesDir/lyrics/*.lrc`），之后重播不再联网。接口属非官方，**任何失败都只等于"没有歌词"**，
  不允许影响渲染。

**功耗约定（必守）**

- 元数据：`MediaController.registerCallback` 事件驱动，零轮询；
- 进度：仅「壁纸可见 + 正在播放」时每 2 秒补一次，`onVisibilityChanged(false)` 立即停；
  可见性通过 `NotificationRelay.setWallpaperVisible()` 传入（同进程静态回调，不跨进程）。

**已知边界**

- 歌词不来自播放器本身（MediaSession 没有歌词字段），是"按歌名猜"的结果，可能匹配到翻唱版本；
- 不做逐字歌词（klyric）与翻译双行；
- 变量全部为**增量**：老预设不写这些变量时行为完全不变。
## 11. 交互动格式 0.8：绑定表达式

绑定从"线性映射"扩展到"可以写公式"，这样"任意起伏形态"不必等官方模板：

```json
{ "id": "b1", "targetId": "bar_3", "property": "height", "expr": "0.6 + 29.4 * audio(3)^0.5" }
```

**规则**

- `expr` 非空时**忽略** `source` / `in` / `out`；三者也就不必写（`source` 可留空）。
- 表达式里可用 `audio(1..8)` / `band(n)` / `bass()` / `mid()` / `treble()` / `level()` / `beat()`，
  加载期由 `scene/BindingExpr.kt` 展开成 `sys.audio.*` 变量名后再求值 —— 求值器本身不认识这些函数。
- 运算符在原有 `+ - * / %` 上新增 `^`（幂，右结合，优先级高于乘除）。
- **加载期校验**：`BindingExpr.validate()` 会拒绝非法段号（`audio(0)`/`audio(9)`）与语法错误，
  错误带 JSON 路径；运行期求值失败则**跳过该条绑定**，不影响其它绑定与渲染。

**三件套**（§2 要求）：`EngineVersion.INTERACTIONS_FORMAT = "0.8"`、`schema/interactions.schema.json`
的 `$defs.binding`（`expr` 与 `source+in+out` 二选一）、`InteractionsParser.parseBinding` 的兼容分支。

**编辑器**：`数据绑定` 节点多一个「公式」输入框；**填了公式就走表达式模式**（不再要单独的"模式"开关，
少一个手输项就少一类填错）。预览直接用同一套求值器算，所以画布上能立刻看到曲线效果而不是"到手机才知道"。
## 12. 阶段 4 提前实现登记：天气与定位

| 项 | 内容 |
|---|---|
| 功能 | 气温 / 体感 / 湿度 / 天气描述 / 风速 / 降水 / 是否白天 / WMO 代码 / 城市 |
| 相关代码 | `net/WeatherSource.kt`（定位 + 联网 + 缓存）、`scene/WeatherCode.kt`（WMO 映射与 JSON 解析，纯逻辑可单测）、`engine/ExternalVars.kt`、`engine/SystemInputHub.kt` |
| 数据源 | Open-Meteo（**无需 API Key**）：`api.open-meteo.com/v1/forecast` + `geocoding-api.open-meteo.com/v1/search` |
| 阶段归属 | 阶段 4（系统状态源） |
| 交付时间 | 阶段 2 期间（2026-11-03），用户要求先做天气 |

**功耗约定（必守）**

- 只有「预设引用 `sys.weather.*`」+「壁纸可见」同时成立才联网；不可见时 `sync()` 会移除唯一那个 30 分钟定时器。
- 定位只读 `getLastKnownLocation`（NETWORK/GPS/PASSIVE 取最新），**不主动发起定位扫描**。
- 联网是单次请求（8 秒超时），失败只打日志；结果落盘 `files/weather-cache.json`，进程重启先显示旧值。

**失败策略**

- 没权限、没网、接口字段变化 → 一律**保留上一次数据**，不清零（天气是装饰信息，不该让壁纸看起来坏了）。
- 从没拿到过数据时：数值为 0、文本为空串；`sys.weather.updated` 返回 `999` 作为"从未更新"的哨兵。

**精确到区**

- `sys.weather.city` 是市（清远市），`sys.weather.district` 是区/县（清城区），想拼一起就写两个占位符。
- 区名**只能**来自 Android `Geocoder` 反向解析（`subLocality`）：Open-Meteo 的地理编码没有区级数据
  （实测 `name=清城区` 返回空）。所以区名为空是正常情况，预设里不要硬依赖它。

**位置可信度**

- `getLastKnownLocation` 可能返回别的城市 / 好几天前的定位，所以只在 **24 小时内**才采用；
  超出就在日志里说明并放弃本次（宁可继续显示旧数据，也不要显示错城市）。
- 中文城市名必须带行政后缀才查得到（接口对 "清远" 返回空、对 "清远市" 命中），
  所以查询会按 `原名 → 原名+市/区/县 → 去掉后缀` 依次试。

**手动兜底**

- MIUI 上定位权限常被拦，所以 App 抽屉里有「天气城市」：填城市名即用地理编码接口，留空 = 跟随定位。
  换城市会先 `clear()` 清掉旧城市的数据与缓存，避免"拉取失败时还挂着外地天气"。

**调试钩子**（都在 `filesDir`，用完即删，与 §9 的 `tilt-debug.txt` 同一套思路）

| 文件 | 作用 |
|---|---|
| `weather-city.txt` | 强制指定城市，跳过定位权限（**一次性**：成功拉取后自动删除）|
| `weather-refresh.txt` | 强制立即刷新一次（否则要等 30 分钟或重启进程），用完自动删除 |
