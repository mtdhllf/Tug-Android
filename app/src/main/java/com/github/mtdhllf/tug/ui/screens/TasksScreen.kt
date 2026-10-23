package com.github.mtdhllf.tug.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.service.DownloadService
import com.github.mtdhllf.tug.ui.LocalSnackbarHostState
import com.github.mtdhllf.tug.ui.components.DeleteConfirmDialog
import com.github.mtdhllf.tug.ui.components.EmptyState
import com.github.mtdhllf.tug.ui.components.TaskFilter
import com.github.mtdhllf.tug.ui.components.TaskRow
import com.github.mtdhllf.tug.ui.components.TaskSort
import com.github.mtdhllf.tug.ui.components.applyFilter
import com.github.mtdhllf.tug.ui.components.applySearch
import com.github.mtdhllf.tug.ui.components.applySort
import com.github.mtdhllf.tug.ui.util.FileOps
import com.github.mtdhllf.tug.ui.util.rememberNotificationPermissionRequest
import com.github.mtdhllf.tug.ui.vm.TasksViewModel
import com.github.mtdhllf.tug.ui.vm.TugViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(
    onTaskClick: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as TugApp
    val context = LocalContext.current
    val viewModelFactory = remember { TugViewModelFactory(app) }
    val tasksViewModel: TasksViewModel = viewModel(factory = viewModelFactory)

    val tasks by tasksViewModel.tasks.collectAsStateWithLifecycle()
    val selectedIds = tasksViewModel.selectedIds
    val selectionMode = selectedIds.isNotEmpty()

    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(TaskFilter.All.name) }
    var sortName by rememberSaveable { mutableStateOf(TaskSort.Recent.name) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<TaskView>?>(null) }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val ensureNotificationPermission = rememberNotificationPermissionRequest()

    val filter = TaskFilter.entries.firstOrNull { it.name == filterName } ?: TaskFilter.All
    val sort = TaskSort.entries.firstOrNull { it.name == sortName } ?: TaskSort.Recent
    val visible = tasks.applySearch(query).applyFilter(filter).applySort(sort)

    Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                onSelectAll = {
                    if (selectedIds.size == visible.size) {
                        tasksViewModel.clearSelection()
                    } else {
                        tasksViewModel.clearSelection()
                        visible.forEach { selectedIds.add(it.id) }
                    }
                },
                onPause = {
                    tasksViewModel.pauseSelected()
                    scope.launch { snackbar?.showSnackbar(context.getString(R.string.snackbar_paused_selected)) }
                },
                onResume = {
                    ensureNotificationPermission()
                    scope.launch {
                        selectedIds.toList().forEach { DownloadService.start(context) }
                    }
                    tasksViewModel.resumeSelected()
                    scope.launch { snackbar?.showSnackbar(context.getString(R.string.snackbar_resumed_selected)) }
                },
                onDelete = { pendingDelete = tasks.filter { it.id in selectedIds } },
                onClose = { tasksViewModel.clearSelection() },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tasks_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.Outlined.Sort, contentDescription = stringResource(R.string.content_sort))
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                ) {
                    TaskSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.labelRes)) },
                            onClick = {
                                sortName = option.name
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TaskFilter.entries.forEach { option ->
                val count = tasks.applyFilter(option).size
                FilterChip(
                    selected = filter == option,
                    onClick = { filterName = option.name },
                    label = { Text("${stringResource(option.labelRes)} $count") },
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        title = if (query.isNotBlank()) {
                            stringResource(R.string.empty_no_match)
                        } else {
                            stringResource(R.string.empty_category)
                        },
                        subtitle = if (tasks.isEmpty()) {
                            stringResource(R.string.empty_tasks_subtitle)
                        } else {
                            stringResource(R.string.empty_filter_subtitle)
                        },
                        actionLabel = if (tasks.isEmpty()) stringResource(R.string.new_task) else null,
                        onAction = if (tasks.isEmpty()) onAdd else null,
                    )
                }
            } else {
                items(visible, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onClick = { onTaskClick(task.id) },
                        onLongClick = { tasksViewModel.toggleSelection(task.id) },
                        selectionMode = selectionMode,
                        selected = task.id in selectedIds,
                        onPauseResume = {
                            scope.launch {
                                if (task.state == TaskState.Downloading ||
                                    task.state == TaskState.Queued
                                ) {
                                    tasksViewModel.pause(task.id)
                                } else {
                                    ensureNotificationPermission()
                                    DownloadService.start(context)
                                    tasksViewModel.resume(task.id)
                                }
                            }
                        },
                        onOpen = { FileOps.openPath(context, task.path) },
                    )
                }
                item { Column(Modifier.padding(bottom = 80.dp)) {} }
            }
        }
    }

    pendingDelete?.let { toDelete ->
        DeleteConfirmDialog(
            task = toDelete.first(),
            title = if (toDelete.size > 1) {
                stringResource(R.string.delete_selected_tasks, toDelete.size)
            } else {
                null
            },
            onConfirm = { deleteFiles ->
                if (toDelete.size > 1) {
                    tasksViewModel.removeSelected(deleteFiles)
                } else {
                    tasksViewModel.remove(toDelete.first().id, deleteFiles)
                }
                pendingDelete = null
                scope.launch { snackbar?.showSnackbar(context.getString(R.string.snackbar_deleted)) }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.content_exit_selection))
        }
        Text(
            stringResource(R.string.selected_count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Outlined.Checklist, contentDescription = stringResource(R.string.content_select_all))
        }
        IconButton(onClick = onPause) {
            Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.content_pause_selected))
        }
        IconButton(onClick = onResume) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.content_resume_selected))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.content_delete_selected))
        }
    }
}
