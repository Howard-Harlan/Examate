package go.examate.hyp.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.MarkdownText
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ChatMsg
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

// ════════════════════════════════════════════════
//  消息气泡(支持 Markdown + LaTeX + 生图 + 工具调用)
// ════════════════════════════════════════════════

@Composable
fun MessageBubble(msg: ChatMsg, timeFmt: SimpleDateFormat, context: Context) {
    val isUser = msg.role == "user"

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp
                ))
                .background(when {
                    msg.isError -> Color(0xFFFFF0F0)
                    isUser -> Color(0xFF5B8DEF)
                    else -> Color.White
                })
                .then(if (!isUser && !msg.isError) Modifier.border(0.5.dp, Color(0x15000000), RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)) else Modifier)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("message", msg.content))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // 用户发送的图片(异步解码 + 降采样,避免主线程 ANR)
            msg.imageUriString?.let { uriStr ->
                var bitmap by remember(uriStr) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(uriStr) {
                    bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                                // 先解码尺寸
                                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                                // 计算降采样(目标宽 720)
                                val sample = (opts.outWidth / 720).coerceAtLeast(1)
                                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { s2 ->
                                    val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                                    android.graphics.BitmapFactory.decodeStream(s2, null, decodeOpts)
                                }
                            }
                        } catch (e: Exception) { null }
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "图片",
                        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    if (msg.content.isNotEmpty()) Spacer(Modifier.height(8.dp))
                }
            }

            // AI 生成的图片(base64,异步解码)
            msg.imageBase64?.let { b64 ->
                var bitmap by remember(b64) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(b64) {
                    bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { null }
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "AI 生成图片",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                    if (msg.content.isNotEmpty()) Spacer(Modifier.height(8.dp))
                }
            }

            // 工具调用日志
            if (msg.toolCalls.isNotEmpty()) {
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
                        Text("🔧", fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(call, color = Color(0xFF5B8DEF), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (msg.content.isNotEmpty()) Spacer(Modifier.height(6.dp))
            }

            // 文本内容(用户用纯 Text,AI 用 Markdown 渲染)
            if (msg.content.isNotEmpty()) {
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
        }
        Text(
            text = timeFmt.format(Date(msg.time)),
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = if (isUser) 0.dp else 4.dp, end = if (isUser) 4.dp else 0.dp, top = 4.dp)
        )
    }
}
