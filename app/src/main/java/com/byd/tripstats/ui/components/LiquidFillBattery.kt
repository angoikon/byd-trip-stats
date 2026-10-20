package com.byd.tripstats.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.sin

private const val WAVE_PERIOD_MS = 6_000L
private const val WAVE_FPS = 18
private const val WAVE_FRAME_MS = 1_000L / WAVE_FPS
private const val WAVE_STEP_DEG = 360f * WAVE_FRAME_MS / WAVE_PERIOD_MS

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
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    height: Dp = 200.dp
) {
    // Animate the liquid fill level
    val animatedSoc by animateFloatAsState(
        targetValue = soc,
        animationSpec = if (animationsEnabled) {
            tween(durationMillis = 1500, easing = EaseInOutCubic)
        } else {
            snap()
        },
        label = "socAnimation"
    )
    
    // Gate all animations on Lifecycle.State.RESUMED using Animatable +
    // LaunchedEffect. When isResumed turns false the launched coroutine is
    // cancelled automatically, stopping the animation in place with zero
    // ongoing CPU/GPU cost. infiniteRepeatable inside animateTo loops until
    // the coroutine is cancelled. snap() / InfiniteRepeatableSpec mismatch
    // is avoided entirely.
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    // Wave runs whenever animations are enabled and screen is resumed — regardless of charging.
    // Only glow and bolt pulse are charging-specific.
    val shouldAnimateWave = animationsEnabled && isResumed

    // Wave offset: 0 → 360 every WAVE_PERIOD_MS, loops while resumed with animations enabled.
    // Stepped at WAVE_FPS instead of every display frame — a 3.3° step on a 6 s loop still reads as
    // smooth — and read only inside the Canvas below, so a step redraws that canvas alone rather
    // than recomposing the battery.
    val waveOffsetAnim = remember { Animatable(0f) }
    LaunchedEffect(shouldAnimateWave) {
        if (shouldAnimateWave) {
            while (true) {
                delay(WAVE_FRAME_MS)
                waveOffsetAnim.snapTo((waveOffsetAnim.value + WAVE_STEP_DEG) % 360f)
            }
        }
        // Coroutine cancelled when isResumed = false — animation freezes in place
    }

    // Charging glow: 0.3 ↔ 0.8, loops while charging AND RESUMED
    val glowAnim = remember { Animatable(0f) }
    LaunchedEffect(isCharging, isResumed) {
        if (animationsEnabled && isCharging && isResumed) {
            glowAnim.animateTo(
                targetValue   = 0.8f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(durationMillis = 3000, easing = EaseInOutSine),  // 2× slower
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(0)
                )
            )
        } else {
            glowAnim.snapTo(0f)
        }
    }

    // Bolt pulse: 0.85 ↔ 1.15, loops while charging AND RESUMED
    val boltAnim = remember { Animatable(1f) }
    LaunchedEffect(isCharging, isResumed) {
        if (animationsEnabled && isCharging && isResumed) {
            boltAnim.animateTo(
                targetValue   = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(durationMillis = 1800, easing = EaseInOutSine),  // 2× slower
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(0)
                )
            )
        } else {
            boltAnim.snapTo(1f)
        }
    }

    Box(
        modifier = modifier
            .width(width)
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        // Battery visualization — its own layer, so a wave step re-records this canvas alone,
        // not the card around it.
        Canvas(
            modifier = Modifier.fillMaxSize().graphicsLayer()
        ) {
            val waveOffset = waveOffsetAnim.value
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
                color = Color.Gray.copy(alpha = 0.5f),
                topLeft = Offset(
                    x = (canvasWidth - batteryNippleWidth) / 2f,
                    y = 0f
                ),
                size = Size(batteryNippleWidth, batteryNippleHeight),
                cornerRadius = CornerRadius(8f, 8f)
            )
            
            // Battery outline
            drawRoundRect(
                color = Color.Gray.copy(alpha = 0.9f),
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
                val waveAmplitude = 4f
                val waveFrequency = 0.08f
                val waveOffsetRad = waveOffset * 0.0174533f  // degrees → radians, computed once

                // Start exactly on the wave curve at x=0 so the left wall has no gap.
                // Previously moveTo(bodyLeft, fillTop) caused a slant because sin(waveOffset)
                // is not zero — the wave's first lineTo jumped from fillTop to the sine value.
                val startY = fillTop + sin(waveOffsetRad) * waveAmplitude
                moveTo(bodyLeft, startY.toFloat())

                // Draw wave across the full battery width in steps of 8px
                for (x in 8..batteryBodyWidth.toInt() step 8) {
                    val angle = (x * waveFrequency) + waveOffsetRad
                    val y = fillTop + sin(angle) * waveAmplitude
                    lineTo(bodyLeft + x, y.toFloat())
                }

                // Close to the exact right wall (batteryBodyWidth, not toInt() which truncates)
                // then down and back to seal the fill area.
                val endAngle = (batteryBodyWidth * waveFrequency) + waveOffsetRad
                val endY = fillTop + sin(endAngle) * waveAmplitude
                lineTo(bodyLeft + batteryBodyWidth, endY.toFloat())
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
                    color = BydElectricBlue.copy(alpha = glowAnim.value),
                    topLeft = Offset(bodyLeft - 8f, bodyTop - 8f),
                    size = Size(batteryBodyWidth + 16f, batteryBodyHeight + 16f),
                    cornerRadius = CornerRadius(24f, 24f),
                    style = Stroke(width = 8f)
                )
            }
        }
        
        // Charging bolt overlay — Icon respects color, emoji does not
        if (isCharging) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = "Charging",
                tint = BydElectricBlue,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer {
                        scaleX = boltAnim.value
                        scaleY = boltAnim.value
                    }
            )
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
            val strokePx = 2f
            val bodyWidth = size.width * 0.85f - strokePx
            val bodyHeight = size.height * 0.9f - strokePx
            val nippleWidth = size.width * 0.1f
            val nippleHeight = size.height * 0.5f
            val bodyTop = (size.height - bodyHeight) / 2f
            val cornerPx = 6f

            // Battery body — inset by half the stroke width so the outline
            // (including the rounded corners) stays fully inside the canvas
            // rather than clipping at the left/top edges.
            drawRoundRect(
                color = Color(0xFF444444),
                topLeft = Offset(strokePx / 2f, bodyTop),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(strokePx)
            )

            // Battery nipple
            drawRoundRect(
                color = Color(0xFF444444),
                topLeft = Offset(strokePx / 2f + bodyWidth, (size.height - nippleHeight) / 2f),
                size = Size(nippleWidth, nippleHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )

            // Fill
            val fillPadding = strokePx + 1f
            val fillWidth = (bodyWidth - 2f * fillPadding).coerceAtLeast(0f) * (animatedSoc / 100f)
            drawRoundRect(
                color = batteryColor,
                topLeft = Offset(strokePx / 2f + fillPadding, bodyTop + fillPadding),
                size = Size(fillWidth, (bodyHeight - 2f * fillPadding).coerceAtLeast(0f)),
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
                color = BydElectricBlue
            )
        }
    }
}