package go.examate.hyp.media.reading

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import go.examate.hyp.ui.components.SearchField
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import go.examate.hyp.media.legado.BookSource
import go.examate.hyp.media.legado.BookSourceRepository
import go.examate.hyp.media.legado.SearchBook
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 书籍搜索页 — 聚合已启用书源搜索,边搜边出结果。
 * 顶栏类型切换(书源/漫画源/听书源)替代独立搜索图标入口。
 *
 * @param allSources 全部已启用书源(按类型过滤由本页完成)
 * @param initialType 初始搜索类型 0=小说 2=漫画 1=听书
 * @param onBack 返回
 * @param onOpenBook 点结果
 */
@Composable
fun BookSearchScreen(
    allSources: List<BookSource>,
    initialType: Int = 0,
    onBack: () -> Unit,
    onOpenBook: (SearchBook) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BookSourceRepository() }
    var keyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchedSources by remember { mutableStateOf(0) }
    var failedSources by remember { mutableStateOf(0) }
    var failDetail by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<SearchBook>() }

    // 搜索类型:0=小说 2=漫画 1=听书(tab顺序:书源/漫画/听书)
    var searchTab by remember { mutableStateOf(initialType) }
    val typeByTab = intArrayOf(0, 2, 1)
    val labelByTab = arrayOf("书源", "漫画", "听书")
    val currentType = typeByTab[searchTab]
    val sources = remember(allSources, searchTab) { allSources.filter { it.bookSourceType == currentType } }

    fun doSearch() {
        val kw = keyword.trim()
        if (kw.isEmpty() || searching) return
        results.clear()
        searchedSources = 0
        failedSources = 0
        failDetail = ""
        searching = true
        val concurrency = ReaderPrefs.getSearchConcurrency(context)
        scope.launch {
            repo.searchAll(sources, kw, concurrency).collect { (src, res) ->
                searchedSources++
                val list = res.getOrNull()
                if (list != null) {
                    results.addAll(list)
                } else {
                    failedSources++
                    val reason = res.exceptionOrNull()?.message ?: "未知错误"
                    failDetail = if (failDetail.isEmpty()) "${src.bookSourceName}: $reason"
                    else "$failDetail\n${src.bookSourceName}: $reason"
                }
            }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
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
                placeholder = "搜索书名 / 作者",
                modifier = Modifier.weight(1f),
                showLeadingIcon = false
            )
        }

        // 类型切换(书源/漫画/听书) — 项目统一 tab 样式
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            labelByTab.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (searchTab == i) Primary else SurfaceVariant)
                        .clickable {
                            if (searchTab != i) {
                                searchTab = i
                                results.clear()
                                keyword = ""
                            }
                        }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (searchTab == i) Color.White else TextSecondary,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 进度提示(含失败源信息)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searching) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TextHint)
                Spacer(Modifier.width(8.dp))
            }
            val status = when {
                sources.isEmpty() -> "无可用${labelByTab[searchTab]}源,请先导入并启用"
                failedSources > 0 -> "已搜 $searchedSources/${sources.size} 源 · 结果 ${results.size} · ${failedSources}源失败"
                else -> "已搜 $searchedSources/${sources.size} 源 · 结果 ${results.size}"
            }
            Text(status, color = if (failedSources > 0 && !searching) Danger else TextHint, fontSize = 11.sp)
        }
        // 失败详情(搜索结束后可展开查看)
        if (!searching && failDetail.isNotEmpty()) {
            var showFail by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)) {
                Text("查看失败原因", color = AccentBlue, fontSize = 10.sp,
                    modifier = Modifier.clickable { showFail = !showFail })
            }
            if (showFail) {
                Text(failDetail, color = TextHint, fontSize = 10.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(results, key = { i, b -> "${b.sourceUrl}_${b.bookUrl}_$i" }) { _, book ->
                SearchResultCard(book, onClick = { onOpenBook(book) })
            }
        }
    }
}

@Composable
private fun SearchResultCard(book: SearchBook, onClick: () -> Unit) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面
            Box(
                Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (book.coverUrl.isNotEmpty()) {
                    AsyncImage(model = book.coverUrl, contentDescription = book.name, modifier = Modifier.fillMaxSize())
                } else {
                    Text("书", color = TextHint, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (book.author.isNotEmpty()) {
                    Text(book.author, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
                }
                if (book.lastChapter.isNotEmpty()) {
                    Text(book.lastChapter, color = TextHint, fontSize = 11.sp, maxLines = 1)
                }
                Spacer(Modifier.height(2.dp))
                Text("来源:${book.sourceName}", color = AccentBlue, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}
