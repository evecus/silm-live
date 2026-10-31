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

    override val heartbeatIntervalMs: Long = 60_000
    private var args: HuyaDanmakuArgs? = null

    private val heartbeatData = byteArrayOf(
        0x00, 0x14, 0x1D, 0x00, 0x0C, 0x36, 0x00, 0x4C
    )

    override suspend fun buildUrls(): List<String> = listOf("wss://cdnws.api.huya.com")

    override fun onOpen() {
        val a = args ?: return
        val joinData = getJoinData(a.ayyuid, a.topSid, a.topSid)
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
