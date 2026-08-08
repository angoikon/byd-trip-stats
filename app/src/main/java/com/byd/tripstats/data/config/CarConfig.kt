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
    /** Approximate EU kerb mass used for gradient-energy estimation. */
    val estimatedKerbMassKg: Double? = null,
    val wltpKm: Int,
    val referenceConsumptionKwhPer100km: Double,
    val frontTyrePressureBar: Double,
    val rearTyrePressureBar: Double,
    val frontMotorRatedKw: Int? = null,
    val rearMotorRatedKw: Int? = null,
    val cellCount: Int = 0,
    /**
     * Aerodynamic drag area: drag coefficient (Cd) × frontal area (m²).
     * Used for aerodynamic drag energy estimation in the trip energy breakdown.
     * Typical range for BYD EVs: 0.55–0.75 m².
     */
    val cdA: Double? = null,
    /** True for plug-in hybrids. Gates fuel/ICE fields throughout the app. */
    val isPhev: Boolean = false,
    /**
     * Usable traction battery capacity in kWh for PHEV models.
     * Null on BEVs (batteryKwh is the full pack there).
     * On PHEVs, batteryKwh is the gross pack size; this is the usable EV-only portion.
     */
    val phevUsableBatteryKwh: Double? = null,
    /**
     * Petrol tank capacity in litres (PHEV only). Used to estimate fuel range from
     * fuel % × tank ÷ learned L/100km when the car's own fuel-range reading is
     * unavailable. Null on BEVs and on PHEVs whose tank size isn't confirmed —
     * the fuel-range display then relies on the car-reported figure alone.
     */
    val fuelTankLiters: Double? = null,
)

// The reference consumption (kWh/100km) is taken via ev-database.org
object CarCatalog {

    val BYD_SEAL_DYNAMIC_RWD = CarConfig(
        id = "BYD_SEAL_DYNAMIC",
        displayName = "Seal Dynamic",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 61.4,
        estimatedKerbMassKg = 2045.0,
        wltpKm = 460,
        referenceConsumptionKwhPer100km = 17.2,
        frontTyrePressureBar = 2.6,
        rearTyrePressureBar = 2.9,
        cellCount = 128,  // 61.4 kWh @ ~410V → 128S LFP
        cdA = 0.568       // Cd 0.219 × A 2.59 m²
    )

    val BYD_SEAL_PREMIUM_RWD = CarConfig(
        id = "BYD_SEAL_PREMIUM",
        displayName = "Seal Premium",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 82.5,
        estimatedKerbMassKg = 2130.0,
        wltpKm = 570,
        referenceConsumptionKwhPer100km = 17.2,
        frontTyrePressureBar = 2.6,
        rearTyrePressureBar = 2.9,
        cellCount = 172,  // 82.5 kWh @ ~554V → 172S LFP
        cdA = 0.568       // Cd 0.219 × A 2.59 m²
    )

    val BYD_SEAL_EXCELLENCE = CarConfig(
        id = "BYD_SEAL_EXCELLENCE",
        displayName = "Seal Excellence",
        drivetrain = Drivetrain.AWD,
        batteryKwh = 82.5,
        estimatedKerbMassKg = 2260.0,
        wltpKm = 520,
        referenceConsumptionKwhPer100km = 18.5,
        frontTyrePressureBar = 2.6,
        rearTyrePressureBar = 2.9,
        frontMotorRatedKw = 160,
        rearMotorRatedKw = 230,
        cellCount = 172,  // 82.5 kWh @ ~554V → 172S LFP
        cdA = 0.568       // Cd 0.219 × A 2.59 m²
    )

    val BYD_DOLPHIN_STANDARD = CarConfig(
        id = "BYD_DOLPHIN_STANDARD",
        displayName = "Dolphin Active / Boost",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 44.9,
        estimatedKerbMassKg = 1650.0,
        wltpKm = 340,
        referenceConsumptionKwhPer100km = 17.3,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        cellCount = 104,  // 44.9 kWh @ ~333V → 104S LFP
        cdA = 0.682       // Cd 0.29 × A 2.35 m²
    )

    val BYD_DOLPHIN_EXTENDED = CarConfig(
        id = "BYD_DOLPHIN_EXTENDED",
        displayName = "Dolphin Extended / Comfort / Design",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 60.4,
        estimatedKerbMassKg = 1733.0,
        wltpKm = 427,
        referenceConsumptionKwhPer100km = 17.3,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        cellCount = 126,  // 60.4 kWh @ ~403V → 126S LFP
        cdA = 0.682       // Cd 0.29 × A 2.35 m²
    )

