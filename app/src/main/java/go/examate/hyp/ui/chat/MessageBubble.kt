package go.examate.hyp.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.ui.components.MarkdownText
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.ErrorBg
import go.examate.hyp.ui.theme.TextHint
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
    isStreaming: Boolean = false,
    showAvatar: Boolean = false,
    avatarBitmap: android.graphics.Bitmap? = null
) {
    val isUser = msg.role == "user"
    var showMenu by remember(msg.id) { mutableStateOf(false) }
    var showSelectionDialog by remember(msg.id) { mutableStateOf(false) }

    // 仅 assistant 且开关开启时显示头像
    val displayAvatar = showAvatar && !isUser

    Column(
        if (isUser) Modifier.wrapContentWidth(Alignment.End) else Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (displayAvatar) {
            // assistant + 开启头像:Row(头像 + 气泡)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(end = 8.dp, top = 2.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8EDF3)),
                    contentAlignment = Alignment.Center
                ) {
                    avatarBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Agent",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                BubbleContent(
                    msg = msg,
                    isUser = isUser,
                    isStreaming = isStreaming,
                    context = context,
                    showMenu = showMenu,
                    onShowMenuChange = { showMenu = it },
                    onQuickFollowUp = onQuickFollowUp,
                    onShowSelectionDialog = { showSelectionDialog = true },
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                )
            }
        } else {
            // 原布局:user 或未开启头像的 assistant
            BubbleContent(
                msg = msg,
                isUser = isUser,
                isStreaming = isStreaming,
                context = context,
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                onQuickFollowUp = onQuickFollowUp,
                onShowSelectionDialog = { showSelectionDialog = true },
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
            )
        }

        Text(
            text = timeFmt.format(Date(msg.time)),
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.padding(
                start = if (isUser) 0.dp else (if (displayAvatar) 40.dp else 4.dp),
                end = if (isUser) 4.dp else 0.dp,
                top = 4.dp
            )
        )
    }

    if (showSelectionDialog) {
        SelectionDialog(
            content = msg.content,
            isUser = isUser,
            onDismiss = { showSelectionDialog = false },
            onCopy = { text -> copyMessage(context, text) },
            onFollowUp = { text -> onQuickFollowUp(text) }
        )
    }
}

/**
 * 选择内容对话框:用只读 TextField 替代 SelectionContainer,避免触发系统选中菜单导致操作卡顿。
 * 用户选中一段文字后,可"复制选中"或"追问选中"(仅 assistant)。
 */
@Composable
private fun SelectionDialog(
    content: String,
    isUser: Boolean,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onFollowUp: (String) -> Unit
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(content)) }
    val selectedText = remember(textFieldValue) {
        if (textFieldValue.selection.collapsed) "" else {
            content.substring(
                textFieldValue.selection.start.coerceAtLeast(0),
                textFieldValue.selection.end.coerceAtMost(content.length)
            )
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(16.dp)
        ) {
            Text("选择内容", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("长按拖动光标选择文字", color = TextHint, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
                readOnly = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 复制选中(无选中时复制全文)
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedText.isNotEmpty()) AccentBlue else Color(0xFFE5E5EA))
                        .clickable {
                            val text = selectedText.ifEmpty { content }
                            onCopy(text)
                            onDismiss()
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (selectedText.isNotEmpty()) "复制选中" else "复制全文",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                // 追问选中(仅 assistant 且有选中时)
                if (!isUser && selectedText.isNotEmpty()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentBlue)
                            .clickable {
                                onFollowUp(selectedText)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "追问选中",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                // 关闭
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFE5E5EA))
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("关闭", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun BubbleContent(
    msg: ChatMsg,
    isUser: Boolean,
    isStreaming: Boolean,
    context: Context,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onQuickFollowUp: (String) -> Unit,
    onShowSelectionDialog: () -> Unit,
    modifier: Modifier,
    contentAlignment: Alignment
) {
    BoxWithConstraints(
        modifier,
        contentAlignment = contentAlignment
    ) {
        val bubbleMaxWidth = maxWidth * 0.86f
        Box {
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
                    .combinedClickable(onClick = {}, onLongClick = { onShowMenuChange(true) })
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                UserImage(msg, context)
                AssistantImage(msg)
                ToolCalls(msg)
                MessageContent(msg, isUser, isStreaming)
                if (msg.cards.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    msg.cards.forEach { card -> ActionCardView(card) }
                }
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { onShowMenuChange(false) }) {
                        DropdownMenuItem(
                            text = { Text("复制全文") },
                            onClick = {
                                onShowMenuChange(false)
                                copyMessage(context, msg.content)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("选择内容") },
                            onClick = {
                                onShowMenuChange(false)
                                onShowSelectionDialog()
                            }
                        )
                    }
        }
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

private fun copyMessage(context: Context, content: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("message", content))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
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
