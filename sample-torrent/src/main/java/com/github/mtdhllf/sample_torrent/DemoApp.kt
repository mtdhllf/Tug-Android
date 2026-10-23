package com.github.mtdhllf.sample_torrent

import android.app.Application
import android.os.Environment
import com.github.mtdhllf.tug.core.engine.torrent.TorrentDownloadEngine
import com.github.mtdhllf.tug.core.model.Settings
import com.github.mtdhllf.tug.core.store.InMemoryTaskStore
import com.github.mtdhllf.tug.core.store.TaskFileDeleter
import com.github.mtdhllf.tug.core.store.TaskManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BT Demo 初始化入口。
 *
 * 与 HTTP Demo 相同：引擎 + TaskStore + TaskManager；
 * 区别只是把 `"torrent"` 引擎注册进 TaskManager，
 * 由 TaskManager 根据 [com.github.mtdhllf.tug.core.model.TaskKind] 路由任务。
 */
class DemoApp : Application() {

    lateinit var taskManager: TaskManager
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val engine = TorrentDownloadEngine(
            workDir = File(filesDir, "torrent").apply { mkdirs() },
            scope = scope,
        )
        val downloadDir = File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
            "downloads",
        ).apply { mkdirs() }

        taskManager = TaskManager(
            store = InMemoryTaskStore(),
            engines = mapOf("torrent" to engine),
            scope = scope,
            defaultDownloadDir = downloadDir.absolutePath,
            initialSettings = Settings(maxConcurrentTasks = 2),
            deleter = TaskFileDeleter { task ->
                runCatching { File(task.path).delete() }
            },
        )

        scope.launch { taskManager.bootstrap() }
    }
}
