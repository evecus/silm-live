package com.silm.live.core.sites

import com.silm.live.core.CoreError
import com.silm.live.core.Http
import com.silm.live.core.LivePlayQuality
import com.silm.live.core.LivePlayUrl
import com.silm.live.core.LiveRoomDetail
import com.silm.live.core.LiveSite
import com.silm.live.core.PlatformIdentity
import com.silm.live.danmaku.DanmakuClient
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 小红书直播。
 *
 * 链路（沙箱实测验证）：
 *  - 必须用**移动端 UA** 请求 `https://www.xiaohongshu.com/livestream/{roomId}`：
 *    桌面 UA 下 SSR 的 `liveStream.roomData.roomInfo` 全空（roomStatus=-1），
 *    移动端 UA 才有完整房间数据（账号/海外 IP 均可）。
 *  - `roomInfo.status == 2` 表示正在直播；`pullConfig` 是 JSON 字符串，含
 *    `h264[]`（master_url + quality_type/quality_type_name）多线路（m3u8 与带签名 flv）。
 *  - 主播名在 `deeplink` 的 `host_nickname` 参数里（URL 编码）。
 *
 * 注意：不要再用"按房间号拼 live-source-play-hw 直链 + 200 即开播"的老办法——
 * 实测无效房间号同样返回 200 master playlist，会造成"未开播也显示在线"的假阳性。
 */
class XiaohongshuSite : LiveSite {

    override val id = "xiaohongshu"
    override val name = "小红书直播"

    /** 播放请求头：官方 Referer + 与取流一致的移动端 UA */
    private val playHeaders = mapOf(
        "user-agent" to PlatformIdentity.XHS_MOBILE_UA,
        "referer" to "https://www.xiaohongshu.com/",
    )

