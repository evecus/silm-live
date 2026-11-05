package com.silm.live.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silm.live.core.SearchAnchorItem
import com.silm.live.core.SearchRoomItem
import com.silm.live.core.SiteManager
import com.silm.live.ui.AppLayout
import com.silm.live.ui.focusClickable
import com.silm.live.ui.tvFocusHighlight

/**
 * 搜索页（移植自 Flutter 版）：
 *  顶部：返回 + 类型下拉（房间/主播）+ 关键词输入 + 搜索按钮
 *  中部：平台 Tab
 *  结果：2 列卡片（房间）/ 列表（主播），点击进入播放页
 */
@Composable
fun SearchScreen(
    layout: AppLayout,
    onBack: () -> Unit,
    onOpenRoom: (platform: String, roomId: String) -> Unit,
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory(LocalContext.current)),
) {
    val isTv = layout == AppLayout.TV
    val state by viewModel.state.collectAsState()
    val sites by viewModel.sites.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    var keywordInput by remember { mutableStateOf("") }
    var typeMenuOpen by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }

    val doSearchAction = {
        keyboard?.hide()
        viewModel.doSearch(keywordInput)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部搜索栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

            // 类型下拉：房间 / 主播
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .focusClickable(isTv, 8.dp) { typeMenuOpen = true }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.searchType == 0) "房间" else "主播",
                        style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "选择搜索类型",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = typeMenuOpen,
                    onDismissRequest = { typeMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("房间", style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.tvFocusHighlight(
                            isTv, scale = 1.0f, cornerRadius = 8.dp, // 菜单窗口小, 不缩放避免边缘裁剪
                        ),
                        onClick = {
                            typeMenuOpen = false
                            viewModel.setSearchType(0)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("主播", style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.tvFocusHighlight(
                            isTv, scale = 1.0f, cornerRadius = 8.dp,
                        ),
                        onClick = {
                            typeMenuOpen = false
                            viewModel.setSearchType(1)
                        }
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            OutlinedTextField(
                value = keywordInput,
                onValueChange = { keywordInput = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocus),
                placeholder = {
                    Text(
                        "搜点什么吧",
                        style = if (isTv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearchAction() }),
                textStyle = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            )

            IconButton(
                onClick = { doSearchAction() },
                modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // 平台 Tab
        val selectedIndex = sites.indexOfFirst { it.id == state.siteId }.coerceAtLeast(0)
        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            edgePadding = 8.dp,
            containerColor = Color.Transparent,
            divider = {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            },
        ) {
            sites.forEach { site ->
                Tab(
                    selected = site.id == state.siteId,
                    onClick = { viewModel.setSite(site.id) },
                    text = {
                        Text(
                            site.name,
                            style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (site.id == state.siteId) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 8.dp)
                )
            }
        }

        // 结果区
        SearchResultArea(
            state = state,
            isTv = isTv,
            onLoadMore = { viewModel.loadMore() },
            onOpenRoom = onOpenRoom,
        )
    }
}

@Composable
private fun SearchResultArea(
    state: SearchViewModel.UiState,
    isTv: Boolean,
    onLoadMore: () -> Unit,
    onOpenRoom: (String, String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val site = SiteManager.byId(state.siteId)

    // 滚动到接近底部时自动加载下一页
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    if (state.loading && resultCount(state) == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val error = state.error
    if (error.isNotEmpty() && resultCount(state) == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                error,
                style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    if (state.searched && resultCount(state) == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (site == null) "平台不存在" else "没有找到相关内容",
                style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        return
    }

    if (!state.searched) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "输入关键词开始搜索",
                style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        return
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(if (isTv) 3 else 2),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.searchType == 0) {
            items(state.roomItems, key = { "${state.siteId}:${it.roomId}:${it.title}" }) { item ->
                RoomResultCard(
                    item = item,
                    isTv = isTv,
                    onClick = { onOpenRoom(state.siteId, item.roomId) },
                )
            }
        } else {
            items(state.anchorItems, key = { "${state.siteId}:${it.roomId}:${it.userName}" }) { item ->
                AnchorResultCard(
                    item = item,
                    isTv = isTv,
                    onClick = { onOpenRoom(state.siteId, item.roomId) },
                )
            }
        }
        if (state.loading && resultCount(state) > 0) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

private fun resultCount(state: SearchViewModel.UiState): Int =
    if (state.searchType == 0) state.roomItems.size else state.anchorItems.size

/** 房间卡片：封面 + 人气 + 标题 + 主播名（与 Flutter 版样式一致） */
@Composable
private fun RoomResultCard(
    item: SearchRoomItem,
    isTv: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .focusClickable(isTv, 12.dp, onClick = onClick)
    ) {
        Box {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isTv) 140.dp else 100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            if (item.online > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x88000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.LocalFireDepartment,
                        contentDescription = null,
                        tint = Color(0xFFFF7043),
                        modifier = Modifier.size(if (isTv) 16.dp else 13.dp)
                    )
                    Text(
                        formatOnline(item.online),
                        style = if (isTv) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        Text(
            item.title,
            style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            item.userName,
            style = if (isTv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
    }
}

/** 主播卡片：圆形头像 + 昵称 + 直播状态 */
@Composable
private fun AnchorResultCard(
    item: SearchAnchorItem,
    isTv: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .focusClickable(isTv, 12.dp, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (isTv) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = item.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(if (isTv) 76.dp else 56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            if (item.liveStatus) {
                Box(
                    modifier = Modifier
                        .size(if (isTv) 18.dp else 12.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color(0xFF2ECC71), CircleShape)
                )
            }
        }
        Spacer(Modifier.width(if (isTv) 16.dp else 12.dp))
        Column {
            Text(
                item.userName,
                style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.liveStatus) "直播中" else "未开播",
                style = if (isTv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelMedium,
                color = if (item.liveStatus) Color(0xFF2ECC71) else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 人气格式化：>=1万 显示 "x.x万" */
private fun formatOnline(online: Long): String = when {
    online >= 10000 -> String.format("%.1f万", online / 10000f)
    else -> online.toString()
}
