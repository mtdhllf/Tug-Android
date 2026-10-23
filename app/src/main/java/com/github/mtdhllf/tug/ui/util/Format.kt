package com.github.mtdhllf.tug.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.core.model.TaskState
import java.util.Locale

/** 字节数格式化（B / KB / MB / GB）。 */
fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val kb = value / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

/** 速度格式化（B/s、KB/s、MB/s）。 */
fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0L -> "0 B/s"
    else -> "${formatBytes(bytesPerSecond)}/s"
}

/** 剩余时间格式化；未知返回 "--"。 */
fun formatEta(seconds: Long?): String {
    if (seconds == null || seconds <= 0L) return "--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 任务状态资源 ID。 */
fun taskStatusRes(state: TaskState): Int = when (state) {
    TaskState.Queued -> R.string.state_queued
    TaskState.FetchingMetadata -> R.string.state_fetching
    TaskState.Downloading -> R.string.state_downloading
    TaskState.Paused -> R.string.state_paused
    TaskState.Seeding -> R.string.state_seeding
    TaskState.Completed -> R.string.state_completed
    TaskState.Failed -> R.string.state_failed
}

/** 任务状态文案。 */
@Composable
fun taskStatusLabel(state: TaskState): String = stringResource(taskStatusRes(state))

/** 进度百分比文案；未知总大小返回 "--"。 */
fun formatPercent(progress: Float, total: Long?): String =
    if (total == null || total <= 0L) "--" else "${(progress * 100).toInt()}%"
