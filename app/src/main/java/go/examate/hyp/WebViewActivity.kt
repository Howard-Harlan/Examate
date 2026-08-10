package go.examate.hyp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import go.examate.hyp.util.ToastUtil
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 应用内 WebView 浏览器 — Compose 顶栏 + AndroidView 承载 WebView。
 *
 * 改进点:
 * - shouldOverrideUrlLoading 返回 false,保持 CAS 302 重定向链和 SPA 路由完整(修复白屏)
 * - UA 切换:默认移动端 / 桌面端 / iPhone,切换后自动重载
 * - 功能菜单:刷新 / UA切换 / 复制链接 / 浏览器打开 / 油猴脚本管理
 * - 加载进度条
 * - 油猴脚本(UserScript)支持:document-start / document-end / document-idle 三时机注入
 * - 混合内容支持 + 第三方 Cookie(校园系统常混用 HTTP/HTTPS)
 */

/** UA 模式:0=移动端(系统UA) 1=桌面端 2=iPhone 3..5=自定义UA1..3(文件级,供顶层菜单组件使用) */
private const val UA_DEFAULT = 0
private const val UA_DESKTOP = 1
private const val UA_IPHONE = 2
/** 自定义 UA 起始模式号与数量上限 */
private const val UA_CUSTOM_BASE = 3
private const val UA_CUSTOM_MAX = 3

/**
 * 自定义 UA 存储(最多 [UA_CUSTOM_MAX] 个,存于应用私有 SharedPreferences)。
 * 空字符串条目视为未设置。
 */
object UserAgentStore {
    private const val PREFS = "webview_ua"
    private const val KEY_UAS = "custom_uas"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取全部自定义 UA(固定长度 [UA_CUSTOM_MAX],空串表示未设置) */
    fun getAll(context: Context): List<String> {
        val json = prefs(context).getString(KEY_UAS, null) ?: return List(UA_CUSTOM_MAX) { "" }
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until UA_CUSTOM_MAX).map { i ->
                if (i < arr.length()) arr.getString(i) else ""
            }
        }.getOrDefault(List(UA_CUSTOM_MAX) { "" })
    }

    /** 设置第 index 个自定义 UA(0..2),空串表示清除 */
    fun set(context: Context, index: Int, value: String) {
        val list = getAll(context).toMutableList()
        if (index !in list.indices) return
        list[index] = value.trim()
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_UAS, arr.toString()).apply()
    }

    /** 删除第 index 个(将后续前移,末尾补空) */
    fun remove(context: Context, index: Int) {
        val list = getAll(context).toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        while (list.size < UA_CUSTOM_MAX) list.add("")
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_UAS, arr.toString()).apply()
    }
}

