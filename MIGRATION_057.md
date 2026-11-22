# MIGRATION_057.md — pd 0.57 迁移准备调查（v2：CHANGELOG 全文通读后按弧线重写）

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
