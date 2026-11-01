package com.silm.live.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 统一 HTTP 客户端封装 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    class HttpException(val code: Int, message: String) : Exception(message)

    private fun build(url: String, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            client.newCall(build(url, headers).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code}")
                resp.body?.string() ?: ""
            }
        }

    /** 带响应头的文本响应（用于取 Set-Cookie 等） */
    data class TextResponse(val body: String, val headers: okhttp3.Headers, val code: Int)

    suspend fun getTextWithHeaders(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): TextResponse = withContext(Dispatchers.IO) {
        client.newCall(build(url, headers).build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            TextResponse(body, resp.headers, resp.code)
        }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            client.newCall(build(url, headers).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code}")
                val text = resp.body?.string() ?: "{}"
                JSONObject(text)
            }
        }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            client.newCall(build(url, headers).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code}")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }

    /** HEAD 请求，返回响应头（用于取 set-cookie） */
    suspend fun head(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Headers =
        withContext(Dispatchers.IO) {
            client.newCall(build(url, headers).head().build()).execute().use { resp ->
                if (resp.code == 444) throw HttpException(444, "HTTP 444")
                resp.headers
            }
        }

    suspend fun postForm(
        url: String,
        form: String,
        headers: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = form.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        client.newCall(build(url, headers).post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = json.toRequestBody("application/json".toMediaType())
        client.newCall(build(url, headers).post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /** 跟随重定向，返回最终 URL */
    suspend fun resolveUrl(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(build(url, mapOf("user-agent" to PlatformIdentity.DOUYIN_UA)).build())
            .execute().use { it.request.url.toString() }
    }
}

fun md5Hex(input: String): String =
    java.security.MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** org.json 便捷取值 */
fun JSONObject.optLongOf(key: String, def: Long = 0): Long = optLong(key, def)
