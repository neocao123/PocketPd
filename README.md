# PocketPd — 触屏版 Pure Data 编辑器

把 [Pure Data](https://puredata.info) 的图形化编程搬到 Android 上：用官方 **libpd for Android**
（`io.github.libpd.android:pd-core:1.2.1-rc6`）负责补丁解析与音频运行，在此之上完整实现了
**适配触屏的补丁编辑器**，并补齐了 **MIDI**（android.media.midi）与 **OSC**（UDP）外设能力。

## 设计语言（VI）

视觉参照 Teenage Engineering 的仪器面板语言，建立三色系统与硬几何规范：

| 令牌 | 值 | 用途 |
|---|---|---|
| 信号橙 `#FF4400` | 唯一强调色 | 选中、信号线、开关 ON、FAB、启动图标连线 |
| 米灰面板 `#E8E8E3` / `#F6F6F2` | 界面铬件 | 工具栏、设置页、卡片 |
| 碳黑 `#1C1C1C` / 墨黑 `#141414` | 画布与丝印 | 画布场、文字、键帽描边 |

- **直角化**：全局圆角压缩为 2/4/6dp（`cut` 切角），对象框、卡片、按钮、FAB、对话框一致
- **比例**：4/8dp 间距网格；键帽统一 40dp 高、工具栏 64dp、FAB 56dp、卡片内距 16dp
- **字体**：三种字形各司其职——丝印标签 11sp 全大写宽字距、数据/文件名等宽、标题等宽粗体
- 画布为深色"仪器场"：碳黑底、暗网格、米白描边对象框，信号线橙粗、消息线灰细

```
┌──────────────────────────────────────────────────────────┐
│  UI 层                                                    │
│   MainActivity(补丁浏览器) / EditorActivity(编辑器) / 设置  │
│   PatchCanvasView —— 自定义触屏画布（编辑/运行双模式）       │
├──────────────────────────────────────────────────────────┤
│  编辑器核心                                                │
│   PatchDocument —— .pd 文件无损解析/回写                    │
│   PdObjectMeta  —— 对象知识表（端口数/信号/GUI 几何）        │
│   Commands      —— 撤销/重做（命令模式）                    │
├──────────────────────────────────────────────────────────┤
│  外设桥                                                    │
│   MidiBridge —— android.media.midi ⇄ [notein]/[noteout]…  │
│   OscBridge  —— UDP OSC ⇄ [r 地址] / [s OSC]              │
├──────────────────────────────────────────────────────────┤
│  运行时                                                    │
│   PdEngine —— libpd 封装（PdAudio 音频、GUI 双向绑定、控制台）│
│   io.github.libpd.android:pd-core（全部 4 种 ABI 原生库）   │
└──────────────────────────────────────────────────────────┘
```

## 构建

1. 用 Android Studio（Hedgehog 以上，含 JDK 17）打开本目录；
2. 首次同步时 Gradle 8.9 / AGP 8.5.2 会自动下载；
3. `Run` 即可安装到设备或模拟器（AAR 含 x86_64，模拟器可用）。

命令行构建（无 Android Studio 时，JDK 17 + `ANDROID_HOME` 指向 SDK）：

```bash
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 声音不响？排查清单

1. **媒体音量为 0**：app 开启 DSP 时会检查并在控制台警告（`WARNING: media volume is 0`），
   实机请先用音量键调高媒体音量；
2. **DSP 未开启**：工具栏喇叭图标需为"有声波"状态，控制台出现 `dsp on (…)`；
3. **信号闸门未打开**：多数补丁用 toggle 控制增益——切到 Run 模式点亮它；
4. 实机若提示 `audio init failed`，app 会自动用更大的缓冲重试（16/32/64 ticks）。

**自动绑定**：libpd 无法模拟"点击 GUI 从 outlet 输出"，控件触摸依赖 receive 符号。
app 在打开补丁时会自动为**完全未绑定**（send/receive 均为空）的 GUI 控件分配 `auto-*`
receive 符号并写入会话副本（原文件不受影响，保存时会一并写入）；因此桌面 Pd 的老补丁
在触摸时也能正常发声。若控件已有 send 符号，请确保补丁里存在对应的 `[r 符号]`。

## 画布手势

- **单指按住空白拖动**：画布实时跟手平移，**松手即停**（无惯性）
- **双指**：捏合缩放 + 双指拖动平移
- **长按**：仅当按住原地抬起时弹出上下文菜单（空白=添加/框选/粘贴，对象=编辑/属性/删除等）；
  **按住后开始拖动则菜单自动取消**、手势无缝转为平移或移动对象——长按计时由画布自行管理，
  与平移完全解耦
- **Edit 模式**：拖对象移动；双击对象改文本、双击空白缩放适配
- **Run 模式**：控件直接触摸演奏；从空白区拖动仍可平移
- 对象密集时建议双指平移，或双击空白"缩放适配"后操作

## 已在模拟器（API 34 x86_64，AEHD 硬件加速）实测

- 补丁浏览器（示例提取/列表/新建）、编辑器渲染（对象框/iemgui 控件/贝塞尔连线/信号线绿色加粗）✓
- 编辑↔运行模式切换；Run 模式下滑块拖动（值实时显示）、toggle、radio 触摸 ✓
- 消息闭环：滑块 → `sendFloat` → `[r]` → `[print]` → 控制台回显 ✓
  （修复：libpd 的 pd→Java 队列必须有人轮询，现由 PdEngine 在补丁打开期间自行轮询，与 DSP 开关无关）
- DSP 开关 `dsp on (48000 Hz)` ✓
- OSC 出站：滑块 → `[s OSC]` → UDP 到宿主机，抓包验证地址/typetag/大端 float ✓
- OSC 入站：宿主机 UDP → `[r synth/freq]`，控制台回显 `osc in /synth/freq 440.0` ✓
- 保存：`.pd` 回写、脏标记、重开持久化 ✓

## 触屏交互设计（参考 Material 触控规范）

- **所有操作点 ≥ 48dp**：连线端口在屏幕上做了与缩放无关的放大热区（22dp）；
- **直接操作**：单指拖动对象/平移画布，双指捏合缩放（0.3×–5×，围绕焦点）；
- **长按 = 上下文菜单**（Material 惯例）：
  - 长按空白处 → 在此处添加对象/消息/注释、框选、粘贴；
  - 长按对象 → 编辑文本、属性、复制、删除；
- **双击**：双击对象改文本，双击空白缩放至全览；
- **编辑 / 运行双模式**（右下角 FAB 切换）：编辑模式做结构操作；运行模式里
  bng/tgl/滑块/radio/数字框直接可触摸演奏，避免与编辑手势冲突 —— 这是触屏
  与桌面 Pd 最大的差异点（桌面靠 modifier 键，触屏没有）；
- **连线**：从端口（进出小圆点）拖出即画线，靠近目标端口有磁吸高亮；单击
  连线即删除（与桌面 Pd 一致）；
- **撤销/重做**覆盖：增删、移动、连线、改文本、粘贴（最多 100 步）。

## GUI 对象的“运行模式”绑定说明

libpd 没有模拟“点击 Pd GUI”的 API，因此 GUI 的触控通过 **send/receive 符号**
完成（这也是 libpd 生态的通用做法）：

- 触摸控件 → 应用向其 **receive 符号** 发值：Pd 会更新控件并把值从出口送进补丁
  （与桌面点击行为一致）；
- 补丁向 receive 符号发值 → 应用收到并刷新屏幕上的控件；
- 若控件只有 send 符号 → 触摸发到 send（外部链路）；
- **本应用新建的 GUI 会自动分配唯一的 send/receive 符号**（如 `tgl-send-3a`），
  开箱即可交互；从桌面导入的补丁保留原有符号，可在属性对话框里修改。

## MIDI

`android.media.midi`（USB / Bluetooth LE / 虚拟设备统一入口），设置页选择设备：

| 方向 | 链路 |
|---|---|
| 设备 → Pd | 字节流 → 运行状态解析器 → `PdBase.sendNoteOn/...` → `[notein] [ctlin] [bendin] [pgmin] [midiin]…` |
| Pd → 设备 | `[noteout] [ctlout] [bendout] [pgmout] [midiout]…` → 编码 → `MidiInputPort.send` |

支持运行状态（running status）与系统实时消息；设备热插拔自动重连/断开。

## OSC

标准 OSC 1.0（UDP，自实现编解码，含 bundle），无第三方依赖：

- **输入**（默认端口 8000）：`/synth/freq 440` → 发给 Pd 的 `[r synth/freq]`
  （地址去掉前导 `/` 作为 receive 名）；无参数的地址发 bang；
- **输出**：补丁里 `[r OSC]`，送入“地址开头的 list”即可：
  `[list /synth/vol 0.5( → [s OSC]`，应用转发到设置的目标主机/端口（默认 9000）。

## .pd 文件格式与无损往返

- 完整支持 `#X obj/msg/text/floatatom/symbolatom/listbox/connect` 与 iemgui
  （bng/tgl/hsl/vsl/vradio/hradio/vu/cnv）的编辑；
- **无法识别的语句**（graph/array、`#N struct` 数据结构、`#X coords` 等）以
  原文块（RawItem）原样保留并按原位置回写 —— 打开→保存不丢内容；
- 连接按对象 id 维护，保存时重新编号，与 Pd 语义一致（注释占索引位）；
- 对象参数逐 token 保留，未编辑的字段（颜色、字号、标签…）原样往返。

## 已知限制 / 路线图

- 嵌套 canvas（graph/array）以整体块显示，可在桌面 Pd 或后续版本中编辑；
- 未知对象的端口数按启发式猜测（`~` 结尾猜信号出口），可在“属性”里手动修正；
- 未实现：虚拟 MIDI 设备注册（让其他 App 与 Pd 互发 MIDI）、abstractions 的
  可视化编辑（运行时 libpd 已支持搜索路径加载）、subpatch（`pd xxx`）下钻。
- externals：把编译好的 `.so` 放入补丁目录并通过 `[declare -lib]` 加载即可
  （libpd 原生能力），应用暂未提供编译环境。

## 目录速览

```
app/src/main/java/com/pocketpd/app/
├── App.kt                    # 进程级运行时持有者
├── MainActivity.kt           # 补丁浏览器
├── editor/                   # PatchCanvasView、EditorActivity、对话框、撤销命令
├── patch/                    # PatchModel（解析/回写）、PdObjectMeta（对象知识表）
├── runtime/                  # PdEngine、MidiBridge、OscBridge、OscCodec
└── util/                     # Settings(SharedPreferences)、PatchStore(文件)
app/src/main/assets/patches/  # 4 个示例补丁（正弦、MIDI 合成、OSC、GUI 演示）
```

依赖：libpd（BSD）、AndroidX / Material Components（Apache 2.0）。
