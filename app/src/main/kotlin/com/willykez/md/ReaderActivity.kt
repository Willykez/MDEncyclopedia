package com.willykez.md

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var progressLine: LinearLayout
    private lateinit var progressWrap: FrameLayout
    private lateinit var fab: View
    private lateinit var titleTv: TextView

    private val headings = mutableListOf<Triple<Int, String, View>>()

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
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) load()
    }

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(AppTheme.bg)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        main.addView(buildTopBar())
        main.addView(buildProgressBar())

        val frame = FrameLayout(this)
        frame.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        scroll = ScrollView(this)
        scroll.setBackgroundColor(AppTheme.bg)
        scroll.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(14), dp(16), dp(90))
        scroll.addView(body)
        frame.addView(scroll)
        main.addView(frame)
        root.addView(main)

        scroll.viewTreeObserver.addOnScrollChangedListener {
            val scrollY = scroll.scrollY
            val totalH = body.height - scroll.height
            val pct = if (totalH > 0) (scrollY * 100f / totalH).toInt() else 0
            updateProgress(pct)
            fab.visibility = if (scrollY > dp(300)) View.VISIBLE else View.GONE
        }

        fab = buildFab()
        val fabLp = FrameLayout.LayoutParams(dp(46), dp(46))
        fabLp.gravity = Gravity.BOTTOM or Gravity.END
        fabLp.bottomMargin = dp(20); fabLp.rightMargin = dp(18)
        fab.layoutParams = fabLp
        fab.visibility = View.GONE
        root.addView(fab)

        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(AppTheme.surface)
        bar.minimumHeight = dp(56)
        bar.setPadding(dp(6), 0, dp(10), 0)

        bar.addView(iconBtn("←") { finish() })
        bar.addView(mkHSpace(6))

        titleTv = mkTv(file.nameWithoutExtension, 14, AppTheme.text, true)
        titleTv.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
        titleTv.maxLines = 1
        bar.addView(titleTv)

        bar.addView(iconBtn("Aa") { cycleFontSize() })
        bar.addView(mkHSpace(4))
        bar.addView(iconBtn("☰") { showToc() })
        bar.addView(mkHSpace(4))
        bar.addView(iconBtn(if (AppTheme.isDark) "☀" else "🌙") { toggleTheme() })
        bar.addView(mkHSpace(4))
        bar.addView(iconBtn("✎") {
            val i = Intent(this, EditorActivity::class.java)
            i.putExtra("path", file.absolutePath)
            startActivity(i)
        })
        bar.addView(mkHSpace(4))
        bar.addView(iconBtn("⇪") { shareFile() })
        return bar
    }

    private fun buildProgressBar(): View {
        progressWrap = FrameLayout(this)
        progressWrap.layoutParams = LinearLayout.LayoutParams(MATCH, dp(3))
        progressWrap.setBackgroundColor(AppTheme.border)
        progressLine = LinearLayout(this)
        progressLine.setBackgroundColor(AppTheme.accent)
        progressLine.layoutParams = FrameLayout.LayoutParams(0, MATCH)
        progressWrap.addView(progressLine)
        return progressWrap
    }

    private fun updateProgress(percent: Int) {
        val lp = progressLine.layoutParams ?: return
        progressWrap.post {
            val total = (progressLine.parent as? View)?.width ?: 0
            lp.width = (total * percent / 100f).toInt()
            progressLine.layoutParams = lp
        }
    }

    private fun buildFab(): View {
        val f = TextView(this)
        f.text = "↑"
        f.setTextColor(if (AppTheme.isDark) Color.BLACK else Color.WHITE)
        f.gravity = Gravity.CENTER
        f.textSize = 18f
        val bg = GradientDrawable(); bg.setColor(AppTheme.accent); bg.shape = GradientDrawable.OVAL
        f.background = bg
        f.setOnClickListener { scroll.smoothScrollTo(0, 0) }
        return f
    }

    private fun load() {
        val md = MarkdownStore.read(file)
        body.removeAllViews()
        headings.clear()

        val words = md.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val minutes = (words / 200).coerceAtLeast(1)
        val meta = "$words words · $minutes min read"

        body.addView(
            Markdown.buildDocHeaderCard(
                this, file.nameWithoutExtension.replace('_', ' '), "DOC", file.name, meta
            )
        )
        body.addView(mkVSpace(12))
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        Markdown.render(
            this, inner, md,
            baseDir = file.parentFile,
            onCopyToast = { toast(it) },
            onHeading = { level, text, view -> headings.add(Triple(level, text, view)) }
        )
        body.addView(inner)
        updateProgress(0)
    }

    // ── Table of contents ───────────────────────────────────────────────
    private fun showToc() {
        if (headings.isEmpty()) {
            toast("No headings in this document")
            return
        }
        val labels = headings.map { (level, text, _) -> "  ".repeat((level - 1).coerceAtLeast(0)) + text }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Contents")
            .setItems(labels) { _, which -> scrollToHeading(which) }
            .show()
    }

    private fun scrollToHeading(index: Int) {
        val target = headings.getOrNull(index)?.third ?: return
        scroll.post {
            val y = offsetYRelativeTo(target, body)
            scroll.smoothScrollTo(0, (y - dp(12)).coerceAtLeast(0))
        }
    }

    private fun offsetYRelativeTo(view: View, root: View): Int {
        var v: View = view
        var y = 0
        while (v !== root) {
            y += v.top
            val parent = v.parent as? View ?: break
            v = parent
        }
        return y
    }

    private fun shareFile() {
        val uri = MarkdownStore.exportUri(this, file)
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/markdown"
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    private fun toggleTheme() {
        AppTheme.toggleTheme()
        window.statusBarColor = AppTheme.surface
        window.navigationBarColor = AppTheme.bg
        setContentView(buildRoot())
        load()
    }

    private fun cycleFontSize() {
        val level = AppTheme.cycleFontSize()
        val labels = arrayOf("Small", "Normal", "Large")
        load()
        toast("Font: ${labels[level]}")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
