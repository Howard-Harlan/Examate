package go.examate.hyp.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ActionCard
import go.examate.hyp.util.ChatMsg
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun MessageBubble(
    msg: ChatMsg,
    timeFmt: SimpleDateFormat,
    context: Context,
    onQuickFollowUp: (String) -> Unit = {}
) {
    val isUser = msg.role == "user"
    var showMenu by remember(msg.id) { mutableStateOf(false) }
    var showSelectionDialog by remember(msg.id) { mutableStateOf(false) }

    Column(
        if (isUser) Modifier.wrapContentWidth(Alignment.End) else Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
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
                                msg.isError -> Color(0xFFFFF0F0)
                                isUser -> Color(0xFF5B8DEF)
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
                        .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    UserImage(msg, context)
                    AssistantImage(msg)
                    ToolCalls(msg)
                    MessageContent(msg, isUser)
                    if (msg.cards.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        msg.cards.forEach { card -> ActionCardView(card) }
                    }
                }

                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("复制全文") },
                        onClick = {
                            showMenu = false
                            copyMessage(context, msg.content)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("选择复制") },
                        onClick = {
                            showMenu = false
                            showSelectionDialog = true
                        }
                    )
                    if (!isUser && msg.content.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("快捷追问") },
                            onClick = {
                                showMenu = false
                                onQuickFollowUp(msg.content)
                            }
                        )
                    }
                }
            }
        }

        Text(
            text = timeFmt.format(Date(msg.time)),
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.padding(
                start = if (isUser) 0.dp else 4.dp,
                end = if (isUser) 4.dp else 0.dp,
                top = 4.dp
            )
        )
    }

    if (showSelectionDialog) {
        Dialog(onDismissRequest = { showSelectionDialog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(16.dp)
            ) {
                Text("选择内容", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Text(msg.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "关闭",
                        color = Color(0xFF5B8DEF),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSelectionDialog = false }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
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
            Text("•", color = Color(0xFF5B8DEF), fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                call,
                color = Color(0xFF5B8DEF),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (msg.content.isNotEmpty()) Spacer(Modifier.height(6.dp))
}

@Composable
private fun MessageContent(msg: ChatMsg, isUser: Boolean) {
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
            textColor = if (msg.isError) Color(0xFFFF3B30) else TextPrimary,
            fontSize = 14
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
                    // 跳转到 Examate 主界面(校园服务 Tab)
                    val intent = android.content.Intent(context, go.examate.hyp.MainActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, "请先登录校园服务", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(card.title, color = Color(0xFF5B8DEF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (card.summary.isNotEmpty()) {
                Text(card.summary, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text("›", color = Color(0xFF5B8DEF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
