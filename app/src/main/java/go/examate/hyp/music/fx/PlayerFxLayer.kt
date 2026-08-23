package go.examate.hyp.music.fx

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import go.examate.hyp.music.MusicController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import go.examate.hyp.music.PlayerColors
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import org.json.JSONObject

/**
 * 播放页 WebView 特效层 — 承载内置 three.js 3D 粒子页 / 用户自定义特效页。
 *
 * 层级:背景之上、内容之下;交互控件在内容层,不受影响。
 * 桥协议:原生 evaluateJavascript 调 window.__onPlayerEvent(json),
 *   字段:isPlaying / progress(0..1) / title / artist / colors{accent,bgTop,bgBottom}。
 * 页面以 assets/player_fx/ 为 baseUrl 加载,自定义页可用 ../libs/three.min.js 引内置库。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerFxLayer(
    controller: MusicController,
    colors: PlayerColors,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.Transparent.toArgb())
                // 硬件图层 + 透明底:WebView 透明背景在 Compose 中稳定渲染的前提
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                val entry = PlayerFxStore.selectedEntry(ctx)
                val html = if (entry.builtin) {
                    runCatching { ctx.assets.open(entry.file).use { it.readBytes().toString(Charsets.UTF_8) } }
                        .getOrDefault("")
                } else {
                    runCatching { java.io.File(entry.file).readText() }.getOrDefault("")
                }
                // baseUrl = assets/player_fx/ → ../libs/three.min.js 指向内置 three.js
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

    // ═══ 事件推送:切歌 / 播放暂停立即推,进度 1s 节流 ═══
    fun pushEvent(includeProgress: Boolean) {
        val wv = webView ?: return
        if (!pageLoaded) return
        val track = controller.current
        val dur = (track?.durationMs ?: 0L).coerceAtLeast(1L)
        val json = JSONObject().apply {
            put("isPlaying", controller.isPlaying)
            if (includeProgress) put("progress", (controller.positionMs.toFloat() / dur).coerceIn(0f, 1f).toDouble())
            put("title", track?.title ?: "")
            put("artist", track?.artist ?: "")
            put("colors", JSONObject().apply {
                put("accent", colorHex(colors.accent))
                put("bgTop", colorHex(colors.overlayTop))
                put("bgBottom", colorHex(colors.overlayBottom))
            })
        }
        val script = "window.__onPlayerEvent && window.__onPlayerEvent(${JSONObject.quote(json.toString())});"
        runCatching { wv.evaluateJavascript(script, null) }
    }

    LaunchedEffect(controller.isPlaying, controller.current?.id, pageLoaded) {
        pushEvent(includeProgress = true)
    }
    // 页面加载完成后延迟补推一次:确保 JS 桥已定义且首屏状态不丢(特效不可见的兼容修复)
    LaunchedEffect(pageLoaded) {
        if (pageLoaded) {
            kotlinx.coroutines.delay(400)
            pushEvent(includeProgress = true)
        }
    }
    LaunchedEffect(controller.isPlaying, pageLoaded) {
        while (controller.isPlaying && pageLoaded) {
            kotlinx.coroutines.delay(1000)
            pushEvent(includeProgress = true)
        }
    }
    LaunchedEffect(colors.accent, pageLoaded) {
        pushEvent(includeProgress = false)
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

/**
 * 外观与特效设置页(视觉设置):
 *  - 播放页主题:原生默认预设 / 网页播放器预设(整页替换,规范 PLAYER_THEME_SPEC.md)
 *  - 背景特效启用开关(默认关闭,不产生运行时开销)+ 特效页选择(仅 fx 模式条目)
 *  - 导入自定义页:含 <meta name="player-theme-mode" content="player"> 识别为网页播放器,否则为背景特效
 *  - 自定义页可用 ../libs/three.min.js 引内置 three.js
 */
