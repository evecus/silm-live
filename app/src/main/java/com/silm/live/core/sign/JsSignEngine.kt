package com.silm.live.core.sign

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.silm.live.core.md5Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 基于 WebView 的 JS 签名执行引擎。
 * 用于执行斗鱼 homeH5Enc 签名脚本与抖音 a_bogus / MSSDK 签名脚本（assets 内置）。
 */
object JsSignEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var cryptoLoaded = false
    @Volatile
    private var msSdkLoaded = false
    @Volatile
    private var abogusLoaded = false

    /**
     * 创建 WebView（必须在主线程），以 suspendCoroutine 等待完成，
     * **不使用任何阻塞等待**：避免在主线程调用时 Looper 被阻塞而死锁（曾导致 ANR）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createWebViewOnMain(context: Context): WebView =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                if (!cont.isActive) return@post
                try {
                    val wv = WebView(context.applicationContext)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = false
                    wv.settings.allowFileAccess = false
                    wv.settings.blockNetworkImage = true
                    wv.settings.loadsImagesAutomatically = false
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (cont.isActive) cont.resume(wv)
                        }
                    }
                    wv.loadData("<html><body></body></html>", "text/html", "utf-8")
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("WebView 初始化失败：${e.message}")
                    )
                }
            }
        }

    /** 始终在 IO 线程获取 WebView，杜绝主线程等待 */
    private suspend fun ensureWebView(context: Context): WebView {
        webView?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(this@JsSignEngine) {
                webView?.let { return@withContext it }
                val wv = runBlocking { createWebViewOnMain(context) }
                webView = wv
                wv
            }
        }
    }

    private suspend fun evalJs(js: String): String = suspendCancellableCoroutine { cont ->
        val wv = webView
        if (wv == null) {
            cont.resumeWithException(IllegalStateException("WebView not initialized"))
            return@suspendCancellableCoroutine
        }
        mainHandler.post {
            try {
                wv.evaluateJavascript(js) { result ->
                    val value = result ?: "null"
                    val unescaped = try {
                        val v = JSONTokener(value).nextValue()
                        if (v is String) v else if (v == null) "" else value
                    } catch (e: Exception) {
                        value
                    }
                    cont.resume(unescaped)
                }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun jsStr(s: String): String = org.json.JSONObject.quote(s)

    /** 斗鱼：执行房间加密脚本生成播放签名参数 */
    suspend fun douyuSign(context: Context, script: String, roomId: String, did: String): String =
        withContext(Dispatchers.IO) {
            ensureWebView(context)
            if (!cryptoLoaded) {
                evalJs(readAsset(context, "douyu_crypto.js"))
                cryptoLoaded = true
            }
            val ts = System.currentTimeMillis() / 1000
            val js = "(function(){\n${script}\nreturn ub98484234(${jsStr(roomId)},${jsStr(did)},${jsStr(ts.toString())});\n})()"
            val r = evalJs(js)
            if (r == "null" || r.isEmpty()) "" else r
        }

    private fun defaultDouyinSignParams(roomId: String, uniqueId: String): String {
        val params = linkedMapOf(
            "live_id" to "1",
            "aid" to "6383",
            "version_code" to "180800",
            "webcast_sdk_version" to "1.3.0",
            "room_id" to roomId,
            "sub_room_id" to "",
            "sub_channel_id" to "",
            "did_rule" to "3",
            "user_unique_id" to uniqueId,
            "device_platform" to "web",
            "device_type" to "",
            "ac" to "",
            "identity" to "audience",
        )
        return params.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    fun douyinMsStub(roomId: String, uniqueId: String): String =
        md5Hex(defaultDouyinSignParams(roomId, uniqueId))

    /** 抖音：webcast 弹幕 signature */
    suspend fun douyinSignature(context: Context, msStub: String, userAgent: String): String =
        withContext(Dispatchers.IO) {
        ensureWebView(context)
        if (!msSdkLoaded) {
            evalJs(readAsset(context, "douyin_mssdk.js"))
            msSdkLoaded = true
        }
        repeat(5) {
            val sig = evalJs("getMSSDKSignature(${jsStr(msStub)},${jsStr(userAgent)})")
            if (sig.isNotEmpty() && !sig.contains("-") && !sig.contains("=")) return@withContext sig
        }
        msStub
        }

    /** 抖音：a_bogus 签名，返回最终带签名的 URL */
    suspend fun douyinABogusUrl(
        context: Context,
        url: String,
        userAgent: String,
        msToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        ensureWebView(context)
        if (!abogusLoaded) {
            evalJs(readAsset(context, "douyin_abogus.js"))
            abogusLoaded = true
        }
        val uri = android.net.Uri.parse(url)
        val params = mutableMapOf<String, String>()
        uri.queryParameterNames.forEach { params[it] = uri.getQueryParameter(it) ?: "" }
        val effectiveMsToken = msToken?.trim().takeUnless { it.isNullOrEmpty() }
            ?: generateMsToken(107)
        params["msToken"] = effectiveMsToken
        params.remove("a_bogus")
        val qb = StringBuilder()
        params.entries.forEachIndexed { i, e ->
            if (i > 0) qb.append("&")
            qb.append(java.net.URLEncoder.encode(e.key, "UTF-8")).append("=")
                .append(java.net.URLEncoder.encode(e.value, "UTF-8").replace("+", "%20"))
        }
        val unsignedUrl = buildString {
            append("https://").append(uri.host ?: "live.douyin.com")
            if (uri.port != -1) append(":").append(uri.port)
            append(uri.path ?: "")
            if (qb.isNotEmpty()) append("?").append(qb)
        }
        val query = unsignedUrl.substringAfter("?", "")
        val aBogus = evalJs("getABogus(${jsStr(query)},${jsStr(userAgent)})")
        val sep = if (query.isEmpty()) "?" else "&"
        unsignedUrl + sep + "a_bogus=" +
            java.net.URLEncoder.encode(aBogus, "UTF-8").replace("+", "%20")
    }

    fun generateMsToken(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars[kotlin.random.Random.nextInt(chars.length)] }.joinToString("")
    }
}
