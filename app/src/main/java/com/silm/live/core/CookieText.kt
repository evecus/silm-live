package com.silm.live.core

import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cookie 粘贴输入的清洗/解析工具。
 *
 * 机制对齐 Flutter 版账号管理（DouyinCookieHelper + account_controller）：
 *  - 用户可能粘贴的是整段 Request Headers，需要先提取 cookie 行；
 *  - 可能只复制了 ttwid 的值（无 `=`），自动补成 `ttwid=...`；
 *  - 抖音可从 `sid_guard` 解析出登录时间 + 有效期，估算剩余时长。
 */
object CookieText {

    /** 从粘贴的多行 header 文本中提取 cookie 行；不是 header 文本返回 null */
    fun extractFromHeaderText(input: String): String? {
        val lines = input.split(Regex("\r?\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        lines.forEachIndexed { i, line ->
            val lower = line.lowercase()
            if (lower.startsWith("cookie:")) {
                return line.substring(line.indexOf(':') + 1).trim()
            }
            if (lower == "cookie" && i + 1 < lines.size) {
                return lines[i + 1].trim()
            }
        }
        return null
    }

    /** 清洗粘贴输入：提取 cookie 行、去 cookie: 前缀、裸值自动补 ttwid= */
    fun normalize(input: String): String {
        var cookie = extractFromHeaderText(input) ?: input.trim()
        if (cookie.lowercase().startsWith("cookie:")) {
            cookie = cookie.substring(cookie.indexOf(':') + 1).trim()
        }
        if (cookie.isNotEmpty() && !cookie.contains("=")) {
            cookie = "ttwid=$cookie"
        }
        return cookie
    }

    /** 是否仅为 ttwid（无登录态，播放可用但搜索受限） */
    fun isOnlyTtwid(cookie: String): Boolean {
        val n = cookie.trim().lowercase()
        return n.startsWith("ttwid=") && !n.contains(";")
    }

    /** k=v; k2=v2 解析 */
    fun parseMap(cookie: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in cookie.split(";")) {
            val item = part.trim()
            val idx = item.indexOf('=')
            if (idx <= 0) continue
            val key = item.substring(0, idx).trim()
            val value = item.substring(idx + 1).trim()
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    /**
     * 从抖音 Cookie 的 sid_guard 解析到期时间（epoch ms）。
     * sid_guard 值格式：`{sessionId}|{登录时间戳}|{有效期秒数}|{过期日期文本}|...`（URL 编码）。
     * 解析不到返回 null。
     */
    fun douyinExpiresAt(cookie: String): Long? {
        val sidGuard = parseMap(cookie)["sid_guard"] ?: return null
        if (sidGuard.isEmpty()) return null
        val decoded = decodeComponent(decodeComponent(sidGuard))
        val parts = decoded.split("|")
        if (parts.size >= 3) {
            val loginTime = parts[1].trim().toLongOrNull()
            val maxAgeSeconds = parts[2].trim().toLongOrNull()
            if (loginTime != null && maxAgeSeconds != null) {
                val loginAtMs =
                    if (loginTime > 1_000_000_000_000L) loginTime else loginTime * 1000L
                return loginAtMs + maxAgeSeconds * 1000L
            }
        }
        if (parts.size >= 4) {
            return parseHttpDate(parts[3])
        }
        return null
    }

    /** 快手粘贴的 Cookie 无标准过期字段（与 Flutter 一致：无法判断），保留接口便于扩展 */
    fun kuaishouHasLoginFields(cookie: String): Boolean {
        val lower = cookie.lowercase()
        return lower.contains("kuaishou.server.web_st") ||
            lower.contains("kuaishou.live.web_st") ||
            lower.contains("passtoken") ||
            lower.contains("userid=")
    }

    /** 剩余时长的短文案：X 天 X 小时 / X 小时 X 分钟 / X 分钟 */
    fun formatRemaining(remainMs: Long): String {
        val totalMinutes = remainMs / 60000
        val days = totalMinutes / 1440
        val hours = (totalMinutes % 1440) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "$days 天 $hours 小时"
            hours > 0 -> "$hours 小时 $minutes 分钟"
            else -> "$minutes 分钟"
        }
    }

    /** 到期时间的展示文案：yyyy-MM-dd HH:mm */
    fun formatDateTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    private fun decodeComponent(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun parseHttpDate(value: String): Long? {
        val normalized = value.replace("+", " ").replace("-", " ")
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            "yyyy-MM-dd HH:mm:ss",
        )
        for (pattern in patterns) {
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.isLenient = false
                return fmt.parse(normalized)?.time
            }
        }
        return null
    }
}