class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        /** 需要注入到请求 header 与 localStorage 的 JWT token(智慧思政 H5 用) */
        const val EXTRA_INJECT_TOKEN = "extra_inject_token"
        /** token 注入目标域(如 oshall.chnu.edu.cn),仅对该域请求注入 */
        const val EXTRA_INJECT_HOST = "extra_inject_host"
        /** 脚本拾取模式:打开后若点击 .user.js 链接则下载内容并以 EXTRA_PICK_RESULT 返回 */
        const val EXTRA_PICK_SCRIPT = "extra_pick_script"
        const val EXTRA_PICK_RESULT = "extra_pick_result"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"
    }

    private var webView: WebView? = null
    private var uaMode by mutableStateOf(UA_DEFAULT)
    private var systemUa: String = ""
    /** 待加载 URL:WebView 挂载到窗口后再 loadUrl(先挂载后加载,避免未 attach 时加载导致不渲染) */
    private var pendingUrl: String = ""
    private lateinit var scriptManager: UserScriptManager
    /** 需要注入的 JWT token(智慧思政 H5 用) */
    private var injectToken: String = ""
    /** token 注入目标 host */
    private var injectHost: String = ""
    /** document-start 油猴脚本是否已通过 addDocumentStartJavaScript 注册(避免与生命周期注入重复执行) */
    private var docStartRegistered = false
    /** 脚本拾取模式:true 时点击 .user.js 链接会把脚本内容回传给调用方 */
    private var pickScript = false
    /** 拾取进行中(防止 shouldOverrideUrlLoading 与 onPageStarted 双入口重复触发) */
    private var pickInProgress = false

    // ═══ 文件上传(input type=file 支持) ═══
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else null
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "网页"
        injectToken = intent.getStringExtra(EXTRA_INJECT_TOKEN) ?: ""
        injectHost = intent.getStringExtra(EXTRA_INJECT_HOST) ?: ""
        pickScript = intent.getBooleanExtra(EXTRA_PICK_SCRIPT, false)
        pendingUrl = url
        scriptManager = UserScriptManager.getInstance(this)

        // 仅创建 + 配置 WebView,不在此处 loadUrl —— 先挂载到窗口后再加载(见 WebViewScreen 的 AndroidView)。
        webView = WebView(this)
        systemUa = webView!!.settings.userAgentString ?: ""
        webView!!.configure(uaMode)

        setContent {
            WebViewScreen(
                initialTitle = title,
                webView = webView!!,
                pendingUrl = pendingUrl,
                progressProvider = { progressState },
                titleProvider = { if (titleState.isBlank()) title else titleState },
                errorProvider = { webError },
                onRetry = {
                    webError = null
                    val wv = webView
                    if (wv != null) {
                        // 渲染进程崩溃后 reload() 可能无效,统一用 loadUrl 重新加载(等价刷新)
                        val target = wv.url ?: pendingUrl
                        if (target.isNotEmpty()) wv.loadUrl(target) else wv.reload()
                    }
                },
                currentUaMode = uaMode,
                onBack = { finish() },
                onRefresh = { webView?.reload() },
                onSwitchUa = { mode ->
                    uaMode = mode
                    webView?.applyUa(mode)
                    webView?.reload()
                },
                onOpenBrowser = {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView?.url ?: url)))
                    } catch (e: Exception) {
                        ToastUtil.show(this, "无法打开浏览器")
                    }
                },
                onManageScripts = {
                    startActivity(Intent(this, UserScriptActivity::class.java))
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

    private fun WebView.applyUa(mode: Int) {
        settings.userAgentString = when {
            mode == UA_DESKTOP -> DESKTOP_UA
            mode == UA_IPHONE -> IPHONE_UA
            // 自定义 UA:模式号 3..5 对应存储的第 0..2 项
            mode in UA_CUSTOM_BASE until (UA_CUSTOM_BASE + UA_CUSTOM_MAX) -> {
                val custom = UserAgentStore.getAll(this@WebViewActivity)
                    .getOrNull(mode - UA_CUSTOM_BASE)
                    ?.trim()
                    .orEmpty()
                custom.ifEmpty { defaultCleanUa() }
            }
            else -> defaultCleanUa()
        }
    }

    /** 默认 UA:去掉 "; wv" 标识(站点检测到 wv 会返回降级/精简页面),保证 Mobile 标识存在 */
    private fun defaultCleanUa(): String {
        val clean = systemUa.replace("wv", "")
        return if (clean.contains("Mobile")) clean else "$clean Mobile"
    }

    private fun WebView.configure(ua: Int) {
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
        applyUa(ua)

        // 允许第三方 Cookie(校园系统 SSO 需要)
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            /**
             * 关键修复:返回 false 让 WebView 自行处理加载。
             * 之前返回 true 并手动 loadUrl 会打断 CAS 302 重定向链和 SPA 路由,导致白屏。
             * PDF 链接单独拦截转发预览。
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                // 脚本拾取模式:点击安装链接(.user.js / scriptcat code)直接下载回传,
                // 而不是让 WebView 把脚本当普通页面渲染(源码/空白)
                if (pickScript && isScriptUrl(u)) {
                    downloadScriptForPick(u)
                    return true
                }
                if (u.endsWith(".pdf", ignoreCase = true) ||
                    request.url.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true) {
                    startActivity(Intent(this@WebViewActivity, PdfPreviewActivity::class.java).apply {
                        putExtra(PdfPreviewActivity.EXTRA_PDF_URL, u)
                        putExtra(PdfPreviewActivity.EXTRA_PDF_TITLE, "PDF预览")
                    })
                    return true
                }
                // 特殊协议(tel:/mailto:/intent:/weixin:// 等)交给系统处理,避免 WebView 报错
                val scheme = request.url.scheme?.lowercase() ?: ""
                if (scheme !in setOf("http", "https", "about", "file", "data")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                        return true
                    } catch (e: Exception) {
                        // 无应用处理时继续走 WebView 内部加载
                    }
                }
                return false
            }

            /**
             * 注意:早期版本这里用 HttpURLConnection 代理 API 请求并注入 token header,
             * 已被移除。原因:
             * 1) 代理不共享 WebView 的 CookieManager —— CAS cookie 注入到 CookieManager 后,
             *    HttpURLConnection 拿不到,API 请求会 401/302,页面数据缺失;
             * 2) 不走 WebView 的 TLS 信任配置 —— 校园自签名证书导致 SSLException,
             *    被 catch 吞掉返回 null,该资源静默加载失败(表现为缺内容);
             * 3) 丢失 CORS/cache-control 等响应头,且异常路径连接泄漏。
             * 现在直接让 WebView 原生加载:登录态由 CookieManager(已在 SzsScreen 提前注入)携带,
             * token 由 injectTokenToStorage() 在页面加载早期写入页面存储供 H5 前端读取。
             */
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                webError = null
                // 兜底:某些安装跳转不走 shouldOverrideUrlLoading(JS location 跳转/重定向直达脚本),
                // 页面已开始加载脚本 URL 时立即拾取并停止渲染
                if (pickScript && url != null && isScriptUrl(url)) {
                    view?.stopLoading()
                    downloadScriptForPick(url)
                    return
                }
                // 页面文档刚开始加载即写入 token,确保 SPA 初始化时可读到(Jeecg-Boot 前端在启动时读页面存储)
                injectTokenToStorage(view, url)
                // document-start 脚本若已通过 addDocumentStartJavaScript 注册,此处跳过避免重复执行
                if (!docStartRegistered && url != null) injectScripts(url, "document-start", view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (url != null) injectScripts(url, "document-end", view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // 兜底:刷新/二次导航后再次注入(幂等)
                injectTokenToStorage(view, url)
                injectScripts(url ?: "", "document-idle", view)
            }

            /**
             * 主框架加载失败(网络不可达/证书错误/超时)时给出可操作的错误提示,
             * 避免静默白屏让用户误以为"缺内容"。
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    webError = "加载失败(${error?.errorCode ?: -1}) ${error?.description ?: "网络错误"}"
                }
            }

            /** 主框架返回 4xx/5xx(如登录态失效 401、服务异常 5xx)时提示 */
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    webError = "页面返回错误 HTTP ${errorResponse?.statusCode ?: -1}${errorResponse?.reasonPhrase?.let { " $it" } ?: ""}"
                }
            }

            /** 渲染进程被杀(低内存/崩溃):返回 true 由应用接管,提示重试而非静默白屏 */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                webError = "网页渲染进程异常,请点击重试"
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressState = newProgress
            }
            override fun onReceivedTitle(view: WebView?, t: String?) {
                if (!t.isNullOrEmpty()) titleState = t
            }
            /** 页面 <input type="file"> 上传支持(校园系统附件/图片上传) */
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // 清理上一个未完成的回调(避免泄漏)
                this@WebViewActivity.filePathCallback?.onReceiveValue(null)
                this@WebViewActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: return false
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@WebViewActivity.filePathCallback?.onReceiveValue(null)
                    this@WebViewActivity.filePathCallback = null
                    false
                }
            }
            /** target=_blank / window.open:在同一个 WebView 内打开(部分校园系统用新窗口跳转) */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(this@WebViewActivity)
                newWebView.settings.javaScriptEnabled = true
                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest
                    ): Boolean {
                        // 脚本拾取:新窗口(安装按钮可能 target=_blank)同样拦截
                        if (pickScript && isScriptUrl(request.url.toString())) {
                            downloadScriptForPick(request.url.toString())
                            view?.destroy()
                            return true
                        }
                        // 新窗口 URL 交回主 WebView 加载,然后关闭新窗口
                        this@WebViewActivity.webView?.loadUrl(request.url.toString())
                        view?.destroy()
                        return true
                    }
                }
                resultMsg?.obj = newWebView
                return true
            }
        }

        setDownloadListener { dUrl, _, _, mimetype, _ ->
            // 脚本拾取模式:拦截 .user.js / 脚本猫 code 链接,下载内容后回传给调用方(脚本管理页导入)
            if (pickScript && (isScriptUrl(dUrl) ||
                    mimetype in setOf("text/javascript", "application/javascript"))) {
                downloadScriptForPick(dUrl)
                return@setDownloadListener
            }
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

        // 关键:在页面任何脚本执行前注入 JWT token(官方 WebViewCompat.addDocumentStartJavaScript,
        // 仅对注入目标 host 生效)。比 onPageFinished 再注入可靠 —— Jeecg-Boot SPA 启动时
        // axios 拦截器会立即读页面存储,注入太晚会导致渲染出"未登录/空数据"页面。
        if (injectToken.isNotEmpty() && injectHost.isNotEmpty()) {
            try {
                val js = buildTokenScript()
                // 返回 ScriptHandler,null = 系统 WebView 版本过旧不支持
                val handler = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this, js,
                    setOf("https://$injectHost", "http://$injectHost", "https://*.$injectHost")
                )
                if (handler == null) {
                    android.util.Log.w("WebViewActivity", "addDocumentStartJavaScript 不可用,改用生命周期注入兜底")
                }
            } catch (e: Exception) {
                android.util.Log.w("WebViewActivity", "token 注入注册失败", e)
            }
        }

        // 油猴 document-start 脚本:同样用 addDocumentStartJavaScript 保证在页面 JS 之前执行。
        // 匹配规则由 UserScriptManager 在注册时快照进脚本,注册成功则生命周期注入跳过 document-start。
        runCatching {
            val usJs = buildDocStartUserscript()
            if (usJs.isNotEmpty()) {
                val h = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, usJs, setOf("*"))
                docStartRegistered = h != null
            }
        }
        // 注意:loadUrl 不在 configure() 中调用 —— 由 WebViewScreen 在 WebView 挂载到窗口后触发,
        // 保证 WebView 已有有效尺寸/已 attach,避免部分系统 WebView 上"先加载后挂载"导致内容不渲染。
    }

    /**
     * 注入匹配当前 URL 的油猴脚本 */
    private fun injectScripts(url: String, runAt: String, view: WebView?) {
        val codes = scriptManager.getScriptsForUrl(url, runAt)
        if (codes.isEmpty() || view == null) return
        // 每个脚本独立 try-catch:单个脚本报错不中断后续脚本
        val combined = buildString {
            append("(function(){")
            codes.forEach { code ->
                append("\n// --- script ---\n")
                append("try {")
                append(code)
                append("\n} catch(e) { console.warn('[userscript]', e); }")
                append("\n")
            }
            append("})();")
        }
        view.evaluateJavascript(combined, null)
    }

    /**
     * 构建写入页面存储的 token 脚本(Jeecg-Boot 框架常见 key)。
     * 幂等,可重复执行;只在配置了 injectToken 时返回非空。
     */
    private fun buildTokenScript(): String {
        if (injectToken.isEmpty()) return ""
        val t = injectToken
        return """
            (function(){
                try {
                    localStorage.setItem('Access-Token', '$t');
                    localStorage.setItem('X-Access-Token', '$t');
                    localStorage.setItem('W-Access-Token', '$t');
                    localStorage.setItem('token', '$t');
                    localStorage.setItem('pro__Access-Token', '$t');
                } catch(e) {}
            })();
        """.trimIndent()
    }

    /**
     * 构建 document-start 油猴脚本包装:内部按 location.href 做 glob 匹配后执行对应脚本。
     * 注册到 addDocumentStartJavaScript,保证在页面任何 JS 之前运行。
     */
    private fun buildDocStartUserscript(): String {
        val payload = scriptManager.buildDocumentStartPayload() ?: return ""
        return """
            (function(){
                var RULES = $payload;
                var url = location.href;
                function globMatch(p, u) {
                    var re = '^';
                    for (var i = 0; i < p.length; i++) {
                        var c = p.charAt(i);
                        if (c === '*') re += '.*';
                        else if (c === '?') re += '.';
                        else if ('\\^\\$\\.\\+\\*\\(\\)\\[\\]\\{\\}\\|'.indexOf(c) >= 0) re += '\\' + c;
                        else re += c;
                    }
                    re += '$';
                    return new RegExp(re, 'i').test(u);
                }
                function matches(ms, xs) {
                    for (var i = 0; i < xs.length; i++) if (globMatch(xs[i], url)) return false;
                    for (var i = 0; i < ms.length; i++) if (globMatch(ms[i], url)) return true;
                    return false;
                }
                for (var i = 0; i < RULES.length; i++) {
                    var r = RULES[i];
                    if (!matches(r.matches, r.excludes)) continue;
                    try { (new Function(r.code))(); }
                    catch (e) { console.warn('[userscript]', e); }
                }
            })();
        """.trimIndent()
    }

    /**
     * 把 JWT token 写入页面存储,供 H5 前端读取。
     * 兜底路径:addDocumentStartJavaScript 不可用时,在 onPageStarted / onPageFinished 注入。
     * 仅当页面 host 与注入目标域匹配时才写,避免 token 泄漏到无关域。
     */
    private fun injectTokenToStorage(view: WebView?, url: String?) {
        if (injectToken.isEmpty() || view == null) return
        if (injectHost.isNotEmpty()) {
            val host = runCatching { java.net.URI(url ?: "").host }.getOrNull() ?: return
            if (host != injectHost && !host.endsWith(".$injectHost")) return
        }
        view.evaluateJavascript(buildTokenScript(), null)
    }

    private var progressState by mutableIntStateOf(0)
    private var titleState by mutableStateOf("")
    /** 主框架加载失败/HTTP 错误/渲染进程崩溃时的错误提示(为空表示正常) */
    private var webError by mutableStateOf<String?>(null)

    /** 脚本拾取:下载 .user.js / scriptcat code 内容,校验头部后回传给调用方(脚本管理页) */
    private fun downloadScriptForPick(url: String) {
        if (pickInProgress) return
        pickInProgress = true
        ToastUtil.show(this, "正在获取脚本...")
        kotlin.concurrent.thread {
            val content = runCatching {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", go.examate.hyp.campus.ChnuConfig.UA)
                    // 部分脚本站校验来源(脚本猫/油猴安装链接)
                    setRequestProperty("Referer", webView?.url ?: "https://scriptcat.org/")
                    setRequestProperty("Accept", "*/*")
                    instanceFollowRedirects = true
                }
                conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
            }.getOrNull()
            runOnUiThread {
                pickInProgress = false
                if (content.isNullOrBlank() || !content.contains("==UserScript==")) {
                    ToastUtil.show(this, "未识别到脚本内容")
                    return@runOnUiThread
                }
                val data = Intent().putExtra(EXTRA_PICK_RESULT, content)
                setResult(android.app.Activity.RESULT_OK, data)
                finish()
            }
        }
    }

    /** 判断 URL 是否为脚本安装链接(脚本猫/油猴常见: .user.js 或 scriptcat 的 /code 端点) */
    private fun isScriptUrl(u: String): Boolean {
        val lower = u.lowercase()
        return lower.endsWith(".user.js") ||
            (lower.contains("scriptcat.org") && lower.contains("/code")) ||
            lower.endsWith("/code") && lower.contains("scriptcat")
    }

    override fun onDestroy() {
        // 清理未完成的文件选择回调,避免回调到已销毁的 Activity
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView?.apply {
            // 先从父容器移除再 destroy,避免悬挂引用/泄漏
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}

// ════════════════════════════════════════════════════════════════════
// 油猴脚本管理器 — 油猴脚本(UserScript)的存储/匹配/注入
// 内联在此文件中,避免跨文件引用编译问题
// ════════════════════════════════════════════════════════════════════

class UserScriptManager private constructor(context: Context) {

    data class UserScript(
        val id: String,
        val name: String,
        val version: String = "1.0",
        val description: String = "",
        val author: String = "",
        /** @require 外部库 URL(脚本猫/油猴脚本依赖,导入时尝试内联下载) */
        val requires: List<String> = emptyList(),
        val matches: List<String>,
        val excludes: List<String>,
        val runAt: String,
        val code: String,
        val enabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val prefs = context.getSharedPreferences("user_scripts", Context.MODE_PRIVATE)
    private val scriptList = mutableListOf<UserScript>()

    init { load() }

    companion object {
        @Volatile private var instance: UserScriptManager? = null
        fun getInstance(context: Context): UserScriptManager =
            instance ?: synchronized(this) {
                instance ?: UserScriptManager(context.applicationContext).also { instance = it }
            }

        /** glob 正则缓存(上限 64,超出清空重建,防止无限膨胀) */
        private val globRegexCache = object : LinkedHashMap<String, Regex>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?): Boolean = size > 64
        }
    }

    fun getScripts(): List<UserScript> = synchronized(scriptList) { scriptList.toList() }

    fun getEnabledScripts(): List<UserScript> =
        synchronized(scriptList) { scriptList.filter { it.enabled } }

    fun import(raw: String): UserScript {
        val parsed = parse(raw)
        // @require 外部库:同步内联下载(短超时,失败则跳过,脚本仍可导入)
        val withLibs = inlineRequires(parsed)
        synchronized(scriptList) { scriptList.add(withLibs); save() }
        return withLibs
    }

    fun update(id: String, raw: String): Boolean {
        val parsed = parse(raw).copy(id = id)
        val withLibs = inlineRequires(parsed)
        synchronized(scriptList) {
            val idx = scriptList.indexOfFirst { it.id == id }
            if (idx < 0) return false
            scriptList[idx] = withLibs; save()
        }
        return true
    }

    /**
     * 把 @require 外部库内容下载并内联到脚本代码头部(脚本猫/油猴脚本常见依赖 jQuery 等)。
     * 网络失败/超时(3s)则保留原代码不阻塞导入。
     * 若 code 已包含该库的内联标记(编辑后重新保存),则跳过避免重复内联。
     */
    private fun inlineRequires(script: UserScript): UserScript {
        if (script.requires.isEmpty()) return script
        val sb = StringBuilder()
        script.requires.forEach { url ->
            val mark = "// ==== @require: $url ===="
            if (script.code.contains(mark)) return@forEach
            val body = runCatching {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", go.examate.hyp.campus.ChnuConfig.UA)
                }
                conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
            }.getOrNull()
            if (!body.isNullOrBlank()) {
                sb.append(mark).append("\n")
                sb.append(body).append("\n")
            }
        }
        if (sb.isEmpty()) return script
        return script.copy(code = sb.append("\n").append(script.code).toString())
    }

    fun delete(id: String) {
        synchronized(scriptList) { scriptList.removeAll { it.id == id }; save() }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(scriptList) {
            val idx = scriptList.indexOfFirst { it.id == id }
            if (idx < 0) return
            scriptList[idx] = scriptList[idx].copy(enabled = enabled); save()
        }
    }

    fun getScriptsForUrl(url: String, runAt: String): List<String> =
        synchronized(scriptList) {
            scriptList.filter { it.enabled && it.runAt == runAt && matchesUrl(url, it.matches, it.excludes) }
                .map { it.code }
        }

    /**
     * 序列化所有启用中的 document-start 脚本(匹配规则+代码)为 JSON 数组字符串。
     * 供 WebViewCompat.addDocumentStartJavaScript 快照注册 —— 在页面任何 JS 执行前运行,
     * 脚本内部按 location.href 做 glob 匹配后执行。无匹配脚本时返回 null。
     */
    fun buildDocumentStartPayload(): String? = synchronized(scriptList) {
        val list = scriptList.filter { it.enabled && it.runAt == "document-start" }
        if (list.isEmpty()) return null
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("matches", JSONArray(s.matches))
                put("excludes", JSONArray(s.excludes))
                put("code", s.code)
            })
        }
        arr.toString()
    }

    private fun parse(raw: String): UserScript {
        // 兼容油猴与脚本猫(ScriptCat)头部:两者都用 ==UserScript== 块
        val pattern = Regex("//\\s*==UserScript==\\s*\\n(.*?)//\\s*==/UserScript==", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(raw) ?: throw IllegalArgumentException("缺少 UserScript 头部")
        val header = match.groupValues[1]
        val body = raw.substring(match.range.last + 1).trim()

        var name = "未命名脚本"; var version = "1.0"; var description = ""
        var author = ""
        val requires = mutableListOf<String>()
        val matches = mutableListOf<String>(); val excludes = mutableListOf<String>()
        var runAt = "document-end"

        Regex("//\\s*@([\\S]+)\\s+(.+)").findAll(header).forEach { m ->
            when (m.groupValues[1].lowercase()) {
                "name" -> name = m.groupValues[2].trim()
                "version" -> version = m.groupValues[2].trim()
                "description" -> description = m.groupValues[2].trim()
                "author" -> author = m.groupValues[2].trim()
                // @require 外部库:URL 可能带引号(脚本猫常见 "@require https://..." 或 "@require\tURL")
                "require" -> {
                    val u = m.groupValues[2].trim().trim('"').trim('\'')
                    if (u.isNotEmpty()) requires.add(u)
                }
                "match", "include" -> matches.add(m.groupValues[2].trim())
                "exclude" -> excludes.add(m.groupValues[2].trim())
                "run-at" -> runAt = m.groupValues[2].trim()
            }
        }
        if (matches.isEmpty()) throw IllegalArgumentException("缺少 @match 规则")
        if (body.isBlank()) throw IllegalArgumentException("脚本正文为空")

        return UserScript(
            id = UUID.randomUUID().toString(), name = name, version = version,
            description = description, author = author, requires = requires,
            matches = matches, excludes = excludes,
            runAt = normalizeRunAt(runAt), code = body
        )
    }

    private fun normalizeRunAt(raw: String): String = when (raw.lowercase()) {
        "document-start" -> "document-start"
        "document-idle" -> "document-idle"
        else -> "document-end"
    }

    private fun matchesUrl(url: String, matches: List<String>, excludes: List<String>): Boolean {
        if (excludes.any { globMatch(it, url) }) return false
        return matches.any { globMatch(it, url) }
    }

    private fun globMatch(pattern: String, url: String): Boolean {
        // 正则缓存:同一 pattern 在多 URL 上重复匹配时避免反复编译
        val regex = globRegexCache.getOrPut(pattern) {
            val sb = StringBuilder("^")
            for (c in pattern) when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                else -> { if (c in "\\.+()[]{}|^$") sb.append('\\'); sb.append(c) }
            }
            sb.append("$")
            sb.toString().toRegex(RegexOption.IGNORE_CASE)
        }
        return regex.matches(url)
    }

    private fun save() {
        val arr = JSONArray()
        scriptList.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("version", s.version)
                put("description", s.description); put("author", s.author)
                put("requires", JSONArray(s.requires)); put("matches", JSONArray(s.matches))
                put("excludes", JSONArray(s.excludes)); put("runAt", s.runAt)
                put("code", s.code); put("enabled", s.enabled); put("createdAt", s.createdAt)
            })
        }
        prefs.edit().putString("scripts", arr.toString()).apply()
    }

    private fun load() {
        val json = prefs.getString("scripts", null) ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                scriptList.add(UserScript(
                    id = o.getString("id"), name = o.getString("name"),
                    version = o.optString("version", "1.0"),
                    description = o.optString("description", ""),
                    author = o.optString("author", ""),
                    requires = o.optJSONArray("requires")?.let { toStringList(it) } ?: emptyList(),
                    matches = toStringList(o.getJSONArray("matches")),
                    excludes = o.optJSONArray("excludes")?.let { toStringList(it) } ?: emptyList(),
                    runAt = o.optString("runAt", "document-end"),
                    code = o.getString("code"),
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                ))
            }
        }
    }

    private fun toStringList(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getString(it) }
}

