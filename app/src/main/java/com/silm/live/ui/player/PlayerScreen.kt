package com.silm.live.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silm.live.core.SiteManager

@Composable
fun PlayerScreen(
    platform: String,
    roomId: String,
    onBack: () -> Unit,
) {
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
    val danmaku by viewModel.danmaku.collectAsState()
    val online by viewModel.online.collectAsState()
    val danmakuEnabled by viewModel.danmakuEnabled.collectAsState()
    val isFollowed by viewModel.isFollowed.collectAsState()

    var fullscreen by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity

    DisposableEffect(fullscreen) {
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }

    // 当前地址与请求头（全部线路统一由 ijk 内核播放：HLS/FLV/RTMP 均支持）
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
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 视频区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (fullscreen) Modifier.fillMaxSize()
                        else Modifier.aspectRatio(16f / 9f)
                    )
                    .background(Color.Black)
            ) {
                if (currentUrl.isNotEmpty()) {
                    IjkPlayer(
                        url = currentUrl,
                        headers = currentHeaders,
                        onError = onPlayError,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // 弹幕层
                DanmakuOverlay(
                    messages = danmaku,
                    enabled = danmakuEnabled && !fullscreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .align(Alignment.TopCenter)
                )

                // 加载中
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(42.dp)
                            .align(Alignment.Center),
                        color = Color.White
                    )
                }

                // 错误
                if (error != null) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
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
            }

            if (!fullscreen) {
                // 标题栏
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                detail?.title ?: if (error != null) (SiteManager.byId(platform)?.name ?: "直播间") else "加载中...",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${detail?.userName ?: ""} · ${SiteManager.byId(platform)?.name ?: platform}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                if (online > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "在线 ${formatOnline(online)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        // 清晰度
                        QualitySelector(
                            qualities = qualities.map { it.quality },
                            selected = selectedQuality?.quality,
                            onSelect = { name ->
                                qualities.firstOrNull { it.quality == name }?.let {
                                    viewModel.selectQuality(it)
                                }
                            }
                        )
                        IconButton(onClick = { viewModel.toggleDanmaku() }) {
                            Icon(
                                if (danmakuEnabled) Icons.Filled.Dehaze else Icons.Filled.HideImage,
                                contentDescription = "弹幕开关"
                            )
                        }
                        IconButton(onClick = { viewModel.toggleFollow() }) {
                            Icon(
                                if (isFollowed) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "关注",
                                tint = if (isFollowed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { fullscreen = true }) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = "全屏")
                        }
                    }
                }

                // 弹幕列表
                val listState = rememberLazyListState()
                LaunchedEffect(danmaku.size) {
                    if (danmaku.isNotEmpty()) {
                        listState.animateScrollToItem(danmaku.size - 1)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    items(danmaku) { msg ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                msg.userName,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                msg.message,
                                color = if (msg.color == 0L) MaterialTheme.colorScheme.onBackground
                                else Color(0xFF000000 or msg.color),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                // 全屏模式下点击视频区域返回
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { fullscreen = false }
                )
            }
        }
    }
}

@Composable
fun QualitySelector(
    qualities: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
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
