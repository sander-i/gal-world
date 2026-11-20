# Wine 运行时实测记录（Linux / Selkies）

> 编写日期：2026-11-20
> 分支：`feat/play-smoothness`
> 关联：`docs/ENVIRONMENT.md`（环境搭建）、`docs/OPTIMIZATION-PLAN.md`（功能优化）
> 本文只记录**实测结论与否定结果**，供后来排查时避免重复踩坑。

---

## 0. 为什么有这份文档

「Ctrl 快速跳过对话时游戏很卡」这个问题的排查过程推翻了几个想当然的假设，
也确认了几个方向走不通。这些结论在代码和 git 历史里都看不出来，只能记在这里。

测试环境：`p-hub-01`，Xeon E5-2650 v2 ×32，128 GB 内存，PERC H710 机械阵列（`sda rotational=1`），
无 GPU（llvmpipe 软渲染），游戏 `tenshi-reboot`（天使☆纷扰 RE-BOOT!，雪绫 GPT 汉化 2.0）。

---

## 1. Ctrl 快速跳过卡顿：根因与已排除项

### 1.1 实测数据

同一会话、同一画面，按住 Ctrl 与松开对比：

| 场景 | 更新率 | p50 | p90 | p99 |
|---|---|---|---|---|
| 空闲对白 | 20.6/s | 47ms | 63ms | 90ms |
| Ctrl 跳过 | 6.3–11.2/s | 29–60ms | 363–564ms | 1243–2115ms |

**关键形状**：p10 = 10ms（瞬时 100fps）、p50 = 29ms，但 p99 = 2115ms。
不是「稳定低帧率」，而是「一段极流畅 + 周期性冻结 1–2 秒」。用户感到的卡就是这些长停顿。

### 1.2 根因（perf 实测）

```
45.85%  [JIT]              ← 游戏本体（Kirikiri 脚本解释器）
41.33%  [kernel.kallsyms]  ← 内核
 7.11%  libgallium          ← llvmpipe 软渲染
```

内核 41% 的构成：

```
5.92%  clear_page_erms     ← 内核清零新页
       get_page_from_freelist → vma_alloc_folio → do_anonymous_page
6.26%  asm_exc_page_fault  ← 缺页异常
```

配套：**44,151 次次缺页/秒**，major fault ≈ 0（不是读盘）。
进程是 32 位 PE，`VmSize` 撞到 2.6 GB（4 GiB 上限内），**2,033 个内存映射**。

结论：Ctrl 跳过时游戏反复分配/释放大块匿名内存，32 位地址空间碎片化后
分配器只能在碎片里找，缺页率暴涨，内核清零新页占满 CPU。

### 1.3 已排除的假设（都有实测数据）

| 假设 | 实测结果 | 结论 |
|---|---|---|
| 分辨率太高 | 800×450 / 960×540 / 1008×616 三组结果相同（~11/s） | ✗ 无效 |
| HDD 随机读慢 | 预热 10 GB 到页缓存后 p99 反而更差（2115ms） | ✗ 证伪 |
| vsync 卡帧 | 主线程 `runqueue-wait=1 ms/s`，0% 被抢占 | ✗ |
| llvmpipe 线程不足 | 各线程合计仅 ~70 ms/s | ✗ |
| 编码器慢 | Selkies 仅 7 ms/s（0.7% 单核） | ✗ |
| 游戏主线程停住 | 15 秒采样，0 次 >200ms 停顿 | ✗ 一直在跑 |
| 门户桥接队列积压 | 已修为有界队列（`StreamWsBridge.FrameQueue`） | 已解决，非本次问题 |

### 1.4 分辨率实验为什么无效

`tenshi-reboot.log` 里：

```
[12:09:08] window 14680069 moved 156,50 -> 256,144 (size 496x328)
```

**游戏窗口渲染尺寸是 496×328，与 `game.env` 的 `WIDTH/HEIGHT` 无关。**
改 `WIDTH/HEIGHT` 只改 Xvfb 屏幕大小（黑边），不改游戏渲染尺寸。
所以三组分辨率测的是同一个东西。