    private val pageHeaders = playHeaders + mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "accept-language" to "zh-CN,zh;q=0.9",
    )

    private class Stream(
        val codec: String,
        val qualityType: String,
        val qualityName: String,
        val url: String,
    )

    private class XhsData(val streams: List<Stream>)

    override fun createDanmakuClient(): DanmakuClient = object : DanmakuClient {
        override fun start(args: Any?, onMessage: (com.silm.live.core.DanmakuMessage) -> Unit, onClose: (String) -> Unit) {
            onClose("暂不支持小红书弹幕")
        }

        override fun stop() {}
    }

    // ---------------- 房间信息 ----------------

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val html = Http.getText(
            "https://www.xiaohongshu.com/livestream/$roomId",
            pageHeaders,
        )
        val state = parseInitialState(html)
            ?: throw CoreError("小红书直播间页面解析失败，请稍后再试")
        // 房间存在但已下播时，SSR 不会注入 roomInfo —— 判为未开播而不是报错，
        // 否则关注列表的开播检测会把"下播"显示成"房间号无效"
        val info = state.optJSONObject("liveStream")
            ?.optJSONObject("roomData")?.optJSONObject("roomInfo")
        if (info == null || info.optString("roomId", "").isEmpty()) return offlineDetail(roomId)
        val status = info.optInt("status", 0) == 2
        val deeplink = info.optString("deeplink", "")
        val hostName = decodeParam(deeplink, "host_nickname")
        val title = info.optString("roomTitle", "").ifEmpty { "小红书直播 $roomId" }
        val streams = parsePullConfig(info.opt("pullConfig"))
        if (status && streams.isEmpty()) {
            // 极端情况下 pullConfig 缺失：退回官方 CDN 直链规律兜底
            streams.addAll(fallbackStreams(roomId))
        }
        return LiveRoomDetail(
            platform = id,
            roomId = roomId,
            title = title,
            cover = info.optString("roomCover", ""),
            userName = hostName.ifEmpty { "小红书主播" },
            userAvatar = "",
            online = parseCount(info.optString("displayViewerCount", "")),
            status = status,
            url = "https://www.xiaohongshu.com/livestream/$roomId",
            introduction = "",
            extra = XhsData(streams),
        )
    }

    private fun offlineDetail(roomId: String) = LiveRoomDetail(
        platform = id,
        roomId = roomId,
        title = "小红书直播 $roomId",
        cover = "",
        userName = "小红书主播",
        userAvatar = "",
        online = 0,
        status = false,
        url = "https://www.xiaohongshu.com/livestream/$roomId",
        extra = XhsData(emptyList()),
    )

    // ---------------- 播放信息 ----------------

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val data = detail.extra as? XhsData ?: throw CoreError("小红书播放数据异常")
        if (data.streams.isEmpty()) throw CoreError("小红书主播未开播或未获取到播放地址")
        // 同 quality_type 的多条 URL 是线路不是画质，按画质去重
        val byQuality = linkedMapOf<String, MutableList<Stream>>()
        for (s in data.streams) byQuality.getOrPut(s.qualityType) { mutableListOf() }.add(s)
        return byQuality.map { (type, items) ->
            LivePlayQuality(
                quality = items.first().qualityName.ifEmpty { type },
                sort = qualityRank(type),
                data = items.sortedBy { if (it.url.contains(".m3u8")) 0 else 1 }.map { it.url },
            )
        }.sortedByDescending { it.sort }
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val urls = (quality.data as? List<*>)?.mapNotNull { it?.toString() }?.filter { it.isNotEmpty() }
            ?: throw CoreError("小红书未获取到播放地址")
        if (urls.isEmpty()) throw CoreError("小红书未获取到播放地址")
        return LivePlayUrl(urls = urls, headers = playHeaders)
    }

    override suspend fun getLiveStatus(roomId: String): Boolean =
        runCatching { getRoomDetail(roomId).status }.getOrDefault(false)

    // ---------------- 解析 ----------------

    /** pullConfig 是 JSON 字符串：{"h265":[],"h264":[{master_url,quality_type,quality_type_name}],...} */
    private fun parsePullConfig(raw: Any?): MutableList<Stream> {
        val text = when (raw) {
            is String -> raw
            is JSONObject -> raw.toString()
            else -> return mutableListOf()
        }
        if (text.isBlank()) return mutableListOf()
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return mutableListOf()
        val out = mutableListOf<Stream>()
        for (codec in listOf("h264", "h265")) {
            val arr = obj.optJSONArray(codec) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("master_url", "")
                if (url.isEmpty()) continue
                out.add(
                    Stream(
                        codec = codec,
                        qualityType = item.optString("quality_type", "HD"),
                        qualityName = item.optString("quality_type_name", ""),
                        // 官方下发的多为 http:// 源站地址；Android 9+ 默认禁止明文流量，
                        // 实测同源 https 可用（沙箱验证 200 + 完整 FLV 数据），统一升级为 https
                        url = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url,
                    )
                )
            }
        }
        return out
    }

    /** pullConfig 缺失时的兜底：官方 CDN 直链规律（.m3u8 / .flv） */
    private fun fallbackStreams(roomId: String): List<Stream> = listOf(
        Stream("h264", "HD", "原画", "https://live-source-play-hw.xhscdn.com/live/$roomId.m3u8"),
        Stream("h264", "HD", "原画", "https://live-source-play-hw.xhscdn.com/live/$roomId.flv"),
    )

    private fun qualityRank(type: String): Int = when (type.uppercase()) {
        "UHD", "4K" -> 4
        "HD" -> 3
        "SD" -> 2
        "LD" -> 1
        else -> 0
    }

    /** 从 deeplink/URL 中取指定参数值（自动 URL 解码） */
    private fun decodeParam(link: String, key: String): String {
        if (link.isBlank()) return ""
        val query = link.substringAfter('?', "")
        if (query.isEmpty()) return ""
        for (pair in query.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == key) {
                return runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
            }
        }
        return ""
    }

    private fun parseCount(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        val value = text.replace("万", "").replace("+", "").replace(",", "").toDoubleOrNull() ?: return 0
        return (if (text.contains("万")) value * 10000 else value).toLong()
    }

    /** 括号配平截取 `window.__INITIAL_STATE__=` 后的 JSON，并把 undefined 归一化 */
    private fun parseInitialState(html: String): JSONObject? {
        val key = "window.__INITIAL_STATE__="
        val start = html.indexOf(key)
        if (start < 0) return null
        var i = start + key.length
        var depth = 0
        var inStr = false
        var esc = false
        var begin = -1
        while (i < html.length) {
            val c = html[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when {
                    c == '"' -> inStr = true
                    c == '{' -> {
                        if (depth == 0) begin = i
                        depth++
                    }
                    c == '}' -> {
                        depth--
                        if (depth == 0 && begin >= 0) break
                    }
                }
            }
            i++
        }
        if (begin < 0 || depth != 0) return null
        val raw = html.substring(begin, i + 1)
        val fixed = Regex("([:{,\\[(]\\s*)undefined").replace(raw, "\$1null")
        return runCatching { JSONObject(fixed) }.getOrNull()
    }
}
