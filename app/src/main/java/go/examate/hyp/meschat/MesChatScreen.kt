package go.examate.hyp.meschat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.material.icons.filled.Pause
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * @param onRecordVoice 录制语音(保留参数;录音逻辑已内置,点击语音按钮由本页直接处理)
 * @param onPickFile 选择文件
 * @param onPlayVoice 播放语音(保留参数;播放逻辑已内置 MediaPlayer)
 * @param onOpenImage 查看大图(保留参数;点击暂时以 Toast 提示)
 * @param onOpenFile 打开文件(保留参数;打开逻辑已内置 FileProvider + Intent)
 * @param onSendVoice 录音结束并发送:语音字节数组 + 时长(秒)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MesChatScreen(
    conversation: ChatConversation,
    messages: List<MesMessage>,
    transportStatus: TransportStatus,
    inputText: String,
    currentMode: ChatMode,
    onSwitchMode: (ChatMode) -> Unit,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSendText: () -> Unit,
    onPickImage: () -> Unit,
    onRecordVoice: () -> Unit,
    onPickFile: () -> Unit,
    onPlayVoice: (MesMessage) -> Unit,
    onOpenImage: (MesMessage) -> Unit,
    onOpenFile: (MesMessage) -> Unit,
    onSendVoice: (ByteArray, Int) -> Unit,
    hasMoreHistory: Boolean = false,
    isLoadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var showAttachPanel by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }

    // ═══ 语音录制状态 ═══
    val context = LocalContext.current
    val voiceRec = remember { VoiceRecorderHolder() }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }

    // 录音权限请求
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (voiceRec.start(context)) {
                isRecording = true
                recordSeconds = 0
            } else {
                Toast.makeText(context, "录音启动失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "需要麦克风权限以录制语音", Toast.LENGTH_SHORT).show()
        }
    }

    /** 开始录音:已授权则直接录,否则请求权限 */
    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (voiceRec.start(context)) {
                isRecording = true
                recordSeconds = 0
            } else {
                Toast.makeText(context, "录音启动失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 停止录音并发送(或取消) */
    fun stopRecording(send: Boolean) {
        isRecording = false
        val result = voiceRec.stop()
        if (send && result != null) {
            onSendVoice(result.first, result.second)
        }
    }

    // 录音计时 + 60 秒上限自动停止
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording && recordSeconds < 60) {
                recordSeconds = ((System.currentTimeMillis() - voiceRec.startTimeMs) / 1000).toInt()
                if (recordSeconds >= 60) {
                    stopRecording(send = true)
                    break
                }
                delay(200)
            }
        }
    }

    // 离开页面时释放录音资源
    DisposableEffect(Unit) {
        onDispose { voiceRec.release() }
    }

    // ═══ 懒加载历史:滚动到顶触发翻页;顶部新增恢复视口锚点,底部新增滚到底 ═══
    var prevSize by remember { mutableStateOf(0) }
    var prevTopId by remember { mutableStateOf<String?>(null) }
    val firstVisible = remember { mutableStateOf(0 to 0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { firstVisible.value = it }
    }
    LaunchedEffect(messages.size) {
        val size = messages.size
        if (size == 0) return@LaunchedEffect
        val topId = messages.firstOrNull()?.id
        if (prevSize == 0) {
            // 首次进入:定位到最新消息
            listState.scrollToItem(size - 1)
        } else if (size > prevSize) {
            if (topId != prevTopId) {
                // 顶部新增(懒加载翻页):恢复锚点,视口不跳动
                val added = size - prevSize
                val (idx, off) = firstVisible.value
                listState.scrollToItem((idx + added).coerceAtMost(size - 1), off)
            } else {
                // 底部新增:滚动到底
                listState.animateScrollToItem(size - 1)
            }
        }
        prevSize = size
        prevTopId = topId
    }
    // 滚动到顶且还有更早历史 → 自动请求下一页(用户看到哪儿才拉哪儿)
    LaunchedEffect(listState, hasMoreHistory, isLoadingOlder) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx <= 1 && hasMoreHistory && !isLoadingOlder && messages.isNotEmpty()) {
                    onLoadOlder()
                }
            }
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
            currentMode = currentMode,
            onBack = onBack,
            onSwitchModeClick = { showModeSheet = true }
        )

        // ═══ 密钥匹对会话横幅 ═══
        if (conversation.isKeyPairChat) {
            KeyPairBanner()
        }

        // ═══ 消息列表 ═══
        if (messages.isEmpty()) {
            EmptyConversationState(conversation, Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (hasMoreHistory) {
                    item(key = "older_indicator") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (isLoadingOlder) "正在加载更早消息…" else "上滑加载更早消息",
                                color = TextHint,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
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

        // ═══ 录音中:用录音状态条替换附件面板与输入栏(互斥过渡) ═══
        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            RecordingBar(
                seconds = recordSeconds,
                onStop = { stopRecording(send = true) },
                onCancel = { stopRecording(send = false) }
            )
        }
        AnimatedVisibility(
            visible = !isRecording,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
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
                            startRecording()
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
    }

    // ═══ 聊天方式切换面板 ═══
    if (showModeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DialogBg
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
            ) {
                Text("聊天方式", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("切换后当前会话将使用新的传输通道", color = TextHint, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                ChatModeSelector(currentMode = currentMode, onSwitchMode = { mode ->
                    onSwitchMode(mode)
                    showModeSheet = false
                })
            }
        }
    }
}

// ════════════════════════════════════════
//  顶栏
// ════════════════════════════════════════

@Composable
private fun ChatTopBar(
    conversation: ChatConversation,
    currentMode: ChatMode,
    onBack: () -> Unit,
    onSwitchModeClick: () -> Unit
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
            // 副标题:频道显示成员数,密钥匹对显示加密说明,私聊显示传输通道
            val subtitle = when {
                conversation.isChannel -> if (conversation.peerCount > 0) "${conversation.peerCount} 位成员" else "频道"
                conversation.isKeyPairChat -> "密钥加密 · 持有相同配对密钥的成员可解密"
                else -> conversation.transport.label
            }
            Text(subtitle, color = TextHint, fontSize = 11.sp, maxLines = 1)
        }
        // 聊天方式切换入口(胶囊,显示当前模式名)
        ChatModeChip(currentMode = currentMode, onClick = onSwitchModeClick)
    }
}

