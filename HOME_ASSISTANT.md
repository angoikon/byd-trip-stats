# Home Assistant Integration

BYD Trip Stats publishes telemetry via MQTT using **Home Assistant MQTT Discovery** — no `configuration.yaml` editing required. Sensors appear automatically once the app connects to the same broker as HA.

---

## How it works

- The app publishes **retained** discovery config messages to `homeassistant/sensor/<name>/<key>/config` and `homeassistant/binary_sensor/<name>/<key>/config`. Because they are retained, the broker keeps serving them to HA across reconnects and app restarts, so they are sent again only when they actually change — an app update that changes the sensor set, a different friendly name, or a different broker — and once a day regardless, so a broker that has lost its retained messages recovers on its own. The app also re-sends them whenever Home Assistant announces itself on `homeassistant/status`. Press **Test & Save** to force a resend if your broker has lost its retained messages.
- Telemetry is published as a **single retained JSON blob** to `byd-trip-stats/<name>/state`.
- Each discovery config uses a `value_template` to extract its field from that JSON (e.g. `{{ value_json.soc }}`), so HA always has the latest value even after a restart.
- An availability topic (`byd-trip-stats/<name>/availability`) carries `online` / `offline` (Last Will and Testament), so HA marks entities unavailable on unexpected disconnect.
- While the car is **on**, state is published at your configured interval (1–120 s). While the **car is off**, it publishes every 30 s regardless of your setting (to keep HA's availability alive without flooding the broker).

---

## Setup

### 1. Choose a broker

Both the car's head unit and Home Assistant must connect to the **same** MQTT broker. Options:

| Broker | Notes |
|---|---|
| **HiveMQ Cloud** (free tier) | Easiest if your car has internet. Port `8883` (TLS). |
| **Mosquitto add-on** (local) | Best for LAN-only setups. Runs inside HA. Port `1883` or `8883`. |
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
| Fuel Percentage | `fuel_percentage` | % | — |
| Fuel Driving Range | `fuel_driving_range_km` | km | — |
| Drive Mode | `drive_mode` | — | — |
| Regen Mode | `regen_mode` | — | — |
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
