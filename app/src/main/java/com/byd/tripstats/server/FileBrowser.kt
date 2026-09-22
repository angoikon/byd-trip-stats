package com.byd.tripstats.server

import android.content.Context
import android.os.Environment
import android.util.Log
import com.byd.tripstats.data.backup.LocalBackupManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * The slice of the head unit's filesystem the web companion is allowed to show.
 *
 * Deliberately a short fixed list of roots rather than the whole of /sdcard. The companion
 * listens on the car's Wi-Fi as well as on the tailnet, and a database backup is the complete
 * trip history, so the browsable surface stays as small as the job allows. Anything wider is
 * still reachable over ADB, which is where unrestricted access belongs.
 *
 * Paths crossing the wire are always "<rootId>/<relative>", never absolute, and [resolveWithin]
 * canonicalises before testing containment so that "..", a symlink or an absolute path cannot
 * walk out of a root.
 */
object FileBrowser {

    private const val TAG = "FileBrowser"

    /**
     * How much of a text file the inline viewer renders.
     *
     * A big file isn't refused, it's tailed: logs are the reason the viewer exists, `diag.log` runs
     * to several MB, and the end is the part anyone wants. 256 KB is a few thousand lines, which a
     * phone can put in a <pre> without stalling.
     */
    const val VIEW_TAIL_BYTES = 256L * 1024

    /** Largest upload accepted, so a mistyped request can't fill the head unit's storage. */
    const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024

    /** The head unit's own screenshot folder, under the shared Download directory. */
    private const val SCREENSHOTS_SUBFOLDER = "Screenshots"

    /** Extensions we are willing to render in the browser instead of downloading. */
    private val TEXT_EXTENSIONS = setOf("log", "txt", "json", "csv", "md", "xml", "html", "htm")

