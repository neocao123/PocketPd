# TODO — 悬置中的未解决问题（看板）

> 2026-11-28 应用户要求建立："把目前几个没解决的问题先写个文档，做成
> to do list，先悬置"。每条注明现状、已收口部分、下一步手段。
> 动手前先按 AGENTS.md 规则通读对应弧线的 CHANGELOG（弧线终点为现状）。

## 1. synth~ 无声（v344/0.18.40 后仍存在）

- **状态**：悬置——等诊断 print 轮批准，或重装排除文件漂移后的新证据。
- **已收口**（勿重查）：
  - clone 目标文件 `synth.voice.template.pd` 已补发（v341/0.18.37，双 ABI）；
  - 模板内 `else/-` 前缀盒子创建失败已修（v338 放宽 + v342 slibs 根目录
    重武装 + v344 物化）；
  - 发送型重建消息 `$1/$2` 空展开已物化（v344/0.18.40）：会话文本证实
    得到 `clone -do synth.voice.template 4`，不再是空 clone；
  - 静态接线逐条核对完整（keyboard relay → guts → route → sum~ → dac~）。
- **剩余疑点**：唯一静态透视不到的环节 = `[pd init]` 链触发时序 +
  keyboard list 经 mono-poly（else/router selector 分支）→ clone 分发的
  **运行时消息流**。
- **下一步手段**（已提出、待批）：在 ship 的 synth~.pd 副本三个关键点
  （`[pd route]` 入口 / mono-poly 出口 / clone 分发前）加临时 `[print]`，
  Slibs.VERSION bump 一次；按一个键即从 console 看到音符死在哪一段；
  定位后出修复轮并拆打印（v332/v343 探针先例：探针→一次复测→拆除）。
- **v345 补充**：已强制 slibs 干净重提取（VERSION 149→150），重装排除
  "手机上 synth~.pd 被改坏"的变量；重装前后设备文件 md5 对比结果记入
  0.18.41 的 CHANGELOG 条目。

## 2. mov.rms~ 已注册却间歇失败

- **状态**：用户明令悬置。
- **事实**：`setup_mov_rms_tilde` 符号已导出、已注册、文件在盘；
  偶发 `_ couldn't create`（灰显）。影响面已缩至独立 else/norm~
  （norm~ 模板内同款对象无恙）。
- **下一步手段**（未批）：复现现场抓 verbose loader 轨迹，比对
  成功/失败两次的查找路径差异。

## 3. 探针拆除轮

- **状态**：等用户确认后单独出轮。
- **内容**：v343 的 `PdBase.setVerbose(true)` + `PocketPd.PdVerbose`
  logcat 专用通道；v335 起的 createFailuresThisOpen 汇总行。按先例
  "探针→一次复测→拆除"，确认无用后移除，Slibs.VERSION 无关（纯 Kotlin）。

## 4. 52 死行清理轮（待批）

- **状态**：待批，须单独一轮（触碰构建期生成链）。
- **内容**：
  - 编译 else 的 Extra/Aliases 进 farm（复活 `lb`——insert.pd 在用，
    其余 51 行为别名/同址死行）；
  - `core_classes.txt` 从真实 .dynsym 导出重生成（现名单含历史残留）；
  - farm 符号链接清单同步收缩。
- **风险**：core_classes.txt 由 build_slibs.py 增量生成，手工改动会被
  下次构建冲掉——须改生成器而非产物。

## 5. `r -,` 类垃圾桩的生成机制本体

- **状态**：已消化——v340 修了污染源头（atomMeta bound() 把粘连
  `,`/`;` 的 token 当真名），桩不再产生。
- **备注**：仅当未来其他路径再生成同类桩时，才需要修桩生成器自身的
  命名转义；届时先查 echo-relay 腿的命名链路。
