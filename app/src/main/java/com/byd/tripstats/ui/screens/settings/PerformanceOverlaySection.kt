package com.byd.tripstats.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.ui.components.PerformanceOverlaySettings

/**
 * The performance overlay's switch, as its own section on the App Diagnostics page: it has
 * nothing to do with the diagnostic details or the log above it. The overlay has its own
 * sampler, not [AppDiagnosticsMonitor]'s, which takes every thread's stack each refresh and
 * would show up in the CPU figure the overlay is there to read.
 */
@Composable
internal fun PerformanceOverlaySection() {
    val context = LocalContext.current
    val overlayEnabled by PerformanceOverlaySettings.enabled.collectAsState()

    LaunchedEffect(context) {
        PerformanceOverlaySettings.initialize(context)
    }

    SectionHeader(icon = Icons.Filled.Speed, title = stringResource(R.string.performance_overlay_label))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.performance_overlay_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            BrandSwitch(
                checked = overlayEnabled,
                onCheckedChange = { PerformanceOverlaySettings.setEnabled(context, it) },
            )
        }
    }
}
