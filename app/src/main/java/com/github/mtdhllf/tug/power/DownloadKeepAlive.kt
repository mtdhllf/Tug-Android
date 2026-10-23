package com.github.mtdhllf.tug.power

import android.content.Context
import android.os.PowerManager

/**
 * 下载期间的 CPU 唤醒锁。
 *
 * 前台服务只能保证进程优先级，不能保证 CPU 不休眠；下载期间持有 PARTIAL_WAKE_LOCK，
 * 空闲时立即释放。带超时兜底，避免异常路径导致锁泄漏。
 */
class DownloadKeepAlive(context: Context) {
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    val isHeld: Boolean
        get() = wakeLock?.isHeld == true

    fun onActiveChanged(active: Boolean) {
        if (active) acquire() else release()
    }

    private fun acquire() {
        if (isHeld) return
        val manager = powerManager ?: return
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Tug:download",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun release() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
    }
}
