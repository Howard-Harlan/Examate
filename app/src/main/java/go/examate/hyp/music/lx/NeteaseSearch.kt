package go.examate.hyp.music.lx

import android.util.Log
import org.json.JSONObject

/** LX 可解析的歌曲(来自平台搜索) */
data class LxSong(
    val platform: String,   // wy/kg/kw/tx/mg/bili
    val songmid: String,    // 平台歌曲 id
    val name: String,
    val artist: String,
    val durationMs: Long,
    val picUrl: String = "",      // 专辑封面 URL(可空,用 albumId 兜底)
    val albumId: String = "",     // 平台专辑 id(封面兜底/coverRef)
    val qualities: List<String> = emptyList()   // 搜索接口给出的可得音质档(如 flac24bit/flac/320k/128k),供列表标注
)

/**
 * 网易云搜索 — 落雪音乐(lx-music-mobile) wy/musicSearch.js 同款 eapi 加密实现。
 *
 * 接口:interface.music.163.com/eapi/batch,params 需 AES-ECB 加密(NeteaseEapi),
 * 请求体 {urlPath: "/api/search/song/list/page", ...},响应明文 JSON:
 *   data.resources[].baseInfo.simpleSongData → name/id/ar[].name/al.name/al.picUrl/dt(ms)
 * 相比老明文 API 更稳定(老 API 正被网易逐步封禁)。
 */
object NeteaseSearch {
    private const val TAG = "NeteaseSearch"

    fun search(keyword: String, limit: Int = 30, page: Int = 1): List<LxSong> {
        val body = JSONObject()
        body.put("keyword", keyword)
        body.put("needCorrect", "1")
        body.put("channel", "typing")
        body.put("offset", (page - 1).coerceAtLeast(0) * limit)
        body.put("scene", "normal")
        body.put("total", true)
        body.put("limit", limit)
        val resp = NeteaseEapi.request("/api/search/song/list/page", body) ?: return emptyList()
        return parse(resp)
    }

    private fun parse(resp: JSONObject): List<LxSong> = runCatching {
        val resources = resp.optJSONObject("data")?.optJSONArray("resources") ?: return emptyList()
        (0 until resources.length()).mapNotNull { i ->
            val item = resources.optJSONObject(i)?.optJSONObject("baseInfo")?.optJSONObject("simpleSongData")
                ?: return@mapNotNull null
            val id = item.optLong("id").toString()
            val name = item.optString("name")
            // 歌手 ar[] → name
            val artist = item.optJSONArray("ar")?.let { arr ->
                (0 until arr.length()).joinToString("/") { arr.optJSONObject(it)?.optString("name").orEmpty() }
            }.orEmpty()
            val album = item.optJSONObject("al")
            val picUrl = album?.optString("picUrl").orEmpty()
            val albumId = album?.optLong("id")?.toString() ?: ""
            val durationMs = item.optLong("dt", 0)
            // 音质档(落雪同款,防御式):privilege.maxBrLevel=hires → flac24bit;maxbr 阈值 → flac/320k/128k
            val qualities = mutableListOf<String>()
            item.optJSONObject("privilege")?.let { priv ->
                if (priv.optString("maxBrLevel") == "hires") qualities.add("flac24bit")
                val maxbr = priv.optLong("maxBr", 0)
                if (maxbr >= 999000) qualities.add("flac")
                if (maxbr >= 320000) qualities.add("320k")
                if (maxbr >= 128000 || maxbr == 0L) qualities.add("128k")
            }
            LxSong("wy", id, name, artist, durationMs, picUrl, albumId, qualities)
        }
    }.getOrDefault(emptyList())
}
