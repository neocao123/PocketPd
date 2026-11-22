# slibs 外部库：历史调整记录与重建基准

（design/ 目录，不进 APK。2026-11-16 事故后整理，作为 cyclone/zexy/iemlib 等
十库重建的唯一权威依据。所有条目注明出处版本/文件，重建时信任但须复核。）

## 0. 事故与现状（2026-11-16）

- srcrepo 补丁态源码树于 **2026-10-10 19:10** 被整体清空（此前为"外部库
  冲突"误诊操作期，用户中止过操作但源码未恢复）。此后每次 build_slibs.py
  运行都把源码为空的库**静默链接成 3576 字节空壳并覆盖 assets 里的好
  blob**。0.17.7x 各版（vm C 改动都触发过 slibs 重编）自此带空壳发布。
- **pd 层已死（空壳）**：cyclone、zexy、iemlib(core)、fftease、bsaylor、
  creb、cxc、osc、midifile、binfile 十库。
- **幸存完好**：else（671KB blob, 10-02, 不在 LIBS 故从未被覆盖）、
  tracker、pocketpd（仍在编译）、diy2（纯抽象，157 个 .pd）、各库
  core_classes.txt 完整类清单（10-03）、assets 内全部 .pd 抽象
  （diy2 157 / iemlib 152 / creb 47）、assets/help 全部、
  examples/slibs 全部演示补丁。
- 本地恢复向量已穷尽（全盘搜副本 / AVD userdata+RAM 快照特征串扫描 /
  回收站 417 条元数据 / VSS 无 / TRIM 开启 carving 无望 / gradle 中间
  产物无 blob）——**本机无任何完好副本**；GM800/手机均被覆盖安装过
  （用户确认），AVD 最后开机 11-07 已是空壳版。

## 1. 幸存的构建配置（build_slibs.py，权威）

- **库清单与源路径**（LIBS 数组顺序即优先级）：
  | 库 | 源 | 排除 |
  |---|---|---|
  | diy2 | 纯抽象（srcrepo/diy2） | - |
  | cyclone | miXed/cyclone/{hammer,sickle,shadow} | cyclone.c, standalone.c |
  | zexy | zexy/src | zexy.c, z_zexy.c |
  | iemlib | iemlib/iemlib1/src + iemlib2/src | iemlib1.c, iemlib2.c |
  | fftease | srcrepo/fftease（顶层） | - |
  | bsaylor | bsaylor | pvoc~.c, partconv~.c（FFTW3 桌面依赖） |
  | creb | creb/modules | - |
  | cxc | cxc | - |
  | osc | mrpeach/osc | osc-help.pd |
  | midifile / binfile | mrpeach/midifile, mrpeach/binfile | - |
  | tracker / pocketpd | 仓内 slibs/tracker, slibs/pocketpd | - |
  - cyclone support：miXed/shared/{common,hammer,sickle,unstable}；
    inc 同源 + miXed/cyclone/{hammer,sickle}。
  - iemlib 抽象：iemlib/iemabs（152 个 .pd 已在 assets 幸存）；
    creb 抽象：creb/abs（47 个 .pd 幸存）。
- **ABI/工具链**：arm64-v8a + x86_64，NDK 27.2.12479018，
  `aarch64/x86_64-linux-android21-clang.cmd`；CFLAGS `-DPD -Os -w -fPIC`
  （Wno-implicit-function-declaration/int）；链接 `-lpd`，路径指向
  gradle 缓存解包的 pd-core AAR（jni/<abi>）；**pocketpd 额外 -lz**。