    // Atto 1 = export name (Asia-Pacific / LatAm) of the Seagull with the
    // China-spec battery packs (30.08 / 38.88 kWh) and Dynamic / Premium trims.
    // Distinct from the European Dolphin Surf, which uses 30.0 / 43.2 kWh packs.
    // Specs per the official BYD Atto 1 spec sheet.
    val BYD_SEAGULL_ACTIVE = CarConfig(
        id = "BYD_SEAGULL_ACTIVE",
        displayName = "Atto 1 Dynamic",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 30.08,
        estimatedKerbMassKg = 1225.0,
        wltpKm = 230,
        referenceConsumptionKwhPer100km = 15.8,
        frontTyrePressureBar = 2.7,  // door-sticker (185/55 R16)
        rearTyrePressureBar = 2.7,   // door-sticker (185/55 R16)
        frontMotorRatedKw = 45,
        cellCount = 94,   // 30.08 kWh @ ~300V -> 94S LFP
        cdA = 0.653
    )

    val BYD_SEAGULL_FLYING = CarConfig(
        id = "BYD_SEAGULL_FLYING",
        displayName = "Atto 1 Premium",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 38.88,
        estimatedKerbMassKg = 1280.0,
        wltpKm = 300,
        referenceConsumptionKwhPer100km = 15.8,
        frontTyrePressureBar = 2.7,  // door-sticker (185/55 R16)
        rearTyrePressureBar = 2.7,   // door-sticker (185/55 R16)
        frontMotorRatedKw = 45,
        cellCount = 120,  // 38.88 kWh @ ~384V -> 120S LFP
        cdA = 0.653
    )

    val BYD_ATTO_2_ACTIVE = CarConfig(
        id = "BYD_ATTO_2_ACTIVE",
        displayName = "Atto 2 Active",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 45.1,
        estimatedKerbMassKg = 1570.0,
        wltpKm = 312,
        referenceConsumptionKwhPer100km = 18.7,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 130,
        cellCount = 94,   // EV Database: 45.1 kWh usable, 94 cells, 400V architecture
        cdA = 0.700       // estimate for compact SUV body
    )

    val BYD_ATTO_2_BOOST = CarConfig(
        id = "BYD_ATTO_2_BOOST",
        displayName = "Atto 2 Boost",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 51.1,
        estimatedKerbMassKg = 1610.0,
        wltpKm = 344,
        referenceConsumptionKwhPer100km = 18.7,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 130,
        cellCount = 100,  // estimate: interpolated between Active (94S) and Comfort (112S); 51.1 kWh @ ~320V → 100S LFP
        cdA = 0.700       // estimate for compact SUV body
    )

    val BYD_ATTO_2_COMFORT = CarConfig(
        id = "BYD_ATTO_2_COMFORT",
        displayName = "Atto 2 Comfort",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 64.8,
        estimatedKerbMassKg = 1720.0,
        wltpKm = 430,
        referenceConsumptionKwhPer100km = 19.1,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 150,
        cellCount = 112,  // EV Database: 64.8 kWh usable, 112 cells, 400V architecture
        cdA = 0.700       // estimate for compact SUV body
    )

    // The three Atto 3 packs are exact multiples of BYD's 0.48 kWh blade cell —
    // 104S = 49.92, 126S = 60.48, 156S = 74.88 — so the cell counts below are
    // derived, not estimated. Same body/aero across the range.
    val BYD_ATTO_3_SR = CarConfig(
        id = "BYD_ATTO_3_SR",
        displayName = "Atto 3 SR",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 49.92,
        estimatedKerbMassKg = 1760.0,   // estimate: ~65 kg under the 60 kWh car
        wltpKm = 345,
        referenceConsumptionKwhPer100km = 18.3,   // same body as the 60 kWh car
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 150,
        cellCount = 104,  // 49.92 kWh = 104 × 0.48 kWh blade cell
        cdA = 0.694       // Cd 0.272 × A 2.55 m²
    )

    val BYD_ATTO_3 = CarConfig(
        id = "BYD_ATTO_3",
        displayName = "Atto 3",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 60.4,
        estimatedKerbMassKg = 1825.0,
        wltpKm = 420,
        referenceConsumptionKwhPer100km = 18.3,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        cellCount = 126,  // 60.4 kWh @ ~403V → 126S LFP
        cdA = 0.694       // Cd 0.272 × A 2.55 m²
    )

    // Ships with DiLink 5
    val BYD_ATTO_3_PREMIUM = CarConfig(
        id = "BYD_ATTO_3_PREMIUM",
        displayName = "Atto 3 Premium",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 74.88,
        estimatedKerbMassKg = 1915.0,   // estimate: ~90 kg over the 60 kWh car
        wltpKm = 520,                   // estimate: scaled from the 60 kWh car at equal efficiency
        referenceConsumptionKwhPer100km = 18.3,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 150,
        cellCount = 156,  // 74.88 kWh = 156 × 0.48 kWh blade cell
        cdA = 0.694       // Cd 0.272 × A 2.55 m²
    )

