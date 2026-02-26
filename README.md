# 🚗⚡ BYD Trip Stats

<div align="center">

![BYD Trip Stats Banner](https://via.placeholder.com/800x200/2196F3/FFFFFF?text=BYD+Trip+Stats)

**Professional trip tracking and analytics for BYD electric vehicles**

[![GitHub release](https://img.shields.io/github/v/release/angoikon/byd-trip-stats?style=flat-square)](https://github.com/angoikon/byd-trip-stats/releases)
[![GitHub downloads](https://img.shields.io/github/downloads/angoikon/byd-trip-stats/total?style=flat-square)](https://github.com/angoikon/byd-trip-stats/releases)
[![License](https://img.shields.io/github/license/angoikon/byd-trip-stats?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)

[📥 Download](#-download) • [✨ Features](#-features) • [📸 Screenshots](#-screenshots) • [🚀 Installation](#-installation) • [🤝 Contributing](#-contributing)

</div>

---

## 📖 About

BYD Trip Stats is a free, open-source Android app that automatically tracks and analyzes your driving trips using real-time MQTT telemetry from the [Electro app](https://electro.app.br).

**Perfect for BYD Seal, Dolphin, Atto3 etc owners** who want detailed insights into their driving efficiency, energy consumption, and trip patterns.

### Why BYD Trip Stats?

- 🎯 **Automatic Trip Detection** - No manual start/stop needed
- 📊 **Beautiful Analytics** - Comprehensive charts and statistics
- 🗺️ **Route Visualization** - OpenStreetMap integration
- 💾 **Data Export** - CSV, JSON, and text formats
- 🔒 **Privacy First** - All data stays on your device
- 🆓 **Completely Free** - No ads, no subscriptions, no tracking

---

## ✨ Features

### 🎯 Smart Trip Detection
- **Automatic trip start/stop** based on gear position (D/R/P)
- **Manual override** option for full control
- **Intelligent merging** for interrupted trips
- **Stale trip handling** after car sleep/restart

### 📊 Real-Time Dashboard
- **Live telemetry display** - Power, speed, SOC, range
- **Animated energy flow** - Battery → Motor
- **AWD motor visualization** - Front and rear motor RPM
- **Tyre pressure monitoring** - Color-coded alerts
- **Battery health** - Temperature, voltage, SOH

### 📈 Detailed Charts
- **Range projection chart** - Estimated remaining range based on distance travelled during the trip
- **Energy Consumption** - kWh over time
- **Speed Profile** - km/h throughout trip
- **State of Charge** - Battery percentage tracking
- **Motor RPM** - Front and rear motors
- **Elevation Profile** - Altitude changes
- **Power Distribution** - Histogram of power usage
- **Speed Distribution** - Histogram of speed usage
- **Route Map** - OpenStreetMap with start/end markers
- **Driving score calculation**  - Score based on your consumption, regen capacity and average speed

### 🗺️ Route Analysis
- **GPS route visualization** with OpenStreetMap
- **Waypoint tracking** - Start/end locations with timestamps
- **Route segments** - Speed and power analysis per segment
- **Energy hotspots** - Identify high-consumption areas
- **Trip timeline** - Major events (acceleration, braking, etc.)

### 💾 Export & Share
- **Copy to clipboard** - Quick trip summary
- **CSV export** - All data points for analysis
- **JSON export** - Complete trip data structure
- **Text summary** - Human-readable trip report
- **Share anywhere** - Send via email, Drive, WhatsApp, etc.

### 🔄 Background Operation
- **Auto-start on boot** - Silently starts when car boots
- **Foreground service** - Keeps running in background
- **No battery drain** - Optimized for efficiency
- **Manual disconnect** - Stop service when not needed

---

## 📥 Download

### Latest Release: [v1.0.0](https://github.com/angoikon/byd-trip-stats/releases/latest)

**Direct APK Download:**
- [app-release.apk](https://github.com/angoikon/byd-trip-stats/releases/download/v1.0.0/app-release.apk) (69 MB)

**What's New in v1.0.0:**
- 🎉 Initial production release
- ✅ All core features implemented
- ✅ Tested on BYD Seal
- ✅ Auto-start verified
- ✅ Export functionality working

---

## ⚙️ Requirements

### Hardware
- ✅ **BYD Vehicle** - Seal, Dolphin, or Atto3 (other models may work)
- ✅ **Side-loading enabled** - Ability to install APKs from unknown sources

### Software
- ✅ **[Electro App](https://electro.app.br)** - Active subscription required (~€30/year)
- ✅ **External MQTT Broker (optional)** - HiveMQ Cloud (free) or similar is needed. Configure publish events in Electro either way (internal/external broker)

### Permissions
- 📢 **Notifications** - For foreground service
- 🚀 **Boot Completed** - For auto-start functionality
- 🌐 **Internet** - For MQTT connection to your external broker

**Note:** No location permissions required! GPS data comes from MQTT telemetry.

---

## 🚀 Installation

### Step 1: Enable Side-Loading

**Since you already have installed electro, this step is already done. For reference:**
1. For Seal, you need to downgrade to system version **2307**
2. Enable wired and wireless debug
3. Install the **PackageInstallerUnlocked.apk** that unlocks the installation of 3rd party apps. You can google it!
4. You can safely upgrade via USB or OTA

### Step 2: Download APK

Download the latest `byd-trip-stats-release.apk` from the [Releases](https://github.com/angoikon/byd-trip-stats/releases) page.

**Transfer to car via:**
- ADB (wirelessly)
- USB stick
- Cloud provider (Use browser to download locally at your car)

### Step 3: Install

1. Open the APK file on your car's file explorer
2. Tap "Install"
3. Grant requested permissions when prompted
4. Open BYD Trip Stats

### Step 4: Configure MQTT

1. Tap **Settings** (gear icon)
2. Enter your Electro MQTT broker details:
   - **Broker URL:** (from your Electro settings). Default is 127.0.0.1
   - **Port:** Usually 1883 (default) or 8883 (SSL)
   - **Username (optional):** (your MQTT username)
   - **Password (optional):** (your MQTT password)
   - **Topic:** `electro/telemetry/byd-seal/data` (different for other BYD cars - check electro for the correct topic)
3. Tap **Save & Restart Service**
4. Check that cloud icon turns **green** ✅ once it receives telemetry

### Step 5: Enable Auto-Start (Important!)

**For persistent operation:**
1. Go to **Disable autostart** app (native BYD app, usually found next to file explorer)
2. Toggle OFF the option for the **BYD Trip Stats** app (as you have already done with **Electro** app) 
3. Restart Car's UI (10 sec hold of the central console volume button)
4. Re-open **BYD Trip Stats** app

### Step 6: Drive!

That's it! Now:
- Start your car → App auto-starts in background
- Put car in Drive → Trip starts automatically
- Drive normally → Data records silently
- Park and turn off → Trip ends automatically
- Open app anytime to see your trips! 🎉

---

## 🔧 Electro Configuration

### Critical Settings in Electro App:

**MQTT Update Interval:**
- **When car is ON:** Either 1 second or 500ms
- **When car is OFF:** Any interval (doesn't matter, longer is better for less data / battery drainage)

**Why this matters:**
- 1-second intervals = smooth charts
- Slower intervals = choppy data
- Same broker/credentials for both states

**Finding your MQTT details:**
1. Open Electro app
2. Integrations -> MQTT
3. Note your broker URL, port, username, password
4. Use these in BYD Trip Stats

---

## 📸 Screenshots

<div align="center">

### Dashboard - Real-Time Telemetry
![Dashboard](https://via.placeholder.com/800x450/1E1E1E/FFFFFF?text=Dashboard+Screenshot)

### Trip History
![History](https://via.placeholder.com/800x450/1E1E1E/FFFFFF?text=Trip+History+Screenshot)

### Detailed Charts
![Charts](https://via.placeholder.com/800x450/1E1E1E/FFFFFF?text=Charts+Screenshot)

### Route Map
![Route](https://via.placeholder.com/800x450/1E1E1E/FFFFFF?text=Route+Map+Screenshot)

</div>

*(Replace these placeholders with actual screenshots from your car tablet)*

---

## 🛠️ Technical Stack

### Architecture
- **Pattern:** MVVM (Model-View-ViewModel)
- **UI:** Jetpack Compose (Material 3)
- **Language:** Kotlin 100%
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 34 (Android 14)

### Key Libraries
- **Database:** Room (local persistence)
- **MQTT:** HiveMQ Client Library
- **Moquette** : Embedded MQTT Broker
- **Charts:** Vico (Compose charts)
- **Maps:** osmdroid (OpenStreetMap)
- **Async:** Kotlin Coroutines + Flow
- **Storage:** DataStore (preferences)

### Code Quality
- ✅ Null safety (Kotlin)
- ✅ Type-safe navigation
- ✅ Lifecycle-aware components
- ✅ Reactive UI with StateFlow
- ✅ Repository pattern
- ✅ Dependency injection ready

---

## 📊 Data Privacy & Security

### What Data is Collected?

**BYD Trip Stats collects ZERO data from you.**

All information stays on your device:
- 🚗 Trip data (routes, stats, telemetry)
- ⚙️ App settings (MQTT configuration)
- 📍 GPS coordinates (from MQTT, not device location)

### What is Sent to External Servers?

**Only towards YOUR EXTERNAL MQTT broker and only if you have chosen so:**
- Subscribe to telemetry topic
- Receive vehicle data

**Nothing else.** No analytics, no crash reports, no tracking.

### How to Verify?

**The code is open source!** Review it yourself:
- All network calls: `MqttClientManager.kt`
- Internal MQTT calls: `MqttBrokerService.kt`
- Data storage: `TripRepository.kt`, `BydStatsDatabase.kt`
- No third-party SDKs except MQTT, Room, and UI libraries

---

## 🤝 Contributing

Contributions are welcome! Whether it's bug fixes, new features, or documentation improvements.

### How to Contribute

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/amazing-feature`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to the branch (`git push origin feature/amazing-feature`)
5. **Open** a Pull Request

### Areas We Need Help

- 🐛 **Testing** on Dolphin, Atto3 as well as other BYD models
- 🌍 **Translations** to other languages
- 📊 **New chart types** or visualizations
- 🗺️ **Enhanced route analysis** features
- 📱 **UI/UX improvements**
- 📝 **Documentation** improvements

### Reporting Bugs

Found a bug? Open an [Issue](https://github.com/angoikon/byd-trip-stats/issues) with:
- BYD model
- Steps to reproduce
- Logcat output (if possible)

### Feature Requests

Have an idea? Open an issue with the "enhancement" label!

---

## 🗺️ Roadmap

### Planned Features (v1.1.0+)

- [ ] Charging session tracking
- [ ] Trip comparison view
- [ ] Custom dashboard widgets
- [ ] Export to ABRP format
- [ ] Weekly/monthly statistics
- [ ] Dark/light theme toggle
- [ ] Multiple vehicle profiles
- [ ] Cloud backup (optional)
- [ ] Web dashboard (companion)

### Maybe Later

- [ ] Android Auto integration
- [ ] Wear OS companion app
- [ ] Home Assistant integration
- [ ] Retrieve MQTT data via API

**Vote on features** by 👍 reacting to issues!

---

## 🐛 Known Issues

### Current Limitations

1. **No offline charts** - Route maps require internet first time
2. **No trip editing** - Can't modify trip start/end times
3. **No cloud sync** - All data is local only

### Workarounds

- **Route not showing:** Check that GPS coordinates in MQTT are non-zero
- **Trip not auto-starting:** Verify auto-detection is ON, gear is D/R
- **Service not auto-starting:** Check Autostart permission (disable toggle at disable Autostart)

See [Issues](https://github.com/angoikon/byd-trip-stats/issues) for full list.

---

## ❓ FAQ

### Q: Does this work with other EVs?
**A:** Potentially! Any car using Electro app should work. Tested on BYD Seal only.

### Q: Do I need Electro subscription?
**A:** Yes, currently. Even if you decide to use the internal MQTT Broker, you still need to retrieve MQTT data from Electro.

### Q: Will this drain my car's 12V battery?
**A:** It uses your 12V which is always being charged via your high-voltage EV battery. Very minimal battery impact.

### Q: Can I use this without side-loading?
**A:** No. BYD no longer allows 3rd party installations. Check relevant paragraph on how to re-enable it.

### Q: Is my data secure?
**A:** Yes. All data stays on your device (or at YOUR external MQTT broker, if you decided to use one instead of the internal one). The code is open source - verify yourself!

### Q: Can I export to Excel?
**A:** Export as CSV, then open in Excel, Google Sheets, etc.

### Q: Does this replace Electro?
**A:** No! BYD Trip Stats is a **companion app**. You still need Electro for MQTT telemetry (plus sentry mode - great work from electro's developer **Rory**)

### Q: Why is MQTT connection failing?
**A:** Check:
1. Electro is running and connected
2. MQTT credentials are corrects
3. Internet connection is active
4. Broker URL has no `http://` or `https://` prefix

---

## 📜 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

```
MIT License - Copyright (c) 2025 Angelos Oikonomou

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

**TL;DR:** You can do whatever you want with this code. Just keep the copyright notice.

---

## 🙏 Acknowledgments

### Built With

- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern Android UI
- [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client) - MQTT library
- [Moquette](https://github.com/moquette-io/moquette) - MQTT Internal Broker
- [Vico](https://github.com/patrykandpatrick/vico) - Beautiful charts
- [osmdroid](https://github.com/osmdroid/osmdroid) - OpenStreetMap for Android
- [Room](https://developer.android.com/training/data-storage/room) - Local database

### Inspired By

- [Electro App](https://electro.app.br) by **Rory** - For making MQTT telemetry accessible
- **BYD Community** - For the enthusiasm and support

---

## 💬 Community & Support

### Get Help
- 🐛 [GitHub Issues](https://github.com/angoikon/byd-trip-stats/issues)
- 💬 [Discussions](https://github.com/angoikon/byd-trip-stats/discussions)

### Share Your Experience
- ⭐ **Star this repo** if you find it useful!
- 🗣️ Share on BYD communities (Reddit, Facebook)
- 📸 Post screenshots of your trips

### Stay Updated
- 👁️ **Watch** this repo for release notifications
- 🔔 Enable notifications for issues you're interested in

---

## ☕ Support Development

BYD Trip Stats is **free and always will be**. But if you'd like to support development:

- ⭐ **Star this repository** (it's free and motivates me!)
- 🐛 **Report bugs** to improve the app
- 💡 **Suggest features** you'd love to see
- 🤝 **Contribute code** via pull requests
- 📣 **Spread the word** in BYD communities

**Optional donation:**
- [Ko-fi](https://ko-fi.com/angoikon) ☕
- [GitHub Sponsors](https://github.com/sponsors/angoikon) ❤️

Every contribution helps make this app better for everyone!

---

## 📞 Contact

**Angelos Oikonomou** (angoikon)

- GitHub: [@angoikon](https://github.com/angoikon)
- Issues: [Report a bug](https://github.com/angoikon/byd-trip-stats/issues)

**Not affiliated with BYD, Electro, or EV Duty.** This is an independent community project.

---

## ⚖️ Disclaimer

This software is provided "as is" without warranty of any kind. Use at your own risk.

- Not affiliated with BYD Auto, Electro, or EV Duty
- Not responsible for any vehicle damage or data loss
- Always prioritize safe driving over app usage
- MQTT credentials are stored locally - keep your device secure

---

<div align="center">

**Made with ❤️ for the BYD EV community**

[⬆ Back to Top](#-byd-trip-stats)

</div>