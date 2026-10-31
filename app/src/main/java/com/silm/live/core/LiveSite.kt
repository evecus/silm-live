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

    fun createDanmakuClient(): DanmakuClient
}

/** 平台注册与链接解析 */
object SiteManager {
    val sites: List<LiveSite> = listOf(
        com.silm.live.core.sites.BiliBiliSite(),
        com.silm.live.core.sites.DouyuSite(),
        com.silm.live.core.sites.HuyaSite(),
        com.silm.live.core.sites.DouyinSite(),
    )

    fun byId(id: String): LiveSite? = sites.firstOrNull { it.id == id }

    /** 平台名 -> 平台 id */
    fun byName(name: String): LiveSite? = sites.firstOrNull { s ->
        name.contains(s.name.removeSuffix("直播")) || s.id.equals(name, true)
    }

    /**
     * 从分享文本/链接解析出平台与房间号。
     * 支持：
     *  - live.bilibili.com/{roomId}
     *  - www.douyu.com/{roomId}
     *  - www.huya.com/{roomId}
     *  - live.douyin.com/{webRid}（含 v.douyin.com 短链跳转）
     *  - webcast.amemv.com/webcast/room/reflow?room_id=xxx
     */
    suspend fun parseUrl(text: String): Pair<LiveSite, String>? {
        var url = Regex("https?://[\\w./?%&=#:\\-]+").find(text)?.value
            ?: return textPure(text)
        // 抖音分享短链需要展开
        if (url.contains("v.douyin.com")) {
            url = Http.resolveUrl(url)
        }
        if (url.contains("webcast.amemv.com") || url.contains("webcast.amemv")) {
            val roomId = Regex("room_id=(\\d+)").find(url)?.groupValues?.get(1)
            val douyin = byId("douyin")
            if (roomId != null && douyin != null) return douyin to roomId
        }
        val rules = listOf(
            "bilibili" to Regex("live\\.bilibili\\.com/(\\d+)"),
            "douyu" to Regex("(?:www|m)\\.douyu\\.com/(\\d+)"),
            "huya" to Regex("(?:www|m)\\.huya\\.com/(\\d+)"),
            "douyin" to Regex("live\\.douyin\\.com/(\\d+)"),
        )
        for ((siteId, regex) in rules) {
            val m = regex.find(url) ?: continue
            val site = byId(siteId) ?: continue
            return site to m.groupValues[1]
        }
        return null
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