    val BYD_SEAL_U_COMFORT = CarConfig(
        id = "BYD_SEAL_U_COMFORT",
        displayName = "Seal U Comfort",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 71.8,
        estimatedKerbMassKg = 2095.0,
        wltpKm = 420,
        referenceConsumptionKwhPer100km = 19.9,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.9,
        cellCount = 184,  // 71.8 kWh @ ~589V → 184S LFP
        cdA = 0.762       // Cd 0.28 × A 2.72 m²
    )

    val BYD_SEAL_U_DESIGN = CarConfig(
        id = "BYD_SEAL_U_DESIGN",
        displayName = "Seal U Design",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 87.0,
        estimatedKerbMassKg = 2222.0,
        wltpKm = 500,
        referenceConsumptionKwhPer100km = 20.5,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.9,
        cellCount = 207,  // 87.0 kWh @ ~663V → 207S LFP (estimate)
        cdA = 0.762       // Cd 0.28 × A 2.72 m² (estimate, same body as Comfort)
    )

    val BYD_DOLPHIN_SURF_ACTIVE = CarConfig(
        id = "BYD_DOLPHIN_SURF_ACTIVE",
        displayName = "Seagull / Dolphin Surf Active",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 30.0,
        estimatedKerbMassKg = 1294.0,
        wltpKm = 220,
        referenceConsumptionKwhPer100km = 13.6,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        cellCount = 94,   // 30.0 kWh @ 301V → 99 Ah (ev-database)
        cdA = 0.653       // Cd 0.29 × A 2.25 m² (estimate)
    )

    val BYD_DOLPHIN_SURF_BOOST = CarConfig(
        id = "BYD_DOLPHIN_SURF_BOOST",
        displayName = "Seagull / Dolphin Surf Boost",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 43.2,
        estimatedKerbMassKg = 1370.0,
        wltpKm = 322,
        referenceConsumptionKwhPer100km = 13.4,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        cellCount = 90,   // 43.2 kWh @ 288V → 150 Ah (ev-database)
        cdA = 0.653       // Cd 0.29 × A 2.25 m² (estimate)
    )

    val BYD_DOLPHIN_SURF_COMFORT = CarConfig(
        id = "BYD_DOLPHIN_SURF_COMFORT",
        displayName = "Seagull / Dolphin Surf Comfort",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 43.2,
        estimatedKerbMassKg = 1390.0,
        wltpKm = 310,
        referenceConsumptionKwhPer100km = 13.9,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        cellCount = 90,   // 43.2 kWh @ 288V → 150 Ah (ev-database)
        cdA = 0.653       // Cd 0.29 × A 2.25 m² (estimate)
    )

    // id kept as "BYD_M6" for backward-compatibility with stored user preferences
    val BYD_M6_SUPERIOR_100KW = CarConfig(
        id = "BYD_M6",
        displayName = "M6 Superior 100kW",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 71.8,
        estimatedKerbMassKg = 1915.0,
        wltpKm = 440,
        referenceConsumptionKwhPer100km = 18.7,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 100,
        cellCount = 132,  // confirmed: 170Ah × 422.4V = 71.8kWh → 422.4/3.2 = 132S Blade LFP
        cdA = 0.868       // Cd 0.33 × A ~2.63 m² (1.81 × 1.69 × 0.86)
    )

    val BYD_M6_SUPERIOR_150KW = CarConfig(
        id = "BYD_M6_SUPERIOR_150KW",
        displayName = "M6 Superior 150kW",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 71.8,
        estimatedKerbMassKg = 1925.0,
        wltpKm = 420,
        referenceConsumptionKwhPer100km = 17.1,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 150,
        cellCount = 132,  // same 71.8kWh pack as Superior 100kW: 132S Blade LFP
        cdA = 0.868
    )

    val BYD_M6_STANDARD_120KW = CarConfig(
        id = "BYD_M6_STANDARD",
        displayName = "M6 Standard 120kW",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 55.4,
        estimatedKerbMassKg = 1870.0,
        wltpKm = 340,
        referenceConsumptionKwhPer100km = 16.5,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 120,
        cellCount = 102,  // estimate: 55.4kWh / (170Ah × 3.2V) ≈ 102S Blade LFP
        cdA = 0.868
    )

