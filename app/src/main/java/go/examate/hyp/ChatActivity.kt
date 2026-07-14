package go.examate.hyp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.chat.ActionCardView
import go.examate.hyp.ui.chat.DynamicInputBar
import go.examate.hyp.ui.chat.HistoryPanel
import go.examate.hyp.ui.chat.MessageBubble
import go.examate.hyp.ui.chat.ModelSelector
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.AvatarHelper
import go.examate.hyp.util.ChatHistoryManager
import go.examate.hyp.util.ChatMsg
import java.text.SimpleDateFormat
import java.util.Locale

class ChatActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LAUNCH_MODE = "launch_mode"
        const val MODE_CHAT_ONLY = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    ChatScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel()
    val ui by vm.uiState.collectAsState()
    val providers = remember { MyConfig.loadProviders(context) }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val historyTimeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val backdrop = LocalGlassBackdrop.current
    val onQuickFollowUp = remember {
        { source: String -> vm.updateInputText("关于这段内容:\n${source.take(800)}\n\n") }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        vm.setPendingImage(uri?.toString())
    }

    // Agent 聊天头像:读取开关 + 加载头像
    val chatAvatarEnabled = remember { PrefsManager.getInstance(context).isAgentChatAvatarEnabled }
    val agentAvatarBitmap = remember(chatAvatarEnabled) {
        if (chatAvatarEnabled) AvatarHelper.loadAgentAvatar(context) else null
    }

    LaunchedEffect(Unit) {
        if (ui.messages.isEmpty()) {
            val sessions = ChatHistoryManager.loadAll(context)
            val latest = sessions.firstOrNull()
            if (latest != null && latest.messages.isNotEmpty()) {
                val elapsed = System.currentTimeMillis() - latest.updateTime
                if (elapsed < 30 * 60 * 1000L) {
                    vm.selectSession(latest)
                }
            }
        }
    }

    // reverseLayout=true 时,index 0 在底部,新消息自动出现在底部
    LaunchedEffect(ui.currentSession.id) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(ui.messages.size) {
        vm.saveCurrentSession(context)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .imePadding()
    ) {
        // ═══ 消息列表(reverseLayout:消息从底部开始,新消息自动出现在底部) ═══
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 0.dp, end = 0.dp,
                top = 64.dp,   // 顶栏高度+留白
                bottom = 90.dp // 输入栏高度+留白
            )
        ) {
            if (ui.messages.isEmpty() && ui.streamingText.isEmpty() && !ui.isLoading) {
                // 空状态提示(reverseLayout 下第一个 item 在底部)
                item(key = "empty") { EmptyHint() }
            } else {
                // ★ 底部:流式文本 或 加载指示器
                if (ui.streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        MessageBubble(
                            ChatMsg("assistant", ui.streamingText, false, System.currentTimeMillis()),
                            timeFmt, context, onQuickFollowUp = onQuickFollowUp,
                            isStreaming = true,
                            showAvatar = chatAvatarEnabled,
                            avatarBitmap = agentAvatarBitmap
                        )
                    }
                } else if (ui.isLoading) {
                    item(key = "loading") {
                        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = TextHint)
                            if (ui.loadingStatus.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(ui.loadingStatus, color = TextHint, fontSize = 12.sp)
                            }
                        }
                    }
                }
                // ★ 工具进度卡片
                ui.toolProgressCards.forEachIndexed { index, card ->
                    item(key = "card_$index") { ActionCardView(card) }
                }
                // ★ 消息列表(reversed:最新消息在底部 = reversed 列表的第一个)
                items(ui.messages.reversed(), key = { it.id }) { message ->
                    MessageBubble(
                        message, timeFmt, context, onQuickFollowUp = onQuickFollowUp,
                        showAvatar = chatAvatarEnabled,
                        avatarBitmap = agentAvatarBitmap
                    )
                }
            }
        }

        // ═══ 顶栏:悬浮液态玻璃(单行:返回 / 模型选择 / 历史 / 设置) ═══
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮 — 液态玻璃胶囊
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp),
                surfaceAlpha = 0.75f
            )
            Spacer(Modifier.width(6.dp))
            // 模型选择(液态玻璃胶囊包裹)
            Box(
                Modifier.then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { vibrancy(); blur(12f) },
                            highlight = { Highlight.Plain },
                            onDrawSurface = { drawRect(Color.White.copy(0.75f)) }
                        )
                    } else {
                        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.88f))
                    }
                )
            ) {
                ModelSelector(
                    providers = providers,
                    selectedIndex = ui.modelIndex,
                    onSelect = { vm.loadModelIndex(it) }
                )
            }
            Spacer(Modifier.weight(1f))
            // 历史按钮 — 液态玻璃胶囊
            LiquidGlassIconButton(
                icon = Icons.Filled.Menu,
                contentDescription = "历史",
                onClick = { vm.loadHistory(context) },
                modifier = Modifier.size(40.dp),
                surfaceAlpha = 0.75f
            )
            Spacer(Modifier.width(6.dp))
            // 设置按钮 — 液态玻璃胶囊
            LiquidGlassIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "设置",
                onClick = {
                    context.startActivity(android.content.Intent(context, AgentSettingsActivity::class.java))
                },
                modifier = Modifier.size(40.dp),
                surfaceAlpha = 0.75f
            )
        }

        // ═══ 底部输入栏 ═══
        Box(Modifier.align(Alignment.BottomCenter)) {
            DynamicInputBar(
                text = ui.inputText,
                onTextChange = { vm.updateInputText(it) },
                hasImage = ui.pendingImageUriString != null,
                onPickImage = { imagePicker.launch("image/*") },
                onClearImage = { vm.clearPendingImage() },
                canSend = (ui.inputText.isNotBlank() || ui.pendingImageUriString != null) && !ui.isLoading && providers.isNotEmpty(),
                onSend = {
                    val text = ui.inputText.trim()
                    if (text.isEmpty() && ui.pendingImageUriString == null) return@DynamicInputBar
                    val uri = ui.pendingImageUriString?.let { Uri.parse(it) }
                    vm.send(text, uri, providers, context)
                }
            )
        }
    }

    AnimatedVisibility(
        visible = ui.showHistory,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut()
    ) {
        HistoryPanel(
            sessions = ui.historyList,
            timeFmt = historyTimeFmt,
            currentSessionId = ui.currentSession.id,
            onNewChat = { vm.clearMessages() },
            onSessionClick = { session -> vm.selectSession(session) },
            onDelete = { id -> vm.deleteSession(context, id) },
            onDismiss = { vm.dismissHistory() }
        )
    }
}

