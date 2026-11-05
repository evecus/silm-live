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

    companion object {
        private const val TAG = "silm"
    }

    protected var onMessage: ((DanmakuMessage) -> Unit)? = null
    protected var onClose: ((String) -> Unit)? = null

    /** start() 传入的弹幕参数，由子类按平台类型自行转换（此前未下发，弹幕从未启动） */
    protected var startArgs: Any? = null
    private var msgCount = 0

    private var ws: WebSocket? = null
    private var candidateUrls: List<String> = emptyList()
    private var heartbeatJob: Job? = null
    private var stopped = false
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 心跳间隔；抖音会由 IM 预取结果动态改写 */
    protected open var heartbeatIntervalMs: Long = 60_000
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
        startArgs = args
        stopped = false
        scope.launch {
            val urls = try {
                buildUrls()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "弹幕 buildUrls 异常: ${e.message}")
                emptyList()
            }
            android.util.Log.i(TAG, "弹幕连接候选地址: $urls")
            candidateUrls = urls
            reconnectCount = 0
            connectAll(urls)
        }
    }

    /** 子类收到弹幕时调用（用于计数与状态上报） */
    protected fun onChatReceived() {
        msgCount++
        if (msgCount == 1) android.util.Log.i(TAG, "弹幕已开始接收")
    }

    /** 已建立连接后被断开时的最大重连次数（与 Flutter WebScoketUtils 一致） */
    private val maxReconnect = 5
    private val reconnectDelayMs = 5_000L
    private var reconnectCount = 0

    private fun connectAll(urls: List<String>, attempt: Int = 0) {
        if (stopped || urls.isEmpty()) {
            if (!stopped) onClose?.invoke("弹幕服务器连接失败")
            return
        }
        val allUrls = candidateUrls.ifEmpty { urls }
        val url = urls.first()
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        // 同一个 socket 的 onFailure / onClosing / onClosed 可能连续触发，只处理第一次
        val handled = java.util.concurrent.atomic.AtomicBoolean(false)
        var opened = false

        fun onDisconnected(reason: String) {
            if (!handled.compareAndSet(false, true)) return
            heartbeatJob?.cancel()
            if (stopped) return
            if (opened) {
                // 已连通后断开：与 Flutter 一致，间隔 5s 重连，最多 5 次；收到消息即清零计数
                if (reconnectCount < maxReconnect) {
                    reconnectCount++
                    android.util.Log.w(TAG, "弹幕断开($reason)，${reconnectDelayMs / 1000}s 后第 $reconnectCount 次重连")
                    scope.launch {
                        delay(reconnectDelayMs)
                        if (!stopped) connectAll(allUrls)
                    }
                } else {
                    onClose?.invoke("重连超过最大次数，与服务器断开连接")
                }
                return
            }
            // 握手阶段失败：依次尝试候选地址
            if (urls.size > 1) {
                connectAll(urls.drop(1), attempt)
            } else if (attempt < 2) {
                scope.launch {
                    delay(3000)
                    connectAll(allUrls, attempt + 1)
                }
            } else {
                onClose?.invoke("弹幕连接失败：$reason")
            }
        }

        ws = HttpHolder.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                ws = webSocket // 进房包发送依赖 ws，避免与 newWebSocket 返回值赋值竞态
                android.util.Log.i(TAG, "弹幕 WS 已连接: $url")
                heartbeatJob?.cancel()
                // 与 Flutter 一致：先发进房包，心跳等一个周期后再发。
                // 此前心跳在进房包之前立刻发出，虎牙/斗鱼会把"未进房先心跳"当作异常。
                onOpen()
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(heartbeatIntervalMs)
                        heartbeat()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                reconnectCount = 0
                try {
                    onMessageInternal(bytes)
                } catch (e: Exception) {
                    // 忽略单帧解析错误
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                reconnectCount = 0
                try {
                    onMessageInternal(okio.ByteString.of(*text.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = t.message ?: t.javaClass.simpleName
                android.util.Log.e(TAG, "弹幕 WS 失败: $url -> $reason")
                onDisconnected(reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // 服务端主动关闭：先回应关闭帧，再按断线处理（此前未重连，弹幕会就此中断）
                try { webSocket.close(1000, null) } catch (_: Exception) {}
                android.util.Log.w(TAG, "弹幕 WS 被服务端关闭: code=$code reason=$reason")
                onDisconnected("closed $code")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnected("closed $code")
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
    /**
     * RESTRICTED_TLS 只有 GCM 套件，斗鱼 danmuproxy 8501-8506 服务器仅支持
     * 旧式 CBC 套件（沙箱实测：GCM-only 握手被拒），握手失败表现为 onFailure(null)。
     * 加 COMPATIBLE_TLS（含 CBC）作为回退，OkHttp 握手失败会按顺序尝试下一个规格。
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(
            listOf(
                okhttp3.ConnectionSpec.RESTRICTED_TLS,
                okhttp3.ConnectionSpec.MODERN_TLS,
                okhttp3.ConnectionSpec.COMPATIBLE_TLS,
            )
        )
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
