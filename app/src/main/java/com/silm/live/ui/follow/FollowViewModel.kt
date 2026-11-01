package com.silm.live.ui.follow

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silm.live.core.LiveRoomDetail
import com.silm.live.core.SiteManager
import com.silm.live.data.FollowItem
import com.silm.live.data.FollowStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class FollowViewModel(private val appContext: Context) : ViewModel() {

    private val _follows = MutableStateFlow<List<FollowItem>>(emptyList())
    val follows: StateFlow<List<FollowItem>> = _follows

    /** roomId(platform:roomId) -> 是否直播中 */
    private val _liveStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val liveStatus: StateFlow<Map<String, Boolean>> = _liveStatus

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _sorted = MutableStateFlow<List<FollowItem>>(emptyList())
    val sorted: StateFlow<List<FollowItem>> = _sorted

    init {
        viewModelScope.launch {
            FollowStore.follows(appContext).collect { list ->
                _follows.value = list
                _sorted.value = list
            }
        }
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _refreshing.value = true
            try {
                val list = FollowStore.current(appContext)
                val semaphore = Semaphore(4)
                val result = mutableMapOf<String, Boolean>()
                // 并发查询各房间开播状态
                val jobs = list.map { item ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val site = SiteManager.byId(item.platform) ?: return@withPermit
                                // 单项最多等 8 秒，避免个别房间拖垮整个刷新
                                val status = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                                    site.getLiveStatus(item.roomId)
                                } ?: return@withPermit
                                synchronized(result) {
                                    result["${item.platform}:${item.roomId}"] = status
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                jobs.forEach { it.join() }
                _liveStatus.value = result
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun addFollow(detail: LiveRoomDetail) {
        viewModelScope.launch {
            FollowStore.add(
                appContext,
                FollowItem(
                    platform = detail.platform,
                    roomId = detail.roomId,
                    userName = detail.userName,
                    avatar = detail.userAvatar,
                    title = detail.title,
                )
            )
        }
    }

    fun remove(item: FollowItem) {
        viewModelScope.launch { FollowStore.remove(appContext, item) }
    }

    fun toggleTop(item: FollowItem) {
        viewModelScope.launch { FollowStore.toggleTop(appContext, item) }
    }

    fun isFollowed(platform: String, roomId: String): Boolean =
        _follows.value.any { it.platform == platform && it.roomId == roomId }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FollowViewModel(context.applicationContext) as T
    }
}
