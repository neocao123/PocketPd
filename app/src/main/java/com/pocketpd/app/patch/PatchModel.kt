package com.pocketpd.app.patch

import kotlin.math.min

/**
 * Data model for Pure Data patch documents (.pd files).
 *
 * Design goal: lossless round-trip. Every line of the source file that is not an
 * editable object or connection (graph/array/subcanvas blocks, coords, structs,
 * unknown directives...) is kept verbatim as a [RawItem] and written back in its
 * original position. Objects keep their original argument tokens; editing only
 * replaces the tokens, so untouched fields survive a load/save cycle.
 */

enum class ObjKind(val label: String) {
    OBJECT("object"),
    MSG("msg"),
    COMMENT("comment"),
    FLOATATOM("floatatom"),
    SYMBOLATOM("symbolatom"),
    LISTBOX("listbox"),
}

/** Position-independent identifier, stable across edits. */
private var nextObjectId: Long = 1
fun newObjectId(): Long = nextObjectId++

/**
 * Graph-on-parent embedding of an abstraction (mirrors pd's goprect): the
 * visible window in the abstraction's own canvas coordinates starts at
 * (marginX, marginY) and spans w×h units; depth-1 gui widgets/atoms/comments
 * whose box falls fully inside are drawn on the parent box (g_editor.c
 * gobj_shouldvis). [widgets] are app-side display copies with absolute
 * parent-space positions and bound receive/send symbols.
 */
class GopEmbed(
    @JvmField val w: Int,
    @JvmField val h: Int,
    @JvmField val mx: Int,
    @JvmField val my: Int,
    @JvmField val widgets: List<PatchObject> = emptyList(),
)

/**
 * A patchable object box: [ObjKind.OBJECT] (`#X obj`), message (`#X msg`),
 * comment (`#X text`), number/symbol/list atom, or an iemgui widget such as
 * bng/tgl/hsl/vsl/vradio/hradio/vu/cnv (those are stored as OBJECT lines too).
 */
