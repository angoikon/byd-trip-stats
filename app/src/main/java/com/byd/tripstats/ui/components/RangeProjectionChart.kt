package com.byd.tripstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.RegenGreen
import kotlin.math.roundToInt

/**
 * Range Projection Chart
 * 
 * Y-axis: Battery SOC (0-100%)
 * X-axis: Projected range in km
 * 
 * Shows current SOC and how far you can go based on current range
 */
@Composable
fun RangeProjectionChart(
    currentSoc: Double, // Current battery %
    currentRange: Int, // Current range estimate in km
    modifier: Modifier = Modifier
) {
    // Extract colors OUTSIDE Canvas (in composable context)
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val gridLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val textColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Chart
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val width = size.width
            val height = size.height
            
            val padding = 45f  // Reduced from 60f - enough for smaller text
            val chartWidth = width - padding * 2
            val chartHeight = height - padding * 2
            
            // Calculate max range at 100% SOC
            val maxRange = if (currentSoc > 0) {
                (currentRange / currentSoc * 100).toInt()
            } else {
                515
            }
            
            // Draw axes
            drawLine(
                color = axisColor,
                start = Offset(padding, padding),
                end = Offset(padding, height - padding),
                strokeWidth = 2f
            )
            
            drawLine(
                color = axisColor,
                start = Offset(padding, height - padding),
                end = Offset(width - padding, height - padding),
                strokeWidth = 2f
            )
            
            // Draw Y-axis labels with TEXT (SOC %)
            val yLabels = listOf(100, 75, 50, 25, 0)
            yLabels.forEach { soc ->
                val y = padding + (chartHeight * (100 - soc) / 100f)
                
                // Tick mark
                drawLine(
                    color = Color.Gray,
                    start = Offset(padding - 5f, y),
                    end = Offset(padding, y),
                    strokeWidth = 2f
                )
                
                // Grid line
                drawLine(
                    color = gridLineColor,
                    start = Offset(padding, y),
                    end = Offset(width - padding, y),
                    strokeWidth = 1f
                )
                
                // Y-axis text label
                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        "$soc%",
                        padding - 15f,  // Position to the left of axis
                        y + 4f,  // Slight offset for vertical centering
                        android.graphics.Paint().apply {
                            // color = BatteryBlue.toArgb()
                            color = textColor.toArgb()
                            textSize = 20f
                            textAlign = android.graphics.Paint.Align.RIGHT
                        }
                    )
                }
            }
            
            // Draw X-axis labels with TEXT (Range km - dynamic)
            val xLabelRanges = listOf(
                0,
                (maxRange * 0.25).toInt(),
                (maxRange * 0.50).toInt(),
                (maxRange * 0.75).toInt(),
                maxRange
            )
            
            xLabelRanges.forEach { range ->
                val x = padding + (chartWidth * range / maxRange)
                
                // Tick mark
                drawLine(
                    color = Color.Gray,
                    start = Offset(x, height - padding),
                    end = Offset(x, height - padding + 5f),
                    strokeWidth = 2f
                )
                
                // Grid line
                drawLine(
                    color = gridLineColor,
                    start = Offset(x, height - padding),
                    end = Offset(x, padding),
                    strokeWidth = 1f
                )
                
                // X-axis text label
                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        "$range",
                        x,
                        height - padding + 25f,  // Position below axis
                        android.graphics.Paint().apply {
                            color = tertiaryColor.toArgb()
                            textSize = 18f
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }
            }
            
            // Draw projection line (TOP-LEFT to BOTTOM-RIGHT)
            val projectionPath = Path().apply {
                moveTo(padding, padding) // 100% SOC, 0 km (top-left)
                lineTo(width - padding, height - padding) // 0% SOC, maxRange (bottom-right)
            }
            
            drawPath(
                path = projectionPath,
                color = BatteryBlue.copy(alpha = 0.5f),
                style = Stroke(
                    width = 3f,
                    cap = StrokeCap.Round
                )
            )
            
            // Draw current position marker
            // X should represent how much battery has been USED (distance traveled)
            // Since projection line goes from 0km at 100% to maxRange at 0%
            val batteryUsedPercent = (100 - currentSoc.toFloat()) / 100f
            val currentX = padding + (chartWidth * batteryUsedPercent)  // Based on battery used, not remaining range
            val currentY = padding + (chartHeight * batteryUsedPercent)  // Same for Y
            
            // Glow effect
            drawCircle(
                color = RegenGreen.copy(alpha = 0.3f),
                radius = 16f,
                center = Offset(currentX, currentY)
            )
            
            // Current position dot
            drawCircle(
                color = RegenGreen,
                radius = 8f,
                center = Offset(currentX, currentY)
            )
            
            // Inner white dot
            drawCircle(
                color = Color.White,
                radius = 3f,
                center = Offset(currentX, currentY)
            )
        }
        
        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Projection line indicator
            Canvas(modifier = Modifier.size(20.dp, 3.dp)) {
                drawLine(
                    color = BatteryBlue.copy(alpha = 0.5f),
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 3f
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Projected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Current position indicator
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(
                    color = RegenGreen,
                    radius = size.width / 2
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Current",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
    }
}