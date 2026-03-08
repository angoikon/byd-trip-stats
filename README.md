<div align="center">

<img width="1921" height="973" alt="BYD Trip Stats Dashboard" src="https://github.com/user-attachments/assets/298bf3a8-67c8-4fd4-aecf-d06e33dfb904" />

# BYD Trip Stats
### Trip Analytics & Telemetry Dashboard for BYD DiLink Vehicles

[![Android](https://img.shields.io/badge/Android-10%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Architecture](https://img.shields.io/badge/Architecture-MVVM-orange?style=flat-square)](https://developer.android.com)
[![License](https://img.shields.io/badge/license-BUSL--1.1-blue?style=flat-square)](LICENSE.md)

[🚀 Getting Started](#-getting-started) • [✨ Features](#-feature-overview) • [🖼️ Screenshots](#%EF%B8%8F-visual-showcase) • [🛠️ Technical Stack](#%EF%B8%8F-technical-stack) • [🗺️ Integration Roadmap](#%EF%B8%8F-integration-roadmap) • [🔒 Privacy](#-data-privacy--security) • [📞 Contact](#-contact--proposal)

</div>

---

> **BYD Trip Stats** is a feature-complete Android analytics dashboard for BYD DiLink vehicles — built by a BYD Seal owner and running on production hardware. It currently operates via an MQTT telemetry bridge (Electro) and is architected so that a native DiLink integration would require minimal changes.

---

## 🚀 Getting Started

### Requirements

- A BYD vehicle with **DiLink 3.0** (tested on BYD Seal; should be compatible with Atto 3, Dolphin, and other DiLink-equipped models)
- An active **[Electro](https://electro.app.br)** subscription — this is the telemetry bridge that exposes vehicle data via MQTT
- Android **10 or higher** on the DiLink head unit

### Installation

1. Download the latest signed APK from the [**Releases**](https://github.com/angoikon/byd-trip-stats/releases) tab
2. On your DiLink unit, run the app - enable installation from unknown sources and follow the on-screen prompt to install
3. Launch BYD Trip Stats, grant permissions for saving data to your car's internal storage
4. Enter your Electro MQTT broker credentials

### Known Limitations

- **Electro is required.** BYD Trip Stats currently receives telemetry via an MQTT bridge provided by the Electro app. A future native implementation would remove this dependency entirely — see the [Integration Roadmap](#%EF%B8%8F-integration-roadmap) below.
- The app runs exclusively while the DiLink unit is active. Background tracking outside the vehicle is not supported by design.
- AWD motor data is available on dual-motor variants (e.g. Seal AWD). Next app versions will detect configuration automatically and adjust the UI accordingly.
- Same goes for any other hardcoded data like WLTP, average consumption (via ev-database.org) etc

---

## ✨ Feature Overview

**Driving Intelligence**
- Fully autonomous trip detection via gear position events (D/R → P) — zero driver input required
- Session distance tracking independent of trip recording state
- Manual override with confirmation safeguards

**Real-Time Telemetry**
- Live AWD front/rear motor RPM and estimated power split
- Battery SoH, cell voltage range, thermal min/max delta
- HV and 12V bus voltage, tyre pressures per wheel (bar / PSI / kPa)
- Gear state, speed, engine power, regen detection

**Range Projection Engine**
- Power-integrated consumption model (Wh/km) computed over a rolling 10 km window
- EMA smoothing with stabilisation warmup (first 2 km discarded)
- Four-tier fallback: live trip → historical speed bins → lifetime average → WLTP baseline
- WLTP upper bound prevents implausible projections during low-speed urban starts
- Compared continuously against BMS estimate with signed delta display

**Trip Management**
- Multi-field filtering: date range, distance, energy, duration, efficiency
- Six sort criteria with ascending/descending toggle
- Per-trip CSV and JSON export

**Analytics & History**
- Full per-trip storage: route, telemetry timeseries, computed statistics
- Daily / weekly / monthly / annual energy consumption views
- 15 heatmap dimensions with crosshair bin-range interaction
- OpenStreetMap route overlay with energy event markers, fully offline

**Reliability & Data**
- Room (SQLite) persistence with WAL, automated maintenance workers, and schema migrations
- Scheduled encrypted backup via Telegram bot or local filesystem
- Full database restore with integrity verification

---

## 🖼️ Visual Showcase

### I. Real-Time Dashboard

Adaptive layouts for both landscape and portrait orientations on the rotating infotainment screen, including full split-screen multi-app support. The UI palette mirrors the DiLink Ocean Series dark and light themes.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Dashboard — Light Theme</b><br><img width="450" src="https://github.com/user-attachments/assets/d7f35696-2f6a-47cd-9ef2-492f5a29a0ff" /></td>
    <td align="center"><b>Dashboard — Dark Theme</b><br><img width="450" src="https://github.com/user-attachments/assets/298bf3a8-67c8-4fd4-aecf-d06e33dfb904" /></td>
  </tr>
  <tr>
    <td align="center"><b>Split-Screen — Horizontal</b><br><img width="450" src="https://github.com/user-attachments/assets/5fc71e08-56eb-492e-8a59-1ba2da531205" /></td>
    <td align="center"><b>Split-Screen — Vertical</b><br><img width="450" src="https://github.com/user-attachments/assets/e8c3852c-5aba-41ce-94be-4f847d77601b" /></td>
  </tr>
</table>
</div>

---

### II. Range Projection & Efficiency

A proprietary power-integration algorithm computes realistic remaining range in real time — based on actual Wh/km consumption, not the BMS's static estimate. The projection self-calibrates across the trip using a rolling 10 km window with EMA smoothing, and is bounded by WLTP to prevent overcorrection during low-speed urban starts.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Live Range Projection</b><br><img width="450" src="https://github.com/user-attachments/assets/15ac1aea-f8de-48ba-bded-cb41fe57deb6" /></td>
    <td align="center"><b>Consumption Trends</b><br><img width="450" src="https://github.com/user-attachments/assets/a88e9926-babe-4c71-9aea-a6067d319d2f" /></td>
  </tr>
</table>
</div>

---

### III. Trip Management

Trips are captured automatically via gear-position events — no driver input required. The history view supports multi-field filtering, six sort criteria, and per-trip CSV / JSON export.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Trip History</b><br><img width="450" src="https://github.com/user-attachments/assets/67c741dc-5647-4970-9f12-6a0a710ad53e" /></td>
    <td align="center"><b>Trip Filtering</b><br><img width="450" src="https://github.com/user-attachments/assets/014bbb12-3304-4980-96cd-0b15b11c809d" /></td>
  </tr>
  <tr>
    <td align="center"><b>Trip Sorting</b><br><img width="450" src="https://github.com/user-attachments/assets/03682d30-5c28-408f-b3c4-00572aa6ea0b" /></td>
    <td align="center"><b>Export (CSV / JSON)</b><br><img width="450" src="https://github.com/user-attachments/assets/e3e870fc-517f-4b7d-ad67-1618dcdfd6fa" /></td>
  </tr>
</table>
</div>

---

### IV. Deep Trip Analysis

Per-trip breakdown of every recorded metric: route path on OpenStreetMap, speed and power profiles, regen events, altitude, battery state, and cell-level data — rendered across dedicated analysis tabs.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Trip Detail</b><br><img width="450" src="https://github.com/user-attachments/assets/a433d72d-3d08-4f37-aece-1fefdf966bf6" /></td>
    <td align="center"><b>Telemetry Overlays</b><br><img width="450" src="https://github.com/user-attachments/assets/675c2a31-6786-4048-b2ae-c10278315875" /></td>
  </tr>
  <tr>
    <td align="center"><b>Route Map</b><br><img width="450" src="https://github.com/user-attachments/assets/557a575a-6f74-4785-bd1a-4aba42d2d4c7" /></td>
    <td align="center"><b>Route Analysis</b><br><img width="450" src="https://github.com/user-attachments/assets/05d52c4a-e51c-4337-b6cc-a01e78b95e7a" /></td>
  </tr>
  <tr>
    <td align="center"><b>Route Analysis (continued)</b><br><img width="450" src="https://github.com/user-attachments/assets/3014f1e8-73ab-4007-8f81-fdde0c7812df" /></td>
    <td align="center"></td>
  </tr>
</table>
</div>

---

### V. High-Resolution Charting

Every technical metric the vehicle exposes is charted — front and rear motor RPM, torque distribution, battery cell voltages, thermal delta, SoH, and charging curves. All charts are custom-rendered on Canvas with no third-party charting libraries.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Speed & Motor Distribution</b><br><img width="450" src="https://github.com/user-attachments/assets/6275b37d-ec3a-41bc-9c00-fdfb2abee750" /></td>
    <td align="center"><b>Power Profile & Energy Consumption</b><br><img width="450" src="https://github.com/user-attachments/assets/303f6b4f-3f86-4d25-a1d5-0ed0555d7506" /></td>
  </tr>
  <tr>
    <td align="center"><b>Battery & Voltage Detail</b><br><img width="450" src="https://github.com/user-attachments/assets/88b956f4-990c-40fc-b2d1-ce2ec99d91a7" /></td>
    <td align="center"><b>Detailed Chart View</b><br><img width="450" src="https://github.com/user-attachments/assets/ae2ec9ce-74ff-4313-be42-59461b6902e2" /></td>
  </tr>
  <tr>
    <td align="center"><b>Charts — Vertical Orientation</b><br><img width="450" src="https://github.com/user-attachments/assets/6ccde8d5-9aad-408a-ba79-cd5689f0ab07" /></td>
    <td align="center"><b>Detailed Charts — Vertical</b><br><img width="450" src="https://github.com/user-attachments/assets/dfffecc2-f420-4c76-8c1d-68ce480b621e" /></td>
  </tr>
</table>
</div>

---

### VI. Heatmap Analysis

15 heatmap dimensions correlating any two telemetry axes — speed vs. power, SoC vs. regen, altitude vs. consumption, and more. Crosshair interaction shows exact bin ranges on tap.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Heatmaps — Landscape</b><br><img width="450" src="https://github.com/user-attachments/assets/8795fc31-290d-4f65-8d24-665afc9fa1c5" /></td>
    <td align="center"><b>Heatmaps — Vertical</b><br><img width="450" src="https://github.com/user-attachments/assets/cc029270-2bb9-45e1-9c72-8cd9e849030c" /></td>
  </tr>
  <tr>
    <td align="center"><b>Heatmaps — Dark Mode (extended)</b><br><img width="450" src="https://github.com/user-attachments/assets/1265511d-a322-473f-ac3c-b5c4a77e1f12" /></td>
    <td align="center"></td>
  </tr>
</table>
</div>

---

### VII. Settings, Backup & Data Integrity

Full MQTT broker configuration (internal or external), local database backup and restore, and Telegram-based encrypted backup. Settings are logically grouped and include an in-app FAQ covering all common integration questions.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Network Settings</b><br><img width="450" src="https://github.com/user-attachments/assets/865163e4-ffa4-4ed6-a3a5-77156be501de" /></td>
    <td align="center"><b>Data Settings</b><br><img width="450" src="https://github.com/user-attachments/assets/cefb4e9f-1b61-429d-8d58-86dfb53aca03" /></td>
  </tr>
  <tr>
    <td align="center"><b>Backup — Telegram Integration</b><br><img width="450" src="https://github.com/user-attachments/assets/f7388f94-9755-49c2-bef0-b9f02c49c559" /></td>
  </tr>
  <tr>
    <td align="center"><b>About & FAQ</b><br><img width="450" src="https://github.com/user-attachments/assets/57183b01-8073-430b-aef5-3f427dbc79da" /></td>
    <td align="center"><b>FAQ (continued)</b><br><img width="450" src="https://github.com/user-attachments/assets/0ac22ab6-42cd-4d55-a73a-179ee5e3c73b" /></td>
  </tr>
</table>
</div>

---

## 🛠️ Technical Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin 1.9 | 100% Kotlin, no Java |
| UI | Jetpack Compose + Material 3 | Adaptive for DiLink landscape / portrait / split-screen |
| Architecture | MVVM + StateFlow | Clean ViewModel/Repository separation |
| Persistence | Room (SQLite) | WAL mode, versioned migrations, maintenance workers |
| Async | Kotlin Coroutines + Channels | Event-driven telemetry pipeline |
| Charts | Custom Canvas rendering | No third-party chart libraries |
| Maps | OpenStreetMap (OSMDroid) | Fully offline-capable |
| Build | Gradle KTS | ProGuard release build, signed APK pipeline |
| Min SDK | API 29 (Android 10) | Matches DiLink 3.0 platform |

---

## 🗺️ Integration Roadmap

This application currently bridges to the vehicle via MQTT (using the Electro third-party service). That dependency exists because direct DiLink API access is not available externally. The architecture is designed as follows;

```
Phase 1 — Current (External MQTT bridge)
  Electro app → MQTT broker → BYD Trip Stats

Phase 2 — Preferred (Native DiLink system app)
  Vehicle CAN / Internal API → BYD Trip Stats (system-signed APK, no bridge)

Phase 3 — Full OEM integration
  Logic absorbed into DiLink firmware; UI surfaced as a native DiLink panel
```

**What changes between phases:** only the data source layer (`MqttClientManager` → `VehicleApiClient`). The ViewModel, Room persistence, all charts, range projection engine, and UI are source-compatible. The MVVM boundary was deliberately drawn to make this substitution a single-file change.

The competitive case for Phase 2/3 is straightforward:

| Capability | Current DiLink OEM | Competitor Benchmark | BYD Trip Stats |
|---|---|---|---|
| Trip range projection | BMS estimate only | Real-time consumption model (Tesla) | Power-integrated live projection |
| Consumption history | 50 km rolling window | Weekly / Monthly / Annual (BMW, Polestar) | Daily / Weekly / Monthly / Annual |
| Motor telemetry | Not exposed | Front/rear torque split live view (NIO) | Real-time RPM + power distribution |
| Battery granularity | SoC % only | Cell voltage, SoH, thermal ranges | Cell min/max voltage, SoH, thermal delta |
| Trip intelligence | Manual | Gear-event triggered (NIO) | Fully autonomous — gear position D/R/P |
| Trip filtering & sorting | Not available | Basic date filter | Multi-field filter + 6 sort criteria |
| Data export | Not available | Varies | CSV / JSON per trip |
| Data sovereignty | Cloud-dependent | Varies | 100% local, zero external calls |

---

## 🔒 Data Privacy & Security

- **Local-first:** 100% of telemetry and trip data stored on-device — no cloud, no third-party analytics
- **Zero outbound calls:** No tracking, no crash reporting, no telemetry leaving the vehicle
- **User-controlled backup:** Encrypted backups to a private Telegram bot or local storage, initiated manually or on a configurable schedule
- **GDPR-aligned by design:** No personal data is collected or transmitted

This architecture requires no modification to comply with EU data regulations.

---

## 📄 Licence

This project is licensed under the **Business Source Licence 1.1 (BUSL-1.1)**.

You are free to view, fork, and use the source for **personal and non-commercial purposes**. Commercial use — including integration into vehicle firmware, commercial products, or redistribution as part of a paid service — requires a separate written licence agreement.

See [LICENSE.md](LICENSE.md) for the full terms.

---

## 📞 Contact & Proposal

I am an independent software engineer and BYD Seal owner based in Greece. I built this application because the gap between the Seal's hardware capability and its software experience was significant enough to solve myself. The application is feature-complete and running on production hardware today.

If you represent BYD's Smart Device or Product Strategy team, I am open to discussing:

**1. Native System Integration** — Porting the application as a DiLink system-signed APK, replacing the MQTT bridge with direct vehicle API access. This would deliver the full feature set to all DiLink-equipped BYD vehicles via OTA.

**2. Analytics Algorithm Licensing** — The range projection engine, trip intelligence logic, and consumption modelling are available for licensing into official BYD firmware or companion applications.

**3. Technical Collaboration** — A scoped engagement to evaluate, extend, or adapt this work for official roadmap integration.

For all other enquiries — bug reports, feature requests, community discussion — please open a [GitHub Issue](https://github.com/angoikon/byd-trip-stats/issues).

---

## 🤝 Contributing

Contributions are welcome! Whether it's bug reporting or new feature requests.

### Areas you might assist

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
- [ ] Spotify / Tidal integration
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
**A:** No. The ultimate goal is for BYD to implement it natively as part of the infotainment system, without the need to side-load.

### Q: Is my data secure?
**A:** Yes. All data stays on your device (or at your own external MQTT broker if you configured one). No analytics, no crash reporting, no advertising. The only outbound network traffic and only if you chose to is a) to your own MQTT broker and b) to your private encrypted telegram bot.

### Q: Can I export to Excel?
**A:** Export as CSV, then open in Excel, Google Sheets, etc.

### Q: Why is MQTT connection failing?
**A:** Check:
1. Electro is running and connected
2. MQTT credentials are corrects
3. Internet connection is active
4. Broker URL has no `http://` or `https://` prefix

---

## 🙏 Acknowledgments

### Built With

- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern Android UI
- [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client) - MQTT library
- [Moquette](https://github.com/moquette-io/moquette) - MQTT Internal Broker
- [osmdroid](https://github.com/osmdroid/osmdroid) - OpenStreetMap for Android
- [Room](https://developer.android.com/training/data-storage/room) - Local database

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

## ⚖️ Disclaimer

This software is provided "as is" without warranty of any kind. Use at your own risk.

- Not responsible for any vehicle damage or data loss
- Always prioritize safe driving over app usage
- MQTT credentials are stored locally - keep your device secure
- If you think telegram private bot might leak your db to telegram servers, you should avoid using it


---

<div align="center">

**Angelos Oikonomou**
*Software Engineer · BYD Seal Owner*

📧 [bydtripstats@gmail.com](mailto:bydtripstats@gmail.com)
🔗 [github.com/angoikon](https://github.com/angoikon)

---

*Independent project. Not affiliated with BYD Auto Co., Ltd. or the Electro application.*
*All trademarks belong to their respective owners.*

</div>