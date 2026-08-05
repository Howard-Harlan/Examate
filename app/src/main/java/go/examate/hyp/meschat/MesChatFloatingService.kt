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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mesh 聊天悬浮窗服务(任务A)。
 *
 * 设计要点:
 * 1. 复用 [MesChatController] 单例([MesChatController.ensureStarted]),与 Activity 共享
 *    传输层与会话状态。Activity finish 后 controller 不停止,悬浮窗继续收发消息
 * 2. 悬浮窗内只展示 "#附近" 频道:消息列表 + 输入框,通过 [MesChatController.sendTextToChannel]
 *    发送,不改动 [MesChatController.currentConversationId],避免与主界面状态耦合
 * 3. 使用 [ComposeView] 挂载到 [WindowManager](参考 [go.examate.hyp.service.LanChatService] 实现)
 * 4. 标题栏可拖动;输入框聚焦时去掉 FLAG_NOT_FOCUSABLE 以允许输入,失焦时恢复
 * 5. 关闭按钮调用 stopSelf,并在 [onDestroy] 中释放 controller 单例
 */
class MesChatFloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "MesChatFloatingService"
        /** 悬浮窗内固定展示的频道(不带 # 前缀)。internal 供同文件 Composable 访问 */
        internal const val CHANNEL_NAME = "附近"

        @Volatile
        @JvmField
        var isActive = false  // 供 Activity 判断是否需要保留 controller 单例
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
            // 二次确认悬浮窗权限(Service 可能在权限被回收后被启动)
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

            // 复用或创建 controller 单例(Activity 已启动过则直接复用)
            val nickname = PrefsManager.getInstance(this).nickname.ifBlank { "匿名用户" }
            val controller = MesChatController.ensureStarted(this, nickname)

            initWindow(controller)
            windowManager.addView(composeView, chatParams)
            isViewAdded = true
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            ToastUtil.showLong(this, "悬浮窗启动失败: ${e.message}")
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
        // 悬浮窗是 controller 的最后一个持有者,关闭时释放传输层
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
            y = (dm.heightPixels - dp(440)) / 2
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
                        onInputFocusChange = { focused -> updateWindowFocusable(focused) }
                    )
                }
            }
        }
    }

    /** 根据输入框聚焦状态切换窗口 flags,失焦时恢复 FLAG_NOT_FOCUSABLE 避免抢占全局焦点 */
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
 * Mesh 聊天悬浮窗 Composable — 仅展示 "#附近" 频道。
 *
 * @param controller 单例 controller,直接观察其 [MesChatController.transportStatus]、
 *                   [MesChatController.inputText] 与 #附近 频道的消息列表
 * @param onClose 关闭按钮回调
 * @param onDrag 标题栏拖动回调(参数为像素增量 dx, dy)
 * @param onInputFocusChange 输入框聚焦状态变化,用于切换窗口 flags
 */
@Composable
private fun MesChatFloatingWindow(
    controller: MesChatController,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onInputFocusChange: (Boolean) -> Unit
) {
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // 直接观察 controller 单例状态(Compose 会自动重组)
    val transportStatus by controller.transportStatus
    val inputText by controller.inputText
    // 从 messagesByConversation 取 #附近 频道的消息列表
    val messages = controller.messagesByConversation
        .find { it.first == "#${MesChatFloatingService.CHANNEL_NAME}" }
        ?.second
        ?: emptyList()

    // 新消息到达时滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // 传输通道状态文案
    val channelLabel = when {
        transportStatus.ble && transportStatus.wifiAware -> "双通道"
        transportStatus.ble -> "蓝牙"
        transportStatus.wifiAware -> "Wi-Fi"
        else -> "离线"
    }
    val channelColor = when {
        transportStatus.ble && transportStatus.wifiAware -> Success
        transportStatus.ble || transportStatus.wifiAware -> AccentMint
        else -> TextHint
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
                    .background(Color(0xFFF5F5F7))
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
                    text = "#${MesChatFloatingService.CHANNEL_NAME}",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                // 传输通道状态
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(channelColor))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$channelLabel · ${transportStatus.peerCount} 人",
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
                        tint = Color(0xFF6B7280),
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
                items(messages, key = { it.id }) { msg ->
                    FloatingMessageBubble(msg, timeFmt)
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
                    onValueChange = { controller.inputText.value = it },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onInputFocusChange(it.isFocused) },
                    placeholder = { Text("说点什么…", fontSize = 13.sp, color = TextSecondary) },
                    shape = RoundedCornerShape(22.dp),
                    singleLine = false,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        controller.sendTextToChannel(MesChatFloatingService.CHANNEL_NAME)
                    }),
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
                        .clickable {
                            if (canSend) controller.sendTextToChannel(MesChatFloatingService.CHANNEL_NAME)
                        },
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
private fun FloatingMessageBubble(msg: MesMessage, timeFmt: SimpleDateFormat) {
    val alignment = if (msg.isMine) Alignment.End else Alignment.Start
    val bgColor = if (msg.isMine) AccentBlue else Color(0xFFEEF1F5)
    val textColor = if (msg.isMine) Color.White else TextPrimary

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // 频道消息显示发送者昵称(非自己)
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
