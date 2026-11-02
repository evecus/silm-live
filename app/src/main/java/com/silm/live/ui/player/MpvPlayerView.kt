package com.silm.live.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib
import java.lang.reflect.Proxy

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
 * 统一播放器（mpv 内核, libmpv = 完整 FFmpeg）：HLS / FLV / RTMP 等全部由它播放.
 *
 * 关键约束:
 *   - libmpv 的 MPVLib 是进程级单例, 同一时刻只能有一个实例 → 用 [current] 保证新实例创建前
 *     先销毁旧实例 (页面切换/重建时新旧 View 的生命周期可能交叠).
 *   - 换线路/换清晰度不重建 mpv, 只对同一实例重新 loadfile (与 ijk 版"重建播放器"不同).
 *   - mpv 的事件回调来自 native 线程, 一律 post 回主线程再处理.
 *
 * 排查播放失败: mpv 自己会把原因 (HTTP 403 / 协议不支持 / 解码失败) 写进 logcat, tag 为 "mpv".
 */
class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "silm"

        // libmpv 事件/格式常量 (mpv client.h), 直接用数值, 不依赖封装库内部类名.
        private const val MPV_FORMAT_INT64 = 4
        private const val MPV_EVENT_END_FILE = 7
        private const val MPV_EVENT_FILE_LOADED = 8

        private const val HW_DEC = "mediacodec,mediacodec-copy"

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 当前持有 libmpv 的实例 (进程内唯一). */
        @Volatile
        private var current: MpvSurfaceView? = null
    }

    private class Request(val url: String, val headers: Map<String, String>, val hwDecode: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val aspectLayout: AspectRatioFrameLayout
    private val surfaceView: SurfaceView

    private var mpvReady = false
    private var surfaceReady = false

    /** 已发出的请求 (用于幂等判断) */
    private var currentRequest: Request? = null

    /** 等 surface 就绪后再 loadfile 的请求 */
    private var pending: Request? = null

    /** 已发出 loadfile 且还没收到 END_FILE */
    private var active = false

    /** 当前文件是否已加载成功 (区分"打开失败"与"播放中断") */
    private var loaded = false

    /** 换源时旧文件会产生一次 END_FILE, 要忽略的次数 */
    private var suppressEnd = 0

    private var dw = 0L
    private var dh = 0L

    /** 播放失败回调（what: -1000=打开失败, -1001=播放中断），上层用于切线路/提示 */
    var onError: ((what: Int, extra: Int) -> Unit)? = null

    /** 视频尺寸回调（宽/高），用于跟随视频方向切屏幕方向 */
    var onVideoSize: ((width: Int, height: Int) -> Unit)? = null

    init {
        aspectLayout = AspectRatioFrameLayout(context)
        surfaceView = SurfaceView(context)
        aspectLayout.addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        // 居中放置，letterbox 黑边上下/左右均分
        addView(
            aspectLayout,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        surfaceView.holder.addCallback(this)
    }

    // ================================================================ 对外

    /** 幂等：同一个地址且解码方式不变时不重复起播 */
    fun play(url: String, headers: Map<String, String> = emptyMap(), hwDecode: Boolean = true) {
        val cur = currentRequest
        if (cur != null && cur.url == url && cur.hwDecode == hwDecode) return
        val req = Request(url, headers, hwDecode)
        if (!surfaceReady) {
            pending = req
            return
        }
        start(req)
    }

    fun release() {
        pending = null
        currentRequest = null
        active = false
        loaded = false
        suppressEnd = 0
        if (!mpvReady) return
        mpvReady = false
        if (current === this) current = null
        try {
            MPVLib.removeObserver(observer)
        } catch (_: Throwable) {
        }
        try {
            MPVLib.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "mpv destroy 失败: ${e.message}")
        }
    }

    // ================================================================ mpv 生命周期

    /** 事件观察者. 用动态代理实现, 不绑定封装库的 EventObserver 方法列表 (各版本方法数不同). */
    private val observer: MPVLib.EventObserver by lazy {
        Proxy.newProxyInstance(
            MPVLib.EventObserver::class.java.classLoader,
            arrayOf(MPVLib.EventObserver::class.java),
        ) { proxy, method, args ->
            // Object 的 equals/hashCode/toString 也会走到这里 (removeObserver 用 equals), 必须正确返回.
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "MpvObserver"
                }
            }
            try {
                when (method.name) {
                    "eventProperty" -> {
                        val name = args?.getOrNull(0) as? String
                        val value = args?.getOrNull(1)
                        if (value is Long && (name == "dwidth" || name == "dheight")) {
                            main.post { onDisplaySize(name, value) }
                        }
                    }
                    "event" -> {
                        val id = args?.getOrNull(0) as? Int
                        if (id == MPV_EVENT_FILE_LOADED) main.post { loaded = true }
                        if (id == MPV_EVENT_END_FILE) main.post { onEndFile() }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "mpv 事件处理异常: ${e.message}")
            }
            null
        } as MPVLib.EventObserver
    }

    private fun ensureMpv() {
        if (mpvReady) return
        // 进程内只有一个 libmpv: 先销毁上一个持有者.
        current?.takeIf { it !== this }?.release()
        val ctx = context.applicationContext
        MPVLib.create(ctx)
        // ---- 输出 ----
        opt("vo", "gpu")
        opt("gpu-context", "android")
        opt("opengl-es", "yes")
        opt("gpu-shader-cache-dir", ctx.cacheDir.path)
        opt("icc-cache-dir", ctx.cacheDir.path)
        opt("hwdec", HW_DEC) // 每次起播按设置覆盖
        opt("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        opt("vd-lavc-fast", "yes")
        opt("vd-lavc-threads", "4")
        // ---- 音频 ----
        opt("ao", "audiotrack,opensles")
        // ---- 直播: 保持低延迟但不过度裁剪 ----
        // 探测不能太短: 音频参数需要解到几帧才能确定, 过短会按错误参数初始化音频输出 (杂音/变调).
        opt("demuxer-lavf-analyzeduration", "0.5")
        opt("demuxer-lavf-probesize", "131072")
        opt("cache", "yes")
        opt("cache-secs", "3")
        opt("demuxer-readahead-secs", "3")
        opt("cache-pause", "no")
        opt("network-timeout", "10")
        // 与原 ijk 版一致: 不校验证书 (ffmpeg 默认也不校验), 避免 Android 上缺 CA 包导致 https 直播打不开.
        opt("tls-verify", "no")
        // 其余
        opt("idle", "once")
        opt("keep-open", "no")
        opt("force-window", "no")
        MPVLib.init()
        MPVLib.observeProperty("dwidth", MPV_FORMAT_INT64)
        MPVLib.observeProperty("dheight", MPV_FORMAT_INT64)
        MPVLib.addObserver(observer)
        mpvReady = true
        current = this
        Log.i(TAG, "mpv 已初始化")
    }

    private fun opt(name: String, value: String) {
        try {
            val rc = MPVLib.setOptionString(name, value)
            if (rc < 0) Log.w(TAG, "mpv 选项设置失败: $name=$value rc=$rc")
        } catch (e: Throwable) {
            Log.w(TAG, "mpv 选项异常: $name=$value ${e.message}")
        }
    }

    private fun prop(name: String, value: String) {
        try {
            MPVLib.setPropertyString(name, value)
        } catch (e: Throwable) {
            Log.w(TAG, "mpv 属性异常: $name=$value ${e.message}")
        }
    }

    private fun cmd(vararg args: String) {
        try {
            MPVLib.command(arrayOf(*args))
        } catch (e: Throwable) {
            Log.w(TAG, "mpv 命令异常: ${args.joinToString(" ")} ${e.message}")
        }
    }

    // ================================================================ 起播

    private fun start(req: Request) {
        pending = null
        try {
            ensureMpv()
        } catch (e: Throwable) {
            Log.e(TAG, "mpv 初始化失败", e)
            main.post { onError?.invoke(-1000, -1) }
            return
        }
        currentRequest = req
        applyRequest(req)
        // 换源: 旧文件会产生一次 END_FILE (reason=stop), 要忽略, 否则被当成失败再切线路.
        if (active) suppressEnd++
        active = true
        loaded = false
        dw = 0
        dh = 0
        Log.i(TAG, "mpv loadfile: ${req.url}")
        cmd("loadfile", req.url, "replace")
    }

    /** 请求头 / UA / 代理 / 解码方式: 这些在打开文件时读取, 所以必须在 loadfile 之前设置. */
    private fun applyRequest(req: Request) {
        prop("hwdec", if (req.hwDecode) HW_DEC else "no")

        // User-Agent 用单独的选项设置, 不放进 http-header-fields (避免与默认 UA 并存, 虎牙 CDN 会 403)
        val ua = req.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
        prop("user-agent", ua ?: DEFAULT_UA)
        // 请求头列表: 先清空再逐条追加 (值里含逗号时一次性设置会被拆开).
        cmd("change-list", "http-header-fields", "clr", "")
        for ((k, v) in req.headers) {
            if (k.equals("user-agent", true)) continue
            cmd("change-list", "http-header-fields", "append", "$k: $v")
        }

        // native 直连不走系统代理; 设备配了 HTTP 代理时透传给 ffmpeg.
        val proxy = systemHttpProxyFor(req.url)
        if (proxy != null) Log.i(TAG, "mpv 使用系统代理: $proxy")
        prop("stream-lavf-o", if (proxy != null) "http_proxy=$proxy" else "")
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

    // ================================================================ 事件 (主线程)

    private fun onDisplaySize(name: String, value: Long) {
        if (name == "dwidth") dw = value else dh = value
        if (dw > 0 && dh > 0) {
            aspectLayout.videoAspectRatio = dw.toFloat() / dh.toFloat()
            aspectLayout.requestLayout()
            onVideoSize?.invoke(dw.toInt(), dh.toInt())
        }
    }

    private fun onEndFile() {
        if (suppressEnd > 0) {
            suppressEnd--
            return
        }
        if (!active) return
        active = false
        // 直播流正常情况下不会结束: 无论是打开失败还是中途断流, 都交给上层切线路/重载.
        val what = if (loaded) -1001 else -1000
        Log.e(TAG, "mpv END_FILE (loaded=$loaded) url=${currentRequest?.url}")
        onError?.invoke(what, 0)
    }

    // ================================================================ Surface

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        val req = pending
        if (req != null) {
            // 先挂 surface 再 loadfile
            try {
                ensureMpv()
                MPVLib.attachSurface(h.surface)
                opt("force-window", "yes")
            } catch (e: Throwable) {
                Log.e(TAG, "mpv attachSurface 失败", e)
            }
            start(req)
        } else if (mpvReady) {
            // 页面回前台等: 重新挂 surface, 恢复视频输出
            try {
                MPVLib.attachSurface(h.surface)
                opt("force-window", "yes")
                prop("vo", "gpu")
            } catch (e: Throwable) {
                Log.w(TAG, "mpv 重新挂载 surface 失败: ${e.message}")
            }
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (mpvReady) prop("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        if (!mpvReady) return
        try {
            prop("vo", "null")
            opt("force-window", "no")
            MPVLib.detachSurface()
        } catch (e: Throwable) {
            Log.w(TAG, "mpv 摘除 surface 失败: ${e.message}")
        }
    }
}

/**
 * Compose 封装：地址变化时对同一个 mpv 实例重新 loadfile (不重建 View).
 * 解码方式变化同样走 loadfile, 在下一次打开时生效.
 */
@Composable
fun MpvPlayer(
    url: String,
    headers: Map<String, String>,
    onError: ((what: Int, extra: Int) -> Unit)? = null,
    onVideoSize: ((width: Int, height: Int) -> Unit)? = null,
    hwDecode: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val viewRef = remember { mutableStateOf<MpvSurfaceView?>(null) }
    val errCallback = remember { mutableStateOf(onError) }
    errCallback.value = onError
    val sizeCallback = remember { mutableStateOf(onVideoSize) }
    sizeCallback.value = onVideoSize

    AndroidView(
        factory = { ctx ->
            MpvSurfaceView(ctx).also {
                viewRef.value = it
                it.onError = { w, e -> errCallback.value?.invoke(w, e) }
                it.onVideoSize = { w, h -> sizeCallback.value?.invoke(w, h) }
                it.play(url, headers, hwDecode)
            }
        },
        update = { it.play(url, headers, hwDecode) },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose { viewRef.value?.release() }
    }
}
