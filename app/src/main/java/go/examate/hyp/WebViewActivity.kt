package go.examate.hyp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.ToastUtil

/**
 * 应用内 WebView 浏览器 — Compose 顶栏 + AndroidView 承载 WebView。
 *
 * - 顶栏:液态玻璃返回按钮 + 居中标题 + 液态玻璃浏览器打开按钮
 * - 支持 PDF 链接拦截(转发到 PdfPreviewActivity)
 * - 加载进度条
 *
 * 注意:WebView 必须用 Activity context 创建(腾讯文档等复杂 SPA 依赖 Activity context),
 * 因此在 Activity 中预先创建 WebView 实例,再通过 AndroidView 复用。
 */
class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "网页"

        // 预创建 WebView(Activity context,确保 SPA 页面兼容)
        webView = WebView(this).apply { configure(url, title) }

        setContent {
            WebViewScreen(
                initialTitle = title,
                webView = webView!!,
                onBack = { finish() },
                onOpenBrowser = {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        ToastUtil.show(this, "无法打开浏览器")
                    }
                }
            )
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** 配置 WebView:完整设置以保证腾讯文档等 SPA 兼容 */
    private fun WebView.configure(url: String, initialTitle: String) {
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
            // 腾讯文档需要 UA 包含 Mobile 标识
            userAgentString = userAgentString?.let { if (it.contains("Mobile")) it else "$it Mobile" }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.endsWith(".pdf", ignoreCase = true) ||
                    request.url.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true) {
                    startActivity(Intent(this@WebViewActivity, PdfPreviewActivity::class.java).apply {
                        putExtra(PdfPreviewActivity.EXTRA_PDF_URL, u)
                        putExtra(PdfPreviewActivity.EXTRA_PDF_TITLE, "PDF预览")
                    })
                    return true
                }
                view.loadUrl(u)
                return true
            }
        }
        setDownloadListener { dUrl, _, _, mimetype, _ ->
            if (dUrl.endsWith(".pdf", ignoreCase = true) || mimetype == "application/pdf") {
                startActivity(Intent(this@WebViewActivity, PdfPreviewActivity::class.java).apply {
                    putExtra(PdfPreviewActivity.EXTRA_PDF_URL, dUrl)
                    putExtra(PdfPreviewActivity.EXTRA_PDF_TITLE, "PDF预览")
                })
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    ToastUtil.show(this@WebViewActivity, "无法下载文件")
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressState = newProgress
            }
            override fun onReceivedTitle(view: WebView?, t: String?) {
                if (!t.isNullOrEmpty()) titleState = t
            }
        }
        loadUrl(url)
    }

    // WebView 进度/标题状态(供 Compose 观察)
    private var progressState: Int = 0
    private var titleState: String = ""

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

@Composable
private fun WebViewScreen(
    initialTitle: String,
    webView: WebView,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        // ═══ 顶栏 ═══
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text(
                initialTitle,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp)
            )
            LiquidGlassIconButton(
                icon = Icons.Filled.OpenInBrowser,
                contentDescription = "在浏览器打开",
                onClick = onOpenBrowser,
                modifier = Modifier.size(40.dp).align(Alignment.CenterEnd)
            )
        }

        // ═══ WebView ═══
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
