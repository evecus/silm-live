package com.silm.live.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.net.Uri
import com.silm.live.core.SiteManager
import com.silm.live.data.SettingsStore
import com.silm.live.ui.account.AccountScreen
import com.silm.live.ui.follow.FollowScreen
import com.silm.live.ui.player.PlayerScreen
import com.silm.live.ui.search.SearchScreen
import com.silm.live.ui.settings.SettingsScreen
import com.silm.live.ui.sync.SyncScreen
import com.silm.live.ui.theme.SilmTheme

/**
 * 应用外壳：
 *  - 无导航栏。首页 = 关注列表（右上角设置图标）→ 设置页（独立页面）
 *  - 手机/平板/电视三种布局由显示模式决定（设置页可切换，自动按设备判定）
 */
@Composable
fun SilmApp(initialLink: String? = null) {
    val context = LocalContext.current
    val modeName by SettingsStore.displayMode(context).collectAsState(initial = DisplayMode.AUTO.name)
    val layout = resolveAppLayout(context, DisplayMode.fromName(modeName))

    // TV 端强制深色，遥控器场景观感更佳
    SilmTheme(forceDark = layout == AppLayout.TV) {
        val navController = rememberNavController()

        // 启动时把持久化的账号 Cookie 灌入内存缓存
        LaunchedEffect(Unit) {
            SettingsStore.primeAccountCookies(context)
        }

        // 处理外部分享链接
        LaunchedEffect(initialLink) {
            if (!initialLink.isNullOrBlank()) {
                SiteManager.parseUrl(initialLink)?.let { (site, roomId) ->
                    navController.navigate("player/${site.id}/$roomId")
                }
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            NavHost(
                navController = navController,
                startDestination = "follow",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
            ) {
                composable("follow") {
                    FollowScreen(
                        layout = layout,
                        onOpenRoom = { platform, roomId ->
                            navController.navigate("player/$platform/${Uri.encode(roomId)}")
                        },
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenSearch = { navController.navigate("search") },
                    )
                }
                composable("search") {
                    SearchScreen(
                        layout = layout,
                        onBack = { navController.popBackStack() },
                        onOpenRoom = { platform, roomId ->
                            navController.navigate("player/$platform/${Uri.encode(roomId)}")
                        },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        layout = layout,
                        onBack = { navController.popBackStack() },
                        onOpenSync = { navController.navigate("sync") },
                        onOpenAccount = { navController.navigate("account") },
                        onOpenRoom = { platform, roomId ->
                            navController.navigate("player/$platform/$roomId")
                        },
                    )
                }
                composable("account") {
                    AccountScreen(
                        layout = layout,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("sync") {
                    SyncScreen(
                        layout = layout,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    "player/{platform}/{roomId}",
                    // SurfaceView 不参与 Compose 转场动画，退出时若带动画会在转场期间残留画面，
                    // 所以播放页进出都不做转场，销毁即时发生
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) { entry ->
                    val platform = entry.arguments?.getString("platform") ?: ""
                    val roomId = entry.arguments?.getString("roomId") ?: ""
                    PlayerScreen(
                        platform = platform,
                        roomId = roomId,
                        layout = layout,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
