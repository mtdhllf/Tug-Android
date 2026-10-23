package com.github.mtdhllf.tug.core.store

import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView

/**
 * 任务持久化契约（纯 JVM）。
 *
 * 宿主可以接入 Room、SQLDelight、文件或内存实现；`TaskManager` 只依赖本接口，
 * 不感知 Android / 数据库。
 */
interface TaskStore {
    suspend fun list(): List<TaskView>

    suspend fun get(id: Long): TaskView?

    /** 新建任务并返回自增 id。 */
    suspend fun add(input: NewTask, saveDir: String, defaultName: String): Long

    /** 读取新建任务时的原始入参（用于恢复连接数等）。 */
    suspend fun payload(id: Long): NewTask?

    suspend fun updateState(
        id: Long,
        state: TaskState,
        error: String? = null,
        name: String? = null,
        fileName: String? = null,
    )

    suspend fun updateProgress(id: Long, downloaded: Long, total: Long?)

    /** 完成并发布到公共目录后回写最终位置。 */
    suspend fun updateLocation(id: Long, saveDir: String, fileName: String?, displayName: String?)

    suspend fun delete(id: Long)

    /** 启动恢复：把上次未完成的活动态改为暂停，返回受影响条数。 */
    suspend fun markInterrupted(): Int
}

/**
 * 任务文件删除钩子（由宿主注入）。
 *
 * 引擎只管理自己的暂存文件；已发布到公共目录（`content://`）的文件由 App 删除。
 */
fun interface TaskFileDeleter {
    suspend fun delete(task: TaskView)
}
