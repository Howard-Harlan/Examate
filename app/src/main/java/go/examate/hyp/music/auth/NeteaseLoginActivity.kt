package go.examate.hyp.music.auth

import go.examate.hyp.music.MusicHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 网易云登录 — WebView 加载官方登录页,抓取 .music.163.com Cookie(关键凭证 MUSIC_U)。
 * 后台调 nuser/account 验证登录态并取昵称。
 */
class NeteaseLoginActivity : MusicLoginActivity() {

    override val platform = "wy"
    override val loginUrl = "https://music.163.com/#/login"
    override val cookieHost = "https://music.163.com"

    override fun loginSucceeded(cookie: String): Boolean = cookie.contains("MUSIC_U=")

    override fun verify(cookie: String): String? {
        val client = MusicHttp.client
        val req = Request.Builder()
            .url("https://music.163.com/api/nuser/account/get")
            .header("Cookie", "$cookie; os=pc")
            .header("Referer", "https://music.163.com")
            .header("User-Agent", DESKTOP_UA)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "")
                if (json.optInt("code") != 200) return null
                json.optJSONObject("profile")?.optString("nickname")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
