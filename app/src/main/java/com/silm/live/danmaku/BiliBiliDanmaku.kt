package com.silm.live.danmaku

import com.silm.live.core.DanmakuMessage
import com.silm.live.core.numberToColor
import okio.ByteString
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.zip.Inflater

class BiliBiliDanmakuArgs(
    val roomId: Long,
    val uid: Long,
    val token: String,
    val serverHost: String,
    val buvid: String,
)

/** B站弹幕（二进制协议，protover=3 brotli） */
class BiliBiliDanmaku : BaseDanmaku() {

    override var heartbeatIntervalMs: Long = 60_000
    private val args: BiliBiliDanmakuArgs? get() = startArgs as? BiliBiliDanmakuArgs

    override suspend fun buildUrls(): List<String> {
        val a = args ?: return emptyList()
        if (a.token.isEmpty()) return emptyList()
        return listOf("wss://${a.serverHost}/sub")
    }

    override fun onOpen() {
        val a = args ?: return
        val join = JSONObject()
            .put("uid", a.uid)
            .put("roomid", a.roomId)
            .put("protover", 3)
            .put("buvid", a.buvid)
            .put("platform", "web")
            .put("type", 2)
            .put("key", a.token)
            .toString()
        send(encode(join, 7))
    }

    override fun heartbeat() {
        send(encode("", 2))
    }

    override fun onMessageInternal(bytes: ByteString) {
        decode(bytes.toByteArray())
    }

    private fun encode(msg: String, action: Int): ByteArray {
        val data = msg.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        val length = data.size + 16
        // 数据包长度
        writeInt(out, length, 4)
        // 头部长度固定16
        writeInt(out, 16, 2)
        // 协议版本 0=JSON
        writeInt(out, 0, 2)
        // 操作类型
        writeInt(out, action, 4)
        // 固定1
        writeInt(out, 1, 4)
        out.write(data)
        return out.toByteArray()
    }

    private fun writeInt(out: java.io.ByteArrayOutputStream, value: Int, len: Int) {
        for (i in len - 1 downTo 0) {
            out.write((value shr (8 * i)) and 0xFF)
        }
    }

    private fun readInt(data: ByteArray, start: Int, len: Int): Long {
        var result = 0L
        for (i in 0 until len) {
            result = (result shl 8) or ((data[start + i].toInt() and 0xFF).toLong())
        }
        return result
    }

    private fun decode(data: ByteArray) {
        if (data.size <= 16) return
        val protocolVersion = readInt(data, 6, 2).toInt()
        val operation = readInt(data, 8, 4).toInt()
        var body = data.copyOfRange(16, data.size)
        when (operation) {
            3 -> { // 心跳回应：人气值
                val online = readInt(body, 0, 4)
                onMessage?.invoke(
                    DanmakuMessage(DanmakuMessage.TYPE_ONLINE, "", "", 0, online)
                )
            }
            5 -> { // 通知（弹幕等）
                when (protocolVersion) {
                    2 -> body = zlibDecode(body)
                    3 -> body = brotliDecode(body)
                }
                val text = String(body, Charsets.UTF_8)
                text.split(Regex("[\\x00-\\x1f]+")).forEach { item ->
                    if (item.length > 2 && item.startsWith("{")) parseMessage(item)
                }
            }
        }
    }

    private fun zlibDecode(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val buffer = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(tmp)
            if (n == 0 && inflater.needsInput()) break
            buffer.write(tmp, 0, n)
        }
        inflater.end()
        return buffer.toByteArray()
    }

    private fun brotliDecode(data: ByteArray): ByteArray {
        val input = org.brotli.dec.BrotliInputStream(ByteArrayInputStream(data))
        return input.use { it.readBytes() }
    }

    private fun parseMessage(jsonMessage: String) {
        try {
            val obj = JSONTokener(jsonMessage).nextValue() as? JSONObject ?: return
            val cmd = obj.optString("cmd", "")
            if (cmd.contains("DANMU_MSG")) {
                val info = obj.optJSONArray("info") ?: return
                if (info.length() == 0) return
                val message = info.optString(1, "")
                val meta = info.optJSONArray(0)
                val colorRaw = meta?.optLong(3) ?: 0L
                val user = info.optJSONArray(2) ?: return
                if (user.length() == 0) return
                val username = user.optString(1, "")
                onChatReceived()
                onMessage?.invoke(
                    DanmakuMessage(
                        type = DanmakuMessage.TYPE_CHAT,
                        userName = username,
                        message = message,
                        color = numberToColor(colorRaw),
                    )
                )
            }
        } catch (e: Exception) {
        }
    }
}
