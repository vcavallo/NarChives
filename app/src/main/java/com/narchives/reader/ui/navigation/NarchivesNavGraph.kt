package com.narchives.reader.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.narchives.reader.NarchivesApp
import com.narchives.reader.ui.screen.feed.FeedScreen
import com.narchives.reader.ui.screen.feed.FeedViewModel
import com.narchives.reader.ui.screen.profile.ProfileScreen
import com.narchives.reader.ui.screen.profile.ProfileViewModel
import com.narchives.reader.ui.screen.reader.ReaderModeScreen
import com.narchives.reader.ui.screen.reader.ReaderModeViewModel
import com.narchives.reader.ui.screen.relay.RelayBrowserScreen
import com.narchives.reader.ui.screen.relay.RelayBrowserViewModel
import com.narchives.reader.ui.screen.saved.SavedScreen
import com.narchives.reader.ui.screen.saved.SavedViewModel
import com.narchives.reader.ui.screen.settings.SettingsScreen
import com.narchives.reader.ui.screen.settings.SettingsViewModel
import com.narchives.reader.ui.screen.viewer.ArchiveViewerScreen
import com.narchives.reader.ui.screen.viewer.ArchiveViewerViewModel

sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector) {
    object Feed : BottomNavItem("feed", "Feed", Icons.Default.DynamicFeed)
    object Relays : BottomNavItem("relays", "Relays", Icons.Default.Dns)
    object Saved : BottomNavItem("saved", "Saved", Icons.Default.Bookmark)
    object Settings : BottomNavItem("settings", "Settings", Icons.Default.Settings)
}

private val bottomNavItems = listOf(
    BottomNavItem.Feed,
    BottomNavItem.Relays,
    BottomNavItem.Saved,
    BottomNavItem.Settings,
)

@Composable
fun NarchivesNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Show bottom nav only on main screens
    val showBottomBar = currentDestination?.route in bottomNavItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val context = LocalContext.current
        val container = (context.applicationContext as NarchivesApp).container

        NavHost(
            navController = navController,
            startDestination = "feed",
            modifier = Modifier.padding(innerPadding),
        ) {
            // Bottom nav destinations
            composable("feed") {
                val viewModel = remember {
                    FeedViewModel(
                        container.archiveRepository,
                        container.profileRepository,
                        container.relayRepository,
                        container.savedArchiveRepository,
                    )
                }
                FeedScreen(
                    viewModel = viewModel,
                    onArchiveClick = { eventId ->
                        navController.navigate("viewer/$eventId")
                    },
                    onAuthorClick = { pubkey ->
                        navController.navigate("profile/$pubkey")
                    },
                )
            }

            composable("relays") {
                val viewModel = remember {
                    RelayBrowserViewModel(container.relayRepository)
                }
                RelayBrowserScreen(
                    viewModel = viewModel,
                    onRelayClick = { relayUrl ->
                        // For now, just show the relay — could navigate to a filtered feed
                    },
                )
            }

            composable("saved") {
                val viewModel = remember {
                    SavedViewModel(
                        container.database.savedArchiveDao(),
                        container.archiveRepository,
                    )
                }
                SavedScreen(
                    viewModel = viewModel,
                    onArchiveClick = { eventId ->
                        navController.navigate("viewer/$eventId")
                    },
                    onAuthorClick = { pubkey ->
                        navController.navigate("profile/$pubkey")
                    },
                )
            }

            composable("settings") {
                val viewModel = remember {
                    SettingsViewModel(
                        container.userPreferences,
                        context.cacheDir,
                    )
                }
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateToRelays = {
                        navController.navigate("relays")
                    },
                )
            }

            // Full-screen destinations
            composable(
                route = "viewer/{eventId}",
                arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString("eventId") ?: return@composable
                val viewModel = remember {
                    ArchiveViewerViewModel(
                        eventId = eventId,
                        archiveRepository = container.archiveRepository,
                        savedArchiveDao = container.database.savedArchiveDao(),
                        blossomClient = container.blossomClient,
                        cacheDir = context.cacheDir,
                    )
                }
                ArchiveViewerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onReaderMode = { id ->
                        navController.navigate("reader/$id")
                    },
                )
            }

            composable(
                route = "reader/{eventId}",
                arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString("eventId") ?: return@composable
                val viewModel = remember {
                    ReaderModeViewModel(
                        eventId = eventId,
                        archiveRepository = container.archiveRepository,
                        savedArchiveDao = container.database.savedArchiveDao(),
                        blossomClient = container.blossomClient,
                        userPreferences = container.userPreferences,
                        cacheDir = context.cacheDir,
                    )
                }
                ReaderModeScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "profile/{pubkey}",
                arguments = listOf(navArgument("pubkey") { type = NavType.StringType }),
            ) { backStackEntry ->
                val pubkey = backStackEntry.arguments?.getString("pubkey") ?: return@composable
                val viewModel = remember {
                    ProfileViewModel(
                        pubkey = pubkey,
                        archiveRepository = container.archiveRepository,
                        profileRepository = container.profileRepository,
                        relayRepository = container.relayRepository,
                    )
                }
                ProfileScreen(
                    viewModel = viewModel,
                    onArchiveClick = { eventId ->
                        navController.navigate("viewer/$eventId")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
