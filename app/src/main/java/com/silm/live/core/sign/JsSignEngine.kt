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

    private const val TAG = "silm"

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
                // 包一层 try/catch：JS 异常透出到日志（此前异常被吞、结果恒为 "null"）。
                // 用户 JS 作为表达式被 return，兼容纯表达式与 IIFE 两种形态
                val wrapped = "(function(){try{return JSON.stringify({ok:1,r:String((function(){return (" +
                    js + ")})())})}catch(e){return JSON.stringify({ok:0,err:String(e&&e.message||e)})}})()"
                wv.evaluateJavascript(wrapped) { result ->
                    cont.resume(parseEvalResult(result))
                }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * 解析 evaluateJavascript 的回调值。
     *
     * 包装脚本返回 JSON.stringify(...) 的字符串，evaluateJavascript 会把字符串结果再 JSON 编码一次，
     * 所以第一次 nextValue() 只能还原出内层 JSON 文本，必须再解析一次才能取到 r。
     * 此前直接把内层 JSON 文本当结果返回，导致斗鱼签名被判无效（"播放签名刷新失败"），
     * 抖音 MSSDK 签名 / a_bogus 也被污染（弹幕连接失败）。
     */
    private fun parseEvalResult(raw: String?): String {
        val value = raw ?: return ""
        return try {
            var v: Any? = JSONTokener(value).nextValue()
            // 第一层：还原成内层 JSON 文本，再尝试解析为对象
            if (v is String) {
                val inner = v.trim()
                if (inner.startsWith("{") && inner.endsWith("}")) {
                    v = try { org.json.JSONObject(inner) } catch (_: Exception) { v }
                }
            }
            when {
                v == null || v == org.json.JSONObject.NULL -> ""
                v is org.json.JSONObject -> {
                    if (v.optInt("ok") == 1) v.optString("r")
                    else {
                        android.util.Log.e(TAG, "JS 执行异常: ${v.optString("err")}")
                        ""
                    }
                }
                v is String -> v
                else -> value
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "JS 结果解析失败: $value")
            ""
        }
    }

    /**
     * 求值一段完整的 JS **脚本**（可含多条语句/函数声明），不包裹表达式。
     * 用于加载资产脚本：此前把函数声明脚本包进 `return (...)` 表达式，
     * 函数声明被括号转为函数表达式，永远不会注册到全局作用域，
     * 导致后续调用报 "xxx is not defined"（抖音/斗鱼签名全灭的根因）。
     */
    private suspend fun evalJsScript(js: String): String = suspendCancellableCoroutine { cont ->
        val wv = webView
        if (wv == null) {
            cont.resumeWithException(IllegalStateException("WebView not initialized"))
            return@suspendCancellableCoroutine
        }
        mainHandler.post {
            try {
                wv.evaluateJavascript(js) { result ->
                    val value = result ?: "null"
                    if (value.length > 120) {
                        android.util.Log.d(TAG, "脚本加载返回(截断): ${value.take(120)}")
                    }
                    cont.resume(value)
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
                evalJsScript(readAsset(context, "douyu_crypto.js"))
                cryptoLoaded = true
            }
            val ts = System.currentTimeMillis() / 1000
            val js = "(function(){\n${script}\nreturn ub98484234(${jsStr(roomId)},${jsStr(did)},${jsStr(ts.toString())});\n})()"
            val r = evalJs(js)
            // 有效性：签名参数是 form 串（v=..&did=..&tt=..&sign=..），函数源码/JSON 等垃圾内容一律拒绝
            val valid = r.contains("=") && !r.contains("function") && !r.contains("{")
            if (!valid) {
                android.util.Log.e(TAG, "斗鱼签名结果异常: ${r.take(120)}")
                return@withContext ""
            }
            r
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
            evalJsScript(readAsset(context, "douyin_mssdk.js"))
            msSdkLoaded = true
        }
        repeat(5) {
            val sig = evalJs("getMSSDKSignature(${jsStr(msStub)},${jsStr(userAgent)})")
            if (sig.isValidDouyinSig()) return@withContext sig
        }
        // 一次失败后可能脚本未正确装载：重载再试一轮
        evalJsScript(readAsset(context, "douyin_mssdk.js"))
        repeat(5) {
            val sig = evalJs("getMSSDKSignature(${jsStr(msStub)},${jsStr(userAgent)})")
            if (sig.isValidDouyinSig()) return@withContext sig
        }
        android.util.Log.e(TAG, "抖音签名生成失败（WebView），降级返回 stub")
        msStub
        }

    /** 与 Flutter 版一致：签名不得含 "-" 或 "="（含则重新生成），且必须是纯 base64 变体字符 */
    private fun String.isValidDouyinSig(): Boolean =
        isNotEmpty() && length <= 512 && !contains('-') && !contains('=') &&
            matches(Regex("[A-Za-z0-9+/_]+"))

    /** 抖音：a_bogus 签名，返回最终带签名的 URL */
    suspend fun douyinABogusUrl(
        context: Context,
        url: String,
        userAgent: String,
        msToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        ensureWebView(context)
        if (!abogusLoaded) {
            evalJsScript(readAsset(context, "douyin_abogus.js"))
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
        if (aBogus.isEmpty()) android.util.Log.e(TAG, "抖音 a_bogus 生成失败（空结果）")
        val sep = if (query.isEmpty()) "?" else "&"
        unsignedUrl + sep + "a_bogus=" +
            java.net.URLEncoder.encode(aBogus, "UTF-8").replace("+", "%20")
    }

    fun generateMsToken(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars[kotlin.random.Random.nextInt(chars.length)] }.joinToString("")
    }
}
