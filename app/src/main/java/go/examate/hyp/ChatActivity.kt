package go.examate.hyp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.agent.AgentOrchestrator
import go.examate.hyp.agent.QuickActions
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.ui.chat.DynamicInputBar
import go.examate.hyp.ui.chat.HistoryPanel
import go.examate.hyp.ui.chat.MessageBubble
import go.examate.hyp.ui.chat.ModelSelector
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.ChatHistoryManager
import go.examate.hyp.util.ChatMsg
import go.examate.hyp.util.ChatSession
import go.examate.hyp.util.HttpUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
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
    val providers = remember { MyConfig.loadProviders(context) }
    val prefs = remember { PrefsManager.getInstance(context) }
    var modelIndex by remember { mutableStateOf(0) }
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadingStatus by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val handler = remember { Handler(Looper.getMainLooper()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var currentSession by remember { mutableStateOf(ChatHistoryManager.newSession()) }
    var showHistory by remember { mutableStateOf(false) }
    var historyList by remember { mutableStateOf(ChatHistoryManager.loadAll(context)) }
    val historyTimeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var agentsMode by remember { mutableStateOf(prefs.isAgentsMode) }
    val scope = rememberCoroutineScope()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingImageUri = uri
    }

    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val title = if (currentSession.title == "新对话" && messages.any { it.role == "user" }) {
                ChatHistoryManager.makeTitle(messages.first { it.role == "user" }.content)
            } else currentSession.title
            currentSession = currentSession.copy(messages = messages.toList(), title = title, updateTime = System.currentTimeMillis())
            ChatHistoryManager.saveSession(context, currentSession)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .imePadding()
            .statusBarsPadding()
    ) {
        // ═══ 顶栏:返回 + 下拉选模型 + Agents开关 + 新聊天 + 历史 ═══
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(22.dp))
            }
            ModelSelector(
                providers = providers,
                selectedIndex = modelIndex,
                onSelect = { modelIndex = it }
            )
            Spacer(Modifier.weight(1f))
            // Agents 模式开关
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (agentsMode) Color(0xFF5B8DEF) else Color(0xFFE5E7EB))
                    .clickable {
                        agentsMode = !agentsMode
                        prefs.isAgentsMode = agentsMode
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (agentsMode) Icons.Filled.Star else Icons.Filled.Info,
                        contentDescription = null,
                        tint = if (agentsMode) Color.White else TextHint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (agentsMode) "Agents" else "对话",
                        color = if (agentsMode) Color.White else TextHint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Box(Modifier.size(40.dp).clickable {
                messages.clear(); currentSession = ChatHistoryManager.newSession()
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "新聊天", tint = TextPrimary, modifier = Modifier.size(22.dp))
            }
            Box(Modifier.size(40.dp).clickable {
                context.startActivity(android.content.Intent(context, go.examate.hyp.AgentSettingsActivity::class.java))
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.size(40.dp).clickable {
                historyList = ChatHistoryManager.loadAll(context); showHistory = true
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MoreVert, contentDescription = "历史", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
        }

        // ═══ 快捷操作栏 ═══
        QuickActionsBar(
            agentsMode = agentsMode,
            onActionClick = { action ->
                if (action.requiresAgents && !agentsMode) {
                    Toast.makeText(context, "此操作需要开启 Agents 模式", Toast.LENGTH_SHORT).show()
                }
                inputText = action.prompt
            }
        )

        // ═══ 消息列表 ═══
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
        ) {
            if (messages.isEmpty()) item(key = "empty") { EmptyHint(agentsMode) }
            items(messages.size, key = { i -> messages[i].id }) { i ->
                MessageBubble(messages[i], timeFmt, context)
            }
            if (isLoading) {
                item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = TextHint)
                        if (loadingStatus.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(loadingStatus, color = TextHint, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // ═══ 动态输入栏 ═══
        DynamicInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            hasImage = pendingImageUri != null,
            onPickImage = { imagePicker.launch("image/*") },
            onClearImage = { pendingImageUri = null },
            canSend = (inputText.isNotBlank() || pendingImageUri != null) && !isLoading && providers.isNotEmpty(),
            onSend = {
                val text = inputText.trim()
                if (text.isEmpty() && pendingImageUri == null) return@DynamicInputBar
                sendMessage(
                    text = text, imageUri = pendingImageUri,
                    providers = providers, modelIndex = modelIndex,
                    messages = messages,
                    setLoading = { isLoading = it },
                    setLoadingStatus = { loadingStatus = it },
                    scope = scope, context = context,
                    agentsMode = agentsMode,
                    onSent = { inputText = ""; pendingImageUri = null }
                )
            }
        )
    }

    // ═══ 历史聊天面板 ═══
    AnimatedVisibility(visible = showHistory, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
        HistoryPanel(
            sessions = historyList, timeFmt = historyTimeFmt,
            currentSessionId = currentSession.id,
            onNewChat = { messages.clear(); currentSession = ChatHistoryManager.newSession(); showHistory = false },
            onSessionClick = { session -> messages.clear(); messages.addAll(session.messages); currentSession = session; showHistory = false },
            onDelete = { id -> ChatHistoryManager.deleteSession(context, id); historyList = ChatHistoryManager.loadAll(context) },
            onDismiss = { showHistory = false }
        )
    }
}

// ════════════════════════════════════════════════
//  Skills 技能栏(水平滚动)
// ════════════════════════════════════════════════

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

// ════════════════════════════════════════════════
//  发送消息(支持 Agents 模式和普通模式)
// ════════════════════════════════════════════════

private fun sendMessage(
    text: String, imageUri: Uri?,
    providers: List<ModelProvider>, modelIndex: Int,
    messages: MutableList<ChatMsg>,
    setLoading: (Boolean) -> Unit,
    setLoadingStatus: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    agentsMode: Boolean,
    onSent: () -> Unit
) {
    val uriStr = imageUri?.toString()
    messages.add(ChatMsg("user", text, false, System.currentTimeMillis(), uriStr))
    onSent()
    setLoading(true)
    setLoadingStatus("")

    val provider = providers[modelIndex]

    scope.launch {
        try {
            if (imageUri != null) {
                // 图片消息:走原有逻辑(vision)
                setLoadingStatus("正在识别图片...")
                val imageBytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                }
                val response = HttpUtils.sendImageRequest(
                    provider.requestUrl, provider.modelId, imageBytes,
                    provider.apiKey, MyConfig.CHAT_SYSTEM_PROMPT, provider.endpointType
                )
                setLoading(false)
                if (response == null) {
                    messages.add(ChatMsg("assistant", "请求失败，请检查网络或 API 配置", true, System.currentTimeMillis()))
                } else {
                    val content = HttpUtils.extractContent(response, provider.endpointType)
                    messages.add(ChatMsg("assistant", if (content.isEmpty()) "解析失败:\n$response" else content, content.isEmpty(), System.currentTimeMillis()))
                }
            } else if (agentsMode) {
                // Agents 模式:使用 AgentOrchestrator(支持工具调用)
                val history = messages.map { it.role to it.content }.filter { it.first == "user" || it.first == "assistant" }
                val result = AgentOrchestrator.execute(
                    context = context,
                    provider = provider,
                    userMessage = text,
                    history = history.dropLast(1), // 去掉刚加的 user 消息(orchestrator 会加)
                    agentsMode = true
                ) { status -> setLoadingStatus(status) }
                setLoading(false)
                messages.add(ChatMsg(
                    "assistant",
                    result.text,
                    result.isError,
                    System.currentTimeMillis(),
                    null,
                    result.imageBase64,
                    result.imageUrl,
                    result.toolCallsLog
                ))
            } else {
                // 普通对话模式
                setLoadingStatus("正在思考...")
                val history = messages.map { it.role to it.content }.filter { it.first == "user" || it.first == "assistant" }
                val content = AgentOrchestrator.simpleChat(
                    context = context,
                    provider = provider,
                    userMessage = text,
                    history = history.dropLast(1),
                    systemPrompt = MyConfig.CHAT_SYSTEM_PROMPT
                )
                setLoading(false)
                if (content.isNullOrEmpty()) {
                    messages.add(ChatMsg("assistant", "请求失败，请检查网络或 API 配置", true, System.currentTimeMillis()))
                } else {
                    messages.add(ChatMsg("assistant", content, false, System.currentTimeMillis()))
                }
            }
        } catch (e: Exception) {
            setLoading(false)
            messages.add(ChatMsg("assistant", "发生错误: ${e.message ?: "未知错误"}", true, System.currentTimeMillis()))
        }
    }
}

// ════════════════════════════════════════════════
//  空状态提示
// ════════════════════════════════════════════════

@Composable
private fun EmptyHint(agentsMode: Boolean) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (agentsMode) Icons.Filled.Star else Icons.Filled.Info,
                contentDescription = null,
                tint = TextHint.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (agentsMode) "Agents 模式" else "与 AI 开始对话",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (agentsMode)
                    "AI 可以调用工具查询成绩、课表等\n试试说「帮我查一下成绩」"
                else
                    "支持 Markdown、LaTeX 公式、图片\n顶部切换模型，开启 Agents 获得更多能力",
                color = TextHint,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))
            // 快捷技能提示
            Row(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActions.allActions.take(4).forEach { action ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(action.icon, fontSize = 20.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(action.name, color = TextHint, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
