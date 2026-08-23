package go.examate.hyp.media.reader

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.media.reading.ReaderPrefs
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.ReadingFont

/**
 * 通用目录页 — 只依赖 TextProvider,任何来源共用。
 *
 * @param bookName 书名(用于 AI 写作 IF 线标识)
 * @param onAiWriter AI 写作入口回调,传入加载好的章节列表(IF 线创作使用)
 */
@Composable
fun ReaderTocView(
    provider: TextProvider,
    onBack: () -> Unit,
    onOpenChapter: (chapters: List<ReaderChapter>, index: Int) -> Unit,
    bookName: String = "",
    onAiWriter: ((chapters: List<ReaderChapter>) -> Unit)? = null
) {
    val tocState by produceState<List<ReaderChapter>?>(initialValue = null, provider) {
        value = runCatching { provider.loadChapters() }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                onClick = onBack, modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            val cnt = tocState?.size ?: 0
            Text(if (cnt > 0) "目录 ($cnt)" else "目录", color = TextPrimary, fontSize = 17.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
            // IF 线创作入口(右侧)
            if (onAiWriter != null) {
                LiquidGlassIconButton(
                    icon = Icons.Filled.AutoAwesome, contentDescription = "IF线创作",
                    onClick = { onAiWriter(tocState ?: emptyList()) },
                    modifier = Modifier.size(40.dp).align(Alignment.CenterEnd)
                )
            }
        }
        val chapters = tocState
        when {
            chapters == null -> LoadingRow("加载目录…")
            chapters.isEmpty() -> Text("未获取到目录(该来源可能失效或规则暂不支持)", color = TextHint, fontSize = 13.sp,
                modifier = Modifier.padding(24.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                itemsIndexed(chapters, key = { i, c -> "${i}_${c.title}" }) { i, c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenChapter(chapters, i) }.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(c.title, color = TextPrimary, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        if (c.badge.isNotEmpty()) Text(c.badge, color = AccentBlue, fontSize = 10.sp)
                    }
                    HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)
                }
            }
        }
    }
}

/**
 * 通用阅读器 — 只依赖 TextProvider.loadContent(index)。
 * 字号/背景来自 ReaderPrefs;点击正文显隐工具条;上下章切换。
 *
 * @param startIndex 起始章节
 * @param startScroll 起始章内段落偏移(续读)
 * @param onProgress 章节/滚动变化时回调(index, title, scrollOffset),用于书架续读;无书架上下文传 null
 */
