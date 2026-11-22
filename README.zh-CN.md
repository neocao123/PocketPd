# PocketPd

[English](README.md) | **简体中文**

**触屏版 Pure Data 编辑器** —— 在 Android 手机/平板上完整地**编辑并运行**
[Pure Data](https://puredata.info) 补丁。基于官方 libpd（`io.github.libpd.android:pd-core:1.2.1-rc6`，
内核 **pd 0.51.3**），在其之上实现了完整的触屏补丁编辑器、内置扩展库与外设桥接。

> **下载**：右侧 [Releases](../../releases) 直接下载 APK 安装（Android 8.0+）。

## 和桌面 pd / 其他 Android 方案相比，多了什么

**1. 完整的触屏编辑，而不是只读播放器**
- 画布上直接创建/拖拽对象、双指连线、点线删除、长按弹出属性 OSD——
  桌面 pd 的编辑工作流完整搬到触屏
- **编辑 / 运行 / 锁定** 三模式：编辑时静默热重建会话，运行时 GUI 全活，
  锁定防误触演出
- `.pd` 文件**无损读写**：与桌面 pd 双向互导（对象、连线、GOP、iemgui
  全保留）

**2. 触屏 GUI 超集（vanilla 之外新增的组件）**
- `keyboard` 和弦键盘、`pad` 打击垫、`button`/`popmenu`——为演奏设计的
  触控件
- `euclid` 欧几里得节奏发生器、`circle5` 五度圈调性盘——音乐理论直接
  变成对象
- `graph~` / `spectrograph~` 示波器与频谱（graph-on-parent 内嵌显示），
  `display`/`display~` 文本显示器
- vanilla 全套 iemgui（bng/tgl/vsl/vradio/…）按触屏习惯重绘

**3. 内置库，零配置开箱即用**
- 自研芯片引擎：`ym2612~`（世嘉 Genesis FM 音源）、`nes~`、`sid~`、
  `vgm~`、`opl3~` 等，随库附带 bank/演示文件
- 经典扩展库预编译内置：ELSE、cyclone、zexy、iemlib、creb、cxc、
  fftease、mrpeach（osc/midi/binfile）、diy2——不需要装 externals、
  不需要配路径
- **653 个帮助补丁** + vanilla 官方教程例程（control/audio 系列）内置，
  离线可查

**4. 外设桥**
- **MIDI**（android.media.midi，USB/BLE）、**OSC**（UDP 双向）、
  **串口**（USB-serial）、**传感器**（加速度计等）——全部直通 pd

## 已知边界（诚实清单）

- 运行时为 pd 0.51.3：**无多声道信号（mc）**、`[clone]` 仅支持 `-s`
  （单声道单实例）、ELSE 1.0-9 级新旗标语法（如 `initmess verbose`）
  的部分对象不可用——详见 [PD_VERSIONS.md](PD_VERSIONS.md)
- debug 签名包：目前发布 APK 为 debug 签名（版本验收以
  `aapt dump badging`/系统设置显示为准）

## 文档

| 文档 | 内容 |
|---|---|
| [README.dev.md](README.dev.md) | 架构分层、VI 设计语言（开发向） |
| [PD_VERSIONS.md](PD_VERSIONS.md) | 内核/各库版本考古（0.51 vs 0.55 究竟谁在哪） |
| [CHANGELOG.md](CHANGELOG.md) | 每轮版本历史（0.6.2 起） |
| [OBJECT_PORTS.md](OBJECT_PORTS.md) | 对象端口表 |
| [GM_BANK.md](GM_BANK.md) | ym2612~ GM 音色库说明 |

## 从源码构建

```bash
JAVA_HOME="C:/Android/jdk-17.0.20.1+1" /c/Android/gradle-8.9/bin/gradle assembleDebug
```

- 需要 Android SDK（compileSdk 34）；gradle wrapper 因网络环境不可用，
  使用本机 gradle 8.9 直调
- slibs（内置扩展库）为预编译产物，`slibs/build_slibs.py` 可复现

## 分支

- `master`：主线
- `0.51`：当前引擎基线（pd 0.51.3 / libpd 1.2.1-rc6）；未来若迁移到
  更新内核（pd-for-android master 的 pd 0.57-dev）会开新分支
