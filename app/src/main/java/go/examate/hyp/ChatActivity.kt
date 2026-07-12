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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.agent.QuickActions
import go.examate.hyp.config.MyConfig
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.ui.chat.DynamicInputBar
import go.examate.hyp.ui.chat.HistoryPanel
import go.examate.hyp.ui.chat.MessageBubble
import go.examate.hyp.ui.chat.ActionCardView
import go.examate.hyp.ui.chat.ModelSelector
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.ChatHistoryManager
import go.examate.hyp.util.ChatMsg
import go.examate.hyp.util.ChatSession
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
    // 单一状态所有者:ChatViewModel 通过 StateFlow 持有全部聊天状态,
    // 由本 Composable 用 collectAsState() 收集。Activity 不再自管状态,
    // 配置变更(旋屏等)后状态不丢失,且彻底避免了后台线程写 Compose MutableState 的崩溃根因。
    val vm: ChatViewModel = viewModel()
    val ui by vm.uiState.collectAsState()
    val providers = remember { MyConfig.loadProviders(context) }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val historyTimeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    // 稳定的 lambda:否则每次重组生成新实例会导致所有可见气泡被迫重组(见 MarkdownRenderer 注释)。
    val onQuickFollowUp = remember {
        { source: String -> vm.updateInputText("继续追问这条回复：\n${source.take(800)}\n\n") }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        vm.setPendingImage(uri?.toString())
    }

    // 启动时:若上次会话距今30分钟内且处于agents模式,自动恢复上次对话;
    // 超过30分钟或非agents模式则开新会话。
    LaunchedEffect(Unit) {
        if (ui.agentsMode && ui.messages.isEmpty()) {
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

    LaunchedEffect(ui.messages.size, ui.isLoading) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    // 消息变化时自动保存当前会话(持久化交给 ChatHistoryManager 的后台线程)。
    LaunchedEffect(ui.messages.size) {
        vm.saveCurrentSession(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .imePadding()
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(22.dp))
            }
            ModelSelector(providers = providers, selectedIndex = ui.modelIndex, onSelect = { vm.loadModelIndex(it) })
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clickable {
                    vm.loadHistory(context)
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "历史", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.size(40.dp).clickable {
                    context.startActivity(android.content.Intent(context, AgentSettingsActivity::class.java))
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
        }

        QuickActionsBar(
            agentsMode = ui.agentsMode,
            onActionClick = { action ->
                if (action.requiresAgents && !ui.agentsMode) {
                    Toast.makeText(context, "此操作需要开启 Agents 模式", Toast.LENGTH_SHORT).show()
                }
                vm.updateInputText(action.prompt)
            }
        )

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
        ) {
            if (ui.messages.isEmpty()) {
                item(key = "empty") { EmptyHint(ui.agentsMode) }
            }
            items(ui.messages, key = { it.id }) { message ->
                MessageBubble(
                    message,
                    timeFmt,
                    context,
                    onQuickFollowUp = onQuickFollowUp
                )
            }
            if (ui.isLoading) {
                item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = TextHint)
                        if (ui.loadingStatus.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(ui.loadingStatus, color = TextHint, fontSize = 12.sp)
                        }
                        // Agents 模式下,工具执行结果卡片实时出现
                        ui.toolProgressCards.forEach { card ->
                            Spacer(Modifier.height(8.dp))
                            ActionCardView(card)
                        }
                    }
                }
            }
        }

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
                vm.send(text, uri, providers, ui.agentsMode, context)
            },
            agentsMode = ui.agentsMode,
            onToggleAgents = { vm.toggleAgentsMode() }
        )
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
            onNewChat = {
                vm.clearMessages()
            },
            onSessionClick = { session ->
                vm.selectSession(session)
            },
            onDelete = { id ->
                vm.deleteSession(context, id)
            },
            onDismiss = { vm.dismissHistory() }
        )
    }
}

@Composable
private fun QuickActionsBar(agentsMode: Boolean, onActionClick: (QuickActions.Action) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActions.allActions.forEach { action ->
            val highlight = agentsMode || !action.requiresAgents
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (highlight) Color(0xFF5B8DEF).copy(0.1f) else Color(0xFFE5E7EB).copy(0.5f))
                    .clickable { onActionClick(action) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(action.icon, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    action.name,
                    color = if (highlight) Color(0xFF5B8DEF) else TextHint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(agentsMode: Boolean) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Text(
            "与 AI 开始对话",
            color = TextHint,
            fontSize = 14.sp
        )
    }
}
