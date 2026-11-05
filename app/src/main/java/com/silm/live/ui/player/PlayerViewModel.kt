package com.silm.live.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silm.live.core.DanmakuMessage
import com.silm.live.core.LivePlayQuality
import com.silm.live.core.LivePlayUrl
import com.silm.live.core.LiveRoomDetail
import com.silm.live.core.SiteManager
import com.silm.live.core.sites.DouyuSite
import com.silm.live.core.sites.DouyinSite
import com.silm.live.data.FollowStore
import com.silm.live.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val appContext: Context,
    val platform: String,
    val roomId: String,
) : ViewModel() {

    companion object {
        private const val TAG = "silm"

        /** 平台缺失时自动探测顺序（B站 short_id 机制最宽容，放最后） */
        private val PROBE_ORDER = listOf("douyu", "huya", "douyin", "kuaishou", "xiaohongshu", "bilibili")
    }

    /** 探测命中的平台（platform 参数无效时的兜底结果） */
    private var detectedSite: com.silm.live.core.LiveSite? = null

    private val site: com.silm.live.core.LiveSite?
        get() = detectedSite ?: SiteManager.byId(platform)

    private val _detail = MutableStateFlow<LiveRoomDetail?>(null)
    val detail: StateFlow<LiveRoomDetail?> = _detail

    private val _qualities = MutableStateFlow<List<LivePlayQuality>>(emptyList())
    val qualities: StateFlow<List<LivePlayQuality>> = _qualities

    private val _selectedQuality = MutableStateFlow<LivePlayQuality?>(null)
    val selectedQuality: StateFlow<LivePlayQuality?> = _selectedQuality

    private val _playUrl = MutableStateFlow<LivePlayUrl?>(null)
    val playUrl: StateFlow<LivePlayUrl?> = _playUrl

    private val _urlIndex = MutableStateFlow(0)
    val urlIndex: StateFlow<Int> = _urlIndex

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _danmaku = MutableStateFlow<List<DanmakuMessage>>(emptyList())
    val danmaku: StateFlow<List<DanmakuMessage>> = _danmaku

    /**
     * 弹幕事件流（供飘屏弹幕层消费）。
     * 列表 _danmaku 有 200 条上限会移除头部元素，按索引消费会错位；
     * 弹幕层改用无回放的事件流，保证不丢、不错位。
     */
    private val _danmakuEvents = MutableSharedFlow<DanmakuMessage>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val danmakuEvents: kotlinx.coroutines.flow.SharedFlow<DanmakuMessage> = _danmakuEvents

    private val _online = MutableStateFlow(0L)
    val online: StateFlow<Long> = _online

    private val _danmakuEnabled = MutableStateFlow(true)
    val danmakuEnabled: StateFlow<Boolean> = _danmakuEnabled

    /** 弹幕运行状态（屏幕小指示器用）：连接中 / 已连接 / 失败原因 */
    private val _danmakuStatus = MutableStateFlow("连接中")
    val danmakuStatus: StateFlow<String> = _danmakuStatus

    private val _danmakuCount = MutableStateFlow(0L)
    val danmakuCount: StateFlow<Long> = _danmakuCount

    private val _isFollowed = MutableStateFlow(false)
    val isFollowed: StateFlow<Boolean> = _isFollowed

    var danmakuClient: com.silm.live.danmaku.DanmakuClient? = null
        private set

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            SettingsStore.danmakuEnabled(appContext).collect {
                _danmakuEnabled.value = it
            }
        }
        viewModelScope.launch {
            _isFollowed.value = FollowStore.current(appContext)
                .any { it.platform == platform && it.roomId == roomId }
        }
        load()
    }

    private suspend fun resolveSite(): com.silm.live.core.LiveSite {
        SiteManager.byId(platform)?.let { return it }
        // platform 缺失或无效（如关注数据损坏）时，用房间号自动探测
        android.util.Log.w(TAG, "platform 无效[$platform]，自动探测 roomId=$roomId")
        for (id in PROBE_ORDER) {
            val s = SiteManager.byId(id) ?: continue
            try {
                val d = s.getRoomDetail(roomId)
                if (d.userName.isNotEmpty() || d.title.isNotEmpty()) {
                    android.util.Log.i(TAG, "探测命中平台: $id")
                    detectedSite = s
                    return s
                }
            } catch (e: Exception) {
                android.util.Log.d(TAG, "探测 $id 失败: ${e.message}")
            }
        }
        throw com.silm.live.core.CoreError(
            "无法识别该直播间（平台[$platform] 房间[$roomId]），请删除后从设置页重新解析链接添加"
        )
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _loading.value = true
            _error.value = null
            android.util.Log.i(TAG, "load platform=[$platform] roomId=[$roomId]")
            try {
                val s = resolveSite()
                // 斗鱼 did 持久化
                if (s.id == "douyu") {
                    SettingsStore.getDouyuDid(appContext)
                }
                val detail = s.getRoomDetail(roomId)
                android.util.Log.i(TAG, "detail ok: ${s.id} room=${detail.roomId} live=${detail.status}")
                _detail.value = detail
                _isFollowed.value = FollowStore.current(appContext)
                    .any { it.platform == platform && it.roomId == detail.roomId }
                startDanmaku(detail)
                if (!detail.status) {
                    _error.value = "当前未开播"
                    _loading.value = false
                    return@launch
                }
                val qualities = s.getPlayQualities(detail)
                _qualities.value = qualities
                // 按设置的默认清晰度档位选择（0=最高 1=较高 2=中等 3=较低 4=最低）
                val pref = SettingsStore.defaultQuality(appContext).first()
                val sortedDesc = qualities.sortedByDescending { it.sort }
                val idx = pref.coerceIn(0, sortedDesc.size - 1)
                android.util.Log.i(TAG, "清晰度档位=$pref/${sortedDesc.size} 选择=${sortedDesc.getOrNull(idx)?.quality}")
                sortedDesc.getOrNull(idx)?.let { selectQuality(it) }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "load 失败 platform=[$platform] roomId=[$roomId]", e)
                _error.value = e.message ?: "加载失败"
                _loading.value = false
            }
        }
    }

    fun selectQuality(quality: LivePlayQuality) {
        val s = site ?: return
        val detail = _detail.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _selectedQuality.value = quality
            _loading.value = true
            _error.value = null
            try {
                val urls = s.getPlayUrls(detail, quality)
                if (urls.urls.isEmpty()) throw com.silm.live.core.CoreError("未获取到播放地址")
                android.util.Log.i(TAG, "取流成功 ${s.id} qn=${quality.quality} 线路=${urls.urls.size} 首条=${urls.urls.firstOrNull()?.take(120)}")
                _playUrl.value = urls
                _urlIndex.value = 0
            } catch (e: Exception) {
                android.util.Log.e(TAG, "取流失败 ${s.id} qn=${quality.quality}", e)
                _error.value = e.message ?: "获取播放地址失败"
            } finally {
                _loading.value = false
            }
        }
    }

    /** 播放器层主动上报错误（重试耗尽等场景），显示到错误浮层 */
    fun surfaceError(msg: String) {
        _loading.value = false
        _error.value = msg
    }

    /** 当前地址播放失败时切换下一条线路 */
    fun nextUrl(): Boolean {
        val urls = _playUrl.value ?: return false
        val next = _urlIndex.value + 1
        if (next >= urls.urls.size) return false
        _urlIndex.value = next
        return true
    }

    /** 手动切换到指定线路（遥控器控制栏「线路」按钮使用） */
    fun selectLine(index: Int) {
        val urls = _playUrl.value ?: return
        if (index !in urls.urls.indices || index == _urlIndex.value) return
        _urlIndex.value = index
    }

    fun currentUrl(): Pair<String, Map<String, String>>? {
        val urls = _playUrl.value ?: return null
        val idx = _urlIndex.value
        if (idx >= urls.urls.size) return null
        return urls.urls[idx] to urls.headers
    }

    private fun startDanmaku(detail: LiveRoomDetail) {
        danmakuClient?.stop()
        val client = site?.createDanmakuClient() ?: return
        danmakuClient = client
        _danmaku.value = emptyList()
        _online.value = 0
        val args = detail.danmakuArgs ?: run {
            // 无弹幕参数时直接用房间号
            return
        }
        _danmakuStatus.value = "连接中"
        _danmakuCount.value = 0
        client.start(
            args,
            onMessage = { msg ->
                when (msg.type) {
                    DanmakuMessage.TYPE_CHAT -> {
                        val list = _danmaku.value.toMutableList()
                        list.add(msg)
                        if (list.size > 200) list.removeAt(0)
                        _danmaku.value = list
                        _danmakuEvents.tryEmit(msg)
                        _danmakuCount.value = _danmakuCount.value + 1
                        if (_danmakuStatus.value != "已连接") _danmakuStatus.value = "已连接"
                    }
                    DanmakuMessage.TYPE_ONLINE -> {
                        if (msg.online > 0) _online.value = msg.online
                    }
                }
            },
            onClose = { reason ->
                if (reason.isNotEmpty()) {
                    android.util.Log.w(TAG, "弹幕连接关闭: $reason")
                    if (_danmakuCount.value == 0L) _danmakuStatus.value = "连接失败"
                }
            },
        )
    }

    fun toggleDanmaku() {
        viewModelScope.launch {
            val next = !_danmakuEnabled.value
            _danmakuEnabled.value = next
            SettingsStore.setDanmakuEnabled(appContext, next)
        }
    }

    fun toggleFollow() {
        val detail = _detail.value ?: return
        viewModelScope.launch {
            val existing = FollowStore.current(appContext)
                .firstOrNull { it.platform == detail.platform && it.roomId == detail.roomId }
            if (existing != null) {
                FollowStore.remove(appContext, existing)
                _isFollowed.value = false
            } else {
                FollowStore.add(
                    appContext,
                    com.silm.live.data.FollowItem(
                        platform = detail.platform,
                        roomId = detail.roomId,
                        userName = detail.userName,
                        avatar = detail.userAvatar,
                        title = detail.title,
                    )
                )
                _isFollowed.value = true
            }
        }
    }

    override fun onCleared() {
        danmakuClient?.stop()
        super.onCleared()
    }

    class Factory(
        private val context: Context,
        private val platform: String,
        private val roomId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(context.applicationContext, platform, roomId) as T
    }
}
