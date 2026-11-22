# MIGRATION_057.md — pd 0.57 迁移准备调查

> **状态：纯调查报告（2026-11-23）。本文档不含任何已执行的代码/构建改动。**
> 背景：PD_VERSIONS.md §五A 取证确认 pd-for-android master = 1.4.4-SNAPSHOT，
> 子模块链 libpd@e3cd52fd7517 → pure-data@43a5b8922a35 = **pd 0.57.0-dev**。
> 本文回答两个问题：① 各库适配 0.57 的版本是什么；② CHANGELOG 里我们做过的
> 功能调整/增加，逐项在 0.57 上的可行性如何。
> 判定图例：✅ 保留（无需改）｜🔁 迁移时重新实测/核实｜♻️ 在 0.57 上多余
> （**退役须用户同意**，见"修而不删"规则）｜🛠 需要改写｜❓ 未决

## §1 目标引擎

- 来源：`libpd/pd-for-android` master（1.4.4-SNAPSHOT，2026-11-16 活跃）
- 链路：pd-for-android → libpd@e3cd52fd7517 → pure-data@43a5b8922a35
  = **pd 0.57.0-dev**（m_pd.h 版本宏实测）
- 消费方式：**无正式 Maven 产物**（Maven 线停在 1.2.1-rc6/2021），
  必须 **自建 AAR**：clone pd-for-android、pin 住两级子模块 SHA、
  用其 gradle 构建（含 oboe 依赖）——构建链由项目接管
- 0.57-dev 相对 0.51.3 的关键语义变化（与本 app 相关）：
  多声道信号（0.54+）、`[clone]` 的 `-d/-x/-di/-do`（0.54+）、
  t_signal 增加 `s_nchans`、新 vanilla 对象与新旗标、
  iemgui hex 颜色/floatatom 新格式（我们已在 0.51.3 上兼容过）

## §2 库适配矩阵（哪些版本适配 0.57）

| 库 | 现用快照 | 上游现状（2026-11-23 取证） | 0.57 适配版本/路径 | 判定 |
|---|---|---|---|---|
| **ELSE**（elsex 编译 + else/ 309 抽象） | else-src = **1.0-0 RC-15**（计划发布 2026-04-09） | 最新正式 release **v.1.0-rc14**（2025-09，要求 pd 0.56-1）；RC15 贴 0.56/0.57-dev 时代 | **RC15 原生贴近 0.57-dev**；elsex 不再需要 `else-src/pd55` 头特例（0.57 头自带 mc API）——需核实 RC15→0.57-dev 期间的 API 漂移（knob 等 GUI 字段补丁是否仍必要） | 🛠 头文件特例撤销，源码小修 |
| **cyclone** | pd-extended **0.43.4** 的 miXed 快照（~2010） | porres/pd-cyclone 活跃：**0.9-5**（2026-09-05，要求 pd **0.56-5**） | **换 modern cyclone 0.9-5**（重编 + 端口表/帮助重审）；老 miXed 代码跨 15 年 API 漂移，直接编译不现实 | 🛠 换版本 + 审计 |
| **zexy** | pd-extended 0.43.4 | git.iem.at/pd/zexy 活跃维护（IOhannes，pd-lib-builder）；站点反爬墙挡自动取证 | 当前版对 0.57 头重编；**版本号迁移时手动核对**（反爬墙抓不到 release 页） | 🛠 重编 |
| **iemlib** | pd-extended 0.43.4 | git.iem.at/pd/iemlib 活跃维护 | 同 zexy | 🛠 重编 |
| **fftease** | ericlyon 3.x | 最新 tag **v3.0.1** | v3.0.1 按 0.57 头重编（pd-lib-builder，漂移预期小） | 🛠 重编 |
| **mrpeach**（osc/midifile/binfile） | pd-extended 0.43.4 | 无正式发布体系（源码流传） | 现有源码按 0.57 头重编 + 修漂移（其代码在 modern pd 上被广泛编译过，预期漂移较小） | 🛠 重编 |
| **creb / cxc / bsaylor** | pd-extended 0.43.4 | **基本失修**（Grill/Chun Lee/Saylor 老项目） | **无上游适配版本**——要么我们自己打 0.57 补丁，要么这三库在 0.57 上缺席 | ❓ 风险最高，需用户决策 |
| **diy2** | plugdata 0.9 纯抽象（159 .pd） | 版本无关 | 直接可用；内容引用的对象需在 0.57 上核对一遍 | ✅/🔁 |
| **tracker / pocketpd 自研**（ym2612~、nes~、sid~、vgm~、opl3~、smp~、modseq…） | 0.51-4 头 | — | 按 0.57 头重编；已静态核查：**无硬编码 t_signal 偏移**（源内 [24] 命中均为 mod/sid 文件格式字节）；⚠️ **tracker 的 smp~/modseq 仍用 canvas_getcurrent()**（0.17.70 事故同款 API，当年只修了 ym2612~）——迁移轮必须一并处理 | 🛠 |
| **libpdtweaks** | dlsym 直改 sys_noloadbang 等 | — | 0.57 libpd.so 是否仍导出同名符号 → 迁移时反汇编核实；nativeLoadCore 机制本身（dlopen+setup）引擎无关 | 🔁 |

