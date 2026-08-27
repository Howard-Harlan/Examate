package go.examate.hyp.lepao

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 步道乐跑 QQ扫码登录 — WebView加载QQ OAuth页面(style=qr)
 * 拦截auth://回调获取access_token和openid
 */
class LePaoScanLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val PLATFORM_QQ = "qq"

        const val QQ_APP_ID = "1105660458"

        fun buildQqAuthUrl(): String {
            val ts = System.currentTimeMillis() / 1000
            return "https://openmobile.qq.com/oauth2.0/m_authorize" +
                    "?_wv=3&status_os=12&client_id=$QQ_APP_ID" +
                    "&format=json&switch=1&status_version=32" +
                    "&pf=openmobile_android&sdkp=a&sdkv=null" +
                    "&sign=&time=$ts" +
                    "&scope=get_user_info,get_simple_userinfo,get_user_profile" +
                    "&style=qr" +
                    "&redirect_uri=auth%3A%2F%2Ftauth.qq.com%2F" +
                    "&display=mobile&response_type=token" +
                    "&loginfrom=main&cancel_display=1&_wwv=2"
        }
    }

    private var webView: WebView? = null
    private var webError by mutableStateOf<String?>(null)
    private var progressState by mutableStateOf(0)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ExamateTheme {
                Column(Modifier.fillMaxSize().background(CardBg).statusBarsPadding()) {
                    // 顶栏（参考项目级 WebViewScreen 样式）
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        LiquidGlassIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            onClick = { finish() },
                            modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
                        )
                        Text("QQ扫码登录",
                            color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp)
                        )
                    }
                    // 加载进度条
                    if (progressState in 1..99) {
                        LinearProgressIndicator(
                            progress = { progressState / 100f },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = AccentBlue
                        )
                    }
                    // 内容区
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx -> createWebView(ctx) },
                            modifier = Modifier.fillMaxSize()
                        )
                        webError?.let { err ->
                            Box(Modifier.fillMaxSize().background(CardBg), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(err, color = TextSecondary, fontSize = 14.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Text("重试", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(AccentBlue)
                                            .clickable(onClick = { webError = null; webView?.loadUrl(buildQqAuthUrl()) })
                                            .padding(horizontal = 28.dp, vertical = 10.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: android.content.Context): WebView {
        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
            }
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 12; AOSP Build/SQ1A.220205.002; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/110.0.5481.154 Mobile Safari/537.36"

            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return interceptUrl(url)
                }
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return false
                    return interceptUrl(url)
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        webError = "加载失败(${error?.errorCode ?: -1}) ${error?.description ?: "网络错误"}"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        webError = "页面返回错误 HTTP ${errorResponse?.statusCode ?: -1}"
                    }
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressState = newProgress
                }
            }
            loadUrl(buildQqAuthUrl())
        }
        return webView!!
    }

    private fun interceptUrl(url: String): Boolean {
        if (url.startsWith("auth://")) { handleAuthCallback(url); return true }
        if (url.contains("access_token") && url.contains("openid")) {
            handleAuthCallback(url); return true
        }
        return false
    }

    private fun handleAuthCallback(url: String) {
        var accessToken = ""
        var openid = ""
        for (part in url.split("#", "?", "&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) {
                when (kv[0].trim()) {
                    "access_token" -> accessToken = kv[1]
                    "openid" -> openid = kv[1]
                }
            }
        }
        if (accessToken.isNotEmpty()) {
            lePaoQuickLogin(openid, accessToken)
        } else {
            Toast.makeText(this, "未提取到access_token", Toast.LENGTH_LONG).show()
        }
    }

    private fun lePaoQuickLogin(openid: String, accessToken: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = LePaoApi().quickLogin(openid, accessToken, type = "2")
                withContext(Dispatchers.Main) {
                    if (result.optInt("status") == 1) {
                        val data = result.optJSONObject("data")
                        if (data != null) {
                            LePaoPrefs.save(this@LePaoScanLoginActivity,
                                data.optString("uid"),
                                data.optString("access_token"), LePaoPrefs.DEFAULT_SCHOOL_ID, "")
                            Toast.makeText(this@LePaoScanLoginActivity,
                                "登录成功! UID=${data.optString("uid")}",
                                Toast.LENGTH_SHORT).show()
                            setResult(RESULT_OK); finish()
                        }
                    } else {
                        Toast.makeText(this@LePaoScanLoginActivity,
                            "登录失败: ${result.optString("info")}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LePaoScanLoginActivity,
                        "${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) webView?.goBack() else super.onBackPressed()
    }

    override fun onDestroy() { webView?.destroy(); super.onDestroy() }
}
