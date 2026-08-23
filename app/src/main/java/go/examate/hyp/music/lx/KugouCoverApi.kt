package go.examate.hyp.music.lx

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 酷狗真实封面 API — 参照落雪 kg/pic.js。
 *
 * 酷狗搜索接口返回的 img 是"替代/占位封面",真实专辑封面需播放时
 * POST `media.store.kugou.com/v1/get_res_privilege` 换取:
 *   resource: [{ album_id, hash, id, name, type:"audio" }] (不能带 album_audio_id,会参数校验失败)
 *   → data[0].info.image = "http://imge.kugou.com/stdmusic/{size}/20250217/xxx.jpg"
 *   → imgsize = [480,400,240,...],真实封面 = image.replace("{size}", imgsize[0])
 *
 * 注意:media.store.kugou.com SSL 证书 hostname 不匹配,需信任所有证书的 OkHttp。
 */
object KugouCoverApi {
    private const val TAG = "KugouCover"
    private const val CACHE_TTL = 24L * 3600_000   // 封面 URL 缓存 24h(列表多曲目避免重复请求)
    private val memCache = android.util.LruCache<String, Pair<String, Long>>(500)

    private val httpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { hostname, _ -> hostname == "media.store.kugou.com" }
            .build()
    }

    /**
     * 换取酷狗真实封面 URL。
     * @param albumId 专辑 id(搜索返回 AlbumID/album_id)
     * @param hash    歌曲 hash(搜索返回 FileHash/hash)
     * @param name    歌曲名(可选,仅作请求体展示用)
     * @return 真实封面 URL(imge.kugou.com/stdmusic/480/...);失败返回 null
     */
    fun getRealCoverUrl(albumId: String, hash: String, name: String = ""): String? {
        if (hash.isBlank()) return null
        // 缓存命中(未过期)直接返回
        val now = System.currentTimeMillis()
        memCache.get(hash)?.let { (u, ts) -> if (now - ts < CACHE_TTL) return u else memCache.remove(hash) }
        val url = fetchRealCoverUrl(albumId, hash, name)
        if (url != null) memCache.put(hash, url to now)
        return url
    }

    private fun fetchRealCoverUrl(albumId: String, hash: String, name: String): String? {
        return runCatching {
            val resource = JSONObject()
                .put("album_id", albumId.toLongOrNull() ?: 0)
                .put("hash", hash)
                .put("id", 0)
                .put("name", name.ifBlank { "audio.mp3" })
                .put("type", "audio")
            val body = JSONObject()
                .put("appid", 1001)
                .put("area_code", "1")
                .put("behavior", "play")
                .put("clientver", "9020")
                .put("need_hash_offset", 1)
                .put("relate", 1)
                .put("resource", org.json.JSONArray().put(resource))
                .put("token", "")
                .put("userid", 2626431536)
                .put("vip", 1)
            val req = Request.Builder()
                .url("https://media.store.kugou.com/v1/get_res_privilege")
                .header("KG-RC", "1")
                .header("KG-THash", "expand_search_manager.cpp:852736169:451")
                .header("User-Agent", "KuGou2012-9020-ExpandSearchManager")
                .header("Content-Type", "application/json;charset=UTF-8")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getRealCoverUrl: HTTP ${resp.code} hash=$hash")
                    return@runCatching null
                }
                val d = JSONObject(resp.body?.string() ?: return@runCatching null)
                if (d.optInt("error_code") != 0) {
                    Log.w(TAG, "getRealCoverUrl: error_code=${d.optInt("error_code")} hash=$hash")
                    return@runCatching null
                }
                val info = d.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("info")
                    ?: return@runCatching null
                val image = info.optString("image")
                if (image.isBlank()) return@runCatching null
                val imgsize = info.optJSONArray("imgsize")
                val size = imgsize?.optString(0) ?: "480"
                val url = image.replace("{size}", size)
                if (!url.startsWith("http")) return@runCatching null
                Log.d(TAG, "getRealCoverUrl OK: $url")
                url
            }
        }.getOrNull()
    }
}
