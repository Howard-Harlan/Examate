package go.examate.hyp

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.agent.AgentOrchestrator
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.ChatHistoryManager
import go.examate.hyp.util.ChatMsg
import go.examate.hyp.util.ChatSession
import go.examate.hyp.util.HttpUtils
import kotlinx.coroutines.Dispatchers
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
     * AgentOrchestrator.execute / simpleChat / HttpUtils.sendImageRequest。
     *
     * @param text 用户输入文本(已 trim)
     * @param imageUri 待发送图片(可选,非空时走 vision 分支)
     * @param providers 已加载的 provider 列表
     * @param agentsMode 是否启用 agents 模式
     * @param context 用于读取图片流 / 初始化 prefs
     */
    fun send(
        text: String,
        imageUri: Uri?,
        providers: List<ModelProvider>,
        agentsMode: Boolean,
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
                loadingStatus = ""
            )
        }
        ensurePrefs(context)

        val provider = providers[_uiState.value.modelIndex.coerceIn(0, providers.size - 1)]

        viewModelScope.launch {
            try {
                if (imageUri != null) {
                    // 图片消息:走原有 vision 逻辑
                    updateState { it.copy(loadingStatus = "正在识别图片...") }
                    val imageBytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(imageUri)?.use { stream ->
                            stream.readBytes()
                        }
                    }
                    val response = HttpUtils.sendImageRequest(
                        provider.requestUrl, provider.modelId, imageBytes,
                        provider.apiKey, MyConfig.CHAT_SYSTEM_PROMPT, provider.endpointType
                    )
                    updateState { it.copy(isLoading = false) }
                    if (response == null) {
                        appendAssistant("请求失败，请检查网络或 API 配置", isError = true)
                    } else {
                        val content = HttpUtils.extractContent(response, provider.endpointType)
                        appendAssistant(
                            if (content.isEmpty()) "解析失败:\n$response" else content,
                            isError = content.isEmpty()
                        )
                    }
                } else if (agentsMode) {
                    // Agents 模式:使用 AgentOrchestrator(支持工具调用)
                    val history = historyPairs()
                    val result = AgentOrchestrator.execute(
                        context = context,
                        provider = provider,
                        userMessage = text,
                        history = history.dropLast(1), // 去掉刚加的 user 消息(orchestrator 会加)
                        agentsMode = true
                    ) { status -> updateState { it.copy(loadingStatus = status) } }
                    updateState { it.copy(isLoading = false) }
                    appendAssistant(
                        result.text,
                        isError = result.isError,
                        imageBase64 = result.imageBase64,
                        imageUrl = result.imageUrl,
                        toolCalls = result.toolCallsLog
                    )
                } else {
                    // 普通对话模式
                    updateState { it.copy(loadingStatus = "正在思考...") }
                    val history = historyPairs()
                    val content = AgentOrchestrator.simpleChat(
                        context = context,
                        provider = provider,
                        userMessage = text,
                        history = history.dropLast(1),
                        systemPrompt = MyConfig.CHAT_SYSTEM_PROMPT
                    )
                    updateState { it.copy(isLoading = false) }
                    if (content.isNullOrEmpty()) {
                        appendAssistant("请求失败，请检查网络或 API 配置", isError = true)
                    } else {
                        appendAssistant(content)
                    }
                }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false) }
                appendAssistant("发生错误: ${e.message ?: "未知错误"}", isError = true)
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
        toolCalls: List<String> = emptyList()
    ) {
        updateState {
            it.copy(
                messages = it.messages + ChatMsg(
                    "assistant", text, isError, System.currentTimeMillis(),
                    null, imageBase64, imageUrl, toolCalls
                )
            )
        }
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
                loadingStatus = ""
            )
        }
    }

    /** 切换模型(更新选中的 provider 索引) */
    fun loadModelIndex(index: Int) {
        updateState { it.copy(modelIndex = index) }
    }

    /** 更新输入框文本 */
    fun updateInputText(text: String) {
        updateState { it.copy(inputText = text) }
    }

    /** 切换 agents 模式(同时持久化到 PrefsManager) */
    fun toggleAgentsMode() {
        val newMode = !_uiState.value.agentsMode
        updateState { it.copy(agentsMode = newMode) }
        prefs?.isAgentsMode = newMode
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
 * @param agentsMode 是否启用 agents 模式
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
    val agentsMode: Boolean = false,
    val pendingImageUriString: String? = null,
    val showHistory: Boolean = false,
    val currentSession: ChatSession = ChatHistoryManager.newSession(),
    val historyList: List<ChatSession> = emptyList()
)
