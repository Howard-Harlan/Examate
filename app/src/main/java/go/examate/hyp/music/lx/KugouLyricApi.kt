package go.examate.hyp.music.lx

import android.util.Base64
import android.util.Log
import go.examate.hyp.music.MusicHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/**
 * 酷狗歌词 API — 落雪音乐(lx-music-mobile)kg/lyric.js 同款实现:
 *  1. lyrics.kugou.com/search 按 歌名+hash+时长 搜候选,取首个(id/accesskey/fmt)
 *  2. lyrics.kugou.com/download 取内容:krc 需解码(base64 → 跳过4字节 → 与16字节密钥异或 → zlib inflate),
 *     lrc 直接 base64
 *  3. KRC 转标准 LRC:[(总毫秒,x)] → [mm:ss.SSS],去掉 <…> 词级时间戳与 [id:]/[language:] 头
 */
object KugouLyricApi {
    private const val TAG = "KugouLyricApi"
    private val client = MusicHttp.client
    private val HEADERS = mapOf(
        "KG-RC" to "1",
        "KG-THash" to "expand_search_manager.cpp:852736169:451",
        "User-Agent" to "KuGou2012-9020-ExpandSearchManager"
    )

    /** 返回标准 LRC 文本;失败/无歌词返回 null */
    fun getLyric(name: String, hash: String, durationMs: Long): String? = runCatching {
        // 1. 搜索候选
        val searchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=${URLEncoder.encode(name, "UTF-8")}&hash=${hash.lowercase()}&timelength=$durationMs&lrctxt=1"
        val searchBody = get(searchUrl) ?: return null
        val candidates = JSONObject(searchBody).optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val first = candidates.optJSONObject(0) ?: return null
        downloadByCandidate(first)
    }.onFailure { Log.w(TAG, "getLyric fail", it) }.getOrNull()

    /**
     * 关键词搜索歌词候选列表(供"暂无歌词 → 搜索歌词"功能)。
     * 不依赖 hash,只用歌名+歌手关键词;返回 (id, accesskey, fmt, name, singer) 候选。
     */
    fun searchCandidates(keyword: String): List<KugouCandidate> = runCatching {
        val searchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=${URLEncoder.encode(keyword, "UTF-8")}&hash=&timelength=0&lrctxt=1"
        val body = get(searchUrl) ?: return emptyList()
        val arr = JSONObject(body).optJSONArray("candidates") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val fmt = if (o.optInt("krctype") == 1 && o.optInt("contenttype") != 1) "krc" else "lrc"
            KugouCandidate(
                id = o.optString("id"),
                accesskey = o.optString("accesskey"),
                fmt = fmt,
                name = o.optString("song"),
                singer = o.optString("singer"),
                durationMs = o.optLong("duration", 0L) * 1000L
            )
        }
    }.onFailure { Log.w(TAG, "searchCandidates fail", it) }.getOrNull() ?: emptyList()

    /** 根据候选下载 LRC 文本(供搜索后选定的歌词导入) */
    fun downloadLyric(c: KugouCandidate): String? = runCatching {
        val dlUrl = "http://lyrics.kugou.com/download?ver=1&client=pc" +
            "&id=${c.id}&accesskey=${c.accesskey}&fmt=${c.fmt}&charset=utf8"
        val body = get(dlUrl) ?: return null
        val jo = JSONObject(body)
        val content = jo.optString("content")
        if (content.isBlank()) return null
        if (jo.optString("fmt") == "krc") {
            val krc = decodeKrc(content) ?: return null
            krcToLrc(krc)
        } else {
            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        }
    }.onFailure { Log.w(TAG, "downloadLyric fail", it) }.getOrNull()

    /** 内部:按候选 JSON 下载(原 getLyric 复用) */
    private fun downloadByCandidate(first: JSONObject): String? {
        val id = first.optString("id")
        val accessKey = first.optString("accesskey")
        val fmt = if (first.optInt("krctype") == 1 && first.optInt("contenttype") != 1) "krc" else "lrc"
        val dlUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=$fmt&charset=utf8"
        val dlBody = get(dlUrl) ?: return null
        val jo = JSONObject(dlBody)
        val content = jo.optString("content")
        if (content.isBlank()) return null
        return if (jo.optString("fmt") == "krc") {
            val krc = decodeKrc(content) ?: return null
            krcToLrc(krc)
        } else {
            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        }
    }

    /** KRC 解码:base64 → 跳过4字节 → 与16字节密钥循环异或 → zlib inflate(落雪 util.decodeLyric 同款) */
    private fun decodeKrc(b64: String): String? = runCatching {
        val key = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
        )
        val buf = Base64.decode(b64, Base64.DEFAULT)
        if (buf.size <= 4) return null
        val data = buf.copyOfRange(4, buf.size)
        for (i in data.indices) data[i] = (data[i].toInt() xor key[i % 16].toInt()).toByte()
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(tmp)
            if (n <= 0) break
            out.write(tmp, 0, n)
        }
        inflater.end()
        out.toString("UTF-8")
    }.getOrNull()

    /** KRC → 标准 LRC:[(总毫秒,x)] → [mm:ss.SSS];去掉词级 <…> 与 [id:]/[language:] 等头行 */
    private fun krcToLrc(krc: String): String {
        val timeRegex = Regex("^\\[(\\d+),\\d+\\](.*)$")
        val sb = StringBuilder()
        for (rawLine in krc.replace("\r", "").split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[id:") || line.startsWith("[language:") ||
                line.startsWith("[offset:") || line.startsWith("[key:")
            ) continue
            val m = timeRegex.find(line) ?: continue
            val ms = m.groupValues[1].toLongOrNull() ?: continue
            val text = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            val totalSec = ms / 1000
            val tag = "%02d:%02d.%03d".format(totalSec / 60, totalSec % 60, ms % 1000)
            sb.append('[').append(tag).append(']').append(text).append('\n')
        }
        return sb.toString()
    }

    private fun get(url: String): String? = runCatching {
        val builder = Request.Builder().url(url)
        HEADERS.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()
}

/** 酷狗歌词搜索候选 */
data class KugouCandidate(
    val id: String,
    val accesskey: String,
    val fmt: String,        // "krc" 或 "lrc"
    val name: String,
    val singer: String,
    val durationMs: Long
)