// ════════════════════════════════════════════════════════════════════
// 油猴脚本管理页面
// ════════════════════════════════════════════════════════════════════

class UserScriptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UserScriptScreen(onBack = { finish() })
        }
    }
}

@Composable
private fun UserScriptScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val manager = remember { UserScriptManager.getInstance(context) }
    val scripts = remember { androidx.compose.runtime.mutableStateListOf<UserScriptManager.UserScript>() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        scripts.clear(); scripts.addAll(manager.getScripts())
    }

    var showEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var showLinkDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    /** 统一导入入口:校验 + 刷新列表 */
    fun doImport(raw: String, source: String) {
        try {
            manager.import(raw)
            ToastUtil.show(context, "已从 ${source} 导入")
            scripts.clear(); scripts.addAll(manager.getScripts())
        } catch (e: Exception) {
            ToastUtil.show(context, "解析失败: ${e.message}")
        }
    }

    // 本地文件导入(SAF 选择 .js/.txt)
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (raw.isNullOrBlank()) ToastUtil.show(context, "读取文件失败")
            else doImport(raw, "本地文件")
        }
    }

    // 脚本猫获取:打开内置浏览器(拾取模式),点 .user.js 后回传内容
    val catPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra(WebViewActivity.EXTRA_PICK_RESULT)
            if (!raw.isNullOrBlank()) doImport(raw, "脚本猫")
        }
    }

    Column(Modifier.fillMaxSize().background(CardBg).statusBarsPadding()) {
        // 顶栏
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text("脚本", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center))
            // 导入菜单(样式与浏览器菜单一致)
            var menuExpanded by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.CenterEnd)) {
                LiquidGlassIconButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "导入",
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(40.dp)
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = Color.White.copy(alpha = 0.95f),
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp
                ) {
                    DropdownMenuItem(text = { Text("粘贴代码") }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                        onClick = { menuExpanded = false; editingId = null; editingContent = ""; showEditor = true })
                    DropdownMenuItem(text = { Text("从本地导入") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                        onClick = { menuExpanded = false; filePicker.launch(arrayOf("text/*", "application/javascript", "*/*")) })
                    DropdownMenuItem(text = { Text("从链接导入") }, leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = { menuExpanded = false; showLinkDialog = true })
                    DropdownMenuItem(text = { Text("从脚本猫获取") }, leadingIcon = { Icon(Icons.Filled.Extension, null) },
                        onClick = {
                            menuExpanded = false
                            catPicker.launch(Intent(context, WebViewActivity::class.java).apply {
                                putExtra(WebViewActivity.EXTRA_URL, "https://scriptcat.org/zh-CN/search")
                                putExtra(WebViewActivity.EXTRA_TITLE, "脚本猫")
                                putExtra(WebViewActivity.EXTRA_PICK_SCRIPT, true)
                            })
                        })
                }
            }
        }

        if (importing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(color = AccentBlue)
                    Spacer(Modifier.height(10.dp))
                    Text("正在获取脚本...", color = TextSecondary, fontSize = 13.sp)
                }
            }
        } else if (scripts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无脚本", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("点击右上角菜单导入,支持油猴 / 脚本猫", color = TextHint, fontSize = 12.sp)
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
            ) {
                items(scripts, key = { it.id }) { script ->
                    ScriptCard(
                        script = script,
                        onToggle = { enabled ->
                            manager.setEnabled(script.id, enabled)
                            val idx = scripts.indexOfFirst { it.id == script.id }
                            if (idx >= 0) scripts[idx] = scripts[idx].copy(enabled = enabled)
                        },
                        onEdit = { editingId = script.id; editingContent = rebuildRaw(script); showEditor = true },
                        onDelete = { manager.delete(script.id); scripts.removeAll { it.id == script.id }; ToastUtil.show(context, "已删除") }
                    )
                }
            }
        }
    }

    if (showEditor) {
        ScriptEditorDialog(
            content = editingContent,
            isEdit = editingId != null,
            onDismiss = { showEditor = false },
            onSave = { raw ->
                try {
                    if (editingId != null) { manager.update(editingId!!, raw); ToastUtil.show(context, "已更新") }
                    else { manager.import(raw); ToastUtil.show(context, "已导入") }
                    scripts.clear(); scripts.addAll(manager.getScripts()); showEditor = false
                } catch (e: Exception) { ToastUtil.show(context, "解析失败: ${e.message}") }
            }
        )
    }

    // 链接导入对话框
    if (showLinkDialog) {
        var url by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(20.dp),
            title = { Text("从链接导入", color = TextPrimary, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    Text("输入 .user.js 脚本直链(脚本猫/油猴均可)", color = TextHint, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://.../script.user.js", color = TextHint, fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showLinkDialog = false
                    if (url.isBlank()) { ToastUtil.show(context, "请输入链接"); return@TextButton }
                    importing = true
                    scope.launch {
                        val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val conn = (java.net.URL(url.trim()).openConnection() as java.net.HttpURLConnection).apply {
                                    connectTimeout = 15000
                                    readTimeout = 15000
                                    setRequestProperty("User-Agent", go.examate.hyp.campus.ChnuConfig.UA)
                                    instanceFollowRedirects = true
                                }
                                conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
                            }.getOrNull()
                        }
                        importing = false
                        if (raw.isNullOrBlank() || !raw.contains("==UserScript==")) {
                            ToastUtil.show(context, "未识别到脚本内容")
                        } else {
                            doImport(raw, "链接")
                        }
                    }
                }) {
                    Text("导入", color = AccentBlue)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLinkDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}

