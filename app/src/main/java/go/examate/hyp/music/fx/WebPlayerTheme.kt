package go.examate.hyp.music.fx

import android.annotation.SuppressLint
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import go.examate.hyp.music.LyricLine
import go.examate.hyp.music.LyricParser
import go.examate.hyp.music.MusicController
import go.examate.hyp.music.PlayMode
import go.examate.hyp.music.PlayerColors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网页播放器视图 — 播放页主题的"网页预设"载体(规范见 assets/player_fx/PLAYER_THEME_SPEC.md)。
 *
 * 层级:背景/特效层之上,整页替换原生播放内容;保留原生收起按钮兜底,
 * 位置可由页面声明:<meta name="player-theme-close" content="top-left|top-right|bottom-left|bottom-right|none">
 * (默认 top-left;none 时不显示兜底按钮,页面需自行经 PlayerBridge.action({a:close}) 收起)。
 *
 * 桥协议:
 *  - 原生 → 页面:window.__onPlayerEvent(jsonStr),字段 isPlaying/positionMs/durationMs/
 *    progress/title/artist/playMode/speed/isFavorite/colors/lyricIndex/lyrics[{t,text}]
 *    状态同步时机:切歌/播放暂停/模式/倍速/收藏变化均会全量重推(页面按钮状态即时同步)
 *  - 页面 → 原生:window.PlayerBridge.action(jsonStr),
 *    a: toggle/next/prev/seek(v:ms)/mode(v:list_loop|single_loop|shuffle|order)/
 *       speed(v:倍率)/favorite/close/queue
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayerView(
    controller: MusicController,
    colors: PlayerColors,
    lyrics: List<LyricLine>,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    // 主题页加载一次:页面内容 + 收起按钮位置声明(meta player-theme-close)
    val themeHtml = remember {
        val entry = PlayerFxStore.playerThemeEntry(context, PlayerFxStore.selectedPlayerThemeId(context))
        when {
            entry == null -> ""
            entry.builtin -> runCatching {
                context.assets.open(entry.file).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrDefault("")
            else -> runCatching { java.io.File(entry.file).readText() }.getOrDefault("")
        }
    }
    val closePos = remember(themeHtml) {
        Regex("player-theme-close\"\\s+content=\"([A-Za-z-]+)\"", RegexOption.IGNORE_CASE)
            .find(themeHtml)?.groupValues?.getOrNull(1)?.lowercase() ?: "top-left"
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.Transparent.toArgb())
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                // 页面 → 原生控制桥(WebView 线程回调,统一切主线程执行播放器操作)
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun action(json: String) {
                        runCatching {
                            val o = JSONObject(json)
                            val main = android.os.Handler(Looper.getMainLooper())
                            when (o.optString("a")) {
                                "toggle" -> main.post { controller.toggle() }
                                "next" -> main.post { controller.next() }
                                "prev" -> main.post { controller.prev() }
                                "seek" -> main.post { controller.seekTo(o.optInt("v", 0)) }
                                "mode" -> main.post { controller.changePlayMode(PlayMode.byId(o.optString("v"))) }
                                "speed" -> main.post { controller.changeSpeed(o.optDouble("v", 1.0).toFloat()) }
                                "favorite" -> main.post { controller.toggleFavorite() }
                                "queue" -> main.post { onOpenQueue() }
                                "close" -> main.post { onClose() }
                            }
                        }
                    }
                }, "PlayerBridge")

                val html = themeHtml
                loadDataWithBaseURL("file:///android_asset/player_fx/", html, "text/html", "utf-8", null)
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageLoaded = true
                    }
                }
                webView = this
            }
        }
    )

    // ═══ 状态推送 ═══
    fun pushState(includeLyrics: Boolean) {
        val wv = webView ?: return
        if (!pageLoaded) return
        val track = controller.current
        val dur = (track?.durationMs ?: 0L).coerceAtLeast(1L)
        val pos = controller.positionMs
        val json = JSONObject().apply {
            put("isPlaying", controller.isPlaying)
            put("positionMs", pos)
            put("durationMs", track?.durationMs ?: 0L)
            put("progress", (pos.toFloat() / dur).coerceIn(0f, 1f).toDouble())
            put("title", track?.title ?: "")
            put("artist", track?.artist ?: "")
            put("playMode", controller.playMode.id)
            put("speed", controller.speed.toDouble())
            put("isFavorite", controller.isCurrentFavorite())
            put("lyricIndex", LyricParser.findCurrentIndex(lyrics, pos.toLong()))
            put("colors", JSONObject().apply {
                put("accent", colorHex(colors.accent))
                put("bgTop", colorHex(colors.fallbackTop))
                put("bgBottom", colorHex(colors.fallbackBottom))
            })
            if (includeLyrics) {
                val arr = JSONArray()
                lyrics.forEach { l -> arr.put(JSONObject().apply { put("t", l.timeMs); put("text", l.text) }) }
                put("lyrics", arr)
            }
        }
        val script = "window.__onPlayerEvent && window.__onPlayerEvent(${JSONObject.quote(json.toString())});"
        runCatching { wv.evaluateJavascript(script, null) }
    }

    // 切歌 / 播放暂停 / 模式 / 倍速 / 收藏 / 歌词加载完成:全量推送(含歌词)。
    // playMode/speed/favoriteVersion 入键 — 修复"按钮点击生效但页面状态不变"的问题
    LaunchedEffect(
        controller.isPlaying, controller.current?.id, lyrics, pageLoaded,
        controller.playMode, controller.speed, controller.favoriteVersion
    ) {
        pushState(includeLyrics = true)
    }
    // 页面加载完成后延迟补推,确保 JS 桥就绪
    LaunchedEffect(pageLoaded) {
        if (pageLoaded) {
            kotlinx.coroutines.delay(400)
            pushState(includeLyrics = true)
        }
    }
    // 播放中进度循环(不带歌词,负载小)
    LaunchedEffect(controller.isPlaying, pageLoaded) {
        while (controller.isPlaying && pageLoaded) {
            kotlinx.coroutines.delay(500)
            pushState(includeLyrics = false)
        }
    }
    LaunchedEffect(colors.accent, pageLoaded) { pushState(includeLyrics = false) }

    // ═══ 原生兜底收起按钮(位置由页面 meta player-theme-close 声明;none 时不显示) ═══
    if (closePos != "none") {
        val alignment = when (closePos) {
            "top-right" -> Alignment.TopEnd
            "bottom-left" -> Alignment.BottomStart
            "bottom-right" -> Alignment.BottomEnd
            else -> Alignment.TopStart
        }
        val verticalPad = if (closePos.startsWith("bottom")) 18.dp else 6.dp
        Box(
            Modifier.fillMaxSize().statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = verticalPad),
            contentAlignment = alignment
        ) {
            Box(
                Modifier.size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.btnFace)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起", tint = colors.btnIcon, modifier = Modifier.size(24.dp))
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { runCatching { it.destroy() } }
            webView = null
        }
    }
}

private fun colorHex(c: Color): String =
    "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