---

## 2. wow64 Wine：解决地址空间，但破坏输入

### 2.1 做法

`game.env` 加一行（`wow64` 构建已由一键部署装到 `~/.local/wine11`）：

```
WINE_HOME=/home/<user>/galworld/.local/wine11
```

### 2.2 结果

**地址空间问题确实解决**：

| 项 | 原 wine 9.0 | wow64 wine 11.21 |
|---|---|---|
| 进程位宽 | ELF32 | **ELF64** |
| `VmSize` | 2.6 GB（撞 4 GiB 上限） | **8.4–14.5 GB** |
| 窗口尺寸 | 496×328 | **1008×616**（全屏） |

**但鼠标输入完全失效**：

- 悬停、点击均无反应（画面 hash 不变）
- 键盘仅方向键偶尔有反应
- 弹窗（Windows 原生控件）能点，游戏内自绘按钮不能

### 2.3 结论

wow64 对**依赖 Kirikiri 自绘 UI 的汉化补丁**不可用。已回退。
`senrenbanka` 用 wow64 正常，说明这是**特定补丁的兼容问题**，不是 wow64 本身的问题。

> 注意：排查过程中发现**原 wine 下鼠标注入同样不稳定**（见 §3），
> 所以 §2.2 的「输入失效」可能部分是环境问题，不是 wow64 特有。
> 但 wow64 下窗口变全屏且弹窗点击成功、游戏内点击全失效，差异仍然明显。

---

## 3. Xvfb 无窗口管理器环境下的鼠标注入

### 3.1 现象

在 `Xvfb :4`（无窗口管理器）下，用 XTest 注入鼠标：

| 验证项 | 结果 |
|---|---|
| 指针位置 | 精确落在游戏窗口内（`query_pointer` 的 `child` = 游戏窗口 id） |
| X 事件到达 | strace 确认 `ButtonPress(4)`、`ButtonRelease(5)`、`MotionNotify(6)` 进入进程 |
| 输入焦点 | 已用 `set_input_focus` 设到游戏窗口 |
| 游戏响应 | **无**（画面 hash 不变） |
| 游戏窗口动画 | 有（多帧不同），不是卡死 |

同一个坐标有时有效（弹窗「否」按钮点击成功过多次），有时无效（标题画面按钮从未成功）。

### 3.2 窗口尺寸漂移

`run-game.sh:426` 的钳制告警：

```
WARN: 窗口 1000x611 顶到屏幕高 616（Wine 钳制上限 596），底部画面可能被裁；
      建议先设 HEIGHT=632，仍被钳制就继续加
```

`game.env` 是 `WIDTH=1008 HEIGHT=616`，但 Wine 把窗口钳到 `SCREEN_H-20 = 596`。
实测窗口在 **496×279 / 496×328 / 1000×611** 之间漂移。任何基于固定坐标的自动化都会失败。

### 3.3 对自动化测试的影响

**不要用固定坐标点击游戏内按钮。** 至少要做：

1. 每次点击前重新读 `xdotool getwindowgeometry`
2. 用窗口相对坐标换算到屏幕坐标
3. 点击后校验画面 hash 变化，失败就重试而不是假定成功

---

## 4. 自动取消更新弹窗：不可行

### 4.1 弹窗性质

`tenshi-reboot` 的启动器（20 MB 自绘 exe，非原版 `tenshi_sz.exe`）开场弹
「最新の修正パッチが提供されています。」+「是 / 否」。

**它不是独立 X 窗口** —— `xdotool search --onlyvisible --name ".+"` 只返回游戏主窗口一个。
所以 `run-game.sh:388` 现有实现（按窗口标题匹配 `*更新*|*アップデート*` 等）**永远抓不到它**。

### 4.2 实测矩阵

