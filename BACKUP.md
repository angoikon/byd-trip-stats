# BYD Trip Stats — Backup & Restore Guide

Your trip data lives in a single SQLite database file on the car's infotainment unit. This guide covers all available methods to back it up and restore it.

---

## Table of Contents

- [Backup Methods](#backup-methods)
  - [Downloads Folder](#1-downloads-folder)
  - [Telegram](#2-telegram)
  - [Wireless ADB](#3-wireless-adb)
- [Restore Methods](#restore-methods)
  - [From the backup list](#1-from-the-backup-list)
  - [From Telegram](#2-from-telegram)
  - [Using ADB push](#3-using-adb-push)
- [Notes](#notes)

---

## Backup Methods

### 1. Downloads Folder

The simplest option. No setup required.

**Steps:**
1. Open the app → **Settings** → **Backup & Restore**
2. Under *Backup to Downloads*, tap **Backup Now**
3. The file is saved to `Downloads/BydTripStats/byd_stats_backup_YYYY-MM-DD_HH-mm.db` on the car's internal storage

The file will appear in the car's file manager and can be copied to a USB drive or SD card from there.

---

### 2. Telegram

Sends the backup file to a private Telegram chat. Accessible from any device with Telegram installed. Supports both manual and automatic scheduled backups, and **restoring directly from within the app** — no PC or ADB needed.

**Setup (first time only):**

1. Open Telegram and message **@BotFather**
2. Send `/newbot` and follow the prompts to create a bot
3. Copy the token BotFather gives you (format: `123456789:ABCdefGHI...`)
4. **Send any message to your new bot** (required so the app can find your chat ID automatically)
5. Open the app → **Settings** → **Backup & Restore** → *Telegram Backup*
6. Paste the token and tap **Validate & Save**
7. The app contacts Telegram, confirms the token, and saves your chat ID automatically

**Manual backup:**
1. Tap **Send Backup Now**
2. The `.db` file arrives in your private chat with the bot, captioned with the timestamp
3. Access it from any device via the Telegram app

**Restore from Telegram:**
1. Open the app → **Settings** → **Backup & Restore** → *Restore from Telegram*
2. Tap the **refresh icon** to load the list of available backups
3. Tap **Restore** next to the backup you want
4. Confirm the warning dialog
5. The app downloads the file, validates it, restores the database, and restarts automatically

**Backup registry — survives reinstalls:**

Every time a backup is sent successfully, its metadata (`file_id`, filename, size, date) is saved in two places simultaneously:
- **SharedPreferences** — fast access while the app is installed
- **`Downloads/BydTripStats/telegram_registry.json`** — persists across uninstalls

If you uninstall or reset app data and then reconnect your bot, tapping refresh in *Restore from Telegram* will rediscover all previous backups automatically by reading the registry file from Downloads. As long as `Downloads/BydTripStats/` has not been manually cleared, no backups are lost.

**Automatic backup:**

Once connected, you can enable automatic scheduled backups using the toggle in the Telegram section. Three intervals are available via radio buttons: **Daily**, **Weekly**, or **Monthly**.

Key behaviours to be aware of:

- The first automatic backup runs after one full interval from the moment you enable it — enabling the toggle does **not** send a backup immediately
- Changing the interval reschedules from that point forward; again, no immediate send
- If a backup attempt fails (e.g. no network at the scheduled time), the system retries automatically with exponential backoff before giving up until the next scheduled window
- If the car is powered off when a scheduled backup is due, it runs automatically on the next boot — it will not be silently skipped. If the car was off for longer than the full interval, only one backup fires on boot (no catch-up runs), and the cadence resets from that point

The *Last auto-backup* timestamp shown in Settings confirms when the most recent automatic run completed.

> To disconnect, tap **Disconnect bot**. This cancels the automatic schedule and clears all saved credentials. Your previous backups in Telegram and the registry file in Downloads are unaffected.

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
    cat /data/data/com.byd.tripstats/files/db_backup/byd_stats_backup_2026-02-28_10-30.db \
    > byd_stats_backup_2026-02-28_10-30.db
```

> The private backup directory keeps the **5 most recent** backups automatically. Older ones are pruned when a new backup is created.

---

## Restore Methods

> ⚠️ Restoring **permanently replaces all current trip data**. The app restarts automatically after a successful restore.

---

### 1. From the Backup List

The app scans all known backup locations (Downloads folder and private ADB directory) and shows them in a single list inside the Restore section.

**Steps:**
1. Open the app → **Settings** → **Backup & Restore**
2. Scroll to the *Restore* section — available backups are listed directly below the warning
3. Tap **Restore** next to the backup you want
4. Confirm the warning dialog
5. The app restores the database and restarts automatically

Each entry shows the filename, date, size, and source location (*Downloads* or *Internal (ADB)*). Tap the refresh icon to re-scan if you have just created a new backup or pushed a file via ADB.

---

### 2. From Telegram

Restore a backup directly from your Telegram chat without needing a PC or ADB.

**Steps:**
1. Open the app → **Settings** → **Backup & Restore**
2. Scroll to *Restore from Telegram* (requires a connected bot — see [Telegram backup](#2-telegram))
3. Tap the **refresh icon** to load available backups
4. Tap **Restore** next to the backup you want
5. Confirm the warning dialog
6. The app downloads the file, validates it, restores the database, and restarts automatically

> If you have just reinstalled the app or cleared app data, your previous backups will reappear after tapping refresh — as long as `Downloads/BydTripStats/telegram_registry.json` is still present.

---

### 3. Using ADB Push

Use this to restore a backup from your PC directly to the car over WiFi, then pick it from the in-app list.

**Steps:**

1. Push the backup file from your PC to your car's download folder:
```bash
adb -s 192.168.x.x:5555 push byd_stats_backup_2026-02-28_10-30.db \
    /sdcard/Download/BydTripStats/byd_stats_backup_2026-02-28_10-30.db
```

2. **(DEBUG-ONLY)** Create the backup directory on the car if it doesn't exist yet:
```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    mkdir -p /data/data/com.byd.tripstats/files/db_backup
```

3. **(DEBUG-ONLY)** Copy the file while using run-as (overrides debug permissions):
```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    cp /sdcard/Download/BydTripStats/byd_stats_backup_2026-02-28_10-30.db /data/data/com.byd.tripstats/files/db_backup/byd_stats_backup_2026-02-28_10-30.db
```
4. Open the app → **Settings** → **Backup & Restore**

5. The pushed file appears in the backup list — tap **Restore** and confirm

> Telegram backups can now be restored directly from within the app — see [From Telegram](#2-from-telegram) above. ADB push is only needed if you want to restore a backup from your PC that is not already in the Telegram list.

---

## Notes

- **Before pulling via ADB**, trigger an in-app backup first. This flushes the SQLite Write-Ahead Log (WAL) and ensures the `.db` file is self-consistent. Pulling the raw file while the app is running may result in an incomplete snapshot.
- **All backup methods** produce the same file format — a standard SQLite 3 database. You can open it with any SQLite browser (e.g. DB Browser for SQLite, SQLiteStudio) for inspection or manual queries.
- The app validates that any file selected for restore is a genuine SQLite database before touching the live data.