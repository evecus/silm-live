package com.silm.live.sync

import android.content.Context
import android.os.Build
import com.silm.live.data.FollowItem
import com.silm.live.data.FollowStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 局域网同步发现的设备 */
data class SyncPeer(
    val deviceId: String,
    val name: String,
    val address: String,
    val type: String,
)

/**
 * 局域网关注同步（协议对齐 dart_simple_live 的 SyncService）：
 *  - UDP 23235 广播发现：收到 "hello" 广播即回复自己的 info（仅同步页面打开时运行）
 *  - HTTP 23234 提供 POST /sync/follow?overlay=1 接收关注列表（必须 overlay=1 才覆盖）
 *  - 发起端直接把本地关注 JSON 数组 POST 给目标设备，overlay=1 表示覆盖
 *
 * 只有进入「远程关注同步」页面才启动 UDP 监听与广播，
 * 其他设备也只有在同步页面里才能扫描到本机。
 */
object LanSyncService {

    const val UDP_PORT = 23235
    const val HTTP_PORT = 23234

    private const val TAG = "silm-sync"

    var deviceName: String = "Silm Live"
        private set
    val deviceId: String by lazy { buildDeviceId() }

    private var scope: CoroutineScope? = null
    private var udpSocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var broadcastJob: Job? = null

    private var appContext: Context? = null

    /** 当前扫描到的设备回调（列表只在本机内存中，离开页面即清空） */
    @Volatile
    var onPeerFound: ((SyncPeer) -> Unit)? = null

    /** 服务启动失败信息（端口占用等） */
    @Volatile
    var lastError: String? = null

    val running: Boolean
        get() = udpSocket != null && serverSocket != null

    private fun buildDeviceId(): String {
        val raw = "${Build.MODEL}-${Build.SERIAL}-${android.os.Build.FINGERPRINT}"
        return Integer.toHexString(raw.hashCode())
    }

