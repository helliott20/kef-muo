package io.github.helliott20.muo

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers so the screens can be built in code without layout XML. */
internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun Context.column(pad: Int = 16) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

internal fun Context.text(s: CharSequence = "", size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(4), 0, dp(4))
}

internal fun Context.heading(s: String) = text(s, 20f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) }

internal fun Context.button(s: String, onClick: () -> Unit) = Button(this).apply {
    text = s
    setOnClickListener { onClick() }
}

internal fun ViewGroup.add(vararg views: View) = views.forEach {
    addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}
