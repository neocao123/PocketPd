# POCKETPD — PC-98 ANALOG UI 提案（V2）

> 给 PocketPd（触屏版 Pure Data 编辑器）做的一版替代视觉语言：
> **NEC PC-98 的 16 色窗口语言 × 克制的模拟质感层**。
> 本文件夹是**纯设计稿**，位于项目根目录 `/design`，不在任何 Gradle source set 里，
> **永远不会被打进 APK**（`settings.gradle.kts` 只包含 `:app`）。

**V2 变更**：删除封面页；端口（进出口）规范逐条对齐 `PatchCanvasView.drawPort()`；
glitch 元素全部退出内容区；画面内容全部来自项目真实功能与素材，无虚构。

## 文件

| 文件 | 内容 |
|---|---|
| `01-browser.svg` | 补丁浏览器：真实文件名 + 「体积 • 日期」元数据行（与 `MainActivity` 一致） |
| `02-editor-edit.svg` | 编辑器·EDIT：真实补丁 `01-hello-sine.pd`、控制台真实日志行、长按菜单、名称栏、模式键 |
| `03-editor-run.svg` | 编辑器·RUN：真实补丁 `04-gui-demo.pd`，控件激活态 |
| `04-settings.svg` | 设置 = BIOS 风格：真实设置项与默认值（Audio/MIDI/Console/OSC） |
| `05-system-sheet.svg` | 系统表：色板 / 字体 / 组件 / 端口规范 / FX 规则 |
| `preview/*.png` | 720×1560 渲染图（Edge headless 输出） |
| `index.html` | 本地画廊页 |

SVG 用浏览器直接打开即可。字体栈：`DotGothic16 → MS Gothic → Consolas → monospace`。

---

## 1. 概念

| 现有 VI（Teenage Engineering） | → | 本提案（PC-98 Analog） |
|---|---|---|
| 米灰仪器面板，丝印标签 | → | 深蓝黑 CRT 场，蓝底标题条，硬投影 +6px 窗口 |
| 信号橙 `#FF4400` 唯一强调 | → | 三强调：**青 `#54FCFC`=信号**、**品红 `#FC54FC`=编辑**、**绿 `#54FC54`=运行** |
| 圆角压缩 2/4/6dp | → | **0 圆角** |
| 平面印刷质感 | → | 仅剩两处模拟层：全屏扫描线 + 屏幕底缘走带噪带（都在内容之下/之外） |

一句纲领：**把补丁画布当成一台 1992 年的仪器屏幕——信号是荧光，界面是机箱；
噪点只准待在显像管的边框里，不准进内容。**

## 2. 色板（严格 16 色 + 5 个 CRT 底色）

沿用 PC-98 16 色（`$0`–`$F`），语义分配：

| 令牌 | 值 | 语义 | 取代现有 |
|---|---|---|---|
| `pd98/signal` | `$D #54FCFC` | 信号线、信号端口、选中值 | `te_orange` |
| `pd98/edit` | `$B #FC54FC` | EDIT 模式、选中框 | — |
| `pd98/run` | `$C #54FC54` | RUN 模式、DSP OK | — |
| `pd98/warn` | `$E #FCFC54` | 控制台 WARNING | — |
| `pd98/error` | `$A #FC5454` | 错误、DELETE | — |
| `pd98/chrome` | `$1 #0000A8` | 标题条、BIOS 场、主按钮 | `te_panel` |
| `pd98/edge` | `#A8A8C0` | 对象框/窗口描边、消息端口 | `te_line` |
| `pd98/ink` | `$F #FCFCFC` | 主文字 | `te_ink` |
| `pd98/dim` | `#6C6C8A` | 次级文字、tgl OFF 的 X | `te_ink_dim` |
| `pd98/field` | `#04040C` | 画布场（也是空端口的挖空色） | `te_carbon` |
| `pd98/body` | `#0A0A1E` | 窗体底 | — |
| `pd98/raised` | `#101040` | 浮窗/键帽底 | — |
| `pd98/line` | `#2A2A5C` | 分隔线 | — |
| 消息线 | `#7C7CA0` | 非信号连线（1.6dp） | 现灰细线 |

规则：**无渐变**，深度用 2×2 有序抖动（D25/D50）；一屏强调色 ≤ 2 种。

## 3. 端口规范（必须逐条对齐 `PatchCanvasView.drawPort()`，历史教训）

1. 端口 = **10×4 canvas 单位的扁矩形**（本稿 2px/单位 → 20×8px），不是方块；
2. **贴在框内缘**：inlet 与顶边内侧齐平（向框内画），outlet 与底边内侧齐平，不跨边线；
3. 水平位置：`portPos = 框左缘`（n≤1 时）或 `left + width*index/(n-1)`（n>1 时铺满左右缘），
   矩形以该点为中心并**完整收在框内**；
4. 填充：空端口 = **场色挖空**（`COL_BG`）+ 灰描边；已连消息 = `EDGE` 实心；
   已连信号 = `$D` 实心；描边 1dp 同色；