@Composable
fun PlayerFxSettingsScreen(onOpenSpec: () -> Unit = {}) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val enabled = remember(tick) { PlayerFxStore.isEnabled(context) }
    val selectedId = remember(tick) { PlayerFxStore.selectedId(context) }
    val entries = remember(tick) { PlayerFxStore.loadAll(context) }
    val fxEntries = remember(tick) { entries.filter { it.mode == PlayerFxEntry.MODE_FX } }
    val playerEntries = remember(tick) { entries.filter { it.mode == PlayerFxEntry.MODE_PLAYER } }
    val themeId = remember(tick) { PlayerFxStore.selectedPlayerThemeId(context) }

    // 导入:系统文件选择器选 .html
    val pickHtml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val html = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull().orEmpty()
            val entry = PlayerFxStore.import(context, html)
            if (entry != null) {
                // 按类型自动归位:网页播放器 → 主题选中项;背景特效 → 特效选中项
                if (entry.mode == PlayerFxEntry.MODE_PLAYER) {
                    PlayerFxStore.setSelectedPlayerTheme(context, entry.id)
                    ToastUtil.show(context, "已导入主题:${entry.name}")
                } else {
                    PlayerFxStore.setSelected(context, entry.id)
                    ToastUtil.show(context, "已导入特效:${entry.name}")
                }
            } else {
                ToastUtil.show(context, "导入失败:非有效 HTML 页面")
            }
            tick++
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 52.dp, bottom = 150.dp)
    ) {
        // ═══ 播放页主题(预设选择) ═══
        Text("播放页主题", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        ThemeOptionRow(
            name = "原生默认", desc = "内置预设 · 沉浸主题 + Apple Music 歌词",
            selected = themeId == PlayerFxStore.THEME_NATIVE,
            deletable = false,
            onClick = { PlayerFxStore.setSelectedPlayerTheme(context, PlayerFxStore.THEME_NATIVE); tick++ },
            onDelete = {}
        )
        playerEntries.forEach { e ->
            ThemeOptionRow(
                name = e.name, desc = if (e.builtin) "内置预设 · 桥接规范参考实现" else "自定义导入 · 网页播放器",
                selected = themeId == e.id,
                deletable = !e.builtin,
                onClick = { PlayerFxStore.setSelectedPlayerTheme(context, e.id); tick++ },
                onDelete = { PlayerFxStore.delete(context, e.id); tick++ }
            )
        }
        Spacer(Modifier.height(20.dp))

        // ═══ 背景特效(开关 + 特效页选择) ═══
        Text("背景特效", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用背景特效", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                LiquidGlassToggle(selected = enabled, onSelect = {
                    PlayerFxStore.setEnabled(context, it)
                    tick++
                })
            }
        }
        Spacer(Modifier.height(10.dp))
        fxEntries.forEach { e ->
            val selected = selectedId == e.id
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp)
            ) {
                FrostedGlassCard(
                    modifier = Modifier.weight(1f).clickable {
                        PlayerFxStore.setSelected(context, e.id)
                        tick++
                    }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
                                .background(if (selected) Primary else Color.Transparent)
                                .padding(4.dp)
                        ) {
                            if (selected) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(Color.White))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(if (e.builtin) "内置 · 基于 three.js" else "自定义导入", color = TextHint, fontSize = 11.sp)
                        }
                        if (!e.builtin) {
                            Icon(
                                Icons.Filled.Delete, contentDescription = "删除",
                                tint = TextHint,
                                modifier = Modifier.size(18.dp).clickable {
                                    PlayerFxStore.delete(context, e.id)
                                    tick++
                                }
                            )
            }
        }
    }
}
}

        // ═══ 导入自定义页(自动识别模式) ═══
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Primary.copy(0.1f))
                .clickable { pickHtml.launch(arrayOf("text/html", "*/*")) }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Add, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("导入自定义特效/主题页", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(10.dp))
        // ═══ 主题规范(页面化展示,支持复制/导出) ═══
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Primary.copy(0.1f))
                .clickable { onOpenSpec() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("查看网页主题规范(可复制/导出)", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        // ═══ 3D 歌单墙 ═══
        Spacer(Modifier.height(20.dp))
        Text("3D 歌单墙", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("3D 歌单墙", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("点击播放页封面进入 3D 歌单墙", color = TextHint, fontSize = 11.sp)
                }
                LiquidGlassToggle(selected = remember(tick) { PlayerFxStore.isWall3DEnabled(context) }, onSelect = {
                    PlayerFxStore.setWall3DEnabled(context, it)
                    tick++
                })
            }
        }
        Spacer(Modifier.height(10.dp))
        // 3D 歌单墙背景设置
        val wall3dOn = remember(tick) { PlayerFxStore.isWall3DEnabled(context) }
        if (wall3dOn) {
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("背景预设", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 10.dp))
                    val bgPresets = listOf(
                        "space" to "默认深空",
                        "custom" to "自定义"
                    )
                    val curBg = remember(tick) { PlayerFxStore.wall3DBgPreset(context) }
                    val scope = rememberCoroutineScope()
                    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        if (uri != null) scope.launch {
                            val f = withContext(Dispatchers.IO) { copyBgToFiles(context, uri, true) }
                            if (f != null) { PlayerFxStore.setWall3DCustomBg(context, f.absolutePath); PlayerFxStore.setWall3DBgPreset(context, "custom"); tick++ }
                            else ToastUtil.show(context, "导入图片失败")
                        }
                    }
                    val vidPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        if (uri != null) scope.launch {
                            val f = withContext(Dispatchers.IO) { copyBgToFiles(context, uri, false) }
                            if (f != null) { PlayerFxStore.setWall3DCustomBg(context, f.absolutePath); PlayerFxStore.setWall3DBgPreset(context, "custom"); tick++ }
                            else ToastUtil.show(context, "导入视频失败")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bgPresets.forEach { (id, name) ->
                            val sel = curBg == id
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) SelectedBg else SelectedBg.copy(0.08f))
                                    .clickable { PlayerFxStore.setWall3DBgPreset(context, id); tick++ }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(name, color = if (sel) Color.White else TextSecondary,
                                    fontSize = 11.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1)
                            }
                        }
                    }
                    if (curBg == "custom") {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(Primary.copy(0.12f)).clickable { imgPicker.launch("image/*") }
                                .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                Text("导入图片", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(Primary.copy(0.12f)).clickable { vidPicker.launch("video/*") }
                                .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                Text("导入视频", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        val customPath = remember(tick) { PlayerFxStore.wall3DCustomBg(context) }
                        if (customPath != null) {
                            val fn = customPath.substringAfterLast('/')
                            Text("当前: $fn", color = TextHint, fontSize = 10.sp,
                                modifier = Modifier.padding(top = 6.dp, start = 2.dp), maxLines = 1)
                        } else {
                            Text("请导入图片或视频(视频将静音循环播放)", color = TextHint, fontSize = 10.sp,
                                modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                        }
                    }

                }
            }
        }

        // ═══ 歌词样式(默认收起) ═══
        Spacer(Modifier.height(20.dp))
        LyricStyleSection(tick = { tick++ })
    }
}