@Composable
private fun EmptyHint() {
    val text = "发掘无限可能"
    val charCount = text.length
    // 从左到右逐字显示进度(0..charCount)
    val revealProgress = remember { Animatable(0f) }
    // 流体闪光偏移(0..2,扫两遍)
    val shimmerOffset = remember { Animatable(0f) }
    var rowWidthPx by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        // 1. 从左到右逐字显示
        revealProgress.animateTo(
            targetValue = charCount.toFloat(),
            animationSpec = tween(durationMillis = charCount * 180, delayMillis = 300)
        )
        // 2. 流体在文字后闪两下
        shimmerOffset.animateTo(
            targetValue = 2f,
            animationSpec = tween(durationMillis = 1600, delayMillis = 120)
        )
    }

    Box(
        Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .onSizeChanged { rowWidthPx = it.width.toFloat() }
                .drawWithContent {
                    drawContent()
                    // 流体闪光:文字全部显示后启动
                    if (shimmerOffset.value > 0f && rowWidthPx > 0f) {
                        // 每一遍 phase 0..1,从左扫到右
                        val phase = shimmerOffset.value % 1f
                        // 中心 x:从 -0.5*width 到 1.5*width,光带进入并离开文字
                        val centerX = (phase * 2f - 0.5f) * rowWidthPx
                        val halfBand = rowWidthPx * 0.28f
                        val brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.32f),
                                Color.Transparent
                            ),
                            startX = centerX - halfBand,
                            endX = centerX + halfBand
                        )
                        drawRect(brush = brush, size = size)
                    }
                }
        ) {
            text.forEachIndexed { index, char ->
                val charProgress = (revealProgress.value - index).coerceIn(0f, 1f)
                Text(
                    text = char.toString(),
                    color = TextHint,
                    fontSize = 15.sp,
                    modifier = Modifier.graphicsLayer {
                        alpha = charProgress
                    }
                )
            }
        }
    }
}
