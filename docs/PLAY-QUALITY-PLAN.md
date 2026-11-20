# 游玩画质优化方案

> 状态：**P0 已实施并在目标机实测通过**（2026-11-20）；P1 未开始
> 编写日期：2026-11-20
> 分支：`feat/play-quality`（从 `master` 切出）
> 关联：`docs/WINE-RUNTIME-NOTES.md`（实测结论与否定结果）、`docs/PERF-PLAN.md`（页面速度，与本文无关）、
> `AGENTS.md`（铁律与改动约定）

---

## 0. 结论先行

画质损失**不在编码端**。`VIDEO_CRF=12` 已接近视觉无损，`RATE_CONTROL_MODE=crf` 时
`VIDEO_BITRATE` 根本不参与码率控制。真正的损失来自三处：

1. **像素被浪费**：画布比游戏窗口大，多出的黑边和 Wine 标题栏一起被编码、被浏览器放大。
2. **游戏按小窗口渲染**：窗口 50%~85% 画布时既不放大也不裁剪，游戏以 700×400 渲染后被放大 2 倍以上。
3. **4:2:0 色度**：文字、线稿、纯色块边缘有彩色溢出，galgame 静态画面下尤其明显。

因此 P0 全是「同样画布下少浪费像素」，不是「把画布做大」——后者受 32 位 Wine 地址空间硬约束（§4）。

链路与损失点：

```
游戏渲染 → wined3d/llvmpipe 合成到 Xvfb → pixelflux 抓屏 → x264 编码
   → WebSocket → 门户桥接 → 浏览器解码 → canvas → CSS 缩放到 stage
```

| 环节 | 现状 | 判断 |
|---|---|---|
| 游戏渲染分辨率 | 窗口 < 画布 50% 才放大 | 50%~85% 不放大，**主要损失** |
| 画布构图 | 画布 = 窗口 + 24，前端不裁剪 | 黑边进编码，**主要损失** |
| Wine 标题栏 | 自绘 30px，`Decorated=N` 未设 | 顶部错位 + 30px 浪费 |
| 色度 | 4:2:0（`video_fullcolor` 默认 false） | 文字边缘彩色溢出 |
| 编码 | CRF 12，paintover CRF 12 | 已足够，paintover 可再降 |
| 帧率 | `30-30` 锁死 | 转场/OP 只有 30fps |
| 码率参数 | `VIDEO_BITRATE=40000,5000-80000` | CRF 模式下**不生效**，死参数 |
| 浏览器缩放 | stage 通常远大于画布 | 前面所有优化被这一步抹平 |

---

## 1. 现状基线（代码位置）

| 项 | 位置 | 当前值 |
|---|---|---|
| 帧率默认 | `deploy/bin/run-game.sh:71` | `FRAMERATE:=30-30` |
| CRF / paintover / keyframe | `deploy/bin/run-game.sh:74` | `12` / `12` / `60` |
| 码率控制模式 | `deploy/bin/run-game.sh:82` | `RATE_CONTROL_MODE:=crf` |
| Xvfb 屏幕 | `deploy/bin/run-game.sh:240-245` | `SCREEN_W/H := WIDTH/HEIGHT` |
| 窗口放大阈值 | `deploy/bin/run-game.sh:32-38` | `ww*wh*2 < cw*ch` 才放大，目标 `画布-24` |
| Selkies 参数 | `deploy/bin/run-game.sh:525-531` | 无 `--video-fullcolor`，`--keyframe-interval 60` |
| 前端裁剪 | `portal/frontend/src/views/PlayView.vue:880` | `cropPlan(box, canvas, null)` —— 恒传 `null`，裁剪分支从未启用 |
| 裁剪算法 | `portal/frontend/src/play-crop.js:17-47` | 已实现 `frameW/frameH/left/top`，有单测 |
| 自动校准 | `portal/src/main/java/io/github/sander_i/service/CanvasPlanner.java:24,51-62,75-79` | `PAD=24`；窗口 < 一半时保留大画布；≥85% 收敛 |
| 推荐画布 | `portal/frontend/src/canvas-hint.js:10-18` | 窗口 + 24，按 8 取整 |
| 门户管理的 env 键 | `portal/src/main/java/io/github/sander_i/service/GameScanService.java:42-45` | 画质键不在其中，写回时原样保留 |
| 帧率默认（接入） | `GameImportValidator.java:100-108` / `GameImportView.vue:31` | `"30"` |

