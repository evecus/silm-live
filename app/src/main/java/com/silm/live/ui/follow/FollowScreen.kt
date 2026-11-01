package com.silm.live.ui.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silm.live.core.SiteManager
import com.silm.live.data.FollowItem
import com.silm.live.ui.AppLayout
import com.silm.live.ui.tvFocusHighlight

private val filters = listOf("全部" to 0, "直播中" to 1, "未开播" to 2)

/**
 * 首页（关注列表），无导航栏。
 *  - 手机：单列列表，头部「关注 + 刷新 + 设置图标」
 *  - 平板：左侧固定筛选栏（含设置入口）+ 右侧 2 列网格
 *  - TV：左侧固定栏 + 3 列网格，全部控件带焦点高亮
 */
@Composable
fun FollowScreen(
    layout: AppLayout,
    onOpenRoom: (platform: String, roomId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit = {},
    viewModel: FollowViewModel = viewModel(factory = FollowViewModel.Factory(LocalContext.current)),
) {
    val isTv = layout == AppLayout.TV
    val follows by viewModel.sorted.collectAsState()
    val liveStatus by viewModel.liveStatus.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()

    var filter by remember { mutableStateOf(0) }

    // TV 焦点管理：进入页面自动聚焦首个关注项；列表为空时聚焦设置入口
    val firstItemFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(layout, follows.size) {
        if (layout == AppLayout.TV) {
            runCatching { firstItemFocus.requestFocus() }
                .onFailure { runCatching { settingsFocus.requestFocus() } }
        }
    }

    val filtered = when (filter) {
        1 -> follows.filter { liveStatus["${it.platform}:${it.roomId}"] == true }
        2 -> follows.filter { liveStatus["${it.platform}:${it.roomId}"] != true }
        else -> follows
    }

    if (layout == AppLayout.PHONE) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 紧凑头部：只应用一次状态栏内边距
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "关注",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "刷新",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { viewModel.refresh() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .then(if (isTv) Modifier.tvFocusHighlight(true, cornerRadius = 8.dp) else Modifier)
                )
                IconButton(
                    onClick = onOpenSearch,
                    modifier = Modifier.then(
                        if (isTv) Modifier.tvFocusHighlight(true, cornerRadius = 20.dp) else Modifier
                    )
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.then(
                        if (isTv) Modifier.tvFocusHighlight(true, cornerRadius = 20.dp)
                            .focusRequester(settingsFocus) else Modifier
                    )
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            FilterRow(filter, onSelect = { filter = it }, tv = false)

            FollowList(
                items = filtered,
                followsEmpty = follows.isEmpty(),
                layout = layout,
                firstFocus = if (isTv) firstItemFocus else null,
                liveStatus = liveStatus,
                onOpenRoom = onOpenRoom,
                onToggleTop = { viewModel.toggleTop(it) },
                onDelete = { viewModel.remove(it) },
            )
        }
    } else {
        // 平板 / TV：左侧固定栏
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(if (isTv) 250.dp else 180.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "关注",
                    style = if (isTv) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )

                filters.forEach { (label, value) ->
                    val selected = filter == value
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable { filter = value }
                            .then(if (isTv) Modifier.tvFocusHighlight(true, cornerRadius = 10.dp) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            label,
                            style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenSearch() }
                        .then(if (isTv) Modifier.tvFocusHighlight(true, cornerRadius = 10.dp) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "搜索",
                        modifier = Modifier.size(if (isTv) 30.dp else 24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "搜索",
                        style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenSettings() }
                        .then(if (isTv) Modifier.tvFocusHighlight(true, cornerRadius = 10.dp) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "设置",
                        modifier = Modifier.size(if (isTv) 30.dp else 24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "设置",
                        style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { viewModel.refresh() }
                        .then(if (isTv) Modifier.tvFocusHighlight(true, cornerRadius = 10.dp) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            "刷新",
                            style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            Box(modifier = Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars)) {
                FollowList(
                    items = filtered,
                    followsEmpty = follows.isEmpty(),
                    layout = layout,
                    firstFocus = if (isTv) firstItemFocus else null,
                    liveStatus = liveStatus,
                    onOpenRoom = onOpenRoom,
                    onToggleTop = { viewModel.toggleTop(it) },
                    onDelete = { viewModel.remove(it) },
                )
            }
        }
    }
}

@Composable
private fun FilterRow(current: Int, onSelect: (Int) -> Unit, tv: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { (label, value) ->
            FilterChip(
                selected = current == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.tvFocusHighlight(tv, cornerRadius = 8.dp)
            )
        }
    }
}

@Composable
private fun FollowList(
    items: List<FollowItem>,
    followsEmpty: Boolean,
    layout: AppLayout,
    firstFocus: FocusRequester? = null,
    liveStatus: Map<String, Boolean>,
    onOpenRoom: (String, String) -> Unit,
    onToggleTop: (FollowItem) -> Unit,
    onDelete: (FollowItem) -> Unit,
) {
    val isTv = layout == AppLayout.TV
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (followsEmpty) "暂无关注，去设置页粘贴直播间链接添加" else "该状态下没有直播间",
                style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        return
    }
    if (layout == AppLayout.PHONE) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items, key = { "${it.platform}:${it.roomId}" }) { item ->
                FollowItemCard(
                    item = item,
                    isLive = liveStatus["${item.platform}:${item.roomId}"] == true,
                    onClick = { onOpenRoom(item.platform, item.roomId) },
                    onToggleTop = { onToggleTop(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isTv) 3 else 2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 2.dp),
        ) {
            gridItems(items, key = { "${it.platform}:${it.roomId}" }) { item ->
                FollowItemCard(
                    item = item,
                    isLive = liveStatus["${item.platform}:${item.roomId}"] == true,
                    tv = isTv,
                    onClick = { onOpenRoom(item.platform, item.roomId) },
                    onToggleTop = { onToggleTop(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    }
}

@Composable
fun FollowItemCard(
    item: FollowItem,
    isLive: Boolean,
    tv: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onToggleTop: () -> Unit,
    onDelete: () -> Unit,
) {
    val platformName = SiteManager.byId(item.platform)?.name ?: item.platform
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(
                if (tv) Modifier
                    .tvFocusHighlight(true, cornerRadius = 12.dp)
                    .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                else Modifier
            )
            .padding(horizontal = if (tv) 20.dp else 16.dp, vertical = if (tv) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AsyncImage(
                model = item.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(if (tv) 76.dp else 52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            if (isLive) {
                Box(
                    modifier = Modifier
                        .size(if (tv) 18.dp else 12.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color(0xFF2ECC71), CircleShape)
                )
            }
        }
        Spacer(Modifier.width(if (tv) 16.dp else 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.userName,
                style = if (tv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(if (tv) 9.dp else 6.dp)
                        .background(
                            if (isLive) Color(0xFF2ECC71) else Color(0xFF999999),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isLive) "直播中" else "未开播",
                    style = if (tv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelMedium,
                    color = if (isLive) Color(0xFF2ECC71) else MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    platformName,
                    style = if (tv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (item.title.isNotEmpty()) {
                Text(
                    item.title,
                    style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // TV 端隐藏星标/垃圾桶（置顶与删除在手机/平板端操作），
        // 省出的空间让主播名/状态文字更大，且避免遥控器焦点落在次要操作上
        if (!tv) {
            IconButton(
                onClick = onToggleTop,
                modifier = Modifier.tvFocusHighlight(tv, cornerRadius = 20.dp)
            ) {
                Icon(
                    if (item.top) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "置顶",
                    tint = if (item.top) Color(0xFFF5A623) else MaterialTheme.colorScheme.outline
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.tvFocusHighlight(tv, cornerRadius = 20.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