/** 顶栏聊天方式胶囊:显示当前模式图标 + 名称,点击打开切换面板 */
@Composable
private fun ChatModeChip(currentMode: ChatMode, onClick: () -> Unit) {
    val icon = when (currentMode) {
        ChatMode.AUTO -> Icons.Filled.AltRoute
        ChatMode.LAN_UDP -> Icons.Filled.Lan
        ChatMode.NOSTR -> Icons.Filled.CloudOff
    }
    FrostedGlassCard(modifier = Modifier.padding(0.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(13.dp))
            Text(currentMode.displayName, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("›", color = TextHint, fontSize = 14.sp)
        }
    }
}

/** 密钥匹对会话横幅 */
@Composable
private fun KeyPairBanner() {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AccentMint.copy(0.1f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = AccentMint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "密钥匹对 · 端到端加密,只有持有相同配对密钥的成员可以解密",
            color = AccentMint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TransportMiniIndicator(status: TransportStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TransportDotIcon(Icons.Filled.Lan, active = status.lanUdp)
        TransportDotIcon(Icons.Filled.Public, active = status.nostr)
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
        TransportType.LAN_UDP -> AccentMint
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
            // 时间 + 送达状态 + 通道标记
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
            ) {
                Text(
                    timeFmt.format(Date(msg.timestamp)),
                    color = TextHint,
                    fontSize = 10.sp
                )
                MessageViaBadge(msg)
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

/** 消息通道标记:显示消息经由的传输通道与跳数(仅收到的消息) */
@Composable
private fun MessageViaBadge(msg: MesMessage) {
    if (msg.isMine || msg.via == TransportType.NONE) return
    val label = when {
        msg.via == TransportType.LAN_UDP -> "局域网"
        msg.via == TransportType.LAN_UDP -> "局域网"
        else -> "Nostr"
    }
    Spacer(Modifier.width(4.dp))
    Text(
        label,
        color = if (msg.via == TransportType.LAN_UDP) AccentMint else AccentBlue,
        fontSize = 9.sp
    )
}

@Composable
private fun TextBubble(msg: MesMessage) {
    // 对方气泡主题化:浅色半透明白/深色深灰面,避免深色下白底+白字
    val bubbleColor = if (msg.isMine) AccentBlue.copy(0.85f) else CardBg
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
    val context = LocalContext.current
    val shape = if (msg.isMine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    val path = msg.imagePath
    Box(
        Modifier
            .size(width = 200.dp, height = 200.dp)
            .clip(shape)
            .background(CardBg)
            .clickable {
                // 暂时仅 Toast 提示"点击查看",后续可由 onOpenImage 接入大图查看页
                Toast.makeText(context, "点击查看", Toast.LENGTH_SHORT).show()
            },
        contentAlignment = Alignment.Center
    ) {
        if (path != null) {
            // 用 Coil 加载本地图片文件
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(path))
                    .crossfade(true)
                    .build(),
                contentDescription = "图片消息",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 无路径时显示占位
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = TextHint, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(4.dp))
                Text("图片", color = TextHint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun VoiceBubble(msg: MesMessage, onPlayVoice: (MesMessage) -> Unit) {
    val context = LocalContext.current
    val bubbleColor = if (msg.isMine) AccentBlue.copy(0.85f) else CardBg
    val textColor = if (msg.isMine) Color.White else TextPrimary
    val shape = if (msg.isMine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    // 播放状态:用 rememberSaveable 保存,避免重组/旋转时丢失
    var isPlaying by rememberSaveable(msg.id) { mutableStateOf(false) }
    var progress by rememberSaveable(msg.id) { mutableStateOf(0f) }
    // MediaPlayer 不持有 Saveable,用 remember 跟随消息 id
    var mediaPlayer by remember(msg.id) { mutableStateOf<MediaPlayer?>(null) }
    val path = msg.voicePath

    // 退出组合时释放播放器
    DisposableEffect(msg.id) {
        onDispose {
            mediaPlayer?.let { mp ->
                try { mp.release() } catch (_: Exception) {}
            }
        }
    }

    // 播放进度轮询(播放中)
    if (isPlaying) {
        LaunchedEffect(msg.id) {
            while (isPlaying) {
                val mp = mediaPlayer
                if (mp != null) {
                    val dur = mp.duration.coerceAtLeast(1)
                    progress = mp.currentPosition.toFloat() / dur
                    if (!mp.isPlaying) {
                        // 自然播放结束
                        isPlaying = false
                        progress = 0f
                        break
                    }
                }
                delay(100)
            }
        }
    }

    Row(
        Modifier
            .clip(shape)
            .background(bubbleColor)
            .clickable {
                val mp = mediaPlayer
                if (mp != null && isPlaying) {
                    // 暂停
                    mp.pause()
                    isPlaying = false
                } else if (mp != null) {
                    // 继续
                    mp.start()
                    isPlaying = true
                } else if (path != null) {
                    // 首次播放
                    try {
                        val player = MediaPlayer()
                        player.setDataSource(path)
                        player.prepare()
                        player.setOnCompletionListener {
                            isPlaying = false
                            progress = 0f
                        }
                        mediaPlayer = player
                        player.start()
                        isPlaying = true
                    } catch (e: Exception) {
                        Toast.makeText(context, "语音播放失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "语音文件不可用", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = textColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        // 简易波形条:播放时按进度高亮
        val bars = 5
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(bars) { i ->
                val active = isPlaying && (i.toFloat() / bars) <= progress
                Box(
                    Modifier
                        .size(width = 2.dp, height = (8 + (i % 3) * 4).dp)
                        .background(if (active) textColor else textColor.copy(0.4f))
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("${msg.voiceDuration}\"", color = textColor, fontSize = 12.sp)
    }
}

@Composable
private fun FileBubble(msg: MesMessage, onOpenFile: (MesMessage) -> Unit) {
    val context = LocalContext.current
    // 对方气泡主题化:浅色半透明白/深色深灰面
    val bubbleColor = if (msg.isMine) AccentBlue.copy(0.85f) else CardBg
    val textColor = if (msg.isMine) Color.White else TextPrimary
    val subColor = if (msg.isMine) Color.White.copy(0.75f) else TextHint
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .width(220.dp)
            .clip(shape)
            .background(bubbleColor)
            .clickable { openLocalFile(context, msg) }
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
        // 附件按钮:+ 旋转为 ×(参考 DynamicInputBar 的旋转模式)
        val attachRotation by animateFloatAsState(
            if (attachExpanded) 45f else 0f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "attachRot"
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Primary.copy(0.06f))
                .clickable { onToggleAttach() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "附件",
                tint = Primary,
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = attachRotation }
            )
        }
        Spacer(Modifier.width(8.dp))
        // 输入框(深色模式降白)
        val isDark = IsDark
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("说点什么…", color = TextHint, fontSize = 14.sp) },
            shape = RoundedCornerShape(22.dp),
            singleLine = false,
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(if (isDark) 0.12f else 0.7f),
                unfocusedContainerColor = Color.White.copy(if (isDark) 0.08f else 0.55f),
                focusedBorderColor = AccentBlue.copy(0.45f),
                unfocusedBorderColor = Color.White.copy(if (isDark) 0.2f else 0.5f)
            )
        )
        Spacer(Modifier.width(8.dp))
        // 发送按钮:禁用↔可用颜色平滑过渡
        val canSend = inputText.isNotBlank()
        val sendColor by animateColorAsState(
            if (canSend) AccentBlue else AccentBlue.copy(0.35f),
            tween(200), label = "sendColor"
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(sendColor)
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
private fun EmptyConversationState(conv: ChatConversation, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (conv.isChannel) "#" else "✉",
                color = TextHint.copy(0.4f),
                fontSize = 40.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    conv.isChannel -> "频道 ${conv.title}"
                    conv.isKeyPairChat -> "密钥匹对会话 ${conv.title}"
                    else -> "与 ${conv.title} 的私聊"
                },
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    conv.isChannel -> "在频道中发送消息即可让所有成员看到"
                    conv.isKeyPairChat -> "消息以配对密钥端到端加密,只有持有相同密钥的成员可以解密"
                    else -> "消息优先走局域网 UDP 直连,不可达时经云端中继加密转发"
                },
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
@Composable
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

// ════════════════════════════════════════
//  语音录制
// ════════════════════════════════════════

/**
 * 语音录制状态持有者:封装 MediaRecorder 与临时文件,避免在 Compose 状态委托上直接持有。
 * 录音写入 [Context.cacheDir] 临时文件(FileProvider 已映射 cache-path)。
 */
private class VoiceRecorderHolder {
    var mediaRecorder: MediaRecorder? = null
    var file: File? = null
    var startTimeMs: Long = 0L

    /** 开始录音,成功返回 true */
    fun start(context: Context): Boolean {
        // 先清理上一次残留
        release()
        return try {
            val outFile = File(context.cacheDir, "mes_voice_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            file = outFile
            startTimeMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            file?.delete()
            file = null
            false
        }
    }

    /**
     * 停止录音,返回 (字节数组, 时长秒) ;录音太短或失败返回 null。
     * 调用方负责消费字节数据;临时文件会被删除。
     */
    fun stop(): Pair<ByteArray, Int>? {
        val rec = mediaRecorder
        val f = file
        val duration = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt().coerceAtLeast(0)
        try { rec?.stop() } catch (_: Exception) {
            // 录音过短会抛异常,忽略
        }
        try { rec?.release() } catch (_: Exception) {}
        mediaRecorder = null
        var result: Pair<ByteArray, Int>? = null
        if (f != null && f.exists() && duration > 0) {
            try {
                val bytes = f.readBytes()
                if (bytes.isNotEmpty()) result = bytes to duration
            } catch (_: Exception) {}
        }
        f?.delete()
        file = null
        return result
    }

    /** 释放资源(离开页面时调用) */
    fun release() {
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        file?.delete()
        file = null
    }
}

/** 格式化录音计时显示:秒数 -> "5\"" 或 "1:05" */
private fun formatRecordTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) String.format(Locale.getDefault(), "%d:%02d", m, s) else "${s}\""
}

/**
 * 录音中状态条:红色闪烁圆点 + 时长 + 取消/停止按钮。
 */
@Composable
private fun RecordingBar(
    seconds: Int,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    // 红色圆点闪烁动画
    val transition = rememberInfiniteTransition(label = "recDot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recDotAlpha"
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(Danger.copy(alpha = dotAlpha))
        )
        Spacer(Modifier.width(8.dp))
        Text("录音中", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text(formatRecordTime(seconds), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        // 取消
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Primary.copy(0.06f))
                .clickable { onCancel() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = "取消", tint = TextHint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        // 停止并发送
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(AccentBlue)
                .clickable { onStop() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = "停止并发送", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// ════════════════════════════════════════
//  文件打开
// ════════════════════════════════════════

/**
 * 用 Intent.ACTION_VIEW 打开本地文件,通过 FileProvider 暴露 content URI。
 * 文件需位于 FileProvider 配置的路径下(cache-path 已覆盖 cacheDir)。
 */
private fun openLocalFile(context: Context, msg: MesMessage) {
    val path = msg.filePath
    if (path.isNullOrEmpty()) {
        Toast.makeText(context, "文件路径无效", Toast.LENGTH_SHORT).show()
        return
    }
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(msg.fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(viewIntent, "打开文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 根据文件名后缀推断 MIME 类型,未知扩展名返回通配类型 */
private fun getMimeType(fileName: String?): String {
    val name = fileName ?: return "*/*"
    val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return when (ext) {
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "7z" -> "application/x-7z-compressed"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        else -> "*/*"
    }
}
