package go.examate.hyp.music

import android.content.Context
import org.json.JSONObject

/**
 * 在线播放 URL 缓存(参照落雪 saveMusicUrl/getMusicUrl):
 *  - key = "platform_songmid_quality" → url + 时间戳
 *  - 缓存命中(未过期)直接播放,避免每次重复解析(音源脚本解析耗时/官方接口限频)
 *  - TTL 默认 12 小时(播放 URL 可能带时效,不宜过长)
 */
object MusicUrlCache {
    private const val PREFS = "music_url_cache"
    private const val KEY = "urls"
    private const val TTL_MS = 12L * 3600 * 1000

    @Volatile
    private var memCache: JSONObject? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadJson(context: Context): JSONObject {
        memCache?.let { return it }
        val obj = runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        memCache = obj
        return obj
    }

    /** 读取缓存 URL;未命中或过期返回 null */
    fun get(context: Context, platform: String, songmid: String, quality: String): String? {
        val obj = loadJson(context).optJSONObject(cacheKey(platform, songmid, quality)) ?: return null
        val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        val ts = obj.optLong("ts")
        if (ts > 0 && System.currentTimeMillis() - ts > TTL_MS) return null
        return url
    }

    /** 写入缓存(平台为空或 URL 为空时不写) */
    fun put(context: Context, platform: String, songmid: String, quality: String, url: String) {
        if (platform.isBlank() || songmid.isBlank() || url.isBlank()) return
        val json = loadJson(context)
        json.put(cacheKey(platform, songmid, quality), JSONObject().apply {
            put("url", url)
            put("ts", System.currentTimeMillis())
        })
        // 简单容量控制:超过 300 条时清理过期的
        var count = json.length()
        if (count > 300) {
            val now = System.currentTimeMillis()
            val keys = json.keys()
            val toRemove = mutableListOf<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                val entry = json.optJSONObject(k)
                if (entry == null || now - entry.optLong("ts") > TTL_MS) toRemove.add(k)
            }
            toRemove.forEach { json.remove(it) }
            count = json.length()
        }
        if (count <= 0) return
        prefs(context).edit().putString(KEY, json.toString()).apply()
        memCache = null
    }

    /** 移除单条(播放失败时调用,避免下次命中失效 URL) */
    fun invalidate(context: Context, platform: String, songmid: String, quality: String) {
        val json = loadJson(context)
        if (json.remove(cacheKey(platform, songmid, quality)) == null) return
        prefs(context).edit().putString(KEY, json.toString()).apply()
        memCache = null
    }

    /** 移除某歌曲全部音质缓存(播放失败时调用) */
    fun invalidateAll(context: Context, platform: String, songmid: String) {
        if (platform.isBlank() || songmid.isBlank()) return
        val json = loadJson(context)
        val keys = json.keys()
        val toRemove = mutableListOf<String>()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("${platform}_${songmid}_")) toRemove.add(k)
        }
        if (toRemove.isEmpty()) return
        toRemove.forEach { json.remove(it) }
        prefs(context).edit().putString(KEY, json.toString()).apply()
        memCache = null
    }

    /** 清空全部 URL 缓存(设置页"缓存管理"用) */
    fun clearAll(context: Context) {
        prefs(context).edit().remove(KEY).apply()
        memCache = null
    }

    private fun cacheKey(platform: String, songmid: String, quality: String) =
        "${platform}_${songmid}_$quality"
}
