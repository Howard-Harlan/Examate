package go.examate.hyp.music.auth

import go.examate.hyp.music.MusicHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 酷狗登录 — WebView 加载酷狗官网登录页,抓取 .kugou.com Cookie。
 * 登录成功凭证:token(登录令牌);kg_mid/userid 附带。
 * 后台用带登录态的 Cookie 请求验证有效性。
 */
class KugouLoginActivity : MusicLoginActivity() {

    override val platform = "kg"
    override val loginUrl = "https://www.kugou.com/"
    override val cookieHost = "https://www.kugou.com"

    /** 酷狗访客也有 kg_mid,故以 token / userid 非零作为登录判定 */
    override fun loginSucceeded(cookie: String): Boolean {
        val token = MusicAuth.value(cookie, "token")
        if (token.isNotBlank() && token != "0") return true
        val userId = MusicAuth.value(cookie, "userid")
        return userId.isNotBlank() && userId != "0"
    }

    override fun verify(cookie: String): String? {
        val client = MusicHttp.client
        // 用登录态访问酷狗用户信息接口(失败不影响登录成立)
        val req = Request.Builder()
            .url("https://login.user.kugou.com/v3/user/basic?src=1")
            .header("Cookie", cookie)
            .header("User-Agent", DESKTOP_UA)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = org.json.JSONObject(resp.body?.string() ?: "")
                if (json.optInt("error_code") != 0) return null
                json.optJSONObject("data")?.optString("nickname")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
