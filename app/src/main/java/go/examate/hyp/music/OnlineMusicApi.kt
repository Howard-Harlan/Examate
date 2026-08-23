package go.examate.hyp.music

import android.content.Context
import android.util.Log
import go.examate.hyp.music.auth.MusicAuth
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 在线音源官方直连播放 — 各平台官方 API 直连,不依赖音源脚本/第三方中转。
 *
 * 官方播放 = "模拟登录拿数据":用户在「我的-账号」登录各平台后,播放解析把
 * 已保存的 Cookie 附到官方接口请求上,按登录账号获取播放链接与音质。
 * 未登录的平台由 PlayResolver 在入口处拦截提示登录,本层只负责带登录态请求。
 *
 * 平台支持:
 *  - kw 酷我:antiserver.kuwo.cn 直连(带 Cookie 提升可得率)
 *  - wy 网易:music.163.com enhance/player/url(带 MUSIC_U)
 *  - tx QQ:u.y.qq.com vkey(带 uin/qm_keyst,不使用任何第三方中转)
 *  - kg 酷狗:getSongInfo(带登录 Cookie)
 *  - mg 咪咕:listenSong.do(带登录 Cookie,先经 copyrightId 换 songId)
 *
 * 失败返回 null,由调用方给出提示。
 */
object OnlineMusicApi {
    private const val TAG = "OnlineMusicApi"
    private val client = MusicHttp.client

    /** 统一入口:平台 + songmid/hash → 播放 URL;失败 null。quality: 128k/320k/flac(默认 128k) */
    fun resolvePlayUrl(context: Context, platform: String, songmid: String, quality: String = "128k"): String? {
        val cookie = MusicAuth.cookie(context, platform)
        val url = try {
            when (platform) {
                "kw" -> resolveKw(songmid, cookie)
                "wy" -> resolveWy(songmid, quality, cookie)
                "kg" -> resolveKg(songmid, cookie)
                "tx" -> resolveTx(songmid, cookie)
                "mg" -> resolveMg(songmid, quality, cookie)
                else -> null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "resolvePlayUrl fail: $platform/$songmid", e)
            null
        }
        Log.d(TAG, "resolvePlayUrl: $platform/$songmid($quality) → ${url?.take(80) ?: "null"}")
        return url
    }

    /** 酷我官方直连:antiserver 返回纯文本 URL 或 JSON;带登录 Cookie 提升 VIP 曲可得率 */
    private fun resolveKw(rid: String, cookie: String): String? {
        val headers = mutableMapOf<String, String>()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        val body = get(
            "http://antiserver.kuwo.cn/anti.s?type=convert_url3&rid=MUSIC_$rid&format=mp3|aac&response=url",
            headers
        ) ?: return null
        val trimmed = body.trim().trim('"')
        return if (trimmed.startsWith("http")) trimmed
        else runCatching { JSONObject(trimmed).optString("url") }
            .getOrNull()?.takeIf { it.startsWith("http") }
    }

    /** 网易官方直连:enhance/player/url 老 API;带 MUSIC_U 时按登录账号出 VIP/高音质 */
    private fun resolveWy(id: String, quality: String, cookie: String): String? {
        val br = when (quality) {
            "flac" -> 999000
            "320k" -> 320000
            else -> 128000
        }
        val cookieHeader = buildString {
            append("os=pc; appver=8.0.0")
            if (cookie.isNotBlank()) append("; ").append(cookie)
        }
        val body = get(
            "https://music.163.com/api/song/enhance/player/url?ids=[$id]&br=$br",
            headers = mapOf(
                "Referer" to "https://music.163.com",
                "Cookie" to cookieHeader
            )
        ) ?: return null
        val arr = JSONObject(body).optJSONArray("data") ?: return null
        val url = arr.optJSONObject(0)?.optString("url").orEmpty()
        return url.takeIf { it.isNotBlank() }
    }

