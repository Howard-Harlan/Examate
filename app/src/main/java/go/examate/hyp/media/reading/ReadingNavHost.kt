package go.examate.hyp.media.reading

import android.app.Activity
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.media.legado.BookSource
import go.examate.hyp.media.legado.BookSourceParser
import go.examate.hyp.media.legado.BookSourceStore
import go.examate.hyp.media.legado.SearchBook
import go.examate.hyp.media.reader.AudioPlayerScreen
import go.examate.hyp.media.reader.LegadoTextProvider
import go.examate.hyp.media.reader.LocalTextProvider
import go.examate.hyp.media.reader.ReaderChapter
import go.examate.hyp.media.reader.ReaderCoreScreen
import go.examate.hyp.media.reader.ReaderTocView
import go.examate.hyp.media.reader.TextProvider
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/** 阅读内部路由(状态机,不引 navigation 库) */
private sealed class ReadingRoute {
    object Shelf : ReadingRoute()                 // 首屏:书架
    /** 阅读设置;initialTab:0=阅读偏好 1=内容管理(从书源管理返回时传 1) */
    data class Settings(val initialTab: Int = 0) : ReadingRoute()
    object Sources : ReadingRoute()               // 书源管理
    data class Search(val type: Int) : ReadingRoute()
    data class Detail(val book: SearchBook) : ReadingRoute()
    data class Toc(val book: SearchBook, val tocUrl: String) : ReadingRoute()
    data class Reader(
        val provider: TextProvider, val chapters: List<ReaderChapter>, val index: Int,
        val bookId: String? = null, val startScroll: Int = 0
    ) : ReadingRoute()
    // 听书播放器(音频源 type=1):provider 取音频 URL,MediaPlayer 播放
    data class AudioReader(
        val provider: TextProvider, val book: SearchBook, val tocUrl: String,
        val chapters: List<ReaderChapter>, val index: Int, val bookId: String?
    ) : ReadingRoute()
    // AI 写作页:从书架入口(无上下文)或章节目录入口(IF线,带原书 provider+chapters)
    data class AiWriter(
        val mode: WriterMode,
        val seedText: String = "",
        val bookName: String = "",
        val chapters: List<ReaderChapter> = emptyList(),
        val provider: TextProvider? = null
    ) : ReadingRoute()
}

/**
 * 阅读模块导航宿主 — 书源管理 Hub ⇄ 搜索。
 * 详情/目录/阅读器在阶段3 接入。
 *
 * @param refreshTick 外部导入源后 +1 触发列表刷新
 * @param onImportSource 由 Activity 提供文件选择器
 * @param onImportRule 由 Activity 提供净化规则文件选择器
 */
