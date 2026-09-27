package com.byd.tripstats.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.ui.components.BrandNavigationBar
import com.byd.tripstats.ui.screens.settings.AboutTab
import com.byd.tripstats.ui.screens.settings.AppManagementTab
import com.byd.tripstats.ui.screens.settings.AppPreferencesTab
import com.byd.tripstats.ui.screens.settings.ConnectionsTab
import com.byd.tripstats.ui.screens.settings.ProTab
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel         : DashboardViewModel,
    onNavigateBack    : () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToTripGoals: () -> Unit = {}
) {
    val context           = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context) }
    val scope             = rememberCoroutineScope()

    val snackbarHostState  = remember { SnackbarHostState() }
    var selectedTab       by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.settings_tab_app),
        stringResource(R.string.settings_tab_connections),
        stringResource(R.string.settings_tab_preferences),
        stringResource(R.string.settings_tab_pro),
        stringResource(R.string.settings_tab_about)
    )
    val proTabIndex = 3
    val aboutTabIndex = 4

    val updateInfo by viewModel.updateInfo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.nav_settings), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onNavigateBack() }
                    )
                },
                navigationIcon = {
                  BrandNavigationBar {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), modifier = Modifier.size(32.dp))
                    }
                  }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick  = { selectedTab = index },
                        text = {
                            if (index == aboutTabIndex && updateInfo != null) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = AccelerationOrange,
                                            modifier = Modifier.offset(x = 18.dp, y = (-2).dp)
                                        ) {
                                            Text(
                                                text = "1",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                ) {
                                    Text(
                                        title,
                                        fontSize   = 17.sp,
                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            } else {
                                Text(
                                    title,
                                    fontSize   = 17.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> AppManagementTab(
                        viewModel         = viewModel,
                        context           = context,
                        onNavigateToBackup = onNavigateToBackup,
                        scope             = scope
                    )
                    1 -> ConnectionsTab()
                    2 -> AppPreferencesTab(
                        viewModel = viewModel,
                        preferencesManager = preferencesManager,
                        onNavigateToTripGoals = onNavigateToTripGoals,
                        onNavigateToProTab = { selectedTab = proTabIndex }
                    )
                    3 -> ProTab()
                    4 -> AboutTab(viewModel = viewModel)
                }
            }
        }
    }
}
