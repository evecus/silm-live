package com.silm.live.core.sites

import android.content.Context
import com.silm.live.core.CoreError
import com.silm.live.data.SettingsStore
import com.silm.live.core.Http
import com.silm.live.core.LivePlayQuality
import com.silm.live.core.LivePlayUrl
import com.silm.live.core.LiveRoomDetail
import com.silm.live.core.LiveSite
import com.silm.live.core.SearchAnchorItem
import com.silm.live.core.SearchAnchorResult
import com.silm.live.core.SearchRoomItem
import com.silm.live.core.SearchRoomResult
import com.silm.live.core.PlatformIdentity
import com.silm.live.core.sign.JsSignEngine
import com.silm.live.danmaku.DanmakuClient
import com.silm.live.danmaku.DouyuDanmaku
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 斗鱼直播，接口逻辑移植自 dart_simple_live。
 * 播放签名依赖 QuickJS 引擎（JsSignEngine）执行服务端下发的加密脚本。
 */
class DouyuSite : LiveSite {
    override val id = "douyu"
    override val name = "斗鱼直播"

    /**
     * 设备号：优先用持久化值（跨启动稳定，避免"千万用户共享一个设备"触发风控），
     * 与 Flutter 版 App 层注入 deviceId 的做法对齐；取不到时退回进程内随机值。
     */
    private var deviceId: String = ""

    private suspend fun did(): String {
        if (deviceId.isEmpty()) {
            deviceId = runCatching { SettingsStore.getDouyuDid(context()) }
                .getOrDefault("")
                .ifEmpty { generateHexDid(32) }
        }
        return deviceId
    }

    private fun generateHexDid(len: Int): String {
        val random = SecureRandom()
        val sb = StringBuilder()
        repeat(len) { sb.append("0123456789abcdef"[random.nextInt(16)]) }
        return sb.toString()
    }

    /** 请求头：Cookie 携带与签名一致的设备号 */
    private suspend fun requestHeaders(): Map<String, String> = mapOf(
        "referer" to "https://www.douyu.com/",
        "user-agent" to PlatformIdentity.DOUYU_UA,
        "Cookie" to "dy_did=${did()}; acf_did=${did()}",
    )

    private fun context(): Context = appContext ?: throw CoreError("App 未初始化")

    companion object {
        @Volatile
        var appContext: Context? = null
    }

    override fun createDanmakuClient(): DanmakuClient = DouyuDanmaku()

    // ---------------- 房间信息 ----------------

    private suspend fun getRoomInfo(roomId: String): JSONObject {
        val result = Http.getText(
            "https://www.douyu.com/betard/$roomId",
            mapOf(
                "referer" to "https://www.douyu.com/$roomId",
                "user-agent" to PlatformIdentity.DOUYU_ROOM_UA,
            )
        )
        val json = JSONObject(result)
        val room = json.optJSONObject("room")
            ?: throw CoreError("斗鱼房间数据结构变更或房间不存在，请稍后重试")
        if (!room.has("room_id")) throw CoreError("斗鱼房间数据结构变更或房间不存在，请稍后重试")
        return room
    }

    /** 获取并执行签名脚本，返回播放参数 */
    private suspend fun getFreshPlayArgs(roomId: String): String {
        try {
            val jsEncResult = Http.getText(
                "https://www.douyu.com/swf_api/homeH5Enc?rids=$roomId",
                requestHeaders() + mapOf("referer" to "https://www.douyu.com/$roomId")
            )
            val decoded = JSONObject(jsEncResult)
            val script = decoded.optJSONObject("data")?.optString("room$roomId", "") ?: ""
            if (script.isNotEmpty()) {
                val signed = JsSignEngine.douyuSign(context(), script, roomId, did())
                if (signed.isNotEmpty()) return signed
            }
        } catch (e: Exception) {
            // 忽略，走失败
        }
        throw CoreError("斗鱼播放签名刷新失败")
    }

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val roomInfo = getRoomInfo(roomId)

        var showTime = ""
        try {
            val h5RoomInfo = Http.getJson(
                "https://www.douyu.com/swf_api/h5room/$roomId",
                requestHeaders() + mapOf("referer" to "https://www.douyu.com/$roomId")
            )
            showTime = h5RoomInfo.optJSONObject("data")?.optString("show_time", "") ?: ""
        } catch (e: Exception) {
        }

