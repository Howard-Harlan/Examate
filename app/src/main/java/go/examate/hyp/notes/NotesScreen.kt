package go.examate.hyp.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Divider
import go.examate.hyp.ui.theme.GlassChipTint
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记主页(优化版) — 搜索 + 全部/置顶筛选 + 精致卡片。
 *
 * - 置顶笔记排在前面,卡片带 📌 标识
 * - 相对时间展示(刚刚 / N 分钟前 / 今天 / 昨天 / 日期)
 * - 长按弹出操作菜单:置顶/取消置顶、删除
 */
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    onOpenNote: (String?) -> Unit
) {
    val context = LocalContext.current

    var notesTick by remember { mutableIntStateOf(0) }
    val notes by produceState(initialValue = emptyList<NoteStorage.Note>(), notesTick) {
        value = withContext(Dispatchers.IO) {
            NoteStorage.listNotes(context).map { n ->
                val content = NoteStorage.loadNote(context, n.id)?.content ?: ""
                n.copy(content = plainPreview(content).take(120))
            }
        }
    }

    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var onlyPinned by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<NoteStorage.Note?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // 外显布局:0=卡片式, 1=列表式
    var layoutMode by remember { mutableIntStateOf(NotePrefs.layoutMode(context)) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(searchVisible) {
        if (searchVisible) focusRequester.requestFocus()
    }

    val filtered = remember(notes, query, onlyPinned) {
        notes.filter { n ->
            (if (onlyPinned) n.pinned else true) &&
                (query.isBlank() || n.title.contains(query, ignoreCase = true) || n.content.contains(query, ignoreCase = true))
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 顶栏 ═══
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
                )
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("笔记", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    if (notes.isNotEmpty()) {
                        Text("${notes.size} 篇", color = TextHint, fontSize = 10.sp)
                    }
                }
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiquidGlassIconButton(
                        icon = Icons.Filled.Search,
                        contentDescription = "搜索",
                        onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) query = ""
                        },
                        modifier = Modifier.size(40.dp)
                    )
                    LiquidGlassIconButton(
                        icon = Icons.Filled.Settings,
                        contentDescription = "设置",
                        onClick = { showSettings = true },
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // ═══ 搜索框(点击顶栏搜索按钮展开) ═══
            if (searchVisible) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索笔记标题或内容", color = TextHint, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextHint, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Text(
                                "✕",
                                color = TextHint,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { query = "" }
                                    .padding(6.dp)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Primary.copy(alpha = 0.35f),
                        unfocusedContainerColor = SurfaceVariant,
                        focusedContainerColor = SurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .focusRequester(focusRequester)
                )
            }

            // ═══ 筛选:全部 / 置顶 ═══
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip("全部", selected = !onlyPinned) { onlyPinned = false }
                FilterChip("📌 置顶", selected = onlyPinned) { onlyPinned = true }
            }

            // ═══ 笔记列表 ═══
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(AccentBlue.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.NoteAdd, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (notes.isEmpty()) "还没有笔记" else "没有匹配的笔记",
                            color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (notes.isEmpty()) "点击右下角 + 创建第一篇知识笔记" else "换个关键词试试",
                            color = TextHint, fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { note ->
                        if (layoutMode == 1) {
                            NoteListRow(
                                title = note.title.ifEmpty { "无标题" },
                                updatedAt = relativeTime(note.updatedAt),
                                pinned = note.pinned,
                                onClick = { onOpenNote(note.id) },
                                onLongPress = { actionTarget = note }
                            )
                        } else {
                            NoteCard(
                                title = note.title.ifEmpty { "无标题" },
                                summary = note.content,
                                updatedAt = relativeTime(note.updatedAt),
                                pinned = note.pinned,
                                onClick = { onOpenNote(note.id) },
                                onLongPress = { actionTarget = note }
                            )
                        }
                    }
                }
            }
        }

        // ═══ 悬浮添加按钮(右下角) ═══
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp)
        ) {
            LiquidGlassIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "新建笔记",
                onClick = { onOpenNote(null) },
                modifier = Modifier.size(52.dp)
            )
        }

        // ═══ 设置面板(显示设置 + 功能设置) ═══
        if (showSettings) {
            NoteSettingsPanel(
                notes = notes,
                layoutMode = layoutMode,
                onSelectLayout = {
                    layoutMode = it
                    NotePrefs.setLayoutMode(context, it)
                },
                onBack = { showSettings = false; notesTick++ },
                onToggleKnowledge = { id, enabled ->
                    NoteStorage.setKnowledgeSource(context, id, enabled)
                    notesTick++
                }
            )
        }
    }

    // ═══ 长按操作菜单(项目风格弹窗:圆角卡片 + 取消按钮) ═══
    actionTarget?.let { note ->
        Dialog(onDismissRequest = { actionTarget = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(CardBg)
                    .padding(20.dp)
            ) {
                Text(
                    note.title.ifEmpty { "无标题" },
                    color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (note.pinned) "已置顶 · 置顶笔记会排在列表最前" else "可置顶到列表最前,或删除笔记",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 取消按钮
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceVariant)
                            .clickable { actionTarget = null }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("取消", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.weight(1f))
                    // 删除按钮
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Danger.copy(alpha = 0.12f))
                            .clickable {
                                NoteStorage.deleteNote(context, note.id)
                                actionTarget = null
                                notesTick++
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("删除", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    // 置顶/取消置顶按钮
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SelectedBg)
                            .clickable {
                                NoteStorage.setPinned(context, note.id, !note.pinned)
                                actionTarget = null
                                notesTick++
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            if (note.pinned) "取消置顶" else "置顶",
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/** 笔记设置面板:显示设置(布局/预览主题) + 功能设置(AI 搜题知识来源) */
@Composable
private fun NoteSettingsPanel(
    notes: List<NoteStorage.Note>,
    layoutMode: Int,
    onSelectLayout: (Int) -> Unit,
    onBack: () -> Unit,
    onToggleKnowledge: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var previewThemeIndex by remember { mutableIntStateOf(NotePrefs.previewThemeIndex(context)) }

    Box(Modifier.fillMaxSize().background(BgPrimary)) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏(仅返回,无标题文字)
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
                )
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
            ) {
                // ═══ 显示设置 ═══
                Text("显示设置", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                FrostedGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("显示风格(外显布局)", color = TextPrimary, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "卡片式", 1 to "列表式").forEach { (mode, label) ->
                                val selected = layoutMode == mode
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) SelectedBg else GlassChipTint)
                                        .clickable { onSelectLayout(mode) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        label,
                                        color = if (selected) Color.White else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Text("预览主题", color = TextPrimary, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NOTE_PREVIEW_THEMES.forEachIndexed { index, theme ->
                                val selected = previewThemeIndex == index
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) SelectedBg else GlassChipTint)
                                        .clickable {
                                            previewThemeIndex = index
                                            NotePrefs.setPreviewTheme(context, index)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        theme.name,
                                        color = if (selected) Color.White else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ═══ 功能设置 ═══
                Text("功能设置", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                FrostedGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("AI 搜题知识来源", color = TextPrimary, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "只把勾选的笔记提供给 AI 搜题做渐进读取,而不是全部注入",
                            color = TextHint,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        if (notes.isEmpty()) {
                            Text("还没有笔记", color = TextHint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                        } else {
                            notes.forEach { note ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onToggleKnowledge(note.id, !note.knowledgeSource) }
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        note.title.ifEmpty { "无标题" },
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    CheckIndicator(selected = note.knowledgeSource)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 打勾指示器(勾选样式,非开关) */
@Composable
private fun CheckIndicator(selected: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) AccentBlue else SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 摘要净化:去掉 Markdown 语法符号与媒体引用,列表卡片只显示纯文字 */
private fun plainPreview(content: String): String = content
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("!\\[[^]]*\\]\\([^)]*\\)"), " ")       // 图片
    .replace(Regex("(\\[audio\\]|::audio\\[[^]]*\\])\\([^)]*\\)"), " ") // 音频
    .replace(Regex("\\[([^]]*)\\]\\([^)]*\\)"), "$1")     // 链接保留文字
    .replace(Regex("[#>*_~`\\-]+"), " ")                  // 标题/引用/强调等符号
    .replace(Regex("\\$+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** 相对时间:刚刚 / N 分钟前 / N 小时前 / 今天 / 昨天 / MM-dd */
private fun relativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 2 * day -> "昨天"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) SelectedBg else GlassChipTint)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/** 笔记卡片(卡片式布局):置顶标识 + 标题 + 摘要(前2行) + 相对时间 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    title: String,
    summary: String,
    updatedAt: String,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    FrostedGlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        // 长按触感反馈,增强交互感知
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (pinned) {
                    Spacer(Modifier.width(6.dp))
                    Text("📌", fontSize = 12.sp)
                }
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(updatedAt, color = TextHint, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("编辑 ›", color = AccentBlue, fontSize = 11.sp)
            }
        }
    }
}

/** 笔记行(列表式布局):紧凑单行,标题 + 置顶 + 时间 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteListRow(
    title: String,
    updatedAt: String,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (pinned) {
            Spacer(Modifier.width(6.dp))
            Text("📌", fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(updatedAt, color = TextHint, fontSize = 11.sp)
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Divider))
}
