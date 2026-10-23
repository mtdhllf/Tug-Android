package com.github.mtdhllf.tug.core.engine.api

import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.model.TorrentDetails
import com.github.mtdhllf.tug.core.model.TorrentMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 下载引擎抽象。
 *
 * HTTP 与 BT 各自实现本接口；上层 TaskManager 不感知协议细节，
 * 便于后续启用 engine-torrent。
 */
public interface DownloadEngine {
    /** 本引擎处理的任务类型（http / torrent）。 */
    public val kind: String

    /** 引擎状态事件流（低频、可靠；进度请读 [LiveProgressSource.liveTasks]）。 */
    public fun events(): Flow<EngineEvent>

    /**
     * 启动任务：完成探测/规划后返回，**不等待下载结束**。
     *
     * 下载结果通过 [events] 的 [EngineEvent.State]（Completed/Failed/Paused）上报；
     * 探测阶段的致命错误可直接抛出，由调用方决定如何落库。
     */
    public suspend fun start(taskId: Long, input: NewTask, saveDir: String)

    /** 暂停：取消在途请求并等待 worker 退出（含 checkpoint），返回后不再写盘。 */
    public suspend fun pause(taskId: Long)

    public suspend fun resume(taskId: Long)

    public suspend fun remove(taskId: Long, deleteFiles: Boolean)

    /** 当前运行中的实时视图；未在本引擎运行则返回 null。 */
    public fun snapshot(taskId: Long): TaskView?

    /** 全局下载限速（字节/秒）；`null` 表示不限速。默认实现为空。 */
    public fun setDownloadRateLimit(bytesPerSecond: Long?) {
        // 不支持限速的引擎可忽略
    }

    public fun shutdown()
}

/** 引擎层事件（状态变化；进度走 [LiveProgressSource]）。 */
public sealed class EngineEvent {
    public data class Progress(
        val taskId: Long,
        val downloaded: Long,
        val total: Long?,
        val downloadBps: Long,
        val uploadBps: Long = 0L,
        val peers: Int = 0,
        val etaSecs: Long? = null,
    ) : EngineEvent()

    public data class State(
        val taskId: Long,
        val state: TaskState,
        val name: String? = null,
        val fileName: String? = null,
        val error: String? = null,
    ) : EngineEvent()
}

/** 引擎工厂：按 kind 选择实现，BT 未启用时返回 null。 */
public interface EngineRegistry {
    public fun engine(kind: String): DownloadEngine?

    public fun engines(): List<DownloadEngine>
}

/**
 * 可提供高频实时视图的引擎（HTTP 等）。
 *
 * UI/TaskManager 优先读这里的 StateFlow，避免每条 Progress 都打库。
 */
public interface LiveProgressSource {
    public fun liveTasks(): StateFlow<Map<Long, TaskView>>
}

/** BT 引擎扩展：入队前解析元数据 + 运行详情。 */
public interface TorrentEngine : DownloadEngine {
    /** 解析 magnet/.torrent URL/本地种子字节，返回文件列表供用户勾选。 */
    public suspend fun parse(input: NewTask): TorrentMetadata

    /** 运行中任务的 Files/Peers/Trackers；不在运行则返回 null。 */
    public fun torrentDetails(taskId: Long): TorrentDetails?
}
