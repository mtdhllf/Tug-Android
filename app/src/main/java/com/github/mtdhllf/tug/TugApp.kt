package com.github.mtdhllf.tug

import android.app.Application
import android.os.Environment
import com.github.mtdhllf.tug.core.engine.api.DownloadEngine
import com.github.mtdhllf.tug.core.engine.api.TorrentEngine
import com.github.mtdhllf.tug.core.engine.http.HttpDownloadEngine
import com.github.mtdhllf.tug.core.engine.torrent.TorrentDownloadEngine
import com.github.mtdhllf.tug.core.model.TaskEvent
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TugLog
import com.github.mtdhllf.tug.core.store.sqlite.SettingsRepository
import com.github.mtdhllf.tug.core.store.sqlite.SqliteTaskStore
import com.github.mtdhllf.tug.core.store.TaskManager
import com.github.mtdhllf.tug.net.NetworkMonitor
import com.github.mtdhllf.tug.net.NetworkPolicy
import com.github.mtdhllf.tug.power.DownloadKeepAlive
import com.github.mtdhllf.tug.storage.AppTaskFileDeleter
import com.github.mtdhllf.tug.storage.OrphanFiles
import com.github.mtdhllf.tug.storage.PublicDownloads
import com.github.mtdhllf.tug.storage.PublishResult
import com.github.mtdhllf.tug.ui.util.AppLocale
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TugApp : Application() {
    lateinit var taskManager: TaskManager
        private set

    lateinit var torrentEngine: TorrentEngine
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    /** 其他应用分享/打开进来的链接，等待 UI 消费。 */
    val pendingShare = MutableStateFlow<String?>(null)

    /** `tug://task/{id}` 深链待跳转的任务。 */
    val pendingTaskId = MutableStateFlow<Long?>(null)

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t ->
                android.util.Log.e("TugApp", "uncaught coroutine error", t)
            },
    )

    override fun onCreate() {
        super.onCreate()
        AppLocale.restore(this)
        TugLog.sink = { level, tag, message, error ->
            when (level) {
                "E" -> android.util.Log.e(tag, message, error)
                "W" -> android.util.Log.w(tag, message, error)
                "I" -> android.util.Log.i(tag, message)
                "V" -> android.util.Log.v(tag, message)
                else -> android.util.Log.d(tag, message)
            }
        }
        android.util.Log.i("TugApp", "onCreate pid=${android.os.Process.myPid()}")

        val stagingDir = File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
            "staging",
        ).apply { mkdirs() }

        settingsRepository = SettingsRepository(this)
        val store = SqliteTaskStore(this)
        val btEngine = TorrentDownloadEngine(
            workDir = File(filesDir, "torrent").apply { mkdirs() },
            scope = appScope,
        )
        torrentEngine = btEngine
        val engines: Map<String, DownloadEngine> = mapOf(
            "http" to HttpDownloadEngine(scope = appScope),
            "torrent" to btEngine,
        )
        taskManager = TaskManager(
            store = store,
            engines = engines,
            scope = appScope,
            defaultDownloadDir = stagingDir.absolutePath,
            initialSettings = settingsRepository.settings.value,
            deleter = AppTaskFileDeleter(this),
        )

        // 设置变更 → 并发/连接数/全局限速即时生效
        appScope.launch {
            settingsRepository.settings.collect { settings -> taskManager.applySettings(settings) }
        }

        // 网络策略：断网 / 仅 Wi-Fi 不满足时暂停，条件恢复后继续
        val networkMonitor = NetworkMonitor(this)
        NetworkPolicy(
            taskManager = taskManager,
            settingsRepository = settingsRepository,
            monitor = networkMonitor,
            scope = appScope,
        ).start()

        // 下载期间持有 CPU 唤醒锁，空闲释放
        val keepAlive = DownloadKeepAlive(this)
        appScope.launch {
            taskManager.tasks.collect { tasks ->
                val active = tasks.any {
                    it.state == TaskState.Downloading ||
                        it.state == TaskState.Queued ||
                        it.state == TaskState.FetchingMetadata
                }
                keepAlive.onActiveChanged(active)
            }
        }

        // 完成 → 发布到公共下载目录（Download/Tug）
        appScope.launch {
            taskManager.events.collect { event ->
                if (event is TaskEvent.Completed) {
                    publishCompleted(event)
                }
            }
        }

        // 启动恢复 + 孤儿文件清理
        appScope.launch {
            taskManager.bootstrap()
            runCatching {
                val removed = withContext(Dispatchers.IO) {
                    OrphanFiles.clean(stagingDir, taskManager.list())
                }
                if (removed > 0) android.util.Log.i("TugApp", "清理孤儿边车文件 $removed 个")
            }.onFailure { android.util.Log.w("TugApp", "清理孤儿文件失败: ${it.message}") }
        }

        // 不在 Application.onCreate 启动 FGS：Android 12+ 会 ForegroundServiceStartNotAllowedException
        // 前台服务在用户开始/继续下载时再启动
    }

    private suspend fun publishCompleted(event: TaskEvent.Completed) {
        val source = File(event.path)
        val isDirectory = source.isDirectory
        val result = withContext(Dispatchers.IO) {
            if (isDirectory) {
                PublicDownloads.publishDirectory(this@TugApp, source, event.name)
            } else {
                PublicDownloads.publish(this@TugApp, source, event.name)
            }
        }
        when (result) {
            is PublishResult.Success -> {
                android.util.Log.i("TugApp", "已发布到 ${PublicDownloads.displayDir}/${result.fileName}")
                taskManager.markPublished(
                    id = event.id,
                    saveDir = result.location,
                    fileName = result.fileName,
                    displayName = if (isDirectory) event.name else result.fileName,
                )
            }
            is PublishResult.Failure -> {
                android.util.Log.w("TugApp", "发布失败: ${result.message}")
                taskManager.markPublishFailed(event.id, result.message)
            }
        }
    }

    companion object {
        /** 通知/深链携带任务 id。 */
        const val EXTRA_TASK_ID = "task_id"
    }
}

