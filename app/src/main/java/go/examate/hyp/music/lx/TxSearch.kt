package go.examate.hyp.music.lx

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import go.examate.hyp.music.MusicHttp
import go.examate.hyp.music.MusicUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐搜索 — 落雪音乐(lx-music-mobile)同款实现。
 *
 * 接口:u.y.qq.com/cgi-bin/musicu.fcg POST,module=music.search.SearchCgiService,
 * method=DoSearchForQQMusicLite(tmeAppID=qqmusiclight,明文无签名,抓包实测)。
 *
 * 返回字段:item_song[] → mid(播放)/title/singer[].name/album.mid(封面)/interval(秒)。
 * 播放走 OnlineMusicApi.resolveTxPlayUrl(中转 → vkey 兜底)。
 */
object TxSearch {
    private const val TAG = "TxSearch"
    private val client = MusicHttp.derived(15, 15)
    private val jsonType = "application/json".toMediaType()

    fun search(keyword: String, limit: Int = 30, page: Int = 1): List<LxSong> {
        val body = JSONObject()
        val comm = JSONObject()
        comm.put("ct", 11)
        comm.put("cv", "1003006")
        comm.put("v", "1003006")
        comm.put("os_ver", "12")
        comm.put("phonetype", "0")
        comm.put("devicelevel", "31")
        comm.put("tmeAppID", "qqmusiclight")
        comm.put("nettype", "NETWORK_WIFI")
        body.put("comm", comm)
        val req = JSONObject()
        req.put("module", "music.search.SearchCgiService")
        req.put("method", "DoSearchForQQMusicLite")
        val param = JSONObject()
        param.put("query", keyword)
        param.put("search_type", 0)
        param.put("num_per_page", limit)
        param.put("page_num", page.coerceAtLeast(1))
        param.put("nqc_flag", 0)
        param.put("grp", 1)
        req.put("param", param)
        body.put("req", req)

        val request = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .header("User-Agent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
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
        val list = JSONObject(body)
            .optJSONObject("req")?.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONArray("item_song") ?: return emptyList()
        (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val songmid = o.optString("mid")
            if (songmid.isBlank()) return@mapNotNull null
            val name = MusicUtil.cleanHtml(o.optString("title").ifBlank { o.optString("name") })
            // 歌手:singer[] 里取 name
            val singer = o.optJSONArray("singer")?.let { arr ->
                (0 until arr.length()).joinToString(" / ") { j ->
                    arr.optJSONObject(j)?.optString("name").orEmpty()
                }
            }.orEmpty()
            val durationSec = o.optLong("interval", 0)
            // 音质档(落雪同款):file.size_128mp3/size_320mp3/size_flac/size_hires
            val qualities = mutableListOf<String>()
            o.optJSONObject("file")?.let { f ->
                if (f.optLong("size_128mp3", 0) != 0L) qualities.add("128k")
                if (f.optLong("size_320mp3", 0) != 0L) qualities.add("320k")
                if (f.optLong("size_flac", 0) != 0L) qualities.add("flac")
                if (f.optLong("size_hires", 0) != 0L) qualities.add("flac24bit")
            }
            // 封面:QQ 官方图床(专辑图)
            val albumMid = o.optJSONObject("album")?.optString("mid").orEmpty()
            val picUrl = if (albumMid.isNotBlank() && albumMid != "空")
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
            else {
                val singerMid = o.optJSONArray("singer")?.optJSONObject(0)?.optString("mid").orEmpty()
                if (singerMid.isNotBlank()) "https://y.gtimg.cn/music/photo_new/T001R500x500M000$singerMid.jpg" else ""
            }
            LxSong("tx", songmid, name, singer, durationSec * 1000, picUrl, albumMid, qualities)
        }
    }.getOrDefault(emptyList())


}
