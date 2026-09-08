package com.byd.tripstats.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.data.config.CarCatalog
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.ui.components.BrandNavigationBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitializationScreen(
    onContinue: suspend (car: CarConfig) -> Unit
) {
    var selectedCar by remember { mutableStateOf<CarConfig?>(null) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val canContinue = selectedCar != null

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.select_car_label),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = { BrandNavigationBar() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.choose_model_title),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(R.string.model_saved_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            CarSelectionPicker(
                categories = listOf(
                    stringResource(R.string.car_type_bev) to CarCatalog.groupedBev,
                    stringResource(R.string.car_type_phev) to CarCatalog.groupedPhev
                ),
                selectedCarId = selectedCar?.id,
                onCarSelected = { selectedCar = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val car = selectedCar ?: return@Button
                    scope.launch {
                        onContinue(car)
                    }
                },
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.continue_action))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Shared car selection composables ─────────────────────────────────────────
// Used by InitializationScreen (compact=false) and the Dashboard change-car
// dialog (compact=true).

@Composable
fun CarSelectionPicker(
    categories: List<Pair<String, Map<String, List<CarConfig>>>>,
    selectedCarId: String?,
    onCarSelected: (CarConfig) -> Unit,
    compact: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEachIndexed { index, (title, groups) ->
            val containsSelected = groups.values.flatten().any { it.id == selectedCarId }
            // BEV (index 0) open by default; PHEV closed unless it holds the selection
            val defaultExpanded = index == 0 || containsSelected
            CollapsibleCategorySection(
                title = title,
                defaultExpanded = defaultExpanded,
                groups = groups,
                selectedCarId = selectedCarId,
                onCarSelected = onCarSelected,
                compact = compact
            )
        }
    }
}

@Composable
private fun CollapsibleCategorySection(
    title: String,
    defaultExpanded: Boolean,
    groups: Map<String, List<CarConfig>>,
    selectedCarId: String?,
    onCarSelected: (CarConfig) -> Unit,
    compact: Boolean
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val chevron by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "cat")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.rotate(chevron)
            )
        }

        HorizontalDivider()

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                groups.forEach { (groupTitle, cars) ->
                    CollapsibleGroupSection(
                        title = groupTitle,
                        cars = cars,
                        selectedCarId = selectedCarId,
                        onCarSelected = onCarSelected,
                        compact = compact
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsibleGroupSection(
    title: String,
    cars: List<CarConfig>,
    selectedCarId: String?,
    onCarSelected: (CarConfig) -> Unit,
    compact: Boolean
) {
    val containsSelected = cars.any { it.id == selectedCarId }
    var expanded by remember { mutableStateOf(containsSelected) }
    val chevron by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "grp")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${cars.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevron),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(
                    start = if (compact) 0.dp else 4.dp,
                    end = if (compact) 0.dp else 4.dp,
                    bottom = 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)
            ) {
                cars.forEach { car ->
                    if (compact) {
                        CompactCarRow(
                            car = car,
                            selected = selectedCarId == car.id,
                            onClick = { onCarSelected(car) }
                        )
                    } else {
                        CarOptionCard(
                            car = car,
                            selected = selectedCarId == car.id,
                            onClick = { onCarSelected(car) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCarRow(
    car: CarConfig,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = car.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
            val rangeLabel = if (car.isPhev) stringResource(R.string.ev_range_label) else stringResource(R.string.wltp_label)
            Text(
                text = "$rangeLabel: ${car.wltpKm} km | ${car.drivetrain}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CarOptionCard(
    car: CarConfig,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.DirectionsCar,
                contentDescription = null
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = car.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val evRangeLabel = stringResource(R.string.ev_range_label)
                val wltpLabel = stringResource(R.string.wltp_label)
                val subtitle = if (car.isPhev) {
                    "$evRangeLabel: ${car.wltpKm} km | ${car.phevUsableBatteryKwh ?: car.batteryKwh} kWh | ${car.drivetrain}"
                } else {
                    "$wltpLabel: ${car.wltpKm} km | ${car.batteryKwh} kWh | ${car.drivetrain}"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RadioButton(
                selected = selected,
                onClick = onClick
            )
        }
    }
}
