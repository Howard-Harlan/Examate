package go.examate.hyp.meschat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.IsDark
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mesh 聊天主页 — 频道(群聊) + 用户(私聊)双 Tab,液态玻璃设计。
 *
 * 重构后:
 * - 频道 Tab:已加入频道(群聊)、加入/创建频道、Nostr 中继管理入口
 * - 用户 Tab:在线用户列表(局域网用户 + 云端用户),支持直接私聊与密钥匹对
 * - 网络面板:传输状态、聊天方式选择(自动/仅局域网/仅云端)
 * - 传输指示:局域网 UDP / Nostr 中继
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MesChatListScreen(
    conversations: List<ChatConversation>,
    transportStatus: TransportStatus,
    peers: List<MeshPeer>,
    myName: String,
    currentMode: ChatMode,
    relays: List<String>,
    builtinRelays: List<String>,
    awarenessGranted: Boolean,
    isBridging: Boolean,
    onSwitchMode: (ChatMode) -> Unit,
    onEnableLocalMesh: () -> Unit,
    onP2pDirect: () -> Unit = {},
    onBack: () -> Unit,
    onOpenConversation: (ChatConversation) -> Unit,
    onJoinChannel: () -> Unit,
    onStartPrivateChat: (MeshPeer) -> Unit,
    onStartKeyPairChat: () -> Unit,
    onSetRelays: (List<String>) -> Unit,
    onAddRelay: (String) -> Boolean,
    onRemoveRelay: (String) -> Unit,
    onResetRelays: () -> Unit,
    onFloatingMode: () -> Unit
) {
    var showNetworkPanel by remember { mutableStateOf(false) }
    var showRelayPanel by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
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
                TransportStatusChip(transportStatus, onClick = { showNetworkPanel = true })
                Spacer(Modifier.width(6.dp))
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

            // ═══ 频道 / 用户 Tab ═══
            HomeTabRow(
                channelCount = conversations.count { it.isChannel },
                userCount = peers.size,
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )

            // ═══ 内容区 ═══
            if (selectedTab == 0) {
                ChannelsTabContent(
                    conversations = conversations,
                    timeFmt = timeFmt,
                    transportStatus = transportStatus,
                    relays = relays,
                    onOpenConversation = onOpenConversation,
                    onJoinChannel = onJoinChannel,
                    onOpenRelayPanel = { showRelayPanel = true }
                )
            } else {
                UsersTabContent(
                    peers = peers,
                    transportStatus = transportStatus,
                    onStartPrivateChat = onStartPrivateChat,
                    onStartKeyPairChat = onStartKeyPairChat
                )
            }
        }
    }

    // ═══ 网络面板 ═══
    if (showNetworkPanel) {
        ModalBottomSheet(
            onDismissRequest = { showNetworkPanel = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DialogBg
        ) {
            NetworkPanelContent(
                transportStatus = transportStatus,
                peers = peers,
                myName = myName,
                currentMode = currentMode,
                awarenessGranted = awarenessGranted,
                isBridging = isBridging,
                onSwitchMode = onSwitchMode,
                onEnableLocalMesh = onEnableLocalMesh,
                onP2pDirect = onP2pDirect,
                onOpenRelayPanel = {
                    showNetworkPanel = false
                    showRelayPanel = true
                }
            )
        }
    }

    // ═══ 中继管理面板 ═══
    if (showRelayPanel) {
        ModalBottomSheet(
            onDismissRequest = { showRelayPanel = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DialogBg
        ) {
            RelayPanelContent(
                relays = relays,
                builtinRelays = builtinRelays,
                connectedCount = transportStatus.relayConnected,
                onSetRelays = onSetRelays,
                onAddRelay = onAddRelay,
                onRemoveRelay = onRemoveRelay,
                onResetRelays = onResetRelays
            )
        }
    }
}

// ════════════════════════════════════════
//  Tab 栏
// ════════════════════════════════════════

@Composable
private fun HomeTabRow(
    channelCount: Int,
    userCount: Int,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HomeTab(
            icon = Icons.Filled.Group,
            label = "频道",
            count = channelCount,
            selected = selected == 0,
            onClick = { onSelect(0) },
            modifier = Modifier.weight(1f)
        )
        HomeTab(
            icon = Icons.AutoMirrored.Filled.Chat,
            label = "用户",
            count = userCount,
            selected = selected == 1,
            onClick = { onSelect(1) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HomeTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(
        if (selected) Primary.copy(0.12f) else Color.Transparent,
        tween(200), label = "tabBg"
    )
    val fg by animateColorAsState(
        if (selected) Primary else TextSecondary,
        tween(200), label = "tabFg"
    )
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), color = TextHint, fontSize = 11.sp)
        }
    }
}

// ════════════════════════════════════════
//  频道 Tab
// ════════════════════════════════════════

