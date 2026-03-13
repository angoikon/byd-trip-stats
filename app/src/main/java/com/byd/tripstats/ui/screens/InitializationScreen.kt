package com.byd.tripstats.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.data.config.CarCatalog
import com.byd.tripstats.data.config.CarConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitializationScreen(
    initialTopic: String = "",
    onContinue: suspend (car: CarConfig, topic: String) -> Unit
) {
    var selectedCar by remember { mutableStateOf<CarConfig?>(null) }
    var topic by remember(initialTopic) { mutableStateOf(initialTopic) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val TOPIC_PREFIX = "electro/telemetry/"

    var topicSuffix by remember(initialTopic) {
        mutableStateOf(initialTopic.removePrefix(TOPIC_PREFIX))
    }

    val topicTrimmed = TOPIC_PREFIX + topicSuffix.trim()
    val canContinue = selectedCar != null && topicSuffix.trim().isNotEmpty()

    val groupedCars = remember {
        linkedMapOf(
            "BYD Seal" to listOf(
                CarCatalog.BYD_SEAL_DYNAMIC_RWD,
                CarCatalog.BYD_SEAL_PREMIUM_RWD,
                CarCatalog.BYD_SEAL_EXCELLENCE
            ),
            "BYD Dolphin" to listOf(
                CarCatalog.BYD_DOLPHIN_STANDARD,
                CarCatalog.BYD_DOLPHIN_EXTENDED
            ),
            "BYD ATTO 3" to listOf(
                CarCatalog.BYD_ATTO_3
            )
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Initialization screen",
                        fontWeight = FontWeight.Bold
                    )
                },
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
                text = "1) Choose the BYD model you drive",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "This selection will be saved and used to load the correct car configuration",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            groupedCars.forEach { (groupTitle, cars) ->
                CarGroupSection(
                    title = groupTitle,
                    cars = cars,
                    selectedCarId = selectedCar?.id,
                    onCarClick = { selectedCar = it }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "2) Input the electro topic you use",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = topicSuffix,
                onValueChange = { topicSuffix = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("MQTT Topic") },
                prefix = { Text("electro/telemetry/", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                placeholder = { Text("byd-seal/data") },
                supportingText = {
                    Text("Required. Input the suffix from Electro → Integrations → MQTT")
                }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "The topic is custom, based on the name you gave at the MQTT integration. If you input it wrong, the broker may still connect, but no telemetry will arrive.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val car = selectedCar ?: return@Button
                    scope.launch {
                        onContinue(car, topicTrimmed)
                    }
                },
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CarGroupSection(
    title: String,
    cars: List<CarConfig>,
    selectedCarId: String?,
    onCarClick: (CarConfig) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        cars.forEach { car ->
            CarOptionCard(
                car = car,
                selected = selectedCarId == car.id,
                onClick = { onCarClick(car) }
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
                Text(
                    text = "WLTP: ${car.wltpKm} km | ${car.batteryKwh} kWh | ${car.drivetrain}",
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