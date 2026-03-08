
<div align="center"><img width="1921" height="973" alt="Screenshot 2026-03-08 at 18 04 08" src="https://github.com/user-attachments/assets/298bf3a8-67c8-4fd4-aecf-d06e33dfb904" />



</br>

**Professional trip tracking and analytics for BYD electric vehicles**

[![GitHub release](https://img.shields.io/github/v/release/angoikon/byd-trip-stats?style=flat-square)](https://github.com/angoikon/byd-trip-stats/releases)
[![GitHub downloads](https://img.shields.io/github/downloads/angoikon/byd-trip-stats/total?style=flat-square)](https://github.com/angoikon/byd-trip-stats/releases)
[![License](https://img.shields.io/badge/license-MIT%20%2B%20Commons%20Clause-blue?style=flat-square)](LICENSE.md)
[![Android](https://img.shields.io/badge/Android-10%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)

[📥 Download](#-download) • [✨ Features](#-features) • [📸 Screenshots](#-screenshots) • [🚀 Installation](#-installation) • [🤝 Contributing](#-contributing)

</div
</br>


## 💬 First Words..

BYD Trip Stats is an Android app that is installed at your BYD Card and enables you to automatically track and analyze your driving trips using real-time MQTT telemetry from the [Electro app](https://electro.app.br).

**Perfect for BYD Seal owners (propably for Dolphin, Atto3 and other models too)** who want detailed insights into their driving efficiency, energy consumption, and trip patterns.

### Why BYD Trip Stats?

Unfortunately, BYD's built-in software falls short when it comes to trip statistics and data visualisation. Out of the box, drivers are limited to a single chart showing energy consumption over the last 50 km — nothing more. No trip history, no long-term trends (other than cumulative average energy consumption), no breakdown by speed or driving style.

BYD builds excellent cars with genuinely impressive battery technology, but the software experience hasn't kept pace. As a BYD Seal owner who wanted deeper insight into my own driving data, I built BYD Trip Stats to fill that gap — giving BYD drivers the analytics dashboard their car deserves.

And who knows — perhaps BYD will take notice. The integration path is already there: no sideloading, no third-party dependencies, no hacks. A native implementation would be entirely feasible.

The BYD community, myself included, would love to see the software division take the same bold strides that the engineering team clearly has. The hardware is already world-class. The software just needs to catch up.

## 📖 General info

- 🎯 **Automatic Trip Detection** - No manual start/stop needed
- 📊 **Beautiful Analytics** - Comprehensive charts, heatmaps and statistics
- 🗺️ **Route Visualization** - OpenStreetMap integration
- 💾 **Data Export** - CSV, JSON, and text formats
- 🗄️ **DB Backup & Restore** - Manual and scheduled backups via Download folder, Telegram, or ADB; one-tap restore
- 🔒 **Privacy First** - All data stays on your device
- 🆓 **Completely Free for non-commercial use** - No ads, no subscriptions, no tracking

---

## ✨ Features

### 🎯 Smart Trip Detection
- **Automatic trip start/stop** based on gear position (D/R/P)
- **Manual override** option for full control
- **Stale trip handling** after car sleep/restart/shutdown

### 📊 Real-Time Dashboard
- **Range projection chart** - Live estimated remaining range based on how you drive - comparison with BMS. Four levels of projection
- **Live telemetry display** - Power, speed, SOC, range
- **Animated energy flow** - Battery → Motor
- **AWD motor visualization** - Front and rear motor RPM
- **Tyre pressure monitoring** - Color-coded alerts
- **Battery health** - Temperature, voltage, SoH
- **Consumption charts** - Daily / Weekly / Monthly average energy consumption charts 

### 📈 Detailed Charts
- **Energy Consumption** - kWh over time
- **Speed Profile** - km/h throughout trip
- **State of Charge** - Battery percentage tracking
- **Motor RPM** - Front and rear motors
- **Elevation Profile** - Altitude changes
- **Power Distribution** - Histogram of power usage
- **Speed Distribution** - Histogram of speed usage
- **Heatmap Distributions** - Combination of power, speed, motor, energy matrices
- **Route Map** - OpenStreetMap with start/end markers
- **Driving score calculation**  - Score based on your consumption, regen capacity and average speed

### 🗺️ Route Analysis
- **GPS route visualization** with OpenStreetMap
- **Waypoint tracking** - Start/end locations with timestamps
- **Route segments** - Speed and power analysis per segment
- **Energy hotspots** - Identify high-consumption areas
- **Trip timeline** - Major events (acceleration, braking, etc.)

### 💾 Export & Save internally
- **Copy to clipboard** - Quick trip summary
- **CSV export** - All data points for analysis
- **JSON export** - Complete trip data structure
- **DB backup/restore** - Take backups of your current trips or restore those from a previous backup

### 🗄️ DB Backup & Restore
- **Via Download folder** - Save the DB to `Download/BydTripStats/`, restore from the in-app list
- **Via Telegram** - Send backups to a private Telegram chat; restore directly from the app. Supports manual and scheduled (daily/weekly/monthly) automatic backups. Backup registry persists across reinstalls
- **Via ADB** - Pull/push wirelessly over ADB for technical users

### 🔄 Background Operation
- **Auto-start on boot** - Silently starts when car boots
- **Foreground service** - Keeps running in background
- **No battery drain** - Optimized for efficiency
- **Manual disconnect** - Stop service when not needed
- **Run MQTT Services independently** - Don't kill the services if the activity is swiped away or the task is removed
- **Highly optimized** - State flow management, SQLite limits, unified watchdog

### 📐 Adaptive layout
- **Resizable & Portrait Support** - Open another app next to it , or even enjoy it in portrait mode

---

## 📥 Download

### Latest Release: [v1.0.0](https://github.com/angoikon/byd-trip-stats/releases/latest)

**Direct APK Download:**
- [byd-trip-stats-1.0.0-release.apk](https://github.com/angoikon/byd-trip-stats/releases/download/v1.0.0/byd-trip-stats-1.0.0-release.apk) (12 MB)

**What's New in v1.0.0:**
- 🎉 Initial production release
- ✅ All core features implemented
- ✅ Tested on BYD Seal
- ✅ Auto-start verified
- ✅ Export functionality working

---

## ⚙️ Requirements

### Hardware
- ✅ **BYD Vehicle** - Seal (Dolphin, Atto3 and other models may work)
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

Download the latest `byd-trip-stats-1.0.0-release.apk` from the [Releases](https://github.com/angoikon/byd-trip-stats/releases) page.

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
5. Rinse and repeat for **every** new app update

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
- **When car is ON:** 1 second is fine - you don't have to decrease the interval
- **When car is OFF:** Any interval (e.g. 5 minutes but it doesn't matter, longer is better for less data / battery drainage)

**Why this matters:**
- 1-second intervals = smooth charts
- Slower intervals = choppy data
- Faster intervals = more unnecessary data accumulated without any benefit
- Same broker/credentials for both states

**Finding your MQTT details:**
1. Open Electro app
2. Integrations -> MQTT
3. Note your broker URL, port, username, password
4. Use these in BYD Trip Stats

---

## 📸 Screenshots

<div align="center">

### Dashboard - Telemetry
<img width="1919" height="972" alt="Screenshot 2026-03-06 at 19 42 51" src="https://github.com/user-attachments/assets/d7f35696-2f6a-47cd-9ef2-492f5a29a0ff" />


### Dashboard - Telemetry (in dark mode)
<img width="1921" height="973" alt="Screenshot 2026-03-08 at 18 04 08" src="https://github.com/user-attachments/assets/298bf3a8-67c8-4fd4-aecf-d06e33dfb904" />


### Dashboard - Real projection range
![IMG_1934](https://github.com/user-attachments/assets/15ac1aea-f8de-48ba-bded-cb41fe57deb6)


### Dashboard - Consumptions
![IMG_1927](https://github.com/user-attachments/assets/a88e9926-babe-4c71-9aea-a6067d319d2f)


### Dashboard - multi screen horizontal
![IMG_1937](https://github.com/user-attachments/assets/5fc71e08-56eb-492e-8a59-1ba2da531205)


### Dashboard - multi screen vertical
![IMG_1938](https://github.com/user-attachments/assets/e8c3852c-5aba-41ce-94be-4f847d77601b)


### Trip History
<img width="1920" height="972" alt="Screenshot 2026-03-06 at 19 43 09" src="https://github.com/user-attachments/assets/67c741dc-5647-4970-9f12-6a0a710ad53e" />


### Trip details
<img width="1920" height="973" alt="Screenshot 2026-03-06 at 19 43 45" src="https://github.com/user-attachments/assets/a433d72d-3d08-4f37-aece-1fefdf966bf6" />


### Trip details (more)
<img width="1920" height="973" alt="Screenshot 2026-03-06 at 19 43 59" src="https://github.com/user-attachments/assets/675c2a31-6786-4048-b2ae-c10278315875" />


### Trip filtering
<img width="1918" height="972" alt="Screenshot 2026-03-08 at 18 15 50" src="https://github.com/user-attachments/assets/014bbb12-3304-4980-96cd-0b15b11c809d" />


### Trip sorting
<img width="1919" height="1006" alt="Screenshot 2026-03-08 at 18 16 04" src="https://github.com/user-attachments/assets/03682d30-5c28-408f-b3c4-00572aa6ea0b" />


### Charts
<img width="1921" height="974" alt="Screenshot 2026-03-06 at 19 44 09" src="https://github.com/user-attachments/assets/6275b37d-ec3a-41bc-9c00-fdfb2abee750" />


### Charts (more)
<img width="1920" height="972" alt="Screenshot 2026-03-06 at 19 44 48" src="https://github.com/user-attachments/assets/303f6b4f-3f86-4d25-a1d5-0ed0555d7506" />


### Charts (even more)
<img width="1921" height="973" alt="Screenshot 2026-03-06 at 19 45 04" src="https://github.com/user-attachments/assets/88b956f4-990c-40fc-b2d1-ce2ec99d91a7" />


### Charts (vertical)
![IMG_1939](https://github.com/user-attachments/assets/6ccde8d5-9aad-408a-ba79-cd5689f0ab07)


### Detailed Charts
<img width="1922" height="974" alt="Screenshot 2026-03-06 at 23 09 53" src="https://github.com/user-attachments/assets/ae2ec9ce-74ff-4313-be42-59461b6902e2" />


### Detailed Charts (vertical)
![IMG_1940](https://github.com/user-attachments/assets/dfffecc2-f420-4c76-8c1d-68ce480b621e)


### Heatmaps
<img width="1919" height="970" alt="Screenshot 2026-03-06 at 23 10 11" src="https://github.com/user-attachments/assets/8795fc31-290d-4f65-8d24-665afc9fa1c5" />


### Heatmaps (vertical)
![IMG_1941](https://github.com/user-attachments/assets/cc029270-2bb9-45e1-9c72-8cd9e849030c)


### Heatmaps (even more and in dark mode)
<img width="1920" height="975" alt="Screenshot 2026-03-06 at 23 13 35" src="https://github.com/user-attachments/assets/1265511d-a322-473f-ac3c-b5c4a77e1f12" />


### Route Map
<img width="1917" height="974" alt="Screenshot 2026-03-06 at 23 14 52" src="https://github.com/user-attachments/assets/557a575a-6f74-4785-bd1a-4aba42d2d4c7" />


### Trip Analysis
<img width="1917" height="973" alt="Screenshot 2026-03-06 at 23 15 08" src="https://github.com/user-attachments/assets/05d52c4a-e51c-4337-b6cc-a01e78b95e7a" />


### Trip Analysis (more)
<img width="1919" height="975" alt="Screenshot 2026-03-06 at 23 15 18" src="https://github.com/user-attachments/assets/3014f1e8-73ab-4007-8f81-fdde0c7812df" />


### Export trips
<img width="1918" height="971" alt="Screenshot 2026-03-06 at 23 15 56" src="https://github.com/user-attachments/assets/e3e870fc-517f-4b7d-ad67-1618dcdfd6fa" />


### Network settings
<img width="1444" height="660" alt="Screenshot 2026-03-06 at 23 17 10" src="https://github.com/user-attachments/assets/865163e4-ffa4-4ed6-a3a5-77156be501de" />


### Data settings
<img width="1444" height="730" alt="Screenshot 2026-03-06 at 23 17 19" src="https://github.com/user-attachments/assets/cefb4e9f-1b61-429d-8d58-86dfb53aca03" />


### Database backup & restore
<img width="1917" height="972" alt="Screenshot 2026-03-06 at 23 17 31" src="https://github.com/user-attachments/assets/a8339b96-b00a-499b-bdae-6b630855d311" />


### Database backup & restore (more)
<img width="1919" height="971" alt="Screenshot 2026-03-06 at 23 17 45" src="https://github.com/user-attachments/assets/f7388f94-9755-49c2-bef0-b9f02c49c559" />


### About & FAQ
<img width="1923" height="971" alt="Screenshot 2026-03-07 at 15 10 27" src="https://github.com/user-attachments/assets/57183b01-8073-430b-aef5-3f427dbc79da" />


### About & FAQ (more)
<img width="1443" height="659" alt="Screenshot 2026-03-07 at 15 11 10" src="https://github.com/user-attachments/assets/0ac22ab6-42cd-4d55-a73a-179ee5e3c73b" />

</div>

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
- **Moquette:** Embedded MQTT Broker
- **Charts:** Canvas charts
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
- ✅ Single-coroute event pipeline
- ✅ Maintenance worker (vacuum)
- ✅ Watchdog timer
- ✅ Data point throttling
- ✅ SQLite limits

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

**Telegram servers and only if you have chose so:**
- DB backup files via telegram private bot

**Nothing else.** No analytics, no crash reports, no tracking.

### How to Verify?
- MQTT credentials and all data remain on-device. There are no outbound connections beyond your own MQTT broker. The app requests only the permissions listed above — you can verify this in AppManager → BYD Trip Stats → Permissions.

- No third-party SDKs except MQTT, Room, and UI libraries
- (For the time being, the code is not publicly available on GitHub)

---

## 🤝 Contributing

Contributions are welcome! Whether it's bug reporting or new feature requests.

### Areas I Need Help

- 🐛 **Testing** on Dolphin, Atto3 as well as other BYD models
- 🌍 **Translations** to other languages
- 📊 **New chart types** or visualizations
- 🗺️ **Enhanced route analysis** features
- 📱 **UI/UX improvements**

### Reporting Bugs

Found a bug? Open an [Issue](https://github.com/angoikon/byd-trip-stats/issues) with:
- Your BYD model
- Version app
- Steps to reproduce the problem
- Logcat output (if possible)

### Feature Requests

Have an idea? Open an issue with the "enhancement" label — well-reasoned requests are regularly considered for future releases.

### Testing Help

If you are running BYD Trip Stats on a **Dolphin, Atto3, or any other BYD model**, reports about what works and what doesn't are especially valuable.

---

## 🗺️ Roadmap

### Planned Features (v1.1.0+)

- [ ] Predefined vehicle configuration
- [ ] Charging session tracking
- [ ] Trip comparison view
- [ ] Custom dashboard widgets
- [ ] Multiple vehicle profiles
- [ ] Web dashboard (companion)

### Maybe Later

- [ ] Android Auto integration
- [ ] Wear OS companion app
- [ ] Home Assistant integration
- [ ] Retrieve MQTT data via API
- [ ] Export to ABRP format

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

### Q: Does this work with other BYD EVs?
**A:** Potentially! Any car using Electro app should work. Tested on BYD Seal only.

### Q: Do I need Electro subscription?
**A:** Yes, currently. Even if you decide to use the external MQTT Broker, you still need to retrieve MQTT data from Electro.

### Q: Will this drain my car's 12V battery?
**A:** It uses your 12V which is always being charged via your high-voltage EV battery. Very minimal battery impact.

### Q: Can I use this without side-loading?
**A:** No. BYD no longer allows 3rd party installations. Check relevant paragraph on how to re-enable it.

### Q: Is my data secure?
**A:** Yes. All data stays on your device (or at your own external MQTT broker if you configured one). No analytics, no crash reporting, no advertising. The only outbound network traffic is to your own MQTT broker, if you chose so.

### Q: Can I export to Excel?
**A:** Export as CSV, then open in Excel, Google Sheets, etc.

### Q: Does this replace Electro?
**A:** No! BYD Trip Stats is a **companion app**. You still need Electro for MQTT telemetry (plus sentry mode - great work from electro's developer **Rory** !!)

### Q: Why is MQTT connection failing?
**A:** Check:
1. Electro is running and connected
2. MQTT credentials are corrects
3. Internet connection is active
4. Broker URL has no `http://` or `https://` prefix

---

## 📜 License

BYD Trip Stats is **proprietary software**. All rights reserved.

See [LICENSE.md](LICENSE.md) for the full terms.

**In short:** you may install and use the app for personal use. You may not redistribute, resell, reverse-engineer, or modify it without explicit written permission from the author.

The app is built on open-source libraries (Jetpack Compose, Room, HiveMQ, osmdroid, Moquette, and others). These are used in accordance with their respective Apache 2.0 and MIT licenses, full attribution for which is included in [LICENSE.md](LICENSE.md).

---

## 🙏 Acknowledgments

### Built With

- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern Android UI
- [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client) - MQTT library
- [Moquette](https://github.com/moquette-io/moquette) - MQTT Internal Broker
- [osmdroid](https://github.com/osmdroid/osmdroid) - OpenStreetMap for Android
- [Room](https://developer.android.com/training/data-storage/room) - Local database

### Inspired By

- [Electro App](https://electro.app.br) by **Rory** - For making MQTT telemetry accessible
- **BYD Community** - For the enthusiasm and support
- **Official BYD** - For the unjustified lack of trip statistics and visualization

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

If you'd like to support development:

- ⭐ **Star this repository** (it's free and motivates me!)
- 🐛 **Report bugs** to improve the app
- 💡 **Suggest features** you'd love to see
- 📣 **Spread the word** in BYD communities
- 🤝 **Contribute code** via pull requests

**Optional donation:**
- [Ko-fi](https://ko-fi.com/angoikon) ☕
- [GitHub Sponsors](https://github.com/sponsors/angoikon) ❤️

Every contribution helps make this app better for everyone!

---

## 📞 Contact

**Angelos Oikonomou** (angoikon)

- GitHub: [@angoikon](https://github.com/angoikon)
- Issues: [Report a bug](https://github.com/angoikon/byd-trip-stats/issues)
- Email: [bydtripstats@gmail.com](mailto:bydtripstats@gmail.com)

**Not affiliated with BYD, or Electro.** This is an independent community project.

---

## ⚖️ Disclaimer

This software is provided "as is" without warranty of any kind. Use at your own risk.

- Not responsible for any vehicle damage or data loss
- Always prioritize safe driving over app usage
- MQTT credentials are stored locally - keep your device secure
- If you think telegram private bot might leak your db to telegram servers, you should avoid using it

---

<div align="center">

**Made with ❤️ for the BYD EV community**

[⬆ Back to Top](#-byd-trip-stats)

</div>
