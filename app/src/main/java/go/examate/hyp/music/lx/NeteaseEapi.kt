package go.examate.hyp.music.lx

import okhttp3.MediaType.Companion.toMediaType
import go.examate.hyp.music.MusicHttp
import go.examate.hyp.music.MusicUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 eapi 加密请求 — 落雪音乐(lx-music-mobile) wy/utils/crypto.js 同款实现。
 *
 * 网易云新版接口(interface.music.163.com/eapi/batch)请求参数需 AES-ECB 加密:
 *   message = "nobody{urlPath}use{text}md5forencrypt"
 *   digest  = MusicUtil.md5(message)
 *   data    = "{urlPath}-36cd479b6b5-{text}-36cd479b6b5-{digest}"
 *   params  = hex( AES-ECB(data, key="e82ckenh8dichen8") ).toUpperCase()
 * 响应为明文 JSON(抓包证实)。
 */
object NeteaseEapi {
    private const val TAG = "NeteaseEapi"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val EAPI_SUFFIX = "-36cd479b6b5-"
    private val client = MusicHttp.client
    private val formType = "application/x-www-form-urlencoded".toMediaType()

    /** 加密 params(与落雪 eapi() 一致) */
    fun encrypt(urlPath: String, obj: JSONObject): String {
        val text = obj.toString()
        val message = "nobody${urlPath}use${text}md5forencrypt"
        val digest = MusicUtil.md5(message)
        val data = "$urlPath$EAPI_SUFFIX$text$EAPI_SUFFIX$digest"
        return aesEcbHex(data, EAPI_KEY)
    }

    /** 发起 eapi 请求,返回明文 JSONObject;失败返回 null */
    fun request(urlPath: String, obj: JSONObject): JSONObject? {
        val params = encrypt(urlPath, obj)
        val body = "params=${java.net.URLEncoder.encode(params, "UTF-8")}"
        val req = Request.Builder()
            .url("http://interface.music.163.com/eapi/batch")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36")
            .header("Origin", "https://music.163.com")
            .header("Accept", "application/json")
            .post(body.toRequestBody(formType))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: return null)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "request fail: $urlPath", e)
            null
        }
    }

    /** AES-128-ECB(PKCS5) 加密 → hex 大写 */
    private fun aesEcbHex(data: String, key: String): String = try {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        cipher.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.uppercase()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "aes fail", e)
        ""
    }

    // ── linuxapi(歌单详情等) ──

    private const val LINUX_KEY = "rFgB&h#%2?^eDg:Q"

    /** linuxapi 加密:eparams = hex(AES-ECB(json)) 大写(与落雪 linuxapi() 一致) */
    fun linuxApiEncrypt(method: String, url: String, params: JSONObject): String {
        val obj = JSONObject()
        obj.put("method", method)
        obj.put("url", url)
        obj.put("params", params)
        return aesEcbHex(obj.toString(), LINUX_KEY)
    }

    /** 发起 linuxapi 请求(music.163.com/api/linux/forward),返回明文 JSON;失败 null */
    fun linuxApiRequest(method: String, url: String, params: JSONObject): JSONObject? {
        val eparams = linuxApiEncrypt(method, url, params)
        val body = "eparams=${java.net.URLEncoder.encode(eparams, "UTF-8")}"
        val req = Request.Builder()
            .url("https://music.163.com/api/linux/forward")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36")
            .post(body.toRequestBody(formType))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: return null)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "linuxApi fail: $url", e)
            null
        }
    }


}
