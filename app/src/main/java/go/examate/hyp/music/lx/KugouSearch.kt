package go.examate.hyp.music.lx

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 酷狗公开搜索 — 给 LX 音源提供歌曲列表(hash 供 musicUrl 解析)。
 * 纯 OkHttp,零依赖。与 NeteaseSearch 同构。
 */
object KugouSearch {
    private const val TAG = "KugouSearch"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun search(keyword: String, limit: Int = 30): List<LxSong> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$kw&page=1&pagesize=$limit"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
        val data = JSONObject(body).optJSONObject("data") ?: return emptyList()
        val info = data.optJSONArray("info") ?: return emptyList()
        (0 until info.length()).mapNotNull { i ->
            val o = info.optJSONObject(i) ?: return@mapNotNull null
            val hash = o.optString("hash")
            if (hash.isBlank()) return@mapNotNull null
            val name = clean(o.optString("songname"))
            val artist = clean(o.optString("singername"))
            val durationSec = o.optLong("duration", 0)
            // 封面:优先 img 字段,缺失则按 album_id 拼接
            val img = o.optString("img")
            val albumId = o.optString("album_id")
            val picUrl = when {
                img.isNotBlank() -> img
                albumId.isNotBlank() -> "https://imge.kugou.com/album/img/480/$albumId.jpg"
                else -> ""
            }
            LxSong("kg", hash, name, artist, durationSec * 1000, picUrl)
        }
    }.getOrDefault(emptyList())

    /** 清理常见 HTML 转义(酷狗 songname 可能含 &lt; &gt; &amp; 等) */
    private fun clean(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