    /** 进入同步页调用：启动 UDP + HTTP + 定时广播 hello */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        deviceName = buildDeviceName()
        lastError = null
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        // UDP 发现
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(UDP_PORT))
                soTimeout = 0
            }
            udpSocket = socket
            s.launch { udpLoop(socket) }
        } catch (e: Exception) {
            udpSocket = null
            lastError = "UDP ${UDP_PORT} 端口不可用：${e.message}"
        }

        // HTTP 接收端
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(HTTP_PORT))
            serverSocket = server
            s.launch { httpLoop(server) }
        } catch (e: Exception) {
            serverSocket = null
            lastError = "HTTP ${HTTP_PORT} 端口不可用：${e.message}"
        }

        // 定时广播，让后进入页面的设备也能发现本机
        broadcastJob = s.launch {
            while (isActive) {
                sendHello()
                delay(2_000)
            }
        }
    }

    /** 离开同步页调用：关闭所有服务，停止被发现 */
    @Synchronized
    fun stop() {
        broadcastJob?.cancel()
        broadcastJob = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        onPeerFound = null
    }

    /** 刷新：广播 hello，等待其他设备回复 */
    fun sendHello() {
        val socket = udpSocket ?: return
        try {
            val payload = JSONObject()
                .put("id", deviceId)
                .put("type", "hello")
                .toString()
                .toByteArray(Charsets.UTF_8)
            val addresses = broadcastAddresses()
            addresses.forEach { addr ->
                runCatching {
                    socket.send(
                        DatagramPacket(payload, payload.size, addr, UDP_PORT)
                    )
                }
            }
            // 兜底：直接发往 255.255.255.255（部分设备不响应定向广播）
            runCatching {
                socket.send(
                    DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName("255.255.255.255"),
                        UDP_PORT
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "sendHello 失败: ${e.message}")
        }
    }

    /** 本机各网卡对应的广播地址 */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.interfaceAddresses.forEach { ia ->
                    ia.broadcast?.let { result.add(it) }
                }
            }
        }
        return result
    }

    private fun udpLoop(socket: DatagramSocket) {
        val buffer = ByteArray(64 * 1024)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                handleUdp(packet, text)
            } catch (e: Exception) {
                if (socket.isClosed) break
                android.util.Log.d(TAG, "udp 接收异常: ${e.message}")
            }
        }
    }

    private fun handleUdp(packet: DatagramPacket, text: String) {
        if (!text.startsWith("{") || !text.endsWith("}")) return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = json.optString("id", "")
        if (id.isEmpty() || id == deviceId) return
        val type = json.optString("type", "")
        if (type == "hello") {
            // 别人在找设备，直接单播回复自己的信息（比广播更可靠）
            replyInfo(packet.address, packet.port)
            return
        }
        val address = packet.address.hostAddress ?: return
        onPeerFound?.invoke(
            SyncPeer(
                deviceId = id,
                name = json.optString("name", address),
                address = address,
                type = type,
            )
        )
    }

    private fun replyInfo(address: InetAddress, port: Int) {
        val socket = udpSocket ?: return
        runCatching {
            val payload = JSONObject()
                .put("id", deviceId)
                .put("type", "android")
                .put("name", deviceName)
                .toString()
                .toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, address, port))
        }
    }

    private fun httpLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (server.isClosed) break
                continue
            }
            scope?.launch { handleHttp(client) }
        }
    }

    private suspend fun handleHttp(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            try {
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")

                var contentLength = 0
                var line: String?
                do {
                    line = readLine(input)
                    if (line.isNullOrEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                } while (true)

                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    buf
                } else {
                    ByteArray(0)
                }

                when {
                    method == "GET" && (path == "/" || path == "/info") -> {
                        writeJson(s.getOutputStream(), infoJson())
                    }
                    method == "POST" && path == "/sync/follow" -> {
                        val overlay = queryParam(query, "overlay") == "1"
                        writeJson(s.getOutputStream(), importFollows(body, overlay))
                    }
                    else -> writeJson(
                        s.getOutputStream(),
                        JSONObject().put("status", false).put("message", "not found"),
                        code = 404
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "http 处理异常: ${e.message}")
                runCatching {
                    writeJson(
                        s.getOutputStream(),
                        JSONObject().put("status", false).put("message", e.message ?: "error"),
                        code = 500
                    )
                }
            }
        }
    }

    private fun infoJson(): JSONObject = JSONObject()
        .put("status", true)
        .put("app", "Silm Live")
        .put("id", deviceId)
        .put("type", "android")
        .put("name", deviceName)
        .put("port", HTTP_PORT)

    /**
     * 接收关注列表。overlay=1 时覆盖本机全部关注（不需要用户确认）；
     * 否则合并（同名同房间去重）。
     */
    private suspend fun importFollows(body: ByteArray, overlay: Boolean): JSONObject =
        withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext JSONObject().put("status", false).put("message", "no context")
        try {
            val text = String(body, Charsets.UTF_8).trim()
            val arr = if (text.startsWith("{")) {
                // 兼容部分客户端包一层 { "data": [...] }
                JSONObject(text).optJSONArray("data") ?: JSONArray()
            } else {
                JSONArray(text)
            }
            val imported = mutableListOf<FollowItem>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { FollowItem.fromJson(it)?.let(imported::add) }
            }
            FollowStore.replaceAll(ctx, imported, replace = overlay)
            JSONObject()
                .put("status", true)
                .put("message", if (overlay) "已覆盖关注列表" else "已合并关注列表")
                .put("imported", imported.size)
        } catch (e: Exception) {
            JSONObject().put("status", false).put("message", e.message ?: "格式错误")
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            if (prev != -1) out.write(prev)
            prev = b
        }
    }

    private fun queryParam(query: String, key: String): String? {
        if (query.isEmpty()) return null
        return query.split("&").firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }

    private fun writeJson(out: OutputStream, json: JSONObject, code: Int = 200) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: "Android"
        val pretty = if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model"
        return pretty.trim().ifEmpty { "Android" }
    }

    /** 发起端：把本地关注列表推送到目标设备（overlay=true 表示覆盖对方数据） */
    fun pushFollows(
        context: Context,
        peer: SyncPeer,
        items: List<FollowItem>,
        overlay: Boolean,
    ): Result<Int> {
        return try {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            val body = arr.toString().toByteArray(Charsets.UTF_8)
            val url = java.net.URL(
                "http://${peer.address}:$HTTP_PORT/sync/follow?overlay=${if (overlay) 1 else 0}"
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (json?.optBoolean("status", false) == true) {
                Result.success(json.optInt("imported", items.size))
            } else {
                Result.failure(Exception(json?.optString("message")?.ifEmpty { null } ?: "同步失败（HTTP ${conn.responseCode}）"))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "连接失败"))
        }
    }
}
