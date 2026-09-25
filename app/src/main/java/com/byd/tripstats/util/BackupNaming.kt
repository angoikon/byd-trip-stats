package com.byd.tripstats.util

import com.byd.tripstats.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralised backup filename scheme: `<prefix>_v<appVersion>_<timestamp><extension>`,
 * where database backups use `.db.gz` (older ones `.db`) and settings files `.json`.
 *
 * The `v<appVersion>` segment records which build produced a backup, so a restored
 * `.db` can be matched to the schema it was written against. Every scan/sort/prune path
 * keys off the file's `lastModified()` time rather than this name; the one thing read
 * back out of it is the timestamp ([timestampOf]), which pairs a database backup with
 * the settings file written in the same run.
 */
object BackupNaming {
    const val EXTENSION = ".db"

    /** `..._<yyyy-MM-dd_HH-mm>.<ext>` — the shared segment of a backup/settings pair.
     *  The extension may be compound (`.db.gz`). */
    private val TIMESTAMP_REGEX = Regex("""_(\d{4}-\d{2}-\d{2}_\d{2}-\d{2})(?:\.[A-Za-z0-9]+)+$""")

    /** App version, sanitised to filename-safe characters (e.g. "2.11.1-beta09"). */
    val appVersionTag: String
        get() = BuildConfig.VERSION_NAME.replace(Regex("[^A-Za-z0-9._-]"), "-")

    /** Minute-resolution timestamp, matching the existing backup naming. */
    fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())

    fun fileName(
        prefix: String = "byd_stats_backup",
        timestamp: String = timestamp(),
        extension: String = EXTENSION,
    ): String = "${prefix}_v${appVersionTag}_$timestamp$extension"

    /** The timestamp segment of [fileName], or null for a name that doesn't carry one. */
    fun timestampOf(fileName: String): String? =
        TIMESTAMP_REGEX.find(fileName)?.groupValues?.getOrNull(1)
}
