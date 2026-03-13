package com.byd.tripstats.data.config

enum class Drivetrain {
    FWD,
    RWD,
    AWD
}

data class CarConfig(
    val id: String,
    val displayName: String,
    val drivetrain: Drivetrain,
    val batteryKwh: Double,
    val wltpKm: Int,
    val referenceConsumptionKwhPer100km: Double,
    val frontTyrePressureBar: Double,
    val rearTyrePressureBar: Double
)

// The reference consumption (kWh/100km) is taken via ev-database.org
object CarCatalog {

    val BYD_SEAL_DYNAMIC_RWD = CarConfig(
        id = "BYD_SEAL_DYNAMIC",
        displayName = "BYD Seal Dynamic",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 61.4,
        wltpKm = 460,
        referenceConsumptionKwhPer100km = 17.2,
        frontTyrePressureBar = 2.6,
        rearTyrePressureBar = 2.9
    )

    val BYD_SEAL_PREMIUM_RWD = CarConfig(
        id = "BYD_SEAL_PREMIUM",
        displayName = "BYD Seal Premium",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 82.5,
        wltpKm = 570,
        referenceConsumptionKwhPer100km = 17.2,
        frontTyrePressureBar = 2.6,
        rearTyrePressureBar = 2.9
    )

    val BYD_SEAL_EXCELLENCE = CarConfig(
        id = "BYD_SEAL_EXCELLENCE",
        displayName = "BYD Seal Excellence",
        drivetrain = Drivetrain.AWD,
        batteryKwh = 82.5,
        wltpKm = 520,
        referenceConsumptionKwhPer100km = 18.5,
        frontTyrePressureBar = 2.6,
        rearTyrePressureBar = 2.9
    )

    val BYD_DOLPHIN_STANDARD = CarConfig(
        id = "BYD_DOLPHIN_STANDARD",
        displayName = "BYD Dolphin Active / Boost",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 44.9,
        wltpKm = 340,
        referenceConsumptionKwhPer100km = 17.3,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5
    )

    val BYD_DOLPHIN_EXTENDED = CarConfig(
        id = "BYD_DOLPHIN_EXTENDED",
        displayName = "BYD Dolphin Extended / Comfort / Design",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 60.4,
        wltpKm = 427,
        referenceConsumptionKwhPer100km = 17.3,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5
    )

    val BYD_ATTO_3 = CarConfig(
        id = "BYD_ATTO_3",
        displayName = "BYD ATTO 3",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 60.4,
        wltpKm = 420,
        referenceConsumptionKwhPer100km = 18.3,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5
    )

    val allCars: List<CarConfig> = listOf(
        BYD_SEAL_DYNAMIC_RWD,
        BYD_SEAL_PREMIUM_RWD,
        BYD_SEAL_EXCELLENCE,
        BYD_DOLPHIN_STANDARD,
        BYD_DOLPHIN_EXTENDED,
        BYD_ATTO_3
    )

    fun fromId(id: String?): CarConfig? {
        return allCars.firstOrNull { it.id == id }
    }
}