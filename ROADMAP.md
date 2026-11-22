# ROADMAP.md — PocketPd 逐轮路线表（0.2.0 → 0.17.161）

> 从 CHANGELOG 294 轮逐轮整理的路线表：每轮的新增/修复/变更各占一行。
> 判读规则（弧线终点为准）见 AGENTS.md「CHANGELOG = 项目地图」；运行时
> 版本考证见 PD_VERSIONS.md；0.57 迁移调查见 MIGRATION_057.md。
> 类型缩写：**新增**=功能/对象/库；**修复**=bug；**变更**=行为/接口调整；
> **回退**=撤销此前改动；**事故**=事故与善后记录。

## 段 1：0.2.0–0.3.0（vc 1–2，2026-09-22）初始发布

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.2.0 | 新增 | libpd 运行时（audio/消息轮询/receiver 分发）；补丁库（列表/新建/导入/重命名/删除/示例抽取）；可视化编辑器（画布缩放平移、obj/msg/注释/num、iemgui 部件、连线/取消、undo/redo、复制粘贴、编辑-运行双模式）；名称栏（~150 vanilla 对象分类提示）；控制台；OSC 桥（/addr ↔ [r addr]，出站 [r OSC]）；MIDI 桥；官方示例 159 例（只读保护） |
| 0.3.0 | 新增 | Pd 字标 logo（自适应图标）；签名组织 EUfON（debug/release 同签名）；Settings OSC 区本机 IP 显示 |

## 段 2：0.4.0–0.6.3（vc 3–9，2026-09-22）抽象/图形/传感器

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.4.0 | 新增 | 外部 pd 文件（abstraction）支持：files/externals + Android/data 共享目录、SAF 导入、搜索路径注册（用户目录优先）、端口索引（扫描 inlet/inlet~/outlet/outlet~）、名称建议合并 |
| 0.4.1 | 修复 | 外部目录被 root 属主污染时 EACCES（可写性探测自动回退内部存储；新建补丁异常保护） |
| 0.5.0 | 新增 | graph/数组显示与编辑：#X array/#N array + #A 数据 + #X coords(GOP)、运行 10Hz 轮询实时波形、Run 态触摸绘制回写；GRAPH 键；[table] 按 graph 显示；子画布块按 inlet/inlet~ 绘制端口 |
| 0.5.0 | 修复 | 超长注释撑爆 zoom-to-fit；注释剥离 pd 0.54 的 `, f N` 字号属性 |
| 0.6.0 | 新增 | 传感器对象 [accel][gyro][magnet][light][pressure][humidity]（~50Hz，Java SensorBridge→receiver 抽象） |
| 0.6.1 | 变更 | 传感器改名（accelerometer→accel 等，避免冲突）；num 默认宽 5→8；num 框随数值加宽 |
| 0.6.2 | 新增 | msg 分号按桌面 pd 换行显示；CHANGELOG 开篇 |
| 0.6.2 | 修复 | msg 分号内容丢失（名称栏 apply 未转义，裸 `;` 截断 .pd 语句）——storeEscape + 防二次转义 |
| 0.6.2 | 变更 | 端口矩形完全随缩放（1px 下限）；只读文件 Save 弹带提示的 Save as |
| 0.6.3 | 修复 | radio 方格修正（每选项 size×size；兼容 pd 0.54 格数 token 4 与旧 token 3；尺寸钳制放宽至 128 格） |
| 0.6.3 | 新增 | slider 属性 Min/Max 输入框与 Integer steps 开关（app 专属 #int 尾 token，会话剥离）；Properties ScrollView |

## 段 3：0.7.0–0.9.5（vc 10–22，2026-09-24）十库内置与基建

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.7.0 | 新增 | 十库内置 524 类：cyclone 215 / zexy 99 / iemlib 89（含 iemabs 152 抽象）/ fftease 38 / creb 41 / cxc 32 / bsaylor 4 / osc 4 / midifile 1 / binfile 1（NDK 编译，每库一个 <lib>_core.so，双 ABI）；裸名与 `库/类` 前缀两种调用；同名冲突逆优先级注册（cyclone>zexy>…） |
| 0.7.0 | 新增 | libpdtweaks.so（4 ABI）：dlopen 各库 core 调 setup（等效外部加载器）；sys_noloadbang=0 桌面 loadbang 语义；失败降级 [declare -lib] |
| 0.7.0 | 修复 | 构建期兼容：creb IS_DENORMAL 依赖 pd 0.54 宏；空翻译单元跳过；zexy 特殊字符 setup 重命名；cyclone forky 支持文件；iem_pbank_csv const 改写；K&R 宽容 |
| 0.8.0 | 新增 | 库帮助补丁 651 个内置（build_help.py）；例程抽象（output~/adsr…）入搜索路径与端口索引；**GOP UI 显示与交互**（g_editor/g_graph 语义）；GOP 会话内联（$0/$N 替换、连线重定向）；11 个库例程 demo |
| 0.8.1 | 新增 | 长按菜单 Open（抽象源文件）/Help（<名>-help.pd），库目录优先、大小写不敏感 |
| 0.8.1 | 修复 | AbstractionPorts 冷启动 ANR（零正则快速解析） |
| 0.8.2 | 新增 | GOP 展示范围（goprect）可视化 + 补丁 Properties 编辑 #X coords |
| 0.8.2 | 变更 | 右上菜单改 3×3 网格（去重复项） |
| 0.8.3 | 新增 | diy2 库（plugdata 0.9，157 对象，裸名最高优先）；`库/对象` 形式进入端口索引与建议 |
| 0.8.3 | 修复 | 对象盒宽度保证容纳引脚数；GOP 内 nbx 绘制/拖动/回显；hsl/vsl isa 槽位错位；iemgui label 显示与编辑；cnv 不再把参数 token 画进框 |
| 0.8.4 | 新增 | pd 无法创建的对象（不在知识面）红色虚线框、无引脚不可连线 |
| 0.8.5 | 新增 | pd 运行时 "couldn't create" 对象同样警示标色（提取失败名单，run 重开刷新） |
| 0.9.0 | 新增 | console 从弹窗改常驻条（顶栏下固定 5 行，报错红色，菜单开关，Settings 行数） |
| 0.9.0 | 修复 | 编辑器冷启动 ANR（端口索引后台扫描；console 单行截断 2000 字符） |
| 0.9.1 | 变更 | console 深色配色、Clear 位置、开关记忆、默认关闭、1dp 分界线 |
| 0.9.2 | 新增 | console 展开/收起按钮（45% 屏高） |
| 0.9.3 | 变更 | console 两按钮位置对调 |
| 0.9.4 | 变更 | 补丁列表紧凑（顶栏 46dp 等） |
| 0.9.5 | 修复 | 缩写别名类（t/sel/b/f/i/s/r/v/del/s~/r~）红框；两词类名（list append 等）端口解析；补收 9 个缺类 |

