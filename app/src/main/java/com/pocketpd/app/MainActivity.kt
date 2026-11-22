package com.pocketpd.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.pocketpd.app.editor.EditorActivity
import com.pocketpd.app.ui.MouseBack
import com.pocketpd.app.ui.SettingsActivity
import com.pocketpd.app.util.PatchStore
import com.pocketpd.app.util.WorkStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Patch browser, FD-3.13 style: tap a row to open, long-press to select,
 *  the bottom keycap bar renames / duplicates / deletes the selection. */
class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var keyRename: TextView
    private lateinit var keyDuplicate: TextView
    private lateinit var keyDelete: TextView
    private val files = mutableListOf<File>()

    /** Right-click must never navigate (app-wide rule): on devices that map
     *  a mouse's right button to BACK the key is swallowed here. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            MouseBack.isRightClick(event)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    /** FD-style cursor row: long-press selects it for the keycap bar. */
    private var selectedFile: File? = null

    /** Multi-file import (patches, modules, ROM dumps - anything the
     *  objects can load); every picked file is copied into the patches
     *  dir, the count is toasted. */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val ok = uris.count { PatchStore.import(this, it) != null }
            if (ok > 0) {
                refresh()
                Toast.makeText(this, getString(R.string.import_done, ok), Toast.LENGTH_SHORT).show()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.err_import_failed)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    /** Whole-folder import: walks the picked tree and copies every file. */
    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val ok = PatchStore.importTree(this, uri)
            if (ok > 0) {
                refresh()
                Toast.makeText(this, getString(R.string.import_done, ok), Toast.LENGTH_SHORT).show()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.err_import_failed)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private val adapter = object : RecyclerView.Adapter<PatchHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatchHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_patch, parent, false)
            return PatchHolder(view)
        }

        override fun getItemCount(): Int = files.size

        override fun onBindViewHolder(holder: PatchHolder, position: Int) {
            holder.bind(files[position])
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PatchStore.extractSamples(this)
        // pd files belong in /sdcard/pocketPD (WorkStore) - without the
        // All-Files-Access grant they land in app-scoped storage that an
        // uninstall wipes (the 2026-11-21 data-loss). Ask every launch
        // until granted; "later" keeps the external-fallback working.
        WorkStore.migrateLegacy(this)
        if (!WorkStore.storageGranted(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.storage_title)
                .setMessage(R.string.storage_msg)
                .setPositiveButton(R.string.storage_grant) { _, _ ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName"))
                            )
                        }.onFailure {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 401
                        )
                    }
                }
                .setNegativeButton(R.string.storage_later, null)
                .show()
        }

        findViewById<android.view.View>(R.id.appbar).background =
            com.pocketpd.app.ui.ScanlineDrawable.titleBar(this)
        findViewById<android.view.View>(R.id.scan_overlay).background =
            com.pocketpd.app.ui.ScanlineDrawable.overlay(this)
        list = findViewById(R.id.patch_list)
        emptyView = findViewById(R.id.empty_view)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        // title carries the brand + running version (user request)
        findViewById<android.widget.TextView>(R.id.toolbar_title).text =
            "PocketPD v" + packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMainMenu(it) }
        findViewById<ImageButton>(R.id.btn_new).setOnClickListener { newPatchDialog() }
        keyRename = findViewById(R.id.key_rename)
        keyDuplicate = findViewById(R.id.key_duplicate)
        keyDelete = findViewById(R.id.key_delete)
        keyRename.setOnClickListener { selectedFile?.let(::renameDialog) }
        keyDuplicate.setOnClickListener { selectedFile?.let(::duplicatePatch) }
        keyDelete.setOnClickListener { selectedFile?.let(::deleteDialog) }
        updateKeyState()

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        refresh()
    }

    /**
     * Browser actions as PC-98 keycaps (import / examples / settings), in a
     * raised popup under the menu key — same language as the editor's
     * overflow grid, and the pixel font comes from the key style.
     */
    private fun showMainMenu(anchor: android.view.View) {
        data class Entry(val label: String, val run: () -> Unit)
        val entries = listOf(
            Entry(getString(R.string.action_import_patch)) {
                importLauncher.launch(arrayOf("*/*"))
            },
            Entry(getString(R.string.action_import_folder)) {
                importFolderLauncher.launch(null)
            },
            Entry(getString(R.string.examples_title)) { showExamples() },
            Entry(getString(R.string.action_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        val d = resources.displayMetrics.density
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF101040.toInt())
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        for (e in entries) {
            val b = com.google.android.material.button.MaterialButton(
                this, null, R.attr.pocketPdKeyStyle
            ).apply {
                text = e.label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                minHeight = (40 * d).toInt()
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
                textSize = 13f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (164 * d).toInt(), (40 * d).toInt()
                ).apply { setMargins(0, 0, 0, (3 * d).toInt()) }
                setOnClickListener {
                    mainPopup?.dismiss()
                    e.run()
                }
            }
            column.addView(b)
        }
        column.measure(
            android.view.View.MeasureSpec.UNSPECIFIED,
            android.view.View.MeasureSpec.UNSPECIFIED,
        )
        android.widget.PopupWindow(
            column,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).let { popup ->
            mainPopup = popup
            popup.showAsDropDown(anchor, -column.measuredWidth + anchor.width, 0)
        }
    }

    private var mainPopup: android.widget.PopupWindow? = null

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Examples browser: pick a category, then a patch. Read-only originals. */
    private fun showExamples() {
        com.pocketpd.app.util.Examples.ensureExtracted(this)
        val categories = com.pocketpd.app.util.Examples.categories(this)
        if (categories.isEmpty()) {
            Toast.makeText(this, R.string.examples_missing, Toast.LENGTH_SHORT).show()
            return
        }
        com.pocketpd.app.editor.EditorDialogs.showActionMenu(
            this,
            getString(R.string.examples_title),
            categories.map { it to true },
        ) { which -> showExampleList(categories[which]) }
    }

    private fun showExampleList(category: String) {
        val patches = com.pocketpd.app.util.Examples.patches(this, category)
        val names = patches.map { it.name.removeSuffix(".pd") }
        com.pocketpd.app.editor.EditorDialogs.showActionMenu(
            this,
            "$category (${names.size})",
            names.map { it to true },
        ) { which -> open(patches[which], isNew = false) }
    }

    private fun refresh() {
        files.clear()
        files.addAll(PatchStore.list(this))
        if (selectedFile != null && selectedFile !in files) selectedFile = null
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (files.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        updateKeyState()
    }

    private fun newPatchDialog() {
        val view = layoutInflater.inflate(com.pocketpd.app.R.layout.dialog_text_edit, null)
        val edit = view.findViewById<android.widget.EditText>(R.id.edit)
        view.findViewById<TextInputLayout>(com.pocketpd.app.R.id.til).hint = "patch.pd"
        edit.setText("")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_new_patch)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) {
                    open(PatchStore.create(this, name), isNew = true)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun open(file: File, isNew: Boolean) {
        if (isNew && file.length() == 0L) {
            // start new patches with a canvas header so they load cleanly;
            // never let an unwritable location take the app down
            val ok = runCatching {
                file.writeText("#N canvas 0 0 720 1280 10;\n")
            }.isSuccess
            if (!ok) {
                Toast.makeText(this, R.string.err_storage, Toast.LENGTH_LONG).show()
                return
            }
        }
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_PATH, file.absolutePath)
        startActivity(intent)
    }

    // FD-3.13 interactions: the row menu is gone — short press opens,
    // long press selects, the keycap bar operates on the selection.
    private fun renameDialog(file: File) {
        val view = layoutInflater.inflate(com.pocketpd.app.R.layout.dialog_text_edit, null)
        val edit = view.findViewById<android.widget.EditText>(R.id.edit)
        view.findViewById<TextInputLayout>(com.pocketpd.app.R.id.til).hint = "patch.pd"
        edit.setText(file.name)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.patch_rename)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val target = PatchStore.create(this, edit.text.toString().trim())
                if (file.renameTo(target)) {
                    selectedFile = target
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteDialog(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.patch_delete)
            .setMessage(getString(R.string.confirm_delete_patch, file.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                file.delete()
                selectedFile = null
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** xxx.pd → xxx_1.pd (then _2, _3 …) copied beside the original. */
    private fun duplicatePatch(file: File) {
        val base = file.nameWithoutExtension
        var n = 1
        var target = File(file.parentFile, "${base}_$n.pd")
        while (target.exists()) {
            n += 1
            target = File(file.parentFile, "${base}_$n.pd")
        }
        val ok = runCatching { file.copyTo(target, overwrite = false) }.isSuccess
        if (!ok) {
            Toast.makeText(this, R.string.err_storage, Toast.LENGTH_LONG).show()
            return
        }
        refresh()
    }

    /** FD keycaps: $D cyan rename/duplicate on the left, $B magenta delete
        on the right; all gray keycaps until a row is selected (user spec). */
    private fun updateKeyState() {
        val on = selectedFile != null
        val keys = listOf(
            keyRename to 0xFF54FCFC.toInt(),
            keyDuplicate to 0xFF54FCFC.toInt(),
            keyDelete to 0xFFFC54FC.toInt(),
        )
        for ((key, hue) in keys) {
            key.setBackgroundColor(if (on) hue else 0xFF545454.toInt())
            key.setTextColor(0xFF04040C.toInt())
        }
    }

    private inner class PatchHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val row: android.view.View = view.findViewById(R.id.row_content)
        private val name: TextView = view.findViewById(R.id.patch_name)
        private val meta: TextView = view.findViewById(R.id.patch_meta)
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun bind(file: File) {
            name.text = file.name
            meta.text = getString(
                R.string.patch_meta,
                android.text.format.Formatter.formatShortFileSize(itemView.context, file.length()),
                dateFormat.format(Date(file.lastModified())),
            )
            applySelection(file)
            itemView.setOnClickListener { open(file, isNew = false) }
            itemView.setOnLongClickListener { v ->
                selectedFile = if (file == selectedFile) null else file
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                updateKeyState()
                adapter.notifyDataSetChanged()
                true
            }
        }

        /** Cursor row like FD's highlighted KERNEL.SYS line: inverse video,
            warn-yellow band with field-color text. */
        private fun applySelection(file: File) {
            val sel = file == selectedFile
            if (sel) {
                row.setBackgroundColor(0xFFFCFC54.toInt())
                name.setTextColor(0xFF04040C.toInt())
                meta.setTextColor(0xFF04040C.toInt())
            } else {
                row.background = null
                name.setTextColor(0xFFFCFCFC.toInt())
                meta.setTextColor(0xFF6C6C8A.toInt())
            }
        }
    }
}