@Composable
fun ReadingNavHost(
    refreshTick: Int,
    pendingLocalFile: java.io.File? = null,
    onImportSource: () -> Unit,
    onOpenLocalFile: () -> Unit = {},
    onImportRule: () -> Unit = {},
    initialBookId: String? = null
) {
    val context = LocalContext.current
    var route by remember { mutableStateOf<ReadingRoute>(ReadingRoute.Shelf) }
    var shelfTick by remember { mutableIntStateOf(0) }   // 书架变更后刷新
    // deep link: 外部(如 agent open_book 工具)通过 bookId 直接续读到上次位置
    androidx.compose.runtime.LaunchedEffect(initialBookId) {
        val bid = initialBookId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val sb = BookshelfStore.find(context, bid) ?: return@LaunchedEffect
        continueRead(context, sb) { route = it }
    }
    // 外部选中本地文件后,进入通用阅读器(LocalTextProvider);本地书自动登记书架以记进度
    androidx.compose.runtime.LaunchedEffect(pendingLocalFile) {
        val f = pendingLocalFile ?: return@LaunchedEffect
        val provider = LocalTextProvider(f)
        val chapters = provider.loadChapters()
        val bookId = ShelfBook.localId(f.absolutePath)
        if (!BookshelfStore.contains(context, bookId)) {
            BookshelfStore.add(context, ShelfBook(
                bookId = bookId, name = provider.title, kind = "local", isLocal = true,
                bookUrl = f.absolutePath
            ))
            shelfTick++
        }
        val sb = BookshelfStore.find(context, bookId)
        route = ReadingRoute.Reader(provider, chapters, sb?.lastChapterIndex ?: 0, bookId, sb?.scrollOffset ?: 0)
    }
    BackHandler(enabled = route !is ReadingRoute.Shelf) {
        route = when (val cur = route) {
            is ReadingRoute.Settings -> ReadingRoute.Shelf
            is ReadingRoute.Sources -> ReadingRoute.Settings(initialTab = 1)
            is ReadingRoute.Search -> ReadingRoute.Shelf
            is ReadingRoute.Detail -> ReadingRoute.Search(bookTypeOf(context, cur.book.sourceUrl))
            is ReadingRoute.Toc -> ReadingRoute.Detail(cur.book)
            is ReadingRoute.Reader -> ReadingRoute.Shelf
            is ReadingRoute.AudioReader -> ReadingRoute.Toc(cur.book, cur.tocUrl)
            is ReadingRoute.AiWriter -> if (cur.provider != null && cur.chapters.isNotEmpty()) {
                // 章节目录入口 → 回到目录(需重建 Toc 路由需要的 SearchBook)
                ReadingRoute.Shelf
            } else ReadingRoute.Shelf
            else -> ReadingRoute.Shelf
        }
    }

    when (val r = route) {
        is ReadingRoute.Shelf -> BookshelfScreen(
            refreshTick = refreshTick + shelfTick,
            onBack = { (context as? android.app.Activity)?.finish() },
            onContinueRead = { sb -> continueRead(context, sb) { route = it } },
            onSearch = { route = ReadingRoute.Search(0) },
            onSettings = { route = ReadingRoute.Settings(initialTab = 0) },
            onAiWriter = { route = ReadingRoute.AiWriter(WriterMode.FullCreation) }
        )
        is ReadingRoute.Settings -> ReadingSettingsScreen(
            onBack = { route = ReadingRoute.Shelf },
            onManageSources = { route = ReadingRoute.Sources },
            onOpenLocalFile = onOpenLocalFile,
            onImportRule = onImportRule,
            initialTab = r.initialTab
        )
        is ReadingRoute.Sources -> ReadingHubScreen(
            refreshTick = refreshTick,
            onImportSource = onImportSource,
            onBack = { route = ReadingRoute.Settings(initialTab = 1) }
        )
        is ReadingRoute.Search -> {
            val allEnabled = remember(refreshTick) {
                BookSourceStore.loadEnabled(context, null) // 全部已启用(不限类型)
            }
            BookSearchScreen(
                allSources = allEnabled,
                initialType = r.type,
                onBack = { route = ReadingRoute.Shelf },
                onOpenBook = { book -> route = ReadingRoute.Detail(book) }
            )
        }
        is ReadingRoute.Detail -> {
            val source = remember(r.book.sourceUrl) {
                BookSourceStore.loadAll(context).find { it.bookSourceUrl == r.book.sourceUrl }
            }
            if (source == null) { route = ReadingRoute.Shelf } else {
                val bookId = ShelfBook.legadoId(r.book.sourceUrl, r.book.bookUrl)
                val inShelf = remember(bookId, shelfTick) { BookshelfStore.contains(context, bookId) }
                BookDetailScreen(
                    book = r.book, source = source,
                    onBack = { route = ReadingRoute.Search(source.bookSourceType) },
                    onOpenToc = { tocUrl -> route = ReadingRoute.Toc(r.book, tocUrl) },
                    inShelf = inShelf,
                    onAddShelf = { name, author, cover, tocUrl ->
                        BookshelfStore.add(context, ShelfBook(
                            bookId = bookId, name = name, author = author, coverUrl = cover,
                            kind = when (source.bookSourceType) { 2 -> "comic"; 1 -> "audio"; else -> "book" },
                            sourceUrl = r.book.sourceUrl, bookUrl = r.book.bookUrl, tocUrl = tocUrl
                        ))
                        shelfTick++
                    }
                )
            }
        }
        is ReadingRoute.Toc -> {
            val source = remember(r.book.sourceUrl) {
                BookSourceStore.loadAll(context).find { it.bookSourceUrl == r.book.sourceUrl }
            }
            if (source == null) { route = ReadingRoute.Shelf } else {
                val provider = remember(r.book.bookUrl, r.tocUrl) {
                    LegadoTextProvider(context, source, r.tocUrl, r.book.name)
                }
                val bookId = ShelfBook.legadoId(r.book.sourceUrl, r.book.bookUrl)
                ReaderTocView(
                    provider = provider,
                    bookName = r.book.name,
                    onBack = { route = ReadingRoute.Detail(r.book) },
                    onAiWriter = { chapters ->
                        route = ReadingRoute.AiWriter(
                            mode = WriterMode.IfLine,
                            bookName = r.book.name,
                            chapters = chapters,
                            provider = provider
                        )
                    },
                    onOpenChapter = { chapters, idx ->
                        val sb = BookshelfStore.find(context, bookId)
                        // 首次阅读自动加入书架(最近在看),无需手动点收藏
                        val effectiveBookId = if (sb == null) {
                            BookshelfStore.add(context, ShelfBook(
                                bookId = bookId, name = r.book.name, author = r.book.author,
                                coverUrl = r.book.coverUrl,
                                kind = when (source.bookSourceType) { 2 -> "comic"; 1 -> "audio"; else -> "book" },
                                sourceUrl = r.book.sourceUrl, bookUrl = r.book.bookUrl, tocUrl = r.tocUrl,
                                lastChapterIndex = idx, lastChapterTitle = chapters.getOrNull(idx)?.title ?: ""
                            ))
                            shelfTick++
                            bookId
                        } else bookId
                        // 听书源(type=1)调用音乐播放组件;其他走文本阅读器
                        if (source.bookSourceType == 1) {
                            route = ReadingRoute.AudioReader(provider, r.book, r.tocUrl, chapters, idx, effectiveBookId)
                        } else {
                            route = ReadingRoute.Reader(provider, chapters, idx, effectiveBookId)
                        }
                    }
                )
            }
        }
        is ReadingRoute.Reader -> {
            ReaderCoreScreen(
                provider = r.provider, chapters = r.chapters, startIndex = r.index,
                startScroll = r.startScroll,
                onBack = { route = ReadingRoute.Shelf },
                onProgress = if (r.bookId != null) { idx, title, scroll ->
                    BookshelfStore.updateProgress(context, r.bookId, idx, title, scroll)
                } else null
            )
        }
        is ReadingRoute.AudioReader -> {
            AudioPlayerScreen(
                provider = r.provider, chapters = r.chapters, startIndex = r.index,
                bookTitle = r.book.name,
                onBack = { route = ReadingRoute.Toc(r.book, r.tocUrl) },
                onProgress = if (r.bookId != null) { idx, title ->
                    BookshelfStore.updateProgress(context, r.bookId, idx, title, 0)
                } else null
            )
        }
        is ReadingRoute.AiWriter -> {
            AiWriterScreen(
                mode = r.mode,
                seedText = r.seedText,
                bookName = r.bookName,
                chapters = r.chapters,
                provider = r.provider,
                onBack = { route = ReadingRoute.Shelf }
            )
        }
    }
}

