package com.byd.tripstats.sdk

import android.content.Context
import android.content.pm.PackageManager

/**
 * The app's `BYDAUTO_*` permission state, captured for the compatibility report.
 *
 * Reported as raw fact and nothing more. An earlier version of this file derived a
 * `canReadInProcess` flag from whether `BYDAUTO_INSTRUMENT_GET` was granted, on the theory that a
 * head unit withholding the `_GET` permissions was what stopped a DiLink-100 car reading any
 * telemetry. Checking a working DiLink-3 car disproved it: there `BYDAUTO_INSTRUMENT_GET` is
 * `protectionLevel:signature` (declared by `com.byd.auto.permission`) and **not granted either**,
 * yet every read succeeds. The BYD service simply does not enforce it there — `getGetPermission()`
 * reports a nominal name, not a gate that is actually checked.
 *
 * So: do not infer capability from these values. Two platforms with identical permission state
 * behave completely differently, and the real discriminator on DiLink-100 is more likely hidden-API
 * enforcement (Android 14, `hidden_api_policy` unset) than anything here. The table is still worth
 * capturing — it shows which permissions a platform defines at all, which is how DiLink-100's
 * missing BATTERY/BMS/VEHICLEHEALTH devices were explained — but it is evidence to interpret, never
 * a conclusion to act on.
 */
object VehicleSdkAccess {

    private const val BYDAUTO_PREFIX = "android.permission.BYDAUTO_"

    /**
     * Every `BYDAUTO_*` permission this app requests, mapped to whether it is actually held.
     *
     * Read back from our own manifest rather than a hard-coded list so it stays correct as the
     * manifest changes, and so a permission the head unit doesn't define at all still appears
     * (as not-granted) instead of vanishing from the report.
     */
    fun bydautoPermissionStates(context: Context): Map<String, Boolean> = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
            .filter { it.startsWith(BYDAUTO_PREFIX) }
            .sorted()
            .associateWith { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }.getOrDefault(emptyMap())
}
