package go.examate.hyp.music.lx

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** LX 可解析的歌曲(来自平台搜索) */
data class LxSong(
    val platform: String,   // wy/kg/kw/tx/mg
    val songmid: String,    // 平台歌曲 id
    val name: String,
    val artist: String,
    val durationMs: Long,
    val picUrl: String = "" // 专辑封面 URL(网易云 album.picUrl)
)

/**
 * 网易云公开搜索 — 给 LX 音源提供歌曲列表(id 供 musicUrl 解析)。
 * 纯 OkHttp,零依赖。LX 自定义源只负责取播放URL,搜索由平台 API 补齐。
 */
object NeteaseSearch {
    private const val TAG = "NeteaseSearch"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun search(keyword: String, limit: Int = 30): List<LxSong> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://music.163.com/api/search/get/web?s=$kw&type=1&offset=0&total=true&limit=$limit"
        val req = Request.Builder().url(url)
            .header("Referer", "https://music.163.com")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Cookie", "appver=8.0.0; os=pc")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parse(body)
            }
        } catch (e: Exception) { Log.w(TAG, "search fail", e); emptyList() }
    }

    private fun parse(body: String): List<LxSong> = runCatching {
        val result = JSONObject(body).optJSONObject("result") ?: return emptyList()
        val songs = result.optJSONArray("songs") ?: return emptyList()
        (0 until songs.length()).mapNotNull { i ->
            val o = songs.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id").toString()
            val name = o.optString("name")
            val artists = o.optJSONArray("artists")
            val artist = if (artists != null && artists.length() > 0)
                (0 until artists.length()).joinToString("/") { artists.optJSONObject(it)?.optString("name").orEmpty() }
            else ""
            // 专辑封面:网易云搜索 API 返回 album.picUrl
            val album = o.optJSONObject("album")
            val picUrl = album?.optString("picUrl") ?: ""
            LxSong("wy", id, name, artist, o.optLong("duration"), picUrl)
        }
    }.getOrDefault(emptyList())
}
