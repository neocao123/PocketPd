package com.pocketpd.app.editor

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.DragEvent
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.pocketpd.app.R
import com.pocketpd.app.patch.ObjKind
import com.pocketpd.app.patch.Circle5
import com.pocketpd.app.patch.EuclidRhythm
import com.pocketpd.app.patch.PatchConnection
import com.pocketpd.app.patch.PatchDocument
import com.pocketpd.app.patch.PatchObject
import com.pocketpd.app.patch.Port
import com.pocketpd.app.patch.PdObjectMeta
import com.pocketpd.app.patch.newObjectId
import com.pocketpd.app.runtime.PdEngine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Touch-first patch editor canvas (all geometry in Pd canvas units; the view
 * maps units -> pixels with a pan offset and pinch zoom).
 *
 * Touch language (Material guidance: 48 dp minimum targets, direct
 * manipulation, long-press for contextual actions):
 *
 *  Edit mode
 *   - drag empty area: pan; two-finger: pinch zoom + pan
 *   - tap object: select; drag object: move (whole selection)
 *   - drag from an inlet/outlet: draw a patch cord, release on a port to connect
 *   - tap a cord: delete it (desktop Pd behavior)
 *   - double-tap object: edit text; double-tap empty: zoom to fit
 *   - long-press object: context menu; long-press empty: add menu / marquee select
 *  Run mode
 *   - gui widgets are live: bang/toggle/radio tap, sliders drag, number boxes
 *     tap for a keypad; the canvas still pans from empty areas
 */
class PatchCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { EDIT, RUN }
    enum class Tool { PAN, SELECT }

    interface Callbacks {
        fun onSelectionChanged(selected: Set<Long>)
        fun onEditObjectRequested(obj: PatchObject)
        /** A palette item was dropped onto the canvas; obj is a fresh placeholder. */
        fun onObjectDropped(obj: PatchObject)
        /** A message box was tapped in run mode; fire it into the flow. */
        fun onMsgTriggered(obj: PatchObject)
        fun onObjectLongPress(obj: PatchObject, screenX: Float, screenY: Float)
        /** Ctrl+left-click on EMPTY canvas (desktop-pd convention,
         *  user-requested): toggles edit/run. Not fired over objects,
         *  ports or cords, and never in LOCK mode. */
        fun onCtrlTapEmpty()
        fun onGuiTouched(obj: PatchObject, value: Float, isBang: Boolean)
        /** Run-mode long-press on a parametered widget: Properties dialog. */
        fun onRunProperties(obj: PatchObject)
        /** ELSE-compat [keyboard]: a key went down/up. */
        fun onKeyboardKey(obj: PatchObject, pitch: Int, down: Boolean)
        /** App-only [pad]: a list event left one of its outlets
         *  (0: "x y e" finger stream, 1: "col row e" grid stream). */
        fun onGuiList(obj: PatchObject, outlet: Int, args: List<Any>)
        /** App-only [popmenu]: the picked item as a plain symbol message. */
        fun onGuiSymbol(obj: PatchObject, symbol: String)
        fun onNumberEntryRequested(obj: PatchObject)
        fun onDirtyChanged(dirty: Boolean)
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
        fun onToolChanged(tool: Tool)
    }

    @JvmField
    var callbacks: Callbacks? = null

    @JvmField
    var document: PatchDocument? = null

    @JvmField
    var mode: Mode = Mode.EDIT

    /** LOCK mode (hold the OSD badge): a run flavor where the canvas cannot
     *  be panned or zoomed — widgets stay interactive for playing. */
    var locked: Boolean = false

    /** Widget touches behave like run mode in LOCK too. */
    private val interactive: Boolean get() = mode == Mode.RUN || locked

    var tool: Tool = Tool.PAN
        set(value) {
            field = value
            callbacks?.onToolChanged(value)
        }

    private val stack = CommandStack { canUndo, canRedo ->
        invalidate()
        markDirty()
        callbacks?.onHistoryChanged(canUndo, canRedo)
    }

    private val selection = HashSet<Long>()

    // camera: screen = canvas * zoom + (camX, camY)
    private var zoom = resources.displayMetrics.density
    private var camX = 40f * resources.displayMetrics.density
    private var camY = 40f * resources.displayMetrics.density

    private var dirty = false

    // ---------------------------------------------------------------- paints

    /** F5.6 pixel Latin for all canvas text (design/pc98-analog §4); glyphs
     *  the font lacks (CJK) fall through to the system font. */
    private val uiFont: Typeface? = runCatching {
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.pd98_latin)
    }.getOrNull()

    private val bgPaint = Paint().apply { color = COL_BG }
    private val gridMinor = Paint().apply { color = COL_GRID; strokeWidth = dp(1f) }
    private val gridMajor = Paint().apply { color = COL_GRID_MAJOR; strokeWidth = dp(1f) }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_BOX_BG }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_BOX_STROKE; style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_SELECT; style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_TEXT; typeface = uiFont ?: Typeface.MONOSPACE
    }
    private val commentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_COMMENT; typeface = uiFont ?: Typeface.SANS_SERIF
    }
    private val cordMsg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_CORD_MSG; style = Paint.Style.STROKE; strokeWidth = dp(1.6f)
    }
    private val cordSig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_CORD_SIG; style = Paint.Style.STROKE; strokeWidth = dp(3.2f)
    }
    // signal-outlet-to-control-inlet cords (pd refuses them with "signal
    // outlet connect to nonsignal inlet"): signal weight in error red
    private val cordError = Paint(cordSig).apply { color = COL_CORD_ERROR }

    /** Scope-widget trace: thinner than a cord — the waveform is content,
     *  not wiring. */
    private val scopeTrace = Paint(cordSig).apply { strokeWidth = dp(1.2f) }
    private val cordPending = Paint(cordMsg).apply { color = COL_SELECT }
    private val cordSelected = Paint(cordMsg).apply {
        color = COL_SELECT; strokeWidth = dp(4f)
    }
    private val portBodyEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_BG }
    private val portFillMsg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_BOX_STROKE }
    private val portFillSig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_CORD_SIG }
    private val portStrokeMsg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_BOX_STROKE; style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val portStrokeSig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_CORD_SIG; style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val portHitRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_SELECT; style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val marqueeFill = Paint().apply { color = 0x2EFC54FC }
    private val marqueeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_SELECT; strokeWidth = dp(1.5f); style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    /** Vanilla pd draws a red frame marking a GOP canvas's visible range. */
    private val gopRangeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_GOP_RANGE; style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val path = Path()
    private val graphFill = Paint().apply { color = COL_GRAPH_BG }
    private val graphAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40A8A8C0; strokeWidth = dp(1f)
    }
    private val graphCurve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_SELECT; style = Paint.Style.STROKE; strokeWidth = dp(1.6f)
    }
    private val graphDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_SELECT; strokeWidth = dp(2.5f); strokeCap = Paint.Cap.ROUND
    }

    // ------------------------------------------------------------ geometry

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun units(v: Float) = v * zoom
    private fun screenX(x: Float) = x * zoom + camX
    private fun screenY(y: Float) = y * zoom + camY

    private fun toCanvasPx(mx: Float, my: Float) = floatArrayOf(
        (mx - camX) / zoom,
        (my - camY) / zoom,
    )

    /** Bounds in canvas units. */
    fun objRect(o: PatchObject): RectF {
        o.gop?.let { g ->
            // graph-on-parent abstraction: fixed w×h box, pd-style
            return RectF(o.x.toFloat(), o.y.toFloat(),
                (o.x + g.w).toFloat(), (o.y + g.h).toFloat())
        }
        val g = graphBlock(o)
        if (g != null) {
            val c = g.coords!!
            return RectF(o.x.toFloat(), o.y.toFloat(),
                (o.x + c.width).toFloat(), (o.y + c.height).toFloat())
        }
        val m = o.meta
        if (o.kind == ObjKind.FLOATATOM || o.kind == ObjKind.SYMBOLATOM || o.kind == ObjKind.LISTBOX) {
            // number boxes grow with their displayed value, like desktop pd:
            // at least the configured character width, more when needed.
            // guiWidth is already chars * CHAR_UNIT(7); desktop adds its
            // ATOM_RMARGIN (g_text.c) on top, we do the same
            textPaint.textSize = FS_BOX
            val w = max(m.guiWidth + 2f, textPaint.measureText(o.displayName()) + 14f)
            return RectF(o.x.toFloat(), o.y.toFloat(), o.x + w, o.y + m.guiHeight.toFloat())
        }
        if (o.kind == ObjKind.MSG) {
            // one line per top-level comma, like desktop pd
            textPaint.textSize = FS_BOX
            val lines = o.msgDisplayLines()
            // glyph leg covers the widest measured line plus the fold
            // reserve (depth 6 + margin 12); lines draw in full - ellipsis
            // is forbidden
            val w = max(lines.maxOf { max(it.length * 7f + 4f,
                textPaint.measureText(it) + 18f) }, 34f)
            val h = BOX_H + (lines.size - 1) * 20f
            return RectF(o.x.toFloat(), o.y.toFloat(), o.x + w, o.y + h)
        }
        if (m.isGui && m.guiWidth > 0) {
            return RectF(o.x.toFloat(), o.y.toFloat(),
                (o.x + m.guiWidth).toFloat(), (o.y + m.guiHeight).toFloat())
        }
        if (o.kind == ObjKind.COMMENT) {
            textPaint.textSize = FS_COMMENT
            // pd comments run at natural width (T_TEXT: ncolumns*fontwidth
            // + LMARGIN+RMARGIN, no cap) - a capped rect lets the drawn
            // glyphs spill into the neighbouring column; zoomToFit clamps
            // its own bounds instead
            val s = o.displayName()
            val w = max(max(s.length * 7f + 4f, textPaint.measureText(s) + 6f), 16f)
            return RectF(o.x.toFloat(), o.y.toFloat(), o.x + w, o.y + 16f)
        }
        textPaint.textSize = FS_BOX
        // else/display and display~: the box IS the display — size it to the
        // shown message (runtimeText), not the bare object name, so the
        // frame actually encloses the content. The empty placeholder is the
        // bare class name: the private receive tag lives in args but must
        // not render (desktop pd draws an empty-content shape).
        val isDisplay =
            o.className().substringAfterLast('/') in setOf("display", "display~")
        val shown = when {
            isDisplay && o.runtimeText.isNotEmpty() -> o.runtimeText
            isDisplay -> o.className().substringAfterLast('/')
            else -> o.displayName()
        }
        // desktop pd proportions (0.57 g_rtext.c rtext_formattext): width =
        // ncolumns*fontwidth + LMARGIN(2)+RMARGIN(2), objects padded to
        // >= 3 columns. Our canvas units ARE pd's font-12 column (CHAR_UNIT
        // 7 = fi_width; BOX_H 22 ~ fontheight 16 + TMARGIN 3 + BMARGIN 2),
        // so nchars*7+4 puts the box exactly where desktop pd's help patches
        // assume it; the glyph-measured leg (+12 margin, text is drawn
        // in full - ellipsis is forbidden) only widens wide-glyph runs past
        // the mono allotment, 34 keeps the tested minimum footprint
        val w = max(max(shown.length * 7f + 4f,
            textPaint.measureText(shown) + 12f), 34f)
        // a crowded inlet row must not overlap itself (adsr has 6 inlets on a
        // 4-letter box): keep the even port spread, just widen the box so the
        // spread has room. 12 units per port keeps the 10-unit port rects apart.
        val ports = max(o.meta.effInlets(), o.meta.effOutlets())
        return RectF(o.x.toFloat(), o.y.toFloat(),
            o.x + max(w, ports * 12f + 12f), o.y + BOX_H)
    }

    /**
     * The graph to draw for [o]: a GOP canvas block with arrays, or a [table]
     * object (pd sizes its built-in graph 200x100; the array is named by the
     * first argument). Null for everything else. Internal (not private) so the
     * activity's array poller can synthesize a [table] graph before the first
     * draw and poll its pd array.
     */
    fun graphBlock(o: PatchObject): com.pocketpd.app.patch.GraphBlock? {
        // A [table]'s graph is synthesized from its arguments, so it must be
        // re-synced against them on every query: renaming the array (edit
        // text / properties args row) rewrites args but pd has no live array
        // rename — the drawn name would otherwise stay the old cached one.
        // subpatchLines != null means a parse-derived canvas block named like
        // the class; its graph is file content, never synthesized here.
        if (o.kind == ObjKind.OBJECT && o.className() == "table" &&
            o.subpatchLines == null
        ) {
            val name = o.args.getOrNull(1) ?: return null
            val size = (o.args.getOrNull(2)?.toIntOrNull() ?: 100).coerceIn(1, 1 shl 22)
            // app extension: a trailing [table name size style] argument
            // selects the draw style (pd ignores the extra argument)
            val style = (o.args.getOrNull(3)?.toIntOrNull() ?: 0).coerceIn(0, 2)
            val cached = o.graph
            val arr = cached?.arrays?.firstOrNull()
            if (cached == null || cached.coords == null || arr == null ||
                arr.name != name || arr.size != size || arr.style != style
            ) {
                val g = com.pocketpd.app.patch.GraphBlock(
                    mutableListOf(com.pocketpd.app.patch.PdArray(name, size, style, true)),
                )
                g.coords = com.pocketpd.app.patch.GraphCoords(0f, 1f, size.toFloat(), -1f, 200, 100, 1)
                o.graph = g
            }
            return o.graph
        }
        return o.graph?.takeIf { g -> g.coords != null }
    }

    /**
     * Patch-space port rectangle. Layout follows the inset-first rule: the
     * edge rects are inset so they stay fully inside the box, then ALL rects
     * are distributed evenly across that inset span with a visible gap
     * between every neighbour. (The old distribute-then-clamp order put the
     * end ports exactly on the box edges, and the clamp then pushed each
     * edge rect half a width towards its neighbour — first/last pairs
     * touched while middle ones kept gaps.) Cords anchor at its center, so
     * wire ends always meet the port rect midpoint.
     */
    private fun portRectPatch(o: PatchObject, index: Int, inlet: Boolean): RectF {
        val r = objRect(o)
        val n = if (inlet) o.meta.effInlets() else o.meta.effOutlets()
        // portPw/portPh are screen-space (zoomed): map them back to patch
        // units so the whole layout computes in one coordinate system
        val w0 = min(portPw() / zoom, max(r.width(), 1f))
        val ph = max(portPh() / zoom, 0.5f)
        val gap = max(units(3f) / zoom, 1f)
        // full rect width when n rects plus (n-1) gaps fit the box; otherwise
        // shrink the width until every rect keeps the minimum gap, staying
        // inside the box even in the degenerate sub-pixel case
        val w = when {
            n <= 1 -> w0
            n * w0 + (n - 1) * gap <= r.width() -> w0
            else -> ((r.width() - (n - 1) * gap) / n).coerceIn(1f, max(r.width(), 1f))
        }
        val left = if (n <= 1) r.left
        else r.left + (r.width() - w) * index / (n - 1)
        val top = if (inlet) r.top else r.bottom - ph
        return RectF(left, top, left + w, top + ph)
    }

    /** Screen-space size of one port rectangle: scales with zoom (with a
     *  1px floor so ports never vanish), keeping proportion with the boxes. */
    private fun portPw(): Float = max(units(10f), 1.5f)
    private fun portPh(): Float = max(units(4f), 1f)

    /**
     * The port rectangle in screen coordinates: a flat rectangle sitting just
     * inside the box, flush with the top (inlet) / bottom (outlet) edge and
     * horizontally clamped so it never crosses the box border.
     */
    fun portRectScreen(o: PatchObject, index: Int, inlet: Boolean): RectF {
        val p = portRectPatch(o, index, inlet)
        return RectF(screenX(p.left), screenY(p.top), screenX(p.right), screenY(p.bottom))
    }

    /** Cord anchor: the center of the port rectangle (screen coords). */
    fun cordAnchor(o: PatchObject, index: Int, inlet: Boolean): FloatArray {
        val r = portRectScreen(o, index, inlet)
        return floatArrayOf(r.centerX(), r.centerY())
    }

    private fun isSignalPort(o: PatchObject, index: Int, inlet: Boolean): Boolean =
        (if (inlet) o.meta.signalInlets else o.meta.signalOutlets).contains(index)

    private fun objAt(cx: Float, cy: Float): PatchObject? {
        val doc = document ?: return null
        // Embedded GOP widgets sit visually on top of their box; they capture
        // touches in run mode only (edit mode manipulates the box itself).
        if (interactive) {
            for (o in doc.objects().asReversed()) {
                val gop = o.gop ?: continue
                for (w in gop.widgets.asReversed()) {
                    if (objRect(w).contains(cx, cy)) return w
                }
            }
        }
        return doc.objects().lastOrNull { objRect(it).contains(cx, cy) }
    }

    private fun portAt(cx: Float, cy: Float): Port? {
        val doc = document ?: return null
        if (mode != Mode.EDIT) return null
        val radius = max(dp(PORT_TOUCH_DP) / zoom, 9f)
        var best: Port? = null
        var bestD = radius * radius
        for (o in doc.objects()) {
            // broken objects have no ports
            if (!o.meta.exists || PdEngine.failedClasses.contains(o.className())) continue
            for (i in 0 until o.meta.effInlets()) {
                val p = portRectPatch(o, i, true)
                val d = dist2(p.centerX(), p.centerY(), cx, cy)
                if (d < bestD) {
                    bestD = d
                    best = Port(o.id, i, true, isSignalPort(o, i, true))
                }
            }
            for (i in 0 until o.meta.effOutlets()) {
                val p = portRectPatch(o, i, false)
                val d = dist2(p.centerX(), p.centerY(), cx, cy)
                if (d < bestD) {
                    bestD = d
                    best = Port(o.id, i, false, isSignalPort(o, i, false))
                }
            }
        }
        return best
    }

    private fun objectById(id: Long): PatchObject? = document?.objectById(id)

    private fun cordHit(sx: Float, sy: Float): PatchConnection? {
        val doc = document ?: return null
        val radius = dp(9f)
        var best: PatchConnection? = null
        var bestD = radius * radius
        for (c in doc.connections) {
            val a = objectById(c.fromId) ?: continue
            val b = objectById(c.toId) ?: continue
            val p0 = cordAnchor(a, c.outlet, false)
            val p3 = cordAnchor(b, c.inlet, true)
            val sig = isSignalPort(a, c.outlet, false)
            var t = 0f
            while (t <= 1f) {
                val pt = bezierScreen(p0, p3, sig, t)
                val d = dist2(pt[0], pt[1], sx, sy)
                if (d < bestD) { bestD = d; best = c }
                t += 0.05f
            }
        }
        return best
    }

    /** Screen-space bezier sample matching the cords drawn in onDraw. */
    private fun bezierScreen(p0: FloatArray, p3: FloatArray, sig: Boolean, t: Float): FloatArray {
        val lead = min(
            if (sig) units(LEAD_SIG) else units(LEAD_MSG),
            Math.abs(p3[1] - p0[1]) / 2f,
        )
        val p1 = floatArrayOf(p0[0], p0[1] + lead)
        val p2 = floatArrayOf(p3[0], p3[1] - lead)
        val u = 1 - t
        return floatArrayOf(
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
        )
    }

    // -------------------------------------------------------------- selection

    fun selectedObjects(): List<PatchObject> =
        document?.objects()?.filter { it.id in selection } ?: emptyList()

    fun clearSelection() {
        if (selection.isNotEmpty()) {
            selection.clear()
            callbacks?.onSelectionChanged(selection)
            invalidate()
        }
    }

    private fun setSelection(ids: Collection<Long>) {
        selection.clear()
        selection.addAll(ids)
        Log.i(TAG, "SEL n=${ids.size} $ids")
        callbacks?.onSelectionChanged(selection)
        invalidate()
    }

    // ------------------------------------------------------------- editing ops

    private fun markDirty() {
        if (!dirty) {
            dirty = true
            callbacks?.onDirtyChanged(true)
        }
        onContentMutated?.invoke()
    }

    /**
     * Fired after every document mutation (add/delete/wire/edit/undo/redo/
     * metadata dialogs): the activity keeps the live pd session in sync with
     * the edited doc (debounced there). Without this, a session opened at
     * first drop would be a stale snapshot — objects and cords wired AFTER
     * it never reach pd at all (the one-note-[float] saga).
     */
    var onContentMutated: (() -> Unit)? = null
    /** Content changed through any edit path: redraw immediately (an edited
     *  object's port row — route/sel/trigger/pack … — is meta-driven and the
     *  canvas otherwise only invalidates on gestures, leaving stale pins on
     *  screen until the next touch) and arm the live-session rebuild. */
    private fun contentMutated() {
        invalidate()
        onContentMutated?.invoke()
    }

    fun hasUnsavedChanges(): Boolean = dirty
    fun clearDirty() {
        dirty = false
        callbacks?.onDirtyChanged(false)
    }

    /** Flags unsaved content for edits that bypass the command stack
     *  (metadata / graph-on-parent dialogs). */
    fun markContentDirty() = markDirty()

    fun undo() {
        stack.undo()
        contentMutated()
    }

    fun redo() {
        stack.redo()
        contentMutated()
    }
    fun canUndo(): Boolean = stack.canUndo
    fun canRedo(): Boolean = stack.canRedo

    /** Drops all undo/redo history — used when the document is replaced
     *  wholesale by the plain-text editor round-trip. */
    fun clearHistory() = stack.clear()

    fun addObject(kind: ObjKind, args: List<String>, cx: Float, cy: Float): PatchObject {
        val finalArgs = ArrayList(
            when {
                // atoms carry their kind as args[0] internally (width lives in
                // args[1]); without it the meta reads a zero width box
                kind == ObjKind.FLOATATOM || kind == ObjKind.SYMBOLATOM || kind == ObjKind.LISTBOX ->
                    listOf(kind.label) + args
                kind == ObjKind.OBJECT && args.firstOrNull()?.let { PdObjectMeta.isIemGui(it) } == true && args.size == 1 ->
                    PdObjectMeta.defaultArgs(args.first())
                else -> args
            }
        )
        val obj = PatchObject(newObjectId(), cx.toInt(), cy.toInt(), kind, finalArgs)
        obj.meta = PdObjectMeta.resolve(obj)
        assignInteractiveSymbols(obj)
        stack.execute(AddObjectCommand(document!!, obj) {})
        // newly placed GOP abstractions (output~, diy2/eee-adsr…) expand now
        PdEngine.expandGop(document!!)
        setSelection(listOf(obj.id))
        contentMutated()
        return obj
    }

    /**
     * New gui widgets get unique send/receive symbols so they are touchable in
     * Run mode and reachable from Pd out of the box (desktop patches created
     * elsewhere keep whatever they already declare).
     */
    private fun assignInteractiveSymbols(obj: PatchObject) {
        // display/display~: args[1] is the widget's private receive tag (the
        // abstraction forwards every message to [send $1]) — a fresh box gets
        // its own so two displays never mirror each other
        if (obj.className() == "display" || obj.className() == "display~") {
            PdObjectMeta.setDisplayTag(obj, "display-rcv-${obj.id.toString(36)}")
            return
        }
        if (!obj.meta.isGui || obj.kind == ObjKind.OBJECT && obj.className() == "cnv") return
        if (obj.className() == "graph~" || obj.className() == "spectrograph~") return
        // euclid carries four internal relay slots instead of send/rcv
        if (obj.className() == "euclid") {
            PdEngine.assignEuclidSymbols(obj, obj.id.toString(36))
            return
        }
        // circle5 dial: two internal relay slots (in0/out0)
        if (obj.className() == "circle5") {
            PdEngine.assignCircle5Symbols(obj, obj.id.toString(36))
            return
        }
        // button's relay symbols are internal (transient fields): Properties
        // show blank send/receive unless the user names them
        if (obj.className() == "button") {
            val uid = obj.id.toString(36)
            if (obj.autoSend.isEmpty() && obj.meta.sendSymbol.isEmpty()) {
                obj.autoSend = "button-send-$uid"
            }
            if (obj.autoReceive.isEmpty() && obj.meta.receiveSymbol.isEmpty()) {
                obj.autoReceive = "button-rcv-$uid"
            }
            return
        }
        // popmenu: same internal-relay pattern as button, plus the full
        // upstream property-block layout so Properties sees every slot
        if (obj.className() == "popmenu") {
            val uid = obj.id.toString(36)
            if (obj.autoSend.isEmpty() && obj.meta.sendSymbol.isEmpty()) {
                obj.autoSend = "popmenu-send-$uid"
            }
            if (obj.autoReceive.isEmpty() && obj.meta.receiveSymbol.isEmpty()) {
                obj.autoReceive = "popmenu-rcv-$uid"
            }
            PdObjectMeta.ensurePopmenuLayout(obj)
            return
        }
        val uid = obj.id.toString(36)
        PdObjectMeta.setSymbols(obj, "${obj.className()}-send-$uid", "${obj.className()}-rcv-$uid")
    }

    fun editObject(obj: PatchObject, newArgs: List<String>) {
        // typing a display into a box (fresh or retyped): fill its private
        // receive tag unless the user named one; the add-bar drop path gets
        // the same treatment in assignInteractiveSymbols
        val base = newArgs.firstOrNull()?.substringAfterLast('/') ?: ""
        val finalArgs =
            if (base == "display" || base == "display~")
                PdObjectMeta.withDisplayTag(newArgs, "display-rcv-${obj.id.toString(36)}")
            else newArgs
        stack.execute(EditObjectCommand(document!!, obj, obj.args.toList(), finalArgs) {})
        // the class may have changed into or out of graph-on-parent
        obj.gop = null
        obj.meta = PdObjectMeta.resolve(obj)
        PdEngine.expandGop(document!!)
        contentMutated()
    }

    fun deleteSelection() {
        val sel = selectedObjects()
        if (sel.isNotEmpty()) {
            stack.execute(DeleteObjectsCommand(document!!, sel) {})
            clearSelection()
            contentMutated()
            return
        }
        selectedConnection?.let {
            deleteConnection(it)
            selectedConnection = null
            invalidate()
        }
    }

    fun duplicateSelection(ids: Collection<Long>? = null) {
        val sel = if (ids != null)
            document?.objects()?.filter { it.id in ids } ?: return
        else selectedObjects()
        if (sel.isEmpty()) return
        val copies = sel.map {
            PatchObject(newObjectId(), it.x + 24, it.y + 24, it.kind, ArrayList(it.args))
                .also { c ->
                    c.meta = PdObjectMeta.resolve(c)
                    reassignClonedRelays(c)
                }
        }
        stack.execute(PasteCommand(document!!, copies, emptyList()) {})
        // duplicated GOP abstractions (output~, adsr…) expand like fresh ones
        PdEngine.expandGop(document!!)
        setSelection(copies.map { it.id })
        contentMutated()
    }

    /**
     * Cloned args carry the source's internal relay names: two euclids (or
     * buttons) sharing a name steal each other's triggers and outputs, so
     * every copy gets its own right here.
     */
    /**
     * Cloned args carry the source's internal relay names: two euclids (or
     * buttons) sharing a name steal each other's triggers and outputs, and
     * pasted atom twins share one rcv so every value lands in all of them —
     * every clone gets its own names right here.
     */
    private fun reassignClonedRelays(copy: PatchObject) {
        val uid = copy.id.toString(36)
        when (copy.className()) {
            "euclid" -> PdEngine.assignEuclidSymbols(copy, uid, force = true)
            "circle5" -> PdEngine.assignCircle5Symbols(copy, uid, force = true)
            // display tags are private channel names, not user links: a clone
            // always gets its own so it never mirrors its source (same rule
            // as the auto snd/rcv regeneration below)
            "display", "display~" ->
                PdObjectMeta.setDisplayTag(copy, "display-rcv-$uid")
            "button" -> {
                copy.autoSend = "button-send-$uid"
                copy.autoReceive = "button-rcv-$uid"
                // stale stored auto names (pre-transient files) would
                // cross-wire the clone; user-typed names survive
                if (PdObjectMeta.isAutoSymbol("button", copy.meta.sendSymbol) ||
                    PdObjectMeta.isAutoSymbol("button", copy.meta.receiveSymbol)
                ) {
                    PdObjectMeta.setSymbols(copy, "empty", "empty")
                }
            }
            "popmenu" -> {
                // duplicate must never share the source's relays (same rule
                // as button/display above): fresh internal names, stale
                // stored autos wiped, user-typed names survive
                copy.autoSend = "popmenu-send-$uid"
                copy.autoReceive = "popmenu-rcv-$uid"
                if (PdObjectMeta.isAutoSymbol("popmenu", copy.meta.sendSymbol) ||
                    PdObjectMeta.isAutoSymbol("popmenu", copy.meta.receiveSymbol)
                ) {
                    PdObjectMeta.setSymbols(copy, "empty", "empty")
                }
            }
        }
        // every symbol-carrying class (atoms, iemgui, keyboard): app-generated
        // rcv/send names are re-generated for the clone — paste twins sharing
        // one auto rcv land every value in all of them — while user-typed
        // names survive duplication (intentional linking, like desktop pd)
        if (copy.className() != "button" && copy.className() != "popmenu" &&
            PdObjectMeta.hasSymbolSlots(copy)) {
            val cls = copy.className()
            val snd = copy.meta.sendSymbol
            val rcv = copy.meta.receiveSymbol
            val newSnd = if (snd.isEmpty() || PdObjectMeta.isAutoSymbol(cls, snd)) "$cls-send-$uid" else snd
            val newRcv = if (rcv.isEmpty() || PdObjectMeta.isAutoSymbol(cls, rcv)) "$cls-rcv-$uid" else rcv
            if (newSnd != snd || newRcv != rcv) {
                PdObjectMeta.setSymbols(copy, newSnd, newRcv)
            }
        }
    }

    /** Duplicates an explicit id set — the long-press menu passes the
     *  selection captured when it opened, so the action stays scoped to the
     *  multi-selection the user made even if the live selection moves. */
    fun duplicateObjects(ids: Collection<Long>) {
        if (ids.isNotEmpty()) duplicateSelection(ids)
    }

    /** Copies an explicit id set (clipboard form of [duplicateObjects]). */
    fun copyObjects(ids: Collection<Long>) {
        val doc = document ?: return
        val sel = doc.objects().filter { it.id in ids }
        if (sel.isEmpty()) return
        val minX = sel.minOf { it.x }
        val minY = sel.minOf { it.y }
        val idMap = HashMap<Long, Long>()
        clipboardObjects = sel.map {
            val copy = PatchObject(newObjectId(), it.x - minX, it.y - minY, it.kind, ArrayList(it.args))
            reassignClonedRelays(copy)
            idMap[it.id] = copy.id
            copy
        }
        clipboardConnections = doc.connections
            .filter { idMap.containsKey(it.fromId) && idMap.containsKey(it.toId) }
            .map { PatchConnection(newObjectId(), idMap[it.fromId]!!, it.outlet, idMap[it.toId]!!, it.inlet) }
    }

    /** Deletes an explicit id set (the long-press menu's snapshot). */
    fun deleteObjects(ids: Collection<Long>) {
        val doc = document ?: return
        val sel = doc.objects().filter { it.id in ids }
        Log.i(TAG, "DELETE n=${sel.size} of ${ids.size} requested")
        if (sel.isNotEmpty()) {
            stack.execute(DeleteObjectsCommand(doc, sel) {})
            selection.removeAll(ids.toSet())
            callbacks?.onSelectionChanged(selection)
            invalidate()
            contentMutated()
            return
        }
        selectedConnection?.let {
            deleteConnection(it)
            selectedConnection = null
            invalidate()
        }
    }

    fun copySelection() {
        val sel = selectedObjects()
        if (sel.isEmpty()) return
        val minX = sel.minOf { it.x }
        val minY = sel.minOf { it.y }
        val idMap = HashMap<Long, Long>()
        clipboardObjects = sel.map {
            val copy = PatchObject(newObjectId(), it.x - minX, it.y - minY, it.kind, ArrayList(it.args))
            idMap[it.id] = copy.id
            copy
        }
        clipboardConnections = document!!.connections
            .filter { idMap.containsKey(it.fromId) && idMap.containsKey(it.toId) }
            .map { PatchConnection(newObjectId(), idMap[it.fromId]!!, it.outlet, idMap[it.toId]!!, it.inlet) }
    }

    fun hasClipboard(): Boolean = clipboardObjects.isNotEmpty()

    fun paste(cx: Float, cy: Float) {
        if (clipboardObjects.isEmpty() || document == null) return
        val objects = clipboardObjects.map {
            PatchObject(newObjectId(), it.x + cx.toInt(), it.y + cy.toInt(), it.kind, ArrayList(it.args))
                .also { c ->
                    c.meta = PdObjectMeta.resolve(c)
                    reassignClonedRelays(c)
                }
        }
        val idMap = HashMap<Long, Long>()
        clipboardObjects.forEachIndexed { i, src -> idMap[src.id] = objects[i].id }
        val connections = clipboardConnections.mapNotNull { c ->
            val from = idMap[c.fromId] ?: return@mapNotNull null
            val to = idMap[c.toId] ?: return@mapNotNull null
            PatchConnection(newObjectId(), from, c.outlet, to, c.inlet)
        }
        stack.execute(PasteCommand(document!!, objects, connections) {})
        // pasted GOP abstractions (output~, adsr…) expand like fresh ones
        PdEngine.expandGop(document!!)
        setSelection(objects.map { it.id })
        contentMutated()
    }

    private var clipboardObjects: List<PatchObject> = emptyList()
    private var clipboardConnections: List<PatchConnection> = emptyList()

    fun deleteConnection(c: PatchConnection) {
        stack.execute(DisconnectCommand(document!!, c.id) {})
        contentMutated()
    }

    fun connectPorts(from: Port, to: Port) {
        val a = objectById(from.objectId) ?: return
        val b = objectById(to.objectId) ?: return
        val (srcOut, dstIn) =
            if (from.isInlet) to.index to from.index
            else from.index to to.index
        val (srcObj, dstObj) = if (from.isInlet) b to a else a to b
        // Re-drawing a cord that already exists between the same two ports
        // removes it (toggle), matching how users naturally "unwire".
        val existing = document!!.connections.firstOrNull {
            it.fromId == srcObj.id && it.outlet == srcOut &&
                it.toId == dstObj.id && it.inlet == dstIn
        }
        if (existing != null) {
            stack.execute(DisconnectCommand(document!!, existing.id) {})
            selectedConnection = null
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
            return
        }
        val conn = document!!.addConnection(srcObj, srcOut, dstObj, dstIn) ?: return
        stack.execute(ConnectCommand(document!!, conn) {})
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        contentMutated()
    }

    fun zoomToFit() {
        val doc = document ?: return
        val objs = doc.objects()
        if (objs.isEmpty()) {
            zoom = dp(1.4f)
            camX = dp(30f); camY = dp(30f)
            invalidate()
            return
        }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (o in objs) {
            val rect = objRect(o)
            // comments keep pd's natural full width for drawing and hit
            // testing, but one 200-char remark must not blow up the fit
            val right = if (o.kind == ObjKind.COMMENT)
                min(rect.right, rect.left + 400f) else rect.right
            l = min(l, rect.left); t = min(t, rect.top)
            r = max(r, right); b = max(b, rect.bottom)
        }
        // frame the GOP visible range too, so it is always in view
        doc.gop?.takeIf { it.enabled }?.let { g ->
            val gr = g.rect()
            l = min(l, gr.left.toFloat()); t = min(t, gr.top.toFloat())
            r = max(r, gr.right.toFloat()); b = max(b, gr.bottom.toFloat())
        }
        val pad = dp(24f)
        val zx = (width - pad * 2) / max(r - l, 1f)
        val zy = (height - pad * 2) / max(b - t, 1f)
        zoom = (min(zx, zy)).coerceIn(dp(0.35f), dp(4f))
        camX = (width - (r - l) * zoom) / 2 - l * zoom
        camY = (height - (b - t) * zoom) / 2 - t * zoom
        invalidate()
    }

    fun selectAll() {
        document?.let {
            setSelection(it.objects().map { o -> o.id })
            Log.i(TAG, "SELECT_ALL n=${selection.size}")
        }
    }

    /** Canvas-unit coordinates of the current viewport center. */
    fun viewportCenter(): FloatArray = toCanvasPx(width / 2f, height / 2f)

    // -------------------------------------------------------------- drag & drop

    /**
     * Accepts palette drops: the payload is "kind|arg|arg…" (args may be
     * empty). The drop point becomes the new object's center; the activity
     * then opens the inline name bar for it.
     */
    fun bindDragReceiver() {
        setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.label == DRAG_ADD
                DragEvent.ACTION_DROP -> {
                    val item = event.clipData?.getItemAt(0) ?: return@setOnDragListener false
                    val payload = item.text?.toString() ?: return@setOnDragListener false
                    val parts = payload.split("|")
                    val kind = try {
                        ObjKind.valueOf(parts[0])
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "drop: bad kind '${parts[0]}'")
                        return@setOnDragListener false
                    }
                    val args = if (parts.size > 1) parts.drop(1) else emptyList()
                    val doc = toCanvasPx(event.x, event.y)
                    val obj = if (args.firstOrNull() == "__graph__") {
                        addGraphBlock(doc[0], doc[1])
                    } else {
                        addObject(kind, args, doc[0], doc[1])
                    }
                    Log.i(TAG, "drop kind=$kind args=$args at doc=(${doc[0].toInt()}, ${doc[1].toInt()})")
                    callbacks?.onObjectDropped(obj)
                    true
                }
                else -> true
            }
        }
    }

    // -------------------------------------------------------------- gestures

    /** Keyboard creation path (Ctrl+1..4): exactly the palette-drop route —
     *  addObject (or a graph block) plus the name bar via onObjectDropped.
     *  Edit mode only; the caller owns the mode check. */
    fun spawnPaletteObject(kind: ObjKind, args: List<String>, cx: Float, cy: Float) {
        val obj = if (args.firstOrNull() == "__graph__") addGraphBlock(cx, cy)
        else addObject(kind, args, cx, cy)
        callbacks?.onObjectDropped(obj)
    }

    /** Last mouse-cursor position in canvas coordinates — keyboard creation
     *  "at the mouse"; null when no pointer has hovered the canvas. */
    fun pointerCanvasPos(): FloatArray? =
        if (hasHover) toCanvasPx(lastHover[0], lastHover[1]) else null

    /** The canvas point at the middle of the view (fallback placement). */
    fun centerCanvasPos(): FloatArray = toCanvasPx(width / 2f, height / 2f)

    private enum class Drag { NONE, PAN, MOVE, CORD, MARQUEE, GUI }

    private var drag = Drag.NONE
    private var downCanvas = FloatArray(2)
    private var lastCanvas = FloatArray(2)
    private var moved = false
    private var downObject: PatchObject? = null
    private var downPort: Port? = null
    private var pendingFrom: Port? = null
    private var pendingTo: Port? = null
    private var pendingPoint = FloatArray(2)
    private var marquee = RectF()
    private var moveOrigins: Map<Long, Pair<Int, Int>> = emptyMap()
    private var guiDragObject: PatchObject? = null

    // number box scrub gesture: drag origin and value at press time
    private var guiAtomStartY = 0f
    private var guiAtomStartValue = 0f

    // graph plot gesture: previous touch point (sweep interpolation) and a
    // per-array scratch buffer so one stroke writes one pd array range
    private var graphEditPrev: FloatArray? = null
    private val graphScratch = HashMap<String, FloatArray>()

    // array plot scratch, reused every frame: per pixel-column x plus the y
    // pixels of the column's max (top) and min (bottom) — see graphPlot()
    private var plotX = FloatArray(0)
    private var plotTop = FloatArray(0)
    private var plotBot = FloatArray(0)

    /** A cord tapped in Edit mode: highlighted, second tap deletes it. */
    private var selectedConnection: PatchConnection? = null

    fun clearCordSelection() {
        selectedConnection = null
        invalidate()
    }


    /** Verbose camera-position logging for interaction testing. */
    private var debugCam = false
    private fun dbg(s: String) { android.util.Log.d("PocketPdCanvas", s) }

    private val gesture = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            removeCallbacks(longPressRunnable)
            longPressFired = false
            longPressObj = null
            sawSecondPointer = false
            downScreenX = e.x
            downScreenY = e.y
            if (mode == Mode.EDIT || mode == Mode.RUN) postDelayed(longPressRunnable, 450)
            val c = toCanvasPx(e.x, e.y)
            downCanvas = c.copyOf()
            lastCanvas = c.copyOf()
            moved = false
            downPort = portAt(c[0], c[1])
            downObject = objAt(c[0], c[1])
            pendingFrom = downPort
            pendingTo = null
            drag = Drag.NONE
            // keyboard/pad: raw per-pointer path (handleKeyboardTouch /
            // handlePadTouch) — gesture detectors only see the primary
            // pointer and would strand notes/fingers. LOCK is handled
            // wholesale by handleLockWidgets above.
            if (interactive && !locked &&
                downObject?.className() != "keyboard" &&
                downObject?.className() != "pad" &&
                (downObject?.meta?.isGui == true || downObject?.graph != null)
            ) {
                drag = Drag.GUI
                guiDragObject = downObject
                guiPress(downObject!!, c)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            moved = true
            removeCallbacks(longPressRunnable)
            if (pinchZooming) return true // camera already driven by applyPinch
            val c = toCanvasPx(e2.x, e2.y)
            when {
                drag == Drag.NONE && e2.pointerCount >= 2 && !locked -> drag = Drag.PAN
                drag == Drag.NONE && downPort != null -> drag = Drag.CORD
                drag == Drag.NONE && downObject != null && mode == Mode.EDIT -> {
                    if (selection.isEmpty() || !selection.contains(downObject!!.id)) {
                        setSelection(listOf(downObject!!.id))
                    }
                    // capture GOP display widgets too: they are absolute
                    // parent-space copies that must ride along (undo included)
                    moveOrigins = buildMap {
                        for (o in selectedObjects()) {
                            put(o.id, o.x to o.y)
                            o.gop?.widgets?.forEach { w -> put(w.id, w.x to w.y) }
                        }
                    }
                    drag = Drag.MOVE
                }
                drag == Drag.NONE && mode == Mode.EDIT && tool == Tool.SELECT -> drag = Drag.MARQUEE
                // LOCK: dragging empty space does nothing — no pan, no zoom
                // a gesture that started on a keyboard key or a pad belongs
                // to the raw per-pointer player — never pan the camera with it
                drag == Drag.NONE && !locked &&
                    downObject?.className() != "keyboard" &&
                    downObject?.className() != "pad" -> drag = Drag.PAN
            }
            when (drag) {
                Drag.PAN -> {
                    camX -= dx; camY -= dy
                    invalidate()
                    if (debugCam) dbg("PAN camX=$camX camY=$camY ptr=${e2.pointerCount}")
                }
                Drag.MOVE -> {
                    val ddx = (c[0] - lastCanvas[0]).toInt()
                    val ddy = (c[1] - lastCanvas[1]).toInt()
                    if (ddx != 0 || ddy != 0) {
                        for (o in selectedObjects()) {
                            o.x += ddx
                            o.y += ddy
                            // embedded GOP widgets live in parent space
                            o.gop?.widgets?.forEach { w -> w.x += ddx; w.y += ddy }
                        }
                        invalidate()
                    }
                }
                Drag.CORD -> {
                    pendingPoint = floatArrayOf(e2.x, e2.y)
                    pendingTo = portAt(c[0], c[1])
                    invalidate()
                }
                Drag.MARQUEE -> {
                    android.util.Log.d("PocketPdCanvas", "MARQUEE drag rect=$marquee")
                    marquee = RectF(
                        min(downCanvas[0], c[0]), min(downCanvas[1], c[1]),
                        max(downCanvas[0], c[0]), max(downCanvas[1], c[1]),
                    )
                    invalidate()
                }
                Drag.GUI -> guiDragObject?.let { guiDrag(it, c) }
                Drag.NONE -> Unit
            }
            lastCanvas = c.copyOf()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // a long-press already handled this gesture: the lift is not a
            // tap. Without this the collapse ran AFTER the OSD opened and
            // shrank a multi-selection to the pressed object alone.
            // Likewise a gesture that grew a second pointer (grip touch or
            // pinch over the selection) is not a tap: on real devices that
            // path cancelled the long-press and the lift then collapsed a
            // multi-selection to single "with some probability".
            if (longPressFired || sawSecondPointer) return true
            val c = toCanvasPx(e.x, e.y)
            // Ctrl+click on EMPTY canvas toggles edit/run (desktop-pd
            // convention, user-requested) — checked before the run-mode
            // branch so the toggle works in BOTH directions; a tap that
            // lands on an object, port or cord keeps its normal meaning
            if (e.metaState and KeyEvent.META_CTRL_ON != 0 && !locked &&
                downPort == null && cordHit(e.x, e.y) == null &&
                objAt(c[0], c[1]) == null
            ) {
                callbacks?.onCtrlTapEmpty()
                return true
            }
            if (interactive) return true // widgets act on ACTION_UP, see GUI branch
            val cord = cordHit(e.x, e.y)
            if (cord != null && downPort == null) {
                deleteConnection(cord)
                return true
            }
            if (downPort != null) return true // tap on a port alone: no-op
            val obj = objAt(c[0], c[1])
            setSelection(if (obj != null) listOf(obj.id) else emptyList())
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (mode != Mode.EDIT) return false
            val c = toCanvasPx(e.x, e.y)
            val obj = objAt(c[0], c[1])
            if (obj != null) {
                callbacks?.onEditObjectRequested(obj)
            } else {
                zoomToFit()
            }
            return true
        }

    }

    private val gestureDetector = GestureDetector(context, gesture).apply {
        setIsLongpressEnabled(false)
    }

    /** Own long-press timer: fires on objects only (empty canvas has no
     *  long-press at all, so panning is never blocked by a popup). The
     *  450ms tick only gives haptic feedback and latches [longPressObj];
     *  the OSD itself opens on finger-up — opening it mid-hold put the
     *  menu under the still-down finger, so the lift (or a reflex tap)
     *  landed on a row and "randomly" opened Open/Help. The selection is
     *  NEVER touched here: deciding "which single object" during a hold
     *  collapsed multi-selections on device; the menu acts on the
     *  selection as it stands, or on the pressed object when nothing is
     *  selected (snapshot in the activity). */
    private val longPressRunnable = Runnable {
        val obj = objAt(downCanvas[0], downCanvas[1]) ?: return@Runnable
        // EDIT ONLY, like the LOCK lesson: a run-mode long-press must open
        // NOTHING — the widget under the finger just keeps playing (finger-up
        // falls through to guiTap, exactly how LOCK's wholesale handler
        // behaves). Every run-mode route to a menu or dialog on release
        // (Properties included) has surfaced as "the edit menu pops while
        // playing" on device, so the latch no longer fires outside EDIT.
        if (mode != Mode.EDIT || drag != Drag.NONE) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // latch the object AND raise the flag together: the confirmed-tap
        // callback fires SYNCHRONOUSLY inside the UP dispatch (before the
        // UP branch below can set the flag), so it must already be up here
        longPressFired = true
        longPressObj = obj
        Log.i("PocketPdCanvas", "LP latch edit '${obj.displayName()}'")
    }

    /** Object whose long-press latched; consumed on the next clean UP. */
    private var longPressObj: PatchObject? = null

    /** Set when the current gesture's long-press took effect; cleared on the
     *  next DOWN. GestureDetector (with its own long-press disabled) still
     *  classifies the held-and-lifted finger as a tap, so every tap consumer
     *  checks this. */
    private var longPressFired = false

    /** True once the current gesture grew a second pointer. A multi-pointer
     *  gesture must never end in tap-to-single-select. */
    private var sawSecondPointer = false
    private var downScreenX = 0f
    private var downScreenY = 0f
    // Pinch zoom is judged here, not by ScaleGestureDetector: its begin
    // rule requires the initial two-finger span to exceed
    // config_minScalingSpan (~27mm), so on small/low-density screens a
    // short pinch never zoomed at all. We only ask that the span of the
    // two tracked pointers leaves touch slop — no minimum span.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pinchStartSpan = 0f // slop gate; 0 = not tracking
    private var pinchSpan = 0f      // previous event's span (per-event ratio)
    private var pinchMidX = 0f
    private var pinchMidY = 0f
    private var pinchZooming = false

    private fun pointerSpan(e: MotionEvent): Float =
        hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    /** Two-pointer camera drag: zoom about the finger midpoint and follow
     *  its movement. Called for every 2+-pointer MOVE while tracking. */
    private fun applyPinch(e: MotionEvent) {
        val span = pointerSpan(e)
        if (span <= 0f) return
        val midX = (e.getX(0) + e.getX(1)) / 2f
        val midY = (e.getY(0) + e.getY(1)) / 2f
        if (!pinchZooming && abs(span - pinchStartSpan) > touchSlop) pinchZooming = true
        if (pinchZooming && pinchSpan > 0f) {
            val newZoom = (zoom * span / pinchSpan).coerceIn(dp(0.3f), dp(5f))
            val a = newZoom / zoom
            // keep the canvas point that was under the previous midpoint
            // under the new one — zoom and midpoint pan in one step
            camX = midX - (pinchMidX - camX) * a
            camY = midY - (pinchMidY - camY) * a
            zoom = newZoom
            if (debugCam) dbg("ZOOM zoom=$zoom focus=$midX,$midY span=$span")
            invalidate()
        }
        pinchSpan = span
        pinchMidX = midX
        pinchMidY = midY
    }

    /** Mouse support (Android routes a mouse through the touch pipeline):
     *  left button IS the touch path above; the wheel zooms about the
     *  cursor with the same anchored math as the pinch; the right button
     *  pre-latches the long-press so its button-up opens the object menu
     *  through the same Drag.NONE branch the 450 ms touch latch uses. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // remember where the cursor is: the activity routes the system's
        // mouse-right-click BACK key here (openLongPressMenuAtCursor)
        if (event.action == MotionEvent.ACTION_HOVER_MOVE ||
            event.action == MotionEvent.ACTION_HOVER_ENTER
        ) {
            lastHover[0] = event.x
            lastHover[1] = event.y
            hasHover = true
        }
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.source and InputDevice.SOURCE_CLASS_POINTER != 0 && !locked
        ) {
            val notches = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (notches != 0f) {
                val newZoom = (zoom * 1.15f.pow(notches)).coerceIn(dp(0.3f), dp(5f))
                val a = newZoom / zoom
                // keep the canvas point under the cursor under it (pinch step)
                camX = event.x - (event.x - camX) * a
                camY = event.y - (event.y - camY) * a
                zoom = newZoom
                invalidate()
                return true
            }
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (h != 0f) {
                camX -= h * 48f * resources.displayMetrics.density
                invalidate()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // last mouse cursor position (hover), for the right-click-as-BACK route
    private val lastHover = FloatArray(2)
    private var hasHover = false

    /** The system maps mouse right-click to BACK on some devices; the
     *  activity intercepts that key and calls this instead of navigating
     *  back. Opens the same object menu as the touch long-press, anchored
     *  at the cursor. Returns false when there is nothing to open (no
     *  hover yet, RUN mode, no object under the cursor) so the caller can
     *  fall back to normal back navigation. */
    fun openLongPressMenuAtCursor(): Boolean {
        if (!hasHover || mode != Mode.EDIT || locked) return false
        val c = toCanvasPx(lastHover[0], lastHover[1])
        val obj = objAt(c[0], c[1]) ?: return false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        callbacks?.onObjectLongPress(obj, lastHover[0], lastHover[1])
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (debugCam && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val c = toCanvasPx(event.x, event.y)
            Log.i(TAG, "DOWN screen=(${event.x.toInt()},${event.y.toInt()}) canvas=(${c[0].toInt()},${c[1].toInt()}) zoom=$zoom obj=${objAt(c[0], c[1])?.displayName()}")
        }
        // mouse right button = long-press menu: latch immediately, the
        // button-up below opens the menu exactly like a held finger
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.getButtonState() and MotionEvent.BUTTON_SECONDARY != 0
        ) {
            removeCallbacks(longPressRunnable)
            longPressFired = false
            longPressObj = null
            val c = toCanvasPx(event.x, event.y)
            downCanvas = c.copyOf()
            downScreenX = event.x
            downScreenY = event.y
            val obj = objAt(c[0], c[1])
            if (mode == Mode.EDIT && !locked && obj != null) {
                longPressFired = true
                longPressObj = obj
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        handleKeyboardTouch(event)
        handlePadTouch(event)
        // LOCK: every finger plays the widget under it — consume the whole
        // stream here; the camera is frozen, so pan/marquee/long-press have
        // nothing to do. RUN keeps the single-pointer path below.
        if (locked) {
            handleLockWidgets(event)
            return true
        }
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (event.pointerCount >= 2 && !locked) {
                    // lazy baseline: span at the first two-finger move gates
                    // zoom start (touch slop), never an absolute minimum
                    if (pinchStartSpan <= 0f) {
                        pinchStartSpan = pointerSpan(event)
                        pinchSpan = pinchStartSpan
                        pinchMidX = (event.getX(0) + event.getX(1)) / 2f
                        pinchMidY = (event.getY(0) + event.getY(1)) / 2f
                    }
                    applyPinch(event)
                }
            MotionEvent.ACTION_POINTER_UP -> {
                // a finger lifted: re-baseline around the surviving pair on
                // the next move instead of continuing with a stale span
                pinchStartSpan = 0f
                pinchZooming = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                sawSecondPointer = true
                removeCallbacks(longPressRunnable)
                longPressObj = null
                // A second finger always takes over as pan/zoom, abandoning
                // object moves or cord drawing. Locked (run) mode freezes the
                // camera, so there is no pan to hand over — just drop the
                // widget drag.
                if (drag == Drag.MOVE) {
                    commitMove()
                    drag = Drag.PAN
                }
                if (drag == Drag.CORD || drag == Drag.GUI) {
                    drag = if (locked) Drag.NONE else Drag.PAN
                    pendingFrom = null
                    guiDragObject = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                val isUp = event.actionMasked == MotionEvent.ACTION_UP
                if (!isUp && drag == Drag.GUI) {
                    // gesture canceled mid-widget: release pads anyway so a
                    // held button can't stick sending 1 forever
                    guiDragObject?.let { guiRelease(it); releaseKeyboard(it) }
                    guiDragObject = null
                }
                if (isUp) {
                    when (drag) {
                        Drag.MOVE -> commitMove()
                        Drag.CORD -> {
                            if (pendingTo != null && pendingFrom != null) {
                                connectPorts(pendingFrom!!, pendingTo!!)
                            }
                            pendingFrom = null
                            pendingTo = null
                            invalidate()
                        }
                        Drag.MARQUEE -> {
                            val hit = document?.objects()?.filter {
                                objRect(it).intersect(marquee)
                            }?.map { it.id } ?: emptyList()
                            setSelection(hit)
                            marquee.setEmpty()
                            tool = Tool.PAN
                        }
                        Drag.GUI -> {
                            guiDragObject?.let {
                                guiRelease(it) // pads release even after a drag
                                // no long-press dialog on finger-up in RUN:
                                // release plays the widget (guiTap), like LOCK
                                if (!moved) {
                                    Log.i("PocketPdCanvas", "GUI up tap '${it.displayName()}' mode=$mode")
                                    guiTap(it)
                                }
                                releaseKeyboard(it)
                            }
                            guiDragObject = null
                        }
                        // a still hold that latched the long-press opens the
                        // OSD now — the finger is up, nothing can tap through.
                        // The latch only ever fires in EDIT, so this is
                        // edit-only by construction; the run-mode branch is
                        // kept as a forensic no-op (logs if it is ever hit)
                        Drag.NONE -> longPressObj?.let { obj ->
                            longPressObj = null
                            longPressFired = true
                            Log.i("PocketPdCanvas", "LP up mode=$mode locked=$locked obj='${obj.displayName()}'")
                            if (mode == Mode.RUN) {
                                Unit // nothing may open while playing
                            } else {
                                callbacks?.onObjectLongPress(obj, downScreenX, downScreenY)
                            }
                        }
                        else -> Unit
                    }
                }
                longPressObj = null
                drag = Drag.NONE
                pinchStartSpan = 0f
                pinchZooming = false
            }
        }
        return true
    }

    private fun commitMove() {
        val sel = selectedObjects()
        if (moveOrigins.isEmpty() || sel.isEmpty()) return
        val first = sel.first()
        val origin = moveOrigins[first.id] ?: return
        val dx = first.x - origin.first
        val dy = first.y - origin.second
        val origins = moveOrigins
        moveOrigins = emptyMap()
        if (dx == 0 && dy == 0) return
        // The gesture already applied the move on-screen; push the captured
        // origins so undo restores them.
        stack.executeAlreadyApplied(MoveCommand(document!!, origins, dx, dy) {})
    }

    // ------------------------------------------------- lock-mode multi-touch

    /** One finger's live interaction with a widget in LOCK mode: every
     *  pointer owns the widget under it, so several controls play at once.
     *  RUN mode deliberately keeps the single-pointer path — there a second
     *  finger must stay free for pinch zoom. */
    private class LockTouch(
        val obj: PatchObject,
        val downC: FloatArray,
        val startValue: Float,
        var moved: Boolean,
    )

    private val lockTouches = HashMap<Int, LockTouch>()

    private fun handleLockWidgets(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pi = e.actionIndex
                val id = e.getPointerId(pi)
                if (lockTouches.containsKey(id)) return
                val c = toCanvasPx(e.getX(pi), e.getY(pi))
                val obj = objAt(c[0], c[1]) ?: return
                // keyboard keys and pads play through their raw per-pointer
                // handlers (handleKeyboardTouch / handlePadTouch)
                if (obj.className() == "keyboard" || obj.className() == "pad") return
                if (!(obj.meta.isGui || graphBlock(obj) != null)) return
                lockTouches[id] = LockTouch(obj, c.copyOf(), obj.runtimeValue, false)
                lockPress(obj, c)
            }
            MotionEvent.ACTION_MOVE -> {
                for ((id, t) in lockTouches) {
                    val pi = e.findPointerIndex(id)
                    if (pi < 0) continue
                    val c = toCanvasPx(e.getX(pi), e.getY(pi))
                    if (abs(c[1] - t.downC[1]) > touchSlop / zoom.coerceAtLeast(0.01f)) t.moved = true
                    lockDrag(t, c)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> lockRelease(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                for (id in lockTouches.keys.toList()) lockRelease(id)
            }
        }
    }

    private fun lockPress(obj: PatchObject, c: FloatArray) {
        val g = graphBlock(obj)
        if (g != null) {
            graphEditPrev = null // a new finger starts a fresh stroke
            return
        }
        when (obj.className()) {
            // bang fires on touch-down, like a physical instrument key
            "bng" -> {
                obj.flashUntil = System.currentTimeMillis() + 120
                callbacks?.onGuiTouched(obj, 0f, true)
                postInvalidateDelayed(140)
                invalidate()
            }
            // button is a pad: 1 while held, released in lockRelease
            "button" -> {
                obj.runtimeValue = 1f
                callbacks?.onGuiTouched(obj, 1f, false)
                invalidate()
            }
            // popmenu opens its dropdown; the picked item sends per outmode
            "popmenu" -> showPopmenuPopup(obj)
            // radios switch on touch-down, like desktop pd's click
            "vradio", "hradio" -> radioSwitch(obj, c, force = true)
            else -> Unit
        }
    }

    private fun lockDrag(t: LockTouch, c: FloatArray) {
        val obj = t.obj
        val g = graphBlock(obj)
        if (g != null) {
            graphEdit(obj, g, c)
            return
        }
        val r = objRect(obj)
        val range = PdObjectMeta.sliderRange(obj)
        val value = when (obj.className()) {
            "hsl" -> {
                val frac = ((c[0] - r.left) / r.width()).coerceIn(0f, 1f)
                range.min + frac * (range.max - range.min)
            }
            "vsl" -> {
                val frac = ((c[1] - r.top) / r.height()).coerceIn(0f, 1f)
                range.max - frac * (range.max - range.min)
            }
            // knob dial: vertical sweep across the box (the native widget is
            // angular; the app keeps the simpler absolute mapping)
            "knob" -> {
                val frac = ((c[1] - r.top) / r.height()).coerceIn(0f, 1f)
                range.max - frac * (range.max - range.min)
            }
            // number boxes: vertical scrub, one integer step per canvas unit
            "floatatom", "nbx" -> t.startValue + (t.downC[1] - c[1]).roundToInt()
            // radios: slide-follow while held, inside the widget only
            "vradio", "hradio" -> {
                if (r.contains(c[0], c[1])) radioSwitch(obj, c, force = false)
                return
            }
            else -> return // tgl/bng/msg act on release/press elsewhere
        }
        val quantized = if (PdObjectMeta.isIntSteps(obj) &&
            (obj.className() == "hsl" || obj.className() == "vsl")
        ) {
            value.roundToInt().toFloat().coerceIn(range.min, range.max)
        } else value
        obj.runtimeValue = quantized
        callbacks?.onGuiTouched(obj, quantized, false)
        invalidate()
    }

    private fun lockRelease(pointerId: Int) {
        val t = lockTouches.remove(pointerId) ?: return
        val obj = t.obj
        // a pad's release fires even after a drag: the held 1 must always
        // be followed by the 0
        if (obj.className() == "button") {
            obj.runtimeValue = 0f
            callbacks?.onGuiTouched(obj, 0f, false)
            invalidate()
            return
        }
        if (t.moved) return
        when (obj.className()) {
            // one tap = one trigger, when the widget is interactive
            "euclid" -> if ((obj.args.getOrNull(6)?.toIntOrNull() ?: 1) != 0) {
                obj.flashUntil = System.currentTimeMillis() + 120
                callbacks?.onGuiTouched(obj, 1f, true)
                postInvalidateDelayed(140)
            }
            "circle5" -> if ((obj.args.getOrNull(5)?.toIntOrNull() ?: 1) != 0) {
                circle5CellAt(obj, t.downC)?.let { cell ->
                    callbacks?.onGuiTouched(obj, cell.toFloat(), false)
                }
            }
            "tgl" -> {
                val v = if (obj.runtimeValue > 0.5f) 0f else 1f
                obj.runtimeValue = v
                callbacks?.onGuiTouched(obj, v, false)
            }
            // radios switched on touch-down (see lockPress)
            // tapping a message box fires it into the flow; flash like a bang
            "msg" -> {
                obj.flashUntil = System.currentTimeMillis() + 120
                postInvalidateDelayed(140)
                callbacks?.onMsgTriggered(obj)
            }
            "floatatom", "nbx" -> callbacks?.onNumberEntryRequested(obj)
            else -> return // bng fired on press; graphs are drag-only
        }
        invalidate()
    }

    // ----------------------------------------------------------- gui widgets

    /** Live display buffers for the ELSE-compat scope widgets (graph~ =
     *  waveform, spectrograph~ = spectrum), polled from their pd arrays by
     *  the activity; plus the currently held keyboard notes. */
    private val scopeData = HashMap<Long, FloatArray>()
    private val keyboardNotes = HashMap<Long, MutableSet<Int>>()
    private val keyboardOctave = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    fun setScopeData(id: Long, data: FloatArray) {
        scopeData[id] = data
        invalidate()
    }

    fun clearScopeData() {
        scopeData.clear()
        keyboardNotes.clear()
        invalidate()
    }

    private fun keyboardWhiteKeys(o: PatchObject): Int =
        (objRect(o).width() / 14f).toInt().coerceIn(4, 128)

    /** Black keys sit right of C, D, F, G, A within each octave. */
    private fun keyboardIsBlack(w: Int): Boolean =
        w % 7 == 0 || w % 7 == 1 || w % 7 == 3 || w % 7 == 4 || w % 7 == 5

    private fun keyboardWhitePitch(o: PatchObject, w: Int): Int {
        // real ELSE [keyboard]: first C = lowC*12 + 12 (default lowC = 2)
        val base = (o.args.getOrNull(4)?.toIntOrNull() ?: 2) * 12 + 12
        return base + (w / 7) * 12 + keyboardOctave[w % 7]
    }

    /** Key under the canvas point: black keys win in the upper 60%. */
    private fun keyboardKeyAt(o: PatchObject, cx: Float, cy: Float): Int? {
        val r = objRect(o)
        if (!r.contains(cx, cy)) return null
        val n = keyboardWhiteKeys(o)
        val ww = r.width() / n
        val w = ((cx - r.left) / ww).toInt().coerceIn(0, n - 1)
        if (cy < r.top + r.height() * 0.6f) {
            val bw = ww * 0.6f
            for (b in intArrayOf(w, w - 1)) {
                if (b < 0 || b + 1 >= n || !keyboardIsBlack(b)) continue
                val bx = r.left + (b + 1) * ww
                if (Math.abs(cx - bx) <= bw / 2f) return keyboardWhitePitch(o, b) + 1
            }
        }
        return keyboardWhitePitch(o, w)
    }

    private fun keyboardPress(obj: PatchObject, pitch: Int) {
        keyboardNotes.getOrPut(obj.id) { mutableSetOf() }.add(pitch)
        callbacks?.onKeyboardKey(obj, pitch, true)
        invalidate()
    }

    private fun keyboardRelease(obj: PatchObject, pitch: Int) {
        // the held-notes set drives the drawn key states — a released key
        // must leave it or every slid-over key stays lit ("all keys on")
        keyboardNotes[obj.id]?.remove(pitch)
        callbacks?.onKeyboardKey(obj, pitch, false)
        invalidate()
    }

    private fun releaseKeyboard(obj: PatchObject) {
        val notes = keyboardNotes.remove(obj.id) ?: return
        for (p in notes) callbacks?.onKeyboardKey(obj, p, false)
        invalidate()
    }

    /**
     * Keyboard glissando runs on raw pointer events so every finger tracks
     * its own key: the gesture detectors only ever see the primary pointer,
     * and the old single-pointer path stranded held notes when a second
     * finger demoted the drag to pan — notes then piled up on every later
     * press ("sliding lights all keys").
     */
    private val keyboardPointers = HashMap<Int, Pair<Long, Int>>()

    // ---------------------------------------------------------- app-only [pad]

    /**
     * One finger on a pad. [slot] is the finger's stable ordinal on that
     * pad (lowest free index on press, freed on release) — the finger's
     * position inside the outlet-0 group follows its slot order.
     */
    private class PadTouch(
        val objId: Long,
        val slot: Int,
        var x: Float,
        var y: Float,
        var cellX: Int,
        var cellY: Int,
        var moved: Boolean,
    )

    private val padPointers = HashMap<Int, PadTouch>()

    /** Pads whose finger positions changed since the last flush: drag moves
     *  coalesce into ONE group emission + repaint per frame instead of one
     *  send per move event (a 120 Hz stream flooding pd, the console and the
     *  UI thread made dragging unusable — 0.17.27 user report). */
    private val padMoveDirty = HashSet<Long>()
    private var padFlushPosted = false
    private val padFlush = Runnable {
        padFlushPosted = false
        if (padMoveDirty.isEmpty()) return@Runnable
        var painted = false
        for (id in padMoveDirty) {
            document?.objectById(id)?.let { obj ->
                padEmitGroup(obj, null)
                painted = true
            }
        }
        padMoveDirty.clear()
        if (painted) invalidate()
    }

    /** Lowest finger slot not in use on this pad right now. */
    private fun padFreeSlot(objId: Long): Int {
        var s = 0
        while (padPointers.values.any { it.objId == objId && it.slot == s }) s++
        return s
    }

    /** Normalized position of a canvas point inside a pad's rect (0..1,
     *  top-left origin), clamped — a finger held outside sticks at the edge. */
    private fun padNorm(obj: PatchObject, c: FloatArray): Pair<Float, Float> {
        val r = objRect(obj)
        val x = ((c[0] - r.left) / r.width()).coerceIn(0f, 1f)
        val y = ((c[1] - r.top) / r.height()).coerceIn(0f, 1f)
        return x to y
    }

    private fun padCell(obj: PatchObject, x: Float, y: Float): Pair<Int, Int> =
        (x * PdObjectMeta.padGridX(obj)).toInt().coerceIn(0, PdObjectMeta.padGridX(obj) - 1) to
            (y * PdObjectMeta.padGridY(obj)).toInt().coerceIn(0, PdObjectMeta.padGridY(obj) - 1)

    /** 4-decimal rounding: keeps the emitted floats readable (unpack display)
     *  at more precision than a finger can resolve. */
    private fun padR(v: Float): Float = (v * 10000f).roundToInt() / 10000f

    /**
     * Outlet 0: the two-layer finger group, encoded the only way a pd
     * message can carry it (m_pd.h atom types have NO list atom — messages
     * are flat, libpd's sendList flattens): a leading finger COUNT names
     * the surface layer, then every finger's `x y e` triplet follows in
     * slot order — `list n x1 y1 e1 x2 y2 e2 …`. [list split 1 1] reads
     * n; every following stride of 3 is one inner `{x y e}` element.
     * [releasing] is the finger that just lifted: it appears ONCE at its
     * slot position with e=0, then leaves the group.
     */
    private fun padEmitGroup(obj: PatchObject, releasing: PadTouch?) {
        val fingers = padPointers.values.filter { it.objId == obj.id }.sortedBy { it.slot }
        val n = fingers.size + (if (releasing != null) 1 else 0)
        val out = ArrayList<Any>(1 + n * 3)
        out.add(n)
        var relDone = releasing == null
        for (t in fingers) {
            if (!relDone && t.slot > releasing!!.slot) {
                out.add(padR(releasing.x)); out.add(padR(releasing.y)); out.add(0)
                relDone = true
            }
            out.add(padR(t.x)); out.add(padR(t.y)); out.add(1)
        }
        if (!relDone) {
            out.add(padR(releasing!!.x)); out.add(padR(releasing.y)); out.add(0)
        }
        callbacks?.onGuiList(obj, 0, out)
    }

    private fun padEmitCell(obj: PatchObject, cx: Int, cy: Int, e: Int) {
        callbacks?.onGuiList(obj, 1, listOf(cx, cy, e))
    }

    /**
     * Raw per-pointer pad path (mirrors [handleKeyboardTouch]; the gesture
     * detector only sees the primary pointer and would strand fingers).
     * Active in RUN and LOCK: press/release emit the group immediately
     * (the new finger enters with e=2, the lifted one leaves with e=0);
     * drag moves update positions and coalesce into one group snapshot
     * per frame ([padFlush]). Grid-cell events on outlet 1 fire only on
     * cell crossings (radio-style follow).
     */
    private fun handlePadTouch(e: MotionEvent) {
        if (!interactive) return
        val doc = document ?: return
        fun objById(id: Long): PatchObject? = doc.objects().firstOrNull { it.id == id }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val c = toCanvasPx(e.getX(i), e.getY(i))
                val obj = objAt(c[0], c[1]) ?: return
                if (obj.className() != "pad") return
                if (!objRect(obj).contains(c[0], c[1])) return
                val (x, y) = padNorm(obj, c)
                val (cx, cy) = padCell(obj, x, y)
                val t = PadTouch(
                    obj.id, padFreeSlot(obj.id), x, y, cx, cy, false,
                )
                padPointers[e.getPointerId(i)] = t
                padEmitGroupPress(obj, t)
                padEmitCell(obj, cx, cy, 1)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val t = padPointers[e.getPointerId(p)] ?: continue
                    val c = toCanvasPx(e.getX(p), e.getY(p))
                    val obj = objById(t.objId) ?: continue
                    val (x, y) = padNorm(obj, c)
                    if (x != t.x || y != t.y) {
                        t.x = x; t.y = y; t.moved = true
                        padMoveDirty.add(t.objId)
                    }
                    val (cx, cy) = padCell(obj, x, y)
                    if (cx != t.cellX || cy != t.cellY) {
                        padEmitCell(obj, t.cellX, t.cellY, 0)
                        padEmitCell(obj, cx, cy, 1)
                        t.cellX = cx; t.cellY = cy
                        invalidate()
                    }
                }
                if (padMoveDirty.isNotEmpty() && !padFlushPosted) {
                    padFlushPosted = true
                    post(padFlush)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> padRelease(e.getPointerId(e.actionIndex), ::objById)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL)
                    padPointers.keys.toList()
                else listOf(e.getPointerId(0))
                for (id in ids) padRelease(id, ::objById)
            }
        }
    }

    /** Press emission: the group snapshot with the new finger at e=2
     *  (every other held finger e=1), once, immediately. */
    private fun padEmitGroupPress(obj: PatchObject, fresh: PadTouch) {
        val fingers = padPointers.values.filter { it.objId == obj.id }.sortedBy { it.slot }
        val out = ArrayList<Any>(1 + fingers.size * 3)
        out.add(fingers.size)
        for (t in fingers) {
            out.add(padR(t.x)); out.add(padR(t.y)); out.add(if (t === fresh) 2 else 1)
        }
        callbacks?.onGuiList(obj, 0, out)
    }

    private fun padRelease(pointerId: Int, objById: (Long) -> PatchObject?) {
        val t = padPointers.remove(pointerId) ?: return
        val obj = objById(t.objId) ?: return
        padEmitGroup(obj, t)
        padEmitCell(obj, t.cellX, t.cellY, 0)
        invalidate()
    }

    private fun handleKeyboardTouch(e: MotionEvent) {
        if (!interactive) return
        val doc = document ?: return
        fun objById(id: Long): PatchObject? = doc.objects().firstOrNull { it.id == id }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val c = toCanvasPx(e.getX(i), e.getY(i))
                val obj = objAt(c[0], c[1]) ?: return
                if (obj.className() != "keyboard") return
                keyboardKeyAt(obj, c[0], c[1])?.let { pitch ->
                    keyboardPointers[e.getPointerId(i)] = obj.id to pitch
                    keyboardPress(obj, pitch)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val held = keyboardPointers[e.getPointerId(p)] ?: continue
                    val obj = objById(held.first) ?: continue
                    val c = toCanvasPx(e.getX(p), e.getY(p))
                    val pitch = keyboardKeyAt(obj, c[0], c[1])
                    if (pitch != null && pitch != held.second) {
                        // glissando: leaving a key releases it, the new key
                        // under the same finger takes over
                        keyboardRelease(obj, held.second)
                        keyboardPointers[e.getPointerId(p)] = obj.id to pitch
                        keyboardPress(obj, pitch)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val held = keyboardPointers.remove(e.getPointerId(e.actionIndex)) ?: return
                objById(held.first)?.let { keyboardRelease(it, held.second) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL)
                    keyboardPointers.keys.toList()
                else listOf(e.getPointerId(0))
                for (id in ids) {
                    val held = keyboardPointers.remove(id) ?: continue
                    objById(held.first)?.let { keyboardRelease(it, held.second) }
                }
            }
        }
    }

    /** Switches a radio to the cell under [c] (canvas units) and fires it.
     *  On PRESS ([force]) it always switches, like desktop pd's click; on
     *  SLIDE it only fires when the finger crosses into a DIFFERENT cell —
     *  keyboard-style sweep without re-sending the same value per move. */
    private fun radioSwitch(obj: PatchObject, c: FloatArray, force: Boolean) {
        val r = objRect(obj)
        val cells = PdObjectMeta.radioCells(obj)
        val idx = if (obj.className() == "vradio") {
            ((c[1] - r.top) / (r.height() / cells)).toInt()
        } else {
            ((c[0] - r.left) / (r.width() / cells)).toInt()
        }.coerceIn(0, cells - 1)
        if (!force && idx == obj.runtimeValue.toInt()) return
        obj.runtimeValue = idx.toFloat()
        callbacks?.onGuiTouched(obj, obj.runtimeValue, false)
        invalidate()
    }

    private fun guiPress(obj: PatchObject, c: FloatArray) {
        // graph: plot like desktop pd — touch/drag draws values into the array
        val g = graphBlock(obj)
        if (g != null) {
            graphEditPrev = null // a new gesture starts a fresh stroke
            graphEdit(obj, g, c)
            return
        }
        when (obj.className()) {
            "hsl", "vsl", "knob" -> guiDrag(obj, c)
            // radios switch on touch-down, like desktop pd's click
            "vradio", "hradio" -> radioSwitch(obj, c, force = true)
            // bang fires on touch-down, like a physical instrument key
            "bng" -> {
                obj.flashUntil = System.currentTimeMillis() + 120
                callbacks?.onGuiTouched(obj, 0f, true)
                postInvalidateDelayed(140)
                invalidate()
            }
            // button is a pad: 1 while held, released in guiRelease
            "button" -> {
                obj.runtimeValue = 1f
                callbacks?.onGuiTouched(obj, 1f, false)
                invalidate()
            }
            // number boxes: remember the drag origin for vertical scrubbing
            "floatatom", "nbx" -> {
                guiAtomStartY = c[1]
                guiAtomStartValue = obj.runtimeValue
            }
            // keyboard: handled on the raw per-pointer path, not here
            else -> Unit
        }
    }

    private fun guiDrag(obj: PatchObject, c: FloatArray) {
        val g = graphBlock(obj)
        if (g != null) {
            graphEdit(obj, g, c)
            return
        }
        val r = objRect(obj)
        val range = PdObjectMeta.sliderRange(obj)
        val value = when (obj.className()) {
            "hsl" -> {
                val frac = ((c[0] - r.left) / r.width()).coerceIn(0f, 1f)
                range.min + frac * (range.max - range.min)
            }
            "vsl" -> {
                val frac = ((c[1] - r.top) / r.height()).coerceIn(0f, 1f)
                range.max - frac * (range.max - range.min)
            }
            // knob dial: vertical sweep across the box (the native widget is
            // angular; the app keeps the simpler absolute mapping)
            "knob" -> {
                val frac = ((c[1] - r.top) / r.height()).coerceIn(0f, 1f)
                range.max - frac * (range.max - range.min)
            }
            // number box scrub: drag up/down, one integer step per canvas unit
            "floatatom", "nbx" -> {
                guiAtomStartValue + (guiAtomStartY - c[1]).roundToInt()
            }
            // radios: slide-follow while held, like the keyboard — the cell
            // under the finger switches as it crosses, inside the widget only
            "vradio", "hradio" -> {
                if (r.contains(c[0], c[1])) radioSwitch(obj, c, force = false)
                return
            }
            else -> return
        }
        val quantized = if (PdObjectMeta.isIntSteps(obj) &&
            (obj.className() == "hsl" || obj.className() == "vsl")
        ) {
            value.roundToInt().toFloat().coerceIn(range.min, range.max)
        } else value
        obj.runtimeValue = quantized
        callbacks?.onGuiTouched(obj, quantized, false)
        invalidate()
    }

    /** Release-half of pads ([guiPress] set them): fires on finger-up even
     *  when the gesture drifted or was canceled. */
    private fun guiRelease(obj: PatchObject) {
        if (obj.className() == "button") {
            obj.runtimeValue = 0f
            callbacks?.onGuiTouched(obj, 0f, false)
            invalidate()
        }
    }

    /** Activates a tap-type widget (called on finger-up without a drag). */
    private fun guiTap(obj: PatchObject) {
        when (obj.className()) {
            // one tap = one trigger, when the widget is interactive
            "euclid" -> if ((obj.args.getOrNull(6)?.toIntOrNull() ?: 1) != 0) {
                obj.flashUntil = System.currentTimeMillis() + 120
                callbacks?.onGuiTouched(obj, 1f, true)
                postInvalidateDelayed(140)
            }
            // circle5: tapping a major/minor cell emits its chord and sets
            // the current key (the inner ring is display-only)
            "circle5" -> if ((obj.args.getOrNull(5)?.toIntOrNull() ?: 1) != 0) {
                circle5CellAt(obj, downCanvas)?.let { cell ->
                    callbacks?.onGuiTouched(obj, cell.toFloat(), false)
                }
            }
            "tgl" -> {
                val v = if (obj.runtimeValue > 0.5f) 0f else 1f
                obj.runtimeValue = v
                callbacks?.onGuiTouched(obj, v, false)
                invalidate()
            }
            // bng fires on press-down (see guiPress); nothing on release;
            // radios also switched on press-down (see guiPress)
            "vradio", "hradio" -> Unit
            "floatatom", "nbx" -> callbacks?.onNumberEntryRequested(obj)
            // tapping a message box in run mode fires it into the flow;
            // flash the box like a bang so the tap has visible feedback
            "msg" -> {
                obj.flashUntil = System.currentTimeMillis() + 120
                postInvalidateDelayed(140)
                callbacks?.onMsgTriggered(obj)
                invalidate()
            }
            else -> Unit
        }
    }

    /** Called by the activity after the number keypad confirms. */
    fun setAtomValue(obj: PatchObject, value: Float) {
        obj.runtimeValue = value
        obj.runtimeText = "" // a typed value supersedes any pending list text
        callbacks?.onGuiTouched(obj, value, false)
        invalidate()
    }

    /**
     * Graph touch-editing (run mode, like plotting in desktop pd). Touch is
     * not a mouse: the pad covers a ~22dp-radius span of cells, and a drag
     * sweeps every cell between the previous and current touch position with
     * interpolated values, so fast strokes leave no gaps. Values go straight
     * into the running pd array (one range write per event) and the buffers.
     */
    private fun graphEdit(
        obj: PatchObject,
        g: com.pocketpd.app.patch.GraphBlock,
        c: FloatArray,
    ) {
        val coords = g.coords ?: return
        val arr = g.arrays.firstOrNull() ?: return
        val r = objRect(obj)
        val size = (PdEngine.arraySize(arr.name).takeIf { it > 0 } ?: arr.size)
            .coerceAtMost(maxOf(arr.live?.size ?: 0, arr.saved.size))
        if (size <= 0) return
        val xSpan = (coords.xmax - coords.xmin).takeIf { it != 0f } ?: 1f
        val idxAt = fun(px: Float, py: Float): Pair<Float, Float> {
            val idx = ((px - r.left) / r.width().coerceAtLeast(1f)) * xSpan + coords.xmin
            val frac = ((py - r.top) / r.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
            return idx to (coords.ymax - frac * (coords.ymax - coords.ymin))
        }
        val (idxF, value) = idxAt(c[0], c[1])
        val prev = graphEditPrev
        val (prevIdx, prevVal) = if (prev != null) idxAt(prev[0], prev[1]) else idxF to value
        // fingertip radius in cells: 22dp of screen mapped through the zoom
        val finger = dp(22f) / zoom * (xSpan / r.width().coerceAtLeast(1f))
        val i0 = (min(prevIdx, idxF) - finger).toInt().coerceIn(0, size - 1)
        val i1 = (ceil(max(prevIdx, idxF) + finger)).toInt().coerceIn(0, size - 1)
        if (i1 < i0) return
        val count = i1 - i0 + 1
        var scratch = graphScratch[arr.name]
        if (scratch == null || scratch.size < size) {
            scratch = FloatArray(size)
            graphScratch[arr.name] = scratch
        }
        var k = 0
        for (idx in i0..i1) {
            val t = if (abs(idxF - prevIdx) < 1e-6f) 1f
            else ((idx - prevIdx) / (idxF - prevIdx)).coerceIn(0f, 1f)
            val v = prevVal + t * (value - prevVal)
            arr.live?.let { if (idx < it.size) it[idx] = v }
            if (idx < arr.saved.size) arr.saved[idx] = v
            scratch[k++] = v
        }
        if (!PdEngine.writeArrayRange(arr.name, i0, scratch, 0, count)) {
            Log.i(TAG, "graph write failed: ${arr.name}[$i0..$i1]")
        }
        graphEditPrev = floatArrayOf(c[0], c[1])
        invalidate()
    }

    /**
     * Creates a fresh graph-on-parent block (add-bar "graph" key): a subpatch
     * canvas holding one new array plus its `#X coords`, matching what pd
     * writes for Put → Graph. Subsequent saves round-trip it verbatim.
     */
    fun addGraphBlock(cx: Float, cy: Float): PatchObject {
        val doc = document!!
        val used = doc.objects().flatMap { o ->
            o.graph?.arrays?.map { a -> a.name } ?: emptyList()
        }.toMutableSet()
        val graphCount = doc.objects().count { it.subpatchLines != null } + 1
        var n = graphCount
        var arrName = "array$n"
        while (arrName in used) { n++; arrName = "array$n" }
        val graphName = "graph$n"
        val size = 100
        val obj = PatchObject(
            newObjectId(), cx.toInt(), cy.toInt(), ObjKind.OBJECT,
            mutableListOf("pd", graphName),
        )
        val g = com.pocketpd.app.patch.GraphBlock(
            mutableListOf(com.pocketpd.app.patch.PdArray(arrName, size, 0, true)),
        )
        g.coords = com.pocketpd.app.patch.GraphCoords(0f, 1f, size.toFloat(), -1f, 200, 100, 1)
        obj.graph = g
        obj.subpatchLines = mutableListOf(
            "#N canvas 0 0 450 300 (subpatch) 0;",
            "#X array $arrName $size float 1;",
            "#X coords 0 1 $size -1 200 100 1;",
            "#X restore ${obj.x} ${obj.y} graph;",
        )
        obj.meta = PdObjectMeta.resolve(obj)
        stack.execute(AddObjectCommand(doc, obj) {})
        setSelection(listOf(obj.id))
        return obj
    }

    /** Called from Pd (via the activity) to flash bangs / refresh widget values. */
    fun refreshGui(objId: Long) {
        invalidate()
        postInvalidateDelayed(150) // repaint after a bang flash expires
    }

    // --------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawGrid(canvas)
        val doc = document ?: return

        // graph-on-parent visible range, like vanilla pd's red goprect
        doc.gop?.takeIf { it.enabled }?.let { g ->
            val r = g.rect()
            canvas.drawRect(
                screenX(r.left.toFloat()), screenY(r.top.toFloat()),
                screenX(r.right.toFloat()), screenY(r.bottom.toFloat()),
                gopRangeStroke,
            )
        }

        for (c in doc.connections) {
            val a = objectById(c.fromId) ?: continue
            val b = objectById(c.toId) ?: continue
            val p0 = cordAnchor(a, c.outlet, false)
            val p3 = cordAnchor(b, c.inlet, true)
            val sig = isSignalPort(a, c.outlet, false)
            // pd rejects signal→control connects at load ("signal outlet
            // connect to nonsignal inlet"); flag them in error red. Derived
            // per frame from the current metas, so fixing either endpoint's
            // class or deleting the cord restores the normal color.
            val bad = sig && !isSignalPort(b, c.inlet, true)
            val selCord = selectedConnection?.id == c.id
            // control handles leave the port perpendicular; their length is
            // capped at half the outlet↔inlet vertical distance, so cords
            // between near-level ports never loop past the endpoint
            val lead = min(
                units(if (sig) LEAD_SIG else LEAD_MSG),
                Math.abs(p3[1] - p0[1]) / 2f,
            )
            path.reset()
            path.moveTo(p0[0], p0[1])
            path.cubicTo(p0[0], p0[1] + lead, p3[0], p3[1] - lead, p3[0], p3[1])
            canvas.drawPath(path, when {
                selCord -> cordSelected
                bad -> cordError
                sig -> cordSig
                else -> cordMsg
            })
        }

        for (o in doc.objects()) drawObject(canvas, o)

        // pending cord
        val from = pendingFrom
        if (drag == Drag.CORD && from != null) {
            val obj = objectById(from.objectId)
            if (obj != null) {
                val start = cordAnchor(obj, from.index, from.isInlet)
                val target = pendingTo
                val end = if (target != null && target.objectId != from.objectId) {
                    val tobj = objectById(target.objectId)
                    if (tobj != null) cordAnchor(tobj, target.index, target.isInlet) else pendingPoint
                } else pendingPoint
                canvas.drawLine(start[0], start[1], end[0], end[1], cordPending)
                if (target != null) {
                    val tobj = objectById(target.objectId)
                    if (tobj != null) canvas.drawRect(portRectScreen(tobj, target.index, target.isInlet), portHitRing)
                }
            }
        }

        if (!marquee.isEmpty) {
            canvas.drawRect(
                screenX(marquee.left), screenY(marquee.top),
                screenX(marquee.right), screenY(marquee.bottom),
                marqueeFill,
            )
            canvas.drawRect(
                screenX(marquee.left), screenY(marquee.top),
                screenX(marquee.right), screenY(marquee.bottom),
                marqueeStroke,
            )
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val stepMinor = units(GRID)
        val stepMajor = units(GRID * 5)
        if (stepMinor >= dp(7f)) {
            var x = camX % stepMinor
            while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridMinor); x += stepMinor }
            var y = camY % stepMinor
            while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, gridMinor); y += stepMinor }
        }
        var x = camX % stepMajor
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridMajor); x += stepMajor }
        var y = camY % stepMajor
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, gridMajor); y += stepMajor }
    }

    private fun drawObject(canvas: Canvas, o: PatchObject) {
        val r = objRect(o)
        val l = screenX(r.left); val t = screenY(r.top)
        val rr = screenX(r.right); val bb = screenY(r.bottom)
        val selected = o.id in selection
        val cls = o.className()

        // pd "couldn't create" objects (statically unknown, or failed at
        // pd-side instantiation): red dashed frame, no ports
        val broken = !o.meta.exists || PdEngine.failedClasses.contains(cls)
        if (broken) {
            boxFill.color = COL_BOX_BG
            canvas.drawRect(l, t, rr, bb, boxFill)
            canvas.drawRect(l, t, rr, bb, gopRangeStroke)
            textPaint.textSize = units(FS_BOX)
            val keep = textPaint.color
            textPaint.color = COL_GOP_RANGE
            // ellipsis truncation is forbidden (standing user rule): the
            // name draws in full even if it spills past the fixed GOP rect
            canvas.drawText(
                o.displayName(),
                l + units(5f), t + units(BOX_H) * 0.72f, textPaint,
            )
            textPaint.color = keep
            if (selected) {
                canvas.drawRect(l - dp(3f), t - dp(3f), rr + dp(3f), bb + dp(3f), selStroke)
            }
            return
        }
        val graph = graphBlock(o)
        if (graph != null) {
            drawGraph(canvas, o, graph, l, t, rr, bb)
            if (selected) {
                canvas.drawRect(l - dp(3f), t - dp(3f), rr + dp(3f), bb + dp(3f), selStroke)
            }
            for (i in 0 until o.meta.effInlets()) {
                drawPort(canvas, portRectScreen(o, i, true),
                    isSignalPort(o, i, true), portConnected(o.id, i, true))
            }
            for (i in 0 until o.meta.effOutlets()) {
                drawPort(canvas, portRectScreen(o, i, false),
                    isSignalPort(o, i, false), portConnected(o.id, i, false))
            }
            return
        }

        // graph-on-parent abstraction: frame plus the abstraction's own
        // widgets drawn inside the visible window (like desktop pd)
        o.gop?.let { drawGop(canvas, o, it, selected, l, t, rr, bb); return }

        // box / widget body
        when {
            o.kind == ObjKind.COMMENT -> Unit
            cls == "cnv" -> {
                boxFill.color = COL_CNV_BG
                canvas.drawRect(l, t, rr, bb, boxFill)
                boxFill.color = COL_BOX_BG
            }
            // msg boxes draw their own vanilla fold outline in drawMsg —
            // a plain rectangle underneath would keep a straight right edge
            o.kind == ObjKind.MSG -> Unit
            else -> {
                canvas.drawRect(l, t, rr, bb, boxFill)
                canvas.drawRect(l, t, rr, bb, boxStroke)
            }
        }

        drawWidgetBody(canvas, o, l, t, rr, bb)
        drawWidgetLabel(canvas, o, l, t, rr, bb)

        if (selected) {
            canvas.drawRect(l - dp(3f), t - dp(3f), rr + dp(3f), bb + dp(3f), selStroke)
        }

        // ports, drawn like desktop pd: a small rectangle sitting on the edge,
        // hollow when unconnected, filled once a cord is attached. Visible in
        // both edit and run mode so wiring stays readable.
        for (i in 0 until o.meta.effInlets()) {
            drawPort(canvas, portRectScreen(o, i, true),
                isSignalPort(o, i, true), portConnected(o.id, i, true))
        }
        for (i in 0 until o.meta.effOutlets()) {
            drawPort(canvas, portRectScreen(o, i, false),
                isSignalPort(o, i, false), portConnected(o.id, i, false))
        }
    }

    /** The visible content of one box: comment text, atoms, msg flag, widget. */
    private fun drawWidgetBody(canvas: Canvas, o: PatchObject, l: Float, t: Float, rr: Float, bb: Float) {
        val cls = o.className()
        when {
            o.kind == ObjKind.COMMENT -> {
                commentPaint.textSize = units(FS_COMMENT)
                canvas.drawText(o.displayName(), l + dp(2f), t + units(FS_COMMENT), commentPaint)
            }
            o.kind == ObjKind.FLOATATOM || o.kind == ObjKind.SYMBOLATOM || o.kind == ObjKind.LISTBOX -> {
                drawAtom(canvas, o, l, t, rr, bb)
            }
            o.kind == ObjKind.MSG -> {
                drawMsg(canvas, o, l, t, rr, bb)
            }
            else -> when (cls) {
                "bng" -> drawBang(canvas, o, l, t, rr, bb)
                "button" -> drawButton(canvas, o, l, t, rr, bb)
                "popmenu" -> drawPopmenu(canvas, o, l, t, rr, bb)
                "pad" -> drawPad(canvas, o, l, t, rr, bb)
                "euclid" -> drawEuclid(canvas, o, l, t, rr, bb)
                "circle5" -> drawCircle5(canvas, o, l, t, rr, bb)
                "tgl" -> drawToggle(canvas, o, l, t, rr, bb)
                "hsl", "vsl" -> drawSlider(canvas, o, l, t, rr, bb)
                "knob" -> drawKnob(canvas, o, l, t, rr, bb)
                "vradio", "hradio" -> drawRadio(canvas, o, l, t, rr, bb)
                "vu" -> drawVu(canvas, o, l, t, rr, bb)
                "keyboard" -> drawKeyboard(canvas, o, l, t, rr, bb)
                "graph~" -> drawScope(canvas, o, l, t, rr, bb, spectrum = false)
                "spectrograph~" -> drawScope(canvas, o, l, t, rr, bb, spectrum = true)
                // cnv (my_canvas) is just a colored panel; its label is drawn
                // by drawWidgetLabel — never its argument tokens
                "cnv" -> Unit
                // pd's iemgui number box renders like a number atom
                "nbx" -> {
                    textPaint.textSize = units(FS_BOX)
                    // lists (chord voicings...) show in full, like floatatom
                    val text = if (o.runtimeText.isNotEmpty()) o.runtimeText
                    else formatValue(o.runtimeValue)
                    canvas.drawText(
                        text,
                        l + units(5f), t + units(BOX_H) * 0.72f, textPaint,
                    )
                }
                // else/display and display~ (0.17.135): the box IS the widget —
                // it shows the last inbound message, which the abstraction
                // echoes over the shared pocketpd-display[~] channel or the
                // instance's private tag into runtimeText/runtimeValue
                // (PdEngine.bindGuiSymbols); before anything arrived it still
                // reads as an ordinary box. Both spellings (bare and
                // else/-prefixed) land here.
                "display", "display~", "else/display", "else/display~" -> {
                    val text = when {
                        o.runtimeText.isNotEmpty() -> o.runtimeText
                        o.runtimeValue != 0f -> formatValue(o.runtimeValue)
                        else -> ""
                    }
                    if (text.isNotEmpty()) {
                        textPaint.textSize = units(FS_BOX)
                        canvas.drawText(
                            text,
                            l + units(5f), t + units(BOX_H) * 0.72f, textPaint,
                        )
                    } else {
                        // placeholder = the bare class name, never the args:
                        // the private receive tag must not render (desktop pd
                        // shows an empty-content shape)
                        textPaint.textSize = units(FS_BOX)
                        canvas.drawText(
                            o.className().substringAfterLast('/'),
                            l + units(5f), t + units(BOX_H) * 0.72f, textPaint,
                        )
                    }
                }
                else -> drawTextInBox(canvas, o, l, t, rr)
            }
        }
    }

    /**
     * Graph-on-parent abstraction body (desktop pd goprect): the w×h frame,
     * then every depth-1 widget/atom/comment that fits the visible window,
     * drawn at its absolute position (expansion already translated the
     * abstraction's margin offset into parent space). Like pd, the internal
     * cords stay hidden and the abstraction's own iolets are the box ports.
     */
    private fun drawGop(
        canvas: Canvas,
        o: PatchObject,
        gop: com.pocketpd.app.patch.GopEmbed,
        selected: Boolean,
        l: Float, t: Float, rr: Float, bb: Float,
    ) {
        boxFill.color = COL_GRAPH_BG
        canvas.drawRect(l, t, rr, bb, boxFill)
        boxFill.color = COL_BOX_BG
        canvas.drawRect(l, t, rr, bb, boxStroke)
        for (w in gop.widgets) {
            val wr = objRect(w)
            val wl = screenX(wr.left); val wt = screenY(wr.top)
            val wrr = screenX(wr.right); val wbb = screenY(wr.bottom)
            if (wrr < l || wl > rr || wbb < t || wt > bb) continue
            drawWidgetBody(canvas, w, wl, wt, wrr, wbb)
            drawWidgetLabel(canvas, w, wl, wt, wrr, wbb)
        }
        if (selected) {
            canvas.drawRect(l - dp(3f), t - dp(3f), rr + dp(3f), bb + dp(3f), selStroke)
        }
        for (i in 0 until o.meta.effInlets()) {
            drawPort(canvas, portRectScreen(o, i, true),
                isSignalPort(o, i, true), portConnected(o.id, i, true))
        }
        for (i in 0 until o.meta.effOutlets()) {
            drawPort(canvas, portRectScreen(o, i, false),
                isSignalPort(o, i, false), portConnected(o.id, i, false))
        }
    }

    /**
     * Draws a widget's built-in label: iemgui widgets keep label text plus
     * an offset (ldx/ldy) from their top-left corner; float/symbol atoms
     * carry a label with a side selector (left/right/top/bottom).
     */
    private fun drawWidgetLabel(canvas: Canvas, o: PatchObject, l: Float, t: Float, rr: Float, bb: Float) {
        val text = o.meta.labelText
        if (text.isEmpty()) return
        commentPaint.textSize = units(FS_COMMENT)
        when {
            o.kind == ObjKind.FLOATATOM || o.kind == ObjKind.SYMBOLATOM -> {
                val pos = o.args.getOrNull(4)?.toIntOrNull() ?: 0
                when (pos) {
                    0 -> {
                        commentPaint.textAlign = Paint.Align.RIGHT
                        canvas.drawText(text, l - units(3f), t + units(BOX_H) * 0.72f, commentPaint)
                        commentPaint.textAlign = Paint.Align.LEFT
                    }
                    1 -> canvas.drawText(text, rr + units(3f), t + units(BOX_H) * 0.72f, commentPaint)
                    2 -> canvas.drawText(text, l, t - units(2f), commentPaint)
                    else -> canvas.drawText(text, l, bb + units(FS_COMMENT), commentPaint)
                }
            }
            PdObjectMeta.isIemGui(o.className()) -> {
                val (dx, dy) = PdObjectMeta.labelOffset(o)
                canvas.drawText(text, screenX((o.x + dx).toFloat()), screenY((o.y + dy).toFloat()), commentPaint)
            }
        }
    }

    private fun portConnected(id: Long, index: Int, inlet: Boolean): Boolean {
        for (c in (document ?: return false).connections) {
            if (inlet && c.toId == id && c.inlet == index) return true
            if (!inlet && c.fromId == id && c.outlet == index) return true
        }
        return false
    }

    /**
     * Draws a graph-on-parent body: framed viewport, then one polyline per
     * array. Values come from the pd instance when one has been polled (run
     * mode), otherwise from the `#A` contents stored in the file. Long arrays
     * are decimated so a 44k-sample table never stalls the draw.
     */
    private fun drawGraph(
        canvas: Canvas,
        o: PatchObject,
        g: com.pocketpd.app.patch.GraphBlock,
        l: Float, t: Float, rr: Float, bb: Float,
    ) {
        val c = g.coords ?: return
        graphFill.setColor(COL_GRAPH_BG)
        canvas.drawRect(l, t, rr, bb, graphFill)
        canvas.drawRect(l, t, rr, bb, boxStroke)
        // curves stay inside the graph: arrays longer than the x range (or
        // values rounding past an edge) must not paint outside the box
        canvas.save()
        canvas.clipRect(l, t, rr, bb)
        // zero line when the range straddles it
        if (c.ymin < 0f && c.ymax > 0f) {
            val zeroY = t + (c.ymax / (c.ymax - c.ymin)) * (bb - t)
            canvas.drawLine(l, zeroY, rr, zeroY, graphAxis)
        }
        val w = rr - l
        val h = bb - t
        val xSpan = (c.xmax - c.xmin).takeIf { it != 0f } ?: 1f
        val ySpan = (c.ymax - c.ymin).takeIf { it != 0f } ?: 1f
        g.arrays.forEachIndexed { ai, arr ->
            val vals = if (interactive) arr.live ?: arr.saved else arr.saved
            val n = vals.size
            if (n == 0) return@forEachIndexed
            graphCurve.color = if (ai == 0) COL_ACTIVE else COL_COMMENT
            // arr.style is pd's `#X array` filestyle (g_graph.c garray_save):
            // 0 = polygon (default line), 1 = points, 2 = bezier curve
            val count = graphPlot(vals, n, l, t, w, h, c, xSpan, ySpan)
            when (arr.style) {
                1 -> { // points: dots at every sample (both band edges when a
                    // column covers several samples)
                    graphDot.color = if (ai == 0) COL_ACTIVE else COL_COMMENT
                    for (i in 0 until count) {
                        canvas.drawPoint(plotX[i], plotTop[i], graphDot)
                        if (plotBot[i] != plotTop[i]) {
                            canvas.drawPoint(plotX[i], plotBot[i], graphDot)
                        }
                    }
                }
                2 -> { // bezier: Catmull-Rom segments through the plot points
                    path.rewind()
                    catmullThrough(path, plotTop, 0, count - 1, rev = false, move = true)
                    if (plotBot[count - 1] != plotTop[count - 1]) {
                        // envelope band: return along the min edge
                        path.lineTo(plotX[count - 1], plotBot[count - 1])
                        catmullThrough(path, plotBot, 0, count - 1, rev = true, move = false)
                    }
                    canvas.drawPath(path, graphCurve)
                }
                else -> { // polygon (pd default): line segments, vertical
                    // min/max envelope when a column covers several samples
                    path.rewind()
                    path.moveTo(plotX[0], plotTop[0])
                    for (i in 0 until count) {
                        path.lineTo(plotX[i], plotTop[i])
                        if (plotBot[i] != plotTop[i]) path.lineTo(plotX[i], plotBot[i])
                    }
                    canvas.drawPath(path, graphCurve)
                }
            }
        }
        canvas.restore()
        // array names, small in the top-left corner
        textPaint.textSize = units(FS_COMMENT)
        val label = g.arrays.joinToString(",") { it.name }
        // full array-name list, never truncated (ellipsis forbidden)
        canvas.drawText(
            label, l + units(4f), t + units(FS_COMMENT) + units(2f), textPaint,
        )
    }

    /**
     * Maps an array onto the graph's pixel columns into the plot* scratch
     * buffers: one entry per sample when zoomed in that far, otherwise a
     * per-column min/max envelope. Stride-decimation (skipping samples) made
     * the curve's shape depend on the zoom level — peaks appeared or vanished
     * as the view scaled — while the envelope keeps every extremum at any
     * zoom and converges to the per-sample plot below. plotTop is the pixel
     * y of the column's maximum, plotBot of its minimum (equal once one
     * sample maps to a column). Returns the entry count.
     */
    private fun graphPlot(
        vals: FloatArray, n: Int,
        l: Float, t: Float, w: Float, h: Float,
        c: com.pocketpd.app.patch.GraphCoords, xSpan: Float, ySpan: Float,
    ): Int {
        val cols = w.toInt().coerceAtLeast(1)
        val block = n / cols
        fun ensure(i: Int) {
            if (plotX.size < i) {
                plotX = FloatArray(i)
                plotTop = FloatArray(i)
                plotBot = FloatArray(i)
            }
        }
        val yLoLim = min(c.ymin, c.ymax)
        val yHiLim = max(c.ymin, c.ymax)
        fun yPix(v: Float) = t + (c.ymax - v.coerceIn(yLoLim, yHiLim)) / ySpan * h
        if (block < 2) {
            ensure(n)
            for (i in 0 until n) {
                plotX[i] = l + ((i - c.xmin) / xSpan) * w
                val y = yPix(vals[i])
                plotTop[i] = y
                plotBot[i] = y
            }
            return n
        }
        // the envelope covers ceil(n/block) columns — when the length is not
        // a multiple of the block size that exceeds cols (n=2048 into a 552px
        // graph writes 683), so size the buffers for the real count
        ensure((n + block - 1) / block)
        var k = 0
        var i = 0
        while (i < n) {
            val end = min(i + block, n)
            var mn = vals[i]
            var mx = vals[i]
            for (j in i + 1 until end) {
                val v = vals[j]
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            plotX[k] = l + ((i - c.xmin) / xSpan) * w
            plotTop[k] = yPix(mx)
            plotBot[k] = yPix(mn)
            k++
            i = end
        }
        return k
    }

    /**
     * Extends [p] with Catmull-Rom spline segments (converted to cubic
     * beziers, pd PLOTSTYLE_BEZ look) through plot columns [from]..[to] of
     * [ys]; [rev] walks the columns backwards (the return leg of an envelope
     * band), [move] starts a new subpath instead of continuing the current one.
     */
    private fun catmullThrough(
        p: Path, ys: FloatArray, from: Int, to: Int, rev: Boolean, move: Boolean,
    ) {
        val cnt = abs(to - from) + 1
        if (cnt == 0) return
        fun pt(k: Int) = if (rev) to - k else from + k
        if (move) p.moveTo(plotX[pt(0)], ys[pt(0)]) else p.lineTo(plotX[pt(0)], ys[pt(0)])
        if (cnt == 1) return
        if (cnt == 2) {
            p.lineTo(plotX[pt(1)], ys[pt(1)])
            return
        }
        for (k in 0 until cnt - 1) {
            val a = pt(max(k - 1, 0))
            val b = pt(k)
            val c = pt(k + 1)
            val d = pt(min(k + 2, cnt - 1))
            p.cubicTo(
                plotX[b] + (plotX[c] - plotX[a]) / 6f, ys[b] + (ys[c] - ys[a]) / 6f,
                plotX[c] - (plotX[d] - plotX[b]) / 6f, ys[c] - (ys[d] - ys[b]) / 6f,
                plotX[c], ys[c],
            )
        }
    }

    /**
     * Draws one port as a flat rectangle that sits [inlet]? just inside the
     * top edge : just inside the bottom edge of the object box - the way
     * desktop pd renders its inlets/outlets. [cy] is the edge y coordinate.
     */
    private fun drawPort(canvas: Canvas, r: RectF, sig: Boolean, connected: Boolean) {
        canvas.drawRect(r, portBodyEmpty)
        if (connected) canvas.drawRect(r, if (sig) portFillSig else portFillMsg)
        canvas.drawRect(r, if (sig) portStrokeSig else portStrokeMsg)
    }

    private fun drawTextInBox(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float) {
        // ellipsis truncation is forbidden (standing user rule); objRect
        // guarantees the box grows past the measured glyphs
        textPaint.textSize = units(FS_BOX)
        canvas.drawText(o.displayName(), l + units(5f), t + units(BOX_H) * 0.72f, textPaint)
    }

    private fun drawAtom(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        drawTextInBox(canvas, o, l, t, r)
    }

    private fun drawMsg(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        // vanilla message-box outline: the right edge folds inward, tip at
        // mid height pointing left; a taller box stretches the tip into a
        // vertical line at the same depth instead of steepening the diagonals
        val depth = units(6f)
        val rise = units(BOX_H) / 2
        val midY = (t + b) / 2
        path.reset()
        path.moveTo(l, t)
        path.lineTo(r, t)
        if (b - t > rise * 2) {
            path.lineTo(r - depth, t + rise)
            path.lineTo(r - depth, b - rise)
        } else {
            path.lineTo(r - depth, midY)
        }
        path.lineTo(r, b)
        path.lineTo(l, b)
        path.close()
        // run-mode feedback (like bng): the box fills signal-cyan while the
        // message fires, text inverted to stay readable
        val flashing = System.currentTimeMillis() < o.flashUntil
        if (flashing) boxFill.color = COL_ACTIVE
        canvas.drawPath(path, boxFill)
        if (flashing) boxFill.color = COL_BOX_BG
        canvas.drawPath(path, boxStroke)
        textPaint.textSize = units(FS_BOX)
        if (flashing) textPaint.color = COL_BG
        // ellipsis truncation is forbidden (standing user rule): each line
        // draws in full; objRect sizes the box past the fold for the widest
        // measured line
        val lineH = units(20f)
        o.msgDisplayLines().forEachIndexed { i, line ->
            canvas.drawText(
                line,
                l + units(5f),
                t + units(BOX_H) * 0.72f + i * lineH,
                textPaint,
            )
        }
        if (flashing) textPaint.color = COL_TEXT
    }

    private fun drawBang(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        // PC-98 bng (design 05 system sheet): square outline outside, circle
        // outline inside; the circle fills signal-cyan only while flashing.
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val rad = min(r - l, b - t) / 2 - dp(4f)
        val flashing = System.currentTimeMillis() < o.flashUntil
        canvas.drawRect(l, t, r, b, boxStroke)
        if (flashing) {
            val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COL_ACTIVE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, rad, circle)
        }
        canvas.drawCircle(cx, cy, rad, boxStroke)
    }

    /** App-only button pad: filled while held (runtimeValue is the pressed
     *  state; pd-side floats ≥0.5 light it up the same way). */
    private fun drawButton(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        boxFill.color = if (o.runtimeValue > 0.5f) COL_ACTIVE else COL_BOX_BG
        canvas.drawRect(l, t, r, b, boxFill)
        canvas.drawRect(l, t, r, b, boxStroke)
        boxFill.color = COL_BOX_BG
    }

    /** popmenu's live selection (app-side; args[13] seeds it on load). */
    private val popmenuSelection = HashMap<Long, Int>()

    private fun popmenuCurrentItem(o: PatchObject): String? {
        val items = PdObjectMeta.popmenuItems(o)
        val idx = (popmenuSelection[o.id] ?: o.args.getOrNull(13)?.toIntOrNull() ?: -1)
        return items.getOrNull(idx)
    }

    private fun drawPopmenu(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        val bg = runCatching {
            android.graphics.Color.parseColor(o.args.getOrNull(4) ?: "#dfdfdf")
        }.getOrDefault(0xFFDFDFDF.toInt())
        val fg = runCatching {
            android.graphics.Color.parseColor(o.args.getOrNull(5) ?: "black")
        }.getOrDefault(0xFF000000.toInt())
        boxFill.color = bg
        canvas.drawRect(l, t, r, b, boxFill)
        canvas.drawRect(l, t, r, b, boxStroke)
        boxFill.color = COL_BOX_BG
        textPaint.textSize = units(
            (o.args.getOrNull(3)?.toIntOrNull()?.coerceIn(8, 40) ?: 12).toFloat(),
        )
        val oldColor = textPaint.color
        textPaint.color = fg
        val text = popmenuCurrentItem(o) ?: ""
        // full item text, never truncated (ellipsis forbidden)
        canvas.drawText(
            text, l + dp(6f), (t + b) / 2f + textPaint.textSize / 3f, textPaint,
        )
        // the dropdown arrow
        val cy = (t + b) / 2f
        val ax = r - dp(14f)
        val arrow = Path().apply {
            moveTo(ax - dp(4f), cy - dp(2f))
            lineTo(ax + dp(4f), cy - dp(2f))
            lineTo(ax, cy + dp(3f))
            close()
        }
        canvas.drawPath(arrow, textPaint)
        textPaint.color = oldColor
    }

    /** Run-mode tap on a [popmenu]: the dropdown, then the pick routed out
     *  per the configured output mode (0 index float, 1 item symbol,
     *  2 both - mirroring upstream popmenu.c's outmode). */
    private fun showPopmenuPopup(o: PatchObject) {
        val items = PdObjectMeta.popmenuItems(o)
        if (items.isEmpty()) return
        val r = objRect(o)
        val popup = android.widget.ListPopupWindow(context)
        popup.setAdapter(
            android.widget.ArrayAdapter(
                context, android.R.layout.simple_list_item_1, items,
            )
        )
        popup.setAnchorView(this)
        popup.setHorizontalOffset(screenX(r.left).toInt())
        popup.setVerticalOffset(screenY(r.bottom).toInt() + 2)
        popup.setWidth(r.width().toInt().coerceAtLeast(dp(160f).toInt()))
        popup.setModal(true)
        popup.setOnItemClickListener { _, _, pos, _ ->
            popup.dismiss()
            popmenuSelection[o.id] = pos
            invalidate()
            val outmode = o.args.getOrNull(12)?.toIntOrNull()?.coerceIn(0, 2) ?: 0
            if (outmode == 0 || outmode == 2) callbacks?.onGuiTouched(o, pos.toFloat(), false)
            if (outmode == 1 || outmode == 2) callbacks?.onGuiSymbol(o, items[pos])
        }
        popup.show()
    }

    private val padGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_BOX_STROKE; strokeWidth = dp(0.75f); style = Paint.Style.STROKE
    }
    private val padCellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_ACTIVE; alpha = 80
    }
    private val padDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_SELECT; style = Paint.Style.FILL
    }
    private val padDotHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_SELECT; style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }

    /**
     * App-only [pad]: dark panel with a gridx×gridy grid (hidden at the 1×1
     * default), the cells under live fingers filled, and a dot + halo at
     * every tracked finger (visual press feedback, [padPointers]).
     */
    private fun drawPad(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        val gx = PdObjectMeta.padGridX(o)
        val gy = PdObjectMeta.padGridY(o)
        val w = r - l
        val h = b - t
        canvas.drawRect(l, t, r, b, boxFill)
        // active cells first, grid lines on top
        val touches = padPointers.values.filter { it.objId == o.id }
        for (tc in touches) {
            val cw = w / gx
            val ch = h / gy
            canvas.drawRect(
                l + tc.cellX * cw, t + tc.cellY * ch,
                l + (tc.cellX + 1) * cw, t + (tc.cellY + 1) * ch,
                padCellPaint,
            )
        }
        if (gx > 1) {
            for (i in 1 until gx) {
                val x = l + w * i / gx
                canvas.drawLine(x, t, x, b, padGridPaint)
            }
        }
        if (gy > 1) {
            for (j in 1 until gy) {
                val y = t + h * j / gy
                canvas.drawLine(l, y, r, y, padGridPaint)
            }
        }
        canvas.drawRect(l, t, r, b, boxStroke)
        for (tc in touches) {
            val px = l + tc.x * w
            val py = t + tc.y * h
            canvas.drawCircle(px, py, dp(4f), padDotPaint)
            canvas.drawCircle(px, py, dp(8f), padDotHalo)
        }
    }

    private val euclidPoly = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_ACTIVE; style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val euclidDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_ACTIVE }
    private val euclidPtr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_SELECT; strokeWidth = dp(2f)
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val euclidTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_BOX_STROKE }
    private val euclidPath = Path()

    /**
     * App-only euclidean rhythm clock: one tick per step on the outer ring,
     * a pointer at the play position (runtimeValue, engine-owned), hit dots
     * connected into a polygon (1 hit = dot, 2 = line, 3+ = closed shape)
     * and a hits/steps readout in the middle. Tapping an interactive widget
     * triggers one step (see [guiTap]).
     */
    private fun drawEuclid(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        val pat = EuclidRhythm.pattern(
            o.args.getOrNull(1)?.toIntOrNull() ?: 3,
            o.args.getOrNull(2)?.toIntOrNull() ?: 8,
            o.args.getOrNull(3)?.toIntOrNull() ?: 0,
        )
        val n = pat.size
        val interactive = (o.args.getOrNull(6)?.toIntOrNull() ?: 1) != 0
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val radius = min(r - l, b - t) / 2f - dp(5f)
        if (radius <= 0f) return
        val ring = Paint(boxStroke) // local copy: only tweaks the width
        canvas.drawCircle(cx, cy, radius, ring)
        // fractional steps: the ready notch sits at -0.5 (half a tick
        // counterclockwise of step 0)
        fun angleF(step: Float): Float = ((-90f + step * 360f / n) * Math.PI.toFloat() / 180f)
        fun pxF(step: Float, rad: Float): Float = cx + rad * cos(angleF(step))
        fun pyF(step: Float, rad: Float): Float = cy + rad * sin(angleF(step))
        // step ticks on the ring, hit dots a step inside, polygon through them
        euclidTick.strokeWidth = dp(1.2f)
        for (i in 0 until n) {
            val on = pat[i] == 1
            canvas.drawCircle(
                pxF(i.toFloat(), radius), pyF(i.toFloat(), radius),
                dp(if (on) 2.2f else 1.2f), euclidTick,
            )
        }
        val dotR = radius * 0.84f
        if (pat.sum() == 1) {
            val i = pat.indexOf(1)
            canvas.drawCircle(pxF(i.toFloat(), dotR), pyF(i.toFloat(), dotR), dp(2.8f), euclidDot)
        } else if (pat.sum() >= 2) {
            euclidPath.rewind()
            var first = true
            for (i in 0 until n) {
                if (pat[i] != 1) continue
                if (first) {
                    euclidPath.moveTo(pxF(i.toFloat(), dotR), pyF(i.toFloat(), dotR))
                    first = false
                } else euclidPath.lineTo(pxF(i.toFloat(), dotR), pyF(i.toFloat(), dotR))
            }
            if (pat.sum() >= 3) euclidPath.close()
            canvas.drawPath(euclidPath, euclidPoly)
            for (i in 0 until n) {
                if (pat[i] == 1) {
                    canvas.drawCircle(pxF(i.toFloat(), dotR), pyF(i.toFloat(), dotR), dp(2.2f), euclidDot)
                }
            }
        }
        // pointer: EDIT mode (and RUN before the first trigger / after a
        // reset — runtimeValue < 0) rests at the ready notch, half a tick
        // counterclockwise of step 0; otherwise it sits on the step whose
        // values are on the outlets. PINK on hit positions (1), CYAN on
        // rests (0); the rule is pure position, so the color flips the
        // instant the pointer moves. Dim when the widget is not interactive.
        val rv = o.runtimeValue
        val ready = mode != Mode.RUN || rv < 0f
        val stepPos = if (ready) -0.5f else rv.coerceIn(0f, (n - 1).toFloat())
        val hitNow = !ready && pat[stepPos.toInt()] == 1
        euclidPtr.color = when {
            !interactive -> COL_BOX_STROKE
            hitNow -> COL_SELECT
            else -> COL_ACTIVE
        }
        euclidPtr.strokeWidth = dp(2f)
        canvas.drawLine(cx, cy, pxF(stepPos, radius), pyF(stepPos, radius), euclidPtr)
        val head = Paint(euclidDot)
        head.color = euclidPtr.color
        canvas.drawCircle(pxF(stepPos, radius), pyF(stepPos, radius), dp(2.6f), head)
        // hits/steps readout, rotation suffixed when it actually rotates
        // (E(3,8) rotated by 1 reads "3/8r1"); effective (mod steps) value
        if (radius > dp(14f)) {
            textPaint.textSize = units(FS_BOX)
            textPaint.color = COL_TEXT
            val rot = (((o.args.getOrNull(3)?.toIntOrNull() ?: 0) % n) + n) % n
            val label = "${pat.sum()}/$n" + if (rot != 0) "r$rot" else ""
            val tw = textPaint.measureText(label)
            canvas.drawText(label, cx - tw / 2f, cy + units(FS_BOX) * 0.35f, textPaint)
        }
    }

    private val c5Path = Path()
    private val c5RectO = RectF()
    private val c5RectI = RectF()

    /**
     * Circle-of-fifths dial: 12 sectors (C at 12 o'clock, clockwise a fifth
     * per step), three rings — outer major, middle relative minor, inner
     * diminished (display-only, never tappable). The current key's seven
     * diatonic cells render inverted (reference cyan); the tonic glows
     * pink. Ring bands abut: one separator circle between rings, not two.
     * The middle hole shows the root of the chord that last went out.
     */
    private fun drawCircle5(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        // signed key: +pos = major, -pos = relative minor (degree 1 = am)
        val key = (o.args.getOrNull(1)?.toIntOrNull() ?: 1).let { raw ->
            if (raw == 0) 1 else (if (raw < 0) -Circle5.keyPos(raw) else Circle5.keyPos(raw))
        }
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val radius = min(r - l, b - t) / 2f - dp(3f)
        if (radius <= 0f) return
        val bands = arrayOf(0.66f to 1f, 0.42f to 0.66f, 0.2f to 0.42f)
        for (rg in 0..2) {
            val lo = bands[rg].first
            val hi = bands[rg].second
            for (p in 1..12) {
                val degree = Circle5.degreeOf(key, rg, p)
                val a0 = -90f + (p - 1) * 30f - 15f
                c5RectO.set(cx - radius * hi, cy - radius * hi, cx + radius * hi, cy + radius * hi)
                c5RectI.set(cx - radius * lo, cy - radius * lo, cx + radius * lo, cy + radius * lo)
                c5Path.rewind()
                c5Path.arcTo(c5RectO, a0, 30f)
                c5Path.arcTo(c5RectI, a0 + 30f, -30f)
                c5Path.close()
                boxFill.color = when {
                    degree == 1 -> COL_SELECT // tonic: bright pink
                    degree != null -> COL_ACTIVE // diatonic: reference cyan
                    else -> COL_BOX_BG
                }
                canvas.drawPath(c5Path, boxFill)
                val mid = (lo + hi) / 2f
                // labels in the standard widget font size: minors shorten to
                // the lowercase letter (d), dims to b° — the full quality
                // names (Dm, B°) live in the center readout only
                val label = when (rg) {
                    0 -> Circle5.MAJOR[p - 1]
                    1 -> Circle5.MINOR[p - 1].removeSuffix("m")
                    else -> Circle5.pcName(Circle5.outerPc(p) + 11).lowercase() + "°"
                }
                val ang = (a0 + 15f) * Math.PI.toFloat() / 180f
                val tx = cx + radius * mid * cos(ang)
                val ty = cy + radius * mid * sin(ang)
                textPaint.textSize = units(FS_BOX)
                textPaint.color = if (degree != null) COL_BG else COL_COMMENT
                val tw = textPaint.measureText(label)
                canvas.drawText(label, tx - tw / 2f, ty + units(FS_BOX) * 0.35f, textPaint)
            }
        }
        // one separator circle between the rings, both rims, and the radial
        // sector dividers (a previous pass dropped those by accident)
        for (rr in floatArrayOf(0.42f, 0.66f, 0.2f, 1f)) {
            canvas.drawCircle(cx, cy, radius * rr, boxStroke)
        }
        for (k in 0..11) {
            val a = (-105f + k * 30f) * Math.PI.toFloat() / 180f
            canvas.drawLine(
                cx + radius * 0.2f * cos(a), cy + radius * 0.2f * sin(a),
                cx + radius * cos(a), cy + radius * sin(a), boxStroke,
            )
        }
        // the middle hole shows the root of the chord that last went out
        // (falls back to the current key's tonic before anything was
        // emitted — am for a minor key, C for C major)
        val rootLabel = o.runtimeText.ifEmpty {
            val kp = Circle5.keyPos(key)
            if (Circle5.isMinor(key)) Circle5.MINOR[kp - 1] else Circle5.MAJOR[kp - 1]
        }
        textPaint.textSize = units(FS_BOX)
        textPaint.color = COL_TEXT
        val tw = textPaint.measureText(rootLabel)
        canvas.drawText(rootLabel, cx - tw / 2f, cy + units(FS_BOX) * 0.35f, textPaint)
        textPaint.color = COL_TEXT
        // boxFill is shared across every widget's draw: leaving the dial's
        // cell colors on it would repaint random other boxes after the next
        // full-canvas invalidate (resizing the dial leaked exactly there)
        boxFill.color = COL_BOX_BG
    }

    /** Dial cell under a canvas point: ring*12 + pos0, or null (outside,
     *  the center hole, or the display-only inner ring). */
    private fun circle5CellAt(o: PatchObject, c: FloatArray): Int? {
        val rect = objRect(o)
        val dx = c[0] - rect.centerX()
        val dy = c[1] - rect.centerY()
        val radius = min(rect.width(), rect.height()) / 2f - dp(3f)
        val dist = hypot(dx, dy)
        if (radius <= 0f || dist > radius || dist < radius * 0.2f) return null
        val ring = when {
            dist > radius * 0.66f -> 0
            dist > radius * 0.42f -> 1
            else -> return null // inner ring: display-only
        }
        var deg = atan2(dy, dx) * 180f / Math.PI.toFloat() + 90f + 15f
        deg %= 360f
        if (deg < 0f) deg += 360f
        return ring * 12 + (deg / 30f).toInt().coerceIn(0, 11)
    }

    /** ELSE keyboard: piano rows in the canvas language — field keys with
     *  edge strokes, black keys solid ink, pressed keys signal cyan. */
    private fun drawKeyboard(
        canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float,
    ) {
        val n = keyboardWhiteKeys(o)
        val ww = (r - l) / n
        val pressed = keyboardNotes[o.id] ?: emptySet()
        for (i in 0 until n) {
            val pitch = keyboardWhitePitch(o, i)
            boxFill.color = if (pitch in pressed) COL_ACTIVE else COL_BOX_BG
            canvas.drawRect(l + i * ww, t, l + (i + 1) * ww, b, boxFill)
            canvas.drawRect(l + i * ww, t, l + (i + 1) * ww, b, boxStroke)
        }
        val bw = ww * 0.6f
        for (i in 0 until n - 1) {
            if (!keyboardIsBlack(i)) continue
            val pitch = keyboardWhitePitch(o, i) + 1
            boxFill.color = if (pitch in pressed) COL_ACTIVE else COL_TEXT
            val bx = l + (i + 1) * ww - bw / 2
            canvas.drawRect(bx, t, bx + bw, t + (b - t) * 0.6f, boxFill)
        }
        boxFill.color = COL_BOX_BG
    }

    // scratch for the spectrograph's app-side FFT: the stub records the raw
    // waveform and the magnitudes are computed here — hann window kills the
    // leakage that smeared a sine's energy across every bin, and 1024 points
    // give 513 bins (47 Hz @ 48 k) instead of the coarse 33
    private val fftRe = FloatArray(PdEngine.SCOPE_FFT_BLOCK)
    private val fftIm = FloatArray(PdEngine.SCOPE_FFT_BLOCK)
    private var fftWin = FloatArray(0)
    private val fftMag = FloatArray(PdEngine.SCOPE_FFT_BLOCK / 2 + 1)

    /** In-place iterative radix-2 FFT over [fftRe]/[fftIm]. */
    private fun fftInPlace() {
        val n = fftRe.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = fftRe[i]; fftRe[i] = fftRe[j]; fftRe[j] = tr
                val ti = fftIm[i]; fftIm[i] = fftIm[j]; fftIm[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang).toFloat()
            val wi = Math.sin(ang).toFloat()
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until half) {
                    val ur = fftRe[i + k]
                    val ui = fftIm[i + k]
                    val vr = fftRe[i + k + half] * cr - fftIm[i + k + half] * ci
                    val vi = fftRe[i + k + half] * ci + fftIm[i + k + half] * cr
                    fftRe[i + k] = ur + vr
                    fftIm[i + k] = ui + vi
                    fftRe[i + k + half] = ur - vr
                    fftIm[i + k + half] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Hann-windowed FFT magnitudes of the scope waveform → [fftMag]. */
    private fun spectrumBins(wave: FloatArray) {
        val n = fftRe.size
        if (fftWin.size != n) {
            fftWin = FloatArray(n) { i ->
                (0.5 * (1 - Math.cos(2 * Math.PI * i / n))).toFloat()
            }
        }
        for (i in 0 until n) fftRe[i] = wave.getOrElse(i) { 0f } * fftWin[i]
        fftIm.fill(0f)
        fftInPlace()
        for (k in fftMag.indices) {
            fftMag[k] = Math.hypot(fftRe[k].toDouble(), fftIm[k].toDouble()).toFloat()
        }
    }

    /** ELSE graph~/spectrograph~: dark screen with a mid grid; the trace is
     *  the polled pd array — waveform for graph~ (peak-normalized), spectrum
     *  for spectrograph~ (app-side hann FFT of the recorded window). Flat
     *  grid while idle. */
    private fun drawScope(
        canvas: Canvas, o: PatchObject,
        l: Float, t: Float, r: Float, b: Float, spectrum: Boolean,
    ) {
        boxFill.color = COL_BG
        canvas.drawRect(l, t, r, b, boxFill)
        canvas.drawRect(l, t, r, b, boxStroke)
        val mid = (t + b) / 2f
        canvas.drawLine(l, mid, r, mid, gridMinor)
        val data = scopeData[o.id]
        if (data == null || data.isEmpty()) {
            boxFill.color = COL_BOX_BG
            return
        }
        if (spectrum) {
            // data holds the recorded 1024-sample waveform; magnitudes are
            // computed app-side (hann window → a sine's energy stays in a
            // few bins instead of leaking into every one)
            spectrumBins(data)
            var peak = 0f
            for (k in 1 until fftMag.size) if (fftMag[k] > peak) peak = fftMag[k]
            if (peak <= 1e-4f) {
                boxFill.color = COL_BOX_BG
                return
            }
            // one filled column per pixel — no gaps, the strip reads as a
            // continuous curve; each column takes the max magnitude of the
            // bins it covers (bin 0 = DC, skipped)
            val sr = PdEngine.audioConfig.sampleRate.toFloat()
            val binHz = sr / PdEngine.SCOPE_FFT_BLOCK
            val bins = fftMag.size
            val nyquist = sr / 2.0
            boxFill.color = COL_CORD_SIG
            val nCols = (r - l).toInt().coerceIn(16, bins)
            val colW = (r - l) / nCols
            val nColsD = nCols.toDouble()
            for (i in 0 until nCols) {
                val f0 = i / nColsD * nyquist
                val f1 = (i + 1) / nColsD * nyquist
                val b0 = (f0 / binHz).toInt().coerceIn(1, bins - 1)
                val b1 = ((f1 / binHz).toInt()).coerceAtLeast(b0 + 1).coerceAtMost(bins)
                var m = 0f
                for (j in b0 until b1) {
                    val a = fftMag[j]
                    if (a > m) m = a
                }
                val v = (m / peak).coerceIn(0f, 1f)
                canvas.drawRect(l + i * colW, b - v * (b - t), l + (i + 1) * colW, b, boxFill)
            }
        } else {
            val peak = data.maxOf { Math.abs(it) }.coerceAtLeast(0.05f)
            path.reset()
            val n = data.size
            for (i in 0 until n) {
                val x = l + (r - l) * i / (n - 1).coerceAtLeast(1)
                val y = mid - (data[i] / peak) * (b - t) * 0.45f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, scopeTrace)
        }
        boxFill.color = COL_BOX_BG
    }

    private fun drawToggle(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        if (o.runtimeValue > 0.5f) {
            val x = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COL_ACTIVE; strokeWidth = dp(2.2f); style = Paint.Style.STROKE
            }
            canvas.drawLine(l + dp(3f), t + dp(3f), r - dp(3f), b - dp(3f), x)
            canvas.drawLine(r - dp(3f), t + dp(3f), l + dp(3f), b - dp(3f), x)
        } else {
            val x = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COL_COMMENT; strokeWidth = dp(1.2f); style = Paint.Style.STROKE
            }
            canvas.drawLine(l + dp(3f), t + dp(3f), r - dp(3f), b - dp(3f), x)
            canvas.drawLine(r - dp(3f), t + dp(3f), l + dp(3f), b - dp(3f), x)
        }
    }

    private fun drawSlider(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        val range = PdObjectMeta.sliderRange(o)
        val frac = ((o.runtimeValue - range.min) / (range.max - range.min).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_CORD_SIG }
        val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_INK }
        if (o.className() == "hsl") {
            canvas.drawRect(l + dp(2f), t + dp(3f), l + (r - l - dp(4f)) * frac, b - dp(3f), fill)
            val tx = l + (r - l - dp(4f)) * frac + dp(2f)
            canvas.drawRect(tx - dp(2f), t + dp(1f), tx + dp(2f), b - dp(1f), thumb)
        } else {
            val by = t + (b - t - dp(4f)) * (1 - frac) + dp(2f)
            canvas.drawRect(l + dp(3f), by, r - dp(3f), b - dp(2f), fill)
            canvas.drawRect(l + dp(1f), by - dp(2f), r - dp(1f), by + dp(2f), thumb)
        }
        textPaint.textSize = units(8f)
        canvas.drawText(formatValue(o.runtimeValue), l + dp(3f), b - dp(3f), textPaint)
    }

    /** else knob (0.17.136): a 270° dial — background arc, value arc and a
     *  pointer; the current value prints in the corner like the sliders. */
    private fun drawKnob(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        val range = PdObjectMeta.sliderRange(o)
        val frac = ((o.runtimeValue - range.min) /
            (range.max - range.min).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val rad = min(r - l, b - t) / 2f - dp(5f)
        val arcBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3f); color = COL_CNV_BG
        }
        val arcVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3f); color = COL_CORD_SIG
        }
        canvas.drawArc(cx - rad, cy - rad, cx + rad, cy + rad, 135f, 270f, false, arcBg)
        canvas.drawArc(cx - rad, cy - rad, cx + rad, cy + rad, 135f, 270f * frac, false, arcVal)
        val ang = Math.toRadians((135.0 + 270.0 * frac).toDouble())
        val pr = rad * 0.5f
        val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(2f); color = COL_INK
        }
        canvas.drawLine(cx, cy, cx + (pr * cos(ang)).toFloat(),
            cy + (pr * sin(ang)).toFloat(), pointer)
        textPaint.textSize = units(8f)
        canvas.drawText(formatValue(o.runtimeValue), l + dp(3f), b - dp(2f), textPaint)
    }

    private fun drawRadio(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        val cells = PdObjectMeta.radioCells(o)
        val active = o.runtimeValue.toInt()
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_ACTIVE }
        if (o.className() == "vradio") {
            val ch = (b - t) / cells
            for (i in 0 until cells) {
                canvas.drawRect(l, t + ch * i, r, t + ch * (i + 1), boxStroke)
                if (i == active) canvas.drawRect(l + dp(2f), t + ch * i + dp(2f), r - dp(2f), t + ch * (i + 1) - dp(2f), cellPaint)
            }
        } else {
            val cw = (r - l) / cells
            for (i in 0 until cells) {
                canvas.drawRect(l + cw * i, t, l + cw * (i + 1), b, boxStroke)
                if (i == active) canvas.drawRect(l + cw * i + dp(2f), t + dp(2f), l + cw * (i + 1) - dp(2f), b - dp(2f), cellPaint)
            }
        }
    }

    private fun drawVu(canvas: Canvas, o: PatchObject, l: Float, t: Float, r: Float, b: Float) {
        val frac = (o.runtimeValue / 100f).coerceIn(0f, 1f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_CORD_SIG }
        val top = b - (b - t) * frac
        canvas.drawRect(l + dp(2f), top, r - dp(2f), b - dp(2f), fill)
    }

    private fun formatValue(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v)

    // ---------------------------------------------------------------- helpers

    private fun dist2(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return dx * dx + dy * dy
    }

    companion object {
        /** ClipData label marking a palette drag payload. */
        const val DRAG_ADD = "pocketpd/add"
        private const val TAG = "PocketPd.Canvas"

        // canvas-unit metrics
        private const val BOX_H = 22f
        private const val FS_BOX = 11f
        private const val FS_COMMENT = 11f
        private const val GRID = 20f
        private const val LEAD_MSG = 30f
        private const val LEAD_SIG = 60f
        private const val PORT_TOUCH_DP = 22f

        // PC-98 analog canvas (design/pc98-analog/README.md §2/§5):
        // near-black field, edge-grey boxes, magenta = selection,
        // cyan = signal/live widget states. Port & cord geometry untouched.
        private const val COL_BG = 0xFF04040C.toInt()
        private const val COL_GRID = 0x1E2A2A5C
        private const val COL_GRID_MAJOR = 0x3C2A2A5C
        private const val COL_BOX_BG = 0xFF0D0D28.toInt()
        private const val COL_BOX_STROKE = 0xFFA8A8C0.toInt()
        private const val COL_TEXT = 0xFFFCFCFC.toInt()
        private const val COL_COMMENT = 0xFF6C6C8A.toInt()
        private const val COL_SELECT = 0xFFFC54FC.toInt()
        private const val COL_ACTIVE = 0xFF54FCFC.toInt()
        private const val COL_INK = 0xFFFCFCFC.toInt()
        private const val COL_PORT = 0xFFA8A8C0.toInt()
        private const val COL_CORD_MSG = 0xFF7C7CA0.toInt()
        private const val COL_CORD_SIG = 0xFF54FCFC.toInt()
        private const val COL_CORD_ERROR = 0xFFFC5454.toInt()
        private const val COL_CNV_BG = 0xFF101040.toInt()
        private const val COL_GRAPH_BG = 0xFF0A0A1E.toInt()
        private const val COL_GOP_RANGE = 0xFFFC5454.toInt()
    }
}