**脚本两份副本**：`deploy/bin/run-game.sh` 与
`portal/src/main/resources/deploy/linux/bin/run-game.sh` 必须逐字节相同（`GameAssetsScriptTest` 校验）。
本文所有涉及 `run-game.sh` 的改动都要同步改两份。

---

## 2. 优化项

### P0-1 前端按窗口裁剪黑边

**问题**：`PlayView.vue:880` 调 `cropPlan(box, canvas, null)`，第三个参数恒为 `null`，
`play-crop.js:32-35` 直接走「按画布比例铺满」的旧分支。`winRect` 已经通过
`/api/games/{id}/window`（`SessionController.java:88-96`）拿到并存在 `refreshWindowRect()`（`:830`），
只是没传给裁剪。

**改法**：传 `winRect.value`，并把 `plan.frameW/frameH/left/top` 用上。
容器尺寸仍是 `plan.viewW × plan.viewH`（铺满 stage），但内部画面按
`frameW/frameH` 放大、按 `left/top` 位移，把窗口外的黑边推出容器外，
由 `.play-viewport`（`styles.css:534-539`，已有 `overflow: hidden`）裁掉。

```js
// PlayView.vue:880
const plan = cropPlan(box, canvas, winRect.value)

// style.textContent 里：
// .video-container  left/top/width/height 用 plan.viewW / plan.viewH（不变）
// video, canvas      width: ${plan.frameW}px; height: ${plan.frameH}px;
//                    left: ${plan.left}px;  top: ${plan.top}px;
```

**预期**：窗口占画布 26%（`tenshi-reboot` 实测）时，有效像素放大 3.8 倍；
占 60% 时放大 1.7 倍。这是单项收益最大的一条。

**风险（最高）**：历史上裁剪导致指针偏移（`PlayView.vue:815-827` 注释），
但那两次实现分别是「改 iframe 布局尺寸」和「transform 缩放 iframe」。
本次只改 iframe **内部** `.video-container` 的尺寸与位置，Selkies 按元素矩形
（`getBoundingClientRect` + 居中留白修正）换算指针，理论上不受影响。
**必须实测指针**，验收见 §5。

**实测发现的两个坑（已修）**：

1. **`#overlayInput` 必须一起改。** Selkies 按容器宽度给它单独算居中留白
   （内联 `left`），只覆盖 `video, canvas` 时输入层缩在中间，两侧各约 47px 的
   可见画面没有输入层，点击落到 `videoStream` 上，事件根本不发。修复是把
   `#overlayInput` 并进同一条选择器。
2. **窗口可能越出画布。** `sugarstyle` 实测窗口 1286×752 对画布 1280×720
   （Wine 没钳住），按整窗缩放会算出比容器小的 frame，画面铺不满、右下留黑缝。
   修复是先把窗口矩形裁到画布内可见部分再算比例；完全不相交时退回旧行为。
   越界部分本来就没被采样，裁掉不丢信息。

**注意**：
- `winRect` 为 `null`（游戏未运行 / `xwininfo` 失败）时 `cropPlan` 自动退回旧行为，不需要额外判空。
- `refreshWindowRect()`（`:829-841`）在进入播放后 1.5s 再兜底取一次，窗口放大/居中后要重新裁剪，这条路径已存在。
- 现有 `play-crop.test.js` 的 4 个用例覆盖了裁剪分支，改前端只需新增一个「容器内偏移」的断言，不需要新算法。

**文件**：`portal/frontend/src/views/PlayView.vue`、`portal/frontend/test/play-crop.test.js`

---

### P0-2 窗口放大阈值与画布校准对齐

**问题**：`run-game.sh:36` 的 `resize_target` 只在 `ww*wh*2 < cw*ch`（窗口不足画布一半）时放大，
而 `CanvasPlanner.java:75-79` 的收敛阈值是 85%。两处不一致，导致 50%~85% 区间
**既不放窗口也不缩画布**：游戏以 700×400 渲染，被浏览器放大到 1400×800。

