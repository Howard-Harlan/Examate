package go.examate.hyp.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
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
 * 笔记主页 — 顶栏 + 底部悬浮液态玻璃 Tab(笔记 / 速记)。
 *
 * 两个独立概念彻底拆开:
 * - 笔记:Markdown 长文,点击进入编辑器,长按删除。
 * - 速记:瞬时灵感,随心贴(瀑布流彩色便利贴),无日期无完成状态。
 *
 * @param onBack 返回回调
 * @param onOpenNote 打开笔记编辑器;参数为 noteId,null 表示新建
 * @param initialTab 初始 Tab(0=笔记 1=速记)
 */
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    onOpenNote: (String?) -> Unit,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // ── 笔记列表(异步加载前 200 字作为摘要) ──
    var notesTick by remember { mutableIntStateOf(0) }
    val notes by produceState(initialValue = emptyList<NoteStorage.Note>(), notesTick) {
        value = withContext(Dispatchers.IO) {
            NoteStorage.listNotes(context).map { n ->
                val content = NoteStorage.loadNote(context, n.id)?.content ?: ""
                n.copy(content = content.take(200))
            }
        }
    }
    var deleteNoteTarget by remember { mutableStateOf<NoteStorage.Note?>(null) }

    // ── 速记列表 ──
    var memosTick by remember { mutableIntStateOf(0) }
    val memos by produceState(initialValue = emptyList<MemoStorage.Memo>(), memosTick) {
        value = withContext(Dispatchers.IO) { MemoStorage.listMemos(context) }
    }
    var deleteMemoTarget by remember { mutableStateOf<MemoStorage.Memo?>(null) }

    val backdrop = LocalGlassBackdrop.current

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        // ═══ 内容区(底部留白避开悬浮 Tab) ═══
        Box(Modifier.fillMaxSize().padding(bottom = 76.dp)) {
            // ═══ 顶栏:返回 + 居中标题(动态) + 新建按钮(笔记 Tab) ═══
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
                )
                Text(
                    when (currentTab) {
                        0 -> "笔记"
                        else -> "速记"
                    },
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center)
                )
                if (currentTab == 0) {
                    LiquidGlassIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "新建笔记",
                        onClick = { onOpenNote(null) },
                        modifier = Modifier.size(40.dp).align(Alignment.CenterEnd)
                    )
                }
            }

            // ═══ Tab 内容 ═══
            Box(Modifier.fillMaxSize()) {
                when (currentTab) {
                    0 -> NotesTab(
                        notes = notes,
                        timeFormat = timeFormat,
                        onOpenNote = onOpenNote,
                        onLongPress = { deleteNoteTarget = it }
                    )

                    1 -> MemosTab(
                        memos = memos,
                        timeFormat = timeFormat,
                        onSend = { text ->
                            MemoStorage.createMemo(context, text)
                            memosTick++
                        },
                        onLongPress = { memo -> deleteMemoTarget = memo }
                    )
                }
            }
        }

        // ═══ 底部悬浮液态玻璃 Tab ═══
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
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
                    listOf("笔记" to 0, "速记" to 1).forEach { (label, idx) ->
                        val selected = currentTab == idx
                        val bg by animateColorAsState(
                            targetValue = if (selected) Primary else Color.Transparent,
                            animationSpec = tween(220),
                            label = "bottom_tab_bg_$idx"
                        )
                        val fg by animateColorAsState(
                            targetValue = if (selected) Color.White else TextSecondary,
                            animationSpec = tween(220),
                            label = "bottom_tab_fg_$idx"
                        )
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(bg)
                                .clickable { currentTab = idx }
                                .padding(horizontal = 22.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = fg,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }

    // ═══ 删除笔记确认 ═══
    deleteNoteTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteNoteTarget = null },
            title = { Text("删除笔记", color = TextPrimary, fontWeight = FontWeight.Medium) },
            text = { Text("确定删除「${note.title.ifEmpty { "无标题" }}」吗?此操作不可撤销。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    NoteStorage.deleteNote(context, note.id)
                    deleteNoteTarget = null
                    notesTick++
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteNoteTarget = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }

    // ═══ 删除速记确认 ═══
    deleteMemoTarget?.let { memo ->
        AlertDialog(
            onDismissRequest = { deleteMemoTarget = null },
            title = { Text("删除速记", color = TextPrimary, fontWeight = FontWeight.Medium) },
            text = { Text("确定删除这条随心贴吗?此操作不可撤销。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    MemoStorage.deleteMemo(context, memo.id)
                    deleteMemoTarget = null
                    memosTick++
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteMemoTarget = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }
}

// ════════════════════════════════════════
//  笔记 Tab
// ════════════════════════════════════════

@Composable
private fun NotesTab(
    notes: List<NoteStorage.Note>,
    timeFormat: SimpleDateFormat,
    onOpenNote: (String?) -> Unit,
    onLongPress: (NoteStorage.Note) -> Unit
) {
    if (notes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.NoteAdd,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("还没有笔记", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("点击右上角创建第一篇", color = TextHint, fontSize = 12.sp)
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 56.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(notes, key = { it.id }) { note ->
                NoteCard(
                    title = note.title.ifEmpty { "无标题" },
                    summary = note.content,
                    updatedAt = timeFormat.format(Date(note.updatedAt)),
                    onClick = { onOpenNote(note.id) },
                    onLongPress = { onLongPress(note) }
                )
            }
        }
    }
}

/** 笔记卡片:标题 + 摘要(前2行) + 更新时间;点击进入编辑器,长按弹出删除确认 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    title: String,
    summary: String,
    updatedAt: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    FrostedGlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(updatedAt, color = TextHint, fontSize = 11.sp)
        }
    }
}

// ════════════════════════════════════════
//  速记 Tab — 随心贴瀑布流
// ════════════════════════════════════════

/**
 * 随心贴背景色系 — 莫兰迪低饱和,8 色循环,营造便利贴墙的视觉层次。
 * 参考 SnapMark 等便利贴应用的配色思路,保持克制不刺眼。
 */
private val MemoColors = listOf(
    Color(0xFFFFF9C4), // 淡黄
    Color(0xFFC8E6C9), // 淡绿
    Color(0xFFBBDEFB), // 淡蓝
    Color(0xFFFFCDD2), // 淡粉
    Color(0xFFE1BEE7), // 淡紫
    Color(0xFFFFCCBC), // 淡橙
    Color(0xFFB2DFDB), // 青绿
    Color(0xFFF0F4C3)  // 黄绿
)

/** 卡片轻微旋转角度,营造手贴的随性感(±2°) */
private val MemoRotations = listOf(-2f, 1.5f, -1f, 2f, -1.5f, 1f, -2f, 1.5f)

@Composable
private fun MemosTab(
    memos: List<MemoStorage.Memo>,
    timeFormat: SimpleDateFormat,
    onSend: (String) -> Unit,
    onLongPress: (MemoStorage.Memo) -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().imePadding()) {
        // ═══ 随心贴瀑布流(占满上方剩余空间,顶部留白避开悬浮顶栏) ═══
        if (memos.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(top = 52.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("随心贴 ✨", color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("随手记下灵感,贴在墙上", color = TextHint, fontSize = 12.sp)
                }
            }
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 4.dp),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(memos, key = { _, m -> m.id }) { index, memo ->
                    MemoStickerCard(
                        memo = memo,
                        timeText = timeFormat.format(Date(memo.createdAt)),
                        rotation = MemoRotations[memo.colorIndex % MemoRotations.size],
                        onLongPress = { onLongPress(memo) }
                    )
                }
            }
        }

        // ═══ 底部快速输入区 ═══
        FrostedGlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("贴一张随心贴...", color = TextHint) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Primary.copy(alpha = 0.4f),
                        unfocusedContainerColor = SurfaceVariant,
                        focusedContainerColor = SurfaceVariant
                    ),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Spacer(Modifier.width(10.dp))
                val canSend = input.isNotBlank()
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (canSend) Primary else Primary.copy(alpha = 0.4f))
                        .clickable(enabled = canSend) {
                            val t = input.trim()
                            if (t.isNotEmpty()) {
                                onSend(t)
                                input = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 随心贴卡片 — 彩色背景 + 轻微旋转 + 文本 + 时间戳,长按删除。
 * 视觉上模拟便利贴贴在墙上的感觉。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoStickerCard(
    memo: MemoStorage.Memo,
    timeText: String,
    rotation: Float,
    onLongPress: () -> Unit
) {
    val bgColor = MemoColors[memo.colorIndex % MemoColors.size]
    Box(
        Modifier
            .fillMaxWidth()
            .rotate(rotation)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Column {
            Text(
                memo.content,
                color = Color(0xFF424242),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                timeText,
                color = Color(0xFF757575),
                fontSize = 10.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
