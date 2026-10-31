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
 * 统一播放器（ijk 内核）：ijk/ffmpeg 原生支持 HLS/m3u8、FLV、RTMP，
 * 四个平台（B站/斗鱼/虎牙/抖音）的所有线路都由它播放，不再使用 ExoPlayer。
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
    private val holder: SurfaceHolder

    /** 播放失败回调（what/extra 为 ijk 错误码），上层用于切线路/提示 */
    var onError: ((what: Int, extra: Int) -> Unit)? = null

    init {
        val surfaceView = SurfaceView(context)
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        holder = surfaceView.holder
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                surfaceReady = true
                player?.setDisplay(h)
                pendingUrl?.let { start(it, pendingHeaders) }
            }

            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceReady = false
                player?.setDisplay(null)
            }
        })
    }

    /** 幂等：同一个地址不重复起播 */
    fun play(url: String, headers: Map<String, String> = emptyMap()) {
        if (url == currentUrl && player != null) return
        if (!surfaceReady) {
            pendingUrl = url
            pendingHeaders = headers
            return
        }
        start(url, headers)
    }

    private fun start(url: String, headers: Map<String, String>) {
        pendingUrl = null
        release()
        try {
            IjkMediaPlayer.loadLibrariesOnce(null)
            IjkMediaPlayer.native_profileBegin("libijkplayer.so")
            val p = IjkMediaPlayer()

            // 硬件解码（修复黑屏的关键配置）
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)

            // 渲染 / 缓冲
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 1024 * 1024)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 2)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 3000)

            // 格式 / 网络（直播低延迟）
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 32)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 5)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", 15_000_000)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15_000_000)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer")
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flags", "low_delay")

            // 解码
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48)

            p.setOnPreparedListener {
                Log.i(TAG, "ijk prepared: $url")
                it.start()
            }
            p.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "ijk error what=$what extra=$extra url=$url")
                onError?.invoke(what, extra)
                true
            }
            p.setOnInfoListener { _, what, _ ->
                if (what == IjkMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.i(TAG, "video rendering start")
                }
                false
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

    fun release() {
        currentUrl = null
        player?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
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
    modifier: Modifier = Modifier,
) {
    val viewRef = remember { mutableStateOf<IjkSurfaceView?>(null) }
    val errCallback = remember { mutableStateOf(onError) }
    errCallback.value = onError
    AndroidView(
        factory = { ctx ->
            IjkSurfaceView(ctx).also {
                viewRef.value = it
                it.onError = { w, e -> errCallback.value?.invoke(w, e) }
                it.play(url, headers)
            }
        },
        update = {
            it.play(url, headers)
        },
        modifier = modifier,
    )
    DisposableEffect(url) {
        onDispose { viewRef.value?.release() }
    }
}
