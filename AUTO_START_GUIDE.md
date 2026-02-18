# 🚗 Auto-Start Implementation Guide

## Problem
App doesn't auto-start when car boots, and trips aren't recorded automatically in background.

## Solution
Three components needed:

### 1. BootReceiver ✅ CREATED
**File:** `receiver/BootReceiver.kt`
**What it does:** Listens for `BOOT_COMPLETED` broadcast and starts MQTT service when car boots

### 2. AndroidManifest Updates ✅ DONE
**Added permissions:**
- `RECEIVE_BOOT_COMPLETED` - Listen for boot events
- `FOREGROUND_SERVICE_DATA_SYNC` - Explicit foreground service type

**Added receiver:**
```xml
<receiver android:name=".receiver.BootReceiver" ... >
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

### 3. Service Configuration ⚠️ NEEDS VERIFICATION

**Current status:**
- MqttService already runs in foreground ✅
- TripRepository already has auto-detection in `processTelemetry()` ✅
- Trips auto-start when `telemetry.isDriving` is true ✅

**What should happen:**
1. Car boots → BootReceiver triggers
2. BootReceiver starts MqttService in foreground
3. MqttService connects to MQTT broker
4. As telemetry flows in, `processTelemetry()` detects driving
5. Trip auto-starts when speed > 0 and gear = D/R

---

## How It Works

### Boot Sequence:
```
Car starts
    ↓
Android boots
    ↓
BOOT_COMPLETED broadcast
    ↓
BootReceiver.onReceive()
    ↓
Start MqttService (foreground)
    ↓
Service shows notification "BYD Stats - Connected"
    ↓
MQTT connects and streams telemetry
    ↓
TripRepository.processTelemetry() detects driving
    ↓
Trip auto-starts (isManual = false)
```

### Trip Detection Logic (Already Implemented):
```kotlin
// In TripRepository.processTelemetry()
if (telemetry.isDriving) {  // speed > 0 && gear in [D, R]
    if (!tripStarted) {
        startTrip(telemetry, isManual = false)
        tripStarted = true
    }
}
```

---

## Files Modified

1. **Created:** `app/src/main/java/com/byd/sealstats/receiver/BootReceiver.kt`
2. **Modified:** `app/src/main/AndroidManifest.xml`
   - Added `RECEIVE_BOOT_COMPLETED` permission
   - Added `FOREGROUND_SERVICE_DATA_SYNC` permission
   - Added `<receiver>` for BootReceiver

---

## Testing Steps

### 1. Install & Grant Permissions
```bash
adb install -r app-debug.apk
```

In car settings:
- Allow "Display over other apps"
- Allow "Autostart" (already done ✅)
- Allow "Run in background"

### 2. Test Boot Receiver
```bash
# Reboot the tablet
adb reboot

# After boot, check if service is running
adb shell dumpsys activity services | grep MqttService

# Check logs
adb logcat | grep BootReceiver
```

Expected output:
```
BootReceiver: Boot completed - starting MQTT service
MqttService: Service started in foreground
```

### 3. Test Auto-Trip Recording
1. Let car sit idle (gear = P, speed = 0)
2. Put car in Drive (D) and move
3. Check if trip auto-starts:
```bash
adb logcat | grep "Trip started"
```

Expected:
```
TripRepository: Trip started automatically (isManual=false)
```

### 4. Verify Notification
After boot, you should see persistent notification:
```
BYD Seal Stats
Connected - 123 updates
```

---

## Troubleshooting

### ❌ Service doesn't start on boot
**Check:**
1. Is "Autostart" enabled for app? (Settings → Apps → BYD Stats)
2. Is battery optimization disabled?
3. Check logcat: `adb logcat | grep BootReceiver`

**Fix:**
```kotlin
// Car settings:
Settings → Apps → BYD Seal Stats
- Autostart: ON
- Battery optimization: OFF
- Run in background: ON
```

### ❌ Trips don't auto-record
**Check:**
1. Is MQTT connected? (Check notification)
2. Is telemetry flowing? `adb logcat | grep "Telemetry received"`
3. Is `isDriving` detecting properly?

**Debug:**
```kotlin
// Add to TripRepository.processTelemetry()
Log.d("TripRepo", "isDriving=${telemetry.isDriving}, speed=${telemetry.speed}, gear=${telemetry.gear}")
```

### ❌ Service stops after car sleep
**Fix:** Add to MqttService.onCreate():
```kotlin
// Acquire partial wake lock
val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "BydStats::MqttWakeLock"
).apply {
    acquire()
}
```

---

## What User Sees

### First Time (Manual Setup):
1. Install APK via ADB
2. Open app once (grants permissions, starts service)
3. Enable "Autostart" in settings
4. **Done!**

### Every Car Start After:
1. Car boots → App auto-starts in background
2. Notification shows "BYD Seal Stats - Connected"
3. User drives → Trip auto-records
4. User can open app anytime to see live stats
5. App runs silently in background otherwise

### No Need To:
❌ Open app manually  
❌ Start trips manually  
❌ Keep app in foreground  

---

## Additional Enhancements (Optional)

### 1. Keep Service Alive During Car Sleep
```kotlin
// In MqttService
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // ...existing code...
    return START_STICKY  // Service restarts if killed
}
```

### 2. Add Restart After Crash
```kotlin
// In AndroidManifest service declaration
android:stopWithTask="false"
```

### 3. Battery Optimization Exemption Request
```kotlin
// In MainActivity.onCreate()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }
    startActivity(intent)
}
```

---

## Summary

✅ **BootReceiver** - Starts service on car boot  
✅ **BOOT_COMPLETED permission** - Allows listening for boot  
✅ **Auto-trip detection** - Already implemented in TripRepository  
✅ **Foreground service** - Keeps running in background  

**Next step:** Install updated APK and test on car! 🚗
