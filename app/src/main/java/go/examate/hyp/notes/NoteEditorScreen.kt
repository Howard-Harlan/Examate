package go.examate.hyp.notes

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import go.examate.hyp.config.MyConfig
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.ImageViewerDialog
import go.examate.hyp.ui.components.LocalMarkdownLinkHandler
import go.examate.hyp.ui.components.openUrlExternally
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.MarkdownText
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Divider
import go.examate.hyp.ui.theme.GlassChipTint
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.HttpUtils
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记编辑器(重做版) — 顶栏分段切换(编辑/预览)+ 纸张感编辑区 + 沉浸阅读预览。
 *
 * - 顶栏:返回 + 中央"编辑/预览"分段控件(Crossfade 过渡)+ 保存
 * - 编辑:标题 + 正文落在带横线的纸张卡片上,底部 Markdown 快捷工具栏 + AI
 * - 预览:大标题 + 元信息(时间/字数/阅读时长)+ 排版化 Markdown 正文,图片圆角带说明
 */
@Composable
fun NoteEditorScreen(
    noteId: String?,
    folderId: String = "",
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf(TextFieldValue("")) }
    // 读写切换:记住上次退出时的状态(读/写)
    var previewMode by remember { mutableStateOf(NotePrefs.previewMode(context)) }
    var loaded by remember { mutableStateOf(noteId == null) }
    var originalNote by remember { mutableStateOf<NoteStorage.Note?>(null) }

    // ═══ AI 优化/总结 ═══
    val scope = rememberCoroutineScope()
    var showAiDialog by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }

    /** 调用第一个可用 provider 对笔记内容做 AI 处理 */
    fun runAi(mode: String) {
        val provider = MyConfig.loadProviders(context).firstOrNull {
            it.visible && it.apiKey.isNotEmpty() && it.requestUrl.isNotEmpty()
        }
        if (provider == null) {
            ToastUtil.show(context, "请先在 API 设置里配置可用的模型")
            return
        }
        if (content.text.isBlank()) {
            ToastUtil.show(context, "先写点内容再让 AI 处理吧")
            return
        }
        aiBusy = true
        val systemPrompt = when (mode) {
            "summarize" -> "你是笔记助手。请把用户笔记总结为简洁的要点(保留 Markdown 格式),直接输出总结内容,不要客套话。"
            else -> "你是笔记助手。请优化用户笔记的表述与结构,使其更清晰、更有条理(保留 Markdown 格式)。直接输出优化后的完整笔记内容,不要客套话。"
        }
        val userInput = if (mode == "summarize") {
            "请总结以下笔记:\n\n${content.text}"
        } else {
            "请优化以下笔记:\n\n${content.text}"
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                HttpUtils.sendPostRequestResult(
                    provider.requestUrl,
                    provider.modelId,
                    userInput,
                    provider.apiKey,
                    systemPrompt,
                    provider.enableThinking,
                    provider.endpointType ?: "chat"
                )
            }
            aiBusy = false
            val text = when (result) {
                is go.examate.hyp.util.ApiResult.Success ->
                    HttpUtils.extractContent(result.data, provider.endpointType ?: "chat")
                else -> ""
            }
            if (text.isBlank()) {
                ToastUtil.show(context, "AI 处理失败,请检查网络或模型配置")
            } else if (mode == "summarize") {
                val block = "\n\n## AI 总结\n$text\n"
                content = if (content.text.contains("## AI 总结")) {
                    content
                } else {
                    insertAtCursor(content, block)
                }
                ToastUtil.show(context, "AI 总结已插入")
            } else {
                content = TextFieldValue(text.trim())
                ToastUtil.show(context, "AI 优化完成,可预览或继续编辑")
            }
            showAiDialog = false
        }
    }

    // 加载已有笔记内容
    LaunchedEffect(noteId) {
        if (noteId != null) {
            val note = NoteStorage.loadNote(context, noteId)
            if (note != null) {
                title = note.title
                content = TextFieldValue(note.content)
                originalNote = note
            }
        }
        loaded = true
    }

    // 图片选择器:复制到 assets 目录,光标处插入 ![](assets/xxx)
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val path = NoteStorage.copyAsset(context, uri)
            if (path.isNotEmpty()) {
                content = insertAtCursor(content, "\n![]($path)\n")
                ToastUtil.show(context, "图片已插入")
            } else {
                ToastUtil.show(context, "图片复制失败")
            }
        }
    }

    // 音频选择器:复制到 assets 目录,光标处插入 [audio](assets/xxx)
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val path = NoteStorage.copyAsset(context, uri)
            if (path.isNotEmpty()) {
                content = insertAtCursor(content, "\n[audio]($path)\n")
                ToastUtil.show(context, "音频已插入")
            } else {
                ToastUtil.show(context, "音频复制失败")
            }
        }
    }

    fun saveAndBack() {
        val t = title.trim()
        val c = content.text
        if (t.isEmpty() && c.isBlank()) {
            onBack(); return
        }
        val finalTitle = t.ifEmpty { "无标题" }
        if (noteId == null || originalNote == null) {
            NoteStorage.createNote(context, finalTitle, c, folderId)
        } else {
            NoteStorage.saveNote(context, originalNote!!.copy(title = finalTitle, content = c))
        }
        ToastUtil.show(context, "已保存")
        onBack()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ═══ 顶栏:返回 + 分段切换 + 保存 ═══
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = { saveAndBack() },
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            // 中央分段控件:编辑 / 预览
            ModeSegment(
                previewMode = previewMode,
                onToggle = {
                    previewMode = it
                    NotePrefs.setPreviewMode(context, it)
                },
                modifier = Modifier.align(Alignment.Center)
            )
            LiquidGlassIconButton(
                icon = Icons.Filled.Save,
                contentDescription = "保存",
                onClick = { saveAndBack() },
                modifier = Modifier.size(40.dp).align(Alignment.CenterEnd)
            )
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentBlue)
            }
            return@Column
        }

        // ═══ 正文区:编辑 / 预览(Crossfade 过渡) ═══
        Crossfade(
            targetState = previewMode,
            label = "note_mode",
            modifier = Modifier.weight(1f)
        ) { isPreview ->
            if (isPreview) {
                NotePreview(
                    title = title,
                    content = content.text,
                    updatedAt = originalNote?.updatedAt ?: System.currentTimeMillis(),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                NoteEditPaper(
                    title = title,
                    onTitleChange = { title = it },
                    content = content,
                    onContentChange = { content = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ═══ 底部 Markdown 快捷工具栏(仅编辑模式显示) ═══
        if (!previewMode) {
            MarkdownShortcutBar(
                onInsert = { insertion -> content = insertAtCursor(content, insertion) },
                onWrap = { prefix, suffix -> content = wrapSelection(content, prefix, suffix) },
                onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
                onInsertAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onAi = { showAiDialog = true }
            )
        }
    }

    // ═══ AI 优化/总结弹窗 ═══
    if (showAiDialog) {
        PlainDialog(onDismissRequest = { if (!aiBusy) showAiDialog = false }) {
            PlainDialogTitle("AI 笔记助手")
            PlainDialogContent(
                if (aiBusy) "正在处理笔记,请稍候…"
                else "总结:提炼笔记要点,插入到正文末尾。\n优化:润色表述与结构,覆盖正文。"
            )
            if (aiBusy) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(28.dp))
                }
            }
            PlainDialogButtons(
                buttons = listOf(
                    Triple("AI 总结", true) { runAi("summarize") },
                    Triple("AI 优化", true) { runAi("optimize") },
                    Triple("取消", false) { showAiDialog = false }
                )
            )
        }
    }
}

