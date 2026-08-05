package go.examate.hyp.media.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

private val SHELF_GREETINGS = listOf(
    "今天看点什么好呢？",
    "又是看书的好时间呢！",
    "孜孜不倦，必有回响。"
)

/**
 * 书架页 — 阅读模块首屏。悬浮顶栏 + 封面网格 + 液态悬浮底栏(书架/最近在看)。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    refreshTick: Int,
    onBack: () -> Unit,
    onContinueRead: (ShelfBook) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onAiWriter: () -> Unit
) {
    val context = LocalContext.current
    var localTick by remember { mutableIntStateOf(0) }
    val allBooks = remember(refreshTick, localTick) { BookshelfStore.loadAll(context) }
    var removeTarget by remember { mutableStateOf<ShelfBook?>(null) }
    // 0=书架(全部) 1=最近在看(有阅读进度)
    var tab by remember { mutableIntStateOf(0) }
    val books = if (tab == 0) allBooks else allBooks.filter { it.lastChapterIndex > 0 || it.lastChapterTitle.isNotEmpty() }
    val greeting = remember { SHELF_GREETINGS.random() }
    val backdrop = LocalGlassBackdrop.current

    Box(Modifier.fillMaxSize()) {
        // ═══ 内容层:随机文字 + 网格(顶部/底部留白避开悬浮栏) ═══
        Column(
            Modifier.fillMaxSize().padding(top = 72.dp, bottom = 76.dp)
        ) {
            // 随机问候语
            Text(
                greeting,
                color = TextHint,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
            )
            if (books.isEmpty()) {
                EmptyShelfHint(if (tab == 1) "还没有阅读记录" else "暂无图书")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(books, key = { it.bookId }) { book ->
                        ShelfCard(
                            book = book,
                            onClick = { onContinueRead(book) },
                            onLongClick = { removeTarget = book }
                        )
                    }
                }
            }
        }

        // ═══ 顶栏:真悬浮(返回 / 搜索 / 设置) ═══
        Row(
            Modifier.statusBarsPadding().fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                onClick = onBack, modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.weight(1f))
            LiquidGlassIconButton(icon = Icons.Filled.Search, contentDescription = "搜索",
                onClick = onSearch, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(6.dp))
            LiquidGlassIconButton(icon = Icons.Filled.Settings, contentDescription = "阅读设置",
                onClick = onSettings, modifier = Modifier.size(40.dp))
        }

        // ═══ 底栏:液态悬浮(书架 / 最近在看 + AI写作圆形悬浮按钮) ═══
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧:Tab 胶囊
            Box(
                Modifier.then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { vibrancy(); blur(14f) },
                            onDrawSurface = { drawRect(Color.White.copy(0.4f)) }
                        )
                    } else {
                        Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(0.88f))
                    }
                )
            ) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("书架" to 0, "最近在看" to 1).forEach { (label, idx) ->
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(if (tab == idx) Primary else Color.Transparent)
                                .clickable { tab = idx }.padding(horizontal = 24.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (tab == idx) Color.White else TextSecondary,
                                fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            // 右侧:AI 写作胶囊悬浮按钮(液态玻璃,文字样式,与左侧 Tab 统一)
            Box(
                Modifier.then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { vibrancy(); blur(14f) },
                            onDrawSurface = { drawRect(Color.White.copy(0.4f)) }
                        )
                    } else {
                        Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(0.88f))
                    }
                ).clickable { onAiWriter() }
                    .padding(horizontal = 24.dp, vertical = 15.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "AI写作",
                    color = Primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // 长按移除确认
    removeTarget?.let { target ->
        PlainDialog(onDismissRequest = { removeTarget = null }) {
            PlainDialogTitle("移出书架")
            PlainDialogContent("确定将《${target.name}》移出书架吗?(阅读进度将清除)")
            PlainDialogButtons(listOf(
                Triple("取消", false) { removeTarget = null },
                Triple("移出", true) {
                    BookshelfStore.remove(context, target.bookId); localTick++; removeTarget = null
                }
            ))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(book: ShelfBook, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // 封面
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)).background(SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (book.coverUrl.isNotEmpty()) {
                AsyncImage(model = book.coverUrl, contentDescription = book.name, modifier = Modifier.fillMaxSize())
            } else {
                Text(book.name.take(4), color = TextHint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (book.isLocal) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(AccentBlue.copy(0.85f)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text("本地", color = Color.White, fontSize = 8.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(book.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        // 续读提示
        if (book.lastChapterTitle.isNotEmpty()) {
            Text("读到:${book.lastChapterTitle}", color = TextHint, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text("未读", color = TextHint, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EmptyShelfHint(text: String = "暂无图书") {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = TextHint, fontSize = 14.sp)
    }
}