- **脚本内兼容补丁（幸存部分）**：
  - patches: `iem_pbank_csv.c` 的 `format->s_name[p] += 'a'-'A'` →
    `((char*)format->s_name)[p] += ...`（const 修正）。
  - EXTRA_C: creb 需要 `IS_DENORMAL`（pd≥0.54 才有该宏）：
    `int IS_DENORMAL(double x){ return x > -1.2e-38 && x < 1.2e-38; }`。
  - `uniquify_generic_symbols`：老源码里有非 static 的全局 `setup` 符号，
    用 llvm-nm 检出后 objcopy 重命名为 `setup_<sha1(文件)[:16]>`。
  - **master-drop**：调用 ≥3 个外部 setup 且自身无 class_new 的文件
    （总装器）不编译。
  - **include-wrapper 跳过**：只 `#include "xxx~.c"` 的文件跳过（cxc 族）。
  - **empty-TU 跳过**：编译产物 <2048 字节 = 全文件被平台宏包住。
  - `pd_symbols`：setup 符号推导，类名 `~` 结尾→`_tilde`，其他非法字符→
    `0x%02x` 十六进制转义（带转义时符号为 `setup_xxx`，否则 `xxx_setup`）。
  - 每库一个 `<lib>_core.so`（对象互相交叉引用，不能拆 per-object），
    core_wrap.c 生成全部 setup 的 extern 声明+调用，llvm-strip。
- **core_classes.txt**：每库目录下一份，逐类一行，是 pd 二进制加载器的
  符号链接清单（pd dlopen 精确路径 `<dir>/<类名>.so`，所以需要
  `<类名>.so → <lib>_core.so` 软链；app 端 Slibs.symlinkCoreClasses 用
  /system/bin/sh ln -sfn 批量建）。**必须增量合并（只增不删）**——
  0.17.68 的 vgm~ 漏登记就是手工维护的反例，现由构建期自动生成。
- **slibs_manifest.txt**：`lib<TAB>class<TAB>文件` 三列；喂给 app 的
  已知类集合/自动补全/冲突报告/帮助归属。**重建教训：它每次全量重写，
  被缓存跳过的库不会追加条目 → 空壳事故后 manifest 缩到 12 行，
  编辑器把 else/diy2 的活对象也标红**。重建时必须从
  core_classes.txt + 各库 .pd 文件名全量再生，而不是依赖增量编译。
- **0.17.91 补刀**：全量再生之外还有第二坑——support_setups 库
  （zexy）的类名经 zexy_new 宏注册，class_new 扫描看不到，manifest
  只有 pack/unpack 2 行（应为 ~101），编辑器把所有 [zexy/...] 画红
  （0.17.81-0.17.90 潜伏）。已修：zexy_new 字面提取 + core_classes.txt
  兜底再生（覆盖变量名注册如 lister）。前缀拼写（[zexy/pack]）的
  编辑器 exists 判定同轮修复：PdObjectMeta.resolve 统一按 bare 名查
  defs/抽象表/knownClasses。

## 2. 加载与裸名冲突系统（Slibs.kt，全幸存）

- **PRIORITY**（首者优先）：diy2, else, cyclone, zexy, iemlib, fftease,
  bsaylor, creb, cxc, osc, midifile, binfile, tracker, pocketpd。
- **反向 autoload**：openAutoload 按 PRIORITY.reversed() 经
  PdTweaks.nativeLoadCore(路径, `<lib>_core_setup`) 逐个加载；pd 的
  objectmaker 保留最后注册的类 → **先列者赢裸名**。fallback 为
  declare -lib 补丁。加载完成后同步 open/close autoload.pd 强制 pd
  提前启动（见 §5 冷启动竞速）。
- **实测冲突定案**（0.17.35 逐个用 <lib>_core.so 的 setup 字符串验证）：
  - 裸名 `svf~` 归 **bsaylor**，cyclone 版走 `cyclone/svf~`
    （examples/slibs/0-slibs-overview.pd 明文）⚠ 与 PRIORITY 推导顺序
    矛盾（cyclone 在 bsaylor 前），**重建后须实测复核**。
  - `sort` 裸名归 **zexy**；`stack` 编译版在 **cyclone** 而非 else；
    `median`/`voice` **没有编译版**（.so 字符串验证，纯 .pd）。
  - `split` 裸名 = **cyclone 赢 iemlib**（PdObjectMeta 注释）。
  - `route~` 按 zexy 版登记（PdObjectMeta 固定行）。
  - 同名冲突由 Slibs.logConflicts 在控制台报告（`'x' -> a (else b/c)`）。
  - **DUAL_COMPILE_PROTECT（15 个双形态名）**：sort, stack, hex2dec,
    pvoc~, pick, bpm, cmul~, count, e, inc, pi, rms~, sin~, tempo,
    pink~——既发 .pd 又有编译类的名字，逐一用 blob 的 setup 字符串
    验证归属；这些名字的 .pd 不做 per-instance inline。
