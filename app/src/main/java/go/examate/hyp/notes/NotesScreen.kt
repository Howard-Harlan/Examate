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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
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
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
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
 * 笔记主页 — 方形卡片网格 + 文件夹收纳。
 *
 * - 根目录:文件夹卡片(含"新建文件夹") + 笔记卡片,两列方形网格
 * - 进入文件夹后只显示该文件夹的笔记,新建笔记自动归入当前文件夹
 * - 长按笔记:移动到文件夹 / 置顶 / 删除;长按文件夹:重命名 / 删除(笔记移回根目录)
 * - 搜索 + 全部/置顶筛选,相对时间展示
 */
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    onOpenNote: (noteId: String?, folderId: String) -> Unit
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
    val folders by produceState(initialValue = emptyList<NoteStorage.NoteFolder>(), notesTick) {
        value = withContext(Dispatchers.IO) { NoteStorage.listFolders(context) }
    }

    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var onlyPinned by remember { mutableStateOf(false) }
    // null = 根目录;否则为当前浏览的文件夹 id
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var actionTarget by remember { mutableStateOf<NoteStorage.Note?>(null) }
    var folderTarget by remember { mutableStateOf<NoteStorage.NoteFolder?>(null) }
    var moveTarget by remember { mutableStateOf<NoteStorage.Note?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<NoteStorage.NoteFolder?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(searchVisible) {
        if (searchVisible) focusRequester.requestFocus()
    }

    val currentFolder = folders.find { it.id == currentFolderId }
    val inFolder = currentFolderId != null
    val scoped = remember(notes, currentFolderId, query, onlyPinned) {
        notes.filter { n ->
            val folderMatch = if (query.isNotBlank()) true else (n.folderId == (currentFolderId ?: ""))
            (if (onlyPinned) n.pinned else true) && folderMatch &&
                (query.isBlank() || n.title.contains(query, ignoreCase = true) || n.content.contains(query, ignoreCase = true))
        }
    }
    val rootNotes = remember(notes) { notes.filter { it.folderId.isEmpty() } }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 顶栏:返回 + 标题 + 操作 ═══
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = {
                        if (inFolder) {
                            currentFolderId = null
                            searchVisible = false
                            query = ""
                        } else onBack()
                    },
                    modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
                )
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        currentFolder?.name ?: "笔记",
                        color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 72.dp)
                    )
                    val totalCount = if (inFolder) scoped.size else notes.size
                    if (totalCount > 0) {
                        Text("$totalCount 篇", color = TextHint, fontSize = 10.sp)
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

            // ═══ 搜索框 ═══
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

            // ═══ 筛选 ═══
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip("全部", selected = !onlyPinned) { onlyPinned = false }
                FilterChip("📌 置顶", selected = onlyPinned) { onlyPinned = true }
            }

            // ═══ 方形卡片网格 ═══
            if (scoped.isEmpty() && !inFolder && folders.isEmpty() && !searchVisible && !onlyPinned) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(AccentBlue.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.NoteAdd, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("还没有笔记", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("点击右下角 + 创建第一篇,或先建一个文件夹", color = TextHint, fontSize = 12.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 根目录:文件夹区(含新建文件夹入口)
                    if (!inFolder && query.isBlank()) {
                        items(folders, key = { "f_" + it.id }, span = { GridItemSpan(1) }) { folder ->
                            FolderCard(
                                name = folder.name,
                                count = notes.count { it.folderId == folder.id },
                                onClick = { currentFolderId = folder.id },
                                onLongPress = { folderTarget = folder }
                            )
                        }
                        item(key = "new_folder") {
                            NewFolderCard(onClick = { showNewFolder = true })
                        }
                    }
                    // 空文件夹提示
                    if (inFolder && scoped.isEmpty() && query.isBlank()) {
                        item(span = { GridItemSpan(2) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Text("这个文件夹还是空的,点右下角 + 写一篇", color = TextHint, fontSize = 12.sp)
                            }
                        }
                    }
                    // 笔记卡片
                    items(scoped, key = { it.id }) { note ->
                        NoteCard(
                            title = note.title.ifEmpty { "无标题" },
                            summary = note.content,
                            updatedAt = relativeTime(note.updatedAt),
                            pinned = note.pinned,
                            onClick = { onOpenNote(note.id, "") },
                            onLongPress = { actionTarget = note }
                        )
                    }
                    if (!inFolder && query.isBlank() && rootNotes.isEmpty() && folders.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                                Text("根目录暂无笔记", color = TextHint, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ═══ 悬浮添加按钮(右下角,新建笔记归入当前文件夹) ═══
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp)) {
            LiquidGlassIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "新建笔记",
                onClick = { onOpenNote(null, currentFolderId ?: "") },
                modifier = Modifier.size(52.dp)
            )
        }

        // ═══ 设置面板 ═══
        if (showSettings) {
            NoteSettingsPanel(
                notes = notes,
                onBack = { showSettings = false; notesTick++ },
                onToggleKnowledge = { id, enabled ->
                    NoteStorage.setKnowledgeSource(context, id, enabled)
                    notesTick++
                }
            )
        }
    }

    // ═══ 笔记长按操作:移动 / 置顶 / 删除 ═══
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
                    if (note.pinned) "已置顶 · 置顶笔记会排在网格最前" else "可移动到文件夹、置顶到最前,或删除笔记",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow(icon = "📁", label = if (note.folderId.isEmpty()) "移动到文件夹" else "移动到其他位置") {
                        moveTarget = note
                        actionTarget = null
                    }
                    ActionRow(icon = "✕", label = "删除", danger = true) {
                        NoteStorage.deleteNote(context, note.id)
                        actionTarget = null
                        notesTick++
                    }
                    ActionRow(icon = "📌", label = if (note.pinned) "取消置顶" else "置顶") {
                        NoteStorage.setPinned(context, note.id, !note.pinned)
                        actionTarget = null
                        notesTick++
                    }
                    ActionRow(icon = "", label = "取消") {
                        actionTarget = null
                    }
                }
            }
        }
    }

    // ═══ 移动到文件夹 ═══
    moveTarget?.let { note ->
        Dialog(onDismissRequest = { moveTarget = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(CardBg)
                    .padding(20.dp)
            ) {
                Text("移动到", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                MoveRow(
                    icon = "🏠", name = "根目录", selected = note.folderId.isEmpty(),
                    onClick = {
                        NoteStorage.moveNoteToFolder(context, note.id, "")
                        moveTarget = null
                        notesTick++
                    }
                )
                folders.filter { it.id != note.folderId }.forEach { f ->
                    MoveRow(
                        icon = "📁", name = f.name, selected = false,
                        onClick = {
                            NoteStorage.moveNoteToFolder(context, note.id, f.id)
                            moveTarget = null
                            notesTick++
                        }
                    )
                }
                MoveRow(icon = "＋", name = "新建文件夹并移入", selected = false, accent = true) {
                    moveTarget = null
                    showNewFolder = true
                    pendingMoveNewNote = note.id
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "取消", color = TextSecondary, fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { moveTarget = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // ═══ 新建/重命名文件夹 ═══
    if (showNewFolder) {
        FolderNameDialog(
            title = "新建文件夹",
            initial = "",
            onDismiss = { showNewFolder = false; pendingMoveNewNote = null },
            onConfirm = { name ->
                val folder = NoteStorage.createFolder(context, name)
                pendingMoveNewNote?.let { NoteStorage.moveNoteToFolder(context, it, folder.id) }
                pendingMoveNewNote = null
                showNewFolder = false
                notesTick++
            }
        )
    }
    renameTarget?.let { folder ->
        FolderNameDialog(
            title = "重命名文件夹",
            initial = folder.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                NoteStorage.renameFolder(context, folder.id, name)
                renameTarget = null
                notesTick++
            }
        )
    }

    // ═══ 文件夹长按操作:重命名 / 删除 ═══
    folderTarget?.let { folder ->
        Dialog(onDismissRequest = { folderTarget = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(CardBg)
                    .padding(20.dp)
            ) {
                Text(
                    folder.name,
                    color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "删除文件夹后,里面的笔记会移回根目录,不会被删除",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow(icon = "✏️", label = "重命名") {
                        renameTarget = folder
                        folderTarget = null
                    }
                    ActionRow(icon = "✕", label = "删除文件夹", danger = true) {
                        NoteStorage.deleteFolder(context, folder.id)
                        if (currentFolderId == folder.id) currentFolderId = null
                        folderTarget = null
                        notesTick++
                    }
                    ActionRow(icon = "", label = "取消") {
                        folderTarget = null
                    }
                }
            }
        }
    }
}

/** "新建文件夹并移入"时暂存待移动的笔记 id */
private var pendingMoveNewNote: String? = null

/** 长按操作行 */
@Composable
private fun ActionRow(icon: String, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) Danger.copy(alpha = 0.10f) else SurfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon.isNotEmpty()) {
            Text(icon, fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            color = if (danger) Danger else TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text("›", color = TextHint, fontSize = 15.sp)
    }
}

/** 移动目标行 */
@Composable
private fun MoveRow(icon: String, name: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    selected -> SelectedBg
                    accent -> Primary.copy(alpha = 0.10f)
                    else -> SurfaceVariant
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            color = if (selected || accent) Primary else TextPrimary,
            fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (selected) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 文件夹名输入弹窗(新建/重命名共用) */
@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle(title)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("文件夹名称", color = TextHint, fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Primary.copy(alpha = 0.35f),
                unfocusedContainerColor = SurfaceVariant,
                focusedContainerColor = SurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(14.dp))
        PlainDialogButtons(
            buttons = listOf(
                Triple("取消", false) { onDismiss() },
                Triple("确定", true) { onConfirm(name.trim()) }
            )
        )
    }
}

/** 文件夹方形卡片 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    name: String,
    count: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    FrostedGlassCard(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Folder, contentDescription = name, tint = AccentBlue, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                name,
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text("$count 篇", color = TextHint, fontSize = 11.sp)
        }
    }
}

/** 新建文件夹卡片(虚位卡片) */
@Composable
private fun NewFolderCard(onClick: () -> Unit) {
    FrostedGlassCard(Modifier.fillMaxWidth().aspectRatio(1f)) {
        Column(
            Modifier.fillMaxSize().clickable(onClick = onClick).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹", tint = TextHint, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text("新建文件夹", color = TextHint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 笔记方形卡片:标题 + 摘要 + 时间,1:1 比例 */
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
    FrostedGlassCard(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Spacer(Modifier.width(4.dp))
                    Text("📌", fontSize = 11.sp)
                }
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Spacer(Modifier.height(6.dp))
            Text(updatedAt, color = TextHint, fontSize = 10.sp)
        }
    }
}

/** 笔记设置面板:显示设置(预览主题) + 功能设置(AI 搜题知识来源) */
@Composable
private fun NoteSettingsPanel(
    notes: List<NoteStorage.Note>,
    onBack: () -> Unit,
    onToggleKnowledge: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var previewThemeIndex by remember { mutableIntStateOf(NotePrefs.previewThemeIndex(context)) }

    Box(Modifier.fillMaxSize().background(BgPrimary)) {
        Column(Modifier.fillMaxSize()) {
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