class PatchObject(
    @JvmField val id: Long,
    @JvmField var x: Int,
    @JvmField var y: Int,
    @JvmField val kind: ObjKind,
    /** Tokens after "#X <kind> x y", e.g. ["osc~", "440"] or ["15","250","50",...] for a bng. */
    @JvmField val args: MutableList<String>,
) {
    @JvmField var meta: ObjectMeta = ObjectMeta.EMPTY

    /**
     * For a nested `#N canvas … #X restore` block (subpatch / graph / array):
     * the verbatim block lines, written back instead of an `#X obj` line. The
     * block occupies an object index in pd's parent canvas just like a box,
     * so it is kept as an [PatchObject] with [args] `["pd", name]`.
     */
    @JvmField var subpatchLines: MutableList<String>? = null

    /**
     * Parsed graph-on-parent content of the block (`#X array` + `#A` data +
     * `#X coords`): non-null only for blocks pd would draw as a graph. The
     * canvas renders the arrays and, in run mode, polls live values from pd.
     */
    @JvmField var graph: GraphBlock? = null

    /** Runtime value used by gui widgets in Run mode (toggle state, slider position...). */
    @JvmField var runtimeValue: Float = 0f

    /** Last symbol/list payload received from pd (symbolatom/listbox display). */
    @JvmField var runtimeText: String = ""

    /** Graph-on-parent embedding for abstractions (see [GopEmbed]); null otherwise. */
    @JvmField var gop: GopEmbed? = null

    /** True for GOP display widgets living inside a parent's [gop] box. */
    @JvmField var embedded: Boolean = false

    /** Flash state for bang buttons, driven by messages received from Pd. */
    @JvmField var flashUntil: Long = 0L

    /**
     * Internal relay symbols for app-only widgets ([button]) — deliberately
     * NOT stored in [args]: the Properties send/receive rows stay blank
     * unless the user types a name. The session builder stamps the same
     * names onto the session copies (doc and session objects correspond
     * 1:1 by file order), so touch output and cord relays agree.
     */
    @JvmField var autoSend: String = ""
    @JvmField var autoReceive: String = ""

    fun className(): String = when (kind) {
        ObjKind.OBJECT -> args.firstOrNull() ?: ""
        else -> kind.label
    }

    fun displayName(): String = when (kind) {
        ObjKind.OBJECT -> args.joinToString(" ")
        ObjKind.MSG -> args.joinToString(" ") { displayEscape(it) }
        ObjKind.COMMENT -> commentText()
        // a floatatom fed a LIST (chord voicings from the app widgets...)
        // shows the whole list; the plain-value form wins whenever no list
        // text is pending (receiveFloat clears it)
        ObjKind.FLOATATOM -> if (runtimeText.isNotEmpty()) runtimeText else formatAtomValue(runtimeValue)
        ObjKind.SYMBOLATOM -> if (runtimeText.isNotEmpty()) runtimeText else args.lastOrNull() ?: "symbol"
        ObjKind.LISTBOX -> runtimeText.ifEmpty { "list" }
    }

    /**
     * Comment text for display: unescaped, without pd's trailing font-size
     * attribute (`… , f 74` — 74 is a point size, not part of the sentence).
     */
    private fun commentText(): String {
        val toks = args.map { displayEscape(it) }.toMutableList()
        if (toks.size >= 3 && toks.last().toFloatOrNull() != null && toks[toks.size - 2] == "f") {
            toks.removeAt(toks.size - 1)
            toks.removeAt(toks.size - 1)
            if (toks.lastOrNull() == ",") toks.removeAt(toks.size - 1)
        }
        return toks.joinToString(" ")
    }

    private fun formatAtomValue(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

    /**
     * Inner lines of the inline subpatch block: everything between the
     * `#N canvas` header and the closing `#X restore`. Empty for blocks
     * that carry no content (or a manually typed [pd x] pd never saved).
     */
    fun subpatchInnerLines(): List<String> {
        val lines = subpatchLines ?: return emptyList()
        val inner = lines.dropWhile { it.trimStart().startsWith("#N canvas") }
        return inner.dropLastWhile { it.trimStart().startsWith("#X restore") }
    }

    /**
     * Rebuilds the inline subpatch block from an edited sub-document: the
     * original `#N canvas` header and `#X restore` tail are kept verbatim
     * (toText re-derives the restore position from the box), the middle is
     * re-serialized from [doc]. Works for blocks that had no content yet —
     * header and restore are synthesized then.
     *
     * @return true when the serialized block actually changed (the caller
     *   marks the patch dirty only then — entering and leaving a subpatch
     *   without edits must not trip the unsaved-changes state).
     */
    fun writeSubpatch(doc: PatchDocument): Boolean {
        val header = subpatchLines?.firstOrNull { it.trimStart().startsWith("#N canvas") }
            ?: "#N canvas 0 0 450 300 (subpatch) 0;"
        val restore = subpatchLines?.lastOrNull { it.trimStart().startsWith("#X restore") }
            ?: "#X restore $x $y pd ${args.getOrNull(1) ?: "subpatch"};"
        val content = doc.toText().split('\n').drop(1).filter { it.isNotBlank() }
        val out = ArrayList<String>(content.size + 2)
        out.add(header)
        out.addAll(content)
        out.add(restore)
        val changed = out != subpatchLines
        subpatchLines = out
        return changed
    }

    /**
     * Display lines of a msg box, matching desktop pd: a semicolon starts a
     * new line and sits alone on it ("; pd dsp $1" renders as ";" /
     * "pd dsp $1"); commas stay inline ("a , b" renders as "a, b").
     */
    fun msgDisplayLines(): List<String> {
        val lines = mutableListOf<String>()
        val cur = StringBuilder()
        for (tok in args) {
            val t = displayEscape(tok)
            if (t.startsWith(";")) {
                // ";" starts a new line and sits alone on it; pd also splits
                // glued forms ("\;pd" = ";" then "pd")
                if (cur.isNotEmpty()) {
                    lines.add(cur.toString())
                    cur.setLength(0)
                }
                lines.add(";")
                if (t.length > 1) cur.append(t.substring(1))
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(t)
            }
        }
        lines.add(cur.toString())
        return lines
    }

    companion object {
        /** Render a stored token for display: "\\;" -> ";" etc. */
        fun displayEscape(token: String): String = when {
            token == "\\;" -> ";"
            token == "\\," -> ","
            token.length > 1 && token[0] == '\\' -> token.substring(1)
            else -> token
        }

    /** Escape text typed by the user back into storage tokens. Separators
     *  that are ALREADY escaped ("\;" from a previous edit) are left alone,
     *  so edit cycles never double-escape. */
    fun storeEscape(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tok ->
            tok.replace("(?<!\\\\);".toRegex(), "\\\\;")
                .replace("(?<!\\\\),".toRegex(), "\\\\,")
        }
    }
}

class PatchConnection(
    @JvmField val id: Long,
    /** Ids of [PatchObject]s, resolved at parse time from file order. */
    @JvmField var fromId: Long,
    @JvmField var outlet: Int,
    @JvmField var toId: Long,
    @JvmField var inlet: Int,
    /** Original file indices for a connection read from disk; -1 when new. */
    @JvmField var origFromIdx: Int = -1,
    @JvmField var origToIdx: Int = -1,
)

/**
 * One pd array, parsed from an `#X array` (pd >= 0.49) or `#N array` line
 * inside a graph block. `saved` holds the `#A` contents from the file (shown
 * in edit mode); `live` is the buffer polled from the running pd instance.
 */
class PdArray(
    @JvmField val name: String,
    @JvmField val size: Int,
    /**
     * pd's `#X array` filestyle (flags bits 1-2, garray_save/g_graph.c):
     * 0 = polygon (default line), 1 = points, 2 = bezier curve. Editable
     * in the properties dialog, rewritten back into the file flags.
     */
    @JvmField var style: Int,
    @JvmField val saveContents: Boolean,
) {
    @JvmField var saved: FloatArray = FloatArray(size)
    @JvmField var live: FloatArray? = null
}

/**
 * `#X coords xmin ymax xmax ymin width height gop` — the graph viewport in
 * canvas units: values are plotted with x in [xmin,xmax] and y in
 * [ymin,ymax] (note the file stores the TOP value first), drawn into a
 * width x height box.
 */
class GraphCoords(
    @JvmField val xmin: Float,
    @JvmField val ymax: Float,
    @JvmField val xmax: Float,
    @JvmField val ymin: Float,
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val gop: Int,
)

/** The graph content of a canvas block: its arrays and viewport. */
class GraphBlock(
    @JvmField val arrays: MutableList<PdArray> = mutableListOf(),
    @JvmField var coords: GraphCoords? = null,
)

/** One port (inlet or outlet) of an object. */
data class Port(val objectId: Long, val index: Int, val isInlet: Boolean, val isSignal: Boolean)

/**
 * Hints describing how an object behaves and how it should be drawn.
 * Everything here is derived from the class-name table in [PdObjectMeta];
 * unknown objects get conservative defaults. Port counts are derived, not
 * user-editable.
 */
data class ObjectMeta(
    val className: String,
    val inlets: Int,
    val outlets: Int,
    /** Port indexes (0-based) that carry signals instead of messages. */
    val signalInlets: Set<Int>,
    val signalOutlets: Set<Int>,
    val isGui: Boolean,
    /** Geometry in canvas units for gui widgets (atoms use width in chars). */
    val guiWidth: Int,
    val guiHeight: Int,
    /** iemgui send/receive symbols ("" = unbound). */
    val sendSymbol: String,
    val receiveSymbol: String,
    val labelText: String,
    /** False when pd has no such class and no abstraction provides it —
     *  the box renders with a warning frame and no ports, like pd's
     *  "couldn't create" objects. */
    val exists: Boolean = true,
) {
    fun effInlets(): Int = inlets
    fun effOutlets(): Int = outlets

    companion object {
        val EMPTY = ObjectMeta(
            className = "", inlets = 1, outlets = 1,
            signalInlets = emptySet(), signalOutlets = emptySet(),
            isGui = false, guiWidth = 0, guiHeight = 0,
            sendSymbol = "", receiveSymbol = "", labelText = "",
        )
    }
}

/**
 * The top-level `#X coords` statement of a document: vanilla pd's canvas
 * graph settings, including the graph-on-parent visible window (`gop != 0`).
 * The raw statement is rewritten in place so round-trips keep its position;
 * patches without one get it appended when GOP is first enabled.
 */
class GopRange private constructor(
    @JvmField val raw: RawItem,
    @JvmField var x1: Float,
    @JvmField var y1: Float,
    @JvmField var x2: Float,
    @JvmField var y2: Float,
    @JvmField var w: Int,
    @JvmField var h: Int,
    @JvmField var enabled: Boolean,
    @JvmField var mx: Int,
    @JvmField var my: Int,
) {
    /** Visible-window rect in this canvas's own coordinates. */
    fun rect(): RectI = RectI(mx, my, mx + w, my + h)

    /** Serializes back into the raw statement (kept verbatim on save). */
    fun rewrite() {
        fun f(v: Float) = if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
        raw.lines.clear()
        raw.lines.add(
            "#X coords ${f(x1)} ${f(y1)} ${f(x2)} ${f(y2)} $w $h " +
                (if (enabled) 1 else 0) + " $mx $my;"
        )
    }

    class RectI(@JvmField val left: Int, @JvmField val top: Int,
                @JvmField val right: Int, @JvmField val bottom: Int)

    companion object {
        /** Parses the statement kept in [raw]; null when malformed. */
        fun from(raw: RawItem): GopRange? {
            val t = raw.lines.firstOrNull()?.trimEnd(';')?.trim()
                ?.split(Regex("\\s+")) ?: return null
            fun f(i: Int): Float? = t.getOrNull(i)?.toFloatOrNull()
            val (fx1, fy1, fx2, fy2) = listOf(
                f(2) ?: 0f, f(3) ?: 1f, f(4) ?: 100f, f(5) ?: -1f,
            )
            return GopRange(
                raw,
                x1 = fx1, y1 = fy1, x2 = fx2, y2 = fy2,
                w = (t.getOrNull(6)?.toIntOrNull() ?: 200).coerceIn(1, 4096),
                h = (t.getOrNull(7)?.toIntOrNull() ?: 100).coerceIn(1, 4096),
                enabled = (t.getOrNull(8)?.toIntOrNull() ?: 0) != 0,
                mx = t.getOrNull(9)?.toIntOrNull() ?: 0,
                my = t.getOrNull(10)?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** Header of the top-level canvas: `#N canvas x y w h fontsize;`. */
class CanvasHeader(
    @JvmField val tokens: MutableList<String>,
) {
    @JvmField var windowX: Int = tokens.getOrNull(2)?.toIntOrNull() ?: 0
    @JvmField var windowY: Int = tokens.getOrNull(3)?.toIntOrNull() ?: 0
    @JvmField var width: Int = tokens.getOrNull(4)?.toIntOrNull() ?: 600
    @JvmField var height: Int = tokens.getOrNull(5)?.toIntOrNull() ?: 400
    @JvmField var fontSize: Int = tokens.getOrNull(6)?.toIntOrNull() ?: 10

    fun toLine(): String = "#N " + tokens.drop(1).joinToString(" ") + ";"
}

/** Document entry. Either an editable object or an opaque verbatim block. */
sealed class DocItem

class ObjectItem(@JvmField val obj: PatchObject) : DocItem()

/**
 * A connection read from the file, kept at its original position in the item
 * stream: pd resolves `#X connect` indices against objects created so far in
 * the enclosing canvas, so hoisting connections to the end of the file
 * re-wires subpatch-scoped cords to the wrong objects. [origFromIdx] and
 * [origToIdx] are the indices as they appeared on disk.
 */
class ConnectionItem(
    @JvmField val conn: PatchConnection,
    @JvmField val origFromIdx: Int,
    @JvmField val origToIdx: Int,
) : DocItem()

/**
 * Verbatim block (graph/array data, coords, unknown directives). Kept exactly
 * as parsed so that files using advanced features still round-trip.
 */
class RawItem(val lines: MutableList<String>) : DocItem()

class PatchDocument private constructor() {
    @JvmField var header: CanvasHeader = CanvasHeader(
        mutableListOf("#N", "canvas", "0", "0", "600", "400", "10")
    )
    @JvmField val items = ArrayList<DocItem>()
    @JvmField val connections = ArrayList<PatchConnection>()

    /** Top-level `#X coords` (graph-on-parent window), when the patch has one. */
    @JvmField var gop: GopRange? = null

    /**
     * Appends a fresh `#X coords` statement enabling graph-on-parent, for
     * patches that never had one (data range mirrors pd's default graph).
     */
    fun createGop(w: Int, h: Int): GopRange {
        gop?.let { return it }
        val raw = RawItem(mutableListOf("#X coords 0 1 100 -1 $w $h 1 0 0;"))
        items.add(raw)
        return GopRange.from(raw)!!.also { gop = it }
    }

    fun objects(): List<PatchObject> = items.mapNotNull { (it as? ObjectItem)?.obj }

    fun objectById(id: Long): PatchObject? = objects().firstOrNull { it.id == id }

    fun connectionsOf(id: Long): List<PatchConnection> =
        connections.filter { it.fromId == id || it.toId == id }

    fun add(obj: PatchObject) {
        items.add(ObjectItem(obj))
    }

    fun remove(obj: PatchObject) {
        items.removeAll { it is ObjectItem && it.obj.id == obj.id }
        items.removeAll { it is ConnectionItem && (it.conn.fromId == obj.id || it.conn.toId == obj.id) }
        connections.removeAll { it.fromId == obj.id || it.toId == obj.id }
    }

    fun addConnection(from: PatchObject, outlet: Int, to: PatchObject, inlet: Int): PatchConnection? {
        if (from.id == to.id) return null
        if (outlet < 0 || inlet < 0) return null
        if (outlet >= from.meta.effOutlets() || inlet >= to.meta.effInlets()) return null
        val dup = connections.any {
            it.fromId == from.id && it.outlet == outlet && it.toId == to.id && it.inlet == inlet
        }
        if (dup) return null
        val c = PatchConnection(newObjectId(), from.id, outlet, to.id, inlet)
        connections.add(c)
        return c
    }

    /** File-order index of an object, used when serializing `#X connect` lines. */
    private fun fileIndex(): Map<Long, Int> {
        var i = 0
        val map = HashMap<Long, Int>()
        for (item in items) {
            val obj = (item as? ObjectItem)?.obj ?: continue
            map[obj.id] = i++
        }
        return map
    }

    fun toText(): String {
        val sb = StringBuilder()
        sb.append(header.toLine()).append('\n')
        // pd's `#X connect` indices count every object created in the canvas,
        // subpatch/graph blocks included — walk the stream assigning indices
        // in exactly that order.
        val index = HashMap<Long, Int>()
        var i = 0
        val placed = HashSet<Long>()
        for (item in items) {
            when (item) {
                is ObjectItem -> {
                    val o = item.obj
                    index[o.id] = i++
                    val lines = o.subpatchLines
                    if (lines != null) {
                        emitSubpatchBlock(sb, o, lines)
                    } else {
                        // atoms carry their kind as args[0] for internal use;
                        // `#X floatatom` already names the kind when writing
                        val args = if (o.kind == ObjKind.FLOATATOM || o.kind == ObjKind.SYMBOLATOM ||
                            o.kind == ObjKind.LISTBOX
                        ) o.args.drop(1) else o.args
                        sb.append("#X ").append(kindToken(o.kind)).append(' ')
                            .append(o.x).append(' ').append(o.y)
                        // pd's file format escapes '$' inside atoms
                        // (atom_string): a bare $1 in a file is substituted
                        // with the EMPTY load message before ever reaching the
                        // box, killing abstraction args / $0 bindings / msg
                        // templates. Tokens are stored unescaped internally,
                        // so every written $ gets exactly one backslash.
                        for (tok in args) {
                            sb.append(' ')
                            if ('$' in tok) sb.append(tok.replace("$", "\\$"))
                            else sb.append(tok)
                        }
                        sb.append(";\n")
                    }
                }
                is ConnectionItem -> {
                    val c = item.conn
                    if (c !in connections) continue // deleted in the editor
                    val from = index[c.fromId] ?: continue
                    val to = index[c.toId] ?: continue
                    placed.add(c.id)
                    sb.append("#X connect ").append(from).append(' ').append(c.outlet)
                        .append(' ').append(to).append(' ').append(c.inlet).append(";\n")
                }
                is RawItem -> for (line in item.lines) {
                    sb.append(line)
                    if (!line.endsWith(";")) sb.append(';')
                    sb.append('\n')
                }
            }
        }
        // connections created in the editor have no file position; append them
        for (c in connections) {
            if (c.id in placed) continue
            val from = index[c.fromId] ?: continue
            val to = index[c.toId] ?: continue
            sb.append("#X connect ").append(from).append(' ').append(c.outlet)
                .append(' ').append(to).append(' ').append(c.inlet).append(";\n")
        }
        return sb.toString()
    }

    /** Writes a `#N canvas … #X restore` block, updating the restore position.
     *  Only the block's OWN restore (the last one) is rewritten: nested
     *  subpatch restores carry their own boxes' positions, and pd places each
     *  subpatch box from its restore coords — clobbering them stacked every
     *  nested box at the outer box's spot (0.17.147). */
    private fun emitSubpatchBlock(sb: StringBuilder, o: PatchObject, lines: List<String>) {
        var lastRestore = -1
        for ((k, line) in lines.withIndex()) {
            if (line.trimEnd(';').trim().startsWith("#X restore")) lastRestore = k
        }
        for ((k, line) in lines.withIndex()) {
            val trimmed = line.trimEnd(';').trim()
            if (k == lastRestore && trimmed.startsWith("#X restore")) {
                val tok = trimmed.split(Regex("\\s+")).toMutableList()
                if (tok.size > 3) {
                    tok[2] = o.x.toString()
                    tok[3] = o.y.toString()
                }
                sb.append(tok.joinToString(" ")).append(";\n")
            } else {
                sb.append(line)
                if (!line.endsWith(";")) sb.append(';')
                sb.append('\n')
            }
        }
    }

    private fun kindToken(kind: ObjKind) = when (kind) {
        ObjKind.OBJECT -> "obj"
        ObjKind.MSG -> "msg"
        ObjKind.COMMENT -> "text"
        ObjKind.FLOATATOM -> "floatatom"
        ObjKind.SYMBOLATOM -> "symbolatom"
        ObjKind.LISTBOX -> "listbox"
    }

    companion object {
        /**
         * Parse a .pd document. Malformed statements are kept as raw items so no
         * content is ever dropped; [errors] collects human-readable complaints.
         */
        fun parse(text: String, errors: MutableList<String>): PatchDocument {
            val doc = PatchDocument()
            val order = ArrayList<Long>() // file-order object ids, for connect resolution
            val statements = splitStatements(text)
            var headerSeen = false
            var i = 0
            while (i < statements.size) {
                val stmt = statements[i]
                val tok = tokenize(stmt)
                if (tok.isEmpty()) {
                    i++
                    continue
                }
                when {
                    !headerSeen && tok[0] == "#N" && tok.getOrNull(1) == "canvas" -> {
                        doc.header = CanvasHeader(tok.toMutableList())
                        headerSeen = true
                    }
                    // A nested canvas (graph, array, data-structure view) mid-file:
                    // keep the block verbatim but model it as an object — it
                    // occupies an index in pd's parent canvas and receives cords.
                    tok[0] == "#N" && tok.getOrNull(1) == "canvas" -> {
                        i += swallowCanvasBlock(doc, statements, i, order)
                        continue
                    }
                    tok[0] == "#X" -> {
                        val consumed = tryConsumeX(doc, tok, order, statements, i, errors)
                        if (consumed > 1) {
                            i += consumed
                            continue
                        }
                    }
                    else -> doc.items.add(RawItem(mutableListOf(stmt.trim() + ";")))
                }
                i++
            }
            return doc
        }

        /**
         * Handles one `#X ...` statement. Returns how many statements were consumed:
         * 1 for a plain object line, or the length of a nested `#N canvas ... #X restore`
         * block that was swallowed into a raw item.
         */
        private fun tryConsumeX(
            doc: PatchDocument,
            tok: List<String>,
            order: MutableList<Long>,
            statements: List<String>,
            at: Int,
            errors: MutableList<String>,
        ): Int {
            val kind = tok.getOrNull(1)
            val objLine = when (kind) {
                "obj" -> when (tok.getOrNull(4)) {
                    // pd registers the gatom names as creators, so an "#X obj"
                    // line naming one IS an atom — files from other editors and
                    // LLM output often use this form. Normalize to the atom
                    // kinds so the box renders/edits as a number box instead of
                    // a raw object ("intatom" exists in no pd flavour; LLMs
                    // invent it, and it means a whole-number floatatom).
                    // tok.drop(4) already starts with the kind token — atoms
                    // keep it as args[0], so NO includeKind here (prepending
                    // again duplicated it on save).
                    "floatatom", "intatom" -> makeObject(tok, ObjKind.FLOATATOM)
                    "symbolatom" -> makeObject(tok, ObjKind.SYMBOLATOM)
                    "listbox" -> makeObject(tok, ObjKind.LISTBOX)
                    else -> makeObject(tok, ObjKind.OBJECT)
                }
                "msg" -> makeObject(tok, ObjKind.MSG)
                "text" -> makeObject(tok, ObjKind.COMMENT)
                // atoms keep their kind token as args[0], matching obj lines
                "floatatom" -> makeObject(tok, ObjKind.FLOATATOM, includeKind = true)
                "symbolatom" -> makeObject(tok, ObjKind.SYMBOLATOM, includeKind = true)
                "listbox" -> makeObject(tok, ObjKind.LISTBOX, includeKind = true)
                else -> null
            }
            if (objLine != null) {
                objLine.meta = PdObjectMeta.resolve(objLine)
                doc.items.add(ObjectItem(objLine))
                order.add(objLine.id)
                return 1
            }
            if (kind == "connect") {
                val from = tok.getOrNull(2)?.toIntOrNull()
                val outlet = tok.getOrNull(3)?.toIntOrNull()
                val to = tok.getOrNull(4)?.toIntOrNull()
                val inlet = tok.getOrNull(5)?.toIntOrNull()
                if (from == null || outlet == null || to == null || inlet == null) {
                    errors.add("bad #X connect: $tok")
                } else {
                    val fromId = order.getOrNull(from)
                    val toId = order.getOrNull(to)
                    if (fromId == null || toId == null) {
                        errors.add("#X connect references unknown object ($from,$to)")
                    } else {
                        val conn = PatchConnection(
                            newObjectId(), fromId, outlet, toId, inlet, from, to
                        )
                        doc.connections.add(conn)
                        doc.items.add(ConnectionItem(conn, from, to))
                    }
                }
                return 1
            }
            if (kind == "coords") {
                // top-level graph settings; statement reaches here only when
                // it is not inside a swallowed subpatch block
                val raw = RawItem(mutableListOf(statements[at].trim() + ";"))
                doc.items.add(raw)
                GopRange.from(raw)?.let { doc.gop = it }
                return 1
            }
            // Unknown statement (`#X declare`, stray `#X restore`, ...):
            // keep it verbatim as a one-line raw item.
            doc.items.add(RawItem(mutableListOf(statements[at].trim() + ";")))
            return 1
        }

        /**
         * Gathers a nested `#N canvas … #X restore` block, models it as a
         * pseudo object (args `["pd", name]`, positioned at the restore), and
         * registers it in the object order so connect indices stay faithful.
         * Returns the number of statements consumed.
         */
        private fun swallowCanvasBlock(
            doc: PatchDocument,
            statements: List<String>,
            at: Int,
            order: MutableList<Long>,
        ): Int {
            val lines = ArrayList<String>()
            var depth = 0
            var j = at
            while (j < statements.size) {
                val tok = tokenize(statements[j])
                val isNCanvas = tok.firstOrNull() == "#N" && tok.getOrNull(1) == "canvas"
                val isRestore = tok.firstOrNull() == "#X" && tok.getOrNull(1) == "restore"
                if (isNCanvas) depth++
                if (isRestore) depth--
                lines.add(statements[j].trim() + ";")
                j++
                if (depth <= 0 && j > at + 1) break // closing restore (or malformed EOF)
            }
            val restore = lines.lastOrNull { it.trimStart().startsWith("#X restore") }
                ?.trimEnd(';')?.trim()?.split(Regex("\\s+"))?.map(::unescapeDollars)
            val x = restore?.getOrNull(2)?.toIntOrNull() ?: 0
            val y = restore?.getOrNull(3)?.toIntOrNull() ?: 0
            // "#X restore x y pd name" carries the name; otherwise fall back to
            // the glist name on the "#N canvas x y w h name" line ("(graph)"…).
            val name = when {
                restore?.indexOf("pd")?.let { it >= 0 } == true ->
                    restore.drop(restore.indexOf("pd") + 1).joinToString(" ").ifEmpty { "subpatch" }
                else -> tokenize(lines.first()).getOrNull(6)?.trim('(', ')') ?: "subpatch"
            }
            val obj = PatchObject(newObjectId(), x, y, ObjKind.OBJECT, mutableListOf("pd", name))
            obj.subpatchLines = lines
            obj.meta = PdObjectMeta.resolve(obj)
            // A GOP block with arrays is drawn as a graph; every block exposes
            // the ports of its own nested inlet/outlet objects.
            val block = parseGraphBlock(lines)
            val ports = blockPorts(lines)
            if (ports.inlets > 0 || ports.outlets > 0) {
                obj.meta = obj.meta.copy(
                    inlets = ports.inlets, outlets = ports.outlets,
                    signalInlets = ports.signalInlets, signalOutlets = ports.signalOutlets,
                )
            }
            if (block.arrays.isNotEmpty() && (block.coords?.gop ?: 0) != 0) {
                obj.graph = block
            }
            doc.items.add(ObjectItem(obj))
            order.add(obj.id)
            return j - at
        }

        /**
         * Scans a canvas block for array declarations (`#X array` / `#N
         * array`), saved contents (`#A <startIdx> v1 v2 …`) and the viewport
         * (`#X coords`). Returns an empty block when none are present.
         */
        private fun parseGraphBlock(lines: List<String>): GraphBlock {
            val block = GraphBlock()
            var current: PdArray? = null
            for (raw in lines) {
                val t = raw.trimEnd(';').trim().split(Regex("\\s+"))
                when {
                    (t.getOrNull(0) == "#X" || t.getOrNull(0) == "#N") && t.getOrNull(1) == "array" -> {
                        val name = t.getOrNull(2) ?: continue
                        val size = (t.getOrNull(3)?.toIntOrNull() ?: 100).coerceIn(1, 1 shl 22)
                        val flags = t.getOrNull(5)?.toIntOrNull() ?: 0
                        val arr = PdArray(name, size, (flags shr 1) and 3, flags and 1 != 0)
                        block.arrays.add(arr)
                        current = arr
                    }
                    t.getOrNull(0) == "#A" -> {
                        val arr = current ?: continue
                        val start = t.getOrNull(2)?.toIntOrNull() ?: continue
                        if (start < 0 || start >= arr.size) continue
                        val n = min(t.size - 3, arr.size - start)
                        for (k in 0 until n) {
                            arr.saved[start + k] = t.getOrNull(3 + k)?.toFloatOrNull() ?: 0f
                        }
                    }
                    t.getOrNull(0) == "#X" && t.getOrNull(1) == "coords" -> {
                        fun f(i: Int) = t.getOrNull(i)?.toFloatOrNull()
                        val xmin = f(2) ?: continue
                        block.coords = GraphCoords(
                            xmin = xmin,
                            ymax = f(3) ?: 1f,
                            xmax = f(4) ?: 100f,
                            ymin = f(5) ?: -1f,
                            width = (f(6) ?: 200f).toInt().coerceAtLeast(1),
                            height = (f(7) ?: 100f).toInt().coerceAtLeast(1),
                            gop = (f(8) ?: 1f).toInt(),
                        )
                    }
                }
            }
            return block
        }

        /**
         * Recomputes a block object's port hints from its CURRENT lines.
         * [swallowCanvasBlock] derives them once at parse time; after the user
         * adds/removes inlet/outlet objects inside the subpatch and pops back
         * out, the parent-side stubs must follow (they stayed stale until a
         * full reopen — user report 2026-11-04).
         */
        fun refreshBlockPorts(obj: PatchObject) {
            val lines = obj.subpatchLines ?: return
            val ports = blockPorts(lines)
            obj.meta = obj.meta.copy(
                inlets = ports.inlets, outlets = ports.outlets,
                signalInlets = ports.signalInlets, signalOutlets = ports.signalOutlets,
            )
        }

        /**
         * Counts the block's own inlet/inlet~/outlet/outlet~ objects (canvas
         * depth 1 inside the block) so the pseudo object draws the same ports
         * pd gives the subpatch/graph.
         */
        private class BlockPorts(
            val inlets: Int,
            val outlets: Int,
            val signalInlets: Set<Int>,
            val signalOutlets: Set<Int>,
        )

        private fun blockPorts(lines: List<String>): BlockPorts {
            var depth = 0
            var ins = 0
            var outs = 0
            val sigIn = HashSet<Int>()
            val sigOut = HashSet<Int>()
            for (raw in lines) {
                val t = raw.trimEnd(';').trim().split(Regex("\\s+"))
                when {
                    t.getOrNull(0) == "#N" && t.getOrNull(1) == "canvas" -> depth++
                    t.getOrNull(0) == "#X" && t.getOrNull(1) == "restore" -> depth--
                    t.getOrNull(0) == "#X" && t.getOrNull(1) == "obj" && depth <= 1 ->
                        when (t.getOrNull(4)?.trimEnd(';')) {
                            "inlet" -> ins++
                            "inlet~" -> { sigIn.add(ins); ins++ }
                            "outlet" -> outs++
                            "outlet~" -> { sigOut.add(outs); outs++ }
                        }
                }
            }
            return BlockPorts(ins, outs, sigIn.toSet(), sigOut.toSet())
        }

        private fun makeObject(
            tok: List<String>,
            kind: ObjKind,
            includeKind: Boolean = false,
        ): PatchObject? {
            val x = tok.getOrNull(2)?.toIntOrNull() ?: return null
            val y = tok.getOrNull(3)?.toIntOrNull() ?: return null
            val args = ArrayList<String>(tok.drop(4))
            // atoms (#X floatatom/symbolatom) don't carry their kind in the
            // stream the way "#X obj" lines do - normalize so args[0] is
            // always the class token for every object kind.
            if (includeKind) args.add(0, tok[1])
            return PatchObject(newObjectId(), x, y, kind, args)
        }

        /** Splits file text into `;`-terminated statements; "\\;" is not a terminator. */
        internal fun splitStatements(text: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var escaped = false
            for (ch in text) {
                if (escaped) {
                    sb.append(ch)
                    escaped = false
                    continue
                }
                when (ch) {
                    '\\' -> {
                        sb.append(ch)
                        escaped = true
                    }
                    ';' -> {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotBlank()) out.add(sb.toString())
            return out
        }

        /** Canonical token form: dollars unescaped ("\$1" from pd-written
         *  files → "$1"); toText re-escapes on write. "\;" / "\," keep their
         *  escapes (they are separators, not dollars). */
        private fun unescapeDollars(tok: String): String =
            if ("\\$" in tok) tok.replace("\\$", "$") else tok

        internal fun tokenize(statement: String): List<String> =
            statement.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .map(::unescapeDollars)
    }
}
