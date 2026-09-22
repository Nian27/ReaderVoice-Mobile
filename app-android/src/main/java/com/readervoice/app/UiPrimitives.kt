package com.readervoice.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import kotlin.math.roundToInt

private const val SURFACE = 0xFFFFFFFF.toInt()
private const val OUTLINE = 0xFFE2E8F0.toInt()

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun TextView.setTextSp(value: Float) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
    includeFontPadding = false
}

fun Context.roundedSurface(
    color: Int = SURFACE,
    radiusDp: Int = 16,
    strokeColor: Int = OUTLINE,
): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    setStroke(dp(1), strokeColor)
}

/**
 * Android 15 enforces edge-to-edge for targetSdk 35 apps. Keep content inside the
 * actual system-bar/display-cutout safe region and use density-independent margins.
 */
fun View.applySafeDrawingPadding(horizontalDp: Int = 20, verticalDp: Int = 16) {
    setOnApplyWindowInsetsListener { view, insets ->
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) insets.displayCutout else null
        val horizontal = context.dp(horizontalDp)
        val vertical = context.dp(verticalDp)
        view.setPadding(
            horizontal + maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
            vertical + maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
            horizontal + maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
            vertical + maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
        )
        insets
    }
}
