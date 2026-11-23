# MIGRATION_057.md — pd 0.57 迁移准备调查（v2：CHANGELOG 全文通读后按弧线重写）

## ★ 当前进度与下一步（2026-11-24，round 4 后更新）

**分支状态**：`0.57` 分支。设备在装 v318/0.18.14。0.51 主线冻结于
`freeze-0.51-base`（= 0.17.161/vc303）。

**已完成的轮次**（详见 CHANGELOG 0.18.0–0.18.13）：
- **0.18.0/304**（round 1）：引擎置换——自建 AAR，pin
  libpd@e3cd52fd7517 → pure-data@43a5b8922a35 = pd 0.57.0test1，
  mavenLocal 消费；minSdk 26 靠 glob shim 保住安卓 8。
- **0.18.1/305**（round 2）：slibs 13 库全量重编 0.57 头，28/28 全绿。
- **0.18.2/306**（round 3）：**RC15 全量 else 核**替换 vintage
  else_core（源码失传）——392 对象，vintage 217 类 217/217 可解析，
  vendored 于 `slibs/else-src/full/`（plugdata 用户可对照
  `plugdata/Abstractions/else/`）。
- **0.18.3/307**：`Abstractions.locate` 父目录回退——`else/count` 等
  前缀抽象引用恢复内联。
- **0.18.4/308**：core_wrap 接受 `setup_` 前缀式 setup（rand.f/rand.i/
  rand.u 等点号类名此前从未注册——用户诊断命中）。
- **0.18.5/309**：内联跳过探针（诊断轮）。
- **0.18.6/310**：深度-2 `[args]` 转换 payload 改裸原子（去 `list` 头）。
- **0.18.7/311 → 0.18.8/312**：plugdata tempo.pd 替换实验 → 用户判定
  失败，整体回退（**不要再建议 plugdata tempo.pd**）。
- **0.18.9–0.18.12/313–316**：工作流重建（用户指令）：EDIT 改动只标
  `sessionDirty` 不重启 pd；EDIT 入口 dsp off；RUN 入口重建+开机；
  打开补丁走新 `AudioOpen.LOAD`（tick 冲刷 setup 但 DSP 全程 off，
  打开零破音）。音频状态 = 模式驱动 + 图标为准；RUN 内音频键自由且
  不影响模式切换。
- **0.18.13/317**（round 4，任务一第一轮）：cyclone 0.43.4 → **0.9-5**
  （porres tag 0.9-5，`%TEMP%/srcrepo/pd-cyclone-0.9-5`）。256 类
  （原 230）/209 setups，两 ABI，audit_blobs 全绿。机制增量：
  CYCLONE_OBJ_API setup 前缀、setup 收集器只认定义（single_lib 原型
  泄漏事故）、src_files、strip_boot_pderror（~30 条开机弃用告警）、
  atodb/dbtoa 上岗、buffer~.pd 抽象、sys_geteventno→sched_geteventno
  补丁、EXTRA_C purr 垫片退役、bare line~/pow~/clip 兼容 creator。
  io 审计重做：PdObjectMeta 漂移修正 + 新类行 + 8 条 reg 规则；
  OBJECT_PORTS.md cyclone 段增补；help/cyclone 刷新 193 补丁。
  **教训：%TEMP%/pdaar 是陈旧 libpd 构建，符号审计禁用它**
  （pdgui_vmess/pd_undo/signal_setmultiout 在真 AAR 里都导出）。

**用户已确认**：tempo 在 help 外正常工作 ✓；打开补丁静默 ✓；工作流
往返正常（本轮待最终确认）。tempo-help 内的 `bang → sel` 报错 =
help 自身接线的显示噪音（桌面 pd 同样会报），非故障。

### 下一步任务（两组，按序）

**任务一：换版重编轮（MIGRATION §7 W2.3–W2.5/W2.4，逐库独立轮）**
1. ~~cyclone 0.43.4 → **0.9-5**~~ ✅ 0.18.13/317 完成；
2. ~~fftease 2.5.2 → **3.0.1**~~ ✅ 0.18.14/318 完成（6/7 找回，
   loopsea~ 3.0.1 上游不存在；bare cross~ 归 cyclone，fftease 版走
   fftease/cross~；源码仓 = ericlyon/pd-fftease）；
3. zexy / iemlib 抓当前版（git.iem.at 有反爬墙，手动过）；
4. mrpeach 重编（预期漂移小）。
每库一轮：换源 → 试编 → 修补（修而不删）→ Slibs.VERSION bump →
装机 → CHANGELOG → commit。

**任务二：🄰 决策点（须用户点头才动）**
1. **elsex 退役**：52 类与新 else 核完全重复（同源 RC15）；唯一搬移项
   = knob.c 从 elsex 挪进 else 核构建（else 核目前 excl knob）。
   elsex 与 else 核是两份相同源码树：`slibs/else-src/{,full/}`。
2. 其余 🄰：superosc~/f2s~ 回归上游 mc 版、graph~/spectrograph~/display
   桩回归、timer 看门狗重做（0.57 上待实测）、creb/cxc/bsaylor 去留。

**遗留已知项**：tempo-help 内 `-div` 实例降级（plugdata/新修订去掉了
-div，RC15 tempo.pd 保留）；else/display 裸建时 `[send $1]` 报
couldn't create（无参数原生加载的显示噪音，桌面同构）。



> **状态：纯调查报告（2026-11-23）。不含任何已执行的代码/构建改动。**
> 方法论（v2 修订，用户明令）：CHANGELOG 是项目地图——本次已**全文通读**
> 6403 行 / 294 轮（0.6.2 → 0.17.161）。每项机制按**弧线**判读：引入轮 →
> 事故/返工轮 → 现行终态（最后一次触及该机制的轮次）。“修好了”的宣称
> 一律以弧线终点为准，不以单轮宣称为准。
> 弧线判读的典型陷阱（CHANGELOG 实例）：0.17.34 的 pdClasses 修复被
> 0.17.35 推翻；0.15.40 的 GOP-only 内联被 0.15.41 扩为全量内联；
> 0.14.9 的"help 不建会话"被 0.17.9 反转；0.17.95 的 alloff 被 0.17.96
> 回退；0.17.158/159 看门狗被 0.17.160 整体回退；0.17.53/54 的右键
> 拦截被 0.17.101 的指纹方案取代。
> 判定图例：✅ 保留（无需改）｜🔁 迁移时重新实测/核实｜♻️ 在 0.57 上多余
> （**退役须用户同意**，"修而不删"规则）｜🛠 需要改写｜❓ 未决

