# BYD Trip Stats — Backup & Restore Guide

Your trip data lives in a single SQLite database file on the car's infotainment unit. Your **settings** live outside it, so every backup writes a second, tiny file next to the database — see [Settings backup](#settings-backup). This guide covers all available methods to back both up and restore them.

---

## Table of Contents

- [Backup Methods](#backup-methods)
  - [Download Folder](#1-download-folder)
  - [Telegram](#2-telegram)
  - [Wireless ADB](#3-wireless-adb)
- [Settings backup](#settings-backup)
- [Restore Methods](#restore-methods)
  - [From the backup list](#1-from-the-backup-list)
  - [From Telegram](#2-from-telegram)
  - [Using ADB push](#3-using-adb-push)
- [Notes](#notes)

---

## Backup Methods

### 1. Download Folder

The simplest option. No setup required.

**Steps:**
1. Open the app → **Settings** → **Backup & Restore**
2. Under *Backup to Download*, tap **Backup Now**
3. The file is saved to `Download/BydTripStats/byd_stats_backup_vX.Y.Z_YYYY-MM-DD_HH-mm.db.gz` on the car's internal storage (a gzip-compressed database — see [Notes](#notes))

The file will appear in the car's file manager and can be copied to a USB drive or SD card from there.

---

### 2. Telegram

Sends the backup file to a private Telegram chat. Accessible from any device with Telegram installed. Supports both manual and automatic scheduled backups, and **restoring directly from within the app** — no PC or ADB needed.

**Setup (first time only):** the bot is linked in **Settings → Connections**, with the other connections — one bot serves backups, notifications, diagnostics and trip exports alike.

1. Open Telegram and message **@BotFather**
2. Send `/newbot` and follow the prompts to create a bot
3. Copy the token BotFather gives you (format: `123456789:ABCdefGHI...`)
4. **Send any message to your new bot** (required so the app can find your chat ID automatically)
5. Open the app → **Settings** → **Connections** → *Telegram*
6. Paste the token and tap **Validate & Save**
7. The app contacts Telegram, confirms the token, and saves your chat ID automatically

Everything below then happens in **Settings → App → Backup & Restore**.

**Manual backup:**
1. Tap **Send Backup Now**
2. The `.db.gz` file arrives in your private chat with the bot, captioned with the timestamp
3. Access it from any device via the Telegram app

**Restore from Telegram:**
1. Open the app → **Settings** → **Backup & Restore** → *Restore from Telegram*
2. Tap the **refresh icon** to load the list of available backups
3. Tap **Restore** next to the backup you want
4. The app downloads and checks the file, then shows what it holds next to what's on the car — tap **Restore** to confirm
5. The app saves a copy of your current data, restores the database, and restarts automatically

> **20 MB download limit.** Telegram lets a bot *send* files up to 50 MB but *download* only up to 20 MB, so the in-app restore works for backups of 20 MB or less (the size shown in the list). Compression keeps that true until the raw database reaches roughly 300 MB. For a bigger backup, save it from the chat on your phone, upload it to *Backups (Download)* in the web companion's **Files** tab, and restore it from the backup list.

**Backup registry — survives reinstalls:**

Every time a backup is sent successfully, its metadata (`file_id`, filename, size, date) is saved in three places simultaneously:
- **SharedPreferences** — fast access while the app is installed
- **`Download/BydTripStats/telegram_registry_full.json`** — the complete list, persists across uninstalls
- **`Download/BydTripStats/telegram_registry.json`** — the same, limited to plain `.db` backups. Versions before 2.17 read only this file and drop any entry they don't recognise, so the compressed backups are kept out of it — installing an older version can't remove them from the list.

If you uninstall or reset app data and then reconnect your bot, tapping refresh in *Restore from Telegram* will rediscover all previous backups automatically by reading the registry files from Download. As long as `Download/BydTripStats/` has not been manually cleared, no backups are lost.

**Automatic backup:**

Once connected, you can enable automatic scheduled backups using the toggle in the Telegram section. Three intervals are available via radio buttons: **Daily**, **Weekly**, or **Monthly**.

Key behaviours to be aware of:

- The first automatic backup runs after one full interval from the moment you enable it — enabling the toggle does **not** send a backup immediately
- Changing the interval reschedules from that point forward; again, no immediate send
- If a backup attempt fails (e.g. no network at the scheduled time), the system retries automatically with exponential backoff before giving up until the next scheduled window
- If the car is powered off when a scheduled backup is due, it runs automatically on the next boot — it will not be silently skipped. If the car was off for longer than the full interval, only one backup fires on boot (no catch-up runs), and the cadence resets from that point

The *Last auto-backup* timestamp shown in Settings confirms when the most recent automatic run completed.

> To disconnect, tap **Disconnect bot** in *Settings → Connections*. This cancels the automatic schedule and clears all saved credentials. Your previous backups in Telegram and the registry file in Download are unaffected.

**Notifications through the same bot:**

Configured in **Settings → Connections**, directly under the bot itself. It is **off by default**; switch on *Send notifications*, then pick which you want:

- **Trip summary** — after every drive: distance, duration, average speed, energy used and consumption measured against your lifetime average, state of charge used, cost, and the trip score. Every figure is read back from the recorded trip and priced exactly as the trip's own screen prices it, so the two always agree.
- **Charging finished** — when a charging session ends: whether it completed or stopped short and at what level, the energy added, how long it ran, the power it managed and what it cost. **Off by default**, because it is the one event whose timing you don't choose — an overnight charge that completes at 03:00 sends at 03:00. That is why it has its own switch, separate from trip summaries.
- **Cell imbalance alert** (**Pro**) — the battery warning, which otherwise only appears on the car's own screen.

**Send test message** confirms the whole path before you rely on it.

*When a summary is sent.* It goes out **when the trip ends**, which is not the moment you park: a trip ends once the car has been off for the **auto-stop time** in *Settings → Preferences → Trip recording* (3 minutes by default). Set that to 45 minutes and the summary arrives 45 minutes after you park — that is the trip genuinely ending, not a delay. On **DiLink-3** in the default **Always On** background mode the app is still running then, so this is the normal case.

A trip the app could only close **afterwards** is recorded in full but **not announced**. On **DiLink-5** the car force-stops the app at ignition-off, so a trip left to close on its own is closed at the next start, and a notification then would be about yesterday's drive arriving as you set off on today's. Tapping **Stop** to end the trip before you switch the car off closes it live, and that one does send a summary.

Alerts that are not tied to a trip keep firing while parked in **Always On**, because the head unit stays on **mobile data** after the car cuts Wi-Fi. In **Minimal** mode the app is woken every 90 minutes, and in **Deep Sleep** not until the next drive.

The same events also appear in the **web companion**, under the bell in its header, whether or not a bot is linked — see the notes there. That feed is the car's own history; Telegram is a copy pushed to your phone.

If the car has no connection when an event happens, the message is **queued and retried** — the queue survives the app being killed — and is dropped only once it is more than a day old, by which point it is history rather than an alert.

---

### 3. Wireless ADB

For technical users. Requires wireless ADB to be enabled (already a prerequisite for sideloading the app). Every in-app backup also writes a copy to the app's private directory, accessible via ADB without any extra steps.

**Connect to the car:**
```bash
adb connect 192.168.x.x:5555
adb devices  # confirm the car appears
```

**List available backups:**
```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    ls -la /data/data/com.byd.tripstats/files/db_backup/
```

**Pull a backup to your PC:**
```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    cat /data/data/com.byd.tripstats/files/db_backup/byd_stats_backup_v2.17.0_2026-02-28_10-30.db.gz \
    > byd_stats_backup_v2.17.0_2026-02-28_10-30.db.gz
```

> The private backup directory keeps the **5 most recent** backups automatically. Older ones are pruned when a new backup is created.

---

## Settings backup

The database holds trips and charging sessions. Everything *around* them — selected car, units, theme, dashboard layout, electricity tariff and currency, personal goals, MQTT / ABRP / Telegram connections, web companion port and PIN, language, tyre-pressure unit and your Pro unlock code — lives in the app's preferences, which an uninstall wipes.

So every backup writes a second file alongside the `.db`:

```
Download/BydTripStats/byd_stats_settings_v2.15.2_2026-09-07_14-30.json
```

It is a couple of kilobytes of plain JSON, written to the same places as the database backup (Download, the private ADB directory, the SD card) and sent to Telegram with it — including on the automatic schedule. The two files share the same timestamp, which is how the app pairs them at restore time.

> Telegram is the one exception to "always written": the settings file goes to your chat **only when the database goes with it**. A backup over Telegram's 50 MB limit (measured on the compressed file) is skipped, and the settings file is skipped with it, so a message in your chat always means a real backup. Export settings by hand from the card described below when that happens — the local copy is written regardless of database size.

**Credentials.** By default the file carries the secrets it needs to be useful: the MQTT password, the ABRP and Telegram tokens, the web companion PIN and the Pro code. That file sits in your own car's Download folder, next to your trip database. If you plan to share it — to copy a configuration to another car, or to attach it to a bug report — turn **Include credentials** off in *Settings → App → Backup & Restore → Settings backup & restore* first; everything else is still exported, and the connections come back configured but need their passwords re-entered.

The Pro unlock code is safe to carry either way: it is checked against the vehicle it was issued for on every launch, so a copied code unlocks nothing on another car.

**Backing up settings on their own.** Tap **Back up settings now** in that card, for example after changing a tariff, without waiting for the next database backup.

**Restoring.** The same card lists every settings file it can find, newest first, each with **Restore**. Restoring settings does **not** touch trips, and does **not** close the app — every value applies immediately (only a language change restarts the screen). A settings file restored from a car that had a different vehicle selected will switch the selected car too.

> The `Download/BydTripStats/` folder survives an uninstall, so after a reinstall the settings file is normally still there, ready to restore before or after the database.

---

## Restore Methods

> ⚠️ Restoring **replaces all current trip data**. The app closes after a successful restore so the database reopens cleanly, and comes back on its own where the head unit allows it (DiLink-3). On newer head units it does not relaunch itself — an app-initiated restart there can wedge the unit — so it leaves a **Tap to reopen BYD Trip Stats** notification instead; tapping it, or the app icon, brings it back.

**Before anything is replaced** — whichever way you restore:

1. **The backup is checked end to end.** A file that is damaged inside — not just one that isn't a database at all — is refused with a message, and your current data is left alone. The only other refusal is a backup whose database *format* is newer than the installed app knows (made by a later release that changed the format — update the app first). Backups from older versions restore as always, and are upgraded on the next start; one with fewer trips than you have now is never refused, only flagged in the confirmation.
2. **You see what you're about to get.** The confirmation shows the backup's trips (count and date range) and charging sessions next to what is on the car now, with a warning in red when the backup has fewer or older trips than you have.
3. **Your current data is saved first**, to `Download/BydTripStats/byd_stats_before_restore_….db.gz`. If you picked the wrong backup, restore that file to get back to where you were. (Skipped when there is nothing to keep, e.g. restoring onto a fresh install.)

Once after updating to 2.17, the app also checks its own database in the background (about two minutes after it starts, a minute or so of reading). A backup restored with an earlier version could have left damage there that only shows when the app next writes to it; if the check finds any, it saves a copy as `byd_stats_damaged_….sqlite.gz` and shows a notification — restore a recent backup then (a damaged one is refused, so you can try them in turn).

If the app ever finds its own database damaged, it no longer deletes it: it starts over with an empty one and saves the damaged file to `Download/BydTripStats/byd_stats_damaged_….sqlite.gz`. That file is not offered in the restore list — its data can usually still be recovered on a computer (`gunzip`, then `sqlite3 file.sqlite ".recover" | sqlite3 recovered.db`), and the recovered database restored as usual.

---

### 1. From the Backup List

The app scans all known backup locations (Download folder and private ADB directory) and shows them in a single list inside the Restore section.

**Steps:**
1. Open the app → **Settings** → **Backup & Restore**
2. Scroll to the *Restore* section — available backups are listed directly below the warning
3. Tap **Restore** next to the backup you want
4. The app checks the backup, then shows what it holds next to what's on the car — tap **Restore** to confirm
5. The app saves a copy of your current data, restores the database and restarts automatically

Each entry shows the filename, date, size, and source location (*Download* or *Internal (ADB)*). Tap the refresh icon to re-scan if you have just created a new backup or pushed a file via ADB.

If the settings file written with that backup is still present, the confirmation dialog offers **Also restore the settings saved with this backup**, ticked by default. Untick it to restore only the trip data and keep your current settings — restoring an old database onto a working install would otherwise replace your current tariff, broker password and layout as well.

---

### 2. From Telegram

Restore a backup directly from your Telegram chat without needing a PC or ADB.

**Steps:**
1. Open the app → **Settings** → **Backup & Restore**
2. Scroll to *Restore from Telegram* (requires a connected bot — see [Telegram backup](#2-telegram))
3. Tap the **refresh icon** to load available backups
4. Tap **Restore** next to the backup you want
5. The app downloads and checks the file, then shows what it holds next to what's on the car — tap **Restore** to confirm
6. The app saves a copy of your current data, restores the database, and restarts automatically

> Backups over 20 MB can't be downloaded by the bot — see the [20 MB download limit](#2-telegram) for the way around it.

> If you have just reinstalled the app or cleared app data, your previous backups will reappear after tapping refresh — as long as the `telegram_registry*.json` files in `Download/BydTripStats/` are still present.

> The Telegram restore list holds **databases only**. Settings files are sent to your chat with every backup so you have an off-car copy, but they are restored from the local list in the *Settings backup & restore* card — on the car itself, `Download/BydTripStats/` survives the uninstall, so the settings file is normally already there.

---

### 3. Using ADB Push

Use this to restore a backup from your PC directly to the car over WiFi, then pick it from the in-app list.

**Steps:**

1. Push the backup file from your PC to your car's download folder:
```bash
adb -s 192.168.x.x:5555 push byd_stats_backup_v2.17.0_2026-02-28_10-30.db.gz \
    /sdcard/Download/BydTripStats/byd_stats_backup_v2.17.0_2026-02-28_10-30.db.gz
```

2. **(DEBUG-ONLY)** Create the backup directory on the car if it doesn't exist yet:
```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    mkdir -p /data/data/com.byd.tripstats/files/db_backup
```

3. **(DEBUG-ONLY)** Copy the file while using run-as (overrides debug permissions):
```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    cp /sdcard/Download/BydTripStats/byd_stats_backup_v2.17.0_2026-02-28_10-30.db.gz /data/data/com.byd.tripstats/files/db_backup/byd_stats_backup_v2.17.0_2026-02-28_10-30.db.gz
```
4. Open the app → **Settings** → **Backup & Restore**

5. The pushed file appears in the backup list — tap **Restore** and confirm

> Telegram backups can now be restored directly from within the app — see [From Telegram](#2-from-telegram) above. ADB push is only needed if you want to restore a backup from your PC that is not already in the Telegram list.

---

## Notes

- **Before pulling via ADB**, trigger an in-app backup first. This flushes the SQLite Write-Ahead Log (WAL) and ensures the `.db` file is self-consistent. Pulling the raw file while the app is running may result in an incomplete snapshot.
- **All backup methods** produce the same file format — a standard SQLite 3 database, **gzip-compressed** (`.db.gz`). Decompress it (`gunzip byd_stats_backup_….db.gz`, or 7-Zip on Windows) to open it with any SQLite browser (e.g. DB Browser for SQLite, SQLiteStudio) for inspection or manual queries.
- **Restore accepts both** compressed `.db.gz` and plain `.db` files — backups made by older versions, or a database you decompressed and pushed back yourself, restore the same way. The format is detected from the file's contents, not its name.
- The app validates that any file selected for restore is a genuine SQLite database (after decompressing it, if needed) before touching the live data.