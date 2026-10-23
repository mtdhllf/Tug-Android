package com.github.mtdhllf.tug.ui.screens
import com.github.mtdhllf.tug.core.model.kindFor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.service.DownloadService
import com.github.mtdhllf.tug.ui.LocalSnackbarHostState
import com.github.mtdhllf.tug.ui.components.EmptyState
import com.github.mtdhllf.tug.ui.components.SectionCard
import com.github.mtdhllf.tug.ui.components.TaskRow
import com.github.mtdhllf.tug.ui.util.formatSpeed
import com.github.mtdhllf.tug.ui.util.rememberNotificationPermissionRequest
import com.github.mtdhllf.tug.ui.vm.HomeViewModel
import com.github.mtdhllf.tug.ui.vm.TugViewModelFactory
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onAdd: () -> Unit,
    onTorrentAdd: (String) -> Unit,
    onTasks: () -> Unit,
    onTaskClick: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as TugApp
    val context = LocalContext.current
    val viewModelFactory = remember { TugViewModelFactory(app) }
    val homeViewModel: HomeViewModel = viewModel(factory = viewModelFactory)

    val tasks by homeViewModel.tasks.collectAsStateWithLifecycle()
    val stats by homeViewModel.stats.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val ensureNotificationPermission = rememberNotificationPermissionRequest()

    var quickUrl by rememberSaveable { mutableStateOf("") }
    var quickError by remember { mutableStateOf<String?>(null) }

    val active = tasks.filter {
        it.state == TaskState.Downloading || it.state == TaskState.Queued ||
            it.state == TaskState.Paused || it.state == TaskState.Failed
    }
    val recentCompleted = tasks.filter { it.state == TaskState.Completed }
        .sortedByDescending { it.updatedAt }
        .take(5)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text("Tug", style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(stringResource(R.string.stat_speed), formatSpeed(stats.downloadBps), Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_active), "${stats.activeTasks}", Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_completed), "${stats.completedTasks}", Modifier.weight(1f))
            }
        }

        item {
            SectionCard(
                title = stringResource(R.string.quick_add),
                action = {
                    TextButton(onClick = onAdd) { Text(stringResource(R.string.more_options)) }
                },
            ) {
                OutlinedTextField(
                    value = quickUrl,
                    onValueChange = {
                        quickUrl = it
                        quickError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.quick_add_placeholder)) },
                    singleLine = true,
                    isError = quickError != null,
                    supportingText = {
                        val message = quickError
                        if (message != null) {
                            Text(message)
                        }
                    },
                )
                Button(
                    onClick = {
                        val target = quickUrl.trim()
                        val error = when {
                            target.isEmpty() -> context.getString(R.string.error_need_url)
                            target.startsWith("http://", true) ||
                                target.startsWith("https://", true) ||
                                target.startsWith("magnet:", true) -> null
                            else -> context.getString(R.string.error_unsupported_url)
                        }
                        if (error != null) {
                            quickError = error
                        } else if (
                            kindFor(target) == TaskKind.Torrent &&
                            app.settingsRepository.settings.value.btChooseFilesBeforeStart
                        ) {
                            // 开启"BT 下载前选择文件"时，转到新建面板解析并勾选
                            quickUrl = ""
                            onTorrentAdd(target)
                        } else {
                            scope.launch {
                                ensureNotificationPermission()
                                DownloadService.start(context)
                                app.taskManager.add(NewTask(url = target))
                                quickUrl = ""
                                snackbar?.showSnackbar(context.getString(R.string.snackbar_added))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.common_start_download))
                }
            }
        }

        item {
            SectionCard(
                title = stringResource(R.string.active_title),
                action = {
                    TextButton(onClick = onTasks) { Text(stringResource(R.string.common_all)) }
                },
            ) {
                if (active.isEmpty()) {
                    EmptyState(
                        stringResource(R.string.empty_active_title),
                        stringResource(R.string.empty_active_subtitle),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        active.take(3).forEach { task ->
                            TaskRow(
                                task = task,
                                onClick = { onTaskClick(task.id) },
                                onLongClick = { onTaskClick(task.id) },
                                selectionMode = false,
                                selected = false,
                                onPauseResume = {
                                    scope.launch {
                                        if (task.state == TaskState.Downloading ||
                                            task.state == TaskState.Queued
                                        ) {
                                            app.taskManager.pause(task.id)
                                        } else {
                                            ensureNotificationPermission()
                                            DownloadService.start(context)
                                            app.taskManager.resume(task.id)
                                        }
                                    }
                                },
                                onOpen = {
                                    com.github.mtdhllf.tug.ui.util.FileOps
                                        .openPath(context, task.path)
                                },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.recent_completed)) {
                if (recentCompleted.isEmpty()) {
                    EmptyState(
                        stringResource(R.string.empty_completed_title),
                        stringResource(R.string.empty_completed_subtitle),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentCompleted.forEach { task ->
                            TaskRow(
                                task = task,
                                onClick = { onTaskClick(task.id) },
                                onLongClick = { onTaskClick(task.id) },
                                selectionMode = false,
                                selected = false,
                                onPauseResume = {},
                                onOpen = {
                                    com.github.mtdhllf.tug.ui.util.FileOps
                                        .openPath(context, task.path)
                                },
                            )
                        }
                    }
                }
            }
        }

        item { Column(Modifier.padding(bottom = 80.dp)) {} }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // 速度等长文本按长度自适应字号，避免窄卡片换行/溢出
            val fontSize = when {
                value.length <= 6 -> 24.sp
                value.length <= 8 -> 20.sp
                value.length <= 10 -> 17.sp
                else -> 15.sp
            }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = fontSize),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
