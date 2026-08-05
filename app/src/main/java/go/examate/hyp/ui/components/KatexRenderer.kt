package go.examate.hyp.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 离屏 KaTeX 渲染器。
 *
 * 用「单个复用的离屏 WebView」把 LaTeX / mhchem 公式渲染后截图成 Bitmap 并缓存,列表里以 Image 显示:
 *  - 列表中没有常驻 WebView、无数量上限、滚动命中缓存不重渲(根治旧的 MAX_ACTIVE_WEBVIEWS 上限问题)。
 *  - 复用已内置的 katex.min.js/css + fonts/ + mhchem.min.js,零 Gradle 依赖。
 *
 * 优雅降级:assets 无 KaTeX 字体、或渲染/截图失败时返回 null,调用方回退到原生文本,绝不崩溃/白屏。
 *
 * 线程:公开方法均在主线程调用;JS 回调在 WebView 私有线程,内部已 post 回主线程再截图。
 */
object KatexRenderer {

    private val main = Handler(Looper.getMainLooper())

    // 缓存:key = latex|display|colorHex|px
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private data class Req(
        val key: String, val latex: String, val display: Boolean,
        val colorHex: String, val px: Int, val token: Int
    )

    // 自增 token:区分每次渲染请求,丢弃超时后迟到的旧回调
    private var seq = 0

    private val waiting = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val queue = ArrayDeque<Req>()
    private var rendering = false

    private var webView: WebView? = null
    private var pageReady = false
    private var fonts: Boolean? = null
    private var density = 3f
    private var screenWidthPx = 1080

    fun keyOf(latex: String, display: Boolean, colorHex: String, px: Int) =
        "$latex|$display|$colorHex|$px"

    fun cached(key: String): Bitmap? = cache.get(key)

    /** assets/fonts 下是否存在 KaTeX 字体(woff2);无字体则不渲染,调用方回退文本 */
    fun fontsAvailable(context: Context): Boolean {
        fonts?.let { return it }
        val ok = try {
            (context.assets.list("fonts") ?: emptyArray())
                .any { it.startsWith("KaTeX_") && it.endsWith(".woff2") }
        } catch (_: Exception) { false }
        fonts = ok
        return ok
    }

    /** 请求渲染(主线程)。命中缓存立即回调;否则入队,渲染完成或失败后回调(可能为 null) */
    @SuppressLint("SetJavaScriptEnabled")
    fun request(
        context: Context, latex: String, display: Boolean,
        colorHex: String, px: Int, cb: (Bitmap?) -> Unit
    ) {
        val key = keyOf(latex, display, colorHex, px)
        cache.get(key)?.let { cb(it); return }
        if (!fontsAvailable(context)) { cb(null); return }

        val list = waiting.getOrPut(key) { mutableListOf() }
        list.add(cb)
        if (list.size > 1) return   // 已有相同公式在排队,合并等待

        val dm = context.resources.displayMetrics
        density = dm.density
        screenWidthPx = dm.widthPixels.coerceAtLeast(360)
        queue.addLast(Req(key, latex, display, colorHex, px, ++seq))
        ensureWebView(context.applicationContext)
        pump()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(appCtx: Context) {
        if (webView != null) return
        val wv = WebView(appCtx)
        // 关键:强制软件渲染,否则硬件加速下 draw(canvas) 截图为空白
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.useWideViewPort = true
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onRendered(wCss: Double, hCss: Double, token: Int) = onRenderedInternal(wCss, hCss, token)
        }, "AndroidKatex")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pump()
            }
        }
        // 先给一个初始布局尺寸,JS 才能测量
        wv.layout(0, 0, screenWidthPx, 2000)
        wv.loadDataWithBaseURL("file:///android_asset/", TEMPLATE, "text/html", "utf-8", null)
        webView = wv
    }

    private fun pump() {
        if (rendering || !pageReady) return
        val req = queue.firstOrNull() ?: return
        val wv = webView ?: return
        rendering = true
        val js = "renderMath(${JSONObject.quote(req.latex)}, ${req.display}, " +
                "${JSONObject.quote(req.colorHex)}, ${req.px}, ${req.token});"
        wv.evaluateJavascript(js) { }
        // 超时保护:4s 没回调则判定失败,回退并继续队列
        main.postDelayed({
            if (rendering && queue.firstOrNull() === req) finish(req, null)
        }, 4000)
    }

    private fun onRenderedInternal(wCss: Double, hCss: Double, token: Int) {
        main.post {
            val req = queue.firstOrNull() ?: run { rendering = false; return@post }
            if (req.token != token) return@post  // 迟到的旧请求回调:丢弃,避免用错尺寸污染新请求的缓存
            val wv = webView
            if (wv == null || wCss <= 0 || hCss <= 0) { finish(req, null); return@post }
            val wPx = kotlin.math.ceil(wCss * density).toInt().coerceIn(1, 4096)
            val hPx = kotlin.math.ceil(hCss * density).toInt().coerceIn(1, 4096)
            val bmp = try {
                // 保持 WebView 宽度不变(避免 KaTeX 重排),仅确保高度足够后截左上角 wPx×hPx
                wv.layout(0, 0, maxOf(screenWidthPx, wPx), hPx + 4)
                val b = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
                wv.draw(Canvas(b))
                b
            } catch (_: Throwable) { null }
            finish(req, bmp)
        }
    }

    private fun finish(req: Req, bmp: Bitmap?) {
        if (queue.firstOrNull() === req) queue.removeFirst()
        if (bmp != null) cache.put(req.key, bmp)
        val cbs = waiting.remove(req.key) ?: mutableListOf()
        cbs.forEach { it(bmp) }
        rendering = false
        pump()
    }

    private const val TEMPLATE = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="file:///android_asset/katex.min.css">
        <script src="file:///android_asset/katex.min.js"></script>
        <script src="file:///android_asset/mhchem.min.js"></script>
        <style>
          html,body{margin:0;padding:0;background:transparent;}
          #box{display:inline-block;padding:3px 5px;line-height:normal;white-space:nowrap;overflow:visible;}
        </style></head>
        <body><span id="box"></span>
        <script>
          function renderMath(latex, display, color, px, token){
            var box=document.getElementById('box');
            box.style.color=color;
            box.style.fontSize=px+'px';
            try{ katex.render(latex, box, {displayMode:display, throwOnError:false, output:'html'}); }
            catch(e){ box.textContent = latex; }
            function report(){
              var r=box.getBoundingClientRect();
              var w=Math.max(box.scrollWidth, Math.ceil(r.width));
              var h=Math.max(box.scrollHeight, Math.ceil(r.height));
              if(window.AndroidKatex) AndroidKatex.onRendered(w, h, token);
            }
            if(document.fonts && document.fonts.ready){ document.fonts.ready.then(function(){ setTimeout(report,0); }); }
            else { setTimeout(report, 80); }
          }
        </script></body></html>
    """
}