    val BYD_SEAL_6_PREMIUM_95KW = CarConfig(
        id = "BYD_SEAL_6_PREMIUM_95KW",
        displayName = "Seal 6 Premium 95kW",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 56.64,
        estimatedKerbMassKg = 1750.0,
        wltpKm = 425,               // WLTC figure; WLTP not published
        referenceConsumptionKwhPer100km = 13.3,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 95,
        cellCount = 118,  // confirmed: 150Ah × 377.6V = 56.64kWh → 377.6/3.2 = 118S Blade LFP
    )

    val BYD_SEAL_6_PREMIUM_160KW = CarConfig(
        id = "BYD_SEAL_6_PREMIUM_160KW",
        displayName = "Seal 6 Premium 160kW",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 56.64,
        estimatedKerbMassKg = 1780.0,
        wltpKm = 405,               // estimate; WLTP not published
        referenceConsumptionKwhPer100km = 14.0,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        rearMotorRatedKw = 160,
        cellCount = 118,  // same 56.64kWh pack as 95kW: 118S Blade LFP
    )

    val BYD_TANG_EV = CarConfig(
        id = "BYD_TANG_EV",
        displayName = "Tang EV",
        drivetrain = Drivetrain.AWD,
        batteryKwh = 108.8,
        estimatedKerbMassKg = 2635.0,
        wltpKm = 530,
        referenceConsumptionKwhPer100km = 22.3,
        frontTyrePressureBar = 2.6,
        rearTyrePressureBar = 2.9,
        frontMotorRatedKw = 163,
        rearMotorRatedKw = 200,
        cellCount = 204,  // 108.8 kWh @ ~640V → 204S Blade LFP (estimate)
        cdA = 0.827       // Cd 0.29 × A 2.85 m² (same body as Tang DM-i)
    )

    val BYD_SEAL_U_DM_I = CarConfig(
        id = "BYD_SEAL_U_DM_I",
        displayName = "Seal U DM-i",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 18.3,
        estimatedKerbMassKg = 2140.0,
        wltpKm = 80,            // EV-only WLTP range
        referenceConsumptionKwhPer100km = 19.0,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.9,
        cellCount = 96,         // 18.3 kWh @ ~307V → 96S LFP
        cdA = 0.925,            // Cd 0.34 × A 2.72 m²
        isPhev = true,
        phevUsableBatteryKwh = 15.2,
        fuelTankLiters = 60.0   // confirmed EU spec sheet
    )

    val BYD_SEAL_U_DM_I_COMFORT = CarConfig(
        id = "BYD_SEAL_U_DM_I_COMFORT",
        displayName = "Seal U DM-i Comfort",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 27.3,
        estimatedKerbMassKg = 2210.0,
        wltpKm = 125,           // EV-only WLTP range
        referenceConsumptionKwhPer100km = 19.5,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.9,
        frontMotorRatedKw = 145,
        cellCount = 144,        // 27.3 kWh @ ~461V → 144S LFP (estimate)
        cdA = 0.925,            // Cd 0.34 × A 2.72 m² (same body as DM-i FWD)
        isPhev = true,
        phevUsableBatteryKwh = 26.6,
        fuelTankLiters = 60.0   // confirmed EU spec sheet (shared body/tank)
    )

    val BYD_SEAL_U_DM_I_DESIGN_AWD = CarConfig(
        id = "BYD_SEAL_U_DM_I_DESIGN_AWD",
        displayName = "Seal U DM-i Design AWD",
        drivetrain = Drivetrain.AWD,
        batteryKwh = 18.3,
        estimatedKerbMassKg = 2100.0,
        wltpKm = 70,            // EV-only WLTP range
        referenceConsumptionKwhPer100km = 20.0,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.9,
        frontMotorRatedKw = 150,
        rearMotorRatedKw = 120,
        cellCount = 96,         // 18.3 kWh @ ~307V → 96S LFP (same pack as FWD)
        cdA = 0.870,            // Cd 0.32 × A 2.72 m²
        isPhev = true,
        phevUsableBatteryKwh = 15.2,
        fuelTankLiters = 60.0   // confirmed EU spec sheet (shared body/tank)
    )

    val BYD_SONG_PLUS_DM_I = CarConfig(
        id = "BYD_SONG_PLUS_DM_I",
        displayName = "Song Plus DM-i",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 18.3,
        estimatedKerbMassKg = 1880.0,
        wltpKm = 100,           // EV-only WLTP range
        referenceConsumptionKwhPer100km = 16.0,
        frontTyrePressureBar = 2.4,
        rearTyrePressureBar = 2.4,
        cellCount = 144,        // 18.3 kWh Blade pack — might be 144S configuration
        cdA = 0.774,            // Cd 0.29 × A 2.67 m²
        isPhev = true,
        phevUsableBatteryKwh = 15.2,
        fuelTankLiters = 60.0   // shared Sealion 6 platform tank
    )

