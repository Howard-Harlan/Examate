package go.examate.hyp.music.lx

import android.util.Log
import go.examate.hyp.music.MusicHttp
import go.examate.hyp.music.MusicUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 酷狗公开搜索 — 参照落雪 musicSdk/kg/musicSearch.js(用户抓包同款接口)。
 *
 * 接口: https://songsearch.kugou.com/song_search_v2?keyword=...&platform=WebFilter&filter=2
 * 返回字段(大写,与 mobilecdn 不同):
 *   - FileHash: 歌曲 hash(小写即播放用 hash)
 *   - SongName / SingerName / AlbumID
 *   - Duration: 单位是**秒**(对标落雪 musicSearch.js 的 formatPlayTime(rawData.Duration))
 *
 * ⚠️ 注意:落雪用这个接口的原因是 mobilecdn 的 img 字段是"替代封面"不可用;
 * 本搜索只返回 hash + AlbumID,封面由 AlbumArtLoader → KugouCoverApi 兜底加载真实封面。
 */
object KugouSearch {
    private const val TAG = "KugouSearch"
    private val client = MusicHttp.derived(15, 15)

    fun search(keyword: String, limit: Int = 30, page: Int = 1): List<LxSong> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://songsearch.kugou.com/song_search_v2" +
            "?keyword=$kw&page=${page.coerceAtLeast(1)}&pagesize=$limit&userid=0&clientver=&platform=WebFilter" +
            "&filter=2&iscorrection=1&privilege_filter=0&area_code=1"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "search: HTTP ${resp.code} url=$url")
                    return emptyList()
                }
                parse(resp.body?.string() ?: return emptyList())
            }
        } catch (e: Exception) { Log.w(TAG, "search fail", e); emptyList() }
    }

    private fun parse(body: String): List<LxSong> = runCatching {
        val lists = JSONObject(body).optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        (0 until lists.length()).mapNotNull { i ->
            val o = lists.optJSONObject(i) ?: return@mapNotNull null
            val hash = o.optString("FileHash")
            if (hash.isBlank()) return@mapNotNull null
            val name = MusicUtil.cleanHtml(o.optString("SongName"))
            val artist = MusicUtil.cleanHtml(o.optString("SingerName"))
            val durationSec = o.optLong("Duration", 0)  // song_search_v2 的 Duration 单位是秒(落雪同款)
            val albumId = o.optString("AlbumID")
            // 音质档(落雪同款):HQFileSize≠0 → 320k;SQFileSize≠0 → flac(128k 恒有)
            val qualities = mutableListOf("128k")
            if (o.optLong("HQFileSize", 0) != 0L) qualities.add("320k")
            if (o.optLong("SQFileSize", 0) != 0L) qualities.add("flac")
            // 封面:AlbumImage 为空(song_search_v2 不返回)→ 占位给 album_id 图床 URL,
            // 真实封面由 AlbumArtLoader → KugouCoverApi(get_res_privilege)兜底加载
            val picUrl = if (albumId.isNotBlank()) "https://imgessl.kugou.com/stdmusic/150/$albumId.jpg" else ""
            LxSong("kg", hash, name, artist, durationSec * 1000, picUrl, albumId, qualities)
        }
    }.getOrDefault(emptyList())


}