@Composable
private fun ChannelsTabContent(
    conversations: List<ChatConversation>,
    timeFmt: SimpleDateFormat,
    transportStatus: TransportStatus,
    relays: List<String>,
    onOpenConversation: (ChatConversation) -> Unit,
    onJoinChannel: () -> Unit,
    onOpenRelayPanel: () -> Unit
) {
    val channels = conversations.filter { it.isChannel }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header_channel") {
            SectionHeader("群聊频道", channels.size, onJoinChannel)
        }
        if (channels.isEmpty()) {
            item(key = "empty_channel") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有频道,点击右上角 + 加入或创建", color = TextHint, fontSize = 12.sp)
                }
            }
        } else {
            items(channels, key = { "ch_" + it.id }) { conv ->
                ConversationRow(conv, timeFmt, onClick = { onOpenConversation(conv) })
            }
        }

        item(key = "relay_card") {
            RelayStatusCard(
                transportStatus = transportStatus,
                relays = relays,
                onClick = onOpenRelayPanel
            )
        }
        item(key = "join_channel") {
            JoinChannelCard(onClick = onJoinChannel)
        }
    }
}

@Composable
private fun RelayStatusCard(
    transportStatus: TransportStatus,
    relays: List<String>,
    onClick: () -> Unit
) {
    FrostedGlassCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        cornerRadius = 18.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(AccentBlue.copy(0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Nostr 中继", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (transportStatus.nostr) {
                        "已连接 " + transportStatus.relayConnected + "/" + transportStatus.relayTotal + " 个中继"
                    } else {
                        "云端通道未启用(" + relays.size + " 个中继待命)"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Text("管理 ›", color = TextHint, fontSize = 12.sp)
        }
    }
}

// ════════════════════════════════════════
//  用户 Tab
// ════════════════════════════════════════

@Composable
private fun UsersTabContent(
    peers: List<MeshPeer>,
    transportStatus: TransportStatus,
    onStartPrivateChat: (MeshPeer) -> Unit,
    onStartKeyPairChat: () -> Unit
) {
    // 局域网用户优先展示,云端用户排在后面
    val sortedPeers = peers.sortedWith(
        compareBy<MeshPeer> { it.transport != TransportType.LAN_UDP }
            .thenBy { it.hopCount }
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header_users") {
            SectionHeader("在线用户", peers.size, null)
        }
        item(key = "keypair_entry") {
            KeyPairEntryCard(onClick = onStartKeyPairChat)
        }
        if (sortedPeers.isEmpty()) {
            item(key = "empty_users") {
                EmptyUsersState(transportStatus)
            }
        } else {
            items(sortedPeers, key = { "peer_" + it.peerId }) { peer ->
                PeerRow(
                    peer = peer,
                    onChat = { onStartPrivateChat(peer) },
                    onKeyPair = onStartKeyPairChat
                )
            }
        }
    }
}

@Composable
private fun KeyPairEntryCard(onClick: () -> Unit) {
    FrostedGlassCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        cornerRadius = 18.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(AccentMint.copy(0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Key, contentDescription = null, tint = AccentMint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("密钥匹对私聊", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("与对方约定同一密钥,进入加密房间", color = TextSecondary, fontSize = 12.sp)
            }
            Icon(Icons.Filled.Lock, contentDescription = null, tint = TextHint, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun EmptyUsersState(status: TransportStatus) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (status.lanUdp) Icons.Filled.Lan else Icons.Filled.Public,
                contentDescription = null,
                tint = if (status.isAnyActive) AccentBlue else TextHint,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    status.lanUdp -> "正在搜索局域网设备…"
                    status.nostr -> "等待云端用户上线…"
                    else -> "未连接任何传输通道"
                },
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (status.lanUdp) "同一 Wi-Fi 下开启 Mesh 聊天的设备会自动出现" else "请检查网络或传输设置",
                color = TextHint,
                fontSize = 12.sp
            )
        }
    }
}

// ════════════════════════════════════════
//  传输状态胶囊
// ════════════════════════════════════════

