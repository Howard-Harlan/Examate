package go.examate.hyp.music

import android.util.Log
import go.examate.hyp.music.lx.LxSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 榜单信息(多平台: kw/kg/tx) */
data class KtBoard(
    val id: String,
    val name: String,
    val img: String = "",
    val playCount: Long = 0,
    val desc: String = "",
    val platform: String = "tx"
)

/**
 * 多平台排行榜 API(参照落雪 musicSdk 各平台 leaderboard.js):
 *  - tx 小秋(QQ):  列表 fcg_myqq_toplist(明文) / 详情 musicu.fcg GetDetail(明文)
 *  - kg 小枸(酷狗): 列表 rank/list / 详情 rank/song(明文)
 *  - kw 小蜗(酷我): 列表 qukudata q.k(明文) / 详情 wbd bang_info(加密,暂不支持)
 *  - wy 小芸(网易): 需 weapi 加密,暂不支持(后续)
 */
object LeaderboardApi {
    private const val TAG = "LeaderboardApi"
    private val client = MusicHttp.client

    /** 三平台榜单列表(合并,单平台失败不阻断) */
    fun boards(): List<KtBoard> = runBlocking(Dispatchers.IO) {
        val tx = async { runCatching { boardsTx() }.onFailure { Log.w(TAG, "boardsTx fail", it) }.getOrDefault(emptyList()) }
        val kg = async { runCatching { boardsKg() }.onFailure { Log.w(TAG, "boardsKg fail", it) }.getOrDefault(emptyList()) }
        val kw = async { runCatching { boardsKw() }.onFailure { Log.w(TAG, "boardsKw fail", it) }.getOrDefault(emptyList()) }
        tx.await() + kg.await() + kw.await()
    }

    /** 榜单详情(歌曲列表);kw/wy 暂不支持返回 null;kg 支持分页(page)供懒加载 */
    fun detail(platform: String, id: String, page: Int = 1): KtSongListDetail? = when (platform) {
        "tx" -> detailTx(id)
        "kg" -> detailKg(id, page)
        else -> null
    }

    /**
     * 带缓存的榜单列表(1h):榜单元信息短期不变,避免首页每次重复请求三平台。
     */
    fun boardsCached(context: android.content.Context): List<KtBoard> {
        MusicDataCache.get(context, "boards_v1", 3600_000L)?.let { cached ->
            val list = fromBoardsJson(cached)
            if (list.isNotEmpty()) return list
        }
        val list = boards()
        if (list.isNotEmpty()) MusicDataCache.put(context, "boards_v1", toBoardsJson(list))
        return list
    }

    /** 带缓存的榜单详情(6h):榜单内容按日更新,6h 内不重复请求 */
    fun detailCached(context: android.content.Context, platform: String, id: String): KtSongListDetail? =
        SongListApi.detailCached(context, platform, id, isBoard = true) { detail(platform, id) }

