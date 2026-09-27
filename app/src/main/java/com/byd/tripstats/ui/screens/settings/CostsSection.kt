package com.byd.tripstats.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.viewmodel.DashboardViewModel

// Sentinel used as the "code" of the free-text currency choice, so a user-entered symbol
// (kr, zł, ₺, Fr, …) can be persisted and detected without waiting for it to be added officially.
private const val CURRENCY_CUSTOM = "Custom"

/** Preferences → Costs: the electricity tariff and currency used to price trips and charges. */
@Composable
internal fun CostsSection(viewModel: DashboardViewModel) {
    val electricityPrice by viewModel.electricityPricePerKwh.collectAsState()
    val currencySymbol by viewModel.currencySymbol.collectAsState()
    var showTariffDialog by remember { mutableStateOf(false) }
    var priceInput by remember(electricityPrice) {
        mutableStateOf(if (electricityPrice > 0.0) "%.4f".format(electricityPrice) else "")
    }
    val currencyOptions = remember {
        listOf(
            "€" to "EUR",
            "£" to "GBP",
            "$" to "USD",
            "A$" to "AUD",
            "฿" to "THB",
            "R$" to "BRL",
            "RM" to "MYR"
        )
    }
    var currencyMenuExpanded by remember { mutableStateOf(false) }
    var selectedCurrency by remember(currencySymbol) {
        mutableStateOf(
            currencyOptions.firstOrNull { it.first == currencySymbol }
                ?: (currencySymbol to CURRENCY_CUSTOM)   // a previously-saved custom symbol round-trips as Custom
        )
    }
    // Free-text symbol backing the "Custom…" choice; seeded from a saved custom symbol so it persists.
    var customCurrencyInput by remember(currencySymbol) {
        mutableStateOf(if (currencyOptions.none { it.first == currencySymbol }) currencySymbol else "")
    }

    SectionHeader(icon = Icons.Filled.Payments, title = stringResource(R.string.section_costs))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.pref_electricity_tariff),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (electricityPrice > 0.0) {
                    stringResource(R.string.current_rate_value, "%.4f".format(electricityPrice), currencySymbol)
                } else {
                    stringResource(R.string.tariff_not_set_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { showTariffDialog = true }) {
                Icon(Icons.Filled.Euro, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (electricityPrice > 0.0) stringResource(R.string.edit_tariff_action) else stringResource(R.string.set_tariff_action))
            }
        }
    }

    if (showTariffDialog) {
        AlertDialog(
            onDismissRequest = { showTariffDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.tariff_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.tariff_input_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = priceInput,
                        onValueChange = { priceInput = it },
                        label = { Text(stringResource(R.string.price_per_kwh_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Box {
                        OutlinedTextField(
                            value = "${selectedCurrency.first} (${selectedCurrency.second})",
                            onValueChange = { },
                            label = { Text(stringResource(R.string.currency_label)) },
                            singleLine = true,
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = { currencyMenuExpanded = !currencyMenuExpanded }) {
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.currency_label))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = currencyMenuExpanded,
                            onDismissRequest = { currencyMenuExpanded = false },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 0.dp
                        ) {
                            currencyOptions.forEach { (symbol, code) ->
                                DropdownMenuItem(
                                    text = { Text("$code ($symbol)") },
                                    onClick = {
                                        selectedCurrency = symbol to code
                                        currencyMenuExpanded = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.currency_custom_option)) },
                                onClick = {
                                    selectedCurrency = customCurrencyInput to CURRENCY_CUSTOM
                                    currencyMenuExpanded = false
                                }
                            )
                        }
                    }
                    if (selectedCurrency.second == CURRENCY_CUSTOM) {
                        OutlinedTextField(
                            value = customCurrencyInput,
                            onValueChange = {
                                // Validate as you type: a currency symbol is short and carries no digits
                                // or whitespace (kr, zł, A$, ₺, Fr, Kč…). Drop disallowed characters and
                                // cap at 4. The saved symbol is used verbatim for display everywhere.
                                customCurrencyInput = it.filterNot { c -> c.isDigit() || c.isWhitespace() }.take(4)
                                selectedCurrency = customCurrencyInput to CURRENCY_CUSTOM
                            },
                            label = { Text(stringResource(R.string.currency_custom_label)) },
                            placeholder = { Text(stringResource(R.string.currency_custom_placeholder)) },
                            supportingText = { Text(stringResource(R.string.currency_custom_hint)) },
                            isError = customCurrencyInput.isBlank(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (electricityPrice > 0.0) {
                        Text(
                            stringResource(R.string.active_tariff_value, "%.4f".format(electricityPrice), currencySymbol),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val price = priceInput.replace(',', '.').toDoubleOrNull()
                        // Never persist an empty custom symbol — keep the previous one if left blank.
                        val symbol = selectedCurrency.first.ifBlank { currencySymbol }
                        viewModel.saveElectricityPrice(price ?: 0.0, symbol)
                        showTariffDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showTariffDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
