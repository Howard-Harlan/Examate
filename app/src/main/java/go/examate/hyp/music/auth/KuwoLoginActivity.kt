package go.examate.hyp.music.auth

import go.examate.hyp.music.MusicHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 酷我登录 — WebView 加载酷我官网登录页,抓取 .kuwo.cn Cookie(关键凭证 kw_token)。
 * kw_token 即酷我 Web 端登录令牌,官方接口带上后按登录账号取数。
 */
class KuwoLoginActivity : MusicLoginActivity() {

    override val platform = "kw"
    override val loginUrl = "https://www.kuwo.cn/"
    override val cookieHost = "https://www.kuwo.cn"

    override fun loginSucceeded(cookie: String): Boolean =
        MusicAuth.value(cookie, "kw_token").isNotBlank()

    override fun verify(cookie: String): String? {
        val client = MusicHttp.client
        val token = MusicAuth.value(cookie, "kw_token")
        val req = Request.Builder()
            .url("https://www.kuwo.cn/api/www/user/userInfo?httpsStatus=1")
            .header("Cookie", cookie)
            .header("csrf", token)
            .header("Referer", "https://www.kuwo.cn/")
            .header("User-Agent", DESKTOP_UA)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "")
                if (json.optInt("code") != 200) return null
                json.optJSONObject("data")?.optString("uname")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
