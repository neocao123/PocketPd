# slibs — 内置 pd-extended 经典外部库

本目录保存为 PocketPd 构建内置库(diy2 / cyclone / zexy / iemlib / fftease /
bsaylor / creb / cxc / mrpeach osc / midifile / binfile)所用的可复现流水线。

## 源码

- `DIY2`(plugdata 0.9 附带的纯抽象库,ugur "doctor Wah" diy2,159 个 .pd):
  本机 `C:\Users\Public\Documents\plugdata\Extra\DIY2`,拷贝到
  `%TEMP%/srcrepo/diy2/`;**纯抽象库,不编译 core**,整目录作为对象入库,
  且在 [PRIORITY] 中列首位(裸名冲突默认 DIY2 胜出)
- `pd-extended 0.43.4 source tarball`
  https://sourceforge.net/projects/pure-data/files/pd-extended/0.43.4/Pd-extended_0.43.4-source.tar.bz2
  提供 cyclone(miXed)、zexy、iemlib、creb、cxc、bsaylor、mrpeach osc/midifile/binfile
- `fftease`(现代版,3.x)来自 GitHub `ericlyon/pd-fftease`,经 jsdelivr CDN 拉取
  (本机 GitHub 直连不通;`svnfetch.py` 是早期 SVN 抓取脚本,现已不需要)
- pd 头文件:`pd-0.51-4.src.tar.gz`(msp.ucsd.edu),与 AAR 内 pd 0.51.3 匹配

放置到 `%TEMP%/srcrepo/` 下:
`pd-extended/externals/{...}` 与 `fftease/`、`diy2/`、`pdsrc/pd-0.51-4/src/`。

## 构建

```
python slibs/build_slibs.py            # 全部库 × {arm64-v8a, x86_64}
python slibs/build_slibs.py cyclone    # 单库(manifest 按库合并写回)
python slibs/build_help.py             # 653 个帮助补丁 → assets/help/<lib>/
python slibs/build_demos.py            # 11 个库例程 → assets/examples/slibs/
```

要点:
- 每库链接为**单个** `<lib>_core.so`(库内对象互相引用,无法按对象拆分),
  导出 `<lib>_core_setup`,llvm-strip 去符号后放入 `app/src/main/assets/slibs/<abi>/<lib>/`
- 类清单写入 `assets/slibs/slibs_manifest.txt`,供 app 启动时报告同名冲突,
  也供 Help 菜单把类名路由到所属库的帮助目录
- 应用侧:`Slibs.kt` 解压 → `PdTweaks`(libpdtweaks.so,源码见本目录注释)
  直接 dlopen 并调用 setup;注册顺序为优先级的**逆序**(pd 后注册者胜)
- 帮助补丁源:v_5.reference(vanilla)、miXed/doc/help/cyclone、
  zexy/{reference,abs}、iemlib/iemabs、bsaylor、creb/doc、cxc、mrpeach 各库
  (只取 `*-help.pd`/`help-*.pd`,fftease 上游未随源码发布帮助补丁)
- 例程均为可听 demo,经官方 `[output~]`(GOP 抽象,examples/audio)出声,
  并内置 demo.mid / demo.bin 供 midifile / binfile 演示读取

## 运行期行为

- 裸名 `[mean]` 由注册顺序决定归属(优先级:**diy2** > cyclone > zexy > iemlib >
  fftease > bsaylor > creb > cxc > osc > midifile > binfile);diy2 为纯抽象库,
  抽象按搜索路径首个命中解析,同样受 PRIORITY 顺序保护
- 前缀 `[zexy/lister]` 走库源码自带的命名空间别名注册,与桌面 pd-extended 相同
- 冲突清单在启动控制台输出;用户 externals 目录永远优先于内置库
- 长按对象 → Open / Help:Open 打开抽象源文件;Help 命中顺序 = 所属库帮助目录
  (manifest 反查)→ vanilla → 其余库,无帮助时灰显
- GOP 抽象(如 `[output~]`)的内嵌控件画在父盒上并可交互:交互会话把该
  抽象内联为等价对象图($0/$N 替换、连线重定向),磁盘文件保持原样