- **pdClasses 三源纪律（0.17.35）**：vanilla+app-only 镜像 +
  Slibs.compiledNativeClasses（manifest 减捆绑 .pd 名）+ 上述双保护名单；
  **用户补丁目录永不进入**——0.17.34 曾把补丁目录名并进去，用户抽象
  停止 inline，四副本撞全局延时线（multiply defined ×4）。

## 3. IO 数量与可变 IO（重点：全部在案知识）

### 3.1 三层解析（PdObjectMeta.kt，全幸存）
1. **variadic 注册表**（查在静态 defs 之前，支持 lib/ 前缀拼写）——
   创建参数决定进出数。
2. **静态 defs 表**（376 行，OBJECT_PORTS.md 是它的渲染快照）。
3. 兜底：抽象名查 AbstractionPorts（扫 .pd 的 inlet/outlet 对象）；
   都没有时 tilde 启发式 = 左口信号入/单信号出、1进1出。

**测量方法论（重建 io 表必须沿用）**：探针补丁给对象接 8 进 8 出，
解码 pd 载入期 `(a->b) connection failed` 行定真值；33 批探针 +
pd 0.51 源码核对；数量是权威，信号口标注次之。全部实测记录写于
0.17.34（2026-11-05），表快照 = 工作区根 OBJECT_PORTS.md（幸存，
26KB），生成器 = Temp/vario/soanalysis/genports.py（幸存，可重新生成）。

### 3.2 可变 IO 公式全表（33 组，逐条自 PdObjectMeta.kt）
**vanilla**：
- `trigger/t`：1 进，max(2, 参数个数) 出
- `pack`：max(2, 参数个数) 进 1 出；`unpack`：1 进 max(2, 参数个数) 出
- `route`：≤1 参数时 2 进；出 = 参数数(去前导`-`)+1
- `select/sel`：≤1 参数时 2 进；出 = max(1, 参数数)+1
- `dac~/adc~`：max(2, 参数数) 个信号口
- `pipe`：槽位 = 参数去掉**尾部数字**（尾数字是默认延迟），≥2 槽；
  裸 pipe 2 进
- `expr`：进数 = 表达式里最大 `$f/$v` 序号；`expr~/fexpr~` 全进信号
- `sigmund~`：每个关键词（pitch/env/note/notes/peaks/tracks）一个出，
  无关键词 2 出，0 号口信号
- `readsf~`：1 进，首数字参数(≤64)+1 出（末口 EOF bang）；
  `writesf~`：n 个信号进
- `netreceive`：TCP 默认 2 出，udp（"1"/"udp"/"-u"）1 出

**cyclone**（裸名归 cyclone）：
- `gate`：2 进 n 出（n=首参数，默认 1）；`switch`：n+1 进 1 出（默认 2）
- `Bondo/bondo/Buddy/buddy`：N 进 **N 出**（默认 2；实测推翻 Max 的
  N 进 1 出）
- `Bucket/bucket/Cycle/cycle`：1 进 n 出；`Decode/decode`：3 进 n 出
- `Funnel/funnel`：n 进 1 出（默认 2）；`Spray/spray`：1 进 n 出（默认 2）

**zexy**：
- `relay`：1 进 max(1, 参数数)+1 出
- `mux/multiplex`：**3 进 1 出固定**——靠 `n <count>` 消息扩展，
  创建参数**不**生效（实测定案）；`demux/demultiplex`：2 进 2 出固定
  （同上）；`niagara` 2/2；`packel` 2 进 1 出

**else**：
- `gate~`：**固定 2 进**（selector+signal），n 个信号出
- `select~`：n+1 进 1 出（1..n 信号）；`selector`：n+1 进 1 出（默认 2）
- `router`：2 进 n 出（默认 2）；`unmerge`：2 进 n+1 出（默认 1）
- `pack2`：max(2, 参数数) 进 1 出
- `xgate~`：2 进 n 信号出；`xselect~`：n+1 进 1 出；
  `xselect2~`：n+1 进 n 出（n≥2 默认 2）

