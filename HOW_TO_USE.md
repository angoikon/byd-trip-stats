# How to Use BYD Trip Stats

A plain-language guide for getting the most out of the app — no technical knowledge required.

---

## Before You Start

You need two things before BYD Trip Stats can work:

1. **A BYD vehicle with a DiLink infotainment screen** (Seal, Dolphin, Atto 3, or similar)
2. **The Electro app** installed on your car's DiLink unit, with an active subscription

Electro is the bridge between your car and BYD Trip Stats. It reads the car's telemetry and broadcasts it locally so BYD Trip Stats can pick it up. Without Electro running, no data arrives.

> **Electro subscription:** roughly €30/year. Find it at [electro.app.br](https://electro.app.br).

---

## Installation

1. Download the latest `.apk` file from the [Releases page](https://github.com/angoikon/byd-trip-stats/releases) on GitHub
2. On your DiLink unit, open the downloaded file and follow the on-screen prompt to install — the system will ask you to allow installation from unknown sources, which is normal for apps installed outside the Play Store
3. Allow access to your storage files so data can be written there, along with backups
4. Once installed, find **BYD Trip Stats** in your app drawer and open it

---

## First Launch — Initialization

The very first time you open the app you will see a setup screen with two steps.

**Step 1 — Choose your car**

Tap your exact model from the list (e.g. "BYD Seal Dynamic", "BYD Dolphin Extended", etc.). This tells the app things like your battery size, WLTP range, reference consumption, and tyre pressures — all specific to your variant.

**Step 2 — Enter your MQTT topic**

This is the address the app uses to receive data from Electro. To find it:

1. Open the **Electro** app
2. Go to **Integrations → MQTT**
3. Copy the topic shown there (it looks something like `electro/telemetry/byd-seal/data`)
4. Paste it into the MQTT Topic field in BYD Trip Stats

Tap **Continue**. You only do this once — the settings are saved permanently.

> **Note:** If you ever need to change your car model later, tap the car name displayed next to the title on the main dashboard. To change the MQTT topic, go to Settings → Network.

---

## The Main Dashboard

The dashboard is what you see when you're driving. Here's what everything means.

### Top Bar

- **Car name** (tappable) — shows your currently selected model. Tap it to switch cars
- **Sync icon** (top right area) — shows the connection status:
  - 🟢 Green sync icon — connected and receiving live data
  - 🔴 Red sync icon — connection error (check Settings)
  - ⚫ Disabled icon — connected byt not receiving telemetry (check settings)

### Energy Flow Area

The central section of the dashboard shows:

- **Battery icon** (left) — a liquid-fill animation showing your current charge level. Animated while charging
- **Drivetrain diagram** (centre) — tap it to change tyre pressure units (Bar / PSI / kPa)
- **Tyre pressure badges** — a small coloured label at each corner of the drivetrain image:
  - 🟢 Green = pressure is within ±0.2 bar of the recommended value
  - 🟠 Orange = pressure is low (more than 0.2 bar below recommended)
  - 🔴 Red = pressure is high (more than 0.2 bar above recommended)
  - Grey = no data received yet
- **Consumption chart thumbnail** (top right corner) — a small sparkline of your recent efficiency. Tap it to open the full consumption chart

### Range Projection Chart

The large chart below the energy flow shows your projected remaining range as you drive:

- **Dashed grey line** — the car's own BMS estimate (what the dashboard usually shows)
- **Coloured solid line** — BYD Trip Stats' own projection, calculated from your actual energy consumption
  - 🟢 Green = you are being more efficient than the BMS expects
  - 🟠 Orange = you are consuming more than the BMS expects
- The header above the chart shows the projected range in km and how many km ahead or behind the BMS estimate you are

Tap anywhere on the chart to flip it to a full-screen expanded view. Tap again to flip back.

### Stats Column (right side in landscape)

A list of live readings updated every second:

| Stat | What it means |
|---|---|
| Battery health | State of Health (SoH) — 100% when new, decreases slowly over time |
| Battery temperature | Average pack temperature; cell min–max range shown below |
| HV / 12V | High-voltage bus and 12V auxiliary battery voltages |
| Motor RPM | RPM for your driven axle(s). AWD cars also show an estimated kW split |
| Odometer | Total kilometres on the car |
| Total Discharge | Cumulative energy drawn from the battery since the car was manufactured |

### Power Metrics Row (bottom)

Five numbers shown across the bottom of the energy flow card:

**Power** · **Speed** · **Battery (SoC)** · **Range (BMS)** · **Distance**

"Distance" is your session distance — it resets to zero each time the car (and therefore the app) starts, so it shows how far you've driven in the current session.

---

## Trip Recording

BYD Trip Stats can record your trips automatically or manually.

### Automatic recording (recommended)

By default, the **Auto** toggle in the Trip Tracking card is on. The app watches your gear position:

- Gear shifts to **D** or **R** → trip recording starts automatically
- Gear shifts back to **P** → trip is saved automatically

You don't need to do anything. Just drive.
> **Note:** While in auto and driving, if you touch the stop button and confirm, a new trip is going to automatically start being recorded

### Manual recording

If you turn off the Auto toggle (you'll see a warning — read it carefully), a **Record** button appears. Tap it to start recording, tap **Stop** when you're done. A confirmation dialog will appear before the trip is closed, to prevent accidental stops.

> **Tip:** Leave Auto on. Manual mode is only useful in certain cases. If you leave it in manual and don't initiate recordings, you will not record daily consumption (amongst other things).

---

## Trip History

Tap the **History** icon (clock, top right of the dashboard) to see all your saved trips.

Each trip card shows the date, distance, duration, and average consumption. You can:

- **Filter** trips by date range, distance, energy used, duration, or efficiency
- **Sort** by any of six criteria (tap the sort icon)
- **Tap a trip** to open the full detail view

### Trip Detail

The trip detail screen has several tabs:

**Overview** — summary stats: distance, duration, energy consumed, average speed, regen recovered, efficiency

**Charts** — interactive charts you can scroll and tap:
- Speed over time
- Power over time (positive = driving, negative = regenerating)
- State of Charge (battery %) over time
- Altitude over time
- Motor RPM (front and/or rear depending on your car)
- Energy consumption rate

**Route** — your route drawn on a map with energy event markers overlaid

**Route Analysis** — further breakdown of the route with stats per segment

**Heatmaps** — scatter heatmaps that show patterns across the whole trip (see below)

**Export** — tap the share icon (top right) to export the trip as CSV or JSON

### Heatmaps

Heatmaps are colour grids that reveal patterns. Each cell's colour represents how many data points fell into that combination of two values — the brighter the colour, the more time you spent there.

Examples of what you can learn:
- **Power vs Speed** — where your motor is working hardest
- **Consumption vs Speed** — your most and least efficient speed ranges
- **Regen vs Speed** — at what speeds you recover the most energy
- **Gradient vs Consumption** — how hills affect your efficiency
- **Battery Temp vs Power** — whether cold battery limits your acceleration

Tap and hold any cell to see the exact value ranges and sample count for that bin.

---

## Settings

Tap the **gear icon** (top right of the dashboard) to open Settings.

### Network tab

This is where your MQTT connection lives. The two status cards at the top show:

- **Embedded MQTT Broker** — the internal broker that receives data from Electro. It should always show "Running"
- **Connection status** — shows whether the app is connected and receiving data

If you need to change the MQTT topic (e.g. you renamed your integration in Electro), update it here and tap **Save & Restart MQTT connection**.

For the internal Electro setup the correct values are:
- Broker URL: `127.0.0.1`
- Port: `1883`
- No username or password

### Data tab

Opens the Backup & Restore screen. See the Backup section below.

### About & FAQ tab

Contains a built-in FAQ that answers the most common questions about setup, connections, and data.

---

## Backup & Restore

Your trip data is precious. BYD Trip Stats supports two backup methods.

### Local backup

Go to **Settings → Data → Open Backup & Restore → Backup Now**.

The backup file (a standard SQLite database) is saved to `Download/BydTripStats/` on your car's internal storage. You can pull it to your computer via ADB or copy it to a USB drive.

### Telegram backup (recommended)

This sends your backup to a private Telegram bot that only you control — think of it as your own personal cloud, with zero third-party access.

1. Create a Telegram bot via [@BotFather](https://t.me/BotFather) and copy the bot token
2. In BYD Trip Stats go to **Backup & Restore → Telegram Backup → Connect**
3. Paste the token and follow the prompts

Once connected you can:
- **Send backup now** — manual on-demand backup
- **Enable auto-backup** — choose Daily, Weekly, or Monthly; the app handles the rest

### Restoring a backup

In **Backup & Restore**, tap the refresh icon under **Local Restore** or **Restore from Telegram** to load available backups. Tap any entry and confirm to restore. The app will validate the file before replacing any data.

> **After a reinstall:** your local backups in `Download/BydTripStats/` survive uninstalls. After reinstalling, just open Backup & Restore, tap refresh, and your backups will reappear.

---

## Autostart Permission (Important)

BYD DiLink has a built-in autostart manager that kills apps after a car restart unless you whitelist them. Without this, the app stops working every time the car reboots.

1. Find the **Disable Autostart** app on your DiLink (it's a native BYD app, usually near the file manager)
2. Find **BYD Trip Stats** in the list and set its toggle to **OFF** (disabled = allowed to autostart)
3. Do the same for **Electro** if you haven't already
4. You need to repeat this step after every app update, because the permission resets on reinstall

---

## Tips

- **Set Electro's publish interval to 1 second** while the car is on — this gives smooth charts and accurate consumption figures. While the car is off, 5 minutes is fine.
- **The range projection needs about 2 km to calibrate** — it will show "Calibrating…" at the start of a trip. This is normal.
- If the MQTT connection shows an error after changing settings, tap **Save & Restart MQTT connection** in Settings → Network.
- The consumption chart thumbnail on the dashboard shows the last 7 days. Tap it to see 30-day and 12-month views.
- Trip auto-detection relies on the gear signal from MQTT — if Electro is not running, trips will not be recorded automatically.