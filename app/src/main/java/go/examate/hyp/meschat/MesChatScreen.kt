package go.examate.hyp.meschat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentMint
import go.examate.hyp.ui.theme.AccentPeach
import go.examate.hyp.ui.theme.AccentPurple
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mesh 聊天会话页 — 频道或私聊上下文 + 多消息类型气泡。
 *
 * 支持的消息类型:[MesMessageType.TEXT] / [MesMessageType.IMAGE] /
 * [MesMessageType.VOICE] / [MesMessageType.FILE]。
 *
 * @param conversation 当前会话(频道或私聊)
 * @param messages 当前会话的消息列表
 * @param transportStatus 传输通道状态(用于顶栏指示)
 * @param inputText 输入框文本
 * @param onBack 返回会话列表
 * @param onInputChange 输入框文本变更
 * @param onSendText 发送文本
 * @param onPickImage 选择图片
 * @param onRecordVoice 录制语音
 * @param onPickFile 选择文件
 * @param onPlayVoice 播放语音
 * @param onOpenImage 查看大图
 * @param onOpenFile 打开文件
 */
@Composable
fun MesChatScreen(
    conversation: ChatConversation,
    messages: List<MesMessage>,
    transportStatus: TransportStatus,
    inputText: String,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSendText: () -> Unit,
    onPickImage: () -> Unit,
    onRecordVoice: () -> Unit,
    onPickFile: () -> Unit,
    onPlayVoice: (MesMessage) -> Unit,
    onOpenImage: (MesMessage) -> Unit,
    onOpenFile: (MesMessage) -> Unit
) {
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var showAttachPanel by remember { mutableStateOf(false) }

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
        // ═══ 顶栏 ═══
        ChatTopBar(
            conversation = conversation,
            transportStatus = transportStatus,
            onBack = onBack
        )

        // ═══ 消息列表 ═══
        if (messages.isEmpty()) {
            EmptyConversationState(conversation)
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        msg = msg,
                        timeFmt = timeFmt,
                        isChannel = conversation.isChannel,
                        onPlayVoice = onPlayVoice,
                        onOpenImage = onOpenImage,
                        onOpenFile = onOpenFile
                    )
                }
            }
        }

        // ═══ 附件面板(展开式) ═══
        AnimatedVisibility(
            visible = showAttachPanel,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AttachPanel(
                onPickImage = {
                    showAttachPanel = false
                    onPickImage()
                },
                onRecordVoice = {
                    showAttachPanel = false
                    onRecordVoice()
                },
                onPickFile = {
                    showAttachPanel = false
                    onPickFile()
                }
            )
        }

        // ═══ 输入栏 ═══
        ChatInputBar(
            inputText = inputText,
            onInputChange = onInputChange,
            onSend = onSendText,
            onToggleAttach = { showAttachPanel = !showAttachPanel },
            attachExpanded = showAttachPanel
        )
    }
}

// ════════════════════════════════════════
//  顶栏
// ════════════════════════════════════════

@Composable
private fun ChatTopBar(
    conversation: ChatConversation,
    transportStatus: TransportStatus,
    onBack: () -> Unit
) {
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
        Spacer(Modifier.width(8.dp))
        // 标题 + 副标题
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (conversation.isPasswordProtected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = TextHint, modifier = Modifier.size(13.dp))
                }
            }
            // 副标题:频道显示成员数,私聊显示传输通道
            val subtitle = if (conversation.isChannel) {
                if (conversation.peerCount > 0) "${conversation.peerCount} 位成员" else "频道"
            } else {
                conversation.transport.label
            }
            Text(subtitle, color = TextHint, fontSize = 11.sp, maxLines = 1)
        }
        // 传输状态指示(私聊显示对端通道,频道显示聚合状态)
        if (conversation.isChannel) {
            TransportMiniIndicator(transportStatus)
        } else {
            TransportDot(conversation.transport)
        }
    }
}

@Composable
private fun TransportMiniIndicator(status: TransportStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TransportDotIcon(Icons.Filled.Bluetooth, active = status.ble)
        TransportDotIcon(Icons.Filled.Wifi, active = status.wifiAware)
        TransportDotIcon(Icons.Filled.CloudOff, active = status.nostr)
    }
}

