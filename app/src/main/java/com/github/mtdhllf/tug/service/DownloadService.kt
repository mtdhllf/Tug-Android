package com.github.mtdhllf.tug.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.mtdhllf.tug.MainActivity
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.core.model.GlobalStats
import com.github.mtdhllf.tug.core.model.TaskEvent
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.model.TugLog
import com.github.mtdhllf.tug.core.model.TugTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 下载前台服务（dataSync）。
 *
 * - 仅在存在活动任务时运行，空闲 3s 自动停止（避免常驻通知）；
 * - API 35+ 前台服务超时（dataSync 6h/24h）时暂停任务并停止，避免被系统强杀；
 * - 进度通知显示聚合进度与速度。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastNotificationAt = 0L
    private var idleSince = 0L
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TugLog.i(TugTags.SVC, "DownloadService onCreate")
        createChannels()
        startAsForeground(getString(R.string.notification_preparing))
        observeTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pauseAll()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_ALL -> pauseAll()
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        // API 35+：dataSync 前台服务达到系统时限
        TugLog.w(TugTags.SVC, "前台服务超时，暂停任务并停止服务 startId=$startId")
        pauseAll()
    }

    override fun onDestroy() {
        TugLog.i(TugTags.SVC, "DownloadService onDestroy")
        scope.cancel()
        super.onDestroy()
    }

    private fun pauseAll() {
        val app = application as TugApp
        scope.launch {
            runCatching { app.taskManager.pauseAll() }
            stopForegroundNow()
        }
    }

    private fun observeTasks() {
        val app = application as TugApp
        scope.launch {
            app.taskManager.tasks.collect { list ->
                val active = list.filter { it.state.isActive }
                val stats = app.taskManager.globalStats()
                updateNotification(active, stats, list)
                if (active.isEmpty()) {
                    val now = System.currentTimeMillis()
                    if (idleSince == 0L) {
                        idleSince = now
                    } else if (now - idleSince >= IDLE_STOP_MS) {
                        TugLog.i(TugTags.SVC, "空闲 ${IDLE_STOP_MS}ms，停止前台服务")
                        stopForegroundNow()
                    }
                } else {
                    idleSince = 0L
                }
            }
        }
        scope.launch {
            app.taskManager.events.collect { event ->
                when (event) {
                    is TaskEvent.Completed -> notifyFinished(
                        event.id,
                        getString(R.string.notification_completed),
                        event.name,
                        completed = true,
                    )
                    is TaskEvent.Failed -> notifyFinished(
                        event.id,
                        getString(R.string.notification_failed),
                        event.message,
                        completed = false,
                    )
                    else -> Unit
                }
            }
        }
    }

    private fun updateNotification(
        active: List<TaskView>,
        stats: GlobalStats,
        all: List<TaskView>,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFY_INTERVAL_MS) return
        lastNotificationAt = now

        val totalKnown = active.sumOf { it.total ?: 0L }
        val downloaded = active.sumOf { it.downloaded }
        val indeterminate = active.isEmpty() || active.any { it.total == null } || totalKnown <= 0L
        val percent = if (!indeterminate) {
            ((downloaded.toDouble() / totalKnown) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val running = all.count { it.state == TaskState.Downloading }
        val current = active.firstOrNull { it.state == TaskState.Downloading }
        val currentTotal = current?.total
        val text = when {
            active.isEmpty() -> getString(R.string.notification_service_running)
            current != null && currentTotal != null && currentTotal > 0 -> {
                val currentPercent = (current.downloaded * 100 / currentTotal).toInt()
                "${current.name} · $currentPercent% · ${formatSpeed(stats.downloadBps)}" +
                    if (active.size > 1) {
                        getString(R.string.notification_tasks_count, active.size)
                    } else {
                        ""
                    }
            }
            running > 0 -> getString(
                R.string.notification_active,
                active.size,
                formatSpeed(stats.downloadBps),
            ) + if (!indeterminate) " · $percent%" else ""
            else -> getString(R.string.notification_queued, active.size)
        }
        val builder = baseNotification(text).apply {
            if (!indeterminate) setProgress(100, percent, false) else setProgress(0, 0, true)
            if (active.isNotEmpty()) {
                addAction(
                    0,
                    getString(R.string.notification_pause_all),
                    PendingIntent.getService(
                        this@DownloadService,
                        1,
                        Intent(this@DownloadService, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        startForegroundSafely(NOTIFICATION_ID, builder.build(), ongoing = true)
    }

    private fun notifyFinished(taskId: Long, title: String, text: String, completed: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(
                if (completed) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(taskId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(taskId.toInt().coerceAtLeast(100), notification)
        }
    }

    private fun contentIntent(taskId: Long? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (taskId != null) putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getActivity(
            this,
            taskId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun baseNotification(text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)

    private fun startAsForeground(text: String) {
        startForegroundSafely(NOTIFICATION_ID, baseNotification(text).build(), ongoing = true)
    }

    private fun startForegroundSafely(id: Int, notification: Notification, ongoing: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
        foregroundStarted = true
    }

    private fun stopForegroundNow() {
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_progress_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                getString(R.string.channel_done),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.channel_done_desc)
            },
        )
    }

    private fun formatSpeed(bps: Long): String {
        if (bps <= 0) return "0 B/s"
        val kb = bps / 1024.0
        return if (kb < 1024) String.format("%.1f KB/s", kb)
        else String.format("%.2f MB/s", kb / 1024.0)
    }

    companion object {
        const val CHANNEL_PROGRESS = "tug_download_progress"
        const val CHANNEL_DONE = "tug_download_done"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.github.mtdhllf.tug.action.STOP"
        const val ACTION_PAUSE_ALL = "com.github.mtdhllf.tug.action.PAUSE_ALL"
        const val EXTRA_TASK_ID = "task_id"

        private const val IDLE_STOP_MS = 3_000L
        private const val NOTIFY_INTERVAL_MS = 900L

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