private fun rebuildRaw(s: UserScriptManager.UserScript): String = buildString {
    append("// ==UserScript==\n")
    append("// @name ").append(s.name).append('\n')
    append("// @version ").append(s.version).append('\n')
    if (s.description.isNotEmpty()) append("// @description ").append(s.description).append('\n')
    if (s.author.isNotEmpty()) append("// @author ").append(s.author).append('\n')
    s.requires.forEach { append("// @require ").append(it).append('\n') }
    s.matches.forEach { append("// @match ").append(it).append('\n') }
    s.excludes.forEach { append("// @exclude ").append(it).append('\n') }
    append("// @run-at ").append(s.runAt).append('\n')
    append("// ==/UserScript==\n\n")
    append(s.code)
}

@Composable
private fun ScriptCard(
    script: UserScriptManager.UserScript,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(script.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = script.enabled, onCheckedChange = onToggle)
        }
        if (script.matches.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(script.matches.joinToString(" | "), color = TextSecondary, fontSize = 11.sp, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 版本 · 时机 · 依赖库数
            val meta = buildString {
                append("v").append(script.version)
                append(" · ").append(script.runAt)
                if (script.requires.isNotEmpty()) append(" · ").append(script.requires.size).append("依赖")
            }
            Text(meta, color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Edit, contentDescription = "编辑",
                tint = TextSecondary, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { onEdit() }.padding(6.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Delete, contentDescription = "删除",
                tint = TextSecondary, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { onDelete() }.padding(6.dp))
        }
    }
}

