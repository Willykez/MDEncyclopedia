package com.willykez.md

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.willykez.md.model.MdFile

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var listBody: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var searchInput: EditText
    private lateinit var emptyState: LinearLayout
    private lateinit var countChip: TextView

    private var allFiles: List<MdFile> = emptyList()

    private enum class SortMode { NAME, MODIFIED }
    private var sortMode = SortMode.NAME

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var imported = 0
        for (u in uris) {
            runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (MarkdownStore.importFrom(contentResolver, u) != null) imported++
        }
        toast("Imported $imported file(s)")
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        AppTheme.init(this)
        MarkdownStore.init(this)

        setContentView(buildRoot())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::listBody.isInitialized) refresh()
    }

    private fun applyChrome() {
        window.statusBarColor = AppTheme.surface
        window.navigationBarColor = AppTheme.bg
    }

    // ── Root layout ──────────────────────────────────────────────────
    private fun buildRoot(): View {
        applyChrome()
        root = FrameLayout(this)
        root.setBackgroundColor(AppTheme.bg)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        main.addView(buildTopBar())
        main.addView(buildBreadcrumb())
        main.addView(buildContentArea())
        root.addView(main)

        root.addView(buildFab())
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(AppTheme.surface)
        bar.setPadding(dp(14), 0, dp(8), 0)
        bar.minimumHeight = dp(58)

        val brand = LinearLayout(this)
        brand.orientation = LinearLayout.VERTICAL
        brand.setPadding(0, 0, dp(10), 0)
        brand.addView(mkTv("Android UI", 14, AppTheme.text, true))
        val sub = mkTv("ENCYCLOPEDIA", 9, AppTheme.text4, false)
        sub.letterSpacing = 0.12f
        brand.addView(sub)
        bar.addView(brand)

        searchInput = buildSearchField()
        val siLp = LinearLayout.LayoutParams(0, dp(38), 1f)
        siLp.leftMargin = dp(10)
        searchInput.layoutParams = siLp
        bar.addView(searchInput)

        bar.addView(mkHSpace(6))
        bar.addView(iconBtn("⇅") { showSortMenu() })
        bar.addView(mkHSpace(4))
        bar.addView(iconBtn("Aa") { cycleFontSize() })
        bar.addView(mkHSpace(4))
        bar.addView(iconBtn(if (AppTheme.isDark) "☀" else "🌙") { toggleTheme() })
        bar.addView(mkHSpace(4))
        bar.addView(iconBtn("＋") { showImportPicker() })
        return bar
    }

    private fun buildSearchField(): EditText {
        val bg = roundRect(AppTheme.elevated, dp(20), AppTheme.border2, dp(1))
        val et = EditText(this)
        et.background = bg
        et.hint = "Search your library"
        et.setHintTextColor(AppTheme.text4)
        et.setTextColor(AppTheme.text)
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        et.setPadding(dp(14), 0, dp(12), 0)
        et.gravity = Gravity.CENTER_VERTICAL
        et.isSingleLine = true
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { applyFilter(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        return et
    }

    private fun buildBreadcrumb(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(AppTheme.surface2)
        bar.setPadding(dp(14), dp(7), dp(14), dp(7))

        val d = dot(AppTheme.accent, 5)
        (d.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
        bar.addView(d)
        bar.addView(mkTv("Local library", 11, AppTheme.accent, true))

        val sp = View(this); sp.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        bar.addView(sp)

        countChip = pillChip("0 files", AppTheme.text4, AppTheme.elevated, AppTheme.border)
        bar.addView(countChip)
        return bar
    }

    private fun buildContentArea(): View {
        val frame = FrameLayout(this)
        frame.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)

        scroll = ScrollView(this)
        scroll.setBackgroundColor(AppTheme.bg)
        scroll.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        listBody = LinearLayout(this)
        listBody.orientation = LinearLayout.VERTICAL
        listBody.setPadding(dp(14), dp(12), dp(14), dp(100))
        scroll.addView(listBody)
        frame.addView(scroll)

        emptyState = LinearLayout(this)
        emptyState.orientation = LinearLayout.VERTICAL
        emptyState.gravity = Gravity.CENTER
        emptyState.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        emptyState.setPadding(dp(30), 0, dp(30), 0)
        emptyState.addView(mkTv("Your library is empty", 16, AppTheme.text2, true).also { it.gravity = Gravity.CENTER })
        emptyState.addView(mkVSpace(6))
        emptyState.addView(mkTv("Tap + to create a note or import .md files from storage.", 13, AppTheme.text4, false).also { it.gravity = Gravity.CENTER })
        emptyState.visibility = View.GONE
        frame.addView(emptyState)

        return frame
    }

    private fun buildFab(): View {
        val fab = mkTv("＋ New", 13, if (AppTheme.isDark) Color.BLACK else Color.WHITE, true)
        fab.gravity = Gravity.CENTER
        fab.setPadding(dp(18), dp(12), dp(20), dp(12))
        val bg = GradientDrawable()
        bg.setColor(AppTheme.accent)
        bg.cornerRadius = dp(24).toFloat()
        fab.background = bg
        val lp = FrameLayout.LayoutParams(WRAP, WRAP)
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.bottomMargin = dp(20); lp.rightMargin = dp(18)
        fab.layoutParams = lp
        fab.elevation = dp(6).toFloat()
        fab.setOnClickListener { showNewFileDialog() }
        return fab
    }

    // ── Data / list ──────────────────────────────────────────────────
    private fun refresh() {
        allFiles = applySort(MarkdownStore.listFiles())
        countChip.text = "${allFiles.size} file${if (allFiles.size == 1) "" else "s"}"
        renderList(allFiles)
    }

    private fun applySort(files: List<MdFile>): List<MdFile> = when (sortMode) {
        SortMode.NAME -> files.sortedBy { it.title.lowercase() }
        SortMode.MODIFIED -> files.sortedByDescending { it.file.lastModified() }
    }

    private fun showSortMenu() {
        val options = arrayOf("Name (A–Z)", "Last modified")
        val current = if (sortMode == SortMode.NAME) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(options, current) { dialog, which ->
                sortMode = if (which == 0) SortMode.NAME else SortMode.MODIFIED
                refresh()
                dialog.dismiss()
            }.show()
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) { renderList(allFiles); return }
        val filtered = allFiles.filter {
            it.title.lowercase().contains(q) || it.name.lowercase().contains(q) ||
                MarkdownStore.read(it.file).lowercase().contains(q)
        }
        renderList(filtered)
    }

    private fun renderList(items: List<MdFile>) {
        listBody.removeAllViews()
        emptyState.visibility = if (allFiles.isEmpty()) View.VISIBLE else View.GONE
        scroll.visibility = if (allFiles.isEmpty()) View.GONE else View.VISIBLE
        for (item in items) listBody.addView(buildFileCard(item))
        if (items.isEmpty() && allFiles.isNotEmpty()) {
            listBody.addView(mkTv("No matches.", 13, AppTheme.text4, false).also {
                val lp = LinearLayout.LayoutParams(MATCH, WRAP); lp.topMargin = dp(20); it.layoutParams = lp
                it.gravity = Gravity.CENTER
            })
        }
    }

    private fun buildFileCard(item: MdFile): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(14), dp(14), dp(14))
        row.background = roundRect(AppTheme.surface, dp(12), AppTheme.border, dp(1))
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.bottomMargin = dp(10)
        row.layoutParams = rlp

        val textCol = LinearLayout(this)
        textCol.orientation = LinearLayout.VERTICAL
        textCol.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        textCol.addView(mkTv(item.title, 14, AppTheme.text, true))
        textCol.addView(mkVSpace(4))
        val meta = LinearLayout(this)
        meta.orientation = LinearLayout.HORIZONTAL
        meta.addView(pillChip(item.badge, AppTheme.accent, AppTheme.accentBg, AppTheme.accentDim))
        meta.addView(mkHSpace(6))
        val fname = mkTv(item.name, 10, AppTheme.text4, false)
        fname.typeface = Typeface.MONOSPACE
        meta.addView(fname)
        textCol.addView(meta)
        row.addView(textCol)

        row.addView(mkHSpace(8))
        row.addView(iconBtn("✎") { openEditor(item.file) })
        row.addView(mkHSpace(6))
        row.addView(iconBtn("⋮") { showFileMenu(item) })

        row.setOnClickListener { openReader(item.file) }
        return row
    }

    private fun showFileMenu(item: MdFile) {
        val options = arrayOf("Open", "Edit", "Rename", "Share / Export", "Delete")
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openReader(item.file)
                    1 -> openEditor(item.file)
                    2 -> showRenameDialog(item)
                    3 -> shareFile(item.file)
                    4 -> confirmDelete(item)
                }
            }.show()
    }

    private fun showRenameDialog(item: MdFile) {
        val input = EditText(this)
        input.setText(item.file.nameWithoutExtension)
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                MarkdownStore.rename(item.file, input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(item: MdFile) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${item.title}\"?")
            .setMessage("This removes the file from local storage. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                MarkdownStore.delete(item.file)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareFile(file: java.io.File) {
        val uri = MarkdownStore.exportUri(this, file)
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/markdown"
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    private fun showNewFileDialog() {
        val input = EditText(this)
        input.hint = "Document title"
        AlertDialog.Builder(this)
            .setTitle("New markdown file")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val title = input.text.toString().ifBlank { "Untitled" }
                val f = MarkdownStore.createNew(title)
                refresh()
                openEditor(f)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportPicker() {
        val options = arrayOf("New file", "Import .md from device")
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                if (which == 0) showNewFileDialog() else importLauncher.launch(arrayOf("text/markdown", "text/plain", "text/*"))
            }.show()
    }

    private fun openReader(file: java.io.File) {
        val i = Intent(this, ReaderActivity::class.java)
        i.putExtra("path", file.absolutePath)
        startActivity(i)
    }

    private fun openEditor(file: java.io.File) {
        val i = Intent(this, EditorActivity::class.java)
        i.putExtra("path", file.absolutePath)
        startActivity(i)
    }

    // ── Theme / font ─────────────────────────────────────────────────
    private fun toggleTheme() {
        AppTheme.toggleTheme()
        applyChrome()
        setContentView(buildRoot())
        refresh()
    }

    private fun cycleFontSize() {
        val level = AppTheme.cycleFontSize()
        val labels = arrayOf("Small", "Normal", "Large")
        toast("Font: ${labels[level]}")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
