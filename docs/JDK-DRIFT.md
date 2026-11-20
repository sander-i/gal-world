# JDK 在线升级导致门户子进程失效

> 编写日期：2026-11-20
> 关联：`docs/OPERATIONS.md`（运维）、`docs/ENVIRONMENT.md`（环境搭建）
> 记录一次真实故障的根因、表现与三层修复。

---

## 1. 故障现象

用户在页面上启动 `tenshi-reboot`，**进度条卡在 55% 不动，页面没有任何报错**。

同时：

- 游戏进程本身正常（`systemctl --user is-active galworld-game@tenshi-reboot` 返回 `active`）
- 游戏画面正常（Xvfb 上能看到标题画面）
- 串流端口正常（`ss -tlnp` 显示 9004 在听，`curl -k https://127.0.0.1:9004/...` 返回 200）
- 门户 HTTP 正常（`/api/health` 返回 `{"status":"UP",...}`）

唯一异常：

```bash
curl -sk https://127.0.0.1:8088/api/games/tenshi-reboot/session
# {"id":"tenshi-reboot","state":"UNKNOWN","crashed":false,"streamUrl":null,
#  "streamReady":true,"gameAlive":null}
```

`state` 是 `UNKNOWN`，`gameAlive` 是 `null`。

---

## 2. 根因

### 2.1 时间线

| 时间 | 事件 |
|---|---|
| 09:40:57 | 门户启动，加载 JDK `21.0.13.0.3+1-1~24.04.1` |
| 09:51:23 | `unattended-upgrade` 开始升级系统包 |
| 09:51:59 | `openjdk-21-jre-headless` 升级到 `21.0.13.1+2-1~24.04.1` |
| 14:19 | 用户报告进度条卡 55% |

### 2.2 机制

Java 进程 `fork()` 子进程时会 exec `$JAVA_HOME/lib/jspawnhelper`。门户内存里还是旧 JDK
的镜像，但磁盘上的 `jspawnhelper` 已被换成新版本，两者版本号不匹配：

```
Incorrect Java version: 21.0.13.0.3+1-1-24.04.1
jspawnhelper version 21.0.13.1+2-1-24.04.1-24.04.1
This command is not for general use and should only be run as the result of a call to
ProcessBuilder.start() or Runtime.exec() in a java application
```

后果：**所有 `ProcessBuilder` / `Runtime.exec` 调用失败**。

证据：`/proc/<portal-pid>/exe` 指向 `(deleted)` 的旧 inode。

### 2.3 为什么表现为「卡 55%」

链路：

1. `SystemdSessionManager.activeStates()` 调 `systemctl --user list-units ...` —— 子进程失败，返回空
2. `statusFrom()` 的 switch 只认 `active`/`inactive`/`failed`，其余落到 `default -> UNKNOWN`
3. `/api/games/<id>/session` 返回 `state: "UNKNOWN"`
4. 前端 `use-game-launch.js` 只在 `state === 'RUNNING'` 时推进到 `stream` 阶段
5. 进度条停在 `start` 阶段上限 —— 正好 **55%**（`launch-progress.js` 的 `RANGES.start = [8, 55]`）
6. 前端只在 `FAILED`/`crashed`/`STOPPED` 时收尾，`UNKNOWN` 落进「继续等」，等到 90 秒超时才提示

### 2.4 为什么潜伏 4 小时

`needrestart` 的配置是 `$nrconf{restart} = 'i'`（interactive）。在 unattended 模式下
它**不会自动重启守护进程**，所以门户一直带着已失效的 fork 能力运行，没有崩溃、
HTTP 也一直正常。日志里只有 stderr 的 `Incorrect Java version` 噪音，
没有任何 ERROR 级别记录。

---

## 3. 三层修复

### 3.1 门户自愈：`JdkDriftWatchdog`

`portal/src/main/java/io/github/sander_i/service/JdkDriftWatchdog.java`

启动时记下 `java.home` 目录的 mtime，之后每 60 秒比对。不一致就 `System.exit(1)`，
由 unit 的 `Restart=on-failure` 拉起一个与磁盘 JDK 一致的新进程。

设计要点：

- **比对目录 mtime 而不是版本字符串**：安全补丁可能只改 build 号，也可能同版本重装。
  apt 升级是「解包到临时目录再改名」，`bin/java` 的路径不变但 inode 会变。
- **读不到 mtime 时不判定**（`drifted()` 里两侧都必须 > 0）：目录被删、权限变化
  都不该误杀进程，宁可漏报一次。
- **用 `System.exit(1)` 而不是 `SpringApplication.exit()`**：后者走正常关闭路径、
  退出码为 0，`Restart=on-failure` 不会触发。
- **不主动停游戏会话**：会话是独立 systemd 单元，门户重启不影响它们。

关闭方式：`galworld.jdk-watchdog-enabled=false`。

### 3.2 前端兜底：`UNKNOWN` 超时判失败

`portal/frontend/src/launch-progress.js` 的 `startVerdict()`：

