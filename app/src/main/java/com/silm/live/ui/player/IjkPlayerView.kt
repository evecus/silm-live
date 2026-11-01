package com.silm.live.ui.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 按视频原始比例缩放的容器（ExoPlayer AspectRatioFrameLayout 思路）：
 * 视频在容器内等比缩放（letterbox），不拉伸填充。
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 视频宽高比（w/h），0 表示未知（退化为填满容器） */
    var videoAspectRatio: Float = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (videoAspectRatio <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val viewAspect = width.toFloat() / height.toFloat()
        val fitWidth: Int
        val fitHeight: Int
        if (videoAspectRatio > viewAspect) {
            // 视频更宽：以宽度为准，上下留黑边
            fitWidth = width
            fitHeight = (width / videoAspectRatio).toInt()
        } else {
            // 视频更高：以高度为准，左右留黑边
            fitHeight = height
            fitWidth = (height * videoAspectRatio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(fitWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(fitHeight, MeasureSpec.EXACTLY),
        )
    }
}

/**
 * 统一播放器（ijk 内核）：ijk/ffmpeg 原生支持 HLS/m3u8、FLV、RTMP，
 * 所有平台的所有线路都由它播放，不再使用 ExoPlayer。
 * SurfaceView 包在 AspectRatioFrameLayout 内，按视频原始比例显示（不填充屏幕）。
 */
class IjkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "silm"
    }

    private var player: IjkMediaPlayer? = null
    private var currentUrl: String? = null
    private var surfaceReady = false
    private var pendingUrl: String? = null
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingHwDecode: Boolean = true
    private var currentHwDecode: Boolean = true
    private val holder: SurfaceHolder
    private val aspectLayout: AspectRatioFrameLayout

    /** 播放失败回调（what/extra 为 ijk 错误码），上层用于切线路/提示 */
    var onError: ((what: Int, extra: Int) -> Unit)? = null

    /** 视频尺寸回调（宽/高），用于跟随视频方向切屏幕方向 */
    var onVideoSize: ((width: Int, height: Int) -> Unit)? = null

    init {
        aspectLayout = AspectRatioFrameLayout(context)
        val surfaceView = SurfaceView(context)
        aspectLayout.addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        // 居中放置，letterbox 黑边上下/左右均分
        addView(
            aspectLayout,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER)
        )
        holder = surfaceView.holder
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                surfaceReady = true
                player?.setDisplay(h)
                pendingUrl?.let { start(it, pendingHeaders, pendingHwDecode) }
            }

            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceReady = false
                player?.setDisplay(null)
            }
        })
    }

    /** 幂等：同一个地址且解码方式不变时不重复起播 */
    fun play(url: String, headers: Map<String, String> = emptyMap(), hwDecode: Boolean = true) {
        if (url == currentUrl && hwDecode == currentHwDecode && player != null) return
        if (!surfaceReady) {
            pendingUrl = url
            pendingHeaders = headers
            pendingHwDecode = hwDecode
            return
        }
        start(url, headers, hwDecode)
    }

    private fun start(url: String, headers: Map<String, String>, hwDecode: Boolean) {
        pendingUrl = null
        currentHwDecode = hwDecode
        release()
        try {
            IjkMediaPlayer.loadLibrariesOnce(null)
            // 打开 native 日志 (tag: IJKMEDIA/IJKPLAYER/FFPlayer):
            // ijk 报的 what=-10000 是通用错误, 真实原因(HTTP 403/拒绝连接/DNS/协议缺失)
            // 只在 native 日志里, 必须开着才能诊断.
            runCatching { IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_DEBUG) }
            IjkMediaPlayer.native_profileBegin("libijkplayer.so")
            val p = IjkMediaPlayer()
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 32)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48)
            // 解码方式：硬解走 MediaCodec（省电、降低 CPU 占用），软解走 FFmpeg（兼容性好）
            if (hwDecode) {
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1)
            }
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0)
            // reconnect 在 ffmpeg 5.x 是布尔选项(只接受 0/1), 传其他值会让
            // avformat_open_input 直接 Invalid argument 秒失败(无网络请求!)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", 10_000_000)
            p.setOnPreparedListener {
                Log.i(TAG, "ijk prepared: $url")
                it.start()
            }
            // 视频尺寸回调 → 更新容器比例（原始比例，letterbox）
            p.setOnVideoSizeChangedListener { _, w, h, _, _ ->
                if (w > 0 && h > 0) {
                    aspectLayout.videoAspectRatio = w.toFloat() / h.toFloat()
                    aspectLayout.requestLayout()
                    onVideoSize?.invoke(w, h)
                }
            }
            p.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "ijk error what=$what extra=$extra url=$url")
                dumpNativeLogs()
                onError?.invoke(what, extra)
                true
            }
            // ijk 是 native 层直连, 不走系统代理; 设备配了代理时 native 直连会被拒.
            // 把系统 http 代理透传给 ffmpeg (ffmpeg http 协议的 http_proxy 选项).
            systemHttpProxyFor(url)?.let { proxy ->
                Log.i(TAG, "ijk 使用系统代理: $proxy")
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http_proxy", proxy)
            }
            // User-Agent 用 ffmpeg 的 user_agent 选项单独设置：
            // 若留在 headers 里与 ffmpeg 默认 UA 并存，部分 CDN（虎牙）校验后直接 403
            val ua = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
            if (!ua.isNullOrEmpty()) {
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", ua)
            }
            val restHeaders = headers.filterKeys { !it.equals("user-agent", true) }
            if (restHeaders.isNotEmpty()) {
                p.setDataSource(context, android.net.Uri.parse(url), restHeaders)
            } else {
                p.dataSource = url
            }
            p.setDisplay(holder)
            p.prepareAsync()
            player = p
            currentUrl = url
            Log.i(TAG, "ijk start: $url")
        } catch (e: Exception) {
            Log.e(TAG, "ijk start 异常", e)
            onError?.invoke(-1, -1)
        }
    }

    /**
     * 把本进程 native ijk 日志(IJKMEDIA/IJKPLAYER/FFPlayer)搬进 silm tag.
     * ijk 的 what=-10000 extra=0 不含真实原因(如 avformat_open_input failed: xxx),
     * 真实错误只在 native 日志里; App 读取自己进程的 logcat 无需任何权限.
     */
    private fun dumpNativeLogs() {
        try {
            val p = ProcessBuilder("logcat", "-d", "-t", "500", "-v", "brief").start()
            val lines = p.inputStream.bufferedReader().readLines()
            p.destroy()
            val native = lines.filter {
                it.contains("IJKMEDIA") || it.contains("IJKPLAYER") ||
                    it.contains("FFPlayer") || it.contains("ff_ffplay")
            }
            // 1) 先输出错误相关行 (最关键, 排前面)
            val errors = native.filter {
                it.contains("failed") || it.contains("Failed") ||
                    it.contains("error") || it.contains("Error") ||
                    it.contains("not found") || it.contains("No such") ||
                    it.contains("refused") || it.contains("timeout") ||
                    it.contains("Immediate exit") || it.contains("unrecognized")
            }
            errors.takeLast(30).forEach { Log.w(TAG, "native-err| $it") }
            // 2) 再输出未过滤的最后 25 行 (兜底上下文)
            native.takeLast(25).forEach { Log.w(TAG, "native| $it") }
            if (native.isEmpty()) {
                Log.w(TAG, "native 日志: (未捕获到 IJKMEDIA/IJKPLAYER/FFPlayer 行)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 native 日志失败: ${e.message}")
        }
    }

    /** 读取系统 http 代理（Wi-Fi/VPN http 代理），无则返回 null */
    private fun systemHttpProxyFor(url: String): String? = try {
        val selector = java.net.ProxySelector.getDefault() ?: return null
        val proxy = selector.select(java.net.URI(url)).firstOrNull() ?: return null
        if (proxy.type() == java.net.Proxy.Type.HTTP && proxy.address() is java.net.InetSocketAddress) {
            val addr = proxy.address() as java.net.InetSocketAddress
            "http://${addr.hostString}:${addr.port}"
        } else null
    } catch (e: Exception) {
        null
    }

    fun release() {
        currentUrl = null
        player?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
            }
        }
        player = null
    }
}

