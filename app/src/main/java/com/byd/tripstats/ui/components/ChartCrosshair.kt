package com.byd.tripstats.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * Draws a crosshair overlay on a chart canvas at a selected data point.
 *
 * Call this at the very end of your Canvas block so it renders on top of everything.
 *
 * @param cx          Pixel X of the selected point
 * @param cy          Pixel Y of the selected point
 * @param w           Full canvas width
 * @param padL/R/T    Chart padding (to constrain crosshair lines to chart area)
 * @param chartH      Chart drawing height (padT..padT+chartH)
 * @param line1       First tooltip line — typically the Y value (e.g. "87.5 km/h")
 * @param line2       Second tooltip line — typically the X value (e.g. "5m 30s")
 * @param accentColor The chart's primary color — used for the lines and dot
 * @param textColor   Label text color (onSurface)
 */
fun DrawScope.drawCrosshair(
    cx: Float,
    cy: Float,
    w: Float,
    padL: Float,
    padR: Float,
    padT: Float,
    chartH: Float,
    line1: String,
    line2: String,
    accentColor: Color,
    textColor: Color
) {
    val nc = drawContext.canvas.nativeCanvas
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))

    // Vertical crosshair line
    drawLine(
        color = accentColor.copy(alpha = 0.7f),
        start = Offset(cx, padT),
        end   = Offset(cx, padT + chartH),
        strokeWidth = 1.5f,
        pathEffect = dashEffect
    )
    // Horizontal crosshair line
    drawLine(
        color = accentColor.copy(alpha = 0.7f),
        start = Offset(padL, cy),
        end   = Offset(w - padR, cy),
        strokeWidth = 1.5f,
        pathEffect = dashEffect
    )

    // Intersection dot — outer ring + white center pin
    drawCircle(accentColor.copy(alpha = 0.25f), 14f, Offset(cx, cy))
    drawCircle(accentColor, 7f, Offset(cx, cy))
    drawCircle(Color.White, 3f, Offset(cx, cy))

    // Tooltip
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 24f
    }
    val padding   = 14f
    val lineGap   = 6f
    val textH     = paint.descent() - paint.ascent()
    val boxW      = maxOf(paint.measureText(line1), paint.measureText(line2)) + padding * 2
    val boxH      = textH * 2 + lineGap + padding * 2

    // Position tooltip: prefer top-right of crosshair, flip if it would clip
    val tooltipX = if (cx + 12f + boxW < w - padR) cx + 12f else cx - 12f - boxW
    val tooltipY = if (cy - boxH - 8f > padT) cy - boxH - 8f else cy + 8f

    // Background pill
    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = accentColor.copy(alpha = 0.90f).toArgb()
        style = android.graphics.Paint.Style.FILL
    }
    nc.drawRoundRect(
        tooltipX, tooltipY,
        tooltipX + boxW, tooltipY + boxH,
        10f, 10f, bgPaint
    )

    // Text — line1 (value) bold, line2 (time) lighter
    paint.color = Color.White.toArgb()
    paint.isFakeBoldText = true
    nc.drawText(line1, tooltipX + padding, tooltipY + padding - paint.ascent(), paint)
    paint.isFakeBoldText = false
    paint.alpha = 200
    nc.drawText(line2, tooltipX + padding, tooltipY + padding - paint.ascent() + textH + lineGap, paint)
}