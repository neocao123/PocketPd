package com.pocketpd.app.patch

/**
 * Best-effort knowledge about Pd object classes: inlet/outlet counts, signal
 * ports and iemgui geometry. This only drives drawing and interaction; the
 * patch file itself is always round-tripped verbatim (see [PatchDocument]).
 * Unknown objects default to one inlet and one outlet.
 */
object PdObjectMeta {

    private class Def(
        val inlets: Int,
        val outlets: Int,
        val sigIn: Set<Int> = emptySet(),
        val sigOut: Set<Int> = emptySet(),
    )

    private fun d(inlets: Int, outlets: Int, vararg sigOut: Int) =
        Def(inlets, outlets, emptySet(), sigOut.toSet())

    private fun ds(inlets: Int, outlets: Int, sigIn: IntRange, sigOut: IntRange) =
        Def(inlets, outlets, sigIn.toSet(), sigOut.toSet())

    /** First numeric token (1-based "n" style args), [def] when absent/invalid. */
    private fun num(args: List<String>, i: Int = 0, def: Int): Int =
        args.getOrNull(i)?.toIntOrNull()?.takeIf { it > 0 } ?: def

    /** expr family: one inlet per $f/$v variable in the expression text. */
    private fun exprInlets(a: List<String>): Int =
        (a.flatMap {
            Regex("\\$(f|v)(\\d+)").findAll(it).mapNotNull { m ->
                m.groupValues[2].toIntOrNull()
            }
        }.maxOrNull() ?: 1).coerceIn(1, 100)

    /**
     * Variable-port classes: creation arguments decide inlet/outlet counts.
     * Consulted BEFORE the static [defs] table (a fixed row must never shadow
     * these). Each entry receives the tokens AFTER the class name and returns
     * the full port layout. Counts/defaults were MEASURED against this app's
     * own pd instance (probe canvases wired past the real ports, decoded from
     * pd's "(a->b) connection failed" load lines), so Max-era defaults come
     * from the SHIPPED cyclone/zexy/else builds — not from upstream repos.
     */
    private val variadic: Map<String, (List<String>) -> Def> = buildMap {
        fun reg(vararg names: String, def: (List<String>) -> Def) {
            for (n in names) put(n, def)
        }
        // -- vanilla (pd 0.51 x_connective/x_time/d_soundfile semantics) -----
        // bare [trigger] = t b b: two outlets
        reg("t", "trigger") { a -> d(1, (a.size).coerceAtLeast(2)) }
        reg("pack") { a -> d((a.size).coerceAtLeast(2), 1) }
        reg("unpack") { a -> d(1, (a.size).coerceAtLeast(2)) }
        // single-match route/sel grow the cold match-value inlet (pd
        // route_new/select_new: argc<=1 → float/symbolinlet)
        reg("route") { a ->
            val n = a.dropWhile { it.startsWith("-") }.size
            d(if (n <= 1) 2 else 1, n.coerceAtLeast(1) + 1)
        }
        reg("select", "sel") { a ->
            val n = a.size
            d(if (n <= 1) 2 else 1, n.coerceAtLeast(1) + 1)
        }
        reg("dac~") { a ->
            val n = (a.size).coerceAtLeast(2)
            ds(n, 0, 0 until n, IntRange.EMPTY)
        }
        reg("adc~") { a ->
            val n = (a.size).coerceAtLeast(2)
            ds(0, n, IntRange.EMPTY, 0 until n)
        }
        // pipe: a trailing numeric token is the default delay, not a slot;
        // bare [pipe] still shows two inlets (float + bang slot)
        reg("pipe") { a ->
            val slots = a.dropLastWhile { it.toFloatOrNull() != null }
            d((slots.size).coerceAtLeast(2), 1)
        }
        // expr family: one inlet per $f/$v variable in the expression; the ~
        // variants take every inlet as signal
        reg("expr") { a -> ds(exprInlets(a), 1, IntRange.EMPTY, IntRange.EMPTY) }
        reg("expr~", "fexpr~") { a ->
            val inlets = exprInlets(a)
            ds(inlets, 1, 0 until inlets, 0..0)
        }
        // sigmund~: every keyword argument creates one message outlet
        reg("sigmund~") { a ->
            val nout = a.count {
                it == "pitch" || it == "env" || it == "note" ||
                    it == "notes" || it == "peaks" || it == "tracks"
            }
            ds(1, if (nout > 0) nout else 2, 0..0, IntRange.EMPTY)
        }
        // readsf~: message outlets + rightmost end-of-file bang; writesf~:
        // one signal inlet per channel
        reg("readsf~") { a -> d(1, num(a, 0, 1).coerceAtMost(64) + 1) }
        reg("writesf~") { a ->
            val n = num(a, 0, 1).coerceAtMost(64)
            ds(n, 0, 0 until n, IntRange.EMPTY)
        }
        // TCP (default) echoes data on a second outlet; udp has only status
        reg("netreceive") { a ->
            val udp = a.any { it == "1" || it == "udp" || it == "-u" }
            d(0, if (udp) 1 else 2)
        }
        // -- cyclone (bare names resolve to cyclone; see Slibs PRIORITY) -----
        reg("gate") { a -> d(2, num(a, 0, 1)) }
        reg("switch") { a -> d(num(a, 0, 2) + 1, 1) }
        // bondo/buddy hold N inlets and echo through N outlets (measured 4/4)
        reg("Bondo", "bondo", "Buddy", "buddy") { a ->
            val n = (a.getOrNull(0)?.toIntOrNull() ?: 2).coerceAtLeast(2)
            d(n, n)
        }
        reg("Bucket", "bucket", "Cycle", "cycle") { a -> d(1, num(a, 0, 1)) }
        reg("Decode", "decode") { a -> d(3, num(a, 0, 1)) }
        reg("Funnel", "funnel") { a -> d(num(a, 0, 2), 1) }
        reg("Spray", "spray") { a -> d(1, num(a, 0, 2)) }
        // -- zexy -------------------------------------------------------------
        reg("relay") { a -> d(1, a.size.coerceAtLeast(1) + 1) }
        // 0.17.142 zexy audit: mux/multiplex expand via CREATION ARGS (one
        // token per channel), not a resize message — in = max(argc,2)+1
        // (proxy msg inlets + rightmost float selector; CLASS_NOINLET kills
        // the default inlet), demux = 2 in (main + float selector), out per arg
        reg("mux", "multiplex") { a -> d(a.size.coerceAtLeast(2) + 1, 1) }
        reg("mux~", "multiplex~") { a ->
            val n = a.size.coerceAtLeast(2)
            ds(n, 1, 0 until n, 0 until 1)
        }
        reg("demux", "demultiplex") { a -> d(2, a.size.coerceAtLeast(2)) }
        reg("demux~", "demultiplex~") { a ->
            val n = a.size.coerceAtLeast(2)
            ds(1, n, 0 until 1, 0 until n)
        }
        // packel: one float inlet + one outlet per creation arg
        reg("packel") { a -> val n = a.size.coerceAtLeast(1); d(1 + n, n) }
        // multiline~: n = max(argc-1,1) signal in/out pairs + 2 float inlets
        reg("multiline~") { a ->
            val n = (a.size - 1).coerceAtLeast(1)
            ds(n + 2, n, 0 until n, 0 until n)
        }
        // limiter~: one signal inlet per channel arg (cap 64), one signal out
        reg("limiter~") { a ->
            val n = a.size.coerceAtLeast(1).coerceAtMost(64)
            ds(n, 1, 0 until n, 0 until 1)
        }
        // sfplay/sfrecord: channel count from the first arg (1,2,4,8 kept;
        // 3->2; 5-7->7; anything else 1). sfplay: 2 control inlets, c signal
        // outlets + trailing end-of-file bang; sfrecord: c signal inlets,
        // one float status outlet
        reg("sfplay") { a ->
            val c = when (a.firstOrNull()?.toIntOrNull()) {
                1, 2, 4, 8 -> a.first().toInt()
                3 -> 2
                in 5..7 -> 7
                else -> 1
            }
            Def(2, c + 1, emptySet(), (0 until c).toSet())
        }
        reg("sfrecord") { a ->
            val c = when (a.firstOrNull()?.toIntOrNull()) {
                1, 2, 4, 8 -> a.first().toInt()
                3 -> 2
                in 5..7 -> 7
                else -> 1
            }
            Def(c, 1, (0 until c).toSet(), setOf(0))
        }
        // -- else -------------------------------------------------------------
        // gate~: selector + signal inlet fixed at two; routes to n outlets
        reg("gate~") { a ->
            val n = num(a, 0, 1)
            ds(2, n, 1..1, 0 until n)
        }
        // select~: selector + n signal inlets -> one signal outlet
        reg("select~") { a ->
            // 0.17.143 else audit: inlet 0 IS signal (CLASS_MAINSIGNALIN),
            // the selector float rides it - sig in = 0..n
            val n = num(a, 0, 1)
            ds(n + 1, 1, 0..n, 0..0)
        }
        reg("selector") { a -> d((a.getOrNull(0)?.toIntOrNull() ?: 2).coerceAtLeast(2) + 1, 1) }
        reg("router") { a -> d(2, num(a, 0, 2)) }
        // unmerge clamps the split count to >=2 (beta21 unmerge.c:91):
        // bare [unmerge] has THREE outlets, not two
        reg("unmerge") { a -> d(2, num(a, 0, 1).coerceAtLeast(2) + 1) }
        reg("pack2") { a -> d((a.size).coerceAtLeast(2), 1) }
        // xgate~/xselect~ families route n signal channels; n comes from the
        // first creation arg. 0.17.143 audit: in this vintage the selector
        // float rides the signal inlet's signalscalar, not a cold inlet
        reg("xgate~") { a ->
            // ONE signal inlet in this vintage (beta21 xgate~.c:123): the
            // selector float rides the inlet's signalscalar
            val n = num(a, 0, 1)
            ds(1, n, 0..0, 0 until n)
        }
        reg("xselect~") { a ->
            // n signal inlets, selector float rides inlet 0 (beta21
            // xselect~.c:114): bare [xselect~] is 1 sig in / 1 sig out
            val n = num(a, 0, 1).coerceAtLeast(1)
            ds(n, 1, 0 until n, 0..0)
        }
        reg("xselect2~") { a ->
            // ONE signal outlet in this vintage (beta21 xselect2~.c:70):
            // 1+n signal inlets (n arg, min 2) -> 1 signal out
            val n = num(a, 0, 2).coerceAtLeast(2)
            ds(n + 1, 1, 0..n, 0..0)
        }
        // 0.17.143 else audit: variable-io classes whose bare default does
        // NOT match the 1-in/1-out heuristic
        reg("autofade~", "autofade2~") { a ->
            val c = num(a, 1, 1).coerceAtLeast(1)
            Def(1 + c, c + 1, (0 until 1 + c).toSet(), (0 until c).toSet())
        }
        reg("hot") { a -> val n = num(a, 0, 2).coerceAtLeast(2); d(n, n) }
        reg("match~") { a ->
            val outs = if (a.isEmpty()) 2 else a.size + 1
            Def(1, outs, setOf(0), (0 until outs).toSet())
        }
        reg("merge") { a -> d(a.size.coerceAtLeast(2), 1) }
        reg("rec~") { a ->
            // channels of a named array are engine-side; bare [rec~] has no
            // array and zero outlets
            if (a.isEmpty()) Def(1, 0, setOf(0), emptySet())
            else Def(1, 1, setOf(0), setOf(0))
        }
        reg("routeall") { a -> d(1, a.size.coerceAtLeast(1) + 1) }
        reg("t2", "trigger2") { a -> d(1, a.size.coerceAtLeast(2)) }
        reg("spread~") { a ->
            val n = num(a, 0, 2).coerceAtLeast(2)
            ds(n, n, 0 until n, 0 until n)
        }
        reg("xgate2~") { a ->
            val n = num(a, 0, 2).coerceAtLeast(2)
            ds(1, n, 0..0, 0 until n)
        }
        reg("chance") { a ->
            if (a.size >= 2) d(1, a.size) else d(2, 2)
        }
        reg("format") { a ->
            // one inlet per %-slot, the first slot rides inlet 0
            val slots = a.sumOf { tok -> tok.count { c -> c == '%' } }
            d(slots.coerceAtLeast(1), 1)
        }
        reg("limit") { a ->
            if (num(a, 1, 0) > 0) d(2, 2) else d(2, 1)
        }
        reg("route2", "spread") { a ->
            if (a.isEmpty()) d(1, 2) else d(1, a.size + 1)
        }
        // -- iemlib / mrpeach --------------------------------------------------
        reg("iem_route") { a ->
            val n = a.size
            d(if (n <= 1) 2 else 1, n.coerceAtLeast(1) + 1)
        }
        reg("routeOSC") { a -> d(1, if (a.isEmpty()) 1 else a.size + 1) }
    }

