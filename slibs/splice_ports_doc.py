#!/usr/bin/env python3
"""splice_ports_doc.py - rewrite OBJECT_PORTS.md library sections with the
0.17.142 source-verified audit rows (mechanical scanner + zexy agent table),
replacing stale sections and appending phantom-class notes."""
import re, io

DOC = r"C:/Projects/26PDAndroid/OBJECT_PORTS.md"

def table(rows, title):
    out = ["### %s" % title, "", "| 对象 | 进 | 出 | 信号进 | 信号出 | 来源 |", "|---|---|---|---|---|---|"]
    for r in rows:
        out.append("| `%s` | %s | %s | %s | %s | 源码核对 |" % r)
    return "\n".join(out) + "\n"

def C(cls, i, o, si="-", so="-"):
    return (cls, i, o, si, so)

cyclone = [C("Append",2,1),C("Borax",3,9),C("Clip",3,1),C("Clip~",1,1,"0","0"),
    C("Histo",2,2),C("Line~",2,2,"0","0,1"),C("MouseState",1,5),C("Peak",2,3),
    C("Scope~",2,0,"0,1","-"),C("Snapshot~",2,1,"0","-"),C("Table",2,2),
    C("TogEdge",1,2),C("Trough",2,3),C("accum",3,1),C("acos",1,1),
    C("acosh~",1,1,"0","0"),C("acos~",1,1,"0","0"),C("active",1,1),
    C("allpass~",3,1,"0,1,2","0"),C("anal",1,1),C("asin",1,1),
    C("asinh~",1,1,"0","0"),C("asin~",1,1,"0","0"),C("atanh~",1,1,"0","0"),
    C("atan~",1,1,"0","0"),C("average~",1,1,"0","-"),C("avg~",1,1,"0","-"),
    C("bangbang",1,1),C("bitand~",2,1,"0,1","0"),C("bitnot~",1,1,"0","0"),
    C("bitor~",2,1,"0,1","0"),C("bitshift~",1,1,"0","0"),
    C("bitxor~",2,1,"0,1","0"),C("borax",3,9),C("buffir~",3,1,"0,1,2","0"),
    C("capture",1,1),C("capture~",1,0,"0","-"),C("cartopol",2,2),
    C("cartopol~",2,2,"0,1","0,1"),C("change~",1,1,"0","0"),
    C("click~",1,1,"0","0"),C("comb~",5,1,"0,1,2,3,4","0"),C("comment",1,0),
    C("cosh",1,1),C("cosh~",1,1,"0","0"),C("cosx~",1,1,"0","0"),
    C("count~",2,1,"0","0"),C("curve~",3,2,"0","0"),
    C("cycle~",2,1,"0,1","0"),C("decide",2,1),C("delay~",2,1,"0","0"),
    C("deltaclip~",1,1,"0","0"),C("delta~",1,1,"0","0"),C("drunk",3,1),
    C("edge~",1,2,"0","-"),C("flush",2,2),C("forward",1,0),
    C("frameaccum~",1,1,"0","0"),C("framedelta~",1,1,"0","0"),
    C("fromsymbol",1,1),C("funbuff",2,3),C("hammer",1,1),C("histo",2,2),
    C("index~",2,1,"0","0"),C("iter",1,1),C("kink~",1,1,"0","0"),
    C("linedrive",2,1),C("lookup~",3,1,"0,1,2","0"),
    C("lores~",3,1,"0,1,2","0"),C("maximum",2,1),C("maximum~",1,1,"0","0"),
    C("maxmode",1,2),C("mean",1,2),C("midiflush",1,1),C("midiformat",7,1),
    C("midiparse",1,7),C("minimum",2,1),C("minimum~",1,1,"0","0"),
    C("minmax~",1,4,"0","0,1"),C("mousefilter",1,1),C("mousestate",1,5),
    C("mstosamps~",1,2,"0","0"),C("next",1,2),C("offer",2,1),
    C("onebang",2,1),C("onepole~",2,1,"0,1","0"),C("overdrive~",2,1,"0","0"),
    C("past",1,1),C("peak",2,3),C("peakamp~",2,1,"0","-"),C("peek~",3,1),
    C("phasewrap~",1,1,"0","0"),C("pink~",1,1,"0","0"),C("play~",1,1,"0","0"),
    C("poke~",3,0,"0,1","-"),C("poltocar",2,2),
    C("poltocar~",2,2,"0,1","0,1"),C("pong~",3,1,"0,1,2","0"),
    C("pow~",2,1,"0,1","0"),C("prepend",2,1),C("prob",1,2),C("pv",1,1),
    C("rampsmooth~",3,1,"0","0"),C("rand~",1,1,"0","0"),
    C("record~",4,1,"0,1","0"),C("reson~",4,1,"0,1,2,3","0"),
    C("sah~",2,1,"0,1","0"),C("sampstoms~",1,2,"0","0"),
    C("scope~",2,0,"0,1","-"),C("seq",1,2),C("sickle",1,1),C("sinh",1,1),
    C("sinh~",1,1,"0","0"),C("sinx~",1,1,"0","0"),C("slide~",1,1,"0","0"),
    C("speedlim",2,1),C("spell",1,1),C("spike~",2,1,"0","-"),
    C("sprintf",2,1),C("substitute",2,2),C("sustain",3,2),
    C("svf~",3,4,"0,1,2","0,1,2,3"),C("tanx~",1,1,"0","0"),C("testmess",1,1),
    C("thresh",2,1),C("togedge",1,2),C("tosymbol",1,1),
    C("train~",1,2,"0","0"),C("trapezoid~",1,1,"0","0"),
    C("triangle~",1,1,"0","0"),C("trough",2,3),C("universal",1,0),
    C("urn",2,2),C("vectral~",3,1,"0,1,2","0"),
    C("wave~",3,1,"0,1,2","0"),C("xbendin",1,2),C("xbendin2",1,3),
    C("xbendout",2,1),C("xbendout2",3,1),C("xnotein",1,4),C("xnoteout",4,1),
    C("zerox~",1,2,"0","0,1"),C("!-",2,1,"0","-"),C("!-~",1,1,"0","0"),
    C("!/",2,1,"0","-"),C("!/~",1,1,"0","0"),C("!=~",1,1,"0","0"),
    C("%~",1,1,"0","0"),C("+=~",1,1,"0","0"),C("<=~",1,1,"0","0"),
    C("<~",1,1,"0","0"),C("==~",1,1,"0","0"),C(">=~",1,1,"0","0"),
    C(">~",1,1,"0","0"),C("loadmess",1,1),C("pong",3,1),C("round",2,1),
    C("round~",2,1,"1","0"),C("trunc~",2,1,"1","0"),
    C("counter(循环代理建口,维持静态 5/4)",5,4),C("mtr(按轨数循环,未定规则)",2,2),
]

