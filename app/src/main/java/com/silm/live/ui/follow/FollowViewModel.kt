package com.silm.live.ui.follow

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silm.live.core.LiveRoomDetail
import com.silm.live.core.SiteManager
import com.silm.live.data.FollowItem
import com.silm.live.data.FollowStore
import com.silm.live.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    }

    private var enterRefreshDone = false

    /** 进入关注页时调用：仅在「自动刷新」开关开启、且本 ViewModel 尚未做过进入刷新时请求一次开播状态 */
    fun refreshOnEnter() {
        if (enterRefreshDone) return
        enterRefreshDone = true
        viewModelScope.launch {
            if (SettingsStore.followAutoRefresh(appContext).first()) refresh()
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _refreshing.value = true
            try {
                val list = FollowStore.current(appContext)
                // 与 Flutter 一致：并发数 = CPU 核数 × 2.5，限制在 4..20
                val concurrency = (Runtime.getRuntime().availableProcessors() * 2.5)
                    .toInt().coerceIn(4, 20)
                // 按平台交错排列，避免某个慢平台占满并发名额
                val queue = java.util.concurrent.ConcurrentLinkedQueue(interleaveByPlatform(list))
                // 与 Flutter 一致：全部查完后一次性更新，已开播的据此排到列表上面
                val result = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
                val workers = (1..concurrency).map {
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        while (true) {
                            val item = queue.poll() ?: break
                            try {
                                val site = SiteManager.byId(item.platform) ?: continue
                                // 单项最多等 8 秒，避免个别房间拖垮整个刷新
                                val status = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                                    site.getLiveStatus(item.roomId)
                                } ?: continue
                                val key = "${item.platform}:${item.roomId}"
                                result[key] = status
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                workers.forEach { it.join() }
                _liveStatus.value = result.toMap()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun interleaveByPlatform(list: List<FollowItem>): List<FollowItem> {
        val groups = list.groupBy { it.platform }.values.map { ArrayDeque(it) }
        val out = ArrayList<FollowItem>(list.size)
        while (groups.any { it.isNotEmpty() }) {
            groups.forEach { g -> if (g.isNotEmpty()) out.add(g.removeFirst()) }
        }
        return out
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
