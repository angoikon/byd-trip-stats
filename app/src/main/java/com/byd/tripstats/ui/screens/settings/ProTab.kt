package com.byd.tripstats.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.entitlement.RedeemResult
import com.byd.tripstats.ui.theme.BydElectricAzure

/**
 * The "Pro" settings tab — the unlock/status card, and what each Pro feature does (Cards
 * dashboard layout, Neon theme, cell-imbalance alert). The settings themselves live with the
 * others of their kind — Theme and Dashboard Layout on Preferences → Appearance, the alert on
 * Preferences → Battery — shown to everyone, with locked options that route here.
 */
@Composable
internal fun ProTab() {
    val context = LocalContext.current

    val isPro by EntitlementManager.isPro.collectAsState()
    val hasSavedCode by EntitlementManager.hasSavedCode.collectAsState()
    val currentDeviceId by EntitlementManager.currentDeviceId.collectAsState()
    var showLicenseDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(icon = Icons.Filled.WorkspacePremium, title = stringResource(R.string.settings_tab_pro))

        // Unlock / status card.
        ProUnlockCard(
            isPro = isPro,
            currentDeviceId = currentDeviceId,
            hasSavedCode = hasSavedCode,
            onEnterCode = { showLicenseDialog = true },
        )

        // ── Cards dashboard layout (informational, with preview) ──────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.pro_cards_layout_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isPro) {
                        Spacer(Modifier.width(8.dp))
                        ProBadge()
                    }
                }
                Image(
                    painter = painterResource(R.drawable.cards_neon),
                    contentDescription = stringResource(R.string.pro_cards_layout_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth
                )
                Text(
                    stringResource(R.string.pro_cards_layout_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Neon theme (informational) ────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.pro_neon_theme_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isPro) {
                        Spacer(Modifier.width(8.dp))
                        ProBadge()
                    }
                }
                Text(
                    stringResource(R.string.pro_neon_theme_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Cell imbalance alert (informational — the switch is in Preferences → Battery) ─
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.pref_cell_imbalance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isPro) {
                        Spacer(Modifier.width(8.dp))
                        ProBadge()
                    }
                }
                Text(
                    stringResource(R.string.pro_cell_imbalance_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showLicenseDialog) {
        var codeInput by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Unlock BYD Trip Stats Pro", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter the unlock code you received after purchase. It's a short, " +
                            "vehicle-specific code, checked on-device — nothing leaves your car.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it; errorMsg = null },
                        label = { Text("Unlock code") },
                        singleLine = true,
                        isError = errorMsg != null
                    )
                    if (errorMsg != null) {
                        Text(
                            errorMsg!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (EntitlementManager.redeem(codeInput)) {
                            RedeemResult.SUCCESS -> {
                                android.widget.Toast.makeText(
                                    context, "Pro unlocked ✓", android.widget.Toast.LENGTH_SHORT
                                ).show()
                                showLicenseDialog = false
                            }
                            RedeemResult.INVALID ->
                                errorMsg = "That code isn't valid for this vehicle."
                            RedeemResult.NO_VEHICLE_YET ->
                                errorMsg = "Start the car so the app can read your Vehicle ID, then try again."
                            RedeemResult.UNAVAILABLE ->
                                errorMsg = "Pro verification is unavailable in this build."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { showLicenseDialog = false }) { Text("Cancel") }
            }
        )
    }
}
