package com.willykez.md

import android.content.Context
import android.content.SharedPreferences

/**
 * Central colour palette + small persisted UI prefs (theme, font size).
 * Ported from the original Sketchware MainActivity's static palette so every
 * screen (library, reader, editor) looks consistent.
 */
object AppTheme {

    // Dark palette
    const val D_BG = 0xFF0A0A0A.toInt()
    const val D_SURFACE = 0xFF111111.toInt()
    const val D_SURFACE2 = 0xFF161616.toInt()
    const val D_ELEVATED = 0xFF1C1C1C.toInt()
    const val D_CARD = 0xFF181818.toInt()
    const val D_HIGHLIGHT = 0xFF252525.toInt()
    const val D_ACCENT = 0xFFE8FF57.toInt()
    const val D_ACCENT_DIM = 0xFFB8CC3F.toInt()
    const val D_ACCENT_BG = 0x1AE8FF57
    const val D_TEXT = 0xFFF2F2F2.toInt()
    const val D_TEXT2 = 0xFFBBBBBB.toInt()
    const val D_TEXT3 = 0xFF666666.toInt()
    const val D_TEXT4 = 0xFF444444.toInt()
    const val D_CODE_BG = 0xFF0D0D0D.toInt()
    const val D_CODE_TEXT = 0xFFABD5FF.toInt()
    const val D_BORDER = 0xFF282828.toInt()
    const val D_BORDER2 = 0xFF333333.toInt()

    // Light palette
    const val L_BG = 0xFFF5F5F5.toInt()
    const val L_SURFACE = 0xFFFFFFFF.toInt()
    const val L_SURFACE2 = 0xFFF0F0F0.toInt()
    const val L_ELEVATED = 0xFFE8E8E8.toInt()
    const val L_CARD = 0xFFF8F8F8.toInt()
    const val L_HIGHLIGHT = 0xFFE0E0E0.toInt()
    const val L_ACCENT = 0xFF5C6B00.toInt()
    const val L_ACCENT_DIM = 0xFF7A8F00.toInt()
    const val L_ACCENT_BG = 0x18A0B000
    const val L_TEXT = 0xFF1A1A1A.toInt()
    const val L_TEXT2 = 0xFF444444.toInt()
    const val L_TEXT3 = 0xFF888888.toInt()
    const val L_TEXT4 = 0xFFAAAAAA.toInt()
    const val L_CODE_BG = 0xFFF0F4FF.toInt()
    const val L_CODE_TEXT = 0xFF2563EB.toInt()
    const val L_BORDER = 0xFFDDDDDD.toInt()
    const val L_BORDER2 = 0xFFCCCCCC.toInt()

    const val C_GREEN = 0xFF4CAF50.toInt()
    const val C_ORANGE = 0xFFFF9800.toInt()
    const val C_PURPLE = 0xFF9C7FD4.toInt()
    const val C_RED = 0xFFE53935.toInt()

    var isDark: Boolean = true
        private set
    var fontSizeLevel: Int = 1
        private set

    var bg = 0; var surface = 0; var surface2 = 0; var elevated = 0; var card = 0; var highlight = 0
    var accent = 0; var accentDim = 0; var accentBg = 0
    var text = 0; var text2 = 0; var text3 = 0; var text4 = 0
    var codeBg = 0; var codeText = 0; var border = 0; var border2 = 0

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("enc_prefs", Context.MODE_PRIVATE)
        val systemDark = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        isDark = prefs.getBoolean("dark_mode", systemDark)
        fontSizeLevel = prefs.getInt("font_size", 1)
        resolve()
    }

    fun toggleTheme() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()
        resolve()
    }

    fun cycleFontSize(): Int {
        fontSizeLevel = (fontSizeLevel + 1) % 3
        prefs.edit().putInt("font_size", fontSizeLevel).apply()
        return fontSizeLevel
    }

    fun fontBaseSp(): Int = when (fontSizeLevel) {
        0 -> 13
        2 -> 17
        else -> 15
    }

    private fun resolve() {
        if (isDark) {
            bg = D_BG; surface = D_SURFACE; surface2 = D_SURFACE2
            elevated = D_ELEVATED; card = D_CARD; highlight = D_HIGHLIGHT
            accent = D_ACCENT; accentDim = D_ACCENT_DIM; accentBg = D_ACCENT_BG
            text = D_TEXT; text2 = D_TEXT2; text3 = D_TEXT3; text4 = D_TEXT4
            codeBg = D_CODE_BG; codeText = D_CODE_TEXT; border = D_BORDER; border2 = D_BORDER2
        } else {
            bg = L_BG; surface = L_SURFACE; surface2 = L_SURFACE2
            elevated = L_ELEVATED; card = L_CARD; highlight = L_HIGHLIGHT
            accent = L_ACCENT; accentDim = L_ACCENT_DIM; accentBg = L_ACCENT_BG
            text = L_TEXT; text2 = L_TEXT2; text3 = L_TEXT3; text4 = L_TEXT4
            codeBg = L_CODE_BG; codeText = L_CODE_TEXT; border = L_BORDER; border2 = L_BORDER2
        }
    }

    fun langColor(lang: String?): Int {
        if (lang.isNullOrEmpty()) return text3
        return when (lang.lowercase()) {
            "kotlin" -> 0xFF9B89FF.toInt()
            "java" -> 0xFFFF8C69.toInt()
            "xml" -> 0xFF88D5F0.toInt()
            "gradle" -> 0xFF97CC64.toInt()
            "bash", "shell" -> 0xFFF4E06D.toInt()
            else -> text3
        }
    }
}