## §1 目标引擎

- 来源：`libpd/pd-for-android` master（1.4.4-SNAPSHOT，2026-11-16 活跃）
- 链路：pd-for-android → libpd@e3cd52fd7517 → pure-data@43a5b8922a35
  = **pd 0.57.0-dev**（m_pd.h 版本宏实测）
- 消费方式：无正式 Maven 产物（Maven 线停在 1.2.1-rc6/2021）→
  **自建 AAR**（pin 两级子模块 SHA，走其 gradle 构建，含 oboe）
- 0.57-dev 相对 0.51.3 的关键语义变化（与本 app 相关）：多声道信号
  （0.54+）、`[clone]` 的 `-d/-x/-di/-do`、t_signal 增加 `s_nchans`、
  新 vanilla 对象与新旗标、iemgui hex 颜色/floatatom 新格式
  （0.51.3 上已兼容过，0.57 上更是原生）

## §2 库适配矩阵（哪些版本适配 0.57）

| 库 | 现用快照（弧线终点） | 上游现状（2026-11-23） | 0.57 适配路径 | 判定 |
|---|---|---|---|---|
| **ELSE**（elsex 52 编译类 + else/ 309 抽象） | 弧线：0.14.4 手工集成 **v1.0-beta20**（217 类）→ 0.17.80/81 srcrepo 事故后重建 → 0.17.134 增补 169 抽象 + 新建 **elsex**（52 类，`else-src/pd55` 0.55 头）→ 0.17.136/137/143 补 support/垫片/审计。源码树 = **1.0-0 RC-15** | 最新正式 release **v.1.0-rc14**（2025-09，要求 pd 0.56-1）；RC15 贴 0.56/0.57-dev | RC15 源码对 **0.57 头**重编 elsex——`pd55` 头特例、`signal_setmultiout`/`pdgui_vmess` 等 5 项 no-op 垫片（0.17.137）随 0.57 原生 API 出现而重审；`THISGUI` 颜色字段补丁（knob）是否仍需保留待核 | 🛠 |
| **cyclone** | pd-extended **0.43.4** miXed 快照（0.7.0 引入；0.17.80/81 purr-data 源重建 + 兼容补丁：t_uint→size_t、Scope 光标、sys_hostfontsize 双参、gui_* 六件套 no-op 垫片、purr-data 内部符号 glist_grabx 等） | porres/pd-cyclone 活跃：**0.9-5**（2026-09-05，要求 pd **0.56-5**） | 换 **0.9-5** 重编 + 端口表/帮助重审（0.17.142 的 230 类审计是按 0.43.4 快照做的，换版后须重做） | 🛠 |
| **zexy** | pd-extended 0.43.4（0.17.81 重建；ZEXY_SETUP/`zexy_new` 宏注册修复 0.17.84/91；lpt 平台 stub；ioperm AV 事故记录） | git.iem.at/pd/zexy 活跃（pd-lib-builder） | 当前版重编；**版本号反爬墙抓不到，迁移时手动核对**；`zexy_new` 宏注册的 manifest 生成链（0.17.91 修复）直接适用 | 🛠 |
| **iemlib** | pd-extended 0.43.4（0.17.81 iem_pbank_csv const 修复等） | git.iem.at/pd/iemlib 活跃 | 当前版重编；同上核对 | 🛠 |
| **fftease** | Debian 2.5.2 快照（0.17.81） | ericlyon **v3.0.1** | 直接上 3.0.1 重编（顺带找回 0.17.81 记录的 7 个 3.0-only 对象：cross~/enrich~/loopsea~/pileup~/pvwarpb~/schmear~/residency_buffer~） | 🛠 |
| **mrpeach**（osc/midifile/binfile） | pd-extended 0.43.4（老命名 packOSC） | 无正式发布体系 | 现源码重编 + 漂移修补（预期小） | 🛠 |
| **creb / cxc / bsaylor** | pd-extended 0.43.4（creb 链接静态 libc++） | **失修** | 无上游 0.57 版本——自打补丁或缺席 | ❓ 用户决策 |
| **diy2** | plugdata 0.9 纯抽象 159 .pd | 版本无关 | 直接可用；对象引用面在 0.57 上核对 | ✅/🔁 |
| **tracker / pocketpd 自研**（ym2612~、nes~、sid~、sid6581~、vgm~、opl3~、xm~、sn76489~、mpein、smp~、modseq、instr） | 0.51-4 头 | — | 0.57 头重编。已静态核查：**无硬编码 t_signal 偏移**（源内 [24] 命中均为 mod/sid 文件格式字节）；dsp_add 3 参形态（0.17.83 教训）与 dsp_addv 布局（0.17.128 反汇编核实的是 0.51.3 运行时）→ **0.57 上重新核实 dsp 链布局**；⚠️ **tracker 的 smp~/modseq 仍用 canvas_getcurrent()**（0.17.70 只修了 ym2612~/vgm~） | 🛠 |
| **libpdtweaks** | dlsym `sys_noloadbang` 直写 | — | 0.57 libpd.so 导出符号核实；nativeLoadCore（dlopen+setup）机制引擎无关 | 🔁 |

## §3 CHANGELOG 机制弧线 × 0.57 可行性（按弧线终点判定）

### §3.1 运行时/引擎层

