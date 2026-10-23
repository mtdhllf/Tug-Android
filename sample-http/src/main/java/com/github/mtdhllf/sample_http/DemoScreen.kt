package com.github.mtdhllf.sample_http

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.mtdhllf.sample_http.R
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.Settings
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.store.TaskManager
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 最小下载 Demo：
 * - 初始化后的 TaskManager 注入
 * - 常用设置（连接数/并发/全局限速）
 * - 下载列表状态与进度
 * - 新建、暂停、继续、删除
 */
@Composable
fun DemoScreen(manager: TaskManager, engineName: String) {
    val tasks by manager.tasks.collectAsState()
    val stats by manager.stats.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var connections by remember { mutableStateOf(4) }
    var maxConcurrent by remember { mutableStateOf(2) }
    var limitText by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<TaskView?>(null) }
    var deleteFiles by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(
                R.string.demo_stats,
                engineName,
                stats.totalTasks,
                stats.activeTasks,
                stats.completedTasks,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.live_speed, formatBps(stats.downloadBps)),
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.common_settings), style = MaterialTheme.typography.titleMedium)
                Stepper(stringResource(R.string.connections_per_task), connections, 1, 16) { connections = it }
                Stepper(stringResource(R.string.max_concurrent), maxConcurrent, 1, 6) { maxConcurrent = it }
                OutlinedTextField(
                    value = limitText,
                    onValueChange = {
                        limitText = it.filter { c -> c.isDigit() }
                        applied = null
                    },
                    label = { Text(stringResource(R.string.limit_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        manager.applySettings(
                            Settings().apply {
                                connectionsPerTask = connections
                                maxConcurrentTasks = maxConcurrent
                                globalDownloadLimitKib = limitText.trim().toLongOrNull()
                                    ?.takeIf { it > 0 }
                            },
                        )
                        applied = context.getString(R.string.settings_applied)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.apply_settings))
                }
                applied?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.save_dir, manager.defaultDownloadDir),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.new_download), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.download_url)) },
                    placeholder = { Text("https://example.com/file.zip") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val target = url.trim()
                        if (target.isEmpty()) return@Button
                        scope.launch {
                            runCatching { manager.add(NewTask(url = target)) }
                                .onSuccess {
                                    url = ""
                                    error = null
                                }
                                .onFailure { error = it.message ?: context.getString(R.string.add_failed) }
                        }
                    },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.start_download))
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { scope.launch { manager.pauseAll() } },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.pause_all)) }
            OutlinedButton(
                onClick = { scope.launch { manager.resumeAll() } },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.resume_all)) }
        }

        Text(stringResource(R.string.download_list), style = MaterialTheme.typography.titleMedium)
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.empty_tasks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        tasks.forEach { task ->
            TaskRow(
                task = task,
                onPause = { scope.launch { manager.pause(task.id) } },
                onResume = { scope.launch { manager.resume(task.id) } },
                onDelete = {
                    pendingDelete = task
                    deleteFiles = false
                },
            )
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_task)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteFiles,
                            onCheckedChange = { deleteFiles = it },
                        )
                        Text(stringResource(R.string.delete_files))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch { manager.remove(task.id, deleteFiles) }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onChange((value - 1).coerceAtLeast(min)) },
            enabled = value > min,
        ) { Text("−") }
        Text("$value")
        TextButton(
            onClick = { onChange((value + 1).coerceAtMost(max)) },
            enabled = value < max,
        ) { Text("+") }
    }
}

@Composable
private fun TaskRow(
    task: TaskView,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${stateLabel(task.state)} · ${formatBps(task.downloadBps)} · " +
                            "${formatBytes(task.downloaded)}" +
                            (task.total?.let { " / ${formatBytes(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.state.isActive) {
                    TextButton(onClick = onPause) { Text(stringResource(R.string.pause)) }
                }
                if (task.state == TaskState.Paused || task.state == TaskState.Failed) {
                    TextButton(onClick = onResume) { Text(stringResource(R.string.resume)) }
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            task.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun stateLabel(state: TaskState): String = stringResource(
    when (state) {
        TaskState.Queued -> R.string.state_queued
        TaskState.FetchingMetadata -> R.string.state_fetching
        TaskState.Downloading -> R.string.state_downloading
        TaskState.Paused -> R.string.state_paused
        TaskState.Seeding -> R.string.state_seeding
        TaskState.Completed -> R.string.state_completed
        TaskState.Failed -> R.string.state_failed
    },
)

private fun formatBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var index = 0
    while (size >= 1024 && index < units.lastIndex) {
        size /= 1024
        index++
    }
    return if (index == 0) {
        "$value B"
    } else {
        String.format(Locale.US, "%.1f %s", size, units[index])
    }
}

private fun formatBps(value: Long): String =
    if (value <= 0L) "0 B/s" else "${formatBytes(value)}/s"
