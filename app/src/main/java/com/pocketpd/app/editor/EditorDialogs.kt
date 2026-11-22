package com.pocketpd.app.editor

import android.app.Activity
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.pocketpd.app.R
import com.pocketpd.app.patch.ObjKind
import com.pocketpd.app.patch.PatchObject
import com.pocketpd.app.patch.PdObjectMeta
import com.pocketpd.app.runtime.PdEngine

/** All editor dialogs and bottom sheets in one place. */
object EditorDialogs {

    // ------------------------------------------------------------- text edit

    /** Text editor for object boxes / messages / comments. [onCancel] runs when
     *  the dialog closes without applying (used to remove placeholder objects). */
    fun showTextEditor(
        activity: AppCompatActivity,
        obj: PatchObject,
        onApply: (List<String>) -> Unit,
        onCancel: (() -> Unit)? = null,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_text_edit, null)
        val edit = view.findViewById<EditText>(R.id.edit)
        val til = view.findViewById<TextInputLayout>(R.id.til)
        when (obj.kind) {
            ObjKind.OBJECT -> {
                til.hint = activity.getString(R.string.hint_object_name)
                edit.setText(obj.args.joinToString(" "))
            }
            ObjKind.MSG -> {
                til.hint = activity.getString(R.string.hint_message)
                edit.setText(obj.args.joinToString(" ") { PatchObject.displayEscape(it) })
            }
            ObjKind.COMMENT -> {
                til.hint = activity.getString(R.string.hint_comment)
                edit.setText(obj.args.joinToString(" ") { PatchObject.displayEscape(it) })
            }
            else -> {
                til.hint = activity.getString(R.string.hint_object_name)
                edit.setText(obj.args.joinToString(" "))
            }
        }
        var applied = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(obj.kind.label)
            .setView(view)
            .setPositiveButton(R.string.apply) { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isEmpty() && obj.kind != ObjKind.COMMENT) return@setPositiveButton
                applied = true
                onApply(tokenizeFor(obj, text))
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        if (onCancel != null) {
            dialog.setOnDismissListener { if (!applied) onCancel() }
        }
        dialog.show()
    }

    fun tokenizeFor(obj: PatchObject, text: String): List<String> = when (obj.kind) {
        ObjKind.MSG, ObjKind.COMMENT -> PatchObject.storeEscape(text)
        else -> text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ properties

    // Both Properties dialogs share one PC-98 window kit: the design-02
    // popup panel (body ground, edge stroke, +6dp hard shadow — same chrome
    // as the long-press menu) carrying design-04 BIOS rows, a small edge
    // caption over a cyan-stroked value box. Panel width = the 02 action
    // panel (230 design px at the dialog's 2px-per-dp scale). Port counts
    // are NOT editable here — they come from the class table (PdObjectMeta).
    private const val PANEL_W_DP = 230

    private fun dialogFont(activity: Activity) = runCatching {
        androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.pd98_latin)
    }.getOrNull()

    /** popup_panel_bg's hard shadow is a +6dp layer inset INSIDE the
     *  drawable, so the visible body ends that far short of the view's
     *  right/bottom edges — children pad by [shadowInset] on those sides
     *  to stay symmetric against the visible stroke. */
    private fun shadowInset(activity: Activity): Int =
        (6f * activity.resources.displayMetrics.density).toInt()

    private fun hairline(activity: Activity): android.view.View =
        android.view.View(activity).apply {
            setBackgroundColor(0xFF23234A.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1f),
            ).apply { marginEnd = shadowInset(activity) }
        }

    /** Builds the frameless window whose chrome is [panel] itself (no dim,
     *  tap outside cancels — same presentation as the long-press menu).
     *  Returns it unshown; the caller shows and dismisses it. */
    private fun pc98Window(activity: Activity, panel: android.view.View): android.app.Dialog {
        val d = dp(activity, 1f)
        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(android.widget.FrameLayout(activity).apply {
            setPadding(16 * d, 16 * d, 16 * d, 16 * d)
            addView(panel)
        })
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0f)
            setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return dialog
    }

    /** Caption above a value box: small edge-caps line (design 04). */
    private fun fieldCaption(activity: Activity, text: String): android.widget.TextView =
        android.widget.TextView(activity).apply {
            this.text = text
            typeface = dialogFont(activity)
            textSize = 8.5f
            includeFontPadding = false
            setTextColor(0xFFA8A8C0.toInt())
        }

    /** BIOS value box: field fill, signal-cyan stroke, cyan F5.6 text. */
    private fun valueEdit(
        activity: Activity, text: String, numeric: Boolean = false,
    ): EditText {
        val d = dp(activity, 1f)
        return EditText(activity).apply {
            setText(text)
            typeface = dialogFont(activity)
            textSize = 10f
            setTextColor(0xFF54FCFC.toInt())
            setSingleLine(true)
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            background = activity.getDrawable(R.drawable.value_box_bg)
            setPadding(8 * d, 0, 8 * d, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = 40 * d
        }
    }

    /** design-04 ON/OFF value chip: dark + cyan stroke when off, solid cyan
     *  with field-dark text when on. */
    private fun valueChip(
        activity: Activity, checked: Boolean, onChange: (Boolean) -> Unit,
    ): android.widget.TextView {
        val d = dp(activity, 1f)
        var on = checked
        val chip = android.widget.TextView(activity).apply {
            typeface = dialogFont(activity)
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            minHeight = 40 * d
            minWidth = 88 * d
        }
        fun repaint() {
            chip.text = if (on) "ON" else "OFF"
            chip.setTextColor(if (on) 0xFF04040C.toInt() else 0xFF54FCFC.toInt())
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (on) 0xFF54FCFC.toInt() else 0xFF04040C.toInt())
                setStroke(dp(activity, 1f), 0xFF54FCFC.toInt())
            }
        }
        chip.setOnClickListener { on = !on; repaint(); onChange(on) }
        repaint()
        return chip
    }

    /** Panel key: field fill + stroke; APPLY carries the signal cyan. */
    private fun panelKey(
        activity: Activity, label: String, stroke: Int, color: Int, onClick: () -> Unit,
    ): android.widget.TextView =
        android.widget.TextView(activity).apply {
            text = label
            typeface = dialogFont(activity)
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(color)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF04040C.toInt())
                setStroke(dp(activity, 1f), stroke)
            }
            setOnClickListener { onClick() }
        }

    /** Appends a caption-over-field row to [body] (8dp rhythm). */
    private fun fieldRow(
        activity: Activity,
        body: android.widget.LinearLayout,
        captionText: String,
        field: android.view.View,
    ) {
        val d = dp(activity, 1f)
        body.addView(android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8 * d }
            addView(fieldCaption(activity, captionText))
            addView(
                field,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 4 * d },
            )
        })
    }

    /** Two caption-over-field columns side by side (Label X/Y, Min/Max). */
    /**
     * A value-box choice field with its own anchored popup (design 04 closed
     * value + design 02 panel rows). Replaces Spinner: the popup is shown as
     * a drop-down of the value box with **the same width** — Spinner's
     * internal popup placement offsets and re-sizes itself, which read as a
     * misaligned, differently-sized menu. Rows are plain pixel-font TextViews
     * (no singleLine/ellipsis); the current pick is emphasized in signal cyan.
     */
    private fun choiceField(
        activity: AppCompatActivity,
        options: List<String>,
        initial: Int,
        onPick: (Int) -> Unit,
    ): android.view.View {
        val d = dp(activity, 1f)
        var sel = initial.coerceIn(0, options.size - 1)
        val value = TextView(activity).apply {
            typeface = dialogFont(activity)
            textSize = 10f
            setTextColor(0xFF00A8A8.toInt()) // pd98_cyd value ink
            gravity = android.view.Gravity.CENTER_VERTICAL
            text = options[sel]
        }
        val box = android.widget.FrameLayout(activity).apply {
            background = activity.getDrawable(R.drawable.value_box_bg)
            minimumHeight = 40 * d
            addView(value, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = 10 * d
                marginEnd = 22 * d // clears the ▼
            })
            addView(android.widget.TextView(activity).apply {
                text = "▼"
                typeface = dialogFont(activity)
                textSize = 9f
                setTextColor(0xFF54FCFC.toInt())
            }, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.END,
            ).apply { marginEnd = 8 * d })
            setOnClickListener { anchor ->
                // the panel drops flush under the box, its width = the box's
                // own width (Spinner's internal placement offset/re-sized it)
                var popupRef: android.widget.PopupWindow? = null
                val panel = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = activity.getDrawable(R.drawable.popup_panel_bg)
                }
                val hair = fun(): android.view.View = android.view.View(activity).apply {
                    setBackgroundColor(0xFF23234A.toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt(),
                    ).apply { marginEnd = (6 * d).toInt() } // clear the hard shadow
                }
                options.forEachIndexed { index, label ->
                    if (index > 0) panel.addView(hair())
                    panel.addView(TextView(activity).apply {
                        text = label
                        typeface = dialogFont(activity)
                        textSize = 10f
                        setTextColor(
                            if (index == sel) 0xFF54FCFC.toInt() else 0xFFFCFCFC.toInt()
                        )
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).toInt(),
                        )
                        setOnClickListener {
                            popupRef?.dismiss()
                            sel = index
                            value.text = label
                            onPick(index)
                        }
                    })
                }
                val pw = android.widget.PopupWindow(
                    // +9dp: 6dp for popup_panel_bg's hard shadow inset (kept
                    // inside the drawable's right edge) + 3dp measured trim —
                    // puts the visible panel stroke dead on the box border
                    // (d=1.5: stroke center lands within 1px of the box's)
                    panel, anchor.width + (9 * d).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true,
                )
                pw.isOutsideTouchable = true
                pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
                popupRef = pw
                pw.showAsDropDown(anchor, 0, 0)
            }
        }
        return box
    }

    private fun fieldRowPair(
        activity: Activity,
        body: android.widget.LinearLayout,
        a: Pair<String, android.view.View>,
        b: Pair<String, android.view.View>,
    ) {
        val d = dp(activity, 1f)
        fun col(p: Pair<String, android.view.View>): android.view.View =
            android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
                addView(fieldCaption(activity, p.first))
                addView(
                    p.second,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = 4 * d },
                )
            }
        body.addView(android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8 * d }
            addView(col(a))
            addView(
                col(b),
                android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginStart = 8 * d },
            )
        })
    }

    fun showProperties(activity: AppCompatActivity, obj: PatchObject, onApply: () -> Unit) {
        val d = dp(activity, 1f)
        val si = shadowInset(activity)
        val iemLabels = PdObjectMeta.labelIndices(obj)
        val atomLabel = PdObjectMeta.atomLabel(obj)
        val isSlider = obj.kind == ObjKind.OBJECT &&
            (obj.className() == "hsl" || obj.className() == "vsl")
        // per-class numeric parameters (size/hold/cells/limits…)
        val params = PdObjectMeta.objectParams(obj)
        // generic objects edit their creation arguments as raw text — the
        // only way external/abstraction parameters are reachable here.
        // [table] keeps an app-synthesized display graph in obj.graph; its
        // args (name/size) must stay editable, so exempt it
        val hasArgsRow = obj.kind == ObjKind.OBJECT &&
            !PdObjectMeta.isIemGui(obj.className()) &&
            obj.className() !in setOf("graph~", "spectrograph~", "keyboard", "euclid", "circle5", "pad", "knob", "popmenu") &&
            (obj.graph == null || obj.className() == "table")

        // send/receive only for classes that actually carry symbol tokens —
        // plain objects (osc~, metro…) would just show two dead rows
        val symSlots = PdObjectMeta.hasSymbolSlots(obj)
        val sendEdit = valueEdit(activity, obj.meta.sendSymbol)
        val rcvEdit = valueEdit(activity, obj.meta.receiveSymbol)
        val labelEdit = valueEdit(activity, obj.meta.labelText)
        val ldxEdit = valueEdit(activity, "0", numeric = true)
        val ldyEdit = valueEdit(activity, "0", numeric = true)
        val minEdit = valueEdit(activity, "0", numeric = true)
        val maxEdit = valueEdit(activity, "1", numeric = true)

        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(12 * d, 0, 12 * d + si, 12 * d)
        }
        fun addRow(captionText: String, field: android.view.View) =
            fieldRow(activity, body, captionText, field)
        fun addPair(
            a: Pair<String, android.view.View>, b: Pair<String, android.view.View>,
        ) = fieldRowPair(activity, body, a, b)

        // CLASS is the object's identity, not a field: plain selectable text,
        // no value box (there is nothing to type into)
        addRow(
            activity.getString(R.string.prop_class),
            android.widget.TextView(activity).apply {
                text = obj.args.joinToString(" ").ifEmpty { obj.kind.label }
                typeface = dialogFont(activity)
                textSize = 10f
                setTextColor(0xFFFCFCFC.toInt())
                setTextIsSelectable(true)
            },
        )
        if (symSlots) {
            addRow(activity.getString(R.string.prop_send), sendEdit)
            addRow(activity.getString(R.string.prop_receive), rcvEdit)
        }

        // graph blocks ([table] objects, GOP arrays): pick the render style —
        // pd's three #X array filestyles (g_graph.c garray_save)
        var drawStyleSel = 0
        val styleField = obj.graph?.arrays?.takeIf { it.isNotEmpty() }?.let { arrays ->
            drawStyleSel = arrays[0].style.coerceIn(0, 2)
            val f = choiceField(
                activity, listOf("Polygon", "Points", "Bezier curve"), drawStyleSel,
            ) { i -> drawStyleSel = i }
            addRow(activity.getString(R.string.prop_draw_style), f)
            f
        }

        // ---- built-in label (iemgui widgets: text + offset; atoms: text +
        // side selector) — restore what desktop pd's properties offer
        var posSel = 0 // label position choice (see atomLabel branch)
        if (iemLabels != null) {
            val (dx, dy) = PdObjectMeta.labelOffset(obj)
            labelEdit.setText(obj.meta.labelText)
            ldxEdit.setText(dx.toString())
            ldyEdit.setText(dy.toString())
            addRow(activity.getString(R.string.prop_label), labelEdit)
            addPair(
                activity.getString(R.string.prop_label_x) to ldxEdit,
                activity.getString(R.string.prop_label_y) to ldyEdit,
            )
        }
        if (atomLabel != null) {
            labelEdit.setText(atomLabel.first)
            val posSel0 = atomLabel.second.coerceIn(0, 3)
            val posField = choiceField(
                activity, listOf("Left", "Right", "Top", "Bottom"), posSel0,
            ) { i -> posSel = i }
            posSel = posSel0
            addRow(activity.getString(R.string.prop_label_pos), posField)
        }

        // sliders: range + integer-step toggle, adjustable without editing text
        var intSteps = false
        if (isSlider) {
            val range = PdObjectMeta.sliderRange(obj)
            minEdit.setText(range.min.toString())
            maxEdit.setText(range.max.toString())
            intSteps = PdObjectMeta.isIntSteps(obj)
            addPair(
                activity.getString(R.string.prop_min) to minEdit,
                activity.getString(R.string.prop_max) to maxEdit,
            )
            addRow(
                activity.getString(R.string.prop_int_steps),
                valueChip(activity, intSteps) { intSteps = it },
            )
        }

        // ---- app-only [popmenu]: the complete upstream parameter set ----
        // (width/height/fontsize, output mode, initial selection, loadbang,
        // label, param/var, items) — the user asked for full properties, not
        // a one-line args stub
        var popWidth: EditText? = null
        var popHeight: EditText? = null
        var popFont: EditText? = null
        var popLoad: EditText? = null
        var popItems: EditText? = null
        var popOutmode = 0
        var popLb = 1
        val isPopmenu = obj.kind == ObjKind.OBJECT && obj.className() == "popmenu"
        if (isPopmenu) {
            PdObjectMeta.ensurePopmenuLayout(obj)
            popWidth = valueEdit(activity, obj.args[1], numeric = true)
            popHeight = valueEdit(activity, obj.args[2], numeric = true)
            popFont = valueEdit(activity, obj.args[3], numeric = true)
            addPair(
                activity.getString(R.string.prop_width) to popWidth,
                activity.getString(R.string.prop_height) to popHeight,
            )
            addRow(activity.getString(R.string.prop_font_size), popFont)
            popOutmode = obj.args.getOrNull(12)?.toIntOrNull()?.coerceIn(0, 2) ?: 0
            addRow(
                activity.getString(R.string.prop_output),
                choiceField(activity, listOf("Index", "Item", "Both"), popOutmode) {
                    popOutmode = it
                },
            )
            popLoad = valueEdit(activity, obj.args.getOrNull(13) ?: "-1", numeric = true)
            addRow(activity.getString(R.string.prop_initial), popLoad)
            popLb = obj.args.getOrNull(14)?.toIntOrNull() ?: 1
            addRow(
                activity.getString(R.string.prop_loadbang),
                choiceField(activity, listOf("Off", "On"), popLb) { popLb = it },
            )
            labelEdit.setText(obj.meta.labelText)
            addRow(activity.getString(R.string.prop_label), labelEdit)
            popItems = valueEdit(activity, PdObjectMeta.popmenuItems(obj).joinToString("\n"))
            popItems.setSingleLine(false)
            popItems.minLines = 4
            addRow(activity.getString(R.string.prop_items), popItems)
        }

        // per-class numeric parameters (bng hold, radio cells, nbx limits…)
        val paramEdits = mutableListOf<Pair<PdObjectMeta.ParamSpec, EditText>>()
        if (params != null) {
            for (spec in params) {
                val edit = valueEdit(
                    activity, PdObjectMeta.paramOf(obj, spec).toString(), numeric = true,
                )
                paramEdits.add(spec to edit)
            }
            // rows of two, captioned with the param captions
            var i = 0
            while (i < paramEdits.size) {
                val a = paramEdits[i]
                val b = paramEdits.getOrNull(i + 1)
                if (b == null) {
                    addRow(paramCaption(activity, a.first.key), a.second)
                } else {
                    addPair(
                        paramCaption(activity, a.first.key) to a.second,
                        paramCaption(activity, b.first.key) to b.second,
                    )
                }
                i += 2
            }
        }

        // generic objects: raw creation arguments (osc~ 440, metro 500,
        // externals, abstractions) — class name stays fixed
        val argsEdit = if (hasArgsRow) {
            val e = valueEdit(activity, PdObjectMeta.argumentsOf(obj))
            addRow(activity.getString(R.string.prop_args), e)
            e
        } else null

        val cancelKey = panelKey(
            activity, activity.getString(R.string.cancel),
            0xFFA8A8C0.toInt(), 0xFFFCFCFC.toInt(),
        ) { }
        val applyKey = panelKey(
            activity, activity.getString(R.string.apply),
            0xFF54FCFC.toInt(), 0xFF54FCFC.toInt(),
        ) { }
        val panel = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.popup_panel_bg)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                PANEL_W_DP * d, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(android.widget.TextView(activity).apply {
                text = activity.getString(R.string.prop_title)
                typeface = dialogFont(activity)
                textSize = 10f
                setTextColor(0xFFA8A8C0.toInt())
                setPadding(12 * d, 10 * d, 12 * d + si, 8 * d)
            })
            addView(hairline(activity))
            addView(android.widget.ScrollView(activity).apply {
                addView(body, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            })
            addView(hairline(activity))
            addView(android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(12 * d, 10 * d, 12 * d + si, 12 * d + si)
                addView(
                    cancelKey,
                    android.widget.LinearLayout.LayoutParams(0, 44 * d, 1f)
                        .apply { marginEnd = 8 * d },
                )
                addView(
                    applyKey,
                    android.widget.LinearLayout.LayoutParams(0, 44 * d, 1f),
                )
            })
        }
        val dialog = pc98Window(activity, panel)
        cancelKey.setOnClickListener { dialog.dismiss() }
        applyKey.setOnClickListener {
            PdObjectMeta.setSymbols(
                obj, sendEdit.text.toString(), rcvEdit.text.toString(),
            )
            when {
                iemLabels != null -> PdObjectMeta.setLabel(
                    obj,
                    labelEdit.text.toString(),
                    ldxEdit.text.toString().toIntOrNull() ?: 0,
                    ldyEdit.text.toString().toIntOrNull() ?: 0,
                )
                atomLabel != null -> PdObjectMeta.setAtomLabel(
                    obj,
                    labelEdit.text.toString(),
                    posSel,
                )
            }
            if (isSlider) {
                val min = minEdit.text.toString().toFloatOrNull()
                val max = maxEdit.text.toString().toFloatOrNull()
                if (min != null && max != null && max > min) {
                    PdObjectMeta.setSliderParams(obj, min, max, intSteps)
                } else if (intSteps != PdObjectMeta.isIntSteps(obj)) {
                    // range invalid: keep it, still honor the integer toggle
                    val r = PdObjectMeta.sliderRange(obj)
                    PdObjectMeta.setSliderParams(obj, r.min, r.max, intSteps)
                }
            }
            for ((spec, edit) in paramEdits) {
                edit.text.toString().toIntOrNull()?.let {
                    if (it != PdObjectMeta.paramOf(obj, spec)) {
                        PdObjectMeta.setParam(obj, spec, it)
                    }
                }
            }
            argsEdit?.let {
                val text = it.text.toString().trim()
                if (text != PdObjectMeta.argumentsOf(obj)) {
                    PdObjectMeta.setArguments(obj, text)
                }
            }
            if (isPopmenu) {
                val items = popItems?.text?.toString()
                    ?.split('\n')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                PdObjectMeta.applyPopmenuProperties(
                    obj,
                    popWidth?.text?.toString()?.toIntOrNull() ?: 128,
                    popHeight?.text?.toString()?.toIntOrNull() ?: 26,
                    popFont?.text?.toString()?.toIntOrNull() ?: 12,
                    popOutmode,
                    popLoad?.text?.toString()?.toIntOrNull() ?: -1,
                    popLb,
                    labelEdit.text.toString(),
                    items,
                )
            }
            if (styleField != null) {
                PdObjectMeta.setArrayStyle(obj, drawStyleSel)
            }
            dialog.dismiss()
            onApply()
        }
        dialog.show()
    }

    /** Human captions for the per-class parameter keys. */
    private fun paramCaption(activity: Activity, key: String): String = when (key) {
        "size" -> activity.getString(R.string.prop_size)
        "width" -> activity.getString(R.string.prop_width)
        "height" -> activity.getString(R.string.prop_height)
        "hold" -> activity.getString(R.string.prop_hold)
        "interrupt" -> activity.getString(R.string.prop_interrupt)
        "nonzero" -> activity.getString(R.string.prop_nonzero)
        "cells" -> activity.getString(R.string.prop_cells)
        "log_height" -> activity.getString(R.string.prop_log_height)
        "min" -> activity.getString(R.string.prop_min)
        "max" -> activity.getString(R.string.prop_max)
        "key_width" -> activity.getString(R.string.prop_key_width)
        "octaves" -> activity.getString(R.string.prop_octaves)
        "hits" -> activity.getString(R.string.prop_hits)
        "steps" -> activity.getString(R.string.prop_steps)
        "rotation" -> activity.getString(R.string.prop_rotation)
        "interactive" -> activity.getString(R.string.prop_interactive)
        "key" -> activity.getString(R.string.prop_key)
        "chord_size" -> activity.getString(R.string.prop_chord_size)
        "gridx" -> activity.getString(R.string.prop_gridx)
        "gridy" -> activity.getString(R.string.prop_gridy)
        else -> key
    }

    fun showNumberEntry(activity: AppCompatActivity, current: Float, onValue: (Float) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_text_edit, null)
        val edit = view.findViewById<EditText>(R.id.edit)
        view.findViewById<TextInputLayout>(R.id.til).hint = "0"
        edit.inputType = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        edit.setText(current.toString())
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.obj_class_floatatom)
            .setView(view)
            .setPositiveButton(R.string.apply) { _, _ ->
                edit.text.toString().toFloatOrNull()?.let(onValue)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------- palette

    private val CATEGORIES = linkedMapOf(
        "signal" to listOf(
            "osc~", "phasor~", "noise~", "sig~", "adc~", "dac~", "+~", "-~", "*~", "/~",
            "line~", "vline~", "env~", "snapshot~", "clip~", "delwrite~", "delread4~",
            "tabwrite~", "tabread4~", "send~", "receive~", "catch~", "throw~", "print~",
        ),
        "control" to listOf(
            "metro", "delay", "float", "int", "trigger", "select", "route", "pack",
            "unpack", "moses", "spigot", "change", "random", "line", "timer", "until",
            "loadbang", "print", "send", "receive", "list", "value", "stripnote", "poly",
            "mtof", "ftom", "makefilename",
        ),
        "MIDI" to listOf(
            "notein", "noteout", "ctlin", "ctlout", "bendin", "bendout",
            "pgmin", "pgmout", "midiin", "midiout", "polytouchin",
        ),
    )

    fun showPalette(activity: AppCompatActivity, onPick: (List<String>) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_palette, null)
        // square PC-98 sheet; the rounded drag handle is hidden by the
        // bottomSheetDragHandleStyle override in the theme
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)
        val input = view.findViewById<EditText>(R.id.palette_text)
        val groups = view.findViewById<ChipGroup>(R.id.palette_groups)
        for ((category, items) in CATEGORIES) {
            val header = TextView(activity).apply {
                text = category
                setPadding(0, dp(activity, 12f), 0, dp(activity, 4f))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            }
            groups.addView(header)
            val chipGroup = ChipGroup(activity).apply {
                isSingleLine = false
                chipSpacingHorizontal = dp(activity, 6f)
            }
            for (item in items) {
                chipGroup.addView(Chip(activity).apply {
                    text = item
                    isCheckable = false
                    setOnClickListener {
                        dialog.dismiss()
                        onPick(listOf(item))
                    }
                })
            }
            groups.addView(chipGroup)
        }
        input.setOnEditorActionListener { _, _, _ ->
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                dialog.dismiss()
                onPick(value.split(Regex("\\s+")))
            }
            true
        }
        dialog.show()
    }

    private fun dp(activity: Activity, v: Float): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    // ------------------------------------------------- patch properties

    /**
     * The patch's own properties: where the file lives on disk, plus the
     * graph-on-parent UI range (enable, size, offset — vanilla pd's
     * goprect). Applies in memory; the change lands in the file on save.
     */
    fun showPatchProperties(
        activity: AppCompatActivity,
        doc: com.pocketpd.app.patch.PatchDocument,
        filePath: String?,
        onApply: () -> Unit,
    ) {
        val d = dp(activity, 1f)
        val si = shadowInset(activity)
        val g = doc.gop
        var enabled = g?.enabled ?: false
        val wEdit = valueEdit(activity, (g?.w ?: 200).toString(), numeric = true)
        val hEdit = valueEdit(activity, (g?.h ?: 100).toString(), numeric = true)
        val mxEdit = valueEdit(activity, (g?.mx ?: 0).toString(), numeric = true)
        val myEdit = valueEdit(activity, (g?.my ?: 0).toString(), numeric = true)

        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(12 * d, 0, 12 * d + si, 12 * d)
        }
        fun addRow(captionText: String, field: android.view.View) =
            fieldRow(activity, body, captionText, field)
        fun addPair(
            a: Pair<String, android.view.View>, b: Pair<String, android.view.View>,
        ) = fieldRowPair(activity, body, a, b)

        // file location, selectable so it can be copied
        addRow(activity.getString(R.string.gop_location), android.widget.TextView(activity).apply {
            text = filePath ?: activity.getString(R.string.gop_unsaved)
            typeface = dialogFont(activity)
            textSize = 10f
            setTextColor(0xFFFCFCFC.toInt())
            setTextIsSelectable(true)
        })
        // section caption in signal cyan (design 04 headers)
        body.addView(android.widget.TextView(activity).apply {
            text = activity.getString(R.string.gop_section)
            typeface = dialogFont(activity)
            textSize = 8.5f
            includeFontPadding = false
            setTextColor(0xFF54FCFC.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 12 * d }
        })
        addRow(activity.getString(R.string.gop_enable), valueChip(activity, enabled) { enabled = it })
        addPair("Width" to wEdit, "Height" to hEdit)
        addPair("Offset X" to mxEdit, "Offset Y" to myEdit)
        body.addView(android.widget.TextView(activity).apply {
            text = activity.getString(R.string.gop_hint)
            typeface = dialogFont(activity)
            textSize = 8.5f
            setTextColor(0xFF6C6C8A.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8 * d }
        })

        val cancelKey = panelKey(
            activity, activity.getString(R.string.cancel),
            0xFFA8A8C0.toInt(), 0xFFFCFCFC.toInt(),
        ) { }
        val applyKey = panelKey(
            activity, activity.getString(R.string.apply),
            0xFF54FCFC.toInt(), 0xFF54FCFC.toInt(),
        ) { }
        val panel = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.popup_panel_bg)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                PANEL_W_DP * d, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(android.widget.TextView(activity).apply {
                text = activity.getString(R.string.action_properties)
                typeface = dialogFont(activity)
                textSize = 10f
                setTextColor(0xFFA8A8C0.toInt())
                setPadding(12 * d, 10 * d, 12 * d + si, 8 * d)
            })
            addView(hairline(activity))
            addView(android.widget.ScrollView(activity).apply {
                addView(body, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            })
            addView(hairline(activity))
            addView(android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(12 * d, 10 * d, 12 * d + si, 12 * d + si)
                addView(
                    cancelKey,
                    android.widget.LinearLayout.LayoutParams(0, 44 * d, 1f)
                        .apply { marginEnd = 8 * d },
                )
                addView(
                    applyKey,
                    android.widget.LinearLayout.LayoutParams(0, 44 * d, 1f),
                )
            })
        }
        val dialog = pc98Window(activity, panel)
        cancelKey.setOnClickListener { dialog.dismiss() }
        applyKey.setOnClickListener {
            val nw = (wEdit.text.toString().toIntOrNull() ?: 200).coerceIn(8, 2000)
            val nh = (hEdit.text.toString().toIntOrNull() ?: 100).coerceIn(8, 2000)
            val nmx = (mxEdit.text.toString().toIntOrNull() ?: 0).coerceIn(-4096, 4096)
            val nmy = (myEdit.text.toString().toIntOrNull() ?: 0).coerceIn(-4096, 4096)
            val gr = doc.gop ?: doc.createGop(nw, nh)
            gr.w = nw; gr.h = nh; gr.mx = nmx; gr.my = nmy
            gr.enabled = enabled
            gr.rewrite()
            dialog.dismiss()
            onApply()
        }
        dialog.show()
    }

    // ------------------------------------------------------------ variables

    /**
     * Patch-wide variable manager: groups every named channel in the
     * document ([value]/[send]/[receive] families, throw~/catch~, delay
     * lines, tables + array references, and the send/receive symbols bound
     * into gui widgets and atoms) into one list. Tapping a variable opens
     * the action menu (Rename / Delete); New value variable appends a
     * `[value name]` box in a free spot. Edits rewrite the document in
     * place — the session rebuild picks them up on the next RUN.
     */
    fun showVariables(
        activity: AppCompatActivity,
        doc: com.pocketpd.app.patch.PatchDocument,
        onApply: () -> Unit,
    ) {
        val d = dp(activity, 1f)
        val si = shadowInset(activity)
        val uses = doc.objects().mapNotNull { PdObjectMeta.varUses(it) }.flatten()
        // "auto-*" symbols are the app's own touch-UI relays assigned at
        // load — plumbing, not the user's named variables
            .filter { !symbolOf(it).startsWith("auto-") }
        val byName = LinkedHashMap<String, MutableList<PdObjectMeta.VarUse>>()
        for (u in uses) {
            val name = (if (u.mode == 0) u.obj.args.getOrNull(u.nameIndex) else symbolOf(u))
                ?.takeIf { it.isNotEmpty() } ?: continue
            byName.getOrPut(name) { mutableListOf() }.add(u)
        }
        var listDialog: android.app.Dialog? = null
        // each action rebuilds the patch, so close the list — reopening the
        // manager shows the fresh names
        fun changed() {
            listDialog?.dismiss()
            onApply()
        }

        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(12 * d, 0, 12 * d + si, 12 * d)
        }
        if (byName.isEmpty()) {
            body.addView(TextView(activity).apply {
                text = activity.getString(R.string.var_none)
                typeface = dialogFont(activity)
                textSize = 8.5f
                setTextColor(0xFF6C6C8A.toInt())
                setPadding(0, 4 * d, 0, 4 * d)
            })
        }
        for ((name, list) in byName) {
            val roles = list.map { it.role }.distinct().joinToString(" · ")
            body.addView(TextView(activity).apply {
                text = "$name  $roles"
                typeface = dialogFont(activity)
                textSize = 10f
                setTextColor(0xFFFCFCFC.toInt())
                setPadding(0, 5 * d, 0, 5 * d)
                setOnClickListener {
                    showVariableActions(activity, doc, name, list) { changed() }
                }
            })
        }
        // new [value name]: the canonical global numeric variable
        body.addView(TextView(activity).apply {
            text = "+ ${activity.getString(R.string.var_new)}"
            typeface = dialogFont(activity)
            textSize = 10f
            setTextColor(0xFF54FCFC.toInt())
            setPadding(0, 8 * d, 0, 2 * d)
            setOnClickListener {
                promptName(activity, activity.getString(R.string.var_new), "") { name ->
                    if (name == null) return@promptName
                    if (!validVarName(name)) {
                        android.widget.Toast.makeText(
                            activity, R.string.var_invalid, android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        return@promptName
                    }
                    var y = 40
                    while (doc.objects().any { it.x == 40 && kotlin.math.abs(it.y - y) < 24 }) {
                        y += 30
                    }
                    val obj = com.pocketpd.app.patch.PatchObject(
                        com.pocketpd.app.patch.newObjectId(), 40, y,
                        ObjKind.OBJECT, mutableListOf("value", name),
                    )
                    obj.meta = PdObjectMeta.resolve(obj)
                    doc.add(obj)
                    changed()
                }
            }
        })

        val closeKey = panelKey(
            activity, activity.getString(R.string.cancel),
            0xFFA8A8C0.toInt(), 0xFFFCFCFC.toInt(),
        ) { listDialog?.dismiss() }
        val panel = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.popup_panel_bg)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                PANEL_W_DP * d, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(android.widget.TextView(activity).apply {
                text = activity.getString(R.string.var_title)
                typeface = dialogFont(activity)
                textSize = 10f
                setTextColor(0xFFA8A8C0.toInt())
                setPadding(12 * d, 10 * d, 12 * d + si, 8 * d)
            })
            addView(hairline(activity))
            addView(android.widget.ScrollView(activity).apply {
                addView(body, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            })
            addView(hairline(activity))
            addView(android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(12 * d, 10 * d, 12 * d + si, 12 * d + si)
                addView(closeKey, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 44 * d,
                ))
            })
        }
        pc98Window(activity, panel).let { dialog ->
            listDialog = dialog
            dialog.show()
        }
    }

    /** Symbol bound by a mode-1/2 (widget/atom send-receive) use. */
    private fun symbolOf(u: PdObjectMeta.VarUse): String =
        if (u.mode == 1) u.obj.meta.sendSymbol else u.obj.meta.receiveSymbol

    /** Rename / Delete menu for one variable, then applies through [onChange]. */
    private fun showVariableActions(
        activity: AppCompatActivity,
        doc: com.pocketpd.app.patch.PatchDocument,
        name: String,
        uses: List<PdObjectMeta.VarUse>,
        onChange: () -> Unit,
    ) {
        fun done() {
            onChange()
        }
        EditorDialogs.showActionMenu(
            activity, "$name — ${activity.getString(R.string.var_uses, uses.size)}",
            listOf(
                activity.getString(R.string.var_rename) to true,
                activity.getString(R.string.var_delete) to true,
            ),
        ) { pick ->
            when (pick) {
                0 -> promptName(
                    activity,
                    activity.getString(R.string.var_rename), name,
                ) { newName ->
                    if (!newName.isNullOrBlank() && newName != name) {
                        if (!validVarName(newName)) {
                            android.widget.Toast.makeText(
                                activity, R.string.var_invalid, android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            for (u in uses) PdObjectMeta.setVarUse(u, newName.trim())
                        }
                        done()
                    }
                }
                1 -> {
                    for (u in uses) {
                        if (u.mode == 0) {
                            doc.remove(u.obj)
                        } else {
                            PdObjectMeta.setVarUse(u, "")
                        }
                    }
                    done()
                }
            }
        }
    }

    /** Small PC-98 prompt for a variable name; null result = cancelled. */
    private fun promptName(
        activity: AppCompatActivity, title: String, initial: String,
        onDone: (String?) -> Unit,
    ) {
        val d = dp(activity, 1f)
        val si = shadowInset(activity)
        val edit = valueEdit(activity, initial)
        val cancelKey = panelKey(
            activity, activity.getString(R.string.cancel),
            0xFFA8A8C0.toInt(), 0xFFFCFCFC.toInt(),
        ) { }
        val applyKey = panelKey(
            activity, activity.getString(R.string.apply),
            0xFF54FCFC.toInt(), 0xFF54FCFC.toInt(),
        ) { }
        val panel = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.popup_panel_bg)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                PANEL_W_DP * d, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(android.widget.TextView(activity).apply {
                text = title
                typeface = dialogFont(activity)
                textSize = 10f
                setTextColor(0xFFA8A8C0.toInt())
                setPadding(12 * d, 10 * d, 12 * d + si, 8 * d)
            })
            addView(hairline(activity))
            addView(android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(12 * d, 10 * d, 12 * d + si, 12 * d)
                addView(fieldCaption(activity, activity.getString(R.string.var_name_hint)))
                addView(edit, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 4 * d })
            })
            addView(hairline(activity))
            addView(android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(12 * d, 10 * d, 12 * d + si, 12 * d + si)
                addView(cancelKey, android.widget.LinearLayout.LayoutParams(
                    0, 44 * d, 1f,
                ).apply { marginEnd = 8 * d })
                addView(applyKey, android.widget.LinearLayout.LayoutParams(0, 44 * d, 1f))
            })
        }
        val dialog = pc98Window(activity, panel)
        cancelKey.setOnClickListener {
            dialog.dismiss()
            onDone(null)
        }
        applyKey.setOnClickListener {
            val v = edit.text.toString().trim()
            dialog.dismiss()
            onDone(v.ifEmpty { null })
        }
        dialog.show()
    }

    /** Variable names are pd symbols: one word, no separators. */
    private fun validVarName(name: String): Boolean =
        name.matches(Regex("[^\\s;]+"))

    /**
     * Context menu styled as the design 02 popup: body panel, edge stroke,
     * hard +6dp shadow, hairline-separated rows in pixel type. Disabled
     * entries ("no help available") render dim and are not clickable, so
     * tapping them can never error. Delete renders in the error red.
     * [onPick] receives the index into [items].
     */
    fun showActionMenu(
        activity: AppCompatActivity,
        title: String,
        items: List<Pair<String, Boolean>>,
        onPick: (Int) -> Unit,
    ) {
        val d = dp(activity, 1f)
        val font = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.pd98_latin)
        }.getOrNull()
        val deleteLabels = setOf(
            activity.getString(R.string.delete), activity.getString(R.string.patch_delete),
        )
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.popup_panel_bg)
            // design 02: the action panel is 230px wide in the mockup — a
            // narrow column, not a full-width sheet
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (230 * d).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        // header: the object / list title, hairline below (design popup rows)
        box.addView(TextView(activity).apply {
            text = title
            typeface = font
            textSize = 10f
            setTextColor(0xFFA8A8C0.toInt())
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        })
        // hairlines must stop at the visible panel edge — the drawable's
        // right 6dp are the hard shadow, a full-width line pokes past it
        val hair = fun(): android.view.View = android.view.View(activity).apply {
            setBackgroundColor(0xFF23234A.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt(),
            ).apply { marginEnd = (6 * d).toInt() }
        }
        box.addView(hair())
        val dialog = android.app.Dialog(activity)
        items.forEachIndexed { index, (label, enabled) ->
            if (index > 0) box.addView(hair())
            box.addView(TextView(activity).apply {
                text = label
                typeface = font
                textSize = 10f
                setTextColor(
                    when {
                        !enabled -> 0xFF6C6C8A.toInt()
                        label in deleteLabels -> 0xFFFC5454.toInt()
                        else -> 0xFFFCFCFC.toInt()
                    }
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt(),
                )
                if (enabled) setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            })
        }
        // frameless window: the panel itself carries the design chrome
        val wrapper = android.widget.FrameLayout(activity).apply {
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            addView(box)
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(wrapper)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0f)
            setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
    }

    /** Bottom-sheet console was replaced by the persistent strip in
     *  EditorActivity; menu Console toggles that strip's visibility. */
}
