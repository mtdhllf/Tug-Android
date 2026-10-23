package com.github.mtdhllf.tug.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.ui.util.AppLocale
import com.github.mtdhllf.tug.core.model.ThemeMode
import com.github.mtdhllf.tug.storage.PublicDownloads
import com.github.mtdhllf.tug.ui.components.SectionCard
import com.github.mtdhllf.tug.ui.vm.SettingsViewModel
import com.github.mtdhllf.tug.ui.vm.TugViewModelFactory

enum class SettingsSection(
    val route: String,
    @StringRes val titleRes: Int,
) {
    Download("download", R.string.settings_download),
    Network("network", R.string.settings_network),
    Notifications("notifications", R.string.settings_notifications),
    Appearance("appearance", R.string.settings_appearance),
    Background("background", R.string.settings_background),
    About("about", R.string.settings_about),
}

@Composable
fun SettingsScreen(onSection: (SettingsSection) -> Unit) {
    val app = LocalContext.current.applicationContext as TugApp
    val viewModelFactory = remember { TugViewModelFactory(app) }
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationsEnabled = remember {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    var languageTag by remember { mutableStateOf(AppLocale.savedTag(context)) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        SectionCard(title = stringResource(R.string.settings_download_network)) {
            val limitLabel = settings.globalDownloadLimitKib?.let { "$it KiB/s" }
                ?: stringResource(R.string.limit_unlimited)
            SettingsEntry(
                title = stringResource(R.string.settings_download),
                subtitle = stringResource(
                    R.string.settings_download_summary,
                    settings.maxConcurrentTasks,
                    settings.connectionsPerTask,
                    limitLabel,
                ),
                onClick = { onSection(SettingsSection.Download) },
            )
            SettingsEntry(
                title = stringResource(R.string.settings_network),
                subtitle = stringResource(
                    if (settings.wifiOnly) {
                        R.string.settings_network_wifi_only
                    } else {
                        R.string.settings_network_mobile
                    },
                ),
                onClick = { onSection(SettingsSection.Network) },
            )
            SettingsEntry(
                title = stringResource(R.string.settings_notifications),
                subtitle = stringResource(
                    if (notificationsEnabled) {
                        R.string.settings_notifications_on
                    } else {
                        R.string.settings_notifications_off
                    },
                ),
                onClick = { onSection(SettingsSection.Notifications) },
            )
        }

        SectionCard(title = stringResource(R.string.settings_app)) {
            SettingsEntry(
                title = stringResource(R.string.settings_appearance),
                subtitle = stringResource(
                    R.string.settings_appearance_summary,
                    themeLabel(settings.theme),
                    if (settings.dynamicColor) {
                        stringResource(R.string.settings_dynamic_color_suffix)
                    } else {
                        ""
                    },
                ),
                onClick = { onSection(SettingsSection.Appearance) },
            )
            SettingsEntry(
                title = stringResource(R.string.settings_background),
                subtitle = stringResource(R.string.settings_background_summary),
                onClick = { onSection(SettingsSection.Background) },
            )
            SettingsEntry(
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_about_summary),
                onClick = { onSection(SettingsSection.About) },
            )
            SettingsEntry(
                title = stringResource(R.string.settings_language),
                subtitle = languageLabel(languageTag),
                onClick = { showLanguageDialog = true },
            )
        }

        Text(
            stringResource(R.string.settings_save_location, PublicDownloads.displayDir),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showLanguageDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language_dialog_title)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    listOf(
                        AppLocale.SYSTEM to stringResource(R.string.language_system),
                        AppLocale.ZH_CN to stringResource(R.string.language_zh),
                        AppLocale.ZH_TW to stringResource(R.string.language_zh_tw),
                        AppLocale.EN to stringResource(R.string.language_en),
                        AppLocale.ES to stringResource(R.string.language_es),
                        AppLocale.HI to stringResource(R.string.language_hi),
                        AppLocale.AR to stringResource(R.string.language_ar),
                    ).forEach { (tag, label) ->
                        TextButton(
                            onClick = {
                                languageTag = tag
                                showLanguageDialog = false
                                AppLocale.set(context, tag)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
fun SettingsSectionScreen(section: SettingsSection, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as TugApp
    val viewModelFactory = remember { TugViewModelFactory(app) }
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(stringResource(section.titleRes), style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (section) {
                SettingsSection.Download -> DownloadSettings(settingsViewModel, settings)
                SettingsSection.Network -> NetworkSettings(settingsViewModel, settings)
                SettingsSection.Notifications -> NotificationSettings()
                SettingsSection.Appearance -> AppearanceSettings(settingsViewModel, settings)
                SettingsSection.Background -> BackgroundSettings()
                SettingsSection.About -> AboutSettings()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DownloadSettings(viewModel: SettingsViewModel, settings: com.github.mtdhllf.tug.core.model.Settings) {
    SectionCard(title = stringResource(R.string.settings_concurrency)) {
        StepperRow(
            label = stringResource(R.string.settings_max_tasks),
            subtitle = stringResource(R.string.settings_max_tasks_subtitle),
            value = settings.maxConcurrentTasks,
            onChange = viewModel::setConcurrent,
        )
        HorizontalDivider()
        StepperRow(
            label = stringResource(R.string.settings_connections),
            subtitle = stringResource(R.string.settings_connections_subtitle),
            value = settings.connectionsPerTask,
            onChange = viewModel::setConnections,
        )
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.settings_bt_choose),
            subtitle = stringResource(R.string.settings_bt_choose_subtitle),
            checked = settings.btChooseFilesBeforeStart,
            onChange = viewModel::setBtChooseFilesBeforeStart,
        )
    }
    SectionCard(title = stringResource(R.string.settings_global_limit)) {
        Text(
            stringResource(R.string.settings_global_limit_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val options = listOf(
                null to stringResource(R.string.limit_unlimited),
                1024L to stringResource(R.string.limit_1mb),
                2048L to stringResource(R.string.limit_2mb),
                5120L to stringResource(R.string.limit_5mb),
            )
            options.forEach { (value, label) ->
                FilterChip(
                    selected = settings.globalDownloadLimitKib == value,
                    onClick = { viewModel.setGlobalLimitKib(value) },
                    label = { Text(label) },
                )
            }
        }
    }
    SectionCard(title = stringResource(R.string.settings_save_location_title)) {
        Text(
            PublicDownloads.displayDir,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.settings_save_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NetworkSettings(viewModel: SettingsViewModel, settings: com.github.mtdhllf.tug.core.model.Settings) {
    SectionCard(title = stringResource(R.string.settings_network)) {
        SwitchRow(
            title = stringResource(R.string.settings_wifi_only),
            subtitle = stringResource(R.string.settings_wifi_only_subtitle),
            checked = settings.wifiOnly,
            onChange = viewModel::setWifiOnly,
        )
        HorizontalDivider()
        Text(
            stringResource(R.string.settings_network_policy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotificationSettings() {
    val context = LocalContext.current
    val enabled = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    SectionCard(title = stringResource(R.string.settings_notifications)) {
        Text(
            stringResource(
                if (enabled) {
                    R.string.settings_notifications_enabled
                } else {
                    R.string.settings_notifications_disabled
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            },
        ) {
            Text(
                stringResource(
                    if (enabled) {
                        R.string.settings_open_notification_settings
                    } else {
                        R.string.settings_enable_notifications
                    },
                ),
            )
        }
    }
}

@Composable
private fun AppearanceSettings(
    viewModel: SettingsViewModel,
    settings: com.github.mtdhllf.tug.core.model.Settings,
) {
    SectionCard(title = stringResource(R.string.settings_theme)) {
        listOf(
            ThemeMode.System to stringResource(R.string.theme_system),
            ThemeMode.Light to stringResource(R.string.theme_light),
            ThemeMode.Dark to stringResource(R.string.theme_dark),
        ).forEach { (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setTheme(mode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.theme == mode,
                    onCheckedChange = { viewModel.setTheme(mode) },
                )
            }
        }
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            subtitle = stringResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    R.string.settings_dynamic_color_subtitle
                } else {
                    R.string.settings_dynamic_color_unsupported
                },
            ),
            checked = settings.dynamicColor,
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            onChange = viewModel::setDynamicColor,
        )
    }
}

@Composable
private fun BackgroundSettings() {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_background_title)) {
        Text(
            stringResource(R.string.settings_background_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                }
            },
        ) {
            Text(stringResource(R.string.settings_open_battery))
        }
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
        ) {
            Text(stringResource(R.string.settings_open_app))
        }
    }
}

@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    } ?: stringResource(R.string.common_unknown)
    val stagingDir = remember {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        java.io.File(base, "staging").absolutePath
    }
    SectionCard(title = stringResource(R.string.settings_version)) {
        Text("Tug for Android $versionName", style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_version_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SectionCard(title = stringResource(R.string.settings_system)) {
        Text(
            stringResource(
                R.string.settings_android_version,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.settings_abi,
                Build.SUPPORTED_ABIS.firstOrNull() ?: stringResource(R.string.common_unknown),
            ),            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SectionCard(title = stringResource(R.string.settings_storage)) {
        Text(
            stringResource(R.string.settings_download_dir, PublicDownloads.displayDir),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.settings_staging_dir, stagingDir),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
    SectionCard(title = stringResource(R.string.settings_engine)) {
        Text(
            stringResource(R.string.settings_http_engine),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.settings_bt_engine),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    SectionCard(title = stringResource(R.string.settings_open_source)) {
        Text(
            stringResource(R.string.settings_open_source_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    subtitle: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }) {
            Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.content_dec))
        }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(32)) }) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.content_inc))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.System -> R.string.theme_system
        ThemeMode.Light -> R.string.theme_light
        ThemeMode.Dark -> R.string.theme_dark
    },
)

@Composable
private fun languageLabel(tag: String): String = stringResource(
    when (tag) {
        AppLocale.ZH_CN -> R.string.language_zh
        AppLocale.ZH_TW -> R.string.language_zh_tw
        AppLocale.EN -> R.string.language_en
        AppLocale.ES -> R.string.language_es
        AppLocale.HI -> R.string.language_hi
        AppLocale.AR -> R.string.language_ar
        else -> R.string.language_system
    },
)