cxc = [C("ENV",1,1),C("ascseq",2,1),C("ascwave",4,1),C("bfilt",2,1),
    C("bfilt2",2,1),C("cxavgdev",1,1),C("cxc",1,0),C("cxc_counter",4,2),
    C("cxc_prepend",1,1),C("cxc_split",1,1),C("cxmean",1,1),C("cxstddev",1,1),
    C("dist_normal",2,1),C("hex2dec",1,1),C("ixprint",1,0),C("mean~(控制)",1,1),
    C("proc",1,1),C("random1",3,1),C("random1~",1,1,"-","0"),
    C("random_fl",3,1),C("random_fl~",1,1,"-","0"),C("random_icg",3,1),
    C("random_icg~",1,1,"-","0"),C("random_tw",3,1),C("utime",1,2),
    C("<<",1,0),C(">>",1,0),C("cx.avgdev",1,1),C("cx.mean",1,1),C("cx.stddev",1,1)]

creb = [C("bdiag~",1,1,"0","0"),C("bfft~",1,1,"0","0"),
    C("bitsplit~",1,1,"0","0"),C("blocknorm~",2,1,"0,1","0"),
    C("bmatrix~",1,1,"0","0"),C("bwin~",1,1,"0","0"),C("cheby~",1,1,"0","0"),
    C("diag~",1,1,"0","0"),C("dist~",2,1,"0","0"),C("dwt~",1,0,"0","-"),
    C("dynwav~",2,1,"0,1","0"),C("eadsr~",5,1,"-","0"),C("ead~",3,1,"-","0"),
    C("ear~",3,1,"-","0"),C("fdn~",3,2,"0","0,1"),C("ffpoly",1,1),
    C("fwarp",1,1),C("junction~",2,1,"0,1","0"),C("lattice~",1,1,"0","0"),
    C("permut~",1,1,"0","0"),C("qmult~",2,1,"0,1","0"),
    C("qnorm~",2,1,"0,1","0"),C("ratio",1,1),C("resofilt~",3,1,"0,1,2","0"),
    C("sbosc~",3,2,"0,1","0,1"),C("scrollgrid1D~",4,3,"0,1,2,3","0,1,2"),
    C("statwav~",1,1,"0","0"),C("tabreadmix~",2,1,"0","0"),
    C("xfm~",4,2,"0,1,2,3","0,1"),C("clog~",2,2,"0,1","0,1"),
    C("cexp~",2,2,"0,1","0,1"),C("nfft~",2,2,"0,1","0,1"),
    C("nifft~",2,2,"0,1","0,1"),C("bifft~",1,1,"0","0"),
    C("blockramp~",1,1,"-","0"),C("dt_ms",1,2),C("dwt16~",1,0,"0","-"),
    C("fht~",1,1,"0","0"),C("ibfft~",1,1,"0","0"),C("idwt16~",1,0,"0","-"),
    C("idwt~",1,0,"0","-"),C("sawtooth~",1,1,"-","0"),
    C("biquadseries~",1,1,"0","0"),C("blosc~",3,1,"0,1","0"),
    C("filterortho~",1,1,"0","0")]