    val BYD_HAN_EV = CarConfig(
        id = "BYD_HAN_EV",
        displayName = "Han EV",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 85.44,
        estimatedKerbMassKg = 2165.0,
        wltpKm = 521,
        referenceConsumptionKwhPer100km = 16.8,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        rearMotorRatedKw = 200,
        cellCount = 176,  // 85.44 kWh @ ~563V → 176S Blade LFP (176 × 3.2V × 152Ah ≈ 85.4 kWh)
        cdA = 0.601       // Cd 0.233 × A 2.58 m²
    )

    val BYD_HAN_EV_AWD = CarConfig(
        id = "BYD_HAN_EV_AWD",
        displayName = "Han EV AWD",
        drivetrain = Drivetrain.AWD,
        batteryKwh = 85.44,
        estimatedKerbMassKg = 2335.0,
        wltpKm = 466,
        referenceConsumptionKwhPer100km = 18.4,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 163,
        rearMotorRatedKw = 200,
        cellCount = 176,  // same 85.44 kWh pack as RWD: 176S Blade LFP
        cdA = 0.601       // Cd 0.233 × A 2.58 m² (same body)
    )

    val BYD_HAN_DM_I = CarConfig(
        id = "BYD_HAN_DM_I",
        displayName = "Han DM-i",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 37.5,
        estimatedKerbMassKg = 2065.0,
        wltpKm = 202,           // EV-only WLTP range
        referenceConsumptionKwhPer100km = 15.8,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        cellCount = 168,        // 37.5 kWh @ ~537V → 168S LFP (estimate)
        cdA = 0.601,            // Cd 0.233 × A 2.58 m²
        isPhev = true,
        phevUsableBatteryKwh = 34.0
    )

    val BYD_TANG_DM_I = CarConfig(
        id = "BYD_TANG_DM_I",
        displayName = "Tang DM-i",
        drivetrain = Drivetrain.AWD,
        batteryKwh = 49.0,
        estimatedKerbMassKg = 2690.0,
        wltpKm = 235,           // EV-only WLTP range
        referenceConsumptionKwhPer100km = 19.0,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.7,
        frontMotorRatedKw = 160,
        rearMotorRatedKw = 160,
        cellCount = 168,        // 49.0 kWh @ ~537V → 168S LFP (estimate)
        cdA = 0.827,            // Cd 0.29 × A 2.85 m²
        isPhev = true,
        phevUsableBatteryKwh = 44.0
    )

    // ── BYD Sealion 6 — international branding of the Song Plus platform ──────
    // DM-i variants: 18.3 kWh Blade LFP (96S), 1.5L Atkinson ICE + electric motor(s).
    // BEV variants: Song Plus EV / Song L EV exported as Sealion 6 EV in some markets.

    val BYD_SEALION_6_DMI_PREMIUM = CarConfig(
        id = "BYD_SEALION_6_DMI_PREMIUM",
        displayName = "Sealion 6 DM-i Premium",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 18.3,
        estimatedKerbMassKg = 1880.0,
        wltpKm = 92,            // EV-only WLTP range
        referenceConsumptionKwhPer100km = 17.5,
        frontTyrePressureBar = 2.4,
        rearTyrePressureBar = 2.4,
        frontMotorRatedKw = 160,
        cellCount = 144,        // 18.3 kWh Blade pack — might be 144S configuration
        cdA = 0.774,            // Cd 0.29 × A 2.67 m² (shared Song Plus body)
        isPhev = true,
        phevUsableBatteryKwh = 15.2,
        fuelTankLiters = 60.0   // confirmed AU spec sheet
    )

    val BYD_SEALION_6_DMI_PERFORMANCE = CarConfig(
        id = "BYD_SEALION_6_DMI_PERFORMANCE",
        displayName = "Sealion 6 DM-i Performance AWD",
        drivetrain = Drivetrain.AWD,
        batteryKwh = 18.3,
        estimatedKerbMassKg = 2010.0,
        wltpKm = 80,            // EV-only WLTP range
        referenceConsumptionKwhPer100km = 18.5,
        frontTyrePressureBar = 2.4,
        rearTyrePressureBar = 2.4,
        frontMotorRatedKw = 160,
        rearMotorRatedKw = 150,
        cellCount = 144,        // 18.3 kWh Blade pack — might be 144S configuration (shared with FWD)
        cdA = 0.774,            // Cd 0.29 × A 2.67 m²
        isPhev = true,
        phevUsableBatteryKwh = 15.2,
        fuelTankLiters = 60.0   // confirmed AU spec sheet
    )

