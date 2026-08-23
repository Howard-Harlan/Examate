package go.examate.hyp.music

import android.util.Log
import go.examate.hyp.music.lx.LxSong
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 歌单信息(多平台: kw/kg/tx) */
data class KtSongList(
    val id: String,
    val name: String,
    val author: String,
    val img: String,
    val playCount: Long = 0,
    val songCount: Long = 0,
    val desc: String = "",
    val platform: String = "kw"
)

/** 歌单详情(信息 + 歌曲列表) */
data class KtSongListDetail(
    val info: KtSongList,
    val songs: List<LxSong>
)

/**
 * 多平台歌单 API(参照落雪 musicSdk 各平台 songList.js,接口均已实测):
 *  - kw 小蜗(酷我): 推荐 getTagPlayList / 搜索 r.s?ft=playlist / 详情 nplserver pl.svc
 *  - kg 小枸(酷狗): 搜索 msearchretry api/v3/search/special /
 *    详情 special/single HTML 抓 global.data → gateway album_audio/audio 补全歌曲信息(落雪同款)
 *  - tx 小秋(QQ):  搜索 client_music_search_songlist / 详情 fcg_ucc_getcdinfo_byids
 *  - mg 小蜜(咪咕): 搜索 jadeite searchAll(songlist)/ 详情 MIGUM3.0 playlist(歌曲+信息)
 */
object SongListApi {
    private const val TAG = "SongListApi"
    private val client = MusicHttp.client

    /** 歌单搜索(分平台) */
    fun search(platform: String, keyword: String, page: Int = 1, limit: Int = 20): List<KtSongList> =
        when (platform) {
            "kw" -> searchKw(keyword, page, limit)
            "kg" -> searchKg(keyword, page, limit)
            "tx" -> searchTx(keyword, page, limit)
            "wy" -> searchWy(keyword, page, limit)
            "mg" -> searchMg(keyword, page, limit)
            else -> emptyList()
        }

    /** 歌单聚合搜索(kw/kg/tx/wy/mg,单个源失败不阻断) */
    fun searchAll(keyword: String, page: Int = 1): List<KtSongList> = runBlocking(Dispatchers.IO) {
        listOf("kw", "kg", "tx", "wy", "mg").map { p ->
            async {
                runCatching { search(p, keyword, page) }
                    .onFailure { Log.w(TAG, "searchAll $p fail", it) }
                    .getOrDefault(emptyList())
            }
        }.flatMap { it.await() }
    }

    /** 歌单详情(分平台);kg 经 HTML 抓取 + gateway 补全,mg 经 MIGUM3.0 接口 */
    fun detail(platform: String, id: String, page: Int = 1): KtSongListDetail? = when (platform) {
        "kw" -> detailKw(id, page)
        "kg" -> detailKg(id)
        "tx" -> detailTx(id)
        "wy" -> detailWy(id)
        "mg" -> detailMg(id, page)
        else -> null
    }

    /** 详情页分页大小:kw 歌单 100 / kg 榜单 100 / mg 歌单 30(MIGUM3.0 pageSize) */
    fun pageSizeFor(platform: String, isBoard: Boolean): Int =
        if (!isBoard && platform == "mg") 30 else 100

    /**
     * 带缓存的歌单详情(24h):歌单内容稳定,避免每次进入详情页重复请求。
     * 榜单详情同样经此入口(缓存键含 isBoard 区分)。
     */
    fun detailCached(
        context: android.content.Context,
        platform: String,
        id: String,
        isBoard: Boolean = false,
        detailFetcher: () -> KtSongListDetail? = { detail(platform, id) }
    ): KtSongListDetail? {
        val key = "detail_${if (isBoard) "board" else "sl"}_${platform}_$id"
        MusicDataCache.get(context, key, 24L * 3600_000)?.let { cached ->
            fromDetailJson(cached)?.let { return it }
        }
        val d = detailFetcher() ?: return null
        if (d.songs.isNotEmpty()) MusicDataCache.put(context, key, toDetailJson(d))
        return d
    }