## §3 CHANGELOG 功能调整/增加 × 0.57 可行性

（提取自 294 轮历史中的**机制级**项；纯 UI/手势/主题类不列——与引擎无关）

### §3.1 运行时/引擎层

| 机制 | 引入 | 0.57 判定 | 说明 |
|---|---|---|---|
| PdTweaks.nativeLoadCore（dlopen+setup 装库） | 早期 | ✅ | 引擎无关；符号清单需按 §2 复核 |
| PdTweaks.setLoadbangEnabled（直写 sys_noloadbang） | 0.17.36 前后 | 🔁 | 0.57 libpd.so 导出符号核实；libpd 吞 loadbang 的行为是否仍在也要核 |
| canvas_getcurrent 禁用令（externals 一律 open_via_path） | 0.17.70 | 🔁 | 0.57 的 libpd-ism 是否仍是 "pd" 符号绑定查表需重新反汇编；**tracker 的 smp~/modseq 未守此规**，迁移轮必须处理 |
| 调度器懒启动 ~3.5s + boot-race 重开（10s 窗口/4s/6s 重 bang） | 2026-11-06 实测 | 🔁 | 0.57 上重新实测时序；数值很可能不同 |
| dsp off→parse→on 编译周期（2026-11-06 实测） | 0.17.36 前后 | 🔁 | 0.57 上重新实测（0.54+ 对 dsp 切换有改动） |
| AudioOpen 三模式（START/RUN/SYNC）+ 静默编辑 | 0.17.109 时代 | ✅（语义）/🔁（数值） | 语义保留；dsp 边缘行为重测 |
| ~~逻辑时钟看门狗~~（libpdclock） | 0.17.158/159 | — | 已在 0.17.160 整体回退，0.57 上是否重做属新立项（pd 0.57 主循环行为不同，可能不再冻结） |

### §3.2 会话构建管线（buildInteractiveSession 及周边）

