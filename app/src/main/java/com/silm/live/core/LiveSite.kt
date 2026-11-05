package com.silm.live.core

import com.silm.live.danmaku.DanmakuClient

/** 直播平台抽象 */
interface LiveSite {
    val id: String
    val name: String

    suspend fun getRoomDetail(roomId: String): LiveRoomDetail

    suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality>

    suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl

    suspend fun getLiveStatus(roomId: String): Boolean

    /** 搜索房间（默认不支持，返回空） */
    suspend fun searchRooms(keyword: String, page: Int = 1): SearchRoomResult =
        SearchRoomResult(hasMore = false, items = emptyList())

    /** 搜索主播（默认不支持，返回空） */
    suspend fun searchAnchors(keyword: String, page: Int = 1): SearchAnchorResult =
        SearchAnchorResult(hasMore = false, items = emptyList())

    fun createDanmakuClient(): DanmakuClient
}

/** 平台注册与链接解析 */
object SiteManager {
    val sites: List<LiveSite> = listOf(
        com.silm.live.core.sites.BiliBiliSite(),
        com.silm.live.core.sites.DouyuSite(),
        com.silm.live.core.sites.HuyaSite(),
        com.silm.live.core.sites.DouyinSite(),
        com.silm.live.core.sites.KuaishouSite(),
        com.silm.live.core.sites.XiaohongshuSite(),
    )

    fun byId(id: String): LiveSite? = sites.firstOrNull { it.id == id }

    /** 平台名 -> 平台 id */
    fun byName(name: String): LiveSite? = sites.firstOrNull { s ->
        name.contains(s.name.removeSuffix("直播")) || s.id.equals(name, true)
    }

    /** 需要先跟随重定向才能拿到房间号的分享短链 host */
    private val shortLinkHosts = setOf("b23.tv", "v.douyin.com", "v.kuaishou.com", "xhslink.com")

    /** 短链展开的最大次数，防止短链之间互相跳转导致无限循环 */
    private const val MAX_SHORT_LINK_DEPTH = 3

    /** 房间号作为完整 path 段校验，含 "." 等字符的房间号不会被截断 */
    private val ROOM_ID = Regex("^[A-Za-z0-9._-]+$")
    private val NUMERIC_ROOM_ID = Regex("^\\d+$")
    private val KUAISHOU_ROOM_ID = Regex("^[A-Za-z0-9_-]+$")

    /**
     * 从分享文本/链接解析出平台与房间号。
     * 支持（对齐 simple_live_core 的 LiveUrlParser）：
     *  - live.bilibili.com/{roomId}（含 b23.tv 短链跳转）
     *  - (www|m).douyu.com/{roomId}、douyu.com/topic?rid={roomId}
     *  - (www|m).huya.com/{roomId}
     *  - live.douyin.com/{webRid}、www.douyin.com/live/{webRid}、
     *    www.douyin.com/follow/live/{webRid}（含 v.douyin.com 短链跳转）
     *  - *.amemv.com/webcast/room/reflow?room_id={roomId} 或 /reflow/{roomId}
     *  - live.kuaishou.com/u/{快手号}、(v.)m.chenzhongtech.com/fw/live/{快手号}
     *    （含 v.kuaishou.com 短链跳转）
     *  - www.xiaohongshu.com/livestream/{roomId}（含 xhslink.com 短链跳转，落点可能
     *    为 /livestream/{dynpath…}/{roomId} 两段形式）
     */
    suspend fun parseUrl(text: String): Pair<LiveSite, String>? {
        var url = Regex("https?://[\\w./?%&=#:\\-]+").find(text)?.value
            ?: return textPure(text)
        // 短链需要先展开，每一跳重新尝试匹配规则
        repeat(MAX_SHORT_LINK_DEPTH) {
            matchRoom(url)?.let { return it }
            if (!isShortLink(url)) return null
            val expanded = runCatching { Http.resolveUrl(url) }.getOrNull() ?: return null
            if (expanded == url) return null
            url = expanded
        }
        return matchRoom(url)
    }

