# BYD Trip Stats - Fixes Summary

## Changes Implemented

### 1. ✅ Trip Ending: 5 minutes → 15 seconds
**File:** `TripRepository.kt`
**Change:** Line 43
```kotlin
private val briefStopDelayMs = 15 * 1000L  // 15 seconds in P to end trip
```

**Log message updated:** Line 123
```kotlin
Log.i(TAG, "Brief stop timeout (15s in P, not charging) - ending trip")
```

**Rationale:** Prevents unnecessary data points when parked, keeps X-axis cleaner

---

### 2. ✅ Merge Trips Functionality
**Files:** `TripRepository.kt`, `DashboardViewModel.kt`, `TripHistoryScreen.kt`

**TripRepository.kt** - New function (lines 323-373):
```kotlin
suspend fun mergeTrips(tripIds: List<Long>): Long?
```
- Combines multiple trips into one
- Preserves all data points
- Recalculates statistics
- Deletes original trips

**TripHistoryScreen.kt** - New UI:
- "Merge trips" button in top bar
- Long-press trip to enter selection mode
- Checkbox selection for multiple trips
- Merge confirmation dialog
- Requires minimum 2 trips selected

**DashboardViewModel.kt** - New function (line 69):
```kotlin
fun mergeTrips(tripIds: List<Long>)
```

---

### 3. ⚠️ Trip Detail Flickering (NEEDS INVESTIGATION)
**Status:** Needs testing to identify cause

**Potential causes:**
1. Multiple recompositions from Flow updates
2. Chart data processing on main thread
3. State updates triggering full screen recomposition

**Recommended investigation:**
- Add logging to identify recomposition triggers
- Check if `remember` is being used correctly for expensive computations
- Verify Flow collectors aren't causing unnecessary updates

---

### 4. ⚠️ MQTT Connection Status Validation (NEEDS IMPLEMENTATION)
**Current issue:** Green cloud shows even with invalid credentials

**Required changes:**
1. **MqttService.kt**: 
   - Track actual connection state
   - Emit connection success/failure
   - Validate credentials before showing green

2. **DashboardScreen.kt**:
   - Show gray cloud until actual connection
   - Display connection error message
   - Don't show "Waiting for data..." unless connected

3. **Suggested states:**
   - Gray: Not configured or disconnected
   - Yellow: Connecting/Authenticating
   - Green: Successfully connected
   - Red: Connection failed

**Implementation needed:**
```kotlin
sealed class MqttConnectionState {
    object NotConfigured : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}
```

---

### 5. ⚠️ MQTT Disconnect Button (NEEDS IMPLEMENTATION)
**Location:** SettingsScreen.kt

**Proposed UI:**
- Add button below "Save & Restart Service"
- Label: "Disconnect from MQTT"
- Only enabled when connected
- Stops MqttService

**Implementation:**
```kotlin
OutlinedButton(
    onClick = { viewModel.stopMqttService() },
    enabled = mqttConnected,
    modifier = Modifier.fillMaxWidth()
) {
    Text("Disconnect from MQTT")
}
```

---

### 6. ⚠️ Auto-Start on Boot (NEEDS VERIFICATION)
**Current implementation:** BootReceiver.kt exists

**Verification needed:**
1. Check if `BootReceiver` is being called
2. Verify MQTT service starts properly
3. Confirm preferences are loaded correctly

**Testing steps:**
```bash
# Reboot device
adb reboot

# After boot, check if service is running
adb shell dumpsys activity services | grep MqttService

# Check logs
adb logcat | grep BootReceiver
```

**Potential issues:**
- Battery optimization preventing auto-start
- Service not configured as foreground service
- Permissions not granted

