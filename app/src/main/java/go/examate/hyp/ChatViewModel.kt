package go.examate.hyp

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.agent.AgentOrchestrator
import go.examate.hyp.agent.AgentTools
import go.examate.hyp.agent.MemoryManager
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.ActionCard
import go.examate.hyp.util.ChatHistoryManager
import go.examate.hyp.util.ChatMsg
import go.examate.hyp.util.ChatSession
import go.examate.hyp.util.HttpUtils
import go.examate.hyp.util.ToolStatus
import go.examate.hyp.util.ToolStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ChatViewModel:持有 ChatActivity 的 UI 状态,使用 MutableStateFlow + asStateFlow()
 * 暴露不可变 StateFlow,由 Composable 用 collectAsState() 收集。
 *
 * - 不持有 Context:需要 Context 的操作(send/loadHistory/deleteSession/saveCurrentSession)
 *   在方法参数里传入;PrefsManager 单例(内部持有 applicationContext,不会泄漏)会被缓存。
 * - 图片 Uri 用字符串表示,避免 ViewModel 持有 Android 框架类。
 * - 发送逻辑参考原 ChatActivity.sendMessage(图片 / agents / 普通对话三条分支)。
 */
class ChatViewModel : ViewModel() {

    /** 缓存 PrefsManager 单例(内部持有 applicationContext,不会泄漏 Activity) */
    private var prefs: PrefsManager? = null

    /** 当前发送任务(用于停止生成) */
    private var sendJob: Job? = null

    private val _uiState = MutableStateFlow(
        ChatUiState(currentSession = ChatHistoryManager.newSession())
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _uiState.value = transform(_uiState.value)
    }

    private fun ensurePrefs(context: Context): PrefsManager =
        prefs ?: PrefsManager.getInstance(context).also { prefs = it }

    // ════════════════════════════════════════════════
    //  发送消息
    // ════════════════════════════════════════════════

