package com.silm.live.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silm.live.core.SiteManager
import com.silm.live.data.SettingsStore
import com.silm.live.ui.AppLayout
import com.silm.live.ui.PhonePlayOrientation
import com.silm.live.ui.tvFocusHighlight
import kotlinx.coroutines.delay

/** 控制层自动隐藏时长 */
private const val CONTROLS_HIDE_MS = 3_000L

/**
 * 全屏沉浸播放页（三端统一）：
 *  - 进入即全屏播放，无全屏按钮；视频按原始比例 letterbox，不填充屏幕
 *  - 手机/平板：隐藏系统状态栏；点击屏幕显示控制层（返回/清晰度/弹幕/关注），3s 无操作自动隐藏
 *  - 平板：强制横屏
 *  - TV：无返回按钮，遥控器菜单键切换控制层显示，3s 无操作自动隐藏
 */
@Composable
fun PlayerScreen(
    platform: String,
    roomId: String,
    layout: AppLayout,
    onBack: () -> Unit,
) {
    val isTv = layout == AppLayout.TV
    val viewModel: PlayerViewModel = viewModel(
        key = "$platform:$roomId",
        factory = PlayerViewModel.Factory(
            LocalContext.current,
            platform,
            roomId
        )
    )

    val detail by viewModel.detail.collectAsState()
    val qualities by viewModel.qualities.collectAsState()
    val selectedQuality by viewModel.selectedQuality.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val online by viewModel.online.collectAsState()
    val danmakuEnabled by viewModel.danmakuEnabled.collectAsState()
    val danmakuStatus by viewModel.danmakuStatus.collectAsState()
    val isFollowed by viewModel.isFollowed.collectAsState()

    val context = LocalContext.current
    val view = LocalView.current
    var controlsVisible by remember { mutableStateOf(true) }

    // ---------- 弹幕设置（字号 / 屏占比） ----------
    val danmakuTextSp by SettingsStore.danmakuTextSp(context).collectAsState(initial = 16)
    val danmakuArea by SettingsStore.danmakuArea(context).collectAsState(initial = 25)

    // 弹幕状态一次性提示：仅在状态到达终态（已连接/连接失败）时提示一次
    var danmakuToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(danmakuStatus) {
        if (!danmakuEnabled) return@LaunchedEffect
        when (danmakuStatus) {
            "已连接" -> danmakuToast = "弹幕已连接"
            "连接失败" -> danmakuToast = "弹幕连接失败"
        }
    }
    LaunchedEffect(danmakuToast) {
        if (danmakuToast != null) {
            delay(2500)
            danmakuToast = null
        }
    }

    // ---------- 沉浸式：隐藏系统状态栏/导航栏 ----------
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------- 屏幕方向 ----------
    // 平板：强制横屏；手机：按「手机播放比例」设置（横屏 / 竖屏 / 按视频方向自动）；TV：不干预
    val hwDecode by SettingsStore.hwDecode(context).collectAsState(initial = true)
    val orientationName by SettingsStore.phoneOrientation(context)
        .collectAsState(initial = PhonePlayOrientation.AUTO.name)
    val phoneOrientation = PhonePlayOrientation.fromName(orientationName)
    // 视频是否为竖屏（由播放器回调）；AUTO 模式下据此切换屏幕方向，null 表示尚未收到尺寸
    var videoIsPortrait by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(layout, phoneOrientation, videoIsPortrait) {
        val activity = context as? Activity
        val target: Int = when (layout) {
            AppLayout.TABLET -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            AppLayout.TV -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            AppLayout.PHONE -> when (phoneOrientation) {
                PhonePlayOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                PhonePlayOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                PhonePlayOrientation.AUTO -> when (videoIsPortrait) {
                    true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    false -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    // 画质尚未就绪：先保持当前方向，避免闪转
                    null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        activity?.requestedOrientation = target
        onDispose {
            if (layout != AppLayout.TV) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // ---------- 3s 无操作自动隐藏控制层 ----------
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
    }

    // 当前地址与请求头（全部线路统一由 mpv 内核播放：HLS/FLV/RTMP 均支持）
    val currentPair = viewModel.currentUrl()
    val currentUrl = currentPair?.first ?: ""
    val currentHeaders = currentPair?.second ?: emptyMap()

    // 播放失败自动切换线路 (线路耗尽后最多整页重载 2 次, 之后错误上屏, 避免静默黑屏)
    var autoReload by remember { mutableStateOf(0) }
    LaunchedEffect(viewModel.playUrl.collectAsState().value) {
        autoReload = 0
    }
    val onPlayError: (Int, Int) -> Unit = { what, extra ->
        if (viewModel.nextUrl()) {
            autoReload = 0
        } else if (autoReload < 2) {
            autoReload++
            viewModel.load()
        } else {
            viewModel.surfaceError("播放失败(code=$what/$extra)，请点击重试")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .let { m ->
                if (isTv) {
                    // TV：菜单键切换控制层
                    m.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                        ) {
                            toggleControls()
                            true
                        } else false
                    }
                } else {
                    m
                }
            }
            // 点击屏幕切换控制层（手机/平板/TV 通用）
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { toggleControls() }
    ) {
        // 视频层（内部按原始比例 letterbox，不填充屏幕）
        if (currentUrl.isNotEmpty()) {
            MpvPlayer(
                url = currentUrl,
                headers = currentHeaders,
                onError = onPlayError,
                // AUTO 模式需要知道视频方向来决定屏幕方向
                onVideoSize = { w, h -> if (w > 0 && h > 0) videoIsPortrait = h > w },
                hwDecode = hwDecode,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 弹幕层
        DanmakuOverlay(
            events = viewModel.danmakuEvents,
            enabled = danmakuEnabled,
            textSp = danmakuTextSp.toFloat(),
            areaRatio = danmakuArea / 100f,
            modifier = Modifier.fillMaxSize()
        )

        // 弹幕状态一次性提示（仅在状态变化时短暂显示 2.5s，不常驻）
        danmakuToast?.let { msg ->
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // 加载中
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(42.dp)
                    .align(Alignment.Center),
                color = Color.White
            )
        }

        // 错误浮层（常驻显示，不受控制层隐藏影响）
        if (error != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .then(
                            if (isTv) Modifier.tvFocusHighlight(true, cornerRadius = 8.dp)
                            else Modifier
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error ?: "", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.load() }) {
                        Text("重试", color = Color.White)
                    }
                }
            }
        }

        // ---------- 控制层（3s 自动隐藏） ----------
        if (controlsVisible) {
            // 顶部：返回（TV 无）+ 标题/在线人数 + 清晰度
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isTv) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        detail?.title ?: SiteManager.byId(platform)?.name ?: "直播间",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${detail?.userName ?: ""} · ${SiteManager.byId(platform)?.name ?: platform}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        if (online > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "在线 ${formatOnline(online)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4FC3F7)
                            )
                        }
                    }
                }
                // 清晰度
                QualitySelector(
                    qualities = qualities.map { it.quality },
                    selected = selectedQuality?.quality,
                    tv = isTv,
                    onSelect = { name ->
                        qualities.firstOrNull { it.quality == name }?.let {
                            viewModel.selectQuality(it)
                        }
                    }
                )
            }

            // 底部：弹幕开关 + 关注
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.toggleDanmaku() }) {
                    Icon(
                        if (danmakuEnabled) Icons.Filled.Dehaze else Icons.Filled.HideImage,
                        contentDescription = "弹幕开关",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { viewModel.toggleFollow() }) {
                    Icon(
                        if (isFollowed) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "关注",
                        tint = if (isFollowed) Color(0xFFFF6B9D) else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun QualitySelector(
    qualities: List<String>,
    selected: String?,
    tv: Boolean = false,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.tvFocusHighlight(tv, cornerRadius = 18.dp)) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected ?: "清晰度", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            qualities.forEach { q ->
                DropdownMenuItem(
                    text = { Text(q) },
                    onClick = {
                        expanded = false
                        onSelect(q)
                    }
                )
            }
        }
    }
}

private fun formatOnline(online: Long): String = when {
    online >= 100_0000 -> "${"%.1f".format(online / 100_0000f)}亿"
    online >= 10_000 -> "${"%.1f".format(online / 10_000f)}万"
    else -> online.toString()
}