/** 从书架条目重建阅读来源并进入阅读器(续读到上次位置) */
private fun continueRead(context: android.content.Context, sb: ShelfBook, go: (ReadingRoute) -> Unit) {
    // Reader/AudioReader 需 suspend 加载章节;这里用后台重建,交由调用方 route 切换
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        val provider: TextProvider? = withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (sb.isLocal) {
                val f = java.io.File(sb.bookUrl)
                if (f.exists()) LocalTextProvider(f, sb.name) else null
            } else {
                val source = BookSourceStore.loadAll(context).find { it.bookSourceUrl == sb.sourceUrl }
                if (source != null) LegadoTextProvider(context, source, sb.tocUrl.ifEmpty { sb.bookUrl }, sb.name) else null
            }
        }
        if (provider == null) { go(ReadingRoute.Shelf); return@launch }
        val chapters = withContext(kotlinx.coroutines.Dispatchers.IO) { provider.loadChapters() }
        // 听书(kind="audio")进入音频播放器;其他走文本阅读器
        if (sb.kind == "audio") {
            // AudioReader 需要 SearchBook;仅用 name 即可,AudioPlayerScreen 只显示书名
            val sbk = SearchBook(
                name = sb.name, author = sb.author, coverUrl = sb.coverUrl,
                bookUrl = sb.bookUrl, sourceUrl = sb.sourceUrl, sourceName = ""
            )
            go(ReadingRoute.AudioReader(provider, sbk, sb.tocUrl, chapters, sb.lastChapterIndex, sb.bookId))
        } else {
            go(ReadingRoute.Reader(provider, chapters, sb.lastChapterIndex, sb.bookId, sb.scrollOffset))
        }
    }
}

