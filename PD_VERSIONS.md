# PocketPd 版本考古（PD_VERSIONS.md）

> 目的：把"核心到底是什么、各库什么版本、分别适配 pd 哪个版本、
> 0.51 和 0.55 到底各自用在哪里"一次性钉死。所有结论附证据与出处；
> 无法钉死的项明确标注"未定/待溯源"。
> 维护规则：凡涉及运行时版本的新证据，先进本文档，再改 AGENTS.md。

## 一、结论速览

| 组件 | 来源/坐标 | 版本 | 适配 pd | 证据 |
|---|---|---|---|---|
| **运行时 pd 内核**（真正跑补丁的引擎） | `io.github.libpd.android:pd-core:1.2.1-rc6`（Gradle/Maven） | **pd 0.51.3** | 0.51.3 | libpd.so 内烧录字符串 `Pd-0.51.3`；`[clone]` 只认 `-s`（无 0.54+ 的 `-d/-x/-di/-do`）；`multichannel` 字符串 0 次；mc 类运行不工作（0.17.146–0.17.152 实测） |
| libpd Java/ JNI 层 | 同上 AAR | 1.2.1-rc6 | — | Gradle 缓存 AAR；上游源码树未本地留档（待溯源） |
| **slibs 编译头（主线）** | pd-0.51-4 官方源码包（msp.ucsd.edu），解包于 `%TEMP%/srcrepo/pdsrc/pd-0.51-4/src` | **pd 0.51-4** | 0.51.3（同代） | `build_slibs.py` `PDSRC`；slibs/README 明言"与 AAR 内 pd 0.51.3 匹配" |
| **elsex 编译头（唯一特例）** | `slibs/else-src/pd55/`（ELSE 自带 vendored 头） | **pd 0.55.0**（`PD_MAJOR 0 / MINOR 55 / BUGFIX 0`） | 声称对齐运行时，**有矛盾，见 §四** | 头文件内版本宏实测；`build_slibs.py` elsex 配置 `inc_first` |
| ELSE 源码树 | `slibs/else-src/`（porres/pd-else 快照） | **1.0-0 RC-15**（计划发布日 2026-04-09） | 上游 1.0 系面向 pd 0.54+（mc API）；我们只编译子集 | else-src/README.md 版本页 |
| else/ 抽象（309 个 .pd） | 手工集成老快照（137 个，来源树已失传）+ 0.17.134 从 else-src 补 169 个 + 本项目自改版 | 混合 | 见 §三 | 0.17.134 CHANGELOG |
| cyclone/zexy/iemlib/creb/cxc/bsaylor/osc/midifile/binfile | **pd-extended 0.43.4** source tarball | 0.43.4 时代的各库快照 | 编译对 0.51-4 头 | slibs/README |
| fftease | GitHub `ericlyon/pd-fftease`（经 jsdelivr CDN） | 3.x | 同上 | slibs/README |
| diy2 | plugdata 0.9 `Extra/DIY2` | 159 个纯抽象 | 纯 .pd，不编译 | slibs/README |
| tracker / pocketpd（ym2612~、nes~、sid~、vgm~、opl3 等） | 本项目自研 | — | pd-0.51-4 头 | slibs/pocketpd/src、slibs/tracker |
| 帮助补丁（653 个） | vanilla←**pd-0.51-4** doc/5.reference；cyclone/zexy/iemlib/…←pd-extended 0.43.4；else←ELSE 素材 | 混合 | vanilla 部分与运行时同版本；else 部分有旗标失配，见 §五 | `build_help.py` 头注释 |
| vanilla 例程（examples/control、audio） | 取自 pd 0.55 时代的 doc 素材（`van055.txt` 类清单同源） | 0.55 文件格式 | 0.54+ 文件格式（hex iemgui 颜色、floatatom 尾字段）在 0.51.3 上可解析 | 07.time.pd 等文件格式特征 |
| `slibs/pd55-ref/` | 静态分析参考源码，**不进 APK** | 0.55（g_canvas/m_class/…）+ 0.51-4（x_time/m_sched/m_pd/m_class/s_audio…本轮补） | 仅参考 | 目录即清单 |

**一句话**：运行时是 **pd 0.51.3**；0.55 只存在于两处——elsex 的编译头
（ELSE 自带 pd55 头）和 pd55-ref 参考源码（纸面参考，不参与运行）。
历史上"运行时 = 0.55 系"的结论是**误判**，见 §六纠错。

## 二、核心（运行时）实测记录

1. **版本字符串**：`llvm-strings libpd.so` → `Pd-0.51.3 ("")`、
   `%s compiled %s %s`（编译日期是 AAR 构建时 `__DATE__` 填充，与 pd 版本无关）。
2. **[clone] 旗标**：字符串表里 clone 只有 `-s`。0.54+ 的
   `-d/-x/-di/-do`（多声道分布）全部缺失 → 0.17.147 起 app 侧把
   单声道 `clone -d` 内联绕过。
3. **多声道**：`.so` 内 `multichannel` 字符串 **0 次**；`[mix~]`/`[f2s~]`
   的 mc 架构运行不工作 → 0.17.152 起 superosc~/f2s~ 改单声道直连版。
4. **libpd 自带补丁（libpd-ism，与 pd 版本无关，但必须知道）**：
   - `canvas_getcurrent()` = `pd_findbyclass(gensym("pd"))` 绑定查表，
     不是栈指针（0.17.70 反汇编实证）→ externals 禁用
     canvas_open/canvas_getcurrent，一律 `open_via_path`；
   - 调度器懒启动：进程冷启动后 ~3.5 s 才出现首次 DSP tick；
   - openPatch 永久吞 loadbang（`PdTweaks.setLoadbangEnabled(true)` 对抗）。
