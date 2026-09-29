package com.byd.tripstats.ui.screens

import android.os.Environment
import com.byd.tripstats.data.backup.BackupCodec
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.byd.tripstats.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Self-contained in-app file browser for picking a `.db` or `.db.gz` backup to restore. Replaces the SAF
 * OpenDocument picker, which on DiLink head units (no DocumentsUI) degrades to an app chooser that
 * forces reliance on a third-party file explorer. Reads the filesystem directly via [File.listFiles]
 * — works here because the app runs with requestLegacyExternalStorage + READ_EXTERNAL_STORAGE (the
 * same access the automatic backup scan already uses). Selecting a file hands back a plain [File];
 * the caller hands it to `prepareRestore(Uri.fromFile(file), …)`, which already handles file:// URIs
 * and puts it through the same check and confirmation as every other restore.
 */
@Composable
fun FileBrowserDialog(
    startDir: File,
    sdCardRoot: File?,
    onDismiss: () -> Unit,
    onFileSelected: (File) -> Unit,
) {
    val internalRoot = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember {
        mutableStateOf(startDir.takeIf { it.isDirectory && it.canRead() } ?: internalRoot)
    }

    // List off the main thread: directories (for navigation) + .db / .db.gz backups (for restore).
    val listing by produceState(
        initialValue = emptyList<File>() to emptyList<File>(),
        currentDir
    ) {
        value = withContext(Dispatchers.IO) {
            val files = currentDir.listFiles()?.toList().orEmpty()
            val dirs = files
                .filter { it.isDirectory && it.canRead() && !it.isHidden }
                .sortedBy { it.name.lowercase() }
            val dbs = files
                .filter { it.isFile && BackupCodec.isBackupName(it.name) }
                .sortedByDescending { it.lastModified() }
            dirs to dbs
        }
    }
    val (dirs, dbs) = listing

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    stringResource(R.string.restore_browser_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    currentDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { currentDir = internalRoot },
                        label = { Text(stringResource(R.string.file_browser_internal)) }
                    )
                    if (sdCardRoot != null) {
                        AssistChip(
                            onClick = { currentDir = sdCardRoot },
                            label = { Text(stringResource(R.string.file_browser_sdcard)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    currentDir.parentFile?.takeIf { it.canRead() }?.let { parent ->
                        item {
                            BrowserRow(Icons.Filled.ArrowUpward, "..", null) { currentDir = parent }
                        }
                    }
                    items(dirs) { d ->
                        BrowserRow(Icons.Filled.Folder, d.name, null) { currentDir = d }
                    }
                    items(dbs) { f ->
                        BrowserRow(Icons.Filled.Description, f.name, formatFileSize(f.length())) {
                            onFileSelected(f)
                        }
                    }
                    if (dirs.isEmpty() && dbs.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.restore_browser_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 20.dp)
                            )
                        }
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(icon: ImageVector, name: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
