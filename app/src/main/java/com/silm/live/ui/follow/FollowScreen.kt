package com.silm.live.ui.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silm.live.core.SiteManager
import com.silm.live.data.FollowItem
import com.silm.live.data.SettingsStore
import com.silm.live.ui.AppLayout
import com.silm.live.ui.focusClickable
import com.silm.live.ui.tvFocusHighlight

/** 筛选项：platformId == null 表示「全部」 */
private data class PlatformTab(val platformId: String?, val label: String)

/** 平台显示名："抖音直播" -> "抖音" */
private fun platformLabel(platformId: String): String =
    SiteManager.byId(platformId)?.name?.removeSuffix("直播") ?: platformId

/**
 * 「全部」+ 当前关注里实际存在的平台。
 * 平台次序取设置页的「直播平台排序」（与搜索页一致），未知平台排在最后。
 */
private fun buildPlatformTabs(follows: List<FollowItem>, platformOrder: List<String>): List<PlatformTab> {
    val present = follows.map { it.platform }.toSet()
    val ordered = platformOrder.filter { it in present } +
        present.filter { it !in platformOrder }.sorted()
    return listOf(PlatformTab(null, "全部")) + ordered.map { PlatformTab(it, platformLabel(it)) }
}

/**
 * 首页（关注列表），无导航栏。
 *  - 手机：单列列表，头部「关注 + 刷新 + 设置图标」
 *  - 分类按直播平台（全部 / 抖音 / 虎牙 ...），直播中的主播自动排在未开播之前
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

    // 进入关注页：开关开启时才自动请求开播状态（每个 ViewModel 只做一次）
    LaunchedEffect(Unit) { viewModel.refreshOnEnter() }

    // 定时刷新：开关开启且间隔 > 0 时，仅在关注页可见（RESUMED）期间运行
    val autoRefresh by SettingsStore.followAutoRefresh(LocalContext.current).collectAsState(initial = true)
    val refreshMinutes by SettingsStore.followRefreshMinutes(LocalContext.current).collectAsState(initial = 15)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(autoRefresh, refreshMinutes, lifecycleOwner) {
        if (!autoRefresh || refreshMinutes <= 0) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(refreshMinutes * 60_000L)
                viewModel.refresh()
            }
        }
    }

    // null = 全部，否则为平台 id
    var filter by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val platformOrder by remember(context) { SettingsStore.platformOrder(context) }
        .collectAsState(initial = SettingsStore.defaultPlatformOrder())
    val tabs = remember(follows, platformOrder) { buildPlatformTabs(follows, platformOrder) }
    // 选中的平台已没有关注（如删除了最后一个）时回到「全部」
    LaunchedEffect(tabs) {
        if (filter != null && tabs.none { it.platformId == filter }) filter = null
    }

    // TV 焦点管理：进入页面自动聚焦首个关注项；列表为空时聚焦设置入口
    val firstItemFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(layout, follows.size) {
        if (layout == AppLayout.TV) {
            runCatching { firstItemFocus.requestFocus() }
                .onFailure { runCatching { settingsFocus.requestFocus() } }
        }
    }

    // 按平台筛选；直播中的排到未开播前面（sortedByDescending 稳定，组内保持置顶/添加顺序）。
    // liveStatus 刷新完成后会重新计算，因此开播状态获取后列表自动重排。
    val filtered = remember(follows, liveStatus, filter) {
        follows
            .filter { filter == null || it.platform == filter }
            .sortedByDescending { liveStatus["${it.platform}:${it.roomId}"] == true }
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

            FilterRow(tabs, filter, onSelect = { filter = it }, tv = false)

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
        // TV 侧边栏按显示顺序显式串联上下焦点（平台标签 → 搜索 → 设置 → 刷新），
        // 不依赖 Compose 的几何位置搜索，避免与右侧网格错位时某个按钮按下键无法下移。
        val searchFocus = remember { FocusRequester() }
        val sideSettingsFocus = remember { FocusRequester() }
        val refreshFocus = remember { FocusRequester() }
        val tabFocus = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
        val sideOrder = remember(tabFocus) { tabFocus + searchFocus + sideSettingsFocus + refreshFocus }
        // 当前获得焦点的侧边栏条目序号（-1 = 焦点不在侧边栏）
        var sideIndex by remember { mutableStateOf(-1) }
        fun sideNav(i: Int): Modifier =
            if (!isTv) Modifier else Modifier.focusRequester(sideOrder[i])
        fun sideFocused(i: Int): (Boolean) -> Unit = { f ->
            if (f) sideIndex = i else if (sideIndex == i) sideIndex = -1
        }
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(if (isTv) 250.dp else 180.dp)
                    .then(
                        if (!isTv) Modifier else Modifier.onPreviewKeyEvent { ev ->
                            // TV 侧边栏上下键：直接按显示顺序移动焦点，不走 Compose 的位置搜索
                            if (ev.type != KeyEventType.KeyDown || sideIndex < 0) return@onPreviewKeyEvent false
                            val target = when (ev.key) {
                                Key.DirectionDown -> sideIndex + 1
                                Key.DirectionUp -> sideIndex - 1
                                else -> return@onPreviewKeyEvent false
                            }
                            if (target in sideOrder.indices) {
                                runCatching { sideOrder[target].requestFocus() }
                                true
                            } else false
                        }
                    )
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

                // 平台多时可滚动，避免把下方 搜索/设置/刷新 挤出屏幕
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.forEachIndexed { tabIndex, tab ->
                        val selected = filter == tab.platformId
                        var focused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .then(sideNav(tabIndex))
                                .focusClickable(isTv, 10.dp, onFocused = {
                                    focused = it
                                    sideFocused(tabIndex)(it)
                                }) { filter = tab.platformId }
                                .background(
                                    // 焦点时底色透明, 让 tvFocusHighlight 的深灰底可见;
                                    // 选中(未聚焦) = 白底黑字 (对照 ubox TvButton), 不再用蓝色 primaryContainer
                                    // ——蓝色实底会把焦点描边和选中态混在一起.
                                    when {
                                        isTv && focused -> Color.Transparent
                                        selected -> if (isTv) Color.White
                                            else MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    }
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Text(
                                tab.label,
                                style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                                color = when {
                                    isTv && focused -> Color.White
                                    selected -> if (isTv) Color.Black
                                        else MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onBackground
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .then(sideNav(tabs.size))
                        .focusClickable(isTv, 10.dp, onFocused = sideFocused(tabs.size)) { onOpenSearch() }
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
                        .then(sideNav(tabs.size + 1))
                        .focusClickable(isTv, 10.dp, onFocused = sideFocused(tabs.size + 1)) { onOpenSettings() }
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
                        .then(sideNav(tabs.size + 2))
                        .focusClickable(isTv, 10.dp, onFocused = sideFocused(tabs.size + 2)) { viewModel.refresh() }
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
private fun FilterRow(
    tabs: List<PlatformTab>,
    current: String?,
    onSelect: (String?) -> Unit,
    tv: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { tab ->
            FilterChip(
                selected = current == tab.platformId,
                onClick = { onSelect(tab.platformId) },
                label = { Text(tab.label) },
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
                if (followsEmpty) "暂无关注，去设置页粘贴直播间链接添加" else "该平台下没有直播间",
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
            .focusClickable(tv, 12.dp, onClick = onClick)
            .then(
                if (tv) focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
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
