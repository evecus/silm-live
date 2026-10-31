package com.silm.live.data

import android.content.Context
import androidx.datastore.preferences.core.edit
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
}

/** 通用配置存储 */
object SettingsStore {
    private val KEY_DANMAKU = stringPreferencesKey("danmaku_enabled")
    private val KEY_DOUYU_DID = stringPreferencesKey("douyu_did")

    fun danmakuEnabled(context: Context): Flow<Boolean> =
        context.dataStore.data.map { (it[KEY_DANMAKU] ?: "true") == "true" }

    suspend fun setDanmakuEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_DANMAKU] = enabled.toString() }
    }

    suspend fun getDouyuDid(context: Context): String {
        val existing = context.dataStore.data.first()[KEY_DOUYU_DID]
        if (!existing.isNullOrEmpty()) return existing
        val did = (1..32).map { "0123456789abcdef"[kotlin.random.Random.nextInt(16)] }.joinToString("")
        context.dataStore.edit { it[KEY_DOUYU_DID] = did }
        return did
    }
}
