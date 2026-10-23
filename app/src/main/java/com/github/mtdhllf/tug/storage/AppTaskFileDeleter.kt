package com.github.mtdhllf.tug.storage

import android.content.Context
import android.net.Uri
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.store.TaskFileDeleter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 删除任务关联文件：同时处理已发布到公共目录（`content://`）与私有暂存区的文件。
 */
class AppTaskFileDeleter(private val context: Context) : TaskFileDeleter {

    override suspend fun delete(task: TaskView) = withContext(Dispatchers.IO) {
        val path = task.path
        if (path.startsWith("content://")) {
            runCatching { context.contentResolver.delete(Uri.parse(path), null, null) }
        } else {
            runCatching { File(path).delete() }
        }

        // 私有暂存区的边车文件（.part / meta / meta.tmp）
        if (!task.saveDir.startsWith("content://")) {
            val base = File(task.saveDir, task.fileName ?: task.name)
            SIDECAR_SUFFIXES.forEach { suffix ->
                runCatching { File(base.path + suffix).delete() }
            }
        }
    }

    private companion object {
        val SIDECAR_SUFFIXES = listOf(".part", ".tug.json", ".tug.json.tmp")
    }
}
