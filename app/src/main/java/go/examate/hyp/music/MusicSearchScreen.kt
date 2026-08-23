package go.examate.hyp.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.music.lx.LxSong
import go.examate.hyp.music.lx.PlatformSearcher
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.SearchField
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 搜索类型(歌曲 / 歌单) — 首页搜索区与搜索页共享 */
enum class MusicSearchType(val label: String) {
    MUSIC("歌曲"), SONGLIST("歌单")
}

/**
 * 搜索结果页 — 现代化筛选:
 *  - 顶栏:返回 + 搜索框
 *  - 筛选行:音源横向 chips(选中渐变)+ 歌曲/歌单胶囊分段,切换即重搜
 *  - 聚合搜索(all):并行搜所有源,单源失败不阻断;结果 3 分钟短期缓存
 *  - 歌单搜索:官方歌单 API(kw/kg/tx/wy)
 */
@Composable
fun MusicSearchScreen(
    controller: MusicController,
    initialKeyword: String,
    initialSource: String = "all",
    initialType: MusicSearchType = MusicSearchType.MUSIC,
    onSourceChange: (String) -> Unit = {},
    onTypeChange: (MusicSearchType) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var sourceCode by remember { mutableStateOf(initialSource) }
    var searchType by remember { mutableStateOf(initialType) }
    var keyword by remember { mutableStateOf(initialKeyword) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var songLists by remember { mutableStateOf<List<KtSongList>>(emptyList()) }
    var lastKeyword by remember { mutableStateOf("") }
    var openSongList by remember { mutableStateOf<KtSongList?>(null) }  // 歌单详情导航

    // ═══ 分页加载更多 ═══
    var page by remember { mutableIntStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun doSearch(kw: String) {
        val k = kw.trim()
        if (k.isEmpty() || searching) return
        keyboard?.hide()
        searching = true
        lastKeyword = k
        page = 1
        exhausted = false
        scope.launch {
            if (searchType == MusicSearchType.MUSIC) {
                results = withContext(Dispatchers.IO) { searchSongsCached(context, sourceCode, k, 1) }
                songLists = emptyList()
            } else {
                // 歌单搜索按所选源(修复:之前切换源仍返回相同聚合结果)
                songLists = withContext(Dispatchers.IO) { searchSongListsCached(context, sourceCode, k) }
                results = emptyList()
            }
            searching = false
        }
    }

    fun loadMore() {
        if (searching || loadingMore || exhausted || searchType != MusicSearchType.MUSIC) return
        if (lastKeyword.isBlank() || results.isEmpty()) return
        loadingMore = true
        scope.launch {
            val more = withContext(Dispatchers.IO) { searchSongsCached(context, sourceCode, lastKeyword, page + 1) }
            if (more.isEmpty()) {
                exhausted = true
            } else {
                val existing = results.map { it.id }.toSet()
                results = results + more.filter { it.id !in existing }
                page++
            }
            loadingMore = false
        }
    }

    // 滚动接近底部自动加载下一页
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            last to total
        }.collect { pair ->
            val (lastIndex, total) = pair
            if (lastIndex >= 0 && total > 0 && lastIndex >= total - 4) loadMore()
        }
    }

    fun switchSource(code: String) {
        if (sourceCode == code) return
        sourceCode = code
        onSourceChange(code)
        if (lastKeyword.isNotBlank() && !searching) doSearch(lastKeyword)
        else { results = emptyList(); songLists = emptyList() }
    }

    fun switchType(type: MusicSearchType) {
        if (searchType == type) return
        searchType = type
        onTypeChange(type)
        if (lastKeyword.isNotBlank() && !searching) doSearch(lastKeyword)
        else { results = emptyList(); songLists = emptyList() }
    }

    // 进入页面立即用 initialKeyword 执行搜索
    LaunchedEffect(Unit) {
        if (initialKeyword.isNotBlank()) doSearch(initialKeyword.trim())
    }

    // 歌单详情
    openSongList?.let { sl ->
        SongListDetailScreen(
            controller = controller,
            songList = sl,
            onBack = { openSongList = null }
        )
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ═══ 顶栏:返回 + 搜索框 ═══
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
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
                onSearch = { doSearch(keyword) },
                placeholder = "听你想听",
                modifier = Modifier.weight(1f),
                showLeadingIcon = true
            )
        }

        // ═══ 筛选行:音源 chips(横滚)+ 歌曲/歌单分段 ═══
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
            ) {
                MusicSourceDefs.selectorListDynamic(context).forEach { s ->
                    SourceChip(selected = sourceCode == s.code, label = s.alias) { switchSource(s.code) }
                }
            }
            Spacer(Modifier.width(8.dp))
            SearchTypeSegment(type = searchType, onChange = { switchType(it) })
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
                searchType == MusicSearchType.SONGLIST && songLists.isNotEmpty() -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp, end = 14.dp, top = 8.dp, bottom = 100.dp
                    )
                ) {
                    itemsIndexed(songLists, key = { _, s -> "${s.platform}_${s.id}" }) { _, sl ->
                        SongListRow(sl) { openSongList = sl }
                    }
                }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (lastKeyword.isBlank()) "输入关键词搜索" else "无结果",
                            color = TextHint, fontSize = 13.sp
                        )
                        if (lastKeyword.isNotBlank() && searchType == MusicSearchType.MUSIC && sourceCode != "all") {
                            Spacer(Modifier.height(8.dp))
                            Text("试试「聚合大全」跨源搜索", color = go.examate.hyp.ui.theme.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp, end = 14.dp, top = 8.dp, bottom = 100.dp
                    )
                ) {
                    itemsIndexed(results, key = { _, t -> t.id }) { index, item ->
                        TrackRow(
                            item = item,
                            active = controller.current?.id == item.id,
                            isPlaying = controller.isPlaying && controller.current?.id == item.id,
                            onClick = { controller.playQueue(results, index) },
                            sourceLabel = sourceLabelOf(item.id)
                        )
                    }
                    // 底部加载状态
                    item(key = "__footer__") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                            when {
                                loadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TextHint)
                                    Spacer(Modifier.size(8.dp))
                                    Text("加载更多…", color = TextHint, fontSize = 12.sp)
                                }
                                exhausted -> Text("已加载全部结果", color = TextHint, fontSize = 12.sp)
                                else -> Text("继续上滑加载更多", color = TextHint, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 歌单结果行:封面 + 名称 + 作者 + 播放量 */
@Composable
private fun SongListRow(sl: KtSongList, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Primary.copy(0.14f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                bitmap = rememberSongListCover(sl.img),
                contentDescription = sl.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(sl.name, color = TextPrimary, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    sl.author.ifBlank { null }?.let { "创建者: $it" },
                    if (sl.playCount > 0) "${formatPlayCount(sl.playCount)} 收藏" else null,
                    if (sl.songCount > 0) "${sl.songCount} 首" else null,
                    MusicSourceDefs.aliasOf(sl.platform)
                ).joinToString(" · "),
                color = TextHint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun rememberSongListCover(url: String): androidx.compose.ui.graphics.ImageBitmap {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            if (url.isBlank()) null
            else runCatching { java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    return bitmap?.asImageBitmap() ?: songListPlaceholder()
}

@Composable
private fun songListPlaceholder(): androidx.compose.ui.graphics.ImageBitmap {
    val color = Primary.toArgb()
    return remember(color) {
        android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }
            .asImageBitmap()
    }
}

private fun formatPlayCount(n: Long): String = when {
    n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
    else -> n.toString()
}

/** 从 QueueItem.id("lx_kw_xxx")解析源别名 */
private fun sourceLabelOf(id: String): String? {
    if (!id.startsWith("lx_")) return null
    val rest = id.removePrefix("lx_")
    val sep = rest.indexOf('_')
    if (sep <= 0) return null
    return MusicSourceDefs.aliasOf(rest.substring(0, sep))
}

// ════════════════════════════════════════════
//  搜索(带 3 分钟短期缓存):相同 源+类型+关键词 不重复请求
// ════════════════════════════════════════════

private const val SEARCH_CACHE_TTL = 3 * 60_000L

/**
 * 按源搜索歌曲(3min 缓存,支持分页) → 统一 QueueItem。
 * 播放 resolver:统一走 PlayResolver(官方路线要求登录 / 脚本路线解析);bili 走官方 DASH。
 */
private suspend fun searchSongsCached(
    context: android.content.Context,
    sourceCode: String,
    keyword: String,
    page: Int = 1
): List<QueueItem> {
    val key = "search_songs_${sourceCode}_${keyword}_p$page"
    MusicDataCache.get(context, key, SEARCH_CACHE_TTL)?.let { cached ->
        val songs = songsFromJson(cached)
        if (songs.isNotEmpty()) return songs.map { it.toQueueItem(context) }
    }
    val songs = if (sourceCode == "all") {
        if (page <= 1) PlatformSearcher.searchAll(context, keyword)
        else PlatformSearcher.searchAllPage(context, keyword, page)
    } else {
        PlatformSearcher.search(context, sourceCode, keyword, page)
    }
    if (songs.isNotEmpty()) MusicDataCache.put(context, key, songsToJson(songs))
    return songs.map { it.toQueueItem(context) }
}

/** 歌单搜索(3min 缓存)— 按所选源:all 聚合 kw/kg/tx/wy,单源只搜该平台 */
private fun searchSongListsCached(context: android.content.Context, sourceCode: String, keyword: String): List<KtSongList> {
    val key = "search_songlists_${sourceCode}_$keyword"
    MusicDataCache.get(context, key, SEARCH_CACHE_TTL)?.let { cached ->
        val lists = songListsFromJson(cached)
        if (lists.isNotEmpty()) return lists
    }
    val lists = if (sourceCode == "all") SongListApi.searchAll(keyword)
    else SongListApi.search(sourceCode, keyword)
    if (lists.isNotEmpty()) MusicDataCache.put(context, key, songListsToJson(lists))
    return lists
}

private fun songsToJson(list: List<LxSong>): String {
    val arr = JSONArray()
    list.forEach { s ->
        arr.put(JSONObject().apply {
            put("platform", s.platform); put("songmid", s.songmid)
            put("name", s.name); put("artist", s.artist)
            put("durationMs", s.durationMs); put("picUrl", s.picUrl); put("albumId", s.albumId)
            put("qualities", JSONArray(s.qualities))
        })
    }
    return arr.toString()
}

private fun songsFromJson(json: String): List<LxSong> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val qArr = o.optJSONArray("qualities")
        val qualities = if (qArr != null) (0 until qArr.length()).map { qArr.optString(it) } else emptyList()
        LxSong(
            platform = o.optString("platform"), songmid = o.optString("songmid"),
            name = o.optString("name"), artist = o.optString("artist"),
            durationMs = o.optLong("durationMs"), picUrl = o.optString("picUrl"),
            albumId = o.optString("albumId"), qualities = qualities
        )
    }
}.getOrDefault(emptyList())

private fun songListsToJson(list: List<KtSongList>): String {
    val arr = JSONArray()
    list.forEach { s ->
        arr.put(JSONObject().apply {
            put("id", s.id); put("name", s.name); put("author", s.author); put("img", s.img)
            put("playCount", s.playCount); put("songCount", s.songCount)
            put("desc", s.desc); put("platform", s.platform)
        })
    }
    return arr.toString()
}

private fun songListsFromJson(json: String): List<KtSongList> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        KtSongList(
            id = o.optString("id"), name = o.optString("name"), author = o.optString("author"),
            img = o.optString("img"), playCount = o.optLong("playCount"),
            songCount = o.optLong("songCount"), desc = o.optString("desc"),
            platform = o.optString("platform", "kw")
        )
    }
}.getOrDefault(emptyList())