@Composable
fun ReaderCoreScreen(
    provider: TextProvider,
    chapters: List<ReaderChapter>,
    startIndex: Int,
    onBack: () -> Unit,
    startScroll: Int = 0,
    onProgress: ((index: Int, title: String, scroll: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))) }
    var fontLevel by remember { mutableIntStateOf(ReaderPrefs.getFontLevel(context)) }
    var bgTheme by remember { mutableIntStateOf(ReaderPrefs.getBgTheme(context)) }
    var showBar by remember { mutableStateOf(true) }
    var showFontMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startScroll)

    val chapter = chapters.getOrNull(index)
    val contentState by produceState<String?>(initialValue = null, index, provider) {
        value = null
        value = runCatching { provider.loadContent(index) }.getOrDefault("")
    }

    // CSS 自定义样式(覆盖字号/颜色/行距/缩进/段距/对齐/字重)
    val customStyle = remember { ReaderPrefs.parseCss(ReaderPrefs.getCustomCss(context)) }

    // 背景主题:系统夜间时"纸白"自动切换为夜黑,护眼绿保持不切换
    val effectiveTheme = if (go.examate.hyp.ui.theme.IsDark && bgTheme == 0) 2 else bgTheme
    val bg = ReaderPrefs.bgColor(effectiveTheme)
    val fg = customStyle.textColor ?: ReaderPrefs.textColor(effectiveTheme)
    val fontSize = customStyle.fontSize ?: ReaderPrefs.fontSizeSp(fontLevel)
    val lineHeightExtra = customStyle.lineHeightExtra ?: 10
    val paragraphSpacing = customStyle.paragraphSpacing ?: 12
    val textIndent = customStyle.textIndent ?: 0
    // 自定义字体:启用开关打开且有导入字体时才应用(字体文件与 Agents 共享)
    val readFamily: FontFamily? = if (ReaderPrefs.isCustomFontEnabled(context)) ReadingFont.family(context) else null

    // 章节或滚动位置变化时上报进度(供书架续读);首行可见索引作为章内偏移
    val firstVisible by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex } }
    androidx.compose.runtime.LaunchedEffect(index, firstVisible) {
        val title = chapters.getOrNull(index)?.title.orEmpty()
        onProgress?.invoke(index, title, firstVisible)
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        LazyColumn(
            Modifier.fillMaxSize().clickable { showBar = !showBar },
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 56.dp, end = 20.dp, bottom = 64.dp)
        ) {
            item {
                Text(chapter?.title.orEmpty(), color = fg, fontSize = (fontSize + 3).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
            }
            when (val c = contentState) {
                null -> item { LoadingRow("加载正文…") }
                else -> {
                    val paras = c.split("\n").filter { it.isNotBlank() }
                    if (paras.isEmpty()) item { Text("(本章无正文或获取失败)", color = fg.copy(0.6f), fontSize = fontSize.sp, fontFamily = readFamily) }
                    items(paras) { p ->
                        val indent = if (textIndent > 0) " ".repeat((textIndent / fontSize).coerceAtLeast(0)) else ""
                        Text(
                            indent + p.trim(),
                            color = fg,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + lineHeightExtra).sp,
                            fontFamily = readFamily,
                            fontWeight = customStyle.fontWeight ?: FontWeight.Normal,
                            textAlign = customStyle.textAlign,
                            modifier = Modifier.padding(bottom = paragraphSpacing.dp)
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChapterNavBtn("上一章", enabled = index > 0, modifier = Modifier.weight(1f)) { if (index > 0) index-- }
                    ChapterNavBtn("下一章", enabled = index < chapters.size - 1, modifier = Modifier.weight(1f)) { if (index < chapters.size - 1) index++ }
                }
                Spacer(Modifier.height(30.dp))
            }
        }

        if (showBar) {
            Box(Modifier.fillMaxWidth().background(bg.copy(0.95f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    onClick = onBack, modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
                )
                Text(chapter?.title.orEmpty(), color = fg, fontSize = 13.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 50.dp))
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(bg.copy(0.95f))
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("字号", color = fg.copy(0.7f), fontSize = 12.sp)
                // 字号选择:点击当前标签弹出 5 档菜单
                Box {
                    SettingChip(ReaderPrefs.fontLevelLabel(fontLevel), fg) { showFontMenu = true }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showFontMenu,
                        onDismissRequest = { showFontMenu = false }
                    ) {
                        listOf(0 to "小", 1 to "偏小", 2 to "常规", 3 to "偏大", 4 to "大").forEach { (level, label) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        color = if (fontLevel == level) androidx.compose.ui.graphics.Color(0xFF3B82F6) else fg,
                                        fontSize = 13.sp,
                                        fontWeight = if (fontLevel == level) FontWeight.Medium else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    fontLevel = level
                                    ReaderPrefs.setFontLevel(context, level)
                                    showFontMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("背景", color = fg.copy(0.7f), fontSize = 12.sp)
                listOf(0 to Color(0xFFF5F1E8), 1 to Color(0xFFCCE8CF), 2 to Color(0xFF1A1A1A)).forEach { (t, c) ->
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(c)
                            .clickable { bgTheme = t; ReaderPrefs.setBgTheme(context, t) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TextHint)
        Spacer(Modifier.width(8.dp)); Text(text, color = TextHint, fontSize = 12.sp)
    }
}

@Composable
private fun ChapterNavBtn(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Primary.copy(0.15f) else Primary.copy(0.05f))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (enabled) Primary else TextHint, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun SettingChip(text: String, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(fg.copy(0.1f))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}
