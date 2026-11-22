#!/usr/bin/env python3
"""Generate GM_BANK.md (workspace root) from the same source as the bank:
archive/xg.wopn melodic bank 0.  Since 0.17.50 the engine consumes RAW chip
register values, so every number below is the bank byte itself."""
import struct

SRC = r"C:\Projects\26PDAndroid\archive\fmtowns.wopn"
DST = r"C:\Projects\26PDAndroid\GM_BANK.md"

d = open(SRC, "rb").read()
off = 18 + 34 * 21   # header + 21 bank metadata blocks (10 mel + 11 perc)
INST = 69

rows = []
for n in range(128):
    b = off + n * INST
    name = d[b:b + 32].split(b"\x00")[0].decode("ascii", "replace").lstrip("* ")
    tr = struct.unpack_from(">h", d, b + 32)[0]
    fbalg = d[b + 35]
    alg, fb = fbalg & 7, (fbalg >> 3) & 7
    ops = []
    for l in range(4):
        o = b + 37 + l * 7
        dtmul, tl, rsatk, amdr, dt2sr, susrel = d[o:o + 6]
        ops.append(dict(
            mul=dtmul & 15, dt=(dtmul >> 4) & 7, tl=tl & 0x7f,
            ar=rsatk & 31, rs=(rsatk >> 6) & 3, dr=amdr & 31,
            sr=dt2sr & 31, rr=susrel & 15, sl=(susrel >> 4) & 15))
    rows.append((n + 1, name, tr, alg, fb, ops))

L = []
L.append("# GM_BANK.md - ym2612~ `gm 1-128` 音色参数总表\n")
L.append("来源：**FMTowns GM bank**（`archive/fmtowns.wopn`，WOPN v2，melodic "
         "bank 0，GM 顺序 1-128）。出处：libOPNMIDI 仓库 `fm_banks/FMTowns.wopn`"
         "（github.com/Wohlstand/libOPNMIDI；出处说明 `fm_banks/FMTowns-readme.txt`，"
         "本仓库镜像于 `archive/fmtowns-readme.txt`）。该表由 FM TOWNS 的 FMB 音色库"
         "（Euphony 轨道模块，modland.com）整理而成，音色在真实芯片上设计，"
         "Wohlstand 以公有领域发布；其打击乐组（基于 xg.wopn/fmmidi.wopn）不在本表"
         "使用范围（只用 melodic bank）。0.17.67 起替换原 XG bank（`archive/xg.wopn` "
         "保留可随时回滚）：实测审计中 XG 表的 MULTI=0 调制器落在整数 MULTI 载波"
         "链路上，产生半整数边带（用户报告的锯齿质感），FMTowns 表该项为候选中最少"
         "（9 处 vs 48 处）。下表是**引擎实际生效值**（引擎直接吃芯片寄存器原始值，"
         "由 `gen_gm_bank_wopn.py` 生成，与 `ym_gm_bank.h` 逐字节一致）。\n")
L.append("## 怎么读\n")
L.append("| 列 | 含义 |")
L.append("|---|---|")
L.append("| 移调 | XG note_offset（半音），gm 载入后自动作用于音高 |")
L.append("| alg | 算法 0-7（GPGX 接线：alg0 链 op1>op3>op2>op4，载波 op4；alg4 载波 op3+op4；alg5 op1 扇出到 op2-4；alg7 四个全载波）|")
L.append("| fb | op1 反馈深度 0-7（芯片阶梯，抽包络后输出，±2^(fb-7) 周期）|")
L.append("| opN | 该算子 9 个参数，顺序 `mul·dt·tl·ar·rs·dr·sr·rr·sl` |")
L.append("\n算子参数速查（全部为 YM2612 芯片原始寄存器值）：\n")
L.append("| 参数 | 范围 | 含义 |")
L.append("|---|---|---|")
L.append("| mul | 0-15 | 音高比（0 = ×0.5，1-15 = ×1-×15）|")
L.append("| dt | 0-7 | 芯片 DT 寄存器：0-3 正、4-7 负，幅度 = dt_tab[fnum 段]（约 ±0.4-2.5 音分微失谐）|")
L.append("| tl | 0-127 | 音量衰减，**0 最响 / 127 静音**，每步 -0.75dB |")
L.append("| ar / dr / sr / rr | 0-31（rr 0-15）| 攻击/衰减/sustain 段/释放**速率**，越大越快；**0/1 = 不动**（芯片表格无穷时间）|")
L.append("| rs | 0-3 | 速率键标度（rate scaling）：ksr = kcode >> (3-RS)，音高越高包络越快 |")
L.append("| sl | 0-15 | sustain 电平衰减，**0 = 满电平 / 15 = 93dB（近静音）**，每步 3dB |\n")
L.append("芯片行为（0.17.51 起由内嵌的 Nuked-OPN2 芯片核逐周期仿真，"
         "nukeykt/Nuked-OPN2 ym3438.c v1.0.12）：包络发生器、LFO 硬件、"
         "反馈阶梯、算子间 1 样本 MEM 延迟、DAC 截断、相位生成都按硅片"
         "行为走；gm 载入 = 把下表字节 1:1 写入通道 0 的算子寄存器组。\n")
L.append("引擎不建模的芯片功能（xg.wopn 里有、被丢弃）：DT2（YM2612 无此寄存器，"
         "芯片本身忽略）、AM（颤音 LFO，177 个算子带 AM 位）、SSG-EG（54 个算子）、"
         "LFO（银行头开了 LFO，6.4Hz 档）。AM/LFO 是待办，不是噪声源。\n")
L.append("## 128 个音色\n")
L.append("| GM | 名称 | 移调 | alg | fb | op1 (mul·dt·tl·ar·rs·dr·sr·rr·sl) | op2 | op3 | op4 |")
L.append("|---:|---|---:|---:|---:|---|---|---|---|")

def cell(op):
    return "%d·%d·%d·%d·%d·%d·%d·%d·%d" % (
        op["mul"], op["dt"], op["tl"], op["ar"], op["rs"], op["dr"],
        op["sr"], op["rr"], op["sl"])

for gm, name, tr, alg, fb, ops in rows:
    L.append("| %d | %s | %s | %d | %d | %s | %s | %s | %s |" % (
        gm, name, ("%+d" % tr) if tr else "-", alg, fb,
        cell(ops[0]), cell(ops[1]), cell(ops[2]), cell(ops[3])))

L.append("\n## 备注（0.17.67 实测）\n")
L.append("- 本表 ×0.5 调制器落整数载波链的混合边 = 9 处（原 XG 表 48 处）；"
         "gm41 小提琴渲染频谱为纯谐波列（165Hz 整数倍），无半整数边带。")
L.append("- fb=7（最大反馈）预置占比 71/128：FM Towns 游戏音色的性格，部分预置"
         "偏粗砺；fb 只作用于 op1 自反馈，实际深度随该算子电平变化。")
L.append("- 弦乐/铜管类若感高八度：该表的 note_offset 与 mul 设计如此（渲染实测 "
         "gm1 钢琴 163Hz、gm33 贝斯 163Hz 锚点正确）。")
L.append("- tl=127 的算子 = bank 标记的静音/未用算子（引擎按 ≥78dB 硬静音，与芯片一致）。")
L.append("- 128 预置峰值扫描 0 静音（min peak 0.060，note 60 vel 127）。\n")
open(DST, "w", encoding="utf-8", newline="\n").write("\n".join(L) + "\n")
print("wrote GM_BANK.md, %d rows" % len(rows))