    /**
     * 发送一条消息。内部用 viewModelScope.launch 调用
     * AgentOrchestrator.executeFlow(流式 SSE) / HttpUtils.sendImageRequest。
     *
     * @param text 用户输入文本(已 trim)
     * @param imageUri 待发送图片(可选,非空时走 vision 分支)
     * @param providers 已加载的 provider 列表
     * @param context 用于读取图片流 / 初始化 prefs
     */
    fun send(
        text: String,
        imageUri: Uri?,
        providers: List<ModelProvider>,
        context: Context
    ) {
        if (providers.isEmpty()) return
        val uriStr = imageUri?.toString()
        val now = System.currentTimeMillis()

        // 追加用户消息并重置输入框 / 图片 / 加载状态
        updateState {
            it.copy(
                messages = it.messages + ChatMsg("user", text, false, now, uriStr),
                inputText = "",
                pendingImageUriString = null,
                isLoading = true,
                loadingStatus = "",
                toolProgressCards = emptyList(),
                streamingText = "",
                currentToolSteps = emptyList()
            )
        }
        ensurePrefs(context)

        val provider = providers[_uiState.value.modelIndex.coerceIn(0, providers.size - 1)]

        sendJob = viewModelScope.launch {
            try {
                if (imageUri != null) {
                    // 图片消息:走原有 vision 逻辑
                    updateState { it.copy(loadingStatus = "正在识别图片...") }
                    val imageBytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(imageUri)?.use { stream ->
                            stream.readBytes()
                        }
                    }
                    val result = HttpUtils.sendImageRequestResult(
                        provider.requestUrl, provider.modelId, imageBytes,
                        provider.apiKey, MyConfig.CHAT_SYSTEM_PROMPT, provider.endpointType
                    )
                    updateState { it.copy(isLoading = false) }
                    when (result) {
                        is go.examate.hyp.util.ApiResult.Success -> {
                            val content = HttpUtils.extractContent(result.data, provider.endpointType)
                            appendAssistant(
                                if (content.isEmpty()) "解析失败:\n${result.data}" else content,
                                isError = content.isEmpty()
                            )
                        }
                        else -> appendAssistant("请求失败: ${result.errorMessage()}", isError = true)
                    }
                } else {
                    // Agents 模式(Flow 渐进式 + SSE 流式输出)
                    val history = historyPairs()
                    AgentOrchestrator.executeFlow(
                        context = context,
                        provider = provider,
                        userMessage = text,
                        history = history.dropLast(1),
                        agentsMode = true
                    ).collect { event -> handleAgentEvent(event, context) }
                }
            } catch (e: CancellationException) {
                // 用户主动停止:清理加载状态,保留已生成的流式文本(如果有)
                val partial = _uiState.value.streamingText
                val steps = _uiState.value.currentToolSteps
                updateState { it.copy(isLoading = false, streamingText = "", toolProgressCards = emptyList(), currentToolSteps = emptyList()) }
                if (partial.isNotBlank()) {
                    appendAssistant(partial, toolSteps = steps)
                }
                throw e
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, streamingText = "") }
                appendAssistant("发生错误: ${e.message ?: "未知错误"}", isError = true)
            }
        }
    }

    /** 停止当前生成(取消正在进行的请求,保留已输出的部分文本) */
    fun stopSend() {
        sendJob?.cancel()
        sendJob = null
        val partial = _uiState.value.streamingText
        val steps = _uiState.value.currentToolSteps
        updateState {
            it.copy(
                isLoading = false,
                streamingText = "",
                loadingStatus = "",
                toolProgressCards = emptyList(),
                currentToolSteps = emptyList()
            )
        }
        if (partial.isNotBlank()) {
            appendAssistant(partial, toolSteps = steps)
        }
    }

    /**
     * 重新生成指定助手消息:找到该消息前最近的一条 user 消息,
     * 把该助手消息及其之后的所有消息删除,然后用相同 user 文本重新发送。
     *
     * @param messageId 要重新生成的助手消息 id
     * @param providers 当前 provider 列表(send 需要)
     * @param context 用于读取图片流 / 初始化 prefs
     */
    fun regenerate(messageId: String, providers: List<ModelProvider>, context: Context) {
        if (providers.isEmpty()) return
        val state = _uiState.value
        if (state.isLoading) return  // 正在生成时不允许重新生成

        val targetIdx = state.messages.indexOfFirst { it.id == messageId }
        if (targetIdx < 0) return
        val targetMsg = state.messages[targetIdx]
        if (targetMsg.role != "assistant") return

        // 向前找最近一条 user 消息
        val userIdx = (0 until targetIdx).reversed().firstOrNull {
            state.messages[it].role == "user"
        } ?: return
        val userMsg = state.messages[userIdx]

        // 删除该 user 消息之后的所有消息(含原助手回复),保留 user 消息
        val kept = state.messages.subList(0, userIdx + 1).toList()

        updateState {
            it.copy(
                messages = kept,
                inputText = "",
                pendingImageUriString = null,
                isLoading = true,
                loadingStatus = "",
                toolProgressCards = emptyList(),
                streamingText = "",
                currentToolSteps = emptyList()
            )
        }
        ensurePrefs(context)

        val provider = providers[_uiState.value.modelIndex.coerceIn(0, providers.size - 1)]
        val userText = userMsg.content
        val imageUri = userMsg.imageUriString?.let { Uri.parse(it) }

        sendJob = viewModelScope.launch {
            try {
                if (imageUri != null) {
                    updateState { it.copy(loadingStatus = "正在识别图片...") }
                    val imageBytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(imageUri)?.use { stream ->
                            stream.readBytes()
                        }
                    }
                    val result = HttpUtils.sendImageRequestResult(
                        provider.requestUrl, provider.modelId, imageBytes,
                        provider.apiKey, MyConfig.CHAT_SYSTEM_PROMPT, provider.endpointType
                    )
                    updateState { it.copy(isLoading = false) }
                    when (result) {
                        is go.examate.hyp.util.ApiResult.Success -> {
                            val content = HttpUtils.extractContent(result.data, provider.endpointType)
                            appendAssistant(
                                if (content.isEmpty()) "解析失败:\n${result.data}" else content,
                                isError = content.isEmpty()
                            )
                        }
                        else -> appendAssistant("请求失败: ${result.errorMessage()}", isError = true)
                    }
                } else {
                    val history = historyPairs()
                    AgentOrchestrator.executeFlow(
                        context = context,
                        provider = provider,
                        userMessage = userText,
                        history = history.dropLast(1),
                        agentsMode = true
                    ).collect { event -> handleAgentEvent(event, context) }
                }
            } catch (e: CancellationException) {
                val partial = _uiState.value.streamingText
                val steps = _uiState.value.currentToolSteps
                updateState { it.copy(isLoading = false, streamingText = "", toolProgressCards = emptyList(), currentToolSteps = emptyList()) }
                if (partial.isNotBlank()) {
                    appendAssistant(partial, toolSteps = steps)
                }
                throw e
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, streamingText = "") }
                appendAssistant("发生错误: ${e.message ?: "未知错误"}", isError = true)
            }
        }
    }

    /**
     * 删除指定消息(按 id)。删除 user 消息时,其后紧跟的 assistant 回复一并删除,
     * 避免出现孤立的助手回复。
     */
    fun deleteMessage(messageId: String) {
        val state = _uiState.value
        val idx = state.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val msg = state.messages[idx]
        var endIdx = idx + 1
        // 删除 user 消息时连带删除其后连续的 assistant 消息
        if (msg.role == "user") {
            while (endIdx < state.messages.size && state.messages[endIdx].role == "assistant") {
                endIdx++
            }
        }
        val newMessages = state.messages.toMutableList().apply {
            subList(idx, endIdx).clear()
        }
        updateState { it.copy(messages = newMessages) }
    }

    /**
     * 统一处理 AgentOrchestrator 的事件(send / regenerate 共用,避免重复代码)。
     */
    private suspend fun handleAgentEvent(
        event: go.examate.hyp.agent.AgentOrchestrator.AgentEvent,
        context: Context
    ) {
        when (event) {
            is go.examate.hyp.agent.AgentOrchestrator.AgentEvent.Thinking -> {
                updateState { it.copy(loadingStatus = event.text) }
            }
            is go.examate.hyp.agent.AgentOrchestrator.AgentEvent.ToolStarted -> {
                updateState {
                    it.copy(
                        loadingStatus = event.label,
                        currentToolSteps = it.currentToolSteps + ToolStep(
                            name = event.name,
                            label = event.label,
                            status = ToolStatus.RUNNING
                        )
                    )
                }
            }
            is go.examate.hyp.agent.AgentOrchestrator.AgentEvent.ToolDone -> {
                val card = event.card
                if (card != null) {
                    updateState { it.copy(toolProgressCards = it.toolProgressCards + card) }
                }
                updateState {
                    val updated = it.currentToolSteps.map { step ->
                        if (step.name == event.name && step.status == ToolStatus.RUNNING) {
                            step.copy(status = ToolStatus.DONE, output = event.output, card = card)
                        } else step
                    }
                    it.copy(loadingStatus = "${event.name} 完成", currentToolSteps = updated)
                }
            }
            is go.examate.hyp.agent.AgentOrchestrator.AgentEvent.FinalReply -> {
                val steps = _uiState.value.currentToolSteps
                updateState {
                    it.copy(
                        isLoading = false,
                        toolProgressCards = emptyList(),
                        streamingText = "",
                        currentToolSteps = emptyList()
                    )
                }
                appendAssistant(
                    event.text,
                    isError = event.isError,
                    imageBase64 = event.imageBase64,
                    imageUrl = event.imageUrl,
                    cards = event.cards,
                    filePath = event.filePath,
                    toolSteps = steps
                )
                if (!event.isError) maybeCaptureMemory(context)
            }
            is go.examate.hyp.agent.AgentOrchestrator.AgentEvent.FinalTextToken -> {
                updateState { it.copy(streamingText = it.streamingText + event.token) }
            }
            is go.examate.hyp.agent.AgentOrchestrator.AgentEvent.FinalTextEnd -> {
                val finalText = _uiState.value.streamingText
                val steps = _uiState.value.currentToolSteps
                updateState {
                    it.copy(
                        streamingText = "",
                        isLoading = false,
                        toolProgressCards = emptyList(),
                        currentToolSteps = emptyList()
                    )
                }
                if (finalText.isNotEmpty()) {
                    appendAssistant(
                        finalText,
                        imageBase64 = event.imageBase64,
                        imageUrl = event.imageUrl,
                        cards = event.cards,
                        filePath = event.filePath,
                        toolSteps = steps
                    )
                }
                maybeCaptureMemory(context)
            }
            is go.examate.hyp.agent.AgentOrchestrator.AgentEvent.ClearStream -> {
                updateState { it.copy(streamingText = "") }
            }
        }
    }

    /** 当前消息列表转为 (role, content) 历史,仅保留 user/assistant */
    private fun historyPairs(): List<Pair<String, String>> =
        _uiState.value.messages
            .map { it.role to it.content }
            .filter { it.first == "user" || it.first == "assistant" }

    private fun appendAssistant(
        text: String,
        isError: Boolean = false,
        imageBase64: String? = null,
        imageUrl: String? = null,
        toolCalls: List<String> = emptyList(),
        cards: List<ActionCard> = emptyList(),
        filePath: String? = null,
        toolSteps: List<ToolStep> = emptyList()
    ) {
        updateState {
            it.copy(
                messages = it.messages + ChatMsg(
                    "assistant", text, isError, System.currentTimeMillis(),
                    null, imageBase64, imageUrl, toolCalls, cards, filePath, toolSteps
                )
            )
        }
    }

    /**
     * 记忆捕获:在一段非错误回复后,按策略从最近的用户消息中抽取长期偏好记忆。
     * 涉及文件 I/O(JSON 读写),在 viewModelScope 内调用,不阻塞 UI。
     */
    private fun maybeCaptureMemory(context: Context) {
        val prefsManager = ensurePrefs(context)
        if (!prefsManager.isMemoryEnabled) return
        val dialogMessages = _uiState.value.messages
            .filter { it.role == "user" || it.role == "assistant" }
        if (dialogMessages.size < 10) return

        val userCount = dialogMessages.count { it.role == "user" }
        val policyPrefs = context.getSharedPreferences("agent_memory_policy", Context.MODE_PRIVATE)
        val lastUserCount = policyPrefs.getInt("last_user_count", 0)
        val lastAt = policyPrefs.getLong("last_at", 0L)
        val now = System.currentTimeMillis()
        if (userCount - lastUserCount < 8 && now - lastAt < 30 * 60 * 1000L) return

        val lastUser = dialogMessages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val candidate = extractMemoryCandidate(lastUser) ?: return
        val existing = MemoryManager.loadAll(context)
        if (existing.size >= 30 || existing.any { it.content == candidate }) return

        MemoryManager.add(context, candidate, "偏好")
        policyPrefs.edit()
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

    // ════════════════════════════════════════════════
    //  会话 / 输入 / 模型 / 模式
    // ════════════════════════════════════════════════

    /** 清空消息开启新会话 */
    fun clearMessages() {
        updateState {
            it.copy(
                messages = emptyList(),
                currentSession = ChatHistoryManager.newSession(),
                inputText = "",
                pendingImageUriString = null,
                isLoading = false,
                loadingStatus = "",
                toolProgressCards = emptyList(),
                showHistory = false
            )
        }
    }

    /** 关闭历史面板 */
    fun dismissHistory() {
        updateState { it.copy(showHistory = false) }
    }

    /** 切换模型(更新选中的 provider 索引) */
    fun loadModelIndex(index: Int) {
        updateState { it.copy(modelIndex = index) }
    }

    /** 更新输入框文本 */
    fun updateInputText(text: String) {
        updateState { it.copy(inputText = text) }
    }

    /** 设置待发送图片(Uri 字符串) */
    fun setPendingImage(uriString: String?) {
        updateState { it.copy(pendingImageUriString = uriString) }
    }

    /** 清除待发送图片 */
    fun clearPendingImage() {
        updateState { it.copy(pendingImageUriString = null) }
    }

    // ════════════════════════════════════════════════
    //  历史会话
    // ════════════════════════════════════════════════

    /** 加载历史列表并打开历史面板 */
    fun loadHistory(context: Context) {
        ensurePrefs(context)
        val list = ChatHistoryManager.loadAll(context)
        updateState { it.copy(historyList = list, showHistory = true) }
    }

    /** 选择历史会话,载入其消息并关闭历史面板 */
    fun selectSession(session: ChatSession) {
        updateState {
            it.copy(
                messages = session.messages,
                currentSession = session,
                showHistory = false
            )
        }
    }

    /** 删除历史会话并刷新列表 */
    fun deleteSession(context: Context, id: String) {
        ChatHistoryManager.deleteSession(context, id)
        updateState { it.copy(historyList = ChatHistoryManager.loadAll(context)) }
    }

    /** 保存当前会话(标题取首条用户消息前 20 字) */
    fun saveCurrentSession(context: Context) {
        val state = _uiState.value
        if (state.messages.isEmpty()) return
        val title = if (state.currentSession.title == "新对话" &&
            state.messages.any { it.role == "user" }
        ) {
            ChatHistoryManager.makeTitle(state.messages.first { it.role == "user" }.content)
        } else {
            state.currentSession.title
        }
        val updated = state.currentSession.copy(
            messages = state.messages,
            title = title,
            updateTime = System.currentTimeMillis()
        )
        updateState { it.copy(currentSession = updated) }
        ChatHistoryManager.saveSession(context, updated)
    }
}