5. **pd 0.51-4 与 0.51.3 的关系**：同为 0.51 代（bugfix 差异），
   slibs 一律以 0.51-4 头编译、在 0.51.3 运行时上加载；凡调用接触
   pd 内部结构的 API，先反汇编运行时 libpd.so 核实语义（AGENTS.md 旧规，
   继续有效）。

## 三、0.51 / 0.55 到底各自用在哪里

**pd 0.51（0.51.3 运行时 + 0.51-4 编译头）——一切实际执行发生在这里：**

- libpd AAR 的 pd 内核（运行时，跑用户补丁/会话）；
- 绝大多数 slibs 编译头（cyclone/zexy/iemlib/creb/cxc/bsaylor/osc/
  midifile/binfile/fftease/tracker/pocketpd）；
- vanilla 帮助补丁的来源版本（help/vanilla ← pd-0.51-4 doc/5.reference，
  与运行时同版本，无失配）；
- 补丁语法基线：无多声道信号、无 clone -do、对象行为按 0.51 语义。

**pd 0.55 ——只存在于两处，都不直接执行用户补丁：**

1. `slibs/else-src/pd55/`：ELSE 自带的 pd 0.55 m_pd.h，作为 elsex
   的 `inc_first` 编译头（为了 ELSE 上游的 mc API 声明）。编译产物
   elsex_core.so 在 0.51.3 运行时上加载——**其中的多声道类实际不可用**
   （见 §四矛盾）。knob.c 等还引用 0.56-dev GUI 字段，构建时打了
   颜色常量补丁（build_slibs.py `patches`）。
2. `slibs/pd55-ref/`：0.55 源码（g_canvas.c/g_clone.c/g_editor.c/
   g_text.c/m_binbuf.c/m_class.c/m_glob.c/m_pd.c/s_loader.c/
   s_audio_pa.c）——**纯静态分析参考**。使用规则：涉及语义差异的
   结论必须回到 0.51-4 源码或运行时反汇编复核（0.51→0.55 有
   canvas_objtext、gop、timer/tempo 等多处行为差异）。

**素材版本（既非 0.51 也非 0.55 的"第三方时间轴"）**：

- ELSE 素材跨版本混装：手工集成老快照（版本失传）+ else-src
  （1.0-0 RC-15）+ 更新旗标语法的帮助/示例（`initmess verbose/midi`、
  `rampnoise~ -ch`、`tempo~ -mul`——1.0-9 级语法，待精确溯源）。
  失配表现就是 couldn't create / "no method for 'verbose'"（0.17.150）。
- pd-extended 0.43.4 的库快照 + plugdata 0.9 的 diy2 + pd 0.55 格式的
  vanilla 例程。

## 四、已知矛盾与未定项（诚实记录）

1. **elsex 的 t_signal offset 断言 vs 运行时功能证据**：
   - 0.17.134 曾"反汇编 ugen_connect 验证" `s_n@0 s_vec@8 s_sr@16
     s_nchans@24`；
   - 但 pd **0.51-4** 头的 t_signal 在 offset 24 是 `s_refcount`
     （无 s_nchans 字段），且运行时无 multichannel 字符串、clone 无
     `-do`、banner 0.51.3。
   - 判断（**本人推断，未再实测**）：0.17.134 大概率把 ugen_connect
     对 `s_refcount` 的访问误读成了通道数访问。elsex 的 mc 类
     编译通过 ≠ 运行时支持 mc——功能证据（mc 类不可用）为准。
     如需终裁：反汇编 ugen_connect 并对照 0.51-4 的 s_refcount 语义。
2. **libpd AAR 上游源码树**：pd-core 1.2.1-rc6 的确切上游 tag/
   fork commit 未本地留档（待溯源；坐标与内核版本字符串已实测）。
3. **手工集成期 else 素材的确切版本**：0.17.134 之前的 137 个抽象与
   217 类 else_core.so 来源失传（该 core 已被 elsex 取代）。

## 五、对补丁作者的实际意义（0.51.3 基线）

- 可用：全部 vanilla 0.51 控制对象（含 `[/]`/`[/~]`/timer/pipe/metro…）；
  slibs 各库 0.43.4/1.0-0 时代对象；自研 ym2612~ 等。
- 不可用/受限：
  - 多声道信号（mc）全家：clone -do/-di/-x、mix~ 多通道、nchs~、
    f2s~ 的 mc 用法；
  - ELSE 新旗标语法：initmess `verbose/midi`、`rampnoise~ -ch`、
    `tempo~ -mul` 等（帮助补丁里出现即 couldn't create）；
  - 依赖 pd 0.54+ GUI/Tcl 语义的对象（libpd 无 Tcl，上游 Tcl GUI
    类本来就不工作）。
- 帮助补丁里出现的失配对象**不是运行时坏了**，是素材版本超前。

## 六、历史误判纠错（时间线）

| 轮次 | 当时结论 | 现在的裁定 |
|---|---|---|
| 0.17.70 | "运行时 = pd 0.55 系（反汇编实证）" | **误判**。实证到的 `canvas_getcurrent = "pd" 符号绑定查表` 是 **libpd 自己的补丁**（libpd-ism），任何 pd 版本的 libpd 都有；不能当版本证据。运行时 banner `Pd-0.51.3` 为准 |
| 0.17.134 | "t_signal s_nchans@24 offset 验证" | **存疑**。与 0.51-4 头布局冲突（offset 24 = s_refcount），与运行时无 mc 字符串冲突；按 §四处理 |
| 0.17.146+ | "clone 无 -d、mc 不工作、banner 0.51.3" | **正确**，本文档基线 |
| AGENTS.md 旧行"pd-core 1.2.1-rc6 = pd 0.55 系" | — | 已修正（指向本文档） |
