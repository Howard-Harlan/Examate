package go.examate.hyp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.service.LanChatService
import go.examate.hyp.service.LanChatTransport
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.screens.LanChatMessage
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 局域网聊天全屏页面(参考 [ChatActivity] 的结构)。
 *
 * 与悬浮窗 [LanChatService] 共用 [LanChatTransport],但输入框在普通 Activity 中
 * 默认可获取焦点,无需任何 flags 切换。
 */
class LanChatActivity : ComponentActivity(), LanChatTransport.Callback {

    private var transport: LanChatTransport? = null

    // Compose 状态(主线程访问)
    private val messages = mutableStateListOf<LanChatMessage>()
    private val onlineCount = mutableStateOf(1)
    private val inputText = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    LanChatScreen(
                        messages = messages,
                        onlineCount = onlineCount.value,
                        inputText = inputText.value,
                        myName = transport?.myName ?: "",
                        onBack = { finish() },
                        onInputChange = { inputText.value = it },
                        onSend = {
                            val text = inputText.value.trim()
                            if (text.isNotEmpty()) {
                                sendText(text)
                                inputText.value = ""
                            }
                        },
                        onFloatingMode = { startFloatingMode() }
                    )
                }
            }
        }
        transport = LanChatTransport(this, this).also { it.start() }
    }

    override fun onDestroy() {
        transport?.stop()
        transport = null
        super.onDestroy()
    }

    // ═══ LanChatTransport.Callback(子线程触发,切主线程) ═══

    override fun onMessageReceived(msg: LanChatMessage) {
        runOnUiThread { messages.add(msg) }
    }

    override fun onOnlineCountChanged(count: Int) {
        runOnUiThread { onlineCount.value = count }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun sendText(text: String) {
        transport?.sendMessage(text)
        messages.add(LanChatMessage(text, true, transport?.myName ?: "", System.currentTimeMillis()))
    }

    /** 切换到悬浮窗模式:需悬浮窗权限 */
    private fun startFloatingMode() {
        if (Settings.canDrawOverlays(this)) {
            // 先释放 UDP 端口,避免 Service 启动时绑定失败
            transport?.stop()
            transport = null
            startService(Intent(this, LanChatService::class.java))
            finish()
        } else {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法跳转悬浮窗设置", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun LanChatScreen(
    messages: List<LanChatMessage>,
    onlineCount: Int,
    inputText: String,
    myName: String,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onFloatingMode: () -> Unit
) {
    val listState = rememberLazyListState()
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 新消息到达时滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .imePadding()
            .statusBarsPadding()
    ) {
        // ═══ 顶栏:返回 + 标题 + 在线人数 + 悬浮窗模式 ═══
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = "局域网聊天",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            Text(
                text = "$onlineCount 人在线",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(40.dp).clickable { onFloatingMode() },
                contentAlignment = Alignment.Center
            ) {
                Text("悬浮", color = TextPrimary, fontSize = 12.sp)
            }
        }

        // ═══ 消息列表 ═══
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            if (messages.isEmpty()) {
                item(key = "empty") { EmptyHint() }
            }
            items(messages.size, key = { i -> "${messages[i].time}_$i" }) { i ->
                LanChatBubble(messages[i], timeFmt)
            }
        }

        // ═══ 输入栏 ═══
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(0.88f))
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息", color = TextHint, fontSize = 14.sp) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            // 有内容时显示发送按钮
            AnimatedVisibility(
                visible = inputText.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF5B8DEF))
                            .clickable { onSend() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
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
}

@Composable
private fun LanChatBubble(msg: LanChatMessage, timeFmt: SimpleDateFormat) {
    val isMine = msg.isMine
    val alignment = if (isMine) Alignment.End else Alignment.Start
    val bgColor = if (isMine) Color(0xFF5B8DEF) else Color.White
    val textColor = if (isMine) Color.White else TextPrimary

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isMine) {
            Text(
                text = msg.senderName,
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isMine) 16.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 16.dp
                    )
                )
                .background(bgColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text = msg.text, color = textColor, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Text(
            text = timeFmt.format(Date(msg.time)),
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.padding(
                start = if (isMine) 0.dp else 4.dp,
                end = if (isMine) 4.dp else 0.dp,
                top = 2.dp
            )
        )
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✦", color = TextHint.copy(alpha = 0.5f), fontSize = 36.sp)
            Spacer(Modifier.height(10.dp))
            Text("暂无消息", color = TextHint, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("同一局域网内的设备会自动发现彼此", color = TextHint.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}
