package go.examate.hyp.music.lx

import android.util.Log
import go.examate.hyp.music.MusicHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 酷我封面 API — 参照落雪 musicSdk/kw/pic.js:
 *   GET http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid={songmid}
 *   返回纯文本封面 URL(非 http 开头视为失败)。
 *
 * 用途:歌单详情(nplserver)不返回歌曲封面时按 MUSICRID 兜底取真实封面。
 */
object KuwoCoverApi {
    private const val TAG = "KuwoCover"
    private const val CACHE_TTL = 24L * 3600_000
    private val memCache = android.util.LruCache<String, Pair<String, Long>>(500)
    private val client = MusicHttp.derived(10, 12)

    fun getPicUrl(songmid: String): String? {
        if (songmid.isBlank()) return null
        val now = System.currentTimeMillis()
        memCache.get(songmid)?.let { (u, ts) -> if (now - ts < CACHE_TTL) return u else memCache.remove(songmid) }
        val url = fetchPicUrl(songmid)
        if (url != null) memCache.put(songmid, url to now)
        return url
    }

    private fun fetchPicUrl(songmid: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url("http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=$songmid")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getPicUrl: HTTP ${resp.code} rid=$songmid")
                    return@runCatching null
                }
                val body = resp.body?.string()?.trim().orEmpty()
                if (body.startsWith("http")) body else null
            }
        }.getOrNull()
    }
}
