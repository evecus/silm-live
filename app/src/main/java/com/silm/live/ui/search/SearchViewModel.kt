package com.silm.live.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silm.live.core.LiveSite
import com.silm.live.core.SearchAnchorItem
import com.silm.live.core.SearchRoomItem
import com.silm.live.core.SiteManager
import com.silm.live.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 搜索页状态：
 *  - searchType：0=房间 1=主播
 *  - siteId：当前平台
 *  - 结果按页追加，滚动到底自动加载下一页
 */
class SearchViewModel(private val context: Context) : ViewModel() {

    data class UiState(
        val searchType: Int = 0,
        val siteId: String = SiteManager.sites.firstOrNull()?.id ?: "",
        val keyword: String = "",
        val roomItems: List<SearchRoomItem> = emptyList(),
        val anchorItems: List<SearchAnchorItem> = emptyList(),
        val page: Int = 0,
        val hasMore: Boolean = false,
        val loading: Boolean = false,
        val error: String = "",
        val searched: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val loadMutex = Mutex()

    /** 平台 Tab 列表：按设置页「直播平台排序」排列（与关注页一致） */
    private val _sites = MutableStateFlow(SettingsStore.sitesInOrder(SettingsStore.defaultPlatformOrder()))
    val sites: StateFlow<List<LiveSite>> = _sites.asStateFlow()

    init {
        viewModelScope.launch {
            // 初次进入：默认选中排序后的第一个平台（用户尚未操作时）
            val first = SettingsStore.sitesInOrder(SettingsStore.platformOrder(context).first())
            _sites.value = first
            val s = _state.value
            if (!s.searched && first.isNotEmpty()) {
                _state.value = s.copy(siteId = first.first().id)
            }
        }
        viewModelScope.launch {
            SettingsStore.platformOrder(context).collect { _sites.value = SettingsStore.sitesInOrder(it) }
        }
    }

    fun setSearchType(type: Int) {
        if (_state.value.searchType == type) return
        _state.value = _state.value.copy(searchType = type, error = "")
        // 已有结果时切换类型立即重新搜索
        if (_state.value.searched && _state.value.keyword.isNotBlank()) {
            doSearch(_state.value.keyword)
        }
    }

    fun setSite(siteId: String) {
        if (_state.value.siteId == siteId) return
        _state.value = _state.value.copy(siteId = siteId, error = "")
        // 已有结果时切换平台立即重新搜索
        if (_state.value.searched && _state.value.keyword.isNotBlank()) {
            doSearch(_state.value.keyword)
        }
    }

    fun doSearch(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        viewModelScope.launch {
            loadMutex.withLock {
                _state.value = _state.value.copy(
                    keyword = kw,
                    roomItems = emptyList(),
                    anchorItems = emptyList(),
                    page = 0,
                    hasMore = false,
                    loading = true,
                    error = "",
                    searched = true,
                )
                loadPageLocked(1)
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore || s.keyword.isBlank()) return
        viewModelScope.launch {
            loadMutex.withLock {
                if (_state.value.loading) return@withLock
                _state.value = _state.value.copy(loading = true)
                loadPageLocked(s.page + 1)
            }
        }
    }

    private suspend fun loadPageLocked(page: Int) {
        val s = _state.value
        val site = SiteManager.byId(s.siteId)
        if (site == null) {
            _state.value = s.copy(loading = false, error = "平台不存在")
            return
        }
        try {
            if (s.searchType == 0) {
                val result = site.searchRooms(s.keyword, page)
                _state.value = _state.value.copy(
                    roomItems = _state.value.roomItems + result.items,
                    page = page,
                    hasMore = result.hasMore,
                    loading = false,
                    error = "",
                )
            } else {
                val result = site.searchAnchors(s.keyword, page)
                _state.value = _state.value.copy(
                    anchorItems = _state.value.anchorItems + result.items,
                    page = page,
                    hasMore = result.hasMore,
                    loading = false,
                    error = "",
                )
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                hasMore = false,
                error = e.message ?: "搜索失败",
            )
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(context.applicationContext) as T
    }
}
