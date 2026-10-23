package com.github.mtdhllf.tug.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf

/** 全局 Snackbar，供各页面统一反馈。 */
val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState?> { null }
