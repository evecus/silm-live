package com.silm.live.core.sites

import com.silm.live.core.CoreError
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
import com.silm.live.core.md5Hex
import com.silm.live.danmaku.BiliBiliDanmaku
import com.silm.live.danmaku.BiliBiliDanmakuArgs
import com.silm.live.danmaku.DanmakuClient
import org.json.JSONObject

/**
 * 哔哩哔哩直播，接口逻辑移植自 dart_simple_live。
 */
class BiliBiliSite : LiveSite {
    override val id = "bilibili"
    override val name = "哔哩哔哩直播"

    private var buvid3 = ""
    private var buvid4 = ""

    private val lock = Any()

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "user-agent" to PlatformIdentity.BILI_UA,
        "referer" to "https://live.bilibili.com/",
    )

    private suspend fun getHeader(): Map<String, String> {
        if (buvid3.isEmpty()) getBuvid()
        return defaultHeaders() + mapOf("cookie" to "buvid3=$buvid3;buvid4=$buvid4;")
    }

    private suspend fun getBuvid() {
        try {
            val result = Http.getJson(
                "https://api.bilibili.com/x/frontend/finger/spi",
                defaultHeaders()
            )
            synchronized(lock) {
                buvid3 = result.optJSONObject("data")?.optString("b_3", "") ?: ""
                buvid4 = result.optJSONObject("data")?.optString("b_4", "") ?: ""
            }
        } catch (e: Exception) {
            // 忽略，使用空 buvid
        }
    }

    // ---------------- WBI 签名 ----------------

    private var imgKey = ""
    private var subKey = ""

    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52,
    )

    private suspend fun getWbiKeys() {
        if (imgKey.isNotEmpty() && subKey.isNotEmpty()) return
        val resp = Http.getJson("https://api.bilibili.com/x/web-interface/nav", defaultHeaders())
        val wbi = resp.optJSONObject("data")?.optJSONObject("wbi_img") ?: return
        val imgUrl = wbi.optString("img_url", "")
        val subUrl = wbi.optString("sub_url", "")
        imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        subKey = subUrl.substringAfterLast('/').substringBefore('.')
    }

    private suspend fun wbiSign(url: String): Map<String, String> {
        getWbiKeys()
        val mixinKey = (imgKey + subKey).mapIndexedNotNull { idx, _ ->
            mixinKeyEncTab.getOrNull(idx)
        }.map { (imgKey + subKey)[it] }.joinToString("").take(32)
        val currentTime = System.currentTimeMillis() / 1000

        val queryParams = mutableMapOf<String, String>()
        url.substringAfter('?', "").split("&").forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) queryParams[kv.substring(0, idx)] = kv.substring(idx + 1)
        }
        queryParams["wts"] = currentTime.toString()

        val sorted = queryParams.toSortedMap()
        val query = sorted.entries.joinToString("&") { (k, v) ->
            val value = v.filter { it !in "!'()*" }
            "$k=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
        queryParams["w_rid"] = md5Hex(query + mixinKey)
        return queryParams
    }

    // ---------------- 房间信息 ----------------

    private suspend fun getRoomInfo(roomId: String): JSONObject {
        val base = "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom"
        val params = wbiSign("$base?room_id=$roomId")
        val qs = params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        val result = Http.getJson("$base?$qs", getHeader())
        return result.optJSONObject("data") ?: throw CoreError("B站房间数据为空")
    }

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val roomInfo = getRoomInfo(roomId)
        val realRoomId = roomInfo.optJSONObject("room_info")?.optString("room_id", "") ?: roomId
        val roomInfoObj = roomInfo.optJSONObject("room_info") ?: JSONObject()

        // 弹幕信息（失败不阻塞进房）
        var token = ""
        var serverHost = "broadcastlv.chat.bilibili.com"
        try {
            val danmuBase =
                "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
            val params = wbiSign("$danmuBase?id=$realRoomId")
            val qs = params.entries.joinToString("&") {
                "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
            val danmuResult = Http.getJson("$danmuBase?$qs", getHeader())
            val danmuData = danmuResult.optJSONObject("data")
            if (danmuData != null) {
                token = danmuData.optString("token", "")
                val hosts = danmuData.optJSONArray("host_list")
                if (hosts != null && hosts.length() > 0) {
                    serverHost = hosts.getJSONObject(0).optString("host", serverHost)
                }
            }
        } catch (e: Exception) {
            // 忽略
        }

        val baseInfo = roomInfo.optJSONObject("anchor_info")?.optJSONObject("base_info")
        val liveStatus = roomInfoObj.optInt("live_status", 0)
        return LiveRoomDetail(
            platform = id,
            roomId = realRoomId,
            title = roomInfoObj.optString("title", ""),
            cover = roomInfoObj.optString("cover", ""),
            userName = baseInfo?.optString("uname", "") ?: "",
            userAvatar = (baseInfo?.optString("face", "") ?: "") + "@100w.jpg",
            online = roomInfoObj.optLong("online", 0),
            status = liveStatus == 1,
            url = "https://live.bilibili.com/$roomId",
            introduction = roomInfoObj.optString("description", ""),
            danmakuArgs = BiliBiliDanmakuArgs(
                roomId = realRoomId.toLongOrNull() ?: 0,
                uid = 0,
                token = token,
                serverHost = serverHost,
                buvid = buvid3,
            ),
        )
    }

    // ---------------- 播放信息 ----------------

    private suspend fun getRoomPlayInfo(query: Map<String, String>): JSONObject {
        val qs = query.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        val url = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?$qs"
        return Http.getJson(url, getHeader())
    }

    private fun readPlayUrl(result: JSONObject): JSONObject {
        val playUrl = result.optJSONObject("data")
            ?.optJSONObject("playurl_info")
            ?.optJSONObject("playurl")
        return playUrl ?: throw CoreError("B站播放信息响应异常，请稍后重试")
    }

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val result = getRoomPlayInfo(
            mapOf(
                "room_id" to detail.roomId,
                "protocol" to "0,1",
                "format" to "0,1,2",
                "codec" to "0,1",
                "platform" to "web",
            )
        )
        val playUrl = readPlayUrl(result)
        val qualitiesMap = mutableMapOf<Int, String>()
        playUrl.optJSONArray("g_qn_desc")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                qualitiesMap[item.optInt("qn")] = item.optString("desc", "未知")
            }
        }
        val qualities = mutableListOf<LivePlayQuality>()
        val streams = playUrl.optJSONArray("stream")
        if (streams != null && streams.length() > 0) {
            val formats = streams.getJSONObject(0).optJSONArray("format")
            if (formats != null && formats.length() > 0) {
                val codecs = formats.getJSONObject(0).optJSONArray("codec")
                if (codecs != null && codecs.length() > 0) {
                    val accept = codecs.getJSONObject(0).optJSONArray("accept_qn")
                    if (accept != null) {
                        for (i in 0 until accept.length()) {
                            val qn = accept.optInt(i)
                            qualities.add(
                                LivePlayQuality(
                                    quality = qualitiesMap[qn] ?: "未知清晰度",
                                    sort = qn,
                                    data = qn,
                                )
                            )
                        }
                    }
                }
            }
        }
        if (qualities.isEmpty()) throw CoreError("B站暂时无法获取播放清晰度，请稍后重试")
        return qualities
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val result = getRoomPlayInfo(
            mapOf(
                "room_id" to detail.roomId,
                "protocol" to "0,1",
                "format" to "0,2",
                "codec" to "0",
                "platform" to "web",
                "qn" to quality.data.toString(),
            )
        )
        val playUrl = readPlayUrl(result)
        val urls = mutableListOf<String>()
        playUrl.optJSONArray("stream")?.let { streams ->
            for (s in 0 until streams.length()) {
                val formats = streams.getJSONObject(s).optJSONArray("format") ?: continue
                for (f in 0 until formats.length()) {
                    val codecs = formats.getJSONObject(f).optJSONArray("codec") ?: continue
                    for (c in 0 until codecs.length()) {
                        val codec = codecs.getJSONObject(c)
                        val baseUrl = codec.optString("base_url", "")
                        val urlInfos = codec.optJSONArray("url_info") ?: continue
                        for (u in 0 until urlInfos.length()) {
                            val info = urlInfos.getJSONObject(u)
                            urls.add(info.optString("host", "") + baseUrl + info.optString("extra", ""))
                        }
                    }
                }
            }
        }
        // ExoPlayer 不支持 FLV：m3u8 排最前，mcdn 线路排后
        urls.sortWith(compareBy({ !it.contains("m3u8") }, { it.contains("mcdn") }))
        return LivePlayUrl(
            urls = urls,
            headers = mapOf(
                "referer" to "https://live.bilibili.com",
                "user-agent" to PlatformIdentity.BILI_ROOM_UA,
            ),
        )
    }

    override suspend fun getLiveStatus(roomId: String): Boolean {
        val result = Http.getJson(
            "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId",
            defaultHeaders()
        )
        return result.optJSONObject("data")?.optInt("live_status", 0) == 1
    }

    // ---------------- 搜索（移植自 simple_live_core） ----------------

    private fun stripEmTags(text: String): String =
        text.replace(Regex("<.*?em.*?>"), "")

    private suspend fun searchType(keyword: String, page: Int, searchType: String): JSONObject {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = "https://api.bilibili.com/x/web-interface/search/type" +
            "?context=&search_type=$searchType&cover_type=user_cover" +
            "&order=&keyword=$kw&category_id=&__refresh__=&_extra=" +
            "&highlight=0&single_column=0&page=$page"
        return Http.getJson(url, getHeader())
    }

    override suspend fun searchRooms(keyword: String, page: Int): SearchRoomResult {
        val result = searchType(keyword, page, "live")
        val items = mutableListOf<SearchRoomItem>()
        val rooms = result.optJSONObject("data")?.optJSONObject("result")
            ?.optJSONArray("live_room")
        rooms?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                items.add(
                    SearchRoomItem(
                        roomId = item.optString("roomid"),
                        title = stripEmTags(item.optString("title")),
                        cover = "https:${item.optString("cover")}@400w.jpg",
                        userName = item.optString("uname"),
                        online = item.optLong("online", 0),
                    )
                )
            }
        }
        return SearchRoomResult(hasMore = items.size >= 40, items = items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): SearchAnchorResult {
        val result = searchType(keyword, page, "live_user")
        val items = mutableListOf<SearchAnchorItem>()
        val users = result.optJSONObject("data")?.optJSONArray("result")
        users?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                items.add(
                    SearchAnchorItem(
                        roomId = item.optString("roomid"),
                        avatar = "https:${item.optString("uface")}@400w.jpg",
                        userName = stripEmTags(item.optString("uname")),
                        liveStatus = item.optBoolean("is_live", false),
                    )
                )
            }
        }
        return SearchAnchorResult(hasMore = items.size >= 40, items = items)
    }

    override fun createDanmakuClient(): DanmakuClient = BiliBiliDanmaku()
}
