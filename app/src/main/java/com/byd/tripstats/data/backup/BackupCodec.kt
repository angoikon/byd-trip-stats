package com.byd.tripstats.data.backup

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * On-disk format of database backups. Pure JVM — no Android context required.
 *
 * New backups are gzip-compressed (`.db.gz`); a SQLite file compresses several times over,
 * which matters most against Telegram's 50 MB cap. Restore accepts both: the format is
 * detected from the gzip magic bytes, never from the file name, so a renamed file, an
 * adb-pushed raw `.db` and every backup written before compression existed all restore.
 */
object BackupCodec {
    const val PLAIN_EXTENSION      = ".db"
    const val COMPRESSED_EXTENSION = ".db.gz"

    private const val GZIP_MAGIC_1 = 0x1f
    private const val GZIP_MAGIC_2 = 0x8b
    private const val SQLITE_MAGIC = "SQLite format 3\u0000"

    /** True for any name a database backup can carry, compressed or not. */
    fun isBackupName(name: String): Boolean =
        name.endsWith(PLAIN_EXTENSION, ignoreCase = true) ||
            name.endsWith(COMPRESSED_EXTENSION, ignoreCase = true)

    /** Gzips [source] into [dest], replacing it. Default level: 9 is ~2x the CPU for ~3% smaller. */
    fun compress(source: File, dest: File) {
        GZIPOutputStream(FileOutputStream(dest), 64 * 1024).use { gz ->
            source.inputStream().use { it.copyTo(gz, 64 * 1024) }
        }
    }

    /**
     * Wraps [input] so it yields the raw SQLite bytes, whether [input] is a gzip-compressed
     * backup or a plain `.db`. Closing the returned stream closes [input].
     */
    fun decode(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == GZIP_MAGIC_1 && b2 == GZIP_MAGIC_2) GZIPInputStream(buffered) else buffered
    }

    /** Decodes [input] (compressed or plain) into [dest]. [input] is closed. */
    fun decodeTo(input: InputStream, dest: File) {
        decode(input).use { src -> FileOutputStream(dest).use { out -> src.copyTo(out) } }
    }

    /** True when [file] starts with the SQLite magic header. */
    fun isSQLiteFile(file: File): Boolean {
        val header = ByteArray(SQLITE_MAGIC.length)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && SQLITE_MAGIC.indices.all { header[it] == SQLITE_MAGIC[it].code.toByte() }
    }
}