`GameScriptsTest` 已用 `--source-only` 直接单测 `resize_target`（`GameScriptsTest.java:39-50`），
改阈值要同步改用例。

**改法**：阈值从「一半」改为 85%，与 `CanvasPlanner.java:77` 一致；目标仍是 `画布 - 24`。

```bash
# run-game.sh:36
(( ww * wh * 100 < cw * ch * 85 )) || return 1
```

**预期**：窗口 50%~85% 的游戏放大到接近画布，游戏渲染尺寸提升 1.2~2 倍。

**风险**：放大窗口会改变游戏布局（`CanvasPlanner.java:75-77` 注释提到
「为几十像素再重启不值得」，且实测 `localove2` 会重启两次）。所以阈值必须与
85% 完全一致，不能更激进。

**文件**：两份 `run-game.sh`、`GameScriptsTest.java`（改 3 个用例的边界值）

---

### P0-3 去掉 Wine 自绘标题栏

**问题**：`run-game.sh:239` 注释写着「小屏 1280x720 + Decorated=N（无标题栏）→ 窗口 1228x720 完整可见」，
但代码里**从未设置过** `Decorated`。实际每个窗口顶部有 30px Wine 自绘标题栏，
既占像素又在居中计算里造成错位（`docs/WINE-RUNTIME-NOTES.md` §3.2 的窗口漂移有它一份）。

**改法**：`run-game.sh` 在启动 wine 前写注册表（`wine-fonts.sh:90-91` 已有同样的 `wine reg add` 写法可参考）：

```bash
WINEPREFIX="$WINEPREFIX" WINEDEBUG=-all wine reg add \
  'HKCU\Software\Wine\X11 Driver' /v Decorated /t REG_SZ /d N /f >/dev/null 2>&1 || true
```

幂等（`/f` 强制覆盖），放 §5 wine 环境段、`wineserver -k` 之后。

**预期**：窗口高度 -30px，同画布下游戏占比提升；居中计算不再需要为标题栏补偿。

**风险**：部分引擎依赖标题栏做窗口尺寸协商，去掉后窗口可能变大或位置变化。
`resize_target`（P0-2）与居中逻辑（`:485-489`）会兜住，但要实测一款 Kirikiri 与一款非 Kirikiri。

**文件**：两份 `run-game.sh`

---

### P0-4 开启 4:4:4 全色度

**问题**：Selkies 2.2.0 的 `video_fullcolor` 默认 `false`（4:2:0）。galgame 大量静态文字与线稿，
4:2:0 在细线、彩色文字边缘有明显溢出。

**改法**（已实施）：`run-game.sh` 默认值段加可覆盖开关 `: "${VIDEO_FULLCOLOR:=1}"`，
Selkies 参数段用数组拼装，空数组在 `set -u` 下用 `${FULLCOLOR_ARGS[@]+"${FULLCOLOR_ARGS[@]}"}` 展开：

```bash
FULLCOLOR_ARGS=()
[[ "$VIDEO_FULLCOLOR" == "1" ]] && FULLCOLOR_ARGS=(--video-fullcolor)
# ...
"$SELKIES" --addr 127.0.0.1 --port "$PORT" \
  --framerate "$FRAMERATE" \
  ${FULLCOLOR_ARGS[@]+"${FULLCOLOR_ARGS[@]}"} \
  ...
```

回退：在 `game.env` 写 `VIDEO_FULLCOLOR=0`，不用改代码。

**预期**：文字与线条锐度提升。码率在 CRF 下会自动上升（4:4:4 比 4:2:0 多约 20%~30% 数据），
局域网无压力。

**风险（低）**：Selkies 2.2.0 源码 `settings.py` 确认 x264 软编码支持 4:4:4；
浏览器解码器不支持 High 4:4:4 profile 时，服务端会**为这个客户端自动降回 4:2:0**
（`settings.py` 的 `video_fullcolor` help 原文：「a client whose decoder has no 4:4:4 profile
turns it off for itself」），不会黑屏。可用 `--video-fullcolor=false` 快速回退。

