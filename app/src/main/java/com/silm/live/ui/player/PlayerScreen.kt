package com.silm.live.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import android.widget.Toast
import androidx.activity.compose.BackHandler
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch

/** 控制层自动隐藏时长 */
private const val CONTROLS_HIDE_MS = 3_000L

/**
 * 按直播间记忆视频方向（持久化）：再次进入同一直播间时直接转到正确方向。
 * 从未播放过的直播间返回 null —— 此时保持当前屏幕方向不动，等拿到视频尺寸后再转一次，
 * 不预设横/竖，避免竖屏直播先被转成横屏再转回来。
 */
private fun orientPrefs(context: android.content.Context) =
    context.getSharedPreferences("player_orientation", android.content.Context.MODE_PRIVATE)

private fun loadVideoPortrait(context: android.content.Context, key: String): Boolean? {
    val p = orientPrefs(context)
    return if (p.contains(key)) p.getBoolean(key, false) else null
}

private fun saveVideoPortrait(context: android.content.Context, key: String, portrait: Boolean) {
    orientPrefs(context).edit().putBoolean(key, portrait).apply()
}

/**
 * 全屏沉浸播放页（三端统一）：
 *  - 进入即全屏播放，无全屏按钮；视频按原始比例 letterbox，不填充屏幕
 *  - 手机/平板：隐藏系统状态栏；点击屏幕显示控制层（返回/清晰度/弹幕/关注），3s 无操作自动隐藏
 *  - 平板：强制横屏
 *  - TV：无返回按钮；控制层为底部一排文字按钮（清晰度/线路/画面/解码/弹幕/刷新/关注），
 *    遥控器「下键 / 菜单键 / 确认键」唤出，3s 无操作自动隐藏（菜单展开期间不隐藏）
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
    // 任意操作（按键/点击）自增，用于重置 3s 自动隐藏计时
    var interactionTick by remember { mutableIntStateOf(0) }
    // 下拉菜单是否展开（展开期间暂停自动隐藏）
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var videoAspect by remember { mutableStateOf(VideoAspect.DEFAULT) }
    val urlIndex by viewModel.urlIndex.collectAsState()
    val playUrlInfo by viewModel.playUrl.collectAsState()
    // TV 焦点：控制层显示时 → 第一个按钮；隐藏时 → 隐形接收器（保证遥控器按键始终有处可去）；错误 → 重试按钮
    val firstButtonFocus = remember { FocusRequester() }
    val keySinkFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

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
    // 设置项必须同步读出真实值作初值：DataStore 异步加载期间若用默认值 (AUTO / 硬解)，
    // 会先按默认值转屏/起播，加载完成后再按真实设置转一次/重建一次播放器
    val hwDecodeInit = remember { runBlocking { SettingsStore.hwDecode(context).first() } }
    val orientationInit = remember { runBlocking { SettingsStore.phoneOrientation(context).first() } }
    val hwDecode by SettingsStore.hwDecode(context).collectAsState(initial = hwDecodeInit)
    val orientationName by SettingsStore.phoneOrientation(context)
        .collectAsState(initial = orientationInit)
    val phoneOrientation = PhonePlayOrientation.fromName(orientationName)
    // 视频是否为竖屏（由播放器回调）；AUTO 模式下据此切换屏幕方向，null 表示尚未收到尺寸
    // 初值取该直播间上次的视频方向：已知 → 进入时直接转到目标方向, 尺寸回调后方向一致不再切换;
    // 未知 → null, 保持当前方向不动, 拿到尺寸后只转一次
    val orientKey = "$platform:$roomId"
    var videoIsPortrait by remember { mutableStateOf(loadVideoPortrait(context, orientKey)) }

    // 离开播放页时才恢复方向; 不放在下面随 key 重建的 effect 里,
    // 否则每次 key 变化都会先被重置为 UNSPECIFIED, 屏幕会短暂转回竖屏
    DisposableEffect(layout) {
        val activity = context as? Activity
        onDispose {
            if (layout != AppLayout.TV) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

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
        if (activity?.requestedOrientation != target) activity?.requestedOrientation = target
        onDispose { }
    }

    // ---------- 播放时保持屏幕常亮 (不跟随系统自动息屏) ----------
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------- TV：返回键需二次确认 ----------
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler(enabled = isTv) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt <= 2000L) {
            onBack()
        } else {
            lastBackAt = now
            Toast.makeText(context, "再按一次返回键退出播放", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 3s 无操作自动隐藏控制层 ----------
    // 每次操作 interactionTick 变化 → 协程重启重新计 3s；菜单展开期间不计时
    LaunchedEffect(controlsVisible, interactionTick, menuOpen) {
        if (controlsVisible && !menuOpen) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) menuOpen = false
    }

    // ---------- TV 焦点管理 ----------
    // 隐藏时：焦点交给隐形接收器（有错误浮层时交给重试按钮），保证下键等按键仍能送达
    LaunchedEffect(controlsVisible, error) {
        if (!isTv) return@LaunchedEffect
        if (error != null) {
            runCatching { retryFocus.requestFocus() }
        } else if (!controlsVisible) {
            runCatching { keySinkFocus.requestFocus() }
        }
    }
    // 显示时：焦点落到底部第一个按钮
    LaunchedEffect(controlsVisible) {
        if (isTv && controlsVisible) {
            delay(50) // 等按钮栏完成组合再请求
            runCatching { firstButtonFocus.requestFocus() }
        }
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
        interactionTick++
    }

    // 当前地址与请求头（全部线路统一由 ExoPlayer 内核播放：HLS/FLV 均支持）
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
                    // TV：控制层隐藏时 下键/菜单键/确认键 唤出；显示时菜单键收起，
                    // 其他任意按键只重置 3s 计时（返回 false 不拦截，焦点移动照常进行）
                    m.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val code = event.nativeKeyEvent.keyCode
                        if (!controlsVisible) {
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_MENU,
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    controlsVisible = true
                                    interactionTick++
                                    true
                                }
                                else -> false
                            }
                        } else if (code == KeyEvent.KEYCODE_MENU) {
                            controlsVisible = false
                            true
                        } else {
                            interactionTick++
                            false
                        }
                    }
                } else {
                    m
                }
            }
            // 点击屏幕切换控制层（手机/平板/TV 通用）.
            // 用 pointerInput 而不是 clickable: clickable 会让根节点成为可聚焦目标,
            // TV 上 DPAD 焦点被整个屏幕"吃掉", 永远走不到控制层按钮 (焦点陷阱);
            // pointerInput 只响应触摸, 不产生焦点节点.
            .pointerInput(Unit) { detectTapGestures(onTap = { toggleControls() }) }
    ) {
        // 视频层（内部按原始比例 letterbox，不填充屏幕）
        if (currentUrl.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LivePlayer(
                    url = currentUrl,
                    headers = currentHeaders,
                    onError = onPlayError,
                    // AUTO 模式需要知道视频方向来决定屏幕方向
                    onVideoSize = { w, h ->
                        if (w > 0 && h > 0) {
                            videoIsPortrait = h > w
                            saveVideoPortrait(context, orientKey, h > w)
                        }
                    },
                    hwDecode = hwDecode,
                    aspect = videoAspect,
                    // 16:9 / 4:3：容器按固定比例居中，视频拉伸填满该容器
                    modifier = videoAspect.ratio
                        ?.let { Modifier.aspectRatio(it) }
                        ?: Modifier.fillMaxSize()
                )
            }
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
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error ?: "", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.load() },
                        modifier = Modifier
                            .focusRequester(retryFocus)
                            .tvFocusHighlight(isTv, cornerRadius = 20.dp)
                    ) {
                        Text("重试", color = Color.White)
                    }
                }
            }
        }

        // TV 控制层隐藏时：隐形焦点接收器，让遥控器按键（下键等）有处可去
        if (isTv && !controlsVisible && error == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(keySinkFocus)
                    .focusable()
            )
        }

        // ---------- 控制层（3s 自动隐藏） ----------
        if (controlsVisible && isTv) {
            // TV：标题在左上角，所有按钮（含清晰度）统一放在底部一排
            TvPlayerControls(
                title = detail?.title ?: SiteManager.byId(platform)?.name ?: "直播间",
                subtitle = "${detail?.userName ?: ""} · ${SiteManager.byId(platform)?.name ?: platform}",
                online = if (online > 0) formatOnline(online) else null,
                qualities = qualities.map { it.quality },
                selectedQuality = selectedQuality?.quality,
                onSelectQuality = { name ->
                    qualities.firstOrNull { it.quality == name }?.let { viewModel.selectQuality(it) }
                },
                lineCount = playUrlInfo?.urls?.size ?: 0,
                lineIndex = urlIndex,
                onSelectLine = { viewModel.selectLine(it) },
                aspect = videoAspect,
                onSelectAspect = { videoAspect = it },
                hwDecode = hwDecode,
                onToggleHwDecode = {
                    scope.launch { SettingsStore.setHwDecode(context, !hwDecode) }
                },
                danmakuEnabled = danmakuEnabled,
                onToggleDanmaku = { viewModel.toggleDanmaku() },
                danmakuTextSp = danmakuTextSp,
                onSelectTextSp = { scope.launch { SettingsStore.setDanmakuTextSp(context, it) } },
                danmakuArea = danmakuArea,
                onSelectArea = { scope.launch { SettingsStore.setDanmakuArea(context, it) } },
                isFollowed = isFollowed,
                onToggleFollow = { viewModel.toggleFollow() },
                onRefresh = { viewModel.load() },
                firstFocus = firstButtonFocus,
                onInteract = { interactionTick++ },
                onMenuOpenChange = { menuOpen = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (controlsVisible) {
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
                    onExpandedChange = { menuOpen = it },
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
                IconButton(
                    onClick = { viewModel.toggleDanmaku() },
                    modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                ) {
                    Icon(
                        if (danmakuEnabled) Icons.Filled.Dehaze else Icons.Filled.HideImage,
                        contentDescription = "弹幕开关",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { viewModel.toggleFollow() },
                    modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                ) {
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
    onExpandedChange: (Boolean) -> Unit = {},
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    Box(modifier = Modifier.tvFocusHighlight(tv, cornerRadius = 18.dp)) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected ?: "清晰度", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            qualities.forEach { q ->
                DropdownMenuItem(
                    text = { Text(q) },
                    modifier = Modifier.tvFocusHighlight(
                        tv, scale = 1.0f, cornerRadius = 8.dp, // 菜单窗口小, 不缩放避免边缘裁剪
                    ),
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