    private val defs: Map<String, Def> = buildMap {
        // -- control basics ------------------------------------------------
        put("loadbang", d(0, 1)); put("bang", d(1, 1)); put("b", d(1, 1))
        // float/int: bang fires on the left, the RIGHT inlet sets without
        // firing — two inlets, like real pd
        put("float", d(2, 1)); put("f", d(2, 1)); put("int", d(2, 1)); put("i", d(2, 1))
        put("moses", d(2, 2)); put("change", d(1, 1)) // until: 2-in/1-out, defined below
        put("spigot", d(2, 1)); put("del", d(2, 1)); put("delay", d(2, 1))
        put("metro", d(2, 1)); put("line", d(2, 1)); put("timer", d(2, 1))
        // zexy time/date: one default inlet (bang prints). time emits hour/
        // min/sec/msec, date year/month/day/weekday/day-of-year/dst — four
        // and six float outlets (zexy src time.c/date.c, checked 0.17.140)
        put("time", d(1, 4)); put("date", d(1, 6))
        put("random", d(2, 1)); put("abs", d(1, 1)); put("mod", d(2, 1))
        put("div", d(2, 1)); put("pow", d(2, 1)); put("max", d(2, 1)); put("min", d(2, 1))
        put("gt", d(2, 1)); put("lt", d(2, 1)); put("ge", d(2, 1))
        put("le", d(2, 1)); put("eq", d(2, 1)); put("ne", d(2, 1))
        put("exp", d(1, 1)); put("log", d(2, 1)); put("sqrt", d(1, 1))
        // wrap: the slibs pre-load SHADOWS vanilla's wrap (pd renames the
        // first-registered class <name>_aliased), so the bare name is zexy's
        // 2-inlet form (main + float "set"), not vanilla's
        put("stripnote", d(1, 2)); put("poly", d(2, 3)); put("makefilename", d(1, 1))
        // value: the table below is the NAMED form (1 inlet); the nameless
        // `value` / `send` gain a symbol-setter inlet — handled in
        // resolveObject (pd value_new/send_new guard on an empty name)
        put("value", d(1, 1)); put("v", d(1, 1)); put("print", d(1, 0))
        put("table", d(0, 0))
        put("send", d(1, 0)); put("s", d(1, 0)); put("receive", d(0, 1)); put("r", d(0, 1))
        // canvas ports: [inlet] only emits into the patch (its own outlet),
        // [outlet] only receives from it (its own inlet)
        put("inlet", d(0, 1)); put("outlet", d(1, 0))
        put("inlet~", d(0, 1, 0)); put("outlet~", Def(1, 0, setOf(0)))
        put("list", d(1, 1))
        put("list append", d(2, 1)); put("list prepend", d(2, 1))
        put("list split", d(2, 2)); put("list trim", d(1, 1)); put("list length", d(1, 1))
        put("list store", d(2, 1)); put("list fromsymbol", d(1, 1)); put("list tosymbol", d(1, 1))
        // arithmetic with a cold inlet
        for (op in listOf("+", "-", "*", "/", "%", ">>", "<<", "&&", "||", "|")) {
            put(op, d(2, 1))
        }
        for (op in listOf(">", "<", ">=", "<=", "==", "!=")) put(op, d(2, 1))
        // -- MIDI -----------------------------------------------------------
        put("notein", d(0, 3)); put("ctlin", d(0, 3)); put("polytouchin", d(0, 3))
        put("pgmin", d(0, 2)); put("bendin", d(0, 2)); put("touchin", d(0, 2))
        put("midiin", d(0, 2)); put("sysexin", d(0, 2)); put("midirealtimein", d(0, 2))
        put("noteout", d(3, 0)); put("ctlout", d(3, 0)); put("polytouchout", d(3, 0))
        put("pgmout", d(2, 0)); put("bendout", d(2, 0)); put("touchout", d(2, 0))
        put("midiout", d(2, 0))
        // -- signal objects --------------------------------------------------
        put("osc~", Def(2, 1, setOf(0), setOf(0))); put("phasor~", Def(1, 1, setOf(0), setOf(0))); put("noise~", d(0, 1, 0))
        put("sig~", d(1, 1, 0)); put("adc~", d(0, 2)); put("dac~", Def(2, 0, setOf(0, 1)))
        put("+~", Def(2, 1, setOf(0, 1), setOf(0))); put("-~", Def(2, 1, setOf(0, 1), setOf(0))); put("*~", Def(2, 1, setOf(0, 1), setOf(0))); put("/~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("max~", Def(2, 1, setOf(0, 1), setOf(0))); put("min~", Def(2, 1, setOf(0, 1), setOf(0))); put("clip~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("rsqrt~", Def(2, 1, setOf(0), setOf(0))); put("sqrt~", Def(1, 1, setOf(0), setOf(0))); put("wrap~", Def(1, 1, setOf(0), setOf(0)))
        put("mtof~", Def(1, 1, setOf(0), setOf(0))); put("ftom~", Def(1, 1, setOf(0), setOf(0)))
        put("dbtorms~", Def(1, 1, setOf(0), setOf(0))); put("rmstodb~", Def(1, 1, setOf(0), setOf(0)))
        put("dbtopow~", Def(1, 1, setOf(0), setOf(0))); put("powtodb~", Def(1, 1, setOf(0), setOf(0)))
        put("rmstofad~", Def(1, 1, setOf(0), setOf(0))); put("fadtorms~", Def(1, 1, setOf(0), setOf(0)))
        put("line~", d(1, 1, 0)); put("vline~", d(1, 1, 0))
        put("snapshot~", Def(2, 1, setOf(0), emptySet())); put("env~", Def(1, 1, setOf(0), emptySet()))
        // threshold~ on pd 0.51: signal + cold float inlet, ONE bang outlet
        // (the second outlet arrived in later pd)
        put("threshold~", Def(2, 1, setOf(0), emptySet()))
        put("samphold~", Def(2, 1, setOf(0, 1), setOf(0))); put("print~", Def(1, 0, setOf(0), emptySet()))
        put("bang~", Def(1, 1, setOf(0), emptySet())) // the inlet is a signal port
        put("block~", d(1, 0))
        // vanilla switch~ (dsp on/off) is fixed one-in/zero-out; the
        // variable-inlet Max-style router here is cyclone's [switch]
        put("switch~", d(1, 0))
        put("send~", Def(1, 0, setOf(0), emptySet())); put("s~", Def(1, 0, setOf(0), emptySet()))
        put("receive~", d(0, 1, 0)); put("r~", d(0, 1, 0)); put("catch~", d(0, 1, 0))
        put("throw~", Def(1, 0, setOf(0), emptySet()))
        put("delwrite~", Def(1, 0, setOf(0), emptySet())); put("delread~", d(1, 1, 0))
        put("delread4~", Def(1, 1, setOf(0), setOf(0))); put("vd~", Def(1, 1, setOf(0), setOf(0)))
        put("tabwrite~", Def(1, 0, setOf(0), emptySet())); put("tabread~", Def(1, 1, setOf(0), setOf(0))); put("tabread4~", Def(2, 1, setOf(0), setOf(0)))
        // tabosc4~: cold float phase inlet; tabplay~: bang outlet at end of
        // playback; tabreceive~: cold "set" inlet 0 — all single-signal-out
        put("tabosc4~", Def(2, 1, emptySet(), setOf(0)))
        put("tabplay~", Def(1, 2, emptySet(), setOf(0)))
        put("tabreceive~", Def(1, 1, emptySet(), setOf(0)))
        put("tabsend~", Def(1, 0, setOf(0), emptySet()))
        // tabread/tabread4: the table is switched by a "set" message, one inlet
        put("tabread", d(1, 1)); put("tabread4", d(1, 1)); put("tabwrite", d(2, 0))
        // soundfiler: right outlet carries the second return value (resize head)
        put("soundfiler", d(1, 2)); put("arraysize", d(1, 1))
        // the coefficient inlet of rpole~/rzero~ is a COLD float, not signal
        put("rpole~", Def(2, 1, setOf(0), setOf(0))); put("rzero~", Def(2, 1, setOf(0), setOf(0)))
        put("rzero_rev~", Def(2, 1, setOf(0), setOf(0)))
        put("cpole~", d(2, 1, 0)); put("czero~", d(2, 1, 0))

        // -- full vanilla 0.51 class audit: everything whose port count or
        //    signal layout differs from the 1-in/1-out default ---------------
        put("atan2", d(2, 1))
        put("&", d(2, 1))
        put("swap", d(2, 2))
        put("until", d(2, 1))
        put("clip", d(3, 1))
        put("bag", d(2, 1))
        put("makenote", d(3, 2))
        // netsend: 1 inlet (commands/data); TCP (default) echoes received
        // data on a second outlet, UDP has only the status outlet
        put("netsend", d(1, 2))
        put("netreceive", d(0, 2))
        put("key", d(0, 1)); put("keyup", d(0, 1)); put("keyname", d(0, 2))
        put("openpanel", d(0, 1)); put("savepanel", d(0, 1))
        // [symbol] object: right inlet stores without firing, like [float]
        put("symbol", d(2, 1))
        put("midirealtimein", d(0, 2))
        put("realtime", d(2, 1))
        put("cputime", d(2, 1))
        put("stdout", d(1, 0))
        put("pdcontrol", d(1, 1))
        put("savestate", d(1, 2))
        // qlist/textfile: read/print/tempo messages ride the LEFT inlet;
        // outlets are the emitted messages + the end-of-file bang
        put("textfile", d(1, 2))
        put("qlist", d(1, 2))
        put("text define", d(0, 2))
        put("cos~", Def(1, 1, setOf(0), setOf(0)))
        // hip~/lop~: cold float frequency inlet; unary math on signals
        put("hip~", Def(2, 1, setOf(0), setOf(0))); put("lop~", Def(2, 1, setOf(0), setOf(0)))
        put("pow~", Def(1, 1, setOf(0), setOf(0))); put("abs~", Def(1, 1, setOf(0), setOf(0)))
        put("%~", Def(2, 1, setOf(0, 1), setOf(0))); put("/~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("log~", Def(2, 1, setOf(0, 1), setOf(0))); put("exp~", Def(1, 1, setOf(0), setOf(0))); put("atan2~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("fft~", Def(1, 1, setOf(0), setOf(0))); put("ifft~", Def(1, 1, setOf(0), setOf(0)))
        put("rfft~", Def(1, 1, setOf(0), setOf(0))); put("rifft~", Def(1, 1, setOf(0), setOf(0))); put("framp~", Def(1, 2, setOf(0), setOf(0, 1)))
        // biquad~: coefficients ride "set" messages, one signal inlet;
        // slop~ has five signal inlets (sig, maxrise, maxfall, slope, delay)
        put("biquad~", Def(1, 1, setOf(0), setOf(0))); put("bp~", Def(3, 1, setOf(0), setOf(0)))
        put("slop~", Def(5, 1, setOf(0, 1, 2, 3, 4), setOf(0)))
        put("vcf~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("samplerate~", d(0, 1, 0))
        put("cpole~", Def(4, 2, setOf(0, 1), setOf(0, 1)))
        put("czero~", Def(3, 2, setOf(0), setOf(0, 1)))
        put("czero_rev~", Def(3, 2, setOf(0), setOf(0, 1)))
        // tracker library (bundled slib, see slibs/tracker)
        put("modseq", d(1, 3)); put("smp~", d(1, 2, 0)); put("instr", d(1, 1, 0))
        // pocketpd library (bundled slib, see slibs/pocketpd)
        // ym2612~: outlet 0 = six-channel chip mix, 1..6 = channels 1-6
        put("ym2612~", d(2, 7, 0, 1, 2, 3, 4, 5, 6))
        // sn76489~: 1 control inlet (raw PSG byte writes); outlet 0 =
        // four-channel sum, 1..4 = tone 1-3 / noise
        put("sn76489~", d(1, 5, 0, 1, 2, 3, 4))
        // vgm~: 1 control inlet; outlet 0 = `ch<k> reg <a> <d>` writes for
        // the single ym2612~, 1 = PSG/GG/AY/OPL3, 2 = DAC signal, 3 = end bang
        put("vgm~", d(1, 4, 2))
        // opl3~: inlet 0 = control (notes/params/raw reg writes, `opl`
        // alias for vgm~'s stream), inlet 1 = gate; outlet 0 = left sum,
        // 1 = right sum (C0 pan bits), 2..19 = chip channels 1-18 alone
        put("opl3~", d(2, 20, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                       13, 14, 15, 16, 17, 18, 19))
        // nes~: inlet 0 = control (voice layer + `nes`/`reg` raw writes),
        // inlet 1 = gate (ch1); outlet 0 = the chip's own mix, 1..5 =
        // channels 1-5 (pulse1/pulse2/triangle/noise/DPCM) alone
        put("nes~", d(2, 6, 0, 1, 2, 3, 4, 5))
        // nsf~: 1 control inlet; outlet 0 = `nes <reg> <val>` stream for
        // nes~, 1 = DPCM signal, 2 = end bang
        put("nsf~", d(1, 3, 1))
        // xm~: 1 control inlet (load/play/...); outlets 0/1 = stereo mix
        // L/R, 2..33 = module channels 1-32 in isolation, 34 = end bang
        put("xm~", d(1, 35, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
                     14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
                     27, 28, 29, 30, 31, 32, 33, 34))
        // mpein: 1 control inlet (raw midi list / clear / bendrange /
        // status); outlet 0 = selector-ized voice events (note/pitch/
        // slide/press/midi/zone)
        put("mpein", d(1, 1))
        // sid6581~: 1 control inlet (notes + raw reg writes); outlet 0 =
        // the chip's AUDIO OUT (filter + external filter), 1..3 = voices
        put("sid6581~", d(1, 4, 0, 1, 2, 3))
        // sid~: 1 control inlet; outlet 0 = `reg <a> <d>` writes (+ clock/
        // model on load) for the single sid6581~; no signal ports - the
        // replay is driven from the dsp block callback
        put("sid~", d(1, 1))
        // fixed-port rows MEASURED against this app's pd (probe canvases +
        // "(a->b) connection failed" decode): cyclone/zexy/else classes whose
        // counts the tilde heuristic or the 1-in/1-out default gets wrong.
        // Bare names resolve per Slibs PRIORITY (cyclone > zexy > iemlib).
        put("uzi", d(2, 3)); put("Uzi", d(2, 3))
        put("counter", d(5, 4))
        put("coll", d(1, 4))
        put("match", d(1, 1))
        // cyclone split wins the bare name over iemlib's (loads later)
        put("split", d(3, 2))
        put("Peak", d(2, 2)); put("peak", d(2, 2))
        put("Trough", d(2, 2)); put("trough", d(2, 2))
        put("zl", d(2, 2))
        // zexy mux/demux expand via "n <count>" messages, NOT creation args
        put("mux", d(3, 1)); put("multiplex", d(3, 1))
        put("demux", d(2, 2)); put("demultiplex", d(2, 2))
        put("niagara", d(2, 2)); put("packel", d(2, 1))
        put("voices", d(2, 2))
        put("route~", Def(1, 2, setOf(0), setOf(0)))
        put("pan4~", Def(3, 4, setOf(0, 1, 2), setOf(0, 1, 2, 3)))
        // list split: first-n / remainder / end-of-list bang (x_list.c)
        put("list split", d(2, 3))
        // -- else compiled classes ------------------------------------------
        // port counts declared by the bundled help patches' inlets/outlets
        // sections (contiguous cnv port markers), cross-checked to names
        // else_core.so registers and no other bundled lib claims. The .pd
        // abstractions of the same library are NOT here - their ports come
        // from the AbstractionPorts scan of their inlet/outlet objects.
        // signal-port marks follow the help text ("float/signal -"); counts
        // are the authoritative part.
        put("above~", ds(2, 2, 0 until 2, 0 until 2))
        put("add~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("adsr~", Def(5, 2, setOf(0, 1, 2, 3, 4), setOf(0)))
        put("allpass.2nd~", ds(3, 1, 0 until 3, 0 until 1))
        put("allpass.rev~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("asr~", Def(3, 2, setOf(0, 1, 2), setOf(0)))
        put("bandpass~", ds(3, 1, 0 until 3, 0 until 1))
        put("bandstop~", ds(3, 1, 0 until 3, 0 until 1))
        put("bend.in", Def(2, 2, emptySet(), emptySet()))
        put("bend.out", Def(2, 1, emptySet(), emptySet()))
        put("bicoeff", Def(3, 1, emptySet(), emptySet()))
        put("biquads~", ds(1, 1, 0 until 1, 0 until 1))
        put("blocksize~", Def(1, 1, setOf(0), emptySet()))
        put("brown~", Def(1, 1, emptySet(), setOf(0)))
        put("ceil", Def(1, 1, emptySet(), emptySet()))
        put("ceil~", Def(1, 1, setOf(0), setOf(0)))
        put("cents2ratio", Def(1, 1, emptySet(), emptySet()))
        put("cents2ratio~", Def(1, 1, setOf(0), setOf(0)))
        put("changed", Def(2, 2, emptySet(), emptySet()))
        put("changed2~", Def(1, 2, setOf(0), setOf(0, 1)))
        put("changed~", ds(2, 1, 0 until 2, 0 until 1))
        put("clipnoise~", Def(1, 1, emptySet(), setOf(0)))
        put("coin", Def(2, 2, emptySet(), emptySet()))
        put("coin~", ds(2, 2, 0 until 2, 0 until 2))
        put("colors", Def(1, 1, emptySet(), emptySet()))
        put("comb.filt~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("cosine~", ds(3, 1, 0 until 3, 0 until 1))
        put("crackle~", Def(1, 1, setOf(0), setOf(0)))
        put("crossover~", ds(2, 2, 0 until 2, 0 until 2))
        put("ctl.in", Def(2, 3, emptySet(), emptySet()))
        put("ctl.out", Def(3, 1, emptySet(), emptySet()))
        put("cusp~", ds(1, 1, 0 until 1, 0 until 1))
        put("decay2~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("decay~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("detect~", ds(1, 1, 0 until 1, 0 until 1))
        put("dir", Def(1, 3, emptySet(), emptySet()))
        put("dollarzero", Def(1, 1, emptySet(), emptySet()))
        put("downsample~", ds(2, 1, 0 until 2, 0 until 1))
        put("drive~", ds(2, 1, 0 until 2, 0 until 1))
        put("dust2~", Def(1, 1, setOf(0), setOf(0)))
        put("dust~", Def(1, 1, setOf(0), setOf(0)))
        put("envgen~", Def(2, 2, setOf(0), setOf(0)))
        put("eq~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("fader~", ds(1, 1, 0 until 1, 0 until 1))
        put("fbdelay~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("fbsine2~", ds(1, 1, 0 until 1, 0 until 1))
        put("fbsine~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("fdn.rev~", Def(3, 2, emptySet(), setOf(0, 1)))
        put("ffdelay~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("float2bits", Def(2, 1, emptySet(), emptySet()))
        put("float2sig~", Def(2, 1, emptySet(), setOf(0)))
        put("floor", Def(1, 1, emptySet(), emptySet()))
        put("floor~", Def(1, 1, setOf(0), setOf(0)))
        put("fold", Def(3, 1, emptySet(), emptySet()))
        put("fold~", ds(3, 1, 0 until 3, 0 until 1))
        put("freq.shift~", ds(2, 1, 0 until 2, 0 until 1))
        put("fromany", Def(1, 1, emptySet(), emptySet()))
        put("function~", Def(1, 1, setOf(0), setOf(0)))
        put("gate2imp~", ds(1, 1, 0 until 1, 0 until 1))
        put("gbman~", ds(1, 1, 0 until 1, 0 until 1))
        put("giga.rev~", Def(1, 2, setOf(0), setOf(0, 1)))
        put("glide2~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("glide~", ds(2, 1, 0 until 2, 0 until 1))
        put("gray~", Def(1, 1, emptySet(), setOf(0)))
        put("henon~", ds(1, 1, 0 until 1, 0 until 1))
        put("highpass~", ds(3, 1, 0 until 3, 0 until 1))
        put("highshelf~", ds(4, 1, 0 until 4, 0 until 1))
        put("hz2rad", Def(1, 1, emptySet(), emptySet()))
        put("hz2rad~", Def(1, 1, setOf(0), setOf(0)))
        put("ikeda~", ds(1, 2, 0 until 1, 0 until 2))
        put("impseq~", Def(1, 1, setOf(0), setOf(0)))
        put("impulse2~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("impulse~", ds(3, 1, 0 until 3, 0 until 1))
        put("initmess", Def(1, 1, emptySet(), emptySet()))
        put("int~", ds(1, 1, 0 until 1, 0 until 1))
        put("lastvalue~", ds(1, 1, 0 until 1, 0 until 1))
        put("latoocarfian~", ds(1, 1, 0 until 1, 0 until 1))
        put("lfnoise~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("lincong~", ds(1, 1, 0 until 1, 0 until 1))
        put("logistic~", ds(2, 1, 0 until 2, 0 until 1))
        put("lorenz~", ds(1, 1, 0 until 1, 0 until 1))
        put("lowpass~", ds(3, 1, 0 until 3, 0 until 1))
        put("lowshelf~", ds(4, 1, 0 until 4, 0 until 1))
        put("median~", Def(2, 1, setOf(0), setOf(0)))
        put("midi", Def(1, 2, emptySet(), emptySet()))
        put("mov.avg~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("mov.rms~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("nbang", Def(2, 2, emptySet(), emptySet()))
        put("note.in", Def(2, 4, emptySet(), emptySet()))
        put("note.out", Def(4, 1, emptySet(), emptySet()))
        put("nyquist~", Def(1, 1, setOf(0), emptySet()))
        put("pan2~", ds(2, 2, 0 until 2, 0 until 2))
        put("panic", Def(1, 1, emptySet(), emptySet()))
        put("parabolic~", ds(3, 1, 0 until 3, 0 until 1))
        put("peak~", Def(1, 1, setOf(0), emptySet()))
        put("pgm.in", Def(2, 2, emptySet(), emptySet()))
        put("pimp~", ds(3, 2, 0 until 3, 0 until 2))
        put("pinknoise~", Def(2, 1, emptySet(), setOf(0)))
        put("pluck~", Def(5, 1, setOf(0, 1, 2, 3, 4), setOf(0)))
        put("pmosc~", ds(4, 1, 0 until 4, 0 until 1))
        put("pulsecount~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("pulsediv~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("pulse~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("quad~", ds(1, 1, 0 until 1, 0 until 1))
        put("quantizer", Def(2, 1, emptySet(), emptySet()))
        put("quantizer~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("rad2hz", Def(1, 1, emptySet(), emptySet()))
        put("rad2hz~", Def(1, 1, setOf(0), setOf(0)))
        put("rampnoise~", ds(1, 1, 0 until 1, 0 until 1))
        put("rand.f", Def(3, 1, emptySet(), emptySet()))
        put("rand.f~", ds(3, 1, 0 until 3, 0 until 1))
        put("rand.i", Def(3, 1, emptySet(), emptySet()))
        put("rand.i~", ds(3, 1, 0 until 3, 0 until 1))
        put("rand.seq", Def(2, 2, emptySet(), emptySet()))
        put("randpulse2~", Def(1, 1, setOf(0), setOf(0)))
        put("randpulse~", ds(3, 1, 0 until 3, 0 until 1))
        put("ratio2cents", Def(1, 1, emptySet(), emptySet()))
        put("ratio2cents~", Def(1, 1, setOf(0), setOf(0)))
        put("receiver", Def(1, 1, emptySet(), emptySet()))
        put("rec~", Def(1, 1, emptySet(), setOf(0)))
        put("rescale", Def(1, 1, emptySet(), emptySet()))
        put("rescale~", ds(1, 1, 0 until 1, 0 until 1))
        put("resonant2~", ds(4, 1, 0 until 4, 0 until 1))
        put("resonant~", ds(3, 1, 0 until 3, 0 until 1))
        put("rint", Def(2, 1, emptySet(), emptySet()))
        put("saw2~", ds(3, 1, 0 until 3, 0 until 1))
        put("saw~", ds(3, 1, 0 until 3, 0 until 1))
        put("schmitt~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("separate", Def(1, 1, emptySet(), emptySet()))
        put("sequencer~", Def(1, 1, setOf(0), setOf(0)))
        put("shaper~", Def(1, 1, setOf(0), setOf(0)))
        put("sh~", ds(2, 1, 0 until 2, 0 until 1))
        put("sig2float~", Def(2, 1, setOf(0), emptySet()))
        put("sine~", ds(3, 1, 0 until 3, 0 until 1))
        put("slew~", Def(3, 1, setOf(0), setOf(0)))
        put("square~", ds(4, 1, 0 until 4, 0 until 1))
        put("sr~", Def(1, 1, setOf(0), emptySet()))
        put("standard~", ds(1, 1, 0 until 1, 0 until 1))
        put("status~", Def(1, 2, setOf(0), setOf(0, 1)))
        put("stepnoise~", ds(1, 1, 0 until 1, 0 until 1))
        put("suspedal", Def(3, 2, emptySet(), emptySet()))
        put("svfilter~", Def(3, 4, setOf(0, 1, 2), setOf(0, 1, 2)))
        put("table~", Def(1, 1, setOf(0), setOf(0)))
        put("tempo~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("timed.gate~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("toany", Def(2, 1, emptySet(), emptySet()))
        put("toggleff~", Def(1, 1, setOf(0), setOf(0)))
        put("trig.delay2~", ds(2, 1, 0 until 2, 0 until 1))
        put("trig.delay~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("trighold~", ds(1, 1, 0 until 1, 0 until 1))
        put("tri~", ds(3, 1, 0 until 3, 0 until 1))
        put("vsaw~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("vu~", Def(1, 2, setOf(0), emptySet()))
        put("wavetable~", ds(3, 1, 0 until 3, 0 until 1))
        put("window", Def(1, 1, emptySet(), emptySet()))
        put("wrap2", Def(3, 1, emptySet(), emptySet()))
        put("wrap2~", ds(3, 1, 0 until 3, 0 until 1))
        put("xmod2~", ds(4, 2, 0 until 4, 0 until 2))
        put("xmod~", ds(4, 2, 0 until 4, 0 until 2))
        put("zerocross~", ds(1, 3, 0 until 1, 0 until 3))
        // -- elsex: incremental else classes compiled in 0.17.134 (the hand-
        // integrated else_core.so predates upstream master). Port counts from
        // the vendored C sources' inlet/outlet constructor calls; classes
        // whose outlets scale with a creation arg (chance/spread/route2/
        // format) show their default.
        put("bitnormal~", Def(1, 1, setOf(0), setOf(0)))
        put("buffer", Def(2, 1, emptySet(), emptySet()))
        put("canvas.active", Def(1, 1, emptySet(), emptySet()))
        put("canvas.edit", Def(1, 1, emptySet(), emptySet()))
        put("canvas.setname", Def(1, 0, emptySet(), emptySet()))
        put("chance", Def(2, 5, emptySet(), emptySet()))
        put("dcblock~", Def(1, 1, setOf(0), setOf(0)))
        put("default", Def(2, 1, emptySet(), emptySet()))
        put("dollsym", Def(1, 1, emptySet(), emptySet()))
        put("format", Def(2, 1, emptySet(), emptySet()))
        put("gcd", Def(2, 1, emptySet(), emptySet()))
        put("keycode", d(0, 2))
        put("lag2~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("limit", Def(2, 2, emptySet(), emptySet()))
        put("mix~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("mpe.in", Def(2, 2, emptySet(), emptySet()))
        put("nchs~", Def(1, 1, setOf(0), emptySet()))
        put("noteinfo", Def(2, 2, emptySet(), emptySet()))
        put("op", Def(2, 1, emptySet(), emptySet()))
        put("op~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("osc.format", Def(1, 1, emptySet(), emptySet()))
        put("osc.parse", Def(1, 2, emptySet(), emptySet()))
        put("pimpmul~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("pipe2", Def(2, 1, emptySet(), emptySet()))
        put("power~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("ptouch.in", Def(2, 2, emptySet(), emptySet()))
        put("ptouch.out", Def(3, 1, emptySet(), emptySet()))
        put("resonator~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("resonator2~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("retrieve", Def(1, 1, emptySet(), emptySet()))
        put("route2", Def(1, 2, emptySet(), emptySet()))
        put("sign", Def(1, 1, emptySet(), emptySet()))
        put("sign~", Def(1, 1, setOf(0), setOf(0)))
        put("slice", Def(2, 2, emptySet(), emptySet()))
        put("spread", Def(1, 2, emptySet(), emptySet()))
        put("sum~", Def(1, 1, setOf(0), setOf(0)))
        put("tabplayer~", Def(1, 2, emptySet(), setOf(0)))
        put("tabreader~", Def(1, 1, setOf(0), setOf(0)))
        put("tabwriter~", Def(3, 2, setOf(0, 1, 2), setOf(0)))
        put("tanh", Def(1, 1, emptySet(), emptySet()))
        put("tanh~", Def(1, 1, setOf(0), setOf(0)))
        put("unique", Def(1, 2, emptySet(), emptySet()))
        put("var", Def(1, 1, emptySet(), emptySet()))
        put("white~", Def(1, 1, emptySet(), setOf(0)))
        put("xfade.mc~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        // 0.17.143 else audit additions: constructor counts of the shipped
        // else_core vintage (~1.0-beta19..21; where the bundled help declares
        // port markers, the help truth was kept - freq.shift~'s 2nd outlet is
        // a beta22+ change and stays off). Envelope/noise generators whose
        // params ride SIGNAL inlets get them marked; rms~/s2f~ output FLOATS.
        put("balance~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("cmul~", Def(4, 2, setOf(0, 1, 2, 3), setOf(0, 1)))
        put("comb.rev~", Def(5, 1, setOf(0, 1, 2, 3, 4), setOf(0)))
        put("flipflop~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("imp~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("imp2~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("rms~", Def(1, 1, setOf(0), emptySet()))
        put("rotate~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("s2f~", Def(2, 1, setOf(0), emptySet()))
        put("susloop~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("wt~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("xfade~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("message", d(2, 1)); put("pgm.out", d(2, 1)); put("pic", d(1, 0))
        put("note", d(1, 0)); put("openfile", d(1, 0))
        put("click", d(0, 1)); put("properties", d(0, 1))
        put("loop", d(1, 2))
        put("_active_gui", d(0, 0)); put("_format_proxy", d(0, 0))
        put("_handle", d(0, 0))
        // 0.17.144 supersaw~ (our own abstraction, upstream ELSE has none):
        // 7x vsaw~ voices -> per-voice expr/sig~ detune ratios -> pan2~
        // spread. inlet 0 = frequency (signal), inlet 1 = detune float,
        // outlets = left/right signal.
        put("supersaw~", Def(2, 2, setOf(0), setOf(0, 1)))
        // -- 0.17.142 library-wide io audit ----------------------------------
        // Every row below is the constructor's own io (pd_new default inlet +
        // inlet_new/floatinlet_new/... + outlet_new; CLASS_MAINSIGNALIN /
        // sic_setup / "signal" method mark the main signal inlet; miXed's
        // sic_newinlet adds one SIGNAL inlet per call), position-tracked so
        // the signal sets carry the true indices. Names claimed bare by a
        // higher-priority lib (diy2/else/elsex/cyclone/zexy first) are NOT
        // here; loop-driven (variable) io keeps its reg() rule or the
        // heuristic instead.
        // -- cyclone (bare names cyclone owns) --------------------------------
        put("Append", d(2, 1)); put("Borax", d(3, 9)); put("Clip", d(3, 1))
        put("Clip~", Def(1, 1, setOf(0), setOf(0))); put("Histo", d(2, 2))
        put("Line~", Def(2, 2, setOf(0), setOf(0))); put("MouseState", d(1, 5))
        put("Peak", d(2, 3)); put("Scope~", Def(2, 0, setOf(0, 1), emptySet()))
        put("Snapshot~", Def(2, 1, setOf(0), emptySet())); put("Table", d(2, 2))
        put("TogEdge", d(1, 2)); put("Trough", d(2, 3)); put("accum", d(3, 1))
        put("acos", d(1, 1)); put("acosh~", Def(1, 1, setOf(0), setOf(0)))
        put("acos~", Def(1, 1, setOf(0), setOf(0))); put("active", d(1, 1))
        put("allpass~", Def(3, 1, setOf(0, 1, 2), setOf(0))); put("anal", d(1, 1))
        put("asin", d(1, 1)); put("asinh~", Def(1, 1, setOf(0), setOf(0)))
        put("asin~", Def(1, 1, setOf(0), setOf(0)))
        put("atanh~", Def(1, 1, setOf(0), setOf(0)))
        put("atan~", Def(1, 1, setOf(0), setOf(0)))
        put("average~", Def(1, 1, setOf(0), emptySet()))
        put("avg~", Def(1, 1, setOf(0), emptySet())); put("bangbang", d(1, 1))
        put("bitand~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("bitnot~", Def(1, 1, setOf(0), setOf(0)))
        put("bitor~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("bitshift~", Def(1, 1, setOf(0), setOf(0)))
        put("bitxor~", Def(2, 1, setOf(0, 1), setOf(0))); put("borax", d(3, 9))
        put("buffir~", Def(3, 1, setOf(0, 1, 2), setOf(0))); put("capture", d(1, 1))
        put("capture~", Def(1, 0, setOf(0), emptySet())); put("cartopol", d(2, 2))
        put("cartopol~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("change~", Def(1, 1, setOf(0), setOf(0)))
        put("click~", Def(1, 1, setOf(0), setOf(0)))
        put("comb~", Def(5, 1, setOf(0, 1, 2, 3, 4), setOf(0))); put("comment", d(1, 0))
        put("cosh", d(1, 1)); put("cosh~", Def(1, 1, setOf(0), setOf(0)))
        put("cosx~", Def(1, 1, setOf(0), setOf(0))); put("count~", Def(2, 1, setOf(0), setOf(0)))
        put("curve~", Def(3, 2, setOf(0), setOf(0)))
        put("cycle~", Def(2, 1, setOf(0, 1), setOf(0))); put("decide", d(2, 1))
        put("delay~", Def(2, 1, setOf(0), setOf(0)))
        put("deltaclip~", Def(1, 1, setOf(0), setOf(0)))
        put("delta~", Def(1, 1, setOf(0), setOf(0))); put("drunk", d(3, 1))
        put("edge~", Def(1, 2, setOf(0), emptySet()))
        put("flush", d(2, 2)); put("forward", d(1, 0))
        put("frameaccum~", Def(1, 1, setOf(0), setOf(0)))
        put("framedelta~", Def(1, 1, setOf(0), setOf(0))); put("fromsymbol", d(1, 1))
        put("funbuff", d(2, 3)); put("hammer", d(1, 1)); put("histo", d(2, 2))
        put("index~", Def(2, 1, setOf(0), setOf(0))); put("iter", d(1, 1))
        put("kink~", Def(1, 1, setOf(0), setOf(0))); put("linedrive", d(2, 1))
        put("lookup~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("lores~", Def(3, 1, setOf(0, 1, 2), setOf(0))); put("maximum", d(2, 1))
        put("maximum~", Def(1, 1, setOf(0), setOf(0))); put("maxmode", d(1, 2))
        put("mean", d(1, 2)); put("midiflush", d(1, 1)); put("midiformat", d(7, 1))
        put("midiparse", d(1, 7)); put("minimum", d(2, 1))
        put("minimum~", Def(1, 1, setOf(0), setOf(0)))
        put("minmax~", Def(1, 4, setOf(0), setOf(0, 1))); put("mousefilter", d(1, 1))
        put("mousestate", d(1, 5)); put("mstosamps~", Def(1, 2, setOf(0), setOf(0)))
        put("next", d(1, 2)); put("offer", d(2, 1)); put("onebang", d(2, 1))
        put("onepole~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("overdrive~", Def(2, 1, setOf(0), setOf(0))); put("past", d(1, 1))
        put("peak", d(2, 3)); put("peakamp~", Def(2, 1, setOf(0), emptySet()))
        put("peek~", d(3, 1)); put("phasewrap~", Def(1, 1, setOf(0), setOf(0)))
        put("pink~", Def(1, 1, setOf(0), setOf(0)))
        put("play~", Def(1, 1, setOf(0), setOf(0)))
        put("poke~", Def(3, 0, setOf(0, 1), emptySet())); put("poltocar", d(2, 2))
        put("poltocar~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("pong~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("pow~", Def(2, 1, setOf(0, 1), setOf(0))); put("prepend", d(2, 1))
        put("prob", d(1, 2)); put("pv", d(1, 1))
        put("rampsmooth~", Def(3, 1, setOf(0), setOf(0)))
        put("rand~", Def(1, 1, setOf(0), setOf(0)))
        put("record~", Def(4, 1, setOf(0, 1), setOf(0)))
        put("reson~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("sah~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("sampstoms~", Def(1, 2, setOf(0), setOf(0)))
        put("scope~", Def(2, 0, setOf(0, 1), emptySet())); put("seq", d(1, 2))
        put("sickle", d(1, 1)); put("sinh", d(1, 1))
        put("sinh~", Def(1, 1, setOf(0), setOf(0)))
        put("sinx~", Def(1, 1, setOf(0), setOf(0)))
        put("slide~", Def(1, 1, setOf(0), setOf(0))); put("speedlim", d(2, 1))
        put("spell", d(1, 1)); put("spike~", Def(2, 1, setOf(0), emptySet()))
        put("sprintf", d(2, 1)); put("substitute", d(2, 2))
        put("sustain", d(3, 2))
        put("svf~", Def(3, 4, setOf(0, 1, 2), setOf(0, 1, 2, 3)))
        put("tanx~", Def(1, 1, setOf(0), setOf(0))); put("testmess", d(1, 1))
        put("thresh", d(2, 1)); put("togedge", d(1, 2)); put("tosymbol", d(1, 1))
        put("train~", Def(1, 2, setOf(0), setOf(0)))
        put("trapezoid~", Def(1, 1, setOf(0), setOf(0)))
        put("triangle~", Def(1, 1, setOf(0), setOf(0))); put("trough", d(2, 3))
        put("universal", d(1, 0)); put("urn", d(2, 2))
        put("vectral~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("wave~", Def(3, 1, setOf(0, 1, 2), setOf(0))); put("xbendin", d(1, 2))
        put("xbendin2", d(1, 3)); put("xbendout", d(2, 1)); put("xbendout2", d(3, 1))
        put("xnotein", d(1, 4)); put("xnoteout", d(4, 1))
        put("zerox~", Def(1, 2, setOf(0), setOf(0, 1)))
        put("!-", Def(2, 1, setOf(0), emptySet())); put("!-~", Def(1, 1, setOf(0), setOf(0)))
        put("!/", Def(2, 1, setOf(0), emptySet())); put("!/~", Def(1, 1, setOf(0), setOf(0)))
        put("!=~", Def(1, 1, setOf(0), setOf(0))); put("%~", Def(1, 1, setOf(0), setOf(0)))
        put("+=~", Def(1, 1, setOf(0), setOf(0))); put("<=~", Def(1, 1, setOf(0), setOf(0)))
        put("<~", Def(1, 1, setOf(0), setOf(0))); put("==~", Def(1, 1, setOf(0), setOf(0)))
        put(">=~", Def(1, 1, setOf(0), setOf(0))); put(">~", Def(1, 1, setOf(0), setOf(0)))
        put("loadmess", d(1, 1)); put("pong", d(3, 1))
        put("round", d(2, 1)); put("round~", Def(2, 1, setOf(1), setOf(0)))
        put("trunc~", Def(2, 1, setOf(1), setOf(0)))
        // -- cxc ---------------------------------------------------------------
        put("ENV", d(1, 1)); put("ascseq", d(2, 1)); put("ascwave", d(4, 1))
        put("bfilt", d(2, 1)); put("bfilt2", d(2, 1)); put("cxavgdev", d(1, 1))
        put("cxc", d(1, 0)); put("cxc_counter", d(4, 2)); put("cxc_prepend", d(1, 1))
        put("cxc_split", d(1, 1)); put("cxmean", d(1, 1)); put("cxstddev", d(1, 1))
        put("dist_normal", d(2, 1)); put("hex2dec", d(1, 1)); put("ixprint", d(1, 0))
        put("mean~", d(1, 1)); put("proc", d(1, 1)); put("random1", d(3, 1))
        put("random1~", Def(1, 1, emptySet(), setOf(0))); put("random_fl", d(3, 1))
        put("random_fl~", Def(1, 1, emptySet(), setOf(0))); put("random_icg", d(3, 1))
        put("random_icg~", Def(1, 1, emptySet(), setOf(0))); put("random_tw", d(3, 1))
        put("utime", d(1, 2)); put("<<", d(1, 0)); put(">>", d(1, 0))
        put("cx.avgdev", d(1, 1)); put("cx.mean", d(1, 1)); put("cx.stddev", d(1, 1))
        // -- creb ---------------------------------------------------------------
        put("bdiag~", Def(1, 1, setOf(0), setOf(0)))
        put("bfft~", Def(1, 1, setOf(0), setOf(0)))
        put("bitsplit~", Def(1, 1, setOf(0), setOf(0)))
        put("blocknorm~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("bmatrix~", Def(1, 1, setOf(0), setOf(0)))
        put("bwin~", Def(1, 1, setOf(0), setOf(0)))
        put("cheby~", Def(1, 1, setOf(0), setOf(0)))
        put("diag~", Def(1, 1, setOf(0), setOf(0)))
        put("dist~", Def(2, 1, setOf(0), setOf(0)))
        put("dwt~", Def(1, 0, setOf(0), emptySet()))
        put("dynwav~", Def(2, 1, setOf(0, 1), setOf(0)))
        // ead~/eadsr~/ear~ are envelope GENERATORS: control inlets only, the
        // dsp_add feeds sp[0] = the outlet vector (no signal inlet at all)
        put("eadsr~", Def(5, 1, emptySet(), setOf(0)))
        put("ead~", Def(3, 1, emptySet(), setOf(0)))
        put("ear~", Def(3, 1, emptySet(), setOf(0)))
        put("fdn~", Def(3, 2, setOf(0), setOf(0, 1))); put("ffpoly", d(1, 1))
        put("fwarp", d(1, 1)); put("junction~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("lattice~", Def(1, 1, setOf(0), setOf(0)))
        put("permut~", Def(1, 1, setOf(0), setOf(0)))
        put("qmult~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("qnorm~", Def(2, 1, setOf(0, 1), setOf(0))); put("ratio", d(1, 1))
        put("resofilt~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("sbosc~", Def(3, 2, setOf(0, 1), setOf(0, 1)))
        put("scrollgrid1D~", Def(4, 3, setOf(0, 1, 2, 3), setOf(0, 1, 2)))
        put("statwav~", Def(1, 1, setOf(0), setOf(0)))
        put("tabreadmix~", Def(2, 1, setOf(0), setOf(0)))
        put("xfm~", Def(4, 2, setOf(0, 1, 2, 3), setOf(0, 1)))
        // cmath family: one class_new (clog~) + three class_addcreator
        // aliases, all sharing cmath_new_common (extra sig inlet + 2 sig outs)
        put("clog~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("cexp~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("nfft~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("nifft~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("bifft~", Def(1, 1, setOf(0), setOf(0)))
        put("blockramp~", Def(1, 1, emptySet(), setOf(0))); put("dt_ms", d(1, 2))
        put("dwt16~", Def(1, 0, setOf(0), emptySet()))
        put("fht~", Def(1, 1, setOf(0), setOf(0)))
        put("ibfft~", Def(1, 1, setOf(0), setOf(0)))
        put("idwt16~", Def(1, 0, setOf(0), emptySet()))
        put("idwt~", Def(1, 0, setOf(0), emptySet()))
        put("sawtooth~", Def(1, 1, emptySet(), setOf(0)))
        put("biquadseries~", Def(1, 1, setOf(0), setOf(0)))
        put("blosc~", Def(3, 1, setOf(0, 1), setOf(0)))
        put("filterortho~", Def(1, 1, setOf(0), setOf(0)))
        // -- fftease (PD branches create io explicitly) --------------------------
        put("bthresher~", Def(3, 3, setOf(0, 1, 2), setOf(0, 1)))
        put("burrow~", Def(4, 2, setOf(0, 1, 2, 3), setOf(0, 1)))
        put("cavoc27~", Def(1, 2, setOf(0), setOf(0, 1)))
        put("cavoc~", Def(1, 2, setOf(0), setOf(0, 1)))
        put("centerring~", Def(4, 2, setOf(0, 1, 2, 3), setOf(0, 1)))
        put("codepend~", Def(4, 2, setOf(0, 1, 2, 3), setOf(0, 1)))
        put("dentist~", Def(1, 5, setOf(0), setOf(0, 1, 2, 3)))
        put("disarrain~", Def(1, 5, setOf(0), setOf(0, 1, 2, 3)))
        put("disarray~", Def(1, 3, setOf(0), setOf(0, 1)))
        put("drown~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("ether~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("leaker~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("mindwarp~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("morphine~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("pvcompand~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("pvgrain~", Def(1, 1, setOf(0), emptySet()))
        put("pvharm~", Def(4, 2, setOf(0, 1, 2, 3), setOf(0, 1)))
        put("pvoc~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("pvtuner~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("pvwarp~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("reanimator~", Def(2, 4, setOf(0, 1), setOf(0, 1, 2, 3)))
        put("resent~", Def(1, 4, setOf(0), setOf(0, 1, 2, 3)))
        put("residency~", Def(3, 4, setOf(0, 1, 2), setOf(0, 1, 2, 3)))
        put("scrape~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("shapee~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("swinger~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("taint~", Def(4, 2, setOf(0, 1, 2, 3), setOf(0, 1)))
        put("thresher~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("vacancy~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("xsyn~", Def(2, 2, setOf(0, 1), setOf(0, 1)))
        put("crossx~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("presidency~", Def(4, 4, setOf(0, 1, 2, 3), setOf(0, 1, 2, 3)))
        // -- iemlib ---------------------------------------------------------------
        put("FIR~", Def(1, 1, setOf(0), setOf(0)))
        put("LFO_noise~", Def(1, 1, emptySet(), setOf(0))); put("add2_comma", d(1, 1))
        put("aspeedlim", d(2, 1)); put("biquad_freq_resp", d(1, 4)); put("bpe", d(1, 3))
        put("db2v", d(1, 1)); put("dollarg", d(1, 2)); put("exp_inc", d(5, 1))
        put("f2note", d(1, 3)); put("fade~", Def(1, 1, setOf(0), setOf(0)))
        put("filter~", Def(5, 2, setOf(0), setOf(0))); put("float24", d(1, 1))
        put("for++", d(4, 2)); put("forpp", d(4, 2))
        put("hml_shelf~", Def(7, 2, setOf(0), setOf(0)))
        put("iem_alisttosym", d(1, 1)); put("iem_anything", d(2, 1))
        put("iem_append", d(2, 1)); put("iem_blocksize~", Def(1, 1, setOf(0), emptySet()))
        put("iem_cot4~", Def(1, 1, setOf(0), setOf(0)))
        put("iem_delay~", Def(2, 1, setOf(0), setOf(0))); put("iem_i_route", d(1, 1))
        put("iem_pbank_csv", d(2, 2)); put("iem_pow4~", Def(2, 1, setOf(0), setOf(0)))
        put("iem_prepend", d(2, 1)); put("iem_receive", d(1, 1))
        put("iem_samplerate~", Def(1, 1, setOf(0), emptySet())); put("iem_sel_any", d(1, 2))
        put("iem_send", d(2, 0)); put("iem_sqrt4~", Def(1, 1, setOf(0), setOf(0)))
        put("iem_symtoalist", d(1, 1)); put("init", d(1, 1)); put("list2send", d(1, 0))
        put("lp1_t~", Def(3, 1, setOf(0), setOf(0)))
        put("m2f~", Def(1, 1, setOf(0), setOf(0))); put("mergefilename", d(1, 1))
        put("modulo_counter", d(2, 1))
        put("mov_avrg_kern~", Def(3, 1, setOf(0, 1), setOf(0)))
        put("para_bp2~", Def(5, 2, setOf(0), setOf(0))); put("parentdollarzero", d(1, 1))
        put("peakenv_hold~", Def(3, 1, setOf(0), setOf(0)))
        put("peakenv~", Def(2, 1, setOf(0), setOf(0))); put("post_netreceive", d(1, 1))
        put("pre_inlet", d(1, 1)); put("prepend_ascii", d(1, 1))
        put("protect_against_open", d(1, 0)); put("prvu~", Def(1, 1, setOf(0), emptySet()))
        put("pvu~", Def(1, 2, setOf(0), emptySet())); put("receive2list", d(1, 1))
        put("round_zero", d(1, 1)); put("rvu~", Def(1, 1, setOf(0), emptySet()))
        put("sin_phase~", Def(2, 1, setOf(0, 1), setOf(0))); put("soundfile_info", d(1, 1))
        put("splitfilename", d(1, 2)); put("stripfilename", d(1, 1))
        put("toggle_mess", d(1, 3)); put("transf_fader", d(1, 1)); put("unsymbol", d(1, 1))
        put("v2db", d(1, 1)); put("vcf_filter~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        // iemlib aliases (registered by the same sources as their primaries)
        put("\$n", d(1, 2)); put("any", d(2, 1)); put("f24", d(1, 1))
        put("iem_cot~", Def(1, 1, setOf(0), setOf(0))); put("iem_r", d(1, 1))
        put("iem_s", d(2, 0)); put("ii", d(1, 1)); put("iiroute", d(1, 1))
        put("merge_any", d(2, 1)); put("parent\$0", d(1, 1)); put("pp", d(2, 1))
        put("sin_freq~", Def(1, 1, setOf(0), setOf(0)))
        put("sparse_FIR~", Def(1, 1, setOf(0), setOf(0))); put("t3_bpe", d(1, 3))
        put("t3_del", d(2, 1)); put("t3_delay", d(2, 1))
        put("t3_line~", Def(2, 1, emptySet(), setOf(0))); put("t3_metro", d(2, 2))
        put("t3_sig~", Def(1, 1, emptySet(), setOf(0))); put("t3_timer", d(2, 1))
        put("tm", d(1, 3)); put("unsym", d(1, 1))
        // -- bsaylor ---------------------------------------------------------------
        put("aenv~", Def(5, 1, emptySet(), setOf(0)))
        put("zhzxh~", Def(2, 1, setOf(0), setOf(0)))
        // -- osc / midifile / binfile ------------------------------------------------
        put("packOSC", d(1, 2)); put("pipelist", d(2, 1)); put("unpackOSC", d(1, 2))
        put("midifile", d(1, 3))
        put("binfile", d(1, 3))
        // -- zexy (bare names zexy owns; see the 0.17.142 zexy audit) ----------
        put(".", d(2, 1))
        put("&&~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("absgn~", Def(1, 2, setOf(0), setOf(0, 1)))
        put("atoi", d(1, 2)); put("atof", d(1, 2))
        // five generators with a CONTROL inlet and a signal outlet - pd draws
        // the left iolet as a control iolet (no CLASS_MAINSIGNALIN, no
        // "signal" method): dirac~ noish~ noisi~ unpack~ step~
        put("dirac~", Def(1, 1, emptySet(), setOf(0)))
        put("noish~", Def(1, 1, emptySet(), setOf(0)))
        put("noisi~", Def(1, 1, emptySet(), setOf(0)))
        put("unpack~", Def(1, 1, emptySet(), setOf(0)))
        put("step~", Def(2, 1, emptySet(), setOf(0)))
        put("drip", d(2, 1))
        // float-outlet signal objects the heuristic drew as signal outlets
        put("envrms~", Def(1, 1, setOf(0), emptySet()))
        put("sigzero~", Def(1, 1, setOf(0), emptySet()))
        put("tavg~", Def(1, 1, setOf(0), emptySet()))
        put("pack~", Def(1, 1, setOf(0), emptySet()))
        put("pdf~", Def(1, 1, setOf(0), emptySet()))
        put("fifop", d(2, 2)); put("lifop", d(2, 2))
        put("freadln", d(1, 2)); put("fwriteln", d(1, 0))
        put("glue", d(2, 1)); put("l", d(2, 1)); put("lister", d(2, 1))
        put("l2s", d(2, 1)); put("list2symbol", d(2, 1))
        put("s2l", d(2, 1)); put("symbol2list", d(2, 1))
        put("list2lists", d(2, 1)); put("listfind", d(2, 1))
        put("liststorage", d(2, 2)); put("lpt", d(2, 1))
        put("minmax", d(1, 2)); put("msgfile", d(1, 2))
        put("matchbox", d(2, 2)); put("mavg", d(2, 1))
        put("rawprint", d(1, 0)); put("tabset", d(1, 0)); put("longload", d(0, 0))
        put("regex", d(2, 3)); put("repack", d(2, 1)); put("repeat", d(2, 1))
        put("strcmp", d(2, 1)); put("tabminmax", d(1, 2))
        put("tabread4~~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("wrap", d(2, 1))
    }

    /** Prefixes under which pd registers two-word creators ("list append"). */
    private val TWO_WORD_PREFIXES = setOf("list", "array", "text")

    /** Classes whose second inlet appears only in the NAMELESS form. */
    private val NAMELESS_SETTER_INLET = setOf("value", "v", "send", "s")

    /** iemgui classes and their widget family (nbx = pd's number box). */
    val IEMGUI = setOf("bng", "tgl", "hsl", "vsl", "vradio", "hradio", "vu", "cnv", "nbx")

    /**
     * Classes known to pd: the vanilla table below plus bundled-library
     * classes (Slibs manifest, incl. lib/name forms) and every indexed
     * abstraction. Populated at startup; a class outside this set (and not
     * handled by the variadic cases in [resolveObject]) does not exist in
     * the pd instance — pd itself would print "couldn't create".
     */
    val knownClasses = HashSet<String>()

    /**
     * Names pd resolves to a COMPILED class — the session builder's inlining
     * gate (PdEngine expandGop / buildInteractiveSession) must not replace
     * these with a search-path .pd file, because pd checks the class table
     * before ANY file search. Everything else that resolves to a .pd file —
     * user abstractions first among them — MUST inline per instance: pd's
     * delay-line and send/receive namespaces are one global table per
     * instance, so two native copies of the same module cross-wire
     * ("warning: s_delay: multiply defined", hurdy2 ×4 c_ks_string).
     *
     * Populated from three sources, in order:
     *  1. the init block below mirrors [knownClasses] BEFORE any runtime
     *     merge can pollute it (vanilla + app-only names);
     *  2. [addCompiledNative] merges Slibs manifest names that have no .pd
     *     file on the bundled path — the manifest also lists pure
     *     abstractions (else bl.square~/output~, the whole diy2 library),
     *     so manifest membership alone proves nothing;
     *  3. [DUAL_COMPILE_PROTECT], for names shipped BOTH ways.
     * Patch directories NEVER feed this set: patch-dir abstractions must
     * always inline. 0.17.34 regressed exactly that by snapshotting
     * knownClasses lazily after refreshIndex had merged patch-dir names —
     * user abstractions stopped inlining and pd re-created their bare
     * delay lines per copy ("multiply defined" returned).
     */
    val pdClasses = HashSet<String>()

    /**
     * Names shipped BOTH as a bundled .pd abstraction and as a compiled
     * class, verified offline by scanning the shipped <lib>_core.so setup
     * strings (arm64-v8a, 2026-11-05). pd resolves these compiled-first, so
     * inlining the .pd would swap in a different implementation (different
     * ports/semantics). `stack` compiles in cyclone, `sort` in zexy (bare
     * name goes to the PRIORITY winner), `hex2dec` in cxc, `pvoc~` in
     * fftease, the rest in else_core. Prefixed spellings ("else/sort") are
     * registered by no shipped .so — pd loads the file there and inlining
     * is correct, so only bare names are listed. median/voice have no
     * compiled version at all (0.17.34's changelog claimed otherwise —
     * wrong): their .pd files inline.
     */
    private val DUAL_COMPILE_PROTECT = setOf(
        "sort", "pick", "stack",
        "bpm", "cmul~", "count", "e", "inc", "pi", "rms~", "sin~", "tempo",
        "hex2dec", "pink~", "pvoc~",
    )

    init {
        knownClasses.addAll(
            setOf(
                "pd", "array", "text", "scalar", "struct", "clone", "bonk~",
                "fiddle~", "loop~", "lrshift~", "pique", "sigmund~",
                "readsf~", "writesf~", "pipe",
                // libpd pd-core vanilla extras (PdCoreExtras loads them)
                "bob~", "choice",
            )
        )
        // every vanilla 0.51 class name (pd-0.51-4/src class_new enumeration)
        knownClasses.addAll(listOf(
                "!=",
                "%",
                "&",
                "&&",
                "*",
                "*~",
                "+",
                "+~",
                "-",
                "-~",
                "/",
                "/~",
                "<",
                "<<",
                "<=",
                "==",
                ">",
                ">=",
                ">>",
                "abs",
                "abs~",
                "adc~",
                "append",
                "array",
                "array",
                "define",
                "array",
                "get",
                "array",
                "max",
                "array",
                "min",
                "array",
                "quantile",
                "array",
                "random",
                "array",
                "set",
                "array",
                "size",
                "array",
                "sum",
                "atan",
                "atan2",
                "bag",
                "bang",
                "bang~",
                "bendin",
                "bendout",
                "biquad~",
                "block~",
                "bng",
                "bp~",
                "catch~",
                "change",
                "clip",
                "clip~",
                "clone",
                "cnv",
                "cos",
                "cos~",
                "cpole~",
                "cputime",
                "ctlin",
                "ctlout",
                "czero_rev~",
                "czero~",
                "dac~",
                "dbtopow",
                "dbtopow~",
                "dbtorms",
                "dbtorms~",
                "declare",
                "delay",
                "delread4~",
                "delread~",
                "delwrite~",
                "div",
                "drawpolygon",
                "drawtext",
                "element",
                "env~",
                "exp",
                "expr",
                "expr~",
                "exp~",
                "fexpr~",
                "fft~",
                "float",
                "framp~",
                "ftom",
                "ftom~",
                "fudiformat",
                "fudiparse",
                "gatom",
                "get",
                "getsize",
                "hip~",
                "hradio",
                "hsl",
                "ifft~",
                "inlet",
                "inlet~",
                "int",
                "key",
                "keyname",
                "keyup",
                "line",
                "line~",
                "list",
                "append",
                "list",
                "fromsymbol",
                "list",
                "list",
                "length",
                "list",
                "prepend",
                "list",
                "split",
                "list",
                "store",
                "list",
                "tosymbol",
                "list",
                "trim",
                "loadbang",
                "log",
                "log~",
                "lop~",
                "makefilename",
                "makenote",
                "max",
                "max~",
                "metro",
                "midiin",
                "midiout",
                "midirealtimein",
                "min",
                "min~",
                "mod",
                "moses",
                "mtof",
                "mtof~",
                "namecanvas",
                "nbx",
                "netreceive",
                "netsend",
                "noise~",
                "notein",
                "noteout",
                "openpanel",
                "oscformat",
                "oscparse",
                "osc~",
                "outlet",
                "outlet~",
                "pack",
                "pd",
                "pdcontrol",
                "pgmin",
                "pgmout",
                "phasor~",
                "pipe",
                "plot",
                "pointer",
                "poly",
                "polytouchin",
                "polytouchout",
                "pow",
                "powtodb",
                "powtodb~",
                "pow~",
                "print",
                "print~",
                "qlist",
                "random",
                "readsf~",
                "realtime",
                "receive",
                "receive~",
                "rfft~",
                "rifft~",
                "rmstodb",
                "rmstodb~",
                "route",
                "rpole~",
                "rsqrt~",
                "rzero_rev~",
                "rzero~",
                "samphold~",
                "samplerate~",
                "savepanel",
                "savestate",
                "scalar",
                "scalar",
                "define",
                "select",
                "send",
                "send~",
                "set",
                "setsize",
                "sig~",
                "sin",
                "slop~",
                "snapshot~",
                "soundfiler",
                "spigot",
                "sqrt",
                "sqrt~",
                "stripnote",
                "struct",
                "swap",
                "symbol",
                "sysexin",
                "tabosc4~",
                "tabplay~",
                "tabread",
                "tabread4",
                "tabread4~",
                "tabread~",
                "tabreceive~",
                "tabsend~",
                "tabwrite",
                "tabwrite~",
                "tan",
                "text",
                "text",
                "define",
                "text",
                "delete",
                "text",
                "fromlist",
                "text",
                "get",
                "text",
                "insert",
                "text",
                "search",
                "text",
                "sequence",
                "text",
                "set",
                "text",
                "size",
                "text",
                "tolist",
                "textfile",
                "tgl",
                "threshold~",
                "throw~",
                "timer",
                "touchin",
                "touchout",
                "trigger",
                "unpack",
                "until",
                "value",
                "vcf~",
                "vdl",
                "vline~",
                "vradio",
                "vsl",
                "vsnapshot~",
                "vu",
                "wrap",
                "wrap~",
                "writesf~",
                "|",
                "||"
        ))
        // pd registers short aliases via class_addcreator — real creators the
        // class_new enumeration above does not list, so [t]/[sel]/[f]… would
        // otherwise be drawn as "couldn't create" although pd creates them.
        knownClasses.addAll(listOf(
                "b", "f", "i", "s", "r", "v", "t", "sel", "del", "s~", "r~",
        ))
        // 0.51 classes missed by the enumeration
        knownClasses.addAll(listOf(
                "arraysize", "atan2~", "fadtorms~", "rmstofad~",
                "stdout", "switch~", "table", "vd~",
        ))
        // 0.51 message gate/router; the names resolve to cyclone in our lib
        knownClasses.addAll(listOf("gate", "switch"))
        // ELSE-family UI widgets the app draws itself (bundled abstractions)
        knownClasses.addAll(listOf("graph~", "spectrograph~", "keyboard", "button", "pad", "popmenu"))
        // app-only widgets with no pd class at all: the session builder
        // drops the box and wires its cords through hidden relays
        knownClasses.addAll(listOf("euclid", "circle5"))
        // two-word creators resolve under their prefix ("list append"…)
        knownClasses.addAll(listOf(
                "list append", "list prepend", "list split", "list trim",
                "list length", "list store", "list fromsymbol", "list tosymbol",
                "array define", "array get", "array max", "array min",
                "array quantile", "array random", "array set", "array size",
                "array sum", "text define", "text delete", "text fromlist",
                "text get", "text insert", "text search", "text sequence",
                "text set", "text size", "text tolist",
        ))
        // mirror BEFORE any runtime writer (App.onCreate / Externals
        // refreshIndex) can merge file-backed abstraction names into
        // [knownClasses] — this copy stays compiled-only by construction
        pdClasses.addAll(knownClasses)
        pdClasses.addAll(DUAL_COMPILE_PROTECT)
    }

    /** Merges Slibs manifest names that have no .pd file on the bundled
     *  path (see Slibs.compiledNativeClasses) into the compiled gate set. */
    fun addCompiledNative(names: Set<String>) {
        pdClasses.addAll(names)
    }

    fun isIemGui(name: String): Boolean = name in IEMGUI

    /** Ranges for a slider parsed from its tokens; used by the Run-mode widget. */
    data class SliderRange(val min: Float, val max: Float)

    fun resolve(obj: PatchObject): ObjectMeta {
        val args = obj.args
        val name = args.firstOrNull() ?: ""
        return when (obj.kind) {
            ObjKind.MSG -> ObjectMeta("msg", 1, 1, emptySet(), emptySet(), true, 0, 0, "", "", "")
            ObjKind.COMMENT -> ObjectMeta("comment", 0, 0, emptySet(), emptySet(), false, 0, 0, "", "", "")
            ObjKind.FLOATATOM -> atomMeta("floatatom", args, float = true)
            ObjKind.SYMBOLATOM -> atomMeta("symbolatom", args, float = false)
            ObjKind.LISTBOX -> ObjectMeta("listbox", 1, 1, emptySet(), emptySet(), true, atomWidthChars(args) * CHAR_UNIT, 20, "", "", "")
            ObjKind.OBJECT -> resolveObject(name, args)
        }
    }

    private fun atomMeta(name: String, args: List<String>, float: Boolean): ObjectMeta {
        // args: [<kind>, width, min, max, labelpos, label, rcv, snd]
        // "0" in the rcv/snd slots is the LLM-typo form of "unbound" (pd uses
        // "-" or "empty"); binding it literally would send cords to a [r 0]
        // relay pd cannot create
        fun bound(tok: String?) = tok?.takeIf { it != "empty" && it != "-" && it != "0" } ?: ""
        val snd = bound(args.getOrNull(7))
        val rcv = bound(args.getOrNull(6))
        val label = args.getOrNull(5)?.takeIf { it != "empty" && it != "-" } ?: ""
        return ObjectMeta(
            className = name, inlets = 1, outlets = 1,
            signalInlets = emptySet(), signalOutlets = emptySet(),
            isGui = true, guiWidth = atomWidthChars(args) * CHAR_UNIT, guiHeight = 20,
            sendSymbol = snd, receiveSymbol = rcv, labelText = label,
        )
    }

    /** Atom args look like: [<kind>, width, min, max, labelpos, label, rcv, snd] */
    private fun atomWidthChars(args: List<String>): Int =
        (args.getOrNull(1)?.toIntOrNull() ?: 5).coerceIn(1, 30)

    private fun guiSymbols(send: String?, rcv: String?): Pair<String, String> =
        Pair(
            send?.takeIf { it != "empty" && it != "-" && it != "0" } ?: "",
            rcv?.takeIf { it != "empty" && it != "-" && it != "0" } ?: "",
        )

    private fun resolveObject(name: String, args: List<String>): ObjectMeta {
        if (isIemGui(name)) return resolveIemGui(name, args)

        // ELSE-family UI widgets: app-drawn canvases with fixed ports
        // (graph~/spectrograph~ = 1 signal in, keyboard = 1 in / 2 out);
        // optional numeric args override the pixel size.
        when (name) {
            // app-only button: no inlets, one control outlet; args =
            // <width=24> <height=24> <send> <receive> — square by default,
            // both size tokens editable in Properties. Press sends 1,
            // release sends 0 — pd never sees the class (the session
            // builder drops the box, its cords ride the send symbol).
            "button" -> {
                val syms = guiSymbols(args.getOrNull(3), args.getOrNull(4))
                return ObjectMeta(
                    className = name, inlets = 0, outlets = 1,
                    signalInlets = emptySet(), signalOutlets = emptySet(),
                    isGui = true,
                    guiWidth = args.getOrNull(1)?.toIntOrNull()?.coerceIn(8, 500) ?: 24,
                    guiHeight = args.getOrNull(2)?.toIntOrNull()?.coerceIn(8, 500) ?: 24,
                    sendSymbol = syms.first, receiveSymbol = syms.second,
                    labelText = "",
                )
            }
            "popmenu" -> {
                // app-only dropdown widget (porres 2025 upstream is a Tcl
                // GUI object; libpd has no Tcl and no blob ever compiled it
                // - the session drops the box, cords ride the send symbol).
                // Upstream file layout: args[1..3] width/height/fontsize,
                // [4..5] colors, [6] label, [7] rcv, [8] snd, [9..10]
                // param/var, [11] outline, [12] outmode, [13] load,
                // [14] loadbang, [15] savestate, [16] keep, [17] pos,
                // [18..21] placeholders, [22..] items.
                val syms = guiSymbols(args.getOrNull(7), args.getOrNull(8))
                return ObjectMeta(
                    className = name, inlets = 1, outlets = 1,
                    signalInlets = emptySet(), signalOutlets = emptySet(),
                    isGui = true,
                    guiWidth = args.getOrNull(1)?.toIntOrNull()?.coerceIn(24, 800) ?: 128,
                    guiHeight = args.getOrNull(2)?.toIntOrNull()?.coerceIn(16, 200) ?: 26,
                    sendSymbol = syms.first, receiveSymbol = syms.second,
                    labelText = args.getOrNull(6)?.takeIf {
                        it.isNotBlank() && it != "empty"
                    } ?: "",
                )
            }
            "euclid" -> {
                // app-only euclidean rhythm clock: args = <hits=3>
                // <steps=8> <rotation=0> <width=64> <height=64>
                // <interactive=1> <in0> <in1> <out0> <out1> — the last four
                // are auto-assigned relay symbols, never user-facing.
                // Left inlet: a bang fires the current step (1/0 on outlet
                // 0, the hit's index within the circle on outlet 1 — hit
                // steps only — then advances); a plain float jumps to that
                // step (mod steps) and fires it; `hit/step/rotation n`
                // messages reconfigure live. Right inlet resets to step 0.
                // Tapping an interactive widget triggers.
                return ObjectMeta(
                    className = name, inlets = 2, outlets = 2,
                    signalInlets = emptySet(), signalOutlets = emptySet(),
                    isGui = true,
                    guiWidth = args.getOrNull(4)?.toIntOrNull()?.coerceIn(8, 500) ?: 64,
                    guiHeight = args.getOrNull(5)?.toIntOrNull()?.coerceIn(8, 500) ?: 64,
                    sendSymbol = "", receiveSymbol = "", labelText = "",
                )
            }
            "circle5" -> {
                // app-only circle-of-fifths dial: args = <key=1> <size=3>
                // <width=250> <height=250> <interactive=1> <in0> <out0> —
                // the last two are auto-assigned relay symbols, never
                // user-facing. 12 positions clockwise (chord 1 = C,
                // chord 2 = G; chord -1 = am = key 1), rings major/minor/
                // diminished. Tapping a cell emits its chord's midi notes
                // and sets the current key; inlet floats 1..7 emit that
                // diatonic degree's chord; `chord <name|n>` / `size n`
                // messages reconfigure live. The inner ring is display-only.
                return ObjectMeta(
                    className = name, inlets = 1, outlets = 1,
                    signalInlets = emptySet(), signalOutlets = emptySet(),
                    isGui = true,
                    guiWidth = args.getOrNull(3)?.toIntOrNull()?.coerceIn(60, 2000) ?: 250,
                    guiHeight = args.getOrNull(4)?.toIntOrNull()?.coerceIn(60, 2000) ?: 250,
                    sendSymbol = "", receiveSymbol = "", labelText = "",
                )
            }
            "graph~", "spectrograph~" -> return ObjectMeta(
                className = name, inlets = 1, outlets = 0,
                signalInlets = setOf(0), signalOutlets = emptySet(),
                isGui = true,
                guiWidth = args.getOrNull(1)?.toIntOrNull()?.coerceIn(40, 4096) ?: 200,
                guiHeight = args.getOrNull(2)?.toIntOrNull()?.coerceIn(20, 4096) ?: 100,
                sendSymbol = "", receiveSymbol = "", labelText = "",
            )
            // app-only XY pad with multi-touch: args = <width=180>
            // <height=180> <gridx=1> <gridy=1> <in0> <out0> <out1> — the
            // last three are auto-assigned relay symbols, never user-facing.
            // Outlet 0 streams "x y e" lists per finger (x/y normalized
            // 0..1 from the top-left corner; e: 2 = press, 1 = drag,
            // 0 = release — all fingers share the outlet); outlet 1 streams
            // "col row e" grid-cell events (e: 1 = cell entered, 0 = left,
            // radio-style slide-follow). Inlet messages reconfigure live:
            // `gridx n`, `gridy n`, `size w h`. The grid never affects
            // outlet 0. pd never sees the class (session builder drops the
            // box, its cords ride the relay symbols).
            "pad" -> return ObjectMeta(
                className = name, inlets = 1, outlets = 2,
                signalInlets = emptySet(), signalOutlets = emptySet(),
                isGui = true,
                guiWidth = args.getOrNull(1)?.toIntOrNull()?.coerceIn(40, 4096) ?: 180,
                guiHeight = args.getOrNull(2)?.toIntOrNull()?.coerceIn(40, 4096) ?: 180,
                sendSymbol = "", receiveSymbol = "", labelText = "",
            )
            // real ELSE [keyboard]: args = <keywidth=17> <height=80>
            // <octaves=4> <lowC=2> <toggle> <norm> <send> <receive>;
            // ONE outlet emitting "pitch vel" lists (vel 0 = note off).
            "keyboard" -> {
                val kw = args.getOrNull(1)?.toIntOrNull()?.coerceIn(7, 100) ?: 17
                val oct = args.getOrNull(3)?.toIntOrNull()?.coerceIn(1, 10) ?: 4
                val syms = guiSymbols(args.getOrNull(7), args.getOrNull(8))
                return ObjectMeta(
                    className = name, inlets = 1, outlets = 1,
                    signalInlets = emptySet(), signalOutlets = emptySet(),
                    isGui = true,
                    guiWidth = kw * 7 * oct, guiHeight = args.getOrNull(2)
                        ?.toIntOrNull()?.coerceIn(10, 1000) ?: 80,
                    sendSymbol = syms.first, receiveSymbol = syms.second,
                    labelText = "",
                )
            }
            // real ELSE [knob] (0.17.136, compiled in elsex): iemgui-style
            // dial. args after the class name: <size=50> <min=0> <max=127>
            // <exp> <load> <snd> <rcv> ... — slots 6/7 carry the symbols
            // ("empty" = auto-assigned at creation). One value inlet, one
            // float outlet; the canvas draws the dial and vertical drag
            // sweeps the value.
            "knob" -> {
                val syms = guiSymbols(args.getOrNull(6), args.getOrNull(7))
                val size = args.getOrNull(1)?.toIntOrNull()?.coerceIn(12, 400) ?: 50
                return ObjectMeta(
                    className = name, inlets = 1, outlets = 1,
                    signalInlets = emptySet(), signalOutlets = emptySet(),
                    isGui = true,
                    guiWidth = size, guiHeight = size,
                    sendSymbol = syms.first, receiveSymbol = syms.second,
                    labelText = "",
                )
            }
        }

        var inlets = 1
        var outlets = 1
        var sigOut: Set<Int> = emptySet()
        var sigIn: Set<Int> = emptySet()

        // "[list append]" parses with name "list"; pd's real creator is the
        // two-word "list append", so prefer that form for port lookup.
        // lib-prefixed spellings ("zexy/lister") resolve via the BARE name
        // everywhere below — pd's loader strips the prefix when forming the
        // dlopen filename (symlinked per class next to the core blob), and
        // the registered class keeps its bare name, so defs / abstraction
        // ports / the known-class set are all keyed bare. Without this the
        // editor drew every prefixed compiled class red ("爆红不识别") even
        // though pd created it fine. Strip only when a real prefix exists:
        // vanilla's [/] (division) and [/~] have a slash AS the name —
        // substringAfter('/') on those yields "" / "~", both lookups miss,
        // and the box fell through to the 1-inlet unknown fallback
        // (user report: "奇怪的/ obj 只有一个 inlet").
        val slash = name.indexOf('/')
        val bare = if (slash > 0) name.substring(slash + 1) else name
        val twoWord =
            if (args.size > 1 && bare in TWO_WORD_PREFIXES) "$bare ${args[1]}" else null
        // variable-port classes first (a static defs row must never shadow
        // them)
        val variadicDef = variadic[name]
            ?: (if (name != bare) variadic[bare] else null)
            ?: twoWord?.let { variadic[it] }
        if (variadicDef != null) {
            val vdef = variadicDef(args.drop(1))
            inlets = vdef.inlets; outlets = vdef.outlets
            sigIn = vdef.sigIn; sigOut = vdef.sigOut
        } else {
            val def = twoWord?.let { defs[it] } ?: defs[bare]
            if (def != null) {
                inlets = def.inlets; outlets = def.outlets; sigIn = def.sigIn; sigOut = def.sigOut
                // [value]/[send] with NO name argument grow a second inlet that
                // sets the target symbol (pd value_new / send_new only create the
                // symbol inlet when the name is empty — float-help's bare `value`
                // shows two inlets, a named `v x` shows one)
                if (args.size == 1 && bare in NAMELESS_SETTER_INLET) inlets = 2
            } else {
                // Unknown classes.
                // A user abstraction declares its own ports via its
                // inlet/outlet objects; only fall back to the tilde
                // heuristic when no such file is indexed.
                val abs = AbstractionPorts.lookup(bare)
                if (abs != null) {
                    inlets = abs.inlets
                    outlets = abs.outlets
                    sigIn = abs.signalInlets
                    sigOut = abs.signalOutlets
                } else if (name.endsWith("~")) {
                    // most tilde externals take signal in the left inlet
                    sigIn = setOf(0)
                    outlets = 1
                    sigOut = setOf(0)
                }
            }
        }

        // expr: the max-$f/$v scan above already counts the expression's
        // variables — token count is not the inlet count ([expr $f1 + $f2]
        // has TWO inlets, not four)
        val known = name in knownClasses ||
            twoWord != null && twoWord in knownClasses ||
            name != bare && bare in knownClasses
        return ObjectMeta(
            className = name, inlets = inlets, outlets = outlets,
            signalInlets = sigIn, signalOutlets = sigOut,
            isGui = false, guiWidth = 0, guiHeight = 0,
            sendSymbol = "", receiveSymbol = "", labelText = "",
            exists = known,
        )
    }

    /**
     * iemgui token layout after "#X obj x y <class>":
     *  bng:    size hold intr init snd rcv label xoff yoff fstyle fsize cols...
     *  tgl:    size init snd rcv label xoff yoff fstyle fsize cols... value
     *  hsl/vsl w h min max init snd rcv label xoff yoff fstyle fsize cols... value
     *  radio:  size a2 a3 cells snd rcv label xoff yoff fstyle fsize cols... value
     *          (0.54 files keep cells at index 4; older ones at index 3)
     *  vu:     size h rcv label xoff yoff fstyle fsize cols...
     *  cnv:    size w h snd rcv label xoff yoff fstyle fsize cols...
     */
    private fun resolveIemGui(name: String, a: List<String>): ObjectMeta {
        // "0" in a snd/rcv slot is pd's legacy spelling of "unbound" (and the
        // LLM-typo form); binding it literally sends cords to a [r 0] relay
        // pd cannot create
        val empty = { s: String? -> if (s == null || s == "empty" || s == "-" || s == "0") "" else s }
        var w = a.getOrNull(1)?.toIntOrNull() ?: 15
        var h = w
        var send = ""
        var rcv = ""
        var label = ""
        var inlets = 1
        var outlets = 1
        var sigIn: Set<Int> = emptySet()
        when (name) {
            "bng" -> {
                send = empty(a.getOrNull(5)); rcv = empty(a.getOrNull(6)); label = empty(a.getOrNull(7))
            }
            "tgl" -> {
                send = empty(a.getOrNull(3)); rcv = empty(a.getOrNull(4)); label = empty(a.getOrNull(5))
            }
            "hsl", "vsl" -> {
                h = a.getOrNull(2)?.toIntOrNull() ?: 15
                // pd-0.51-written files carry an isa int at args[6] (so snd/rcv
                // sit at 7/8); app-created ones start snd directly at 6
                val shift = if (a.getOrNull(6)?.toIntOrNull() != null) 1 else 0
                send = empty(a.getOrNull(6 + shift))
                rcv = empty(a.getOrNull(7 + shift))
                label = empty(a.getOrNull(8 + shift))
            }
            "nbx" -> {
                // pd 0.51 g_numbox.c number_box_save: nbx w h min max lin isa
                // snd rcv label ldx ldy fstyle fsize colors… value log_height
                w = (a.getOrNull(1)?.toIntOrNull() ?: 4) * CHAR_UNIT + 8
                h = a.getOrNull(2)?.toIntOrNull() ?: 14
                send = empty(a.getOrNull(7)); rcv = empty(a.getOrNull(8)); label = empty(a.getOrNull(9))
            }
            "vradio", "hradio" -> {
                send = empty(a.getOrNull(5)); rcv = empty(a.getOrNull(6)); label = empty(a.getOrNull(7))
                // cells of size×size tiles: vradio stacks them vertically,
                // hradio lays them out in a row
                val size = a.getOrNull(1)?.toIntOrNull() ?: 20
                val cells = radioCellsFrom(a)
                if (name == "vradio") {
                    w = size; h = size * cells
                } else {
                    w = size * cells; h = size
                }
            }
            "vu" -> {
                h = a.getOrNull(2)?.toIntOrNull() ?: 60
                rcv = empty(a.getOrNull(3)); label = empty(a.getOrNull(4))
                // g_vumeter vu_new: cold float inlet (ft1) + signal inlet 0,
                // rms + peak outlets — NOT a display-only box
                inlets = 2
                outlets = 2
                sigIn = setOf(0)
            }
            "cnv" -> {
                w = a.getOrNull(2)?.toIntOrNull() ?: 100
                h = a.getOrNull(3)?.toIntOrNull() ?: 60
                send = empty(a.getOrNull(4)); rcv = empty(a.getOrNull(5)); label = empty(a.getOrNull(6))
                inlets = 0; outlets = 0
            }
        }
        return ObjectMeta(
            className = name, inlets = inlets, outlets = outlets,
            signalInlets = sigIn, signalOutlets = emptySet(),
            isGui = true,
            // radios tile size×size squares and may legitimately exceed the
            // usual widget clamp (128 cells × 20 units = 2560)
            guiWidth = w.coerceIn(8, if (isIemGui(name) && (name == "vradio" || name == "hradio")) 4000 else 400),
            guiHeight = h.coerceIn(8, if (name == "vradio" || name == "hradio") 4000 else 400),
            sendSymbol = send, receiveSymbol = rcv, labelText = label,
        )
    }

    fun sliderRange(obj: PatchObject): SliderRange {
        if (obj.className() == "knob") {
            // knob's min/max live at pd args 2/3 (hsl/vsl keep theirs at 3/4)
            val min = obj.args.getOrNull(2)?.toFloatOrNull() ?: 0f
            val max = obj.args.getOrNull(3)?.toFloatOrNull() ?: 127f
            return if (max > min) SliderRange(min, max) else SliderRange(0f, 127f)
        }
        val min = obj.args.getOrNull(3)?.toFloatOrNull() ?: 0f
        val max = obj.args.getOrNull(4)?.toFloatOrNull() ?: 127f
        return if (max > min) SliderRange(min, max) else SliderRange(0f, 127f)
    }

    /** Stored gui value, if the class persists one (tgl/hsl/vsl/radio/nbx). */
    fun guiValue(obj: PatchObject): Float {
        // euclid stores nothing: -0.5 is the app's "ready" notch (pointer
        // rests half a tick counterclockwise of step 0 until the first hit)
        if (obj.className() == "euclid") return -0.5f
        val idx = when (obj.className()) {
            "tgl" -> 13
            // pd-written sliders carry an isa int at args[6]; value shifts +1
            "hsl", "vsl" ->
                if (obj.args.getOrNull(6)?.toIntOrNull() != null) 17 else 16
            "vradio", "hradio" -> 15
            "nbx" -> 17
            // else knob: pd arg 5 (0-based slot 5) is the load value
            "knob" -> 5
            else -> return 0f
        }
        return obj.args.getOrNull(idx)?.toFloatOrNull() ?: 0f
    }

    /**
     * Number of cells for radio buttons (1..128). pd 0.54 files store the
     * count at args[4] (e.g. "vradio 19 1 0 3 …"), while older files and
     * app-created radios keep it at args[3]; prefer whichever is a valid
     * count.
     */
    fun radioCells(obj: PatchObject): Int = radioCellsFrom(obj.args)

    /** Pad grid counts (creation arg slots 3/4, clamped 1..64). */
    fun padGridX(obj: PatchObject): Int =
        obj.args.getOrNull(3)?.toIntOrNull()?.coerceIn(1, 64) ?: 1

    fun padGridY(obj: PatchObject): Int =
        obj.args.getOrNull(4)?.toIntOrNull()?.coerceIn(1, 64) ?: 1

    private fun radioCellsFrom(a: List<String>): Int {
        val a3 = a.getOrNull(3)?.toIntOrNull() ?: 0
        val a4 = a.getOrNull(4)?.toIntOrNull() ?: 0
        return (if (a3 >= 1) a3 else a4).coerceIn(1, 128)
    }

    /** App-only trailing token marking a slider that outputs integer steps. */
    const val INT_MARKER = "#int"

    fun isIntSteps(obj: PatchObject): Boolean = obj.args.contains(INT_MARKER)

    /**
     * Writes a slider's range (standard hsl/vsl tokens 3/4) and the app-only
     * integer-steps marker, then re-resolves the widget geometry.
     */
    fun setSliderParams(obj: PatchObject, min: Float, max: Float, intSteps: Boolean) {
        fun fmt(v: Float): String =
            if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
        while (obj.args.size < 5) obj.args.add("0")
        obj.args[3] = fmt(min)
        obj.args[4] = fmt(max)
        obj.args.removeAll { it == INT_MARKER }
        if (intSteps) obj.args.add(INT_MARKER)
        obj.meta = resolve(obj)
    }

    const val CHAR_UNIT = 7 // canvas units per atom width character

    // ---- popmenu (app-only dropdown widget) --------------------------------

    /**
     * Pads a bare or partial [popmenu] to the full upstream 21-token layout
     * (width/height/fontsize/colors/label/rcv/snd/param/var/outline/outmode/
     * load/loadbang/savestate/keep/pos + 4 placeholders) so Properties and
     * item editing always have every slot. Numeric slots must stay numeric
     * (setSymbols fills holes with "empty" - fine for symbol slots, wrong
     * for the numbers a desktop-else file round-trip expects).
     */
    fun ensurePopmenuLayout(obj: PatchObject) {
        val defaults = listOf(
            "128", "26", "12", "#dfdfdf", "black", "empty", "empty", "empty",
            "empty", "1", "0", "-1", "1", "0", "1", "0", "0", "0", "0", "0", "0",
        )
        val numeric = setOf(1, 2, 3, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21)
        for (k in 1..21) {
            while (obj.args.size <= k) obj.args.add(defaults[k - 1])
            if (k in numeric && obj.args[k].toFloatOrNull() == null) {
                obj.args[k] = defaults[k - 1]
            }
        }
    }

    /** The dropdown's items: tokens after the 21-token property block. */
    fun popmenuItems(obj: PatchObject): List<String> =
        obj.args.drop(22).filter { it != "empty" }

    /** Rewrites the property block + items from the Properties dialog. */
    fun applyPopmenuProperties(
        obj: PatchObject, width: Int, height: Int, fontsize: Int,
        outmode: Int, load: Int, loadbang: Int, label: String, items: List<String>,
    ) {
        ensurePopmenuLayout(obj)
        obj.args[1] = width.coerceIn(24, 800).toString()
        obj.args[2] = height.coerceIn(16, 200).toString()
        obj.args[3] = fontsize.coerceIn(8, 40).toString()
        obj.args[6] = label.ifBlank { "empty" }
        obj.args[12] = outmode.coerceIn(0, 2).toString()
        obj.args[13] = load.toString()
        obj.args[14] = if (loadbang != 0) "1" else "0"
        while (obj.args.size > 22) obj.args.removeAt(obj.args.size - 1)
        for (it0 in items) obj.args.add(it0)
    }

    /**
     * True when a send/receive symbol looks app-generated (drop-time
     * `<cls>-send-<uid>` / load-time `auto-<cls>-<uid>-send`) rather than
     * typed by the user. Clones must regenerate app-generated names — paste
     * twins sharing one auto rcv cross-wire fatally — while user-typed names
     * survive duplication (intentional linking, like desktop pd).
     */
    fun isAutoSymbol(cls: String, sym: String): Boolean =
        sym.isNotEmpty() && Regex("^(auto-)?$cls-(send|rcv)-[0-9a-z]+$").matches(sym)

    /**
     * True when the class carries send/receive symbol tokens (atoms, iemgui
     * widgets, the app-drawn keyboard) — plain objects like osc~ don't, and
     * the Properties dialog hides the rows for them.
     */
    fun hasSymbolSlots(obj: PatchObject): Boolean {
        if (obj.kind == ObjKind.FLOATATOM || obj.kind == ObjKind.SYMBOLATOM) return true
        if (obj.kind != ObjKind.OBJECT) return false
        val cls = obj.className()
        if (isIemGui(cls) || cls == "keyboard" || cls == "button" ||
            cls == "knob" || cls == "popmenu") return true
        return false
    }


    /** Writes send/receive symbols back into an object's token list. */
    fun setSymbols(obj: PatchObject, send: String, receive: String) {
        val cls = obj.className()
        // atoms use "-" as their unbound marker, iemgui widgets use "empty"
        val atom = obj.kind == ObjKind.FLOATATOM || obj.kind == ObjKind.SYMBOLATOM
        val unbound = if (atom) "-" else "empty"
        fun set(idx: Int, v: String) {
            while (obj.args.size <= idx) obj.args.add(unbound)
            obj.args[idx] = if (v.isBlank()) unbound else v.trim()
        }
        when {
            atom -> { set(7, send); set(6, receive) }
            cls == "bng" -> { set(5, send); set(6, receive) }
            cls == "tgl" -> { set(3, send); set(4, receive) }
            cls == "keyboard" -> { set(7, send); set(8, receive) }
            cls == "knob" -> {
                // knob's args are POSITIONAL numbers: slots 1-5 (size/min/
                // max/exp/load) must stay numeric or pd falls into its
                // symbol-args branch and rejects the whole creation
                // ("improper creation arguments" - 0.17.138 regression: a
                // bare [knob] got holes filled with the "empty" symbol)
                fun ensure(idx: Int, default: String) {
                    while (obj.args.size <= idx) obj.args.add("empty")
                    if (obj.args[idx].toFloatOrNull() == null) obj.args[idx] = default
                }
                ensure(1, "50"); ensure(2, "0"); ensure(3, "127")
                ensure(4, "0"); ensure(5, "0")
                set(6, send); set(7, receive)
            }
            cls == "button" -> { set(3, send); set(4, receive) }
            cls == "popmenu" -> { set(8, send); set(7, receive) }
            cls == "hsl" || cls == "vsl" -> {
                val shift = if (obj.args.getOrNull(6)?.toIntOrNull() != null) 1 else 0
                set(6 + shift, send); set(7 + shift, receive)
            }
            cls == "nbx" -> { set(7, send); set(8, receive) }
            cls == "vradio" || cls == "hradio" -> { set(5, send); set(6, receive) }
            cls == "vu" -> set(3, receive)
            cls == "cnv" -> { set(4, send); set(5, receive) }
            else -> return
        }
        obj.meta = resolve(obj)
    }

    /**
     * display/display~: args[1] names the widget's PRIVATE receive channel
     * (the abstraction forwards every inbound message to [send $1] besides
     * the shared pocketpd-display fallback). The canvas assigns one per
     * instance so two displays never mirror each other.
     */
    fun setDisplayTag(obj: PatchObject, tag: String) {
        if (obj.args.size < 2) obj.args.add(tag) else obj.args[1] = tag
    }

    /** [setDisplayTag] for a token list: fills the private receive tag only
     *  when the user did not name one (missing or the "empty" pad token). */
    fun withDisplayTag(args: List<String>, tag: String): List<String> {
        val cur = args.getOrNull(1)
        if (!cur.isNullOrEmpty() && cur != "empty") return args
        val out = ArrayList(args)
        if (out.size < 2) out.add(tag) else out[1] = tag
        return out
    }

    /**
     * Token indices of (label, label-dx, label-dy) for iemgui widgets, or
     * null for objects without a label. Label offsets are in canvas units
     * relative to the widget's top-left corner.
     */
    fun labelIndices(obj: PatchObject): IntArray? {
        if (obj.kind != ObjKind.OBJECT) return null
        val cls = obj.className()
        if (!isIemGui(cls)) return null
        val shift = if ((cls == "hsl" || cls == "vsl") &&
            obj.args.getOrNull(6)?.toIntOrNull() != null
        ) 1 else 0
        return when (cls) {
            "bng" -> intArrayOf(7, 8, 9)
            "tgl" -> intArrayOf(5, 6, 7)
            "hsl", "vsl" -> intArrayOf(8 + shift, 9 + shift, 10 + shift)
            "nbx" -> intArrayOf(9, 10, 11)
            "vradio", "hradio" -> intArrayOf(7, 8, 9)
            "vu" -> intArrayOf(4, 5, 6)
            "cnv" -> intArrayOf(6, 7, 8)
            else -> null
        }
    }

    /** iemgui label offsets (dx, dy) in canvas units, read from the tokens. */
    fun labelOffset(obj: PatchObject): Pair<Int, Int> {
        val idx = labelIndices(obj) ?: return 0 to 0
        return (obj.args.getOrNull(idx[1])?.toIntOrNull() ?: 0) to
            (obj.args.getOrNull(idx[2])?.toIntOrNull() ?: 0)
    }

    /** Writes an iemgui label text and its position offset. */
    fun setLabel(obj: PatchObject, text: String, dx: Int, dy: Int) {
        val idx = labelIndices(obj) ?: return
        while (obj.args.size <= idx[2]) obj.args.add("empty")
        obj.args[idx[0]] = text.trim().ifEmpty { "empty" }
        obj.args[idx[1]] = dx.toString()
        obj.args[idx[2]] = dy.toString()
        obj.meta = resolve(obj)
    }

    /**
     * Float/symbol atoms carry a label plus a position selector
     * (0 left, 1 right, 2 top, 3 bottom) at args[4]/args[5].
     */
    fun atomLabel(obj: PatchObject): Pair<String, Int>? {
        if (obj.kind != ObjKind.FLOATATOM && obj.kind != ObjKind.SYMBOLATOM) return null
        val text = obj.args.getOrNull(5)?.takeIf { it != "-" && it != "empty" } ?: ""
        return text to (obj.args.getOrNull(4)?.toIntOrNull() ?: 0)
    }

    fun setAtomLabel(obj: PatchObject, text: String, pos: Int) {
        if (obj.kind != ObjKind.FLOATATOM && obj.kind != ObjKind.SYMBOLATOM) return
        while (obj.args.size <= 7) obj.args.add("-")
        obj.args[4] = pos.coerceIn(0, 3).toString()
        obj.args[5] = text.trim().ifEmpty { "-" }
        obj.meta = resolve(obj)
    }

    /** Sensible default tokens for freshly created iemgui widgets. */
    fun defaultArgs(cls: String): List<String> = when (cls) {
        "bng" -> listOf("bng", "25", "250", "50", "0", "empty", "empty", "empty", "17", "7", "0", "12", "-262144", "-1", "-1")
        "tgl" -> listOf("tgl", "25", "0", "empty", "empty", "empty", "17", "7", "0", "12", "-262144", "-1", "-1", "0", "1")
        "hsl" -> listOf("hsl", "128", "25", "0", "127", "0", "empty", "empty", "empty", "-2", "-8", "0", "12", "-262144", "-1", "-1", "0", "1")
        "vsl" -> listOf("vsl", "25", "128", "0", "127", "0", "empty", "empty", "empty", "-2", "-8", "0", "12", "-262144", "-1", "-1", "0", "1")
        "nbx" -> listOf("nbx", "4", "14", "0", "0", "0", "0", "empty", "empty", "empty", "0", "-8", "0", "10", "-262144", "-1", "-1", "0", "250")
        "vradio" -> listOf("vradio", "20", "1", "8", "0", "empty", "empty", "empty", "0", "-8", "0", "12", "-262144", "-1", "-1", "0")
        "hradio" -> listOf("hradio", "20", "1", "8", "0", "empty", "empty", "empty", "0", "-8", "0", "12", "-262144", "-1", "-1", "0")
        "vu" -> listOf("vu", "20", "100", "empty", "empty", "-1", "-8", "0", "12", "-66577", "-1", "1", "0")
        "cnv" -> listOf("cnv", "15", "200", "100", "empty", "empty", "empty", "20", "12", "0", "14", "-233017", "-66577", "0")
        else -> listOf(cls)
    }

    // ------------------------------------------------------ object parameters

    /** One editable numeric creation parameter shown in the Properties dialog. */
    class ParamSpec(
        val key: String,
        val index: Int,
        /** Fallback when the token slot is absent or unparseable ("empty"). */
        val def: Int,
    )

    /**
     * Numeric parameters per class, beyond the rows the dialog already
     * offers (send/receive, labels, slider range): mirrors what desktop
     * pd's properties dialog exposes for each widget, plus the app-drawn
     * ELSE widgets' size arguments. Null = no extra numeric rows.
     */
    fun objectParams(obj: PatchObject): List<ParamSpec>? {
        when (obj.kind) {
            ObjKind.FLOATATOM -> return listOf(
                ParamSpec("width", 1, 5),
                ParamSpec("min", 2, 0), // 0 0 = unbounded, as in pd
                ParamSpec("max", 3, 0),
            )
            ObjKind.SYMBOLATOM, ObjKind.LISTBOX -> return listOf(
                ParamSpec("width", 1, 5),
            )
            else -> {}
        }
        if (obj.kind != ObjKind.OBJECT) return null
        return when (obj.className()) {
            "bng" -> listOf(
                ParamSpec("size", 1, 25),
                ParamSpec("hold", 2, 250),
                ParamSpec("interrupt", 3, 50),
            )
            "button" -> listOf(
                ParamSpec("width", 1, 24),
                ParamSpec("height", 2, 24),
            )
            "pad" -> listOf(
                ParamSpec("width", 1, 180),
                ParamSpec("height", 2, 180),
                ParamSpec("gridx", 3, 1),
                ParamSpec("gridy", 4, 1),
            )
            // else knob: the positional numbers its creation syntax demands
            "knob" -> listOf(
                ParamSpec("size", 1, 50),
                ParamSpec("min", 2, 0),
                ParamSpec("max", 3, 127),
                ParamSpec("exp", 4, 0),
                ParamSpec("load", 5, 0),
            )
            "euclid" -> listOf(
                ParamSpec("hits", 1, 3),
                ParamSpec("steps", 2, 8),
                ParamSpec("rotation", 3, 0),
                ParamSpec("width", 4, 64),
                ParamSpec("height", 5, 64),
                ParamSpec("interactive", 6, 1),
            )
            "circle5" -> listOf(
                ParamSpec("key", 1, 1),
                ParamSpec("chord_size", 2, 3),
                ParamSpec("width", 3, 250),
                ParamSpec("height", 4, 250),
                ParamSpec("interactive", 5, 1),
            )
            "tgl" -> listOf(
                ParamSpec("size", 1, 25),
                ParamSpec("nonzero", 14, 1),
            )
            "hsl", "vsl" -> listOf(
                ParamSpec("width", 1, 128),
                ParamSpec("height", 2, 25),
            )
            "vradio", "hradio" -> listOf(
                ParamSpec("size", 1, 20),
                ParamSpec("cells", -1, 8), // -1: legacy count sits at index 3 or 4
            )
            "nbx" -> listOf(
                ParamSpec("width", 1, 4),
                ParamSpec("log_height", 18, 250),
            )
            "vu" -> listOf(
                ParamSpec("width", 1, 20),
                ParamSpec("height", 2, 100),
            )
            "cnv" -> listOf(
                ParamSpec("size", 1, 15),
                ParamSpec("width", 2, 200),
                ParamSpec("height", 3, 100),
            )
            "graph~", "spectrograph~" -> listOf(
                ParamSpec("width", 1, 200),
                ParamSpec("height", 2, 100),
            )
            "keyboard" -> listOf(
                ParamSpec("key_width", 1, 17),
                ParamSpec("height", 2, 80),
                ParamSpec("octaves", 3, 4),
            )
            else -> null
        }
    }
    /** Reads one [ParamSpec]; "empty"/missing tokens fall back to [ParamSpec.def]. */
    fun paramOf(obj: PatchObject, spec: ParamSpec): Int {
        if (spec.key == "cells") return radioCells(obj)
        return obj.args.getOrNull(spec.index)?.toIntOrNull() ?: spec.def
    }

    /**
     * Writes one numeric parameter back into the token list. Padding uses
     * class-appropriate fillers; radios recompute their tiled geometry via
     * [resolve] (cells live at index 3, or 4 in pd-0.54-written files).
     */
    fun setParam(obj: PatchObject, spec: ParamSpec, v: Int) {
        if (spec.key == "cells") {
            // write the count wherever it currently lives (index 3 in
            // app/legacy files, 4 in pd-0.54-written ones with an isa int
            // at 3) so the other slot keeps its meaning
            val idx = if ((obj.args.getOrNull(3)?.toIntOrNull() ?: 0) >= 1) 3 else 4
            while (obj.args.size <= idx) obj.args.add("0")
            obj.args[idx] = v.coerceIn(1, 128).toString()
            obj.meta = resolve(obj)
            return
        }
        if (spec.key == "nonzero") {
            while (obj.args.size < 15) obj.args.add("0")
            obj.args[14] = v.coerceAtLeast(1).toString()
            obj.meta = resolve(obj)
            return
        }
        // euclid writes through the same generic tail, with per-key clamps
        // (steps are capped at 16 by design, rotation wraps into the circle)
        var value = v
        when (obj.className()) {
            "pad" -> value = when (spec.key) {
                "gridx", "gridy" -> v.coerceIn(1, 64)
                "width", "height" -> v.coerceIn(40, 4096)
                else -> v
            }
            "euclid" -> value = when (spec.key) {
                "hits" -> v.coerceIn(0, EuclidRhythm.MAX_STEPS)
                "steps" -> v.coerceIn(1, EuclidRhythm.MAX_STEPS)
                "rotation" -> v.coerceIn(0, EuclidRhythm.MAX_STEPS - 1)
                "interactive" -> if (v != 0) 1 else 0
                else -> v
            }
            "circle5" -> value = when (spec.key) {
                // signed: 1..12 major, -1..-12 the relative minor at that
                // position (0 = C major); the sign is the key's MODE
                "key" -> if (v == 0) 1 else {
                    val pos = Circle5.keyPos(v)
                    if (v < 0) -pos else pos
                }
                // 0/invalid means "default triad" — never a one-note chord
                "chord_size" -> if (v in 1..Circle5.MAX_SIZE) v else 3
                "interactive" -> if (v != 0) 1 else 0
                else -> v
            }
        }
        while (obj.args.size <= spec.index) obj.args.add("0")
        obj.args[spec.index] = value.toString()
        obj.meta = resolve(obj)
    }

    /** Creation arguments after the class name, for the generic args row. */
    fun argumentsOf(obj: PatchObject): String =
        obj.args.drop(1).joinToString(" ")

    /** Replaces an object's creation arguments ([setArguments] text form). */
    fun setArguments(obj: PatchObject, text: String) {
        val toks = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        while (obj.args.size > 1) obj.args.removeAt(obj.args.size - 1)
        obj.args.addAll(toks)
        obj.meta = resolve(obj)
    }

    /**
     * Sets the draw style of a graph block's arrays (pd `#X array` filestyle:
     * 0 = polygon, 1 = points, 2 = bezier curve). For block objects the flag
     * token inside the verbatim `#X array` line is rewritten (saveit bit 0
     * and the hidename bit 3 kept, bits 1-2 replaced) so the change survives
     * saving; `[table]` objects keep the style in a trailing argument
     * (`table name size style` — pd ignores the extra argument).
     */
    fun setArrayStyle(obj: PatchObject, style: Int) {
        val st = style.coerceIn(0, 2)
        obj.graph?.arrays?.forEach { it.style = st }
        val lines = obj.subpatchLines
        if (lines != null) {
            for (i in lines.indices) {
                val t = lines[i].trimEnd(';').trim().split(Regex("\\s+"))
                if ((t.firstOrNull() == "#X" || t.firstOrNull() == "#N") &&
                    t.getOrNull(1) == "array"
                ) {
                    val flags = t.getOrNull(5)?.toIntOrNull() ?: 0
                    val newFlags = (flags and 1) or (st shl 1) or (flags and 8)
                    val out = t.toMutableList()
                    while (out.size <= 5) out.add("0")
                    out[5] = newFlags.toString()
                    lines[i] = out.joinToString(" ") + ";"
                }
            }
        } else if (obj.className() == "table") {
            while (obj.args.size <= 3) obj.args.add("0")
            obj.args[3] = st.toString()
        }
    }

    // ------------------------------------------------------- named variables

    /**
     * The named channels of a patch, for the variable manager: every
     * `[value]`/`[send]`/`[receive]`-family object, array carriers and
     * references, and the send/receive symbols bound into gui widgets and
     * atoms. [nameIndex] is the token slot holding the symbol.
     */
    class VarUse(
        @JvmField val obj: PatchObject,
        @JvmField val role: String,
        /** How the name is rewritten: 0 = args[nameIndex], 1 = send, 2 = receive. */
        @JvmField val mode: Int,
        @JvmField val nameIndex: Int,
    )

    /** Variable-manager roles for one object, or null when it binds no name. */
    fun varUses(obj: PatchObject): List<VarUse>? {
        val cls = obj.className()
        val uses = mutableListOf<VarUse>()
        if (obj.kind == ObjKind.OBJECT) {
            val role = when (cls) {
                "value", "v" -> "value"
                "send", "s" -> "send"
                "receive", "r" -> "receive"
                "send~", "s~" -> "send~"
                "receive~", "r~" -> "receive~"
                "throw~" -> "throw~"
                "catch~" -> "catch~"
                "delwrite~" -> "delay"
                "delread~", "delread4~", "vd~" -> "delay"
                "table" -> "array"
                "array" -> if (obj.args.getOrNull(1) == "define") "array" else null
                else -> null
            }
            if (role != null) {
                // [array define name] carries the name at index 2
                val idx = if (cls == "array") 2 else 1
                obj.args.getOrNull(idx)?.takeIf { it.isNotEmpty() && it != "empty" }?.let {
                    uses.add(VarUse(obj, role, 0, idx))
                }
            }
            // tabread/tabwrite families and soundfiler reference arrays by name
            if (cls in setOf(
                    "tabread", "tabread4", "tabwrite", "tabread~", "tabread4~",
                    "tabwrite~", "tabosc4~", "tabsend~", "tabreceive~",
                )
            ) {
                obj.args.getOrNull(1)?.takeIf { it.isNotEmpty() && it != "empty" }?.let {
                    uses.add(VarUse(obj, "array ref", 0, 1))
                }
            }
        }
        // gui widgets and atoms bind send/receive symbols (resolved "" = unbound)
        if (obj.meta.isGui) {
            if (obj.meta.sendSymbol.isNotEmpty()) {
                uses.add(VarUse(obj, "send", 1, 0))
            }
            if (obj.meta.receiveSymbol.isNotEmpty()) {
                uses.add(VarUse(obj, "receive", 2, 0))
            }
        }
        return uses.ifEmpty { null }
    }

    /** Rewrites one [VarUse] to [newName] and re-resolves the object. */
    fun setVarUse(use: VarUse, newName: String) {
        when (use.mode) {
            0 -> {
                while (use.obj.args.size <= use.nameIndex) use.obj.args.add("empty")
                use.obj.args[use.nameIndex] = newName
            }
            1 -> setSymbols(use.obj, newName, use.obj.meta.receiveSymbol)
            2 -> setSymbols(use.obj, use.obj.meta.sendSymbol, newName)
        }
        use.obj.meta = resolve(use.obj)
    }
}