**注意**：不要同时开 `video_10bit`。源码明确说明源是 8-bit 桌面，
10-bit 只在配合 4:4:4 时才有意义，且软件编码要额外转换，CPU 代价高。

**文件**：两份 `run-game.sh`

---

### P0-5 静止画面质量独立调

**问题**：`video_paintover_crf` 默认 `12`（脚本 `run-game.sh:74` 显式传 12）。
galgame 90% 时间画面静止，paintover 直接决定观感，比 `video_crf` 影响更大。

**改法**（已实施）：默认降到 `8`。两处都要改——默认值行与 Selkies 参数的 `:-` 兜底，
否则 `game.env` 未设时仍取 12：

```bash
: "${VIDEO_PAINTOVER_CRF:=8}"
# ...
  --video-paintover-crf "${VIDEO_PAINTOVER_CRF:-8}" \
```

**预期**：静止画面（对话框、立绘）细节明显更好。代价是静止画面单帧更大，
但静止时只发一次，平均码率几乎不变。

**风险**：极低。`use_paint_over_quality` 默认 `true`，功能默认就开着。

**文件**：两份 `run-game.sh`

---

### P1-1 帧率默认放开到 30-60

**问题**：`run-game.sh:71` 默认 `FRAMERATE:=30-30`，锁死 30fps。转场、OP、滚动只有 30fps。

**改法**：

```bash
: "${FRAMERATE:=30-60}"
```

**预期**：动态场景流畅度提升。Selkies 的 `framerate` 是 `初始值,允许范围` 语法，
客户端可在范围内自行调整。

**风险**：CRF 模式下帧率翻倍约等于码率翻倍（局域网可承受）；
软编码 CPU 占用上升，`docs/OPERATIONS.md:140` 记录「每路约 1–2 核编码，32 线程实测 2 路稳定」，
改成 60fps 后并发上限会下降。

**并发决策（2026-11-20）**：目标机定位是**个人游玩项目，无并发需求**，
并发上限下降不构成阻塞，这一项可以按需放开。

**同时要改**：`GameImportValidator.java:100-108` 的默认值 `"30"`、
`GameImportView.vue:31,81` 的 `framerate: '30'`、`Game.java:67` 的 `"30-30"`。
这些默认值三处不一致（`"30"` vs `"30-30"`），改动时统一成 `"30-60"`。

**文件**：两份 `run-game.sh`、`GameImportValidator.java`、`GameImportView.vue`、`Game.java`

---

### P1-2 画质参数进页面

**问题**：`VIDEO_CRF` / `RATE_CONTROL_MODE` / `VIDEO_BITRATE` / `KEYFRAME_INTERVAL` /
`VIDEO_PAINTOVER_CRF` 目前只能手改 `game.env`。`GameScanService.java:38-45` 明确把它们排除在
`MANAGED_KEYS` 之外（写回时原样保留），所以门户改分辨率不会冲掉它们——这是对的，
但也意味着没有任何 UI 入口。

**改法**（最小方案，不碰数据模型）：

1. `Game` 实体加一个 `quality` 字段（`@Column` 存 JSON 或若干独立列）。
2. `GameScanService.MANAGED_KEYS` 加入这五个键，`writeEnv()` 从实体写回。
3. `GameEditView.vue` 分辨率区块下面加「画质」分组：CRF 滑杆（5-30）、
   paintover CRF 滑杆（5-30）、帧率（复用现有输入）、码率模式单选（crf / cbr）。
4. 只对 `cbr` 模式显示码率输入（CRF 下它不生效，见 §3 第 3 条）。

**风险**：中等。`GameEnvPreserveTest.java` 专门测「门户不管理的键写回时保留」，
把画质键纳入管理后要改写这个测试的语义——保留范围从「画质键」缩小到「用户自定义的其它键」。

**优先级建议**：P0 全部落地并验证收益后再做。如果 P0 的默认值已经够用，
这一项可以长期不做。

**文件**：`Game.java`、`GameScanService.java`、`GameEditView.vue`、`GameEnvPreserveTest.java`、
四个语言包

