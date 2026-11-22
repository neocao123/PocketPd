package com.pocketpd.app.editor

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.PopupWindow
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.pocketpd.app.R
import com.pocketpd.app.ai.LlmClient
import com.pocketpd.app.editor.PatchCanvasView.Mode
import com.pocketpd.app.patch.AbstractionPorts
import com.pocketpd.app.patch.ObjKind
import com.pocketpd.app.patch.PatchDocument
import com.pocketpd.app.patch.PatchObject
import com.pocketpd.app.patch.PdObjectMeta
import com.pocketpd.app.runtime.PdEngine
import com.pocketpd.app.ui.MouseBack
import com.pocketpd.app.ui.SettingsActivity
import com.pocketpd.app.util.Examples
import com.pocketpd.app.util.Externals
import com.pocketpd.app.util.PdHelp
import com.pocketpd.app.util.PatchStore
import com.pocketpd.app.util.Slibs
import com.pocketpd.app.util.Settings
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Patch editor screen. Owns the document file, drives the canvas, and hosts
 * the mode toggle (edit/run), audio switch and undo/redo.
 */
class EditorActivity : AppCompatActivity(), PatchCanvasView.Callbacks {

    private lateinit var canvas: PatchCanvasView
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
    private lateinit var barTitle: TextView
    private lateinit var osdMode: VhsOsdView
    private lateinit var vuStrip: VuStripView
    private lateinit var addBar: LinearLayout
    private lateinit var addBarScroll: HorizontalScrollView

    // Inline name bar (non-blocking object text entry, docked at the bottom)
    private lateinit var nameBar: LinearLayout
    private lateinit var nameInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var nameHint: TextView
    private lateinit var nameSuggestions: LinearLayout
    private var nameObject: PatchObject? = null
    private var nameIsNew = false
    private var nameUpdating = false

    // Persistent console strip between toolbar and canvas. The text view owns
    // ONE Editable for its whole lifetime: re-setting the text per line would
    // rebuild the static layout of the whole buffer on every pd print (ANR
    // territory on bursts), while Editable mutation reflows incrementally.
    private lateinit var consoleBar: android.widget.FrameLayout
    private lateinit var consoleScroll: android.widget.ScrollView
    private lateinit var consoleText: TextView
    private lateinit var consoleEditable: android.text.Editable

