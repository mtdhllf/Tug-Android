package com.github.mtdhllf.tug.sample

import com.github.mtdhllf.tug.core.engine.api.EngineEvent
import com.github.mtdhllf.tug.core.engine.http.HttpDownloadEngine
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 最小可复用示例：只依赖 `tug-model` + `tug-engine-api` + `tug-http` 三个纯 JVM 模块。
 *
 * 用法：
 *   ./gradlew :samples:jvm-downloader:run --args="<url> [目录] [连接数]"
 */
fun main(args: Array<String>) {
    val url = args.getOrNull(0)
    if (url.isNullOrBlank()) {
        System.err.println("用法: jvm-downloader <url> [目录] [连接数]")
        exitProcess(2)
    }
    val dir = File(args.getOrNull(1) ?: "downloads")
    val connections = args.getOrNull(2)?.toIntOrNull() ?: 4
    val ok = runBlocking { download(url, dir, connections) }
    exitProcess(if (ok) 0 else 1)
}

private suspend fun download(url: String, dir: File, connections: Int): Boolean {
    dir.mkdirs()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = HttpDownloadEngine(scope = scope)
    val taskId = 1L

    val terminal = scope.async {
        engine.events()
            .filterIsInstance<EngineEvent.State>()
            .first {
                it.taskId == taskId &&
                    (it.state == TaskState.Completed || it.state == TaskState.Failed)
            }
    }
    val renderer = scope.launch {
        var lastPrint = 0L
        engine.liveTasks().collect { map ->
            val task = map[taskId] ?: return@collect
            val now = System.currentTimeMillis()
            if (now - lastPrint < 200) return@collect
            lastPrint = now
            val percent = task.total?.takeIf { it > 0 }?.let {
                (task.downloaded * 100 / it).coerceIn(0, 100)
            }
            print(
                "\r${percent?.let { "$it%" } ?: "--"} " +
                    "${format(task.downloaded)}/${task.total?.let(::format) ?: "?"} " +
                    "${format(task.downloadBps)}/s " +
                    "eta=${task.etaSecs?.let { "${it}s" } ?: "--"}   ",
            )
            System.out.flush()
        }
    }

    return try {
        engine.start(taskId, NewTask(url = url, connections = connections), dir.absolutePath)
        val result = terminal.await()
        renderer.cancel()
        println()
        when (result.state) {
            TaskState.Completed -> {
                println("完成：${File(dir, "${result.fileName ?: "download.bin"}").absolutePath}")
                true
            }
            else -> {
                System.err.println("失败：${result.error ?: "未知错误"}")
                false
            }
        }
    } finally {
        renderer.cancel()
        engine.shutdown()
        scope.cancel()
    }
}

private fun format(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.2f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
