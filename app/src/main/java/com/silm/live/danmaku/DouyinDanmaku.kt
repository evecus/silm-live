package com.silm.live.danmaku

import android.content.Context
import com.silm.live.core.DanmakuMessage
import com.silm.live.core.Http
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

    companion object {
        private const val TAG = "silm"
        private const val SERVER_URL = "wss://webcast3-ws-web-lq.douyin.com/webcast/im/push/v2/"
    }

    override var heartbeatIntervalMs: Long = 10_000
    private val args: DouyinDanmakuArgs? get() = startArgs as? DouyinDanmakuArgs
    private var appContext: Context? = null

    /** IM 预取得到的动态上下文（对齐 Flutter _fetchImContext） */
    private data class ImContext(
        val pushServer: String,
        val cursor: String,
        val internalExt: String,
        val heartbeatDuration: Long,
    )

    private var imContext: ImContext? = null

    private fun cookieValue(name: String): String? {
        val a = args ?: return null
        return a.cookie.split(";").firstOrNull { part ->
            part.trim().substringBefore("=").trim() == name
        }?.substringAfter("=")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun buildQuery(
        cursor: String? = null,
        internalExt: String? = null,
        heartbeatDuration: Long? = null,
    ): LinkedHashMap<String, String> {
        val a = args ?: return linkedMapOf()
        val ts = System.currentTimeMillis()
        return linkedMapOf(
            "app_name" to "douyin_web",
            "version_code" to "180800",
            "webcast_sdk_version" to "1.3.0",
            "update_version_code" to "1.3.0",
            "compress" to "gzip",
            "resp_content_type" to "protobuf",
            "cursor" to (cursor ?: "h-1_t-${ts}_r-1_d-1_u-1"),
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
            "heartbeatDuration" to (heartbeatDuration ?: 0L).toString(),
        ).apply {
            if (!internalExt.isNullOrEmpty()) put("internal_ext", internalExt)
        }
    }

    private fun queryString(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")}"
        }

    /** IM 预取：拿动态 cursor / internal_ext / push_server / 心跳间隔（失败返回 null，走静态地址） */
    private suspend fun fetchImContext(): ImContext? {
        val a = args ?: return null
        val ctx = appContext ?: return null
        val query = buildQuery()
        val stub = JsSignEngine.douyinMsStub(a.roomId, a.userId)
        val signature = JsSignEngine.douyinSignature(ctx, stub, DouyinSite.DEFAULT_UA)
        val unsigned = "https://live.douyin.com/webcast/im/fetch/?${queryString(query)}" +
            "&signature=${java.net.URLEncoder.encode(signature, "UTF-8")}"
        val signedUrl = JsSignEngine.douyinABogusUrl(ctx, unsigned, DouyinSite.DEFAULT_UA, cookieValue("msToken"))
        val bytes = try {
            Http.getBytes(signedUrl, socketHeaders())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "抖音 IM 预取请求失败：${e.message}")
            return null
        }
        if (bytes.isEmpty() || bytes[0] == 0x7b.toByte()) {
            android.util.Log.w(TAG, "抖音 IM 预取响应不可用（字节=${bytes.size}）")
            return null
        }
        val payload = gunzip(bytes)
        val resp = Pb.parse(payload)
        val cursor = resp.str(2).ifEmpty { resp.str(11) } // cursor / liveCursor
        if (cursor.isEmpty()) {
            android.util.Log.w(TAG, "抖音 IM 预取缺少动态游标")
            return null
        }
        val duration = resp.int(8)
        if (duration in 1000..120000) heartbeatIntervalMs = duration
        android.util.Log.i(TAG, "抖音 IM 预取成功 pushServer=${resp.str(10)} heartbeat=${heartbeatIntervalMs}ms")
        return ImContext(
            pushServer = resp.str(10),
            cursor = cursor,
            internalExt = resp.str(5),
            heartbeatDuration = duration,
        )
    }

    private fun normalizePushServer(value: String): String {
        var server = value.trim()
        if (server.isEmpty()) return SERVER_URL
        if (!server.contains("://")) server = "wss://$server"
        val uri = android.net.Uri.parse(server)
        val path = uri.path
        return uri.buildUpon()
            .scheme("wss")
            .path(if (path.isNullOrEmpty() || path == "/") "/webcast/im/push/v2/" else path)
            .build().toString()
    }

    override suspend fun buildUrls(): List<String> {
        val a = args ?: return emptyList()
        val ctx = appContext ?: return emptyList()
        val context = try {
            fetchImContext()
        } catch (e: Exception) {
            null
        }
        imContext = context

        suspend fun buildSocketUrl(base: String, c: ImContext?): String {
            val query = buildQuery(c?.cursor, c?.internalExt, c?.heartbeatDuration)
            val stub = JsSignEngine.douyinMsStub(a.roomId, a.userId)
            val signature = JsSignEngine.douyinSignature(ctx, stub, DouyinSite.DEFAULT_UA)
            return "$base?${queryString(query)}&signature=${
                java.net.URLEncoder.encode(signature, "UTF-8")
            }"
        }

        val staticUrl = buildSocketUrl(SERVER_URL, context)
        val urls = linkedSetOf<String>()
        if (context != null) urls.add(buildSocketUrl(normalizePushServer(context.pushServer), context))
        urls.add(staticUrl)
        // 备用推送域名（对齐 Flutter _socketUrls）
        for (alt in listOf("webcast5-ws-web-lf", "webcast5-ws-web-hl", "webcast3-ws-web-hl", "webcast3-ws-web-lf")) {
            urls.add(staticUrl.replace("webcast3-ws-web-lq", alt))
        }
        android.util.Log.i(TAG, "抖音弹幕候选地址 ${urls.size} 个，动态上下文=${context != null}")
        return urls.toList()
    }

    private fun socketHeaders(): Map<String, String> {
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

    override val headers: Map<String, String>
        get() = socketHeaders()

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
        appContext = DouyinSite.appContext
        super.start(args, onMessage, onClose)
    }
}
