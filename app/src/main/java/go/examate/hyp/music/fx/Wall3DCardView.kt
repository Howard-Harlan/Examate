package go.examate.hyp.music.fx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import go.examate.hyp.music.AlbumArtLoader
import go.examate.hyp.music.FavoritesStore
import go.examate.hyp.music.LyricParser
import go.examate.hyp.music.MusicController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 3D 歌单墙 — WebView 内嵌 wall3d.html(原 style-reference-empty.html 移植)。
 * 数据/播放/歌词/收藏/背景走原生桥接,本视图只承载页面并双向桥接 MusicController。
 * - Native→Page: __setTracks / __setCover / __setLiked / __onPlayerEvent / __setBgPreset / __setBg
 * - Page→Native: PlayerBridge.action({a, v})
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Wall3DCardView(
    controller: MusicController,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val html = remember {
        runCatching { context.assets.open("player_fx/wall3d.html").use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrDefault("")
    }
    val bgPreset = remember { PlayerFxStore.wall3DBgPreset(context) }
    val customBg = remember { PlayerFxStore.wall3DCustomBg(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.mediaPlaybackRequiresUserGesture = false
                wv.isVerticalScrollBarEnabled = false
                wv.isHorizontalScrollBarEnabled = false
                wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                wv.setBackgroundColor(0xFF070910.toInt())
                // 本地视频/图片背景:映射 /wallbg/ → filesDir/wall3d_bg/
                val bgDir = ctx.filesDir.resolve("wall3d_bg").also { it.mkdirs() }
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/wallbg/", WebViewAssetLoader.InternalStoragePathHandler(ctx, bgDir))
                    .build()
                wv.addJavascriptInterface(WallBridge(controller, onBack, context, scope, wv) { pageReady = true }, "PlayerBridge")
                wv.loadDataWithBaseURL("file:///android_asset/player_fx/", html, "text/html", "utf-8", null)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }
                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        pageReady = true
                    }
                }
                webView = wv
                wv
            }
        )
        if (!pageReady) {
            Box(Modifier.fillMaxSize().background(Color(0xFF070910)), contentAlignment = Alignment.Center) {
                Text("正在进入 3D 歌单墙…", color = Color.White.copy(0.5f), fontSize = 14.sp)
            }
        }
    }

    // 页面就绪 / 队列变化 → 推曲目 + 封面(并行)
    LaunchedEffect(pageReady, controller.queueItems.size) {
        if (pageReady) {
            pushTracks(webView, controller)
            pushCovers(webView, controller, context, scope)
        }
    }

    // 切歌 / 播放暂停 / 收藏 → 推全量状态(含歌词)
    LaunchedEffect(controller.isPlaying, controller.current?.id, controller.favoriteVersion, pageReady) {
        if (pageReady) pushFullState(webView, controller, context, scope)
    }

    // 播放中每 500ms 推进度
    LaunchedEffect(pageReady) {
        while (pageReady) {
            kotlinx.coroutines.delay(500)
            pushProgress(webView, controller)
        }
    }

    // 背景预设/自定义/跟随封面(cover 随切歌更新)
    LaunchedEffect(pageReady, bgPreset, customBg, controller.current?.id) {
        if (pageReady) pushBg(webView, context, controller, scope, bgPreset, customBg)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { runCatching { it.destroy() } }
            webView = null
        }
    }
}

/** JS→Native 桥接 */
private class WallBridge(
    private val controller: MusicController,
    private val onBack: () -> Unit,
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val webView: WebView,
    private val onReady: () -> Unit
) {
    @JavascriptInterface
    fun action(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("a")) {
            "ready" -> webView.post { onReady() }
            "play" -> {
                val id = o.optString("v")
                val idx = controller.queueItems.indexOfFirst { it.id == id }
                if (idx >= 0) controller.playIndex(idx)
            }
            "toggle" -> controller.toggle()
            "next" -> controller.next()
            "prev" -> controller.prev()
            "seek" -> controller.seekTo(o.optInt("v"))
            "favorite" -> {
                val id = o.optString("v")
                val track = controller.queueItems.find { it.id == id } ?: return
                if (track.id == controller.current?.id) {
                    controller.toggleFavorite()
                } else {
                    val now = FavoritesStore.toggle(context, track)
                    val script = "window.__setLiked && window.__setLiked(${JSONObject.quote(id)}, $now);"
                    runCatching { webView.evaluateJavascript(script, null) }
                }
            }
            "close" -> onBack()
        }
    }
}

/** 推送曲目列表(不含封面,封面单独 __setCover 推) */
private fun pushTracks(webView: WebView?, controller: MusicController) {
    val wv = webView ?: return
    val arr = JSONArray()
    for (t in controller.queueItems) {
        arr.put(JSONObject().apply {
            put("id", t.id)
            put("title", t.title)
            put("artist", t.artistDisplay())
        })
    }
    runCatching { wv.evaluateJavascript("window.__setTracks && window.__setTracks(${JSONObject.quote(arr.toString())});", null) }
}

