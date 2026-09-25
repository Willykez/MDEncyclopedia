package com.willykez.md

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.regex.Pattern

/**
 * Upgraded markdown renderer.
 *
 * New vs. the original Sketchware version:
 *  - inline styling is real spans (bold/italic/strike/inline-code/links),
 *    not stripped-out plain text — links are tappable, code is monospaced.
 *  - task list checkboxes: `- [ ]` / `- [x]`
 *  - nested bullet / numbered lists (indentation-aware, per-level counters)
 *  - `\*` style escapes and bare `https://` autolinking
 *  - table column alignment from `:---`, `---:`, `:---:` separators
 *  - alternate HR markers (`---`, `***`, `___`) and closing-hash headings
 *  - real images, GitHub-style: a block-level `![]()` on its own line loads
 *    straight off local storage, or over HTTP(S) with a loading placeholder
 *    and an in-memory cache; a local image inline in a paragraph renders as
 *    a true inline ImageSpan, and a remote one inline stays a tap-to-open
 *    chip while it downloads in the background for next render
 *  - a heading callback so a screen can build a tap-to-jump table of
 *    contents without re-parsing the document itself
 *  - same visual language (macOS-style code cards, accent blockquotes,
 *    striped tables, module header card) as the original app.
 */
object Markdown {

