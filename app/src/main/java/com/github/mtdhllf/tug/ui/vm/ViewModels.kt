package com.github.mtdhllf.tug.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.core.engine.api.TorrentEngine
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.model.TorrentDetails
import com.github.mtdhllf.tug.core.store.sqlite.SettingsRepository
import com.github.mtdhllf.tug.core.store.TaskManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 首页数据。 */
class HomeViewModel(
    private val manager: TaskManager,
) : ViewModel() {
    val tasks: StateFlow<List<TaskView>> = manager.tasks
    val stats = manager.stats
}

/** 任务列表数据与操作。 */
class TasksViewModel(
    private val manager: TaskManager,
) : ViewModel() {
    val tasks: StateFlow<List<TaskView>> = manager.tasks

    val selectedIds: androidx.compose.runtime.snapshots.SnapshotStateList<Long> =
        androidx.compose.runtime.mutableStateListOf()

    fun toggleSelection(id: Long) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
    }

    fun clearSelection() = selectedIds.clear()

    fun pause(id: Long) = viewModelScope.launch { manager.pause(id) }

    fun resume(id: Long) = viewModelScope.launch { manager.resume(id) }

    fun remove(id: Long, deleteFiles: Boolean) =
        viewModelScope.launch { manager.remove(id, deleteFiles) }

    fun pauseAll() = viewModelScope.launch { manager.pauseAll() }

    fun resumeAll() = viewModelScope.launch { manager.resumeAll() }

    fun pauseSelected() = viewModelScope.launch {
        selectedIds.toList().forEach { manager.pause(it) }
        selectedIds.clear()
    }

    fun resumeSelected() = viewModelScope.launch {
        selectedIds.toList().forEach { manager.resume(it) }
        selectedIds.clear()
    }

    fun removeSelected(deleteFiles: Boolean) = viewModelScope.launch {
        selectedIds.toList().forEach { manager.remove(it, deleteFiles) }
        selectedIds.clear()
    }
}

/** 任务详情。 */
class TaskDetailViewModel(
    private val manager: TaskManager,
    private val taskId: Long,
    private val torrentEngine: TorrentEngine? = null,
) : ViewModel() {
    val task: StateFlow<TaskView?> = manager.tasks
        .map { list -> list.firstOrNull { it.id == taskId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pause() = viewModelScope.launch { manager.pause(taskId) }

    fun resume() = viewModelScope.launch { manager.resume(taskId) }

    fun remove(deleteFiles: Boolean) = viewModelScope.launch {
        manager.remove(taskId, deleteFiles)
    }

    fun torrentDetails(): TorrentDetails? = torrentEngine?.torrentDetails(taskId)
}

/** 新建任务表单。 */
class AddTaskViewModel(
    private val manager: TaskManager,
    private val settings: SettingsRepository,
    private val context: Context,
) : ViewModel() {

    var url: String = ""
        private set
    var fileName: String = ""
        private set
    var connections: Int = settings.settings.value.connectionsPerTask
        private set
    var error: String? = null
        private set
    var submitting: Boolean = false
        private set

    fun onUrlChange(value: String) {
        url = value
        error = null
    }

    fun onFileNameChange(value: String) {
        fileName = value
        error = null
    }

    fun onConnectionsChange(value: Int) {
        connections = value.coerceIn(1, 32)
    }

    /** 校验；返回错误文案或 null。 */
    fun validate(): String? {
        val target = url.trim()
        val message = when {
            target.isEmpty() -> context.getString(R.string.error_need_url)
            target.startsWith("http://", ignoreCase = true) ||
                target.startsWith("https://", ignoreCase = true) ||
                target.startsWith("magnet:", ignoreCase = true) -> null
            else -> context.getString(R.string.error_unsupported_url)
        }
        error = message
        return message
    }

    /** 提交；返回新任务 id，失败返回 null（错误写入 [error]）。 */
    suspend fun submit(): Long? {
        if (validate() != null) return null
        submitting = true
        return try {
            manager.add(
                NewTask(
                    url = url.trim(),
                    fileName = fileName.trim().takeIf { it.isNotEmpty() },
                    connections = connections,
                ),
            )
        } catch (t: Throwable) {
            error = t.message ?: context.getString(R.string.error_create_task)
            null
        } finally {
            submitting = false
        }
    }
}

/** 设置。 */
class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<com.github.mtdhllf.tug.core.model.Settings> = repository.settings

    fun setConnections(value: Int) = repository.setConnectionsPerTask(value)

    fun setConcurrent(value: Int) = repository.setMaxConcurrentTasks(value)

    fun setGlobalLimitKib(value: Long?) = repository.setGlobalDownloadLimitKib(value)

    fun setWifiOnly(value: Boolean) = repository.setWifiOnly(value)

    fun setBtChooseFilesBeforeStart(value: Boolean) =
        repository.setBtChooseFilesBeforeStart(value)

    fun setTheme(value: com.github.mtdhllf.tug.core.model.ThemeMode) = repository.setTheme(value)

    fun setDynamicColor(value: Boolean) = repository.setDynamicColor(value)
}

/** 统一 ViewModel 工厂（无 DI 框架，按 App 单例装配）。 */
class TugViewModelFactory(
    private val app: TugApp,
    private val taskId: Long = 0L,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(app.taskManager) as T
        modelClass.isAssignableFrom(TasksViewModel::class.java) ->
            TasksViewModel(app.taskManager) as T
        modelClass.isAssignableFrom(TaskDetailViewModel::class.java) ->
            TaskDetailViewModel(app.taskManager, taskId, app.torrentEngine) as T
        modelClass.isAssignableFrom(AddTaskViewModel::class.java) ->
            AddTaskViewModel(app.taskManager, app.settingsRepository, app) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(app.settingsRepository) as T
        else -> error("未知 ViewModel: ${modelClass.name}")
    }
}

