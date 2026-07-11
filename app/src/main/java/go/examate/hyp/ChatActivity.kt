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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
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
import go.examate.hyp.agent.MemoryManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val providers = remember { MyConfig.loadProviders(context) }
    val prefs = remember { PrefsManager.getInstance(context) }
    var modelIndex by remember { mutableStateOf(0) }
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadingStatus by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
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
            } else {
                currentSession.title
            }
            currentSession = currentSession.copy(
                messages = messages.toList(),
                title = title,
                updateTime = System.currentTimeMillis()
            )
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(22.dp))
            }
            ModelSelector(providers = providers, selectedIndex = modelIndex, onSelect = { modelIndex = it })
            Spacer(Modifier.weight(1f))
            ChatModeSwitch(
                agentsMode = agentsMode,
                onChange = { enabled ->
                    agentsMode = enabled
                    prefs.isAgentsMode = enabled
                }
            )
            Box(
                Modifier.size(40.dp).clickable {
                    messages.clear()
                    currentSession = ChatHistoryManager.newSession()
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新对话", tint = TextPrimary, modifier = Modifier.size(22.dp))
            }
            Box(
                Modifier.size(40.dp).clickable {
                    context.startActivity(android.content.Intent(context, AgentSettingsActivity::class.java))
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.size(40.dp).clickable {
                    historyList = ChatHistoryManager.loadAll(context)
                    showHistory = true
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "历史", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
        }

        QuickActionsBar(
            agentsMode = agentsMode,
            onActionClick = { action ->
                if (action.requiresAgents && !agentsMode) {
                    Toast.makeText(context, "此操作需要开启 Agents 模式", Toast.LENGTH_SHORT).show()
                }
                inputText = action.prompt
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
            if (messages.isEmpty()) {
                item(key = "empty") { EmptyHint(agentsMode) }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message,
                    timeFmt,
                    context,
                    onQuickFollowUp = { source ->
                        inputText = "继续追问这条回复：\n${source.take(800)}\n\n"
                    }
                )
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
                    text = text,
                    imageUri = pendingImageUri,
                    providers = providers,
                    modelIndex = modelIndex,
                    messages = messages,
                    setLoading = { isLoading = it },
                    setLoadingStatus = { loadingStatus = it },
                    scope = scope,
                    context = context,
                    agentsMode = agentsMode,
                    onSent = {
                        inputText = ""
                        pendingImageUri = null
                    }
                )
            }
        )
    }

    AnimatedVisibility(
        visible = showHistory,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut()
    ) {
        HistoryPanel(
            sessions = historyList,
            timeFmt = historyTimeFmt,
            currentSessionId = currentSession.id,
            onNewChat = {
                messages.clear()
                currentSession = ChatHistoryManager.newSession()
                showHistory = false
            },
            onSessionClick = { session ->
                messages.clear()
                messages.addAll(session.messages)
                currentSession = session
                showHistory = false
            },
            onDelete = { id ->
                ChatHistoryManager.deleteSession(context, id)
                historyList = ChatHistoryManager.loadAll(context)
            },
            onDismiss = { showHistory = false }
        )
    }
}

@Composable
private fun ChatModeSwitch(agentsMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFDEE3F0).copy(alpha = 0.7f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModeSegment(
            text = "对话",
            icon = Icons.Filled.Info,
            selected = !agentsMode
        ) { onChange(false) }
        ModeSegment(
            text = "Agents",
            icon = Icons.Filled.Build,
            selected = agentsMode
        ) { onChange(true) }
    }
}