| 机制 | 引入 | 0.57 判定 | 说明 |
|---|---|---|---|
| [clone] 单声道内联（parseCloneArgs + 单 voice 展开） | 0.17.147/149 | ♻️**多余**（**退役须同意**） | 0.54+ clone 原生支持 -d/-x/-di/-do，0.57 上原生即可；但多声道语义与内联不等价（内联=多实例，-do=打包声道），**去留影响补丁行为，须实测后由用户定** |
| else/ 前缀剥离（compiledClasses 查表） | 0.17.153 | 🔁 | 若 ELSE 换新构建（§2），裸名/前缀注册方式可能变化；剥离规则按实际注册面重审 |
| superosc~/f2s~ 单声道替代版（自改 slibs） | 0.17.150-152 | ♻️ 可回归上游 | 0.57+RC15 下上游 mc 版原生可跑；**是否回归=放弃我们的接线修正，须用户同意** |
| initmess 旗标替换（r \$0-initmute1/2） | 0.17.150 | 🔁 | 取决于 ELSE 版本：RC15 编译的 initmess 不认 verbose/midi；若迁移时 ELSE 升级到更新版则可去掉 |
| GOP 展开（expandGop）+ 端口表 | 早期 | ✅ | 机制引擎无关；0.57 新增 vanilla 对象/GOP 行为差异需增量审计 |
| msg pmsg-N 中继（doc/session 双侧对齐） | 0.17.120s | ✅ | 语义与引擎无关 |
| euclid/circle5/pad/button/popmenu 的 relay 化（盒体对 pd 不可见） | 早期 | ✅ | app-only widget，引擎无关 |
| scope stub（pocketpd-scope-go 时钟） | 早期 | ✅ | 机制不变；懒启动时序数值重测 |
| GUI 绑定 + atom 的 [s]/[r] relay 腿 | 早期 | 🛠 **mc 新风险** | relay 机制按单声道设计；0.57 上用户若把**多声道 cord** 接进 relay 化路径，mono [s]/[r] 会破坏 mc 连线——迁移轮必须给 relay 加 mc 直通规则 |
| connect 索引纪律 + check_pd_connects.py | 0.17.156 | ✅ | 文本语义，引擎无关（0.54+ 文件格式我们已兼容） |
| **vanilla 对象知识表**（pdClasses/knownClasses/PdObjectMeta defs） | 持续 | 🛠 增量 | 0.54/0.55/0.57 新增 vanilla 对象与新旗标需入表（scope~、polybang 等；具体清单迁移时按 0.57 源码枚举） |
| PdTweaks/nativeLoadCore 之外的 slibs 抽取 + Slibs.VERSION | 持续 | ✅ | 引擎无关 |

### §3.3 引擎无关（迁移不动，列出备查）

UI 全套（画布手势/三模式/OSD/对话框/主题/设置）、PatchDocument 无损往返、
文件管理、OSC/MIDI/串口/传感器**桥**（Kotlin 侧收发，送进 pd 的消息语义不变）、
版本纪律/git 机制/lint_pd.py/check_pd_connects.py、help/examples 抽取机制。

## §4 迁移新增工作项（0.57 特有，此前不存在）

1. **自建 AAR**：clone pd-for-android，pin libpd@e3cd52f + pure-data@43a5b89，
   走其 gradle 构建出 4 ABI（构建链、oboe、AGP 版本对齐——工作量未知）
2. **mc 时代的会话管线审计**：所有 relay/改写规则在多声道 cord 下的行为
   （§3.2 的 🛠 项）
3. **vanilla 新对象/新旗标入表**
4. **两台设备全量回归**（按 AGENTS.md 装机验收纪律）

## §5 风险汇总

- **最高**：creb/cxc/bsaylor 失修（无上游 0.57 适配版本）；cyclone 从
  2010 快照跳到 modern 0.9-5 的行为差异面
- **高**：tracker smp~/modseq 的 canvas_getcurrent 隐患；relay 机制 × mc；
  自建 AAR 构建链
- **中**：ELSE RC15→0.57-dev 漂移；vanilla 新对象表；实测时序变化
- **低**：diy2、fftease、mrpeach、管线主体、app UI

## §6 未决问题

1. RC15 之后 ELSE 是否已发 RC16/final（0.57 对齐版）？发布页截至本轮只到 rc14
2. zexy/iemlib 当前确切版本号（git.iem.at 反爬墙，需手动核对）
3. 0.57-dev 的 libpd-ism 清单（canvas_getcurrent/loadbang/懒启动）——需拿到
   0.57 libpd.so 反汇编
4. clone 多声道语义 vs 单声道内联在用户补丁上的实际差异——需实测
5. creb/cxc/bsaylor 的去留（按"修而不删"，须用户决策）
