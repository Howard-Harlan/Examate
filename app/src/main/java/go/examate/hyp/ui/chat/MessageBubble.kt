package go.examate.hyp.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.ui.components.MarkdownText
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.ErrorBg
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ActionCard
import go.examate.hyp.util.ChatMsg
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

private const val TAG = "MessageBubble"

@Composable
fun MessageBubble(
    msg: ChatMsg,
    timeFmt: SimpleDateFormat,
    context: Context,
    onQuickFollowUp: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onDelete: () -> Unit = {},
    showActions: Boolean = false,
    isStreaming: Boolean = false
) {
    val isUser = msg.role == "user"

    Column(
        if (isUser) Modifier.wrapContentWidth(Alignment.End) else Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        BubbleContent(
            msg = msg,
            isUser = isUser,
            isStreaming = isStreaming,
            context = context,
            onQuickFollowUp = onQuickFollowUp,
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
        )

        // 消息操作行(复制 / 重新生成 / 删除):仅在非流式且需要展示时出现
        if (showActions && !isStreaming) {
            MessageActionRow(
                isUser = isUser,
                onCopy = { copyMessageText(context, msg.content) },
                onRegenerate = onRegenerate,
                onDelete = onDelete,
                modifier = Modifier.padding(
                    start = if (isUser) 0.dp else 4.dp,
                    end = if (isUser) 4.dp else 0.dp,
                    top = 2.dp
                )
            )
        }

        Text(
            text = timeFmt.format(Date(msg.time)),
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.padding(
                start = if (isUser) 0.dp else 4.dp,
                end = if (isUser) 4.dp else 0.dp,
                top = 2.dp
            )
        )
    }
}

@Composable
private fun BubbleContent(
    msg: ChatMsg,
    isUser: Boolean,
    isStreaming: Boolean,
    context: Context,
    onQuickFollowUp: (String) -> Unit,
    modifier: Modifier,
    contentAlignment: Alignment
) {
    BoxWithConstraints(
        modifier,
        contentAlignment = contentAlignment
    ) {
        val bubbleMaxWidth = maxWidth * 0.95f
        Column(
            Modifier
                .widthIn(max = bubbleMaxWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp
                    )
                )
                .background(
                    when {
                        msg.isError -> ErrorBg
                        isUser -> AccentBlue
                        else -> Color.White
                    }
                )
                .then(
                    if (!isUser && !msg.isError) {
                        Modifier.border(
                            0.5.dp,
                            Color(0x15000000),
                            RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            UserImage(msg, context)
            AssistantImage(msg)
            ToolStepsSection(msg)
            MessageContent(msg, isUser, isStreaming)
            if (msg.cards.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                msg.cards.forEach { card -> ActionCardView(card) }
            }
            if (!isUser && msg.filePath != null) {
                Spacer(Modifier.height(8.dp))
                FilePreviewCard(msg.filePath)
            }
        }
    }
}

/**
 * 工具调用展示:优先用新的 ToolStepsTimeline(链式思考容器),
 * 旧消息(toolCalls 字符串列表)回退到简陋的行展示。
 */
@Composable
private fun ToolStepsSection(msg: ChatMsg) {
    if (msg.toolSteps.isNotEmpty()) {
        ToolStepsTimeline(steps = msg.toolSteps)
        if (msg.content.isNotEmpty()) Spacer(Modifier.height(6.dp))
    } else if (msg.toolCalls.isNotEmpty()) {
        ToolCalls(msg)
    }
}

@Composable
private fun UserImage(msg: ChatMsg, context: Context) {
    val uriStr = msg.imageUriString ?: return
    var bitmap by remember(uriStr) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uriStr) {
        bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                    val sample = (opts.outWidth / 720).coerceAtLeast(1)
                    context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { s2 ->
                        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        android.graphics.BitmapFactory.decodeStream(s2, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "图片",
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        if (msg.content.isNotEmpty()) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AssistantImage(msg: ChatMsg) {
    val b64 = msg.imageBase64 ?: return
    var bitmap by remember(b64) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(b64) {
        bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "AI 生成图片",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillWidth
        )
        if (msg.content.isNotEmpty()) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ToolCalls(msg: ChatMsg) {
    if (msg.toolCalls.isEmpty()) return
    msg.toolCalls.forEach { call ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF0F4FF))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("•", color = AccentBlue, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                call,
                color = AccentBlue,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (msg.content.isNotEmpty()) Spacer(Modifier.height(6.dp))
}

@Composable
private fun MessageContent(msg: ChatMsg, isUser: Boolean, isStreaming: Boolean = false) {
    if (msg.content.isEmpty()) return
    if (isUser) {
        Text(
            text = msg.content,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    } else {
        MarkdownText(
            markdown = msg.content,
            textColor = if (msg.isError) Danger else TextPrimary,
            fontSize = 14,
            isStreaming = isStreaming
        )
    }
}

@Composable
internal fun ActionCardView(card: ActionCard) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF0F4FF))
            .clickable {
                try {
                    val intent = android.content.Intent(context, go.examate.hyp.CampusServiceActivity::class.java)
                    // 按 card.type 分发到具体页面和 Tab
                    when (card.type) {
                        "ecard_balance" -> {
                            intent.putExtra("target_route", "ecard")
                            intent.putExtra("target_tab", "balance")
                        }
                        "ecard_topup" -> {
                            intent.putExtra("target_route", "ecard")
                            intent.putExtra("target_tab", "balance")
                        }
                        "room_electricity" -> {
                            intent.putExtra("target_route", "ecard")
                            intent.putExtra("target_tab", "payfee")
                        }
                        "campus_grades" -> {
                            intent.putExtra("target_route", "jwxt")
                        }
                        "campus_info" -> {
                            intent.putExtra("target_route", "jwxt")
                        }
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { Log.w(TAG, "startActivity failed", e) }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(card.title, color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (card.summary.isNotEmpty()) {
                Text(card.summary, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text("›", color = AccentBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 文件预览卡片 — AI 生成的文件下方显示,点击打开预览 */
@Composable
private fun FilePreviewCard(filePath: String) {
    var showPreview by remember { mutableStateOf(false) }
    val file = java.io.File(filePath)
    if (!file.exists()) return

    val fileName = file.name
    val ext = fileName.substringAfterLast(".", "").lowercase()
    val icon = when (ext) {
        "html" -> "🌐"
        "md" -> "📝"
        "json" -> "📋"
        "txt" -> "📄"
        else -> "📎"
    }
    val label = when (ext) {
        "html" -> "网页"
        "md" -> "Markdown"
        "json" -> "JSON"
        "txt" -> "文本"
        else -> "文件"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF0F4FF))
            .clickable { showPreview = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(fileName, color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$label · 点击预览", color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", color = AccentBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }

    if (showPreview) {
        FilePreviewDialog(filePath, onDismiss = { showPreview = false })
    }
}

/** 文件预览弹窗 — 根据文件类型用不同方式渲染 */
@Composable
private fun FilePreviewDialog(filePath: String, onDismiss: () -> Unit) {
    val file = java.io.File(filePath)
    val content = remember { try { file.readText() } catch (_: Exception) { "" } }
    val ext = file.extension.lowercase()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 40.dp, bottom = 40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
        ) {
            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8F9FA))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(file.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("关闭", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onDismiss() }.padding(start = 12.dp))
            }

            // 内容区
            when (ext) {
                "html" -> {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                "md" -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownText(
                            markdown = content,
                            textColor = TextPrimary,
                            fontSize = 14
                        )
                    }
                }
                else -> {
                    // txt, json, etc.
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = content,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
