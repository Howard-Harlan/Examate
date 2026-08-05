package go.examate.hyp.music.bili

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
 * B站登录 — WebView 加载标准登录页,登录成功后从 CookieManager 抓 Cookie(含 SESSDATA),
 * 再调 nav 接口验证 token(取到用户名才算成功)。零第三方依赖。
 */
class BiliLoginActivity : ComponentActivity() {

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
            // 桌面 UA:标准登录页兼容性最好,登录后重定向到 www.bilibili.com
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
        web.loadUrl("https://passport.bilibili.com/login")
    }

    /** 抓 Cookie:含 SESSDATA 即视为登录,保存后后台调 nav 验证 token 并取用户名 */
    private fun tryCapture(cm: CookieManager) {
        if (captured) return
        val cookie = cm.getCookie("https://www.bilibili.com")
            ?: cm.getCookie(".bilibili.com") ?: return
        if (!cookie.contains("SESSDATA")) return
        captured = true
        BiliAuth.saveCookie(this, cookie)
        val uid = BiliAuth.value(cookie, "DedeUserID")
        BiliAuth.saveUser(this, uid, "B站用户")
        // 后台验证 token(nav 取真实用户名);无论成败都完成登录
        thread {
            val name = runCatching { BiliClient(this).fetchUserName() }.getOrNull()
            if (!name.isNullOrBlank()) BiliAuth.saveUser(this, uid, name)
            runOnUiThread {
                ToastUtil.show(this, if (!name.isNullOrBlank()) "已登录:$name" else "B站登录成功")
                finish()
            }
        }
    }
}