---

### P1-3 画布比 Xvfb 屏幕小 40px（需先验证）

**问题**：`run-game.sh:240-241` 里 `SCREEN_W/H` 默认等于 `WIDTH/HEIGHT`，
Wine 把窗口钳到 `SCREEN_H - 20`（`:476-483` 的告警），游戏原生 720 高会被裁 20px。
`docs/WINE-RUNTIME-NOTES.md` §7.1 已记录这个现象。

**改法（待验证）**：`SCREEN_H="${SCREEN_H:-$(( HEIGHT + 40 ))}"`，`SCREEN_W` 同理。

**必须先验证**：Selkies 用 `--manual-resolution --manual-width/height` 锁定分辨率
（`run-game.sh:526`）。源码 `websockets_mode.py:4648-4656` 显示服务端 manual 模式
**强制覆盖**客户端尺寸，走 xrandr 改桌面尺寸。若 Xvfb 屏幕比 manual 尺寸大，
Selkies 会把桌面改成 manual 尺寸（等于画布），屏幕多出的部分不可见——**没收益**；
若 Selkies 不缩小桌面，则屏幕多出的部分会变成黑边被编码，**更差**。

**验证方法**：单款游戏把 `SCREEN_H` 设为 `HEIGHT + 40` 跑一次，
在游玩页读 `/api/games/{id}/window` 的 `height`，对比改前改后。
只有窗口高度实际变大才采纳。

**风险**：改错会引入缩放或黑边。**未验证前不要改。**

---

### P1-4 音频码率

**问题**：Selkies `audio_bitrate` 默认 128000（128 kbps，Opus）。

**改法**：`run-game.sh` Selkies 参数加 `--audio-bitrate 192000`。

**预期**：BGM 与语音的细节。Opus 128k 已相当好，收益小于前面几项。

**风险**：极低。带宽增加可忽略（约 0.06 Mbps）。

**文件**：两份 `run-game.sh`

---

### P2-1 修正 keyframe interval 单位

**问题**：`run-game.sh:74` 默认 `KEYFRAME_INTERVAL:=60`，注释与变量名暗示「帧」，
但 Selkies 2.2.0 的 `keyframe_interval` 单位是**秒**（`settings.py`：`"Seconds between
scheduled video recovery keyframes"`，默认 `0.0` = 无限 GOP）。当前实际是每 60 秒一个 IDR。

**改法**：确认意图后二选一——要「每 60 秒」就把变量注释改清楚；
要「无限 GOP」（Selkies 默认，码率与画质更稳）就改成 `0`。

**风险**：改成 0 后，客户端重连/丢帧恢复依赖 `REQUEST_KEYFRAME` 往返，
弱网下恢复会慢一点。**当前 60 秒不算错，只是注释与语义不符**，属于文档级修正。

**文件**：两份 `run-game.sh`（注释 + 默认值）

---

### P2-2 H.265 软编码

**问题**：`h264enc` 是当前默认。x265 同码率下画质更好（约 20%~30%）。

**判断**：**不建议**。`docs/OPERATIONS.md:140` 记录「每路约 1–2 核编码」是 x264 的数字，
x265 软编码在 720p 下约 2~3 倍 CPU。目标机（Xeon E5-2650 v2 ×32）虽有余量，
但并发上限会腰斩。且浏览器端 HEVC 解码在 Chrome 上依赖硬件，不可靠。

**结论**：不实施，记录在此避免重复评估。

---

### P2-3 DXVK

**判断**：**暂不实施**。`docs/WINE-RUNTIME-NOTES.md` §5 确认 D3D9 路径是活的
（`d3d9.dll` / `wined3d.dll` / `opengl32.dll` 已加载），但同节也记录：
空闲时 `wined3d_cs` 无负载（30 ms/s 是音频混音），主渲染可能仍是 GDI blit。
换成 Vulkan 走 lavapipe（软件 Vulkan），**未必比 wined3d → llvmpipe 快**，且实测收益未验证。

**结论**：需要单独实验，不进本次范围。

---

## 3. 已排除 / 需修正的既有假设

