package go.examate.hyp.meschat

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentMint
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.InputBg
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collect

/**
 * Mesh 聊天悬浮窗服务(重构) — 悬浮窗即"简化版主界面"。
 *
 * 设计要点:
 * 1. 复用 [MesChatController] 单例,与主界面共享传输层与会话状态
 * 2. **可折叠**:折叠态为带未读角标的小气泡(可拖动、点击展开);
 *    展开态为简化版主界面:频道/用户双 Tab + 迷你聊天页 + 输入栏
 * 3. 悬浮窗内浏览会话不改变主界面 [MesChatController.currentConversationId],
 *    发送走 [MesChatController.sendTextToConversation],与主界面状态解耦
 * 4. 标题栏可拖动;输入框聚焦时去掉 FLAG_NOT_FOCUSABLE 以允许输入,失焦时恢复
 * 5. 关闭按钮调用 stopSelf,并在 [onDestroy] 中释放 controller 单例
 */
class MesChatFloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "MesChatFloatingService"

        @Volatile
        @JvmField
        var isActive = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var chatParams: WindowManager.LayoutParams
    private lateinit var composeView: ComposeView
    private var isViewAdded = false

    /** Service 实现 SavedStateRegistryOwner 以支持 ComposeView */
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry
    override val lifecycle: Lifecycle = lifecycleRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "悬浮窗权限未授予,服务退出")
                ToastUtil.show(this, "悬浮窗权限未授予")
                stopSelf()
                return
            }

            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

            isActive = true
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val nickname = PrefsManager.getInstance(this).nickname.ifBlank { "匿名用户" }
            val controller = MesChatController.ensureStarted(this, nickname)

            initWindow(controller)
            windowManager.addView(composeView, chatParams)
            isViewAdded = true
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            ToastUtil.showLong(this, "悬浮窗启动失败")
            stopSelf()
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (isViewAdded && ::composeView.isInitialized && composeView.isAttachedToWindow) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
            isViewAdded = false
        }
        isActive = false
        MesChatController.release()
        super.onDestroy()
    }

    // ═══ 悬浮窗(Compose) ═══

    private fun initWindow(controller: MesChatController) {
        chatParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = (dm.widthPixels - dp(300)) / 2
            y = (dm.heightPixels - dp(480)) / 2
        }
        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MesChatFloatingService)
            setViewTreeSavedStateRegistryOwner(this@MesChatFloatingService)
            setContent {
                ExamateTheme {
                    MesChatFloatingWindow(
                        controller = controller,
                        onClose = { stopSelf() },
                        onDrag = { dx, dy ->
                            chatParams.x += dx.toInt()
                            chatParams.y += dy.toInt()
                            try {
                                windowManager.updateViewLayout(composeView, chatParams)
                            } catch (e: Exception) {
                                Log.w(TAG, "updateViewLayout failed", e)
                            }
                        },
                        onSizeChanged = {
                            // 折叠/展开后触发窗口重新测量
                            try {
                                windowManager.updateViewLayout(composeView, chatParams)
                            } catch (e: Exception) {
                                Log.w(TAG, "updateViewLayout failed", e)
                            }
                        },
                        onInputFocusChange = { focused -> updateWindowFocusable(focused) }
                    )
                }
            }
        }
    }

    /** 根据输入框聚焦状态切换窗口 flags */
    private fun updateWindowFocusable(focused: Boolean) {
        if (!isViewAdded || !composeView.isAttachedToWindow) return
        try {
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            chatParams.flags = if (focused) {
                baseFlags
            } else {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            windowManager.updateViewLayout(composeView, chatParams)
        } catch (e: Exception) {
            Log.w(TAG, "updateWindowFocusable failed", e)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * Mesh 聊天悬浮窗 Composable — 简化版主界面。
 *
 * - 折叠态:小气泡 + 未读角标,拖动移动,点击展开
 * - 展开态:频道/用户 Tab、迷你聊天页、输入栏
 */
@Composable
private fun MesChatFloatingWindow(
    controller: MesChatController,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onSizeChanged: () -> Unit,
    onInputFocusChange: (Boolean) -> Unit
) {
    var collapsed by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var openConvId by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateMapOf<String, String>() }

    // 折叠/展开时通知 Service 重新测量窗口
    LaunchedEffect(collapsed) {
        onSizeChanged()
    }

    val unread = controller.conversations.sumOf { it.unreadCount }

    if (collapsed) {
        // ═══ 折叠气泡 ═══
        CollapsedBubble(
            unread = unread,
            onDrag = onDrag,
            onClick = { collapsed = false }
        )
        return
    }

    // ═══ 展开:简化版主界面 ═══
    Box(
        Modifier
            .width(300.dp)
            .height(480.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(DialogBg)
    ) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 标题栏(可拖动) ═══
            FloatingTitleBar(
                statusLabel = floatingStatusLabel(controller.transportStatus.value),
                statusColor = floatingStatusColor(controller.transportStatus.value),
                onDrag = onDrag,
                onCollapse = { collapsed = true },
                onClose = onClose
            )

            if (openConvId == null) {
                // ═══ 主页 Tab:频道 / 用户 ═══
                FloatingTabRow(
                    channelCount = controller.conversations.count { it.isChannel },
                    userCount = controller.peers.size,
                    selected = selectedTab,
                    onSelect = { selectedTab = it }
                )

                if (selectedTab == 0) {
                    FloatingChannelList(
                        conversations = controller.conversations,
                        onOpenChannel = { convId ->
                            openConvId = convId
                            controller.clearUnread(convId)
                        }
                    )
                } else {
                    FloatingUserList(
                        peers = controller.peers,
                        onChatWith = { peer ->
                            controller.ensurePrivateChat(peer.peerId)
                            controller.clearUnread(peer.peerId)
                            openConvId = peer.peerId
                        }
                    )
                }
            } else {
                // ═══ 迷你聊天页 ═══
                val convId = openConvId!!
                val conv = controller.conversations.find { it.id == convId }
                MiniChatView(
                    conversation = conv,
                    messages = controller.getConversationMessages(convId),
                    draft = drafts[convId] ?: "",
                    onDraftChange = { drafts[convId] = it },
                    onSend = {
                        val text = (drafts[convId] ?: "").trim()
                        if (text.isNotEmpty()) {
                            controller.sendTextToConversation(convId, text)
                            drafts[convId] = ""
                        }
                    },
                    onBack = { openConvId = null },
                    onInputFocusChange = onInputFocusChange,
                    hasMoreHistory = controller.hasMoreOlder[convId] ?: false,
                    onLoadOlder = { controller.loadOlderHistory(convId) }
                )
            }
        }
    }
}

/** 折叠态小气泡 */
@Composable
private fun CollapsedBubble(
    unread: Int,
    onDrag: (Float, Float) -> Unit,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(AccentBlue)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Chat,
            contentDescription = "展开 Mesh 聊天",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Danger),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 悬浮窗标题栏 */
@Composable
private fun FloatingTitleBar(
    statusLabel: String,
    statusColor: Color,
    onDrag: (Float, Float) -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit
) {
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
            text = "Mesh 聊天",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(statusColor))
        Spacer(Modifier.width(4.dp))
        Text(
            text = statusLabel,
            color = TextSecondary,
            fontSize = 11.sp
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(24.dp).clickable { onCollapse() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = "折叠",
                tint = Color(0xFF6B7280),
                modifier = Modifier.size(18.dp)
            )
        }
        Box(
            Modifier.size(24.dp).clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "关闭",
                tint = Color(0xFF6B7280),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 悬浮窗 Tab */
@Composable
private fun FloatingTabRow(
    channelCount: Int,
    userCount: Int,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FloatingTab(Icons.Filled.Group, "频道", channelCount, selected == 0, { onSelect(0) }, Modifier.weight(1f))
        FloatingTab(Icons.AutoMirrored.Filled.Chat, "用户", userCount, selected == 1, { onSelect(1) }, Modifier.weight(1f))
    }
}

@Composable
private fun FloatingTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentBlue.copy(0.14f) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (selected) AccentBlue else TextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label + (if (count > 0) "($count)" else ""),
            color = if (selected) AccentBlue else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/** 悬浮窗频道列表(含行内加入频道) */
@Composable
private fun FloatingChannelList(
    conversations: List<ChatConversation>,
    onOpenChannel: (String) -> Unit
) {
    val channels = conversations.filter { it.isChannel }
    var showJoinInput by remember { mutableStateOf(false) }
    var joinInput by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(channels, key = { "fch_" + it.id }) { conv ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(ScheduleCellBg.copy(alpha = 0.5f))
                        .clickable { onOpenChannel(conv.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "#" + conv.title,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conv.unreadCount > 0) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape).background(Danger),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString(),
                                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (conv.lastMessage.isNotEmpty()) {
                        Text(
                            conv.lastMessage.take(10),
                            color = TextHint,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            item(key = "join_input") {
                Spacer(Modifier.height(4.dp))
                if (!showJoinInput) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(AccentMint.copy(0.12f))
                            .clickable { showJoinInput = true }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = AccentMint, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("加入频道", color = AccentMint, fontSize = 12.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = joinInput,
                            onValueChange = { joinInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("频道名", fontSize = 12.sp, color = TextHint) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(0.15f),
                                unfocusedContainerColor = Color.White.copy(0.1f),
                                focusedBorderColor = AccentBlue.copy(0.5f),
                                unfocusedBorderColor = Color.White.copy(0.3f)
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                                .background(AccentMint)
                                .clickable {
                                    val name = joinInput.trim().removePrefix("#")
                                    if (name.isNotBlank()) {
                                        controllerJoinChannel(name)
                                        joinInput = ""
                                        showJoinInput = false
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "加入", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 加入频道(悬浮窗内通过单例获取 controller) */
private fun controllerJoinChannel(channel: String) {
    MesChatController.get()?.joinChannel(channel)
}

/** 悬浮窗用户列表 */
@Composable
private fun FloatingUserList(
    peers: List<MeshPeer>,
    onChatWith: (MeshPeer) -> Unit
) {
    if (peers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Lan,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("暂无在线用户", color = TextHint, fontSize = 12.sp)
            }
        }
        return
    }
    // 局域网用户优先展示,云端用户排在后面
    val sortedPeers = peers.sortedWith(
        compareBy<MeshPeer> { it.transport != TransportType.LAN_UDP }
            .thenBy { it.hopCount }
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(sortedPeers, key = { "fpeer_" + it.peerId }) { peer ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(ScheduleCellBg.copy(alpha = 0.5f))
                    .clickable { onChatWith(peer) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(AccentBlue.copy(0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        peer.nickname.take(1).ifBlank { "?" },
                        color = AccentBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        peer.nickname.ifBlank { "未知节点" },
                        color = TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (peer.transport == TransportType.LAN_UDP) {
                            if (peer.hopCount > 0) "本地 · " + peer.hopCount + "跳" else "本地"
                        } else "云端",
                        color = TextHint,
                        fontSize = 10.sp
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "私聊",
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 迷你聊天页 */
@Composable
private fun MiniChatView(
    conversation: ChatConversation?,
    messages: List<MesMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onInputFocusChange: (Boolean) -> Unit,
    hasMoreHistory: Boolean = false,
    onLoadOlder: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // 顶部新增(懒加载)恢复锚点;底部新增才滚到底
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
            listState.scrollToItem(size - 1)
        } else if (size > prevSize) {
            if (topId == prevTopId) {
                listState.animateScrollToItem(size - 1)
            } else {
                // 顶部新增(懒加载页):恢复锚点,视口不跳动
                val added = size - prevSize
                val (idx, off) = firstVisible.value
                listState.scrollToItem((idx + added).coerceAtMost(size - 1), off)
            }
        }
        prevSize = size
        prevTopId = topId
    }

    Column(Modifier.fillMaxSize()) {
        // 迷你聊天标题栏
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                (if (conversation?.isChannel == true) "#" else "") + (conversation?.title ?: "聊天"),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasMoreHistory) {
                item(key = "older_btn") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "加载更早消息",
                            color = AccentBlue,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onLoadOlder() }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            items(messages, key = { it.id }) { msg ->
                FloatingMessageBubble(msg, timeFmt)
            }
        }

        // 输入栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f).onFocusChanged { onInputFocusChange(it.isFocused) },
                placeholder = { Text("说点什么…", fontSize = 12.sp, color = TextSecondary) },
                shape = RoundedCornerShape(20.dp),
                singleLine = false,
                maxLines = 3,
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
            val canSend = draft.trim().isNotEmpty()
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(19.dp))
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

@Composable
private fun FloatingMessageBubble(msg: MesMessage, timeFmt: SimpleDateFormat) {
    val alignment = if (msg.isMine) Alignment.End else Alignment.Start
    val bgColor = if (msg.isMine) AccentBlue else ScheduleCellBg
    val textColor = if (msg.isMine) Color.White else TextPrimary

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!msg.isMine) {
            Text(
                text = msg.sender.ifBlank { "匿名" },
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
                        bottomStart = if (msg.isMine) 14.dp else 4.dp,
                        bottomEnd = if (msg.isMine) 4.dp else 14.dp
                    )
                )
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = msg.content, color = textColor, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Text(
            text = timeFmt.format(Date(msg.timestamp)),
            color = TextSecondary.copy(alpha = 0.6f),
            fontSize = 9.sp,
            modifier = Modifier.padding(
                start = if (msg.isMine) 0.dp else 4.dp,
                end = if (msg.isMine) 4.dp else 0.dp,
                top = 2.dp
            )
        )
    }
}

@Composable
private fun floatingStatusLabel(status: TransportStatus): String = when {
    status.isDual -> "本地+云端"
    status.lanUdp -> "局域网"
    status.nostr -> "云端中继"
    else -> "离线"
}

@Composable
private fun floatingStatusColor(status: TransportStatus): Color = when {
    status.isDual -> Success
    status.isAnyActive -> AccentMint
    else -> TextHint
}
