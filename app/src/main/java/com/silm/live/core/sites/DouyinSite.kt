package com.silm.live.core.sites

import android.content.Context
import com.silm.live.core.CoreError
import com.silm.live.core.Http
import com.silm.live.core.LivePlayQuality
import com.silm.live.core.LivePlayUrl
import com.silm.live.core.LiveRoomDetail
import com.silm.live.core.LiveSite
import com.silm.live.core.PlatformIdentity
import com.silm.live.core.sign.JsSignEngine
import com.silm.live.danmaku.DanmakuClient
import com.silm.live.danmaku.DouyinDanmaku
import com.silm.live.danmaku.DouyinDanmakuArgs
import org.json.JSONObject
import kotlin.random.Random

/**
 * 抖音直播，接口逻辑移植自 dart_simple_live。
 * 依赖 WebView JS 引擎生成 a_bogus 签名。
 */
class DouyinSite : LiveSite {
    override val id = "douyin"
    override val name = "抖音直播"

    companion object {
        const val DEFAULT_UA = PlatformIdentity.DOUYIN_UA
        const val DEFAULT_REFERER = "https://live.douyin.com"
        const val DEFAULT_AUTHORITY = "live.douyin.com"

        /** 默认 ttwid（仅此字段即可拉流） */
        const val DEFAULT_COOKIE =
            "ttwid=1%7CB1qls3GdnZhUov9o2NxOMxxYS2ff6OSvEWbv0ytbES4%7C1680522049%7C280d802d6d478e3e78d0c807f7c487e7ffec0ae4e5fdd6a0fe74c3c6af149511"

        @Volatile
        var appContext: Context? = null
    }

    private fun context(): Context = appContext ?: throw CoreError("App 未初始化")

