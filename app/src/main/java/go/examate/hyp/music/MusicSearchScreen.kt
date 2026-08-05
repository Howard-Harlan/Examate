package go.examate.hyp.music

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import go.examate.hyp.music.lx.LxManager
import go.examate.hyp.music.lx.PlatformSearcher
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.SearchField
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索结果页 — 主页悬浮搜索区输入关键词后跳转过来。
 *
 * 顶栏:[返回] [浅白搜索框(内嵌平台 chip + 输入框)] [搜索]
 * - 平台 chip 在 wy/kg/kw 间循环切换(平台搜索独立,音源只负责解析播放 URL)
 * - placeholder 统一为"听你想听"(避免切换平台时长度变化)
 * - 输入框高度对齐容器,无裁切
 * - 收到 initialKeyword 时立即执行首次搜索
 */
@Composable
fun MusicSearchScreen(
    controller: MusicController,
    initialKeyword: String,
    onOpenSources: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // 用 tick 跟踪音源状态变化(ON_RESUME 时刷新,覆盖所有返回路径)
    var sourceTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) sourceTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasSource = remember(sourceTick) { LxManager(context).hasEnabledSource() }

    if (!hasSource) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Text("没有可用音源", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text("平台搜索需要音源解析播放地址。在「音源管理」导入并启用 LX 音源后即可搜索。",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Primary).clickable {
                    onOpenSources()
                    sourceTick++   // 从音源管理返回后刷新状态
                }
                .padding(horizontal = 24.dp, vertical = 11.dp)) {
                Text("去音源管理", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        return
    }

    val platforms = listOf("wy", "kg", "kw")
    var platformIdx by remember { mutableIntStateOf(0) }
    val platformCode = platforms[platformIdx.coerceIn(0, platforms.size - 1)]
    var keyword by remember { mutableStateOf(initialKeyword) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var lastKeyword by remember { mutableStateOf("") }

    // 进入页面立即用 initialKeyword 执行搜索
    LaunchedEffect(Unit) {
        if (initialKeyword.isNotBlank()) {
            searching = true
            val kw = initialKeyword.trim()
            lastKeyword = kw
            results = withContext(Dispatchers.IO) { searchPlatform(context, platformCode, kw) }
            searching = false
        }
    }

    fun doSearch() {
        val kw = keyword.trim()
        if (kw.isEmpty() || searching) return
        keyboard?.hide()
        searching = true
        lastKeyword = kw
        scope.launch {
            results = withContext(Dispatchers.IO) { searchPlatform(context, platformCode, kw) }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶栏:返回 + 搜索框
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
            Spacer(Modifier.width(8.dp))
            SearchField(
                value = keyword,
                onValueChange = { keyword = it },
                onSearch = { doSearch() },
                placeholder = "听你想听",
                modifier = Modifier.weight(1f),
                showLeadingIcon = false
            )
        }

        // 平台切换(wy/kg/kw) — 项目统一 tab 样式
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            platforms.forEachIndexed { i, code ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (platformIdx == i) Primary else SurfaceVariant)
                        .clickable {
                            if (platformIdx != i) {
                                platformIdx = i
                                // 切换平台后自动重新搜索(有关键词时)
                                if (lastKeyword.isNotBlank() && !searching) {
                                    searching = true
                                    scope.launch {
                                        results = withContext(Dispatchers.IO) {
                                            searchPlatform(context, platforms[i], lastKeyword)
                                        }
                                        searching = false
                                    }
                                } else {
                                    results = emptyList()
                                }
                            }
                        }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(code, color = if (platformIdx == i) Color.White else TextSecondary,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 结果区
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TextHint)
                        Spacer(Modifier.size(8.dp))
                        Text("搜索中…", color = TextHint, fontSize = 13.sp)
                    }
                }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (lastKeyword.isBlank()) "输入关键词搜索" else "无结果",
                        color = TextHint, fontSize = 13.sp
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp, end = 14.dp, top = 8.dp, bottom = 100.dp
                    )
                ) {
                    itemsIndexed(results, key = { _, t -> t.id }) { index, item ->
                        TrackRow(
                            item = item,
                            active = controller.current?.id == item.id,
                            isPlaying = controller.isPlaying && controller.current?.id == item.id,
                            onClick = { controller.playQueue(results, index) }
                        )
                    }
                }
            }
        }
    }
}

/** 多平台搜索 → 统一 QueueItem(LX 音源 resolver 播放时解析) */
private fun searchPlatform(context: android.content.Context, platform: String, keyword: String): List<QueueItem> {
    val manager = LxManager(context)
    return PlatformSearcher.search(platform, keyword).map { song ->
        QueueItem(
            id = "lx_${song.platform}_${song.songmid}", title = song.name, artist = song.artist,
            durationMs = song.durationMs, coverUrl = song.picUrl,
            resolver = { withContext(Dispatchers.IO) { manager.resolve(song) } }
        )
    }
}
