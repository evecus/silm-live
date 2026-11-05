package com.silm.live.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silm.live.data.FollowStore
import com.silm.live.sync.LanSyncService
import com.silm.live.sync.SyncPeer
import com.silm.live.ui.AppLayout
import com.silm.live.ui.TvDialogButton
import com.silm.live.ui.theme.TvBackground
import com.silm.live.ui.focusClickable
import com.silm.live.ui.tvFocusHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程关注同步（局域网）：
 *  - 进入本页才启动 UDP 发现与 HTTP 接收端，离开即关闭（其他设备也只有在同样的页面才能被扫描到）
 *  - 显示局域网设备列表 + 刷新（重新广播扫描）
 *  - 选中一个设备 → 点「同步」→ 用本机关注列表覆盖该设备（对方无需确认）
 */
@Composable
fun SyncScreen(
    layout: AppLayout,
    onBack: () -> Unit,
) {
    val isTv = layout == AppLayout.TV
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val peers = remember { mutableStateListOf<SyncPeer>() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var confirmSync by remember { mutableStateOf(false) }
    var restartKey by remember { mutableStateOf(0) }

    // 进入页面启动服务，离开关闭
    DisposableEffect(restartKey) {
        LanSyncService.onPeerFound = { peer ->
            if (peers.none { it.deviceId == peer.deviceId || it.address == peer.address }) {
                peers.add(peer)
            }
        }
        LanSyncService.start(context)
        onDispose { LanSyncService.stop() }
    }

    // 服务启动失败提示（端口占用）
    LaunchedEffect(restartKey) {
        delay(300)
        LanSyncService.lastError?.let { snackbar.showSnackbar(it) }
    }

    fun refresh() {
        peers.clear()
        selectedId = null
        LanSyncService.start(context)
        LanSyncService.sendHello()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isTv) TvBackground else MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 头部：返回 + 标题 + 刷新
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "远程关注同步",
                        style = if (isTv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "本机：${LanSyncService.deviceName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(
                    onClick = { refresh() },
                    modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "已发现设备（${peers.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (peers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "正在扫描局域网设备…",
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    peers.forEach { peer ->
                        PeerRow(
                            peer = peer,
                            selected = selectedId == peer.deviceId,
                            tv = isTv,
                            onClick = { selectedId = peer.deviceId },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "选择一台设备后点击同步，将会用本机关注列表覆盖对方设备的关注列表（对方无需确认）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { if (selectedId != null && !syncing) confirmSync = true },
                            enabled = selectedId != null && !syncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusHighlight(isTv, cornerRadius = 8.dp)
                        ) {
                            if (syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("同步中…")
                            } else {
                                Icon(Icons.Filled.Sync, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("同步（覆盖对方关注列表）")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "需要两台设备在同一 Wi-Fi / 局域网，并都停留在「远程关注同步」页面才能互相发现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (confirmSync) {
        val peer = peers.firstOrNull { it.deviceId == selectedId }
        AlertDialog(
            onDismissRequest = { confirmSync = false },
            title = { Text("覆盖对方关注列表？") },
            text = {
                Text("将把本机关注列表发送到「${peer?.name ?: ""}（${peer?.address ?: ""}）」，覆盖对方现有的关注列表。")
            },
            confirmButton = {
                TvDialogButton(label = "覆盖同步", tv = isTv, onClick = {
                    confirmSync = false
                    val target = peer ?: return@TvDialogButton
                    syncing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val items = FollowStore.current(context)
                            LanSyncService.pushFollows(context, target, items, overlay = true)
                        }
                        syncing = false
                        snackbar.showSnackbar(
                            result.fold(
                                { "已同步 ${it} 个关注到 ${target.name}" },
                                { "同步失败：${it.message}" }
                            )
                        )
                    }
                })
            },
            dismissButton = {
                TvDialogButton(label = "取消", tv = isTv, onClick = { confirmSync = false })
            }
        )
    }
}

@Composable
private fun PeerRow(
    peer: SyncPeer,
    selected: Boolean,
    tv: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusClickable(tv, 12.dp, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                peer.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${peer.address} · ${peer.type.uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        RadioButton(
            selected = selected,
            onClick = onClick
        )
    }
}
