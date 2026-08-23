package go.examate.hyp.music

import android.content.Context
import go.examate.hyp.music.lx.LxSong
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户歌单 — 本地持久化(SharedPreferences + JSON):
 *  - 新建空歌单(用户自命名)
 *  - 从平台导入(调用 [SongListApi.detail] 拉取歌曲后存为本地歌单,保留 source/sourceId 供刷新)
 *  - 增删歌曲、删除歌单、重命名
 *
 * 存储结构(独立分 key,避免增删歌曲时全量读写):
 *  - "index":  JSONArray of 歌单元信息(不含歌曲),含 songCount
 *  - "songs_{id}": 每个歌单的歌曲 JSONArray
 *
 * 歌曲以 [LxSong] 形式存储(平台 + songmid),播放时经 [go.examate.hyp.music.lx.LxSong.toQueueItem] 转 QueueItem。
 */
object PlaylistStore {
    private const val PREFS = "music_playlists"
    private const val KEY_INDEX = "index"

    private fun songsKey(id: String) = "songs_$id"

    /** 用户歌单 */
    data class UserPlaylist(
        val id: String,
        val name: String,
        val coverUrl: String = "",
        val source: String = "local",   // "local" 或平台码 kw/kg/tx/wy/mg
        val sourceId: String = "",       // 平台歌单原 ID(导入来源,刷新时用)
        val songs: List<LxSong> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val songCount: Int = 0
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取索引(轻量,不含歌曲内容) */
    private fun readIndex(context: Context): MutableList<JSONObject> {
        val json = prefs(context).getString(KEY_INDEX, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNullTo(mutableListOf()) { i -> arr.optJSONObject(i) }
        } catch (_: Exception) { mutableListOf() }
    }

    /** 写入索引 */
    private fun writeIndex(context: Context, index: List<JSONObject>) {
        val arr = JSONArray()
        index.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_INDEX, arr.toString()).apply()
    }

    /** 索引项 → UserPlaylist(songs=empty) */
    private fun JSONObject.toMeta(): UserPlaylist = UserPlaylist(
        id = optString("id"),
        name = optString("name"),
        coverUrl = optString("coverUrl"),
        source = optString("source", "local"),
        sourceId = optString("sourceId"),
        createdAt = optLong("createdAt"),
        songCount = optInt("songCount")
    )

    /** 构造索引项 */
    private fun indexEntry(id: String, name: String, coverUrl: String, source: String,
                           sourceId: String, createdAt: Long, songCount: Int): JSONObject =
        JSONObject().apply {
            put("id", id); put("name", name); put("coverUrl", coverUrl)
            put("source", source); put("sourceId", sourceId); put("createdAt", createdAt)
            put("songCount", songCount)
        }

    /** 读取歌单歌曲 */
    private fun readSongs(context: Context, id: String): MutableList<LxSong> {
        val json = prefs(context).getString(songsKey(id), null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNullTo(mutableListOf()) { j ->
                val s = arr.optJSONObject(j) ?: return@mapNotNullTo null
                LxSong(
                    platform = s.optString("platform"),
                    songmid = s.optString("songmid"),
                    name = s.optString("name"),
                    artist = s.optString("artist"),
                    durationMs = s.optLong("durationMs"),
                    picUrl = s.optString("picUrl"),
                    albumId = s.optString("albumId")
                )
            }
        } catch (_: Exception) { mutableListOf() }
    }

    /** 写入歌单歌曲 */
    private fun writeSongs(context: Context, id: String, songs: List<LxSong>) {
        val arr = JSONArray()
        songs.forEach { s ->
            arr.put(JSONObject().apply {
                put("platform", s.platform); put("songmid", s.songmid)
                put("name", s.name); put("artist", s.artist)
                put("durationMs", s.durationMs); put("picUrl", s.picUrl); put("albumId", s.albumId)
            })
        }
        prefs(context).edit().putString(songsKey(id), arr.toString()).apply()
    }

    /** 读取全部歌单(按创建时间倒序,不含歌曲内容;列表页用 [songCount] 显示数量) */
    fun loadAll(context: Context): List<UserPlaylist> =
        readIndex(context).map { it.toMeta() }.sortedByDescending { it.createdAt }

    /** 获取单个歌单(含完整歌曲列表) */
    fun get(context: Context, id: String): UserPlaylist? {
        val meta = readIndex(context).find { it.optString("id") == id } ?: return null
        val songs = readSongs(context, id)
        return meta.toMeta().copy(songs = songs, songCount = songs.size)
    }

    /** 新建空歌单,返回新歌单 ID */
    fun create(context: Context, name: String): String {
        val id = UUID.randomUUID().toString()
        val index = readIndex(context)
        index.add(indexEntry(id, name.ifBlank { "新建歌单" }, "", "local", "",
            System.currentTimeMillis(), 0))
        writeIndex(context, index)
        return id
    }

    /** 全量拉取歌单歌曲(循环分页;kw/mg 支持分页,kg/tx/wy 一次返回全部) */
    private fun fetchAllSongs(platform: String, sourceId: String): Pair<KtSongListDetail, List<LxSong>>? {
        val first = SongListApi.detail(platform, sourceId) ?: return null
        val pageSize = SongListApi.pageSizeFor(platform, false)
        val allSongs = first.songs.toMutableList()
        var page = 2
        while (first.songs.size >= pageSize && allSongs.size < 1000 && page <= 20) {
            val d = SongListApi.detail(platform, sourceId, page) ?: break
            if (d.songs.isEmpty()) break
            val before = allSongs.size
            for (s in d.songs) if (allSongs.none { it.songmid == s.songmid && it.platform == s.platform }) allSongs.add(s)
            if (allSongs.size == before || d.songs.size < pageSize) break
            page++
        }
        return first to allSongs
    }

