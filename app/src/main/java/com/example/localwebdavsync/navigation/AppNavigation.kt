package com.example.localwebdavsync.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.localwebdavsync.AppContainer
import com.example.localwebdavsync.ui.home.HomeScreen
import com.example.localwebdavsync.ui.home.HomeViewModel
import com.example.localwebdavsync.ui.log.LogScreen
import com.example.localwebdavsync.ui.log.LogViewModel
import com.example.localwebdavsync.ui.settings.SettingsScreen
import com.example.localwebdavsync.ui.settings.SettingsViewModel
import com.example.localwebdavsync.ui.task.TaskEditScreen
import com.example.localwebdavsync.ui.task.TaskEditViewModel

object AppRoutes {
    const val Home = "home"
    const val TaskEdit = "taskEdit"
    const val TaskEditWithId = "taskEdit/{taskId}"
    const val Settings = "settings"
    const val Logs = "logs"

    fun taskEdit(taskId: Long) = "taskEdit/$taskId"
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val appContainer = remember { AppContainer(context.applicationContext) }
    val navController = rememberNavController()
    val settings by appContainer.settingsRepository.settings.collectAsState()
    val isWebDavConfigured = settings.username.isNotBlank() && settings.appPassword.isNotBlank()
    val startDestination = remember {
        if (appContainer.settingsRepository.isWebDavConfigured()) {
            AppRoutes.Home
        } else {
            AppRoutes.Settings
        }
    }
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(
            repository = appContainer.syncTaskRepository,
            localScanRepository = appContainer.localScanRepository,
            webDavUploadRepository = appContainer.webDavUploadRepository,
            developerLogRepository = appContainer.developerLogRepository,
            settingsRepository = appContainer.settingsRepository,
            networkStateProvider = appContainer.networkStateProvider
        )
    )

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(AppRoutes.Home) {
            HomeScreen(
                viewModel = homeViewModel,
                isWebDavConfigured = isWebDavConfigured,
                onEditTask = { task ->
                    navController.navigate(AppRoutes.taskEdit(task.id))
                },
                onCreateTask = {
                    if (isWebDavConfigured) {
                        navController.navigate(AppRoutes.TaskEdit)
                    } else {
                        navController.navigate(AppRoutes.Settings)
                    }
                },
                onOpenSettings = {
                    navController.navigate(AppRoutes.Settings)
                },
                onOpenLogs = {
                    navController.navigate(AppRoutes.Logs)
                }
            )
        }
        composable(AppRoutes.TaskEdit) {
            val editViewModel: TaskEditViewModel = viewModel(
                key = "task-edit-new",
                factory = TaskEditViewModel.Factory(
                    repository = appContainer.syncTaskRepository,
                    settingsRepository = appContainer.settingsRepository,
                    webDavClient = appContainer.webDavClient,
                    taskId = null
                )
            )
            TaskEditScreen(
                viewModel = editViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = AppRoutes.TaskEditWithId,
            arguments = listOf(navArgument("taskId") { type = NavType.LongType })
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getLong("taskId")
            val editViewModel: TaskEditViewModel = viewModel(
                key = "task-edit-$taskId",
                factory = TaskEditViewModel.Factory(
                    repository = appContainer.syncTaskRepository,
                    settingsRepository = appContainer.settingsRepository,
                    webDavClient = appContainer.webDavClient,
                    taskId = taskId
                )
            )
            TaskEditScreen(
                viewModel = editViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(AppRoutes.Settings) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    settingsRepository = appContainer.settingsRepository,
                    webDavClient = appContainer.webDavClient
                )
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(AppRoutes.Home) {
                            popUpTo(AppRoutes.Settings) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
        composable(AppRoutes.Logs) {
            val logViewModel: LogViewModel = viewModel(
                factory = LogViewModel.Factory(
                    developerLogRepository = appContainer.developerLogRepository,
                    settingsRepository = appContainer.settingsRepository
                )
            )
            LogScreen(
                viewModel = logViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
