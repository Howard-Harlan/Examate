package go.examate.hyp.music

import android.content.Context

/**
 * 收藏音乐存储 — 存 QueueItem 元信息(SharedPreferences + JSON),读取时重建可播放项(含 resolver)。
 * 播放链路与搜索一致:统一走 PlayResolver(官方账号播放 / 音源解析脚本,按设置路线);bili 走官方 DASH。
 */
object FavoritesStore {
    private const val PREFS = "music_favorites"
    private const val KEY = "list"

    @Volatile
    private var idCache: Set<String>? = null

    fun load(context: Context): List<QueueItem> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                val platform = o.optString("platform")
                val songmid = o.optString("songmid")
                val base = QueueItem(
                    id = id,
                    title = o.optString("title", "未知"),
                    artist = o.optString("artist"),
                    durationMs = o.optLong("duration"),
                    coverUrl = o.optString("cover"),
                    coverRef = o.optString("coverRef"),
                    albumId = o.optLong("albumId", -1L)
                )
                if (platform.isBlank() || songmid.isBlank()) base
                else base.copy(resolver = {
                    val song = go.examate.hyp.music.lx.LxSong(
                        platform, songmid, base.title, base.artist, base.durationMs, base.coverUrl, base.coverRef
                    )
                    PlayResolver.resolve(context, song)
                })
            }
        } catch (_: Exception) { emptyList() }
    }

    fun isFavorite(context: Context, id: String): Boolean {
        idCache?.let { return id in it }
        val set = load(context).map { it.id }.toHashSet()
        idCache = set
        return id in set
    }

    /** 切换收藏,返回切换后是否已收藏 */
    fun toggle(context: Context, item: QueueItem): Boolean {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        return if (idx >= 0) {
            list.removeAt(idx)
            save(context, list)
            false
        } else {
            list.add(0, item)
            save(context, list)
            true
        }
    }

    private fun save(context: Context, list: List<QueueItem>) {
        val arr = org.json.JSONArray()
        list.forEach { t ->
            // 从 id("lx_platform_songmid")解析平台与 songmid
            var platform = ""; var songmid = ""
            if (t.id.startsWith("lx_")) {
                val rest = t.id.removePrefix("lx_")
                val sep = rest.indexOf('_')
                if (sep > 0) { platform = rest.substring(0, sep); songmid = rest.substring(sep + 1) }
            }
            arr.put(org.json.JSONObject().apply {
                put("id", t.id); put("title", t.title); put("artist", t.artist)
                put("duration", t.durationMs); put("cover", t.coverUrl)
                put("coverRef", t.coverRef); put("albumId", t.albumId)
                put("platform", platform); put("songmid", songmid)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
        idCache = null
    }
}