5. **连线锚在端口矩形中心**（`cordAnchor` = portRect.centerX/Y），贝塞尔控制点垂直引出：
   消息 LEAD 30 单位、信号 LEAD 60 单位（`LEAD_MSG/LEAD_SIG`）；线宽消息 1.6dp、信号 3.2dp；
6. 连线先画、对象框后画（框会自然压住线的端头，与 `onDraw` 顺序一致）；
7. 消息盒（msg）的小旗在**右缘**（`drawMsg`），bang 是圆（`drawBng`），
   tgl = 描边方框 + X（OFF 用 dim 色 1.2dp、ON 用强调色 2.2dp，均不填充），
   VU = 描边框 + 底部实心填充随值上升（`drawVu`）。

## 4. 字体（已内嵌进 SVG，按字符分工）

| 内嵌名 | 字体 | 许可 | 角色 | 大小 |
|---|---|---|---|---|
| `PD98LAT` | **F5.6**（Sora Sagano / dotcolon.net） | ⚠️ 免费字体、无内嵌许可字段，分发前需核实 | 全部英文 / 数字（拉丁点阵） | 3.8 KB |
| `PD98UI` | **Ark Pixel 12px Proportional zh-Hans**（方舟像素 12px 简体） | OFL 1.1 | 中文 + F5.6 没有的符号（`~ * $ % = — × … · • → ▼`） | 7.1 KB |

- CSS 逐字符回退：英文/数字由 F5.6 渲染，中文与符号由方舟像素渲染；
- 字重（"显粗"）方案：F5.6 竖笔为字号的 25%，属固有结构；SVG 内对拉丁文字叠加
  `feMorphology erode` 腐蚀滤镜削细——展示层（≥21px）0.6、亮色正文 0.3、暗色小字与
  中文不腐蚀；方舟像素小字吸附 12px 整数倍（笔画 1px）；
- SVG 用 base64 data-URI 内嵌子集，**任何机器打开都一致**；
- ⚠️ F5.6 元数据无许可证字段：设计稿内部预览无碍，**若随 APK 分发需先到
  dotcolon.net 核实授权条款**；届时也可整体退回纯方舟像素（OFL）方案，删掉 PD98LAT 即可；
- 方舟像素：OFL 许可全文 `fonts/OFL-Ark-Pixel.txt`；无 Bold 字重，稿内已移除全部合成加粗，
  强调靠反色与颜色；文字尺寸取 12 的整数倍（12/24/36px）时像素最整齐；
- 缺字适配（改稿措辞）：`≥→>=`、`警告→WARN`、`斜体→ITALIC`、`窗体→面板`、`浮窗→浮层`、`缘→侧/边`、
  F1.8 的 `…` 过小→`...`（保留）；