/** Compose 封装：地址变化时重播 */
@Composable
fun IjkPlayer(
    url: String,
    headers: Map<String, String>,
    onError: ((what: Int, extra: Int) -> Unit)? = null,
    onVideoSize: ((width: Int, height: Int) -> Unit)? = null,
    hwDecode: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val viewRef = remember { mutableStateOf<IjkSurfaceView?>(null) }
    val errCallback = remember { mutableStateOf(onError) }
    errCallback.value = onError
    val sizeCallback = remember { mutableStateOf(onVideoSize) }
    sizeCallback.value = onVideoSize
    // key 包裹：切换解码方式时重建播放器视图（ijk 解码器只能在 prepare 前指定）
    androidx.compose.runtime.key(url, hwDecode) {
        AndroidView(
            factory = { ctx ->
                IjkSurfaceView(ctx).also {
                    viewRef.value = it
                    it.onError = { w, e -> errCallback.value?.invoke(w, e) }
                    it.onVideoSize = { w, h -> sizeCallback.value?.invoke(w, h) }
                    it.play(url, headers, hwDecode)
                }
            },
            update = {
                it.play(url, headers, hwDecode)
            },
            modifier = modifier,
        )
        DisposableEffect(url, hwDecode) {
            onDispose { viewRef.value?.release() }
        }
    }
}
