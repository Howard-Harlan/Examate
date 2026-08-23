package go.examate.hyp.music.auth

/**
 * 咪咕登录 — WebView 加载咪咕音乐 Web 端,抓取 .migu.cn Cookie。
 * 咪咕登录凭证经 passport 下发(mg_ss / uid 类字段),以 uid 非空作为登录判定;
 * 官方播放接口带上完整 Cookie 即可按登录账号取数。
 */
class MiguLoginActivity : MusicLoginActivity() {

    override val platform = "mg"
    override val loginUrl = "https://music.migu.cn/v3"
    override val cookieHost = "https://music.migu.cn"

    override fun loginSucceeded(cookie: String): Boolean {
        // 咪咕登录态字段(任一命中即视为登录):uid / mg_ss / passport
        val uid = MusicAuth.value(cookie, "uid")
        if (uid.isNotBlank() && uid != "0") return true
        if (MusicAuth.value(cookie, "mg_ss").isNotBlank()) return true
        return cookie.contains("passport")
    }

    /** 咪咕无轻量免签名资料接口,仅保存登录态,昵称留空由 UI 展示默认称呼 */
    override fun verify(cookie: String): String? = null
}
