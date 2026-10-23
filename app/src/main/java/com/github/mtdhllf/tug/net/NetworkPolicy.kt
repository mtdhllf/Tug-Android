package com.github.mtdhllf.tug.net

import com.github.mtdhllf.tug.core.model.Settings
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TugLog
import com.github.mtdhllf.tug.core.store.sqlite.SettingsRepository
import com.github.mtdhllf.tug.core.store.TaskManager
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 纯函数网络策略（便于 JVM 单测）。 */
object NetworkRules {
    fun allowed(online: Boolean, wifi: Boolean, wifiOnly: Boolean): Boolean =
        online && (!wifiOnly || wifi)
}

/**
 * 网络策略：
 * - 断网或"仅 Wi-Fi"不满足时，暂停正在下载/排队的任务；
 * - 条件恢复后，只恢复由策略暂停的任务（不打扰用户手动暂停）。
 */
class NetworkPolicy(
    private val taskManager: TaskManager,
    private val settingsRepository: SettingsRepository,
    private val monitor: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val pausedByPolicy: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    fun start() {
        monitor.start()
        scope.launch {
            combine(monitor.state, settingsRepository.settings) { network, settings ->
                Decision(network, settings)
            }
                .distinctUntilChanged()
                .collect { decision -> apply(decision) }
        }
    }

    private suspend fun apply(decision: Decision) {
        val allowed = NetworkRules.allowed(
            online = decision.network.online,
            wifi = decision.network.wifi,
            wifiOnly = decision.settings.wifiOnly,
        )
        if (!allowed) {
            val toPause = taskManager.tasks.value.filter {
                it.state == TaskState.Downloading || it.state == TaskState.Queued
            }
            for (task in toPause) {
                pausedByPolicy.add(task.id)
                runCatching { taskManager.pause(task.id) }
            }
            if (toPause.isNotEmpty()) {
                TugLog.i(
                    "TugNet",
                    "网络不满足下载条件，暂停 ${toPause.size} 个任务 " +
                        "(online=${decision.network.online} wifi=${decision.network.wifi} " +
                        "wifiOnly=${decision.settings.wifiOnly})",
                )
            }
        } else {
            val toResume = synchronized(pausedByPolicy) {
                pausedByPolicy.toList().also { pausedByPolicy.clear() }
            }
            toResume.forEach { id -> runCatching { taskManager.resume(id) } }
            if (toResume.isNotEmpty()) {
                TugLog.i("TugNet", "网络恢复，继续 ${toResume.size} 个任务")
            }
        }
    }

    private data class Decision(
        val network: NetworkState,
        val settings: Settings,
    )
}