| 机制弧线 | 关键轮次 | 现行终态 | 0.57 判定 |
|---|---|---|---|
| **会话加载与 DSP 生命周期** | 0.13.5 auto-DSP → 0.14.9 computeAudio(true)+scope-go → 0.17.45 首开竞态五层修复（parse 前 ensureAudioOn、无条件 off→parse→on、10s settle、4s/6s 重 bang）→ 0.17.109 edit 静默 → 0.17.110 **AudioOpen 三态**（START/RUN/SYNC；computeAudio-only 软沿、音频线程/sink 不动、线程泄漏根除） | AudioOpen 三态 + ensureAudioOn 先于 parse + 无条件 dsp 周期 + 门控重开（0.17.36，known-class 失败才 reopen，上限 3 次）+ 4s/6s scope-go | ✅ 语义保留；🔁 **时序数值全部重测**（懒启动 ~3.5s、banner 时刻、dsp 沿行为在 0.57 上大概率不同——0.17.45 的五层就是为 0.51.3 的时序量身定的） |
| **loadbang 吞噬对抗** | 0.7.0 sys_noloadbang=0 → 0.14.9 发现 libpd 永久吞 + scope-go 触发器 → 0.15.29 发现 [pd] 块内 loadbang 仍不触发（只救了根画布）→ 0.17.14 每次 openPatch **前**重设标志 | openPatch 前重设 + scope-go 重 bang（120/500ms + 4s/6s） | 🔁 0.57 的 libpd open_patch 行为核实（很可能仍吞——libpd 设计如此）；[pd] 块内 loadbang 缺口是否仍存 |
| **canvas_getcurrent 禁令** | 0.17.69 构造时存 canvas（标准惯例）→ **真机必崩** → 0.17.70 反汇编定案（"pd" 符号绑定查表，churn 下陈旧指针）→ ympp_findfile（open_via_path） | ym2612~/vgm~ 走 open_via_path；**tracker 的 smp~/modseq 仍用 canvas_getcurrent（弧线缺口）** | 🔁 0.57 libpd-ism 重新反汇编；🛠 smp~/modseq 迁移轮一并移植 open_via_path |
| **PdBase 加锁纪律** | 0.17.15（26 处入口统一 synchronized；传感器流竞争吞消息事故） | 全部 PdBase 入口持 PdBase monitor | ✅ 引擎无关（libpd Java API 若变则随之） |
| **多选/长按/右键交互** | 0.17.53→54→101（右键指纹：完整 SOURCE_MOUSE 掩码） | MouseBack 三 Activity 共用 | ✅ 引擎无关 |

### §3.2 会话构建管线（buildInteractiveSession 及周边）