## 段 4：0.10.0–0.11.3（vc 23–35，2026-09-24→09-28）tracker 库与外围桥

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.10.0 | 新增 | tracker 库：[modseq]（XM/MOD 音序器，事件流输出）、[smp~]（wav 采样播放器，Hermite 插值、smpl 块）；demo 与 help |
| 0.10.1 | 修复 | tracker demo 移到 examples/slibs 顶层（分类只扫顶层） |
| 0.10.2 | 新增 | [instr] 独立乐器节点（采样库/键位图/包络/声部池）；[modseq] 重写为纯歌曲侧 + 效果引擎补全（全 XM 效果码）；tracker-demo 全量演示 |
| 0.10.3 | 变更 | demo 全动态化：dump→smpfile 元消息→动态加载，删除 16 个预提取 wav |
| 0.10.4 | 新增 | [instr] vol 方法（modseq 实时通道音量） |
| 0.10.5 | 修复 | tracker 补丁/help 文本内 `,` `;` 清理（消息分隔符事故） |
| 0.10.6 | 变更 | 采样改 pd 原生数组（去 wav 导出） |
| 0.10.6 | 修复 | 效果引擎循环回绕越界（Hermite）、garray_getfloatwords 64 位、smp base/loop 解析、instr vol |
| 0.11.0 | 新增 | BLE-MIDI（独立通道+扫描）；OTG 串口桥（usb-serial-for-android，[r tty0]/[s TTY]）；About 开源声明逐库核对；manifest 蓝牙/USB 声明 |
| 0.11.1 | 修复 | [inlet]/[outlet] 类名表缺失（红框）与端口方向写反；GOP widget 拖拽跟随；console 长按复制 |
| 0.11.2 | 修复 | console 完全不显示（setTextIsSelectable buffer 脱钩，身份自检兜底）；[inlet]/[outlet] 方向再修 |
| 0.11.3 | 新增 | 补全加 [inlet]/[inlet~]/[outlet]/[outlet~] |

## 段 5：0.12.0–0.13.17（vc 36–61，2026-09-30→10-01）PC-98 视觉与编辑器功能

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.12.0 | 变更 | 视觉整体切换 PC-98/VHS：pd98 色板、F5.6 像素字体、画布/OSD/浏览器/设置/对话框重做、版本号进 0.12.x |
| 0.12.1–0.12.7 | 修复 | 扫描线全屏落地、弹窗定宽/高度还原、标题条按键去框、字号 12sp、OSD 取色、扫描线剂量、启动图标（逐轮按稿校准） |
| 0.13.0 | 新增 | 画布 VU 电平条（10 段）、编辑器对象条测试卡样式、启动图标重构 |
| 0.13.1 | 新增 | VU 双声道化（上=左下=右）；编辑器自动注入 dac 抽头链（s pocketpd-vu-l/-r） |
| 0.13.2 | 修复 | VU 链改 vanilla 对象（env~ 被 cyclone 遮蔽为数字量）；打开补丁一律走会话副本 |
| 0.13.3 | 变更 | 对象条配色/键宽随文字、拖拽幻影即键帽 |
| 0.13.4 | 修复 | EDIT→RUN 后 VU 失联（closePatch 保留 pocketpd-vu-* 订阅） |
| 0.13.5 | 新增 | 自动开 DSP（进补丁/EDIT→RUN；mic 未授权走纯输出） |
| 0.13.6 | 修复 | VU 单声道补丁右排清零（抽头按 dac 进线按需创建） |
| 0.13.7–0.13.10 | 变更 | 工具条图标像素化三轮、NEW PATCH 键入工具条、浏览器 PC-98 化 + DUPLICATE、键帽配色 |
| 0.13.11–0.13.13 | 新增/回退 | CRT 辉光 v1（分带晕）→ 全屏后处理 → 整体移除（用户否决） |
| 0.13.14 | 变更 | Properties 弹窗 PC-98 套件重做；**移除 inlet/outlet 数量设置**（端口数纯派生） |
| 0.13.15 | 修复 | 弹窗左右边距不对称（shadowInset） |
| 0.13.16 | 新增 | 文本/图形编辑切换键；纯文本编辑页 + 语法着色（严格 16 色）；双向同步与命令栈清除 |
| 0.13.17 | 修复 | 连接曲线锚点收敛（lead ≤ |Δy|/2） |
| 0.13.17 | 新增 | OSD 长按 LOCK 模式；**else UI 控件雏形**（graph~/spectrograph~/keyboard 的 vanilla 抽象版 + announce 机制） |

