package com.silm.live.ui.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silm.live.core.SiteManager
import com.silm.live.data.FollowItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowScreen(
    onOpenRoom: (platform: String, roomId: String) -> Unit,
    viewModel: FollowViewModel = viewModel(factory = FollowViewModel.Factory(androidx.compose.ui.platform.LocalContext.current)),
) {
    val follows by viewModel.sorted.collectAsState()
    val liveStatus by viewModel.liveStatus.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()

    var filter by remember { mutableStateOf(0) } // 0=全部 1=直播中 2=未开播

    val filtered = when (filter) {
        1 -> follows.filter { liveStatus["${it.platform}:${it.roomId}"] == true }
        2 -> follows.filter { liveStatus["${it.platform}:${it.roomId}"] != true }
        else -> follows
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("关注") },
            actions = {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(end = 12.dp),
                        strokeWidth = 2.dp
                    )
                }
                IconButton(onClick = { viewModel.refresh() }) {
                    Text("刷新", style = MaterialTheme.typography.labelLarge)
                }
            }
        )

        // 状态筛选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("全部" to 0, "直播中" to 1, "未开播" to 2).forEach { (label, value) ->
                FilterChip(
                    selected = filter == value,
                    onClick = { filter = value },
                    label = { Text(label) }
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (follows.isEmpty()) "暂无关注，去设置页粘贴直播间链接添加"
                    else "该状态下没有直播间",
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { "${it.platform}:${it.roomId}" }) { item ->
                    FollowItemCard(
                        item = item,
                        isLive = liveStatus["${item.platform}:${item.roomId}"] == true,
                        onClick = { onOpenRoom(item.platform, item.roomId) },
                        onToggleTop = { viewModel.toggleTop(item) },
                        onDelete = { viewModel.remove(item) },
                    )
                }
            }
        }
    }
}

@Composable
fun FollowItemCard(
    item: FollowItem,
    isLive: Boolean,
    onClick: () -> Unit,
    onToggleTop: () -> Unit,
    onDelete: () -> Unit,
) {
    val platformName = SiteManager.byId(item.platform)?.name ?: item.platform
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AsyncImage(
                model = item.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            if (isLive) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color(0xFF2ECC71), CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.userName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (isLive) Color(0xFF2ECC71) else Color(0xFF999999),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isLive) "直播中" else "未开播",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLive) Color(0xFF2ECC71) else MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    platformName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (item.title.isNotEmpty()) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggleTop) {
            Icon(
                if (item.top) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "置顶",
                tint = if (item.top) Color(0xFFF5A623) else MaterialTheme.colorScheme.outline
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
