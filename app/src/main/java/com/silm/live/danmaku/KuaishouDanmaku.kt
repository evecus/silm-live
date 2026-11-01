package com.silm.live.danmaku

import com.silm.live.core.DanmakuMessage
import com.silm.live.core.PlatformIdentity
import com.silm.live.core.pb.Pb
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** 快手弹幕启动参数（对齐 simple_live_core 的 KuaishouDanmakuArgs） */
class KuaishouDanmakuArgs(
    val roomId: String,
    val liveStreamId: String,
    val token: String,
    val websocketUrls: List<String>,
    val pageId: String,
    val expTag: String = "",
    val attach: String = "",
    val cookie: String = "",
    val userAgent: String = PlatformIdentity.KUAISHOU_UA,
)

/**
 * 快手弹幕（protobuf over WebSocket）。
 * 帧结构：payloadType(1) / compressionType(2) / payload(3)
 *  - 200：进房包（token + liveStreamId + expTag + attach + pageId）
 *  - 1  ：心跳（时间戳）
 *  - 103：错误（code + message）
 *  - 310：弹幕推送（field 5 = 评论）
 */
class KuaishouDanmaku : BaseDanmaku() {

    override var heartbeatIntervalMs: Long = 20_000
    private val args: KuaishouDanmakuArgs? get() = startArgs as? KuaishouDanmakuArgs

    override suspend fun buildUrls(): List<String> {
        val a = args
        if (a == null || a.liveStreamId.isEmpty() || a.token.isEmpty() || a.websocketUrls.isEmpty()) {
            android.util.Log.w(TAG, "快手弹幕凭证缺失：token/websocketUrls 为空（匿名态通常拿不到）")
            onClose?.invoke("快手弹幕需要登录态凭证")
            return emptyList()
        }
        return a.websocketUrls
    }

    override val headers: Map<String, String>
        get() {
            val a = args ?: return emptyMap()
            return buildMap {
                put("User-Agent", a.userAgent)
                put("Origin", "https://live.kuaishou.com")
                put("Referer", "https://live.kuaishou.com/u/${a.roomId}")
                if (a.cookie.isNotEmpty()) put("Cookie", a.cookie)
            }
        }

    override fun onOpen() {
        val a = args ?: return
        val payload = PbWriter()
            .str(1, a.token)
            .str(2, a.liveStreamId)
            .uint64(3, 0)
            .uint64(4, 0)
            .str(5, a.expTag)
            .str(6, a.attach)
            .str(7, a.pageId)
            .toByteArray()
        send(encodeSocketMessage(200, payload))
    }

    override fun heartbeat() {
        send(encodeSocketMessage(1, PbWriter().uint64(1, System.currentTimeMillis()).toByteArray()))
    }

    override fun onMessageInternal(bytes: ByteString) {
        decode(bytes.toByteArray())
    }

    private fun encodeSocketMessage(payloadType: Int, payload: ByteArray): ByteArray =
        PbWriter().uint64(1, payloadType.toLong()).bytes(3, payload).toByteArray()

    private fun decode(data: ByteArray) {
        try {
            val frame = Pb.parse(data)
            val payloadType = frame.int(1).toInt()
            val compressionType = frame.int(2).toInt()
            var payload = frame.bytes(3)
            if (payload.isEmpty()) return
            payload = when (compressionType) {
                2 -> gunzip(payload)
                3 -> return // AES 压缩，暂不支持
                else -> payload
            }
            when (payloadType) {
                103 -> {
                    val err = Pb.parse(payload)
                    val code = err.int(1).toInt()
                    val msg = err.str(2)
                    if (msg.isEmpty() && code == 0) return
                    android.util.Log.w(TAG, "快手弹幕错误: ${msg.ifEmpty { code.toString() }}")
                    onClose?.invoke(msg.ifEmpty { "快手弹幕错误：$code" })
                }
                310 -> decodeFeedPush(payload)
            }
        } catch (e: Exception) {
            // 忽略单帧解析错误
        }
    }

    private fun decodeFeedPush(payload: ByteArray) {
        val push = Pb.parse(payload)
        for (comment in push.msgs(5)) {
            // 评论结构在 field 5 的子消息里，快手实际下发的是嵌套一层
            val msg = decodeComment(comment) ?: decodeComment(Pb.parse(comment.bytes(1)))
            if (msg != null) {
                onChatReceived()
                onMessage?.invoke(msg)
            }
        }
    }

    private fun decodeComment(comment: Pb): DanmakuMessage? {
        val content = comment.str(3)
        val hidden = comment.int(7) == 2L
        if (hidden || content.isEmpty()) return null
        val user = comment.msgs(2).firstOrNull()
        val userName = user?.str(2)?.takeIf { it.isNotEmpty() }
            ?: comment.msgs(2).map { it.str(2) }.firstOrNull { it.isNotEmpty() }
            ?: ""
        val colorText = comment.str(6).trim().removePrefix("#")
        val color = if (colorText.length == 6) {
            colorText.toLongOrNull(16)?.let { 0xFF000000L or (it and 0xFFFFFF) } ?: 0L
        } else 0L
        return DanmakuMessage(
            type = DanmakuMessage.TYPE_CHAT,
            userName = userName,
            message = content,
            color = color,
        )
    }

    private fun gunzip(data: ByteArray): ByteArray = try {
        GZIPInputStream(java.io.ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        data
    }

    companion object {
        private const val TAG = "silm"
    }
}