@Composable
private fun ScriptEditorDialog(
    content: String, isEdit: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(content) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = { Text(if (isEdit) "编辑脚本" else "粘贴导入", color = TextPrimary, fontWeight = FontWeight.Medium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("支持油猴 / 脚本猫脚本(含 ==UserScript== 头部)", color = TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = TextPrimary
                    )
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(text) }) { Text("保存", color = AccentBlue) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } }
    )
}

// ════════════════════════════════════════════════════════════════════
// WebView 屏幕 UI
// ════════════════════════════════════════════════════════════════════

@Composable
private fun WebViewScreen(
    initialTitle: String,
    webView: WebView,
    pendingUrl: String,
    progressProvider: () -> Int,
    titleProvider: () -> String,
    errorProvider: () -> String?,
    onRetry: () -> Unit,
    currentUaMode: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchUa: (Int) -> Unit,
    onOpenBrowser: () -> Unit,
    onManageScripts: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(CardBg).statusBarsPadding()) {
        // 顶栏
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text(
                titleProvider(),
                color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp)
            )
            // 功能菜单
            WebViewOverflowMenu(
                currentUaMode = currentUaMode,
                onRefresh = onRefresh,
                onSwitchUa = onSwitchUa,
                onOpenBrowser = onOpenBrowser,
                onManageScripts = onManageScripts,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        // 加载进度条
        val p = progressProvider()
        if (p in 1..99) {
            LinearProgressIndicator(
                progress = { p / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = AccentBlue
            )
        }

        // 内容区:FrameLayout 容器承载 WebView(先挂载后加载,与 B站登录 setContentView 路径一致)。
        // 错误提示以覆盖层叠加,不把 WebView 移出组合树,避免重组后无法重新挂载。
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        // WebView 加入容器:确保已有尺寸/已 attach 后再 loadUrl
                        addView(
                            webView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        webView.post {
                            if (webView.url.isNullOrEmpty() && pendingUrl.isNotEmpty()) {
                                webView.loadUrl(pendingUrl)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 主框架错误:覆盖显示错误提示 + 重试(不再静默白屏)
            val err = errorProvider()
            if (err != null) {
                Box(
                    Modifier.fillMaxSize().background(CardBg),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(err, color = TextSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.OutlinedButton(onClick = onRetry) {
                            Text("重试", color = AccentBlue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebViewOverflowMenu(
    currentUaMode: Int,
    onRefresh: () -> Unit,
    onSwitchUa: (Int) -> Unit,
    onOpenBrowser: () -> Unit,
    onManageScripts: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showUaPicker by remember { mutableStateOf(false) }

    Box(modifier) {
        LiquidGlassIconButton(
            icon = Icons.Filled.MoreVert,
            contentDescription = "菜单",
            onClick = { expanded = true },
            modifier = Modifier.size(40.dp)
        )
        // 样式与 ModelSelector 对齐:圆角 20dp / 白 0.95 / 无 tonal elevation
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White.copy(alpha = 0.95f),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp
        ) {
            DropdownMenuItem(text = { Text("刷新") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                onClick = { expanded = false; onRefresh() })
            DropdownMenuItem(text = { Text("切换UA") },
                leadingIcon = { Icon(Icons.Filled.Smartphone, null) },
                onClick = { expanded = false; showUaPicker = true })
            DropdownMenuItem(text = { Text("在浏览器打开") }, leadingIcon = { Icon(Icons.Filled.OpenInBrowser, null) },
                onClick = { expanded = false; onOpenBrowser() })
            DropdownMenuItem(text = { Text("脚本") }, leadingIcon = { Icon(Icons.Filled.Extension, null) },
                onClick = { expanded = false; onManageScripts() })
        }
    }

    // UA 选择弹窗(样式:项目 AlertDialog 风格;内置三档 + 自定义UA最多3个)
    if (showUaPicker) {
        UaPickerDialog(
            currentUaMode = currentUaMode,
            onSelect = { mode ->
                showUaPicker = false
                onSwitchUa(mode)
            },
            onDismiss = { showUaPicker = false }
        )
    }
}

/**
 * UA 选择弹窗:内置 移动端/桌面端/iPhone + 自定义UA(最多3个,可添加/编辑/删除)。
 */
@Composable
private fun UaPickerDialog(
    currentUaMode: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 本地保存一份自定义 UA 快照,编辑后立即生效
    var customUas by remember { mutableStateOf(UserAgentStore.getAll(context)) }
    // 正在编辑的 UA(空串=新建;null=未打开编辑器)
    var editing by remember { mutableStateOf<Pair<Int?, String>?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = { Text("切换 UA", color = TextPrimary, fontWeight = FontWeight.Medium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                UaOption("移动端", currentUaMode == UA_DEFAULT) { onSelect(UA_DEFAULT) }
                UaOption("桌面端", currentUaMode == UA_DESKTOP) { onSelect(UA_DESKTOP) }
                UaOption("iPhone", currentUaMode == UA_IPHONE) { onSelect(UA_IPHONE) }

                Spacer(Modifier.height(4.dp))
                Text("自定义 UA", color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp))

                // 已配置的自定义 UA 列表
                customUas.forEachIndexed { i, ua ->
                    if (ua.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(UA_CUSTOM_BASE + i) }
                                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "自定义${i + 1}",
                                color = if (currentUaMode == UA_CUSTOM_BASE + i) AccentBlue else TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (currentUaMode == UA_CUSTOM_BASE + i) FontWeight.Medium else FontWeight.Normal
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                ua.take(18) + if (ua.length > 18) "…" else "",
                                color = TextHint, fontSize = 11.sp, maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Filled.Edit, contentDescription = "编辑",
                                tint = TextSecondary, modifier = Modifier.size(28.dp).padding(4.dp)
                                    .clickable { editing = i to ua })
                            Icon(Icons.Filled.Delete, contentDescription = "删除",
                                tint = TextSecondary, modifier = Modifier.size(28.dp).padding(4.dp)
                                    .clickable {
                                        UserAgentStore.remove(context, i)
                                        customUas = UserAgentStore.getAll(context)
                                        // 若删除的是当前生效的自定义 UA,回到移动端
                                        if (currentUaMode == UA_CUSTOM_BASE + i) onSelect(UA_DEFAULT)
                                    })
                        }
                    }
                }

                // 添加按钮(最多3个)
                if (customUas.count { it.isNotBlank() } < UA_CUSTOM_MAX) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { editing = null to "" }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null,
                            tint = AccentBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加自定义 UA", color = AccentBlue, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )

    // UA 编辑/新建对话框
    val edit = editing
    if (edit != null) {
        var input by remember(edit) { mutableStateOf(edit.second) }
        val isNew = edit.first == null
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (isNew) "添加自定义 UA" else "编辑自定义 UA", color = TextPrimary, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    Text("最多 3 个,留空删除", color = TextHint, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Mozilla/5.0 ...", color = TextHint, fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("可用占位符: {system} = 系统UA(自动去wv)", color = TextHint, fontSize = 11.sp)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // {system} 占位符 → 系统UA(自动去 wv 标识),随后写入存储
                    val value = input
                        .replace("{system}", android.webkit.WebSettings.getDefaultUserAgent(context))
                        .replace("wv", "")
                    val idx = edit.first
                    if (idx != null) {
                        UserAgentStore.set(context, idx, value)
                        customUas = UserAgentStore.getAll(context)
                        // 编辑当前生效项时立即应用
                        if (currentUaMode == UA_CUSTOM_BASE + idx) {
                            onSelect(UA_CUSTOM_BASE + idx)
                        }
                    } else {
                        val emptyIdx = customUas.indexOfFirst { it.isBlank() }
                        if (emptyIdx >= 0) {
                            UserAgentStore.set(context, emptyIdx, value)
                            customUas = UserAgentStore.getAll(context)
                        }
                    }
                    editing = null
                }) {
                    Text("保存", color = AccentBlue)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editing = null }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun UaOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            color = if (selected) AccentBlue else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("✓", color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}
