package go.examate.hyp.music.bili

import android.content.Context
import android.util.Log
import go.examate.hyp.music.MusicHttp
import go.examate.hyp.music.MusicUtil
import go.examate.hyp.music.PlayRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** B站视频音频条目 */
data class BiliTrack(
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val durationSec: Long,
    val cover: String
) {
    /** 历史重建键 */
    fun historyKey(): String = "bili:$bvid:$cid"
}

/**
 * B站客户端 — 搜索视频 + 取 DASH 音频流。纯 OkHttp + WBI 签名,零第三方。
 *
 * 播放链路(逻辑清晰化):
 *   1. 登录(可选):WebView 抓取 Cookie(SESSDATA 等)经 BiliAuth 保存 — 未登录也可直接播放,
 *      登录态 Cookie 存在时自动附带(可享更高质量/同步进度);
 *   2. 请求:带登录态 Cookie(若有)调官方 playurl(fnval=16)获取视频 DASH 清单;
 *   3. 取音频:仅从 dash.audio 选最高音质音频流,不取视频流,以音频形式播放。
 *
 * 合规:不内置任何内容,依赖用户自己的B站账号(Cookie);仅取音频流用于个人播放。
 */
class BiliClient(private val context: Context) {

    private val client = MusicHttp.derived(15, 20)

    private fun cookie(): String = BiliAuth.cookie(context)

