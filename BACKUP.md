## ADB Wireless Backup & Restore

You have probably already enabled wireless ADB for sideloading the app, so you can use it to pull and push database backups directly from your PC without removing an SD card, assuming both PC and car are in the same network.
No need to use an external SD card (or the car's internal storage)

Every in-app backup writes to two locations simultaneously:
- `Downloads/BydTripStats/` — visible in the car's file manager
- `/data/data/com.byd.tripstats/files/db_backup/` — private app storage, accessible via ADB (newest 5 kept automatically)

### Connect

```bash
adb connect 192.168.x.x:5555 # or perhaps 10.0.x.x:5555 - check your network!
adb devices  # confirm the car appears
```

### List backups on the car

```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats ls -la /data/data/com.byd.tripstats/files/db_backup/
```

### Pull a backup to your PC

```bash
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    cat /data/data/com.byd.tripstats/files/db_backup/byd_stats_backup_2026-02-28_10-30.db \
    > byd_stats_backup_2026-02-28_10-30.db
```

### Inspect the database (SQLite)

```bash
sqlite3 byd_stats_backup_2026-02-28_10-30.db

# Useful queries once inside sqlite3:
SELECT tripId, COUNT(*) FROM trip_data_points GROUP BY tripId ORDER BY tripId DESC;
.quit
```

### Push a backup back to the car (restore)

```bash
# 1. Create the backup dir if it doesn't exist yet
adb -s 192.168.x.x:5555 shell run-as com.byd.tripstats \
    mkdir -p /data/data/com.byd.tripstats/files/db_backup

# 2. Push the file
adb -s 192.168.x.x:5555 push byd_stats_backup_2026-02-28_10-30.db \
    /data/data/com.byd.tripstats/files/db_backup/byd_stats_backup_2026-02-28_10-30.db

# 3. Open the app → Settings → Backup & Restore → the file appears in the list → tap Restore
```

> **Note:** ALWAYS trigger a backup from within the app before pulling, to ensure the WAL is flushed and the `.db` file is self-consistent. Pulling the raw database file while the app is running may result in an incomplete snapshot.