| 输入方式 | 结果 |
|---|---|
| `xdotool key Escape` | 无效 |
| `xdotool key Tab` | 无效 |
| 原始 XTest `Escape` | 无效 |
| 原始 XTest `Tab` | **时灵时不灵**（重启 50 秒后有效，16 秒后无效） |
| Selkies `_XTestKeyboard` Tab | 时灵时不灵 |
| 点击「否」按钮 | 有效（但需精确坐标，见 §3.2） |

### 4.3 结论

**不实现自动关闭。** 现有 `GALWORLD_DISMISS_UPDATE_DIALOG` 开关保持默认关闭。

如果将来要做，可行路径是：
- 弹窗出现后固定等 10–15 秒（实测弹窗在启动后 ~10.8 秒出现）
- 用像素聚类定位白色按钮行，点最右一簇
- 点完校验画面 hash，失败重试

但 §3.2 的窗口漂移让这个方案脆弱，不建议默认启用。

---

## 5. 渲染路径：D3D9 是活的

之前认为「主渲染器是 GDI/DirectDraw（`SteamDrawDevice.dll`），DXVK 不适用」——
这个判断不完整。

### 5.1 实测加载的 DLL

```
d3d9.dll  d3dx9_43.dll  d3dcompiler_47.dll  wined3d.dll  opengl32.dll
layerExDraw.dll  textrender.dll  AlphaMovie.dll  motionplayer.dll
```

线程：

```
2 × wined3d_cs      ← D3D 命令流线程
30+ × llvmpipe-N    ← Mesa 软渲染线程
```

**`SteamDrawDevice.dll` 并未加载** —— 当前用的是 `layerExDraw.dll`。

### 5.2 结论

D3D9 路径是活的，**DXVK 理论上可试**。但：

- 空闲时 `wined3d_cs` 无负载（30 ms/s 是音频混音），说明 D3D 用得很少
- 主渲染可能仍是 GDI blit，D3D9 只用于部分特效（`AlphaMovie`、`motionplayer`）
- DXVK 换成 Vulkan 后走 lavapipe（软件 Vulkan），未必比 wined3d→llvmpipe 快

**未验证**：DXVK 的实际收益。之前 DXVK 下载多次截断（`unexpected end of file`），未完成安装。

---

## 6. 当前阻塞

鼠标注入在 Xvfb 无 WM 环境下不可靠（§3），无法继续做需要点击的自动化验证。

**待确认**：从浏览器实际打开游戏，能否正常点击？

- 能 → 问题在自动化路径，可以继续试 DXVK
- 不能 → 这是比 Ctrl 卡顿更严重的问题，应先修

---

## 7. 附带发现的可修问题

### 7.1 窗口高度被钳制

`game.env` 的 `HEIGHT=616`，但 Wine 钳到 `SCREEN_H-20 = 596`，窗口在 279/328/611 间漂移。
`run-game.sh:426` 已告警但不会自动修。建议按告警建议把 `HEIGHT` 加到 632 以上再试。

### 7.2 页缓存利用率低

128 GB 内存只用了 11 GB 做 `buff/cache`，10 GB 游戏数据大部分不在缓存里。
虽然预热后 Ctrl 跳过没变快（§1.3），但对首次加载和场景切换应有帮助。
`vm.swappiness=100` 偏高，可考虑降到 10。

---

## 8. 复现方法

```bash
# 1. 启动会话
systemctl --user restart galworld-game@tenshi-reboot.service

# 2. 等弹窗（约 10.8 秒）
sleep 15

# 3. 测 Ctrl 跳过帧率（需 Xvfb 已就绪）
DISPLAY=:4 ~/galworld/venv/bin/python /tmp/probe.py 10

# 4. perf 采样主线程
sudo perf record -F 999 -g -p <game-pid> -o /tmp/perf.data -- sleep 8
sudo perf report -i /tmp/perf.data --no-children --stdio --sort dso

# 5. 缺页率
grep -E "VmRSS|VmSize" /proc/<game-pid>/status
# minflt 从 /proc/<game-pid>/stat 的第 10 个字段读两次相减
```