| 机制弧线 | 关键轮次 | 现行终态 | 0.57 判定 |
|---|---|---|---|
| **抽象逐实例内联** | 0.8.0 GOP 内联 → 0.14.8 GOP 内联 $-替换三错重写 → 0.14.15 内联端口穿通（信号线穿过 inlet/outlet）+ block~ 丢弃 → 0.15.40 GOP-only + 延迟线前缀 → 0.15.41 **全量内联**（非 GOP 同治）→ 0.15.43 pdClasses 原生类守卫（iemlib bng.pd 帮助内脏事故）→ 0.17.34/35 三源 pdClasses + DUAL_COMPILE_PROTECT 15 名 → 0.17.149 **不动点循环**（快照盲区）→ 0.17.150 预算 4096 | 不动点内联 + 每 uid 独立 $0/延迟线命名空间 + 原生类不内联 + block~ 丢弃 | ✅ 机制整体保留（0.57 上命名空间隔离的理由不变）；🔁 内联与 0.57 新语义的交互（见 mc 行） |
| **[clone] 单声道直拆** | 0.17.148（parseCloneArgs，voice==1 内联）→ 0.17.149 不动点才能看到内联产生的 clone → 0.17.151 逗号粘连剥离 | voice==1 且文件可解析 → 内联；多声部原生（0.51.3 上=报错） | ♻️ **0.57 上多余（退役须同意）**：clone -d/-do 原生。**语义差异须实测**：内联=多实例（独立 $0/命名空间），-do=多声道打包——用户补丁若依赖内联副作用，退役会变行为 |
| **[args]→initmess 转换** | 0.17.145（会话根画布 canvas_getenv NULL SIGSEGV；[args] 语义对照 initmess 逐字一致）→ 0.17.147 同故障点的第二次崩溃（restore 错位） | 内联时 [args] 全量转 initmess（含 flag 分解并行组、子图块 1:1 换行不重排索引） | ✅ **保留**：只要管线仍内联抽象，会话根画布仍无 env，[args] 仍会踩 canvas_getenv——0.57 上同构风险（0.17.145/147 是 pd 层行为不是 0.51.3 独有，迁移时重新验证一次） |
| **else/ 前缀剥离** | 0.17.134 把 1391 处上游 `else/x` 引用改写为裸名（资产层）→ 0.17.153 会话层剥离规则（双查表失败 + 裸名在 core_classes 才剥）→ 0.17.161 只在真前缀时剥（[/] 修复） | 资产已改写 + 会话层兜底剥离 | 🔁 若迁移时更新 ELSE 资产，**1391 处改写须对新文件重放**（脚本化）；会话层剥离规则保留 |
| **superosc~/f2s~/supersaw~ 单声道替代 + initmess 换惰性 r** | 0.17.144 supersaw~ 自研（上游无此对象）+ MERDA help 换名 → 0.17.150 initmess mute → 0.17.151 逗号 → 0.17.152 单声道替代 → 0.17.155 参数求和 → 0.17.157 连线重定向 | 单声道替代版为现行资产；0.51.3 上游 mc 版不可能跑 | ♻️ 0.57 上游 mc 版可跑——**是否回归上游=放弃我们的接线修正，须用户同意**；supersaw~ 自研无上游对应，保留 |
| **elsex mc 类单声道降级垫片** | 0.17.137（signal_setmultiout no-op → mc 类安全退化单声道；pdgui_vmess 等 5 符号垫片；17 个 mc 类 + knob） | 垫片方案在 0.51.3 上让 52 类全创建 | ♻️ 0.57 原生 API 存在 → 垫片自然消失（重编即愈）；pdgui_vmess 在 0.57 是否导出待核（GUI 桥可能仍缺席 → headless 垫片仍需） |
| **msg pmsg-N 中继 + 深度感知扫描** | 0.15.29 根层 pmsg → 0.16.1 euclid 双腿 → 0.17.147 **深度感知** subpatchBlockRestore（restore 错位 SIGSEGV 事故）+ 文档序 ordinal → 0.17.156 check_pd_connects.py 机械防线 | 深度感知注入 + 文档序 + 双检查器 | ✅ 纯 .pd 文本语义，引擎无关 |
| **信号线不被中继改写** | 0.14.15（widget relay 只承载控制域；信号 cord 保持原端点） | 现行规则按 meta 的 signalInlets/Outlets 判定 | ✅→🔁 **这条规则在 0.57 上恰好是 mc 防线**（多声道 cord=信号 cord，自动绕过 relay）——但前提是端口表的信号口标注对 0.57 的新对象/新版本库仍然正确 → 端口表审计（§3.3）成为 mc 正确性的前置条件 |
| **GOP 显示**（graph~/spectrograph~ 桩） | 0.13.17 内置 else 抽象版 → 0.14.9 上游内脏在 0.51 上崩（array 消息打歪 → Scudo 堆损坏）→ **最小桩**（s~/r~+table+metro）→ 0.14.10/0.15.25/26 频谱迭代（app 侧 hann FFT、撕读修复）→ 0.15.8/9 graph 交互/样式 | 上游 graph~/spectrograph~ 不展开（expandGop 排除），app 侧绘制 + 会话桩 | 🔁 0.57 上 `array` 消息链可能已修 → 上游原版内脏可否回归须实测（回归=弃桩，须同意）；桩本身引擎无关可继续用 |
| **display/display~** | 0.17.134 上游 namecanvas 机制崩 → console 版 → 0.17.135 widget echo → 0.17.138 固定符号 → 0.17.140 私有 tag → 0.17.141 $1→n-1 修正 + 不共享 | 每实例 display-rcv-\<uid\> + bare 双拼写 + $1 修正 | 🔁 上游 display 用 namecanvas+canvas 消息（pd 核心，非 Tcl）——0.57 上是否可跑需实测；app 版 widget 已自成体系，大概率保留 |
| **[random] 兜底 / [table] resize / sinesum 语义** | 0.15.42（random<1 兜底 128）/ 0.15.29（[; name resize N( 实时改容量；sinesum 首参=容量） | 会话层兜底 + 直发消息 | ✅ pd 语义，0.57 同构；🔁 个别消息行为重验 |
| **pocketpd-scope-go / VU 抽头 / 数组名 w\<id\>** | 0.13.1→0.13.2（env~ 被 cyclone 遮蔽改 vanilla 链）→ 0.13.4（closePatch 保留 VU 符号）→ 0.13.6（按需抽头）→ 0.14.9（文档 id 命名） | 现行注入机制 | ✅ 引擎无关；🔁 closePatch 订阅清理在 0.57 Java API 下复验 |

### §3.3 对象知识层（PdObjectMeta / 端口表 / knownClasses）

| 机制弧线 | 关键轮次 | 现行终态 | 0.57 判定 |
|---|---|---|---|
| **vanilla defs 全量审计** | 0.14.15 信号口全面修正 → 0.17.8 对照 0.51.3 源码清查 → **0.17.16 全量机械提取**（0.51-3 源码 30 文件，class_new/inlet_new/outlet_new 逐类） → 0.17.34 变参注册表（**33 批探针在 0.51.3 实测** + 源码核对）→ 0.17.161 [/] 修复 | defs/variadic 双表，按 **0.51 语义**定值 | 🛠 **0.57 上系统性重审**：0.54+ 已知差异（threshold~ 第二出——0.17.34 当轮就注明"0.51 没有 0.54 的第二出"）、新对象入表、变参规则重测（探针方法论照用） |
| **跨库 io 全量审计** | 0.17.140 zexy time/date → **0.17.142 全库 640 类审计**（构造器级：cyclone 230/zexy 107/iemlib 100/creb 47/cxc 33/fftease 41/…；sic_newinlet 信号口规律；~230 行修正）→ 0.17.143 else/elsex 269 类（**else_core vintage 钉死 ~beta19-21**，52 处信号标重写）→ 0.17.135/138 tabreader~/tabwriter~ 修正 | 审计结论绑定**当前各库 vintage** | 🛠 **换库版本 = 审计重做**：cyclone 0.9-5、ELSE RC15 的构造器 io 全部重新核对（0.17.142/143 的方法论与工具直接复用） |
| **knownClasses/manifest** | 0.14.4 else manifest 重生成 → 0.14.8 symlink farm（core_classes.txt）→ 0.17.90 ln 引号修复 → 0.17.91 manifest 回填（zexy_new 宏）→ 0.17.120 build_slibs 回填未管理库 | 双 ABI manifest ≈1328 行 + 符号链接 | ✅ 机制不变；重编后行数随版本变 |
| **同名冲突/PRIORITY** | 0.7.0 逆优先级注册 → 0.14.10 冲突矩阵定案 → 0.17.35 DUAL_COMPILE_PROTECT | diy2 > else > elsex > cyclone > zexy > … 反序加载 | 🔁 换 cyclone/ELSE 版本后冲突面重算（新 cyclone 可能新增/移除裸名） |

### §3.4 引擎无关（迁移不动）

UI 全套（画布手势/三模式/OSD/对话框/撤销/多选/快捷键/鼠标支持 0.17.52-105 弧线）、
PatchDocument 无损往返（0.14.x 弧线）、AI 助手（0.15.0+ 流式弧线）、
MIDI/OSC/串口/传感器桥（0.2.0→0.17.133 弧线；midi-raw/[midi-sys]/mpein 的
pd 侧消费在 0.57 上照常）、文件/存储迁移（0.17.145/146 已完成）、
版本纪律（bump/Slibs.VERSION/Examples.VERSION/PdHelp.VERSION/YMB_VERSION）、
lint_pd.py / check_pd_connects.py / audit_blobs.py / sim_inline.py 工具链、
git 版本机制。

### §3.5 弧线判读示例（为什么必须读全文）

- **0.15.43 的"bng 被换成帮助内脏"**：根因链横跨 0.15.40（目录喂入）→
  0.15.41（全量内联）→ 0.15.42（显示同步——修的是假问题）→ 0.15.43
  （pdClasses 守卫才是真修）。单看 0.15.42 会得出"已修好"的错误结论。
- **0.17.34→0.17.35**：pdClasses lazy 快照把 multiply defined 放回来——
  0.17.26 的修复"一直依赖 pdClasses 几乎为空这个 bug"。
- **0.17.45**：0.17.36 的 reopen 门控"砍掉了首开竞态的唯一自愈路径"——
  门控与窗口化必须并存。
- **0.17.137**：0.17.134 的"knob 需要 GUI 桥编不了"结论被推翻（sys_gui/
  sys_vgui 实际导出）。
- **0.17.149**：0.17.148 的 clone 修复"没有被执行到"（进程未重启 +
  快照循环盲区双重原因）。
- **0.17.63/65/96/160**：多轮"修复"被用户裁决/后续轮整体回退——
  判读现状时这些轮次必须从"现行终态"里剔除。

## §4 迁移新增工作项（0.57 特有）

1. **自建 AAR**（pin 子模块 SHA、gradle 构建链、oboe、AGP 对齐）
2. **mc 时代审计**：relay 规则 × mc cord 的自动化校验；端口表信号口标注
   是 mc 正确性的前置条件
3. **vanilla 新对象/新旗标入表 + 变参重测**（探针方法论照用）
4. **换版库的 io 审计重做**（cyclone 0.9-5、ELSE RC15；0.17.142/143 工具复用）
5. **两台设备全量回归**（AGENTS.md 装机验收纪律）

## §5 风险汇总

- **最高**：creb/cxc/bsaylor 失修无上游 0.57 版本；cyclone 2010→0.9-5 的
  行为差异面 + 审计重做量
- **高**：tracker smp~/modseq 的 canvas_getcurrent 缺口；自建 AAR 构建链；
  0.57 时序重测（0.17.45 五层修复全部押在 0.51.3 的时序上）
- **中**：ELSE RC15→0.57-dev 漂移（knob THISGUI、pdgui 垫片去留）；
  vanilla/变参端口表重审；上游 graph~/display 可否回归的实测
- **低**：diy2、fftease、mrpeach、pmsg/relay 主体、app UI、桥接层

## §6 未决问题

1. ELSE 是否已发 RC16/final（0.57 对齐版）？发布页本轮止于 rc14
2. zexy/iemlib 当前版本号（反爬墙，手动核对）
3. 0.57-dev 的 libpd-ism 清单（canvas_getcurrent/loadbang/懒启动/dsp 沿）
   ——需 0.57 libpd.so 反汇编（拿到自建 AAR 后第一件事）
4. clone 多声道语义 vs 单声道内联在用户补丁上的实际差异——需实测
5. creb/cxc/bsaylor 去留（"修而不删"：须用户决策）
6. 上游 graph~/spectrograph~/display 在 0.57 上能否跑（决定桩与 widget
   echo 的去留）

## §7 修改意见（实施清单，2026-11-23；**待用户批准后执行**）

> 依据：ROADMAP.md 逐轮表 + 本文 §2/§3 弧线。按五个工作流组织，建议
> 分四个版本阶段落地（0.18.x 序列），每阶段一个单主题轮、走完整验收
> （bump/aapt/dumpsys/commit）。标注 🄰=须用户先决策 的条目。

### W0 地基（阶段 1：只换内核，库不动 → 产出 0.57 冒烟 APK）

| # | 修改意见 | 依据 |
|---|---|---|
| W0.1 | clone pd-for-android，**pin 子模块链**（libpd@e3cd52fd7517 → pure-data@43a5b8922a35），本地构建 PdCore AAR；gradle 依赖从 Maven 1.2.1-rc6 切到本地产物 | §1 |
| W0.2 | 构建 PdCore 前核对 **ABI 覆盖**（我们要 arm64-v8a/armeabi-v7a/x86/x86_64 四个；pd-for-android 可能只出部分） | §1 |
| W0.3 | **0.57 libpd.so 反汇编核实清单**（一次做完，写进 PD_VERSIONS.md）：①canvas_getcurrent 是否仍为 "pd" 符号绑定查表；②openPatch 是否仍吞 loadbang（sys_noloadbang 语义）；③调度器懒启动时序（banner/首 tick 时刻）；④dsp 沿是否仍为唯一编译时机；⑤导出符号面（sys_noloadbang/sys_getsr/sys_getblksize/sched_tick/clock_getlogicaltime/signal_setmultiout/pdgui_vmess）；⑥dsp_add/dsp_addv 链布局（0.17.83/0.17.128 的两次教训都在这） | §3.1/§6.3 |
| W0.4 | **Java API 差异核对**：pd-for-android 1.4.x 的 PdBase/PdAudio/PdReceiver 签名与行为（尤其 AudioWrapper.start 是否仍有 0.17.110 记录的"无条件重开 sink/线程"行为——AudioOpen 软沿依赖它） | §3.1 |
| W0.5 | **冒烟 APK**（vanilla only）：0.51.3 与 0.57 双基线跑同一组探针补丁（0.17.34 的 33 批变参探针方法论 + 07.time timer + arp/superosc~），输出《0.57 行为差异表》——后续所有轮次的依据 | 0.17.34/0.17.45 弧线 |

### W1 运行时层（PdEngine/PdTweaks，阶段 1–2）

| # | 修改意见 | 依据 |
|---|---|---|
| W1.1 | AudioOpen 三态与门控重开**语义保留**，按时序实测结果调参（懒启动窗口/settle/re-bang 时刻大概率全变） | 0.17.45 弧线 |
| W1.2 | PdTweaks.setLoadbangEnabled 按 W0.3-② 结果处置：符号在→保留；行为变→调整调用点（openPatch 前重设保留） | 0.17.14 弧线 |
| W1.3 | **tracker 的 smp~/modseq 移植 ympp_findfile（open_via_path）**——0.17.70 同款修法，弧线缺口补齐（这次修在迁移轮一并做，因为要重编） | §2/§3.1 |
| W1.4 🄰 | **timer 冻结问题是否重做看门狗**：0.57 的 m_mainloop/m_callbackscheduler 在 libpd 下可能仍不跑。若仍冻结，按 0.17.159 的设计（显式门控+墙钟追赶）重新实现；若 0.57 行为已同桌面，放弃重做 | 0.17.158–160 弧线 |
| W1.5 🄰 | **stopAudio 是否恢复"同步关 pd dsp 开关"**（0.17.158 引入、0.17.160 回退的那半）——按 0.57 的 dsp 沿实测行为再定 | 0.17.158–160 弧线 |

### W2 slibs 库矩阵（阶段 2：全量重编）

| # | 修改意见 | 依据 |
|---|---|---|
| W2.1 | build_slibs.py `PDSRC` 切到 0.57 头（pure-data@43a5b89/src）；**elsex 的 `inc_first=else-src/pd55` 撤销**（0.57 头自带 mc API，t_signal 矛盾终裁） | §2 |
| W2.2 | **ELSE RC15 重编 elsex**：THISGUI 颜色补丁、pdgui_vmess 等 5 垫片按 W0.3-⑤ 结果重审（0.57 若导出则撤垫片；headless 缺席则保留） | 0.17.137 弧线 |
| W2.3 | **cyclone 0.43.4 → 0.9-5**：源码替换；patches 字典里 0.43.4 专属补丁（t_uint、Scope、sys_hostfontsize、gui_* 六件套）按新源码重写；audit_blobs 重跑 | §2/0.17.81 弧线 |
| W2.4 | **zexy/iemlib 抓当前版**（git.iem.at 手动过反爬墙）重编；ZEXY_SETUP/supports 提取链与 lpt stub 直接复用 | 0.17.84/91 弧线 |
| W2.5 | **fftease 2.5.2 → 3.0.1**（顺带找回 7 个 3.0-only 对象：cross~/enrich~/loopsea~/pileup~/pvwarpb~/schmear~/residency_buffer~） | §2 |
| W2.6 | mrpeach 重编（预期漂移小）；**creb/cxc/bsaylor 逐库试编 0.57**——失败面报用户 🄰 决策（修/暂缓/缺席） | §2/§5 |
| W2.7 | **自研库重编**（tracker/pocketpd 全部对象）+ dsp_add 3 参形态复查 + dsp_addv 布局按 W0.3-⑥ 核实 | 0.17.83/128 弧线 |
| W2.8 | diy2 纯抽象对象引用面核对（在 0.57 上逐对象冒烟） | §2 |
| W2.9 | **audit_blobs.py 全量重跑**（新内核导出面变化）；**Slibs.VERSION 一次性大 bump**（0.17.71/154 教训：修复必须真的到达设备） | 0.17.71/154 弧线 |
| W2.10 | 视情况：0.51-4 头目录与 pd55 头目录归档留证（不删除） | "修而不删" |

### W3 会话构建管线（buildInteractiveSession，阶段 2–3）

| # | 修改意见 | 依据 |
|---|---|---|
| W3.1 🄰 | **[clone] 单声道直拆分支退役**：0.57 原生支持 -d/-x/-di/-do；parseCloneArgs 保留但默认直通原生。⚠️ 多实例 vs 打包声道语义不等价——退役前用用户补丁（arp/superosc~）实测对比 | 0.17.148/149 弧线 |
| W3.2 | **[args]→initmess 转换保留**（内联仍会产生无 env 的会话根画布，0.17.145/147 是 pd 层行为）；0.57 冒烟时复验一次 [args] 原生行为 | 0.17.145/147 弧线 |
| W3.3 | **else/ 前缀剥离保留**；若更新 ELSE 资产，**1391 处引用改写须对新文件重放**（脚本化，别手工） | 0.17.134/153 弧线 |
| W3.4 | **relay × mc 防线**：现有"信号线不被中继改写"（0.14.15）自动保护 mc cord——补一条自动化校验（模拟会话里断言多声道 cord 端点原样），并把"端口表信号口标注"列为 mc 正确性前置（W4.2） | 0.14.15 弧线 |
| W3.5 | **elsex mc 类端口表按 mc 语义更新**（mix~/nchs~/xfade.mc~/sum~ 等 16 类在 0.57 上原生多声道；变参注册表挂真实声道数） | 0.17.137 弧线 |
| W3.6 🄰 | **superosc~/f2s~ 回归上游 mc 版**（0.57 上原生可跑； supersaw~ 自研保留）；**initmess mute 替换去留**取决于 W2.2 的 ELSE 版本 | 0.17.150–157 弧线 |
| W3.7 🄰 | **graph~/spectrograph~ 桩与 display widget echo 是否回归上游**：0.57 上实测 namecanvas/array 链——可跑则提案回归（桩与 echo 是 0.51 限制的工作区），不可跑则原样保留 | 0.14.9/0.17.134 弧线 |
| W3.8 | 深度感知扫描/check_pd_connects/lint_pd/sim_inline：按 0.57 输出格式增量（若有新语句类型） | 0.17.147/156 弧线 |

### W4 知识层（PdObjectMeta/OBJECT_PORTS/帮助，阶段 3）

| # | 修改意见 | 依据 |
|---|---|---|
| W4.1 | **vanilla 对象表增量**：从 0.57 源码枚举 class_new/class_addcreator 全清单（van055.txt 方法论更新为 van057），新增对象入 defs/knownClasses/pdClasses | 0.17.16 弧线 |
| W4.2 | **端口表全面重审**：0.54+ 已知差异先行（threshold~ 第二出——0.17.34 当轮就注明）＋变参规则按 W0.5 探针重测（33 批方法论照用） | 0.17.34/16 弧线 |
| W4.3 | **换版库审计重做**：cyclone 0.9-5 全量、ELSE RC15 elsex 段（0.17.142/143 的构造器级方法论与脚本直接复用） | 0.17.142/143 弧线 |
| W4.4 | OBJECT_PORTS.md 重生成（genports.py）；帮助补丁按新源码树重抓（build_help.py；else help 更新到新版本） | 0.17.36/0.14.4 弧线 |
| W4.5 | knownClasses/manifest 重生成（regen_manifest + 0.17.91 回填链） | 0.17.91 弧线 |

### W5 验收（阶段 4）

| # | 修改意见 | 依据 |
|---|---|---|
| W5.1 | 双设备装机验收（版本纪律：bump/aapt/dumpsys 三核对） | 版本机制 |
| W5.2 | **回归清单**（按 ROADMAP 弧线终点逐项）：①superosc~/arp（0.17.145–157 崩溃链）；②07.time timer＋pmsg/euclid/pad/keyboard/widget 全家；③hurdy2 递归/延时线（0.17.25/26）；④vgm-player/sid-player/nes-player/opl3-player/xm toilet3（各引擎弧线终点）；⑤help 全量冒烟＋couldn't create 清零；⑥变参探针 33 批重放；⑦**新 mc 补丁**（clone -do、mix~/nchs~ 多声道） | §3 全部 |
| W5.3 | **性能对照**：0.57 DSP 负载复测（0.17.76 六实例 Nuked 核超限教训——audio_flinger underrun 记录对比） | 0.17.76 弧线 |
| W5.4 | PD_VERSIONS.md 终裁更新（0.55/0.57 矛盾项关闭；0.51 弧线归档标注） | §6 |

### 决策点汇总（🄰，全部须用户点头后才动）

1. W1.4 看门狗是否重做（取决于 0.57 时钟行为实测）
2. W1.5 stopAudio 的 dsp 开关语义是否恢复
3. W3.1 [clone] 直拆分支退役
4. W3.6 superosc~/f2s~ 回归上游 mc 版 + initmess 替换去留
5. W3.7 graph~/spectrograph~ 桩、display echo 是否回归上游
6. W2.6 creb/cxc/bsaylor 三个失修库的去留

### 建议阶段切分

- **阶段 1 = W0**（0.18.0：只换内核跑 vanilla，产出行为差异表）
- **阶段 2 = W1+W2**（0.18.1：库矩阵重编 + 运行时层适配）
- **阶段 3 = W3+W4**（0.18.2：管线适配 + 知识层审计重做）
- **阶段 4 = W5**（0.18.3：回归与验收；回到 0.17.161 内容等价的"0.57 版"）
- 每阶段可独立回退（git 版本机制）；任何一阶段失败不阻塞前序成果保留

## §8 测试与验证清单（依据 CHANGELOG 弧线逐项；2026-11-23）

> 方法约束（AGENTS.md）：不用模拟器；测试补丁用文本构建经 run-as 写入
> app 存储；进出走 logcat（[print]/console/session dump）与 OSC 桥；
> 判据一律用**绝对指标**（频率/DC/削波对称/底噪/计数），不用相对比较
> （0.17.46/60/76 教训）；不截图、不猜坐标、不碰设备文件。
> 「基线」列：51 = 现行 0.51.3 上也该复验；57 = 仅 0.57 迁移后适用。

### A. 引擎/运行时

| # | 对象（弧线） | 测试点 | 方法 | 通过判据 | 基线 |
|---|---|---|---|---|---|
| T-A1 | 调度器懒启动 | banner/首个 DSP tick 相对 openPatch 的时刻 | logcat 时间线（openPatch → 首条 pd 输出/首个 tick 计数） | 0.17.45 五层参数与新时序匹配（boot-race 窗口/settle/re-bang 时刻有效） | 57 |
| T-A2 | loadbang | ①根画布 loadbang 触发；②[pd] 块内 loadbang 是否仍缺；③PdAudio reinit 是否重置 sys_noloadbang | 文本补丁 loadbang→[print]，logcat 计数（根/块各一） | 根画布触发=1；块内缺口状态记录；标志在 openPatch 前后均为 0 | 51+57 |
| T-A3 | dsp 编译周期 | dsp off→parse→on 后 coded 对象是否进链 | dsp 关开补丁（含 ym2612~）→开 dsp→console/DBG | 对象入链、0 couldn't create（0.17.45② 场景） | 57 |
| T-A4 | AudioWrapper | 已运行时调 startAudio 是否重开 sink/泄漏线程 | javap 反汇编 1.4.x + audio_flinger 记录 | 无 sink 重开、无线程增长（0.17.110 基线行为） | 57 |
| T-A5 | canvas_getcurrent | 是否仍 "pd" 符号绑定查表 | 反汇编 + **churn 压测**（4 秒内连开 3 补丁 ×N 轮，含 tracker smp~/modseq 补丁） | 零 SIGSEGV；0.57 行为定案写回 PD_VERSIONS.md | 51+57 |
| T-A6 | 逻辑时钟 | dsp 关态 timer/delay/metro 是否冻结 | 07.time 例程（bng→timer→floatatom）+ dsp 关/开两态 | dsp 开：计时不为 0；dsp 关：按 0.57 实测定案（0.17.158 根因是否消失） | 51+57 |
| T-A7 | 导出符号面 | sys_noloadbang/sys_getsr/sys_getblksize/sched_tick/clock_getlogicaltime/signal_setmultiout/pdgui_vmess/sys_gui/sys_vgui | llvm-nm -D libpd.so | 清单落 PD_VERSIONS.md；缺失项对应垫片/调用处置 | 57 |
| T-A8 | dsp 链布局 | dsp_add 3 参段布局 / dsp_addv f-slot-per-segment | 反汇编 dsp_tick/ugen_doit；自研对象（xm~ dsp_addv）冒烟 | 与 0.17.83/128 的 0.51.3 布局结论一致或记录差异 | 57 |
| T-A9 | Java API | pd-for-android 1.4.x PdBase/PdAudio/PdReceiver 签名与行为 | javap 对比 1.2.1-rc6 | 差异表；Kotlin 侧适配点清单 | 57 |
| T-A10 | churn 稳定性 | 连续开关补丁/急停急开音频 | 脚本循环 openPatch/closePatch ×N + crash buffer 巡检 | 零 FATAL、零 native 崩溃 | 51+57 |

### B. 会话构建管线

| # | 对象（弧线） | 测试点 | 方法 | 通过判据 | 基线 |
|---|---|---|---|---|---|
| T-B1 | [args] | 内联会话里 [args] 原生行为（0.17.145/147 SIGSEGV 场景） | superosc~ 会话 dump + [args] 直建补丁 | initmess 转换仍覆盖全部 [args]；0.57 原生行为定案 | 51+57 |
| T-B2 | 深度感知扫描 | superosc~（route→lists→mix-list→gain-array depth-4）会话结构 | PocketPd.Session dump + verify_scan_fix.py | #N canvas/#X restore 12/13 平衡；嵌套 restore 坐标未被改写 | 51+57 |
| T-B3 | pmsg ordinal | 根层 msg 位于含 msg 子图块之前的触发对齐 | 双 msg（根+块内）补丁逐个点按，console `msg trigger` 序号 | 点按 N 恰触发文档序第 N 个 msg | 51+57 |
| T-B4 | clone | ①-d/-x/-di/-do/-s 各旗标原生行为；②voice==1 直拆退役对比（多实例 vs -do）；③文件名粘逗号解析 | 同一补丁双模式 A/B + f2s~.pd 折行用例 | 旗标行为表；A/B 差异报告（🄰 决策依据）；直拆不再触发 | 57 |
| T-B5 | 内联预算 | 大补丁树（自有模块+ELSE 副本）预算是否耗尽 | 用户量级补丁 + "inline budget exhausted" 日志检查 | 零耗尽；或耗尽时优雅降级路径明确 | 51+57 |
| T-B6 | 前缀剥离 | ①else/knob 等编译类可创建；②[/] / [/~] 回归；③换新 ELSE 资产后 dep_check 全绿 | dep_check2 + 帮助补丁冒烟 | 0 couldn't create；红框计数 0 | 51+57 |
| T-B7 | $ 语义 | $1 创建参数（n-1）、msg literal $N、$0 隔离、\$ 往返 | display 标签/hurdy2 四实例/msg $1( 三类补丁 + 保存重开 cat | 各弧线终点行为复现（0.17.141/145/0.15.20/26） | 51+57 |
| T-B8 | 命名空间 | delwrite~/delread~/vd~ 内联前缀配对 | hurdy2 ×4：session 内 1001-1004-s_delay 成对 | 0 multiply defined | 51+57 |
| T-B9 | relay 体系 | ①信号 cord 不被改写（**加 mc cord 用例**）；②控件双腿；③克隆改名；④块内绑定 | 0.14.15 用例 + 多声道新用例 + 粘贴孪生用例 | mc cord 端点原样；双腿成立；孪生独立 | 51+57 |
| T-B10 | iemgui 细节 | "0" 槽位=未绑定；#int 标记剥离；hex 颜色/尾字段往返 | LLM 风格补丁 + 保存重开 diff | 链路通；文件往返无损 | 51+57 |
| T-B11 | 原生类守卫 | iemlib bng.pd 不内联；DUAL_COMPILE_PROTECT 15 名；multiply defined 不复发 | 04-gui-demo + hurdy2 | bng 原样、0 multiply defined | 51+57 |

### C. 对象知识层

| # | 对象（弧线） | 测试点 | 方法 | 通过判据 | 基线 |
|---|---|---|---|---|---|
| T-C1 | 变参注册表 | 33 批探针重放（每对象 8 进 8 出） | 探针补丁 + connection failed 解码 | 与 defs/variadic 全表一致；0.57 差异表 | 51→57 |
| T-C2 | vanilla 清单 | 0.57 新对象/变更类（threshold~ 等） | 0.57 源码枚举 + 探针 | defs/knownClasses/pdClasses 覆盖 | 57 |
| T-C3 | 端口几何 | 大端口数间隙（xm~ 33 进） | 画布渲染 + portAt 命中 | 无重叠、端点不越界 | 51+57 |
| T-C4 | [/] 绘制 | [/] 与 [/~] 两个 inlet | 画布 + 连线探针 | 2 进命中；绘制正确 | 51+57 |
| T-C5 | knownClasses | 红框计数探针（0.9.5 方法） | 全对象遍历补丁 | 红框 0（pd 能建的类全部识别） | 51+57 |

### D. 库矩阵（重编后逐库）

| # | 对象（弧线） | 测试点 | 方法 | 通过判据 | 基线 |
|---|---|---|---|---|---|
| T-D1 | 全库加载 | N/N core 加载 + audit_blobs 26/26（或新计数） | 启动日志 + audit_blobs.py | 零未定义符号、零 dlopen 失败 | 51+57 |
| T-D2 | 逐库 demo | 11 个库例程全冒烟 | 例程 + console 巡检 | 0 couldn't create/0 connection failed | 51+57 |
| T-D3 | ELSE 全家 | superosc~/arp 端到端；midi.in/mpe.in/ptouch/knob/display/presets/scala | 各弧线终点补丁 + console/OSC | 全链路复现；0 缺类 | 51+57 |
| T-D4 | elsex mc 类 | mix~/nchs~/xfade.mc~/sum~ 等 16 类原生 mc 行为 | mc 探针（多声道源→类→输出） | 0.57 上按 mc 语义工作；垫片撤销后零未定义符号 | 57 |
| T-D5 | cyclone 0.9-5 | 高频对象对比（coll/zl/counter/gate/switch/svf~） | 旧行为用例 + demo | 行为差异表；0 崩溃 | 57 |
| T-D6 | zexy/iemlib | lister/pack/mux/demux/limiter~、time/date、forpp 缺口状态 | demo + 探针 | 与新版上游一致 | 57 |
| T-D7 | creb/cxc/bsaylor/fftease | 试编结果 + demo | 逐库 | 按 🄰 决策执行 | 57 |
| T-D8 | 自研芯片引擎 | ym2612~（黄金 PCM 哈希 7b6057fa313d2a69、128 gm 扫描 0 静音）；vgm~（demo.vgm/vgz/goto/pause）；sn76489~（Goertzel 三点+LFSR）；sid~+sid6581~（demo.sid/Seekrank）；opl3~（funkystars、buffered 写、slide 不灭声）；nes~/nsf~（30+21 断言 harness）；xm~（toilet3 时钟降采样、手动演奏）；mpein（手喂字节序列）；smp~/modseq（**open_via_path 移植后**文件查找） | 各弧线终点 harness/探针（宿主+设备双级） | 全部复现弧线终点指标；零回归 | 51+57 |
| T-D9 | 外设桥 | MIDI（语义/raw/sysrt/MPE）、OSC 双向、串口组帧、传感器 | 各桥用例（0.17.15/119/132/133/135 终点） | 消息零丢失（加锁纪律下多次重放稳定） | 51+57 |

### E. 交互/UI（迁移回归时照跑）

| # | 对象（弧线） | 测试点 | 通过判据 |
|---|---|---|---|
| T-E1 | 三模式/LOCK/RUN 长按静音/多选快照 | 菜单快照执行、LOCK chrome、演奏中零弹框（0.15.30-33/0.17.21-22） |
| T-E2 | 右键 MouseBack | 完整 SOURCE_MOUSE 指纹判定；手势返回不受影响（0.17.101） |
| T-E3 | 底栏手势 | 20° 浅斜拽/|y| 超底栏高度/横向滚动三分（0.17.103/104） |
| T-E4 | 焦点策略 | 画布永不被 focus highlight 刷灰、console 不吃键（0.17.105-108） |
| T-E5 | 捏合缩放 | 短距双指可缩放、抬指重起判（0.15.28） |
| T-E6 | 文本模式 | 着色/往返/保存 flush（0.13.16/0.17.120 lint） |
| T-E7 | Properties/变量管理器 | 参数行/ARGUMENTS 行/Rename 全引用重写（0.14.18） |

### F. 性能与稳定

| # | 对象（弧线） | 测试点 | 方法 | 通过判据 |
|---|---|---|---|---|
| T-F1 | DSP 负载 | 六通道 Nuked 曲目/多声部补丁 underrun | audio_flinger 记录对比（0.17.76 方法） | 无 underrun 恶化 |
| T-F2 | 内存 | 1GB 环境（-qemu -m 1024）启动+满载 | 0.15.21 方法 | PSS 可比、无 LMK 杀 |
| T-F3 | 长会话 | 高流量 print 风暴下 UI 响应 | console 节流后压力（0.17.29/31） | 120ms 节流生效、交互不卡 |
| T-F4 | 崩溃巡检 | crash buffer/crash-last.txt 零 FATAL | 每轮验收固定项 | 0 FATAL |

### 执行顺序建议

1. **51 基线复验**（T-A2/A5/A6/A10、T-B 系列抽样、T-C1、T-D8、T-F）——
   现行版本先跑一遍，把"弧线终点=现状"钉死，避免把既有偏差误判为
   迁移回归（0.17.58 的教训：烟测只测幅度漏了频率）。
2. **57 冒烟**（W0.5）：T-A 全组 + T-B1/B4/B6 + T-C1/C2 + T-D1。
3. **57 全量**：随 W2–W5 阶段推进，按本表逐项打勾，任何"终点行为
   复现失败"先查实现再查弧线（是否漏了某轮的隐藏修复）。