fftease = [C("bthresher~",3,3,"0,1,2","0,1"),C("burrow~",4,2,"0,1,2,3","0,1"),
    C("cavoc27~",1,2,"0","0,1"),C("cavoc~",1,2,"0","0,1"),
    C("centerring~",4,2,"0,1,2,3","0,1"),C("codepend~",4,2,"0,1,2,3","0,1"),
    C("dentist~",1,5,"0","0,1,2,3"),C("disarrain~",1,5,"0","0,1,2,3"),
    C("disarray~",1,3,"0","0,1"),C("drown~",3,2,"0,1,2","0,1"),
    C("ether~",3,2,"0,1,2","0,1"),C("leaker~",3,2,"0,1,2","0,1"),
    C("mindwarp~",3,2,"0,1,2","0,1"),C("morphine~",3,2,"0,1,2","0,1"),
    C("pvcompand~",2,2,"0,1","0,1"),C("pvgrain~",1,1,"0","-"),
    C("pvharm~",4,2,"0,1,2,3","0,1"),C("pvoc~",3,2,"0,1,2","0,1"),
    C("pvtuner~",3,2,"0,1,2","0,1"),C("pvwarp~",2,2,"0,1","0,1"),
    C("reanimator~",2,4,"0,1","0,1,2,3"),C("resent~",1,4,"0","0,1,2,3"),
    C("residency~",3,4,"0,1,2","0,1,2,3"),C("scrape~",2,2,"0,1","0,1"),
    C("shapee~",3,2,"0,1,2","0,1"),C("swinger~",2,2,"0,1","0,1"),
    C("taint~",4,2,"0,1,2,3","0,1"),C("thresher~",3,2,"0,1,2","0,1"),
    C("vacancy~",3,2,"0,1,2","0,1"),C("xsyn~",2,2,"0,1","0,1"),
    C("crossx~",3,2,"0,1,2","0,1"),C("presidency~",4,4,"0,1,2,3","0,1,2,3")]

