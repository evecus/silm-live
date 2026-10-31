package com.silm.live.danmaku

import com.silm.live.core.DanmakuMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 弹幕客户端抽象 */
interface DanmakuClient {
    fun start(args: Any?, onMessage: (DanmakuMessage) -> Unit, onClose: (String) -> Unit)
    fun stop()
}

/**
 * WebSocket 弹幕基类：连接、心跳、断线重连（按候选地址轮询）。
 */
abstract class BaseDanmaku : DanmakuClient {

    protected var onMessage: ((DanmakuMessage) -> Unit)? = null
    protected var onClose: ((String) -> Unit)? = null

    private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var stopped = false
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    protected open val heartbeatIntervalMs: Long = 60_000
    protected open val headers: Map<String, String> = emptyMap()

    /** 候选连接地址，按顺序尝试（可能需要签名等耗时操作） */
    protected abstract suspend fun buildUrls(): List<String>

    /** 连接建立后调用（发送进房包） */
    protected abstract fun onOpen()

    /** 心跳 */
    protected abstract fun heartbeat()

    /** 收到二进制/文本帧 */
    protected abstract fun onMessageInternal(bytes: ByteString)

    protected fun send(bytes: ByteArray): Boolean = ws?.send(okio.ByteString.of(*bytes)) ?: false

    override fun start(args: Any?, onMessage: (DanmakuMessage) -> Unit, onClose: (String) -> Unit) {
        this.onMessage = onMessage
        this.onClose = onClose
        stopped = false
        scope.launch {
            val urls = try {
                buildUrls()
            } catch (e: Exception) {
                emptyList()
            }
            connectAll(urls)
        }
    }

    private fun connectAll(urls: List<String>, attempt: Int = 0) {
        if (stopped || urls.isEmpty()) {
            if (!stopped) onClose?.invoke("弹幕服务器连接失败")
            return
        }
        val url = urls.first()
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        ws = HttpHolder.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive) {
                        heartbeat()
                        delay(heartbeatIntervalMs)
                    }
                }
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    onMessageInternal(bytes)
                } catch (e: Exception) {
                    // 忽略单帧解析错误
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    onMessageInternal(okio.ByteString.of(*text.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeatJob?.cancel()
                if (stopped) return
                if (urls.size > 1) {
                    connectAll(urls.drop(1))
                } else if (attempt < 2) {
                    scope.launch {
                        delay(3000)
                        connectAll(urls, attempt + 1)
                    }
                } else {
                    onClose?.invoke("弹幕连接失败：${t.message ?: "未知错误"}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatJob?.cancel()
            }
        })
    }

    override fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        scope.cancel()
        ws?.close(1000, "close")
        ws = null
        onMessage = null
        onClose = null
    }
}

object HttpHolder {
    val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
}

/** 简易 protobuf 编码器（仅用于抖音 PushFrame） */
class PbWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun varint(v: Long): PbWriter {
        var value = v
        while (true) {
            if (value and 0x7F.inv() == 0L) {
                out.write(value.toInt())
                return this
            }
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
    }

    fun tag(field: Int, wire: Int): PbWriter = varint(((field shl 3) or wire).toLong())

    fun str(field: Int, value: String): PbWriter {
        tag(field, 2)
        val bytes = value.toByteArray(Charsets.UTF_8)
        varint(bytes.size.toLong())
        out.write(bytes)
        return this
    }

    fun bytes(field: Int, value: ByteArray): PbWriter {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun uint64(field: Int, value: Long): PbWriter {
        tag(field, 0)
        varint(value)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
