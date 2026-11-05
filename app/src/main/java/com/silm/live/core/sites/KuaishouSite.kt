package com.silm.live.core.sites

import com.silm.live.core.AccountCookies
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
import com.silm.live.danmaku.DanmakuClient
import com.silm.live.danmaku.KuaishouDanmaku
import com.silm.live.danmaku.KuaishouDanmakuArgs
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Random

/**
 * 快手直播。
 *
 * 链路（对齐 simple_live_core 的 kuaishou_site.dart）：
 *  1. GET https://live.kuaishou.com/u/{快手号} （网页端 UA，匿名即可拿到播放地址）
 *  2. 页面 `window.__INITIAL_STATE__.liveroom.playList[]` 携带
 *     `liveStream.playUrls.h264.adaptationSet.representation[]` —— 各清晰度 FLV 直链
 *  3. 弹幕需要 `token + websocketUrls`：页面未下发时补请求
 *     `live_api/liveroom/websocketinfo`（匿名态常返回 result=2，即需要登录态）
 *
 * 房间号约定：分享链接 /u/ 后的快手号（如 w15044543808 / 3xcrixzhwxx94i4）。
 */
class KuaishouSite : LiveSite {

    override val id = "kuaishou"
    override val name = "快手直播"

    private val userAgent = PlatformIdentity.KUAISHOU_UA

    /** 进程内保存的 Cookie（首次访问房间页由 Set-Cookie 下发，如 kuaishou.live.bfb1s） */
    @Volatile
    private var cookieHeader: String = ""

    private val imageExtensions = setOf(
        "svgz", "pjp", "png", "ico", "avif", "tiff", "tif", "jfif", "svg", "xbm",
        "pjpeg", "webp", "jpg", "jpeg", "bmp", "gif",
    )

    private fun baseHeaders(): Map<String, String> = mapOf(
        "user-agent" to userAgent,
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
        "accept-language" to "zh-CN,zh;q=0.9",
        "connection" to "keep-alive",
        "sec-ch-ua" to "Google Chrome;v=120, Chromium;v=120, Not=A?Brand;v=24",
        "sec-ch-ua-platform" to "Windows",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
    )

    private fun headersWithCookie(): Map<String, String> =
        if (cookieHeader.isBlank()) baseHeaders() else baseHeaders() + ("cookie" to cookieHeader)

    override fun createDanmakuClient(): DanmakuClient = KuaishouDanmaku()

    // ---------------- 房间信息 ----------------

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val url = "https://live.kuaishou.com/u/$roomId"

        var anonymous: LiveRoomDetail? = null
        val first = runCatching { Http.getTextWithHeaders(url, baseHeaders()) }.getOrNull()
        if (first != null) {
            saveCookie(first.headers.values("Set-Cookie"))
            anonymous = runCatching { parseRoomDetail(first.body, roomId) }.getOrNull()
        }
        if (anonymous?.status == true) return anonymous

