package com.silm.live.danmaku

import android.content.Context
import com.silm.live.core.DanmakuMessage
import com.silm.live.core.sign.JsSignEngine
import com.silm.live.core.pb.Pb
import com.silm.live.core.sites.DouyinSite
import okio.ByteString
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class DouyinDanmakuArgs(
    val webRid: String,
    val roomId: String,
    val userId: String,
    val cookie: String,
)

/** 抖音弹幕（protobuf over WebSocket） */
class DouyinDanmaku : BaseDanmaku() {

    override val heartbeatIntervalMs: Long = 10_000
    private var args: DouyinDanmakuArgs? = null
    private var appContext: Context? = null

    override val headers: Map<String, String>
        get() {
            val a = args ?: return emptyMap()
            return mapOf(
                "accept" to "application/json, text/plain, */*",
                "accept-language" to "zh-CN,zh;q=0.9,en;q=0.8",
                "cache-control" to "no-cache",
                "pragma" to "no-cache",
                "user-agent" to DouyinSite.DEFAULT_UA,
                "cookie" to a.cookie,
                "origin" to "https://live.douyin.com",
                "referer" to "https://live.douyin.com/${a.webRid}",
            )
        }

    override suspend fun buildUrls(): List<String> {
        val a = args ?: return emptyList()
        val ctx = appContext ?: return emptyList()
        val baseUrl = "wss://webcast3-ws-web-lq.douyin.com/webcast/im/push/v2/"
        val ts = System.currentTimeMillis()
        val query = linkedMapOf(
            "app_name" to "douyin_web",
            "version_code" to "180800",
            "webcast_sdk_version" to "1.3.0",
            "update_version_code" to "1.3.0",
            "compress" to "gzip",
            "resp_content_type" to "protobuf",
            "cursor" to "h-1_t-${ts}_r-1_d-1_u-1",
            "host" to "https://live.douyin.com",
            "aid" to "6383",
            "live_id" to "1",
            "did_rule" to "3",
            "debug" to "false",
            "maxCacheMessageNumber" to "20",
            "endpoint" to "live_pc",
            "support_wrds" to "1",
            "im_path" to "/webcast/im/fetch/",
            "user_unique_id" to a.userId,
            "device_platform" to "web",
            "cookie_enabled" to "true",
            "screen_width" to "1920",
            "screen_height" to "1080",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Mozilla",
            "browser_version" to DouyinSite.DEFAULT_UA.replace("Mozilla/", ""),
            "browser_online" to "true",
            "tz_name" to "Asia/Shanghai",
            "identity" to "audience",
            "room_id" to a.roomId,
            "heartbeatDuration" to "0",
        )
        val qs = query.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")}"
        }
        val stub = JsSignEngine.douyinMsStub(a.roomId, a.userId)
        val signature = JsSignEngine.douyinSignature(ctx, stub, DouyinSite.DEFAULT_UA)
        val unsigned = "$baseUrl?$qs&signature=${java.net.URLEncoder.encode(signature, "UTF-8")}"
        return listOf(unsigned)
    }

    override fun onOpen() {
        // 首次连接发送 hb
        heartbeat()
    }

    override fun heartbeat() {
        send(PbWriter().str(7, "hb").toByteArray())
    }

    override fun onMessageInternal(bytes: ByteString) {
        decode(bytes.toByteArray())
    }

    private fun decode(data: ByteArray) {
        val frame = Pb.parse(data)
        val payloadType = frame.str(7)
        val logId = frame.int(2)
        val payload = frame.bytes(8)
        if (payloadType == "msg" || payloadType.isEmpty()) {
            val decompressed = gunzip(payload)
            val resp = Pb.parse(decompressed)
            if (resp.has(9) && resp.int(9) == 1L && logId != 0L) {
                // needAck
                val internalExt = resp.str(5)
                send(
                    PbWriter()
                        .str(7, "ack")
                        .uint64(2, logId)
                        .bytes(8, internalExt.toByteArray(Charsets.UTF_8))
                        .toByteArray()
                )
            }
            val messages = resp.msgs(1)
            for (msg in messages) {
                val method = msg.str(1)
                val msgPayload = msg.bytes(2)
                when (method) {
                    "WebcastChatMessage" -> unpackChat(msgPayload)
                    "WebcastRoomUserSeqMessage" -> unpackRoomUserSeq(msgPayload)
                }
            }
        }
    }

    private fun unpackChat(payload: ByteArray) {
        val chat = Pb.parse(payload)
        val user = chat.msgs(2).firstOrNull()
        val nickName = user?.str(3) ?: ""
        var content = chat.str(3).trim()
        if (content.isEmpty()) {
            // rtfContent 兜底
            val rtf = chat.msgs(22).firstOrNull()
            val sb = StringBuilder()
            rtf?.msgs(4)?.forEach { piece ->
                var v = piece.str(3)
                if (v.isEmpty()) v = piece.msgs(7).firstOrNull()?.str(2) ?: ""
                sb.append(v)
            }
            content = sb.toString().trim()
        }
        onMessage?.invoke(
            DanmakuMessage(
                type = DanmakuMessage.TYPE_CHAT,
                color = 0,
                message = content,
                userName = nickName,
            )
        )
    }

    private fun unpackRoomUserSeq(payload: ByteArray) {
        val msg = Pb.parse(payload)
        val total = msg.int(3)
        val totalUser = msg.int(7)
        onMessage?.invoke(
            DanmakuMessage(
                type = DanmakuMessage.TYPE_ONLINE,
                online = total,
                userName = "",
                message = "",
                color = 0,
            )
        )
    }

    private fun gunzip(data: ByteArray): ByteArray {
        if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        }
        return data
    }

    override fun start(args: Any?, onMessage: (DanmakuMessage) -> Unit, onClose: (String) -> Unit) {
        this.args = args as? DouyinDanmakuArgs
        appContext = DouyinSite.appContext
        super.start(args, onMessage, onClose)
    }
}