/**
 * ChatActivity 的 UI 状态
 *
 * @param messages 消息列表
 * @param inputText 输入框文本
 * @param isLoading 是否正在加载
 * @param loadingStatus 加载状态文案(如 "正在思考..." / "正在调用工具: xxx")
 * @param modelIndex 当前选中的 provider 索引
 * @param streamingText SSE 流式输出中的文本(逐字追加,流式结束后转为正式消息)
 * @param pendingImageUriString 待发送图片的 Uri 字符串(用字符串避免持有 Android 框架类)
 * @param showHistory 是否显示历史面板
 * @param currentSession 当前会话
 * @param historyList 历史会话列表
 */
data class ChatUiState(
    val messages: List<ChatMsg> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val loadingStatus: String = "",
    val modelIndex: Int = 0,
    val streamingText: String = "",
    val pendingImageUriString: String? = null,
    val showHistory: Boolean = false,
    val currentSession: ChatSession = ChatHistoryManager.newSession(),
    val historyList: List<ChatSession> = emptyList(),
    /** Agents 模式下,工具执行过程实时产出的结果卡片(余额/成绩等),显示在加载指示器下方 */
    val toolProgressCards: List<ActionCard> = emptyList(),
    /** 当前发送过程中实时累积的工具调用步骤(用于链式思考容器展示) */
    val currentToolSteps: List<ToolStep> = emptyList()
)
