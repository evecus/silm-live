package com.silm.live.core.tars

import com.silm.live.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 虎牙 TUP(HTTP+wup) 请求封装，仅支持 PACKET_TYPE_TUP3。
 */
object HuyaTup {

    /** 编码 RequestPacket（TUP3），返回带 4 字节长度前缀的完整请求体 */
    fun buildRequest(servantName: String, funcName: String, newData: Map<String, ByteArray>): ByteArray {
        val sBuffer = TarsOutputStream().apply { writeMapStringBytes(newData, 0) }.toByteArray()

        val body = TarsOutputStream().apply {
            writeInt(3L, 1)                 // iVersion = TUP3
            writeInt(0L, 2)                 // cPacketType
            writeInt(0L, 3)                 // iMessageType
            writeInt(0L, 4)                 // iRequestId
            writeString(servantName, 5)     // sServantName
            writeString(funcName, 6)        // sFuncName
            writeBytes(sBuffer, 7)          // sBuffer
            writeInt(0L, 8)                 // iTimeout
            // context/status 空可不写
        }.toByteArray()

        val out = ByteArrayOutputStream()
        val total = body.size + 4
        out.write((total shr 24) and 0xFF)
        out.write((total shr 16) and 0xFF)
        out.write((total shr 8) and 0xFF)
        out.write(total and 0xFF)
        out.write(body)
        return out.toByteArray()
    }

    /** 解码响应，返回 (code, newData) */
    fun decodeResponse(bytes: ByteArray): Pair<Int, Map<String, ByteArray>> {
        val resp = TarsInputStream(bytes, 4)
        var servantName = ""
        var funcName = ""
        var sBuffer = ByteArray(0)
        resp.readStruct(0) {
            // RequestPacket 字段号与写入端一致
            val iVersion = readInt(1)
            readInt(2); readInt(3); readInt(4)
            servantName = readString(5)
            funcName = readString(6)
            sBuffer = readBytes(7)
        }
        val newData = TarsInputStream(sBuffer).readMapStringBytes(0)
        var code = 0L
        newData[""]?.let { code = TarsInputStream(it).readInt(0) }
        return code.toInt() to newData
    }

    /**
     * 发送 TUP 请求。
     * @param reqStruct 在 TarsOutputStream 内写出请求结构体内容
     * @return "tRsp" 的原始字节
     */
    suspend fun tupRequest(
        servant: String,
        funcName: String,
        headers: Map<String, String>,
        reqStruct: TarsOutputStream.() -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val tReq = TarsOutputStream().apply(reqStruct).toByteArray()
        val body = buildRequest(servant, funcName, mapOf("tReq" to tReq))
        val resp = Http.client.newBuilder()
            .build()
            .newCall(
                okhttp3.Request.Builder()
                    .url("http://wup.huya.com")
                    .headers(okhttp3.Headers.headersOf(
                        "Content-Type", "application/x-wup",
                        *headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                    ))
                    .post(okhttp3.RequestBody.create(null, body))
                    .build()
            )
            .execute()
        resp.use {
            val data = it.body?.bytes() ?: ByteArray(0)
            val (code, newData) = decodeResponse(data)
            if (code != 0) throw com.silm.live.core.CoreError("虎牙TUP请求失败 code=$code func=$funcName")
            newData["tRsp"] ?: ByteArray(0)
        }
    }
}

// ---------------- 虎牙结构体 ----------------

object HuyaStructs {
    /** HuyaUserId */
    fun writeUserId(os: TarsOutputStream, ua: String) {
        os.writeInt(0L, 0)          // lUid
        os.writeString("", 1)       // sGuid
        os.writeString("", 2)       // sToken
        os.writeString(ua, 3)       // sHuYaUA
        os.writeString("", 4)       // sCookie
        os.writeInt(0L, 5)          // iTokenType
        os.writeString("", 6)       // sDeviceInfo
        os.writeString("", 7)       // sQIMEI
    }

    /** GetCdnTokenExReq */
    fun writeGetCdnTokenExReq(os: TarsOutputStream, streamName: String, ua: String) {
        os.writeString("", 0)       // sFlvUrl
        os.writeString(streamName, 1) // sStreamName
        os.writeInt(0L, 2)          // iLoopTime
        os.writeStruct(3) { writeUserId(this, ua) }
        os.writeInt(66L, 4)         // iAppId
    }
}

// ---------------- 弹幕推送结构 ----------------

class HYPushMessage {
    var pushType = 0L
    var uri = 0L
    var msg: ByteArray = ByteArray(0)
    var protocolType = 0L
}

class HYSender {
    var nickName = ""
}

class HYBulletFormat {
    var fontColor = 0L
}

class HYMessage {
    val userInfo = HYSender()
    var content = ""
    val bulletFormat = HYBulletFormat()
}

/**
 * 与 Flutter 版 HYPushMessage.readFrom 对齐：字段直接位于流顶层
 * （pushType=0, uri=1, msg=2, protocolType=3），**不是**嵌套在 tag 0 的 struct 里。
 * 此前多套了一层 readStruct(0)，解析时抛 type mismatch 被吞掉，虎牙弹幕一条都收不到。
 */
fun parseHYPushMessage(data: ByteArray): HYPushMessage {
    val s = TarsInputStream(data)
    val m = HYPushMessage()
    m.pushType = s.readInt(0)
    m.uri = s.readInt(1)
    m.msg = s.readBytes(2)
    m.protocolType = s.readInt(3)
    return m
}

/** 与 Flutter 版 HYMessage.readFrom 对齐：userInfo=struct(0)、content=3、bulletFormat=struct(6)，同样位于顶层 */
fun parseHYMessage(data: ByteArray): HYMessage {
    val s = TarsInputStream(data)
    val m = HYMessage()
    s.readStruct(0) { // HYSender
        readInt(0)      // uid
        readInt(0)      // lMid（Dart 源码同为 tag 0）
        m.userInfo.nickName = readString(2)
    }
    m.content = s.readString(3)
    s.readStruct(6) { m.bulletFormat.fontColor = readInt(0) } // HYBulletFormat
    return m
}
