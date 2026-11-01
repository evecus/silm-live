package com.silm.live.danmaku

import com.silm.live.core.DanmakuMessage
import okio.ByteString
import org.json.JSONObject
import org.json.JSONTokener

/** 斗鱼弹幕（STT 文本协议） */
class DouyuDanmaku : BaseDanmaku() {

    override var heartbeatIntervalMs: Long = 45_000
    private val roomId: String get() = startArgs as? String ?: ""

    override suspend fun buildUrls(): List<String> =
        (8501..8506).map { "wss://danmuproxy.douyu.com:$it" }.shuffled()

    override fun onOpen() {
        send(serialize("type@=loginreq/roomid@=$roomId/"))
        send(serialize("type@=joingroup/rid@=$roomId/gid@=-9999/"))
    }

    override fun heartbeat() {
        send(serialize("type@=mrkl/"))
    }

    override fun onMessageInternal(bytes: ByteString) {
        decode(bytes.toByteArray())
    }

    private fun serialize(body: String): ByteArray {
        val buffer = body.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        val length = 4 + 4 + buffer.size + 1
        writeLittleInt(out, length)
        writeLittleInt(out, length)
        writeLittleShort(out, 689)
        out.write(0)
        out.write(0)
        out.write(buffer)
        out.write(0)
        return out.toByteArray()
    }

    private fun writeLittleInt(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeLittleShort(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun decode(data: ByteArray) {
        try {
            if (data.size < 9) return
            var pos = 0
            fun readLittleInt(): Int {
                val v = (data[pos].toInt() and 0xFF) or
                    ((data[pos + 1].toInt() and 0xFF) shl 8) or
                    ((data[pos + 2].toInt() and 0xFF) shl 16) or
                    ((data[pos + 3].toInt() and 0xFF) shl 24)
                pos += 4
                return v
            }
            val fullMsgLength = readLittleInt()
            readLittleInt()
            val bodyLength = fullMsgLength - 9
            pos += 2 // packType
            pos += 1 // encrypted
            pos += 1 // reserved
            if (bodyLength <= 0 || pos + bodyLength > data.size) return
            val body = String(data, pos, bodyLength, Charsets.UTF_8)
            val json = sttToJson(body)
            val type = json?.optString("type", "")
            if (type == "chatmsg") {
                // 屏蔽阴间弹幕
                if (!json!!.has("dms")) return
                val col = json.optInt("col", 0)
                onMessage?.invoke(
                    DanmakuMessage(
                        type = DanmakuMessage.TYPE_CHAT,
                        userName = json.optString("nn", ""),
                        message = json.optString("txt", ""),
                        color = colColor(col),
                    )
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun colColor(type: Int): Long = when (type) {
        1 -> 0xFF0000L
        2 -> 0x1E87F0L
        3 -> 0x7AC84BL
        4 -> 0xFF7F00L
        5 -> 0x9B39F4L
        6 -> 0xFF69B4L
        else -> 0L
    }

    // ------- 斗鱼 STT 解析 -------

    private fun sttToJson(str: String): JSONObject? {
        val obj = sttParse(str)
        return obj as? JSONObject
    }

    private fun sttParse(str: String): Any? {
        return if (str.contains("//")) {
            val result = org.json.JSONArray()
            for (field in str.split("//")) {
                if (field.isEmpty()) continue
                result.put(sttParse(field))
            }
            result
        } else if (str.contains("@=")) {
            val result = JSONObject()
            for (field in str.split("/")) {
                if (field.isEmpty()) continue
                val tokens = field.split("@=", limit = 2)
                if (tokens.size < 2) continue
                val v = unscapeSlashAt(tokens[1])
                result.put(tokens[0], sttParse(v))
            }
            result
        } else if (str.contains("@A=")) {
            sttParse(unscapeSlashAt(str))
        } else {
            unscapeSlashAt(str)
        }
    }

    private fun unscapeSlashAt(str: String): String =
        str.replace("@S", "/").replace("@A", "@")
}
