package com.byd.tripstats.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.byd.tripstats.ui.screens.ChargingDetailScreen
import com.byd.tripstats.ui.screens.ChargingHistoryScreen
import com.byd.tripstats.ui.screens.DashboardScreen
import com.byd.tripstats.ui.screens.LocalBackupScreen
import com.byd.tripstats.ui.screens.SettingsScreen
import com.byd.tripstats.ui.screens.TripDetailScreen
import com.byd.tripstats.ui.screens.TripHistoryScreen
import com.byd.tripstats.ui.viewmodel.DashboardViewModel

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object TripHistory : Screen("trip_history")
    object TripDetail : Screen("trip_detail/{tripId}") {
        fun createRoute(tripId: Long) = "trip_detail/$tripId"
    }
    object Settings : Screen("settings")
    object LocalBackup : Screen("local_backup")
    object ChargingHistory : Screen("charging_history")
    object ChargingDetail : Screen("charging_detail/{sessionId}") {
        fun createRoute(sessionId: Long) = "charging_detail/$sessionId"
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                viewModel = viewModel,
                onNavigateToHistory = {
                    navController.navigate(Screen.TripHistory.route)
                },
                onNavigateToCharging = {
                    navController.navigate(Screen.ChargingHistory.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        composable(Screen.TripHistory.route) {
            TripHistoryScreen(
                viewModel = viewModel,
                onTripClick = { tripId ->
                    navController.navigate(Screen.TripDetail.createRoute(tripId))
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(
            route = Screen.TripDetail.route,
            arguments = listOf(
                navArgument("tripId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            TripDetailScreen(
                tripId = tripId,
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToBackup = {
                    navController.navigate(Screen.LocalBackup.route)
                }
            )
        }

        composable(Screen.LocalBackup.route) {
            LocalBackupScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ChargingHistory.route) {
            ChargingHistoryScreen(
                viewModel = viewModel,
                onSessionClick = { sessionId ->
                    navController.navigate(Screen.ChargingDetail.createRoute(sessionId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ChargingDetail.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: return@composable
            ChargingDetailScreen(
                sessionId = sessionId,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}