package com.byd.tripstats.ui.screens.dashboard

import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.model.VehicleTelemetry

/**
 * Petrol range for the dashboard on PHEV cars, in km.
 *
 * Prefers the car's own cluster fuel-range reading; when that isn't reported
 * (0 / sentinel filtered upstream) it estimates from fuel % × tank size ÷ the
 * car's own lifetime average fuel consumption. Null on BEVs or when neither
 * source is available — callers hide the fuel figure entirely then.
 */
fun phevFuelRangeKm(telemetry: VehicleTelemetry, car: CarConfig?): Int? {
    if (car?.isPhev != true) return null
    telemetry.fuelDrivingRangeKm.takeIf { it in 1..1000 }?.let { return it }
    val tankLiters = car.fuelTankLiters ?: return null
    val fuelPct = telemetry.fuelPercentage.takeIf { it in 1..100 } ?: return null
    val avgLPer100 = telemetry.avgFuelConsumption?.takeIf { it in 1.0..30.0 } ?: return null
    return (fuelPct / 100.0 * tankLiters / avgLPer100 * 100.0).toInt()
}
