package com.github.mtdhllf.tug.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.core.model.TorrentMetadata
import com.github.mtdhllf.tug.core.model.kindFor
import com.github.mtdhllf.tug.service.DownloadService
import com.github.mtdhllf.tug.ui.LocalSnackbarHostState
import com.github.mtdhllf.tug.ui.util.formatBytes
import com.github.mtdhllf.tug.ui.util.rememberNotificationPermissionRequest
import com.github.mtdhllf.tug.ui.vm.AddTaskViewModel
import com.github.mtdhllf.tug.ui.vm.TugViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    initialUrl: String = "",
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as TugApp
    val context = LocalContext.current
    val viewModelFactory = remember { TugViewModelFactory(app) }
    val addViewModel: AddTaskViewModel = viewModel(factory = viewModelFactory)
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val ensureNotificationPermission = rememberNotificationPermissionRequest()

    var url by remember { mutableStateOf(initialUrl) }
    var fileName by remember { mutableStateOf("") }
    var connections by remember { mutableStateOf(addViewModel.connections) }
    var error by remember { mutableStateOf<String?>(null) }
    var torrentBytes by remember { mutableStateOf<ByteArray?>(null) }
    var torrentName by remember { mutableStateOf<String?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var pendingMetadata by remember { mutableStateOf<TorrentMetadata?>(null) }
    var selectedFiles by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showFileDialog by remember { mutableStateOf(false) }

    suspend fun enqueueTorrent(meta: TorrentMetadata, selected: Set<Int>) {
        if (submitting) return
        submitting = true
        val id = runCatching {
            app.taskManager.add(
                NewTask(
                    url = "torrent://${meta.name}",
                    kind = TaskKind.Torrent,
                    torrentBytes = meta.torrentBytes,
                    onlyFiles = if (meta.singleFile) null else selected.toList(),
                    fileName = null,
                ),
            )
        }.getOrNull()
        if (id == null) {
            error = context.getString(R.string.error_create_task)
            submitting = false
            return
        }
        ensureNotificationPermission()
        DownloadService.start(context)
        snackbar?.showSnackbar(context.getString(R.string.snackbar_added))
        onDone()
    }

    val pickTorrent = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                error = context.getString(R.string.error_read_torrent)
                return@launch
            }
            torrentBytes = bytes
            torrentName = uri.lastPathSegment?.substringAfterLast('/') ?: "torrent"
            url = ""
            // 种子可能是多文件，禁用并清空自定义文件名
            fileName = ""
            error = null
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                    if (it.isNotBlank() && torrentBytes != null) {
                        // 改用链接时清除已选种子，避免来源歧义
                        torrentBytes = null
                        torrentName = null
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_url"),
                label = { Text(stringResource(R.string.url_label)) },
                placeholder = { Text(stringResource(R.string.url_placeholder)) },
                singleLine = true,
                isError = error != null,
                supportingText = {
                    val message = error
                    when {
                        message != null -> Text(message)
                        torrentBytes != null -> Text(
                            stringResource(R.string.selected_torrent_file, torrentName ?: ""),
                        )
                        else -> Text(stringResource(R.string.url_support))
                    }
                },
            )

            OutlinedButton(
                onClick = {
                    pickTorrent.launch(
                        arrayOf("application/x-bittorrent", "application/octet-stream"),
                    )
                },
                enabled = !parsing && !submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (torrentBytes == null) R.string.choose_torrent else R.string.reselect_torrent,
                    ),
                )
            }

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = torrentBytes == null && !parsing && !submitting,
                label = { Text(stringResource(R.string.file_name_label)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (torrentBytes != null) R.string.torrent_no_filename else R.string.file_name_hint,
                        ),
                    )
                },
                singleLine = true,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.connections), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.connections_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { connections = (connections - 1).coerceAtLeast(1) },
                ) {
                    Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.content_dec_connections))
                }
                Text("$connections", style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = { connections = (connections + 1).coerceAtMost(32) },
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.content_inc_connections))
                }
            }

            Button(
                onClick = {
                    if (parsing || submitting) return@Button
                    val target = url.trim()
                    val message = when {
                        torrentBytes != null -> null
                        target.isEmpty() -> context.getString(R.string.error_need_url_or_torrent)
                        target.startsWith("http://", true) ||
                            target.startsWith("https://", true) ||
                            target.startsWith("magnet:", true) -> null
                        else -> context.getString(R.string.error_unsupported_url)
                    }
                    if (message != null) {
                        error = message
                        return@Button
                    }
                    val isTorrent = torrentBytes != null || kindFor(target) == TaskKind.Torrent
                    val chooseFiles = app.settingsRepository.settings.value.btChooseFilesBeforeStart
                    if (!isTorrent) {
                        submitting = true
                        scope.launch {
                            addViewModel.onUrlChange(target)
                            addViewModel.onFileNameChange(fileName.trim())
                            addViewModel.onConnectionsChange(connections)
                            val id = addViewModel.submit()
                            if (id == null) {
                                error = addViewModel.error ?: context.getString(R.string.error_create_task)
                                submitting = false
                            } else {
                                ensureNotificationPermission()
                                DownloadService.start(context)
                                snackbar?.showSnackbar(context.getString(R.string.snackbar_added))
                                onDone()
                            }
                        }
                        return@Button
                    }
                    if (!chooseFiles) {
                        // 默认：不解析/不选文件，直接入队，由引擎下载全部文件
                        submitting = true
                        scope.launch {
                            val id = runCatching {
                                app.taskManager.add(
                                    NewTask(
                                        url = if (torrentBytes != null) {
                                            "torrent://${torrentName ?: "local"}"
                                        } else {
                                            target
                                        },
                                        kind = TaskKind.Torrent,
                                        torrentBytes = torrentBytes,
                                        fileName = null,
                                    ),
                                )
                            }.getOrNull()
                            if (id == null) {
                                error = context.getString(R.string.error_create_task)
                                submitting = false
                            } else {
                                ensureNotificationPermission()
                                DownloadService.start(context)
                                snackbar?.showSnackbar(context.getString(R.string.snackbar_added))
                                onDone()
                            }
                        }
                        return@Button
                    }
                    // 开关开启：先解析元数据 → 单文件直接入队；多文件让用户勾选
                    parsing = true
                    error = null
                    scope.launch {
                        try {
                            val input = NewTask(
                                url = if (torrentBytes != null) {
                                    "torrent://${torrentName ?: "local"}"
                                } else {
                                    target
                                },
                                kind = TaskKind.Torrent,
                                torrentBytes = torrentBytes,
                            )
                            val meta = runCatching { app.torrentEngine.parse(input) }
                                .onFailure { error = it.message ?: context.getString(R.string.error_parse_torrent) }
                                .getOrNull()
                            parsing = false
                            if (meta == null) return@launch
                            if (meta.singleFile) {
                                enqueueTorrent(meta, meta.files.map { it.index }.toSet())
                            } else {
                                pendingMetadata = meta
                                selectedFiles = meta.files.map { it.index }.toSet()
                                showFileDialog = true
                            }
                        } catch (t: Throwable) {
                            parsing = false
                            error = t.message ?: context.getString(R.string.error_parse_torrent)
                        }
                    }
                },
                enabled = !parsing && !submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_submit"),
            ) {
                Text(
                    stringResource(
                        when {
                            submitting -> R.string.processing
                            parsing -> R.string.parsing
                            else -> R.string.common_start_download
                        },
                    ),
                )
            }
        }
    }

    val meta = pendingMetadata
    if (showFileDialog && meta != null) {
        AlertDialog(
            onDismissRequest = { showFileDialog = false },
            title = { Text(stringResource(R.string.choose_files_title, meta.files.size)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    meta.files.forEach { file ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedFiles.contains(file.index),
                                onCheckedChange = { checked ->
                                    selectedFiles = if (checked) {
                                        selectedFiles + file.index
                                    } else {
                                        selectedFiles - file.index
                                    }
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(file.path, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatBytes(file.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (submitting) return@TextButton
                        showFileDialog = false
                        scope.launch { enqueueTorrent(meta, selectedFiles) }
                    },
                ) { Text(stringResource(R.string.common_start_download)) }
            },
            dismissButton = {
                TextButton(onClick = { showFileDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