        // 匿名拿不到在播状态时，带上站点 Cookie 再试一次
        if (cookieHeader.isNotBlank()) {
            val second = runCatching { Http.getTextWithHeaders(url, headersWithCookie()) }.getOrNull()
            val cookieDetail = second?.let { runCatching { parseRoomDetail(it.body, roomId) }.getOrNull() }
            if (cookieDetail != null && (cookieDetail.status || anonymous == null)) return cookieDetail
        }
        return anonymous ?: throw CoreError("快手直播间状态暂时不可用")
    }

    private suspend fun parseRoomDetail(text: String, roomId: String): LiveRoomDetail? {
        val state = parseInitialState(text) ?: return null
        val liveroom = state.optJSONObject("liveroom") ?: return null
        val playList = liveroom.optJSONArray("playList") ?: return null
        if (playList.length() == 0) return offlineDetail(roomId)

        val first = selectLiveRoom(playList, roomId) ?: return null
        val liveStream = first.optJSONObject("liveStream") ?: JSONObject()
        val author = first.optJSONObject("author") ?: JSONObject()
        val gameInfo = first.optJSONObject("gameInfo") ?: JSONObject()

        val isLiving = resolveLiveStatus(first)
        val liveStreamId = liveStream.optString("id", "")
        val realRoomId = author.optString("id", roomId).ifEmpty { roomId }

        val websocketUrls = stringArray(liveroom.optJSONArray("websocketUrls")).toMutableList()
        var token = liveroom.optString("token", "")
        val wsInfo = first.optJSONObject("websocketInfo")
        if (wsInfo != null) {
            if (token.isEmpty()) token = wsInfo.optString("token", "")
            if (websocketUrls.isEmpty()) {
                websocketUrls.addAll(stringArray(wsInfo.optJSONArray("websocketUrls")))
                if (websocketUrls.isEmpty()) {
                    websocketUrls.addAll(stringArray(wsInfo.optJSONArray("webSocketAddresses")))
                }
            }
        }
        if (isLiving && liveStreamId.isNotEmpty() && (token.isEmpty() || websocketUrls.isEmpty())) {
            val info = fetchWebsocketInfo(realRoomId, liveStreamId)
            if (token.isEmpty() && info.first.isNotEmpty()) token = info.first
            if (websocketUrls.isEmpty()) websocketUrls.addAll(info.second)
        }

        var cover = liveStream.optString("poster", "")
        if (cover.isNotEmpty() && !isImage(cover)) cover = "$cover.jpg"

        return LiveRoomDetail(
            platform = id,
            roomId = realRoomId,
            title = resolveRoomTitle(first, liveStream, gameInfo, author).ifEmpty { "${author.optString("name")}的直播间" },
            cover = cover,
            userName = author.optString("name", roomId),
            userAvatar = author.optString("avatar", ""),
            online = if (isLiving) parseCount(gameInfo.optString("watchingCount", gameInfo.opt("watchingCount")?.toString() ?: "")) else 0,
            status = isLiving,
            url = "https://live.kuaishou.com/u/$realRoomId",
            introduction = author.optString("description", ""),
            danmakuArgs = KuaishouDanmakuArgs(
                roomId = realRoomId,
                liveStreamId = liveStreamId,
                token = token,
                websocketUrls = websocketUrls,
                pageId = generatePageId(),
                expTag = liveStream.optString("expTag", ""),
                attach = first.optString("expTag", ""),
                cookie = cookieHeader,
                userAgent = userAgent,
            ),
            extra = liveStream.opt("playUrls"),
        )
    }

    private fun offlineDetail(roomId: String) = LiveRoomDetail(
        platform = id,
        roomId = roomId,
        title = "",
        cover = "",
        userName = "",
        userAvatar = "",
        online = 0,
        status = false,
        url = "https://live.kuaishou.com/u/$roomId",
    )

    /** 页面未下发弹幕凭证时的补充请求；匿名态通常返回 result=2（需登录） */
    private suspend fun fetchWebsocketInfo(roomId: String, liveStreamId: String): Pair<String, List<String>> {
        return try {
            val headers = headersWithCookie() + mapOf(
                "accept" to "application/json, text/plain, */*",
                "referer" to "https://live.kuaishou.com/u/$roomId",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
            )
            val kww = resolveServerKww(cookieHeader)
            val json = Http.getJson(
                "https://live.kuaishou.com/live_api/liveroom/websocketinfo?liveStreamId=$liveStreamId",
                if (kww.isNotEmpty()) headers + ("Kww" to kww) else headers,
            )
            val data = json.optJSONObject("data") ?: return "" to emptyList()
            val token = data.optString("token", "")
            val urls = stringArray(data.optJSONArray("websocketUrls")).ifEmpty {
                stringArray(data.optJSONArray("webSocketAddresses"))
            }
            android.util.Log.i("silm", "快手 websocketinfo: result=${data.optInt("result", -1)} token=${token.isNotEmpty()} urls=${urls.size}")
            token to urls
        } catch (e: Exception) {
            "" to emptyList()
        }
    }

    // ---------------- 播放信息 ----------------

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val playUrls = detail.extra as? JSONObject
            ?: throw CoreError("快手播放数据异常")
        val h264 = playUrls.optJSONObject("h264")
            ?: playUrls.optJSONArray("h264")?.optJSONObject(0)
            ?: throw CoreError("快手未获取到清晰度列表")
        val reps = h264.optJSONObject("adaptationSet")?.optJSONArray("representation")
            ?: throw CoreError("快手未获取到清晰度列表")
        val qualities = mutableListOf<LivePlayQuality>()
        for (i in 0 until reps.length()) {
            val rep = reps.optJSONObject(i) ?: continue
            if (rep.optBoolean("hidden", false)) continue
            val url = rep.optString("url", "")
            if (url.isEmpty()) continue
            val level = rep.optInt("level", 0)
            // hevc 同档线路作为备用，排在 h264 之后
            val backups = hevcUrl(playUrls, level)
            qualities.add(
                LivePlayQuality(
                    quality = rep.optString("name", "未知"),
                    sort = level,
                    data = listOf(url) + backups,
                )
            )
        }
        if (qualities.isEmpty()) throw CoreError("快手未获取到清晰度列表")
        qualities.sortByDescending { it.sort }
        return qualities
    }

    private fun hevcUrl(playUrls: JSONObject, level: Int): List<String> {
        val hevc = playUrls.optJSONObject("hevc") ?: return emptyList()
        val reps = hevc.optJSONObject("adaptationSet")?.optJSONArray("representation") ?: return emptyList()
        for (i in 0 until reps.length()) {
            val rep = reps.optJSONObject(i) ?: continue
            if (rep.optInt("level", -1) == level) {
                val url = rep.optString("url", "")
                if (url.isNotEmpty()) return listOf(url)
            }
        }
        return emptyList()
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val urls = (quality.data as? List<*>)?.mapNotNull { it?.toString() }?.filter { it.isNotEmpty() }
            ?: throw CoreError("快手未获取到播放地址")
        if (urls.isEmpty()) throw CoreError("快手未获取到播放地址")
        return LivePlayUrl(urls = urls, headers = mapOf("referer" to "https://live.kuaishou.com/"))
    }

    override suspend fun getLiveStatus(roomId: String): Boolean =
        runCatching { getRoomDetail(roomId).status }.getOrDefault(false)

    // ---------------- 工具 ----------------

    /** playList 里挑选目标房间：优先 author.id 匹配，其次取在播的那一条 */
    private fun selectLiveRoom(playList: JSONArray, roomId: String): JSONObject? {
        val rooms = (0 until playList.length()).mapNotNull { playList.optJSONObject(it) }
        if (rooms.isEmpty()) return null
        val target = roomId.trim()
        val matched = if (target.isEmpty()) rooms else rooms.filter {
            (it.optJSONObject("author")?.optString("id") ?: "").trim() == target
        }
        val candidates = matched.ifEmpty { rooms }
        return candidates.firstOrNull { resolveLiveStatus(it) } ?: candidates.firstOrNull()
    }

    /** 在播判定：显式标志位，或有效 liveStream id（切 CDN 时 playUrls 可能短暂缺失） */
    private fun resolveLiveStatus(room: JSONObject): Boolean {
        if (isLiveFlag(room.opt("isLiving")) || isLiveFlag(room.opt("living"))) return true
        val liveStream = room.optJSONObject("liveStream") ?: room
        return liveStream.optString("id", "").isNotBlank()
    }

    private fun isLiveFlag(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() == 1
        is String -> value == "1" || value.equals("true", true)
        else -> false
    }

    private fun resolveRoomTitle(
        room: JSONObject,
        liveStream: JSONObject,
        gameInfo: JSONObject,
        author: JSONObject,
    ): String {
        for (value in listOf(
            room.optString("caption", ""),
            room.optString("title", ""),
            liveStream.optString("caption", ""),
            liveStream.optString("title", ""),
            gameInfo.optString("name", ""),
            author.optString("name", ""),
        )) {
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun isImage(url: String): Boolean {
        val path = url.substringBefore("?")
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    private fun parseCount(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        val value = text.replace("万", "").replace(",", "").toDoubleOrNull() ?: return 0
        return (if (text.contains("万")) value * 10000 else value).toLong()
    }

    private fun stringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { v -> v.isNotEmpty() } }
    }

    private fun resolveServerKww(cookie: String): String {
        for (part in cookie.split(";")) {
            val item = part.trim()
            if (!item.startsWith("kwfv1=")) continue
            val value = item.removePrefix("kwfv1=").trim()
            if (value.isEmpty()) continue
            return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value) + "###ssrc"
        }
        return ""
    }

    private fun saveCookie(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val map = linkedMapOf<String, String>()
        cookieHeader.split(";").forEach { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) map[kv[0].trim()] = kv[1].trim()
        }
        setCookies.forEach { raw ->
            val kv = raw.substringBefore(";").split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) map[kv[0].trim()] = kv[1].trim()
        }
        cookieHeader = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun generatePageId(): String {
        val chars = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict"
        val random = Random()
        return (0 until 16).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * 提取 `window.__INITIAL_STATE__`：括号配平截取（JSON 里含字符串与转义），
     * 再把 JS 的 `undefined` 字面量替换为 null。
     */
    private fun parseInitialState(html: String): JSONObject? {
        val key = "window.__INITIAL_STATE__="
        val start = html.indexOf(key)
        if (start < 0) return null
        var i = start + key.length
        var depth = 0
        var inStr = false
        var esc = false
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
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
            }
            i++
        }
        if (depth != 0) return null
        val raw = html.substring(start + key.length, i + 1)
        val fixed = Regex("([:{,\\[(]\\s*)undefined").replace(raw, "\$1null")
        return runCatching { JSONObject(fixed) }.getOrNull()
    }
    // ---------------- 搜索（移植自 simple_live_core） ----------------

    private fun searchHeaders(keyword: String): Map<String, String> = headersWithCookie() + mapOf(
        "accept" to "application/json, text/plain, */*",
        "referer" to "https://live.kuaishou.com/search?keyword=" +
            java.net.URLEncoder.encode(keyword, "UTF-8"),
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
    )

    override suspend fun searchRooms(keyword: String, page: Int): SearchRoomResult {
        warmUpSearchCookie(keyword)
        // 主路：search/liveStream；为空或失败时回退 overview（与 Flutter 一致）
        val primary = runCatching { searchLiveStreams(keyword, page) }.getOrNull()
        if (primary != null && (primary.items.isNotEmpty() || page > 1)) return primary
        if (page > 1) return SearchRoomResult(hasMore = false, items = emptyList())
        val fallback = runCatching { searchRoomsByOverview(keyword) }.getOrNull()
        return fallback ?: SearchRoomResult(hasMore = false, items = emptyList())
    }

    /**
     * 搜索预热（对齐 Flutter `_getCookie` + 登录 Cookie 合并）：
     *  - 用户登录 Cookie 优先合并（搜索接口对匿名请求做风控，返回 result=10「服务器繁忙」）
     *  - 进程内尚无 Cookie 时先访问搜索页，收集 Set-Cookie（did 等）再发起搜索
     */
    private suspend fun warmUpSearchCookie(keyword: String) {
        if (AccountCookies.kuaishou.isNotBlank()) {
            mergeCookieHeader(AccountCookies.kuaishou)
        }
        if (cookieHeader.isBlank()) {
            runCatching {
                val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
                val resp = Http.getTextWithHeaders(
                    "https://live.kuaishou.com/search?keyword=$kw",
                    headersWithCookie()
                )
                saveCookie(resp.headers.values("Set-Cookie"))
            }
        }
    }

    /** 把 "k=v; k2=v2" 形式的 Cookie 合并进 cookieHeader（同名时用户 Cookie 覆盖） */
    private fun mergeCookieHeader(raw: String) {
        val pairs = raw.split(";").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0].trim() to kv[1].trim() else null
        }
        if (pairs.isEmpty()) return
        saveCookie(pairs.map { (k, v) -> "$k=$v; Path=/" })
    }

    private suspend fun searchLiveStreams(keyword: String, page: Int): SearchRoomResult {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val result = Http.getJson(
            "https://live.kuaishou.com/live_api/search/liveStream?keyword=$kw&page=$page&ussid=",
            searchHeaders(keyword)
        )
        val data = result.optJSONObject("data")
        if (data == null || data.optInt("result", 0) != 1) {
            return SearchRoomResult(hasMore = false, items = emptyList())
        }
        val items = mutableListOf<SearchRoomItem>()
        val list = data.optJSONArray("list")
        list?.let {
            for (i in 0 until it.length()) {
                val room = parseSearchLiveRoom(it.optJSONObject(i) ?: continue)
                if (room.roomId.isNotEmpty()) items.add(room)
            }
        }
        return SearchRoomResult(hasMore = list != null && list.length() > 0, items = items)
    }

    private suspend fun searchRoomsByOverview(keyword: String): SearchRoomResult {
        val overview = getSearchOverview(keyword)
        val items = mutableListOf<SearchRoomItem>()
        val liveStreams = findOverviewSectionList(overview, "liveStreams")
        for (i in 0 until liveStreams.length()) {
            val room = parseSearchLiveRoom(liveStreams.optJSONObject(i) ?: continue)
            if (room.roomId.isNotEmpty()) items.add(room)
        }
        return SearchRoomResult(hasMore = false, items = items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): SearchAnchorResult {
        warmUpSearchCookie(keyword)
        val primary = runCatching { searchAuthors(keyword, page) }.getOrNull()
        if (primary != null && (primary.items.isNotEmpty() || page > 1)) return primary
        if (page > 1) return SearchAnchorResult(hasMore = false, items = emptyList())
        val fallback = runCatching {
            val overview = getSearchOverview(keyword)
            val authors = findOverviewSectionList(overview, "authors")
            val items = mutableListOf<SearchAnchorItem>()
            for (i in 0 until authors.length()) {
                val anchor = parseSearchAnchor(authors.optJSONObject(i) ?: continue)
                if (anchor.roomId.isNotEmpty()) items.add(anchor)
            }
            SearchAnchorResult(hasMore = false, items = items)
        }.getOrNull()
        return fallback ?: SearchAnchorResult(hasMore = false, items = emptyList())
    }

    private suspend fun searchAuthors(keyword: String, page: Int): SearchAnchorResult {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val result = Http.getJson(
            "https://live.kuaishou.com/live_api/search/author" +
                "?key=$kw&keyword=$kw&page=$page&count=15&ussid=&lssid=",
            searchHeaders(keyword)
        )
        val data = result.optJSONObject("data")
        if (data == null || data.optInt("result", 0) != 1) {
            return SearchAnchorResult(hasMore = false, items = emptyList())
        }
        val items = mutableListOf<SearchAnchorItem>()
        val list = data.optJSONArray("list")
        list?.let {
            for (i in 0 until it.length()) {
                val anchor = parseSearchAnchor(it.optJSONObject(i) ?: continue)
                if (anchor.roomId.isNotEmpty()) items.add(anchor)
            }
        }
        return SearchAnchorResult(hasMore = list != null && list.length() >= 15, items = items)
    }

    private suspend fun getSearchOverview(keyword: String): JSONObject {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val result = Http.getJson(
            "https://live.kuaishou.com/live_api/search/overview?keyword=$kw&ussid=",
            searchHeaders(keyword)
        )
        return result.optJSONObject("data") ?: JSONObject()
    }

    private fun findOverviewSectionList(overview: JSONObject, type: String): JSONArray {
        val sections = overview.optJSONArray("list") ?: return JSONArray()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            if (section.optString("type") == type) {
                return section.optJSONArray("list") ?: JSONArray()
            }
        }
        return JSONArray()
    }

    private fun parseSearchLiveRoom(item: JSONObject): SearchRoomItem {
        val author = item.optJSONObject("author") ?: JSONObject()
        val gameInfo = item.optJSONObject("gameInfo") ?: JSONObject()
        var cover = item.optString("poster", "")
        if (cover.isEmpty()) cover = item.optString("coverUrl", "")
        if (cover.isEmpty()) cover = gameInfo.optString("poster", "")
        if (cover.isNotEmpty() && !isImage(cover)) cover = "$cover.jpg"
        val userName = author.optString("name", "")
        val title = when {
            item.has("caption") -> item.optString("caption")
            item.has("title") -> item.optString("title")
            else -> userName
        }
        return SearchRoomItem(
            roomId = when {
                author.has("id") -> author.optString("id")
                item.has("authorId") -> item.optString("authorId")
                item.has("userId") -> item.optString("userId")
                else -> ""
            },
            title = title,
            cover = cover,
            userName = userName,
            online = parseCount(item.optString("watchingCount", "0")),
        )
    }

    private fun parseSearchAnchor(item: JSONObject): SearchAnchorItem {
        return SearchAnchorItem(
            roomId = item.optString("id"),
            avatar = item.optString("avatar"),
            userName = item.optString("name"),
            liveStatus = isLiveFlag(item.opt("living")),
        )
    }
}
