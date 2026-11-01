package com.silm.live.core

/** 弹幕消息 */
data class DanmakuMessage(
    val type: Int,               // TYPE_CHAT / TYPE_ONLINE
    val userName: String,
    val message: String,
    val color: Long,             // 0xRRGGBB，0 表示默认白色
    val online: Long = 0,        // 在线人数（TYPE_ONLINE）
) {
    companion object {
        const val TYPE_CHAT = 0
        const val TYPE_ONLINE = 1
    }
}

data class LiveRoomDetail(
    val platform: String,
    val roomId: String,
    val title: String,
    val cover: String,
    val userName: String,
    val userAvatar: String,
    val online: Long,
    val status: Boolean,          // 是否直播中
    val url: String,
    val introduction: String = "",
    /** 弹幕客户端启动参数（各平台自定义类型） */
    val danmakuArgs: Any? = null,
    /** 平台内部播放信息（画质/线路计算用） */
    val extra: Any? = null,
)

/** 搜索结果：房间 */
data class SearchRoomItem(
    val roomId: String,
    val title: String,
    val cover: String,
    val userName: String,
    val online: Long = 0,
)

/** 搜索结果：主播 */
data class SearchAnchorItem(
    val roomId: String,
    val avatar: String,
    val userName: String,
    val liveStatus: Boolean,
)

data class SearchRoomResult(
    val hasMore: Boolean,
    val items: List<SearchRoomItem>,
)

data class SearchAnchorResult(
    val hasMore: Boolean,
    val items: List<SearchAnchorItem>,
)

data class LivePlayQuality(
    val quality: String,
    val sort: Int = 0,
    val data: Any,
)

data class LivePlayUrl(
    val urls: List<String>,
    val headers: Map<String, String> = emptyMap(),
)

class CoreError(message: String, val statusCode: Int = 0) : Exception(message)

fun numberToColor(color: Long): Long = if (color <= 0L) 0L else (color and 0xFFFFFF)