    private fun get(url: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com")
            .apply { if (cookie().isNotEmpty()) header("Cookie", cookie()) }
            .build()
        return runCatching { client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } }
            .getOrNull()
    }

    // ══════════ WBI 签名 ══════════

    private fun ensureMixinKey(): String? {
        mixinKey?.let { return it }
        val body = get("https://api.bilibili.com/x/web-interface/nav") ?: return null
        return runCatching {
            val wbi = JSONObject(body).getJSONObject("data").getJSONObject("wbi_img")
            val imgKey = wbi.getString("img_url").substringAfterLast('/').substringBefore('.')
            val subKey = wbi.getString("sub_url").substringAfterLast('/').substringBefore('.')
            val raw = imgKey + subKey
            val mixed = StringBuilder()
            for (i in MIXIN_TAB) if (i < raw.length) mixed.append(raw[i])
            mixed.substring(0, 32).also { mixinKey = it }
        }.getOrNull()
    }


    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 对参数做 WBI 签名,返回完整 query 串 */
    private fun signWbi(params: Map<String, String>): String {
        val mk = ensureMixinKey()
        val all = params.toMutableMap()
        all["wts"] = (System.currentTimeMillis() / 1000).toString()
        val sorted = all.toSortedMap()
        val query = sorted.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        if (mk == null) return query
        val wrid = MusicUtil.md5(query + mk)
        return "$query&w_rid=$wrid"
    }

    // ══════════ 搜索 ══════════
    fun search(keyword: String, page: Int = 1): List<BiliTrack> {
        ensureBuvid()
        val q = signWbi(mapOf("search_type" to "video", "keyword" to keyword, "page" to page.toString()))
        val body = get("https://api.bilibili.com/x/web-interface/wbi/search/type?$q") ?: return emptyList()
        return runCatching {
            val root = JSONObject(body)
            if (root.optInt("code") != 0) return emptyList()
            val list = root.getJSONObject("data").optJSONArray("result") ?: return emptyList()
            (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val bvid = o.optString("bvid").ifBlank { return@mapNotNull null }
                val title = o.optString("title").replace(Regex("<[^>]+>"), "")
                val author = o.optString("author")
                val cover = o.optString("pic").let { if (it.startsWith("//")) "https:$it" else it }
                val dur = parseDuration(o.optString("duration"))
                BiliTrack(bvid, 0L, title, author, dur, cover)
            }
        }.getOrDefault(emptyList())
    }

    /** 获取收藏夹视频列表(需登录态 Cookie;分页拉取,最多 200 个) */
    fun fetchFavList(mediaId: String, maxCount: Int = 200): List<BiliTrack> {
        val result = mutableListOf<BiliTrack>()
        var pn = 1
        val ps = 20
        while (result.size < maxCount) {
            val body = get("https://api.bilibili.com/x/v3/fav/resource/list?media_id=$mediaId&pn=$pn&ps=$ps&order=mtime&platform=web")
                ?: break
            val root = runCatching { JSONObject(body) }.getOrNull() ?: break
            if (root.optInt("code") != 0) break
            val medias = root.optJSONObject("data")?.optJSONArray("medias") ?: break
            if (medias.length() == 0) break
            for (i in 0 until medias.length()) {
                val o = medias.optJSONObject(i) ?: continue
                val bvid = o.optString("bvid")
                if (bvid.isBlank()) continue
                val title = o.optString("title").replace(Regex("<[^>]+>"), "")
                val author = o.optJSONObject("upper")?.optString("name") ?: ""
                val cover = o.optString("cover").let { if (it.startsWith("//")) "https:$it" else it }
                val dur = o.optLong("duration")
                result.add(BiliTrack(bvid, 0L, title, author, dur, cover))
                if (result.size >= maxCount) break
            }
            pn++
        }
        return result
    }

    /** 调 nav 验证登录态,返回用户名(未登录返回空) */
    fun fetchUserName(): String {
        val body = get("https://api.bilibili.com/x/web-interface/nav") ?: return ""
        return runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return ""
            if (data.optBoolean("isLogin")) data.optString("uname") else ""
        }.getOrDefault("")
    }

    /** 取视频首个分P的 cid(播放前需要) */
    fun firstCid(bvid: String): Long = viewInfo(bvid).second

    /** 取视频信息 (aid, cid);失败返回 (0, 0) */
    fun viewInfo(bvid: String): Pair<Long, Long> {
        val body = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid") ?: return 0L to 0L
        return runCatching {
            val d = JSONObject(body).getJSONObject("data")
            d.optLong("aid") to d.optLong("cid")
        }.getOrDefault(0L to 0L)
    }

    /**
     * 从账号观看历史取该视频的上次播放进度。
     * @return (cid, 进度秒数);未登录/无记录返回 (0, 0)
     */
    fun historyProgress(bvid: String): Pair<Long, Int> {
        if (!BiliAuth.isLoggedIn(context)) return 0L to 0
        val body = get("https://api.bilibili.com/x/web-interface/history/cursor?type=archive&ps=20&view_at=0")
            ?: return 0L to 0
        return runCatching {
            val list = JSONObject(body).getJSONObject("data").optJSONArray("list") ?: return 0L to 0
            for (i in 0 until list.length()) {
                val h = list.optJSONObject(i)?.optJSONObject("history") ?: continue
                if (h.optString("bvid") == bvid) {
                    return h.optLong("cid") to h.optInt("progress")
                }
            }
            0L to 0
        }.getOrDefault(0L to 0)
    }

    /**
     * 上报播放进度到账号历史(x/v2/history/report)。
     * 需要登录态 + bili_jct(csrf);返回是否上报成功。
     */
    fun reportProgress(bvid: String, cid: Long, aid: Long, progressSec: Int): Boolean {
        val cookie = cookie()
        val csrf = BiliAuth.value(cookie, "bili_jct")
        if (!BiliAuth.isLoggedIn(context) || csrf.isEmpty() || progressSec <= 0) return false
        val form = okhttp3.FormBody.Builder()
            .add("aid", aid.toString())
            .add("cid", cid.toString())
            .add("progress", progressSec.toString())
            .add("csrf", csrf)
            .build()
        val req = Request.Builder()
            .url("https://api.bilibili.com/x/v2/history/report")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/video/$bvid")
            .header("Cookie", cookie)
            .post(form)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful && JSONObject(resp.body?.string() ?: "").optInt("code") == 0
            }
        }.getOrDefault(false)
    }

    /** 便捷上报:自动补全 aid/cid 后上报进度 */
    fun reportProgressAuto(bvid: String, progressSec: Int): Boolean {
        if (progressSec <= 0) return false
        val (aid, cid) = viewInfo(bvid)
        if (aid <= 0 || cid <= 0) return false
        return reportProgress(bvid, cid, aid, progressSec)
    }

    /** 取 DASH 音频流最高音质 URL + 播放所需 headers(Referer) */
    fun resolveAudio(bvid: String, cidIn: Long): PlayRequest? {
        val cid = if (cidIn > 0) cidIn else firstCid(bvid)
        if (cid <= 0) return null
        val q = signWbi(mapOf("bvid" to bvid, "cid" to cid.toString(), "fnval" to "16", "fourk" to "1"))
        val body = get("https://api.bilibili.com/x/player/wbi/playurl?$q") ?: return null
        return runCatching {
            val data = JSONObject(body).getJSONObject("data")
            val audio = data.getJSONObject("dash").getJSONArray("audio")
            var bestUrl = ""; var bestId = -1
            for (i in 0 until audio.length()) {
                val a = audio.getJSONObject(i)
                val id = a.optInt("id")
                if (id > bestId) { bestId = id; bestUrl = a.optString("baseUrl").ifBlank { a.optString("base_url") } }
            }
            if (bestUrl.isBlank()) null
            else {
                // 播放 header:Referer 必需;附带 UA + Cookie(对齐 NeriPlayer,部分音质/会员流需要)
                val headers = mutableMapOf(
                    "Referer" to "https://www.bilibili.com",
                    "User-Agent" to UA
                )
                if (cookie().isNotEmpty()) headers["Cookie"] = cookie()
                PlayRequest(android.net.Uri.parse(bestUrl), headers)
            }
        }.getOrNull()
    }

    /** 确保 buvid3 存在(搜索风控需要),缺失则补取并合并进 Cookie */
    private fun ensureBuvid() {
        if (cookie().contains("buvid3")) return
        val body = get("https://api.bilibili.com/x/frontend/finger/spi") ?: return
        runCatching {
            val d = JSONObject(body).getJSONObject("data")
            val b3 = d.optString("b_3"); val b4 = d.optString("b_4")
            if (b3.isNotBlank()) {
                val merged = buildString {
                    append(cookie())
                    if (isNotEmpty() && !endsWith(";")) append("; ")
                    append("buvid3=$b3; buvid4=$b4")
                }
                BiliAuth.saveCookie(context, merged)
            }
        }
    }

    private fun parseDuration(s: String): Long {
        // "12:34" 或 "1:02:03"
        val parts = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toLong()
            2 -> (parts[0] * 60 + parts[1]).toLong()
            else -> 0L
        }
    }

    companion object {
        @Volatile
        private var mixinKey: String? = null
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        // WBI mixin 置换表(B站固定)
        private val MIXIN_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
        )
    }
}