    val BYD_SEALION_6_EV_STANDARD = CarConfig(
        id = "BYD_SEALION_6_EV_STANDARD",
        displayName = "Sealion 6 EV Standard",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 71.8,
        estimatedKerbMassKg = 1950.0,
        wltpKm = 430,           // ~520 km CLTC → ~430 km WLTP (estimate)
        referenceConsumptionKwhPer100km = 17.5,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 150,
        cellCount = 144,        // Sealion 6 BEV pack — might be 144S configuration (matches DM-i sibling)
        cdA = 0.774             // Cd 0.29 × A 2.67 m² (shared body)
    )

    val BYD_SEALION_6_EV_EXTENDED = CarConfig(
        id = "BYD_SEALION_6_EV_EXTENDED",
        displayName = "Sealion 6 EV Extended",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 87.04,
        estimatedKerbMassKg = 2020.0,
        wltpKm = 510,           // ~605 km CLTC → ~510 km WLTP (estimate)
        referenceConsumptionKwhPer100km = 17.8,
        frontTyrePressureBar = 2.5,
        rearTyrePressureBar = 2.5,
        frontMotorRatedKw = 150,
        cellCount = 144,        // Sealion 6 BEV pack — might be 144S configuration (shared with Standard)
        cdA = 0.774             // Cd 0.29 × A 2.67 m²
    )

    // BYD Sealion 7 — DiLink 5 (Android 11) BEV. Supported only by the `dilink5` flavor.
    // Three regional/trim battery variants (nominal pack size, matching how Sealion 6 EV models
    // Standard/Extended above): 71.8 kWh in China/Turkey, 82.5 kWh in most other markets — both
    // single rear-motor RWD — and 91.3 kWh shared everywhere as the long-range Performance trim,
    // which is dual-motor AWD (confirmed), not RWD like the two Standard variants. Chassis specs
    // (tyre pressures, cdA) are assumed shared across variants pending confirmation otherwise —
    // other specs are estimates — VERIFY: wltp/refConsumption/mass/motor/cdA per variant.
    // id kept as "BYD_SEALION_7" (not renamed) so existing installs that already selected this car
    // aren't orphaned by the split.
    val BYD_SEALION_7 = CarConfig(
        id = "BYD_SEALION_7",
        displayName = "Sealion 7 Standard (71.8 kWh)",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 71.8,           // nominal pack — confirmed China/Turkey Standard spec
        estimatedKerbMassKg = 2160.0,// confirmed kerb mass
        wltpKm = 460,                // estimate for the 71.8 kWh RWD variant — verify
        referenceConsumptionKwhPer100km = 18.7, // confirmed mixed-use figure
        frontTyrePressureBar = 2.90, // confirmed datasheet spec: 42 psi, same front/rear
        rearTyrePressureBar = 2.90,  // confirmed datasheet spec: 42 psi, same front/rear
        rearMotorRatedKw = 160,      // confirmed datasheet spec (single RWD motor)
        cdA = 0.78                   // Cd ~0.29 × A ~2.7 m² — estimate
    )

    val BYD_SEALION_7_STANDARD = CarConfig(
        id = "BYD_SEALION_7_STANDARD",
        displayName = "Sealion 7 Standard (82.5 kWh)",
        drivetrain = Drivetrain.RWD,
        batteryKwh = 82.5,           // nominal pack — most markets outside China/Turkey
        estimatedKerbMassKg = 2311.0,// interpolated between confirmed 71.8kWh(2160kg)/91.3kWh(2435kg) — not independently confirmed
        wltpKm = 502,                // estimate — verify
        referenceConsumptionKwhPer100km = 20.5, // interpolated between confirmed 71.8kWh(18.7)/91.3kWh(21.9) — not independently confirmed
        frontTyrePressureBar = 2.90, // confirmed datasheet spec: 42 psi, same front/rear across the lineup
        rearTyrePressureBar = 2.90,  // confirmed datasheet spec: 42 psi, same front/rear across the lineup
        rearMotorRatedKw = 230,      // confirmed via ev-database (single RWD motor)
        cdA = 0.78                   // assumed shared — verify
    )

    val BYD_SEALION_7_PERFORMANCE = CarConfig(
        id = "BYD_SEALION_7_PERFORMANCE",
        displayName = "Sealion 7 Performance AWD (91.3 kWh)",
        drivetrain = Drivetrain.AWD, // confirmed — dual motor, unlike the single-rear-motor Standard trims
        batteryKwh = 91.3,           // nominal pack — shared long-range/AWD trim across all markets
        estimatedKerbMassKg = 2435.0,// confirmed kerb mass
        wltpKm = 540,                // estimate — verify
        referenceConsumptionKwhPer100km = 21.9, // confirmed mixed-use figure
        frontTyrePressureBar = 2.90, // confirmed datasheet spec: 42 psi, same front/rear across the lineup
        rearTyrePressureBar = 2.90,  // confirmed datasheet spec: 42 psi, same front/rear across the lineup
        // Combined 390 kW system power is confirmed; the front/rear split is not independently
        // confirmed but inferred with high confidence: 160+230 exactly matches the datasheet total,
        // matches BYD_SEAL_EXCELLENCE's identical 160/230 AWD split on the same e-Platform 3.0
        // architecture, and matches our own two single-motor Sealion 7 RWD variants' individual
        // ratings (160 kW on the 71.8 kWh car, 230 kW on the 82.5 kWh car) — strongly suggesting BYD
        // reuses those same two motors combined for the AWD Performance trim.
        frontMotorRatedKw = 160,
        rearMotorRatedKw = 230,
        cdA = 0.78                   // assumed shared — verify
    )