    private fun extractTtwid(cookie: String): String? =
        cookie.split(";").firstOrNull { it.trim().startsWith("ttwid=") }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }

    private fun playbackCookie(): String = extractTtwid(DEFAULT_COOKIE) ?: DEFAULT_COOKIE

    private fun requestHeaders(cookieValue: String? = playbackCookie()): Map<String, String> {
        val headers = mutableMapOf(
            "authority" to DEFAULT_AUTHORITY,
            "referer" to DEFAULT_REFERER,
            "user-agent" to DEFAULT_UA,
        )
        if (!cookieValue.isNullOrEmpty()) headers["cookie"] = cookieValue
        return headers
    }

    private fun generateRandomNumber(length: Int): Long {
        val sb = StringBuilder()
        repeat(length) { sb.append(Random.nextInt(10)) }
        return sb.toString().toLongOrNull() ?: Random.nextLong(1000000000)
    }

    override fun createDanmakuClient(): DanmakuClient = DouyinDanmaku()

    // ---------------- Cookie / 房间信息 ----------------

    /** 访问直播间页面取 ttwid/__ac_nonce/msToken，用于弹幕连接 */
    private suspend fun getWebCookie(webRid: String): String {
        var dyCookie = ""
        try {
            val headResp = Http.head(
                "https://live.douyin.com/$webRid",
                requestHeaders() + mapOf("referer" to "https://live.douyin.com/$webRid")
            )
            headResp.values("set-cookie").forEach { element ->
                val cookie = element.split(";")[0]
                if (cookie.contains("ttwid") || cookie.contains("__ac_nonce") || cookie.contains("msToken")) {
                    dyCookie += "$cookie;"
                }
            }
        } catch (e: Exception) {
        }
        if (dyCookie.isEmpty()) dyCookie = DEFAULT_COOKIE
        return dyCookie
    }

    private suspend fun getDanmakuCookie(webRid: String): String {
        val base = playbackCookie()
        return try {
            val webCookie = getWebCookie(webRid)
            mergeCookies(base, webCookie)
        } catch (e: Exception) {
            base
        }
    }

    private fun mergeCookies(vararg cookies: String): String {
        val map = linkedMapOf<String, String>()
        cookies.forEach { c ->
            c.split(";").forEach { part ->
                val item = part.trim()
                val idx = item.indexOf('=')
                if (idx > 0) {
                    map[item.substring(0, idx).trim()] = item.substring(idx + 1).trim()
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        return if (roomId.length <= 16) {
            getRoomDetailByWebRid(roomId)
        } else {
            getRoomDetailByRoomId(roomId)
        }
    }

    private suspend fun getRoomDetailByRoomId(roomId: String): LiveRoomDetail {
        val result = Http.getJson(
            "https://webcast.amemv.com/webcast/room/reflow/info/" +
                "?type_id=0&live_id=1&room_id=$roomId&sec_user_id=&version_code=99.99.99&app_id=6383",
            requestHeaders()
        )
        val room = result.optJSONObject("data")?.optJSONObject("room")
            ?: throw CoreError("抖音直播间数据为空，可能是房间不存在、未开播或被风控限制")
        val webRid = room.optJSONObject("owner")?.optString("web_rid", "") ?: ""
        if (room.optInt("status", 0) == 4 && webRid.isNotEmpty()) {
            return getRoomDetailByWebRid(webRid)
        }
        return buildDetailFromRoom(room, webRid, roomId)
    }

    private suspend fun getRoomDetailByWebRid(webRid: String): LiveRoomDetail {
        try {
            return getRoomDetailByWebRidApi(webRid)
        } catch (e: Exception) {
            if (e is CoreError && e.statusCode == 444) throw e
        }
        return getRoomDetailByWebRidHtml(webRid)
    }

    private suspend fun getRoomDetailByWebRidApi(webRid: String): LiveRoomDetail {
        val data = getRoomDataByApi(webRid)
        val roomData = data.optJSONArray("data")?.optJSONObject(0)
            ?: throw CoreError("抖音直播间数据为空，可能是房间不存在、未开播或被风控限制")
        val roomId = roomData.optString("id_str", "")
        val roomStatus = roomData.optInt("status", 0) == 2
        val danmakuCookie = getDanmakuCookie(webRid)
        val detail = buildDetailFromRoom(roomData, webRid, roomId)
        return detail.copy(
            danmakuArgs = DouyinDanmakuArgs(
                webRid = webRid,
                roomId = roomId,
                userId = generateRandomNumber(12).toString(),
                cookie = danmakuCookie,
            ),
            status = roomStatus,
        )
    }

    private suspend fun getRoomDetailByWebRidHtml(webRid: String): LiveRoomDetail {
        val roomData = getRoomDataByHtml(webRid)
        val roomStore = roomData.optJSONObject("roomStore")
            ?: throw CoreError("抖音直播间数据异常：roomStore 为空，可能该用户不存在或未开播")
        val roomInfo = roomStore.optJSONObject("roomInfo")
            ?: throw CoreError("抖音直播间数据异常：roomInfo 为空，可能该用户不存在或未开播")
        val room = roomInfo.optJSONObject("room")
            ?: throw CoreError("抖音直播间数据异常：房间数据为空，可能该用户 '$webRid' 不存在或从未开播")
        val roomId = room.optString("id_str", "")
        val roomStatus = room.optInt("status", 0) == 2
        val danmakuCookie = getDanmakuCookie(webRid)
        return buildDetailFromRoom(room, webRid, roomId).copy(
            danmakuArgs = DouyinDanmakuArgs(
                webRid = webRid,
                roomId = roomId,
                userId = generateRandomNumber(12).toString(),
                cookie = danmakuCookie,
            ),
            status = roomStatus,
        )
    }

    private fun buildDetailFromRoom(room: JSONObject, webRid: String, roomId: String): LiveRoomDetail {
        val owner = room.optJSONObject("owner") ?: JSONObject()
        val status = room.optInt("status", 0) == 2
        val cover = if (status) room.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0, "") ?: "" else ""
        return LiveRoomDetail(
            platform = id,
            roomId = webRid.ifEmpty { roomId },
            title = room.optString("title", ""),
            cover = cover,
            userName = owner.optString("nickname", ""),
            userAvatar = owner.optJSONObject("avatar_thumb")?.optJSONArray("url_list")
                ?.optString(0, "") ?: "",
            online = 0,
            status = status,
            url = "https://live.douyin.com/$webRid",
            introduction = owner.optString("signature", ""),
            danmakuArgs = null,
            extra = room.optJSONObject("stream_url"),
        )
    }

    /** webcast/room/web/enter 接口 */
    private suspend fun getRoomDataByApi(webRid: String): JSONObject {
        val cookieValue = playbackCookie()
        val requestHeader = requestHeaders(cookieValue) +
            mapOf("referer" to "https://live.douyin.com/$webRid")
        val uri = "https://live.douyin.com/webcast/room/web/enter/" +
            "?aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN" +
            "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome" +
            "&browser_version=125.0.0.0&web_rid=$webRid&msToken="
        val requestUrl = JsSignEngine.douyinABogusUrl(context(), uri, DEFAULT_UA)
        val result = Http.getJson(requestUrl, requestHeader)
        val data = result.optJSONObject("data")
            ?: throw CoreError("抖音直播间数据为空，请稍后再试")
        val rooms = data.optJSONArray("data")
        if (rooms == null || rooms.length() == 0) {
            throw CoreError("抖音直播间数据为空，可能是房间不存在、未开播或被风控限制")
        }
        return result.optJSONObject("data") ?: data
    }

    /** 直播间页面 state 数据 */
    private suspend fun getRoomDataByHtml(webRid: String): JSONObject {
        val dyCookie = getWebCookie(webRid)
        val result = Http.getText(
            "https://live.douyin.com/$webRid",
            mapOf(
                "authority" to DEFAULT_AUTHORITY,
                "referer" to DEFAULT_REFERER,
                "cookie" to dyCookie,
                "user-agent" to DEFAULT_UA,
            )
        )
        if (result.isBlank()) throw CoreError("抖音直播间页面返回为空，请稍后再试")
        if (!result.contains("\\\"state\\\"")) {
            throw CoreError("抖音直播间页面数据不可用，可能是访问受限或页面结构已变化")
        }
        parseStateFromPage(result)
            ?.let { return it }
        // 旧版页面格式兜底 (state 以 ]\n 结尾的整块 JSON)
        val match = Regex("\\{\\\\\"state\\\\\":\\{\\\\\"appStore.*?\\]\\n").find(result)?.value
            ?: throw CoreError("抖音直播间页面数据解析失败，请稍后再试")
        val str = match.trim()
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("]\\n", "")
        val renderDataJson = JSONObject(str)
        val state = renderDataJson.optJSONObject("state")
            ?: throw CoreError("抖音直播间页面状态数据异常")
        return state
    }

    /**
     * 新版页面 (React Server Components) 的 state 藏在
     * `self.__pace_f.push([1,"e:[\"$\",\"$L13\",null,{\"state\":{...}}])` 的转义字符串里,
     * 结尾不再是 `]\n`, 固定正则会失配. 这里定位 `\"state\":{` 后按转义层做括号配平,
     * 截出完整的 state 对象再反解 (与 python 验证一致).
     */
    private fun parseStateFromPage(page: String): JSONObject? {
        val key = "\\\"state\\\":{"
        val idx = page.indexOf(key)
        if (idx < 0) return null
        val start = idx + key.length - 1 // 指向 state 值的 '{'
        var depth = 0
        var i = start
        while (i < page.length) {
            val c = page[i]
            if (c == '\\') {
                i += 2 // 转义对整体跳过 (\", \\, \n ...)
                continue
            }
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val raw = page.substring(start, i + 1)
                        return try {
                            // 注意替换顺序: \" -> " 先做, \\ -> \ 后做
                            JSONObject(raw.replace("\\\"", "\"").replace("\\\\", "\\"))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    // ---------------- 播放信息 ----------------

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val qualities = mutableListOf<LivePlayQuality>()
        val streamUrl = detail.extra as? JSONObject ?: return qualities
        val liveCoreData = streamUrl.optJSONObject("live_core_sdk_data") ?: return qualities
        val pullData = liveCoreData.optJSONObject("pull_data") ?: return qualities
        val options = pullData.optJSONObject("options")
        val qualityList = options?.optJSONArray("qualities") ?: return qualities
        val streamData = pullData.optString("stream_data", "")

        if (!streamData.startsWith("{")) {
            val flvList = mutableListOf<String>()
            streamUrl.optJSONObject("flv_pull_url")?.keys()?.forEach { key ->
                streamUrl.optJSONObject("flv_pull_url")?.optString(key, "")?.let { flvList.add(it) }
            }
            val hlsList = mutableListOf<String>()
            streamUrl.optJSONObject("hls_pull_url_map")?.keys()?.forEach { key ->
                streamUrl.optJSONObject("hls_pull_url_map")?.optString(key, "")?.let { hlsList.add(it) }
            }
            for (i in 0 until qualityList.length()) {
                val q = qualityList.getJSONObject(i)
                val level = q.optInt("level", 0)
                val urls = mutableListOf<String>()
                val hlsIndex = hlsList.size - level
                if (hlsIndex in hlsList.indices) urls.add(hlsList[hlsIndex])
                val flvIndex = flvList.size - level
                if (flvIndex in flvList.indices) urls.add(flvList[flvIndex])
                if (urls.isNotEmpty()) {
                    qualities.add(LivePlayQuality(q.optString("name", "未知"), level, urls))
                }
            }
        } else {
            val qualityData = JSONObject(streamData).optJSONObject("data") ?: JSONObject()
            for (i in 0 until qualityList.length()) {
                val q = qualityList.getJSONObject(i)
                val sdkKey = q.optString("sdk_key", "")
                val urls = mutableListOf<String>()
                val main = qualityData.optJSONObject(sdkKey)?.optJSONObject("main")
                // ExoPlayer 不支持 FLV，HLS 排前
                main?.optString("hls", "")?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
                main?.optString("flv", "")?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
                if (urls.isNotEmpty()) {
                    qualities.add(LivePlayQuality(q.optString("name", "未知"), q.optInt("level", 0), urls))
                }
            }
        }
        qualities.sortByDescending { it.sort }
        return qualities
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        @Suppress("UNCHECKED_CAST")
        val urls = quality.data as? List<String> ?: emptyList()
        return LivePlayUrl(urls = urls.toList())
    }

    override suspend fun getLiveStatus(roomId: String): Boolean {
        val targetId = roomId.trim()
        if (targetId.isEmpty()) return false
        return try {
            if (targetId.length <= 16) {
                val data = getRoomDataByApi(targetId)
                val room = data.optJSONArray("data")?.optJSONObject(0)
                room?.optInt("status", 0) == 2
            } else {
                val result = Http.getJson(
                    "https://webcast.amemv.com/webcast/room/reflow/info/" +
                        "?type_id=0&live_id=1&room_id=$targetId&sec_user_id=&version_code=99.99.99&app_id=6383",
                    requestHeaders()
                )
                result.optJSONObject("data")?.optJSONObject("room")?.optInt("status", 0) == 2
            }
        } catch (e: Exception) {
            false
        }
    }
}