    private fun isShortLink(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host in shortLinkHosts
    }

    /** 按 host + path 段提取站点与房间号，无法识别时返回 null */
    private fun matchRoom(url: String): Pair<LiveSite, String>? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val segments = uri.rawPath?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()
        val query = parseQuery(uri.rawQuery)

        // 快手：live.kuaishou.com/u/{快手号}
        if (host == "live.kuaishou.com") {
            return room("kuaishou", segments.getOrNull(1), segments.size == 2 && segments[0] == "u", KUAISHOU_ROOM_ID)
        }
        // 快手移动端：m.chenzhongtech.com/fw/live/{快手号}（短链展开后的落点，如 v.m.chenzhongtech.com）
        if (host == "m.chenzhongtech.com" || host.endsWith(".m.chenzhongtech.com")) {
            return room("kuaishou", segments.getOrNull(2), segments.size == 3 && segments[0] == "fw" && segments[1] == "live", KUAISHOU_ROOM_ID)
        }
        // B站
        if (host == "live.bilibili.com") {
            return room("bilibili", segments.firstOrNull(), segments.isNotEmpty(), ROOM_ID)
        }
        // 斗鱼：topic 页面房间号在 rid 参数里
        if (host == "douyu.com" || host.endsWith(".douyu.com")) {
            if (segments.firstOrNull() == "topic") {
                return room("douyu", query["rid"], true, NUMERIC_ROOM_ID)
            }
            return room("douyu", segments.firstOrNull(), segments.isNotEmpty(), ROOM_ID)
        }
        // 虎牙
        if (host == "huya.com" || host.endsWith(".huya.com")) {
            return room("huya", segments.firstOrNull(), segments.isNotEmpty(), ROOM_ID)
        }
        // 抖音：live.douyin.com/{webRid}
        if (host == "live.douyin.com") {
            return room("douyin", segments.firstOrNull(), segments.isNotEmpty(), ROOM_ID)
        }
        // 抖音网页分享：www.douyin.com/live/{webRid} 与 www.douyin.com/follow/live/{webRid}
        if (host == "douyin.com" || host.endsWith(".douyin.com")) {
            val webRid = when {
                segments.size == 2 && segments[0] == "live" -> segments[1]
                segments.size == 3 && segments[0] == "follow" && segments[1] == "live" -> segments[2]
                else -> null
            }
            return room("douyin", webRid, true, ROOM_ID)
        }
        // 抖音 webcast：*.amemv.com/.../reflow/{roomId} 或 ?room_id={roomId}
        if (host.endsWith(".amemv.com")) {
            val reflowIndex = segments.indexOf("reflow")
            val roomId = query["room_id"]
                ?: segments.getOrNull(reflowIndex + 1)?.takeIf { reflowIndex >= 0 }
            return room("douyin", roomId, true, NUMERIC_ROOM_ID)
        }
        // 小红书：/livestream/{roomId}；App 分享短链展开后为 /livestream/{dynpath…}/{roomId}
        if (host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com")) {
            if (segments.firstOrNull() == "livestream") {
                return room("xiaohongshu", segments.lastOrNull(), segments.size in 2..3, NUMERIC_ROOM_ID)
            }
            return null
        }
        return null
    }

    private fun room(siteId: String, roomId: String?, valid: Boolean, pattern: Regex): Pair<LiveSite, String>? {
        if (!valid || roomId == null || !pattern.matches(roomId)) return null
        val site = byId(siteId) ?: return null
        return site to roomId
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
    }

    /** 纯数字/含平台名的文本，如 "bilibili 12345" */
    private fun textPure(text: String): Pair<LiveSite, String>? {
        for (site in sites) {
            if (text.contains(site.id, ignoreCase = true) ||
                text.contains(site.name.removeSuffix("直播"))
            ) {
                val num = Regex("(\\d+)").find(text)?.groupValues?.get(1) ?: return null
                return site to num
            }
        }
        return null
    }
}
