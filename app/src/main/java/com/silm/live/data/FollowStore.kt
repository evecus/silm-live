package com.silm.live.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.silm.live.core.AccountCookies
import com.silm.live.core.LiveSite
import com.silm.live.core.SiteManager
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "silm_live")

/** 关注的直播间 */
data class FollowItem(
    val platform: String,
    val roomId: String,
    val userName: String,
    val avatar: String,
    val title: String,
    val top: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("platform", platform)
        .put("roomId", roomId)
        .put("userName", userName)
        .put("avatar", avatar)
        .put("title", title)
        .put("top", top)
        .put("addedAt", addedAt)

    companion object {
        /**
         * 兼容两种格式：
         *  - 本应用：platform/roomId/userName/avatar/title
         *  - Simple Live：siteId/roomId/userName/face/roomTitle
         */
        fun fromJson(o: JSONObject): FollowItem? {
            val platform = (o.optString("platform", "").ifEmpty { o.optString("siteId", "") })
                .trim().lowercase()
            val roomId = o.optString("roomId", "").trim()
            if (platform.isEmpty() || roomId.isEmpty()) return null
            return FollowItem(
                platform = platform,
                roomId = roomId,
                userName = o.optString("userName", ""),
                avatar = o.optString("avatar", "").ifEmpty { o.optString("face", "") },
                title = o.optString("title", "").ifEmpty { o.optString("roomTitle", "") },
                top = o.optBoolean("top", false) || o.optBoolean("isSpecialFollow", false),
                addedAt = o.optLong("addedAt", 0).takeIf { it > 0 }
                    ?: (o.optString("addTime", "").toLongOrNull() ?: 0L),
            )
        }

        fun parseArray(json: String): List<FollowItem> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** 关注列表存储 */
object FollowStore {

    private val KEY_FOLLOWS = stringPreferencesKey("follows")

    fun follows(context: Context): Flow<List<FollowItem>> =
        context.dataStore.data.map { prefs ->
            parseFollows(prefs[KEY_FOLLOWS] ?: "[]")
        }

    private fun parseFollows(json: String): List<FollowItem> {
        return FollowItem.parseArray(json)
            .sortedWith(compareByDescending<FollowItem> { it.top }.thenBy { it.addedAt })
    }

    suspend fun add(context: Context, item: FollowItem) {
        context.dataStore.edit { prefs ->
            val list = parseFollows(prefs[KEY_FOLLOWS] ?: "[]").toMutableList()
            list.removeAll { it.platform == item.platform && it.roomId == item.roomId }
            list.add(0, item.copy(addedAt = System.currentTimeMillis()))
            prefs[KEY_FOLLOWS] = JSONArray(list.map { it.toJson() }).toString()
        }
    }

    suspend fun remove(context: Context, item: FollowItem) {
        context.dataStore.edit { prefs ->
            val list = parseFollows(prefs[KEY_FOLLOWS] ?: "[]").toMutableList()
            list.removeAll { it.platform == item.platform && it.roomId == item.roomId }
            prefs[KEY_FOLLOWS] = JSONArray(list.map { it.toJson() }).toString()
        }
    }

    suspend fun toggleTop(context: Context, item: FollowItem) {
        context.dataStore.edit { prefs ->
            val list = parseFollows(prefs[KEY_FOLLOWS] ?: "[]").toMutableList()
            val idx = list.indexOfFirst { it.platform == item.platform && it.roomId == item.roomId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(top = !list[idx].top)
            }
            val sorted = list.sortedWith(compareByDescending<FollowItem> { it.top }.thenBy { it.addedAt })
            prefs[KEY_FOLLOWS] = JSONArray(sorted.map { it.toJson() }).toString()
        }
    }

    suspend fun current(context: Context): List<FollowItem> =
        parseFollows(context.dataStore.data.first()[KEY_FOLLOWS] ?: "[]")

    suspend fun importJson(context: Context, json: String, replace: Boolean) {
        context.dataStore.edit { prefs ->
            val imported = FollowItem.parseArray(json)
            val list = if (replace) mutableListOf() else
                parseFollows(prefs[KEY_FOLLOWS] ?: "[]").toMutableList()
            imported.forEach { item ->
                list.removeAll { it.platform == item.platform && it.roomId == item.roomId }
                list.add(item)
            }
            val sorted = list.sortedWith(compareByDescending<FollowItem> { it.top }.thenBy { it.addedAt })
            prefs[KEY_FOLLOWS] = JSONArray(sorted.map { it.toJson() }).toString()
        }
    }

    suspend fun exportJson(context: Context): String {
        val list = current(context)
        return JSONArray(list.map { it.toJson() }).toString(2)
    }

    /**
     * 局域网同步接收：replace=true 直接覆盖本机关注列表（无需用户确认）；
     * replace=false 时合并去重。
     */
    suspend fun replaceAll(context: Context, items: List<FollowItem>, replace: Boolean) {
        context.dataStore.edit { prefs ->
            val list = if (replace) mutableListOf() else
                parseFollows(prefs[KEY_FOLLOWS] ?: "[]").toMutableList()
            items.forEach { item ->
                list.removeAll { it.platform == item.platform && it.roomId == item.roomId }
                list.add(item)
            }
            val sorted = list.sortedWith(compareByDescending<FollowItem> { it.top }.thenBy { it.addedAt })
            prefs[KEY_FOLLOWS] = JSONArray(sorted.map { it.toJson() }).toString()
        }
    }
}

/** 通用配置存储 */
object SettingsStore {
    private val KEY_DANMAKU = stringPreferencesKey("danmaku_enabled")
    private val KEY_DOUYU_DID = stringPreferencesKey("douyu_did")
    private val KEY_DISPLAY_MODE = stringPreferencesKey("display_mode")
    private val KEY_PHONE_ORIENTATION = stringPreferencesKey("phone_play_orientation")
    private val KEY_HW_DECODE = stringPreferencesKey("hw_decode")
    private val KEY_DEFAULT_QUALITY = stringPreferencesKey("default_quality")
    private val KEY_DANMAKU_TEXT_SP = stringPreferencesKey("danmaku_text_sp")
    private val KEY_DANMAKU_AREA = stringPreferencesKey("danmaku_area_percent")
    private val KEY_PLATFORM_ORDER = stringPreferencesKey("platform_order")
    private val KEY_COOKIE_DOUYIN = stringPreferencesKey("cookie_douyin")
    private val KEY_COOKIE_KUAISHOU = stringPreferencesKey("cookie_kuaishou")

    /** 默认平台顺序（SiteManager 注册顺序） */
    fun defaultPlatformOrder(): List<String> = SiteManager.sites.map { it.id }

    /**
     * 规整平台顺序：丢弃已不存在的平台与重复项，新增（未保存过）的平台按默认顺序补到末尾。
     */
    fun normalizePlatformOrder(saved: List<String>): List<String> {
        val all = defaultPlatformOrder()
        val kept = saved.filter { it in all }.distinct()
        return kept + all.filter { it !in kept }
    }

    /**
     * 直播平台顺序（平台 id 列表）：设置页配置，搜索页的平台 Tab 与关注页的平台分类共用同一次序。
     */
    fun platformOrder(context: Context): Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            normalizePlatformOrder(
                (prefs[KEY_PLATFORM_ORDER] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            )
        }

    suspend fun setPlatformOrder(context: Context, order: List<String>) {
        context.dataStore.edit {
            it[KEY_PLATFORM_ORDER] = normalizePlatformOrder(order).joinToString(",")
        }
    }

    /** 恢复默认平台顺序 */
    suspend fun resetPlatformOrder(context: Context) {
        context.dataStore.edit { it.remove(KEY_PLATFORM_ORDER) }
    }

    /** 按给定顺序排列的平台列表 */
    fun sitesInOrder(order: List<String>): List<LiveSite> =
        normalizePlatformOrder(order).mapNotNull { SiteManager.byId(it) }

    // ---------------- 账号登录 Cookie（抖音/快手搜索需要登录态） ----------------

    /** 应用启动时把持久化的 Cookie 灌入内存缓存，供 core 层站点同步读取 */
    suspend fun primeAccountCookies(context: Context) {
        val prefs = context.dataStore.data.first()
        AccountCookies.douyin = prefs[KEY_COOKIE_DOUYIN] ?: ""
        AccountCookies.kuaishou = prefs[KEY_COOKIE_KUAISHOU] ?: ""
    }

    fun douyinCookie(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_COOKIE_DOUYIN] ?: "" }

    fun kuaishouCookie(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_COOKIE_KUAISHOU] ?: "" }

    suspend fun setDouyinCookie(context: Context, cookie: String) {
        AccountCookies.douyin = cookie
        context.dataStore.edit {
            if (cookie.isBlank()) it.remove(KEY_COOKIE_DOUYIN) else it[KEY_COOKIE_DOUYIN] = cookie
        }
    }

    suspend fun setKuaishouCookie(context: Context, cookie: String) {
        AccountCookies.kuaishou = cookie
        context.dataStore.edit {
            if (cookie.isBlank()) it.remove(KEY_COOKIE_KUAISHOU) else it[KEY_COOKIE_KUAISHOU] = cookie
        }
    }

    fun danmakuEnabled(context: Context): Flow<Boolean> =
        context.dataStore.data.map { (it[KEY_DANMAKU] ?: "true") == "true" }

    suspend fun setDanmakuEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_DANMAKU] = enabled.toString() }
    }

    /** 显示模式：AUTO / PHONE / TABLET / TV */
    fun displayMode(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_DISPLAY_MODE] ?: "AUTO" }

    suspend fun setDisplayMode(context: Context, mode: String) {
        context.dataStore.edit { it[KEY_DISPLAY_MODE] = mode }
    }

    /** 手机播放比例：LANDSCAPE（横屏）/ PORTRAIT（竖屏）/ AUTO（按视频方向） */
    fun phoneOrientation(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_PHONE_ORIENTATION] ?: "AUTO" }

    suspend fun setPhoneOrientation(context: Context, mode: String) {
        context.dataStore.edit { it[KEY_PHONE_ORIENTATION] = mode }
    }

    /** 播放器解码方式：true=硬解(MediaCodec)，false=软解(FFmpeg)，默认硬解 */
    fun hwDecode(context: Context): Flow<Boolean> =
        context.dataStore.data.map { (it[KEY_HW_DECODE] ?: "true") == "true" }

    suspend fun setHwDecode(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_HW_DECODE] = enabled.toString() }
    }

    /** 默认清晰度档位：0=最高 1=较高 2=中等 3=较低 4=最低 */
    fun defaultQuality(context: Context): Flow<Int> =
        context.dataStore.data.map { it[KEY_DEFAULT_QUALITY]?.toIntOrNull() ?: 0 }

    suspend fun setDefaultQuality(context: Context, value: Int) {
        context.dataStore.edit { it[KEY_DEFAULT_QUALITY] = value.toString() }
    }

    /** 弹幕字号（sp），默认 16 */
    fun danmakuTextSp(context: Context): Flow<Int> =
        context.dataStore.data.map { it[KEY_DANMAKU_TEXT_SP]?.toIntOrNull() ?: 16 }

    suspend fun setDanmakuTextSp(context: Context, sp: Int) {
        context.dataStore.edit { it[KEY_DANMAKU_TEXT_SP] = sp.toString() }
    }

    /** 弹幕屏占比（10/25/50/75/100），表示弹幕从屏幕顶部开始占屏幕高度的百分比，默认 25 */
    fun danmakuArea(context: Context): Flow<Int> =
        context.dataStore.data.map { it[KEY_DANMAKU_AREA]?.toIntOrNull() ?: 25 }

    suspend fun setDanmakuArea(context: Context, percent: Int) {
        context.dataStore.edit { it[KEY_DANMAKU_AREA] = percent.toString() }
    }

    suspend fun getDouyuDid(context: Context): String {
        val existing = context.dataStore.data.first()[KEY_DOUYU_DID]
        if (!existing.isNullOrEmpty()) return existing
        val did = (1..32).map { "0123456789abcdef"[kotlin.random.Random.nextInt(16)] }.joinToString("")
        context.dataStore.edit { it[KEY_DOUYU_DID] = did }
        return did
    }
}