```js
if (elapsedMs <= graceMs) return 'wait'
if (st.state === 'STOPPED' || st.state === 'UNKNOWN') return 'fail'
```

宽限期 5 秒（`STOPPED_GRACE_MS`），覆盖 systemd 的瞬时值。
超时后 `use-game-launch.js` 给出区分文案：

> 无法读取会话状态：门户可能在后台被更新过，请重启门户服务后重试。

比原来的「游戏进程已退出，请到详情页查看日志」更贴合真实原因。

### 3.3 systemd 单元：保留 `Restart=on-failure`

`deploy.sh` 生成的 unit 里已加注释说明这条不能删 —— 它不只是「崩了重拉」，
而是自愈的最后一环。

---

## 4. 运维建议

### 4.1 自动配置（已内置到 `deploy.sh`）

`deploy.sh` 的 `packages` 步骤会调用 `jdk_autoupgrade_off()`，写入：

```
/etc/apt/apt.conf.d/51galworld-jdk-autoupgrade
```

内容：

```
Unattended-Upgrade::Package-Blacklist {
    "openjdk-21-jre-headless$";
    "openjdk-21-jre$";
};
```

要点：

- **用 `Unattended-Upgrade::Package-Blacklist` 而不是 apt preferences pin**：
  前者只影响自动升级，手工 `apt-get install --only-upgrade openjdk-21-jre-headless`
  不受限制，运维打安全补丁时不会被挡住。
- **包名带 `$` 锚定结尾**，避免误伤 `openjdk-21-jre-headless-dbgsym` 之类的兄弟包。
- **独立文件不覆盖发行版配置**：本机可能还承载 GitLab / Jenkins / Nexus 等其它服务，
  改全局 `50unattended-upgrades` / `52unattended-upgrades-local` 风险太大。
- 写入后用 `apt-config dump` 自检，识别不到就告警。

`uninstall-sys` 会删掉这个文件，恢复发行版默认行为。

页面「一键部署」的 `apt-packages` 步骤（`DeployCommands.java`）也内联了同样的写入，
两条路径行为一致。

### 4.2 手工检查

```bash
apt-config dump | grep 'Package-Blacklist::'
sudo unattended-upgrade --dry-run --verbose 2>&1 | grep 'Initial blacklist'
# 期望：Initial blacklist: openjdk-21-jre-headless$ openjdk-21-jre$
```

### 4.3 为什么不用 needrestart 自动重启

把 `/etc/needrestart/needrestart.conf` 的 `$nrconf{restart}` 改为 `'a'`（auto）
也能解决，但会影响整机所有服务，风险远大于收益。本机的实际配置是
`$nrconf{restart} = 'i'`（interactive），unattended 模式下不重启守护进程，
所以故障不会立刻暴露、而是静默潜伏。

### 4.4 排查入口

```bash
# 门户是否带着已删除的 JDK 在跑
sudo ls -l /proc/$(systemctl --user show galworld-portal -p MainPID --value)/exe

# 会话状态是否正常
curl -sk https://127.0.0.1:8088/api/games/<id>/session

# JDK 是否刚被升级
grep -A2 "openjdk" /var/log/apt/history.log | tail -20
```

### 4.3 手工修复

```bash
systemctl --user restart galworld-portal.service
```

重启门户**不会**停止正在运行的游戏会话（独立单元）。

---

## 5. 验证

```bash
# 后端
cd portal && mvn test -Dtest=JdkDriftWatchdogTest

# 前端
cd portal/frontend && npm test

# 全部
cd portal && mvn test
cd portal/frontend && npm test && npm run check:theme
```

实测结果（2026-11-20）：

- `JdkDriftWatchdogTest`：5/5 通过
- 后端全量：533 通过，0 失败，3 跳过
- 前端：177 通过，0 失败
- `check:theme`：通过
- `i18n-check`：4 个语言包各 1618 键，0 缺失

---

## 6. 相关文件

| 文件 | 作用 |
|---|---|
| `portal/src/main/java/io/github/sander_i/service/JdkDriftWatchdog.java` | 自愈检测 |
| `portal/src/test/java/io/github/sander_i/service/JdkDriftWatchdogTest.java` | 判定逻辑测试 |
| `portal/frontend/src/launch-progress.js` | `startVerdict()` 加 UNKNOWN 判定 |
| `portal/frontend/src/use-game-launch.js` | UNKNOWN 专用失败文案 |
| `portal/src/main/resources/i18n/*.json` | 新增文案键 |
| `deploy.sh` | `jdk_autoupgrade_off()` 写 apt 黑名单；unit 模板的 `Restart` 注释 |
| `portal/src/main/java/io/github/sander_i/deploy/DeployCommands.java` | 页面「一键部署」的 `apt-packages` 步骤同步写黑名单 |
| `portal/src/main/java/io/github/sander_i/service/SystemdSessionManager.java` | `statusFrom()` 的状态映射（未改，是问题来源之一） |
