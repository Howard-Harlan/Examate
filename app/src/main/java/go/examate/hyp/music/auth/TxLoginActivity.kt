package go.examate.hyp.music.auth

/**
 * QQ音乐登录 — WebView 加载 y.qq.com,用户经页面右上角/QQ互联登录,
 * 抓取 .y.qq.com Cookie(关键凭证 qm_keyst,uin 附带)。
 * qm_keyst 即 QQ 音乐播放鉴权密钥,官方 vkey 接口带上后可取登录账号音质。
 */
class TxLoginActivity : MusicLoginActivity() {

    override val platform = "tx"
    override val loginUrl = "https://y.qq.com/"
    override val cookieHost = "https://y.qq.com"

    override fun loginSucceeded(cookie: String): Boolean = cookie.contains("qm_keyst=")

    /** QQ 无轻量免签名的资料接口,昵称取 Cookie uin(o 前缀去掉)展示 */
    override fun verify(cookie: String): String? {
        val uin = MusicAuth.value(cookie, "uin").removePrefix("o").trim()
        return if (uin.isNotBlank()) "QQ用户$uin" else null
    }
}
