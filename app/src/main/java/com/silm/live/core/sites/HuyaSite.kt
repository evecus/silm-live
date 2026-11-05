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
import com.silm.live.core.tars.HuyaStructs
import com.silm.live.core.tars.HuyaTup
import com.silm.live.danmaku.DanmakuClient
import com.silm.live.danmaku.HuyaDanmaku
import com.silm.live.danmaku.HuyaDanmakuArgs
import org.json.JSONObject
import java.util.Base64
import kotlin.random.Random

/**
 * 虎牙直播，接口逻辑移植自 dart_simple_live。
 */
class HuyaSite : LiveSite {
    override val id = "huya"
    override val name = "虎牙直播"

    private val hySdkUa = PlatformIdentity.HUYA_HYSDK_UA

    private val requestHeaders = mapOf(
        "Origin" to "https://m.huya.com",
        "Referer" to "https://m.huya.com",
        "User-Agent" to PlatformIdentity.HUYA_HYSDK_UA,
    )

    override fun createDanmakuClient(): DanmakuClient = HuyaDanmaku()

    // ---------------- 房间信息 ----------------

    private data class RoomData(
        val json: JSONObject,
        val topSid: Long,
        val subSid: Long,
    )

    private suspend fun getRoomInfo(roomId: String): RoomData {
        val resultText = Http.getText(
            "https://m.huya.com/$roomId",
            mapOf("user-agent" to PlatformIdentity.HUYA_UA)
        )
        val match = Regex("window\\.HNF_GLOBAL_INIT.=.\\{[\\s\\S]*?\\}[\\s\\S]*?</script>")
            .find(resultText)?.value
            ?: throw CoreError("虎牙页面数据结构变更或访问受限，请稍后重试")
        val jsonText = match
            .replace(Regex("window\\.HNF_GLOBAL_INIT.=."), "")
            .replace("</script>", "")
            .replace(Regex("function.*?\\(.*?\\).\\{[\\s\\S]*?\\}"), "\"\"")
        val jsonObj = JSONObject(jsonText)
        var topSid = Regex("lChannelId\":([0-9]+)").find(resultText)?.groupValues?.get(1)
            ?.toLongOrNull() ?: 0L
        var subSid = Regex("lSubChannelId\":([0-9]+)").find(resultText)?.groupValues?.get(1)
            ?.toLongOrNull() ?: 0L
        if (topSid <= 0) topSid = firstPositiveIntByKeys(jsonObj, setOf("lchannelid", "channelid"))
        if (subSid <= 0) subSid = firstPositiveIntByKeys(jsonObj, setOf("lsubchannelid", "subchannelid"))
        return RoomData(jsonObj, topSid, subSid)
    }

    private fun firstPositiveIntByKeys(source: Any?, keys: Set<String>, depth: Int = 0): Long {
        if (source == null || depth > 8) return 0L
        when (source) {
            is JSONObject -> {
                for (key in keys) {
                    val v = source.opt(key)
                    val parsed = positiveInt(v)
                    if (parsed > 0) return parsed
                }
                for (key in source.keys()) {
                    val v = source.opt(key)
                    val r = firstPositiveIntByKeys(v, keys, depth + 1)
                    if (r > 0) return r
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until source.length()) {
                    val r = firstPositiveIntByKeys(source.opt(i), keys, depth + 1)
                    if (r > 0) return r
                }
            }
        }
        return 0L
    }

    /** 虎牙频道号常超过 Int 范围（12 位数字），必须用 Long，否则被截断导致弹幕进房/取流失败 */
    private fun positiveInt(value: Any?): Long {
        if (value is Number) return value.toLong().takeIf { it > 0 } ?: 0L
        return value?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
    }

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val roomData = getRoomInfo(roomId)
        val roomInfo = roomData.json.optJSONObject("roomInfo")
            ?: throw CoreError("虎牙房间数据结构变更，请联系开发者适配")
        val tLiveInfo = roomInfo.optJSONObject("tLiveInfo")
            ?: throw CoreError("虎牙房间数据结构变更，请联系开发者适配")
        val tProfileInfo = roomInfo.optJSONObject("tProfileInfo")
            ?: throw CoreError("虎牙房间数据结构变更，请联系开发者适配")

        var title = tLiveInfo.optString("sIntroduction", "")
        if (title.isEmpty()) title = tLiveInfo.optString("sRoomName", "")

