package com.willykez.md

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object SyntaxHighlighter {

    private val KW_JAVA = arrayOf(
        "public", "private", "protected", "class", "interface", "extends", "implements",
        "void", "return", "new", "final", "static", "boolean", "int", "float", "double",
        "long", "String", "if", "else", "for", "while", "try", "catch", "null", "true",
        "false", "super", "this", "import", "package", "override", "abstract"
    )
    private val KW_KOTLIN = arrayOf(
        "fun", "val", "var", "class", "object", "interface", "override", "return", "if",
        "else", "when", "for", "while", "try", "catch", "null", "true", "false", "this",
        "super", "import", "package", "companion", "data", "sealed", "suspend", "launch",
        "collect", "by", "lazy", "let", "run", "apply", "also", "is", "as", "in"
    )
    private val KW_XML = arrayOf(
        "android", "app", "tools", "xmlns", "layout_width", "layout_height", "match_parent",
        "wrap_content", "id", "text", "visibility", "orientation"
    )

    fun highlight(code: String, lang: String?): CharSequence {
        val sp = SpannableString(code)
        val lo = (lang ?: "").lowercase()
        val kws = when {
            lo.contains("kotlin") -> KW_KOTLIN
            lo.contains("xml") -> KW_XML
            else -> KW_JAVA
        }
        val kwColor = if (AppTheme.isDark) 0xFFCC99FF.toInt() else 0xFF7C3AED.toInt()

        for (kw in kws) {
            var start = 0
            while (true) {
                val idx = code.indexOf(kw, start)
                if (idx < 0) break
                val pre = idx == 0 || !code[idx - 1].isLetterOrDigit()
                val post = idx + kw.length >= code.length || !code[idx + kw.length].isLetterOrDigit()
                if (pre && post) {
                    sp.setSpan(ForegroundColorSpan(kwColor), idx, idx + kw.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                start = idx + kw.length
            }
        }

        // Strings
        var idx = 0
        while (true) {
            val start = code.indexOf('"', idx)
            if (start < 0) break
            val end = code.indexOf('"', start + 1)
            if (end > start && !code.substring(start, end).contains('\n')) {
                sp.setSpan(
                    ForegroundColorSpan(if (AppTheme.isDark) 0xFF98D982.toInt() else 0xFF16A34A.toInt()),
                    start, end + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = end + 1
            } else break
        }

        // Comments
        for (line in code.split("\n")) {
            val ci = line.indexOf("//")
            if (ci >= 0) {
                val abs = code.indexOf(line)
                if (abs >= 0) {
                    val cs = abs + ci
                    val ce = abs + line.length
                    if (cs < sp.length && ce <= sp.length) {
                        sp.setSpan(ForegroundColorSpan(AppTheme.text3), cs, ce, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }

        // Numbers
        val m = Pattern.compile("\\b\\d+\\.?\\d*\\b").matcher(code)
        while (m.find()) {
            sp.setSpan(
                ForegroundColorSpan(if (AppTheme.isDark) 0xFFFFB86C.toInt() else 0xFFD97706.toInt()),
                m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sp
    }
}
