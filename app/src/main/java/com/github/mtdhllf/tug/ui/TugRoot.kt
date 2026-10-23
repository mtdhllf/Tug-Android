package com.github.mtdhllf.tug.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.ui.screens.AddTaskScreen
import com.github.mtdhllf.tug.ui.screens.HomeScreen
import com.github.mtdhllf.tug.ui.screens.SettingsScreen
import com.github.mtdhllf.tug.ui.screens.SettingsSection
import com.github.mtdhllf.tug.ui.screens.SettingsSectionScreen
import com.github.mtdhllf.tug.ui.screens.TaskDetailScreen
import com.github.mtdhllf.tug.ui.screens.TasksScreen

private object Routes {
    const val HOME = "home"
    const val TASKS = "tasks"
    const val SETTINGS = "settings"
    const val TASK_DETAIL = "task/{taskId}"
    const val SETTINGS_SECTION = "settings/{section}"

    fun taskDetail(id: Long): String = "task/$id"

    fun settingsSection(section: SettingsSection): String = "settings/${section.route}"
}

private data class BottomDest(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDest(Routes.HOME, R.string.nav_home, Icons.Outlined.Dashboard),
    BottomDest(Routes.TASKS, R.string.nav_tasks, Icons.Outlined.Download),
    BottomDest(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

@Composable
fun TugRoot() {
    val app = LocalContext.current.applicationContext as TugApp
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var addInitialUrl by rememberSaveable { mutableStateOf("") }

    fun openAdd(url: String? = null) {
        addInitialUrl = url.orEmpty()
        showAddSheet = true
    }

    // 其他应用分享/打开进来的链接 → 打开新建面板并预填
    val pendingShare by app.pendingShare.collectAsState()
    LaunchedEffect(pendingShare) {
        val url = pendingShare ?: return@LaunchedEffect
        app.pendingShare.value = null
        openAdd(url)
    }

    // tug://task/{id} 深链 → 直达任务详情
    val pendingTaskId by app.pendingTaskId.collectAsState()
    LaunchedEffect(pendingTaskId) {
        val id = pendingTaskId ?: return@LaunchedEffect
        app.pendingTaskId.value = null
        navController.navigate(Routes.taskDetail(id))
    }

    val selectedRoute = when {
        currentRoute == Routes.HOME -> Routes.HOME
        currentRoute == Routes.TASKS -> Routes.TASKS
        currentRoute == Routes.SETTINGS || currentRoute == Routes.SETTINGS_SECTION -> Routes.SETTINGS
        else -> null
    }
    val showChrome = selectedRoute != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showChrome) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                ) {
                    bottomDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = selectedRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(dest.icon, contentDescription = stringResource(dest.labelRes))
                            },
                            label = { Text(stringResource(dest.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == Routes.HOME || currentRoute == Routes.TASKS) {
                ExtendedFloatingActionButton(
                    onClick = { openAdd() },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.fab_new)) },
                )
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
            Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.HOME,
                    modifier = Modifier.padding(padding),
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onAdd = { openAdd() },
                            onTorrentAdd = { url -> openAdd(url) },
                            onTasks = { navController.navigate(Routes.TASKS) },
                            onTaskClick = { id -> navController.navigate(Routes.taskDetail(id)) },
                        )
                    }
                    composable(Routes.TASKS) {
                        TasksScreen(
                            onTaskClick = { id -> navController.navigate(Routes.taskDetail(id)) },
                            onAdd = { openAdd() },
                        )
                    }
                    composable(
                        route = Routes.TASK_DETAIL,
                        arguments = listOf(navArgument("taskId") { type = NavType.LongType }),
                    ) { entry ->
                        val taskId = entry.arguments?.getLong("taskId") ?: 0L
                        TaskDetailScreen(taskId = taskId, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            onSection = { section ->
                                navController.navigate(Routes.settingsSection(section))
                            },
                        )
                    }
                    composable(Routes.SETTINGS_SECTION) { entry ->
                        val route = entry.arguments?.getString("section")
                        val section = SettingsSection.entries.firstOrNull { it.route == route }
                            ?: SettingsSection.Download
                        SettingsSectionScreen(
                            section = section,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                if (showAddSheet) {
                    AddTaskScreen(
                        initialUrl = addInitialUrl,
                        onDone = {
                            showAddSheet = false
                            addInitialUrl = ""
                        },
                        onCancel = {
                            showAddSheet = false
                            addInitialUrl = ""
                        },
                    )
                }
            }
        }
    }
}
