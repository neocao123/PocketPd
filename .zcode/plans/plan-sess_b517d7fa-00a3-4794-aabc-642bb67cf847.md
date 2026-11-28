## OSC 远程调试/遥控功能（基于现有 UDP OSC 桥，为将来主从机遥控打基础）

### Context
用户要求：OSC 可读取并修改当前 patch；**console 自动推送到电脑**；OSC 可切 run/edit/lock 模式；经 symbol（保留命名空间）远程操控；配对码自由指定 + 功能总开关（默认关）。已确认：**整补丁文本读写**；RUN/LOCK 收到 patch/set **自动切回 EDIT 再应用**；**console = 设置驱动自动推送**（不用远端先发订阅命令）。

### 现有底座（已核实，全部复用）
- `runtime/OscBridge.kt`：UDP 桥，监听 `settings.oscInPort`（默认 8000），出站目标 `oscOutHost/oscOutPort`；入站 → main 线程 → `PdEngine.handleOscIn`（PdEngine.kt:2773）；启停 = `settings.oscInEnabled`（App.kt:134-138）。
- `runtime/OscCodec.kt`：OSC 编解码现成。出站约定：`[r OSC]` ← `list /addr …` → `oscOutListener`（App.kt:48-51）→ bridge.send。
- 补丁读写：`PatchDocument.toText()`（PatchModel.kt:468）/ `parse()`（:576）；`EditorActivity.toggleMode()`（:1327，sessionDirty 延迟到 RUN 入口重建）；`canvas.mode{EDIT,RUN}` + `locked`（PatchCanvasView.kt:66/111）。
- console：`PdEngine.log()`（:2744）→ console 列表 + `consoleListener`（:51/:2756）。
- 设置：`util/Settings.kt` 属性模式；`ui/SettingsActivity.kt` 已有 OSC 区。

### 协议（保留命名空间 `/pocketpd/`，首个参数一律为验证码）
| 入站 | 应答（发回包源地址） |
|---|---|
| `/pocketpd/hello <code>` | `/pocketpd/info <mode> <locked> <dirty> <objcount> <patchName>` |
| `/pocketpd/patch/get <code>` | `/pocketpd/patch/chunk <i> <n> <text…>`（1400B 分段） |
| `/pocketpd/patch/set <code> <text…>` | 非 EDIT 自动切 EDIT → parse+换 doc+sessionDirty → `/pocketpd/ok patch <n>`；parse 失败 `/pocketpd/error`（音频不动） |
| `/pocketpd/mode <code> edit\|run\|lock\|unlock` | 复用 toggleMode/locked 现有路径 → `/pocketpd/ok mode <state>` |
| `/pocketpd/console/get <code>` | 回发最近若干行 |

**Console 自动推送（本设计核心修正）**：新设置项 `consoleOscEnabled`（默认关）——开启后 `PdEngine.log()` 的每行自动推 `/pocketpd/console <line>` 到 **`oscOutHost/oscOutPort`（复用现有出站目标 = 电脑这边）**，无需远端先发订阅命令；电脑端一个 UDP 监听即可持续收到。出站单向遥测，不涉码（入站命令才验码）。

安全规则：总开关 `remoteCtlEnabled`（默认 **false**）关时 `/pocketpd/*` 原样透传 pd（零回归）；开启还需 `oscInEnabled`；`remoteCtlCode` 为空拒绝武装（设置页校验+调度器双保险）；未过码未知地址静默丢弃（无 oracle）；过码后未知地址回 `/pocketpd/error unknown`。环回：本机补丁 `[r OSC]` 发出的 `/pocketpd/*` 同样拦截本地分发（同一套过码），主从机同一代码路径。

### 改动文件
1. **新增 `runtime/RemoteControl.kt`**：调度器——过码、分发、应答（分段）、console 自动推送 sink。
2. `runtime/OscBridge.kt`：入站监听器签名加发送方 `InetSocketAddress`。
3. `runtime/PdEngine.kt`：`handleOscIn` 首行拦截钩子；`log()` 挂 console 自动推送（经 dispatcher，按设置门控）；新增 `remotePatchController` 接口槽。
4. `editor/EditorActivity.kt`：实现 `RemotePatchController`（patchText=toText()；applyPatchText=parse+换 doc+sessionDirty；setMode=复用 toggleMode/locked），onCreate 注册/onDestroy 注销。
5. `util/Settings.kt` + `ui/SettingsActivity.kt` + layout：`remoteCtlEnabled`/`remoteCtlCode`/`consoleOscEnabled` 三项，落现有 OSC 区（UI 字符串英文："Remote control" / "Pairing code" / "Console to OSC out"）。
6. `App.kt`：出站环回钩子 + 装配 dispatcher。

### 轮次（一轮一提交：build+aapt+install GM800(adb -s)+dumpsys+CHANGELOG+push）
- **Round 1 v349/0.18.45**：三项设置 + OscBridge 发送方地址 + 调度器（hello/mode/过码/环回/**console 自动推送**）+ EditorActivity 模式控制器。
- **Round 2 v350/0.18.46**：patch get/set（1400B 分段）+ console/get。

### 验证（真端到端，不动 UI）
宿主机零依赖 Python OSC 客户端（裸 UDP）发 GM800 wlan0 IP:8000：hello→info；错码→静默；开关关→透传无应答；mode 三态→ok 且状态对应；**电脑端开 UDP 监听，console 推送自动到达**（patch/set 一个含 `[print]` 的小补丁 → mode run → 电脑收到 `/pocketpd/console` 行，一链三证）；patch/get 分段重组 == 设备文本。

### 风险与边界
- 触点在 PdEngine（console 通路 + handleOscIn）= 高危区；本方案即先审材料，不夹带其他改动。音频引擎生命周期零改动（模式走既有 toggleMode）。盒子几何零触碰（TODO 条目 7 仍悬置）；条目 6 不在本轮。