        data class Line(
            val line: String,
            val cdnType: String,
            val flvAntiCode: String,
            val hlsUrl: String,
            val hlsAntiCode: String,
            val streamName: String,
            val presenterUid: Long,
        )
        val lines = mutableListOf<Line>()
        val streamInfoValue = tLiveInfo.optJSONObject("tLiveStreamInfo")
            ?.optJSONObject("vStreamInfo")?.optJSONArray("value")
        if (streamInfoValue != null) {
            for (i in 0 until streamInfoValue.length()) {
                val item = streamInfoValue.getJSONObject(i)
                val sFlvUrl = item.optString("sFlvUrl", "")
                val sHlsUrl = item.optString("sHlsUrl", "")
                val streamName = item.optString("sStreamName", "")
                if ((sFlvUrl.isNotEmpty() || sHlsUrl.isNotEmpty()) && streamName.isNotEmpty()) {
                    lines.add(
                        Line(
                            line = sFlvUrl,
                            cdnType = item.optString("sCdnType", ""),
                            flvAntiCode = item.optString("sFlvAntiCode", ""),
                            hlsUrl = sHlsUrl,
                            hlsAntiCode = item.optString("sHlsAntiCode", ""),
                            streamName = streamName,
                            presenterUid = if (roomData.topSid > 0) roomData.topSid else roomData.subSid,
                        )
                    )
                }
            }
        }

        data class BitRate(val bitRate: Int, val name: String)
        val bitRates = mutableListOf<BitRate>()
        val bitRateValue = tLiveInfo.optJSONObject("tLiveStreamInfo")
            ?.optJSONObject("vBitRateInfo")?.optJSONArray("value")
        if (bitRateValue != null) {
            for (i in 0 until bitRateValue.length()) {
                val item = bitRateValue.getJSONObject(i)
                val name = item.optString("sDisplayName", "")
                if (name.contains("HDR")) continue
                bitRates.add(BitRate(item.optInt("iBitRate", 0), name))
            }
        }

        val extra = HuyaUrlData(
            lines = lines.map {
                HuyaLine(
                    // 虎牙 CDN 对 https 完整支持(实测 m3u8/ts 同签名 200)。
                    // 明文 http 会被部分网络环境(本地代理/运营商)直接拦截导致播放秒失败,
                    // 统一转 https.
                    it.line.replaceFirst("http://", "https://"),
                    it.flvAntiCode,
                    it.hlsUrl.replaceFirst("http://", "https://"),
                    it.hlsAntiCode,
                    it.streamName,
                    it.presenterUid
                )
            },
            bitRates = bitRates.map { HuyaBitRate(it.bitRate, it.name) },
        )

