# How to Use BYD Trip Stats

A plain-language guide for using the app in its current **Phase 2** form, where telemetry is read directly from the car.

---

## Before You Start

You need:

1. **A BYD vehicle with DiLink 3.0** (tested on Seal; should work on Atto 3, Dolphin, Seal U), or **DiLink 5.0** (Sealion 7), which uses its own build. The **Atto 3 EVO** has a **DiLink 100** head unit — its screen says *DiLink 5.0 UI*, but that is the interface version — and it uses the regular DiLink 3 build; speed, gear, power and trip distance work there, battery, range and odometer not yet
2. **BYD Trip Stats installed on the DiLink unit**

You do **not** need Electro or an MQTT topic for normal operation.

---

## Installation

1. Download the latest `.apk` from the [Releases page](https://github.com/angoikon/byd-trip-stats/releases) — on a DiLink 5 car such as the Sealion 7, the `dilink5` one; on every other car, including the Atto 3 EVO, the `dilink3` one
2. On the DiLink unit, enable installation from unknown sources when prompted
3. Grant the requested permissions
4. Open **BYD Trip Stats**
5. When it asks for the **one-time ADB authorisation**, tap **Authorize**, then **Allow** when the car asks to allow USB debugging

The ADB authorisation lets the app keep running in the background and restart itself after the car stops it, and on DiLink 3 it streams live speed and power up to 10 times a second. The Wi-Fi keepalive, Tailscale remote access and (on DiLink 5) in-app updates also run through it.

Later versions can be installed from inside the app: **Settings → About & FAQ** shows when one is available, with **Download update** and **Install now**. A database backup is taken before every install.

---

## First Launch

On first launch, select your exact car model.

This loads the correct:
- battery capacity
- WLTP range
- reference consumption
- recommended tyre pressures
- vehicle mass and drag area (used for energy breakdown)

You can change the selected car later by tapping the car name on the dashboard.

---

## Main Dashboard

The dashboard is the live vehicle screen.

### Top Bar

- **Car name** — tap to change model
- **History icon** — opens trip and charging history
- **Settings icon** — opens settings

### Energy Flow Area

With **Dashboard icons & animations** enabled (Settings → Preferences → Appearance — the default):

- **Battery icon** — live SoC, animated while charging
- **Drivetrain graphic** — tyre pressure overview
- **Tyre badges** — green = healthy, orange/red = attention needed, grey = no data yet
- **Consumption chart thumbnail** — tap to expand daily / monthly / yearly consumption

With that preference turned **off**, these elements move into the top app bar (battery + consumption icons) and a dedicated **Tyres** card on the right-side stat panel — freeing the full height for the range projection chart and disabling animations to lower CPU load on older firmware.

### Range Projection

The main chart compares:

- **Dashed line** — the car's own BMS estimate
- **Solid line** — BYD Trip Stats projection from your actual driving

Before you move off, the chart shows a **SCANNING…** placeholder. Once you start driving, the projection leaves the catalog baseline within the first few hundred metres and keeps refining as the trip continues. A small badge shows which model tier is currently driving the projection — **Live trip**, **Trip average**, **Lifetime avg** or **Baseline**.

### Right-side Stat Cards

Typical cards include:

- **Estimated SoH / Battery health**
- **Temperature** (ambient + battery)
- **HV / 12V**
- **Front / Rear Motors** (RPM + power split)
- **Odometer**
- **Total Discharge**

Notes:
- `Estimated SoH` is derived from telemetry unless a direct in-car SoH source is confirmed
- Cabin temperature may remain blank if the car does not expose a trustworthy live reading

### Bottom Metrics

- **Power** — live motor output or regen
- **Speed**
- **Battery (SoC)** — tap to switch between the state of charge and the energy left in kWh
- **Range** — the projected range (the car's own estimate until a projection exists)
- **Distance** — current engine-on segment distance; when a trip survives a brief stop the cumulative trip distance is shown alongside it. Tap to switch to the distance driven since your last charge

On DiLink 3, speed, gear, power and front-motor RPM update up to 10 times a second once the one-time ADB authorisation is done.

### Cards layout (Pro)

**Settings → Preferences → Appearance → Dashboard Layout** switches to **Cards**: big tiles instead of the central chart. Tap the pencil to drag tiles into a new order or hide the ones you don't need, and tap the Range tile for the projection chart full-size.

---

## Trip Recording

### Automatic recording

Recommended mode.

When **Auto** is enabled:
- you drive off (car on, in **D** or **R**, moving) → trip starts
- you switch the car off → trip ends once the **Engine-off trip timeout** has passed (Settings → Preferences → Trip recording — default 3 minutes)

Stops with the car still on — lights, queues, waiting in **P** — are part of the trip. Short engine-off breaks continue the same trip too, as long as you return within the timeout. If you'd rather be asked, turn on **Ask before auto-stopping** on the same page. The current engine-on segment distance and the cumulative trip distance are then shown side by side in the dashboard's Distance metric.

### Manual recording

If Auto is turned off, a manual record button is shown. Use this only when you intentionally want to skip automatic detection.

---

## Trip History

Open from the history icon.

You can:
- browse all trips
- sort by six criteria (date, distance, energy, duration, efficiency, cost) with ascending/descending toggle
- filter by date range, distance, energy, duration, and efficiency
- open trip details
- long-press to select multiple trips for comparison or export

### Trip Detail

Each trip detail screen includes:

**Overview** — key stats: distance, duration, energy, consumption, SoC change, max speed, max power, max regen, regeneration efficiency, cost.

**Charts** — per-trip timeseries:
- speed
- power
- SoC
- altitude
- motor RPM (front / rear / both, depending on drivetrain)
- instantaneous consumption
- battery voltage (HV bus + cell min/max band)
- tyre pressures (all four wheels)
- drive mode and regen mode timeline

**Heatmaps** — 14 correlation dimensions including power vs speed, consumption vs speed, regen vs speed, RPM vs speed, battery temp vs power, SoC vs consumption, altitude vs consumption, tyre pressure vs consumption, and more. AWD cars gain an additional front vs rear RPM torque-split heatmap.

**Route** — trip path on OpenStreetMap with energy event markers. Works fully offline once the map tiles are cached.

**Analysis** — computed insights and the **Energy Breakdown** (see below).

### Energy Breakdown

Found in the Analysis tab of each trip detail.

The breakdown splits total energy consumed into four physics-based components:

| Component | What it represents |
|---|---|
| **Rolling resistance** | Energy lost to tyre friction over the trip distance. Proportional to mass × distance. |
| **Aerodynamic drag** | Energy lost to air resistance. Proportional to CdA × speed² × distance. |
| **Net gradient** | Net energy cost of elevation changes. Climb costs energy; descent recovers some via regen. Shown as climb / descent split. |
| **Auxiliary losses** | Remainder after the three modelled forces. Covers 12 V system, HVAC, GPS noise, and model residual. |

All components are **scaled proportionally to always sum to the measured consumed energy**, so the numbers are always internally consistent.

Notes:
- Requires vehicle mass and CdA from the car catalog (all supported models have these)
- HVAC is not separately quantified — the DiLink firmware does not expose a reliable real-time HVAC power signal, so it appears inside auxiliary losses
- The percentage shown next to each component is its share of total consumed energy

### Trip Export

From a trip's detail screen, open **Export** for three formats:

- **CSV** — every recorded telemetry field per data point, for spreadsheets
- **JSON** — the same data in structured form
- **HTML viewer** — a single self-contained `.html` file with the trip data embedded inside it. Double-click it on any computer and every chart renders in the browser — no separate JSON to manage, no viewer to host, nothing to install

Each format can be saved to the car's Download folder or sent straight to your configured Telegram bot. The Download and Telegram groups in the Export dialog are collapsible — tap a section header to expand it.

### Trip Comparison

Long-press a trip → select 2–3 trips → tap the compare icon.

Opens a side-by-side view with:
- **Summary tab** — metric table with winner highlighted per row
- **Charts tab** — overlaid speed, power, consumption, SoC, and elevation normalised to 0–100% trip distance
- **Routes tab** — all routes on a shared map with per-trip visibility toggle

---

## Charging History

Charging sessions are recorded separately from trips and cover two scenarios:

- **Car ON** — real-time session with full Power / SoC / Voltage / Temperature charts
- **Car OFF** — SoC delta reconstruction on next wake-up covers overnight, timed, and remote charging when DiLink kills the app mid-charge

### Charging Detail

Five tabs:
- **Overview** — summary: kWh added, SoC start/end, peak/avg kW, duration, cost, and the distance driven since the previous charge
- **Power + SoC** — dual-axis chart; switch between Time and SoC x-axis (SoC mode is useful for DC taper analysis)
- **Voltage** — HV bus voltage over time
- **Cells** — each cell's high and low voltage, and the spread between them
- **Temperature** — battery temperature rise during the session

### Charging Costs

Trip details can account for:
- **Fixed home tariff** — set once in Settings → Preferences → Costs → Electricity tariff
- **Custom DC charging cost override** — enter the real amount paid for a public charging stop on any individual trip

---

## Consumption Charts

Tap the small chart thumbnail on the dashboard to expand:

- **Daily consumption**
- **Monthly consumption**
- **Yearly consumption**

The chart shows your selected-duration average alongside your selected car's reference consumption. Outlier points outside a plausible consumption range are filtered to keep the chart readable.

---

## Analytics

### Battery Degradation

Tap the **Battery Health** stat card on the dashboard.

Shows SoH per trip over time with a least-squares trend line, a projected trajectory, and an estimated year when the pack will reach 80% — the typical EV warranty threshold.

### Seasonal Analysis

Accessible from the ☀️ icon in the Trip History toolbar.

Groups all trips by meteorological season (Spring / Summer / Autumn / Winter) and shows average consumption per season as a colour-coded bar chart. Automatically highlights a winter efficiency penalty when both winter and summer data are present.

### Trip Goals & Personal Bests

Accessible from **Settings → Preferences → Goals & personal bests**.

- **Personal bests** — lowest efficiency, longest trip, longest consecutive daily driving streak
- **Goals** — set a consumption target and/or a monthly distance goal; animated progress bars update in real time

---

## Settings

Open from the gear icon. Five tabs. **App**, **Connections** and **Preferences** each open on an
overview of cards that show the current state at a glance — tap a card for that topic on its own
page, and use the back arrow (or the car's back gesture) to return.

### App

| Card | What's inside |
|---|---|
| **Power & background** | What keeps running when the car is off (**Always On** / **Minimal** / **Deep Sleep**), and on DiLink 3 **Keep Wi-Fi alive when car is off**. On DiLink 5, also **Allow reading vehicle data** |
| **Backup & Restore** | Opens the backup screen — local, SD card (Pro) and Telegram backups, restore, settings backup, and reset |
| **Vehicle Compatibility** | Records a telemetry report that helps add support for your model; send it by Telegram, save it, or email it via a QR code |
| **App Diagnostics** | Sends the diagnostics log the same three ways, and shows live CPU / memory, permissions and an ADB shell |

### Connections

Everything the app talks to outside the car. **All are off by default except the web companion**,
which only answers on your own network. Five cards show live status at a glance; **tap one** to
configure that connection on its own page.

| Connection | What it does |
|---|---|
| **ABRP** | Sends a live telemetry snapshot (SoC, speed, power, GPS) to ABRP servers via the Link Generic API, using your user token |
| **MQTT** | Publishes full telemetry JSON at a configurable interval to a broker you specify (host, port, topic, credentials) |
| **Web Companion** | The companion's on/off switch, port and PIN, and every address it can be reached at |
| **Tailscale** | Puts the car on your private network so the web companion and ADB work from anywhere |
| **Telegram** | Links your own bot — used for backups, notifications, diagnostics and trip exports |

ABRP and MQTT each have a **Test** action and show a last-sync timestamp. Disabling any of them has
no effect on local trip recording or the dashboard.

### Preferences

| Card | What's inside |
|---|---|
| **Appearance** | Theme (System / Light / Dark / Neon *Pro*), dashboard layout (Classic / Cards *Pro*), dashboard icons & animations — off moves the icons into the top bar and lowers CPU use on older firmware |
| **Language & units** | App language (overrides the head unit's for this app only), metric or imperial |
| **Trip recording** | Engine-off trip timeout (default 3 minutes), ask before auto-stopping, minimum trip distance (shorter trips are discarded; 0 keeps every trip) |
| **Battery** | SoC source (dashboard **Panel** percentage or the **BMS** reading), and the cell-imbalance alert *(Pro)* |
| **Costs** | Electricity tariff (price per kWh and currency) |
| **Goals & personal bests** | Opens the Goals screen |

### Pro

The unlock card, and what each Pro feature does. See the website or the in-app card for how to get a
code.

### About & FAQ

- App version, and **Download update** / **Install now** when a new version is out
- In-app FAQ covering common DiLink behaviour, autostart survival, and charging-session caveats
- Disclaimer

---

## Backup & Restore

### Local backup

`Settings → App → Backup & Restore`

Backups are stored in `Download/BydTripStats/` on the car's internal storage, as compressed
`.db.gz` files with a small settings file beside each one. Restore reads both `.db.gz` and older
`.db` backups. With Pro, **Backup to SD card** writes to a removable card as well, which survives an
app uninstall.

### Telegram backup

Connect a private Telegram bot for remote personal backups. This is optional.

Setup: link the bot once in **Settings → Connections**, alongside MQTT and ABRP — paste the token from @BotFather and the app finds your chat ID itself. Backups then live in **Settings → App → Backup & Restore**, where you can send one manually or set a schedule (daily / weekly / monthly).

The same bot carries **notifications** if you want them (Connections → Telegram Notifications): a summary after each drive, a note when a charge finishes, and the Pro cell-imbalance alert. Each has its own switch and all are off until you turn them on. On DiLink 5, trip summaries are sent only when you end the trip yourself with **Stop** — the car closes the app at switch-off, so the app can't announce a trip it only finishes later.

**Note:** When enabled, your database backup is sent to Telegram's servers as a file attachment to your bot. It is compressed, **not encrypted** — anyone with access to that chat can read your trip history. If you prefer to keep data entirely off third-party servers, use local or SD-card backup instead.

Telegram lets a bot download files of at most **20 MB**, so restoring from the in-app Telegram list only works for backups up to that size. A larger one: save it from the chat on your phone, upload it to *Backups (Download)* in the web companion's **Files** tab, and restore it from the backup list.

---

## Web companion

The app runs a small web server so you can look at your data from a phone or laptop on the same
Wi-Fi. It is **on by default**. **Settings → Connections → Web Companion** shows its PIN and every
address it answers on — open one in a browser and enter the PIN.

It shows your trips and charging history, a **notification feed** (the bell in the header: trip
summaries, finished charges, the Pro cell-imbalance alert — it works with no Telegram bot at all),
the **battery history** chart and a **Files** tab (both below). Away from home, use
[Tailscale](#remote-access-tailscale).

---

## Battery history in the web companion

The 🔋 button beside the notification bell opens the same **48-hour HV / 12V chart** as the
dashboard card: **12V** on the left axis, **SoC** on the right, charging periods shaded green, and
Latest / Min / Max / Δ above it along with the current HV pack voltage.

Gaps in the line are stretches where the app wasn't running, so nothing was sampled. On a DiLink-3
with **Always On** and the parked **Wi-Fi keepalive** enabled the trace is continuous, which is what
makes it useful for watching the 12V overnight from somewhere else — over
[Tailscale](#remote-access-tailscale), from anywhere.

---

## Getting files off the car (web companion → Files)

The web companion has a **Files** tab, so backups, exports and the diagnostics log can be pulled off
the head unit onto whatever device you are holding — no cable, no file manager on the car.

Open the companion (**Settings → Connections → Web Companion** shows the address), enter the PIN, and
pick **Files**. Four folders may be listed:

| Folder | What's in it |
|---|---|
| **App files** | The diagnostics log (`diag.log`) and HTML trip exports |
| **Backups (Download)** | What *Save to Download* writes — `Download/BydTripStats/` |
| **Screenshots** | Screenshots taken on the head unit — `Download/Screenshots/` |
| **Backups (SD card)** | The card's `BydTripStats/` folder, if a card is inserted |
| **Automatic backups** | The rolling copies the app keeps itself — **read-only** |

- **Download** saves the file to your device.
- **View** opens text files — `diag.log` above all — in the browser without downloading first, and
  shows screenshots full-size.
- **Upload** copies a file from your device into the folder you're in: a database backup to restore,
  for example. It is not available in *Automatic backups*.
- **Delete** removes a file after asking you to confirm. Files only, never folders, and not in
  *Automatic backups* — the app prunes those itself.

Over [Tailscale](#remote-access-tailscale) this works from anywhere, which makes fetching a
diagnostics log something you can do without going out to the car.

**What it does not do:** it is not a browser for the whole head unit. Only the folders above are
listed, and everything is behind the companion's PIN — the companion also answers on the car's Wi-Fi,
and a database backup is your complete trip history. For wider access, use ADB.

---

## Remote access (Tailscale)

The web companion and ADB normally work only on your home network. Putting the car on a
[Tailscale](https://tailscale.com) network makes both reachable from anywhere — over mobile data,
including while the car is turned off — without exposing anything to the internet or opening ports on
your router. It is optional and off until you set it up.

You do **not** install the Tailscale app on the head unit. The app carries the Tailscale daemon and
runs it itself, using the same one-time ADB authorisation that instant telemetry uses.

**Setup — the easy way, nothing to type**

1. Create a free account at [tailscale.com](https://tailscale.com), and install Tailscale on your
   phone or laptop signed into that account.
2. In the car: **Settings → Connections → Tailscale → Sign in with your phone**.
3. A **QR code** appears. Scan it with your phone's camera and approve the sign-in in the browser it
   opens. That's it — the car joins your network, and you never type anything on the car screen.

The sign-in waits patiently, so you can leave the screen while you do it. If sunlight makes the QR
unreadable, the same link is printed underneath it.

**Other ways in** (under *Other ways to sign in*)

- **Type the key from your computer.** Generate an auth key in the console (**Settings → Keys**,
  leave *Ephemeral* off), tap the Auth key box on the car so it has focus, then from your computer:
  `adb shell input text "tskey-auth-…"`. You already have ADB set up, since Tailscale needs the same
  authorisation.
- **Paste an auth key** directly into the field, if you have some way to get it onto the car's
  clipboard.

The app then shows the car's address, e.g. `100.99.138.80`, with the two things you can do with it:

```
http://100.99.138.80:8888      # the web companion, from anywhere
adb connect 100.99.138.80:5555 # ADB, from anywhere
```

The address is **stable** — it stays the same across reboots and across switching between Wi-Fi and
mobile data.

**Encrypting it (optional)**

Turn on **Serve over HTTPS** and the Tailscale daemon terminates TLS itself, using a real
certificate for the car's Tailscale name. The companion then answers at
`https://<car>.<tailnet>.ts.net/` instead of an `http://` address, so the browser stops calling it
insecure — and features browsers only allow on a secure page start working, notifications and
copy-to-clipboard among them.

Two one-time settings are needed first, in the Tailscale admin console. They apply to your whole
network, not just the car, and both are free:

1. On your phone or computer, open [login.tailscale.com/admin/dns](https://login.tailscale.com/admin/dns).
2. **MagicDNS** — check it is on. Confusingly, it is **on** when the button reads
   *"Disable MagicDNS…"* — that button would turn it off, so leave it alone. If you see
   *"Enable MagicDNS…"* instead, click it.
3. **HTTPS Certificates** — click *"Enable HTTPS…"* and confirm.
4. Back in the car: **Settings → Connections → Tailscale → Serve over HTTPS**. Nothing needs
   restarting.
5. Open the `https://` address the app now shows — **exactly as shown, with no `:8888` on the end**.
   The encrypted address uses the standard HTTPS port; only the `http://` addresses need a port.
   The first load takes a few seconds while the certificate is issued, then it is instant.

Enabling HTTPS exposes nothing to the internet — the certificate just proves the car is who it says
it is, to devices already on your network. Only the `.ts.net` name is encrypted: a certificate
cannot be issued for a bare `100.x` address, so that one and the Wi-Fi address stay `http://`.

**Three things to set once, in the Tailscale admin console**

- **Disable key expiry** for the car (Machines → your car → ⋯ → *Disable key expiry*). Otherwise it
  silently drops off your network after about six months, and only a trip to the car can fix it.
- **Do not set an exit node** on the car — that would route its ABRP, MQTT and Telegram traffic
  through someone else's internet connection.
- **Do not enable Funnel** — that would publish the web companion on the public internet, behind
  nothing but its PIN.

**DiLink-5:** the car force-stops every app when you switch off, so remote access stops when the car
does and comes back when the car next wakes. While the car is on it works normally.

---

## External Data: What Leaves the Car

By default, **nothing leaves the car**. The following are opt-in only:

- **Telegram backup** — a compressed (not encrypted) database file sent to your own private Telegram bot when you configure it and trigger a backup
- **Telegram notifications** — a short text card (trip summary, charge finished, battery alert) sent to that same bot, only for the events you switch on
- **MQTT** — live telemetry JSON published to a broker you specify, at the interval you set
- **ABRP** — live telemetry snapshot sent to ABRP using the token you provide
- **Tailscale** — once you sign the car in, it joins your own private network; traffic goes to your devices (end-to-end encrypted) and sign-in to Tailscale's servers, and nothing is exposed publicly
- **Email via QR code** — when you tap it on Vehicle Compatibility or App Diagnostics, that report or log is uploaded to a temporary file host so you can email its link; the link expires after 24 hours
- **Update checks** — the app can check for new APK releases; this is the only network call made without explicit user setup

The **web companion** is on by default, but it only *answers* browsers on your own network (or your
tailnet) that know its PIN — it never sends anything out.

---

## Autostart / Survival

DiLink may kill background apps aggressively. To improve survival:

1. Open the car's **Disable Autostart** app
2. Find **BYD Trip Stats**
3. Toggle its entry **OFF** (so autostart is effectively enabled)
4. Reboot the car UI
5. Open BYD Trip Stats again

**Important:** this setting often resets after app updates. Re-check it after every install.

The app uses a foreground telemetry service, wake lock, Wi-Fi lock, boot receiver, and a watchdog worker — but survival still depends on the OEM system behaviour. With the one-time ADB authorisation, a small background helper also brings the app back after the car has stopped it.

---

## What Is Direct and What Is Derived

### Read directly from the car

- SoC
- Charging power
- Speed
- Gear
- Odometer
- HV / 12V voltage
- Motor RPM (front / rear)
- Cell voltage min/max
- Tyre pressures
- Battery temperatures
- Most trip and charging telemetry

### Calculated by the app

- Estimated SoH
- Range projection
- Trip costs
- Energy breakdown (rolling, aero, gradient, auxiliary)
- Battery temperature midpoint when only cell min/max exists
- Analytics, averages, goals, seasonal summary

---

## Known Caveats

- **Estimated SoH** is not yet confirmed as a direct BMS SoH source on all firmware builds
- **Cabin temperature** may be blank — the app avoids showing HVAC setpoints as if they were real cabin air readings
- **HVAC power** is not quantified separately in the energy breakdown — DiLink does not broadcast reliable real-time compressor power
- **Overnight charging capture** depends on whether DiLink keeps the app alive during sleep

---

## Tips

- Re-check Autostart after every app update
- The range projection starts from your own lifetime average and moves to this trip's data within the first few hundred metres — the model-tier badge tells you when it's running on the current trip (Trip average / Live trip) rather than your history (Lifetime avg) or the catalogue figure (Baseline)
- Use the **SoC x-axis** mode in the charging Power + SoC chart for DC taper analysis
- Use **Heatmaps** to spot correlations — e.g. speed vs consumption to find your car's efficiency sweet spot
- Use the **Seasonal Analysis** view after your first winter to see the real cold-weather efficiency penalty
- Use **Trip Comparison** on your regular commute routes to track consistency over time