/** 由 sourceUrl 反查书源类型(0小说/2漫画),供返回搜索页时定位对应 tab */
private fun bookTypeOf(context: android.content.Context, sourceUrl: String): Int =
    BookSourceStore.loadAll(context).find { it.bookSourceUrl == sourceUrl }?.bookSourceType ?: 0

/** 书源管理:分类 tab(小说/漫画/听书)+ 源列表 + 长按多选 + 单击编辑 */
@Composable
private fun ReadingHubScreen(
    refreshTick: Int,
    onImportSource: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // tab 索引到 type 的映射:0=小说(type0) 1=漫画(type2) 2=听书(type1)
    val typeByTab = intArrayOf(0, 2, 1)
    val labelByTab = arrayOf("小说", "漫画", "听书")
    var tab by remember { mutableIntStateOf(0) }
    val type = typeByTab[tab]
    var localTick by remember { mutableIntStateOf(0) }
    val sources = remember(refreshTick, tab, localTick) { BookSourceStore.loadAll(context).filter { it.bookSourceType == type } }

    // 多选模式
    var multiSelect by remember { mutableStateOf(false) }
    val selectedUrls = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // 单源编辑对话框
    var editingSource by remember { mutableStateOf<BookSource?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 顶栏:多选模式显示批量操作,普通模式显示返回/标题/导入
        if (multiSelect) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassIconButton(
                    icon = Icons.Filled.Close, contentDescription = "退出多选",
                    onClick = { multiSelect = false; selectedUrls.clear() }, modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("已选 ${selectedUrls.size}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                // 全选
                val allSel = sources.isNotEmpty() && sources.all { selectedUrls[it.bookSourceUrl] == true }
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (allSel) SelectedBg else SurfaceVariant)
                        .clickable {
                            if (allSel) selectedUrls.clear()
                            else sources.forEach { selectedUrls[it.bookSourceUrl] = true }
                        }.padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(if (allSel) "取消全选" else "全选", color = if (allSel) Color.White else TextSecondary, fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                // 批量启用
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceVariant)
                        .clickable {
                            if (selectedUrls.isNotEmpty()) {
                                BookSourceStore.batchSetEnabled(context, selectedUrls.filter { it.value }.keys, true)
                                localTick++; multiSelect = false; selectedUrls.clear()
                            }
                        }.padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("启用", color = TextSecondary, fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                // 批量禁用
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceVariant)
                        .clickable {
                            if (selectedUrls.isNotEmpty()) {
                                BookSourceStore.batchSetEnabled(context, selectedUrls.filter { it.value }.keys, false)
                                localTick++; multiSelect = false; selectedUrls.clear()
                            }
                        }.padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("禁用", color = TextSecondary, fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                // 批量删除
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Danger)
                        .clickable {
                            if (selectedUrls.isNotEmpty()) {
                                BookSourceStore.batchDelete(context, selectedUrls.filter { it.value }.keys)
                                localTick++; multiSelect = false; selectedUrls.clear()
                            }
                        }.padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("删除", color = Color.White, fontSize = 12.sp) }
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    onClick = onBack, modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
                )
                Text("书源管理", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center))
                LiquidGlassIconButton(
                    icon = Icons.Filled.Add, contentDescription = "导入源",
                    onClick = onImportSource, modifier = Modifier.size(40.dp).align(Alignment.CenterEnd)
                )
            }
        }

        // 分类 tab(多选模式隐藏,避免切换丢失选择)
        if (!multiSelect) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                labelByTab.forEachIndexed { i, label ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (tab == i) SelectedBg else ScheduleCellBg)
                            .clickable { tab = i }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (tab == i) Color.White else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("${labelByTab[tab]}书源", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (multiSelect) "长按选择,点击多选 · 已选 ${selectedUrls.size}/${sources.size}"
                        else "已导入 ${sources.size} 个 · 启用 ${sources.count { it.enabled }} 个 · 长按可多选",
                        color = TextHint, fontSize = 11.sp
                    )
                }
            }
            if (sources.isEmpty()) {
                item { EmptyBookSourceHint(onImportSource) }
            } else {
                items(sources, key = { it.bookSourceUrl }) { src ->
                    BookSourceCard(
                        source = src,
                        multiSelect = multiSelect,
                        isSelected = selectedUrls[src.bookSourceUrl] == true,
                        onToggle = { on -> BookSourceStore.setEnabled(context, src.bookSourceUrl, on); localTick++ },
                        onDelete = { BookSourceStore.delete(context, src.bookSourceUrl); localTick++ },
                        onClick = { editingSource = src },
                        onLongClick = {
                            multiSelect = true
                            selectedUrls[src.bookSourceUrl] = true
                        },
                        onSelectToggle = {
                            if (selectedUrls[src.bookSourceUrl] == true) selectedUrls.remove(src.bookSourceUrl)
                            else selectedUrls[src.bookSourceUrl] = true
                        }
                    )
                }
            }
        }
    }

    // 单源编辑对话框
    editingSource?.let { src ->
        BookSourceEditDialog(
            source = src,
            onDismiss = { editingSource = null },
            onSave = { name, searchUrl, enabled ->
                BookSourceStore.update(context, src.bookSourceUrl, name, null, searchUrl, enabled)
                localTick++; editingSource = null
            },
            onDelete = {
                BookSourceStore.delete(context, src.bookSourceUrl)
                localTick++; editingSource = null
            }
        )
    }
}

