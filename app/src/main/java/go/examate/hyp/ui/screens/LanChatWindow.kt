package go.examate.hyp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.InputBg
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 局域网聊天消息
 * @param text 消息内容
 * @param isMine 是否为自己发出
 * @param senderName 发送方名称
 * @param time 时间戳
 */
data class LanChatMessage(
    val text: String,
    val isMine: Boolean,
    val senderName: String,
    val time: Long
)

/**
 * 聊天悬浮窗(Compose 液态玻璃风格)
 * @param title 标题栏文本(局域网聊天 / 房间聊天 #xxxxxx)
 */
@Composable
fun LanChatWindow(
    title: String,
    messages: List<LanChatMessage>,
    onlineCount: Int,
    inputText: String,
    myName: String,
    timeFmt: SimpleDateFormat,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onInputFocusChange: (Boolean) -> Unit = {}
) {
    val listState = rememberLazyListState()

    // 新消息到达时滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        Modifier
            .width(300.dp)
            .height(440.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.92f))
    ) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 标题栏(可拖动) ═══
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(InputBg)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onDrag(drag.x, drag.y)
                        }
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$onlineCount 人在线",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(24.dp).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ═══ 消息列表 ═══
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages.size) { i ->
                    val msg = messages[i]
                    MessageBubble(msg, msg.isMine, timeFmt)
                }
            }

            // ═══ 输入栏 ═══
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onInputFocusChange(it.isFocused) },
                    placeholder = { Text("输入消息", fontSize = 13.sp, color = TextSecondary) },
                    shape = RoundedCornerShape(22.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(0.25f),
                        unfocusedContainerColor = Color.White.copy(0.15f),
                        focusedBorderColor = AccentBlue.copy(0.45f),
                        unfocusedBorderColor = Color.White.copy(0.35f)
                    )
                )
                Spacer(Modifier.width(8.dp))
                val canSend = inputText.trim().isNotEmpty()
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(if (canSend) AccentBlue else AccentBlue.copy(0.3f))
                        .clickable { if (canSend) onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: LanChatMessage, isMine: Boolean, timeFmt: SimpleDateFormat) {
    val alignment = if (isMine) Alignment.End else Alignment.Start
    val bgColor = if (isMine) AccentBlue else ScheduleCellBg
    val textColor = if (isMine) Color.White else TextPrimary

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isMine) {
            Text(
                text = msg.senderName,
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        Box(
            Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp, topEnd = 14.dp,
                        bottomStart = if (isMine) 14.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 14.dp
                    )
                )
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = msg.text, color = textColor, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Text(
            text = timeFmt.format(Date(msg.time)),
            color = TextSecondary.copy(alpha = 0.6f),
            fontSize = 9.sp,
            modifier = Modifier.padding(
                start = if (isMine) 0.dp else 4.dp,
                end = if (isMine) 4.dp else 0.dp,
                top = 2.dp
            )
        )
    }
}
