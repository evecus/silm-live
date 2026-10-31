package com.silm.live.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.silm.live.core.SiteManager
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.silm.live.ui.follow.FollowScreen
import com.silm.live.ui.player.PlayerScreen
import com.silm.live.ui.settings.SettingsScreen

data class TabItem(
    val route: String,
    val title: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun SilmApp(initialLink: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 处理外部分享链接
    LaunchedEffect(initialLink) {
        if (!initialLink.isNullOrBlank()) {
            val parsed = SiteManager.parseUrl(initialLink)
            parsed?.let { (site, roomId) ->
                navController.navigate("player/${site.id}/$roomId")
            }
        }
    }

    val tabs = listOf(
        TabItem("follow", "关注", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
        TabItem("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
    )

    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.title
                                )
                            },
                            label = { Text(tab.title) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "follow",
            modifier = Modifier.padding(padding)
        ) {
            composable("follow") {
                FollowScreen(
                    onOpenRoom = { platform, roomId ->
                        navController.navigate("player/$platform/$roomId")
                    }
                )
            }
            composable("settings") {
                SettingsScreen(
                    onOpenRoom = { platform, roomId ->
                        navController.navigate("player/$platform/$roomId")
                    }
                )
            }
            composable("player/{platform}/{roomId}") { entry ->
                val platform = entry.arguments?.getString("platform") ?: ""
                val roomId = entry.arguments?.getString("roomId") ?: ""
                PlayerScreen(
                    platform = platform,
                    roomId = roomId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