@Composable
private fun EmptyBookSourceHint(onImport: () -> Unit) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有书源", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                "本应用只提供阅读壳,不内置任何书源。\n导入 legado(阅读3.0)格式的书源 JSON 后即可搜索阅读。",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 19.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Primary)
                    .clickable { onImport() }.padding(horizontal = 24.dp, vertical = 11.dp)
            ) { Text("导入书源", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun BookSourceCard(
    source: BookSource,
    multiSelect: Boolean,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit
) {
    val hasJs = remember(source.bookSourceUrl) { BookSourceParser.containsJs(source) }
    val cardBg by animateColorAsState(if (isSelected) Primary.copy(0.10f) else CardBg, tween(180), label = "srcCardBg")
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(cardBg)
                .combinedClickable(
                    onClick = { if (multiSelect) onSelectToggle() else onClick() },
                    onLongClick = onLongClick
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选模式下显示勾选圆
            if (multiSelect) {
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (isSelected) SelectedBg else Color.Transparent)
                        .border(
                            if (isSelected) 0.dp else 2.dp,
                            if (isSelected) Color.Transparent else Color(0xFFD1D5DB),
                            RoundedCornerShape(11.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.bookSourceName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (hasJs) {
                        Spacer(Modifier.size(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp)).background(AccentBlue.copy(0.14f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) { Text("JS", color = AccentBlue, fontSize = 9.sp) }
                    }
                }
                if (source.bookSourceGroup.isNotEmpty()) {
                    Text(source.bookSourceGroup, color = TextHint, fontSize = 11.sp, maxLines = 1)
                }
                Text(source.bookSourceUrl, color = TextHint, fontSize = 10.sp, maxLines = 1)
            }
            if (!multiSelect) {
                // 灰色对勾开关(替代 LiquidGlassToggle)
                val grayCheck = Color(0xFF6B7280)
                val grayDark = Color(0xFF4B5563)
                val fillScale by animateFloatAsState(if (source.enabled) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "srcFill")
                val checkScale by animateFloatAsState(if (source.enabled) 1f else 0.3f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "srcCheckScale")
                val checkAlpha by animateFloatAsState(if (source.enabled) 1f else 0f, tween(180), label = "srcCheckAlpha")
                val borderColor by animateColorAsState(if (source.enabled) grayCheck else Color(0xFFD1D5DB), tween(300), label = "srcBorder")
                Box(
                    Modifier.size(26.dp).clickable { onToggle(!source.enabled) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(CardBg)
                            .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.matchParentSize().graphicsLayer { scaleX = fillScale; scaleY = fillScale }
                            .clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(grayCheck, grayDark))))
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White,
                            modifier = Modifier.size(15.dp).graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkAlpha })
                    }
                }
            }
        }
    }
}

