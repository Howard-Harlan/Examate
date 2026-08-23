package go.examate.hyp

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.util.ToastUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import org.json.JSONObject

/**
 * 教务系统网页识别导入:打开 WebView 让用户登录教务系统,
 * 进入课表页面后点击"提取课表"按钮,注入 JS 提取课表数据并回传。
 *
 * 支持正方教务系统(通过 AJAX 接口或 DOM 表格提取)。
 */
class ScheduleWebImportActivity : ComponentActivity() {

    internal var webView: WebView? = null
    private var extractedJson: String? = null

    /** 当前是否深色模式(与应用主题一致):0跟随系统/1浅色/2深色 */
    fun isDarkModeActive(): Boolean {
        val mode = PrefsManager.getInstance(this).themeMode
        return when (mode) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    WebImportScreen(
                        onBack = { finish() },
                        onExtract = { extractSchedule() },
                        onDone = { json ->
                            // 回传 JSON 给 ScheduleScreen
                            val data = Intent().putExtra("schedule_json", json)
                            setResult(RESULT_OK, data)
                            finish()
                        }
                    )
                }
            }
        }
    }

    /** 注入 JS 提取课表数据 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun extractSchedule() {
        val wv = webView ?: run {
            ToastUtil.show(this, "WebView 未就绪")
            return
        }
        // 正方教务系统:尝试通过 AJAX 请求课表接口
        val js = """
            (function() {
                try {
                    // 方式1:直接请求正方课表接口
                    var xhr = new XMLHttpRequest();
                    var xnm = new Date().getFullYear();
                    var xqm = '3';
                    xhr.open('POST', '/kbcx/xskbcx_cxXsKb.html', false);
                    xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                    xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                    xhr.send('xnm=' + xnm + '&xqm=' + xqm);
                    if (xhr.status === 200 && xhr.responseText) {
                        var data = JSON.parse(xhr.responseText);
                        if (data.kbList && data.kbList.length > 0) {
                            AndroidBridge.onScheduleExtracted(xhr.responseText);
                            return;
                        }
                    }
                } catch(e) { console.log('AJAX方式失败: ' + e); }
                
                // 方式2:解析 DOM 表格
                try {
                    var table = document.getElementById('kbtable') || document.querySelector('table');
                    if (!table) {
                        AndroidBridge.onScheduleError('未找到课表表格,请确保已进入课表页面');
                        return;
                    }
                    var rows = table.querySelectorAll('tr');
                    var kbList = [];
                    var currentDay = 1;
                    for (var i = 0; i < rows.length; i++) {
                        var cells = rows[i].querySelectorAll('td');
                        for (var j = 0; j < cells.length; j++) {
                            var text = cells[j].textContent.trim();
                            if (text.length > 2) {
                                kbList.push({kcmc: text, xqjmc: '星期' + currentDay, jc: '1-2', xm: '', cdmc: '', zcd: '1-16'});
                            }
                        }
                        currentDay++;
                    }
                    if (kbList.length > 0) {
                        AndroidBridge.onScheduleExtracted(JSON.stringify({kbList: kbList}));
                    } else {
                        AndroidBridge.onScheduleError('未能从页面提取到课表数据');
                    }
                } catch(e) {
                    AndroidBridge.onScheduleError('解析失败: ' + e.message);
                }
            })();
        """.trimIndent()
        wv.post { wv.evaluateJavascript(js, null) }
    }

    /** JS 回调接口 */
    inner class AndroidBridge {
        @JavascriptInterface
        fun onScheduleExtracted(json: String) {
            runOnUiThread {
                extractedJson = json
                // 直接返回结果
                val data = Intent().putExtra("schedule_json", json)
                setResult(RESULT_OK, data)
                ToastUtil.show(this@ScheduleWebImportActivity, "提取成功,正在导入...")
                finish()
            }
        }

        @JavascriptInterface
        fun onScheduleError(msg: String) {
            ToastUtil.showLong(this@ScheduleWebImportActivity, msg)
        }
    }

    override fun onDestroy() {
        webView?.apply {
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}

@Composable
private fun WebImportScreen(
    onBack: () -> Unit,
    onExtract: () -> Unit,
    onDone: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "网页导入课表",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }

        if (!loaded) {
            // URL 输入页
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("输入教务系统网址", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text("登录后进入课表页面,点击提取按钮自动导入", color = TextHint, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://jwxt.example.edu.cn", color = TextHint, fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                LiquidGlassButton(
                    text = "打开教务系统",
                    onClick = {
                        var u = url.trim()
                        if (u.isEmpty()) {
                            ToastUtil.show(context, "请输入网址")
                            return@LiquidGlassButton
                        }
                        if (!u.startsWith("http")) u = "https://$u"
                        loaded = true
                        // 保存最终 URL
                        url = u
                    },
                    style = ButtonStyle.White, height = 44.dp
                )
                Spacer(Modifier.height(24.dp))
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("使用说明", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("1. 输入教务系统网址并打开", color = TextSecondary, fontSize = 12.sp)
                        Text("2. 登录后进入课表查询页面", color = TextSecondary, fontSize = 12.sp)
                        Text("3. 点击右上角提取课表按钮", color = TextSecondary, fontSize = 12.sp)
                        Text("4. 自动识别并导入课表数据", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        } else {
            // WebView 页
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiquidGlassButton(
                        text = "提取课表",
                        onClick = onExtract,
                        style = ButtonStyle.White, height = 38.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(url, color = TextHint, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.weight(1f))
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString.replace("wv", "")
                            webViewClient = WebViewClient()
                            // WebView 内容跟随深色模式
                            if ((context as ScheduleWebImportActivity).isDarkModeActive()) {
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                                } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                                    WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                                }
                                setBackgroundColor(android.graphics.Color.rgb(28, 28, 30))
                            } else {
                                setBackgroundColor(android.graphics.Color.WHITE)
                            }
                            // 注入 JS 接口
                            addJavascriptInterface(
                                (context as ScheduleWebImportActivity).AndroidBridge(),
                                "AndroidBridge"
                            )
                            webViewRef = this
                            (context as ScheduleWebImportActivity).webView = this
                            // 先挂载到窗口后再加载(与 B站登录路径一致,避免未 attach 时加载导致不渲染)
                            post { if (url.isNotEmpty()) loadUrl(url) }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
