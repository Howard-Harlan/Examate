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
 * 酷我公开搜索 — 给 LX 音源提供歌曲列表(MUSICRID 数字部分供 musicUrl 解析)。
 * 纯 OkHttp,零依赖。注意酷我字段名全大写,DURATION 为字符串需转 Long。
 */
object KuwoSearch {
    private const val TAG = "KuwoSearch"
    private val client = MusicHttp.derived(15, 15)

    fun search(keyword: String, limit: Int = 30, page: Int = 1): List<LxSong> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val pn = (page - 1).coerceAtLeast(0) * limit
        val url = "http://search.kuwo.cn/r.s?all=$kw&ft=music&itemset=web_2013&client=kt" +
            "&pn=$pn&rn=$limit&rformat=json&encoding=utf8"
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
            // MUSICRID 形如 "music_123456"/"MUSIC_123456",取数字部分作为 songmid
            val musicrid = o.optString("MUSICRID")
            val songmid = musicrid.substringAfter("_", musicrid).takeIf { it.all { c -> c.isDigit() } } ?: musicrid
            if (songmid.isBlank()) return@mapNotNull null
            val name = MusicUtil.cleanHtml(o.optString("SONGNAME"))
            val artist = MusicUtil.cleanHtml(o.optString("ARTIST"))
            val durationSec = o.optString("DURATION").toLongOrNull() ?: 0L
            // 音质档(落雪同款):N_MINFO "level:x,bitrate:4000,format:flac,size:xx;..."
            //   bitrate 4000→flac24bit / 2000→flac / 320→320k / 128→128k
            val qualities = mutableListOf<String>()
            o.optString("N_MINFO").split(';').forEach { seg ->
                val m = Regex("bitrate:(\\d+)").find(seg)
                when (m?.groupValues?.get(1)) {
                    "4000" -> qualities.add("flac24bit")
                    "2000" -> qualities.add("flac")
                    "320" -> qualities.add("320k")
                    "128" -> qualities.add("128k")
                }
            }
            // 封面:优先 albumpic(完整 URL),其次 web_albumpic_short(无域名短路径,需拼酷我图床域名)
            val albumpic = o.optString("albumpic")
            val short = o.optString("web_albumpic_short")
            val picUrl = when {
                albumpic.isNotBlank() -> albumpic
                    .let { if (it.startsWith("//")) "https:$it" else it }
                    .replace("http://", "https://")
                short.isNotBlank() -> "https://img1.kuwo.cn/star/albumcover/$short"
                else -> ""
            }
            LxSong("kw", songmid, name, artist, durationSec * 1000, picUrl, qualities = qualities)
        }
    }.getOrDefault(emptyList())


}
