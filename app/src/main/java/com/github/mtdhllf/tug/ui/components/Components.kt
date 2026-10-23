package com.github.mtdhllf.tug.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.ui.theme.Success
import com.github.mtdhllf.tug.ui.util.formatBytes
import com.github.mtdhllf.tug.ui.util.formatEta
import com.github.mtdhllf.tug.ui.util.formatPercent
import com.github.mtdhllf.tug.ui.util.formatSpeed
import com.github.mtdhllf.tug.ui.util.taskStatusLabel

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun StatusChip(state: TaskState, modifier: Modifier = Modifier) {
    val (container, content) = when (state) {
        TaskState.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        TaskState.Completed -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        TaskState.Paused -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        TaskState.Downloading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = container,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            taskStatusLabel(state),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun TaskProgress(
    task: TaskView,
    modifier: Modifier = Modifier,
) {
    val total = task.total
    val indeterminate = task.state == TaskState.Downloading &&
        (total == null || total <= 0L)
    if (indeterminate) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
        )
    } else {
        LinearProgressIndicator(
            progress = { task.progress },
            modifier = modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: TaskView,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onPauseResume: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = if (selectionMode) onLongClick else onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onLongClick() })
                Spacer(Modifier.width(4.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(task.state)
                }
                TaskProgress(task)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val percent = formatPercent(task.progress, task.total)
                    val speed = if (task.state == TaskState.Downloading) formatSpeed(task.downloadBps) else null
                    val eta = if (task.state == TaskState.Downloading) formatEta(task.etaSecs) else null
                    Text(
                        listOfNotNull(
                            "${formatBytes(task.downloaded)}/${task.total?.let(::formatBytes) ?: "?"}",
                            percent,
                            speed,
                            eta?.let { stringResource(R.string.remaining, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!selectionMode) {
                        if (task.state == TaskState.Completed) {
                            IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.OpenInNew, contentDescription = stringResource(R.string.content_open_file))
                            }
                        } else {
                            IconButton(onClick = onPauseResume, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (task.state == TaskState.Downloading || task.state == TaskState.Queued) {
                                        Icons.Outlined.Pause
                                    } else {
                                        Icons.Outlined.PlayArrow
                                    },
                                    contentDescription = stringResource(R.string.content_pause_resume),
                                )
                            }
                        }
                    }
                }
                val error = task.error
                if (!error.isNullOrBlank() && task.state == TaskState.Failed) {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmDialog(
    task: TaskView,
    onConfirm: (deleteFiles: Boolean) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.delete_task_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.delete_task_message, task.name))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickableSimp { deleteFiles = !deleteFiles },
                ) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Text(stringResource(R.string.delete_files_checkbox))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteFiles) }) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSimp(onClick: () -> Unit): Modifier = this.then(
    Modifier.combinedClickable(onClick = onClick, onLongClick = onClick),
)

/** 任务列表排序方式。 */
enum class TaskSort(@StringRes val labelRes: Int) {
    Recent(R.string.sort_recent),
    Speed(R.string.sort_speed),
    Name(R.string.sort_name),
}

/** 任务列表筛选。 */
enum class TaskFilter(@StringRes val labelRes: Int) {
    All(R.string.filter_all),
    Downloading(R.string.filter_downloading),
    Completed(R.string.filter_completed),
    Failed(R.string.filter_failed),
}

fun List<TaskView>.applyFilter(mode: TaskFilter): List<TaskView> = when (mode) {
    TaskFilter.All -> this
    TaskFilter.Downloading -> filter { it.state.isActive || it.state == TaskState.Paused }
    TaskFilter.Completed -> filter { it.state == TaskState.Completed || it.state == TaskState.Seeding }
    TaskFilter.Failed -> filter { it.state == TaskState.Failed }
}

fun List<TaskView>.applySort(sort: TaskSort): List<TaskView> = when (sort) {
    TaskSort.Recent -> sortedByDescending { it.createdAt }
    TaskSort.Speed -> sortedByDescending { it.downloadBps }
    TaskSort.Name -> sortedBy { it.name.lowercase() }
}

fun List<TaskView>.applySearch(query: String): List<TaskView> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return this
    return filter {
        it.name.contains(keyword, ignoreCase = true) ||
            it.url.contains(keyword, ignoreCase = true) ||
            (it.error?.contains(keyword, ignoreCase = true) == true)
    }
}
