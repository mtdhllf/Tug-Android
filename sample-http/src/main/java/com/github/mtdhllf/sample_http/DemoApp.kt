package com.github.mtdhllf.sample_http

import android.app.Application
import android.os.Environment
import com.github.mtdhllf.tug.core.engine.api.DownloadEngine
import com.github.mtdhllf.tug.core.engine.http.HttpDownloadEngine
import com.github.mtdhllf.tug.core.engine.http.okhttp.OkHttpDownloadEngine
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
 * Demo 初始化入口（同时集成 JDK / OkHttp 两种 HTTP 实现）。
 *
 * 切换方式：
 * 1. 修改下方 [USE_OKHTTP] 常量：
 *    - false：`HttpDownloadEngine`（JDK HttpURLConnection，零三方 HTTP 依赖）
 *    - true ：`OkHttpDownloadEngine`（OkHttp 传输，适合项目已有 OkHttp 的场景）
 * 2. 也可以把 [createHttpEngine] 改成从设置/远程配置读取，运行时动态选择。
 */
class DemoApp : Application() {

    lateinit var taskManager: TaskManager
        private set

    /** 当前使用的 HTTP 实现名称，展示在 Demo 标题下。 */
    lateinit var engineName: String
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val useOkHttp = USE_OKHTTP
        val engine = createHttpEngine(useOkHttp)
        engineName = if (useOkHttp) "OkHttp Transport" else "JDK HttpURLConnection"

        val downloadDir = File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
            "downloads",
        ).apply { mkdirs() }

        taskManager = TaskManager(
            store = InMemoryTaskStore(),
            engines = mapOf("http" to engine),
            scope = scope,
            defaultDownloadDir = downloadDir.absolutePath,
            initialSettings = Settings(
                connectionsPerTask = 4,
                maxConcurrentTasks = 2,
            ),
            deleter = TaskFileDeleter { task ->
                runCatching { File(task.path).delete() }
            },
        )

        scope.launch { taskManager.bootstrap() }
    }

    private fun createHttpEngine(useOkHttp: Boolean): DownloadEngine =
        if (useOkHttp) {
            OkHttpDownloadEngine(scope = scope)
        } else {
            HttpDownloadEngine(scope = scope)
        }

    private companion object {
        // false = JDK HttpURLConnection；true = OkHttp。
        // 两种实现都已经在 build.gradle.kts 中依赖，切换只需改这一行。
        const val USE_OKHTTP = false
    }
}