@Composable
private fun TransportStatusChip(status: TransportStatus, onClick: () -> Unit) {
    val color = when {
        status.isDual -> Success
        status.isAnyActive -> AccentMint
        else -> TextHint
    }
    val label = when {
        status.isDual -> "本地+云端"
        status.lanUdp -> "局域网"
        status.nostr -> "云端中继"
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
                label + " · " + status.peerCount + " 人",
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
        Text("(" + count + ")", color = TextHint, fontSize = 12.sp)
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
//  会话行(频道)
// ════════════════════════════════════════

@Composable
private fun ConversationRow(
    conv: ChatConversation,
    timeFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "convPress"
    )
    FrostedGlassCard(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = pressScale; scaleY = pressScale }
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(interactionSource = interaction, indication = null) { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConversationAvatar(conv)
            Spacer(Modifier.width(12.dp))
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
                        Text(conv.peerCount.toString(), color = TextHint, fontSize = 11.sp)
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
    val bgColor = when {
        conv.isKeyPairChat -> AccentMint.copy(0.15f)
        conv.isChannel -> AccentPurple.copy(0.15f)
        else -> AccentBlue.copy(0.15f)
    }
    val iconTint = when {
        conv.isKeyPairChat -> AccentMint
        conv.isChannel -> AccentPurple
        else -> AccentBlue
    }
    val transportColor = when (conv.transport) {
        TransportType.LAN_UDP -> AccentMint
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
        if (!conv.isChannel && conv.transport != TransportType.NONE) {
            Box(
                Modifier.size(12.dp).clip(CircleShape)
                    .background(CardBg).padding(2.dp)
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
    val isDark = IsDark
    Box(
        Modifier.fillMaxWidth().then(
            if (backdrop != null) Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = { vibrancy(); blur(14f) },
                onDrawSurface = { drawRect(Color.White.copy(if (isDark) 0.12f else 0.4f)) }
            ) else Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(if (isDark) 0.18f else 0.88f))
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
//  用户行
// ════════════════════════════════════════

@Composable
private fun PeerRow(
    peer: MeshPeer,
    onChat: () -> Unit,
    onKeyPair: () -> Unit
) {
    val isLocal = peer.transport == TransportType.LAN_UDP
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (IsDark) CardBg else Color.White.copy(0.6f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            Box(Modifier.size(11.dp).clip(CircleShape).background(CardBg).padding(2.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(
                        if (isLocal) AccentMint else AccentBlue
                    )
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.nickname.ifBlank { "未知节点" }, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isLocal) "本地" else "云端", color = TextHint, fontSize = 11.sp)
                if (isLocal && peer.hopCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(peer.hopCount.toString() + " 跳", color = TextHint, fontSize = 11.sp)
                }
                if (peer.isBridge) {
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Hub, contentDescription = null, tint = AccentMint, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("桥接", color = AccentMint, fontSize = 10.sp)
                    }
                }
            }
        }
        // 密钥匹对入口
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(AccentMint.copy(0.1f))
                .clickable { onKeyPair() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Key, contentDescription = "密钥匹对", tint = AccentMint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        // 直接私聊
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Primary.copy(0.08f))
                .clickable { onChat() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "私聊", tint = Primary, modifier = Modifier.size(17.dp))
        }
    }
}

// ════════════════════════════════════════
//  网络面板(传输状态 + 模式选择)
// ════════════════════════════════════════

@Composable
private fun NetworkPanelContent(
    transportStatus: TransportStatus,
    peers: List<MeshPeer>,
    myName: String,
    currentMode: ChatMode,
    awarenessGranted: Boolean,
    isBridging: Boolean,
    onSwitchMode: (ChatMode) -> Unit,
    onEnableLocalMesh: () -> Unit,
    onP2pDirect: () -> Unit,
    onOpenRelayPanel: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text("网络状态", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "本地 " + transportStatus.localPeerCount + " 人 · 云端 " + transportStatus.nostrPeerCount + " 人 · 我: " + myName +
                (if (isBridging) " · 本机桥接中" else ""),
            color = TextHint, fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))

        // 传输通道状态行
        TransportLineRow(
            icon = Icons.Filled.Lan,
            label = "局域网 UDP 直连",
            active = transportStatus.lanUdp,
            hint = if (transportStatus.lanUdp) {
                if (transportStatus.dataLinks > 0) {
                    "同一 Wi-Fi 内广播直连"
                } else {
                    "UDP 广播已启用"
                }
            } else "不可用"
        )
        Spacer(Modifier.height(8.dp))
        TransportLineRow(
            icon = Icons.Filled.Public,
            label = "Nostr 云端中继",
            active = transportStatus.nostr,
            hint = if (transportStatus.nostr) {
                "已连接 " + transportStatus.relayConnected + "/" + transportStatus.relayTotal + " 个中继 · 点击管理中继"
            } else "未启用"
        )

        if (!awarenessGranted) {
            Spacer(Modifier.height(12.dp))
            EnableLocalMeshButton(onEnableLocalMesh)
        }

        Spacer(Modifier.height(16.dp))
        Text("聊天方式", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        ChatModeSelector(currentMode = currentMode, onSwitchMode = onSwitchMode)

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Primary.copy(0.06f))
                .clickable { onOpenRelayPanel() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Public, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("管理 Nostr 中继服务器", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("›", color = TextHint, fontSize = 16.sp)
        }

        Spacer(Modifier.height(10.dp))
        // P2P 直连:路由器 AP 隔离时的点对点补充通道
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Primary.copy(0.06f))
                .clickable { onP2pDirect() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Lan, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("P2P 直连", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("路由器隔离设备间通信时,点对点组网绕过路由器", color = TextHint, fontSize = 11.sp)
            }
            Text("›", color = TextHint, fontSize = 16.sp)
        }
    }
}

