package go.examate.hyp.music.lx

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import go.examate.hyp.music.MusicHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 酷狗歌曲信息 API — 参照落雪 musicSdk/kg/musicInfo.js(gateway v3 album_audio/audio)。
 *
 * 搜索接口(song_search_v2)部分歌曲不返回 AlbumID,导致封面无法经 get_res_privilege 换取、
 * 歌曲信息残缺。该接口按 hash 补齐完整歌曲信息(专辑 id/名称、audio_id、时长等)。
 */
object KugouSongInfoApi {
    private const val TAG = "KugouSongInfo"
    private val client = MusicHttp.client

    /** 按 hash 取回的歌曲信息(专辑/audio 引用 + 封面模板,供封面换取与信息补齐) */
    data class KgSongInfo(
        val albumId: String,
        val albumName: String,
        val audioId: String,
        val songName: String,
        val authorName: String,
        /** 封面模板(album_info.sizable_cover,含 {size} 占位) */
        val coverTemplate: String
    )

    /** 按 hash 查询歌曲信息;失败/无结果返回 null(落雪同款请求体与头) */
    fun getSongInfo(hash: String): KgSongInfo? = runCatching {
        if (hash.isBlank()) return null
        val body = JSONObject()
            .put("area_code", "1")
            .put("show_privilege", 1)
            .put("show_album_info", "1")
            .put("is_publish", "")
            .put("appid", 1005)
            .put("clientver", 11451)
            .put("mid", "1")
            .put("dfid", "-")
            .put("clienttime", System.currentTimeMillis())
            .put("key", "OIlwieks28dk2k092lksi2UIkp")
            .put("fields", "album_info,author_name,audio_info,ori_audio_name,base,songname,classification")
            .put("data", JSONArray().put(JSONObject().put("hash", hash)))
        val req = Request.Builder()
            .url("http://gateway.kugou.com/v3/album_audio/audio")
            .header("KG-THash", "13a3164")
            .header("KG-RC", "1")
            .header("KG-Fake", "0")
            .header("KG-RF", "00869891")
            .header("User-Agent", "Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi")
            .header("x-router", "kmr.service.kugou.com")
            .header("Content-Type", "application/json;charset=UTF-8")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "getSongInfo: HTTP ${resp.code} hash=$hash")
                return@use null
            }
            val root = JSONObject(resp.body?.string() ?: return@use null)
            // 响应结构 {data: [[item]]}(落雪 .map(s => s[0]).flat() 同款)
            val item = root.optJSONArray("data")?.optJSONArray(0)?.optJSONObject(0) ?: return@use null
            val albumInfo = item.optJSONObject("album_info")
            val audioInfo = item.optJSONObject("audio_info")
            KgSongInfo(
                albumId = albumInfo?.optString("album_id").orEmpty(),
                albumName = albumInfo?.optString("album_name").orEmpty(),
                audioId = audioInfo?.optString("audio_id").orEmpty(),
                songName = item.optString("songname"),
                authorName = item.optString("author_name"),
                coverTemplate = albumInfo?.optString("sizable_cover").orEmpty()
            ).takeIf { it.albumId.isNotBlank() || it.audioId.isNotBlank() || it.coverTemplate.isNotBlank() }
        }
    }.onFailure { Log.w(TAG, "getSongInfo fail hash=$hash", it) }.getOrNull()
}