@Composable
private fun TransportDotIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean) {
    Box(
        Modifier.size(26.dp).clip(CircleShape)
            .background(if (active) Success.copy(0.12f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (active) Success else TextHint.copy(0.35f),
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun TransportDot(transport: TransportType) {
    val color = when (transport) {
        TransportType.BLE -> Success
        TransportType.WIFI_AWARE -> AccentMint
        TransportType.NOSTR -> AccentBlue
        else -> TextHint
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

// ════════════════════════════════════════
//  消息气泡
// ════════════════════════════════════════

@Composable
private fun MessageBubble(
    msg: MesMessage,
    timeFmt: SimpleDateFormat,
    isChannel: Boolean,
    onPlayVoice: (MesMessage) -> Unit,
    onOpenImage: (MesMessage) -> Unit,
    onOpenFile: (MesMessage) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // 左侧头像(非自己且频道模式)
        if (!msg.isMine) {
            if (isChannel) {
                MessageAvatar(msg.sender)
                Spacer(Modifier.width(8.dp))
            }
        }
        Column(
            horizontalAlignment = if (msg.isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            // 发送者名(频道模式 + 非自己)
            if (isChannel && !msg.isMine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        msg.sender.ifBlank { "匿名" },
                        color = senderColor(msg.sender),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (msg.isRelay && !msg.originalSender.isNullOrEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "中继自 ${msg.originalSender}",
                            color = TextHint,
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            // 气泡内容
            when (msg.type) {
                MesMessageType.TEXT -> TextBubble(msg)
                MesMessageType.IMAGE -> ImageBubble(msg, onOpenImage)
                MesMessageType.VOICE -> VoiceBubble(msg, onPlayVoice)
                MesMessageType.FILE -> FileBubble(msg, onOpenFile)
            }
            // 时间 + 送达状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
            ) {
                Text(
                    timeFmt.format(Date(msg.timestamp)),
                    color = TextHint,
                    fontSize = 10.sp
                )
                if (msg.isMine && msg.deliveryState != null) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryStateIcon(msg.deliveryState)
                }
            }
        }
        if (msg.isMine) {
            Spacer(Modifier.width(8.dp))
            MessageAvatar(msg.sender, isMine = true)
        }
    }
}

@Composable
private fun TextBubble(msg: MesMessage) {
    val bubbleColor = if (msg.isMine) AccentBlue.copy(0.85f) else Color.White.copy(0.75f)
    val textColor = if (msg.isMine) Color.White else TextPrimary
    val shape = if (msg.isMine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    Box(
        Modifier
            .clip(shape)
            .background(bubbleColor)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(msg.content, color = textColor, fontSize = 14.sp)
    }
}

@Composable
private fun ImageBubble(msg: MesMessage, onOpenImage: (MesMessage) -> Unit) {
    val shape = if (msg.isMine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    Box(
        Modifier
            .size(width = 180.dp, height = 180.dp)
            .clip(shape)
            .background(Color.White.copy(0.6f))
            .clickable { onOpenImage(msg) },
        contentAlignment = Alignment.Center
    ) {
        // 占位图:实际接入时用 AsyncImage 加载 imagePath
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = TextHint, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(4.dp))
            Text("图片", color = TextHint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun VoiceBubble(msg: MesMessage, onPlayVoice: (MesMessage) -> Unit) {
    val bubbleColor = if (msg.isMine) AccentBlue.copy(0.85f) else Color.White.copy(0.75f)
    val textColor = if (msg.isMine) Color.White else TextPrimary
    val shape = if (msg.isMine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    Row(
        Modifier
            .clip(shape)
            .background(bubbleColor)
            .clickable { onPlayVoice(msg) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = textColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        // 简易波形条
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(5) { i ->
                Box(
                    Modifier
                        .size(width = 2.dp, height = (8 + (i % 3) * 4).dp)
                        .background(textColor.copy(0.6f))
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("${msg.voiceDuration}\"", color = textColor, fontSize = 12.sp)
    }
}

@Composable
private fun FileBubble(msg: MesMessage, onOpenFile: (MesMessage) -> Unit) {
    val bubbleColor = if (msg.isMine) AccentBlue.copy(0.85f) else Color.White.copy(0.75f)
    val textColor = if (msg.isMine) Color.White else TextPrimary
    val subColor = if (msg.isMine) Color.White.copy(0.75f) else TextHint
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .width(220.dp)
            .clip(shape)
            .background(bubbleColor)
            .clickable { onOpenFile(msg) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(textColor.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                msg.fileName ?: "未命名文件",
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(formatFileSize(msg.fileSize), color = subColor, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MessageAvatar(sender: String, isMine: Boolean = false) {
    val color = if (isMine) AccentBlue else senderColor(sender)
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(color.copy(0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (isMine) "我" else sender.take(1).ifBlank { "?" },
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeliveryStateIcon(state: DeliveryState) {
    when (state) {
        DeliveryState.SENDING -> Icon(Icons.Filled.Schedule, contentDescription = null, tint = TextHint, modifier = Modifier.size(12.dp))
        DeliveryState.SENT -> Icon(Icons.Filled.Check, contentDescription = null, tint = TextHint, modifier = Modifier.size(12.dp))
        DeliveryState.DELIVERED -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(12.dp))
        DeliveryState.READ -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(12.dp))
        DeliveryState.FAILED -> Icon(Icons.Filled.Close, contentDescription = null, tint = Danger, modifier = Modifier.size(12.dp))
    }
}

// ════════════════════════════════════════
//  附件面板
// ════════════════════════════════════════

@Composable
private fun AttachPanel(
    onPickImage: () -> Unit,
    onRecordVoice: () -> Unit,
    onPickFile: () -> Unit
) {
    FrostedGlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        cornerRadius = 20.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AttachItem(Icons.Filled.Image, "图片", AccentPurple, onPickImage)
            AttachItem(Icons.Filled.Mic, "语音", AccentPeach, onRecordVoice)
            AttachItem(Icons.AutoMirrored.Outlined.Article, "文件", AccentMint, onPickFile)
        }
    }
}

@Composable
private fun AttachItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 8.dp)
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(tint.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

// ════════════════════════════════════════
//  输入栏
// ════════════════════════════════════════

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleAttach: () -> Unit,
    attachExpanded: Boolean
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 附件按钮
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Primary.copy(0.06f))
                .clickable { onToggleAttach() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (attachExpanded) Icons.Filled.Close else Icons.Filled.Add,
                contentDescription = "附件",
                tint = Primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        // 输入框
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("说点什么…", color = TextHint, fontSize = 14.sp) },
            shape = RoundedCornerShape(22.dp),
            singleLine = false,
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(0.7f),
                unfocusedContainerColor = Color.White.copy(0.55f),
                focusedBorderColor = AccentBlue.copy(0.45f),
                unfocusedBorderColor = Color.White.copy(0.5f)
            )
        )
        Spacer(Modifier.width(8.dp))
        // 发送按钮
        val canSend = inputText.isNotBlank()
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (canSend) AccentBlue else AccentBlue.copy(0.35f))
                .clickable(enabled = canSend) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ════════════════════════════════════════
//  空状态
// ════════════════════════════════════════

@Composable
private fun EmptyConversationState(conv: ChatConversation) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (conv.isChannel) "#" else "✉",
                color = TextHint.copy(0.4f),
                fontSize = 40.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (conv.isChannel) "频道 ${conv.title}" else "与 ${conv.title} 的私聊",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (conv.isChannel) "在频道中发送消息即可让所有成员看到" else "消息将通过 Mesh 网络加密传输",
                color = TextHint,
                fontSize = 11.sp
            )
        }
    }
}

// ════════════════════════════════════════
//  工具
// ════════════════════════════════════════

/** 根据发送者昵称生成稳定的颜色,便于频道中区分不同人 */
private fun senderColor(name: String): Color {
    val colors = listOf(AccentBlue, AccentPurple, AccentMint, AccentPeach, Success)
    val hash = if (name.isEmpty()) 0 else name.hashCode()
    return colors[((hash % colors.size) + colors.size) % colors.size]
}

/** 格式化文件大小 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.size - 1) {
        size /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", size, units[unit])
}
