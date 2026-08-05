package go.examate.hyp.meschat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentMint
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
 * Mesh 聊天会话列表页 — 频道 + 私聊,液态玻璃设计。
 *
 * 替代旧版单一天聊天室,支持:
 * - 频道(群聊):#xxx 标签,可选密码保护
 * - 私聊:点对点加密
 * - 传输状态指示:BLE / Wi-Fi Aware / Nostr
 * - Mesh 节点列表:查看已连接的对端设备
 *
 * @param conversations 会话列表(频道+私聊)
 * @param transportStatus 传输通道状态
 * @param peers 已连接的 Mesh 节点
 * @param myName 我的昵称
 * @param onBack 返回
 * @param onOpenConversation 打开会话(频道或私聊)
 * @param onJoinChannel 加入/创建频道
 * @param onStartPrivateChat 与某节点发起私聊
 * @param onFloatingMode 切换悬浮窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MesChatListScreen(
    conversations: List<ChatConversation>,
    transportStatus: TransportStatus,
    peers: List<MeshPeer>,
    myName: String,
    onBack: () -> Unit,
    onOpenConversation: (ChatConversation) -> Unit,
    onJoinChannel: () -> Unit,
    onStartPrivateChat: (MeshPeer) -> Unit,
    onFloatingMode: () -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    var showPeerSheet by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(Modifier.fillMaxSize().background(BgPrimary).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 顶栏 ═══
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
                    "Mesh 聊天",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                // 传输状态胶囊
                TransportStatusChip(transportStatus, onClick = { showPeerSheet = true })
                Spacer(Modifier.width(6.dp))
                // 悬浮模式
                FrostedGlassCard(modifier = Modifier.padding(0.dp)) {
                    Text(
                        "悬浮",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onFloatingMode() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // ═══ 会话列表 ═══
            if (conversations.isEmpty()) {
                EmptyMeshState(transportStatus)
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ── 频道区 ──
                    val channels = conversations.filter { it.isChannel }
                    if (channels.isNotEmpty()) {
                        item(key = "header_channel") {
                            SectionHeader("频道", channels.size, onJoinChannel)
                        }
                        items(channels, key = { "ch_${it.id}" }) { conv ->
                            ConversationRow(conv, timeFmt, onClick = { onOpenConversation(conv) })
                        }
                    }

                    // ── 私聊区 ──
                    val privates = conversations.filter { !it.isChannel }
                    if (privates.isNotEmpty()) {
                        item(key = "header_private") {
                            SectionHeader("私聊", privates.size, null)
                        }
                        items(privates, key = { "pm_${it.id}" }) { conv ->
                            ConversationRow(conv, timeFmt, onClick = { onOpenConversation(conv) })
                        }
                    }

                    // ── 底部加入频道入口 ──
                    item(key = "join_channel") {
                        Spacer(Modifier.height(4.dp))
                        JoinChannelCard(onClick = onJoinChannel)
                    }
                }
            }
        }
    }

    // ═══ Mesh 节点列表面板 ═══
    if (showPeerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPeerSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.White
        ) {
            MeshPeerPanelContent(
                peers = peers,
                myName = myName,
                transportStatus = transportStatus,
                onChatWith = { peer ->
                    showPeerSheet = false
                    onStartPrivateChat(peer)
                }
            )
        }
    }
}

// ════════════════════════════════════════
//  传输状态胶囊
// ════════════════════════════════════════