    /** Console expand toggle: large overlay-height strip vs the line-count size. */
    private var consoleExpanded = false
    // Console lines are BATCHED onto the main thread: a fast [print] (or a
    // serial stream) used to post one Runnable per line, each doing an
    // Editable append + span + a layout-listener scroll — hundreds per
    // second saturated the UI thread and every canvas repaint (atom
    // refresh, widget redraw) queued behind it: "the display can't keep
    // up". Now lines queue lock-free and ONE flush per main-loop turn
    // drains them all, trims the view buffer, and scrolls once. Flushes are
    // THROTTLED to [CONSOLE_FLUSH_MIN_MS]: a high-rate stream (widget floods,
    // [print] at audio-adjacent rates) previously re-armed a flush every UI
    // frame and the per-frame trim/scroll work starved the thread that was
    // also drawing the canvas — dragging a pad became unusable. Lines still
    // all land; they just paint in batches.
    private val consolePending = java.util.concurrent.ConcurrentLinkedQueue<PdEngine.ConsoleLine>()
    private val consoleFlushPosted = java.util.concurrent.atomic.AtomicBoolean(false)
    private var consoleLastFlush = 0L
    private val consoleFlush = Runnable {
        // clear the flag FIRST: lines landing during the drain re-arm a flush
        consoleFlushPosted.set(false)
        consoleLastFlush = android.os.SystemClock.uptimeMillis()
        if (consoleBar.visibility != View.VISIBLE) {
            consolePending.clear() // the engine buffer keeps them; rebuildConsole repaints
            return@Runnable
        }
        var any = false
        while (true) {
            val l = consolePending.poll() ?: break
            appendConsoleSpan(l)
            any = true
        }
        if (!any) return@Runnable
        trimConsoleView()
        if (!consoleText.hasSelection()) scrollToConsoleBottom()
    }
    private fun scheduleConsoleFlush() {
        if (consoleFlushPosted.getAndSet(true)) return
        val delay = (consoleLastFlush + CONSOLE_FLUSH_MIN_MS -
            android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
        consoleText.postDelayed(consoleFlush, delay)
    }
    private val consoleLineListener = object : PdEngine.ConsoleListener {
        override fun onConsoleLine(line: PdEngine.ConsoleLine) {
            consolePending.add(line)
            scheduleConsoleFlush()
        }
    }

    /** User abstraction names (externals folders) for the suggestion index. */
    private var abstractionNames: List<String> = emptyList()
    /** Abstractions that ship inside a bundled library (else/diy2/…). */
    private var bundledAbstractions: Set<String> = emptySet()

    /** True while the load path just scanned the port index synchronously. */
    private var indexScannedForLoad = false

    private var patchFile: File? = null
    private var inRunMode = false

    /** Debounced live-session rebuilds while editing (see onContentMutated). */
    private lateinit var sessionSync: android.os.Handler
    private val sessionSyncRunner = Runnable {
        // keepAudioSilent: an edit-mode mutation must not flip the user's
        // dsp switch on (0.17.109) — the rebuilt session stays frozen until
        // they press the dsp key, then compiles whole
        if (canvas.mode == PatchCanvasView.Mode.EDIT) reopenSessionForRun(keepAudioSilent = true)
    }


    /** True while editing a bundled example: saves must go through "Save as…". */
    private var readOnly = false

    // Plain-text patch editor (toolbar switch between canvas and raw pd)
    private lateinit var patchText: EditText
    private lateinit var btnTextMode: android.widget.ImageButton
    private var textMode = false
    private var titleBase: CharSequence = ""
    /** Mirror of canvas.hasUnsavedChanges() for title composition. */
    private var titleDirty = false

    // In-file subpatch navigation: "Open" on a [pd x] box swaps the canvas
    // to its inline content; one entry per ancestor level (objId names the
    // [pd] box inside parentDoc), innermost last. Back pops one level and
    // re-serializes the content into the parent block.
    private class SubLevel(
        val objId: Long,
        val parentDoc: com.pocketpd.app.patch.PatchDocument,
    )
    private val subStack = ArrayList<SubLevel>()

    /** The top-level document: subpatch ancestors with the innermost
     *  content already written back into their blocks. */
    private val rootDoc: com.pocketpd.app.patch.PatchDocument?
        get() = if (subStack.isEmpty()) canvas.document else subStack.first().parentDoc

    /** Serializes each open subpatch level into its parent's [pd] block,
     *  innermost first, so [rootDoc] is complete. Port hints refresh too:
     *  the parent canvas may be re-shown later without a re-parse. */
    private fun writeBackSubpatches() {
        var child = canvas.document ?: return
        for (i in subStack.indices.reversed()) {
            val level = subStack[i]
            level.parentDoc.objectById(level.objId)?.let { obj ->
                obj.writeSubpatch(child)
                com.pocketpd.app.patch.PatchDocument.refreshBlockPorts(obj)
            }
            child = level.parentDoc
        }
    }

    /** Opens an inline [pd] subpatch for editing in place. */
    private fun openInlineSubpatch(obj: com.pocketpd.app.patch.PatchObject) {
        val parentDoc = canvas.document ?: return
        val errors = mutableListOf<String>()
        val inner = com.pocketpd.app.patch.PatchDocument.parse(
            obj.subpatchInnerLines().joinToString("\n"), errors,
        )
        for (e in errors) PdEngine.log("subpatch parse: $e")
        subStack.add(SubLevel(obj.id, parentDoc))
        canvas.clearSelection()
        canvas.clearHistory()
        canvas.document = inner
        renderTitle()
        canvas.zoomToFit()
    }

    /** Pops one subpatch level: write-back (dirty only on a real content
     *  change — visiting a subpatch without editing must not flag unsaved
     *  changes), port refresh (inlet/outlet edits inside change the parent
     *  box's stubs), restore parent doc and title. */
    private fun popSubpatch() {
        if (subStack.isEmpty()) return
        val level = subStack.removeAt(subStack.size - 1)
        // the level must be written back explicitly — it is already off the
        // stack, so writeBackSubpatches (save/share path) can't see it
        val child = canvas.document
        if (child != null) level.parentDoc.objectById(level.objId)?.let { obj ->
            if (obj.writeSubpatch(child)) canvas.markContentDirty()
            com.pocketpd.app.patch.PatchDocument.refreshBlockPorts(obj)
        }
        canvas.clearSelection()
        canvas.clearHistory()
        canvas.document = level.parentDoc
        canvas.clearScopeData()
        renderTitle()
        canvas.zoomToFit()
        canvas.invalidate()
    }

    private fun popAllSubpatches() {
        while (subStack.isNotEmpty()) popSubpatch()
    }

    // AI generation bar (text mode only): one button cycles GEN → STOP →
    // REVERT; aiRevertText holds the pre-generation buffer for one restore.
    // GEN continues the open conversation (aiHistory of clean user prompts
    // + full replies), NEW starts a fresh one, REVERT also pops the exchange
    // it produced; CTX decides whether the current buffer is quoted.
    private lateinit var aiBar: android.view.View
    private lateinit var aiStatusRow: LinearLayout
    private lateinit var aiStatusLabel: TextView
    private lateinit var aiStatusText: TextView
    private lateinit var aiPrompt: EditText
    private lateinit var btnAiSend: com.google.android.material.button.MaterialButton
    private lateinit var btnAiNew: com.google.android.material.button.MaterialButton
    private lateinit var btnAiCtx: com.google.android.material.button.MaterialButton
    private enum class AiState { IDLE, BUSY, CAN_REVERT }
    private var aiState = AiState.IDLE
    private var aiRevertText: String? = null
    private val aiHistory = mutableListOf<LlmClient.Msg>()
    private var aiIncludeCtx = true
    // reasoning tail while GEN streams; coalesced onto the UI at 120ms
    private val aiThinkingBuf = StringBuilder()
    private var aiThinkDirty = false
    // true while the text↔canvas sync re-renders patchText programmatically
    private var aiSyncingText = false

    // ELSE-compat scope widgets (graph~/spectrograph~): the session stub
    // names each display array after the doc box id (w<id>-wave); the poller
    // reads those arrays straight from pd and refreshes the canvas buffers.
    // The capture is APP-CLOCKED: each poll first bangs the scope's private
    // w<id>-tw receiver, then reads ~30 ms later — the tabwrite~ fill spans
    // 16 dsp blocks (~21 ms), and reading mid-fill tears the window (old
    // tail + new head), which splashes broadband junk into the spectrum.
    private val scopePollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scopePoll = object : Runnable {
        override fun run() {
            val doc = canvas.document
            if (doc != null) {
                var any = false
                for (o in doc.objects()) {
                    if (o.className() != "graph~" && o.className() != "spectrograph~") continue
                    PdEngine.sendBang("w${o.id}-tw")
                    any = true
                }
                if (any) scopePollHandler.postDelayed({ readScopeArrays(doc) }, 30)
            }
            scopePollHandler.postDelayed(this, 110)
        }
    }

    private fun readScopeArrays(doc: com.pocketpd.app.patch.PatchDocument) {
        for (o in doc.objects()) {
            if (o.className() != "graph~" && o.className() != "spectrograph~") continue
            val name = "w${o.id}-wave"
            val n = PdEngine.arraySize(name)
            if (n <= 0) continue
            val buf = FloatArray(n)
            if (PdEngine.readArray(name, 0, buf)) {
                canvas.setScopeData(o.id, buf)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startAudio()
        } else {
            PdEngine.log(getString(R.string.err_mic_permission))
            Toast.makeText(this, R.string.err_mic_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        findViewById<android.view.View>(R.id.appbar).background =
            com.pocketpd.app.ui.ScanlineDrawable.titleBar(this)
        findViewById<android.view.View>(R.id.scan_overlay).background =
            com.pocketpd.app.ui.ScanlineDrawable.overlay(this)
        canvas = findViewById(R.id.canvas)
        // keep the live pd session equal to the edited doc: every mutation
        // (add/delete/wire/edit/undo) re-arms a debounced rebuild while
        // editing — a session opened at first drop is otherwise a stale
        // snapshot whose missing objects/cords make patches look dead
        sessionSync = android.os.Handler(android.os.Looper.getMainLooper())
        canvas.onContentMutated = {
            sessionSync.removeCallbacks(sessionSyncRunner)
            sessionSync.postDelayed(sessionSyncRunner, 400)
        }
        toolbar = findViewById(R.id.toolbar)
        barTitle = findViewById(R.id.toolbar_title)
        // marquee only scrolls an overflowing title when the view is selected
        barTitle.isSelected = true
        // cap the title at the free strip before the toolbar keys, computed
        // from laid-out positions (Toolbar's custom-child layout is not
        // trustworthy with a match_parent title view — it dropped the key
        // strip entirely — so wrap_content + maxWidth it is). Recomputed on
        // every toolbar layout pass (rotation resizes the strip).
        toolbar.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (l != ol || r != orr || t != ot || b != ob) capTitleWidth()
        }
        capTitleWidth()
        addBar = findViewById(R.id.add_bar)
        addBarScroll = findViewById(R.id.add_bar_scroll)

        canvas.callbacks = this
        canvas.bindDragReceiver()

        // Tapping the canvas commits the open name bar (like typing away).
        canvas.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && nameObject != null) {
                applyNameBar()
            }
            false
        }

        nameBar = findViewById(R.id.name_bar)
        nameInput = findViewById(R.id.name_input)
        nameHint = findViewById(R.id.name_hint)
        nameSuggestions = findViewById(R.id.name_suggestions)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.name_apply)
            .setOnClickListener { applyNameBar() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.name_cancel)
            .setOnClickListener { cancelNameBar() }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                applyNameBar(); true
            } else false
        }
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!nameUpdating) updateNameSuggestions()
            }
        })

        toolbar.setTitle("")
        toolbar.setNavigationOnClickListener {
            if (!canvas.locked) handleBack()
        }
        // the navigation (back) key is a Toolbar-internal ImageButton: its
        // stock background is the borderless CIRCULAR ripple the other
        // top-bar keys lost in 0.17.92 (user: the back key is still round).
        // Same treatment as those — bounded selectableItemBackground,
        // transparent at rest, colors untouched. Toolbar creates the nav
        // view at first layout, not at inflate, so this runs post-layout.
        toolbar.post {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(
                androidx.appcompat.R.attr.selectableItemBackground, tv, true
            )
            for (i in 0 until toolbar.childCount) {
                val c = toolbar.getChildAt(i)
                if (c is android.widget.ImageButton && c.id == View.NO_ID) {
                    c.setBackgroundResource(tv.resourceId)
                }
            }
        }
        // The three design keys (marquee / dsp / menu) are toolbar children in
        // fixed left-to-right order; no menu inflation involved.
        findViewById<android.widget.ImageButton>(R.id.btn_marquee).setOnClickListener {
            canvas.tool = if (canvas.tool == PatchCanvasView.Tool.SELECT) PatchCanvasView.Tool.PAN
            else PatchCanvasView.Tool.SELECT
        }
        findViewById<android.widget.ImageButton>(R.id.btn_audio).setOnClickListener { toggleAudio() }
        findViewById<View>(R.id.btn_overflow).setOnClickListener { showOverflowMenu(it) }

        consoleBar = findViewById(R.id.console_bar)
        consoleScroll = findViewById(R.id.console_scroll)
        consoleText = findViewById(R.id.console_text)
        consoleEditable = android.text.Editable.Factory.getInstance().newEditable("")
        // selection mode BEFORE binding the editable: some Android versions
        // re-realize the text buffer inside setTextIsSelectable, which would
        // silently detach our reference and leave the console blank
        consoleText.setTextIsSelectable(true)
        // selectable text is for 圈选 only — the console must NEVER become a
        // keyboard-focus target: with nothing focused, a stray letter/digit
        // key let the system's key fallback auto-focus this view, and every
        // following keystroke was then INSERTED into the console text (the
        // focus-steal the user traced while the mouse sat over the canvas).
        // Keyboard focus is off; long-press selection, drag handles and
        // wheel scrolling do not need it.
        consoleText.isFocusable = false
        consoleText.isFocusableInTouchMode = false
        // the WHOLE console strip exits focus: with the text itself made
        // unfocusable the system's stray-key fallback simply walked to the
        // NEXT focusable view — the wrapping ScrollView (verified live by
        // injecting KEYCODE_A: focused="true" landed on console_scroll).
        // Wheel scroll and long-press selection never needed focus; the
        // two console keys are touch-only. The canvas becomes the focus
        // owner instead, so the fallback has a definite, input-free home.
        consoleScroll.isFocusable = false
        consoleScroll.isFocusableInTouchMode = false
        findViewById<android.widget.ImageButton>(R.id.btn_console_clear).isFocusable = false
        findViewById<android.widget.ImageButton>(R.id.btn_console_clear).isFocusableInTouchMode = false
        findViewById<android.widget.ImageButton>(R.id.btn_console_expand).isFocusable = false
        findViewById<android.widget.ImageButton>(R.id.btn_console_expand).isFocusableInTouchMode = false
        consoleText.setText(consoleEditable, android.widget.TextView.BufferType.EDITABLE)
        // keep OUR reference identical to the view's buffer even if the
        // factory wrapped it
        consoleEditable = consoleText.editableText
        findViewById<android.widget.ImageButton>(R.id.btn_console_clear).setOnClickListener {
            PdEngine.clearConsole()
            consoleEditable.clear()
        }
        findViewById<android.widget.ImageButton>(R.id.btn_console_expand).setOnClickListener {
            consoleExpanded = !consoleExpanded
            applyConsolePrefs()
        }
        applyConsolePrefs()

        // the OSD badge IS the mode switch (design 02/03); no extra button
        osdMode = findViewById(R.id.osd_mode)
        osdMode.mode = VhsOsdView.Mode.EDIT
        osdMode.setOnClickListener { toggleMode() }
        // hold the badge: enter/leave LOCK — a run flavor for playing where
        // the canvas cannot be panned or zoomed but widgets stay live; only
        // another long press ends it, back to EDIT
        osdMode.setOnLongClickListener {
            applyOsdMode(
                if (osdMode.mode == VhsOsdView.Mode.LOCK) VhsOsdView.Mode.EDIT
                else VhsOsdView.Mode.LOCK,
            )
            true
        }
        vuStrip = findViewById(R.id.vu_strip)
        PdEngine.vuListener = { l, r -> vuStrip.setLevels(l, r) }

        patchText = findViewById(R.id.patch_text)
        btnTextMode = findViewById(R.id.btn_text_mode)
        btnTextMode.setOnClickListener { toggleTextMode() }
        aiBar = findViewById(R.id.ai_bar)
        aiStatusRow = findViewById(R.id.ai_status_row)
        aiStatusLabel = findViewById(R.id.ai_status_label)
        aiStatusText = findViewById(R.id.ai_status_text)
        aiPrompt = findViewById(R.id.ai_prompt)
        btnAiSend = findViewById(R.id.btn_ai_send)
        btnAiNew = findViewById(R.id.btn_ai_new)
        btnAiCtx = findViewById(R.id.btn_ai_ctx)
        aiChipText = btnAiSend.textColors
        aiChipStroke = btnAiSend.strokeColor
        aiChipBg = btnAiSend.backgroundTintList ?: aiChipStroke
        btnAiSend.setOnClickListener { onAiSend() }
        btnAiNew.setOnClickListener { onAiNew() }
        btnAiCtx.setOnClickListener { applyAiCtx(!aiIncludeCtx) }
        applyAiCtx(true)
        aiPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                onAiSend(); true
            } else false
        }
        // typing a follow-up prompt voids the one-shot AI revert offer the
        // same way buffer edits do: with a conversation open the key must
        // read GEN again, or the natural "type and press" would revert the
        // just-landed reply instead of continuing the chat
        aiPrompt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable) {
                if (aiState == AiState.CAN_REVERT) setAiState(AiState.IDLE)
            }
        })
        // full-buffer recolor per edit: pd files are small, and repainting
        // spans (never the text) leaves the cursor position alone
        scopePollHandler.postDelayed(scopePoll, 400)
        patchText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable) {
                PdTextSpans.apply(e)
                // manual edits void the one-shot AI revert offer (programmatic
                // setText re-arms it right after, so the AI path is unaffected;
                // the text↔canvas sync sets aiSyncingText and keeps it too)
                if (!aiSyncingText && aiState == AiState.CAN_REVERT) setAiState(AiState.IDLE)
            }
        })


        buildAddBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        loadPatch()
        canvas.post { canvas.zoomToFit() }
        stripFocusability(window.decorView)
    }

    /** Stray keys must never auto-focus anything (user: the console first
     *  grabbed focus, then the canvas grayed under the automatic focus
     *  highlight when IT became the fallback's target — the live trace
     *  finally landed on add_bar_scroll): nothing in this window is
     *  keyboard-navigable, so every view except text inputs exits focus.
     *  The only focus moves left are the explicit ones (name bar, text
     *  mode); dialogs are separate windows and keep their own focus.
     *  Printing keys are additionally consumed in editorShortcuts, so the
     *  system's key fallback never even runs. */
    private fun stripFocusability(v: View) {
        if (v is EditText) return
        v.isFocusable = false
        v.isFocusableInTouchMode = false
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) stripFocusability(v.getChildAt(i))
        }
    }

    private fun loadPatch() {
        val path = intent.getStringExtra(EXTRA_PATH)
        val errors = mutableListOf<String>()
        if (path != null) {
            val file = File(path)
            // Port index must know the patch's sibling abstractions before the
            // document is parsed (meta/ports resolve during parse).
            Externals.refreshIndex(this, file.parentFile)
            indexScannedForLoad = true
            refreshAbstractionNames()
            val doc = if (file.exists()) {
                PatchDocument.parse(file.readText(), errors)
            } else {
                PatchDocument.parse("", errors)
            }
            patchFile = file
            canvas.document = doc
            setBarTitle(file.name)
            readOnly = Examples.isExamplePath(file, this) || PdHelp.isHelpPath(file, this)
            // Auto-bind unbound gui widgets, then hand pd a session copy so
            // the opened patch matches what the canvas shows. Any gui widget
            // needs the session: pd iemgui doesn't output received values, so
            // widget cords are re-routed through hidden [r]/[s] relays.
            PdEngine.expandGop(doc)
            // widgets start from the values stored in the file
            for (o in doc.objects()) {
                if (o.meta.isGui) o.runtimeValue = PdObjectMeta.guiValue(o)
                o.runtimeText = ""
                for (gw in o.gop?.widgets ?: emptyList()) {
                    gw.runtimeValue = PdObjectMeta.guiValue(gw)
                    gw.runtimeText = ""
                }
            }
            PdEngine.autoBindGui(doc)
            PdEngine.bindObjects(doc)
            canvas.clearScopeData()
            // Help docs get a LIVE pd session like every other patch (their
            // objects respond, [float]-help right inlets work, bangs flash);
            // the doc itself stays read-only for editing. This replaced the
            // old "pd session skipped" gate: the crashes it guarded against
            // came from pd instantiating RAW app abstractions (spectrograph~-
            // help → spectrograph~.pd → canvas_getargs with a null environ
            // on pd 0.51.3) — buildInteractiveSession already stubs/inlines
            // all of those before pd ever sees a row.
            try {
                // ALWAYS open the session copy — it carries the app's injected
                // VU metering (env~ taps on every dac~) besides the widget
                // relays; the on-disk patch is untouched. The session MUST
                // live in the same directory as the original: pd resolves
                // abstractions and relative sample paths against the opened
                // file's directory.
                // Start the audio/DSP loop BEFORE the first parse: the
                // bundled slib setups are queued until pd's first DSP tick,
                // so a parse that runs before that tick creates placeholder
                // boxes for every coded external (ym2612~ & co) that never
                // join the DSP chain. Starting audio here flushes the setups
                // ahead of the parse (measured 2026-11-06: pd's first tick
                // landed ~3.8 s AFTER openPatch — every first open raced).
                ensureAudioOn()
                val sessionDoc = PdEngine.buildInteractiveSession(doc, errors)
                val session = File(file.parentFile, ".session-${file.name}")
                session.writeText(sessionDoc.toText())
                PdEngine.openPatch(session, file.name)
            } catch (t: Throwable) {
                PdEngine.log("open patch failed: ${t.message}")
                Toast.makeText(this, t.message ?: "error", Toast.LENGTH_LONG).show()
            }
            // pd-side "couldn't create" failures logged during openPatch —
            // repaint so affected boxes pick up the warning style
            canvas.invalidate()
        } else {
            Externals.refreshIndex(this)
            indexScannedForLoad = true
            refreshAbstractionNames()
            canvas.document = PatchDocument.parse("", errors)
            setBarTitle(getString(R.string.action_new_patch))
        }
        val graphs = canvas.document?.objects()?.filter { it.graph != null } ?: emptyList()
        if (graphs.isNotEmpty()) PdEngine.log("graphs: " + graphs.joinToString("; ") { g ->
            val names = g.graph?.arrays?.joinToString(",") { it.name }
            val c = g.graph?.coords
            "$names @(${g.x},${g.y}) ${c?.width}x${c?.height} gop=${c?.gop}"
        })
        for (e in errors) PdEngine.log("parse: $e")
        // auto-DSP on patch entry (user request): the patch is open and
        // audible without touching the toolbar key
        ensureAudioOn()
    }

    // ------------------------------------------------------------- toolbar

    // ------------------------------------------------- text/graph switch

    /** Toolbar text/graph switch: entering serializes the live model into
     *  the buffer; leaving re-parses the buffer into a fresh document, so
     *  the two views agree at the switch moment. */
    private fun toggleTextMode() {
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        if (!textMode) {
            // text mode edits the ROOT document: close subpatch levels first
            // so the buffer shows the whole patch and applying it re-parses
            // into a consistent top-level doc
            if (subStack.isNotEmpty()) popAllSubpatches()
            val doc = canvas.document ?: return
            // re-rendering the same doc must not count as a manual edit: a
            // pending AI revert offer survives the text↔canvas round-trip
            aiSyncingText = true
            patchText.setText(doc.toText())
            aiSyncingText = false
            textMode = true
            patchText.visibility = android.view.View.VISIBLE
            aiBar.visibility = android.view.View.VISIBLE
            refreshAiStatusRow()
            canvas.visibility = android.view.View.GONE
            addBarScroll.visibility = android.view.View.GONE
            vuStrip.visibility = android.view.View.GONE
            osdMode.visibility = android.view.View.GONE
            if (nameBar.visibility == android.view.View.VISIBLE) cancelNameBar()
            btnTextMode.setImageResource(R.drawable.ic_canvas)
            setBarTitle(titleBase)
            patchText.requestFocus()
            patchText.post { imm.showSoftInput(patchText, 0) }
        } else {
            if (!applyTextToPatch()) return
            textMode = false
            // The AI request and its conversation survive the mode switch:
            // an in-flight generation keeps streaming and lands in the
            // (hidden) text buffer — the reply path only touches patchText,
            // never pd. A pending REVERT offer stays armed until used or
            // edited, so returning to text mode shows the same state.
            patchText.visibility = android.view.View.GONE
            aiBar.visibility = android.view.View.GONE
            canvas.visibility = android.view.View.VISIBLE
            addBarScroll.visibility = android.view.View.VISIBLE
            vuStrip.visibility = android.view.View.VISIBLE
            osdMode.visibility = android.view.View.VISIBLE
            btnTextMode.setImageResource(R.drawable.ic_text)
            setBarTitle(titleBase)
            imm.hideSoftInputFromWindow(patchText.windowToken, 0)
            imm.hideSoftInputFromWindow(aiPrompt.windowToken, 0)
        }
    }

    // ------------------------------------------------- AI generation (text mode)

    // Busy-key cycle: bright palette colors light up in turn with a dead
    // phase between each (pink → out → cyan → out → yellow → out → …), and
    // when the generation lands the key stops on the RUN green. All four
    // are design-palette inks ($B/$D/$E/$C).
    private val aiBusyColors = intArrayOf(
        0xFFFC54FC.toInt(), // $B magenta (pink)
        0xFF54FCFC.toInt(), // $D cyan
        0xFFFCFC54.toInt(), // $E yellow
    )
    private val aiDoneColor = 0xFF54FC54.toInt() // $C green
    // dead-blink keycap: same flat gray + dark ink as the file list's bottom
    // keys (rename/duplicate/delete) before a row is selected
    private val aiDeadColor = 0xFF545454.toInt()
    private val aiDeadInk = 0xFF04040C.toInt()
    private var aiBlinkStep = 0
    // normal GEN chip look; captured at bind time — a lazy capture here
    // would run on the first restore and record the green "done" tint
    private lateinit var aiChipText: android.content.res.ColorStateList
    private lateinit var aiChipStroke: android.content.res.ColorStateList
    private lateinit var aiChipBg: android.content.res.ColorStateList

    /** PC-98 hard-blink cycle while a request is in flight: every second
     *  the key either lights SOLID in the next palette color with the text
     *  inverted (same 反色 look as the ON toggle chips), or goes dead; the
     *  first second shows the untouched GEN chip ("normal"). */
    private val aiBlink = object : Runnable {
        override fun run() {
            if (aiState != AiState.BUSY) return
            val step = aiBlinkStep++
            if (step % 2 == 0) {
                val c = aiBusyColors[(step / 2) % aiBusyColors.size]
                btnAiSend.alpha = 1f
                btnAiSend.setTextColor(0xFF04040C.toInt())
                btnAiSend.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(c)
                btnAiSend.strokeColor =
                    android.content.res.ColorStateList.valueOf(c)
            } else { // dead phase: the keycap goes out flat gray, like the
                // file list's bottom keys before a row is selected
                btnAiSend.alpha = 1f
                btnAiSend.setTextColor(aiDeadInk)
                btnAiSend.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(aiDeadColor)
                btnAiSend.strokeColor =
                    android.content.res.ColorStateList.valueOf(aiDeadColor)
            }
            btnAiSend.postDelayed(this, 1000)
        }
    }

    private fun setAiState(state: AiState) {
        val wasBusy = aiState == AiState.BUSY
        aiState = state
        btnAiSend.setText(
            when (state) {
                AiState.IDLE -> R.string.ai_send
                AiState.BUSY -> R.string.ai_stop
                AiState.CAN_REVERT -> R.string.ai_revert
            }
        )
        btnAiSend.removeCallbacks(aiBlink)
        aiBlinkStep = 0
        // the prompt field stops taking input for the duration so the reply
        // can't be racing a mid-flight edit
        aiPrompt.isEnabled = state != AiState.BUSY
        btnAiCtx.isEnabled = state != AiState.BUSY
        if (wasBusy && state != AiState.BUSY) {
            // stream over: drop the reasoning tail and any pending flush
            btnAiSend.removeCallbacks(aiThinkFlush)
            aiThinkDirty = false
            aiThinkingBuf.setLength(0)
        }
        when (state) {
            AiState.BUSY -> {
                btnAiSend.alpha = 1f
                btnAiSend.postDelayed(aiBlink, 1000)
            }
            AiState.CAN_REVERT -> { // generation landed: solid green, stopped
                btnAiSend.alpha = 1f
                btnAiSend.setTextColor(0xFF04040C.toInt())
                btnAiSend.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(aiDoneColor)
                btnAiSend.strokeColor =
                    android.content.res.ColorStateList.valueOf(aiDoneColor)
            }
            AiState.IDLE -> { // back to the untouched GEN chip
                btnAiSend.alpha = 1f
                btnAiSend.setTextColor(aiChipText)
                btnAiSend.backgroundTintList = aiChipBg
                btnAiSend.strokeColor = aiChipStroke
            }
        }
        refreshAiStatusRow()
    }

    /** Status strip above the prompt — always visible while the AI bar is
     *  up, so the conversation state never disappears: live reasoning tail
     *  under THINK while GEN streams, turn count under CHAT otherwise
     *  ("0 TURNS" before the first exchange). CTX and NEW stay put. */
    private fun refreshAiStatusRow() {
        val busy = aiState == AiState.BUSY
        aiStatusRow.visibility = View.VISIBLE
        aiStatusLabel.setText(if (busy) R.string.ai_think else R.string.ai_chat)
        if (!busy) aiStatusText.text = getString(R.string.ai_turns, aiHistory.size / 2)
        btnAiCtx.visibility = View.VISIBLE
        btnAiNew.visibility = View.VISIBLE
    }

    /** Last stretch of the reasoning, for the single clipped status line —
     *  the newest words sit at the end of the buffer, so the tail is the
     *  part worth keeping visible. NULs are stripped HERE, not per chunk:
     *  a multi-char junk sequence split across chunk boundaries would
     *  survive a per-chunk pass. */
    private fun thinkingTail(): String {
        val n = aiThinkingBuf.length
        var start = if (n <= 400) 0 else n - 400
        if (start < n && Character.isLowSurrogate(aiThinkingBuf[start])) start++
        return stripNul(aiThinkingBuf.substring(start))
    }

    /** Some endpoints pad streams with real NUL characters, others let the
     *  escaped "\u0000" six-char sequence through as text; the pd98 bitmap
     *  font renders both as boxes, so both go. Display paths only — pd's
     *  message/data flow never passes through here. */
    private fun stripNul(s: String): String =
        s.replace("\u0000", "").replace("\\u0000", "")

    private val aiThinkFlush = Runnable {
        aiThinkDirty = false
        if (aiState == AiState.BUSY) {
            val tail = thinkingTail()
            PdEngine.log("ai think: …${tail.takeLast(120)}")
            aiStatusText.text = tail
        }
    }

    /** NEW key: drop the conversation so the next GEN starts fresh — the
     *  buffer itself is untouched. */
    private fun onAiNew() {
        if (aiState == AiState.BUSY) return
        aiHistory.clear()
        refreshAiStatusRow()
    }

    /** CTX key look: lit solid green (the RUN green, dark ink text — same
     *  inversion as the ON toggle chips) when the buffer is quoted, dead
     *  gray when not (same flat gray as the file list's unselected keys). */
    private fun applyAiCtx(on: Boolean) {
        aiIncludeCtx = on
        if (on) {
            btnAiCtx.setTextColor(android.content.res.ColorStateList.valueOf(aiDeadInk))
            btnAiCtx.backgroundTintList =
                android.content.res.ColorStateList.valueOf(aiDoneColor)
            btnAiCtx.strokeColor =
                android.content.res.ColorStateList.valueOf(aiDoneColor)
        } else {
            btnAiCtx.setTextColor(android.content.res.ColorStateList.valueOf(aiDeadInk))
            btnAiCtx.backgroundTintList =
                android.content.res.ColorStateList.valueOf(aiDeadColor)
            btnAiCtx.strokeColor =
                android.content.res.ColorStateList.valueOf(aiDeadColor)
        }
    }

    /** GEN/STOP/REVERT key: send the prompt, abort the in-flight call, or
     *  restore the pre-generation buffer — exactly one role per state. */
    private fun onAiSend() {
        when (aiState) {
            AiState.BUSY -> {
                LlmClient.cancel()
                setAiState(AiState.IDLE)
                return
            }
            AiState.CAN_REVERT -> {
                aiRevertText?.let { patchText.setText(it) }
                aiRevertText = null
                // a discarded generation leaves the conversation as well:
                // continuing must not assume the popped reply ever applied
                if (aiHistory.size >= 2) {
                    aiHistory.removeAt(aiHistory.size - 1)
                    aiHistory.removeAt(aiHistory.size - 1)
                }
                setAiState(AiState.IDLE)
                return
            }
            AiState.IDLE -> {}
        }
        val s = Settings.get(this)
        if (s.llmEndpoint.isBlank() || s.llmModel.isBlank()) {
            Toast.makeText(this, R.string.ai_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val prompt = aiPrompt.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(this, R.string.ai_empty_prompt, Toast.LENGTH_SHORT).show()
            return
        }
        // this turn: CTX on quotes the live buffer (the truth after manual
        // edits), CTX off sends the prompt alone and lets the history carry
        // the earlier turns. History stores the clean prompt — the buffer
        // wrapper is per-request, re-composed fresh each time.
        val current = patchText.text.toString()
        val user = if (aiIncludeCtx && current.isNotBlank()) {
            "Here is the current patch:\n\n$current\n\n" +
                "Rewrite or extend it as requested, returning the COMPLETE " +
                "modified patch:\n\n$prompt"
        } else {
            "Create a Pure Data patch:\n\n$prompt"
        }
        val messages: List<LlmClient.Msg> =
            aiHistory + LlmClient.Msg("user", user)
        setAiState(AiState.BUSY)
        LlmClient.stream(
            s.llmEndpoint, s.llmKey, s.llmModel, s.llmFormat, messages,
            onThinking = { chunk ->
                runOnUiThread {
                    if (aiState != AiState.BUSY) return@runOnUiThread
                    aiThinkingBuf.append(chunk)
                    // coalesce the stream onto one status-line update
                    if (!aiThinkDirty) {
                        aiThinkDirty = true
                        aiStatusText.postDelayed(aiThinkFlush, 120)
                    }
                }
            },
        ) { text, err ->
            runOnUiThread {
                // a state other than BUSY means the user left text mode or
                // cancelled meanwhile — drop the stale reply
                if (aiState != AiState.BUSY) return@runOnUiThread
                if (err != null) {
                    PdEngine.log("ai: $err")
                    Toast.makeText(
                        this, getString(R.string.ai_failed, err), Toast.LENGTH_LONG
                    ).show()
                    setAiState(AiState.IDLE)
                    return@runOnUiThread
                }
                val reply = stripNul(text ?: return@runOnUiThread)
                aiHistory += LlmClient.Msg("user", prompt)
                aiHistory += LlmClient.Msg("assistant", reply)
                aiRevertText = current
                patchText.setText(reply)
                // surface parse problems immediately, same channel as loading
                val errs = mutableListOf<String>()
                runCatching { PatchDocument.parse(reply, errs) }
                for (e in errs) PdEngine.log("ai parse: $e")
                if (errs.isNotEmpty()) {
                    Toast.makeText(
                        this, getString(R.string.text_parse_errors, errs.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(this, R.string.ai_done, Toast.LENGTH_SHORT).show()
                }
                setAiState(AiState.CAN_REVERT)
            }
        }
    }

    /** Re-parses the text buffer into the live document (text→graph sync,
     *  also the flush before save / overflow-menu actions). Line-level
     *  problems log to the console like on load; false only when the buffer
     *  can't be parsed at all — the previous document stays active then. */
    private fun applyTextToPatch(): Boolean {
        val errors = mutableListOf<String>()
        val doc = runCatching {
            PatchDocument.parse(patchText.text.toString(), errors)
        }.getOrNull()
        if (doc == null) {
            PdEngine.log("text edit: buffer not parseable")
            Toast.makeText(this, R.string.text_parse_failed, Toast.LENGTH_SHORT).show()
            return false
        }
        for (e in errors) PdEngine.log("parse: $e")
        if (errors.isNotEmpty()) {
            Toast.makeText(
                this, getString(R.string.text_parse_errors, errors.size),
                Toast.LENGTH_SHORT,
            ).show()
        }
        canvas.document = doc
        // widgets start from the values stored in the text (same as load)
        for (o in doc.objects()) {
            if (o.meta.isGui) o.runtimeValue = PdObjectMeta.guiValue(o)
            for (gw in o.gop?.widgets ?: emptyList()) {
                gw.runtimeValue = PdObjectMeta.guiValue(gw)
            }
        }
        PdEngine.expandGop(doc)
        // old commands hold the previous document instance
        canvas.clearHistory()
        canvas.markContentDirty()
        canvas.zoomToFit()
        canvas.invalidate()
        return true
    }

    /**
     * Compact two-column dropdown for the less-frequent editor actions. A
     * native overflow list would stack eight full-width rows; the grid keeps
     * the popup half as tall. Undo/redo gray out from the live stack state.
     */
    private fun showOverflowMenu(anchor: View) {
        // the menu acts on the model (undo/redo/properties/save): flush the
        // text buffer first so every entry sees the current text
        if (textMode) applyTextToPatch()
        data class Entry(val label: String, val enabled: Boolean, val active: Boolean = false, val run: () -> Unit)
        val entries = listOf(
            Entry(getString(R.string.action_save), true) { save() },
            Entry(getString(R.string.action_save_as), true) { saveAs() },
            Entry(getString(R.string.action_share), true) { sharePatch() },
            Entry(getString(R.string.action_undo), canvas.canUndo()) { canvas.undo() },
            Entry(getString(R.string.action_redo), canvas.canRedo()) { canvas.redo() },
            // toggle state shows as a filled (orange) button, not a checkmark
            Entry(getString(R.string.action_console), true, active = Settings.get(this).consoleVisible) { toggleConsole() },
            Entry(getString(R.string.action_zoom_fit), true) { canvas.zoomToFit() },
            Entry(getString(R.string.action_properties), true) {
                canvas.document?.let {
                    EditorDialogs.showPatchProperties(this, it, patchFile?.absolutePath) {
                        canvas.markContentDirty()
                        canvas.invalidate()
                    }
                }
            },
            Entry(getString(R.string.action_variables), true) {
                canvas.document?.let {
                    EditorDialogs.showVariables(this, it) {
                        canvas.markContentDirty()
                        canvas.invalidate()
                    }
                }
            },
            Entry(getString(R.string.action_settings), true) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        val d = resources.displayMetrics.density
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 3
            setBackgroundColor(0xFF101040.toInt())
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        for (e in entries) {
            val b = com.google.android.material.button.MaterialButton(this, null, R.attr.pocketPdKeyStyle).apply {
                text = e.label
                isEnabled = e.enabled
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                minHeight = (40 * d).toInt()
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
                textSize = 12f
                if (e.active) {
                    val active = android.content.res.ColorStateList.valueOf(MENU_ACTIVE)
                    backgroundTintList = active
                    strokeColor = active
                    setTextColor(0xFF04040C.toInt())
                }
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = (108 * d).toInt()
                    height = (40 * d).toInt()
                    setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
                }
                setOnClickListener {
                    overflowPopup?.dismiss()
                    e.run()
                }
            }
            grid.addView(b)
        }
        grid.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        PopupWindow(
            grid,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).let { popup ->
            overflowPopup = popup
            // right-align the dropdown under the anchor button
            popup.showAsDropDown(anchor, -grid.measuredWidth + anchor.width, 0)
        }
    }

    private var overflowPopup: PopupWindow? = null

    // ------------------------------------------------------------- console

    /** Applies the Settings line count / visibility to the console strip. */
    private fun applyConsolePrefs() {
        val s = Settings.get(this)
        val n = s.consoleLines.coerceIn(1, 30)
        // Size the SCROLL VIEW: normal = the Settings line count; expanded =
        // a large read-only overlay (~45% of the screen). The text view itself
        // must grow with its content, otherwise the ScrollView has no scroll
        // range and older lines become unreachable.
        consoleScroll.layoutParams = consoleScroll.layoutParams.apply {
            height = if (consoleExpanded) {
                (resources.displayMetrics.heightPixels * 0.45f).toInt()
            } else {
                // compact shows one MORE line than the Settings count (user pref)
                consoleText.lineHeight * (n + 1)
            }
        }
        consoleBar.visibility = if (s.consoleVisible) View.VISIBLE else View.GONE
        if (s.consoleVisible) rebuildConsole()
    }

    /** Repaints the strip from the engine's whole console buffer. */
    private fun rebuildConsole() {
        if (consoleEditable !== consoleText.editableText) {
            consoleEditable = consoleText.editableText
        }
        consoleEditable.clear()
        for (l in PdEngine.consoleSnapshot()) appendConsoleSpan(l)
        scrollToConsoleBottom()
    }

    private fun appendConsole(line: PdEngine.ConsoleLine) {
        if (consoleBar.visibility != View.VISIBLE) return
        appendConsoleSpan(line)
        trimConsoleView()
        // don't yank the view away while the user is selecting text to copy
        if (!consoleText.hasSelection()) scrollToConsoleBottom()
    }

    /** Keeps the on-screen buffer in step with the engine's 500-line cap:
     *  the Editable only ever grew (rebuildConsole alone reset it), so a
     *  long session's TextView layout got slower with every line. */
    private fun trimConsoleView() {
        val e = consoleEditable
        if (e.length < CONSOLE_TRIM_CHARS) return
        // drop whole lines from the top until ~CONSOLE_KEEP_LINES remain
        var lines = 0
        var i = e.length - 1
        while (i >= 0) {
            if (e[i] == '\n' && ++lines > CONSOLE_KEEP_LINES) break
            i--
        }
        if (i > 0) e.delete(0, i + 1)
    }

    private fun appendConsoleSpan(line: PdEngine.ConsoleLine) {
        // safety net: if anything ever re-realizes the view buffer, re-adopt
        // the live one so appends keep landing (history up to then is kept
        // only if the re-realization copied it - Android does copy text)
        if (consoleEditable !== consoleText.editableText) {
            consoleEditable = consoleText.editableText
        }
        val start = consoleEditable.length
        consoleEditable.append(line.text).append('\n')
        consoleEditable.setSpan(
            android.text.style.ForegroundColorSpan(
                if (line.error) CONSOLE_ERROR else CONSOLE_INK
            ),
            start, consoleEditable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * Jumps to the newest line. Must run AFTER the layout that absorbed the
     * new text: View.post before attach executes at attach time, when the
     * text is still unmeasured and the scroll range is zero.
     */
    private fun scrollToConsoleBottom() {
        consoleScroll.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    consoleScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    // pure scroll, never focus: fullScroll(View.FOCUS_DOWN)
                    // is a FOCUS operation — every arriving console line
                    // yanked focus (and the keyboard) out of the object-name
                    // bar mid-typing. scrollTo clamps to the scroll range.
                    consoleScroll.scrollTo(0, consoleScroll.getChildAt(0).height)
                }
            }
        )
    }

    private fun toggleConsole() {
        val s = Settings.get(this)
        s.consoleVisible = !s.consoleVisible
        applyConsolePrefs()
    }

    /** Reflects the marquee tool state on its keycap (active = inverted). */
    private fun updateMenuState() {
        val marqueeActive = canvas.tool == PatchCanvasView.Tool.SELECT
        findViewById<android.widget.ImageButton>(R.id.btn_marquee)?.apply {
            setImageResource(
                if (marqueeActive) R.drawable.ic_marquee_active else R.drawable.ic_marquee
            )
            contentDescription = if (marqueeActive) "MARQUEE: ON" else "MARQUEE: OFF"
        }
    }

    // ------------------------------------------------------------- audio

    private fun toggleAudio() {
        if (PdEngine.isAudioRunning()) {
            PdEngine.stopAudio()
        } else {
            val settings = Settings.get(this)
            if (settings.audioInput &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            startAudio()
        }
        updateAudioIcon()
    }

    private fun startAudio(forceOutputOnly: Boolean = false) {
        val settings = Settings.get(this)
        val base = PdEngine.AudioConfig(
            sampleRate = settings.sampleRate,
            ticksPerBuffer = settings.ticksPerBuffer,
            inputEnabled = settings.audioInput && !forceOutputOnly,
        )
        // Some devices reject small OpenSL buffers; fall back to larger ones.
        var lastError: Throwable? = null
        for (ticks in linkedSetOf(base.ticksPerBuffer, 16, 32, 64)) {
            try {
                PdEngine.configureAudio(base.copy(ticksPerBuffer = ticks))
                PdEngine.startAudio()
                PdEngine.log("dsp on (${settings.sampleRate} Hz, $ticks ticks)")
                if (ticks != base.ticksPerBuffer) {
                    PdEngine.log("buffer enlarged for device compatibility")
                }
                updateAudioIcon()
                return
            } catch (t: Throwable) {
                lastError = t
                PdEngine.log("audio init failed at $ticks ticks: ${t.message}")
            }
        }
        Toast.makeText(this, getString(R.string.err_audio, lastError?.message), Toast.LENGTH_LONG).show()
        updateAudioIcon()
    }

    /** Auto-DSP (user request): entering a patch or switching to RUN turns
     *  the engine on and the toolbar key reflects it. If mic input is
     *  configured but not yet permitted, start output-only instead of
     *  throwing a permission dialog at the user. */
    private fun ensureAudioOn() {
        if (PdEngine.isAudioRunning()) return
        val settings = Settings.get(this)
        val micOk = !settings.audioInput ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        startAudio(forceOutputOnly = !micOk)
    }

    private fun updateAudioIcon() {
        val on = PdEngine.isAudioRunning()
        findViewById<android.widget.ImageButton>(R.id.btn_audio)?.apply {
            setImageResource(if (on) R.drawable.ic_audio else R.drawable.ic_audio_off)
            contentDescription = getString(if (on) R.string.audio_stop else R.string.audio_start)
        }
    }

    // ------------------------------------------------------------- mode


    /** LOCK round-trip: entering arms the run session first (lock is a run
     *  flavor), leaving lands back in EDIT as designed. */
    private fun applyOsdMode(target: VhsOsdView.Mode) {
        when (target) {
            VhsOsdView.Mode.LOCK -> {
                if (!inRunMode) toggleMode()
                canvas.locked = true
                // LOCK is a run flavor: the test-card toolbar stays closed
                addBarScroll.visibility = View.GONE
                osdMode.mode = VhsOsdView.Mode.LOCK
            }
            VhsOsdView.Mode.EDIT -> {
                canvas.locked = false
                // clear the badge first: toggleMode refuses to run in LOCK
                if (osdMode.mode == VhsOsdView.Mode.LOCK) {
                    osdMode.mode = VhsOsdView.Mode.EDIT
                }
                if (inRunMode) toggleMode()
            }
            VhsOsdView.Mode.RUN -> {
                canvas.locked = false
                if (osdMode.mode == VhsOsdView.Mode.LOCK) {
                    osdMode.mode = VhsOsdView.Mode.RUN
                }
                if (!inRunMode) toggleMode()
                osdMode.mode = VhsOsdView.Mode.RUN
            }
        }
        applyChromeLocked(target == VhsOsdView.Mode.LOCK)
    }

    /** In LOCK the chrome is frozen too: toolbar keys (marquee / text
     *  toggle / audio / menu / back) and the console (clear / expand) take
     *  no touches; only the patch widgets stay live. Icons dim like the
     *  popup menu's disabled rows. */
    private fun applyChromeLocked(locked: Boolean) {
        val alpha = if (locked) 0.4f else 1f
        for (id in intArrayOf(
            R.id.btn_marquee, R.id.btn_text_mode, R.id.btn_audio,
            R.id.btn_overflow, R.id.btn_console_clear, R.id.btn_console_expand,
        )) {
            findViewById<android.view.View>(id).let {
                it.isEnabled = !locked
                it.alpha = alpha
            }
        }
        toolbar.isEnabled = !locked
        consoleText.isEnabled = !locked
    }

    private fun toggleMode() {
        if (osdMode.mode == VhsOsdView.Mode.LOCK) return // tap never leaves LOCK
        inRunMode = !inRunMode
        canvas.mode = if (inRunMode) Mode.RUN else Mode.EDIT
        osdMode.mode = if (inRunMode) VhsOsdView.Mode.RUN else VhsOsdView.Mode.EDIT
        addBarScroll.visibility = if (inRunMode) View.GONE else View.VISIBLE
        // euclid clocks step (and feed their outlets) only while running
        PdEngine.widgetsLive = inRunMode
        if (inRunMode) {
            if (nameObject != null) cancelNameBar()
            // run plays the root patch: close any open subpatch levels first
            if (subStack.isNotEmpty()) popAllSubpatches()
            canvas.clearSelection()
            canvas.tool = PatchCanvasView.Tool.PAN
            reopenSessionForRun()
            // every run starts from the stored widget values (tgl off,
            // sliders at their saved position, euclid clocks at the ready
            // notch) — same state a fresh open shows; pd boots its own
            // objects from the same file args, so the two sides agree
            canvas.document?.objects()?.forEach { o ->
                o.runtimeValue = PdObjectMeta.guiValue(o)
                o.runtimeText = ""
                for (gw in o.gop?.widgets ?: emptyList()) {
                    gw.runtimeValue = PdObjectMeta.guiValue(gw)
                    gw.runtimeText = ""
                }
            }
            PdEngine.resetEuclidStates()
            // run = the patch plays: bring the engine up with it
            ensureAudioOn()
        } else {
            addBarScroll.visibility = View.VISIBLE
        }
    }

    /**
     * Entering run mode replays the patch as edited: pd only knows the file
     * that was open at load time, so objects and cords added on the canvas
     * afterwards exist in the session rebuild below — nowhere else.
     */
    /** Rebuilds and reopens the interactive pd session.
     *  @param keepAudioSilent true for EDIT-mode sync rebuilds: the user's
     *  audio state is preserved (dsp off stays off — forcing it on made
     *  every obj creation audibly pop the switch; objects compile at the
     *  next manual dsp-on edge). Run entry keeps auto-DSP. */
    private fun reopenSessionForRun(keepAudioSilent: Boolean = false) {
        val doc = canvas.document ?: return
        if (doc.objects().isEmpty()) return
        // same native-crash gate as loadPatch: help docs never reach pd
        if (patchFile != null && PdHelp.isHelpPath(patchFile!!, this)) return
        canvas.clearScopeData()
        try {
            val dir = patchFile?.parentFile ?: cacheDir
            val sessionName = patchFile?.name?.let { ".session-$it" } ?: ".session-unsaved.pd"
            val session = File(dir, sessionName)
            val errors = mutableListOf<String>()
            // widgets drawn after load never saw autoBindGui (load time only):
            // without a receive symbol their cords skip the session's [s rcv]
            // rerouting and pd value changes never reach the app-side display
            PdEngine.autoBindGui(doc)
            session.writeText(PdEngine.buildInteractiveSession(doc, errors).toText())
            PdEngine.openPatch(
                session, barTitle.text.toString().trimEnd(' ', '•'),
                audio = if (keepAudioSilent) PdEngine.AudioOpen.SYNC else PdEngine.AudioOpen.RUN,
            )
            PdEngine.bindObjects(doc)
            // pd-side create failures surface only after this open
            canvas.invalidate()
        } catch (t: Throwable) {
            PdEngine.log("run open failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------- add bar

    private data class PaletteEntry(val label: String, val kind: ObjKind, val args: List<String>)

    /** Keys are drag sources: hold one, drag onto the canvas, release to drop.
     *  Key grounds walk the design 05 §1 bright palette row ($8-$F) like a
     *  TV test card. */
    private fun buildAddBar() {
        val items = listOf(
            PaletteEntry("obj", ObjKind.OBJECT, emptyList()),
            PaletteEntry("msg", ObjKind.MSG, emptyList()),
            PaletteEntry("text", ObjKind.COMMENT, emptyList()),
            PaletteEntry("num", ObjKind.FLOATATOM, listOf("8", "0", "0", "0", "-", "-", "-")),
            PaletteEntry("tgl", ObjKind.OBJECT, listOf("tgl")),
            PaletteEntry("bng", ObjKind.OBJECT, listOf("bng")),
            PaletteEntry("btn", ObjKind.OBJECT, listOf("button")),
            PaletteEntry("pmnu", ObjKind.OBJECT, listOf("popmenu")),
            PaletteEntry("pad", ObjKind.OBJECT, listOf("pad")),
            PaletteEntry("ecl", ObjKind.OBJECT, listOf("euclid")),
            PaletteEntry("c5", ObjKind.OBJECT, listOf("circle5")),
            PaletteEntry("ym", ObjKind.OBJECT, listOf("ym2612~")),
            PaletteEntry("hsl", ObjKind.OBJECT, listOf("hsl")),
            PaletteEntry("vsl", ObjKind.OBJECT, listOf("vsl")),
            PaletteEntry("radio", ObjKind.OBJECT, listOf("hradio")),
            PaletteEntry("knob", ObjKind.OBJECT, listOf("knob")),
            PaletteEntry("dspl", ObjKind.OBJECT, listOf("display")),
            PaletteEntry("graph", ObjKind.OBJECT, listOf("__graph__")),
        )
        // design 05 §1 bright palette row, rotated to start at $9 blue (user:
        // the bar must not start with the gray end of the ramp)
        val barColors = intArrayOf(
            0xFF5454FC.toInt(), 0xFFFC5454.toInt(), 0xFFFC54FC.toInt(), 0xFF54FC54.toInt(),
            0xFF54FCFC.toInt(), 0xFFFCFC54.toInt(), 0xFFFCFCFC.toInt(), 0xFF545454.toInt(),
        )
        val d = resources.displayMetrics.density
        val font = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pd98_latin)
        }.getOrNull()
        val firstRun = getSharedPreferences("hints", MODE_PRIVATE).getBoolean("drag_hint", true)
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        // add-bar gesture rule (user, 2026-11-17, third revision): the old
        // angle judgment back, but at 20° instead of 45°, PLUS the absolute-y
        // judgment. A drag starts when EITHER the gesture leans ≥20° off
        // horizontal (|dy| ≥ 0.364·|dx|, past the touch slop — short
        // diagonals inside the bar are drags, not scrolls) OR |y| has
        // traveled past the bar's own height (deep escapes at any angle).
        // Everything else scrolls. ALL measurements use RAW screen
        // coordinates: the key moves under the finger while the bar scrolls,
        // so view-relative deltas feed the scroll back into its own input —
        // that feedback loop was the "inexplicable resistance" (finger 20px,
        // bar 15px), a taboo in a music app. Raw coords are immune: they do
        // not move with the view.
        items.forEachIndexed { index, entry ->
            // plain TextView: flat test-card bar — MaterialButton overrides
            // programmatic backgrounds with its own shape drawable
            val b = android.widget.TextView(this)
            b.text = entry.label.uppercase()
            b.typeface = font
            b.includeFontPadding = false
            b.gravity = android.view.Gravity.CENTER
            b.setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            b.setTextColor(0xFF04040C.toInt())
            b.textSize = 10f
            // flat test-card fills; the outward spill comes from the
            // full-screen bloom pass, and the color rides on tag for the
            // drag shadow
            val keyColor = barColors[index % barColors.size]
            b.tag = keyColor
            b.background = android.graphics.drawable.ColorDrawable(keyColor)
            var downClaimed = false
            var dragStarted = false
            var startRawX = 0f
            var startRawY = 0f
            var lastRawX = 0f
            var scrollAccum = 0f
            var dragThreshold = 0f
            b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downClaimed = true
                        dragStarted = false
                        startRawX = e.rawX
                        startRawY = e.rawY
                        lastRawX = e.rawX
                        scrollAccum = 0f
                        // the whole gesture belongs to this key; the scroll
                        // view must never intercept
                        (v.parent as? android.view.ViewGroup)
                            ?.requestDisallowInterceptTouchEvent(true)
                        // threshold = the bar's own height ("高于底栏")
                        dragThreshold = addBarScroll.height.toFloat()
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (downClaimed && !dragStarted) {
                            val dx = e.rawX - startRawX
                            val dy = e.rawY - startRawY
                            if (abs(dy) > dragThreshold ||
                                (dx * dx + dy * dy > slop * slop &&
                                    abs(dy) >= abs(dx) * 0.364f) // tan(20°)
                            ) {
                                dragStarted = true
                                if (firstRun) {
                                    getSharedPreferences("hints", MODE_PRIVATE)
                                        .edit().putBoolean("drag_hint", false).apply()
                                    Toast.makeText(this, R.string.add_bar_drag_hint, Toast.LENGTH_LONG).show()
                                }
                                startPaletteDrag(v, entry)
                            } else {
                                // horizontal swipe = scroll the row with the
                                // finger, RAW deltas only (see rule comment)
                                scrollAccum -= e.rawX - lastRawX
                                val apply = scrollAccum.toInt()
                                if (apply != 0) {
                                    addBarScroll.scrollBy(apply, 0)
                                    scrollAccum -= apply
                                }
                            }
                        }
                        lastRawX = e.rawX
                        downClaimed
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL
                    -> {
                        downClaimed = false
                        dragStarted
                    }
                    else -> downClaimed
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (30 * d).toInt()
            )
            lp.marginEnd = 8
            addBar.addView(b, lp)
        }
    }

    private fun startPaletteDrag(view: View, entry: PaletteEntry) {
        val payload = (listOf(entry.kind.name) + entry.args).joinToString("|")
        val data = ClipData.newPlainText(PatchCanvasView.DRAG_ADD, payload)
        val shadow = PaletteDragShadow(view, entry.label)
        view.startDragAndDrop(data, shadow, null, 0)
        PdEngine.log("drag start: ${entry.label}")
    }

    /** Drag ghost for a palette key: EXACTLY the pressed key — same palette
     *  ground, same dark label, same size (design 05 §1 test-card bars). */
    private inner class PaletteDragShadow(view: View, private val label: String) :
        View.DragShadowBuilder(view) {

        private val d = resources.displayMetrics.density
        private val sp = resources.displayMetrics.scaledDensity
        private val keyColor =
            (view.tag as? Int) ?: Color.parseColor("#101040")
        private val fill = Paint().apply { color = keyColor }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF04040C.toInt()
            textSize = 10f * sp
            typeface = runCatching {
                androidx.core.content.res.ResourcesCompat.getFont(
                    this@EditorActivity, R.font.pd98_latin
                )
            }.getOrNull()
            textAlign = Paint.Align.CENTER
        }

        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set(view.width, view.height)
            touch.set(view.width / 2, view.height / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fill)
            canvas.drawText(label, canvas.width / 2f, canvas.height / 2f - (text.ascent() + text.descent()) / 2, text)
        }
    }

    // ------------------------------------------------------------- name bar

    /** Opens the docked name bar for a dropped (new) or double-tapped object. */
    private fun showNameBar(obj: PatchObject, isNew: Boolean) {
        nameObject = obj
        nameIsNew = isNew
        addBarScroll.visibility = View.GONE
        nameBar.visibility = View.VISIBLE
        nameUpdating = true
        nameInput.setText(obj.args.joinToString(" "))
        nameUpdating = false
        nameInput.setSelection(nameInput.text?.length ?: 0)
        updateNameSuggestions()
        nameInput.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeNameBar() {
        nameObject = null
        nameBar.visibility = View.GONE
        nameInput.setText("")
        // the hidden EditText would KEEP window focus (GONE does not clear
        // it) — editorShortcuts' focused-EditText bypass then blocked every
        // keyboard shortcut for the rest of the session (the Ctrl+3/4
        // "nothing happens" report)
        nameInput.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(nameInput.windowToken, 0)
        if (!inRunMode) addBarScroll.visibility = View.VISIBLE
    }

    /** Commit: empty text on a fresh object removes the placeholder. */
    private fun applyNameBar() {
        val obj = nameObject ?: return
        // Escape unescaped separators, or a typed ";" truncates the whole
        // statement at the next save/reopen cycle. CJK keyboards emit
        // full-width ASCII variants (ｂｐ～); pd class names are ASCII, so the
        // leading token is normalized — comment prose keeps its punctuation.
        val raw = nameInput.text?.toString() ?: ""
        val tokens = PatchObject.storeEscape(
            if (obj.kind == ObjKind.COMMENT) raw else normalizeFullwidthHead(raw)
        )
        if (nameIsNew && tokens.isEmpty()) {
            canvas.deleteSelection()
        } else if (tokens.isNotEmpty()) {
            val beforeName = obj.args.getOrNull(1)
            val beforeSize = obj.args.getOrNull(2)?.toIntOrNull()
            canvas.editObject(obj, tokens)
            // live refresh: a table capacity edit (Edit text — the path this
            // bar drives) reaches the running garray directly; pd binds every
            // array name as a receiver. A renamed table can't move live
            // (pd has no rename) — it refreshes on the next run entry.
            if (obj.className() == "table" && obj.args.getOrNull(1) == beforeName) {
                val newSize = obj.args.getOrNull(2)?.toIntOrNull()
                if (newSize != null && beforeSize != null && newSize != beforeSize) {
                    PdEngine.resizeArray(obj.args[1], newSize)
                }
            }
            PdEngine.log("name bar apply: ${tokens.joinToString(" ")}")
        }
        closeNameBar()
    }

    /** Full-width ASCII variants (U+FF01..U+FF5E, incl. ｂｐ～) → ASCII in the
     *  leading class/selector token only. */
    private fun normalizeFullwidthHead(raw: String): String {
        val headEnd = raw.indexOfFirst { it.isWhitespace() }
        if (headEnd == 0) return raw
        val head = if (headEnd < 0) raw else raw.substring(0, headEnd)
        var changed = false
        val sb = StringBuilder(head.length)
        for (ch in head) {
            val ascii = ch.code - 0xFEE0
            if (ch.code in 0xFF01..0xFF5E && ascii in 0x21..0x7E) {
                sb.append(ascii.toChar()); changed = true
            } else sb.append(ch)
        }
        if (!changed) return raw
        return if (headEnd < 0) sb.toString() else sb.toString() + raw.substring(headEnd)
    }

    /** Dismiss without committing; a fresh placeholder is removed. */
    private fun cancelNameBar() {
        if (nameIsNew) canvas.deleteSelection()
        closeNameBar()
    }

    private fun compareOf(query: String): Comparator<ObjectCatalog.Entry> =
        compareBy(
            { if (query.isEmpty() || it.name.startsWith(query, ignoreCase = true)) 0 else 1 },
            { it.name.length },
            { it.name },
        )

    private fun updateNameSuggestions() {
        nameSuggestions.removeAllViews()
        // pd-name completion is for OBJECT boxes only (user): a msg's
        // content is a message, not a class name, and comments are free
        // text — the suggestion strip stays empty for them
        if (nameObject?.kind != ObjKind.OBJECT) {
            nameHint.text = ""
            return
        }
        val query = nameInput.text?.toString()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
        val matches = ObjectCatalog.search(query).toMutableList()
        // User abstractions join the index after the built-in classes.
        val seen = matches.mapTo(HashSet()) { it.name }
        val pool = matches.toMutableList()
        for (name in abstractionNames) {
            if (name in seen) continue
            if (query.isNotEmpty() && !name.contains(query, ignoreCase = true)) continue
            seen.add(name)
            val bundled = name in bundledAbstractions
            pool.add(
                ObjectCatalog.Entry(
                    name, if (bundled) "abstraction" else "user abstraction",
                    if (bundled) "bundled library .pd" else "user .pd in externals",
                )
            )
        }
        // bundled pd-lib classes (ELSE, cyclone, zexy, iemlib, …) close the
        // index so compiled objects like else/hann~ are completable too
        for ((cls, lib) in Slibs.classNamesByLib(this)) {
            if (cls in seen) continue
            if (query.isNotEmpty() && !cls.contains(query, ignoreCase = true)) continue
            seen.add(cls)
            pool.add(ObjectCatalog.Entry(cls, lib, "bundled $lib object"))
        }
        // the curated catalog only covers a slice of vanilla pd (bp~,
        // biquad~, hip~, fft~ … are absent) — the full class table pd was
        // built with closes the index so every creatable class is completable
        for (cls in PdObjectMeta.knownClasses) {
            if (cls in seen) continue
            if (query.isNotEmpty() && !cls.contains(query, ignoreCase = true)) continue
            seen.add(cls)
            pool.add(ObjectCatalog.Entry(cls, "vanilla", "pd built-in"))
        }
        // prefix matches beat substring matches, shorter names first — typing
        // "graph" must surface graph~ before filter-graph1 fills the bar
        val ranked = pool.sortedWith(
            compareOf(query)
        ).take(14)
        val top = ranked.firstOrNull()
        nameHint.text = if (top != null) "${top.category.uppercase()} — ${top.hint}" else ""
        if (ranked.isEmpty()) return
        val d = resources.displayMetrics.density
        for (entry in ranked) {
            val chip = com.google.android.material.button.MaterialButton(
                this, null, R.attr.pocketPdKeyStyle
            )
            chip.text = entry.name
            chip.isAllCaps = false
            chip.minWidth = 0
            chip.minimumWidth = 0
            chip.setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
            chip.insetTop = 0
            chip.insetBottom = 0
            chip.minHeight = (32 * d).toInt()
            chip.setOnClickListener { replaceFirstToken(entry.name) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 6
            nameSuggestions.addView(chip, lp)
        }
    }

    /** Chip tap: complete the object name, keep any arguments already typed. */
    private fun replaceFirstToken(name: String) {
        val rest = nameInput.text?.toString()?.trim()?.split(Regex("\\s+"))?.drop(1)
        nameUpdating = true
        nameInput.setText(listOf(name, *rest.orEmpty().toTypedArray()).joinToString(" "))
        nameUpdating = false
        nameInput.setSelection(nameInput.text?.length ?: 0)
        updateNameSuggestions()
    }

    /** Names of the user's abstraction files, for the name-bar suggestions. */
    private fun refreshAbstractionNames() {
        // Only abstractions with a real interface (≥1 depth-0 inlet/outlet)
        // belong in the name-bar index — portless demo patches (A00.intro,
        // B07.sampler, the library readme/menu files) instantiate as
        // unwireable boxes with phantom default ports. AbstractionPorts
        // mirrors pd's search order and indexes exactly the ported files.
        val names = Externals.list(this)
            .map { it.name.removeSuffix(".pd") }
            .filter { AbstractionPorts.lookup(it) != null }
            .toMutableList()
        // official example abstractions (output~, adsr, osc-voice, …) follow
        for (f in Examples.patches(this, "audio")) {
            val n = f.name.removeSuffix(".pd")
            if (n in names) continue
            if (AbstractionPorts.lookup(n) == null) continue
            names.add(n)
        }
        // bundled library abstractions under both spellings: bare [st-buffery]
        // and namespaced [diy2/st-buffery]
        val bundled = mutableSetOf<String>()
        for (lib in Slibs.PRIORITY) {
            val dir = Slibs.libDir(this, lib)
            for (f in dir.listFiles { g -> g.isFile && g.name.endsWith(".pd") } ?: continue) {
                val n = f.name.removeSuffix(".pd")
                if (AbstractionPorts.lookup(n) == null) continue
                names.add("$lib/$n")
                bundled.add("$lib/$n")
                if (n !in names) names.add(n)
                bundled.add(n)
            }
        }
        bundledAbstractions = bundled
        abstractionNames = names
    }

    // ------------------------------------------------------------- save / back

    private fun save() {
        if (textMode && !applyTextToPatch()) return
        if (readOnly) {
            // Bundled examples are never overwritten; say so and save a copy.
            saveAs(getString(R.string.save_as_readonly))
            return
        }
        val doc = run {
            // saving always writes the whole file: write open subpatch levels
            // back into their blocks and serialize from the root document
            if (subStack.isNotEmpty()) writeBackSubpatches()
            rootDoc
        } ?: return
        val file = patchFile ?: askForNameAndSave() ?: return
        runCatching {
            file.writeText(doc.toText())
        }.onSuccess {
            patchFile = file
            setBarTitle(file.name)
            canvas.clearDirty()
            // the saved file may be an abstraction whose parse is cached
            Externals.invalidateAbstractions()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }.onFailure {
            PdEngine.log("save failed: ${it.message}")
        }
    }

    /** Saves the document as a NEW file in the user's patch collection. */
    private fun saveAs(notice: String? = null) {
        if (textMode && !applyTextToPatch()) return
        val doc = run {
            if (subStack.isNotEmpty()) writeBackSubpatches()
            rootDoc
        } ?: return
        val view = layoutInflater.inflate(R.layout.dialog_text_edit, null)
        val til = view.findViewById<TextInputLayout>(R.id.til)
        til.hint = getString(R.string.save_as_name)
        if (notice != null) {
            // ScrollView hosts a single child: wrap input + notice in a column
            val scroll = view as android.widget.ScrollView
            val d = resources.displayMetrics.density
            val column = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            column.addView(TextView(this).apply {
                text = notice
                setPadding(0, 0, 0, (12 * d).toInt())
            })
            (til.parent as? android.view.ViewGroup)?.removeView(til)
            scroll.addView(column)
            column.addView(til)
        }
        val edit = view.findViewById<EditText>(R.id.edit)
        edit.setText(patchFile?.name ?: "patch.pd")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_save_as)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val target = PatchStore.create(this, name)
                runCatching {
                    target.writeText(doc.toText())
                }.onSuccess {
                    patchFile = target
                    readOnly = false
                    setBarTitle(target.name)
                    canvas.clearDirty()
                    Externals.invalidateAbstractions()
                    Toast.makeText(this, R.string.saved_as, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    PdEngine.log("save as failed: ${it.message}")
                    Toast.makeText(this, it.message ?: "error", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askForNameAndSave(): File? {
        var result: File? = null
        val view = layoutInflater.inflate(R.layout.dialog_text_edit, null)
        view.findViewById<TextInputLayout>(R.id.til).hint = "patch.pd"
        val edit = view.findViewById<EditText>(R.id.edit)
        edit.setText("patch-${System.currentTimeMillis() / 1000}.pd")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_save)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) {
                    result = PatchStore.create(this, name)
                    patchFile = result
                    save()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return result
    }

    /** Some devices map mouse right-click to BACK at the system level; it
     *  arrives here as a KEYCODE_BACK key event (the touch pipeline never
     *  sees the right button on such devices). The injected key keeps the
     *  mouse's identity (GM800, verified live: deviceId = the mouse,
     *  source = SOURCE_MOUSE, scanCode = 0, repeatCount ticks while held),
     *  so MouseBack.isRightClick separates it exactly from a genuine
     *  nav-bar/gesture back. Right-click: the long-press menu opens when
     *  the cursor is over an object in edit mode; otherwise the key is
     *  swallowed — a right-click NEVER navigates, anywhere in the app.
     *  A genuine BACK keeps the normal back handling. Key tracing goes to
     *  logcat ONLY (PocketPd.Editor tag) — PdEngine.log would print every
     *  press into the console strip (user: stop that). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (MouseBack.isRightClick(event)) {
                // only the initial press may open the menu; the repeats a
                // held button produces (and the UP) are always swallowed
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    android.util.Log.i("PocketPd.Editor",
                        "right-click back src=0x${Integer.toHexString(event.source)} " +
                            "dev=${event.deviceId}")
                    canvas.openLongPressMenuAtCursor()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                android.util.Log.i("PocketPd.Editor",
                    "back key src=0x${Integer.toHexString(event.source)} " +
                        "dev=${event.deviceId} repeat=${event.repeatCount}")
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        if (editorShortcuts(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Desktop-pd keyboard shortcuts (user request): Del deletes the
     *  selection, Ctrl+D duplicates, Ctrl+A selects all, Ctrl+E toggles
     *  edit/run, Ctrl+S saves, Ctrl+Z / Ctrl+Shift+Z undo/redo, Ctrl+1..4
     *  create obj/msg/text/num at the mouse (or the canvas center).
     *
     *  Typing safety (user: "千万别打字的时候也触发"): any OTHER focused
     *  EditText (console, AI prompt) bypasses the whole set; dialogs are
     *  separate windows and never reach this dispatcher. While the name
     *  bar is open, Ctrl+1..4 and Ctrl+E behave like desktop pd — the open
     *  editor commits first, then the shortcut acts (Ctrl+1 leaves a fresh
     *  name bar for the new object); every other combo stays with typing.
     *
     *  Stray-key policy (user, after the console and then the canvas both
     *  got auto-focused by the system's key fallback): OUTSIDE typing
     *  contexts every printing key (letters/digits/punctuation) and Tab is
     *  consumed HERE, unhandled or not — the event never reaches the view
     *  hierarchy, so the fallback that re-focused the console (0.17.104-)
     *  and then grayed the canvas with its focus highlight (0.17.106) has
     *  nothing left to run on. Nothing in this window is keyboard-
     *  navigable; focus only ever moves explicitly (name bar, text mode,
     *  dialogs). Volume/media keys are not printing keys and pass through. */
    private fun editorShortcuts(event: KeyEvent): Boolean {
        if (textMode) return false
        val naming = nameBar.visibility == View.VISIBLE
        // the name bar's input is a typing context, but it is THE canvas
        // one (handled above); every other focused EditText is unrelated
        // typing — console, AI prompt — and bypasses everything
        if (!naming && currentFocus is EditText) return false
        val edit = canvas.mode == Mode.EDIT && !canvas.locked
        val ctrl = event.metaState and KeyEvent.META_CTRL_ON != 0
        val stray = event.keyCode == KeyEvent.KEYCODE_TAB || event.isPrintingKey
        // Del / Backspace delete the selection (desktop pd); while the name
        // bar is open they are text editing — bypass
        if (!ctrl) {
            if (naming) return false
            if (edit && event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0 &&
                (event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
                    event.keyCode == KeyEvent.KEYCODE_DEL) &&
                canvas.selectedObjects().isNotEmpty()
            ) {
                canvas.deleteObjects(canvas.selectedObjects().map { it.id })
                return true
            }
            return stray
        }
        // ctrl-combos act once per press, on the first DOWN; repeats and
        // the UP of a handled combo are swallowed
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                // desktop-pd chain: commit the open editor, then create —
                // Ctrl+1 while naming replaces the bar with the new object's
                KeyEvent.KEYCODE_1 -> {
                    if (naming) applyNameBar()
                    spawnAtPointer(ObjKind.OBJECT, emptyList()); return true
                }
                KeyEvent.KEYCODE_2 -> {
                    if (naming) applyNameBar()
                    spawnAtPointer(ObjKind.MSG, emptyList()); return true
                }
                KeyEvent.KEYCODE_3 -> {
                    if (naming) applyNameBar()
                    spawnAtPointer(ObjKind.COMMENT, emptyList()); return true
                }
                KeyEvent.KEYCODE_4 -> {
                    if (naming) applyNameBar()
                    spawnAtPointer(
                        ObjKind.FLOATATOM, listOf("8", "0", "0", "0", "-", "-", "-")
                    ); return true
                }
                KeyEvent.KEYCODE_E -> {
                    if (naming) applyNameBar()
                    toggleMode(); return true
                }
            }
            // the rest are canvas-editing combos — they do not act while
            // the name bar is open (typing context)
            if (naming) return false
            when (event.keyCode) {
                KeyEvent.KEYCODE_D ->
                    if (edit) canvas.duplicateObjects(canvas.selectedObjects().map { it.id })
                KeyEvent.KEYCODE_A -> if (edit) canvas.selectAll()
                KeyEvent.KEYCODE_S -> save()
                KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) canvas.redo() else canvas.undo()
                else -> return stray
            }
            return true
        }
        return stray || event.keyCode in setOf(
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
        )
    }

    /** Ctrl+1..4 creation point: the mouse when one hovers the canvas,
     *  else the middle of the view (user). Edit mode only. */
    private fun spawnAtPointer(kind: ObjKind, args: List<String>) {
        if (canvas.mode != Mode.EDIT || canvas.locked) return
        val pos = canvas.pointerCanvasPos() ?: canvas.centerCanvasPos()
        android.util.Log.i("PocketPd.Editor",
            "shortcut spawn ${kind.name} at (${pos[0].toInt()},${pos[1].toInt()})")
        canvas.spawnPaletteObject(kind, args, pos[0], pos[1])
    }

    private fun handleBack() {
        // back out of the text editor first: apply the buffer so the dirty
        // check below sees text edits, then show the graphical patch again
        if (textMode) {
            toggleTextMode()
            return
        }
        if (nameObject != null) {
            cancelNameBar()
            return
        }
        // Back inside an open subpatch pops one level (content is written
        // back into the parent block first); the discard dialog is a
        // root-level concern only
        if (subStack.isNotEmpty()) {
            popSubpatch()
            return
        }
        if (canvas.hasUnsavedChanges()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.discard_changes_title)
                .setMessage(R.string.discard_changes_msg)
                .setPositiveButton(R.string.save) { _, _ -> save(); finish() }
                .setNegativeButton(R.string.discard) { _, _ -> finish() }
                .setNeutralButton(R.string.keep_editing, null)
                .show()
        } else {
            finish()
        }
    }

    // ------------------------------------------------------------- callbacks

    override fun onSelectionChanged(selected: Set<Long>) {}

    /** Ctrl+click on empty canvas toggles edit/run (desktop-pd convention).
     *  While the name bar is open the same tap already commits the name
     *  (canvas DOWN listener) — the tap IS the commit, no mode flip on top. */
    override fun onCtrlTapEmpty() {
        if (nameObject != null) return
        toggleMode()
    }

    override fun onObjectDropped(obj: PatchObject) {
        val textable = obj.kind == ObjKind.OBJECT || obj.kind == ObjKind.MSG ||
            obj.kind == ObjKind.COMMENT
        // Widgets drop ready to use (tgl/bng/num come pre-named); only blank
        // obj/msg/text placeholders need the name bar.
        if (textable && obj.args.isEmpty()) showNameBar(obj, isNew = true)
        ensureLiveSession()
    }

    /**
     * A brand-new patch has no pd session until the first run entry — every
     * tap in edit mode would land in silence. Open one lazily on the first
     * interaction / first dropped object instead.
     */
    private fun ensureLiveSession() {
        if (!PdEngine.hasSession()) {
            // keepAudioSilent: with the user's dsp off the lazily opened
            // session must not start audio either — pd's clock is frozen
            // without the audio thread, so taps land nowhere either way
            // until they press the dsp key (then the graph compiles whole)
            reopenSessionForRun(keepAudioSilent = true)
        }
    }

    override fun onEditObjectRequested(obj: PatchObject) {
        when (obj.kind) {
            ObjKind.OBJECT, ObjKind.MSG, ObjKind.COMMENT -> showNameBar(obj, isNew = false)
            else -> EditorDialogs.showTextEditor(this, obj, onApply = { tokens ->
                canvas.editObject(obj, tokens)
            })
        }
    }

    override fun onObjectLongPress(obj: PatchObject, screenX: Float, screenY: Float) {
        // edit-only menu (Delete/Duplicate/Open/Help…): a run-mode long-press
        // is routed to nothing — refuse here too so no path can ever surface
        // this OSD while playing
        android.util.Log.i("PocketPd.Editor", "OSD call inRunMode=$inRunMode obj='${obj.displayName()}'")
        if (inRunMode) return
        data class Action(val label: String, val enabled: Boolean, val run: () -> Unit)
        val actions = mutableListOf<Action>()
        // The menu acts on the selection AS IT WAS when the menu opened: a
        // multi-selection must keep Delete/Duplicate/Copy scoped to every
        // selected object for the menu's whole lifetime, so all three take
        // this snapshot instead of re-reading the live selection.
        val snapshotIds = canvas.selectedObjects().map { it.id }.ifEmpty { listOf(obj.id) }
        android.util.Log.i("PocketPd.Editor", "OSD '${obj.displayName()}' snapshot=${snapshotIds.size}")
        // Open and Help are two distinct things, like desktop pd: "Open"
        // loads the abstraction's own source file ([output~] → output~.pd;
        // built-ins like [osc~] have none and gray out), "Help" loads the
        // <name>-help.pd documentation patch (gray when none exists). Either
        // way a disabled item is not clickable, so it can never error.
        // An in-file [pd x] subpatch has no source file: Open swaps the
        // canvas to its inline content instead (Back pops one level).
        val inlineSub = obj.kind == ObjKind.OBJECT && obj.className() == "pd" &&
            obj.graph == null
        if (inlineSub) {
            actions.add(Action(getString(R.string.action_open), true) {
                openInlineSubpatch(obj)
            })
        } else {
            val srcFile = PdHelp.findSource(obj)
            actions.add(
                Action(
                    getString(R.string.action_open) + if (srcFile == null) " (" +
                        getString(R.string.open_none) + ")" else "",
                    srcFile != null,
                ) { openSubPatch(srcFile!!) }
            )
        }
        val helpFile = PdHelp.find(this, obj)
        actions.add(
            Action(
                getString(R.string.action_help) + if (helpFile == null) " (" +
                    getString(R.string.help_none) + ")" else "",
                helpFile != null,
            ) { openSubPatch(helpFile!!) }
        )
        if (obj.kind == ObjKind.FLOATATOM) {
            actions.add(Action(getString(R.string.set_value), true) { onNumberEntryRequested(obj) })
        }
        actions.add(Action(getString(R.string.edit_text), true) { onEditObjectRequested(obj) })
        actions.add(Action(getString(R.string.properties), true) {
            // live refresh: capture the pre-dialog table shape so that apply
            // can push a pd-side resize into the running session — the doc
            // change alone never reaches the already-open patch
            val beforeName = obj.args.getOrNull(1)
            val beforeSize = obj.args.getOrNull(2)?.toIntOrNull()
            EditorDialogs.showProperties(this, obj) {
                canvas.invalidate(); markDirtyProxy()
                val name = obj.args.getOrNull(1)
                val size = obj.args.getOrNull(2)?.toIntOrNull()
                if (obj.className() == "table" && name != null && name == beforeName &&
                    size != null && beforeSize != null && size != beforeSize
                ) {
                    PdEngine.resizeArray(name, size)
                }
            }
        })
        actions.add(Action(getString(R.string.duplicate), true) { canvas.duplicateObjects(snapshotIds) })
        actions.add(Action(getString(R.string.copy), true) { canvas.copyObjects(snapshotIds) })
        actions.add(Action(getString(R.string.delete), true) { canvas.deleteObjects(snapshotIds) })
        actions.add(Action(getString(R.string.select_more), true) { canvas.selectAll() })
        EditorDialogs.showActionMenu(
            this,
            obj.displayName().ifEmpty { obj.kind.label },
            actions.map { it.label to it.enabled },
        ) { which ->
            android.util.Log.i("PocketPd.Editor", "PICK $which '${actions.getOrNull(which)?.label}'")
            actions[which].run()
        }
    }

    /** Opens an abstraction source / help patch in a stacked editor (back returns here). */
    private fun openSubPatch(file: File) {        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EXTRA_PATH, file.absolutePath)
        startActivity(intent)
    }

    /**
     * Share entry (overflow menu): sends the current patch text plus, when
     * the file lives in a provider-covered root, the .pd file itself. The
     * text is always the live document — the file can be one save behind
     * when the patch is dirty, so receivers that want the content take the
     * text and receivers that want the file take the stream.
     */
    private fun sharePatch() {
        if (subStack.isNotEmpty()) writeBackSubpatches()
        val text = rootDoc?.toText() ?: return
        val name = patchFile?.name ?: "patch.pd"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, text)
            val f = patchFile
            if (f != null && f.exists()) {
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@EditorActivity, "$packageName.fileprovider", f
                    )
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } // file outside the provider roots → text-only share
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    private fun markDirtyProxy() {
        // metadata edits (symbols/params) change the file; flag it so BACK
        // offers to save
        canvas.markContentDirty()
        canvas.invalidate()
    }

    override fun onGuiTouched(obj: PatchObject, value: Float, isBang: Boolean) {
        ensureLiveSession()
        PdEngine.guiTouched(obj, value, isBang)
    }

    override fun onRunProperties(obj: PatchObject) {
        // run-mode parameter editing (euclid hits/steps/rotation, widget
        // sizes…): the euclid engine state re-derives lazily on the next
        // trigger, the canvas repaints through the apply callback
        EditorDialogs.showProperties(this, obj) {
            canvas.invalidate()
            markDirtyProxy()
        }
    }

    override fun onKeyboardKey(obj: PatchObject, pitch: Int, down: Boolean) {
        ensureLiveSession()
        PdEngine.keyboardNote(obj, pitch, down)
    }

    override fun onGuiList(obj: PatchObject, outlet: Int, args: List<Any>) {
        ensureLiveSession()
        PdEngine.sendGuiList(obj, outlet, args)
    }

    override fun onGuiSymbol(obj: PatchObject, symbol: String) {
        ensureLiveSession()
        PdEngine.guiSymbol(obj, symbol)
    }

    override fun onMsgTriggered(obj: PatchObject) {
        ensureLiveSession()
        // pmsg ordinals are GLOBAL: document order, depth-first into subpatch
        // blocks — the same walk the session builder uses to assign relays
        // (PdEngine.buildInteractiveSession / levelMsgOffset). A level doc
        // only sees its own local order, so sum the offsets of the levels
        // above it; without this, tapping a msg inside a [pd] block fired
        // the root patch's first msg instead of itself.
        val local = canvas.document?.objects()?.filter { it.kind == ObjKind.MSG }?.indexOf(obj)
        if (local == null || local < 0) return
        var ord = local
        for (level in subStack) {
            ord += PdEngine.levelMsgOffset(level.parentDoc, level.objId)
        }
        PdEngine.triggerMsg(obj, ord)
    }

    override fun onNumberEntryRequested(obj: PatchObject) {
        EditorDialogs.showNumberEntry(this, obj.runtimeValue) { v ->
            canvas.setAtomValue(obj, v)
        }
    }

    override fun onDirtyChanged(dirty: Boolean) {
        titleDirty = dirty
        renderTitle()
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        updateMenuState()
    }

    override fun onToolChanged(tool: PatchCanvasView.Tool) {
        updateMenuState()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        PdEngine.guiListener = object : PdEngine.GuiUpdateListener {
            override fun onGuiChanged(objId: Long) = canvas.refreshGui(objId)
        }
        // pd-reported create failures arrive via the message queue; repaint
        PdEngine.onFailedClassesChanged = { canvas.invalidate() }
        // live console stream + refreshed lines/visibility from Settings
        PdEngine.consoleListener = consoleLineListener
        applyConsolePrefs()
        // Externals may have changed while away (Settings import/delete, adb
        // push). The first resume skips it — loadPatch just scanned
        // synchronously because the initial canvas render needs the index.
        if (indexScannedForLoad) {
            indexScannedForLoad = false
            refreshAbstractionNames()
        } else {
            // suggestions are filtered by the port index, so rebuild them
            // once the async rescan lands — the startup scan covers the bar
            // meanwhile
            Externals.refreshIndexAsync(this, patchFile?.parentFile) {
                canvas.invalidate()
                refreshAbstractionNames()
            }
        }
        startGraphPoller()
        // Stream phone sensors into pd while a patch is on screen.
        com.pocketpd.app.runtime.SensorBridge.start(this)
    }

    override fun onPause() {
        PdEngine.guiListener = null
        PdEngine.onFailedClassesChanged = null
        if (PdEngine.consoleListener === consoleLineListener) PdEngine.consoleListener = null
        graphHandler.removeCallbacks(graphPoller)
        com.pocketpd.app.runtime.SensorBridge.stop()
        super.onPause()
    }

    // ------------------------------------------------------------ graphs

    private val graphHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Polls every pd array behind a graph into its live buffer at ~10 Hz so
     * waveforms/tables drawn by the patch (tabwrite~, tabwrite, FFT scripts…)
     * show up on the canvas. Runs in both modes: pd is open while editing too.
     */
    private val graphPoller = object : Runnable {
        override fun run() {
            var graphs = false
            for (o in canvas.document?.objects() ?: emptyList()) {
                // [table] boxes only get their graph when first drawn —
                // synthesize here too, or the very first pass (before any
                // frame) finds no graphs and external writes to the array
                // never reach the display. Always through graphBlock(): it
                // re-syncs a synthesized [table] graph with its (possibly
                // just renamed) args; caching o.graph here would keep
                // polling the old array name forever.
                val g = canvas.graphBlock(o) ?: continue
                if (g.coords == null) continue
                graphs = true
                for (a in g.arrays) {
                    val n = PdEngine.arraySize(a.name)
                    if (n <= 0) continue
                    val buf = a.live?.takeIf { it.size == n } ?: FloatArray(n)
                    if (PdEngine.readArray(a.name, 0, buf)) a.live = buf
                }
            }
            if (graphs) canvas.invalidate()
            // re-arm unconditionally: tables/graphs can appear at any moment,
            // and a first pass that found none must not kill the poller
            graphHandler.postDelayed(this, 100)
        }
    }

    private fun startGraphPoller() {
        graphHandler.removeCallbacks(graphPoller)
        graphPoller.run() // re-arms itself only when a graph is present
    }

    override fun onDestroy() {
        PdEngine.stopAudio()
        PdEngine.closePatch()
        PdEngine.vuListener = null
        super.onDestroy()
    }

    private fun setBarTitle(t: CharSequence) {
        titleBase = t
        renderTitle()
    }

    /**
     * Composes the toolbar title from its parts: the base name, the
     * innermost open subpatch, the unsaved dot, the text-mode suffix.
     * [titleBase] stays PURE — it must never absorb the dot (the old
     * onDirtyChanged rewrote titleBase to "name •", so a later
     * setBarTitle(base) — popSubpatch restoring the captured clean title —
     * silently dropped the dot while the dirty flag was already latched,
     * and edits made inside a [pd x] showed neither dot nor exit warning).
     */
    private fun renderTitle() {
        val sub = subStack.lastOrNull()?.let { l ->
            l.parentDoc.objectById(l.objId)?.displayName()?.let { " / $it" } ?: ""
        } ?: ""
        val full = titleBase.toString() + sub + (if (titleDirty) " •" else "")
        barTitle.text = if (textMode) "$full - TXT" else full
        capTitleWidth()
    }

    /** Width the patch title may occupy: toolbar right edge minus the fixed
     *  key strip, measured from the title's left. The strip width mirrors
     *  activity_editor.xml (4×40dp keys + 3×4dp gaps + 4dp paddingEnd) and
     *  is NOT derived from the keys' laid-out positions — an uncapped long
     *  title pushes them off-screen, which would poison key.left. Runs
     *  deferred until positions are measured; no-op while free ≤ 0. */
    private fun capTitleWidth() {
        toolbar.post {
            val d = resources.displayMetrics.density
            val free = toolbar.width - (176 * d).toInt() - barTitle.left - (8 * d).toInt()
            if (free > 0) barTitle.maxWidth = free
        }
    }

    companion object {
        const val EXTRA_PATH = "path"

        // PC-98 console strip: edge-grey log ink, error red (design §5)
        private const val CONSOLE_INK = 0xFFA8A8C0.toInt()
        private const val CONSOLE_ERROR = 0xFFFC5454.toInt()
        /** view-buffer trim: check once past this many chars, keep this many
         *  lines (just under the engine's 500-line snapshot) */
        private const val CONSOLE_TRIM_CHARS = 24_000
        private const val CONSOLE_KEEP_LINES = 450
        /** Minimum interval between console drains: floods batch instead of
         *  re-rendering per frame (see scheduleConsoleFlush). */
        private const val CONSOLE_FLUSH_MIN_MS = 120L
        private const val MENU_ACTIVE = 0xFF54FCFC.toInt()   // signal-cyan toggle fill
    }
}
