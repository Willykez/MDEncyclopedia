package com.willykez.md

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import java.util.regex.Pattern

/**
 * Lightweight regex-based highlighter — not a real tokenizer, but covers the
 * languages that actually show up in this app's notes: Kotlin, Java, XML,
 * Gradle, Dart/Flutter, JS/TS, Python, Swift and shell.
 */
object SyntaxHighlighter {

    private val KW_JAVA = arrayOf(
        "public", "private", "protected", "class", "interface", "extends", "implements",
        "void", "return", "new", "final", "static", "boolean", "int", "float", "double",
        "long", "String", "if", "else", "for", "while", "try", "catch", "null", "true",
        "false", "super", "this", "import", "package", "override", "abstract", "throws"
    )
    private val KW_KOTLIN = arrayOf(
        "fun", "val", "var", "class", "object", "interface", "override", "return", "if",
        "else", "when", "for", "while", "try", "catch", "null", "true", "false", "this",
        "super", "import", "package", "companion", "data", "sealed", "suspend", "launch",
        "collect", "by", "lazy", "let", "run", "apply", "also", "is", "as", "in", "vararg",
        "internal", "private", "public", "inline", "reified", "typealias", "enum"
    )
    private val KW_XML = arrayOf(
        "android", "app", "tools", "xmlns", "layout_width", "layout_height", "match_parent",
        "wrap_content", "id", "text", "visibility", "orientation"
    )
    private val KW_DART = arrayOf(
        "void", "var", "final", "const", "class", "extends", "implements", "with", "abstract",
        "return", "if", "else", "for", "while", "try", "catch", "null", "true", "false", "this",
        "super", "import", "package", "async", "await", "Future", "Stream", "static", "late",
        "required", "widget", "build", "override", "mixin", "enum", "switch", "case", "break",
        "new", "is", "as", "in", "yield", "typedef", "get", "set"
    )
    private val KW_JS = arrayOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "try",
        "catch", "null", "undefined", "true", "false", "this", "super", "import", "export",
        "from", "default", "class", "extends", "new", "async", "await", "typeof", "instanceof",
        "interface", "type", "public", "private", "readonly", "implements", "enum", "case",
        "break", "switch", "of", "in", "yield", "static"
    )
    private val KW_PYTHON = arrayOf(
        "def", "return", "if", "elif", "else", "for", "while", "try", "except", "finally",
        "None", "True", "False", "self", "import", "from", "as", "class", "with", "lambda",
        "yield", "async", "await", "pass", "break", "continue", "is", "in", "not", "and", "or",
        "raise", "global", "nonlocal", "assert", "del"
    )
    private val KW_SWIFT = arrayOf(
        "func", "var", "let", "return", "if", "else", "for", "while", "guard", "switch", "case",
        "class", "struct", "enum", "protocol", "extension", "import", "true", "false", "nil",
        "self", "super", "override", "static", "private", "public", "internal", "final", "init",
        "try", "catch", "throw", "throws", "async", "await", "in", "as", "is", "where"
    )
    private val KW_BASH = arrayOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "exit", "echo", "export", "local", "in", "break", "continue"
    )

    private data class LangProfile(val keywords: Array<String>, val lineComment: String?)

    private fun profileFor(lang: String): LangProfile {
        val lo = lang.lowercase()
        return when {
            lo.contains("kotlin") || lo.contains("gradle") -> LangProfile(KW_KOTLIN, "//")
            lo.contains("dart") || lo.contains("flutter") -> LangProfile(KW_DART, "//")
            lo.contains("java") -> LangProfile(KW_JAVA, "//")
            lo.contains("xml") -> LangProfile(KW_XML, null)
            lo.contains("swift") -> LangProfile(KW_SWIFT, "//")
            lo.contains("py") -> LangProfile(KW_PYTHON, "#")
            lo.contains("js") || lo.contains("ts") || lo == "javascript" || lo == "typescript" ->
                LangProfile(KW_JS, "//")
            lo.contains("bash") || lo.contains("shell") || lo == "sh" -> LangProfile(KW_BASH, "#")
            lo.contains("yaml") || lo.contains("yml") -> LangProfile(emptyArray(), "#")
            else -> LangProfile(KW_JAVA, "//")
        }
    }

    fun highlight(code: String, lang: String?): CharSequence {
        val sp = SpannableString(code)
        val profile = profileFor(lang ?: "")
        val kwColor = if (AppTheme.isDark) 0xFFCC99FF.toInt() else 0xFF7C3AED.toInt()
        val annotationColor = if (AppTheme.isDark) 0xFFFFB86C.toInt() else 0xFFB45309.toInt()

        fun span(start: Int, end: Int, color: Int, bold: Boolean = false) {
            if (start < 0 || end > sp.length || start >= end) return
            sp.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sp.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Keywords
        for (kw in profile.keywords) {
            var start = 0
            while (true) {
                val idx = code.indexOf(kw, start)
                if (idx < 0) break
                val pre = idx == 0 || !code[idx - 1].isLetterOrDigit()
                val post = idx + kw.length >= code.length || !code[idx + kw.length].isLetterOrDigit()
                if (pre && post) span(idx, idx + kw.length, kwColor)
                start = idx + kw.length
            }
        }

        // Annotations / decorators: @Something (Kotlin/Java/Dart annotations, Python decorators)
        val ann = Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*").matcher(code)
        while (ann.find()) span(ann.start(), ann.end(), annotationColor, bold = true)

        // Strings — both "..." and '...' on a single line
        for (quote in charArrayOf('"', '\'')) {
            var idx = 0
            while (true) {
                val start = code.indexOf(quote, idx)
                if (start < 0) break
                val end = code.indexOf(quote, start + 1)
                if (end > start && !code.substring(start, end).contains('\n')) {
                    span(start, end + 1, if (AppTheme.isDark) 0xFF98D982.toInt() else 0xFF16A34A.toInt())
                    idx = end + 1
                } else { idx = start + 1 }
            }
        }

        // Comments
        if (profile.lineComment != null) {
            for (line in code.split("\n")) {
                val ci = line.indexOf(profile.lineComment)
                if (ci >= 0) {
                    val abs = code.indexOf(line)
                    if (abs >= 0) span(abs + ci, abs + line.length, AppTheme.text3)
                }
            }
        }

        // Numbers
        val m = Pattern.compile("\\b\\d+\\.?\\d*\\b").matcher(code)
        while (m.find()) {
            span(m.start(), m.end(), if (AppTheme.isDark) 0xFFFFB86C.toInt() else 0xFFD97706.toInt())
        }
        return sp
    }
}
