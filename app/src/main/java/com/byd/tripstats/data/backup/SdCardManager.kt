package com.byd.tripstats.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages persistent write access to a user-selected folder on external SD card
 * using the Storage Access Framework (SAF).
 *
 * HOW IT WORKS:
 * 1. The user taps "Select SD Card Folder" once — this opens the system folder picker.
 * 2. The user navigates to their SD card and taps "Use this folder".
 * 3. We call takePersistableUriPermission() to keep write access across reboots.
 * 4. The URI is saved to SharedPreferences. All future writes are silent.
 *
 * USAGE IN A COMPOSABLE:
 *
 *   val sdManager = remember { SdCardManager.getInstance(context) }
 *   val folderPickerLauncher = rememberLauncherForActivityResult(
 *       contract = ActivityResultContracts.OpenDocumentTree()
 *   ) { uri ->
 *       if (uri != null) sdManager.onFolderSelected(context, uri)
 *   }
 *   // To open picker:
 *   folderPickerLauncher.launch(null)
 *
 * NOTE: androidx.documentfile:documentfile must be in build.gradle:
 *   implementation "androidx.documentfile:documentfile:1.0.1"
 */
class SdCardManager private constructor(private val context: Context) {

    companion object {
        private const val TAG          = "SdCardManager"
        private const val PREFS_NAME   = "sd_card_prefs"
        private const val KEY_TREE_URI = "sd_tree_uri"

        @Volatile private var INSTANCE: SdCardManager? = null

        fun getInstance(context: Context): SdCardManager {
            return INSTANCE ?: synchronized(this) {
                SdCardManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Emits the display path string, or null if no folder is configured
    private val _selectedFolder = MutableStateFlow<String?>(null)
    val selectedFolder: StateFlow<String?> = _selectedFolder.asStateFlow()

    val hasFolder: Boolean get() = loadTreeUri() != null
    val treeUri: Uri? get() = loadTreeUri()

    init {
        // Restore display label from saved URI on startup
        loadTreeUri()?.let { uri ->
            _selectedFolder.value = friendlyPath(uri)
        }
    }

    // ── Folder selection ──────────────────────────────────────────────────────

    /**
     * Call this from your OpenDocumentTree ActivityResult callback with the URI
     * the user chose. Persists read+write permission across reboots.
     */
    fun onFolderSelected(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
            _selectedFolder.value = friendlyPath(uri)
            Log.i(TAG, "SD folder set: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist URI permission", e)
        }
    }

    fun clearFolder() {
        val uri = loadTreeUri()
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
        _selectedFolder.value = null
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Writes [content] as a new file named [fileName] in the selected SD folder.
     * Overwrites an existing file with the same name if present.
     *
     * @return The URI of the written file, or null on failure.
     */
    suspend fun writeFile(
        fileName: String,
        mimeType: String,
        content: ByteArray
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val treeUri = loadTreeUri()
                ?: throw Exception("No SD card folder selected")

            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw Exception("Cannot access selected folder")

            if (!tree.canWrite()) {
                throw Exception("Cannot write to selected folder — check permissions")
            }

            // Delete existing file with same name to allow overwrite
            tree.findFile(fileName)?.delete()

            val newFile = tree.createFile(mimeType, fileName)
                ?: throw Exception("Could not create file on SD card")

            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                out.write(content)
            } ?: throw Exception("Could not open output stream")

            Log.i(TAG, "Written to SD: $fileName (${content.size} bytes)")
            newFile.uri

        } catch (e: Exception) {
            Log.e(TAG, "SD write failed: ${e.message}", e)
            null
        }
    }

    /** Convenience overload for text content. */
    suspend fun writeTextFile(
        fileName: String,
        mimeType: String,
        content: String
    ): Uri? = writeFile(fileName, mimeType, content.toByteArray(Charsets.UTF_8))

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadTreeUri(): Uri? {
        return prefs.getString(KEY_TREE_URI, null)?.let { Uri.parse(it) }
    }

    /**
     * Turns a SAF tree URI like
     *   content://com.android.externalstorage.documents/tree/1234-ABCD%3A
     * into a readable label like "SD Card (1234-ABCD)" for display.
     */
    private fun friendlyPath(uri: Uri): String {
        return try {
            // Last path segment is typically "primary:" or "1234-ABCD:"
            val segment = uri.lastPathSegment ?: uri.toString()
            val id = segment.removeSuffix(":")
            if (id == "primary") "Internal Storage" else "SD Card ($id)"
        } catch (_: Exception) {
            uri.toString()
        }
    }
}