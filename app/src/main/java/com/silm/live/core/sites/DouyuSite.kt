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
import com.silm.live.danmaku.DouyuDanmaku
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 斗鱼直播，接口逻辑移植自 dart_simple_live。
 * 播放签名依赖 WebView 执行服务端下发的加密脚本。
 */
class DouyuSite : LiveSite {
    override val id = "douyu"
    override val name = "斗鱼直播"

    /** 进程内设备号（32 位 hex），匿名请求签名用 */
    private val deviceId: String = generateHexDid(32)

    private fun generateHexDid(len: Int): String {
        val random = SecureRandom()
        val sb = StringBuilder()
        repeat(len) { sb.append("0123456789abcdef"[random.nextInt(16)]) }
        return sb.toString()
    }

    private val requestHeaders: Map<String, String>
        get() = mapOf(
            "referer" to "https://www.douyu.com/",
            "user-agent" to PlatformIdentity.DOUYU_UA,
            "Cookie" to "dy_did=$deviceId; acf_did=$deviceId",
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
                requestHeaders + mapOf("referer" to "https://www.douyu.com/$roomId")
            )
            val decoded = JSONObject(jsEncResult)
            val script = decoded.optJSONObject("data")?.optString("room$roomId", "") ?: ""
            if (script.isNotEmpty()) {
                val signed = JsSignEngine.douyuSign(context(), script, roomId, deviceId)
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
                requestHeaders + mapOf("referer" to "https://www.douyu.com/$roomId")
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
            requestHeaders + mapOf("referer" to "https://www.douyu.com/$roomId")
        )
    }

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val args = getFreshPlayArgs(detail.roomId) +
            "&cdn=&rate=-1&ver=Douyu_223061205&iar=1&ive=1&hevc=0&fa=0"
        val result = postH5Play(detail.roomId, args)
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
        // scdn 线路排后
        cdns.sortByDescending { it.startsWith("scdn") }

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
        val baseArgs = getFreshPlayArgs(detail.roomId)
        val urls = mutableListOf<String>()
        for (cdn in data.cdns) {
            try {
                val url = getPlayUrl(detail.roomId, baseArgs, data.rate, cdn)
                if (url.isNotEmpty()) {
                    var finalUrl = url
                    if (finalUrl.contains("expire=300") && finalUrl.contains("fcdn=ws")) {
                        finalUrl += "&expire=0"
                    }
                    urls.add(finalUrl)
                }
            } catch (e: Exception) {
                // 单线路失败继续
            }
        }
        return LivePlayUrl(urls = urls)
    }

    private suspend fun getPlayUrl(roomId: String, baseArgs: String, rate: Int, cdn: String): String {
        val args = baseArgs + "&cdn=$cdn&rate=$rate"
        val result = postH5Play(roomId, args)
        val errorCode = result.optInt("error", -1)
        if (errorCode != 0) {
            throw CoreError("斗鱼播放地址请求失败：${result.optString("msg", errorCode.toString())}")
        }
        val data = result.optJSONObject("data") ?: throw CoreError("斗鱼返回了无效的播放地址")
        val rtmpUrl = data.optString("rtmp_url", "")
        val rtmpLive = data.optString("rtmp_live", "")
        if (rtmpUrl.isEmpty() || rtmpLive.isEmpty()) throw CoreError("斗鱼返回了无效的播放地址")
        return "$rtmpUrl/$rtmpLive"
    }

    override suspend fun getLiveStatus(roomId: String): Boolean {
        val roomInfo = getRoomInfo(roomId)
        return roomInfo.optInt("show_status", 0) == 1 && roomInfo.optInt("videoLoop", 0) != 1
    }
}