**Required permissions checklist:**
- ✅ `RECEIVE_BOOT_COMPLETED`
- ✅ `FOREGROUND_SERVICE_DATA_SYNC`
- ? `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (not implemented)

---

### 7. ⚠️ Permission Prompts Missing (NEEDS INVESTIGATION)
**Expected:** Notification permission prompt
**Actual:** No prompts shown

**Possible causes:**
1. API 33+ notification permission not being requested
2. Permission already granted from previous install
3. Request timing issue (before UI ready)

**File to check:** `MainActivity.kt` line 34-64

**Verification:**
```bash
# Check current permissions
adb shell dumpsys package com.byd.tripstats | grep permission

# Revoke permissions manually
adb shell pm revoke com.byd.tripstats android.permission.POST_NOTIFICATIONS

# Reinstall and test
```

---

### 8. ✅ Motor RPM Chart Legend
**File:** `MotorRpmChart.kt`

**Change:** Moved legend OUTSIDE chart (always visible)
- Line 41-52: Legend now at top
- Line 54-72: Chart below legend

**Before:** Legend only visible in fullscreen
**After:** Legend always visible in both condensed and fullscreen views

---

### 9. ✅ Trip History Subtitle
**File:** `TripHistoryScreen.kt`

**Change:** Added subtitle under "Trip History" (lines 24-30)
```kotlin
Column {
    Text("Trip History", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    if (!selectionMode) {
        Text(
            "Click trip for more details",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

---

## Files Modified

### Completed:
1. ✅ `TripRepository.kt` - 15s trip ending + merge functionality
2. ✅ `DashboardViewModel.kt` - merge trips function
3. ✅ `TripHistoryScreen.kt` - merge UI + subtitle
4. ✅ `MotorRpmChart.kt` - legend always visible
5. ✅ `CondensedCharts.kt` - maxPoints 45 → 36 (you did this manually)

### Needs Work:
6. ⚠️ `TripDetailScreen.kt` - flickering investigation
7. ⚠️ `DashboardScreen.kt` - MQTT status validation
8. ⚠️ `MqttService.kt` - connection state tracking
9. ⚠️ `SettingsScreen.kt` - disconnect button
10. ⚠️ `MainActivity.kt` - permission prompts
11. ⚠️ `BootReceiver.kt` / Auto-start verification

---

## Testing Checklist

### Merge Trips:
- [ ] Select 2+ trips
- [ ] Confirm merge dialog appears
- [ ] Verify merged trip contains all data points
- [ ] Check statistics are recalculated correctly
- [ ] Confirm original trips are deleted

### 15s Trip Ending:
- [ ] Put car in P
- [ ] Wait 15 seconds
- [ ] Verify trip ends automatically
- [ ] Check no extra data points added
- [ ] Verify X-axis labels are clean

### Motor RPM Legend:
- [ ] View condensed chart in trip details
- [ ] Verify legend is visible
- [ ] Click to fullscreen
- [ ] Verify legend still visible

### Trip History Subtitle:
- [ ] Open trip history
- [ ] Verify "Click trip for more details" is visible
- [ ] Enter selection mode
- [ ] Verify subtitle disappears

---

## Known Issues to Address

1. **MQTT Connection State** - Most critical
   - Currently shows green even with invalid config
   - Needs proper state management
   - Should validate before showing "Waiting for data..."

2. **Permissions** - Important for first-time users
   - No prompt shown on fresh install
   - Needs investigation

3. **Auto-Start** - Important for UX
   - Unclear if it's working
   - Needs testing on actual car

4. **Flickering** - Minor UX issue
   - Needs investigation
   - May be related to Flow updates

5. **Disconnect Button** - Nice to have
   - Easy to implement
   - Good for troubleshooting

---

## Implementation Priority

### High Priority (Do First):
1. MQTT connection state validation
2. Permissions investigation/fix
3. Auto-start verification

### Medium Priority:
4. Disconnect button
5. Flickering investigation

### Low Priority (Optional):
6. Battery optimization exemption
7. Additional connection status indicators

---

## Notes

- All code follows existing patterns
- Backward compatible with existing data
- No database migrations needed
- Merge function creates new trip, deletes old ones
- 15s delay applies only to parked state (not charging)
- Engine-off detection (11s) still applies
