# Changelog

All notable changes to **BYD Trip Stats** will be documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.1.0] - 2025-Mar-13

### Added

- **Initialization screen** — first-run setup screen that prompts the user to select their BYD model and enter their Electro MQTT topic before the app becomes usable. Settings are persisted atomically so neither value can be missing after setup.
- **Car catalog (`CarConfig`)** — structured per-car configuration covering drivetrain, battery capacity, WLTP range, reference consumption (kWh/100 km, sourced from ev-database.org), and recommended front/rear tyre pressures. Supported models at launch:
  - BYD Seal Dynamic (RWD, 61.4 kWh)
  - BYD Seal Premium (RWD, 82.5 kWh)
  - BYD Seal Excellence (AWD, 82.5 kWh)
  - BYD Dolphin Active / Boost (FWD, 44.9 kWh)
  - BYD Dolphin Extended / Comfort / Design (FWD, 60.4 kWh)
  - BYD ATTO 3 (FWD, 60.4 kWh)
- **In-app car switcher** — tapping the car name in the dashboard top bar opens a dialog to change the selected model without going through settings.

### Changed

- **Motor RPM card (dashboard stats)** is now drivetrain-aware:
  - FWD cars show "Front Motor — N RPM" only.
  - RWD cars show "Rear Motor — N RPM" only.
  - AWD (Seal Excellence) shows both axles with a proportional live kW estimate (front 160 kW / rear 230 kW split, confirmed per BYD factory specs; total 390 kW combined).
- **Motor RPM chart (trip detail)** shows only the motor(s) present on the selected car. The legend and crosshair tooltip adapt accordingly (FWD: front only; RWD: rear only; AWD: both).
- **Heatmaps tab (trip detail)** — motor-related heatmaps adapt to drivetrain:
  - "Motor RPM vs Speed" title and axis label reflect the driven axle(s).
  - "Front vs Rear Motor RPM" torque-split heatmap is only shown for AWD cars.
- **Tyre pressure indicators** now use the recommended pressures from the selected car's config (previously hardcoded to Seal Excellence values). Alarm thresholds (±0.2 bar) remain car-agnostic.
- **Consumption chart reference line** uses the selected car's reference consumption from the catalog instead of a hardcoded constant.
- **Range projection chart** caps the projected range at the selected car's WLTP figure instead of a hardcoded 520 km.
- **MQTT connection status** in the Settings Network tab is now split into three distinct visual states with matching icons:
  - `SyncProblem` (red) — connection error with error message.
  - `Sync` (green) — connected and actively receiving telemetry.
  - `SyncDisabled` (muted) — disconnected or no data yet.
  - Same three states are mirrored in the dashboard top bar icon.

### Fixed

- `PreferencesManager.getMqttSettings()` would hang indefinitely due to using `collect` (infinite terminal) instead of `first()` on the DataStore Flow.
- First-run race condition: saving the car selection and the MQTT topic as two sequential DataStore writes could cause the topic write to be skipped if recomposition navigated away from the initialization screen between the two writes. Both are now written in a single atomic `edit` block via `saveInitialSetup()`.

---

## [1.0.0] - 2025-Mar-08

### Added

- Initial release.
- Live MQTT telemetry dashboard (speed, power, SoC, range, gear, tyre pressures, battery stats, motor RPM).
- Automatic and manual trip recording with gear-based auto-detection (D/R → start, P → stop).
- Trip history with per-trip charts: speed, power, SoC, altitude, energy consumption, motor RPM, route map.
- Trip detail heatmaps: power vs speed, consumption vs speed, regen vs speed, RPM vs speed, battery temp vs power, acceleration vs speed, SOC vs consumption, time-of-day vs speed, gradient vs consumption.
- Range projection chart with BMS estimate reference line and power-integrated realistic projection.
- Weekly / monthly / yearly consumption charts with car average reference line.
- Liquid-fill battery indicator with charging animation.
- Energy flow canvas with animated directional arrows (acceleration / regen / charging states).
- Tyre pressure indicators with per-axle alarm thresholds and BAR / PSI / kPa unit switcher.
- Local backup to `Download/BydTripStats/` (SQLite).
- Telegram bot backup with manual send and scheduled auto-backup (daily / weekly / monthly).
- CSV and JSON trip export.
- Embedded MQTT broker on port 1883 for local Electro integration.
- Boot receiver and foreground service to keep MQTT alive without manual app restarts.
- DatabaseMaintenanceWorker for automatic old-data pruning.
- Dark-themed Material 3 UI optimised for the BYD DiLink 3 in-car infotainment display.