    /** Images the viewer shows inline, mapped to what to serve them as. */
    private val IMAGE_MIME_TYPES = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp"
    )

    data class Root(
        val id: String,
        val label: String,
        val hint: String,
        val dir: File,
        val writable: Boolean,
        /** True when writes were intended here but the filesystem refuses them — see [root]. */
        val writeBlocked: Boolean = false
    )

    /**
     * Builds a root, deriving writability from the filesystem rather than from intent.
     *
     * This matters on the head unit: READ_EXTERNAL_STORAGE and WRITE_EXTERNAL_STORAGE are granted
     * separately, and an app only joins the `sdcard_rw` group with the write one. With read alone,
     * a shared-storage folder lists perfectly and then refuses every delete — which is exactly the
     * confusing half-working state this avoids, by asking the filesystem and letting the companion
     * hide Upload and Delete rather than offer buttons that fail.
     */
    private fun root(id: String, label: String, hint: String, dir: File, allowWrites: Boolean): Root {
        val writable = allowWrites && runCatching { dir.canWrite() }.getOrDefault(false)
        return Root(id, label, hint, dir, writable, writeBlocked = allowWrites && !writable)
    }

    /**
     * The roots that exist and are readable right now. The storage runtime permission may not
     * be granted and the SD card may be absent, so this is recomputed per request rather than
     * cached — it is a handful of stat() calls.
     */
    fun roots(context: Context): List<Root> {
        val list = mutableListOf<Root>()

        context.getExternalFilesDir(null)?.let {
            list += root("files", "App files", "Diagnostics and exports", it, allowWrites = true)
        }

        val download = File(
            Environment.getExternalStorageDirectory(),
            "${LocalBackupManager.BYD_DOWNLOAD_DIR}/${LocalBackupManager.BACKUP_SUBFOLDER}"
        )
        if (download.isDirectory) {
            list += root("download", "Backups (Download)", "Where Save to Download puts them", download, allowWrites = true)
        }

        // Where the head unit drops its screenshots. Not ours, but it is the other folder anyone
        // wants off the car, and there is no other way to reach it without a cable.
        val screenshots = File(
            Environment.getExternalStorageDirectory(),
            "${LocalBackupManager.BYD_DOWNLOAD_DIR}/$SCREENSHOTS_SUBFOLDER"
        )
        if (screenshots.isDirectory) {
            list += root("screenshots", "Screenshots", "Taken on the head unit", screenshots, allowWrites = true)
        }

        runCatching { LocalBackupManager.getInstance(context).sdCardRoot() }
            .onFailure { Log.w(TAG, "SD root lookup failed: ${it.message}") }
            .getOrNull()
            ?.let { File(it, LocalBackupManager.SD_BACKUP_FOLDER) }
            ?.takeIf { it.isDirectory }
            ?.let { list += root("sdcard", "Backups (SD card)", "The card's BydTripStats folder", it, allowWrites = true) }

        val auto = File(context.filesDir, LocalBackupManager.PRIVATE_BACKUP_DIR)
        if (auto.isDirectory) {
            // Read-only: these are the app's own rolling copies and it prunes them itself.
            list += root("auto", "Automatic backups", "The app's own rolling copies", auto, allowWrites = false)
        }

        return list.filter { runCatching { it.dir.canRead() }.getOrDefault(false) }
    }

    /**
     * Resolves [rel] underneath [base], returning null when the result would land outside.
     *
     * Kept pure and separate from [resolve] because this is the security boundary of the whole
     * feature — it is what a crafted `?p=` argument has to get past — and a pure function can be
     * unit-tested without a device.
     */
    fun resolveWithin(base: File, rel: String): File? {
        val canonicalBase = runCatching { base.canonicalFile }.getOrNull() ?: return null
        val target = runCatching {
            if (rel.isEmpty()) canonicalBase else File(canonicalBase, rel).canonicalFile
        }.getOrNull() ?: return null

        val basePath = canonicalBase.path
        val inside = target.path == basePath || target.path.startsWith(basePath + File.separator)
        return if (inside) target else null
    }

    /** Turns a wire path ("<rootId>/<relative>") into its root and real file, or null. */
    fun resolve(context: Context, path: String): Pair<Root, File>? {
        val clean = path.trim().trim('/')
        if (clean.isEmpty()) return null
        val rootId = clean.substringBefore('/')
        val rel = clean.substringAfter('/', "")
        val root = roots(context).firstOrNull { it.id == rootId } ?: return null
        return resolveWithin(root.dir, rel)?.let { root to it }
    }

    /** The wire path for [file] inside [root] — the inverse of [resolve]. */
    fun wirePath(root: Root, file: File): String {
        val base = runCatching { root.dir.canonicalFile.path }.getOrNull() ?: root.dir.path
        val path = runCatching { file.canonicalFile.path }.getOrNull() ?: file.path
        val rel = if (path == base) "" else path.removePrefix(base + File.separator)
        return if (rel.isEmpty()) root.id else "${root.id}/$rel"
    }

    fun isViewable(file: File): Boolean = viewKind(file) != null

    /**
     * How the viewer should render this file — "text", "image", or null for neither.
     *
     * The companion needs to know before it opens anything, because a log is fetched as text into a
     * <pre> while an image is just an <img> pointed at the same endpoint.
     */
    fun viewKind(file: File): String? {
        if (file.isDirectory || file.length() <= 0) return null
        return when {
            looksTextual(file) -> "text"
            imageMimeType(file) != null -> "image"
            else -> null
        }
    }

    /**
     * Whether the viewer should render this file as text.
     *
     * The extension check alone misses rotated logs, whose `.log` ends up in the middle of the name
     * — `diag.log.prev`, `app.log.1` — and those are exactly the files someone opens the viewer for.
     */
    private fun looksTextual(file: File): Boolean {
        val name = file.name.lowercase()
        return file.extension.lowercase() in TEXT_EXTENSIONS || name.contains(".log")
    }

    fun imageMimeType(file: File): String? = IMAGE_MIME_TYPES[file.extension.lowercase()]

    /**
     * The last [maxBytes] of a text file, cut at a line boundary, with a marker line when there was
     * more above it. Reading from the end keeps a multi-MB log viewable instead of refusing it.
     */
    fun readTail(file: File, maxBytes: Long = VIEW_TAIL_BYTES): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText()

        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - maxBytes)
            // Drops whatever partial line the seek landed in the middle of. readLine() decodes as
            // latin-1, but it is only used to find the next '\n', which is byte-exact either way.
            raf.readLine()
            val bytes = ByteArray((length - raf.filePointer).toInt())
            raf.readFully(bytes)
            val skipped = length - bytes.size
            "… showing the last ${humanBytes(bytes.size.toLong())} of ${humanBytes(length)} — " +
                "${humanBytes(skipped)} above this point is not shown. Download the file for all of it. …\n\n" +
                String(bytes, Charsets.UTF_8)
        }
    }

    private fun humanBytes(n: Long): String = when {
        n >= 1024 * 1024 -> "%.1f MB".format(Locale.US, n / (1024.0 * 1024.0))
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }

    /**
     * A directory listing for the companion. An empty or unresolvable [path] returns just the
     * root list, which is what the companion shows as its top level.
     */
    fun listingJson(context: Context, path: String): JSONObject {
        val available = roots(context)
        val json = JSONObject()

        json.put("roots", JSONArray().apply {
            available.forEach { root ->
                put(JSONObject().apply {
                    put("id", root.id)
                    put("label", root.label)
                    put("hint", root.hint)
                    put("location", root.dir.absolutePath)
                    put("writable", root.writable)
                    put("writeBlocked", root.writeBlocked)
                })
            }
        })

        val resolved = path.takeIf { it.isNotBlank() }?.let { resolve(context, it) }
        if (resolved == null || !resolved.second.isDirectory) {
            json.put("path", "")
            json.put("entries", JSONArray())
            return json
        }

        val (root, dir) = resolved
        json.put("path", wirePath(root, dir))
        json.put("label", root.label)
        json.put("location", dir.absolutePath)
        json.put("writable", root.writable)
        json.put("writeBlocked", root.writeBlocked)
        // Empty parent means "back to the root list" rather than "no parent".
        json.put("parent", if (dir.canonicalPath == root.dir.canonicalPath) "" else wirePath(root, dir.parentFile ?: root.dir))

        val children = dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()

        json.put("entries", JSONArray().apply {
            children.forEach { file ->
                put(JSONObject().apply {
                    put("name", file.name)
                    put("path", wirePath(root, file))
                    put("dir", file.isDirectory)
                    put("size", if (file.isDirectory) 0L else file.length())
                    put("modified", file.lastModified())
                    put("viewable", isViewable(file))
                    put("viewKind", viewKind(file) ?: "")
                })
            }
        })
        return json
    }
}