## 段 6：0.14.0–0.14.25（vc 62–87，2026-10-01→10-04）ELSE 全量与运行时补课

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.14.0 | 新增 | **ELSE v1.0-beta20 全量打包**：216 个 C 类 → else_core.so、137 抽象、manifest 333 类；与 cyclone/zexy/creb 19 处同名冲突按 PRIORITY 裁决；LOCK 徽标重做与收尾审计 |
| 0.14.1 | 变更 | LOCK 冻结页面 chrome（顶栏/控制台禁用压暗） |
| 0.14.2 | 新增 | ELSE 对象进自动补全（classNamesByLib） |
| 0.14.3 | 修复 | 两处未生效旧改动（LOCK 徽标静默未命中；补全库映射方向反） |
| 0.14.4 | 修复 | else manifest 重生成（objects.txt 只取行首 → 226 C 类+137 抽象=363）；帮助树 332 个；补全前缀优先排序 |
| 0.14.5–0.14.6 | 修复 | 启动图标三角（左缘/角度保持） |
| 0.14.7 | 修复 | else 帮助 `./output~` 相对引用补齐（avg/output~/out~ 进 help 目录） |
| 0.14.8 | 修复 | **RUN 态验证方法修正**（EDIT 看台不可信）；else_core 漏调 setup（pack2/merge，生成器重写）；`else/` 前缀 symlink farm（core_classes.txt）；GOP 内联 $-替换三错重写（\$ 剥离、虚线 $N→uid、send 型 $N→uid） |
| 0.14.9 | 修复 | graph~ RUN 四层：上游内脏放弃（array 消息打歪→Scudo 崩）→ 最小桩；**全工程从未调 computeAudio(true)** 补上；libpd 永久吞 loadbang → scope-go 触发器；数组名用文档 id |
| 0.14.10 | 新增 | spectrograph~ 真 FFT 链桩（rfft~→sqrt→tabwrite） |
| 0.14.10 | 变更 | 符号链接农场扩到 12 库；manifest 清 70 行内部注册；跨库 15 组冲突胜负定案 |
| 0.14.11 | 变更 | scope 绘制线细化（1.2dp/2dp） |
| 0.14.12 | 修复 | 补全缺 148 个 vanilla 类（pdClasses 并入建议池）；全角字符规范化；确认 rp~ 不存在 |
| 0.14.13 | 修复 | 补全混入无 io 条目（AbstractionPorts 三源过滤；manifest 清 34 行无 io） |
| 0.14.14 | 修复 | msg 盒形状对齐 pd（右缘内折尖朝左；多行尖延长成线） |
| 0.14.15 | 修复 | **defs 信号口全面修正**（调频口/右入口/snapshot 等 30+ 类）；graph 曲线越界 clip；**keyboard 多点触控重写**（逐指跟踪/滑音/释放集合）；**inlineGop 两结构 bug**（端口穿通、block~ 丢弃）；**信号线不再被中继改写**；ELSE UI 控件全演示 |
| 0.14.16–0.14.17 | 修复 | 文本/图形切换图标重绘两轮（家族光学 footprint） |
| 0.14.18 | 新增 | Properties 按类补齐参数行 + 非 iemgui ARGUMENTS 行；变量管理器（Rename/Delete/New value） |
| 0.14.20–0.14.22 | 新增 | CRT 荧光辉光（RenderEffect+SCREEN 混合；迭代坑留档）→ 黑屏修复（内容无条件绘制）→ 强度 slider |
| 0.14.23–0.14.25 | 修复 | 图标光学高度；辉光实时跟随（onDescendantInvalidated）；Externals 字体；Properties APPLY 标脏；分割线溢出 |