iemlib = [C("FIR~",1,1,"0","0"),C("LFO_noise~",1,1,"-","0"),
    C("add2_comma",1,1),C("aspeedlim",2,1),C("biquad_freq_resp",1,4),
    C("bpe",1,3),C("db2v",1,1),C("dollarg",1,2),C("exp_inc",5,1),
    C("f2note",1,3),C("fade~",1,1,"0","0"),C("filter~",5,2,"0","0"),
    C("float24",1,1),C("for++",4,2),C("forpp",4,2),
    C("hml_shelf~",7,2,"0","0"),C("iem_alisttosym",1,1),C("iem_anything",2,1),
    C("iem_append",2,1),C("iem_blocksize~",1,1,"0","-"),
    C("iem_cot4~",1,1,"0","0"),C("iem_delay~",2,1,"0","0"),
    C("iem_i_route",1,1),C("iem_pbank_csv",2,2),C("iem_pow4~",2,1,"0","0"),
    C("iem_prepend",2,1),C("iem_receive",1,1),
    C("iem_samplerate~",1,1,"0","-"),C("iem_sel_any",1,2),C("iem_send",2,0),
    C("iem_sqrt4~",1,1,"0","0"),C("iem_symtoalist",1,1),C("init",1,1),
    C("list2send",1,0),C("lp1_t~",3,1,"0","0"),C("m2f~",1,1,"0","0"),
    C("mergefilename",1,1),C("modulo_counter",2,1),
    C("mov_avrg_kern~",3,1,"0,1","0"),C("para_bp2~",5,2,"0","0"),
    C("parentdollarzero",1,1),C("peakenv_hold~",3,1,"0","0"),
    C("peakenv~",2,1,"0","0"),C("post_netreceive",1,1),C("pre_inlet",1,1),
    C("prepend_ascii",1,1),C("protect_against_open",1,0),
    C("prvu~",1,1,"0","-"),C("pvu~",1,2,"0","-"),C("receive2list",1,1),
    C("round_zero",1,1),C("rvu~",1,1,"0","-"),C("sin_phase~",2,1,"0,1","0"),
    C("soundfile_info",1,1),C("splitfilename",1,2),C("stripfilename",1,1),
    C("toggle_mess",1,3),C("transf_fader",1,1),C("unsymbol",1,1),
    C("v2db",1,1),C("vcf_filter~",3,1,"0,1,2","0"),
    C("$n(=dollarg)",1,2),C("any(=iem_append)",2,1),C("f24(=float24)",1,1),
    C("iem_cot~(别名)",1,1,"0","0"),C("iem_r(=iem_receive)",1,1),
    C("iem_s(=iem_send)",2,0),C("ii",1,1),C("iiroute",1,1),
    C("merge_any",2,1),C("parent$0(=parentdollarzero)",1,1),C("pp",2,1),
    C("sin_freq~",1,1,"0","0"),C("sparse_FIR~",1,1,"0","0"),
    C("t3_bpe",1,3),C("t3_del",2,1),C("t3_delay",2,1),
    C("t3_line~",2,1,"-","0"),C("t3_metro",2,2),
    C("t3_sig~",1,1,"-","0"),C("t3_timer",2,1),C("tm(=f2note)",1,3),
    C("unsym(=unsymbol)",1,1)]

small = {
    "bsaylor": [C("aenv~",5,1,"-","0"),C("zhzxh~",2,1,"0","0"),
                C("susloop~(else 抢名,bare 不可达)",4,1,"0","0"),
                C("svf~(cyclone 抢名,bare 不可达)",3,4,"0,1,2","0,1,2,3")],
    "osc": [C("packOSC",1,2),C("pipelist",2,1),C("unpackOSC",1,2),
            C("routeOSC(规则)",1,"n+1")],
    "midifile": [C("midifile",1,3)],
    "binfile": [C("binfile",1,3)],
    "tracker": None,
}

