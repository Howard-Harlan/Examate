package go.examate.hyp.music.lx

import android.util.Log
import go.examate.hyp.music.MusicHttp
import go.examate.hyp.music.MusicUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 咪咕音乐搜索 — 落雪音乐(lx-music-mobile) mg 平台同款实现。
 *
 * 接口:https://jadeite.migu.cn/music_search/v3/search/searchAll (GET)
 * 签名(落雪源码 createSignature):sign = MD5(str + "6cdc72a439cef99a3418d2a78aa28c73"
 *   + "yyapp2d16148780a1dcc7408e06336b98cfd50" + deviceId + time)
 * 响应:songResultData.resultList(二维) → songId/copyrightId/name/singerList/album/img
 */
object MiguSearch {
    private const val TAG = "MiguSearch"
    private val client = MusicHttp.derived(15, 15)

    private const val DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20"
    private const val SIGN_MD5 = "6cdc72a439cef99a3418d2a78aa28c73"
    private const val APP_KEY = "yyapp2d16148780a1dcc7408e06336b98cfd50"

    fun search(keyword: String, page: Int = 1, limit: Int = 30): List<LxSong> {
        val time = System.currentTimeMillis().toString()
        val sign = MusicUtil.md5(keyword + SIGN_MD5 + APP_KEY + DEVICE_ID + time)
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll" +
            "?isCorrect=0&isCopyright=1" +
            "&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0%2C%22lyricSong%22%3A0%7D" +
            "&pageSize=$limit&text=${URLEncoder.encode(keyword, "UTF-8")}&pageNo=$page&sort=0&sid=USS"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30")
            .header("Accept", "application/json")
            .header("uiVersion", "A_music_3.6.1")
            .header("deviceId", DEVICE_ID)
            .header("timestamp", time)
            .header("sign", sign)
            .header("channel", "0146921")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "search: HTTP ${resp.code}")
                    return emptyList()
                }
                parse(resp.body?.string() ?: return emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "search fail", e)
            emptyList()
        }
    }

    private fun parse(body: String): List<LxSong> = runCatching {
        val groups = JSONObject(body).optJSONObject("songResultData")
            ?.optJSONArray("resultList") ?: return emptyList()
        val out = mutableListOf<LxSong>()
        val seen = mutableSetOf<String>()
        for (g in 0 until groups.length()) {
            val arr = groups.optJSONArray(g) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val songId = o.optString("songId")
                val copyrightId = o.optString("copyrightId")
                if (songId.isBlank() || copyrightId.isBlank() || !seen.add(copyrightId)) continue
                val name = o.optString("name")
                // 歌手:singerList[] → name
                val singer = o.optJSONArray("singerList")?.let { sa ->
                    (0 until sa.length()).joinToString(" / ") { j ->
                        sa.optJSONObject(j)?.optString("name").orEmpty()
                    }
                }.orEmpty()
                val durationMs = (o.optDouble("length", 0.0) * 1000).toLong()
                // 音质档(落雪同款):audioFormats[].formatType PQ→128k / HQ→320k / SQ→flac / ZQ24→flac24bit
                val qualities = mutableListOf<String>()
                o.optJSONArray("audioFormats")?.let { af ->
                    for (j in 0 until af.length()) {
                        when (af.optJSONObject(j)?.optString("formatType")) {
                            "PQ" -> qualities.add("128k")
                            "HQ" -> qualities.add("320k")
                            "SQ" -> qualities.add("flac")
                            "ZQ24" -> qualities.add("flac24bit")
                        }
                    }
                }
                // 封面:img1/img2/img3(可能相对路径,补域名)
                var pic = o.optString("img1").ifBlank { o.optString("img2").ifBlank { o.optString("img3") } }
                if (pic.isNotBlank() && !pic.startsWith("http")) pic = "http://d.musicapp.migu.cn$pic"
                out.add(LxSong("mg", copyrightId, name, singer, durationMs, pic, copyrightId, qualities))
            }
        }
        out
    }.getOrDefault(emptyList())


}
