package com.willykez.md

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.mkTv(text: String, spSize: Int, color: Int, bold: Boolean): TextView {
    val tv = TextView(this)
    tv.text = text
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize.toFloat())
    tv.setTextColor(color)
    if (bold) tv.typeface = Typeface.DEFAULT_BOLD
    return tv
}

fun Context.mkVSpace(dpH: Int): View {
    val v = View(this)
    v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(dpH))
    return v
}

fun Context.mkHSpace(dpW: Int): View {
    val v = View(this)
    v.layoutParams = LinearLayout.LayoutParams(dp(dpW), 1)
    return v
}

fun roundRect(fillColor: Int, cornerRadiusPx: Int, strokeColor: Int, strokeWidthPx: Int): GradientDrawable {
    val gd = GradientDrawable()
    gd.setColor(fillColor)
    gd.cornerRadius = cornerRadiusPx.toFloat()
    if (strokeWidthPx > 0) gd.setStroke(strokeWidthPx, strokeColor)
    return gd
}

fun Context.pillChip(text: String, textColor: Int, bg: Int, border: Int): TextView {
    val tv = mkTv(text, 9, textColor, false)
    tv.setPadding(dp(7), dp(3), dp(7), dp(3))
    tv.background = roundRect(bg, dp(8), border, dp(1))
    return tv
}

fun Context.iconBtn(label: String, textSizeSp: Int = 14, action: () -> Unit): View {
    val btn = mkTv(label, textSizeSp, AppTheme.text3, false)
    btn.gravity = Gravity.CENTER
    btn.background = roundRect(AppTheme.elevated, dp(8), AppTheme.border, dp(1))
    btn.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    btn.setOnClickListener { action() }
    return btn
}

fun Context.dot(color: Int, sizeDp: Int): View {
    val v = View(this)
    val bg = GradientDrawable()
    bg.setColor(color)
    bg.shape = GradientDrawable.OVAL
    v.background = bg
    v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return v
}
