package com.byd.tripstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glassmorphic Card - Modern semi-transparent card with blur effect
 * 
 * Creates a "frosted glass" appearance popular in modern UI design.
 * Perfect for dashboard cards, statistics, and overlay content.
 * 
 * Features:
 * - Semi-transparent background
 * - Subtle border with gradient
 * - Optional blur effect (simulated)
 * - Elevated shadow
 * 
 * @param modifier Compose modifier
 * @param cornerRadius Corner radius of the card
 * @param borderWidth Width of the card border
 * @param backgroundColor Base color (will be made semi-transparent)
 * @param elevation Shadow elevation
 * @param content Card content composable
 */
@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderWidth: Dp = 1.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = elevation
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Glassmorphic background (semi-transparent)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.7f),
                            backgroundColor.copy(alpha = 0.5f)
                        )
                    ),
                    shape = shape
                )
                // Subtle border with gradient
                .border(
                    width = borderWidth,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.2f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = shape
                )
                .clip(shape),
            content = content
        )
    }
}

/**
 * Stats Card - Glassmorphic card optimized for displaying statistics
 * 
 * Pre-configured for displaying key metrics with icon, label, and value.
 */
@Composable
fun StatsGlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF00D4FF),
    content: @Composable ColumnScope.() -> Unit
) {
    GlassmorphicCard(
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        cornerRadius = 16.dp,
        elevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content
        )
    }
}

/**
 * Premium Card - Enhanced glassmorphic card with accent glow
 * 
 * Features an accent color glow effect for important information.
 */
@Composable
fun PremiumGlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF00D4FF),
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        // Glow effect layer
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(16.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
        )
        
        // Glass card on top
        GlassmorphicCard(
            modifier = Modifier.matchParentSize(),
            cornerRadius = 20.dp,
            borderWidth = 1.5.dp,
            backgroundColor = MaterialTheme.colorScheme.surface,
            content = content
        )
    }
}

/**
 * Dashboard Section Card - Large glassmorphic section for dashboard
 * 
 * Perfect for grouping related information with a header.
 */
@Composable
fun DashboardSectionCard(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassmorphicCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        cornerRadius = 20.dp,
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            title()
            content()
        }
    }
}

/**
 * Compact Info Card - Small glassmorphic card for single metrics
 * 
 * Ideal for displaying one piece of information prominently.
 */
@Composable
fun CompactGlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit
) {
    GlassmorphicCard(
        modifier = modifier,
        backgroundColor = accentColor.copy(alpha = 0.1f),
        cornerRadius = 12.dp,
        borderWidth = 1.dp,
        elevation = 2.dp
    ) {
        Box(
            modifier = Modifier.padding(12.dp),
            content = content
        )
    }
}

/**
 * Dark Glassmorphic Card - Optimized for dark mode
 * 
 * Enhanced contrast and visibility for dark backgrounds.
 */
@Composable
fun DarkGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val darkBackground = Color(0xFF1A1A1A)
    
    GlassmorphicCard(
        modifier = modifier,
        backgroundColor = darkBackground,
        cornerRadius = 20.dp,
        borderWidth = 1.dp,
        elevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Subtle inner glow
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        radius = 500f
                    )
                )
                .padding(20.dp),
            content = content
        )
    }
}
