# fonts/ — 内嵌字体的来源与再生成

设计稿 SVG 内已用 base64 内嵌开源字体子集（woff2），无需系统安装即可在任何浏览器正确显示。

## 当前启用

| 内嵌名 | 字体 | 许可 | 子集 | 大小 |
|---|---|---|---|---|
| `PD98LAT` | **F5.6**（Sora Sagano / dotcolon.net，拉丁点阵，全部拉丁文字） | ⚠️ 免费字体，无内嵌许可证字段——**随 APK/对外分发前需到 dotcolon.net 核实条款** | 正文+展示共用：全部 ASCII（含 `~ * $ % =` 等），仅 `· • → ▼` 回退方舟像素 | 3.8 KB |
| `PD98UI` | **Ark Pixel 12px Proportional zh-Hans**（方舟像素 12px 简体，TakWolf/ark-pixel-font） | OFL 1.1（[OFL-Ark-Pixel.txt](OFL-Ark-Pixel.txt)） | 全部中文与特殊符号；小字文本吸附 12px 整数倍（笔画 1px） | 7.1 KB |

字形分工：CSS 逐字符回退——英文/数字走 `PD98LAT`（F5.6），中文与符号走 `PD98UI`
（方舟像素）。栈末端是系统安装的 Sarasa Gothic HC Light（防未来缺字）。

完整字体：`design/ark-pixel-12px-proportional-zh_hans.ttf`（来源
https://github.com/TakWolf/ark-pixel-font ）、`design/F5.6-Regular.otf`
（来源 http://www.dotcolon.net ，F1.8/F5.6 同系列两种网格）。

## 备用（未引用，保留可用）

| 文件 | 字体 | 许可 |
|---|---|---|
| `pd98-sarasa.woff2` + `OFL-Sarasa-Gothic.txt` | Sarasa Gothic HC Light（更纱黑体，V2 曾用；完整 TTF 在 `design/` 根目录） | OFL 1.1 |
| `dotgothic16-latin.woff2` + `OFL-DotGothic16.txt` | DotGothic16 拉丁（点阵，曾用于字标） | OFL 1.1 |

## 再生成子集

```bash
python -m fontTools.subset <TTF> --text-file=used_chars.txt \
  --flavor=woff2 --output-file=pd98-ui.woff2 --no-hinting
```

`used_chars.txt` 为 5 张 SVG 实际出现的全部字符；改动文字后重跑一次并重新内嵌。

## 适配说明

- 方舟像素原生 12px 网格；稿内文字尺寸若取 12 的整数倍（12/24/36px）可获得完全整齐的
  像素网格，非整数倍时浏览器平滑缩放、观感略柔——当前尺寸即为此状态；
- 两款字体均无 Bold 字重，稿内已移除全部合成加粗（强调靠反色与颜色）；
- 缺字处理（方舟像素）：`≥→>=`、`警告→WARN`、`斜体→ITALIC`、`窗体→面板`、`浮窗→浮层`、`缘→侧/边`；
- 字重协调（用户反馈"显粗"）：实测 F5.6 竖笔=字号×25%（20px→5px，为 2px 边框的 2.5 倍），
  属字体固有结构。方案：SVG 内对拉丁文字叠加 `feMorphology erode` 腐蚀滤镜削细笔画——
  展示层（≥21px，class disp）erode 0.6；亮色正文（wt/gr/cy/yl/lbl/ed/val）erode 0.3；
  暗色小字与全部中文不腐蚀（方舟 12px 正文笔画恰 1px）。落地 App 时等价手段为
  `Paint.setStyle(STROKE_AND_FILL)` 细化或直接接受字重（安卓无此滤镜）；
- F1.8 的 `…` 字形过小，04 页 MIDI 提示改用三个 ASCII 句点（`...`）。
