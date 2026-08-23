package go.examate.hyp.music

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 官方歌词 API(参照落雪音乐 musicSdk 各平台 lyric.js,接口已实测):
 *  - tx 小秋(QQ): c.y.qq.com fcg_query_lyric_new(lyric 字段 base64) ✅
 *  - wy 小芸(网易): music.163.com/api/song/media(id 直出 LRC,免加密) ✅
 *  - kw 小蜗(酷我): m.kuwo.cn songinfoandlrc(lrclist 时间+行,免加密) ✅
 *  - kg 小枸(酷狗): 两步 search+download 且响应加密,暂不支持(由音源脚本兜底)
 * 返回 LRC 文本;失败返回 null。
 */
object OfficialLyricApi {
    private const val TAG = "OfficialLyricApi"
    private val client = MusicHttp.client

    /** 统一入口:平台 + songmid → LRC 文本;失败 null */
    fun fetch(platform: String, songmid: String): String? {
        val lrc = try {
            when (platform) {
                "tx" -> fetchTx(songmid)
                "wy" -> fetchWy(songmid)
                "kw" -> fetchKw(songmid)
                else -> null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "fetch fail: $platform/$songmid", e)
            null
        }
        Log.d(TAG, "fetch: $platform/$songmid → ${lrc?.length?.let { "LRC ${it}字符" } ?: "null"}")
        return lrc
    }

    /** QQ:fcg_query_lyric_new,lyric 字段 base64 */
    private fun fetchTx(songmid: String): String? {
        val body = get(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid" +
                "&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq",
            headers = mapOf(
                "Referer" to "https://y.qq.com/portal/player.html",
                "User-Agent" to "Mozilla/5.0 (compatible; MSIE 9.0)"
            )
        ) ?: return null
        val lrcB64 = JSONObject(body).optString("lyric")
        if (lrcB64.isBlank()) return null
        return String(Base64.decode(lrcB64, Base64.DEFAULT), Charsets.UTF_8)
    }

    /** 网易:api/song/media,lyric 字段直出 LRC */
    private fun fetchWy(id: String): String? {
        val body = get(
            "https://music.163.com/api/song/media?id=$id",
            headers = mapOf("Referer" to "https://music.163.com")
        ) ?: return null
        val lrc = JSONObject(body).optString("lyric")
        return lrc.takeIf { it.isNotBlank() }
    }

    /** 酷我:songinfoandlrc,lrclist(时间+行) → LRC */
    private fun fetchKw(id: String): String? {
        val body = get("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$id") ?: return null
        val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("lrclist") ?: return null
        if (arr.length() == 0) return null
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val time = o.optString("time")
            val line = o.optString("lineLyric")
            // time 如 "0.0"/"0.52" → [00:00.00]
            sb.append(timeToLrc(time)).append(line).append('\n')
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** "0.52" → "[00:00.52]" */
    private fun timeToLrc(time: String): String {
        val sec = time.toDoubleOrNull() ?: return "[00:00.00]"
        val m = (sec / 60).toInt()
        val s = sec.toInt() % 60
        val ms = ((sec - sec.toInt()) * 100).toInt()
        return String.format("[%02d:%02d.%02d]", m, s, ms)
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "get: HTTP ${resp.code} url=$url")
                null
            } else resp.body?.string()
        }
    }.getOrNull()
}