    /**
     * QQ 音乐官方 vkey:musicu.fcg CgiGetVkey。
     * 带登录态(uin + qm_keyst Cookie)时按登录账号出链接;未携带时仅能出免费试听。
     * 注:不使用任何第三方中转,纯官方接口。
     */
    private fun resolveTx(songmid: String, cookie: String): String? {
        val uin = MusicAuth.value(cookie, "uin").removePrefix("o").ifBlank { "0" }
        val guid = (10000000..99999999).random().toString()
        val data = JSONObject()
        val comm = JSONObject()
        comm.put("ct", "11"); comm.put("cv", "14090508"); comm.put("v", "14090508"); comm.put("tmeAppID", "qqmusic")
        data.put("comm", comm)
        val req0 = JSONObject()
        req0.put("module", "vkey.GetVkeyServer")
        req0.put("method", "CgiGetVkey")
        val param = JSONObject()
        param.put("guid", guid)
        param.put("songmid", JSONArray().put(songmid))
        param.put("songtype", JSONArray().put(0))
        param.put("uin", uin)
        param.put("loginflag", if (uin != "0") 1 else 0)
        param.put("platform", "20")
        param.put("needNewCode", 1)
        req0.put("param", param)
        data.put("req_0", req0)

        val headers = mutableMapOf<String, String>()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        val body = post(
            "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${java.net.URLEncoder.encode(data.toString(), "UTF-8")}",
            headers
        ) ?: return null
        val reqObj = JSONObject(body).optJSONObject("req_0")?.optJSONObject("data") ?: return null
        val purl = reqObj.optJSONArray("midurlinfo")?.optJSONObject(0)?.optString("purl").orEmpty()
        if (purl.isBlank()) return null
        val sip = reqObj.optJSONArray("sip")?.optString(0).orEmpty()
            .ifBlank { "http://aqqmusic.tc.qq.com/" }
        return sip.trimEnd('/') + "/" + purl
    }

    /** 酷狗官方直连:getSongInfo(带登录 Cookie 提升付费/VIP 曲可得率) */
    private fun resolveKg(hash: String, cookie: String): String? {
        val headers = mutableMapOf<String, String>()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        val body = get("https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash", headers)
            ?: return null
        val url = JSONObject(body).optString("url").orEmpty()
        return url.takeIf { it.isNotBlank() }
    }

    /**
     * 咪咕官方播放 — listenSong.do(带登录 Cookie):
     * 搜索侧 songmid 为 copyrightId,先经 copyrightId 换 songId,再取播放地址。
     * 音质:LQ/HQ/SQ(128k→LQ,320k→HQ,flac→SQ 降级 HQ)。
     */
    private fun resolveMg(copyrightId: String, quality: String, cookie: String): String? {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) " +
                "AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30",
            "channel" to "0146921"
        )
        if (cookie.isNotBlank()) headers["Cookie"] = cookie

        // 1. copyrightId → songId(copyrightId 集合批量查)
        val songId = mgSongIdByCopyright(copyrightId, headers) ?: return null
        // 2. 取播放地址(音质降级:SQ 失败回退 HQ,HQ 失败回退 LQ)
        val toneFlags = when (quality) {
            "flac" -> listOf("SQ", "HQ", "LQ")
            "320k" -> listOf("HQ", "LQ")
            else -> listOf("LQ", "HQ")
        }
        for (tone in toneFlags) {
            val url = mgListenUrl(songId, tone, headers)
            if (url != null) return url
        }
        return null
    }

    /** 咪咕:copyrightId 批量查询换 songId */
    private fun mgSongIdByCopyright(copyrightId: String, headers: Map<String, String>): String? {
        val body = get(
            "https://app.pd.nf.migu.cn/MIGUM3.0/v1.0/content/copyright_offline/checkCopyright.do" +
                "?resourceType=2&copyrightId=$copyrightId&channel=0146921",
            headers
        ) ?: return null
        return runCatching {
            val root = JSONObject(body)
            val item = root.optJSONArray("resource")?.optJSONObject(0)
                ?: root.optJSONObject("resource")?.optJSONArray("resourceInfo")?.optJSONObject(0)
            item?.optString("songId")?.takeIf { it.isNotBlank() && it != "0" }
        }.getOrNull()
    }

    /** 咪咕:listenSong.do 取播放地址 */
    private fun mgListenUrl(songId: String, toneFlag: String, headers: Map<String, String>): String? {
        val body = get(
            "https://app.pd.nf.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do" +
                "?songId=$songId&resourceType=2&toneFlag=$toneFlag&channel=0146921",
            headers
        ) ?: return null
        return runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return null
            val urlNode = data.optJSONObject("url")
            val fmts = urlNode?.optJSONArray("formatData") ?: JSONArray()
            var best: String? = null
            for (i in 0 until fmts.length()) {
                val f = fmts.optJSONObject(i) ?: continue
                val u = f.optString("url").ifBlank { f.optString("androidUrl").ifBlank { f.optString("iOSUrl") } }
                if (u.isNotBlank()) { best = if (u.startsWith("//")) "https:$u" else u; break }
            }
            // 部分响应直接给 url 字段
            best ?: urlNode?.optString("url")?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("//")) "https:$it" else it
            }
        }.getOrNull()
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

    private fun post(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "post: HTTP ${resp.code} url=$url")
                null
            } else resp.body?.string()
        }
    }.getOrNull()
}
