package com.byd.tripstats.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.entitlement.RedeemResult
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.ui.theme.BydElectricAzure
import kotlinx.coroutines.launch

/**
 * The "Pro" settings tab — gathers everything Pro in one place: the unlock/status card,
 * information about the Pro-only features (Cards dashboard layout, Neon theme), and the
 * cell-imbalance alert. The Preferences tab still hosts the actual Theme and Dashboard
 * Layout pickers (with locked options that route here).
 */
@Composable
internal fun ProTab(preferencesManager: PreferencesManager) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val isPro by EntitlementManager.isPro.collectAsState()
    val hasSavedCode by EntitlementManager.hasSavedCode.collectAsState()
    val currentDeviceId by EntitlementManager.currentDeviceId.collectAsState()
    var showLicenseDialog by remember { mutableStateOf(false) }

    val cellImbalanceAlertEnabled by preferencesManager.cellImbalanceAlertEnabled.collectAsState(
        initial = preferencesManager.getCachedCellImbalanceAlertEnabled()
    )
    val cellImbalanceThresholdV by preferencesManager.cellImbalanceThresholdV.collectAsState(
        initial = preferencesManager.getCachedCellImbalanceThresholdV()
    )
    var showCellImbalanceThresholdDialog by remember { mutableStateOf(false) }

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

        // ── Cell imbalance alert ──────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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
                            stringResource(R.string.cell_imbalance_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isPro) {
                        BrandSwitch(
                            checked = cellImbalanceAlertEnabled,
                            onCheckedChange = {
                                scope.launch { preferencesManager.saveCellImbalanceAlertEnabled(it) }
                            },
                        )
                    } else {
                        IconButton(onClick = { showLicenseDialog = true }) {
                            Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.unlock_pro_action))
                        }
                    }
                }
                Text(
                    stringResource(R.string.cell_imbalance_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isPro && cellImbalanceAlertEnabled) {
                    Text(
                        stringResource(R.string.current_limit_value, "%.0f".format(cellImbalanceThresholdV * 1000)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { showCellImbalanceThresholdDialog = true }) {
                        Icon(Icons.Filled.BatteryAlert, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.change_limit_action))
                    }
                } else if (!isPro) {
                    Text(
                        stringResource(R.string.pro_feature_imbalance_msg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { showLicenseDialog = true }) {
                        Icon(Icons.Filled.Lock, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.unlock_pro_action))
                    }
                }
            }
        }
    }

    if (showCellImbalanceThresholdDialog) {
        var thresholdInput by remember(cellImbalanceThresholdV) {
            mutableStateOf("%.0f".format(cellImbalanceThresholdV * 1000))
        }
        AlertDialog(
            onDismissRequest = { showCellImbalanceThresholdDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.imbalance_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.imbalance_input_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it },
                        label = { Text(stringResource(R.string.limit_mv_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mv = thresholdInput.replace(',', '.').toDoubleOrNull()
                        if (mv != null) {
                            scope.launch { preferencesManager.saveCellImbalanceThresholdV(mv / 1000.0) }
                        }
                        showCellImbalanceThresholdDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCellImbalanceThresholdDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
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
