package com.byd.tripstats.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated Energy Flow Visualization
 * 
 * Shows energy flowing from battery to motors with animated particles.
 * Direction and particle count changes based on power flow:
 * - Positive power: Green particles flow battery → motors
 * - Negative power (regen): Orange particles flow motors → battery
 * - Zero power: No particles
 * 
 * @param power Current power in kW (positive = consuming, negative = regenerating)
 * @param isCharging Whether vehicle is charging
 * @param modifier Compose modifier
 */
@Composable
fun AnimatedEnergyFlow(
    power: Float,
    isCharging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val particleCount = (kotlin.math.abs(power) / 5f).toInt().coerceIn(0, 20)
    val isRegenerating = power < 0
    
    // Animation state
    val infiniteTransition = rememberInfiniteTransition(label = "energyFlow")
    
    // Particle animation progress (0-1, loops continuously)
    val particleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleProgress"
    )
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "Energy Flow",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Main visualization
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            // Energy flow canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                // Define positions
                val batteryPos = Offset(canvasWidth * 0.2f, canvasHeight * 0.5f)
                val motorPos = Offset(canvasWidth * 0.8f, canvasHeight * 0.5f)
                val accessoriesPos = Offset(canvasWidth * 0.5f, canvasHeight * 0.8f)
                
                // Draw connection paths
                val pathColor = Color(0xFF444444)
                
                // Battery to Motor path
                drawLine(
                    color = pathColor,
                    start = batteryPos,
                    end = motorPos,
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
                
                // Battery to Accessories path
                drawLine(
                    color = pathColor,
                    start = batteryPos,
                    end = accessoriesPos,
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
                
                // Draw animated particles
                if (particleCount > 0) {
                    val particleColor = if (isRegenerating) {
                        Color(0xFFFFAA00) // Orange for regen
                    } else {
                        Color(0xFF00FF88) // Green for consumption
                    }
                    
                    // Create particles along the path
                    for (i in 0 until particleCount) {
                        // Stagger particles evenly along the path
                        val offset = (i.toFloat() / particleCount)
                        var progress = (particleProgress + offset) % 1f
                        
                        // Reverse direction for regeneration
                        if (isRegenerating) {
                            progress = 1f - progress
                        }
                        
                        // Calculate particle position
                        val x = batteryPos.x + (motorPos.x - batteryPos.x) * progress
                        val y = batteryPos.y + (motorPos.y - batteryPos.y) * progress
                        
                        // Draw particle with glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    particleColor,
                                    particleColor.copy(alpha = 0.3f),
                                    Color.Transparent
                                ),
                                radius = 12f
                            ),
                            radius = 12f,
                            center = Offset(x, y)
                        )
                        
                        // Core particle
                        drawCircle(
                            color = particleColor,
                            radius = 4f,
                            center = Offset(x, y)
                        )
                    }
                    
                    // Accessories particles (fewer, slower)
                    for (i in 0 until (particleCount / 3).coerceAtLeast(1)) {
                        val offset = (i.toFloat() / (particleCount / 3).coerceAtLeast(1))
                        val progress = (particleProgress * 0.7f + offset) % 1f
                        
                        val x = batteryPos.x + (accessoriesPos.x - batteryPos.x) * progress
                        val y = batteryPos.y + (accessoriesPos.y - batteryPos.y) * progress
                        
                        drawCircle(
                            color = Color(0xFF00D4FF).copy(alpha = 0.6f),
                            radius = 3f,
                            center = Offset(x, y)
                        )
                    }
                }
                
                // Draw glow effect on active components
                if (power != 0f) {
                    // Motor glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF00FF88).copy(alpha = 0.3f),
                                Color.Transparent
                            ),
                            radius = 60f
                        ),
                        radius = 60f,
                        center = motorPos
                    )
                }
                
                if (isCharging) {
                    // Battery charging glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF00D4FF).copy(alpha = 0.4f),
                                Color.Transparent
                            ),
                            radius = 60f
                        ),
                        radius = 60f,
                        center = batteryPos
                    )
                }
            }
            
            // Component icons overlaid
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Battery icon
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = "Battery",
                        modifier = Modifier.size(48.dp),
                        tint = if (isCharging) Color(0xFF00D4FF) else Color(0xFF888888)
                    )
                    Text(
                        text = "Battery",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Motor icon
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ElectricCar,
                        contentDescription = "Motors",
                        modifier = Modifier.size(48.dp),
                        tint = if (kotlin.math.abs(power) > 0) Color(0xFF00FF88) else Color(0xFF888888)
                    )
                    Text(
                        text = "Motors",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            
            // Accessories icon (bottom center)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Accessories",
                    modifier = Modifier.size(32.dp),
                    tint = Color(0xFF00D4FF).copy(alpha = 0.6f)
                )
                Text(
                    text = "Systems",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Power stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Current power
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isRegenerating) "Regenerating" else "Consuming",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "${kotlin.math.abs(power).format(1)} kW",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isRegenerating) Color(0xFFFFAA00) else Color(0xFF00FF88)
                )
            }
            
            // Status
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Status",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = when {
                        isCharging -> "⚡ Charging"
                        power > 50f -> "🚀 High Power"
                        power > 0f -> "🔋 Driving"
                        power < 0f -> "♻️ Regen"
                        else -> "🅿️ Parked"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// Helper extension
private fun Float.format(decimals: Int) = "%.${decimals}f".format(this)

/**
 * Compact Energy Flow - Simplified version for smaller spaces
 */
@Composable
fun CompactEnergyFlow(
    power: Float,
    modifier: Modifier = Modifier
) {
    val isRegenerating = power < 0
    val arrowColor = if (isRegenerating) Color(0xFFFFAA00) else Color(0xFF00FF88)
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.BatteryChargingFull,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color(0xFF888888)
        )
        
        Text(
            text = if (isRegenerating) "←" else "→",
            fontSize = 20.sp,
            color = arrowColor,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "${kotlin.math.abs(power).format(1)} kW",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = arrowColor
        )
        
        Text(
            text = if (isRegenerating) "→" else "←",
            fontSize = 20.sp,
            color = arrowColor,
            fontWeight = FontWeight.Bold
        )
        
        Icon(
            imageVector = Icons.Filled.ElectricCar,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = arrowColor
        )
    }
}