**iemlib / mrpeach**：
- `iem_route`：同 route 规则（≤1 参数 2 进；出 = 参数数+1）
- `routeOSC`：1 进，1 或 参数数+1 出

### 3.3 实测定案的静态修正（0.17.34 清单，重建 defs 必收）
- cyclone：`uzi/Uzi`(2,3)、`counter`(5,4)、`coll`(1,4)、`match`(1,1)、
  `split`(3,2)、`Peak/Trough/peak/trough`(2,2)、`zl`(2,2)
- zexy：`route~`(1 信号进，2 信号出)、`list split`(2,3，末端 bang 出)
  ⚠（注意：表里 `list split` 2/3 是 0.17.34 定案；vanilla 的是 2/2）
- else：`voices`(2,2)、`pan4~`(3,4，全信号口)、`square~` **4 进 1 出**
  （freq/脉宽/相位偏移/相位同步，前 4 口全信号——help+8×8 探针双验证，
  0-3 进成功、4-7 拒）
- vanilla：`threshold~` 0.51 为 2 进 1 出（没有 0.54 的第二出）
- else 帮助声明的系统采信（0.17.35，160 行）：above~/add~/adsr~(5,2)/
  allpass 族/bl.* 振荡器族/saw~/tri~/pulse~(4,1)/impulse~(3,1)/
  pmosc~(4,1)/pan2~(2,2)/output~族/低通高通带阻家族/xmod~(4,2)/
  zerocross~(1,3)/svfilter~(3,4) 等；方法 = help 补丁 inlets/outlets
  声明段的连续 cnv 端口标记（0..n-1 才采信，44 个声明异常丢弃）、
  只收无 .pd 孪生的编译类、else_core.so 独占裸名才收。
- **已知上游缺陷**：else `mtx.ctl`/`mtx~` 同画布混用会崩 pd 0.51
  （画布栈损毁，应用侧不可修，避免混用——重建后此条仍有效）。
- **画布刷新**：改参数后必须 contentMutated() invalidate（0.17.34），
  重建 UI 层时勿回退。

## 4. 运行时集成教训（重建加载层时逐条对照）

- **冷启动竞速**（0.17.33）：slib setup 排队到音频线程首 tick，
  intent 自动开补丁会看到占位盒。对策：openAutoload 末尾同步开/关
  autoload.pd 强制 pd 提前启动 + settle 重开门控。
