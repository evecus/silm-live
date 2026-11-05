package com.silm.live.danmaku

import com.silm.live.core.DanmakuMessage
import com.silm.live.core.numberToColor
import com.silm.live.core.tars.TarsOutputStream
import com.silm.live.core.tars.parseHYMessage
import com.silm.live.core.tars.parseHYPushMessage
import com.silm.live.core.tars.TarsInputStream
import okio.ByteString

class HuyaDanmakuArgs(
    val ayyuid: Long,
    val topSid: Long,
    val subSid: Long,
)

/** 虎牙弹幕（TARS 二进制协议） */
class HuyaDanmaku : BaseDanmaku() {

    companion object {
        private const val TAG = "silm"
    }

    override var heartbeatIntervalMs: Long = 60_000

    // 虎牙 CDN 强校验 Origin，缺失时 WS 握手被静默切断（现象为连接后立刻 closed）
    override val headers: Map<String, String> = mapOf("Origin" to "https://www.huya.com")
    private val args: HuyaDanmakuArgs? get() = startArgs as? HuyaDanmakuArgs

    // 与 Flutter 版 base64.decode("ABQdAAwsNgBM") 完全一致（9 字节）。
    // 此前漏了第 6 字节 0x2C，服务端把畸形心跳当作非法包，连接很快被断开。
    private val heartbeatData = byteArrayOf(
        0x00, 0x14, 0x1D, 0x00, 0x0C, 0x2C, 0x36, 0x00, 0x4C
    )

    override suspend fun buildUrls(): List<String> {
        val a = args
        android.util.Log.i(
            TAG,
            "虎牙弹幕参数: ayyuid=${a?.ayyuid} topSid=${a?.topSid} subSid=${a?.subSid}"
        )
        return listOf("wss://cdnws.api.huya.com")
    }

    override fun onOpen() {
        val a = args ?: return
        val joinData = getJoinData(a.ayyuid, a.topSid, a.topSid)
        android.util.Log.i(TAG, "虎牙弹幕已连接，发送进房包 ${joinData.size} 字节")
        send(joinData)
    }

    private fun getJoinData(ayyuid: Long, tid: Long, sid: Long): ByteArray {
        val inner = TarsOutputStream().apply {
            writeInt(ayyuid, 0)
            writeBool(true, 1)
            writeString("", 2)
            writeString("", 3)
            writeInt(tid, 4)
            writeInt(sid, 5)
            writeInt(0, 6)
            writeInt(0, 7)
        }.toByteArray()

        return TarsOutputStream().apply {
            writeInt(1, 0)
            writeBytes(inner, 1)
        }.toByteArray()
    }

    override fun heartbeat() {
        send(heartbeatData)
    }

    override fun onMessageInternal(bytes: ByteString) {
        decode(bytes.toByteArray())
    }

    private fun decode(data: ByteArray) {
        try {
            val stream = TarsInputStream(data)
            val type = stream.readInt(0)
            if (type == 7L) {
                val inner = stream.readBytes(1)
                val push = parseHYPushMessage(inner)
                when (push.uri) {
                    1400L -> {
                        val messageNotice = parseHYMessage(push.msg)
                        val color = messageNotice.bulletFormat.fontColor
                        onMessage?.invoke(
                            DanmakuMessage(
                                type = DanmakuMessage.TYPE_CHAT,
                                color = if (color <= 0) 0 else numberToColor(color),
                                message = messageNotice.content,
                                userName = messageNotice.userInfo.nickName,
                            )
                        )
                    }
                    8006L -> {
                        val online = TarsInputStream(push.msg).readInt(0)
                        onMessage?.invoke(
                            DanmakuMessage(
                                type = DanmakuMessage.TYPE_ONLINE,
                                online = online,
                                userName = "",
                                message = "",
                                color = 0,
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
        }
    }
}
