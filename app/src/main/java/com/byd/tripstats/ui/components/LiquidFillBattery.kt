package com.byd.tripstats.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/**
 * Liquid-Fill Battery - Premium animated battery indicator
 * 
 * Features:
 * - Animated liquid wave effect
 * - Color gradient based on charge level
 * - Smooth fill animation
 * - Glow effect when charging
 * 
 * @param soc State of Charge (0-100)
 * @param isCharging Whether battery is currently charging
 * @param modifier Compose modifier
 * @param width Battery width
 * @param height Battery height
 */
@Composable
fun LiquidFillBattery(
    soc: Float,
    isCharging: Boolean = false,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    height: Dp = 200.dp
) {
    // Animate the liquid fill level
    val animatedSoc by animateFloatAsState(
        targetValue = soc,
        animationSpec = tween(durationMillis = 1500, easing = EaseInOutCubic),
        label = "socAnimation"
    )
    
    // Animate wave motion
    val infiniteTransition = rememberInfiniteTransition(label = "waveTransition")
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveAnimation"
    )
    
    // Charging glow animation
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = if (isCharging) 0.3f else 0f,
        targetValue = if (isCharging) 0.8f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAnimation"
    )
    
    Box(
        modifier = modifier
            .width(width)
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        // Battery visualization
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            
            // Battery body dimensions
            val batteryBodyHeight = canvasHeight * 0.85f
            val batteryBodyWidth = canvasWidth * 0.7f
            val batteryNippleHeight = canvasHeight * 0.1f
            val batteryNippleWidth = canvasWidth * 0.3f
            
            val bodyLeft = (canvasWidth - batteryBodyWidth) / 2f
            val bodyTop = batteryNippleHeight + (canvasHeight - batteryBodyHeight - batteryNippleHeight) / 2f
            
            // Color gradient based on SoC
            val liquidColor = when {
                animatedSoc >= 80f -> listOf(
                    Color(0xFF00FF88), // Bright green
                    Color(0xFF00CC66)  // Deep green
                )
                animatedSoc >= 50f -> listOf(
                    Color(0xFFFFDD00), // Yellow
                    Color(0xFFFFAA00)  // Orange-yellow
                )
                animatedSoc >= 20f -> listOf(
                    Color(0xFFFFAA00), // Orange
                    Color(0xFFFF6600)  // Deep orange
                )
                else -> listOf(
                    Color(0xFFFF4444), // Red
                    Color(0xFFCC0000)  // Deep red
                )
            }
            
            // Battery nipple (top terminal)
            drawRoundRect(
                color = Color.Gray.copy(alpha = 0.5f)
                topLeft = Offset(
                    x = (canvasWidth - batteryNippleWidth) / 2f,
                    y = 0f
                ),
                size = Size(batteryNippleWidth, batteryNippleHeight),
                cornerRadius = CornerRadius(8f, 8f)
            )
            
            // Battery outline
            drawRoundRect(
                color = Color.Gray.copy(alpha = 0.9f)
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(batteryBodyWidth, batteryBodyHeight),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 3.5f)
            )
            
            // Liquid fill with wave effect
            val fillHeight = batteryBodyHeight * (animatedSoc / 100f)
            val fillTop = bodyTop + batteryBodyHeight - fillHeight
            
            // Create wave path
            val wavePath = Path().apply {
                val waveAmplitude = 8f
                val waveFrequency = 0.03f
                
                moveTo(bodyLeft, fillTop)
                
                // Draw wave
                for (x in 0..batteryBodyWidth.toInt() step 4) {
                    val angle = (x * waveFrequency) + (waveOffset * 0.0174533f) // Convert to radians
                    val y = fillTop + (sin(angle) * waveAmplitude)
                    lineTo(bodyLeft + x, y.toFloat())
                }
                
                // Complete the path
                lineTo(bodyLeft + batteryBodyWidth, bodyTop + batteryBodyHeight)
                lineTo(bodyLeft, bodyTop + batteryBodyHeight)
                close()
            }
            
            // Clip to battery body
            clipRect(
                left = bodyLeft + 3f,
                top = bodyTop + 3f,
                right = bodyLeft + batteryBodyWidth - 3f,
                bottom = bodyTop + batteryBodyHeight - 3f
            ) {
                // Draw liquid fill with gradient
                drawPath(
                    path = wavePath,
                    brush = Brush.verticalGradient(
                        colors = liquidColor,
                        startY = fillTop,
                        endY = bodyTop + batteryBodyHeight
                    )
                )
                
                // Add shimmer effect
                drawPath(
                    path = wavePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        startY = fillTop,
                        endY = fillTop + fillHeight * 0.3f
                    ),
                    blendMode = BlendMode.Plus
                )
            }
            
            // Charging glow effect
            if (isCharging) {
                drawRoundRect(
                    color = Color(0xFF00D4FF).copy(alpha = glowAlpha),
                    topLeft = Offset(bodyLeft - 8f, bodyTop - 8f),
                    size = Size(batteryBodyWidth + 16f, batteryBodyHeight + 16f),
                    cornerRadius = CornerRadius(24f, 24f),
                    style = Stroke(width = 8f)
                )
            }
        }
        
        // Percentage text overlay
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Text(
            //     text = "${animatedSoc.toInt()}%",
            //     fontSize = 32.sp,
            //     fontWeight = FontWeight.Bold,
            //     color = if (animatedSoc > 50f) Color.White else Color(0xFF333333),
            //     style = MaterialTheme.typography.displaySmall
            // )
            
            if (isCharging) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚡ Charging",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF00D4FF)
                )
            }
        }
    }
}

/**
 * Compact horizontal battery indicator
 * Perfect for small spaces like status bars
 */
@Composable
fun CompactBattery(
    soc: Float,
    isCharging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val animatedSoc by animateFloatAsState(
        targetValue = soc,
        animationSpec = tween(1000),
        label = "compactSoc"
    )
    
    val batteryColor = when {
        animatedSoc >= 80f -> Color(0xFF00FF88)
        animatedSoc >= 50f -> Color(0xFFFFDD00)
        animatedSoc >= 20f -> Color(0xFFFFAA00)
        else -> Color(0xFFFF4444)
    }
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 40.dp, height = 20.dp)) {
            val bodyWidth = size.width * 0.85f
            val bodyHeight = size.height * 0.9f
            val nippleWidth = size.width * 0.1f
            val nippleHeight = size.height * 0.5f
            
            // Battery body
            drawRoundRect(
                color = Color(0xFF444444),
                topLeft = Offset(0f, (size.height - bodyHeight) / 2f),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = CornerRadius(4f, 4f),
                style = Stroke(2f)
            )
            
            // Battery nipple
            drawRoundRect(
                color = Color(0xFF444444),
                topLeft = Offset(bodyWidth, (size.height - nippleHeight) / 2f),
                size = Size(nippleWidth, nippleHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )
            
            // Fill
            val fillWidth = (bodyWidth - 4f) * (animatedSoc / 100f)
            drawRoundRect(
                color = batteryColor,
                topLeft = Offset(2f, (size.height - bodyHeight) / 2f + 2f),
                size = Size(fillWidth, bodyHeight - 4f),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
        
        Text(
            text = "${animatedSoc.toInt()}%",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        if (isCharging) {
            Text(
                text = "⚡",
                fontSize = 16.sp,
                color = Color(0xFF00D4FF)
            )
        }
    }
}
