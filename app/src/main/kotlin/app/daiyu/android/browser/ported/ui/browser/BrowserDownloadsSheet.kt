// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see app/src/main/assets/licenses/openminis/NOTICE.md.
package app.daiyu.android.browser.ported.ui.browser

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import app.daiyu.android.ui.haptics.material.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.daiyu.android.R
import app.daiyu.android.browser.ported.ui.components.MinisTextButton
import app.daiyu.android.browser.ui.BrowserUiPool

/**
 * [T-android-browser-download-ux] Downloads panel — Android port of iOS
 * BrowserDownloadPanelSheet (T-browser-download-ux v2/v3): entries
 * newest-first; in-flight rows show live progress + a cancel button;
 * completed rows open the file via ACTION_VIEW (the Android "Show in Files"
 * equivalent — same FileProvider path FilePreviewScreen uses); failed rows
 * show the reason. "Clear" drops ALL finished records (completed AND failed,
 * v3 semantics); in-flight rows stay. Opening the panel marks terminal
 * entries seen, which zeroes the toolbar badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserDownloadsSheet(
    tabPool: BrowserUiPool,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val downloads by tabPool.downloads.collectAsState()

    // Terminal entries stop counting toward the badge once the user has
    // actually looked at the panel.
    LaunchedEffect(Unit) { tabPool.markDownloadsSeen() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.om_browser_downloads_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (downloads.any { it.state != BrowserUiPool.DownloadState.DOWNLOADING }) {
                    MinisTextButton(onClick = { tabPool.clearFinishedDownloads() }) {
                        Text(stringResource(R.string.om_browser_downloads_clear))
                    }
                }
            }
            if (downloads.isEmpty()) {
                Text(
                    stringResource(R.string.om_browser_downloads_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn {
                    items(downloads, key = { it.id }) { entry ->
                        DownloadRow(
                            entry = entry,
                            onCancel = { tabPool.cancelDownload(entry.id) },
                            onOpen = { tabPool.openDownload(entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: BrowserUiPool.DownloadEntry,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        val (icon, tint) = when (entry.state) {
            BrowserUiPool.DownloadState.DOWNLOADING ->
                Icons.Default.Download to MaterialTheme.colorScheme.primary
            BrowserUiPool.DownloadState.COMPLETED ->
                Icons.Default.CheckCircle to androidx.compose.ui.graphics.Color(0xFF34C759)
            BrowserUiPool.DownloadState.FAILED ->
                Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // Middle truncation keeps the extension visible (iOS v3).
                BrowserUiPool.middleTruncated(entry.filename, head = 24, tail = 12),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            when (entry.state) {
                BrowserUiPool.DownloadState.DOWNLOADING -> {
                    val context = LocalContext.current
                    val doneText = android.text.format.Formatter
                        .formatShortFileSize(context, entry.bytesDone)
                    val statusText = if (entry.totalBytes > 0) {
                        val totalText = android.text.format.Formatter
                            .formatShortFileSize(context, entry.totalBytes)
                        val pct = (entry.bytesDone * 100 / entry.totalBytes).toInt()
                        "$pct% · $doneText / $totalText"
                    } else {
                        doneText
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (entry.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (entry.bytesDone.toFloat() / entry.totalBytes).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                BrowserUiPool.DownloadState.COMPLETED -> {
                    val context = LocalContext.current
                    Text(
                        android.text.format.Formatter
                            .formatShortFileSize(context, entry.bytesDone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BrowserUiPool.DownloadState.FAILED -> {
                    Text(
                        entry.failureReason
                            ?: stringResource(R.string.om_browser_downloads_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when (entry.state) {
            BrowserUiPool.DownloadState.DOWNLOADING -> {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = stringResource(R.string.om_browser_downloads_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BrowserUiPool.DownloadState.COMPLETED -> {
                IconButton(onClick = onOpen) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = stringResource(R.string.om_browser_downloads_open),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            BrowserUiPool.DownloadState.FAILED -> Unit
        }
    }
}
