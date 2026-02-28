# BYD Trip Stats — Backup & Restore Guide

Your trip data lives in a single SQLite database file on the car's infotainment unit. This guide covers all available methods to back it up and restore it.

---

## Table of Contents

- [Backup Methods](#backup-methods)
  - [Downloads Folder](#1-downloads-folder)
  - [Telegram](#3-telegram)
  - [Wireless ADB](#4-wireless-adb)
- [Restore Methods](#restore-methods)
  - [From the backup list](#1-from-the-backup-list)
  - [Using the file picker](#2-using-the-file-picker)
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

Sends the backup file to a private Telegram chat. Accessible from any device with Telegram installed. Requires a one-time bot setup.

**Setup (first time only):**

1. Open Telegram and message **@BotFather**
2. Send `/newbot` and follow the prompts to create a bot
3. Copy the token BotFather gives you (format: `123456789:ABCdefGHI...`)
4. **Send any message to your new bot** (this is required so the app can find your chat ID)
5. Open the app → **Settings** → **Backup & Restore** → *Telegram Backup*
6. Paste the token and tap **Validate & Save**
7. The app contacts Telegram, confirms the token, and saves your chat ID automatically

**Backup:**
1. Tap **Send Backup to Telegram**
2. The `.db` file arrives in your private chat with the bot, captioned with the date and time
3. Access it from any device via the Telegram app

> To disconnect, tap **Disconnect bot**. Your previous backups in Telegram are unaffected.

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

The easiest restore path. The app scans all known backup locations and shows them in a single list.

**Steps:**
1. Open the app → **Settings** → **Backup & Restore**
2. Scroll down to the backup list below the *Restore* section
3. Tap **Restore** next to the backup you want
4. Confirm the warning dialog
5. The app restores the database and restarts

Each entry shows the filename, date, size, and source location (*Downloads*, *SD Card*, or *Internal (ADB)*).

---

### 2. Using the File Picker

Use this if the backup is stored somewhere not covered by the automatic scan (e.g. a USB drive, or a file downloaded from Telegram).

**Steps:**
1. Open the app → **Settings** → **Backup & Restore**
2. Under *Restore*, tap **Pick File (File Manager)**
3. Navigate to the `.db` file in the system file picker and select it
4. Confirm the warning dialog
5. The app restores the database and restarts

> If the car's infotainment has no file manager registered, this button will show an error. Use the backup list or ADB push instead.

---

### 3. Using ADB Push

Use this to restore a backup from your PC directly to the car over WiFi.

**Steps:**

1. Create the backup directory on the car if it doesn't exist yet:
```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    mkdir -p /data/data/com.byd.tripstats/files/db_backup
```

2. Push the backup file from your PC:
```bash
adb -s 192.168.x.x:5555 push byd_stats_backup_2026-02-28_10-30.db \
    /data/data/com.byd.tripstats/files/db_backup/byd_stats_backup_2026-02-28_10-30.db
```

3. Open the app → **Settings** → **Backup & Restore**
4. The pushed file appears in the backup list — tap **Restore** and confirm

---

## Notes

- **Before pulling via ADB**, trigger an in-app backup first. This flushes the SQLite Write-Ahead Log (WAL) and ensures the `.db` file is self-consistent. Pulling the raw file while the app is running may result in an incomplete snapshot.
- **All backup methods** write the same file format — a standard SQLite 3 database. You can open it with any SQLite browser (e.g. SQLiteStudio, SQLPro Studio, DB Browser for SQLite) for inspection or manual queries.
- **Telegram backups are not restorable from within the app** — download the `.db` file from your Telegram chat to your device or PC, then restore via the file picker or ADB push.
- The app validates that any file selected for restore is a genuine SQLite database before touching the live data.