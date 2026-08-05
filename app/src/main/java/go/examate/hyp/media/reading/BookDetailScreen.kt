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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import go.examate.hyp.media.legado.BookInfo
import go.examate.hyp.media.legado.BookSource
import go.examate.hyp.media.legado.BookSourceRepository
import go.examate.hyp.media.legado.SearchBook
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 书籍详情页 — 封面/作者/简介 + 目录入口 + 开始阅读。
 *
 * @param book 搜索结果条目(带 sourceUrl/bookUrl)
 * @param source 对应书源
 * @param onOpenToc 打开目录(带 tocUrl)
 */
@Composable
fun BookDetailScreen(
    book: SearchBook,
    source: BookSource,
    onBack: () -> Unit,
    onOpenToc: (tocUrl: String) -> Unit,
    onAddShelf: (name: String, author: String, cover: String, tocUrl: String) -> Unit = { _, _, _, _ -> },
    inShelf: Boolean = false
) {
    val repo = remember { BookSourceRepository() }
    val infoState by produceState<Result<BookInfo>?>(initialValue = null, book.bookUrl) {
        value = repo.getBookInfo(source, book.bookUrl)
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                onClick = onBack, modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text("书籍详情", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center))
        }

        val info = infoState?.getOrNull()
        // 详情用抓取结果,失败则回退搜索结果字段
        val name = info?.name?.ifEmpty { book.name } ?: book.name
        val author = info?.author?.ifEmpty { book.author } ?: book.author
        val cover = info?.coverUrl?.ifEmpty { book.coverUrl } ?: book.coverUrl
        val intro = info?.intro?.ifEmpty { book.intro } ?: book.intro
        val tocUrl = info?.tocUrl?.ifEmpty { book.bookUrl } ?: book.bookUrl

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // 头部:封面 + 名称/作者/最新章
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Box(
                    Modifier.size(width = 90.dp, height = 120.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (cover.isNotEmpty()) AsyncImage(model = cover, contentDescription = name, modifier = Modifier.fillMaxSize())
                    else Text("书", color = TextHint, fontSize = 14.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (author.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(author, color = TextSecondary, fontSize = 13.sp) }
                    val kind = info?.kind.orEmpty().ifEmpty { book.kind }
                    if (kind.isNotEmpty()) { Spacer(Modifier.height(2.dp)); Text(kind, color = TextHint, fontSize = 12.sp, maxLines = 2) }
                    Spacer(Modifier.height(2.dp)); Text("来源:${book.sourceName}", color = AccentBlue, fontSize = 11.sp)
                }
            }

            // 加载态
            if (infoState == null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TextHint)
                    Spacer(Modifier.width(8.dp)); Text("加载详情…", color = TextHint, fontSize = 12.sp)
                }
            }

            // 简介
            if (intro.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("简介", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(intro, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 加入书架
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (inShelf) Primary.copy(0.12f) else Primary.copy(0.15f))
                        .clickable(enabled = !inShelf) { onAddShelf(name, author, cover, tocUrl) }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (inShelf) "已在书架" else "加入书架", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                // 开始阅读(进目录)
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Primary)
                        .clickable { onOpenToc(tocUrl) }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) { Text("开始阅读", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