@Composable
private fun ModeSegment(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) Color(0xFF5B8DEF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = if (selected) Color.White else TextPrimary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            color = if (selected) Color.White else TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
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

private fun sendMessage(
    text: String,
    imageUri: Uri?,
    providers: List<ModelProvider>,
    modelIndex: Int,
    messages: MutableList<ChatMsg>,
    setLoading: (Boolean) -> Unit,
    setLoadingStatus: (String) -> Unit,
    scope: CoroutineScope,
    context: Context,
    agentsMode: Boolean,
    onSent: () -> Unit
) {
    val uriStr = imageUri?.toString()
    messages.add(ChatMsg("user", text, false, System.currentTimeMillis(), uriStr))
    onSent()
    setLoading(true)
    setLoadingStatus("")

    if (providers.isEmpty() || modelIndex < 0 || modelIndex >= providers.size) {
        setLoading(false)
        appendAssistant(context, messages, "请先配置 API 提供商", isError = true)
        return
    }
    val provider = providers[modelIndex]

    scope.launch {
        try {
            if (imageUri != null) {
                setLoadingStatus("正在识别图片...")
                val imageBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                }
                val response = HttpUtils.sendImageRequest(
                    provider.requestUrl,
                    provider.modelId,
                    imageBytes,
                    provider.apiKey,
                    MyConfig.CHAT_SYSTEM_PROMPT,
                    provider.endpointType
                )
                setLoading(false)
                if (response == null) {
                    appendAssistant(context, messages, "请求失败，请检查网络或 API 配置", isError = true)
                } else {
                    val content = HttpUtils.extractContent(response, provider.endpointType)
                    appendAssistant(
                        context,
                        messages,
                        if (content.isEmpty()) "解析失败:\n$response" else content,
                        isError = content.isEmpty()
                    )
                }
            } else if (agentsMode) {
                val history = messages.map { it.role to it.content }.filter { it.first == "user" || it.first == "assistant" }
                val result = AgentOrchestrator.execute(
                    context = context,
                    provider = provider,
                    userMessage = text,
                    history = history.dropLast(1),
                    agentsMode = true
                ) { status -> setLoadingStatus(status) }
                setLoading(false)
                appendAssistant(
                    context = context,
                    messages = messages,
                    content = result.text,
                    isError = result.isError,
                    imageBase64 = result.imageBase64,
                    imageUrl = result.imageUrl,
                    toolCalls = result.toolCallsLog
                )
            } else {
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
                    appendAssistant(context, messages, "请求失败，请检查网络或 API 配置", isError = true)
                } else {
                    appendAssistant(context, messages, content)
                }
            }
        } catch (e: Exception) {
            setLoading(false)
            appendAssistant(context, messages, "发生错误: ${e.message ?: "未知错误"}", isError = true)
        }
    }
}

private fun appendAssistant(
    context: Context,
    messages: MutableList<ChatMsg>,
    content: String,
    isError: Boolean = false,
    imageBase64: String? = null,
    imageUrl: String? = null,
    toolCalls: List<String> = emptyList()
) {
    messages.add(
        ChatMsg(
            role = "assistant",
            content = content,
            isError = isError,
            time = System.currentTimeMillis(),
            imageBase64 = imageBase64,
            imageUrl = imageUrl,
            toolCalls = toolCalls
        )
    )
    if (!isError) maybeCaptureMemory(context, messages)
}

private fun maybeCaptureMemory(context: Context, messages: List<ChatMsg>) {
    val dialogMessages = messages.filter { it.role == "user" || it.role == "assistant" }
    if (dialogMessages.size < 10) return

    val userCount = dialogMessages.count { it.role == "user" }
    val prefs = context.getSharedPreferences("agent_memory_policy", Context.MODE_PRIVATE)
    val lastUserCount = prefs.getInt("last_user_count", 0)
    val lastAt = prefs.getLong("last_at", 0L)
    val now = System.currentTimeMillis()
    if (userCount - lastUserCount < 8 && now - lastAt < 30 * 60 * 1000L) return

    val lastUser = dialogMessages.lastOrNull { it.role == "user" }?.content.orEmpty()
    val candidate = extractMemoryCandidate(lastUser) ?: return
    val existing = MemoryManager.loadAll(context)
    if (existing.size >= 30 || existing.any { it.content == candidate }) return

    MemoryManager.add(context, candidate, "偏好")
    prefs.edit()
        .putInt("last_user_count", userCount)
        .putLong("last_at", now)
        .apply()
}

private fun extractMemoryCandidate(text: String): String? {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    if (normalized.length !in 8..160) return null
    val preferenceSignals = listOf("以后", "下次", "记住", "我喜欢", "我不喜欢", "叫我", "称呼我", "用中文", "简洁", "详细", "不要")
    if (preferenceSignals.none { normalized.contains(it) }) return null
    if (normalized.endsWith("?") || normalized.endsWith("？")) return null
    return normalized
}

@Composable
private fun EmptyHint(agentsMode: Boolean) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (agentsMode) "Agents 模式" else "与 AI 开始对话",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (agentsMode)
                    "AI 可以调用工具查询成绩、课表等\n也支持 LaTeX、JSON、CSV 和分子结构渲染"
                else
                    "支持 Markdown、LaTeX、JSON、CSV、图片和分子结构\n顶部可切换对话与 Agents 模式",
                color = TextHint,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))
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