// ═══ 顶栏分段控件 ═══

/** "编辑/预览"分段切换:胶囊底 + 选中高亮 */
@Composable
private fun ModeSegment(
    previewMode: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceVariant)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SegmentOption(
            icon = Icons.Filled.Edit,
            label = "编辑",
            selected = !previewMode,
            onClick = { onToggle(false) }
        )
        SegmentOption(
            icon = Icons.Filled.Visibility,
            label = "预览",
            selected = previewMode,
            onClick = { onToggle(true) }
        )
    }
}

@Composable
private fun SegmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) SelectedBg else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) Color.White else TextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

// ═══ 编辑模式:纸张感编辑区 ═══

/** 编辑纸张:标题 + 正文,浅横线作行参照 */
@Composable
private fun NoteEditPaper(
    title: String,
    onTitleChange: (String) -> Unit,
    content: TextFieldValue,
    onContentChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    val lineColor = TextPrimary.copy(alpha = 0.045f)
    Box(modifier) {
        // 纸张横线:装饰性行参照,固定间距
        Canvas(Modifier.fillMaxSize()) {
            val spacing = 28.dp.toPx()
            var y = spacing
            while (y < size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += spacing
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 8.dp, bottom = 24.dp)
        ) {
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                textStyle = TextStyle(
                    fontSize = 24.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                ),
                cursorBrush = SolidColor(AccentBlue),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) {
                            Text("标题", color = TextHint, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                        inner()
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Spacer(Modifier.height(12.dp))
            // 正文区:左侧行号列 + 输入框(共用滚动,行号按软换行对齐)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val textWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (maxWidth - 16.dp - 6.dp).toPx()
                }.toInt()
                Row(Modifier.fillMaxWidth()) {
                    LineNumberGutter(content = content.text, textWidthPx = textWidthPx)
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = content,
                        onValueChange = onContentChange,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 28.sp,
                            color = TextPrimary
                        ),
                        cursorBrush = SolidColor(AccentBlue),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (content.text.isEmpty()) {
                                    Text("开始书写…", color = TextHint, fontSize = 15.sp, lineHeight = 28.sp)
                                }
                                inner()
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 行号列 — 与正文共用滚动容器,按逻辑行测量软换行后的视觉行数,
 * 使行号高度与正文行严格对齐(软换行处不显示编号)。
 * [textWidthPx] 为正文输入框的实际像素宽度,由父级测量传入。
 */
@Composable
private fun LineNumberGutter(
    content: String,
    textWidthPx: Int,
    modifier: Modifier = Modifier
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineH = with(density) { 28.sp.toDp() }
    val measureStyle = TextStyle(fontSize = 16.sp, lineHeight = 28.sp)

    // 逻辑行 -> (行号, 软换行后的视觉行数)
    val rows = remember(content, textWidthPx) {
        if (content.isEmpty()) {
            listOf(1 to 1)
        } else {
            content.lines().mapIndexed { idx, line ->
                val visual = runCatching {
                    measurer.measure(
                        androidx.compose.ui.text.AnnotatedString(line.ifEmpty { " " }),
                        measureStyle,
                        constraints = androidx.compose.ui.unit.Constraints(
                            minWidth = 0,
                            maxWidth = textWidthPx.coerceAtLeast(1),
                            minHeight = 0,
                            maxHeight = Int.MAX_VALUE
                        )
                    ).lineCount
                }.getOrDefault(1)
                (idx + 1) to visual.coerceAtLeast(1)
            }
        }
    }
    Column(modifier.width(16.dp)) {
        rows.forEach { (num, visualLines) ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(lineH * visualLines),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    "$num",
                    color = TextHint.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    lineHeight = 28.sp
                )
            }
        }
    }
}

// ═══ 预览模式:沉浸阅读 ═══

/** 预览页:大标题 + 元信息 + 排版化正文(图片圆角带说明,音频播放条) */
@Composable
private fun NotePreview(
    title: String,
    content: String,
    updatedAt: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val segments = remember(content) { parsePreviewSegments(content) }
    val previewTheme = remember {
        NOTE_PREVIEW_THEMES[NotePrefs.previewThemeIndex(context).coerceIn(0, NOTE_PREVIEW_THEMES.size - 1)]
    }
    val charCount = content.replace(Regex("\\s"), "").length
    val metaTime = remember(updatedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))
    }
    // 全屏图片查看(点击预览中的配图)
    var viewingImage by remember { mutableStateOf<File?>(null) }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalMarkdownLinkHandler provides { url -> openUrlExternally(context, url) }
    ) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 48.dp)
    ) {
        if (title.isNotBlank()) {
            Text(title, color = TextPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        } else {
            Text("无标题", color = TextHint, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "$metaTime · $charCount 字",
            color = TextHint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
        Spacer(Modifier.height(18.dp))

        if (segments.isEmpty() || content.isBlank()) {
            Text("这里还是空的,切到编辑开始书写吧 ✍️", color = TextHint, fontSize = 13.sp)
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    segments.forEach { seg ->
                        when (seg) {
                            is PreviewSegment.Image -> {
                                val file = File(context.filesDir, "notes/${seg.path}")
                                if (file.exists()) {
                                    AsyncImage(
                                        model = file,
                                        contentDescription = seg.alt,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 360.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { viewingImage = file }
                                    )
                                    if (seg.alt.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(seg.alt, color = TextHint, fontSize = 11.sp, lineHeight = 16.sp)
                                    }
                                } else {
                                    Text("📷 图片缺失: ${seg.path}", color = TextHint, fontSize = 12.sp)
                                }
                            }
                            is PreviewSegment.Audio -> {
                                val file = File(context.filesDir, "notes/${seg.path}")
                                AudioBar(filename = file.name, playable = file.exists(), onClick = {
                                    if (file.exists()) {
                                        runCatching {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "audio/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "播放音频"))
                                        }.onFailure {
                                            ToastUtil.show(context, "无法打开音频文件")
                                        }
                                    } else {
                                        ToastUtil.show(context, "音频文件不存在")
                                    }
                                })
                            }
                            is PreviewSegment.Text -> {
                                if (seg.text.isNotBlank()) {
                                    MarkdownText(
                                        markdown = seg.text,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = previewTheme.fontSize,
                                        fontFamily = previewTheme.fontFamily,
                                        lineHeightExtra = previewTheme.lineHeightExtra
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    // 全屏图片查看器(含保存到相册)
    viewingImage?.let { file ->
        ImageViewerDialog(model = file, onDismiss = { viewingImage = null })
    }
}

/** 音频播放条:文件名 + 播放图标,点击用系统 Intent 打开 */
@Composable
private fun AudioBar(filename: String, playable: Boolean, onClick: () -> Unit) {
    FrostedGlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = playable, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (playable) AccentBlue else AccentBlue.copy(0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("音频", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(filename, color = TextHint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (playable) "播放" else "缺失", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ═══ 工具:光标处插入文本 ═══

/** 在当前 TextFieldValue 光标处插入文本,光标移到插入内容末尾 */
private fun insertAtCursor(
    current: TextFieldValue,
    insertion: String
): TextFieldValue {
    val pos = current.selection.start.coerceIn(0, current.text.length)
    val newText = current.text.substring(0, pos) + insertion + current.text.substring(pos)
    return TextFieldValue(text = newText, selection = TextRange(pos + insertion.length))
}

/**
 * 用 prefix/suffix 包裹当前选中文本;若无选中则插入 prefix+suffix 并把光标放中间。
 * 用于加粗(**)、斜体(*)、行内代码(`) 等内联格式。
 */
private fun wrapSelection(
    current: TextFieldValue,
    prefix: String,
    suffix: String
): TextFieldValue {
    val start = current.selection.start.coerceIn(0, current.text.length)
    val end = current.selection.end.coerceIn(0, current.text.length)
    val selText = current.text.substring(start, end)
    val newText = current.text.substring(0, start) + prefix + selText + suffix + current.text.substring(end)
    return if (selText.isEmpty()) {
        TextFieldValue(text = newText, selection = TextRange(start + prefix.length))
    } else {
        TextFieldValue(text = newText, selection = TextRange(start, end + prefix.length + suffix.length))
    }
}

// ════════════════════════════════════════
//  Markdown 快捷工具栏(含 AI)
// ════════════════════════════════════════

@Composable
private fun MarkdownShortcutBar(
    onInsert: (String) -> Unit,
    onWrap: (String, String) -> Unit,
    onInsertImage: () -> Unit,
    onInsertAudio: () -> Unit,
    onAi: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // AI 助手(与格式操作同排)
        Box(
            Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AccentBlue.copy(alpha = 0.12f))
                .clickable { onAi() }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("AI", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        // 标题 H1 / H2 / H3
        MarkdownShortcutButton(text = "H1", onClick = { onInsert("\n# ") })
        MarkdownShortcutButton(text = "H2", onClick = { onInsert("\n## ") })
        MarkdownShortcutButton(text = "H3", onClick = { onInsert("\n### ") })
        // 加粗 / 斜体 / 行内代码
        MarkdownShortcutButton(text = "B", bold = true, onClick = { onWrap("**", "**") })
        MarkdownShortcutButton(text = "I", italic = true, onClick = { onWrap("*", "*") })
        MarkdownShortcutButton(text = "</>", onClick = { onWrap("`", "`") })
        // 无序列表 / 有序列表 / 引用 / 分割线
        MarkdownShortcutButton(text = "•", onClick = { onInsert("\n- ") })
        MarkdownShortcutButton(text = "1.", onClick = { onInsert("\n1. ") })
        MarkdownShortcutButton(text = "❝", onClick = { onInsert("\n> ") })
        MarkdownShortcutButton(text = "—", onClick = { onInsert("\n---\n") })
        // 链接
        MarkdownShortcutButton(text = "🔗", onClick = { onInsert("[](https://)") })
        // 图片 / 音频(走文件选择器)
        ShortcutIconButton(
            icon = Icons.Filled.Image,
            contentDescription = "插入图片",
            onClick = onInsertImage
        )
        ShortcutIconButton(
            icon = Icons.Filled.MusicNote,
            contentDescription = "插入音频",
            onClick = onInsertAudio
        )
    }
}

/** 文字快捷按钮:小圆角胶囊,浅色背景 */
@Composable
private fun MarkdownShortcutButton(
    text: String,
    onClick: () -> Unit,
    bold: Boolean = false,
    italic: Boolean = false
) {
    val fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
    val fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassChipTint)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = TextSecondary,
            fontSize = 14.sp,
            fontWeight = fontWeight,
            fontStyle = fontStyle
        )
    }
}

/** 图标快捷按钮:与文字按钮同等大小,用于图片/音频插入 */
@Composable
private fun ShortcutIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassChipTint)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = TextSecondary, modifier = Modifier.size(18.dp))
    }
}

private sealed class PreviewSegment {
    data class Text(val text: String) : PreviewSegment()
    data class Image(val alt: String, val path: String) : PreviewSegment()
    data class Audio(val path: String) : PreviewSegment()
}

/**
 * 解析正文为预览段:图片 ![alt](path) / 音频 [audio](path) 或 ::audio[](path) / 文本
 * 图片与音频按出现位置内联拆分,其余文本段交由 MarkdownText 渲染。
 */
private fun parsePreviewSegments(content: String): List<PreviewSegment> {
    val segments = mutableListOf<PreviewSegment>()
    val regex = Regex("!?\\[[^]]*\\]\\([^)]+\\)|::audio\\[[^]]*\\]\\([^)]+\\)")
    var lastEnd = 0
    regex.findAll(content).forEach { match ->
        if (match.range.first > lastEnd) {
            val text = content.substring(lastEnd, match.range.first)
            if (text.isNotBlank()) segments.add(PreviewSegment.Text(text))
        }
        val full = match.value
        val imgMatch = Regex("!\\[([^]]*)\\]\\(([^)]+)\\)").matchEntire(full)
        val audMatch = Regex("(?:\\[audio\\]|::audio\\[[^]]*\\])\\(([^)]+)\\)").matchEntire(full)
        when {
            imgMatch != null -> segments.add(PreviewSegment.Image(
                alt = imgMatch.groupValues[1],
                path = imgMatch.groupValues[2]
            ))
            audMatch != null -> segments.add(PreviewSegment.Audio(audMatch.groupValues[1]))
            else -> segments.add(PreviewSegment.Text(full))
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < content.length) {
        val text = content.substring(lastEnd)
        if (text.isNotBlank()) segments.add(PreviewSegment.Text(text))
    }
    return segments.ifEmpty { listOf(PreviewSegment.Text(content)) }
}
