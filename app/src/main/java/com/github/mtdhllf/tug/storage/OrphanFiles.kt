package com.github.mtdhllf.tug.storage

import com.github.mtdhllf.tug.core.model.TaskView
import java.io.File

/**
 * 启动时清理暂存区里的孤儿文件：
 * 数据库里已不存在的任务所遗留的 `.part` / meta / meta.tmp。
 *
 * 只删除边车文件，不碰已下载的最终文件，避免误删。
 */
object OrphanFiles {
    private val SIDECAR_SUFFIXES = listOf(".part", ".tug.json", ".tug.json.tmp")

    fun clean(stagingDir: File, tasks: List<TaskView>): Int {
        val expected = tasks.asSequence()
            .filter { it.saveDir == stagingDir.absolutePath }
            .mapNotNull { it.fileName ?: it.name.takeIf { name -> name.isNotBlank() } }
            .flatMap { name -> SIDECAR_SUFFIXES.map { suffix -> name + suffix } }
            .toSet()

        var removed = 0
        stagingDir.listFiles()?.forEach { file ->
            val isSidecar = SIDECAR_SUFFIXES.any { file.name.endsWith(it) }
            if (isSidecar && file.name !in expected) {
                if (file.delete()) removed++
            }
        }
        return removed
    }
}