    val BYD_SEALION_5_DMI_COMFORT = CarConfig(
        id = "BYD_SEALION_5_DMI_COMFORT",
        displayName = "Seal 5 / Sealion 5 DM-i Comfort",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 15.0,
        estimatedKerbMassKg = 1700.0,
        wltpKm = 61,            // EV-only WLTP range
        referenceConsumptionKwhPer100km = 21.2,
        frontTyrePressureBar = 2.4,
        rearTyrePressureBar = 2.4,
        cellCount = 80,         // 15.0 kWh @ ~258V → 80S LFP (estimate)
        cdA = 0.620,            // estimate — BYD has not published Cd for Sealion 5
        isPhev = true,
        phevUsableBatteryKwh = 12.96
    )

    val BYD_SEALION_5_DMI_DESIGN = CarConfig(
        id = "BYD_SEALION_5_DMI_DESIGN",
        displayName = "Seal 5 / Sealion 5 DM-i Design",
        drivetrain = Drivetrain.FWD,
        batteryKwh = 21.5,
        estimatedKerbMassKg = 1785.0,
        wltpKm = 86,            // EV-only WLTP range
        referenceConsumptionKwhPer100km = 21.3,
        frontTyrePressureBar = 2.4,
        rearTyrePressureBar = 2.4,
        cellCount = 112,        // 21.5 kWh @ ~361V → 112S LFP (estimate)
        cdA = 0.620,            // estimate — BYD has not published Cd for Sealion 5
        isPhev = true,
        phevUsableBatteryKwh = 18.3
    )

    // BYD Shark 6 — DM-O (Dual Mode Off-road) plug-in hybrid pickup. Dual-motor AWD
    // (front + rear) with a 1.5T petrol engine. Only sold as an AWD variant.
    val BYD_SHARK_6_AWD = CarConfig(
        id = "BYD_SHARK_6_AWD",
        displayName = "Shark 6 AWD",
        drivetrain = Drivetrain.AWD,        // dual-motor DM-O (front + rear)
        batteryKwh = 29.58,                 // Blade LFP gross pack (confirmed)
        estimatedKerbMassKg = 2710.0,       // ~2710 kg kerb (confirmed ballpark)
        wltpKm = 85,                        // EV-only; CLTC ~100 km → ~85 km WLTP estimate — verify
        referenceConsumptionKwhPer100km = 28.0, // heavy ute EV-mode estimate — verify
        frontTyrePressureBar = 2.8,         // estimate (265/65 R18) — verify against door placard
        rearTyrePressureBar = 2.8,          // estimate — verify against door placard
        frontMotorRatedKw = 170,            // confirmed
        rearMotorRatedKw = 150,             // confirmed (combined 320 kW system)
        cellCount = 140,                    // 29.58 kWh Blade — ~140S estimate
        cdA = 1.45,                         // Cd ~0.42 × A ~3.45 m² — pickup estimate
        isPhev = true,
        phevUsableBatteryKwh = 26.0,        // usable EV portion — estimate (~88% of gross)
        fuelTankLiters = 60.0               // confirmed
    )

