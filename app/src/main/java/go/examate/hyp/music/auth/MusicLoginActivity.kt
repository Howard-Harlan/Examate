package go.examate.hyp.music.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import go.examate.hyp.util.ToastUtil
import kotlin.concurrent.thread

/**
 * 音乐平台登录基类 — WebView 加载官方登录页,登录成功后抓取 Cookie 保存(模拟登录态)。
 *
 * 与 B站登录(BiliLoginActivity)同款模式:桌面 UA 兼容性最好;页面完成/URL 变化时
 * 检测 Cookie,命中 [loginSucceeded] 判定即保存,后台跑 [verify](可选)取昵称后结束。
 *
 * 子类只需提供:平台代号 / 登录页 URL / Cookie 抓取域 / 登录成功判定 /(可选)后台验证。
 */
abstract class MusicLoginActivity : ComponentActivity() {

    /** 平台代号(wy/tx/kg/kw/mg) */
    protected abstract val platform: String

    /** 官方登录页 URL */
    protected abstract val loginUrl: String

    /** CookieManager 抓取 Cookie 用的域 URL(如 https://music.163.com) */
    protected abstract val cookieHost: String

    /** 登录成功判定:Cookie 串中需含的关键凭证 */
    protected abstract fun loginSucceeded(cookie: String): Boolean

    /** 后台验证并取昵称;返回昵称(失败返回 null,不影响登录成立) */
    protected open fun verify(cookie: String): String? = null

    protected open val userAgent: String = DESKTOP_UA

    private var captured = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        val web = WebView(this).apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    tryCapture(cm)
                }
                // 登录成功常伴随重定向,URL 变化时也检测一次
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    tryCapture(cm)
                }
            }
        }
        setContentView(web)
        web.loadUrl(loginUrl)
    }

    /** 抓 Cookie:满足平台凭证判定即保存;后台验证昵称,无论成败都完成登录 */
    private fun tryCapture(cm: CookieManager) {
        if (captured) return
        val cookie = cm.getCookie(cookieHost) ?: return
        if (!loginSucceeded(cookie)) return
        captured = true
        MusicAuth.saveCookie(this, platform, cookie)
        thread {
            val nick = runCatching { verify(cookie) }.getOrNull()
            if (!nick.isNullOrBlank()) MusicAuth.saveNick(this, platform, nick)
            runOnUiThread {
                val alias = MusicAuth.aliasOf(platform)
                ToastUtil.show(this, if (!nick.isNullOrBlank()) "$alias 已登录:$nick" else "$alias 登录成功")
                finish()
            }
        }
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
