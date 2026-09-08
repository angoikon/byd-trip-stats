package com.byd.tripstats.sdk

import android.os.Build
import android.util.Log
import com.byd.tripstats.BuildConfig

/**
 * Detects whether we are running on a DiLink-5 head unit (Sealion 7 etc., `ro.vehicle.type=Di5*`,
 * Android 11 / SDK 30) vs the DiLink-3 platform the app was originally built for.
 *
 * The data-source layer uses this to choose the DiLink-5 code path (typed AbsBYDAuto*Listener +
 * feature-ID event stream) over the DiLink-3 path. The real DiLink-5 bydauto SDK is bundled only
 * in the `dilink5` product flavor; on a `dilink3` build this returns false on D3 hardware and the
 * D5 hooks are simply absent.
 *
 * Shared (src/main) so both flavors compile — it touches no bydauto types.
 */
object DiLink5Platform {
    private const val TAG = "DiLink5Platform"

    val vehicleType: String by lazy { systemProp("ro.vehicle.type") }

    /**
     * True on DiLink-5 hardware — detected solely by `ro.vehicle.type` starting with "Di5".
     * No SDK-version fallback: an SDK>=30 check would misdetect a newer-Android DiLink-3 unit that
     * doesn't expose `ro.vehicle.type` as D5.
     */
    val isDiLink5: Boolean by lazy {
        val result = vehicleType.startsWith("Di5", ignoreCase = true)
        Log.i(TAG, "isDiLink5=$result (ro.vehicle.type='$vehicleType', sdk=${Build.VERSION.SDK_INT})")
        result
    }

    /** The product flavor this hardware expects: "dilink5" on DiLink-5, "dilink3" otherwise. */
    val expectedFlavor: String get() = if (isDiLink5) "dilink5" else "dilink3"

    /**
     * True on any head unit newer than the DiLink-3 platform, which is the only one the blind
     * invoke-every-no-arg-method and self-kill-and-relaunch paths were ever proven safe on.
     *
     * **The boundary is exactly one API level, so do not "tidy" this constant.** DiLink-3 is
     * **Android 10 / API 29** (hence the app's `minSdk = 29`; see MD/APP_OVERVIEW.md), DiLink-5 is
     * Android 11 / API 30, DiLink-100 is Android 14 / API 34. `>= 30` is therefore the tightest
     * predicate that excludes DiLink-3 and catches every later generation; lowering it to 29 would
     * silently disable the compat probe's getter sweep and the VACUUM disk-reclaim on the entire
     * DiLink-3 fleet.
     *
     * Deliberately a version check, not a [vehicleType] match: the prop is a product string, and
     * defaulting an unrecognised one to "safe" is exactly how a DiLink-100 car ended up blind-
     * invoking OEM commands (`wakeUpMcu`, `dspReset`, `padReset`, `StartOTA`, `syncMcuState`) that
     * the DiLink-5 guard exists to prevent. An unknown platform must fail safe, not fail open.
     */
    val isPostDiLink3: Boolean get() = Build.VERSION.SDK_INT >= 30

    /**
     * True when the compat probe must NOT blind-invoke every no-arg method on an OEM device.
     * See [isPostDiLink3] — [isDiLink5] is kept as an explicit term so the guard still holds on a
     * DiLink-5 unit that somehow reports an older SDK level.
     */
    val blindSweepUnsafe: Boolean get() = isDiLink5 || isPostDiLink3

    /**
     * True when the app must not restart or install itself: the self-kill-and-relaunch that
     * follows a database restore/reset/VACUUM, and the in-app download + silent
     * PackageInstaller + post-install relaunch of the updater.
     *
     * Both are the same hazard — an app-initiated process restart racing the bydauto SDK's
     * classloader injection, which on DiLink-5 could leave the head unit boot-looping (the
     * 2.13.0 incident) — so they share one predicate. [isDiLink5] is kept as an explicit term
     * for a DiLink-5 unit that somehow reports an older SDK level; see [isPostDiLink3] for why
     * the version check, and not a [vehicleType] match, is what makes an unknown platform fail
     * safe. Where this is true the app closes without relaunching and updates are sideloaded
     * with `adb install -r`.
     */
    val selfRestartUnsafe: Boolean get() = isDiLink5 || isPostDiLink3

    /**
     * True only when the installed build genuinely can't drive this hardware: a **DiLink-5 car
     * running a non-dilink5 build**, which has no DiLink-5 SDK path at all (no Dilink5Client, no
     * injector) → no telemetry.
     *
     * The reverse — a dilink5 build on a DiLink-3 car — is deliberately NOT flagged: it works,
     * because the D3 telemetry is read through the signature-agnostic RuntimeExtensionBridge and
     * the D5-only typed code (the sole user of drifted signatures like getTotalMileageValue) is
     * gated off by isDiLink5. Always false when the app has no product flavors (FLAVOR == "").
     */
    val isBuildUnsupportedForHardware: Boolean
        get() = isDiLink5 && BuildConfig.FLAVOR.isNotEmpty() && BuildConfig.FLAVOR != "dilink5"

    /** Human-readable label for a flavor, e.g. "DiLink 5" / "DiLink 3". */
    fun flavorLabel(flavor: String): String = if (flavor == "dilink5") "DiLink 5" else "DiLink 3"

    private fun systemProp(key: String): String = try {
        @Suppress("PrivateApi")
        val sp = Class.forName("android.os.SystemProperties")
        (sp.getMethod("get", String::class.java).invoke(null, key) as? String).orEmpty()
    } catch (t: Throwable) {
        ""
    }
}