    val allCars: List<CarConfig> = listOf(
        BYD_SEAL_DYNAMIC_RWD,
        BYD_SEAL_PREMIUM_RWD,
        BYD_SEAL_EXCELLENCE,
        BYD_DOLPHIN_STANDARD,
        BYD_DOLPHIN_EXTENDED,
        BYD_ATTO_2_ACTIVE,
        BYD_ATTO_2_BOOST,
        BYD_ATTO_2_COMFORT,
        BYD_ATTO_3_SR,
        BYD_ATTO_3,
        BYD_ATTO_3_PREMIUM,
        BYD_SEAL_U_COMFORT,
        BYD_SEAL_U_DESIGN,
        BYD_SEAGULL_ACTIVE,
        BYD_SEAGULL_FLYING,
        BYD_DOLPHIN_SURF_ACTIVE,
        BYD_DOLPHIN_SURF_BOOST,
        BYD_DOLPHIN_SURF_COMFORT,
        BYD_M6_STANDARD_120KW,
        BYD_M6_SUPERIOR_100KW,
        BYD_M6_SUPERIOR_150KW,
        BYD_SEAL_6_PREMIUM_95KW,
        BYD_SEAL_6_PREMIUM_160KW,
        BYD_HAN_EV,
        BYD_HAN_EV_AWD,
        BYD_TANG_EV,
        BYD_SEAL_U_DM_I,
        BYD_SEAL_U_DM_I_COMFORT,
        BYD_SEAL_U_DM_I_DESIGN_AWD,
        BYD_SONG_PLUS_DM_I,
        BYD_HAN_DM_I,
        BYD_TANG_DM_I,
        BYD_SEALION_5_DMI_COMFORT,
        BYD_SEALION_5_DMI_DESIGN,
        BYD_SEALION_6_DMI_PREMIUM,
        BYD_SEALION_6_DMI_PERFORMANCE,
        BYD_SHARK_6_AWD,
        BYD_SEALION_6_EV_STANDARD,
        BYD_SEALION_6_EV_EXTENDED,
        BYD_SEALION_7,
        BYD_SEALION_7_STANDARD,
        BYD_SEALION_7_PERFORMANCE,
    )

    fun fromId(id: String?): CarConfig? {
        return allCars.firstOrNull { it.id == id }
    }

    /**
     * Cars grouped for display in selection screens.
     * Two top-level categories (BEV / PHEV), each with named model groups.
     * Mostly DiLink 3 vehicles; the Sealion 7 (DiLink 5) is supported by the `dilink5` flavor.
     */
    val groupedBev: LinkedHashMap<String, List<CarConfig>> = linkedMapOf(
        "BYD Seal" to listOf(BYD_SEAL_DYNAMIC_RWD, BYD_SEAL_PREMIUM_RWD, BYD_SEAL_EXCELLENCE),
        "BYD Dolphin" to listOf(BYD_DOLPHIN_STANDARD, BYD_DOLPHIN_EXTENDED),
        "BYD Atto 2" to listOf(BYD_ATTO_2_ACTIVE, BYD_ATTO_2_BOOST, BYD_ATTO_2_COMFORT),
        "BYD Atto 3" to listOf(BYD_ATTO_3_SR, BYD_ATTO_3, BYD_ATTO_3_PREMIUM),
        "BYD Seal U" to listOf(BYD_SEAL_U_COMFORT, BYD_SEAL_U_DESIGN),
        "BYD Seagull / Dolphin Surf / Atto 1" to listOf(BYD_SEAGULL_ACTIVE, BYD_SEAGULL_FLYING, BYD_DOLPHIN_SURF_ACTIVE, BYD_DOLPHIN_SURF_BOOST, BYD_DOLPHIN_SURF_COMFORT),
        "BYD M6" to listOf(BYD_M6_STANDARD_120KW, BYD_M6_SUPERIOR_100KW, BYD_M6_SUPERIOR_150KW),
        "BYD Seal 6" to listOf(BYD_SEAL_6_PREMIUM_95KW, BYD_SEAL_6_PREMIUM_160KW),
        "BYD Sealion 6" to listOf(BYD_SEALION_6_EV_STANDARD, BYD_SEALION_6_EV_EXTENDED),
        "BYD Sealion 7 (DiLink 5)" to listOf(BYD_SEALION_7, BYD_SEALION_7_STANDARD, BYD_SEALION_7_PERFORMANCE),
        "BYD Han" to listOf(BYD_HAN_EV, BYD_HAN_EV_AWD),
        "BYD Tang" to listOf(BYD_TANG_EV),
    )

    val groupedPhev: LinkedHashMap<String, List<CarConfig>> = linkedMapOf(
        "BYD Seal U DM-i" to listOf(BYD_SEAL_U_DM_I, BYD_SEAL_U_DM_I_COMFORT, BYD_SEAL_U_DM_I_DESIGN_AWD),
        "BYD Song Plus DM-i" to listOf(BYD_SONG_PLUS_DM_I),
        "BYD Han DM-i" to listOf(BYD_HAN_DM_I),
        "BYD Tang DM-i" to listOf(BYD_TANG_DM_I),
        "BYD Seal 5 / Sealion 5 DM-i" to listOf(BYD_SEALION_5_DMI_COMFORT, BYD_SEALION_5_DMI_DESIGN),
        "BYD Sealion 6 DM-i" to listOf(BYD_SEALION_6_DMI_PREMIUM, BYD_SEALION_6_DMI_PERFORMANCE),
        "BYD Shark 6" to listOf(BYD_SHARK_6_AWD),
    )
}