@Composable
private fun TransportLineRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    hint: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape)
                .background(if (active) Success.copy(0.12f) else TextHint.copy(0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (active) Success else TextHint.copy(0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextPrimary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(hint, color = if (active) Success else TextHint, fontSize = 11.sp)
    }
}

@Composable
private fun EnableLocalMeshButton(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(AccentMint.copy(0.12f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Wifi, contentDescription = null, tint = AccentMint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("启用局域网通道", color = AccentMint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ════════════════════════════════════════
//  中继管理面板
// ════════════════════════════════════════

@Composable
private fun RelayPanelContent(
    relays: List<String>,
    builtinRelays: List<String>,
    connectedCount: Int,
    onSetRelays: (List<String>) -> Unit,
    onAddRelay: (String) -> Boolean,
    onRemoveRelay: (String) -> Unit,
    onResetRelays: () -> Unit
) {
    var newRelayInput by remember { mutableStateOf("") }
    val customRelays = relays.filter { it !in builtinRelays }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text("Nostr 中继服务器", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "已连接 " + connectedCount + "/" + relays.size + " 个中继 · 勾选启用,取消勾选停用",
            color = TextHint, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        // 内置 3 个稳定中继
        Text("内置稳定中继", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        builtinRelays.forEach { url ->
            RelayRow(
                url = url,
                displayName = NostrTransport.relayDisplayName(url),
                enabled = url in relays,
                isBuiltin = true,
                onToggle = {
                    val next = if (url in relays) relays - url else relays + url
                    onSetRelays(next)
                },
                onRemove = null
            )
            Spacer(Modifier.height(4.dp))
        }

        // 自定义中继
        if (customRelays.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("自定义中继", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            customRelays.forEach { url ->
                RelayRow(
                    url = url,
                    displayName = NostrTransport.relayDisplayName(url),
                    enabled = true,
                    isBuiltin = false,
                    onToggle = { onRemoveRelay(url) },
                    onRemove = { onRemoveRelay(url) }
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        // 添加自定义中继
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newRelayInput,
                onValueChange = { newRelayInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("wss://relay.example.com", color = TextHint, fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val url = newRelayInput.trim()
                    if (url.isNotBlank() && onAddRelay(url)) newRelayInput = ""
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(0.1f),
                    unfocusedContainerColor = Color.White.copy(0.06f),
                    focusedBorderColor = AccentBlue.copy(0.5f),
                    unfocusedBorderColor = Color.White.copy(0.2f)
                )
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Primary.copy(0.1f))
                    .clickable {
                        val url = newRelayInput.trim()
                        if (url.isNotBlank() && onAddRelay(url)) newRelayInput = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加", tint = Primary, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Primary.copy(0.06f))
                .clickable { onResetRelays() }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text("恢复默认 3 个稳定中继", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RelayRow(
    url: String,
    displayName: String,
    enabled: Boolean,
    isBuiltin: Boolean,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (IsDark) CardBg else Color.White.copy(0.6f))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .background(if (enabled) Primary else Color.Transparent)
                .border(2.dp, if (enabled) Primary else TextHint.copy(0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (enabled) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(displayName, color = if (enabled) TextPrimary else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(url, color = TextHint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!isBuiltin && onRemove != null) {
            Text(
                "移除",
                color = Danger,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRemove() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 聊天方式选择器:自动 / 仅局域网 / 仅云端中继,单选。
 */
@Composable
internal fun ChatModeSelector(
    currentMode: ChatMode,
    onSwitchMode: (ChatMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatMode.entries.forEach { mode ->
            val selected = currentMode == mode
            FrostedGlassCard(
                modifier = Modifier.fillMaxWidth().clickable { onSwitchMode(mode) }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (mode) {
                        ChatMode.AUTO -> Icons.Filled.AltRoute
                        ChatMode.LAN_UDP -> Icons.Filled.Lan
                        ChatMode.NOSTR -> Icons.Filled.Public
                    }
                    val iconTint by animateColorAsState(
                        if (selected) Primary else TextHint, tween(200), label = "modeIcon"
                    )
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mode.displayName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(mode.desc, color = TextHint, fontSize = 11.sp)
                    }
                    val dotBg by animateColorAsState(
                        if (selected) Primary else Color.Transparent, tween(200), label = "modeDotBg"
                    )
                    val dotBorder by animateColorAsState(
                        if (selected) Primary else TextHint.copy(0.3f), tween(200), label = "modeDotBorder"
                    )
                    Box(
                        Modifier.size(20.dp).clip(CircleShape)
                            .background(dotBg)
                            .border(2.dp, dotBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