@Composable
private fun TransportStatusChip(status: TransportStatus, onClick: () -> Unit) {
    // 任务B.7:准确反映实际启动的传输通道(而非是否有 peer 在线)
    val color = when {
        status.ble && status.wifiAware -> Success   // 双通道:绿
        status.ble || status.wifiAware -> AccentMint // 单通道:薄荷
        status.nostr -> AccentBlue                   // 仅 Nostr:蓝
        else -> TextHint                              // 不可用:灰
    }
    val label = when {
        status.ble && status.wifiAware -> "双通道"
        status.ble -> "蓝牙"
        status.wifiAware -> "Wi-Fi"
        status.nostr -> "Nostr"
        else -> "离线"
    }
    FrostedGlassCard(modifier = Modifier.padding(0.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(
                "$label · ${status.peerCount} 人",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ════════════════════════════════════════
//  分区标题
// ════════════════════════════════════════

@Composable
private fun SectionHeader(title: String, count: Int, onAdd: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text("($count)", color = TextHint, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        if (onAdd != null) {
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .background(Primary.copy(0.08f)).clickable { onAdd() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = "加入频道", tint = Primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ════════════════════════════════════════
//  会话行
// ════════════════════════════════════════

@Composable
private fun ConversationRow(
    conv: ChatConversation,
    timeFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    FrostedGlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧图标:频道=Group,私聊=Chat + 传输色点
            ConversationAvatar(conv)
            Spacer(Modifier.width(12.dp))
            // 中间:标题 + 最后消息
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conv.title,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (conv.isPasswordProtected) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = TextHint, modifier = Modifier.size(13.dp))
                    }
                    if (conv.isChannel && conv.peerCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("${conv.peerCount}", color = TextHint, fontSize = 11.sp)
                    }
                }
                if (conv.lastMessage.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        conv.lastMessage,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 右侧:时间 + 未读
            Column(horizontalAlignment = Alignment.End) {
                if (conv.lastMessageTime > 0) {
                    Text(timeFmt.format(Date(conv.lastMessageTime)), color = TextHint, fontSize = 10.sp)
                }
                if (conv.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(Danger),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString(),
                            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationAvatar(conv: ChatConversation) {
    val bgColor = if (conv.isChannel) AccentPurple.copy(0.15f) else AccentBlue.copy(0.15f)
    val iconTint = if (conv.isChannel) AccentPurple else AccentBlue
    val transportColor = when (conv.transport) {
        TransportType.BLE -> Success
        TransportType.WIFI_AWARE -> AccentMint
        TransportType.NOSTR -> AccentBlue
        else -> TextHint
    }
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (conv.isChannel) Icons.Filled.Group else Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        // 传输通道色点(仅私聊)
        if (!conv.isChannel && conv.transport != TransportType.NONE) {
            Box(
                Modifier.size(12.dp).clip(CircleShape)
                    .background(Color.White).padding(2.dp)
            ) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(transportColor))
            }
        }
    }
}

// ════════════════════════════════════════
//  加入频道卡片
// ════════════════════════════════════════

@Composable
private fun JoinChannelCard(onClick: () -> Unit) {
    val backdrop = LocalGlassBackdrop.current
    Box(
        Modifier.fillMaxWidth().then(
            if (backdrop != null) Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = { vibrancy(); blur(14f) },
                onDrawSurface = { drawRect(Color.White.copy(0.4f)) }
            ) else Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(0.88f))
        ).clickable { onClick() }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("加入或创建频道", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ════════════════════════════════════════
//  空状态
// ════════════════════════════════════════

@Composable
private fun EmptyMeshState(status: TransportStatus) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (status.isAnyActive) Icons.Filled.Bluetooth else Icons.Filled.CloudOff,
                contentDescription = null,
                tint = if (status.isAnyActive) AccentBlue else TextHint,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                if (status.isAnyActive) "正在搜索附近设备…" else "未连接任何传输通道",
                color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (status.isAnyActive)
                    "开启蓝牙/Wi-Fi 的同学会自动出现在节点列表"
                else "请开启蓝牙或 Wi-Fi 以发现附近设备",
                color = TextHint, fontSize = 12.sp
            )
        }
    }
}

// ════════════════════════════════════════
//  Mesh 节点面板内容
// ════════════════════════════════════════

@Composable
private fun MeshPeerPanelContent(
    peers: List<MeshPeer>,
    myName: String,
    transportStatus: TransportStatus,
    onChatWith: (MeshPeer) -> Unit
) {
    // 任务B.7:在节点面板副标题展示当前传输通道运行状态
    val channelStatusText = when {
        transportStatus.ble && transportStatus.wifiAware -> "双通道运行中"
        transportStatus.ble -> "仅蓝牙通道"
        transportStatus.wifiAware -> "仅 Wi-Fi Aware 通道"
        transportStatus.nostr -> "仅 Nostr 通道"
        else -> "无可用通道"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mesh 节点", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            // 传输状态图标
            TransportIcons(transportStatus)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "$channelStatusText · ${transportStatus.peerCount} 个节点在线 · $myName",
            color = TextHint, fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))

        if (peers.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Text("暂无已连接的节点", color = TextHint, fontSize = 13.sp)
            }
        } else {
            peers.forEach { peer ->
                PeerRow(peer, onClick = { onChatWith(peer) })
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun PeerRow(peer: MeshPeer, onClick: () -> Unit) {
    val transportColor = when (peer.transport) {
        TransportType.BLE -> Success
        TransportType.WIFI_AWARE -> AccentMint
        TransportType.NOSTR -> AccentBlue
        else -> TextHint
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(0.6f)).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像 + 传输色点
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(AccentBlue.copy(0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    peer.nickname.take(1).ifBlank { "?" },
                    color = AccentBlue, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
            }
            Box(Modifier.size(11.dp).clip(CircleShape).background(Color.White).padding(2.dp)) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(transportColor))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.nickname.ifBlank { "未知节点" }, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(peer.transport.label, color = TextHint, fontSize = 11.sp)
                if (peer.hopCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("${peer.hopCount} 跳", color = TextHint, fontSize = 11.sp)
                }
            }
        }
        // 发起私聊按钮
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Primary.copy(0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "私聊", tint = Primary, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun TransportIcons(status: TransportStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TransportIcon(Icons.Filled.Bluetooth, active = status.ble)
        TransportIcon(Icons.Filled.Wifi, active = status.wifiAware)
        TransportIcon(Icons.Filled.CloudOff, active = status.nostr)
    }
}

@Composable
private fun TransportIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean) {
    val tint by animateColorAsState(
        targetValue = if (active) Success else TextHint.copy(0.3f),
        animationSpec = tween(300), label = "transport_icon"
    )
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(if (active) Success.copy(0.1f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}