/** 分批加载封面 → base64 → __setCover(可见区域优先,后续分批异步) */
private fun pushCovers(
    webView: WebView?,
    controller: MusicController,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val wv = webView ?: return
    val items = controller.queueItems
    val firstBatch = 12
    items.take(firstBatch).forEach { t -> scope.launch { pushSingleCover(wv, context, t) } }
    scope.launch {
        for (batch in items.drop(firstBatch).chunked(8)) {
            kotlinx.coroutines.delay(60)
            batch.forEach { t -> launch { pushSingleCover(wv, context, t) } }
        }
    }
}

private suspend fun pushSingleCover(wv: WebView, context: Context, t: go.examate.hyp.music.QueueItem) {
    val bmp = withContext(Dispatchers.IO) { AlbumArtLoader.load(context, t) }
    val dataUri = bmp?.toDataUri()
    if (dataUri != null) {
        runCatching { wv.evaluateJavascript("window.__setCover && window.__setCover(${JSONObject.quote(t.id)}, ${JSONObject.quote(dataUri)});", null) }
    }
}

/** 推送全量状态(含歌词) */
private fun pushFullState(
    webView: WebView?,
    controller: MusicController,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val wv = webView ?: return
    val cur = controller.current
    val o = JSONObject().apply {
        put("isPlaying", controller.isPlaying)
        put("currentId", cur?.id ?: "")
        put("positionMs", controller.positionMs)
        put("durationMs", cur?.durationMs ?: 0L)
        put("title", cur?.title ?: "")
        put("artist", cur?.artistDisplay() ?: "")
        put("isFavorite", if (cur != null) FavoritesStore.isFavorite(context, cur.id) else false)
    }
    runCatching { wv.evaluateJavascript("window.__onPlayerEvent && window.__onPlayerEvent(${JSONObject.quote(o.toString())});", null) }
    if (cur != null) {
        scope.launch {
            val lines = withContext(Dispatchers.IO) { LyricParser.fetch(cur, context) }
            val arr = JSONArray()
            for (l in lines) arr.put(JSONObject().put("t", l.timeMs).put("text", l.text))
            val ls = "window.__onPlayerEvent && window.__onPlayerEvent(${JSONObject.quote(JSONObject().put("lyrics", arr).toString())});"
            runCatching { wv.evaluateJavascript(ls, null) }
        }
    }
}

/** 推送进度 */
private fun pushProgress(webView: WebView?, controller: MusicController) {
    val wv = webView ?: return
    val cur = controller.current ?: return
    val o = JSONObject().put("positionMs", controller.positionMs).put("durationMs", cur.durationMs)
    runCatching { wv.evaluateJavascript("window.__onPlayerEvent && window.__onPlayerEvent(${JSONObject.quote(o.toString())});", null) }
}

/** 推送背景:内置预设 / 跟随封面 / 自定义图片/视频 */
private fun pushBg(
    webView: WebView?,
    context: Context,
    controller: MusicController,
    scope: kotlinx.coroutines.CoroutineScope,
    preset: String,
    customBg: String?
) {
    val wv = webView ?: return
    when (preset) {
        "custom" -> {
            val path = customBg ?: return
            val file = File(path)
            if (!file.exists()) return
            val name = file.name.lowercase()
            if (name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".3gp") || name.endsWith(".mov")) {
                val bgDir = context.filesDir.resolve("wall3d_bg").also { it.mkdirs() }
                val dest = bgDir.resolve(file.name)
                scope.launch {
                    withContext(Dispatchers.IO) { if (!dest.exists()) file.copyTo(dest, overwrite = true) }
                    val js = "window.__setBg && window.__setBg(${JSONObject.quote(JSONObject().put("type", "video").put("src", "/wallbg/${file.name}").toString())});"
                    runCatching { wv.evaluateJavascript(js, null) }
                }
            } else {
                scope.launch {
                    val dataUri = withContext(Dispatchers.IO) { fileToDataUri(file) }
                    if (dataUri != null) {
                        val js = "window.__setBg && window.__setBg(${JSONObject.quote(JSONObject().put("type", "image").put("src", dataUri).toString())});"
                        runCatching { wv.evaluateJavascript(js, null) }
                    }
                }
            }
        }
        "cover" -> {
            val cur = controller.current ?: return
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { AlbumArtLoader.load(context, cur) }
                val dataUri = bmp?.toDataUri()
                if (dataUri != null) {
                    val js = "window.__setBg && window.__setBg(${JSONObject.quote(JSONObject().put("type", "image").put("src", dataUri).toString())});"
                    runCatching { wv.evaluateJavascript(js, null) }
                }
            }
        }
        else -> {
            runCatching { wv.evaluateJavascript("window.__setBgPreset && window.__setBgPreset(${JSONObject.quote(preset)});", null) }
        }
    }
}

/** Bitmap → data:image/jpeg;base64,... */
private fun Bitmap.toDataUri(): String {
    val baos = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 80, baos)
    return "data:image/jpeg;base64,${Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)}"
}

/** 图片文件 → data URI(带采样,防 OOM) */
private fun fileToDataUri(file: File): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    val dec = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeFile(file.absolutePath, dec) ?: return null
    return bmp.toDataUri()
}
