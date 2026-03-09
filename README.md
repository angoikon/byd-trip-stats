<div align="center">

<img width="1921" height="973" alt="Screenshot 2026-03-08 at 18 04 08" src="https://github.com/user-attachments/assets/e16f41b0-febb-4e19-85f3-d74b0dfc5a27" />


# BYD Trip Stats
### Trip Analytics & Telemetry Dashboard for BYD DiLink Vehicles

[![Android](https://img.shields.io/badge/Android-10%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Architecture](https://img.shields.io/badge/Architecture-MVVM-orange?style=flat-square)](https://developer.android.com)
[![License](https://img.shields.io/badge/license-BUSL--1.1-blue?style=flat-square)](LICENSE.md)
[![GitHub release](https://img.shields.io/github/v/release/angoikon/byd-trip-stats?style=flat-square)](https://github.com/angoikon/byd-trip-stats/releases)
[![GitHub downloads](https://img.shields.io/github/downloads/angoikon/byd-trip-stats/total?style=flat-square)](https://github.com/angoikon/byd-trip-stats/releases)

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
    <td align="center"><b>Dashboard — Light Theme</b><br><img width="450" alt="Screenshot 2026-03-06 at 19 42 51" src="https://github.com/user-attachments/assets/398b67eb-280f-4978-a542-67e6f7f96143" /></td>
    <td align="center"><b>Dashboard — Dark Theme</b><br><img width="450" src="https://github.com/user-attachments/assets/298bf3a8-67c8-4fd4-aecf-d06e33dfb904" /></td>
  </tr>
  <tr>
    <td align="center"><b>Split-Screen — Horizontal</b><br><img width="450" src="https://github.com/user-attachments/assets/e4275bb8-715d-478f-90cf-0c40870d4ee5" /></td>
    <td align="center"><b>Split-Screen — Vertical</b><br><img width="450" src="https://github.com/user-attachments/assets/f5b07c36-b524-44f4-908a-d566401331cd" /></td>
  </tr>
</table>
</div>

---

### II. Range Projection & Efficiency

A proprietary power-integration algorithm computes realistic remaining range in real time — based on actual Wh/km consumption, not the BMS's static estimate. The projection self-calibrates across the trip using a rolling 10 km window with EMA smoothing, and is bounded by WLTP to prevent overcorrection during low-speed urban starts.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Live Range Projection</b><br><img width="450" src="https://github.com/user-attachments/assets/55189f86-d9be-4977-a9f3-98203d8fbe5b" /></td>
    <td align="center"><b>Consumption Trends</b><br><img width="450" src="https://github.com/user-attachments/assets/49098879-dd97-4402-acd7-80816ed564d4" /></td>
  </tr>
</table>
</div>

---

### III. Trip Management

Trips are captured automatically via gear-position events — no driver input required. The history view supports multi-field filtering, six sort criteria, and per-trip CSV / JSON export.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Trip History</b><br><img width="450" src="https://github.com/user-attachments/assets/ea181e7d-021c-4ec2-9a45-ac48c8dd559a" /></td>
    <td align="center"><b>Trip Filtering</b><br><img width="450" src="https://github.com/user-attachments/assets/79c748bc-83d9-441f-b50a-306744d1021a" /></td>
  </tr>
  <tr>
    <td align="center"><b>Trip Sorting</b><br><img width="450" src="https://github.com/user-attachments/assets/677212bd-8e66-4541-9e4b-7f51b3ca41e6" /></td>
    <td align="center"><b>Export (CSV / JSON)</b><br><img width="450" src="https://github.com/user-attachments/assets/a89fc15e-11fd-47ec-8ff2-5b3a96d74365" /></td>
  </tr>
</table>
</div>

---

### IV. Deep Trip Analysis

Per-trip breakdown of every recorded metric: route path on OpenStreetMap, speed and power profiles, regen events, altitude, battery state, and cell-level data — rendered across dedicated analysis tabs.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Trip Detail</b><br><img width="450" src="https://github.com/user-attachments/assets/8c13fc21-f8f6-4e1c-ac9a-63650fe90cdb" /></td>
    <td align="center"><b>Telemetry Overlays</b><br><img width="450" src="https://github.com/user-attachments/assets/be55662a-3770-484e-96d9-bace4fce330f" /></td>
  </tr>
  <tr>
    <td align="center"><b>Route Map</b><br><img width="450" src="https://github.com/user-attachments/assets/3e191091-b794-4746-b76e-6d3f4a5c0480" /></td>
    <td align="center"><b>Route Analysis</b><br><img width="450" src="https://github.com/user-attachments/assets/88f6f160-f02d-4337-a493-1c35e814e2f9" /></td>
  </tr>
  <tr>
    <td align="center"><b>Route Analysis (continued)</b><br><img width="450" src="https://github.com/user-attachments/assets/5f87cc39-96cc-423a-beea-52ac8719cdcf" />/td>
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
    <td align="center"><b>Speed & Motor Distribution</b><br><img width="450" src="https://github.com/user-attachments/assets/61775024-4fe7-48b5-a968-8239b00f5511" />td>
    <td align="center"><b>Power Profile & Energy Consumption</b><br><img width="450" src="https://github.com/user-attachments/assets/4b5cbeab-e041-4751-a756-75bbcc0d8057" /></td>
  </tr>
  <tr>
    <td align="center"><b>SoC & Elevation Detail</b><br><img width="450" src="https://github.com/user-attachments/assets/8d7d57ba-d5c9-47fb-b946-8e863442cabe" /></td>
    <td align="center"><b>Detailed Chart View</b><br><img width="450" src="https://github.com/user-attachments/assets/3f8dd270-3fab-402e-b158-6051e42fe5be" /></td>
  </tr>
  <tr>
    <td align="center"><b>Charts — Vertical Orientation</b><br><img width="450" src="https://github.com/user-attachments/assets/99b9fa06-3ad5-4729-beef-2b27b2a6767e" /></td>
    <td align="center"><b>Detailed Charts — Vertical</b><br><img width="450" src="https://github.com/user-attachments/assets/461c442d-aff6-471d-80b6-5c6e62e49dbd" /></td>
  </tr>
</table>
</div>

---

### VI. Heatmap Analysis

15 heatmap dimensions correlating any two telemetry axes — speed vs. power, SoC vs. regen, altitude vs. consumption, and more. Crosshair interaction shows exact bin ranges on tap.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Heatmaps — Landscape</b><br><img width="450" src="https://github.com/user-attachments/assets/477f046a-e07b-4eb5-a02c-8bc35ce615ef" /></td>
    <td align="center"><b>Heatmaps — Vertical</b><br><img width="450" src="https://github.com/user-attachments/assets/ca390ffa-3c91-4e23-810e-0d88a872baca" /></td>
  </tr>
  <tr>
    <td align="center"><b>Heatmaps — Dark Mode</b><br><img width="450" src="https://github.com/user-attachments/assets/486fb0d5-1971-4924-bb5d-b6a20515ac5c" /></td>
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
    <td align="center"><b>Network Settings</b><br><img width="450" src="https://github.com/user-attachments/assets/18b0de45-3cce-439c-8dbb-38c621ec0257" /></td>
    <td align="center"><b>Data Settings</b><br><img width="450" src="https://github.com/user-attachments/assets/30b9691e-0eb6-4a4a-8a17-8ae4831b5c61" /></td>
  </tr>
  <tr>
    <td align="center"><b>Backup — Telegram Integration</b><br><img width="450" src="https://github.com/user-attachments/assets/f038a553-7375-4629-bc86-778b0f59e74f" /></td>
  </tr>
  <tr>
    <td align="center"><b>About & FAQ</b><br><img width="450" src="https://github.com/user-attachments/assets/90d67634-526e-435d-8894-caa2ba7d6bbd" /></td>
    <td align="center"><b>FAQ (continued)</b><br><img width="450" src="https://github.com/user-attachments/assets/d6cb8144-b29b-4510-9e77-aa383eb5b132" /></td>
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
2. MQTT credentials are correct
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
- If you think telegram encrypted private bot might leak your db to telegram servers, you should avoid using it


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
