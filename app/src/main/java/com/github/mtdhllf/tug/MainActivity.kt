package com.github.mtdhllf.tug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.mtdhllf.tug.core.model.ThemeMode
import com.github.mtdhllf.tug.ui.TugRoot
import com.github.mtdhllf.tug.ui.theme.TugTheme

class MainActivity : AppCompatActivity() {
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()
        handleIntent(intent)
        setContent {
            val app = application as TugApp
            val settings by app.settingsRepository.settings.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.theme) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            TugTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                TugRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 处理"分享进来的链接"与 `tug://task/{id}` 深链。 */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val app = application as TugApp
        // 通知点击：携带任务 id，直接打开详情
        val notificationTaskId = intent.getLongExtra(TugApp.EXTRA_TASK_ID, -1L)
        if (notificationTaskId > 0L) {
            app.pendingTaskId.value = notificationTaskId
            return
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                if (!text.isNullOrEmpty()) {
                    app.pendingShare.value = text
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "tug" && data.host == "task") {
                    data.lastPathSegment?.toLongOrNull()?.let { app.pendingTaskId.value = it }
                } else {
                    app.pendingShare.value = data.toString()
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // 通知权限在用户开始下载时再申请（见 rememberNotificationPermissionRequest）
        // 前台服务只在用户开始/继续下载时启动（Android 12+ 后台启动 FGS 会被拒绝）
    }
}
