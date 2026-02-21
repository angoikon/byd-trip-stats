# BYD Trip Stats - Complete Android App

Trip tracking and visualization for BYD vehicles via MQTT, with built-in mock data for testing!

## 🎯 What's New in This Version

✅ **API 29** (Android 10) - Works on more devices  
✅ **No Google Maps** - Simplified, no location permissions needed  
✅ **Launcher Icons Fixed** - All densities included  
✅ **Chart Components Fixed** - No more Vico errors  
✅ **Mock Data Generator** - Test without real MQTT! ⭐  

## 🚀 Quick Start (2 Minutes!)

### 1. Open Project
```bash
# Extract ZIP
# Open Android Studio
# File → Open → Select byd-stats-app folder
# Wait for Gradle sync (~2 min first time)
```

### 2. Run on Emulator
- Select any emulator with **API 29+**
- Click ▶️ (green play button)
- Grant notification permission

### 3. Test with Mock Data ⭐
**Best way to see the app in action!**

1. App launches → Dashboard appears
2. Click **Play button** (▶️) in top toolbar (next to cloud icon)
3. Watch the magic:
   - Car accelerates to 80 km/h
   - Energy flow animates
   - Power consumption shown live
   - Battery SOC decreases
   - Trip auto-detects and starts recording
   - After 5 min, car decelerates with regen braking
   - Trip auto-ends

4. Navigate to "Trip History" (clock icon)
5. Click on the recorded trip
6. See beautiful charts with real data!

**That's it! No configuration needed!**

## 📱 Features

### Real-Time Dashboard
- Animated energy flow (battery → motor → wheels)
- Live stats: Power, Speed, Battery %, Range
- Battery health monitoring
- Trip controls (auto/manual)

### Trip Statistics
- Distance traveled
- Energy consumed
- Regeneration captured
- Efficiency (kWh/100km)
- Max/avg speed and power
- Battery temperature tracking

### Beautiful Charts
- Energy consumption over time
- Speed profile
- Power distribution histogram

### Trip History
- All trips saved locally
- Click for detailed view
- Delete unwanted trips

## 🎮 Mock Data Generator

### What It Simulates
A realistic 5-minute drive with:
- **0-45sec**: Acceleration (0 → 80 km/h, high power)
- **45-210sec**: Cruising (75-85 km/h, moderate power)
- **210-255sec**: Deceleration (80 → 0 km/h, regeneration!)
- **255-300sec**: Coming to stop (light regen)

### Statistics Generated
- Distance: ~4-5 km
- Energy used: ~1-2 kWh
- Max speed: 80 km/h
- Max regen: -25 kW
- Battery: 97.6% → ~96%
- GPS route: San Francisco demo coordinates

### How to Use
1. Launch app
2. Click ▶️ (Play) in dashboard toolbar
3. Watch it run for 5 minutes
4. Trip auto-records
5. View in history with charts

### Customize Mock Data
Edit `DashboardViewModel.kt`:
```kotlin
mockGenerator.generateMockDrive(
    durationSeconds = 600, // 10 min drive
    updateIntervalMs = 500  // Faster updates
)
```

## 🔧 Technical Details

### Requirements
- Android Studio Hedgehog or newer
- API 29+ (Android 10+)
- JDK 17

### Architecture
- **MVVM** pattern
- **Jetpack Compose** UI
- **Room** database
- **Kotlin Coroutines** for async
- **HiveMQ MQTT** client

### Key Files
```
MainActivity.kt             - Entry point
DashboardScreen.kt          - Main UI (with Play button!)
DashboardViewModel.kt       - Logic + startMockDrive()
MockDataGenerator.kt        - Simulates driving ⭐
TripRepository.kt           - Auto-detection logic
```

## 🌐 Real MQTT Setup (Optional)

Once you've tested with mock data and want to use real MQTT:

1. Navigate to Settings (gear icon)
2. Enter your HiveMQ broker:
   - URL: `your-broker.hivemq.cloud`
   - Port: `8883` (or 1883 for unsecured)
   - Username/Password (if required)
   - Topic: `electro/telemetry/byd-seal/data` (For Seal)
3. Click "Save & Restart Service"
4. Drive your BYD Seal!

## 🐛 Troubleshooting

### "Could not resolve libs.plugins..."
- Check internet connection
- File → Sync Project with Gradle Files
- Wait for dependencies to download

### "Cannot find ic_launcher"
✅ Fixed! All launcher icons included.

### "rememberTextComponent is undefined"
✅ Fixed! Charts now use simple titles.

### Mock Drive Button Not Visible
- Look for Play button (▶️) in top toolbar
- It's between the title and cloud icon

### Charts Empty
- Make sure trip has ended (not still "active")
- Click on trip in history to view details
- Run mock drive to generate data

### App Crashes
- Check Logcat: View → Tool Windows → Logcat
- Filter by "BydStats" or "Error"
- Most common: Ensure API 29+ emulator

## 🎨 Customization

### Colors
`ui/theme/Color.kt`:
```kotlin
val BatteryBlue = Color(0xFF2196F3)      // Change me!
val RegenGreen = Color(0xFF4CAF50)       // Change me!
val AccelerationOrange = Color(0xFFFF9800) // Change me!
```

### Trip Auto-End Timeout
`data/repository/TripRepository.kt`:
```kotlin
private val tripEndDelayMs = 2 * 60 * 1000L // 2 minutes
```

### Mock Drive Speed
`mock/MockDataGenerator.kt`:
```kotlin
"cruising" -> 120.0 + sin(progress * 10) * 5 // Faster!
```

## 📦 What's Included

- ✅ Complete source code
- ✅ Gradle configuration (modern version catalog)
- ✅ All dependencies configured
- ✅ Launcher icons (all densities)
- ✅ Room database schema
- ✅ MQTT service
- ✅ Mock data generator
- ✅ All UI screens
- ✅ Charts and visualizations
- ✅ Material 3 theme

## 🎓 Learning the Code

### Start Here:
1. `MainActivity.kt` - App entry, permissions
2. `DashboardScreen.kt` - Main UI, see the Play button!
3. `MockDataGenerator.kt` - How simulation works
4. `TripRepository.kt` - How trips are detected
5. `DashboardViewModel.kt` - Connects everything

### Key Concepts:
- **Flow**: Real-time data streams
- **Coroutines**: Async operations
- **Compose**: Declarative UI
- **Room**: Local database
- **MQTT**: Message protocol

## 🚗 Next Steps

### Phase 1: Test (You are here!)
- [x] Run on emulator
- [x] Test mock data
- [x] Explore UI

### Phase 2: Real Data
- [ ] Configure your HiveMQ broker
- [ ] Connect Electro app
- [ ] Drive and test

### Phase 3: Deploy
- [ ] Build signed APK
- [ ] Install on BYD tablet
- [ ] Enjoy real-time stats!

### Phase 4: Extend (Ideas)
- [ ] Export trips to CSV
- [ ] Compare trips
- [ ] Driving score
- [ ] Charging session tracking
- [ ] Cloud backup
- [ ] Add Google Maps back (optional)

## 📄 License

Personal use project for BYD Seal owners.

---

## 🎉 You're Ready!

This is a **100% complete, working project** with:
- Zero configuration required
- Mock data for instant testing
- Professional code structure  
- Beautiful Material 3 UI
- Optimized for tablets

Just open, sync, run, and click Play! 🚗⚡📊

**Pro tip**: Start with mock data to understand how everything works, then configure real MQTT when ready!