| 假设 | 实际情况 | 处理 |
|---|---|---|
| `VIDEO_BITRATE=40000,5000-80000` 控制码率 | `RATE_CONTROL_MODE=crf` 下码率参数不参与控制（Selkies `websockets_mode.py:4484-4494` 的 `_settings_applied_summary` 按模式二选一打印，CRF 只打印 `crf N`） | 保留（切 cbr 时有用），但**不要指望它提升 CRF 画质** |
| 画布越大画质越好 | 32 位 Wine 地址空间 4 GiB，大屏实测 1300+ 次 mmap 失败直接崩（`WINE-RUNTIME-NOTES.md` §1.2） | 画布上限受硬件约束，优化方向是「少浪费」不是「做大」 |
| wow64 Wine 能解决画质 | 地址空间确实解决（`VmSize` 2.6 GB → 14.5 GB），但 Kirikiri 汉化补丁的鼠标输入全失效（§2.2） | 仅 `senrenbanka` 类游戏可用，不能作为默认 |
| `KEYFRAME_INTERVAL` 单位是帧 | 是秒 | 见 P2-1 |
| 自动校准把画布缩到窗口 + 24 最优 | 窗口 < 画布一半时缩小画布会让画面更糊，已修（`CanvasPlanner.java:44-62` 的 `keep-large`） | 不要回退这个逻辑 |

---

## 4. 硬约束（改之前必须知道）

1. **32 位 Wine 地址空间 4 GiB**。画布放大到 1920×1080 以上会让部分游戏在
   wined3d 分配 GL buffer 时崩溃。`docs/WINE-RUNTIME-NOTES.md` §1.2 有完整实测数据。
2. **`run-game.sh` 两份副本必须逐字节相同**（`AGENTS.md`「改部署步骤」第 4 条，
   `GameAssetsScriptTest` 会校验）。
3. **每游戏资源隔离**（`AGENTS.md` 铁律 3）：画质参数只能进 `game.env`，
   不能引入跨游戏的共享编码器或共享端口。
4. **脚本里的经验值都有单测兜底**：`resize_target` 有 `GameScriptsTest`，
   `cropPlan` 有 `play-crop.test.js`。改阈值必须同步改用例，不能只改代码。
5. **`VIDEO_CRF` 已是 12**。继续往下调到 8 以下，码率上涨的收益递减，
   且软编码耗时上升。不建议低于 10。

---

## 5. 实施顺序与验收

### 阶段一：去浪费（P0-1 ~ P0-3）

先做这三项，因为它们互相影响：P0-2 放大窗口后 P0-1 的裁剪范围变小，
P0-3 去标题栏后窗口高度变化又影响裁剪坐标。

| 顺序 | 项 | 验收 |
|---|---|---|
| 1 | P0-3 去标题栏 | 启动任一游戏，`xwininfo` 显示窗口高度比改前少 30px；画面顶部不再有标题栏 |
| 2 | P0-2 放大阈值 | `cd portal && mvn test -Dtest=GameScriptsTest` 通过；实测窗口 50%~85% 的游戏被放大 |
| 3 | P0-1 前端裁剪 | **指针必须实测**：在游戏内依次点左上、中心、右下三个按钮，落点正确；再拖动一次对话框验证连续拖拽；`cd portal/frontend && npm test` 通过 |

**P0-1 的指针验证不通过就整体回退**，不要试图用坐标补偿硬修（`PlayView.vue:815-827`
记录了两次失败尝试：改 iframe 尺寸偏 +140 画布像素，transform 缩放偏 1.48 倍）。

### 阶段二：编码参数（P0-4、P0-5）

| 顺序 | 项 | 验收 |
|---|---|---|
| 4 | P0-4 4:4:4 | 同一画面截图对比文字边缘；日志里出现 `h264enc` 且无 profile 回退告警 |
| 5 | P0-5 paintover CRF 8 | 静止画面截图对比；CPU 占用无明显上升 |

**回退方式**：两者都可以单游戏回退——在 `game.env` 写 `VIDEO_PAINTOVER_CRF=12`；
4:4:4 需要临时删掉 `--video-fullcolor` 参数（脚本改动，回退要改代码）。

