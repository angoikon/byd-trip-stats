# Home Assistant Integration

BYD Trip Stats publishes telemetry via MQTT using **Home Assistant MQTT Discovery** — no `configuration.yaml` editing required. Sensors appear automatically once the app connects to the same broker as HA.

---

## How it works

- The app publishes **retained** discovery config messages to `homeassistant/sensor/<name>/<key>/config` and `homeassistant/binary_sensor/<name>/<key>/config`. Because they are retained, the broker keeps serving them to HA across reconnects and app restarts, so they are sent again only when they actually change — an app update that changes the sensor set, a different friendly name, or a different broker — and once a day regardless, so a broker that has lost its retained messages recovers on its own. The app also re-sends them whenever Home Assistant announces itself on `homeassistant/status`. Press **Test & Save** to force a resend if your broker has lost its retained messages.
- Telemetry is published as a **single retained JSON blob** to `byd-trip-stats/<name>/state`.
- Each discovery config uses a `value_template` to extract its field from that JSON (e.g. `{{ value_json.soc }}`), so HA always has the latest value even after a restart.
- An availability topic (`byd-trip-stats/<name>/availability`) carries `online` / `offline` (Last Will and Testament), so HA marks entities unavailable on unexpected disconnect.
- While the car is **on**, state is published at your configured interval (1–120 s). While the **car is off**, it publishes every 30 s regardless of your setting (to keep HA's availability alive without flooding the broker).
- When a **trip** or a **charging session** closes, its summary is published as retained JSON to `byd-trip-stats/<name>/trip` and `byd-trip-stats/<name>/charge_session`. These are events rather than sensors, so they carry no discovery config and create no entities: subscribe to them directly, or template your own sensor from them. Values are always metric and unprefixed by unit system (km, kWh, kWh/100 km, kW, percent) no matter how the app is set to display them, and a field the car did not report is left out rather than sent as zero. A trip summary also carries `live_close`, which is `false` when the trip was finalised at the next ignition rather than when you parked — the normal path on DiLink-5 — and a charge summary carries `origin`, which says whether the session was watched, interrupted or inferred.

### Event topics

| Topic | Sent when | Notable fields |
| --- | --- | --- |
| `byd-trip-stats/<name>/trip` | A trip is finalised | `trip_id`, `distance_km`, `energy_kwh`, `efficiency_kwh_per_100km`, `fleet_avg_kwh_per_100km`, `avg_speed_kmh`, `soc_start_pct`, `soc_end_pct`, `trip_score`, `cost`, `currency`, `live_close`, `closed_at` |
| `byd-trip-stats/<name>/charge_session` | A charging session is finalised | `session_id`, `origin`, `energy_added_kwh`, `energy_received_kwh`, `avg_kw`, `peak_kw`, `rate_per_kwh`, `cost`, `currency`, `complete` |

Both carry `started_at` / `ended_at` as ISO 8601 UTC and `started_at_ms` / `ended_at_ms` as epoch milliseconds, `closed_at` / `closed_at_ms` (when the app published the record — for a trip with `live_close: false`, usually hours after it ended), plus `soc_source` (`panel` or `bms`). A trip always carries `duration_s`; a charge carries it only when one was measured, which is explained below. `energy_added_kwh` is the energy that reached the pack, derived from the SoC delta, so it reads lower than what a charger bills.

#### The two energies on a charge

A charge summary can carry two different quantities, and they are not interchangeable.

| Field | What it is |
| --- | --- |
| `energy_added_kwh` | What the pack ended up holding: the SoC change times the pack's capacity. Always present. |
| `energy_received_kwh` | What the car measured arriving, integrated from the power readings the session recorded. Present only when the session measured it. |

The second is the larger of the two by whatever was lost on the way in. It is **the car's own measurement, not a meter reading**, and where the car takes that measurement decides what it includes:

- **On DC** the charger feeds the pack directly and the car reads the power coming in, so the figure tracks the charger's own meter closely. On one metered session the car integrated to 28.36 kWh against 28.93 kWh on the charger, 2.0% under, where the SoC delta's 26.5 kWh was 8.4% under.
- **On AC** the car reports what leaves its on-board charger, and nothing in the car can see what went into it. A 7 kW wallbox reads about 6.2 kW in the car, so the conversion loss is missing from this figure and it sits below a wall-side meter — around 10% below, and further on a slow charge.

So treat it as what the car saw arrive, not as what you were billed. **`cost` is unchanged: it is `rate_per_kwh` times `energy_added_kwh`, exactly as before this field existed**, and `rate_per_kwh` is still the price the owner entered or the global tariff. If you want cost on a metered basis, enter the receipt total against the session, or price it here from your charger's own data.

`energy_received_kwh` is **absent whenever the recorded samples cannot account for the whole charge**:

- the session recorded no power readings at all, which is every `soc_estimate` session and every car whose SDK never reports charging power;
- it recorded fewer than two usable readings, or every reading was zero;
- the samples are too sparse to integrate, because the app was not running for part of the charge;
- or the integral does not come out at least 1% larger than `energy_added_kwh`. More arrives than the pack keeps, so an integral that fails to clear it has missed part of the charge, or is reading the pack side rather than the power coming in, where integrating it just reproduces the SoC delta. An efficient AC charge can fail this too, and a withheld figure is the intended outcome in all of those cases.

It is left out rather than sent as zero, for the same reason every other unreported reading is.

A `recovered` session's received figure, like its energy and its duration, is a lower bound: the samples stop where the app was killed.

#### How a charging session was recorded

Not every charge is watched. A charge the car takes while it is off can be reconstructed rather than recorded, depending on whether the head unit stays awake while it charges: some keep the app running for the whole session, others force-stop it at ignition-off and only find the charge at the next start. `origin` says which happened, and a charge summary also carries `closed_at` / `closed_at_ms`: when the app published the record, which for the last two is usually hours after the samples it describes.

| `origin` | What it means | What you get |
| --- | --- | --- |
| `live` | Watched from plug to unplug. | Everything, including `duration_s`, `complete` and the power readings. |
| `recovered` | Watched, then the app stopped watching: it was killed mid-charge and the row is finalised at the next start. | Real samples that stop before the app did, so `duration_s` and the energy are **lower bounds**. **No `complete`** — the app never saw the end, so whether the charge reached full is unknown rather than false. |
| `soc_estimate` | Never watched: the car was off. Inferred from the state of charge at shutdown against the one at wake-up. | Energy and SoC only. No power readings, and **no `duration_s`** — `started_at` and `ended_at` bound a window the charge happened somewhere inside, most of which was parking. |

A `live` charge is the equivalent of a trip's `live_close: true`. Only `live` reaches the phone notification, since a charge that ended overnight is not news the next morning; all three are published here. **A payload with no `origin` at all is `live`**: it was queued by a version of the app from before this field existed and flushed after the update.

Two things `recovered` does *not* promise:

- **It is not necessarily over.** `recovered` means the app stopped watching, not that the charge stopped. If the app is revived more than 15 minutes after it was killed and the car is still charging, the interrupted row is closed and published, and a new `live` session opens for the same cable. `ended_at` is where the samples stop.
- **It can be half of one charge.** A charge the app was killed through can reach you as two events with different `session_id`s: a `recovered` one for the stretch the app saw, and a `soc_estimate` one for the stretch after it died. They are disjoint in SoC and it is their `energy_added_kwh` figures that sum to the charge, but a consumer counting charges or totalling cost per session will see two. Only the watched half can carry `energy_received_kwh`, so the two received figures do not sum to anything: use `energy_added_kwh` to total a split charge. Dedupe does not merge them, because they are genuinely different records. This needs a charge that started while the car was still on: the app keeps saving the car's state while it is on, so the starting point for the estimate moves past the start of the charge, and the recovered session no longer covers the window the estimate would claim. A charge plugged in after the car was switched off can't split this way: the recovered session stops the estimate from being made at all.

These topics are **retained**, so a subscriber that connects later sees only the most recent trip and the most recent charging session. To capture every event you have to be subscribed when it is published.

If the broker is unreachable when an event closes, it is held and sent with the next successful state publish, so a summary is not lost to a drive that ended out of range. A queued event is only dropped once the broker has acknowledged it, which means an app killed mid-send, or a send that never got an answer, can publish the same event again. If a send fails, the next attempt waits 5 seconds, then 10, doubling up to a minute, so a broker that refuses these topics is not retried on every state publish; the state itself is never delayed by it. There is no replay protection: **dedupe on `trip_id` / `session_id`** rather than treating every message as a new event.

---

## Setup

### 1. Choose a broker

Both the car's head unit and Home Assistant must connect to the **same** MQTT broker. Options:

| Broker | Notes |
|---|---|
| **HiveMQ Cloud** (free tier) | Easiest if your car has internet. Port `8883` (TLS). |
| **Mosquitto add-on** (local) | Best for LAN-only setups. Runs inside HA. Port `1883` or `8883`. |
| Broker on your **tailnet** | With Tailscale on in the car, enter the broker's `100.x` address or its full `….ts.net` name (a short name isn't routed through Tailscale) — see [Remote access](HOW_TO_USE.md#remote-access-tailscale). |
| Any other broker | Works as long as both sides can reach it. |

### 2. Configure the app

Settings → Connections → MQTT:

| Field | Value |
|---|---|
| Enable MQTT | On |
| Broker URL | Your broker hostname (e.g. `abc123.s1.hivemq.cloud`) |
| Port | `8883` (TLS) or `1883` |
| Username / Password | Your broker credentials |
| **Device friendly name** | A short name with no spaces (e.g. `seal`). This becomes the topic prefix: `byd-trip-stats/seal/state` |
| Publish interval | 1–120 s (applies while driving; 30 s fixed while parked/off) |

Press **Test & Save** — this saves settings, connects, publishes the availability `online` message, and sends all discovery config messages to the broker.

### 3. Configure Home Assistant MQTT integration

Settings → Devices & Services → + Add Integration → MQTT:

- Broker: same hostname as above
- Port: same port
- Username / Password: same credentials
- Leave **Discovery** enabled (it is by default; prefix is `homeassistant`)

Once connected, HA will automatically pick up the retained discovery messages.

### 4. Verify

Settings → Devices & Services → MQTT → **Devices** — you should see a device named after your friendly name (e.g. `seal`) with all entities already created.

If entities show **unavailable**:
1. Press **Test & Save** again in the app (re-publishes `online` + all discovery configs).
2. In HA → Developer Tools → MQTT, subscribe to `byd-trip-stats/#` and confirm messages arrive.
3. Subscribe to `homeassistant/#` and confirm retained discovery configs are present.
4. If old/stale retained configs exist (from Electro or a previous setup), clear them by publishing an **empty payload** to each `homeassistant/sensor/.../config` topic (empty payload = delete retained message in MQTT).

---

## Sensors published

### Numeric / text sensors (`sensor.*`)

| Entity | Key | Unit | Device class |
|---|---|---|---|
| Current Datetime | `current_datetime` | — | — |
| State of Charge | `soc` | % | battery |
| SOC (Panel) | `soc_panel` | % | battery |
| Battery 12V Voltage | `battery_12v_voltage` | V | voltage |
| Battery Total Voltage | `battery_total_voltage` | V | voltage |
| Driving Range | `electric_driving_range_km` | km | — |
| Total Discharge | `total_discharge` | kWh | — |
| Speed | `speed` | km/h | speed |
| Gear | `gear` | — | — |
| Odometer | `odometer` | km | — |
| Engine Power | `engine_power` | kW | power |
| Engine Speed Front | `engine_speed_front` | rpm | — |
| Engine Speed Rear | `engine_speed_rear` | rpm | — |
| Charging Power | `charging_power` | kW | power |
| Fuel Percentage (PHEV only) | `fuel_percentage` | % | — |
| Fuel Driving Range (PHEV only) | `fuel_driving_range_km` | km | — |
| Drive Mode | `drive_mode` | — | — |
| Regen Mode | `regen_mode` | — | — |
| Energy Mode | `energy_mode` | — | — |
| Latitude | `location_latitude` | — | — |
| Longitude | `location_longitude` | — | — |
| Altitude | `location_altitude` | m | — |
| Heading | `heading` | ° | — |
| External Temperature | `ext_temp` | °C | temperature |
| Cabin Temperature | `cabin_temp` | °C | temperature |
| State of Health | `soh` | % | — |
| Statistic SOH | `statistic_soh` | % | — |
| Available Power | `available_power` | kW | — |
| Battery Remaining Energy EV | `battery_remain_power_ev` | kWh | — |
| Battery Pack Temp | `battery_pack_temp` | °C | temperature |
| Cell Temp Min | `battery_cell_temp_min` | °C | temperature |
| Cell Temp Max | `battery_cell_temp_max` | °C | temperature |
| Cell Temp Avg | `battery_cell_temp_avg` | °C | temperature |
| Cell Voltage Min | `cell_voltage_min` | V | voltage |
| Cell Voltage Max | `cell_voltage_max` | V | voltage |
| WiFi SSID | `wifi_ssid` | — | — |
| Tyre Pressure LF | `tyre_pressure_left_front_psi` | psi | — |
| Tyre Pressure RF | `tyre_pressure_right_front_psi` | psi | — |
| Tyre Pressure LR | `tyre_pressure_left_rear_psi` | psi | — |
| Tyre Pressure RR | `tyre_pressure_right_rear_psi` | psi | — |
| Tyre Temp LF | `tyre_temperature_left_front_c` | °C | temperature |
| Tyre Temp RF | `tyre_temperature_right_front_c` | °C | temperature |
| Tyre Temp LR | `tyre_temperature_left_rear_c` | °C | temperature |
| Tyre Temp RR | `tyre_temperature_right_rear_c` | °C | temperature |

> **PHEV only:** `fuel_percentage` and `fuel_driving_range_km` exist only when the car selected
> in the app is a plug-in hybrid. On a BEV they are left out of both the discovery set and the
> state payload. A car with no tank has no reading to give: the SDK's fuel getters answer with a
> sentinel, the app filters that to nothing, and the telemetry then reports 0, so before this
> change a BEV published a flat `0` for ever and Home Assistant drew an empty tank. Their absence
> is not a zero.
>
> `energy_mode` is the other field only a PHEV can answer, but it behaves differently: the entity
> exists on every car and reads `—` on a BEV.
>
> If you ran an earlier version on a BEV, your broker still holds the two retained fuel configs.
> The app deletes them for you on the first connection after upgrading, by publishing an empty
> payload to each config topic (the same thing step 4 of **Troubleshooting** does by hand), so the
> two entities disappear from Home Assistant on their own. A PHEV's discovery set is unchanged and
> is not republished.

### Binary sensors (`binary_sensor.*`)

| Entity | Key | Device class | On when |
|---|---|---|---|
| Charging | `is_charging` | battery_charging | Charging |
| Car On | `car_on` | running | Car is on |
| Locked | `car_locked` | lock | Locked |
| Door Open | `any_door_opened` | door | Any door open |

> **Note:** GPS fields (`location_latitude`, `location_longitude`, `location_altitude`, `heading`) retain their last known value when the car turns off, so HA always knows where the car is parked.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| All entities unavailable | Availability topic is `offline` or empty | Press **Test & Save** in the app |
| Entities don't appear at all | Discovery messages not received by HA | Check broker connectivity; subscribe to `homeassistant/#` to verify |
| Binary sensors always off | Old stale discovery config retained in broker | Publish empty payload to `homeassistant/binary_sensor/<name>/<id>/config` to clear it, then **Test & Save** |
| Duplicate entities | Old Electro/HiveMQ entities still present | Remove old MQTT integration or clear retained messages for the old topics |
| State stops updating | App disconnected or car off (30 s interval applies) | Normal behaviour — entities stay at last known value |
