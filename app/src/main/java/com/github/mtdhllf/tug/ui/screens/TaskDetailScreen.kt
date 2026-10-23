package com.github.mtdhllf.tug.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.model.TorrentDetails
import com.github.mtdhllf.tug.service.DownloadService
import com.github.mtdhllf.tug.ui.LocalSnackbarHostState
import com.github.mtdhllf.tug.ui.components.DeleteConfirmDialog
import com.github.mtdhllf.tug.ui.components.EmptyState
import com.github.mtdhllf.tug.ui.components.SectionCard
import com.github.mtdhllf.tug.ui.components.StatusChip
import com.github.mtdhllf.tug.ui.components.TaskProgress
import com.github.mtdhllf.tug.ui.util.FileOps
import com.github.mtdhllf.tug.ui.util.formatBytes
import com.github.mtdhllf.tug.ui.util.formatEta
import com.github.mtdhllf.tug.ui.util.formatPercent
import com.github.mtdhllf.tug.ui.util.formatSpeed
import com.github.mtdhllf.tug.ui.util.rememberNotificationPermissionRequest
import com.github.mtdhllf.tug.ui.vm.TaskDetailViewModel
import com.github.mtdhllf.tug.ui.vm.TugViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TaskDetailScreen(
    taskId: Long,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as TugApp
    val context = LocalContext.current
    val viewModelFactory = remember(taskId) { TugViewModelFactory(app, taskId) }
    val detailViewModel: TaskDetailViewModel = viewModel(factory = viewModelFactory)
    val task by detailViewModel.task.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val ensureNotificationPermission = rememberNotificationPermissionRequest()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var torrentDetails by remember(taskId) { mutableStateOf<TorrentDetails?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(
                stringResource(R.string.detail_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val current = task
        if (current == null) {
            EmptyState(
                stringResource(R.string.task_missing_title),
                stringResource(R.string.task_missing_subtitle),
            )
            return@Column
        }

        LaunchedEffect(current.id, current.kind, current.state) {
            if (current.kind.name == "Torrent" && current.state.isActive) {
                while (true) {
                    torrentDetails = detailViewModel.torrentDetails()
                    delay(1_500)
                }
            } else if (current.kind.name == "Torrent") {
                torrentDetails = detailViewModel.torrentDetails()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(current.state)
                    }
                    TaskProgress(current)
                    Text(
                        "${formatPercent(current.progress, current.total)} · " +
                            "${formatBytes(current.downloaded)} / " +
                            "${current.total?.let(::formatBytes) ?: "?"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.speed_remaining,
                            formatSpeed(current.downloadBps),
                            formatEta(current.etaSecs),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val taskError = current.error
                    if (!taskError.isNullOrBlank()) {
                        Text(
                            taskError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            InfoCard(
                rows = buildList {
                    add(stringResource(R.string.info_link) to current.url)
                    add(stringResource(R.string.info_save_location) to current.path)
                    add(
                        stringResource(R.string.info_task_type) to
                            if (current.kind.name == "Http") {
                                stringResource(R.string.task_type_http)
                            } else {
                                stringResource(R.string.task_type_torrent)
                            },
                    )
                    if (current.kind.name == "Torrent") {
                        add(stringResource(R.string.info_peers) to "${current.peers}")
                        add(stringResource(R.string.info_uploaded) to formatBytes(current.uploaded))
                    } else {
                        add(stringResource(R.string.info_connections) to "${current.connections}")
                    }
                },
            )

            if (current.kind.name == "Torrent") {
                val details = torrentDetails
                if (details != null) {
                    SectionCard(stringResource(R.string.files_count, details.files.size)) {
                        details.files.take(50).forEach { file ->
                            Row {
                                Text(
                                    (if (file.selected) "✓ " else "· ") + file.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatBytes(file.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    SectionCard(stringResource(R.string.peers_count, details.peers.size)) {
                        if (details.peers.isEmpty()) {
                            Text(stringResource(R.string.no_peers), style = MaterialTheme.typography.bodySmall)
                        }
                        details.peers.take(20).forEach { peer ->
                            Text(
                                "${peer.address} · ${peer.client} · ↓${formatSpeed(peer.downBps)}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    SectionCard(stringResource(R.string.trackers_count, details.trackers.size)) {
                        if (details.trackers.isEmpty()) {
                            Text(stringResource(R.string.no_trackers), style = MaterialTheme.typography.bodySmall)
                        }
                        details.trackers.forEach { tracker ->
                            Text(
                                (if (tracker.verified) "✓ " else "· ") + tracker.url,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (current.state == TaskState.Completed) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { FileOps.openPath(context, current.path) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.common_open_file)) }
                    OutlinedButton(
                        onClick = { FileOps.sharePath(context, current.path) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.common_share_file)) }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            copyToClipboard(context, current.url)
                            snackbar?.showSnackbar(context.getString(R.string.snackbar_link_copied))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_copy_link)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (current.state == TaskState.Downloading ||
                                    current.state == TaskState.Queued
                                ) {
                                    detailViewModel.pause()
                                } else {
                                    ensureNotificationPermission()
                                    DownloadService.start(context)
                                    detailViewModel.resume()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (current.state == TaskState.Downloading ||
                                current.state == TaskState.Queued
                            ) {
                                stringResource(R.string.common_pause)
                            } else {
                                stringResource(R.string.common_resume)
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                copyToClipboard(context, current.url)
                                snackbar?.showSnackbar(context.getString(R.string.snackbar_link_copied))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.common_copy_link)) }
                }
            }

            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.delete_task_title)) }

            Spacer(Modifier.height(24.dp))
        }
    }

    val currentTask = task
    if (showDeleteDialog && currentTask != null) {
        DeleteConfirmDialog(
            task = currentTask,
            onConfirm = { deleteFiles ->
                detailViewModel.remove(deleteFiles)
                showDeleteDialog = false
                snackbar?.let { host ->
                    scope.launch { host.showSnackbar(context.getString(R.string.snackbar_deleted)) }
                }
                onBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.clipboard_download_link), text),
    )
}
