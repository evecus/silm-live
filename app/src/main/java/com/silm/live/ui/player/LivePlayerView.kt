package com.silm.live.ui.player

import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

/** 画面比例（TV 控制栏「画面」按钮） */
enum class VideoAspect(val label: String, val ratio: Float?) {
    DEFAULT("默认", null),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_3("4:3", 4f / 3f),
    CROP("裁剪填充", null),
    STRETCH("拉伸", null),
}

/**
 * 统一播放器（ExoPlayer / Media3 内核）：HLS 由 hls 模块播放，
 * FLV / TS 由 extractor 的 FlvExtractor / TsExtractor 支持逐段拉流。
 *
 * 关键行为（与原 mpv 版保持一致）:
 *   - 换线路/换清晰度/切换解码方式 → 重建 ExoPlayer（headers 烧在 DataSource 工厂里，重建最干净）
 *   - 视频按原始比例 letterbox，不拉伸填充（PlayerView 默认 RESIZE_MODE_FIT）
 *   - 打开失败 → onError(-1000, errorCode)；已开播后断流/结束 → onError(-1001, errorCode)
 *   - 直播流正常不该结束，收到 STATE_ENDED 视为断流交给上层切线路
 */
@UnstableApi
class LivePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "silm"

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 打开失败（未收到首帧） */
        const val ERR_OPEN = -1000

        /** 播放中断（已开播后出错/断流） */
        const val ERR_INTERRUPT = -1001

        /** 共享 HTTP 客户端：超时在这里配，OkHttpDataSource.Factory 没有超时相关方法 */
        val okHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }

    private class Request(val url: String, val headers: Map<String, String>, val hwDecode: Boolean)

    private val playerView: PlayerView = PlayerView(context)

    private var player: ExoPlayer? = null

    /** 当前已加载的请求（用于幂等判断） */
    private var currentRequest: Request? = null

    /** 是否已成功开播过（区分"打开失败"与"播放中断"） */
    private var playedOnce = false

    /** 是否因换源主动释放旧播放器（此时不再触发错误回调） */
    private var releasing = false

    /** 播放失败回调，上层用于切线路/提示 */
    var onError: ((what: Int, extra: Int) -> Unit)? = null

    /** 视频尺寸回调（宽/高），用于跟随视频方向切屏幕方向 */
    var onVideoSize: ((width: Int, height: Int) -> Unit)? = null

    init {
        playerView.useController = false
        // 与 mpv 版一致：等比缩放留黑边，不填充
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
        addView(
            playerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    // ================================================================ 对外

    /** 幂等：同一地址 + 请求头 + 解码方式不重复起播 */
    fun play(url: String, headers: Map<String, String> = emptyMap(), hwDecode: Boolean = true) {
        val req = Request(url, headers, hwDecode)
        val cur = currentRequest
        if (cur == req) return
        buildAndStart(req)
    }

    /** 画面缩放模式：默认等比留黑边；16:9/4:3/拉伸 = 拉伸填满容器；裁剪填充 = 等比放大裁切 */
    fun setAspect(aspect: VideoAspect) {
        playerView.resizeMode = when (aspect) {
            VideoAspect.DEFAULT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoAspect.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoAspect.RATIO_16_9, VideoAspect.RATIO_4_3, VideoAspect.STRETCH ->
                AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    fun release() {
        releasing = true
        currentRequest = null
        player?.let {
            it.removeListener(listener)
            it.stop()
            it.clearVideoSurface()
        }
        playerView.player = null
        player?.release()
        player = null
        // SurfaceView 是独立窗口层，不随 Compose 转场/旋转一起消失：
        // 释放时立刻隐藏 surface 并从 View 树移除，否则会残留最后一帧
        playerView.videoSurfaceView?.visibility = android.view.View.GONE
        playerView.visibility = android.view.View.GONE
        removeAllViews()
    }

    // ================================================================ 构建

    private fun buildAndStart(req: Request) {
        releasing = false
        playedOnce = false
        currentRequest = req

        player?.let {
            it.removeListener(listener)
            it.release()
        }
        playerView.visibility = android.view.View.VISIBLE
        playerView.videoSurfaceView?.visibility = android.view.View.VISIBLE

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            if (!req.hwDecode) {
                // 软解：只选 MediaCodec 软件解码器（c2.android.* / OMX.google.* 等）
                setMediaCodecSelector { mimeType, secure, tunneling ->
                    val all = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
                    val sw = all.filter { !it.hardwareAccelerated }
                    if (sw.isEmpty()) all else sw
                }
            }
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters().setTunnelingEnabled(false).build()
        }

        // 直播低延迟：小缓冲，够抗抖动即可
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .build()

        // OkHttp 默认跟随 http<->https 重定向、走 ProxySelector.getDefault()（系统代理自动透传）
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(req.headers)
            .setUserAgent(req.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: DEFAULT_UA)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        val newPlayer = ExoPlayer.Builder(context, renderersFactory, mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(req.url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3_000)
                    .setMinOffsetMs(2_000)
                    .setMaxOffsetMs(8_000)
                    .build(),
            )
            .build()

        newPlayer.addListener(listener)
        newPlayer.setMediaItem(mediaItem)
        newPlayer.playWhenReady = true
        newPlayer.prepare()

        player = newPlayer
        playerView.player = newPlayer
        Log.i(TAG, "ExoPlayer 起播: ${req.url} hw=${req.hwDecode}")
    }

    // ================================================================ 事件

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                onVideoSize?.invoke(videoSize.width, videoSize.height)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                playedOnce = true
            } else if (playbackState == Player.STATE_ENDED && !releasing) {
                // 直播流正常不会结束：断流交给上层切线路/重载
                Log.e(TAG, "ExoPlayer STATE_ENDED (playedOnce=$playedOnce) url=${currentRequest?.url}")
                onError?.invoke(ERR_INTERRUPT, 0)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (releasing) return
            val what = if (playedOnce) ERR_INTERRUPT else ERR_OPEN
            Log.e(TAG, "ExoPlayer error code=${error.errorCode} (playedOnce=$playedOnce)", error)
            onError?.invoke(what, error.errorCode)
        }
    }
}

/**
 * Compose 封装：地址/请求头/解码方式变化时重建播放器（不重建 View）。
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayer(
    url: String,
    headers: Map<String, String>,
    onError: ((what: Int, extra: Int) -> Unit)? = null,
    onVideoSize: ((width: Int, height: Int) -> Unit)? = null,
    hwDecode: Boolean = true,
    aspect: VideoAspect = VideoAspect.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val viewRef = remember { mutableStateOf<LivePlayerView?>(null) }
    val errCallback = remember { mutableStateOf(onError) }
    errCallback.value = onError
    val sizeCallback = remember { mutableStateOf(onVideoSize) }
    sizeCallback.value = onVideoSize

    AndroidView(
        factory = { ctx ->
            LivePlayerView(ctx).also {
                viewRef.value = it
                it.onError = { w, e -> errCallback.value?.invoke(w, e) }
                it.onVideoSize = { w, h -> sizeCallback.value?.invoke(w, h) }
                it.setAspect(aspect)
                it.play(url, headers, hwDecode)
            }
        },
        update = {
            it.setAspect(aspect)
            it.play(url, headers, hwDecode)
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose { viewRef.value?.release() }
    }
}