zexy = [C(".",2,1),C("&&~",2,1,"0,1","0"),C("a2l",1,1),C("any2list",1,1),
    C("absgn~",1,2,"0","0,1"),C("atoi",1,2),C("atof",1,2),
    C("blockmirror~",1,1,"0","0"),C("blockshuffle~",1,1,"0","0"),
    C("blockswap~",1,1,"0","0"),C("date",1,6),C("dfreq~",1,1,"0","0"),
    C("dirac~",1,1,"-","0"),C("drip",2,1),C("doublepole~",1,1,"0","0"),
    C("envrms~",1,1,"0","-"),C("fifop",2,2),C("freadln",1,2),
    C("fwriteln",1,0),C("glue",2,1),C("index",1,1),C("l",2,1),C("l2i",1,1),
    C("l2s",2,1),C("length",1,1),C("lifop",2,2),C("list2int",1,1),
    C("list2lists",2,1),C("list2symbol",2,1),C("lister",2,1),C("listfind",2,1),
    C("liststorage",2,2),C("lpt",2,1),C("minmax",1,2),C("msgfile",1,2),
    C("matchbox",2,2),C("mavg",2,1),C("multireceive",1,1),
    C("niagara",2,2),C("noish~",1,1,"-","0"),C("noisi~",1,1,"-","0"),
    C("operating_system",1,1),C("pack~",1,1,"0","-"),C("pdf~",1,1,"0","-"),
    C("prime",1,1),C("quantize~",1,1,"0","0"),C("rawprint",1,0),
    C("regex",2,3),C("repack",2,1),C("repeat",2,1),
    C("route~",1,2,"0","-"),C("s2l",2,1),C("sgn~",1,1,"0","0"),
    C("sigzero~",1,1,"0","-"),C("sleepgrain",1,1),C("step~",2,1,"-","0"),
    C("strcmp",2,1),C("swap~",1,1,"0","0"),C("symbol2list",2,1),
    C("tabminmax",1,2),C("tabread4~~",2,1,"0,1","0"),C("tabset",1,0),
    C("tavg~",1,1,"0","-"),C("time",1,4),C("unpack~",1,1,"-","0"),
    C("wrap",2,1),C("longload",0,0)]

notes = """
### 0.17.142 清查备注

- 来源=源码核对:逐类读构造函数(pd_new 默认口 + inlet_new/floatinlet_new/
  symbolinlet_new/pointerinlet_new/outlet_new 计数 + CLASS_MAINSIGNALIN /
  `class_addmethod(...,"signal")` / miXed `sic_setup`/`sic_newinlet` 标信号口),
  端口序号按创建顺序定位。绘制真值是 `PdObjectMeta.kt`,本表是其摘要。
- creb ead~/eadsr~/ear~ 是包络**发生器**:进口全是控制口,dsp_add 直接写
  sp[0]=出口向量,无信号进口。
- zexy 五个"控制进+信号出"类(dirac~/noish~/noisi~/unpack~/step~)与五个
  "信号进+浮点/列表出"类(envrms~/sigzero~/tavg~/pack~/pdf~):pd 画的是
  控制口/浮点口,heuristic 的 ~ 全信号画法是错的。
- zexy route~ 的 2 出口:0=信号直通,1=通用消息口(此前把 1 标成了信号)。
- bare-name 遮蔽:slibs 预加载逆序注册(pd 的 class_addmethodtolist 把先注册
  的类改名 `<name>_aliased`),所以 cyclone 抢 zexy(avg~/urn/abs~/<~/==~/>
  ~)、zexy 抢 vanilla(wrap/pack~/pdf~ 等按裸名加载后的运行时真值)、else 抢
  bsaylor(susloop~)/cyclone(ramp~)/iemlib(wrap 不存在;iemlib 无)。被抢的
  变体只能带前缀到达,绘制按裸名查表(PdObjectMeta.kt:1143),前缀拼写不单独
  建行。
- 幻影类(core_classes.txt 有名、源码无注册,运行时 "couldn't create"):
  zexy 的 lp/mux_tilde~/tabread4_tilde~/zpack/zunpack 及 6 个 16 位十六进制
  串;creb 的 cmath~;cxc 的 binshift;fftease 的 cross~/enrich~/loopsea~/
  pileup~/pvwarpb~/schmear~/residency_buffer~(MSP-only 源码,PD 分支缺失);
  cyclone 的 allhammers/allnettles/allsickles 是聚合 setup 不是实例类。
- 内部代理类(cyclone 的 _bondo_proxy 系、zexy 的 multiplex/strcmp proxy、
  iemlib 的 _*_proxy)不可被补丁实例化,不建行。
- cyclone counter/mtr 的进口由代理循环创建,计数循环体一次=错;counter 维持
  静态 5/4,mtr 待定规则(暂按启发式)。grab 的出口数按首参可变,未定规则。
- zexy 可变 IO 0.17.142 起按创建参数推导:mux/multiplex 进=max(argc,2)+1、
  mux~/multiplex~ 全信号同式、demux/demultiplex 进 2 出=max(argc,2)、
  demux~/demultiplex~ 同式全信号、packel 进=1+n 出=n、multiline~
  n=max(argc-1,1) 进 n+2(前 n 信号+2 浮点)出 n、limiter~ 进=min(max(argc,1),
  64) 全信号出 1、sfplay 进 2 出 c+1(末口 bang)、sfrecord 进 c 出 1(c 由首
  参:1/2/4/8 保留,3→2,5-7→7,其余 1)。此前 kt:341 "mux/demux 由 n <count>
  消息扩展"的机制描述是错的,源码无 resize 方法。
"""