- **settle 重开门控**（0.17.36）：只有"pd 认识的类创建失败"才排 4.5s
  重开（≤3 次）；pd 0.51 失败输出是"binbuf 盒子文本行 + `... couldn't
  create`"两段，**必须与前一行配对取首 token** 才是真实类名。
  缺失抽象/改名对象不得触发重开。
- **首 parse 前类必须就绪**：slibs 开机即加载完（13/13 计数日志在
  `slibs: N/M library cores loaded`）。
- 新增/改名 slib 对象类的**检查单**（AGENTS.md）：① PdObjectMeta 端口行
  ② core_classes.txt（构建期自动增量）③ bump Slibs.VERSION。

### 4.1 重建期新教训（0.17.81-0.17.84，全部实爆过）

- **dlopen 预检**：每次 build_slibs 后必须跑 `slibs/audit_blobs.py`
  （未定义动态符号 vs libpd.so+AAR 库+bionic 导出，双 ABI）。
  符号导出验收（blob 导出了 setup 符号）≠ 可加载 ≠ 类被注册。
  0.17.81-0.17.83 三版带着 cyclone/zexy/creb 三库死亡发布。
- **core_wrap 调用数必须对照基线类数**：符号"导出"≠"被调用"。
  zexy 的教训：RE_SETUP 锚 ^void 匹配不了 `ZEXY_SETUP void xxx_setup`
  （ZEXY_SETUP 是空宏），且 zexy 对象经 zexy_new 宏注册（无字面
  class_new）被收集器归入 supports 分支——supports 循环原来不提取
  setup 调用，core_wrap 只调 2 个，设备上整库只剩 2 类。构建日志
  现在打印每库 `core_wrap calls N setups`，重建后逐库对照基线。
- **purr-data 代码依赖 GUI 桥/fork 内部符号**：gui_* 六件套 +
  glist_grabx + canvas_apply_setundo 在 libpd 运行时不存在 →
  EXTRA_C 无操作垫片 TU（cyclone），EXTRA_C 编译失败必须响亮报错
  （静默跳过 = 复现 0.17.82 模式）。
- **creb modules++（.cc）需要 C++ 运行时**：libpd 不加载任何 C++ 库，
  creb 链接追加 NDK 静态 libc++.a（dlopen 内部解析 operator new/
  personality）。
- **AV 清空行为三连爆**：含端口 IO 触发词的源文件任何"fresh 写盘"
  （手工写、补丁回写都算）都会 0.2s 内被清零——zexy lpt.c 已重写为
  全文无触发词的平台 stub（类照常注册），**永远不要手工"恢复"上游
  lpt.c**；重写 stub 时也保持无触发词。
- **setup 数 ≠ 类数**：多类文件（creb bfft~.c 等）一个 setup 注册
  多个类；对照基线时按类映射，别拿 setup 计数当类数。
- **手改 .pd 的两条铁律**（0.17.85）：① 往文件中部插入对象必须重排
  全部 #X connect 索引——规范做法是把新增对象追加到文件末尾（显示
  位置由坐标决定）；② .pd 文本内容里 `;` `,` 是语句/消息分隔符，
  必须转义 `\;` `\,`，裸分号会把 text 语句拦腰截断。跑
  `slibs/lint_pd.py`（抓裸分号残句/索引越界/msg 裸逗号）。

### 4.2 pocketpd 内嵌第三方核（0.17.88 起）

- **reSID**（sid6581~ 的 SID 核）：github.com/daglem/reSID master
  vendored 于 `slibs/pocketpd/src/resid/`（GPLv2+ © Dag Lem；COPYING
  随附）。vendored 源码**只允许两处标注式改动**（sid.h/sid.cc 的
  voice_outputs accessor，diff 已核对）；siddefs.h 是手工配置（configure
  的 pre-C++20 降级值：RESID_CONSTEVAL 空、RESID_CONSTEXPR=const——
  constexpr exp/log2 只有 GCC/Clang 扩展支持）。build_slibs：pocketpd
  链接追加 NDK 静态 libc++.a（creb 先例），inc 加 resid 目录；重建后
  必须 audit_blobs + core_wrap 计数对照（0.17.88 起 pocketpd = 5 setups）。
- **6502/6510 核 sid6502.c 自研**（TinySID 带 FORBIDDEN 声明不可
  vendor；fake6502 的 TU 静态全局多实例互踩）。sidreplay.c 的 PSID
  事实（大端头、init A=X=Y=song-1、/usr/bin/bash1=0x37）逐一对照 libsidplay2/
  libsidplayfp 实证，未复制代码。
- 宿主验收工具：C:/Temp/ympdcore/host 的 harness29（引擎级，stub 无
  dsp 参数）/harness30（对象级，stub m_pd.h 下 sid~→sid6581~ 完整
  拓扑），build29/30.bat。MSVC 编译 reSID 需 /TP /std:c++17 且 C 部分
  不得 /TP（harness28 教训的镜像）。

## 5. 丢失清单（重建范围）→ 0.17.81 重建结果

**十库全部重建完成（2026-11-16，0.17.81）。** 来源与补丁沉淀在
build_slibs.py（patches 字典，幂等锚点）；验收 = 符号级+别名豁免对照
幸存 core_classes.txt。

| 库 | 来源 | 结果 | 残余缺口 |
|---|---|---|---|
| cyclone | purr-data miXed | 216 类（181 超集） | 无（setup 大写式，裸名经 core 加载注册） |
| zexy | purr-data + Debian 2.3.1 补 lpt.c | 94 setup 调用（基线 102 = 95 实类 + 7 惰性残行 + lp 缺口；hex 名类经 setup_0x 调用） | [lp]（pd-extended 一阶滤波，2.x 移除，SVN 死）；lpt = 平台 stub（AV 三连爆后全文无触发词，见 §4.1） |
| iemlib | git.iem.at master | 99/99 | [forpp]（for_pp 错拼别名，1.22 删） |
| creb | Debian 0.9.2+darcs + 幸存 modules++(.cc) | 46 对象全覆盖 | 无 |
| fftease | Debian 2.5.2.git20121005 | 33 类 | cross~/enrich~/loopsea~/pileup~/pvwarpb~/residency_buffer~/schmear~（3.0 API 代差） |
| bsaylor | purr-data | 4/4 | 无 |
| cxc | purr-data | 32（28 超集） | 无 |
| osc | Debian pd-mrpeach svn17672 | 4/4（老命名 packOSC/unpackOSC） | 无 |
| midifile/binfile | purr-data | 1/1 | 无 |

重建要点（后续再动必读）：
- creb 的 modules++ 是 **.cc**（10-10 清洗只删 .c，.cc 全幸存）——收集
  过滤器已接受 .cc；复杂 FFT 族（bifft~/fht~/nfft~…）在 bfft~.c/
  cmath.c 等**多类文件**里，一个 setup 注册多类，符号级验收要按文件豁免。
- zexy 的 lpt.c 在本机有 AV 实时清空行为（fresh 写入含 ioperm/outb 文本
  0.2s 内被清零）——srcrepo 里的版本已预死码化（`# elif 0` + 
  `#undef __linux__`），直接从 tar 重新解原文件会复现被清空。