        val playArgs = getFreshPlayArgs(roomInfo.optString("room_id", roomId))

        return LiveRoomDetail(
            platform = id,
            roomId = roomInfo.optString("room_id", roomId),
            title = roomInfo.optString("room_name", ""),
            cover = roomInfo.optString("room_pic", ""),
            userName = roomInfo.optString("owner_name", ""),
            userAvatar = roomInfo.optString("owner_avatar", ""),
            online = roomInfo.optJSONObject("room_biz_all")?.optLong("hot", 0) ?: 0,
            status = roomInfo.optInt("show_status", 0) == 1 && roomInfo.optInt("videoLoop", 0) != 1,
            url = "https://www.douyu.com/$roomId",
            introduction = roomInfo.optString("show_details", ""),
            danmakuArgs = roomInfo.optString("room_id", roomId),
            extra = playArgs,
        )
    }

    // ---------------- 播放信息 ----------------

    private class DouyuPlayData(val rate: Int, val cdns: List<String>)

    private suspend fun postH5Play(roomId: String, args: String): JSONObject {
        return Http.postForm(
            "https://www.douyu.com/lapi/live/getH5Play/$roomId",
            args,
            requestHeaders() + mapOf("referer" to "https://www.douyu.com/$roomId")
        )
    }

    /** 带"重新签名重试"的 getH5Play：签名偶发失效（如 error=-9 时间戳错误）时自动重签再试一次 */
    private suspend fun postH5PlayFresh(roomId: String, suffix: String): JSONObject {
        val first = postH5Play(roomId, getFreshPlayArgs(roomId) + suffix)
        if (first.optInt("error", -1) == 0) return first
        val msg = first.optString("msg", "")
        android.util.Log.w("silm", "斗鱼 getH5Play 失败($msg)，重新签名重试")
        return postH5Play(roomId, getFreshPlayArgs(roomId) + suffix)
    }

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val result = postH5PlayFresh(detail.roomId, "&cdn=&rate=-1&ver=Douyu_223061205&iar=1&ive=1&hevc=0&fa=0")
        val errorCode = result.optInt("error", -1)
        if (errorCode != 0) {
            throw CoreError("斗鱼播放质量请求失败：${result.optString("msg", errorCode.toString())}")
        }
        val payload = result.optJSONObject("data") ?: throw CoreError("斗鱼返回了无效的播放质量")
        val cdnsWithName = payload.optJSONArray("cdnsWithName")
            ?: throw CoreError("斗鱼返回了无效的播放质量")
        val multirates = payload.optJSONArray("multirates")
            ?: throw CoreError("斗鱼返回了无效的播放质量")

        val cdns = mutableListOf<String>()
        for (i in 0 until cdnsWithName.length()) {
            cdns.add(cdnsWithName.getJSONObject(i).optString("cdn", ""))
        }
        // scdn 线路排后（Kotlin 升序：false 在前）
        cdns.sortBy { it.startsWith("scdn") }

        val qualities = mutableListOf<LivePlayQuality>()
        for (i in 0 until multirates.length()) {
            val item = multirates.getJSONObject(i)
            qualities.add(
                LivePlayQuality(
                    quality = item.optString("name", "未知"),
                    sort = item.optInt("rate", 0),
                    data = DouyuPlayData(item.optInt("rate", 0), cdns.toList()),
                )
            )
        }
        return qualities
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val data = quality.data as DouyuPlayData
        // 与 Flutter 一致：一次签名遍历所有 CDN（此前每个线路都重新拉脚本签名，
        // 既慢又容易触发风控；签名在短时间内对所有线路同样有效）
        for (attempt in 0..1) {
            val args = runCatching { getFreshPlayArgs(detail.roomId) }.getOrNull() ?: break
            val urls = mutableListOf<String>()
            for (cdn in data.cdns) {
                try {
                    val url = playUrlWith(detail.roomId, args, data.rate, cdn)
                    if (url.isEmpty()) continue
                    var finalUrl = url
                    if (finalUrl.contains("expire=300") && finalUrl.contains("fcdn=ws")) {
                        finalUrl += "&expire=0"
                    }
                    urls.add(finalUrl)
                } catch (e: Exception) {
                    // 单线路失败继续
                }
            }
            if (urls.isNotEmpty()) return LivePlayUrl(urls = urls)
        }
        throw CoreError("斗鱼未获取到播放地址")
    }

    private suspend fun playUrlWith(roomId: String, args: String, rate: Int, cdn: String): String {
        val result = postH5Play(roomId, args + "&cdn=$cdn&rate=$rate")
        val errorCode = result.optInt("error", -1)
        if (errorCode != 0) {
            throw CoreError("斗鱼播放地址请求失败：${result.optString("msg", errorCode.toString())}")
        }
        val data = result.optJSONObject("data") ?: throw CoreError("斗鱼返回了无效的播放地址")
        val rtmpUrl = data.optString("rtmp_url", "")
        // rtmp_live 里带 HTML 实体（&amp; 等），不还原会得到无法播放的地址
        val rtmpLive = htmlUnescape(data.optString("rtmp_live", ""))
        if (rtmpUrl.isEmpty() || rtmpLive.isEmpty()) throw CoreError("斗鱼返回了无效的播放地址")
        return "$rtmpUrl/$rtmpLive"
    }

    /** 还原常见 HTML 实体（斗鱼 rtmp_live 使用 &amp; 分隔参数） */
    private fun htmlUnescape(input: String): String {
        if (!input.contains("&")) return input
        val text = input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        // 数字实体 &#123; / &#x1F600;
        val numeric = Regex("&#(x?)([0-9a-fA-F]+);")
        return numeric.replace(text) { m ->
            val value = if (m.groupValues[1].isEmpty()) {
                m.groupValues[2].toIntOrNull()
            } else {
                m.groupValues[2].toIntOrNull(16)
            }
            value?.toChar()?.toString() ?: m.value
        }
    }

    override suspend fun getLiveStatus(roomId: String): Boolean {
        val roomInfo = getRoomInfo(roomId)
        return roomInfo.optInt("show_status", 0) == 1 && roomInfo.optInt("videoLoop", 0) != 1
    }

    // ---------------- 搜索（移植自 simple_live_core） ----------------

    private fun searchHeaders(): Map<String, String> = mapOf(
        "user-agent" to PlatformIdentity.DOUYU_SEARCH_UA,
        "referer" to "https://www.douyu.com/search/",
    )

    /** "103.5万" -> 1035000，解析失败返回 -999（与 Flutter 一致） */
    private fun parseHotNum(hn: String): Long {
        return try {
            var num = hn.replace("万", "").toDouble()
            if (hn.contains("万")) num *= 10000
            kotlin.math.round(num).toLong()
        } catch (_: Exception) {
            -999
        }
    }

    override suspend fun searchRooms(keyword: String, page: Int): SearchRoomResult {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val result = Http.getJson(
            "https://www.douyu.com/japi/search/api/searchShow?kw=$kw&page=$page&pageSize=20",
            searchHeaders()
        )
        if (result.optInt("error", -1) != 0) {
            throw CoreError(result.optString("msg", "斗鱼搜索失败"))
        }
        val items = mutableListOf<SearchRoomItem>()
        val arr = result.optJSONObject("data")?.optJSONArray("relateShow")
        arr?.let {
            for (i in 0 until it.length()) {
                val item = it.getJSONObject(i)
                items.add(
                    SearchRoomItem(
                        roomId = item.optString("rid"),
                        title = item.optString("roomName"),
                        cover = item.optString("roomSrc"),
                        userName = item.optString("nickName"),
                        online = parseHotNum(item.optString("hot")),
                    )
                )
            }
        }
        return SearchRoomResult(hasMore = items.isNotEmpty(), items = items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): SearchAnchorResult {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val result = Http.getJson(
            "https://www.douyu.com/japi/search/api/searchUser" +
                "?kw=$kw&page=$page&pageSize=20&filterType=1",
            searchHeaders()
        )
        val items = mutableListOf<SearchAnchorItem>()
        val arr = result.optJSONObject("data")?.optJSONArray("relateUser")
        arr?.let {
            for (i in 0 until it.length()) {
                val anchor = it.getJSONObject(i).optJSONObject("anchorInfo") ?: continue
                val isLive = anchor.optInt("isLive", 0) == 1
                val roomType = anchor.optInt("roomType", 0)
                items.add(
                    SearchAnchorItem(
                        roomId = anchor.optString("rid"),
                        avatar = anchor.optString("avatar"),
                        userName = anchor.optString("nickName"),
                        liveStatus = isLive && roomType == 0,
                    )
                )
            }
        }
        return SearchAnchorResult(hasMore = items.isNotEmpty(), items = items)
    }
}