    // ── Inline rendering ────────────────────────────────────────────────
    private val BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+?)\\*(?!\\*)")
    private val STRIKE = Pattern.compile("~~(.+?)~~")
    private val CODE = Pattern.compile("`([^`]+)`")
    private val LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)")
    private val IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)")
    private val CHECK_DONE = Pattern.compile("^\\[[xX]]\\s+")
    private val CHECK_OPEN = Pattern.compile("^\\[ ]\\s+")
    private val ESCAPE = Pattern.compile("^\\\\([\\\\`*_{}\\[\\]()#+\\-.!~>])")
    private val AUTOLINK = Pattern.compile("^https?://[^\\s<>\"')]+")
    private val IMAGE_LINE = Regex("^!\\[([^\\]]*)]\\(([^)]+)\\)\\s*$")
    private val TRAILING_PUNCT = ".,;:!?)]}'\""

    // Small in-memory cache so re-rendering (theme toggle, font size, preview
    // refresh) doesn't re-download the same remote image every time.
    private val remoteImageCache = android.util.LruCache<String, android.graphics.Bitmap>(40)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun downloadBitmap(urlStr: String): android.graphics.Bitmap? = runCatching {
        val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) { conn.disconnect(); return@runCatching null }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        if (bytes.isEmpty() || bytes.size > 15 * 1024 * 1024) return@runCatching null

        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val targetWidthPx = 1080
        while (bounds.outWidth / sample > targetWidthPx * 2) sample *= 2

        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    private fun localBitmap(path: String, baseDir: File?): android.graphics.Bitmap? = runCatching {
        val f = if (path.startsWith("/")) File(path) else File(baseDir, path)
        if (!f.exists()) null else {
            val opts = BitmapFactory.Options()
            opts.inSampleSize = 2
            BitmapFactory.decodeFile(f.absolutePath, opts)
        }
    }.getOrNull()

    /** Wraps a bitmap as an inline ImageSpan, capped to a readable width so it sits in the text flow. */
    private fun inlineImageSpan(context: Context, bitmap: android.graphics.Bitmap): ImageSpan {
        val maxWidthPx = context.dp(240)
        val scale = if (bitmap.width > maxWidthPx) maxWidthPx.toFloat() / bitmap.width else 1f
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val drawable = android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
        drawable.setBounds(0, 0, w, h)
        return ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
    }

    /** Tokenises one line of inline markdown into a styled Spannable. */
    fun renderInline(context: Context, raw: String, baseDir: File? = null, hostView: TextView? = null): SpannableStringBuilder {
        val text = raw

        data class Span(val start: Int, val end: Int, val what: Any)
        val out = StringBuilder()
        val spans = mutableListOf<Span>()

        // Simple sequential scan handling the inline forms; not a full
        // CommonMark parser, but covers everything the encyclopedia content
        // and user notes actually use.
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val mEscape = ESCAPE.matcher(rest)
            val mImage = IMAGE.matcher(rest)
            val mLink = LINK.matcher(rest)
            val mAutolink = AUTOLINK.matcher(rest)
            val mBold = BOLD.matcher(rest)
            val mCode = CODE.matcher(rest)
            val mStrike = STRIKE.matcher(rest)
            val mItalic = ITALIC.matcher(rest)

            when {
                mEscape.lookingAt() -> {
                    out.append(mEscape.group(1))
                    i += mEscape.end()
                }
                mImage.lookingAt() -> {
                    val alt = mImage.group(1).ifEmpty { "image" }
                    val path = mImage.group(2)
                    val start = out.length
                    if (path.startsWith("http://") || path.startsWith("https://")) {
                        val cached = remoteImageCache.get(path)
                        if (cached != null) {
                            out.append(" ")
                            spans.add(Span(start, out.length, inlineImageSpan(context, cached)))
                        } else {
                            out.append("🖼 $alt")
                            spans.add(Span(start, out.length, object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    runCatching { widget.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(path))) }
                                }
                            }))
                            spans.add(Span(start, out.length, ForegroundColorSpan(AppTheme.codeText)))
                            spans.add(Span(start, out.length, UnderlineSpan()))
                            // Fetch in the background so the bitmap is cached — the chip
                            // itself only turns into a real inline image on the *next*
                            // render pass (theme/font toggle, reopening the doc, preview
                            // refresh), since a TextView's Spannable can't be swapped
                            // in place once laid out.
                            Thread {
                                val bmp = downloadBitmap(path)
                                if (bmp != null) remoteImageCache.put(path, bmp)
                            }.start()
                        }
                    } else {
                        val bmp = localBitmap(path, baseDir)
                        if (bmp != null) {
                            out.append(" ")
                            spans.add(Span(start, out.length, inlineImageSpan(context, bmp)))
                        } else {
                            out.append("🖼 $alt")
                            spans.add(Span(start, out.length, ForegroundColorSpan(AppTheme.C_RED)))
                        }
                    }
                    i += mImage.end()
                }
                mLink.lookingAt() -> {
                    val label = mLink.group(1); val url = mLink.group(2)
                    val start = out.length
                    out.append(label)
                    spans.add(Span(start, out.length, object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            runCatching {
                                widget.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    }))
                    spans.add(Span(start, out.length, ForegroundColorSpan(AppTheme.codeText)))
                    spans.add(Span(start, out.length, UnderlineSpan()))
                    i += mLink.end()
                }
                mCode.lookingAt() -> {
                    val start = out.length
                    out.append(mCode.group(1))
                    spans.add(Span(start, out.length, ForegroundColorSpan(AppTheme.codeText)))
                    spans.add(Span(start, out.length, TypefaceSpan("monospace")))
                    spans.add(Span(start, out.length, BackgroundColorSpan(AppTheme.codeBg)))
                    i += mCode.end()
                }
                mAutolink.lookingAt() -> {
                    var url = mAutolink.group()
                    while (url.length > 1 && TRAILING_PUNCT.contains(url.last())) url = url.dropLast(1)
                    val start = out.length
                    out.append(url)
                    spans.add(Span(start, out.length, object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            runCatching {
                                widget.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    }))
                    spans.add(Span(start, out.length, ForegroundColorSpan(AppTheme.codeText)))
                    spans.add(Span(start, out.length, UnderlineSpan()))
                    i += url.length
                }
                mBold.lookingAt() -> {
                    val start = out.length
                    out.append(mBold.group(1))
                    spans.add(Span(start, out.length, StyleSpan(Typeface.BOLD)))
                    spans.add(Span(start, out.length, ForegroundColorSpan(AppTheme.text)))
                    i += mBold.end()
                }
                mStrike.lookingAt() -> {
                    val start = out.length
                    out.append(mStrike.group(1))
                    spans.add(Span(start, out.length, StrikethroughSpan()))
                    spans.add(Span(start, out.length, ForegroundColorSpan(AppTheme.text4)))
                    i += mStrike.end()
                }
                mItalic.lookingAt() -> {
                    val start = out.length
                    out.append(mItalic.group(1))
                    spans.add(Span(start, out.length, StyleSpan(Typeface.ITALIC)))
                    i += mItalic.end()
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }

        val ssb = SpannableStringBuilder(out.toString())
        for (s in spans) ssb.setSpan(s.what, s.start, s.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ssb
    }

    fun stripInline(s: String): String = s
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<!\\*)\\*([^*]+?)\\*(?!\\*)"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("~~(.+?)~~"), "$1")

    // ── Block rendering ──────────────────────────────────────────────────
    /**
     * Renders full markdown [md] into [container], one block-view per element.
     * [baseDir] resolves relative local image paths (the document's own
     * folder). [onHeading] fires once per heading as it's built — level,
     * plain text, and the View itself — so a screen can assemble a
     * tap-to-jump table of contents without a second parse pass.
     */
    fun render(
        context: Context,
        container: LinearLayout,
        md: String,
        baseDir: File? = null,
        onCopyToast: ((String) -> Unit)? = null,
        onHeading: ((level: Int, text: String, view: View) -> Unit)? = null
    ) {
        container.removeAllViews()
        val lines = md.replace("\r\n", "\n").split("\n")
        val base = AppTheme.fontBaseSp()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val hashCount = line.takeWhile { it == '#' }.length
            val isHeadingLine = hashCount in 1..4 && line.length > hashCount && line[hashCount] == ' '

            when {
                line.startsWith("```") -> {
                    val lang = line.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        code.append(lines[i]).append('\n'); i++
                    }
                    container.addView(buildCodeBlock(context, lang, code.toString(), onCopyToast))
                }
                IMAGE_LINE.matches(line.trim()) -> {
                    val m = IMAGE_LINE.find(line.trim())!!
                    container.addView(buildImageBlock(context, m.groupValues[1], m.groupValues[2], baseDir))
                }
                isHeadingLine -> {
                    val raw = stripTrailingHashes(line.substring(hashCount + 1))
                    val hv = buildHeading(context, raw, hashCount, base)
                    container.addView(hv)
                    onHeading?.invoke(hashCount, stripInline(raw), hv)
                }
                isHR(line) -> container.addView(buildHR(context))
                line.startsWith("> ") -> {
                    val bq = StringBuilder(line.removePrefix("> "))
                    while (i + 1 < lines.size && lines[i + 1].startsWith("> ")) {
                        i++; bq.append('\n').append(lines[i].removePrefix("> "))
                    }
                    container.addView(buildBlockquote(context, bq.toString(), base, baseDir))
                }
                line.contains("|") && i + 1 < lines.size && lines[i + 1].contains("---") -> {
                    val aligns = parseAlignment(lines[i + 1])
                    val rows = mutableListOf(parseRow(line))
                    i++ // skip the separator row
                    while (i + 1 < lines.size && lines[i + 1].contains("|")) {
                        i++; rows.add(parseRow(lines[i]))
                    }
                    container.addView(buildTable(context, rows, base, aligns, baseDir))
                }
                isBullet(line) -> {
                    val items = mutableListOf(line)
                    while (i + 1 < lines.size && isBullet(lines[i + 1])) { i++; items.add(lines[i]) }
                    container.addView(buildBulletList(context, items, base, baseDir))
                }
                line.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                    val items = mutableListOf(line)
                    while (i + 1 < lines.size && lines[i + 1].trimStart().matches(Regex("^\\d+\\.\\s.*"))) {
                        i++; items.add(lines[i])
                    }
                    container.addView(buildNumberedList(context, items, base, baseDir))
                }
                line.isBlank() -> container.addView(context.mkVSpace(6))
                else -> container.addView(buildParagraph(context, line, base, baseDir))
            }
            i++
        }
    }

    private fun isBullet(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")
    }

    private fun isHR(line: String): Boolean {
        val t = line.trim().replace(" ", "")
        return t.length >= 3 && (t.all { it == '-' } || t.all { it == '=' } ||
                t.all { it == '*' } || t.all { it == '_' })
    }

    private fun stripTrailingHashes(s: String): String {
        var t = s.trimEnd()
        while (t.endsWith("#")) t = t.dropLast(1).trimEnd()
        return t
    }

    private fun parseAlignment(sepLine: String): List<Int> =
        sepLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }.map { cell ->
            val left = cell.startsWith(":")
            val right = cell.endsWith(":")
            when {
                left && right -> Gravity.CENTER
                right -> Gravity.END
                else -> Gravity.START
            }
        }

    private fun indentLevel(line: String): Int {
        var spaces = 0
        for (c in line) { if (c == ' ') spaces++ else break }
        return (spaces / 2).coerceAtMost(4)
    }

    // ── Module/document header card (kept from original design) ─────────
    fun buildDocHeaderCard(context: Context, title: String, badge: String, filePath: String, meta: String? = null): View {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        card.background = roundRect(AppTheme.surface, context.dp(12), AppTheme.border2, context.dp(1))

        val topRow = LinearLayout(context)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL
        val badgeTv = context.mkTv(badge, 10, AppTheme.accent, false)
        badgeTv.setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(3))
        badgeTv.background = roundRect(AppTheme.accentBg, context.dp(6), AppTheme.accentDim, context.dp(1))
        topRow.addView(badgeTv)
        if (meta != null) {
            topRow.addView(context.mkHSpace(8))
            topRow.addView(context.mkTv(meta, 10, AppTheme.text4, false))
        }
        card.addView(topRow)
        card.addView(context.mkVSpace(10))

        card.addView(context.mkTv(title, 17, AppTheme.text, true))
        card.addView(context.mkVSpace(4))
        val fname = context.mkTv(filePath, 11, AppTheme.text4, false)
        fname.typeface = Typeface.MONOSPACE
        card.addView(fname)
        return card
    }

    // ── Local image block ────────────────────────────────────────────────
    private fun buildImageBlock(context: Context, alt: String, path: String, baseDir: File?): View {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = roundRect(AppTheme.surface, context.dp(10), AppTheme.border2, context.dp(1))
        card.clipToOutline = true
        val clp = LinearLayout.LayoutParams(MATCH, WRAP)
        clp.topMargin = context.dp(6); clp.bottomMargin = context.dp(6)
        card.layoutParams = clp

        if (path.startsWith("http://") || path.startsWith("https://")) {
            val cached = remoteImageCache.get(path)
            if (cached != null) {
                addBitmapToCard(context, card, cached, alt)
                return card
            }

            val placeholder = FrameLayout(context)
            placeholder.layoutParams = LinearLayout.LayoutParams(MATCH, context.dp(160))
            placeholder.setBackgroundColor(AppTheme.elevated)
            val loadingTv = context.mkTv("🖼  Loading image…", 12, AppTheme.text3, false)
            val llp = FrameLayout.LayoutParams(WRAP, WRAP); llp.gravity = Gravity.CENTER
            loadingTv.layoutParams = llp
            placeholder.addView(loadingTv)
            card.addView(placeholder)

            Thread {
                val bmp = downloadBitmap(path)
                mainHandler.post {
                    card.removeView(placeholder)
                    if (bmp != null) {
                        remoteImageCache.put(path, bmp)
                        addBitmapToCard(context, card, bmp, alt)
                    } else {
                        val chip = context.mkTv("🖼  Couldn't load $alt  ·  tap to open", 12, AppTheme.C_RED, false)
                        chip.setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
                        chip.setOnClickListener {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(path))) }
                        }
                        card.addView(chip)
                    }
                }
            }.start()
            return card
        }

        val bitmap = localBitmap(path, baseDir)
        if (bitmap == null) {
            val chip = context.mkTv("🖼  Couldn't load: $alt", 12, AppTheme.C_RED, false)
            chip.setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
            card.addView(chip)
            return card
        }
        addBitmapToCard(context, card, bitmap, alt)
        return card
    }

    private fun addBitmapToCard(context: Context, card: LinearLayout, bitmap: android.graphics.Bitmap, alt: String) {
        val iv = ImageView(context)
        iv.setImageBitmap(bitmap)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.adjustViewBounds = true
        iv.layoutParams = LinearLayout.LayoutParams(MATCH, context.dp(220))
        card.addView(iv)

        if (alt.isNotBlank()) {
            val caption = context.mkTv(alt, 11, AppTheme.text3, false)
            caption.gravity = Gravity.CENTER
            caption.setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(10))
            card.addView(caption)
        }
    }

    private fun buildHeading(context: Context, text: String, level: Int, base: Int): View {
        val wrap = LinearLayout(context)
        wrap.orientation = LinearLayout.VERTICAL

        val sizes = intArrayOf(base + 8, base + 4, base + 2, base)
        val colors = intArrayOf(AppTheme.text, AppTheme.text, AppTheme.accent, AppTheme.text2)
        val bold = booleanArrayOf(true, true, true, false)
        val topMar = intArrayOf(20, 16, 12, 8)
        val botMar = intArrayOf(8, 6, 4, 4)

        val tv = context.mkTv(stripInline(text), sizes[level - 1], colors[level - 1], bold[level - 1])
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = context.dp(topMar[level - 1]); lp.bottomMargin = context.dp(botMar[level - 1])
        tv.layoutParams = lp
        if (level <= 2) tv.setLineSpacing(0f, 1.2f)
        wrap.addView(tv)

        if (level <= 2) {
            val underline = View(context)
            val ud = if (level == 1) {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(AppTheme.accent, android.graphics.Color.TRANSPARENT))
            } else GradientDrawable().apply { setColor(AppTheme.border2) }
            ud.cornerRadius = context.dp(1).toFloat()
            underline.background = ud
            val ulLp = LinearLayout.LayoutParams(MATCH, context.dp(if (level == 1) 2 else 1))
            ulLp.bottomMargin = context.dp(8)
            underline.layoutParams = ulLp
            wrap.addView(underline)
        }
        return wrap
    }

    private fun buildHR(context: Context): View {
        val v = View(context)
        v.setBackgroundColor(AppTheme.border)
        val lp = LinearLayout.LayoutParams(MATCH, context.dp(1))
        lp.topMargin = context.dp(12); lp.bottomMargin = context.dp(12)
        v.layoutParams = lp
        return v
    }

    private fun buildBlockquote(context: Context, text: String, base: Int, baseDir: File? = null): View {
        val wrap = LinearLayout(context)
        wrap.orientation = LinearLayout.HORIZONTAL
        val wLp = LinearLayout.LayoutParams(MATCH, WRAP)
        wLp.topMargin = context.dp(6); wLp.bottomMargin = context.dp(6)
        wrap.layoutParams = wLp
        wrap.background = roundRect(AppTheme.accentBg, context.dp(8), android.graphics.Color.TRANSPARENT, 0)
        wrap.setPadding(0, context.dp(10), context.dp(12), context.dp(10))

        val bar = View(context)
        bar.setBackgroundColor(AppTheme.accent)
        bar.layoutParams = LinearLayout.LayoutParams(context.dp(3), MATCH)
        wrap.addView(bar)
        wrap.addView(context.mkHSpace(12))

        val tv = TextView(context)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (base - 1).toFloat())
        tv.setTextColor(AppTheme.text2)
        tv.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        tv.setLineSpacing(0f, 1.45f)
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.text = renderInline(context, text, baseDir, tv)
        wrap.addView(tv)
        return wrap
    }

    private fun buildCodeBlock(context: Context, lang: String, code: String, onCopyToast: ((String) -> Unit)?): View {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = roundRect(AppTheme.codeBg, context.dp(10), AppTheme.border2, context.dp(1))
        card.clipToOutline = true
        val clp = LinearLayout.LayoutParams(MATCH, WRAP)
        clp.topMargin = context.dp(6); clp.bottomMargin = context.dp(6)
        card.layoutParams = clp

        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(context.dp(12), context.dp(9), context.dp(10), context.dp(9))
        header.setBackgroundColor(if (AppTheme.isDark) 0xFF1C1C1C.toInt() else 0xFFE8EDF5.toInt())

        for (c in intArrayOf(0xFFFF5F57.toInt(), 0xFFFFBD2E.toInt(), 0xFF28CA41.toInt())) {
            val d = context.dot(c, 10)
            (d.layoutParams as LinearLayout.LayoutParams).rightMargin = context.dp(5)
            header.addView(d)
        }
        header.addView(context.mkHSpace(8))

        val langTv = context.mkTv(lang.ifEmpty { "CODE" }.uppercase(), 10, AppTheme.langColor(lang), true)
        langTv.letterSpacing = 0.1f
        header.addView(langTv)

        val hsp = View(context); hsp.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        header.addView(hsp)

        val copyBtn = context.mkTv("⎘ Copy", 10, AppTheme.text3, false)
        copyBtn.setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        copyBtn.background = roundRect(AppTheme.elevated, context.dp(6), AppTheme.border2, context.dp(1))
        copyBtn.setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("code", code.trim()))
            copyBtn.text = "✓ Copied!"
            copyBtn.setTextColor(AppTheme.C_GREEN)
            copyBtn.background = roundRect(0x1A4CAF50, context.dp(6), AppTheme.C_GREEN, context.dp(1))
            onCopyToast?.invoke("Copied to clipboard")
            Handler(Looper.getMainLooper()).postDelayed({
                copyBtn.text = "⎘ Copy"
                copyBtn.setTextColor(AppTheme.text3)
                copyBtn.background = roundRect(AppTheme.elevated, context.dp(6), AppTheme.border2, context.dp(1))
            }, 2000)
        }
        header.addView(copyBtn)
        card.addView(header)

        val accent = View(context)
        accent.setBackgroundColor(AppTheme.langColor(lang))
        accent.layoutParams = LinearLayout.LayoutParams(MATCH, context.dp(1))
        card.addView(accent)

        val hsv = HorizontalScrollView(context)
        hsv.isHorizontalScrollBarEnabled = false
        hsv.setBackgroundColor(AppTheme.codeBg)
        val codeTv = TextView(context)
        codeTv.setTextColor(AppTheme.codeText)
        codeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (AppTheme.fontBaseSp() - 2).toFloat())
        codeTv.typeface = Typeface.MONOSPACE
        codeTv.setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        codeTv.text = SyntaxHighlighter.highlight(code.trim(), lang)
        codeTv.setLineSpacing(context.dp(2).toFloat(), 1f)
        hsv.addView(codeTv)
        card.addView(hsv)
        return card
    }

    private fun buildTable(context: Context, rows: List<List<String>>, base: Int, aligns: List<Int> = emptyList(), baseDir: File? = null): View {
        val outer = LinearLayout(context)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = roundRect(AppTheme.surface, context.dp(10), AppTheme.border2, context.dp(1))
        outer.clipToOutline = true
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.topMargin = context.dp(6); tlp.bottomMargin = context.dp(6)
        outer.layoutParams = tlp

        val hsv = HorizontalScrollView(context)
        hsv.isHorizontalScrollBarEnabled = false
        hsv.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        val inner = LinearLayout(context)
        inner.orientation = LinearLayout.VERTICAL

        val colCount = rows.maxOf { it.size }.coerceAtLeast(1)
        val headerBg = if (AppTheme.isDark) 0xFF1E1E1E.toInt() else 0xFFEEEEEE.toInt()
        val stripeBg = if (AppTheme.isDark) 0xFF141414.toInt() else 0xFFF8F8F8.toInt()

        rows.forEachIndexed { r, cells ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until colCount) {
                val cellText = cells.getOrElse(c) { "" }
                val tv = TextView(context)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (base - 2).toFloat())
                tv.setTextColor(if (r == 0) AppTheme.text else AppTheme.text2)
                if (r == 0) tv.typeface = Typeface.DEFAULT_BOLD
                tv.setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                tv.minWidth = context.dp(72)
                val align = aligns.getOrElse(c) { Gravity.START }
                tv.gravity = align or Gravity.CENTER_VERTICAL
                tv.setLineSpacing(0f, 1.3f)
                if (r == 0) tv.letterSpacing = 0.04f
                val fill = if (r == 0) headerBg else if (r % 2 == 0) stripeBg else AppTheme.surface
                tv.background = roundRect(fill, 0, AppTheme.border, context.dp(1))
                tv.text = renderInline(context, cellText.trim(), baseDir, tv)
                row.addView(tv)
            }
            inner.addView(row)
        }

        hsv.addView(inner)
        outer.addView(hsv)
        return outer
    }

    private fun parseRow(line: String): List<String> =
        line.split("|").map { it.trim() }.filter { it.isNotEmpty() }

    private fun buildBulletList(context: Context, rawItems: List<String>, base: Int, baseDir: File? = null): View {
        val wrap = LinearLayout(context)
        wrap.orientation = LinearLayout.VERTICAL
        val wlp = LinearLayout.LayoutParams(MATCH, WRAP)
        wlp.topMargin = context.dp(4); wlp.bottomMargin = context.dp(4)
        wrap.layoutParams = wlp

        for (raw in rawItems) {
            val level = indentLevel(raw)
            var content = raw.trimStart().removePrefix("- ").removePrefix("* ").removePrefix("+ ")
            var checkState: Boolean? = null
            if (CHECK_DONE.matcher(content).find()) { checkState = true; content = CHECK_DONE.matcher(content).replaceFirst("") }
            else if (CHECK_OPEN.matcher(content).find()) { checkState = false; content = CHECK_OPEN.matcher(content).replaceFirst("") }

            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.TOP
            val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
            rlp.bottomMargin = context.dp(4)
            rlp.leftMargin = context.dp(level * 16)
            row.layoutParams = rlp

            if (checkState != null) {
                val box = context.mkTv(if (checkState) "☑" else "☐", base.toFloat().toInt(), if (checkState) AppTheme.C_GREEN else AppTheme.text3, false)
                val blp = LinearLayout.LayoutParams(WRAP, WRAP)
                blp.rightMargin = context.dp(8)
                box.layoutParams = blp
                row.addView(box)
            } else {
                val bullet = context.dot(AppTheme.accent, 5)
                val blp = LinearLayout.LayoutParams(context.dp(5), context.dp(5))
                blp.topMargin = context.dp(6); blp.rightMargin = context.dp(10); blp.leftMargin = context.dp(4)
                bullet.layoutParams = blp
                row.addView(bullet)
            }

            val tv = TextView(context)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (base - 1).toFloat())
            tv.setTextColor(if (checkState == true) AppTheme.text3 else AppTheme.text2)
            tv.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            tv.setLineSpacing(0f, 1.45f)
            tv.movementMethod = LinkMovementMethod.getInstance()
            tv.text = renderInline(context, content, baseDir, tv)
            if (checkState == true) tv.paintFlags = tv.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            row.addView(tv)
            wrap.addView(row)
        }
        return wrap
    }

    private fun buildNumberedList(context: Context, rawItems: List<String>, base: Int, baseDir: File? = null): View {
        val wrap = LinearLayout(context)
        wrap.orientation = LinearLayout.VERTICAL
        val wlp = LinearLayout.LayoutParams(MATCH, WRAP)
        wlp.topMargin = context.dp(4); wlp.bottomMargin = context.dp(4)
        wrap.layoutParams = wlp

        val counters = HashMap<Int, Int>()
        for (raw in rawItems) {
            val level = indentLevel(raw)
            // A new item at this level restarts numbering on any deeper level.
            counters.keys.filter { it > level }.toList().forEach { counters.remove(it) }
            val n = (counters[level] ?: 0) + 1
            counters[level] = n
            val content = raw.trimStart().replaceFirst(Regex("^\\d+\\.\\s"), "")

            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.TOP
            val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
            rlp.bottomMargin = context.dp(6)
            rlp.leftMargin = context.dp(level * 16)
            row.layoutParams = rlp

            val num = context.mkTv(n.toString(), base - 2, if (AppTheme.isDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), true)
            num.gravity = Gravity.CENTER
            num.setPadding(context.dp(3), context.dp(2), context.dp(3), context.dp(2))
            num.minWidth = context.dp(22)
            num.background = roundRect(AppTheme.accent, context.dp(4), android.graphics.Color.TRANSPARENT, 0)
            val nlp = LinearLayout.LayoutParams(WRAP, WRAP)
            nlp.topMargin = context.dp(2); nlp.rightMargin = context.dp(10)
            num.layoutParams = nlp
            row.addView(num)

            val tv = TextView(context)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (base - 1).toFloat())
            tv.setTextColor(AppTheme.text2)
            tv.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            tv.setLineSpacing(0f, 1.45f)
            tv.movementMethod = LinkMovementMethod.getInstance()
            tv.text = renderInline(context, content, baseDir, tv)
            row.addView(tv)
            wrap.addView(row)
        }
        return wrap
    }

    private fun buildParagraph(context: Context, text: String, base: Int, baseDir: File? = null): View {
        val tv = TextView(context)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (base - 1).toFloat())
        tv.setTextColor(AppTheme.text2)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = context.dp(4)
        tv.layoutParams = lp
        tv.setLineSpacing(0f, 1.55f)
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.text = renderInline(context, text, baseDir, tv)
        return tv
    }
}
