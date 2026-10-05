package com.byd.tripstats.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager

/**
 * Head units built on Android Automotive OS — today the DiLink 100 in the Atto 3 EVO (Android 14).
 *
 * Two things set them apart from DiLink 3 and 5, and both are handled here:
 *
 *  - **Vehicle data.** BYD's own service refuses every bydauto getter to a third-party app there
 *    (`[getInt] permission deny!`), but the car publishes the same readings — battery, range, gear,
 *    ignition, charging, outside temperature — as standard Android Automotive vehicle properties,
 *    readable through `android.car`'s CarPropertyManager. The reader that does it lives in the
 *    `dilink3` flavor (AaosCarPropertyReader), the build those cars run.
 *  - **Users.** The driver is Android user 10; user 0 is a headless system user with no screen.
 *    The app is installed for both, so anything that starts it "as user 0" starts a second,
 *    empty copy that nobody can see — see [isHeadlessSystemUserInstance].
 *
 * Every check requires Android 12 (API 31) or newer, so DiLink 3 (Android 10) and DiLink 5
 * (Android 11) are excluded by construction, whatever their system features say.
 */
object AaosPlatform {

    /** Android's per-user uid range: uid = userId × 100000 + appId. */
    private const val PER_USER_RANGE = 100_000

    /** The two vehicle-property permissions that are `dangerous`, so they need a grant. */
    val CAR_RUNTIME_PERMISSIONS: List<String> = listOf(
        "android.car.permission.CAR_ENERGY",  // battery level, range, charging
        "android.car.permission.CAR_SPEED",   // vehicle speed
    )

    /** The Android user this process runs as (10 for the driver on DiLink 100, 0 elsewhere). */
    val myUserId: Int get() = Process.myUid() / PER_USER_RANGE

    /**
     * True when this head unit can serve vehicle properties to the app: Android Automotive on
     * Android 12+, and never DiLink 5, whose data path is its own.
     */
    fun isCarPropertyCapable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            !DiLink5Platform.isDiLink5 &&
            runCatching {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            }.getOrDefault(false)

    /**
     * True for the copy of the app running in the headless system user (user 0) of an Android
     * Automotive head unit. That copy has its own empty database and no screen, so whatever it
     * records is invisible; worse, while it runs, the background restarter took it for the real
     * app and never revived the driver's copy (Atto 3 EVO, 2026-10-05: `u0_a157` and `u10_a157`
     * running side by side). It stays idle instead.
     */
    val isHeadlessSystemUserInstance: Boolean by lazy {
        Build.VERSION.SDK_INT >= 31 &&
            myUserId == 0 &&
            runCatching { UserManager.isHeadlessSystemUserMode() }.getOrDefault(false)
    }

    fun missingCarRuntimePermissions(context: Context): List<String> =
        CAR_RUNTIME_PERMISSIONS.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * IGNITION_STATE as the app's carOn: ON and START → 2 (ready to drive); LOCK, OFF and ACC → 0,
     * the same split as the bodywork power level (ACC is infotainment only, not drivable);
     * UNDEFINED or anything unknown → null (no reading). On the Atto 3 EVO the property went to
     * OFF (2) at the moment the car was switched off (2026-10-05).
     */
    fun carOnFromIgnitionState(state: Int?): Int? = when (state) {
        IGNITION_ON, IGNITION_START -> 2
        IGNITION_LOCK, IGNITION_OFF, IGNITION_ACC -> 0
        else -> null
    }

    /** GEAR_SELECTION (VehicleGear bit values) as the app's gear letter; null when unknown. */
    fun gearLetter(vehicleGear: Int?): String? = when {
        vehicleGear == null -> null
        vehicleGear == GEAR_PARK -> "P"
        vehicleGear == GEAR_REVERSE -> "R"
        vehicleGear == GEAR_NEUTRAL -> "N"
        vehicleGear == GEAR_DRIVE || vehicleGear >= GEAR_1 -> "D"
        else -> null
    }

    // VehicleIgnitionState
    const val IGNITION_LOCK = 1
    const val IGNITION_OFF = 2
    const val IGNITION_ACC = 3
    const val IGNITION_ON = 4
    const val IGNITION_START = 5

    // VehicleGear
    const val GEAR_NEUTRAL = 0x1
    const val GEAR_REVERSE = 0x2
    const val GEAR_PARK = 0x4
    const val GEAR_DRIVE = 0x8
    const val GEAR_1 = 0x10

    // EvChargeState
    const val CHARGE_STATE_CHARGING = 1
}
