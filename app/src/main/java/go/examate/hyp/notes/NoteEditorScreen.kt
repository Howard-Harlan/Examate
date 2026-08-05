package go.examate.hyp.notes

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.MarkdownText
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import java.io.File

/**
 * 笔记编辑器 — 标题 + 正文(Markdown) + 顶栏预览切换 + 底部 Markdown 快捷工具栏。
 *
 * 设计参考 Bear / Obsidian 等优秀笔记软件:
 * - 顶栏:返回 + 动态标题 + 保存 + 编辑/预览切换
 * - 编辑区:无边框标题(大字) + 无边框正文,视觉干净专注内容
 * - 底部工具栏:可横向滚动的 Markdown 快捷键(标题/加粗/斜体/列表/引用/代码/链接/图片/音频)
 *
 * - 插入图片:OpenDocument(image) → copyAsset → 光标处插入 `![](assets/xxx)`
 * - 插入音频:OpenDocument(audio) → copyAsset → 光标处插入 `[audio](assets/xxx)`
 * - 预览模式:MarkdownText 渲染正文;图片用 AsyncImage,音频显示可点击播放条
 * - 保存:新建用 [NoteStorage.createNote],编辑用 [NoteStorage.saveNote];返回时自动保存
 *
 * @param noteId 笔记 id;null 表示新建
 * @param onBack 返回回调(已自动保存)
 */
@Composable
fun NoteEditorScreen(
    noteId: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf(TextFieldValue("")) }
    var previewMode by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(noteId == null) }
    var originalNote by remember { mutableStateOf<NoteStorage.Note?>(null) }

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
        // 内容全空:直接返回,不创建空笔记
        if (t.isEmpty() && c.isBlank()) {
            onBack(); return
        }
        val finalTitle = t.ifEmpty { "无标题" }
        if (noteId == null || originalNote == null) {
            NoteStorage.createNote(context, finalTitle, c)
        } else {
            NoteStorage.saveNote(context, originalNote!!.copy(title = finalTitle, content = c))
        }
        ToastUtil.show(context, "已保存")
        onBack()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ═══ 顶栏:返回 + 标题 + 预览切换 + 保存 ═══
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = { saveAndBack() },
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text(
                if (noteId == null) "新建笔记" else title.ifEmpty { "编辑笔记" },
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center)
            )
            Row(
                Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidGlassIconButton(
                    icon = if (previewMode) Icons.Filled.Edit else Icons.Filled.Visibility,
                    contentDescription = if (previewMode) "编辑" else "预览",
                    onClick = { previewMode = !previewMode },
                    modifier = Modifier.size(40.dp)
                )
                LiquidGlassIconButton(
                    icon = Icons.Filled.Save,
                    contentDescription = "保存",
                    onClick = { saveAndBack() },
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentBlue)
            }
            return@Column
        }

        // ═══ 正文区:编辑 / 预览 ═══
        if (previewMode) {
            NotePreview(
                title = title,
                content = content.text,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 标题:无边框大字,视觉突出
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("标题", color = TextHint, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // 正文:无边框,占满剩余空间
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text("开始书写…（支持 Markdown）", color = TextHint, fontSize = 15.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = TextPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }

        // ═══ 底部 Markdown 快捷工具栏(仅编辑模式显示,可横向滚动) ═══
        if (!previewMode) {
            MarkdownShortcutBar(
                onInsert = { insertion -> content = insertAtCursor(content, insertion) },
                onWrap = { prefix, suffix -> content = wrapSelection(content, prefix, suffix) },
                onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
                onInsertAudio = { audioPicker.launch(arrayOf("audio/*")) }
            )
        }
    }
}

// ═══ 预览模式 ═══

/** 预览页:标题 + Markdown 正文(图片用 AsyncImage,音频显示可点击播放条) */
@Composable
private fun NotePreview(title: String, content: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val segments = remember(content) { parsePreviewSegments(content) }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (title.isNotBlank()) {
            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        segments.forEach { seg ->
            when (seg) {
                is PreviewSegment.Image -> {
                    val file = File(context.filesDir, "notes/${seg.path}")
                    if (file.exists()) {
                        AsyncImage(
                            model = file,
                            contentDescription = seg.alt,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        )
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
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
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
        // 无选中:光标放 prefix 后,方便直接输入
        TextFieldValue(text = newText, selection = TextRange(start + prefix.length))
    } else {
        // 有选中:光标选中包裹后的整段
        TextFieldValue(text = newText, selection = TextRange(start, end + prefix.length + suffix.length))
    }
}

// ════════════════════════════════════════
//  Markdown 快捷工具栏
// ════════════════════════════════════════

/**
 * Markdown 快捷工具栏 — 可横向滚动的按钮组,参考 Bear / Obsidian 的格式栏设计。
 *
 * 按钮分两类:
 * - [onInsert]:直接在光标处插入文本(标题/列表/引用/链接/分割线等块级格式)
 * - [onWrap]:用前后缀包裹选中文本(加粗/斜体/行内代码等内联格式)
 * - 图片/音频:走系统文件选择器,插入对应 Markdown 语法
 *
 * @param onInsert (插入文本) -> Unit
 * @param onWrap (前缀, 后缀) -> Unit
 * @param onInsertImage 启动图片选择器
 * @param onInsertAudio 启动音频选择器
 */
@Composable
private fun MarkdownShortcutBar(
    onInsert: (String) -> Unit,
    onWrap: (String, String) -> Unit,
    onInsertImage: () -> Unit,
    onInsertAudio: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            .background(Color.White.copy(alpha = 0.6f))
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
            .background(Color.White.copy(alpha = 0.6f))
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
    // 匹配:![alt](path) 图片 | [audio](path) 音频 | ::audio[](path) 音频
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