    /**
     * 从平台导入歌单:全量拉取(循环分页)后存为本地歌单。
     * @return 导入的歌单 ID;失败返回 null
     */
    fun importFromPlatform(
        context: Context, platform: String, sourceId: String, customName: String = ""
    ): String? {
        val (info, songs) = fetchAllSongs(platform, sourceId) ?: return null
        val id = UUID.randomUUID().toString()
        writeSongs(context, id, songs)
        val index = readIndex(context)
        index.add(indexEntry(id, customName.ifBlank { info.info.name.ifBlank { "导入歌单" } },
            info.info.img, platform, sourceId, System.currentTimeMillis(), songs.size))
        writeIndex(context, index)
        return id
    }

    /** 从已加载的队列项创建歌单(收藏搜索到的歌单,避免重复请求) */
    fun importFromQueueItems(
        context: Context, name: String, coverUrl: String, source: String, sourceId: String, items: List<QueueItem>
    ): String {
        val songs = items.map { qi ->
            val rawId = if (qi.id.startsWith("lx_")) qi.id.substring(3) else qi.id
            val parts = rawId.split("_", limit = 2)
            LxSong(
                if (parts.size == 2) parts[0] else "",
                if (parts.size == 2) parts[1] else "",
                qi.title, qi.artist, qi.durationMs, qi.coverUrl, qi.coverRef
            )
        }
        val id = UUID.randomUUID().toString()
        writeSongs(context, id, songs)
        val index = readIndex(context)
        index.add(indexEntry(id, name.ifBlank { "收藏歌单" }, coverUrl, source, sourceId,
            System.currentTimeMillis(), songs.size))
        writeIndex(context, index)
        return id
    }

    /** 从B站收藏夹导入:拉取收藏夹视频列表后存为本地歌单(平台 bili,播放走 BiliClient DASH) */
    fun importFromBiliFav(context: Context, favId: String, customName: String = ""): String? {
        val client = go.examate.hyp.music.bili.BiliClient(context)
        val tracks = client.fetchFavList(favId)
        if (tracks.isEmpty()) return null
        val songs = tracks.map { t ->
            LxSong(
                platform = "bili", songmid = t.bvid, name = t.title, artist = t.author,
                durationMs = t.durationSec * 1000, picUrl = t.cover, albumId = ""
            )
        }
        val id = UUID.randomUUID().toString()
        writeSongs(context, id, songs)
        val index = readIndex(context)
        index.add(indexEntry(id, customName.ifBlank { "B站收藏夹" },
            tracks.firstOrNull()?.cover ?: "", "bili", favId,
            System.currentTimeMillis(), songs.size))
        writeIndex(context, index)
        return id
    }

    /** 刷新已导入的平台歌单(重新全量拉取歌曲) */
    fun refresh(context: Context, id: String): Boolean {
        val index = readIndex(context)
        val idx = index.indexOfFirst { it.optString("id") == id }
        if (idx < 0) return false
        val meta = index[idx]
        val source = meta.optString("source")
        val sourceId = meta.optString("sourceId")
        if (source == "local" || sourceId.isBlank()) return false
        val (info, songs) = fetchAllSongs(source, sourceId) ?: return false
        writeSongs(context, id, songs)
        index[idx] = indexEntry(id, meta.optString("name"),
            info.info.img.ifBlank { meta.optString("coverUrl") }, source, sourceId,
            meta.optLong("createdAt"), songs.size)
        writeIndex(context, index)
        return true
    }

    /** 重命名歌单 */
    fun rename(context: Context, id: String, newName: String) {
        val index = readIndex(context)
        val idx = index.indexOfFirst { it.optString("id") == id }
        if (idx < 0) return
        val old = index[idx]
        index[idx] = indexEntry(id, newName.ifBlank { old.optString("name") },
            old.optString("coverUrl"), old.optString("source", "local"), old.optString("sourceId"),
            old.optLong("createdAt"), old.optInt("songCount"))
        writeIndex(context, index)
    }

    /** 删除歌单 */
    fun delete(context: Context, id: String) {
        val index = readIndex(context)
        if (index.none { it.optString("id") == id }) return
        writeIndex(context, index.filterNot { it.optString("id") == id })
        prefs(context).edit().remove(songsKey(id)).apply()
    }

    /** 向歌单添加歌曲(去重 by platform+songmid) */
    fun addSong(context: Context, playlistId: String, song: LxSong) {
        val index = readIndex(context)
        val idx = index.indexOfFirst { it.optString("id") == playlistId }
        if (idx < 0) return
        val songs = readSongs(context, playlistId)
        if (songs.any { it.platform == song.platform && it.songmid == song.songmid }) return
        songs.add(song)
        writeSongs(context, playlistId, songs)
        index[idx].put("songCount", songs.size)
        writeIndex(context, index)
    }

    /** 从歌单移除歌曲 */
    fun removeSong(context: Context, playlistId: String, platform: String, songmid: String) {
        val index = readIndex(context)
        val idx = index.indexOfFirst { it.optString("id") == playlistId }
        if (idx < 0) return
        val songs = readSongs(context, playlistId).filterNot { it.platform == platform && it.songmid == songmid }
        writeSongs(context, playlistId, songs)
        index[idx].put("songCount", songs.size)
        writeIndex(context, index)
    }
}
