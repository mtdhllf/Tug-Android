package com.github.mtdhllf.tug.core.model

import kotlinx.serialization.Serializable

/** 任务类型。 */
@Serializable
public enum class TaskKind {
    Http,
    Torrent,
}

/** 任务状态，与桌面端语义对齐。 */
@Serializable
public enum class TaskState {
    Queued,
    FetchingMetadata,
    Downloading,
    Paused,
    Seeding,
    Completed,
    Failed,
    ;

    public val isActive: Boolean
        get() = this == Queued || this == FetchingMetadata ||
            this == Downloading || this == Seeding
}

/** 新建任务入参。 */
@Serializable
public data class NewTask(
    val url: String,
    val saveDir: String? = null,
    val fileName: String? = null,
    val connections: Int? = null,
    val onlyFiles: List<Int>? = null,
    /** 任务类型；默认按 URL 识别（见 [kindFor]）。 */
    val kind: TaskKind = TaskKind.Http,
    /** 本地 `.torrent` 文件内容（通过文件选择器导入时使用）。 */
    val torrentBytes: ByteArray? = null,
)

/** 根据 URL 判断任务类型：magnet / `.torrent` URL 走 BitTorrent。 */
public fun kindFor(url: String): TaskKind {
    val target = url.trim()
    return when {
        target.startsWith("magnet:", ignoreCase = true) -> TaskKind.Torrent
        target.substringBefore('?').endsWith(".torrent", ignoreCase = true) -> TaskKind.Torrent
        else -> TaskKind.Http
    }
}

/** 任务视图（UI / 事件共用）。 */
@Serializable
public data class TaskView(
    val id: Long,
    val kind: TaskKind,
    val name: String,
    val url: String,
    val saveDir: String,
    val fileName: String? = null,
    val state: TaskState,
    val total: Long? = null,
    val downloaded: Long = 0L,
    val uploaded: Long = 0L,
    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L,
    val peers: Int = 0,
    val etaSecs: Long? = null,
    val error: String? = null,
    val connections: Int = 8,
    val rateLimitBps: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    public val progress: Float
        get() = when {
            state == TaskState.Completed -> 1f
            total == null || total <= 0L -> 0f
            else -> (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * 最终可打开的路径。
     *
     * - 已完成并发布到公共目录时，[saveDir] 为 `content://` URI 或公共目录路径；
     * - 下载中 [saveDir] 为私有暂存目录，按目录 + 文件名拼接。
     */
    public val path: String
        get() = if (saveDir.startsWith("content://")) {
            saveDir
        } else {
            java.io.File(saveDir, fileName ?: name).absolutePath
        }
}

/** 种子内文件。 */
public data class TorrentFileEntry(
    val index: Int,
    val path: String,
    val size: Long,
    val selected: Boolean = true,
)

/** 种子 Peer。 */
public data class TorrentPeerEntry(
    val address: String,
    val client: String,
    val downBps: Long,
    val upBps: Long,
    val progress: Float,
)

/** 种子 Tracker。 */
public data class TorrentTrackerEntry(
    val url: String,
    val tier: Int,
    val verified: Boolean,
)

/** BT 解析结果（入队前预览/文件选择用）。 */
public data class TorrentMetadata(
    val name: String,
    val totalSize: Long,
    val files: List<TorrentFileEntry>,
    val torrentBytes: ByteArray?,
) {
    public val singleFile: Boolean
        get() = files.size <= 1
}

/** BT 运行详情（详情页 Files/Peers/Trackers）。 */
public data class TorrentDetails(
    val infoHash: String,
    val files: List<TorrentFileEntry>,
    val peers: List<TorrentPeerEntry>,
    val trackers: List<TorrentTrackerEntry>,
)

/** 全局统计。 */
@Serializable
public data class GlobalStats(    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L,
    val activeTasks: Int = 0,
    val totalTasks: Int = 0,
    val completedTasks: Int = 0,
)

/** 用户设置。 */
@Serializable
public data class Settings(
    var downloadDir: String = "",
    var connectionsPerTask: Int = 8,
    var maxConcurrentTasks: Int = 3,
    /** 全局限速（KiB/s）：null 表示不限速。 */
    var globalDownloadLimitKib: Long? = null,
    var wifiOnly: Boolean = false,
    var theme: ThemeMode = ThemeMode.System,
    var dynamicColor: Boolean = true,
    var notifyOnComplete: Boolean = true,
    var btDht: Boolean = true,
    var btListenPort: Int? = null,
    var btUploadLimitKib: Long? = null,
    var btSeedAfterComplete: Boolean = true,
    /** BT 下载前先解析并让用户选择文件；默认关闭（直接入队并下载全部文件）。 */
    var btChooseFilesBeforeStart: Boolean = false,
)

@Serializable
public enum class ThemeMode {
    System,
    Light,
    Dark,
}