- supports 循环现在也跑 uniquify（zexy ~100 宏注册文件裸 setup 撞名）。
- 链接 = 临时名成功才 os.replace；GUARD 拒绝空源码链接。
- cyclone 符号 purr-data 大写式（Scope_tilde_setup）：qualified 拼写
  （cyclone/xxx）依赖类内注册的别名，勿回退到裸符号查找假设。

| （以下为 10-16 事故时点的原始丢失清单，留档）

| 库 | 丢失物 | 幸存参照 |
|---|---|---|
| cyclone | miXed 全源码 + blob | 181 类清单、help、demo |
| zexy | zexy/src + blob | 101 类清单 |
| iemlib | iemlib1/2 源 + blob | 66 类清单 + **152 个 .pd 抽象完好** |
| fftease | 源 + blob | 39 类清单 |
| bsaylor | 源 + blob | 4 类清单（pvoc~/partconv~ 本就排除） |
| creb | modules 源 + blob | 31 类清单 + 47 .pd 抽象完好 |
| cxc | 源 + blob | 28 类清单 |
| osc | mrpeach/osc 源 + blob | 4 类清单 |
| midifile | 源 + blob | 1 类清单 |
| binfile | 源 + blob | 1 类清单 |
| （else） | 无丢失 | blob 671KB + 217 类 + help 全在 |
| （diy2） | 无丢失 | 纯抽象 157 .pd 全在 |

重建验收标准：① 每库编译产物的类集合 ⊇ 幸存 core_classes.txt（不得
少类；多出的上游新类允许，进增量合并）；② 全库 manifest 重生后编辑器
红标/自动补全恢复；③ OBJECT_PORTS.md 用 genports.py 重新生成并对
抽查对象重跑 8×8 探针；④ demo 补丁（examples/slibs/*-demo.pd）逐个
出声；⑤ 已知冲突归属按 §2 复测。

上游源建议（gh-proxy 通道）：zexy = umlaeute/zexy、iemlib =
iem-projects/iemlib（这两个现代 repo 就是 10-03 构建的实际来源）；
miXed/creb/cxc/bsaylor/mrpeach/fftease/diy2 为 pd-extended 系
（pd-extended 镜像 404 待换源：purr-data externals、katjav 镜像等
候选逐一试探）。**上游 ≠ 补丁态**：patches/EXTRA_C/uniquify 只是
幸存的脚本侧补丁，源码内散布的手改会随编译错误重新暴露，逐个重修。