## 段 7：0.15.0–0.15.44（vc 88–132，2026-10-04→10-28）AI 助手与控件细化

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.15.0 | 新增 | **AI 生成补丁**（文本模式 GEN/STOP/REVERT；设置区端点/key/模型；LlmClient 零依赖） |
| 0.15.1 | 新增 | 非法 signal 连线警示色（逐帧派生，自愈） |
| 0.15.2 | 变更 | AI 请求期间 prompt 禁用；GEN BUSY 1s 硬闪；API format 选择（Anthropic/OpenAI）；辉光默认 70% |
| 0.15.3 | 修复 | 长补丁名挤压顶栏（动态 maxWidth+marquee） |
| 0.15.3 | 新增 | 溢出菜单 Share（EXTRA_TEXT+FileProvider 文件流） |
| 0.15.4–0.15.5 | 变更 | GEN 键 BUSY 色相循环闪烁；亮相反色实心 |
| 0.15.6 | 修复 | console 打印行抢命名框焦点（纯滚动+focusable=false） |
| 0.15.6 | 变更 | 生成引导词补强（SIGNAL RULE；toggle→tgl） |
| 0.15.7 | 修复 | console 选择复制回归（回滚 0.15.6 的 focusable 一刀切） |
| 0.15.8 | 修复 | table 三问题：轮询死锁（[table] 现场合成 graph）、触控逐格插值、缩放逐列 min/max 包络 |
| 0.15.9 | 新增 | msg 运行态点击闪光；table/graph point/polygon/bezier 三样式（Properties+持久化） |
| 0.15.9 | 修复 | 圆点初始色 bug；`; 数组名 下标 值(` 只对 [table] 生成的数组生效（pd 语义备注） |
| 0.15.10 | 变更 | GEN 灭灯态改灰键帽；文本/多选键对调 |
| 0.15.11–0.15.14 | 修复 | Properties 下拉重做（pd98 布局）与像素级对齐三轮 |
| 0.15.15 | 修复 | msg→floatatom 不刷新（reopenSessionForRun 漏 autoBindGui）；symbolatom/listbox runtimeText 显示 |
| 0.15.16–0.15.17 | 新增/修复 | 文件内 subpatch 打开（Open+写回）；popSubpatch 写回丢失 |
| 0.15.18 | 修复 | `#X obj … floatatom` 形式归一化（intatom→floatatom）；保存统一 #X floatatom |
| 0.15.19 | 修复 | $1 传值断链（iemgui send/rcv 槽位 "0" = 未绑定，与 empty/- 同视） |
| 0.15.20 | 修复 | `$` 存储转义（toText 写 `\$`；解析端去转义）——抽象参数/$0/msg 模板存盘往返 |
| 0.15.21 | 修复 | gatom obj-form 存盘重复 kind |
| 0.15.21 | 变更 | 真 1GB 内存测试（-qemu -m 1024；可正常运行，PSS 45–57MB） |
| 0.15.22–0.15.26 | 变更/回退 | spectrograph 显示弧线：细柱/dB/对数轴 → 用户裁决回滚 → app 侧 hann FFT → 密柱 → **撕读修复**（app 时钟采集，bang→30ms→读） |
| 0.15.27 | 修复 | [gate]/[switch] 引脚数按 cyclone 源码修正（gate 控制口在右、n 出；switch n+1 进） |
| 0.15.28 | 修复 | 双指捏合自绘判定（去 ScaleGestureDetector 最小跨距限制；LOCK 冻结相机） |
| 0.15.28 | 事故 | space.pd 误删（重装 uid 变更+FUSE）→ **设备文件禁令**入 AGENTS.md |
| 0.15.29 | 新增 | [table] 容量实时 resize（[; 名 resize N(）；Edit text/属性接钩子 |
| 0.15.29 | 新增 | msg pmsg 中继注入子图块（递归）+ 全局 ordinal（levelMsgOffset） |
| 0.15.30–0.15.33 | 修复 | 多选长按弧线（快照执行、longPressFired、sawSecondPointer、 GestureDetector 同帧竞态彻底修复）；spectrograph~-help 进程崩溃（canvas_getargs SIGSEGV → help 不建会话，后被 0.17.9 反转）；Settings 权限竞态守卫；LOCK 多点触控 |
| 0.15.34 | 修复 | sigmund~ couldn't create：**PdCoreExtras**（9 个 vanilla extra .so 抽出+nativeLoadCore）；sigmund~ 关键字端口表；table 改名缓存刷新 |
| 0.15.35–0.15.36 | 新增 | AI 流式（SSE 双格式、thinking、300s 读超时）；THINK 状态行；GEN 会话（CTX/NEW/REVERT 语义） |
| 0.15.37–0.15.39 | 修复 | NUL/"null" 弧线（stripNul、textOrEmpty、假阴性测试自省）；console 120ms 节流；提示词对象范围纠错 |
| 0.15.40 | 修复 | 同模块双实例 delwrite~/vd~ 冲突（GOP 内联实例 uid 前缀）；补丁目录抽象进 Abstractions 搜索 |
| 0.15.41 | 修复 | 非 GOP 抽象同样逐实例内联（multiply defined 全治）；深度一层 limitation 记录 |
| 0.15.42 | 修复 | pd 侧触发控件不更新（send 符号回显订阅；tgl bang/echo 双翻转避免）；walkSubpatchGuis 块内绑定 |
| 0.15.42 | 新增 | [button] 控件（按下 1 松开 0，pd 无此类，盒子移除走 relay） |
| 0.15.42 | 变更 | [random] range<1 会话兜底 128（pd 原生语义备注） |
| 0.15.43 | 修复 | [bng]/[hradio]/[vradio] 被换成 iemlib 帮助内脏（pdClasses=原生类集合，从不内联/从不 GOP 扩展） |
| 0.15.44 | 变更 | button 默认 24×24 方形 + Width/Height 属性 |

## 段 8：0.16.0–0.16.7（vc 133–140，2026-11-01）euclid 控件与中继体系定型

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.16.0 | 新增 | [euclid] 欧几里得节奏控件（Bjorklund 算法全量验证；relay 化：盒不进 pd、双腿改接、信号线丢弃；Properties 运行模式长按可开） |
| 0.16.1 | 修复 | euclid 触发/输出全死（relay 少一条腿——改接后落回通用层）；指针差一拍；send/receive 改瞬态字段（autoSend/autoReceive）；btn 简称 |
| 0.16.2–0.16.4 | 变更/修复 | 指针就绪位/颜色语义弧线；**euclidLive 门控**（edit 惰性防旧会话触发）；run↔edit 复位统一 toggleMode |
| 0.16.5 | 修复 | 多 euclid 互相抢触发（克隆共享中继名→克隆即改名+冲突修复+子 patch 递归） |
| 0.16.6 | 变更 | 表盘中心字带 rotation（r1） |
| 0.16.7 | 变更 | steps 上限 64；msg 实时配置（hit/step/rotation 单项或连写）；裸 float=跳转+触发 |

## 段 9：0.17.0–0.17.16（vc 141–158，2026-11-01）circle5 与 defs 审计

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.17.0 | 新增 | [circle5] 五度圈控件（12 格三环、点击出和弦 MIDI list、inlet 级数、chord/size 消息） |
| 0.17.1–0.17.7 | 修复 | circle5 弧线：反色/串色/映射方向/单音和弦（size 0 钳制）/字号/缩写/out1 撤回 |
| 0.17.8 | 修复 | vanilla 端口定义清查（对照 pd 0.51.3 源码逐类修 defs：float/f 2 进、line 2 进、poly 1 出、pipe 单出、expr 家族按 $f 计数、信号口修正等） |
| 0.17.9 | 变更 | help 补丁真正运行（撤掉"help 不建会话"闸门——0.15.32 的 SIGSEGV 已被会话构建器覆盖） |
| 0.17.10 | 新增 | 新建补丁懒会话（首次 tap 时建） |
| 0.17.11–0.17.12 | 修复 | floatatom 2 进弧线（0.17.11 修对对象、0.17.12 回退错误改动+加常驻 console 追踪） |
| 0.17.13 | 修复 | 会话跟随编辑（全部 mutation 点统一 onContentMutated，400ms 防抖） |
| 0.17.14 | 修复 | loadbang 标志在每次 openPatch 前重设（PdAudio reinit 重置吞噬） |
| 0.17.14 | 规则 | UI 测试禁令入 AGENTS.md |
| 0.17.15 | 修复 | **全工程 PdBase 零加锁**→26 处入口统一 synchronized（传感器流竞争吞消息）；派发链常驻追踪 |
| 0.17.16 | 修复 | defs 全量机械提取（pd 0.51-3 源码 30 文件逐类核对：value/send 变参、poly/makenote/moses/netsend/tab*/slop~/biquad~/vu 等十余处修正；删除幻影 realtimein） |

## 段 10：0.17.17–0.17.36（vc 159–178，2026-11-02→11-05）串口/子图/变参/ym2612~ 起步

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.17.17 | 修复 | circle5 小调建模（有符号 key；级数旋转 5；越界崩溃顺带修） |
| 0.17.18–0.17.20 | 修复 | 串口弧线：spinner off-by-one、vid:pid 稳定 key、状态行、按行组帧（Line framing 开关）、读重试、**timeout=0 异步 UsbRequest 路径**、console 无锁队列合并刷新 |
| 0.17.21–0.17.24 | 修复 | RUN 长按弧线（结构性只在 EDIT 锁存）；radio 按下即切+按住滑动跟随 |
| 0.17.25 | 修复 | 打开含行内子图块必崩 StackOverflowError（walkSubpatchGuis 自递归——头行包进子列表） |
| 0.17.26 | 修复 | 块内延时线 multiply defined（prefixBlockDelays 递归）；[pd x] 端口数刷新；标题脏点派生渲染 |
| 0.17.27–0.17.30 | 新增/修复 | [pad] 多点触控板三轮（out0 组结构两层编码 `n x1 y1 e1 …`；console 刷屏节流；帧合并） |
| 0.17.31 | 修复 | console 逐消息刷屏（取证日志迁 trace()，仅 logcat） |
| 0.17.32 | 新增 | [pd ym2612] 模板（~290 对象，0.17.33 删除） |
| 0.17.32 | 修复 | msg 盒 MSGBOX: 前缀；本 build [*~] 右入 signal 失效（模板内改 expr~ 规避）；canvas vinlet 丢 list |
| 0.17.33 | 新增 | **ym2612~ 本地 C 对象**（pocketpd 库起步）：自研 4 算子 FM；冷启动竞速修复（autoload.pd 提前 boot + settle 重开） |
| 0.17.34 | 修复 | 可变 IO 系统性修复（统一 variadic 注册表；33 批探针在 0.51.3 实测；画布引脚 invalidate；同名 .pd 覆盖编译类的 pdClasses lazy 化） |
| 0.17.35 | 修复 | pdClasses lazy 又放回 multiply defined（三源编译类专用集合 + DUAL_COMPILE_PROTECT 15 名验证）；square~ 等 160 行 ELSE defs（help 端口标记提取） |
| 0.17.36 | 修复 | 每次点运行多 reopen + 控件冻结（reopen 门控：known-class 失败才重开，上限 3 次；失败名与前一行配对解析）；OBJECT_PORTS.md 生成 |
| 0.17.37–0.17.39 | 新增 | ym2612~ help 三轮（接口对 setup 字符串、参数参考、算法载波表） |
| 0.17.40–0.17.42 | 新增/修复 | ym2612~ gm 预置弧线（archetype 表、包络时间标尺全错重标、调制器 det 规则、0.5× 调制器/fb 全库审计） |

## 段 11：0.17.45–0.17.67（vc 187–209，2026-11-06→11-15）ym2612~ 引擎大修

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.17.45 | 修复 | 首开 DSP 竞态五层（parse 前 ensureAudioOn、无条件 off→parse→on、10s settle、scope-go 晚档、note 开 gate） |
| 0.17.46–0.17.50 | 修复 | ym2612~ 弧线：fb 反馈两错（抽头/深度）、算法 2 接线、GPGX 移植（包络/detune/RS/MEM/keyon）、gm 库换 XG（0.17.48）、钢琴家族 fb 补偿（0.17.47） |
| 0.17.51 | 变更 | **ym2612~ DSP 核整体换 Nuked-OPN2**（vendored ym3438.c；寄存器写队列；接口不变） |
| 0.17.52–0.17.54 | 新增/修复 | 鼠标支持（滚轮缩放/右键菜单）；底栏拖拽弧线；案例 slider 量化 |
| 0.17.55–0.17.58 | 修复 | 案例接线弧线；逗号劈文件；note 音高丢失（x_pitch 赋值丢失，8.18Hz 震音） |
| 0.17.59 | 修复 | note <midi> 0 = note-off（20ms 淡出+释放尾巴）；note 路径与 float 路径等价性证明 |
| 0.17.60 | 修复 | 输出提取改帧对齐直读 ch_out[0]（直流垫/残段弧线开始） |
| 0.17.61–0.17.63 | 回退 | bank 弧线两次错误方向被用户纠正（0.17.51 为唯一基准）；提取改动撤销 |
| 0.17.64–0.17.65 | 回退/规则 | bank 谐波锁定补丁被否决撤销；**ym2612~ 冻结规则**入 AGENTS.md |
| 0.17.66–0.17.67 | 修复 | mol 引脚流→帧对齐直读定案；右入口 gate 钉死 ch1（x_cur 引入又于 0.17.94 重构掉） |
| 0.17.68 | 新增 | ym2612~ bank <file.wopn> 运行时换表；**[vgm~]** 播放器（VGM v1.71 命令表、双端口×3 通道路由、DAC 信号口、goto 影子重放）；9 bank 随包 |
| 0.17.69–0.17.71 | 修复 | canvas_open 野指针 → canvas_getcurrent（真机再崩）→ **0.17.70 反汇编定案 libpd-ism → open_via_path 根除**；0.17.71 Slibs.VERSION 未 bump 教训（修复从未上机） |
| 0.17.72–0.17.78 | 修复 | vgm~ 出口契约兑现；core_classes.txt 构建期自动生成（检查单入 AGENTS.md）；vgm-player 弧线；key-on 端口位路由；**寄存器写自动 raw**；**架构重构单芯片**（chN reg 通道寻址；六实例废除；2.7× 提速） |

## 段 12：0.17.79–0.17.100（vc 219–242，2026-11-16→11-17）sn76489~/sid/slibs 灾难与重建

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.17.79 | 新增 | **[sn76489~]**（PSG，语义三方核对：SMS Power/VGMPlay/GPGX） |
| 0.17.80 | 事故 | **slibs 十库空壳事故定案**（srcrepo 被清空→静默空壳覆盖好 blob）；恢复向量穷尽；幸存资产整理 design/slibs-libs-adjustments.md；build_slibs 空壳护栏 + regen_manifest.py |
| 0.17.81 | 修复 | **十库本体重建**（purr-data/Debian 源；兼容补丁全入 patches 字典；GUARD 护栏；已知缺口记录：zexy lp、iemlib forpp、fftease 7 对象） |
| 0.17.82–0.17.84 | 修复 | sn_dsp 参数错位双重破坏 SIGSEGV（改回 3 参 dsp_add 形态——结构性消灭）；**audit_blobs.py 验收门**；cyclone gui_* 垫片、zexy lpt stub、creb libc++；**zexy core_wrap 只调 2/~102 setup**（ZEXY_SETUP/supports 提取修复） |
| 0.17.85 | 修复 | 案例连线错乱+裸分号（**lint_pd.py 新验收门**） |
| 0.17.86–0.17.87 | 变更 | vgm-player 示例按用户会话重做（10 graph~ 网格）；PCM scope 补上 |
| 0.17.88–0.17.89 | 新增/修复 | **[sid6581~]**（vendored reSID，voice_outputs accessor 两处标注式改动）+ **[sid~]**（PSID 解析+自研 6502/6510 核）；GM800 pd 层验证；**sid~ 首次 dsp 编译崩**（零信号口对象 sp[0] 越界 → sys_getsr/sys_getblksize） |
| 0.17.90–0.17.92 | 修复 | symlinkCoreClasses shell 元字符引号（全批符号链接丢失）；[zexy/lister] 爆红两层（manifest zexy_new 宏回填 + resolve bare 名统一）；About 排版 |
| 0.17.93–0.17.97 | 修复 | ym2612~ 弧线：ch1 note 无声（chN 分支误转裸写）；顶栏方形按压；**每声道声部重构**（chN note 真发声、惰性乐器初始化、黄金 PCM 哈希守卫）；chipw_ch 编码 bug；K2/K3 杂音定位（note-off 缺失）；alloff 引入→**0.17.96 回退**；gate 钉死 ch1 修复 |
| 0.17.98–0.17.100 | 新增/修复 | sid-player CC 曲目（ZeckenSID，许可三处署名）；Examples.VERSION 漏 bump 教训；**sid 兹拉声根因**（每样本 clock() 破坏 reSID 相位→块级调用；PSID 头三处解析错误；示例换 Seekrank） |
| 0.17.101–0.17.107 | 修复 | 右键后退弧线（0.17.53/54 → **0.17.101 精确指纹**：完整 SOURCE_MOUSE 掩码，MouseBack 三 Activity）；底栏手势规则三次修订（0.17.101/103/104）；console/画布焦点战争（0.17.105/106/107：消费式 dispatchKeyEvent） |
| 0.17.108–0.17.110 | 修复 | 兜底焦点清扫；**AudioOpen 三态**（build 从 keepAudioSilent 参数演化；AudioWrapper.start 线程泄漏根除）；顶栏后退键方形 |
| 0.17.111–0.17.112 | 修复 | vgz 落位说明+导入名（PatchStore.create 扩展名保留）；**vgm~ gzip 分支双错根除**（inflateInit2 15+32） |

## 段 13：0.17.113–0.17.133（vc 255–275，2026-11-18→11-21）opl3~/nes~/xm~/mpein

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.17.113 | 新增 | **[opl3~]**（Nuked-OPL3 v1.8 vendored；原生率重采样；空闲跳过；vgm~ 路由 0x5E/5F + goto shadow + key-off 扫描；芯片普查 load 信息） |
| 0.17.114 | 变更/修复 | opl3~ 重建"总-分"拓扑（20 出口）+ voice 层（note/gate/alg/fb/pan/op/gm/bank/mono）；**vgm~ PCM 阶梯重建**（44100 刻度+线性插值）；示例 L×R 同乘 [*~] 事故（吞音根因） |
| 0.17.115 | 修复 | opl3~ 命令流解析（`gm 1 note 60 100` 免逗号；chN 作用整条消息；chN 不切声道）；status 诊断 |
| 0.17.116–0.17.117 | 修复 | About OPL3 条目、opl3-player scope 版式；**旋律消失根因**（OPL3_WriteReg 瞬时生效破坏包络重触发 → OPL3_WriteRegBuffered 总线模拟） |
| 0.17.118 | 新增 | **[nes~]+[nsf~]**（自研 APU+6502 核；Klaus Dormann 功能测试；nesdev 逐条实测纠错；vgm~ 0xBC 路由） |
| 0.17.119 | 修复 | nes~/nsf~ 出口层弧线（DMC 电平 AC 耦合、噪声密度 pedestal、门沿 3ms fade；nesdev 页面复核） |
| 0.17.120 | 新增/修复 | ym2612~/opl3~ **glide/slide**；nsf~ 播放模式五态+songtime；**19 处未转义分号**（7 个补丁；lint_pd.py 支持单文件参数——此前"通过"是空扫描）；else rand.i/rand.f 红框（manifest 回填 1328 行） |
| 0.17.121 | 修复 | opl3~ slide 声音消失（opl_chip_fnum 丢 B0 keyon 位；六调用点审计） |
| 0.17.122–0.17.127 | 新增/修复 | **[xm~]**（vendored libxm；C23 lowering；2+32 出口；play-once 语义；xm~-help 连线索引事故→**深度感知 .pd 图校验器**，揪出四处旧补丁同类损坏）；demo.xm 生成器弧线；toilet3.xm 推送；libxm clock uint16 回绕（96k→30464，3.157× fast）→ 时钟降采样 |
| 0.17.128 | 修复 | xm~ clock 自守卫（每块重校验）+ dumpinfo 实际时钟；九层代码审计（无设备测试） |
| 0.17.129–0.17.131 | 修复 | xm~ 时钟 wrap 真因定案（0.17.128 修复不完整）→ 降采样实现；demo.xm 重调音；**xm~ 手动演奏崩溃**（manual_mode 每通道槽位重指） |
| 0.17.132 | 新增 | **[mpein]**（MPE v1.0 通用解析器：[midi-raw] 桥→pd 侧聚合；RPN zone/slide/press/pitch/note/midi passthrough） |
| 0.17.133 | 修复/新增 | xm~ slide 重写（ms 精确、三 API additive）；opl3~/ym2612~ slide vel；**else 库对照审计**（mpe.in 是子集；[midi-sys] 实时字节桥）；导入多选+文件夹；多端口矩形 2/3 pitch 收缩 |

## 段 14：0.17.134–0.17.145（vc 276–287，2026-11-21）elsex 与工程事故

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.17.134 | 新增 | **else 库补全**：169 抽象 + **elsex**（50→52 编译类，pd55 头 offset 验证声明 + PRIORITY）；**1391 处 `else/x` 引用改写**（264 文件）+1915 help；display/display~ 换 console 版；MERDA/knob 缺口记录；Slibs.VERSION 124 |
| 0.17.135–0.17.137 | 修复 | display widget echo（pocketpd-display 符号）；**midi.in 桥根因**（sendMidiByte 全工程未调用→MidiParser 转发）；io 审计两扫描器 bug；**knob 可编译**（sys_gui/sys_vgui 导出；THISGUI 颜色补丁）；**pdgui_vmess 等 5 符号缺失→no-op 垫片**（mc 类安全退化单声道）；display 裸名可达（slibs 根部署） |
| 0.17.138–0.17.139 | 修复 | knob 进底栏+Properties 行（setSymbols 数字槽填充修正）；display 盒随内容生长 |
| 0.17.140–0.17.143 | 修复/新增 | display 私有 tag（防复制镜像）；zexy time/date 端口；**端口矩形间距算法重写**；**跨库 640 类 io 审计**（~230 行修正；creb 包络发生器无信号口、zexy 五类控制进信号出等系统性发现；变参规则 14 条；OBJECT_PORTS.md 重写）；**else/elsex 269 类审计**（vintage 钉死 beta19-21；52 信号标重写；16 行新增；变参 14 条） |
| 0.17.144 | 新增/修复 | **[supersaw~] 自研**（上游无此对象；gen_supersaw.py）；四个 help 的 MERDA 引用换名；dep_check/dep_check2/sim_inline/van055.txt 工具入库 |
| 0.17.145 | 事故+修复 | **设备级测试事故**（connectedDebugAndroidTest 卸载用户 app+补丁）→ **设备级测试禁令**入 AGENTS.md；androidTest 拆除；**[args]→initmess**（会话根画布 env NULL SIGSEGV）；**存储迁移 /sdcard/pocketPD**（MANAGE_EXTERNAL_STORAGE）；substituteLine msg-$N literal 判断修复；**[popmenu] 控件**（21-token 布局+完整 Properties） |
| 0.17.146 | 修复/规则 | **versionName 重复赋值**（两台设备显示旧版）→ **版本编号纪律**；superosc~ 崩溃排查（反汇编+0.55 对账+sim_inline 重建）；**示例迁 /sdcard/pocketPD/examples**（缺则拷贝）；**模拟器禁令**入 AGENTS.md |

## 段 15：0.17.147–0.17.161（vc 289–303，2026-11-22）superosc~ 链、看门狗回退、工程机制

| 版本 | 类型 | 内容 |
|---|---|---|
| 0.17.147 | 修复 | superosc~ SIGSEGV：**injectBlockRelays 无深度计数**（depth-4 嵌套 restore 错位→根画布弹空）→ subpatchBlockRestore 深度感知配对替换五处扫描；pmsg 文档序；emitSubpatchBlock 只重写本块坐标 |
| 0.17.148 | 修复 | **clone 无法创建**（运行时 clone 无 -d/-x/-di/-do，strings 实证）→ parseCloneArgs + voice==1 直拆 |
| 0.17.149 | 修复 | clone 修复未生效（进程未重启+**内联快照循环盲区**）→ **不动点循环**；**运行时版本实证修正：pd 0.51.3（banner），0.17.70 的"0.55 系"误判确认** |
| 0.17.150 | 修复 | inlineBudget 256→4096；superosc.unit initmess 旗标（verbose/midi）→ 惰性 r \$0-initmute |
| 0.17.151 | 修复 | clone 文件名粘逗号（f2s~.pd 折行标记连写）→ unglue |
| 0.17.152 | 变更 | **superosc~/f2s~ 单声道替代版落地**（上游 mc 架构在 0.51.3 不可能工作；接口 4 进 2 出不变） |
| 0.17.153 | 修复 | #X text 置顶导致 connect 索引错位（首次）；**else/ 前缀剥离**会话规则 |
| 0.17.154 | 修复 | Slibs.VERSION 忘 bump（0.17.153 修复未达设备）→ 执行项入版本纪律 |
| 0.17.155 | 修复 | superosc~ 频率只在创建参数（guts 忽略参数→0Hz）→ 参数驱动 [sig~ $1]/[sig~ $3] 求和 |
| 0.17.156 | 修复 | **text 置顶第二次复发**（0.17.155 重写引入）；**check_pd_connects.py 机械防线** |
| 0.17.157 | 修复 | 频率求和接错目标（pan2~ 而非 vsaw~）→ 重定向+程序化验证；来源澄清（自改版非上游） |
| 0.17.158–0.17.159 | 新增/重写 | **timer 不计时间根因**（libpd 逻辑时钟只在音频回调推进）→ 逻辑时钟看门狗（libpdclock.so）→ 0.17.159 重写（显式门控+墙钟追赶） |
| 0.17.160 | 回退/机制 | **看门狗整体回退**（用户裁决"大量东西失效、行为太危险"）→ git 版本机制建立（基线 tag snapshot-0.17.159-broken；每轮 commit；高危先审；坏版本禁叠加修复） |
| 0.17.161 | 修复 | **[/]/[/~] 只画一个 inlet**（前缀剥离吞裸斜杠名）；PD_VERSIONS.md 纠正 0.55 误判 |

## 十、统计与指针

- 294 轮 / 6403 行 CHANGELOG；本文表未改变任何轮次的事实与归因，仅按弧线
  终点标注现状；逐轮原文以 CHANGELOG.md 为准。
- 当前基线：master = 0.17.161（versionCode 303）；`0.51` 分支 = pd 0.51.3
  引擎基线标记。
- 后续岔路与可行性判断：[MIGRATION_057.md](MIGRATION_057.md)；
  版本考古：[PD_VERSIONS.md](PD_VERSIONS.md)。