    private fun toBoardsJson(list: List<KtBoard>): String {
        val arr = org.json.JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id); put("name", b.name); put("img", b.img)
                put("playCount", b.playCount); put("desc", b.desc); put("platform", b.platform)
            })
        }
        return arr.toString()
    }

    private fun fromBoardsJson(json: String): List<KtBoard> = runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            KtBoard(
                id = o.optString("id"), name = o.optString("name"), img = o.optString("img"),
                playCount = o.optLong("playCount"), desc = o.optString("desc"),
                platform = o.optString("platform", "tx")
            )
        }
    }.getOrDefault(emptyList())

    // ── tx 小秋(QQ) ──

    private fun boardsTx(): List<KtBoard> {
        val body = get("https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?g_tk=1928093487" +
            "&inCharset=utf-8&outCharset=utf-8&notice=0&format=json&uin=0&needNewCode=1&platform=h5") ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("topList") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id").toString()
                KtBoard(
                    id = id,
                    name = o.optString("topTitle").ifBlank { o.optString("title") },
                    img = o.optString("picUrl").ifBlank { o.optString("frontPicUrl") },
                    playCount = o.optLong("listenCount", 0),
                    desc = o.optString("info"), platform = "tx"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun detailTx(id: String): KtSongListDetail? {
        val data = JSONObject()
        val toplist = JSONObject()
        toplist.put("module", "musicToplist.ToplistInfoServer")
        toplist.put("method", "GetDetail")
        val param = JSONObject()
        param.put("topid", id.toLongOrNull() ?: return null)
        param.put("num", 100)
        param.put("period", "")
        toplist.put("param", param)
        data.put("toplist", toplist)
        val comm = JSONObject()
        comm.put("uin", 0); comm.put("format", "json"); comm.put("ct", 20); comm.put("cv", 1859)
        data.put("comm", comm)

        val body = post(
            "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=" +
                java.net.URLEncoder.encode(data.toString(), "UTF-8"),
            headers = mapOf("User-Agent" to "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")
        ) ?: return null
        return runCatching {
            val tl = JSONObject(body).optJSONObject("toplist")?.optJSONObject("data") ?: return null
            // 榜单名:extInfoList 里找 title
            var name = ""
            tl.optJSONArray("extInfoList")?.let { ext ->
                for (i in 0 until ext.length()) {
                    val e = ext.optJSONObject(i) ?: continue
                    if (e.optString("name") == "title") { name = e.optString("value"); break }
                }
            }
            val info = KtBoard(id = id, name = name, platform = "tx").toSongListInfo()
            val arr = tl.optJSONArray("songInfoList") ?: org.json.JSONArray()
            val songs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mid = o.optString("mid").ifBlank { return@mapNotNull null }
                val singer = o.optJSONArray("singer")?.optJSONObject(0)?.optString("name").orEmpty()
                val albumMid = o.optJSONObject("album")?.optString("mid").orEmpty()
                LxSong(
                    platform = "tx", songmid = mid,
                    name = o.optString("title").ifBlank { o.optString("name") }, artist = singer,
                    durationMs = o.optLong("interval", 0) * 1000,
                    picUrl = if (albumMid.isNotBlank())
                        "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg" else "",
                    albumId = albumMid
                )
            }
            KtSongListDetail(info, songs)
        }.getOrNull()
    }

    // ── kg 小枸(酷狗) ──

    private fun boardsKg(): List<KtBoard> {
        val body = get("http://mobilecdnbj.kugou.com/api/v5/rank/list?version=9108&plat=0" +
            "&showtype=2&parentid=0&apiver=6&area_code=1&withsong=1") ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("rankid").toString()
                KtBoard(
                    id = id, name = o.optString("rankname"),
                    img = o.optString("imgurl").replace("{size}", "400"),
                    playCount = o.optLong("listen_count", 0),
                    desc = o.optString("intro"), platform = "kg"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun detailKg(id: String, page: Int): KtSongListDetail? {
        val body = get(
            "http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=1&plat=0" +
                "&pagesize=100&area_code=1&page=$page&rankid=$id&with_res_tag=0&show_portrait_5=1"
        ) ?: return null
        return runCatching {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val arr = data.optJSONArray("info") ?: return null
            val name = data.optString("rankname").ifBlank { data.optString("name") }
            val info = KtBoard(id = id, name = name, platform = "kg").toSongListInfo()
            val songs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val hash = o.optString("hash").ifBlank { return@mapNotNull null }
                // 歌名/歌手优先结构化字段(落雪同款:songname + authors[].author_name)——
                // 部分榜单 filename 顺序为"歌名 - 歌手",切分不可靠会导致名字颠倒
                val songname = o.optString("songname")
                val authors = o.optJSONArray("authors")
                val artistFromAuthors = if (authors != null) {
                    (0 until authors.length()).mapNotNull { j ->
                        authors.optJSONObject(j)?.optString("author_name")?.takeIf { it.isNotBlank() }
                    }.joinToString("/")
                } else ""
                var title = songname
                var artist = artistFromAuthors
                if (title.isBlank()) {
                    // 回退 filename 切分:酷狗 filename 约定为"歌手 - 歌名"(与 lx 旧版一致)
                    val fn = o.optString("filename")
                    title = fn.substringAfter(" - ", fn)
                    artist = fn.substringBefore(" - ", "")
                }
                // 封面:实测 rank/song 无 img 字段,真实封面在 album_sizable_cover({size}占位);
                // 旧字段 img 仅作兼容回退,均空时留空由 AlbumArtLoader → KugouCoverApi 取真实封面
                val img = o.optString("album_sizable_cover").ifBlank { o.optString("img") }
                    .replace("{size}", "400")
                LxSong(
                    platform = "kg", songmid = hash,
                    name = title, artist = artist,
                    durationMs = o.optLong("duration", 0) * 1000,
                    picUrl = img,
                    albumId = o.optLong("album_id", 0).toString().takeIf { it != "0" }.orEmpty()
                )
            }
            KtSongListDetail(info, songs)
        }.getOrNull()
    }

    // ── kw 小蜗(酷我,仅榜单列表) ──

    private fun boardsKw(): List<KtBoard> {
        val body = get("http://qukudata.kuwo.cn/q.k?op=query&cont=tree&node=2&pn=0&rn=1000&fmt=json&level=2") ?: return emptyList()
        return runCatching {
            val children = JSONObject(body).optJSONArray("child") ?: return emptyList()
            (0 until children.length()).mapNotNull { i ->
                val o = children.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                KtBoard(
                    id = id, name = o.optString("name").ifBlank { o.optString("disname") },
                    img = o.optString("pic"),
                    playCount = o.optString("listen").toLongOrNull() ?: 0,
                    desc = o.optString("info"), platform = "kw"
                )
            }
        }.getOrDefault(emptyList())
    }

    // ── 工具 ──

    private fun KtBoard.toSongListInfo(): KtSongList = KtSongList(
        id = id, name = name, author = "", img = img,
        playCount = playCount, songCount = 0, desc = desc, platform = platform
    )

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120")
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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "post: HTTP ${resp.code} url=$url")
                null
            } else resp.body?.string()
        }
    }.getOrNull()
}