    /** KtSongListDetail → JSON(缓存序列化) */
    fun toDetailJson(d: KtSongListDetail): String {
        val root = JSONObject()
        root.put("info", d.info.toJson())
        val arr = org.json.JSONArray()
        d.songs.forEach { s ->
            arr.put(JSONObject().apply {
                put("platform", s.platform); put("songmid", s.songmid)
                put("name", s.name); put("artist", s.artist)
                put("durationMs", s.durationMs); put("picUrl", s.picUrl); put("albumId", s.albumId)
            })
        }
        root.put("songs", arr)
        return root.toString()
    }

    /** JSON → KtSongListDetail(缓存反序列化) */
    fun fromDetailJson(json: String): KtSongListDetail? = runCatching {
        val root = JSONObject(json)
        val info = root.optJSONObject("info")?.toSongList() ?: return null
        val arr = root.optJSONArray("songs") ?: return null
        val songs = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            go.examate.hyp.music.lx.LxSong(
                platform = o.optString("platform"), songmid = o.optString("songmid"),
                name = o.optString("name"), artist = o.optString("artist"),
                durationMs = o.optLong("durationMs"), picUrl = o.optString("picUrl"),
                albumId = o.optString("albumId")
            )
        }
        KtSongListDetail(info, songs)
    }.getOrNull()

    /** 推荐歌单(kw 分类歌单,id=173 热门) */
    fun recommend(page: Int = 1, tagId: String = "173", limit: Int = 20): List<KtSongList> {
        val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList" +
            "?loginUid=0&loginSid=0&appUid=76039576&pn=$page&id=$tagId&rn=$limit"
        val body = get(url) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                KtSongList(
                    id = id, name = o.optString("name"), author = o.optString("uname"),
                    img = o.optString("img"),
                    playCount = o.optString("favorcnt").toLongOrNull() ?: 0,
                    songCount = o.optString("total").toLongOrNull() ?: 0,
                    platform = "kw"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 带缓存的推荐歌单(首页用,参照落雪列表缓存):
     * 缓存 1 小时内直接返回,不重复请求;超时则刷新并覆盖缓存。
     */
    fun recommendCached(context: android.content.Context, maxAgeMs: Long = 3600_000L): List<KtSongList> {
        val prefs = context.getSharedPreferences("songlist_cache", android.content.Context.MODE_PRIVATE)
        val cachedJson = prefs.getString("recommend", null)
        val cachedAt = prefs.getLong("recommend_at", 0L)
        if (cachedJson != null && System.currentTimeMillis() - cachedAt < maxAgeMs) {
            return runCatching {
                val arr = org.json.JSONArray(cachedJson)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    o.toSongList()
                }
            }.getOrDefault(emptyList())
        }
        val list = recommend()
        if (list.isNotEmpty()) {
            val arr = org.json.JSONArray()
            list.forEach { arr.put(it.toJson()) }
            prefs.edit()
                .putString("recommend", arr.toString())
                .putLong("recommend_at", System.currentTimeMillis())
                .apply()
        }
        return list
    }

    /**
     * 换一换:随机换分类/页码拉取新推荐歌单(不读缓存,直接请求)。
     */
    fun recommendFresh(context: android.content.Context): List<KtSongList> {
        val tagPool = listOf("173", "184", "181", "190", "327", "151")
        val tagId = tagPool.random()
        val page = (1..3).random()
        val list = recommend(page = page, tagId = tagId, limit = 20)
        if (list.isNotEmpty()) {
            val prefs = context.getSharedPreferences("songlist_cache", android.content.Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            list.forEach { arr.put(it.toJson()) }
            prefs.edit()
                .putString("recommend", arr.toString())
                .putLong("recommend_at", System.currentTimeMillis())
                .apply()
        }
        return list
    }

    private fun org.json.JSONObject.toSongList(): KtSongList = KtSongList(
        id = optString("id"), name = optString("name"), author = optString("author"),
        img = optString("img"), playCount = optLong("playCount"),
        songCount = optLong("songCount"), desc = optString("desc"), platform = optString("platform", "kw")
    )

    private fun KtSongList.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id); put("name", name); put("author", author); put("img", img)
        put("playCount", playCount); put("songCount", songCount)
        put("desc", desc); put("platform", platform)
    }

    // ── kw 小蜗(酷我) ──

    private fun searchKw(keyword: String, page: Int, limit: Int): List<KtSongList> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?all=$kw&pn=${page - 1}&rn=$limit&rformat=json" +
            "&encoding=utf8&ver=mbox&vipver=MUSIC_8.7.7.0_BCS37&plat=pc&devid=28156413" +
            "&ft=playlist&pay=0&needliveshow=0"
        val body = get(url) ?: return emptyList()
        return runCatching {
            val fixed = body.replace("'", "\"")
            val arr = JSONObject(fixed).optJSONArray("abslist") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("playlistid").ifBlank { return@mapNotNull null }
                KtSongList(
                    id = id, name = o.optString("name"), author = o.optString("nickname"),
                    img = o.optString("pic").ifBlank { o.optString("hts_pic") },
                    playCount = o.optString("playcnt").toLongOrNull() ?: 0,
                    songCount = o.optString("songnum").toLongOrNull() ?: 0,
                    desc = o.optString("intro"), platform = "kw"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun detailKw(id: String, page: Int): KtSongListDetail? {
        val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$id&pn=${page - 1}" +
            "&rn=100&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
        val body = get(url) ?: return null
        return runCatching {
            val root = JSONObject(body)
            if (root.optString("result") != "ok") return null
            val info = KtSongList(
                id = id, name = root.optString("title"), author = root.optString("uname"),
                img = root.optString("pic"),
                playCount = root.optString("playnum").toLongOrNull() ?: 0,
                songCount = root.optLong("total"), desc = root.optString("info"), platform = "kw"
            )
            val arr = root.optJSONArray("musiclist") ?: org.json.JSONArray()
            val songs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val songId = o.optString("id").ifBlank { return@mapNotNull null }
                LxSong(
                    platform = "kw", songmid = songId,
                    name = o.optString("name").ifBlank { o.optString("displaysongname") },
                    artist = o.optString("artist").ifBlank { o.optString("displayartistname") },
                    durationMs = o.optLong("duration", 0) * 1000,
                    picUrl = o.optString("musicPic")
                )
            }
            KtSongListDetail(info, songs)
        }.getOrNull()
    }

    // ── kg 小枸(酷狗) ──

    private fun searchKg(keyword: String, page: Int, limit: Int): List<KtSongList> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://msearchretry.kugou.com/api/v3/search/special?keyword=$kw" +
            "&page=$page&pagesize=$limit&showtype=10&filter=0&version=7910&sver=2"
        val body = get(url) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("specialid").ifBlank { return@mapNotNull null }
                KtSongList(
                    id = id, name = o.optString("specialname"), author = o.optString("nickname"),
                    img = o.optString("imgurl").replace("{size}", "400"),
                    playCount = o.optLong("playcount", 0),
                    songCount = o.optLong("songcount", 0),
                    desc = o.optString("intro"), platform = "kg"
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 酷狗歌单详情 — 落雪 getListDetailBySpecialId 同款:
     *  1. GET yueku/v9/special/single/{id}-5-9999.html
     *  2. 正则抓 global.data = [{hash...}] 歌曲 hash 列表 + 歌单名/封面
     *  3. hash 分批 POST gateway.kugou.com/v2/album_audio/audio 补全歌曲信息
     */
    private fun detailKg(id: String): KtSongListDetail? {
        val html = get(
            "http://www2.kugou.kugou.com/yueku/v9/special/single/$id-5-9999.html",
            headers = mapOf("Referer" to "http://www2.kugou.kugou.com/")
        ) ?: return null
        val listMatch = Regex("global\\.data = (\\[[\\s\\S]+?\\]);").find(html) ?: return null
        val rawArr = runCatching { JSONArray(listMatch.groupValues[1]) }.getOrNull() ?: return null
        val hashes = mutableListOf<String>()
        for (i in 0 until rawArr.length()) {
            rawArr.optJSONObject(i)?.optString("hash")?.takeIf { it.isNotBlank() }?.let(hashes::add)
        }
        if (hashes.isEmpty()) return null
        // 歌单名/封面(global = { ... name: ".." ... pic: ".." };)
        val infoMatch = Regex("global = \\{[\\s\\S]+?name: \"(.+?)\"[\\s\\S]+?pic: \"(.+?)\"[\\s\\S]+?\\};").find(html)
        val songs = kgMusicInfos(hashes)
        if (songs.isEmpty()) return null
        val info = KtSongList(
            id = id,
            name = cleanKg(infoMatch?.groupValues?.getOrNull(1).orEmpty()).ifBlank { "酷狗歌单" },
            author = "", img = infoMatch?.groupValues?.getOrNull(2).orEmpty(),
            playCount = 0, songCount = songs.size.toLong(), platform = "kg"
        )
        return KtSongListDetail(info, songs)
    }

    /** hash 列表 → 歌曲信息(gateway album_audio/audio,每批 100 首,落雪 createTask 同款参数) */
    private fun kgMusicInfos(hashes: List<String>): List<LxSong> {
        val out = mutableListOf<LxSong>()
        val seen = HashSet<String>()
        hashes.distinct().chunked(100).forEach { batch ->
            val data = JSONArray()
            batch.forEach { data.put(JSONObject().put("hash", it)) }
            val body = JSONObject().apply {
                put("area_code", "1"); put("show_privilege", 1); put("show_album_info", "1")
                put("is_publish", ""); put("appid", 1005); put("clientver", 11451)
                put("mid", "1"); put("dfid", "-"); put("clienttime", System.currentTimeMillis())
                put("key", "OIlwieks28dk2k092lksi2UIkp")
                put("fields", "album_info,author_name,audio_info,ori_audio_name,base,songname")
                put("data", data)
            }
            val resp = postJson(
                "http://gateway.kugou.com/v2/album_audio/audio", body.toString(),
                headers = mapOf(
                    "KG-THash" to "13a3164", "KG-RC" to "1", "KG-Fake" to "0", "KG-RF" to "00869891",
                    "User-Agent" to "Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi",
                    "x-router" to "kmr.service.kugou.com"
                )
            ) ?: return@forEach
            try {
                // 响应 data 为 [[song], [song]...] 结构(每个内层数组一首歌)
                val arr = JSONObject(resp).optJSONArray("data") ?: return@forEach
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONArray(i)?.optJSONObject(0) ?: arr.optJSONObject(i) ?: continue
                    val audio = o.optJSONObject("audio_info") ?: continue
                    val hash = audio.optString("hash")
                    if (hash.isBlank() || !seen.add(hash)) continue
                    val album = o.optJSONObject("album_info")
                    out.add(
                        LxSong(
                            platform = "kg", songmid = hash,
                            name = cleanKg(o.optString("songname")),
                            artist = cleanKg(o.optString("author_name")),
                            durationMs = audio.optLong("timelength", 0),
                            // 封面留空:AlbumArtLoader → KugouCoverApi(get_res_privilege)取真实封面
                            picUrl = "",
                            albumId = album?.optLong("album_id", 0)?.toString()?.takeIf { it != "0" }.orEmpty()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "kgMusicInfos parse fail", e)
            }
        }
        return out
    }

    /** 酷狗歌单 HTML 中的名称可能含 HTML 转义 */
    private fun cleanKg(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    // ── tx 小秋(QQ) ──

    private fun searchTx(keyword: String, page: Int, limit: Int): List<KtSongList> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist?page_no=${page - 1}" +
            "&num_per_page=$limit&format=json&query=$kw&remoteplace=txt.yqq.playlist&inCharset=utf8&outCharset=utf-8"
        val body = get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)",
            "Referer" to "http://y.qq.com/portal/search.html"
        )) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("dissid").ifBlank { return@mapNotNull null }
                KtSongList(
                    id = id, name = o.optString("dissname"),
                    author = o.optJSONObject("creator")?.optString("name").orEmpty(),
                    img = o.optString("imgurl"),
                    playCount = o.optLong("listennum", 0),
                    songCount = o.optLong("song_count", 0),
                    desc = o.optString("introduction"), platform = "tx"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun detailTx(id: String): KtSongListDetail? {
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1" +
            "&utf8=1&onlysong=0&new_format=1&disstid=$id&loginUin=0&hostUin=0&format=json" +
            "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        val body = get(url, headers = mapOf("Referer" to "https://y.qq.com/")) ?: return null
        return runCatching {
            val cd = JSONObject(body).optJSONArray("cdlist")?.optJSONObject(0) ?: return null
            val info = KtSongList(
                id = id, name = cd.optString("dissname"),
                author = cd.optJSONObject("creator")?.optString("name").orEmpty(),
                img = cd.optString("dir_pic_url_300x300").ifBlank { cd.optString("logo") },
                playCount = cd.optLong("listennum", 0),
                songCount = cd.optLong("songnum", 0),
                desc = cd.optString("diss_desc"), platform = "tx"
            )
            val arr = cd.optJSONArray("songlist") ?: org.json.JSONArray()
            val songs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mid = o.optString("mid").ifBlank { return@mapNotNull null }
                val singer = o.optJSONArray("singer")?.optJSONObject(0)?.optString("name").orEmpty()
                val albumMid = o.optJSONObject("album")?.optString("mid").orEmpty()
                LxSong(
                    platform = "tx", songmid = mid,
                    name = o.optString("name"), artist = singer,
                    durationMs = o.optLong("interval", 0) * 1000,
                    picUrl = if (albumMid.isNotBlank())
                        "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg" else "",
                    albumId = albumMid
                )
            }
            KtSongListDetail(info, songs)
        }.getOrNull()
    }

    // ── wy 小芸(网易云,eapi 加密,落雪同款) ──

    private fun searchWy(keyword: String, page: Int, limit: Int): List<KtSongList> {
        val body = org.json.JSONObject()
        body.put("s", keyword)
        body.put("type", 1000)   // 1000=歌单
        body.put("limit", limit)
        body.put("total", page == 1)
        body.put("offset", limit * (page - 1))
        val resp = go.examate.hyp.music.lx.NeteaseEapi.request("/api/cloudsearch/pc", body) ?: return emptyList()
        return runCatching {
            val arr = resp.optJSONObject("result")?.optJSONArray("playlists") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id").toString()
                if (id == "0") return@mapNotNull null
                KtSongList(
                    id = id, name = o.optString("name"),
                    author = o.optJSONObject("creator")?.optString("nickname").orEmpty(),
                    img = o.optString("coverImgUrl"),
                    playCount = o.optLong("playCount", 0),
                    songCount = o.optLong("trackCount", 0),
                    desc = o.optString("description"), platform = "wy"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun detailWy(id: String): KtSongListDetail? {
        val params = org.json.JSONObject()
        params.put("id", id.toLongOrNull() ?: return null)
        params.put("n", 100000)
        params.put("s", 8)
        val resp = go.examate.hyp.music.lx.NeteaseEapi.linuxApiRequest(
            "POST", "https://music.163.com/api/v3/playlist/detail", params
        ) ?: return null
        return runCatching {
            val pl = resp.optJSONObject("playlist") ?: return null
            val info = KtSongList(
                id = id, name = pl.optString("name"),
                author = pl.optJSONObject("creator")?.optString("nickname").orEmpty(),
                img = pl.optString("coverImgUrl"),
                playCount = pl.optLong("playCount", 0),
                songCount = pl.optLong("trackCount", 0),
                desc = pl.optString("description"), platform = "wy"
            )
            val arr = pl.optJSONArray("tracks") ?: org.json.JSONArray()
            val songs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val songId = o.optLong("id").toString()
                val artist = o.optJSONArray("ar")?.let { a ->
                    (0 until a.length()).joinToString("/") { a.optJSONObject(it)?.optString("name").orEmpty() }
                }.orEmpty()
                LxSong(
                    platform = "wy", songmid = songId,
                    name = o.optString("name"), artist = artist,
                    durationMs = o.optLong("dt", 0),
                    picUrl = o.optJSONObject("al")?.optString("picUrl").orEmpty(),
                    albumId = o.optJSONObject("al")?.optLong("id")?.toString() ?: ""
                )
            }
            KtSongListDetail(info, songs)
        }.getOrNull()
    }

    // ── mg 小蜜(咪咕,落雪 mg/songList.js 同款) ──

    private const val MG_DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20"
    private const val MG_SIGN_MD5 = "6cdc72a439cef99a3418d2a78aa28c73"
    private const val MG_APP_KEY = "yyapp2d16148780a1dcc7408e06336b98cfd50"
    private val mgHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1",
        "Referer" to "https://m.music.migu.cn/"
    )

    /** 咪咕歌单搜索 — jadeite searchAll(searchSwitch 仅开 songlist),签名与单曲搜索同款 */
    private fun searchMg(keyword: String, page: Int, limit: Int): List<KtSongList> {
        val time = System.currentTimeMillis().toString()
        val sign = MusicUtil.md5(keyword + MG_SIGN_MD5 + MG_APP_KEY + MG_DEVICE_ID + time)
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll" +
            "?isCorrect=1&isCopyright=1" +
            "&searchSwitch=%7B%22song%22%3A0%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A0%2C%22mvSong%22%3A0%2C%22bestShow%22%3A0%2C%22songlist%22%3A1%2C%22lyricSong%22%3A0%7D" +
            "&pageSize=$limit&text=${URLEncoder.encode(keyword, "UTF-8")}&pageNo=$page&sort=0&sid=USS"
        val body = get(url, headers = mapOf(
            "uiVersion" to "A_music_3.6.1",
            "deviceId" to MG_DEVICE_ID,
            "timestamp" to time,
            "sign" to sign,
            "channel" to "0146921",
            "User-Agent" to "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) " +
                "AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"
        )) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("songListResultData")
                ?.optJSONArray("result") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                KtSongList(
                    id = id, name = o.optString("name"), author = o.optString("userName"),
                    img = o.optString("musicListPicUrl"),
                    playCount = o.optString("playNum").toLongOrNull() ?: 0,
                    songCount = o.optString("musicNum").toLongOrNull() ?: 0,
                    platform = "mg"
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 咪咕歌单详情(支持分页 pageNo,pageSize=30)— MIGUM3.0 playlist/song/v2.0 */
    private fun detailMg(id: String, page: Int): KtSongListDetail? {
        val songsBody = get(
            "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0" +
                "?pageNo=$page&pageSize=30&playlistId=$id", mgHeaders
        ) ?: return null
        val songsRoot = runCatching { JSONObject(songsBody) }.getOrNull() ?: return null
        if (songsRoot.optString("code") != "000000") return null
        val arr = songsRoot.optJSONObject("data")?.optJSONArray("songList") ?: JSONArray()
        val seen = HashSet<String>()
        val songs = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // 播放用 copyrightId(与 MiguSearch 的 songmid 语义一致)
            val copyrightId = o.optString("copyrightId")
            val key = copyrightId.ifBlank { o.optString("songId") }
            if (key.isBlank() || !seen.add(key)) return@mapNotNull null
            val singer = o.optJSONArray("singerList")?.let { sa ->
                (0 until sa.length()).joinToString("/") { sa.optJSONObject(it)?.optString("name").orEmpty() }
            }.orEmpty()
            var pic = o.optString("img3").ifBlank { o.optString("img2").ifBlank { o.optString("img1") } }
            if (pic.isNotBlank() && !pic.startsWith("http")) pic = "http://d.musicapp.migu.cn$pic"
            LxSong(
                platform = "mg", songmid = copyrightId.ifBlank { o.optString("songId") },
                name = o.optString("songName"), artist = singer,
                durationMs = o.optLong("duration", 0) * 1000,
                picUrl = pic, albumId = copyrightId
            )
        }
        if (songs.isEmpty() && page > 1) return null
        val info = mgPlaylistInfo(id) ?: KtSongList(
            id = id, name = "咪咕歌单", author = "", img = "", platform = "mg"
        )
        return KtSongListDetail(info, songs)
    }

    /** 咪咕歌单元信息(MIGUM3.0 resource/playlist/v2.0) */
    private fun mgPlaylistInfo(id: String): KtSongList? {
        val body = get("https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0?playlistId=$id", mgHeaders)
            ?: return null
        return runCatching {
            val root = JSONObject(body)
            if (root.optString("code") != "000000") return null
            val d = root.optJSONObject("data") ?: return null
            KtSongList(
                id = id, name = d.optString("title"), author = d.optString("ownerName"),
                img = d.optJSONObject("imgItem")?.optString("img").orEmpty(),
                playCount = d.optJSONObject("opNumItem")?.optLong("playNum") ?: 0,
                songCount = 0, desc = d.optString("summary"), platform = "mg"
            )
        }.getOrNull()
    }

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

    /** JSON POST(酷狗 gateway 等) */
    private fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "postJson: HTTP ${resp.code} url=$url")
                null
            } else resp.body?.string()
        }
    }.getOrNull()


}
