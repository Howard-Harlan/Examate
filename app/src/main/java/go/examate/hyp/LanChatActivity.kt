package go.examate.hyp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.service.LanChatService
import go.examate.hyp.service.LanChatTransport
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.screens.LanChatMessage
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.AvatarHelper
import go.examate.hyp.util.LanChatCache
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 局域网聊天全屏页面(UDP 广播,同 WiFi 自动发现)。
 *
 * 与悬浮窗 [LanChatService] 共用 [LanChatTransport]。
 * 消息缓存 5 分钟(与悬浮窗共用 [LanChatCache])。
 */
class LanChatActivity : ComponentActivity(), LanChatTransport.Callback {

    private var transport: LanChatTransport? = null

    // Compose 状态(主线程访问)
    private val messages = mutableStateListOf<LanChatMessage>()
    private val onlineCount = mutableStateOf(1)
    private val onlineNames = mutableStateListOf<String>()
    private val inputText = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 加载缓存消息(5分钟内)
        messages.addAll(LanChatCache.load(this))
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    LanChatScreen(
                        messages = messages,
                        onlineCount = onlineCount.value,
                        onlineNames = onlineNames,
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
        runOnUiThread {
            messages.add(msg)
            LanChatCache.append(this, msg)
        }
    }

    override fun onOnlineCountChanged(count: Int) {
        runOnUiThread {
            onlineCount.value = count
            onlineNames.clear()
            onlineNames.addAll(transport?.getOnlineDeviceNames() ?: emptyList())
        }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun sendText(text: String) {
        transport?.sendMessage(text)
        val msg = LanChatMessage(text, true, transport?.myName ?: "", System.currentTimeMillis())
        messages.add(msg)
        LanChatCache.append(this, msg)
    }

    /** 切换到悬浮窗模式:需悬浮窗权限 */
    private fun startFloatingMode() {
        if (Settings.canDrawOverlays(this)) {
            // 先释放 UDP 端口,避免 Service 启动时绑定失败
            transport?.stop()
            transport = null
            startService(android.content.Intent(this, LanChatService::class.java))
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
    onlineNames: List<String>,
    inputText: String,
    myName: String,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onFloatingMode: () -> Unit
) {
    val listState = rememberLazyListState()
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val context = LocalContext.current
    var showOnlineDialog by remember { mutableStateOf(false) }
    val avatarBitmap by remember { mutableStateOf<Bitmap?>(AvatarHelper.loadAvatar(context)) }

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
        // ═══ 顶栏:液态玻璃悬浮组件 ═══
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "局域网聊天",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            LiquidGlassLabel("$onlineCount 人在线", onClick = { showOnlineDialog = true })
            Spacer(Modifier.width(6.dp))
            LiquidGlassLabel("悬浮", onClick = onFloatingMode)
        }

        // ═══ 消息列表 ═══
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (messages.isEmpty()) {
                item(key = "empty") { EmptyHint() }
            }
            items(messages.size, key = { i -> "${messages[i].time}_$i" }) { i ->
                LanChatBubble(messages[i], timeFmt, avatarBitmap)
            }
        }

        // ═══ 输入栏:液态玻璃 ═══
        FrostedGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么...", color = TextHint, fontSize = 14.sp) },
                    shape = RoundedCornerShape(20.dp),
                    singleLine = false,
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.IconButton(onClick = onSend) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Send,
                            contentDescription = "发送",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // 在线人员弹窗
    if (showOnlineDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showOnlineDialog = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showOnlineDialog = false }) {
                    Text("关闭")
                }
            },
            title = { Text("在线人员 ($onlineCount)") },
            text = {
                Column {
                    Text("我: $myName", color = TextPrimary, fontSize = 14.sp)
                    onlineNames.forEach { name ->
                        Text(name, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        )
    }
}

@Composable
private fun LiquidGlassLabel(text: String, onClick: () -> Unit) {
    FrostedGlassCard(modifier = Modifier.padding(0.dp)) {
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun LanChatBubble(
    msg: LanChatMessage,
    timeFmt: SimpleDateFormat,
    avatarBitmap: Bitmap?
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!msg.isMine) {
            AvatarIcon(avatarBitmap)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (msg.isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = msg.senderName,
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            FrostedGlassCard(modifier = Modifier.padding(0.dp)) {
                Text(
                    text = msg.text,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Text(
                text = timeFmt.format(java.util.Date(msg.time)),
                color = TextHint,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (msg.isMine) {
            Spacer(Modifier.width(8.dp))
            AvatarIcon(avatarBitmap)
        }
    }
}

@Composable
private fun AvatarIcon(bitmap: Bitmap?) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(AccentBlue.copy(0.15f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "头像",
                modifier = Modifier.size(32.dp).clip(CircleShape)
            )
        } else {
            Text("我", color = AccentBlue, fontSize = 12.sp)
        }
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
