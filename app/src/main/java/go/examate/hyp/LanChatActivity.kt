package go.examate.hyp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import go.examate.hyp.util.ToastUtil
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import go.examate.hyp.service.LanChatService
import go.examate.hyp.service.LanChatTransport
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.screens.LanChatMessage
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Success
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
            // 仅保留其他设备(自己的昵称在弹窗中以"我"单独展示,避免重复)
            onlineNames.clear()
            onlineNames.addAll(transport?.getOnlineDeviceNames()?.drop(1) ?: emptyList())
        }
    }

    override fun onError(message: String) {
        ToastUtil.show(this, message)
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
            ToastUtil.show(this, "请先授予悬浮窗权限")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                ToastUtil.show(this, "无法跳转悬浮窗设置")
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

        // ═══ 输入栏:液态玻璃(同步 AI 聊天样式) ═══
        val barBackdrop = LocalGlassBackdrop.current
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .then(
                    if (barBackdrop != null) Modifier.drawBackdrop(
                        backdrop = barBackdrop,
                        shape = { RoundedRectangle(28.dp) },
                        effects = { vibrancy(); blur(20.dp.toPx()) },
                        highlight = { Highlight.Ambient },
                        onDrawSurface = { drawRect(Color.White.copy(0.38f)) }
                    ) else Modifier.background(Color.White.copy(0.88f))
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).then(
                    if (barBackdrop != null) Modifier.drawBackdrop(
                        backdrop = barBackdrop,
                        shape = { RoundedRectangle(22.dp) },
                        effects = { vibrancy(); blur(16.dp.toPx()) },
                        highlight = { Highlight.Ambient },
                        onDrawSurface = { drawRect(Color.White.copy(0.2f)) }
                    ) else Modifier
                ),
                placeholder = { Text("说点什么...", color = TextHint, fontSize = 14.sp) },
                shape = RoundedCornerShape(22.dp),
                singleLine = false,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = AccentBlue.copy(0.45f),
                    unfocusedBorderColor = Color.White.copy(0.35f)
                )
            )
            Spacer(Modifier.width(8.dp))
            val canSend = inputText.isNotBlank()
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (canSend) AccentBlue else AccentBlue.copy(0.35f))
                    .clickable(enabled = canSend) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Send,
                    contentDescription = "发送",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // 在线人员弹窗(用 PlainDialog 与主界面统一)
    if (showOnlineDialog) {
        go.examate.hyp.ui.components.PlainDialog(
            onDismissRequest = { showOnlineDialog = false },
            modifier = Modifier.width(300.dp)
        ) {
            go.examate.hyp.ui.components.PlainDialogTitle("在线人员 ($onlineCount)")
            // 列表区(限高 + 可滚动,避免人太多溢出)
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 我自己(置顶,绿色圆点)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).background(Success, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("我", color = TextHint, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        myName.ifBlank { "未设置昵称" },
                        color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                // 其他在线设备
                if (onlineNames.isEmpty()) {
                    Text(
                        "暂无其他在线设备",
                        color = TextHint, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    onlineNames.forEach { name ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).background(Primary, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            go.examate.hyp.ui.components.PlainDialogButtons(
                buttons = listOf(Triple("关闭", true) { showOnlineDialog = false })
            )
        }
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