        return LiveRoomDetail(
            platform = id,
            roomId = tLiveInfo.optString("lProfileRoom", roomId),
            title = title,
            cover = tLiveInfo.optString("sScreenshot", ""),
            userName = tProfileInfo.optString("sNick", ""),
            userAvatar = tProfileInfo.optString("sAvatar180", ""),
            online = tLiveInfo.optLong("lTotalCount", 0),
            status = roomInfo.optInt("eLiveStatus", 0) == 2,
            url = "https://www.huya.com/$roomId",
            introduction = tLiveInfo.optString("sIntroduction", ""),
            danmakuArgs = HuyaDanmakuArgs(
                ayyuid = tLiveInfo.optLong("lYyid", 0),
                topSid = roomData.topSid.toLong(),
                subSid = roomData.subSid.toLong(),
            ),
            extra = extra,
        )
    }

    class HuyaLine(
        val line: String,
        val flvAntiCode: String,
        val hlsUrl: String = "",
        val hlsAntiCode: String = "",
        val streamName: String,
        val presenterUid: Long,
    )

    class HuyaBitRate(val bitRate: Int, val name: String)

    class HuyaUrlData(val lines: List<HuyaLine>, val bitRates: List<HuyaBitRate>)

    // ---------------- 播放信息 ----------------

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val urlData = detail.extra as? HuyaUrlData ?: throw CoreError("虎牙播放数据异常")
        val bitRates = urlData.bitRates.ifEmpty {
            listOf(HuyaBitRate(0, "原画"), HuyaBitRate(2000, "高清"))
        }
        return bitRates.map {
            LivePlayQuality(quality = it.name, sort = it.bitRate, data = it)
        }
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val urlData = detail.extra as? HuyaUrlData ?: throw CoreError("虎牙播放数据异常")
        val bitRate = quality.data as HuyaBitRate
        val urls = mutableListOf<String>()
        val flvUrls = mutableListOf<String>()
        for (line in urlData.lines) {
            // HLS：直接用页面返回的 anticode 构造，无需 TUP，ExoPlayer 原生支持
            runCatching { getPlayUrlHls(line, bitRate.bitRate) }.getOrNull()?.let { urls.add(it) }
            // FLV：需要 TUP 取 token，作为兜底线路（需支持 FLV 的播放器）
            runCatching { getPlayUrlFlv(line, bitRate.bitRate) }.getOrNull()?.let { flvUrls.add(it) }
        }
        urls.addAll(flvUrls)
        if (urls.isEmpty()) {
            throw CoreError("虎牙未获取到播放地址（可能未开播或被风控）")
        }
        // UA 必须与 anticode 的 ctype 一致：m 站 anticode 是 tars_mobile/t=103，
        // 用 PC 客户端 UA 请求会被 CDN 直接 403（实测），必须用手机端 HYSDK UA
        return LivePlayUrl(urls = urls, headers = mapOf("user-agent" to PlatformIdentity.HUYA_MOBILE_HYSDK_UA))
    }

    private fun getPlayUrlHls(line: HuyaLine, bitRate: Int): String {
        val base = line.hlsUrl.ifEmpty { throw CoreError("无 HLS 线路") }
        val antiCode = line.hlsAntiCode.ifEmpty { line.flvAntiCode }
        val built = buildAntiCode(line.streamName, line.presenterUid, antiCode)
        var url = "${toHttps(base)}/${line.streamName}.m3u8?$built&codec=264"
        if (bitRate > 0) url += ratioParam(bitRate)
        return url
    }

    private fun getPlayUrlFlv(line: HuyaLine, bitRate: Int): String {
        // 直接用页面返回的 sFlvAntiCode（ctype=tars_mobile）构造，与播放 UA 保持一致；
        // 不再用 TUP getCdnTokenInfoEx 取 pc_exe token —— 那会构造出 PC ctype 的签名，
        // 与手机 UA 冲突被 CDN 403，且多一次 TUP 往返
        val built = buildAntiCode(line.streamName, line.presenterUid, line.flvAntiCode)
        var url = "${toHttps(line.line)}/${line.streamName}.flv?$built&codec=264"
        if (bitRate > 0) url += ratioParam(bitRate)
        return url
    }

    /** 虎牙 CDN 全部支持 https，明文 http 在部分网络下会被拦截 */
    private fun toHttps(url: String): String =
        if (url.startsWith("http://")) "https://${url.removePrefix("http://")}" else url

    /**
     * 码率档参数. 实测 (2026-10) HLS/FLV 都只接受 ratio <= 4000:
     * 4K(20000)/4K HDR(20100) 带 ratio 会被 CDN 直接 403. 高码率档一律回退原画
     * (不带 ratio 拿到的就是当前最高码率流, 比整档 403 进入换线路循环强得多).
     */
    private fun ratioParam(bitRate: Int): String =
        if (bitRate in 1..4000) "&ratio=$bitRate" else ""

    /** 构造 anticode（python 算法移植） */
    private fun buildAntiCode(stream: String, presenterUid: Long, antiCode: String): String {
        val mapAnti = parseQuery(antiCode)
        if (!mapAnti.containsKey("fm")) return antiCode

        val ctype = mapAnti["ctype"] ?: "huya_pc_exe"
        val platformId = mapAnti["t"]?.toIntOrNull() ?: 0
        val isWap = platformId == 103
        val calcStartTime = System.currentTimeMillis()

        val seqId = presenterUid.toLong() + calcStartTime
        val secretHash = md5Hex("$seqId|$ctype|$platformId")

        val convertUid = rotl64(presenterUid.toLong())
        val calcUid = if (isWap) presenterUid.toLong() else convertUid
        val fm = java.net.URLDecoder.decode(mapAnti["fm"] ?: "", "UTF-8")
        val secretPrefix = String(Base64.getDecoder().decode(fm), Charsets.UTF_8).split("_").first()
        val wsTime = mapAnti["wsTime"] ?: ""
        val secretStr = "${secretPrefix}_${calcUid}_${stream}_${secretHash}_$wsTime"
        val wsSecret = md5Hex(secretStr)

        val ct = ((wsTime.toLong(16) + Random.nextDouble()) * 1000).toLong()
        val uuid = (((ct % 1e10) + Random.nextDouble()) * 1e3 % 0xffffffff).toLong().toString()

        val parts = mutableListOf(
            "wsSecret=$wsSecret",
            "wsTime=$wsTime",
            "seqid=$seqId",
            "ctype=$ctype",
            "ver=1",
            "fs=${mapAnti["fs"] ?: ""}",
            "fm=${java.net.URLEncoder.encode(mapAnti["fm"] ?: "", "UTF-8")}",
            "t=$platformId",
        )
        if (isWap) {
            parts.add("uid=$presenterUid")
            parts.add("uuid=$uuid")
        } else {
            parts.add("u=$convertUid")
        }
        return parts.joinToString("&")
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        query.split("&").forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) {
                map[kv.substring(0, idx)] = java.net.URLDecoder.decode(kv.substring(idx + 1), "UTF-8")
            }
        }
        return map
    }

    private fun rotl64(t: Long): Long {
        val low = t and 0xFFFFFFFFL
        val rotatedLow = ((low shl 8) or (low ushr 24)) and 0xFFFFFFFFL
        return (t and -0x100000000L) or rotatedLow
    }

    override suspend fun getLiveStatus(roomId: String): Boolean {
        val roomData = getRoomInfo(roomId)
        return roomData.json.optJSONObject("roomInfo")?.optInt("eLiveStatus", 0) == 2
    }

    // ---------------- 搜索（移植自 simple_live_core） ----------------

    /**
     * 搜索接口的 start 并非偏移量：start>0 时返回的是"前 start+rows 条"的累积结果，
     * 且累计窗口上限只有 40 条。因此固定 start=0，通过 rows 扩大累积窗口，
     * 再截取 (page-1)*20 ~ page*20 区间作为当前页。
     */
    private suspend fun searchContent(keyword: String, page: Int, v: Int): JSONObject {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val rows = page * 20
        return Http.getJson(
            "https://search.cdn.huya.com/?m=Search&do=getSearchContent" +
                "&q=$kw&uid=0&v=$v&typ=-5&livestate=0&rows=$rows&start=0"
        )
    }

    override suspend fun searchRooms(keyword: String, page: Int): SearchRoomResult {
        val result = searchContent(keyword, page, v = 4)
        val docs = result.optJSONObject("response")?.optJSONObject("3")
            ?.optJSONArray("docs")
        val items = mutableListOf<SearchRoomItem>()
        docs?.let { arr ->
            val skip = (page - 1) * 20
            for (i in skip until arr.length()) {
                val item = arr.getJSONObject(i)
                var cover = item.optString("game_screenshot")
                if (!cover.contains("?")) cover += "?x-oss-process=style/w338_h190&"
                var title = item.optString("game_introduction")
                if (title.isEmpty()) title = item.optString("game_roomName")
                items.add(
                    SearchRoomItem(
                        roomId = item.optString("room_id"),
                        title = title,
                        cover = cover,
                        userName = item.optString("game_nick"),
                        online = item.optLong("game_total_count", 0),
                    )
                )
            }
        }
        val numFound = result.optJSONObject("response")?.optJSONObject("3")
            ?.optLong("numFound", 0) ?: 0
        return SearchRoomResult(hasMore = numFound > page * 20, items = items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): SearchAnchorResult {
        val result = searchContent(keyword, page, v = 1)
        val docs = result.optJSONObject("response")?.optJSONObject("1")
            ?.optJSONArray("docs")
        val items = mutableListOf<SearchAnchorItem>()
        docs?.let { arr ->
            val skip = (page - 1) * 20
            for (i in skip until arr.length()) {
                val item = arr.getJSONObject(i)
                items.add(
                    SearchAnchorItem(
                        roomId = item.optString("room_id"),
                        avatar = item.optString("game_avatarUrl180"),
                        userName = item.optString("game_nick"),
                        liveStatus = item.optBoolean("gameLiveOn", false),
                    )
                )
            }
        }
        val numFound = result.optJSONObject("response")?.optJSONObject("1")
            ?.optLong("numFound", 0) ?: 0
        return SearchAnchorResult(hasMore = numFound > page * 20, items = items)
    }
}