### 阶段三：P1（按需）

P1-1 帧率、P1-2 画质 UI、P1-3 画布尺寸、P1-4 音频码率。
**P1-3 未验证前不要动**。P1-1 要先确认并发需求。

### 每阶段的通用验收

```bash
# 脚本单测（含 resize_target）
cd portal && mvn test -Dtest=GameScriptsTest,GameAssetsScriptTest

# 前端
cd portal/frontend && npm test && npm run check:theme

# 完整构建
cd portal/frontend && npm run build
cd .. && mvn -DskipTests package
```

实测环境记录：目标机 `p-hub-01`（Xeon E5-2650 v2 ×32，128 GB，无 GPU，
llvmpipe 软渲染）。测试游戏至少覆盖一款 Kirikiri（`tenshi-reboot`）
与一款非 Kirikiri，因为窗口行为差异大。

### 阶段一、二实测结果（2026-11-20，目标机 `p-hub-01`）

测试游戏：`tenshi-reboot`（Kirikiri）、`sugarstyle`（BGI）。

| 项 | 验收方法 | 结果 |
|---|---|---|
| P0-3 去标题栏 | `xwininfo` 查窗口几何 | `984x554+12+31`，无标题栏；注册表 `Decorated REG_SZ N` |
| P0-2 放大阈值 | 看日志 `resized` 行 | `496x328 -> 984x554（原窗口不足画布 85%，按画布放大）`；旧 50% 阈值不会触发 |
| P0-4 4:4:4 | 进程 cmdline | 含 `--video-fullcolor`，无 `unrecognized argument` 告警 |
| P0-5 paintover | 进程 cmdline | 含 `--video-paintover-crf 8` |
| P0-1 指针（Kirikiri） | hook `webrtcInput.send` 抓画布坐标 | 页面 (44,48)→(16,34)、(640,382)→(504,308)、(1236,716)→(992,582)，误差 ≤1px |
| P0-1 连续拖拽 | 12 步 `mousemove` 按住拖动 | x 单调 225→815，拖拽中 mask=1，松开 mask=0 |
| P0-1 指针（BGI） | 同上 | 页面 (66,48)→(28,4)、(640,382)→(639,360)、(1214,716)→(1251,716) |
| P0-1 功能 | 真点游戏按钮 | 两款游戏都由标题画面进到游戏内，落点正确 |

指针换算走的是 sink 路径：`_sinkBox()` 用 `videoStream` 的 `getBoundingClientRect`
与 buffer 尺寸算比例（`manual_resolution=true`），与 `_windowMath()` 无关，
所以裁剪只改 iframe 内部哪些像素可见，不影响坐标映射。

自动化：`mvn test` 547 通过 / 3 跳过；`npm test` 187 通过；`npm run check:theme` 通过。

**未做**：P0-4 / P0-5 的视觉截图对比（只看参数是否生效，没有再取改前基线截图）；
P1 全部（P1-3 用户要求「纳入实测」，尚未开始）。

---

## 6. 度量方法

改动前后用同一套方法对比，不要凭观感：

```bash
# 1. 窗口几何（改前 / 改后各取一次）
curl -s http://127.0.0.1:8088/api/games/<id>/window

# 2. 有效像素占比 = 窗口面积 / 画布面积，从上面结果算

# 3. 编码参数确认（Selkies 启动行）
grep -o '\--video-crf [0-9]*\|--video-fullcolor\|--framerate [0-9,-]*' \
  ~/galworld/data/logs/<id>.log | head

# 4. 实际码率：游玩页侧栏的 Selkies stats，或看日志里的 settings applied 行
grep 'settings applied' ~/galworld/data/logs/<id>.log | tail -1

# 5. 截图对比（同一画面、同一游戏内位置）
#    用 Selkies 侧栏的截图，或浏览器全屏截图后裁同一区域
```

判据：**有效像素占比**是第一指标（P0-1~P0-3 直接提升它），
**文字边缘锐度**是第二指标（P0-4 提升它），
**静止画面细节**是第三指标（P0-5 提升它）。码率上升本身不是目标。