- bng 规范：外方内圆（现版 `drawBang` 为整圆，落地时改 `drawRect` 外框 + 内圆）；
- 备用字体（`fonts/` 内保留、未引用）：Sarasa Gothic HC Light、DotGothic16；
- **App 落地**：两套子集放 `assets/fonts`，Android 无逐字回退机制——用
  [CustomFallbackBuffer](https://github.com/madmas/fontfallback) 或自绘时按字符选择
  Typeface（画布本来就是自绘，逐字符换 Paint 即可）；XML 界面侧中文继续系统黑体、
  数字等宽即可。OFL 部分可随 APK 分发。

层级只用字号+颜色：LOGO 34 / 标题 23 / 正文 20 / 标签 17 全大写宽字距。

## 5. 组件映射（对现有程序）

| 现有组件 | PC-98 化 |
|---|---|
| MaterialToolbar | `$1` 蓝底标题条；右侧三个 40px 键帽（深底+2px 边）**直接复用原始图标形状**：`ic_marquee`（角框+中心方块）、`ic_audio`（喇叭+声波=DSP live，与控制台日志一致）、`ic_more`（三点菜单）；标题=文件名，脏标记 `•` |
| 控制台 `console_bar` | 纯黑条：绿/黄/白日志行 + 右缘 ×（清空）/ ▼（展开），无标题条（同现状） |
| `PatchCanvasView` | 场 `field` + 网格、框 `#0D0D28`/`edge` 描边、选中=品红实线（`selectPaint`） |
| 端口/连线 | 见 §3，逐条对齐现有实现 |
| iemgui 控件 | 见 §3 第 7 条 |
| 模式显示 | 画布右上 VHS OSD 角标（`‖ EDIT` / `▶ RUN`，白字黑边+红青重影）；交互可保留现有 FAB |
| add-bar | 真实键位：`obj msg text num tgl bng hsl vsl radio graph`（`buildAddBar()`），RUN 模式隐藏（同现状） |
| 名称栏 | 浮动标签 `TYPE OBJECT NAME — DROP POINT PLACES IT` + 输入场 + ✓/✗ + 目录提示 + 建议芯片（同现状） |
| 长按对象菜单 | Edit text / Properties / Duplicate / Delete（同现状字符串） |
| 浏览器列表 | **VHS 录像带标签**：深色带壳 + 纸感标签贴片，左侧分类色索引页（01–05 大号编号）+ 底部同色条纹，纸面为文件名（马克笔字重）+ 横线纸纹 + `体积 • 修改时间`（`patch_meta` 格式）+ ⋮（Rename/Delete） |
| 设置页 | BIOS 蓝底：真实分组与默认值（48000Hz / 8 ticks / mic OFF / MIDI None / Console 5 行 / OSC 8000·9000） |

## 6. 模拟层（不干扰视觉传达的剂量）

| 效果 | 参数 | 位置 |
|---|---|---|
| 扫描线 | 4px 节距，2px 黑 @14% | 全屏、内容**之下**（均匀条纹，不遮挡不变形） |
| 走带噪带 | 底缘 20–24px | 屏幕最底边，内容区之外 |
| OSD 模式角标 | `‖ EDIT` / `▶ RUN`，白字黑边 + 红/青重影 | 编辑器画布角（显示真实模式状态） |
| 抖动 | 2×2 D25/D50 | 仅作为填充技法（选中洗色、装饰带） |

已移除且不恢复：颗粒噪点、内容区撕裂位移、正文 RGB 分离、SMPTE 条、假时间码——它们会进入或干扰内容区。
铁律：**glitch 不进入内容区**（走带带只进空白区）；正文对比 ≥ 7:1。

## 7. 内容保真清单（本稿所有画面文字的出处）

- 画布补丁：`assets/patches/01-hello-sine.pd`、`04-gui-demo.pd` 原样布局（对象/参数/连线）；
- 控制台行：`demolog.txt` 真实输出（slibs/libpd runtime/opened…）+ README 行为（`dsp on (48000 Hz)`、
  `WARNING: media volume is 0`）；
- 浏览器行：真实文件名 + 真实体积 + 资产时间戳；
- 设置行：`strings.xml` 原文案 + `Settings.kt` 默认值；
- 提示语：`name_bar_hint`、`add_bar_drag_hint` 等（未在本稿展开）。

## 8. 落地路径（下一阶段，本提案未动代码）

1. `values/colors_pd98.xml` + Theme overlay（`Theme.PocketPd.Pd98`）；
2. `PatchCanvasView` 只改 Paint 常量区（COL_* → pd98 令牌），端口/连线几何**零改动**；
3. 模式 FAB → 双格切换键自绘；控制台按日志级别配色；
4. 模拟层 = 独立 `ScanlineOverlayView`（扫描线 shader + 底缘噪带），设置里可整层关闭；
5. 无障碍：提供 "REDUCE CRT" 开关，关闭全部模拟层只留 16 色布局。

## 9. 不打包声明

文件全部位于 `<project>/design/pc98-analog/`，Gradle 不收集该目录，构建产物不含其中任何文件。

## 10. 落地记录（2026-10-01，本节记录已进入 App 的实现）

设计已按本稿落进安卓程序本身（`design/` 仍不参与打包）：

- **色板**：`app/src/main/res/values/colors.xml` 全量换成 `pd98_*` 令牌（§2 严格 16 色 + 5 底色）；
  主题 `Theme.PocketPd` 改 `Material3.Dark`，0 圆角（cut 0dp），状态栏/导航栏 `body`。
- **标题条**：三页 AppBar = `$1` 蓝底 + 底缘 2dp `#5454FC` 亮蓝线（`drawable/titlebar_bg.xml`）。
- **字体**：F5.6 全量 OTF 打包为 `res/font/pd98_latin.otf`，用于工具条标题、列表、控制台、
  画布文字与按键；⚠️ **F5.6 无明确分发许可——正式发布 APK 前必须先核实 dotcolon.net 条款**
  （届时可整体退回纯方舟像素/OFL 方案）。界面字符串全英文，用户输入的中文由系统字体回退。
- **画布**（`PatchCanvasView`）：只改 `COL_*` 常量区 + 两处小改——bng 改外方内圆（§3.7）、
  选中=品红 / 控件激活=青拆成 `COL_SELECT`/`COL_ACTIVE`；端口、连线几何零改动；
  画布文字用 F5.6；场色之下铺 1/4 设备行 @16% 的扫描线（`scanline_tile.png` shader）。
- **VHS OSD**：新 `VhsOsdView`（画布右上角标：自绘 ▶/‖ + EDIT/RUN，白字黑边+红青重影），
  模式切换联动 FAB 图标色（进 RUN=绿、回 EDIT=品红）。
- **浏览器**：列表行 = VHS 录像带标签（深壳卡片 + 位置色索引页 01… 循环青/品红/绿/黄/红 +
  纸感贴片 + 底部同色条纹），NEW PATCH = 带壳键（shell 底、edge 描边、青色铅笔）。
- **设置页**：按设计稿 04 的行式 BIOS 语言重做——青色分区标题、每行 = 白标签 +
  右侧深底青描边值盒（值 = 青像素字，下拉带 ▼）；开关全部改为 OFF/ON 值芯片
  （OFF = 深底青描边，ON = 实心青反色，`color/vchip_*.xml` 选择器）；
  「刷新/扫描」行为青色文字行；Apply/Import 为全宽青描边键。
- **系统残留清除**：所有 `MaterialAlertDialog` 容器方角化（M3 默认 28dp 圆角已除，
  raised 底）；底部面板（对象目录）方角 + 去把手；Spinner 下拉 = 方角 raised 弹层 +
  自定义像素 item 布局；调色板 Chip 改方形键帽（`chipStyle`）。
- **弹层（02 弹窗规格）**：列表弹窗（长按菜单 / Rename/Delete / Examples）= body 面板 +
  edge 描边 + **+6dp 硬投影 @55% 黑** + 1px 发丝分隔线；行 = 白（普通）/ dim（禁用）/
  红（Delete）；标题行 edge 色。溢出九宫格 = `raised` 底、激活键反色青；控制台按
  edge/红 两级配色；拖拽阴影 = raised 键帽 + 青描边；工具条三键（多选/DSP/菜单）
  固定顺序、无边框（白图形直接置于蓝底扫描线上，无黑底键帽）；OSD 的 ▶ 与 ‖ 等视觉量级（三角放大补偿）。
- **字体根修**：M3 Typescale 父链会经 app 命名空间 `fontFamily` 泄漏 `sans-serif`
  （优先级高于 `android:fontFamily`）——所有自定义字型样式同时覆盖两个命名空间，
  这是「样式颜色生效但字体回退 Roboto」的根因。
- **模拟层（05 §4）**：五个 mockup 的**最后一个元素**都是全屏 `url(#scan)` 矩形——App 同构：
  每页根布局顶端挂一枚不可交互的 `scan_overlay` View（`ScanlineDrawable.overlay()`），
  标题条 / BIOS 场 / 画布 / 内容全部同盖（标题条背景只留 2dp 亮蓝底线，画布自带扫描已删，
  避免双重压暗）。tile 代码生成（PNG 走 AAPT2 会丢 alpha），且**按密度缩放**：稿子
  4px 节距 / 2px 黑是 2x 网格，即 1dp 黑 + 1dp 亮 @14%（420dpi 实测 3px/6px）——
  先前固定 4 设备像素节距比稿子细 1/3，肉眼不可见，正是「扫描线没实现」的根因。
  实测：标题条 144/168 各 3 行交替、纸贴 186/216、BIOS 场 146/146 行、标题字 252/216；
  覆盖层不拦截触摸（卡片点击、OSD 切换均正常）。系统状态栏/导航栏与独立弹窗
  （Dialog/PopupWindow）不在该层内，保持素色——记录为已知偏差。
- **字号统一（05 §2 参考值，px÷2=sp）**：TITLE 23→11.5sp / BODY 20→10sp /
  LABEL 17→8.5sp，全局收敛到这三个值；**标题条经两轮校准定为 11sp**
  （11.5→10→11，=稿 22px÷2；该样式仅三页标题条使用）；设置页值盒 124×32dp、
  开关芯片 88×32dp。
- **OSD = 模式开关本体**（02/03：26px=13sp 标签、6px=3dp 黑边、红青重影、无按钮框），
  点击切换 EDIT/RUN；右下大 FAB 已删除。修了 VhsOsdView 绘制不继承 textSize 的 bug。
- **蓝色标题条字体**：MaterialToolbar 的标题绘制不走样式字体——三页标题改为
  自绘 TextView（F5.6 直挂视图），字号 12sp（见上）。
- **行动弹窗宽度（02 稿）**：面板由窗口 MATCH_PARENT（近全宽 992px）改为定宽
  460px（=稿 230dp 按弹窗自身的 2px/dp 渲染比例），屏幕居中。行高、内边距、字号
  一律未动——弹窗沿用既有 `dp(1f)→2px` 渲染比例（`EditorDialogs.dp()` 返回 Int，
  440dpi 上把 2.75 截为 2，属历史既成比例），高度与用户认可的旧版逐像素一致
  （头部 71px / 行 88px / 总高 431px）。
- **设置下拉 / 标题条键（0.12.4）**：spinner 闭合值与下拉行统一到 10sp 字阶
  （原 14sp 越阶、与全页其余值控件不一致），下拉行 36dp；下拉弹层 = raised 方角 +
  edge 描边。波特率选项去掉「 baud」后缀——「115200」此前在 124dp 值盒内被
  省略号截断，现完整显示（实测字形 743–858px，盒右缘 964px）。三页标题条右侧按键
  去键帽成纯图形；AppBarLayout 显式 `app:elevation="0dp"`——M3 默认高度会把
  标题条抬到全屏扫描层之上（编辑器页实测中招、条纹消失）。
- **首页浏览器 = FD 3.13 文件列表（0.13.9，取代上面的录像带标签行）**：行 =
  纯文件名 + 右侧 dim 大小/日期 + 1dp `line` 软分割线，无卡片无页签无行内菜单；
  **短按进入、长按选中**（反视频：`warn` 黄底 + field 黑字，KERNEL.SYS 光标行），
  再按取消；底部 **RENAME / DUPLICATE / DELETE 键帽条**（同编辑器 add-bar 语法，
  30dp 高、F5.6 大写、field 深字），未选中 = `$8` 灰底，选中后前两键左对齐
  **$D 青 `#54FCFC`**、DELETE 右对齐 **$B 品红 `#FC54FC`**；行文件名字号 ≤
  标题条（12sp）；DUPLICATE 新增：xxx.pd → xxx_1.pd（重名顺延 _2…）。顶栏键序：Toolbar 对
  end 重力按 XML 序**从右往左**排——btn_menu 必须在 btn_new 之前才得到
  「新建左、菜单右」。返回箭头弃阶梯改 1.5 单位真斜线描边（去大锯齿）。
  F5.6 署名补作者 © Sora Sagano。
- **CRT 荧光辉光（0.13.11 控件级 → 0.13.12 全屏化 → 0.13.13 移除）**：辉光属于
  05 §1 的 **$A–$E 醒目色**（红/品红/绿/青/黄），**不给深蓝顶边栏**；且辉光是
  向外溢出，控件级改动表达不了——0.13.12 曾做全屏后处理：`ui/Bloom.kt`，三页根
  布局换 `BloomCoordinatorLayout`/`BloomFrameLayout`，子控件绘完重录画面 →
  1/4 降采样 → 逐像素筛选「v≥190 且饱和≥0.55」（恰为 $A–$E 色族，画布的青色
  端口/信号连线因此发光）→ 两级双线性模糊（≈20px 溢光）→ alpha 洞合成回帧。
  **0.13.13 应用户要求整体移除**（`Bloom.kt` 删除、根布局还原），效果待另起
  方案；$9 蓝/白/灰/$1 深蓝不发光的筛选条件留档于此备用。
  ⚠️ 踩坑记录：①形状 shadowLayer 会被视图渲染表面按 bounds 裁剪（铺满即不可见）；
  ②同一泛光位图画在子控件**之前**整帧不可见、画在**之后**正常——若重启辉光方案，
  直接采用「内容之上 + alpha 洞」合成，扫描线纹理在辉光区略被提亮，记为已知偏差。
- **下拉 = 值盒镜像（0.12.5）**：Spinner 的 10dp/22dp 水平内缩从控件挪进闭合 item
  布局——此前弹出层宽度按控件 content 计算，比 124dp 值盒窄 89px；现弹层与值盒
  等宽（341px 实测）。下拉行高 32dp 与值盒同高；闭合值与下拉行文字统一改用
  稿 04 的暗青值色 `pd98_cyd #00A8A8`（新增令牌；原来错用了 signal 青，与描边同色）。
- **OSD 模式色（0.12.5/0.12.6，用户校准）**：OSD 标签与 ‖/▶ 图形填充随模式取
  设计色——EDIT 品红 `$B #FC54FC` / RUN 绿 `$C #54FC54`，黑边与红青重影保留，
  与工具条状态一眼对应（稿 02/03 正文是白字，此处按用户要求以模式色区分）。
- **扫描线剂量校准（0.12.5）**：稿定 14% 在 1dp 节距 + 8bit 面板量化后不可见
  （场底 12→10 的舍入与 14%/20% 结果相同），提到 **25% 纯黑**（亮面实测
  chrome 168/126、纸贴 216/162 交替清晰）；近黑场底物理上不可再压暗，仍属
  「覆盖全屏」的一层，非按控件铺设。标题条字号最终定 **12sp**（=稿 22px ÷
  设备 1.5 px/sp；10/11sp 两轮偏小后按换算落点）。
- **启动图标（0.12.7 / 0.13.0 重构）**：按设计语言重做（`gen_icon_pd98.py` 生成
  432px adaptive PNG，design 目录不参与打包）——**背景层** = `$1` 蓝场 + **大号
  run 绿 ▶**（用户定稿：播放键属于背景）+ 2dp/25% 扫描线覆盖；**前景层** = F5.6
  真字形「PD」独占主体（白字 + 黑描边 + 红青重影，02/03 稿 OSD 版式）+ 2dp
  `#5454FC` 亮蓝底线；▶ 移入背景后 PD 放大到 cap 106px（安全圆反解上限 ≈113）；0.14.6 起三角左缘左侧的蓝条带改绿（等高矩形，三角角度样式不动）。
  `ic_launcher*.xml` background 由纯色改为背景图。
- **画布 VU 条（0.13.0–0.13.2）**：OSD 行的左端书挡（EDIT/RUN 徽标在右，同为 13sp
  像素字 + 黑描边）——`VuStripView` 每声道 10 段（6×signal 青 `$D` / 2×warn 黄 `$E` /
  2×error 红 `$A`），**每段劈上下两半：上=左、下=右**，场底 + 3dp 黑描边与 OSD 同语言。
  电平**无需补丁配合**：`buildInteractiveSession` 在打开补丁时把每条 dac~ 进线按
  进线序号奇偶复制到注入的抽头链 `abs~ → lop~ 300 → snapshot~ 1 → * 15 → clip 0 1
  → s pocketpd-vu-l/-r`（对象停放在画布外 -6000 处，磁盘文件不动）；补丁也可自带
  `[send pocketpd-vu-l/-r/-vu]`。150ms 峰值保持 + 衰减。**坑**：本库的 `env~` 被
  cyclone 遮蔽（console 的类名冲突列表 "(+2 more)" 之内），输出是 1/0 数字量——
  电平链必须用 vanilla 的 abs~/lop~/snapshot~ 组合；另外 pd 文件的 `#X connect`
  索引把 `#X text/msg` 一并计数。
- **底部对象条 = 测试卡（0.13.0/0.13.3）**：键高 30dp、条 padding 3dp；每键底色按
  05 §1 亮色行循环（0.13.3 起**从 `$9` 蓝起步**，灰端垫后），键宽随文字长度，
  深色像素字，电视测试卡观感；**拖拽幻影 = 被按的键本身**（同色底/同字/同尺寸），
  落画布后仍按对象常规样式。MaterialButton 会接管程序化背景（tint/背景两条路
  都无效），改用纯 TextView 色块。
- **Properties 弹窗 = PC-98 窗口套件（0.13.14）**：对象 Properties 与主菜单
  Properties（GOP）两个弹窗统一弃 MaterialAlertDialog——容器是 02 稿弹层面板
  （body 底 + edge 描边 + +6dp 硬投影，与长按菜单同 chrome，**定宽 460px**），
  行语言取 04 稿 BIOS：小号 edge 大写列标 + 青描边值盒（青 F5.6 值，与设置页
  值盒同 `value_box_bg`）；标题行 edge 字 + 发丝线，底部 CANCEL（edge 描边 ink 字）
  / APPLY（青描边青字）双键、行高 44d 与长按菜单行一致。**Inlets/Outlets 数量
  框删除**——端口数是类表（`PdObjectMeta`）派生量不是用户属性，
  `ObjectMeta.extraInlets/extraOutlets` 一并移除；对象头行改为 CLASS 只读读数盒
  （edge 描边 ink 字）。滑杆 INTEGER STEPS 开关换成 04 稿 OFF/ON 值芯片
  （实心青反色），atom 标签方位选择器 = 值盒包 Spinner + ▼（与设置页同构）。
  弹窗沿用 `dp()→2px` 弹层比例；无压暗、点外部取消（与长按菜单一致）。
  **坑（0.13.15）**：`popup_panel_bg` 的硬投影是 drawable **内部**右/底各内缩
  6dp 的层——可视 body 比视图右/底缘短 6dp，内容若对称留白会右紧左松
  （实测左 24px/右 8px）；标题/表体/键行 paddingEnd 与 paddingBottom 补
  `shadowInset()`（=6dp×密度截断，与 drawable 一致），发丝线 marginEnd 同步。
- **纯文本 pd 编辑页（0.13.16）**：编辑器顶栏 marquee 与 DSP 之间新增文本/图形
  切换键（ic_text 文本行 ↔ ic_canvas 对象框，2px 同笔画粗细）；文本态画布区换成
  `patch_text` EditText——console 同款 field 底 + 发丝底缘、F5.6 10sp；语法着色
  走严格 16 色：`#N/#X/#A` 头与数字 = dim、记录动词 = $B 品红、`~` 类名 = $D 青、
  控制类/字符串 = ink、注释体/续行 = edge、`;` = dim（`PdTextSpans`，全量重刷
  span、光标不动）。切换瞬间双向同步：图形→文本 `toText()`；文本→图形重 parse
  成新文档 + 清命令栈（旧命令持有旧文档）+ zoomToFit。Save/溢出菜单/Back 前
  flush 文本缓冲；标题条加 " - TXT" 模式后缀。
- **LOCK 模式 / 曲线锚点 / ELSE UI（0.13.17；完整 ELSE 库自 0.14.0）**：OSD 徽标**长按**进入 LOCK
  （$E 黄 + 挂锁图形；特殊的 RUN 态——控件可交互，空白拖动不平移、双指不缩放，
  只能再长按回 EDIT）；连接曲线控制柄 `lead = min(常数, |Δy|/2)`，近水平连线
  不回卷。**完整 ELSE 库（beta20 快照，WTFPL）**：216 个 C 类 NDK 编译为 else_core.so（两 ABI，-l:libpd.so 解析 s_signal，undo API shim）+ 137 个官方抽象（真 graph~/spectrograph~）随包分发，manifest 登记 333 类；实测 12/12 核心加载、类总数 998。announce 走 `pocketpd-else-hello` 询问（本构建 libpd 吞 loadbang）+ 应用端
  绘制：LOCK 徽标 = 上三角（与 RUN 三角同包络，$E 黄）；LOCK 下顶栏四键/返回箭头与控制台两键全部禁用并压暗到 40%（弹窗禁用行惯例），仅剩控件与 OSD 徽标可交互。
- **文本切换键图标重绘（0.14.16/0.14.17）**：0.13.16 的两枚形与邻居撞轮廓——「文本行」
  vs 溢出菜单 ic_more 的三道横线只差末行长短；「空对象框环」与满屏对象框/
  marquee 角框同质。按本稿图标语言重绘：文本态 = 折角文档页 + 文本线
  （阶梯折角 = 家族里唯一斜向元素）；画布态 = 迷你 patch（两只对象框环
  + 折线连线）。同 2px 笔画；资源名 ic_text/ic_canvas 不变。
  **0.14.17 修正**：初版为容形拉到 18 单位高，偏离家族光学尺寸——退回
  ic_audio 同款 **x4–20 × y6–18（16×12）** footprint（页内因此两条文本线；
  迷你 patch 改左上/右下对角布框 + 先横后竖连线）。
- **CRT 荧光辉光（0.14.20，取代上方 0.13.11–0.13.13 的存档方案）**：按业界
  标准（Android 官方 RenderEffect 路线，RealtimeBlurView 一系库同法）重写
  ——根布局 dispatchDraw 内子控件每帧只画一次进 RenderNode：节点原样上屏
  为本体；第二节点包裹它挂 **RenderEffect.createBlurEffect**（平台 GPU
  高斯，原生分辨率、全帧均匀），经 saveLayer 的 **SCREEN 混合**（×1.8
  提亮，只增亮永不压暗）≈51% alpha 叠回；scan_overlay 跳过录制、最后手
  绘（辉光在格栅下、格栅只画一次）。辉光与本体同源于当前帧那次渲染——
  拖拽必然跟随、零残影；静止界面零开销；API<31 退回单层 1/16 降/升采样。
  设置页 SCREEN 区有 **CRT bloom 强度 slider**（0 = 关；PC-98 slider 语言 =
  画布 hsl 同款：场色轨道 + edge 描边 + signal 青填充 + 白游标条，右值盒
  读百分比）。迭代坑留档：①软件画布
  drawColor(SRC) 不清屏→旧帧累积残影（改 eraseColor+重置矩阵）；
  ②全屏 BlurMaskFilter 在 SwiftShader/ANGLE 上 CPU 光栅化单帧 81 秒；
  ③偏移多点采样不是模糊（5 个锐利副本=满屏错位重影；帧间差异指标量不出
  单帧内错位，不能用它自检）；④位图降采样链 1/64 层放大 16 倍块状不均
  匀——位图手工模糊路线整体废弃，唯平台 RenderEffect 正解；⑤**内容绘制
  绝不能挪进辉光分支**——关辉光后无人画内容 = 卡死黑屏（内容必须无条件
  绘制，辉光只是叠加层）；⑥**硬件渲染下子控件失效不经过
  invalidateChildInParent（软件路径遗留），只触发 API 26+ 的
  onDescendantInvalidated**——派生辉光层不在后者里连带自失效就会停留
  旧帧（slider 拖动辉光不实时）。
  已知偏差沿用存档记录：辉光区内扫描线纹路略被提亮。
- **标题条图标光学高度对齐（0.14.23）**：back 箭头与 ic_marquee 原 14.5/14
  单位高，与 ic_text/ic_audio 的 12 单位（y6–18）不齐——仅压缩高度、形状
  不动：marquee 整数对齐 y6–18；back 压到 y7–17（**1.5 描边 square 端帽沿
  斜线越出端点 ~0.5 单位/端，路径须短于 12 单位含端帽才恰为 12**，实测
  12u 直写渲染 38px、10u 32px 达标）。
- **顶栏长文件名（0.15.3）**：标题 TextView 动态 maxWidth（工具条右缘−176dp
  键条−标题左缘），超长时省略号 + **marquee 走带滚动**（VHS 藏味，选中态驱动），
  右侧四键位置永不受文件名长度影响；溢出宫格加 Share（补丁文本 + FileProvider
  文件流）。
- **AI 条交互态（0.15.2）**：GEN/STOP 键 BUSY 时 **色相循环硬闪烁**（普通→实心粉$B反色→灭→实心青$D→灭→
  实心黄$E→灭循环，每相 1s，亮相 = 开关 ON 同款实心反色；完成定格 $C 绿实心），prompt 输入框禁用+失焦；设置 AI 区加
  API format spinner（Anthropic Messages 默认 / OpenAI），行式值盒语言。
- **非法 signal 连线警示色（0.15.1）**：signal 出口 → 非 signal 入口（pd 拒绝的
  "signal outlet connect to nonsignal inlet"）的连线画 **$A 红 #FC5454 粗线**
  （`cordError`，与 GOP 范围框/错误色同源）；青粗=signal、灰细=control 不变。
  判定逐帧派生自两端对象的 signalInlets/Outlets 元数据（已知类查表 + 抽象
  [inlet~]/[outlet~] 精确解析），零存储状态——修复端点类名或删线即自动恢复
  原色。control→signal 入口合法（pd 语义），不标红。选中品红仍优先。
- **AI 生成补丁（0.15.0）**：文本模式底部 AI 条 = 值盒提示词输入框 + GEN/STOP/REVERT
  三态键（ValueChip 键帽语言，signal 青）；设置页新增 AI assistant BIOS 区（端点/API
  key/模型三项 + APPLY 全宽键 + 青色 test 链接行，全部行式规范）。回复剥 ``` 围栏后
  整体替换文本缓冲，走既有"切画布即重解析"链路成活对象；同一键 STOP=断连取消、
  REVERT=一键还原生成前缓冲（手动编辑即作废）。测试基建：`fake_llm_server.py`
  （本机假 OpenAI 端点，支持 SLOW/FENCE 模式）+ `adb reverse tcp:8123` 真实 HTTP
  往返，七条路径（替换/还原/取消/围栏/空 body/连不上/成画布）逐条实测。
- **table/graph 触屏与缩放（0.15.8）**：①graph 轮询器无条件重排且为 `[table]`
  现场合成 graph——外部 `[; 名 索引 值(` 写数组 100ms 内上屏（先实测 pd 0.51.3
  接受该写法）；②graph 触绘按**指尖尺寸**（22dp 半径映射 cell 跨度）+ 拖动
  **逐格插值扫过**（快划无缝），区段批量写 pd（`writeArrayRange`，一次 JNI 一笔）；
  ③drawGraph 长数组改**逐列 min/max 包络**（旧 stride 抽点的形状随缩放漂移、
  极值丢失），任何缩放不丢极值，放大过每列一样本退化全点折线——实测 AM 表
  两档缩放包络鼓包数一致。测试基建：emulator console `event send`/sendevent
  均注入不了应用层多点，缩放对比用 zoomToFit 两档（远端 comment 拉小 fit）。
- **msg 闪光与数组三样式（0.15.9）**：run 模式点 msg 盒 = bng 同构闪光
  （`flashUntil` 120ms，整盒填充 signal-cyan、文字反深色，screenrecord 逐帧
  实拍）；数组渲染三样式对齐 pd 0.51.3 `#X array` filestyle（0=Polygon 折线/
  1=Points 圆点/2=Bezier Catmull-Rom 平滑曲线，Properties "Draw style" 行选择，
  graph 块重写 flags token、`[table]` 追加第 4 参持久化）；Points 圆点色随数组
  （首数组青、余灰，修掉旧版固定品红 bug）；Bezier 大数组画包络上下缘平滑带
  （44100 点 AM 实拍=透空椭圆链）。pd 备注：`; 数组名 下标 值(` 只对 `[table]`
  数组生效，`#X array` 不绑 receiver（pd 静默丢弃，实测确认）。
- **GEN 灭灯键帽与顶栏次序（0.15.10）**：GEN busy 闪烁的 dead 相 = 文件页底部
  RENAME/DUPLICATE/DELETE 未选中态的同一灰键帽（0x545454 底 + 深墨字、不透明，
  替换旧 25% 透明做法）；顶栏「文本切换」移到「多选框选」之前（纯布局对调）。
- **Properties 下拉规范回补（0.15.11）**：Draw style / label 方位两个 Spinner
  此前用了系统默认下拉行（非像素字、行高颜色跑偏），改用设置页同款
  `spinner_item_pd98` / `spinner_dropdown_item_pd98`（10sp 像素字 + 暗青
  `#00A8A8` + 32dp 行 + popup_bg_pd98 方角弹层），与设置页下拉完全同构。
- **选择弹层自绘与逐像素对齐（0.15.12–0.15.14）**：Spinner 弹层宽度按内容算、
  弹出位置内部偏移（实测左移 ~120px，"选项和主体宽度不一样"）→ 弃用，Properties
  两处下拉换自绘 choiceField：值盒 + 锚定 PopupWindow，**窗宽 = 值盒宽 +
  9dp**（6dp 补 popup_panel_bg 右侧硬投影内缩 + 3dp 实测微调，右缘描边与值盒
  1dp 描边贴合；左缘 showAsDropDown 天然对齐）；行高 40dp、无 singleLine
  （不省略）、当前项 signal-cyan 强调、像素字。
- **未落地**：底缘走带噪带（app 底部被 add-bar/导航栏占据，无「内容区之外」的空位）、
  方舟像素 CJK 内嵌（界面无中文，系统回退即可；APK 内仅 F5.6 一个 7.2KB 字体）——
  其余模拟层全部按 §6 剂量执行。
