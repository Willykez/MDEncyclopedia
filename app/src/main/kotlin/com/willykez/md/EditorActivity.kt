package com.willykez.md

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var editText: EditText
    private lateinit var previewScroll: ScrollView
    private lateinit var previewBody: LinearLayout
    private lateinit var editWrap: View
    private lateinit var toolbar: HorizontalScrollView
    private lateinit var saveBtn: TextView
    private lateinit var titleTv: TextView

    private var previewMode = false
    private var dirty = false

    // Auto-continue list markers on Enter needs to mutate the Editable from
    // inside its own TextWatcher — this flag stops that mutation re-triggering itself.
    private var autoContinuing = false

    // Lightweight manual undo/redo: debounced snapshots + one snapshot per toolbar action.
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var isRestoringHistory = false
    private var lastSnapshot = ""
    private val historyHandler = Handler(Looper.getMainLooper())
    private var historyRunnable: Runnable? = null
    private var lastFindIndex = -1

    private val BULLET_RE = Regex("^(\\s*)([-*+])\\s(\\[[ xX]]\\s)?(.*)$")
    private val NUM_RE = Regex("^(\\s*)(\\d+)\\.\\s(.*)$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        AppTheme.init(this)
        MarkdownStore.init(this)

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        file = File(path)

        window.statusBarColor = AppTheme.surface
        window.navigationBarColor = AppTheme.bg
        setContentView(buildRoot())
        val initial = MarkdownStore.read(file)
        editText.setText(initial)
        lastSnapshot = initial
        dirty = false
    }

    override fun onBackPressed() {
        if (dirty) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("Save before leaving?")
                .setPositiveButton("Save") { _, _ -> save(); super.onBackPressed() }
                .setNegativeButton("Discard") { _, _ -> super.onBackPressed() }
                .setNeutralButton("Cancel", null)
                .show()
        } else super.onBackPressed()
    }

    // ── Layout ───────────────────────────────────────────────────────
    private fun buildRoot(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(AppTheme.bg)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        main.addView(buildTopBar())
        main.addView(buildFormatToolbar())

        val frame = FrameLayout(this)
        frame.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)

        editWrap = buildEditor()
        frame.addView(editWrap)

        previewScroll = ScrollView(this)
        previewScroll.setBackgroundColor(AppTheme.bg)
        previewScroll.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        previewBody = LinearLayout(this)
        previewBody.orientation = LinearLayout.VERTICAL
        previewBody.setPadding(dp(16), dp(14), dp(16), dp(90))
        previewScroll.addView(previewBody)
        previewScroll.visibility = View.GONE
        frame.addView(previewScroll)

        main.addView(frame)
        root.addView(main)
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(AppTheme.surface)
        bar.minimumHeight = dp(56)
        bar.setPadding(dp(6), 0, dp(10), 0)

        bar.addView(iconBtn("←") { onBackPressed() })
        bar.addView(mkHSpace(6))

        titleTv = mkTv(file.nameWithoutExtension, 14, AppTheme.text, true)
        titleTv.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
        titleTv.maxLines = 1
        bar.addView(titleTv)

        val toggle = pillChip("PREVIEW", AppTheme.text3, AppTheme.elevated, AppTheme.border)
        toggle.setOnClickListener {
            previewMode = !previewMode
            togglePreview(toggle)
        }
        toggle.tag = "toggle"
        bar.addView(toggle)
        bar.addView(mkHSpace(6))

        bar.addView(iconBtn("🔍") { showFindReplace() })
        bar.addView(mkHSpace(6))

        bar.addView(iconBtn("⛶") {
            // Full view: save then jump to the distraction-free reader.
            save()
            val i = Intent(this, ReaderActivity::class.java)
            i.putExtra("path", file.absolutePath)
            startActivity(i)
        })
        bar.addView(mkHSpace(6))

        saveBtn = pillChip("SAVE", if (AppTheme.isDark) Color.BLACK else Color.WHITE, AppTheme.accent, AppTheme.accent)
        saveBtn.setOnClickListener { save() }
        bar.addView(saveBtn)
        return bar
    }

    private fun togglePreview(toggle: TextView) {
        if (previewMode) {
            renderPreview()
            editWrap.visibility = View.GONE
            previewScroll.visibility = View.VISIBLE
            toggle.text = "EDIT"
            toolbar.visibility = View.GONE
        } else {
            editWrap.visibility = View.VISIBLE
            previewScroll.visibility = View.GONE
            toggle.text = "PREVIEW"
            toolbar.visibility = View.VISIBLE
        }
    }

    private fun renderPreview() {
        previewBody.removeAllViews()
        Markdown.render(this, previewBody, editText.text.toString(), baseDir = file.parentFile)
    }

    private fun buildEditor(): View {
        val wrap = FrameLayout(this)
        wrap.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        val sv = ScrollView(this)
        sv.setBackgroundColor(AppTheme.bg)
        sv.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)

        editText = EditText(this)
        editText.setTextColor(AppTheme.text2)
        editText.setHintTextColor(AppTheme.text4)
        editText.hint = "Start writing markdown…"
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppTheme.fontBaseSp().toFloat())
        editText.typeface = Typeface.MONOSPACE
        editText.gravity = Gravity.TOP or Gravity.START
        editText.setPadding(dp(16), dp(14), dp(16), dp(120))
        editText.background = null
        editText.setLineSpacing(dp(4).toFloat(), 1f)
        editText.layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        editText.isVerticalScrollBarEnabled = true
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                dirty = true
                if (autoContinuing || isRestoringHistory || s == null) return
                if (count == 1 && start < s.length && s[start] == '\n') {
                    handleAutoContinue(s, start)
                }
            }
            override fun afterTextChanged(s: Editable?) {
                if (isRestoringHistory || autoContinuing) return
                historyRunnable?.let { historyHandler.removeCallbacks(it) }
                val r = Runnable { pushHistory() }
                historyRunnable = r
                historyHandler.postDelayed(r, 600)
            }
        })

        sv.addView(editText)
        wrap.addView(sv)
        return wrap
    }

    // ── Auto-continue list markers on Enter ─────────────────────────────
    private fun handleAutoContinue(fullText: CharSequence, newlineIndex: Int) {
        val text = fullText.toString()
        var lineStart = newlineIndex
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val completedLine = text.substring(lineStart, newlineIndex)

        val bullet = BULLET_RE.matchEntire(completedLine)
        val numbered = NUM_RE.matchEntire(completedLine)

        val (marker, terminate) = when {
            bullet != null -> {
                val indent = bullet.groupValues[1]
                val bulletChar = bullet.groupValues[2]
                val checkbox = bullet.groupValues[3]
                val content = bullet.groupValues[4]
                if (content.isBlank()) "" to true
                else (if (checkbox.isNotEmpty()) "$indent$bulletChar [ ] " else "$indent$bulletChar ") to false
            }
            numbered != null -> {
                val indent = numbered.groupValues[1]
                val num = numbered.groupValues[2]
                val content = numbered.groupValues[3]
                if (content.isBlank()) "" to true
                else "$indent${(num.toIntOrNull() ?: 0) + 1}. " to false
            }
            else -> return
        }

        autoContinuing = true
        if (terminate) {
            // Enter on an empty list item ends the list instead of continuing it.
            editText.text.delete(lineStart, newlineIndex)
            editText.setSelection((lineStart + 1).coerceAtMost(editText.text.length))
        } else {
            val insertPos = newlineIndex + 1
            editText.text.insert(insertPos, marker)
            editText.setSelection((insertPos + marker.length).coerceAtMost(editText.text.length))
        }
        autoContinuing = false
    }

    // ── Undo / redo ──────────────────────────────────────────────────────
    private fun commitPendingHistory() {
        historyRunnable?.let { historyHandler.removeCallbacks(it) }
        pushHistory()
    }

    private fun pushHistory() {
        val current = editText.text.toString()
        if (current == lastSnapshot) return
        undoStack.addLast(lastSnapshot)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        lastSnapshot = current
    }

    private fun performUndo() {
        historyRunnable?.let { historyHandler.removeCallbacks(it) }
        pushHistory()
        if (undoStack.isEmpty()) { toast("Nothing to undo"); return }
        val current = editText.text.toString()
        redoStack.addLast(current)
        val prev = undoStack.removeLast()
        isRestoringHistory = true
        editText.setText(prev)
        editText.setSelection(prev.length)
        isRestoringHistory = false
        lastSnapshot = prev
        dirty = true
    }

    private fun performRedo() {
        if (redoStack.isEmpty()) { toast("Nothing to redo"); return }
        val current = editText.text.toString()
        undoStack.addLast(current)
        val next = redoStack.removeLast()
        isRestoringHistory = true
        editText.setText(next)
        editText.setSelection(next.length)
        isRestoringHistory = false
        lastSnapshot = next
        dirty = true
    }

    // ── Find & replace ───────────────────────────────────────────────────
    private fun showFindReplace() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(24), dp(8), dp(24), dp(0))
        val findEt = EditText(this); findEt.hint = "Find"
        val replaceEt = EditText(this); replaceEt.hint = "Replace with"
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(MATCH, dp(10))
        container.addView(findEt)
        container.addView(spacer)
        container.addView(replaceEt)
        lastFindIndex = -1

        AlertDialog.Builder(this)
            .setTitle("Find & replace")
            .setView(container)
            .setPositiveButton("Replace all") { _, _ ->
                val find = findEt.text.toString()
                if (find.isEmpty()) return@setPositiveButton
                commitPendingHistory()
                val replaced = editText.text.toString().replace(find, replaceEt.text.toString())
                editText.setText(replaced)
                editText.setSelection(replaced.length)
                lastSnapshot = replaced
                dirty = true
                toast("Replaced all occurrences")
            }
            .setNeutralButton("Find next") { _, _ ->
                val find = findEt.text.toString()
                if (find.isNotEmpty()) findNext(find)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun findNext(query: String) {
        val text = editText.text.toString()
        val from = (lastFindIndex + 1).coerceIn(0, text.length)
        var idx = text.indexOf(query, from, ignoreCase = true)
        if (idx < 0) idx = text.indexOf(query, 0, ignoreCase = true) // wrap around
        if (idx < 0) { toast("Not found"); lastFindIndex = -1; return }
        lastFindIndex = idx
        editText.requestFocus()
        editText.setSelection(idx, idx + query.length)
        editText.post { editText.bringPointIntoView(idx) }
    }

    // ── Formatting toolbar ───────────────────────────────────────────
    private fun buildFormatToolbar(): View {
        toolbar = HorizontalScrollView(this)
        toolbar.isHorizontalScrollBarEnabled = false
        toolbar.setBackgroundColor(AppTheme.surface2)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(10), dp(8), dp(10), dp(8))

        fun tool(label: String, action: () -> Unit) {
            val b = mkTv(label, 13, AppTheme.text2, false)
            b.setPadding(dp(10), dp(6), dp(10), dp(6))
            b.background = roundRect(AppTheme.elevated, dp(8), AppTheme.border, dp(1))
            val lp = LinearLayout.LayoutParams(WRAP, WRAP); lp.rightMargin = dp(6)
            b.layoutParams = lp
            b.setOnClickListener { action() }
            row.addView(b)
        }

        tool("H1") { wrapLinePrefix("# ") }
        tool("H2") { wrapLinePrefix("## ") }
        tool("B") { wrapSelection("**", "**") }
        tool("I") { wrapSelection("*", "*") }
        tool("S") { wrapSelection("~~", "~~") }
        tool("Code") { wrapSelection("`", "`") }
        tool("Block") { insertAtCursor("\n```kotlin\n", "\n```\n") }
        tool("Link") { wrapSelection("[", "](https://)") }
        tool("•") { wrapLinePrefix("- ") }
        tool("1.") { wrapLinePrefix("1. ") }
        tool("☐") { wrapLinePrefix("- [ ] ") }
        tool("Quote") { wrapLinePrefix("> ") }
        tool("Table") { insertAtCursor("\n| Col A | Col B |\n|---|---|\n| val | val |\n", "") }
        tool("HR") { insertAtCursor("\n---\n", "") }
        tool("↺") { performUndo() }
        tool("↻") { performRedo() }

        toolbar.addView(row)
        return toolbar
    }

    private fun wrapSelection(prefix: String, suffix: String) {
        commitPendingHistory()
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(start)
        val text = editText.text
        text.replace(end, end, suffix)
        text.replace(start, start, prefix)
        editText.setSelection((start + prefix.length).coerceAtMost(text.length), (end + prefix.length).coerceAtMost(text.length))
    }

    private fun insertAtCursor(before: String, after: String) {
        commitPendingHistory()
        val pos = editText.selectionStart.coerceAtLeast(0)
        val text = editText.text
        text.insert(pos, before + after)
        editText.setSelection((pos + before.length).coerceAtMost(text.length))
    }

    private fun wrapLinePrefix(prefix: String) {
        commitPendingHistory()
        val pos = editText.selectionStart.coerceAtLeast(0)
        val text = editText.text.toString()
        var lineStart = pos
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        editText.text.insert(lineStart, prefix)
        editText.setSelection((pos + prefix.length).coerceAtMost(editText.text.length))
    }

    // ── Save ─────────────────────────────────────────────────────────
    private fun save() {
        MarkdownStore.write(file, editText.text.toString())
        dirty = false
        toast("Saved")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