/**
 * 单源编辑对话框 — 快速修改名称、搜索URL、启用状态,可删除。
 * 长按源进入多选;单击源进入此编辑页。
 */
@Composable
private fun BookSourceEditDialog(
    source: BookSource,
    onDismiss: () -> Unit,
    onSave: (name: String, searchUrl: String, enabled: Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var nameState by remember { mutableStateOf(source.bookSourceName) }
    var searchUrlState by remember { mutableStateOf(source.searchUrl ?: "") }
    var enabledState by remember { mutableStateOf(source.enabled) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除书源", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
            text = { Text("确定删除「${source.bookSourceName}」?此操作不可撤销。", color = TextSecondary, fontSize = 13.sp) },
            confirmButton = {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Danger).clickable { onDelete() }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("删除", color = Color.White, fontSize = 13.sp)
                }
            },
            dismissButton = {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceVariant).clickable { confirmDelete = false }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("取消", color = TextSecondary, fontSize = 13.sp)
                }
            }
        )
        return
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑书源", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Text("源地址", color = TextHint, fontSize = 10.sp)
                Text(source.bookSourceUrl, color = TextSecondary, fontSize = 11.sp, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                Text("名称", color = TextHint, fontSize = 11.sp)
                OutlinedTextField(
                    value = nameState, onValueChange = { nameState = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary)
                )
                Spacer(Modifier.height(10.dp))
                Text("搜索URL", color = TextHint, fontSize = 11.sp)
                OutlinedTextField(
                    value = searchUrlState, onValueChange = { searchUrlState = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                    minLines = 2
                )
                Spacer(Modifier.height(6.dp))
                Text("留空则不支持搜索(电台类源常见)", color = TextHint, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                // 启用开关
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceVariant).clickable { enabledState = !enabledState }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val grayCheck = Color(0xFF6B7280); val grayDark = Color(0xFF4B5563)
                    val fillScale by animateFloatAsState(if (enabledState) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "edFill")
                    val checkScale by animateFloatAsState(if (enabledState) 1f else 0.3f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "edCheck")
                    val checkAlpha by animateFloatAsState(if (enabledState) 1f else 0f, tween(180), label = "edAlpha")
                    val borderColor by animateColorAsState(if (enabledState) grayCheck else Color(0xFFD1D5DB), tween(300), label = "edBorder")
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(CardBg).border(2.dp, borderColor, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                            Box(Modifier.matchParentSize().graphicsLayer { scaleX = fillScale; scaleY = fillScale }.clip(RoundedCornerShape(3.dp)).background(Brush.linearGradient(listOf(grayCheck, grayDark))))
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp).graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkAlpha })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Danger).clickable { confirmDelete = true }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("删除", color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Primary).clickable { onSave(nameState.trim(), searchUrlState.trim(), enabledState) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("保存", color = Color.White, fontSize = 13.sp)
                }
            }
        },
        dismissButton = {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceVariant).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("取消", color = TextSecondary, fontSize = 13.sp)
            }
        }
    )
}