/**
 * 歌词样式设置区 — 逐字歌词开关 + 高光歌词大小 / 逐字放大倍率 / 非播放行透明度。
 * 默认收起,点击标题展开;值存 PlayerFxStore(player_fx prefs),播放页打开时读取。
 */
@Composable
private fun LyricStyleSection(tick: () -> Unit) {
    val context = LocalContext.current
    var karaoke by remember { mutableStateOf(PlayerFxStore.isKaraokeEnabled(context)) }
    var activeSize by remember { mutableStateOf(PlayerFxStore.lyricActiveSize(context)) }
    var kScale by remember { mutableStateOf(PlayerFxStore.lyricKaraokeScale(context)) }
    var inactiveAlpha by remember { mutableStateOf(PlayerFxStore.lyricInactiveAlpha(context)) }
    var expanded by remember { mutableStateOf(false) }

    Text("歌词样式", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("逐字歌词", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("当前行逐字点亮并放大(Apple Music 效果)", color = TextHint, fontSize = 11.sp)
                }
                LiquidGlassToggle(selected = karaoke, onSelect = {
                    karaoke = it
                    PlayerFxStore.setKaraokeEnabled(context, it)
                    tick()
                })
            }
            // 展开切换:点击行触发,默认收起
            Box(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(vertical = 6.dp)
            ) {
                Text(if (expanded) "收起调整 ▲" else "展开调整 ▼",
                    color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp))
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                LyricSlider(
                    label = "高光歌词大小", value = activeSize, range = 16f..28f,
                    display = "%.0f sp".format(activeSize)
                ) { v -> activeSize = v; PlayerFxStore.setLyricActiveSize(context, v) }
                Spacer(Modifier.height(10.dp))
                LyricSlider(
                    label = "逐字放大倍率", value = kScale, range = 1f..1.6f,
                    display = "%.2f x".format(kScale)
                ) { v -> kScale = v; PlayerFxStore.setLyricKaraokeScale(context, v) }
                Spacer(Modifier.height(10.dp))
                LyricSlider(
                    label = "非播放歌词透明度", value = inactiveAlpha, range = 0.1f..0.8f,
                    display = "%.0f %%".format(inactiveAlpha * 100)
                ) { v -> inactiveAlpha = v; PlayerFxStore.setLyricInactiveAlpha(context, v) }
            }
        }
    }
}

/** 歌词样式滑杆行:标题 + 当前值 + 拖动条(onValueChangeFinished 落盘,拖动中只跟手) */
@Composable
private fun LyricSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onCommit: (Float) -> Unit
) {
    var drag by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.width(128.dp))
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = { onCommit(drag) },
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Primary,
                activeTrackColor = Primary.copy(0.9f),
                inactiveTrackColor = Primary.copy(0.18f)
            )
        )
        Text(display, color = TextHint, fontSize = 11.sp, modifier = Modifier.width(52.dp))
    }
}

/** 主题选项行(原生默认/网页播放器预设) */
@Composable
private fun ThemeOptionRow(
    name: String,
    desc: String,
    selected: Boolean,
    deletable: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        FrostedGlassCard(
            modifier = Modifier.weight(1f).clickable { onClick() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
                        .background(if (selected) Primary else Color.Transparent)
                        .padding(4.dp)
                ) {
                    if (selected) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(Color.White))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(desc, color = TextHint, fontSize = 11.sp)
                }
                if (deletable) {
                    Icon(
                        Icons.Filled.Delete, contentDescription = "删除",
                        tint = TextHint,
                        modifier = Modifier.size(18.dp).clickable { onDelete() }
                    )
                }
            }
        }
    }
}

private fun copyBgToFiles(context: Context, uri: Uri, isImage: Boolean): java.io.File? {
    return try {
        val dir = java.io.File(context.filesDir, "wall3d_bg")
        if (!dir.exists()) dir.mkdirs()
        val ext = if (isImage) "jpg" else "mp4"
        val out = java.io.File(dir, "custom_bg.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return null
        out
    } catch (e: Exception) { null }
}
