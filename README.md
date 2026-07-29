<div align="center">

<img width="1921" height="973" alt="Screenshot 2026-03-08 at 18 04 08" src="https://github.com/user-attachments/assets/e16f41b0-febb-4e19-85f3-d74b0dfc5a27" />


# BYD Trip Stats
### Trip Analytics & Telemetry Dashboard for BYD DiLink 3 Vehicles

[![Android](https://img.shields.io/badge/Android-10%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Architecture](https://img.shields.io/badge/Architecture-MVVM-orange?style=flat-square)](https://developer.android.com)
[![License](https://img.shields.io/badge/license-BUSL--1.1-blue?style=flat-square)](LICENSE.md)
[![Changelog](https://img.shields.io/badge/changelog-v2.14.0-informational?style=flat-square)](CHANGELOG.md)
[![Website](https://img.shields.io/badge/website-byd--trip--stats-F38020?style=flat-square&logo=cloudflare&logoColor=white)](https://byd-trip-stats.angoikon.workers.dev/)
[![GitHub release](https://img.shields.io/github/v/release/angoikon/byd-trip-stats?style=flat-square)](https://github.com/angoikon/byd-trip-stats/releases)
[![GitHub downloads](https://img.shields.io/github/downloads/angoikon/byd-trip-stats/total?style=flat-square)](https://github.com/angoikon/byd-trip-stats/releases)
[![Unit Tests](https://img.shields.io/github/actions/workflow/status/angoikon/byd-trip-stats/unit-tests.yml?branch=main&label=unit%20tests&style=flat-square&logo=github)](https://github.com/angoikon/byd-trip-stats/actions/workflows/unit-tests.yml)
[![Integration Tests](https://img.shields.io/github/actions/workflow/status/angoikon/byd-trip-stats/integration-tests.yml?label=integration%20tests&style=flat-square&logo=github)](https://github.com/angoikon/byd-trip-stats/actions/workflows/integration-tests.yml)

[🚀 Getting Started](#-getting-started) • [✨ Features](#-feature-overview) • [🖼️ Screenshots](#%EF%B8%8F-visual-showcase) • [🛠️ Technical Stack](#%EF%B8%8F-technical-stack) • [🗺️ Integration Roadmap](#%EF%B8%8F-integration-roadmap) • [🔒 Privacy](#-data-privacy--security) • [📞 Contact](#-contact--proposal) • [🌐 Website](https://byd-trip-stats.angoikon.workers.dev/)

</div>

---

> **BYD Trip Stats** is a feature-complete Android analytics dashboard for BYD DiLink vehicles — built by a BYD Seal owner and running on production hardware. It now uses the BYD SDK and operates as a standalone in-car analytics app without relying on an external companion bridge.

---

## 🚀 Getting Started

### Requirements

- A BYD vehicle with **DiLink 3.0** (BEV as well as PHEV are supported)
- Android **10 or higher** on the DiLink head unit
- **DiLink 5.0** (Sealion 7 and other newer BYDs, Android 11+) is supported via a separate build —
  see [**Running on DiLink 5.0**](docs/DILINK5.md).

### Installation

1. Download the latest signed APK from the [**Releases**](https://github.com/angoikon/byd-trip-stats/releases) tab
2. On your DiLink unit, run the app - enable installation from unknown sources and follow the on-screen prompt to install
3. Launch BYD Trip Stats, grant permissions for saving data to your car's internal storage
4. On first launch, select your BYD model and allow the app to finish initial setup

No Electro setup, MQTT broker, or topic configuration is required for normal operation.

> **On a DiLink 5.0 car?** Grab the `dilink5` APK from the same
> [**Releases**](https://github.com/angoikon/byd-trip-stats/releases) page — see
> [**docs/DILINK5.md**](docs/DILINK5.md).

### Known Limitations

- **Some fields are still inferred or unresolved.** SoH is currently shown as an estimate, there is no cabin temperature present in the SDK.
- **Background persistence depends on DiLink firmware behaviour.** The app uses a foreground service, wake lock, boot receiver, and watchdog, but some BYD firmware builds are still aggressive about killing third-party apps while the car is off.
- **Parked WiFi depends on the head unit, and the app cannot force it to stay on.** Many BYD units cut the WiFi module ~15 minutes after the car is switched off. The Android-level WiFi lock the app holds cannot prevent this, and the BYD "keep accessory alive" mechanism that some other apps use requires system/shell privileges this app intentionally does not take. Practical consequences for **parked** publishing:
  - A **cloud/internet-reachable broker** (e.g. HiveMQ Cloud) often keeps receiving telemetry while parked, because the unit can stay on cellular (4G) even after WiFi is cut.
  - A **LAN-only broker** (e.g. a self-hosted Mosquitto on your home network) becomes unreachable once WiFi is cut, so parked publishing stops until the car powers on again. To get parked telemetry to a home broker, either expose it to the internet (see the WebSocket/reverse-proxy support above) or run a dedicated WiFi-keepalive app alongside.
  - Either way, telemetry resumes when the car powers on, and any charging that happened while parked is reconstructed from the State-of-Charge change.

---

## ✨ Feature Overview

**Driving Intelligence**
- Fully autonomous trip detection via gear position events (D/R → P) — zero driver input required
- Session distance tracking independent of trip recording state
- Short engine-off breaks can continue the same trip, with current segment and cumulative trip distance shown separately
- Drive and regen modes are recorded for trip timelines and mode-efficiency analysis
- Manual override with confirmation safeguards

**Real-Time Telemetry**
- Live motor RPM per driven axle and estimated power split (AWD only: front 160 kW / rear 230 kW proportional to total output)
- Battery SoH, cell voltage range, thermal min/max delta
- HV and 12V bus voltage, tyre pressures per wheel (bar / PSI / kPa) and tyre temperatures (×4) where the car exposes them
- Gear state, speed, engine power, regen detection
- Environmentals card with ambient temperature and PM2.5 in/out readings where available

**Range Projection Engine**
- Consumption model (Wh/km) fed from the BMS total-discharge counter — the same source as the live consumption readout — computed over a rolling 10 km window, with engine-power integration as a fallback
- EMA smoothing with a 3 km stabilisation window for the live-trip tier
- Three-tier model: live trip → historical speed bins → WLTP baseline, with the speed-bin tier engaging within ~0.2 km of starting to drive so the projection leaves the catalog baseline almost immediately
- WLTP upper bound prevents implausible projections during low-speed urban starts
- Compared continuously against BMS estimate with signed delta display

**Trip Management**
- Multi-field filtering: date range, distance, energy, duration, efficiency
- Six sort criteria with ascending/descending toggle
- Configurable engine-off trip timeout and a minimum-trip-distance filter (Settings → Preferences)
- Per-trip export as CSV, JSON, or a single self-contained HTML viewer — saved locally or sent straight to a Telegram bot

**Analytics & History**
- Full per-trip storage: route, telemetry timeseries, computed statistics
- Daily / weekly / monthly / annual energy consumption views
- Up to 14 heatmap dimensions with crosshair bin-range interaction (13 universal + 1 AWD-exclusive torque-split map)
- OpenStreetMap route overlay with energy event markers, fully offline
- Physics-based energy breakdown per trip: rolling resistance, aerodynamic drag, gradient (climb/descent), and auxiliary losses — scaled to always sum to actual consumed energy
- Drive and regen mode visualisation woven into existing charts: faint colour-coded background bands on Speed and Power charts show active drive mode across the full trip timeline; the Speed chart line itself changes colour segment-by-segment as the mode changes; crosshair tooltips on both charts show the active drive mode and regen mode at the touched moment; the Analysis tab Mode Insights card shows a horizontal stacked-bar summarising time spent in each drive and regen mode as a share of total trip distance

**Reliability & Data**
- Room (SQLite) persistence with WAL, automated maintenance workers, and schema migrations
- Scheduled encrypted backup via Telegram bot or local filesystem
- Full database restore with integrity verification
- Local safety backup before update installation
- Direct vehicle polling with fallback listeners across charging, statistic, climate, instrument, speed, location, and energy devices
- App Diagnostics monitor: live CPU, RAM, thread, and uptime stats with 60-second history charts and ADB shell runner, available in Settings → Data

**Connections**
- Optional ABRP Link Generic upload using your ABRP user token
- Optional outbound MQTT publisher for external brokers, using an Electro-compatible telemetry JSON schema
- Connection status, test upload/publish actions, and human-readable last-sync timestamps

---

## 🖼️ Visual Showcase


### 📹 Demo Video

<div align="center">
  <a href="https://youtu.be/qmkeVPG8pRo">
    <img src="https://img.youtube.com/vi/qmkeVPG8pRo/hqdefault.jpg" 
         alt="BYD Trip Stats Demo" width="720" />
  </a>
  <br/>
  <em>▶ Click to watch on YouTube</em>
</div>

---

### I. Real-Time Dashboard

Adaptive layouts for both landscape and portrait orientations on the rotating infotainment screen, including full split-screen multi-app support. The UI palette mirrors the DiLink Ocean Series dark and light themes.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Dashboard — Light Theme</b><br><img width="450" alt="Screenshot 2026-03-06 at 19 42 51" src="https://github.com/user-attachments/assets/398b67eb-280f-4978-a542-67e6f7f96143" /></td>
    <td align="center"><b>Dashboard — Dark Theme</b><br><img width="450" src="https://github.com/user-attachments/assets/e16f41b0-febb-4e19-85f3-d74b0dfc5a27" /></td>
  </tr>
  <tr>
    <td align="center"><b>Split-Screen — Horizontal</b><br><img width="450" src="https://github.com/user-attachments/assets/e4275bb8-715d-478f-90cf-0c40870d4ee5" /></td>
    <td align="center"><b>Split-Screen — Vertical</b><br><img width="450" src="https://github.com/user-attachments/assets/f5b07c36-b524-44f4-908a-d566401331cd" /></td>
  </tr>
</table>
</div>

---

### II. Range Projection & Efficiency

A proprietary consumption-modelling algorithm computes realistic remaining range in real time — based on your actual Wh/km from the BMS total-discharge counter, not the BMS's static range estimate. The projection self-calibrates across the trip using a rolling 10 km window with EMA smoothing, and is bounded by WLTP to prevent overcorrection during low-speed urban starts.

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

Trips are captured automatically via gear-position events — no driver input required. The history view supports multi-field filtering, six sort criteria, and per-trip export as CSV, JSON, or a self-contained HTML viewer (saved locally or sent to a Telegram bot).

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
    <td align="center"><b>Route Analysis (continued)</b><br><img width="450" src="https://github.com/user-attachments/assets/5f87cc39-96cc-423a-beea-52ac8719cdcf" /></td>
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
    <td align="center"><b>Speed & Motor Distribution</b><br><img width="450" src="https://github.com/user-attachments/assets/61775024-4fe7-48b5-a968-8239b00f5511" /></td>
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

14 heatmap dimensions correlating any two telemetry axes — speed vs. power, SoC vs. regen, tyre pressure vs. consumption, cell voltage spread vs. SoC, altitude vs. consumption, and more. Crosshair interaction shows exact bin ranges on tap. AWD cars gain an additional front vs. rear RPM torque-split heatmap.

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

Direct vehicle configuration, local database backup and restore, Connections for ABRP/MQTT, and Telegram-based encrypted backup. Settings are logically grouped and include an in-app FAQ covering common DiLink behaviour, autostart survival, and charging-session caveats.

<div align="center">
<table>
  <tr>
    <td align="center"><b>Preferences</b><br><img width="450" src="https://github.com/user-attachments/assets/18b0de45-3cce-439c-8dbb-38c621ec0257" /></td>
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

### VIII. Battery Degradation Tracking *(v1.4.0)*

Tap the Battery Health card on the dashboard to open a dedicated SoH-over-time view. A least-squares trend line projects future health and estimates the year the pack will reach 80% — the typical EV warranty threshold.

---

### IX. Seasonal Analysis & Trip Goals *(v1.4.0)*

**Seasonal Analysis** (☀️ in Trip History toolbar) groups all trips by meteorological season and visualises average consumption per season with a reference line from your car's WLTP figures. Automatically generates a winter-penalty insight when both winter and summer data are present.

**Trip Goals & Personal Bests** (🏆 in Trip History toolbar) tracks lowest ever efficiency, longest single trip, and longest consecutive daily driving streak. Set a consumption target and/or monthly distance goal — animated progress bars update in real time.

---

### X. Cost Tracking *(v1.4.0)*

Tap the € icon in Trip History to set your electricity tariff. Trip cost appears inline in the energy consumed field (`4.40 kWh (€0.62)`) and in a collapsible monthly summary card showing up to 12 months of history.

---

### XI. Hybrid Charging Session Recording *(v1.4.0 → v2.0.0)*

Two complementary mechanisms cover all charging scenarios with no user interaction required:

- **Car ON** — real-time session with full Power / SoC / Voltage / Temperature charts in the Charging Detail view
- **Car OFF** — SoC delta reconstruction on next wake-up still covers overnight, timed, and remote charging sessions when DiLink kills the app mid-charge

---

### XII. Connections *(v2.0.0)*

The app remains standalone for normal use, but can optionally forward live telemetry to external tools:

- **ABRP** — upload live car telemetry to ABRP via Link Generic token
- **MQTT** — publish Electro-compatible JSON to an external broker and topic, with drive/regen modes exported as readable names. Supports plain TCP, TLS (mqtts), and MQTT-over-WebSocket (ws/wss) so the broker can sit behind an HTTP reverse proxy. *(WebSocket requires a WebSocket listener enabled on the broker itself — e.g. Mosquitto needs a `listener` with `protocol websockets`; the reverse proxy forwards to that listener, it does not create one.)*

Both integrations are opt-in and can be disabled without affecting local trip recording, charts, backups, or dashboard telemetry.

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
| Optional outbound connections | ABRP + HiveMQ MQTT client | Disabled by default; used only when configured |
| Build | Gradle KTS | ProGuard release build, signed APK pipeline |
| Min SDK | API 29 (Android 10) | Matches DiLink 3.0 platform |

---

## 🗺️ Integration Roadmap

The project now runs as a standalone in-car telemetry application. Remaining work is focused on improving persistence and polishing a few edge cases.

```
Phase 1 — Legacy (External bridge)
  Electro app → MQTT broker → BYD Trip Stats

Phase 2 — Current (Standalone in-car operation)
  BYD Trip Stats running independently on supported vehicles

Phase 3 — Full OEM integration
  Logic absorbed into DiLink firmware; UI surfaced as a native DiLink panel
```

**What changes between phases:** the runtime moved away from the legacy bridge setup and into a self-contained in-car architecture. The ViewModel, Room persistence, charts, range projection engine, charging-session model, and UI remained largely intact, which is exactly why the migration was feasible without rewriting the product.

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
- **Zero default outbound telemetry:** No tracking, no crash reporting, and no data leaves the vehicle unless you explicitly opt in to one of the integrations below
- **Release privacy:** Release builds strip debug log calls and keep sensitive probe/discovery paths out of normal user-facing behaviour
- **GDPR-aligned by design:** No personal data is collected or transmitted by the app itself

### Optional external data flows

All three are **disabled by default** and require explicit configuration. You control the destination — the app sends nothing unless you set it up.

| Integration | What is sent | Destination |
|---|---|---|
| **Telegram bot backup** | Encrypted database backup file | Your own private Telegram bot (bot token + chat ID you supply). Data goes to Telegram's servers as a file attachment to your bot. |
| **MQTT broker** | Live telemetry JSON (speed, SoC, power, GPS, gear, etc.) at a configurable interval | An external MQTT broker you specify (e.g. HiveMQ Cloud, a self-hosted broker). You control the host, topic, and credentials. |
| **ABRP (A Better Route Planner)** | Live telemetry snapshot (SoC, speed, power, GPS) | ABRP servers, via the Link Generic API using a user token you provide. Subject to ABRP's own privacy policy. |

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

**1. Native System Integration** — Porting the application as a DiLink system-signed APK and turning the current standalone solution into a first-class OEM experience. This would deliver the full feature set to all DiLink-equipped BYD vehicles via OTA.

**2. Analytics Algorithm Licensing** — The range projection engine, trip intelligence logic, and consumption modelling are available for licensing into official BYD firmware or companion applications.

**3. Technical Collaboration** — A scoped engagement to evaluate, extend, or adapt this work for official roadmap integration.

For all other enquiries — bug reports, feature requests, community discussion — please open a [GitHub Issue](https://github.com/angoikon/byd-trip-stats/issues).

---

## 🤝 Contributing

Contributions are welcome! Whether it's bug reporting or new feature requests.

### Areas you might assist

- 🐛 **Testing** on Dolphin, Atto3 as well as other BYD models
- 🌍 **Translations** to reviews/corrections
- 📊 **New chart types** or visualizations
- 🗺️ **Enhanced route analysis** features
- 📱 **UI/UX improvements**

### Reporting Bugs

Found a bug? Open an [Issue](https://github.com/angoikon/byd-trip-stats/issues) with:
- Your BYD model
- Version app
- Steps to reproduce the problem
- Logcat output (if possible)

Alternatively, drop a message at [Discord](https://discord.gg/pf8TjjTce9) at #help channel

### Feature Requests

Have an idea? Open an issue with the "enhancement" label — well-reasoned requests are regularly considered for future releases.

### Testing Help

If you are running BYD Trip Stats on a **Dolphin, Atto3, or any other BYD model**, reports about what works and what doesn't are especially valuable.

---

## 🗺️ Roadmap

### Shipped

- [x] Predefined vehicle configuration ✅ *(v1.1.0)*
- [x] Charging session tracking ✅ *(v1.2.0)*
- [x] Trip comparison view ✅ *(v1.2.0)*
- [x] Heatmap: Tyre Pressure vs Consumption ✅ *(v1.4.0)*
- [x] Heatmap: SOC vs Regen Efficiency ✅ *(v1.4.0)*
- [x] Heatmap: Speed vs Battery Temperature ✅ *(v1.4.0)*
- [x] Heatmap: Cell Voltage Spread vs SOC ✅ *(v1.4.0)*
- [x] Battery degradation tracking ✅ *(v1.4.0)*
- [x] Cost tracking ✅ *(v1.4.0)*
- [x] Seasonal consumption analysis ✅ *(v1.4.0)*
- [x] Trip goals & personal bests ✅ *(v1.4.0)*
- [x] Standalone direct BYD telemetry runtime ✅ *(v2.0.0)*
- [x] ABRP and outbound MQTT Connections ✅ *(v2.0.0)*
- [x] Drive/regen mode timelines and analysis ✅ *(v2.0.0)*
- [x] Environmentals PM2.5 display ✅ *(v2.0.0)*
- [x] Slope in degrees display ✅ *(v2.0.0)*
- [x] App diagnostics CPU/RAM monitor ✅ *(v2.0.0)*
- [x] Physics-based per-trip energy breakdown ✅ *(v2.0.0)*
- [x] 12V DC monitoring when car is off — rolling 48-hour chart overlaying HV bus voltage, cell min/max, and SoC so 12V drain events (and the corresponding HV top-up) are immediately visible ✅ *(v2.1.0)*
- [x] Self-contained HTML trip viewer + one-click "Save as HTML viewer" export ✅ *(v2.5.0)*
- [x] Configurable engine-off trip timeout & minimum-trip-distance filter ✅ *(v2.5.0)*
- [x] Web dashboard companion — browse trip history and charts on any browser offline-first: upload your backup file, charts render locally, nothing leaves your device; built as a PWA so it can be added to your phone's home screen for a near-native experience ✅ *(v2.7.0)*
- [x] **Pro** unlock — optional premium tier via a short per-vehicle unlock code, verified entirely on-device (offline HMAC over the vehicle id, no account, can't be shared); single `EntitlementManager` gate. The free app is unchanged ✅ *(v2.9.0)*
- [x] Battery cell imbalance alert *(Pro)* — opt-in notification when the cell voltage spread exceeds a configurable limit (default 50 mV), with sustained-breach debounce, once-per-episode hysteresis, and a SoC guard; surfaces the diagnostic the Cell Voltage Spread heatmap already visualises ✅ *(v2.9.0)*
- [x] Trip merging — combine two auto-split trips that were the same journey, separated by a brief stop (e.g. petrol station, red light timeout). Select two contiguous completed trips in History → Merge; the earlier trip survives and the later one is absorbed. Cumulative figures (distance, energy, driving time) are the **sum of each trip's own recorded values** — robust to the BYD discharge counter resetting between trips — while the real start of the earlier trip and the real end of the later trip are kept ✅ *(v2.10.0)*
- [x] Recurring route detection — automatically groups completed trips that share the same journey (same start, end and ~distance; direction-sensitive, so the commute *to* work and *home* are tracked separately) once it's been driven 3+ times. A new **Routes** screen (toolbar icon in History) lists each recurring route with its average/best/worst efficiency and a per-trip efficiency trend, so you can compare how each run of your daily commute performed ✅ *(v2.10.0)*
- [x] Trip tagging — label trips with reusable, auto-coloured custom tags (e.g. "commute", "motorway", "errand"). Add/remove tags on a trip's detail screen, or bulk-tag several at once from History's selection mode; tags show as chips in the History list. Filter the list by tag (in the filter sheet), and a dedicated **Tags** screen rolls up each tag's trip count, total distance and average efficiency so you can compare categories ✅ *(v2.10.0)*
- [x] Multilingual support — the whole app is fully translated into **27 languages** (28 with English): Greek, German, French, Spanish, Italian, Dutch, Polish, Czech, Hungarian, Romanian, Swedish, Finnish, Danish, Norwegian, Portuguese, Brazilian Portuguese, Russian, Turkish, Thai, Hindi, Hebrew (RTL), Indonesian, Vietnamese, Japanese, Korean, and Simplified &amp; Traditional Chinese. Pick yours under **Settings → App → Language**; it overrides the head unit's system language for the app only (*System default* follows the car). Product terms (mode names like Eco / Normal / Sport, ABRP, MQTT, Pro) stay untranslated across every language ✅ *(v2.11.0)*

### Planned (v2.0.0+)

- [ ] DiLink home screen widget — quick-glance tile showing current SoC, last trip distance, and range projection without opening the app
**Vote on features** by 👍 reacting to issues!

---

## 🐛 Known Issues

### Current Limitations

1. **No offline charts** - Route maps require internet first time
2. **No trip editing** - Can't modify trip start/end times
3. **No cloud sync** - All data is local only

### Workarounds

- **Route not showing:** Check that GPS coordinates are non-zero and that DiLink location permissions/autostart are still enabled
- **Trip not auto-starting:** Verify auto-detection is ON, gear is D/R
- **Service not auto-starting:** Check Autostart permission (disable toggle at disable Autostart), reboot the UI and re-open the app

See [Issues](https://github.com/angoikon/byd-trip-stats/issues) for full list.

---

## ❓ FAQ

### Q: Does this work with other BYD EVs?
**A:** Potentially. Compatibility depends on how similar your car's DiLink firmware is to the tested models. Tested primarily on BYD Seal.

### Q: Do I need Electro or an Electro subscription?
**A:** No. The app runs standalone on supported vehicles and does not require Electro, a broker, or an MQTT topic for normal use.

### Q: Will this drain my car's 12V battery?
**A:** It uses your 12V which is always being charged via your high-voltage EV battery. Very minimal battery impact.

### Q: Can I use this without side-loading?
**A:** No. The ultimate goal is for BYD to implement it natively as part of the infotainment system, without the need to side-load.

### Q: Is my data secure?
**A:** Yes. All telemetry stays on your device by default. No analytics, no crash reporting, no advertising. The only optional outbound traffic is: **Telegram backup** (encrypted DB file to your own private bot), **MQTT publish** (live telemetry to a broker you configure), and **ABRP upload** (live snapshot to ABRP via your own user token). All three are opt-in and disabled unless you configure them. See the [Data Privacy](#-data-privacy--security) section for details.

### Q: Can I export to Excel?
**A:** Export as CSV, then open in Excel, Google Sheets, etc.

### Q: Why isn't the app receiving live data?
**A:** Check:
1. The app was started at least once after boot/update
2. DiLink's "Disable Autostart" blocker is toggled off for BYD Trip Stats
3. The car was rebooted after changing autostart behaviour
4. Location/storage permissions are still granted

---

## 🙏 Acknowledgments

### Built With

- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern Android UI
- [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client) - Optional outbound MQTT publishing
- [osmdroid](https://github.com/osmdroid/osmdroid) - OpenStreetMap for Android
- [Room](https://developer.android.com/training/data-storage/room) - Local database

---

## 💬 Community & Support

### Get Help
- 🐛 [GitHub Issues](https://github.com/angoikon/byd-trip-stats/issues)
- 💬 [Discussions](https://github.com/angoikon/byd-trip-stats/discussions)
- 🎮 [Discord — BYD Trip Stats](https://discord.gg/pf8TjjTce9)

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
- When Telegram backup is enabled, your encrypted database file is sent to Telegram's servers as a file attachment to your bot. If you do not trust a third-party server with your data even in encrypted form, use local filesystem backup instead


---

<div align="center">

**Angelos Oikonomou**
*Software Engineer · BYD Seal Owner*

📧 [bydtripstats@gmail.com](mailto:bydtripstats@gmail.com)

🔗 [github.com/angoikon](https://github.com/angoikon)

🎮  <a href="https://discord.gg/pf8TjjTce9"><img src="https://img.shields.io/badge/Discord-BYD_Trip_Stats-5865F2?style=flat-square&logo=discord&logoColor=white" alt="Discord"/></a>

---

*Independent project. Not affiliated with BYD Auto Co., Ltd. or the Electro application.*
*All trademarks belong to their respective owners.*

</div>