def splice(doc_text, header_re, new_text):
    """Replace from the header line up to (not incl.) the next blank-line
    followed by ### or ## with new_text."""
    m = re.search(header_re, doc_text, re.M)
    if not m:
        return doc_text + "\n" + new_text
    start = m.start()
    nxt = re.search(r'\n(###|## )', doc_text[m.end():])
    end = m.end() + (nxt.start() + 1 if nxt else len(doc_text) - m.end())
    return doc_text[:start] + new_text + doc_text[end:]

text = open(DOC, encoding="utf-8").read()
text = splice(text, r'^### cxc\(2 个\).*$', table(cxc, "cxc(%d 个,0.17.142 源码核对重写)" % len(cxc)))
text = splice(text, r'^### cyclone\(16 个\).*$', table(cyclone, "cyclone(%d 行,0.17.142 源码核对重写;含 counter/mtr 备注行)" % len(cyclone)))
text = splice(text, r'^### zexy\(2 个.*$', table(zexy, "zexy(%d 行,0.17.142 全类清查;可变 IO 见清查备注)" % len(zexy)))
text = splice(text, r'^### tracker\(3 个\).*$', table(small["bsaylor"], "bsaylor(4 类,0.17.142 源码核对)") + "\n" +
    table(small["osc"], "osc(4 类,0.17.142 源码核对)") + "\n" +
    table(small["midifile"], "midifile(1 类,0.17.142 源码核对)") + "\n" +
    table(small["binfile"], "binfile(1 类,0.17.142 源码核对)") + "\n" +
    """### tracker(3 个)

| 对象 | 进 | 出 | 信号进 | 信号出 | 来源 |
|---|---|---|---|---|---|
| `instr` | 1 | 1 | - | 0 | 静态表 |
| `modseq` | 1 | 3 | - | - | 静态表 |
| `smp~` | 1 | 2 | - | 0 | 静态表 |
""")

# insert creb/fftease/iemlib before ## 3 (after the small-lib blocks)
marker = "\n## 3. help 声明异常"
idx = text.find(marker)
if idx < 0:
    text += notes
else:
    text = text[:idx] + "\n" + table(creb, "creb(%d 类,0.17.142 源码核对)" % len(creb)) + "\n" + \
        table(fftease, "fftease(%d 类,0.17.142 源码核对;PD 分支显式建口)" % len(fftease)) + "\n" + \
        table(iemlib, "iemlib(%d 行,0.17.142 源码核对;含别名行)" % len(iemlib)) + \
        notes + text[idx:]

open(DOC, "w", encoding="utf-8", newline="\n").write(text)
print("sections written; size:", len(text))
