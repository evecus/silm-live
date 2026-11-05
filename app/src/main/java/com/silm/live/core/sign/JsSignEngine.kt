package com.silm.live.core.sign

import com.dokar.quickjs.QuickJs
import com.silm.live.core.md5Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 基于 QuickJS 的 JS 签名执行引擎（对齐 Flutter dart_quickjs 机制）。
 *
 * 用于执行斗鱼 homeH5Enc 签名脚本与抖音 a_bogus / MSSDK 签名脚本（assets 内置）。
 * 相比此前的 WebView 实现：
 *  - 无浏览器环境依赖（abogus 为纯算法脚本、mssdk 自带 document/window 垫片），
 *    不再有 data: URL / cookie 禁用导致的初始化失败；
 *  - 实例常驻，脚本装载一次后重复调用，失败自动销毁重建。
 */
object JsSignEngine {

    private const val TAG = "silm"

    private val mutex = Mutex()

    @Volatile
    private var quickJs: QuickJs? = null

    /** 串行化访问引擎；JS 异常后销毁重建实例，避免坏状态驻留 */
    private suspend fun <T> withEngine(block: suspend (QuickJs) -> T): T =
        mutex.withLock {
            val qjs = quickJs ?: QuickJs.create(Dispatchers.Default).also { quickJs = it }
            try {
                block(qjs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { qjs.close() }
                quickJs = null
                android.util.Log.e(TAG, "QuickJS 执行失败（实例已重建）：${e.message}")
                throw IllegalStateException("QuickJS 执行失败：${e.message}", e)
            }
        }

    /** 求值表达式并转为字符串（undefined/null → 空串），JS 异常透出为空结果 + 日志 */
    private suspend fun evalExpr(qjs: QuickJs, code: String): String =
        try {
            qjs.evaluate<Any?>(code)?.toString() ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "JS 执行异常: ${e.message}")
            ""
        }

    /** 加载完整脚本（可含多条语句/函数声明） */
    private suspend fun evalScript(qjs: QuickJs, js: String) {
        qjs.evaluate<Any?>(js)
    }

    private fun readAsset(context: android.content.Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    /**
     * 加载签名资产脚本并校验目标全局函数已注册；未注册则重载一次。
     */
    private suspend fun ensureScriptFunction(qjs: QuickJs, context: android.content.Context, asset: String, fnName: String) {
        if (evalExpr(qjs, "typeof $fnName") == "function") return
        evalScript(qjs, readAsset(context, asset))
        if (evalExpr(qjs, "typeof $fnName") != "function") {
            android.util.Log.e(TAG, "$asset 首次装载后 $fnName 未注册，重载重试")
            evalScript(qjs, readAsset(context, asset))
            if (evalExpr(qjs, "typeof $fnName") != "function") {
                android.util.Log.e(TAG, "$asset 装载失败：$fnName 不可用")
            }
        }
    }

    private fun jsStr(s: String): String = org.json.JSONObject.quote(s)

    /** 斗鱼：执行房间加密脚本生成播放签名参数 */
    suspend fun douyuSign(context: android.content.Context, script: String, roomId: String, did: String): String =
        withContext(Dispatchers.IO) {
            withEngine { qjs ->
                ensureScriptFunction(qjs, context, "douyu_crypto.js", "ub98484234")
                val ts = System.currentTimeMillis() / 1000
                val js = "(function(){\n${script}\nreturn ub98484234(${jsStr(roomId)},${jsStr(did)},${jsStr(ts.toString())});\n})()"
                val r = evalExpr(qjs, js)
                // 有效性：签名参数是 form 串（v=..&did=..&tt=..&sign=..），函数源码/JSON 等垃圾内容一律拒绝
                val valid = r.contains("=") && !r.contains("function") && !r.contains("{")
                if (!valid) {
                    android.util.Log.e(TAG, "斗鱼签名结果异常: ${r.take(120)}")
                    return@withEngine ""
                }
                r
            }
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

    /** 抖音：webcast 弹幕 signature（与 Flutter getSignature 一致） */
    suspend fun douyinSignature(context: android.content.Context, msStub: String, userAgent: String): String =
        withContext(Dispatchers.IO) {
            withEngine { qjs ->
                ensureScriptFunction(qjs, context, "douyin_mssdk.js", "getMSSDKSignature")
                repeat(5) {
                    val sig = evalExpr(qjs, "getMSSDKSignature(${jsStr(msStub)},${jsStr(userAgent)})")
                    if (sig.isValidDouyinSig()) return@withEngine sig
                }
                android.util.Log.e(TAG, "抖音签名生成失败（QuickJS），降级返回 stub")
                msStub
            }
        }

    /** 与 Flutter 版一致：签名不得含 "-" 或 "="（含则重新生成），且必须是纯 base64 变体字符 */
    private fun String.isValidDouyinSig(): Boolean =
        isNotEmpty() && length <= 512 && !contains('-') && !contains('=') &&
            matches(Regex("[A-Za-z0-9+/_]+"))

    /** 抖音：a_bogus 签名，返回最终带签名的 URL */
    suspend fun douyinABogusUrl(
        context: android.content.Context,
        url: String,
        userAgent: String,
        msToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        withEngine { qjs ->
            ensureScriptFunction(qjs, context, "douyin_abogus.js", "getABogus")
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
            val aBogus = evalExpr(qjs, "getABogus(${jsStr(query)},${jsStr(userAgent)})")
            if (aBogus.isEmpty()) android.util.Log.e(TAG, "抖音 a_bogus 生成失败（空结果）")
            val sep = if (query.isEmpty()) "?" else "&"
            unsignedUrl + sep + "a_bogus=" +
                java.net.URLEncoder.encode(aBogus, "UTF-8").replace("+", "%20")
        }
    }

    fun generateMsToken(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars[kotlin.random.Random.nextInt(chars.length)] }.joinToString("")
    }
}
