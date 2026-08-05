package go.examate.hyp.music.lx

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 酷我公开搜索 — 给 LX 音源提供歌曲列表(MUSICRID 数字部分供 musicUrl 解析)。
 * 纯 OkHttp,零依赖。注意酷我字段名全大写,DURATION 为字符串需转 Long。
 */
object KuwoSearch {
    private const val TAG = "KuwoSearch"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun search(keyword: String, limit: Int = 30): List<LxSong> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?all=$kw&ft=music&itemset=web_2013&client=kt" +
            "&pn=0&rn=$limit&rformat=json&encoding=utf8"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "http://www.kuwo.cn/")
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
        val root = JSONObject(body)
        val list = root.optJSONArray("abslist") ?: return emptyList()
        (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            // MUSICRID 形如 "music_123456",取数字部分作为 songmid
            val musicrid = o.optString("MUSICRID")
            val songmid = musicrid.removePrefix("music_")
            if (songmid.isBlank()) return@mapNotNull null
            val name = clean(o.optString("SONGNAME"))
            val artist = clean(o.optString("ARTIST"))
            val durationSec = o.optString("DURATION").toLongOrNull() ?: 0L
            // 封面:优先 albumpic,其次 web_albumpic_short
            val picUrl = o.optString("albumpic").ifBlank { o.optString("web_albumpic_short") }
            LxSong("kw", songmid, name, artist, durationSec * 1000, picUrl)
        }
    }.getOrDefault(emptyList())

    /** 清理常见 HTML 转义 */
    private fun clean(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
