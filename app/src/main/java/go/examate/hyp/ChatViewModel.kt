package go.examate.hyp

import android.content.Context
import android.net.Uri
import go.examate.hyp.util.ChatMediaStore
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
 * 暴露不可变 StateFlow,由 Composable 用 collectAsStateWithLifecycle() 收集。
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

    /**
     * 流式文本缓冲:逐 token 追加到此,按节流窗口(约 40ms)批量刷新到 streamingText,
     * 避免每个 token 都触发一次全量 StateFlow copy 与整列表重组。
     * 仅在 viewModelScope(主线程)访问,无需同步。
     */
    private val streamBuffer = StringBuilder()
    private var lastStreamFlushAt = 0L

    private fun resetStreamBuffer() {
        streamBuffer.setLength(0)
        lastStreamFlushAt = 0L
    }

    /** 取出缓冲全部文本并清空(用于流式结束/停止时落为正式消息) */
    private fun consumeStreamText(): String {
        val text = streamBuffer.toString()
        resetStreamBuffer()
        return text
    }

    /** 刷新缓冲到 UI:force=true 立即刷新,否则距上次刷新超过节流窗口才刷新 */
    private fun flushStream(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStreamFlushAt < 40L) return
        lastStreamFlushAt = now
        val text = streamBuffer.toString()
        updateState { it.copy(streamingText = text) }
    }

    private val _uiState = MutableStateFlow(
        ChatUiState(currentSession = ChatHistoryManager.newSession())
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 本轮回复的父节点 id(发送时捕获,生成完成时用于挂载 assistant),避免生成中被改动 */
    private var pendingReplyParent: String? = null

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
     * @param imageUris 待发送图片列表(非空时走 vision 分支)
     * @param providers 已加载的 provider 列表
     * @param context 用于读取图片流 / 初始化 prefs
     */
    fun send(
        text: String,
        imageUris: List<Uri>,
        providers: List<ModelProvider>,
        context: Context
    ) {
        if (providers.isEmpty()) return
        resetStreamBuffer()

        sendJob = viewModelScope.launch {
            // 图片先持久化到私有目录(content URI 授权会过期,重启后消息里的图就没了)
            val uriStrs = withContext(Dispatchers.IO) {
                imageUris.map { uri ->
                    val raw = uri.toString()
                    ChatMediaStore.importUri(context, raw) ?: raw
                }
            }
            val now = System.currentTimeMillis()

            // 追加用户消息:挂到分支锚点或当前激活叶子;重置输入/图片/加载状态
            val branchParent = _uiState.value.branchAnchorId ?: _uiState.value.activeLeafId
            val userMsg = ChatMsg("user", text, false, now, imageUris = uriStrs, parentId = branchParent)
            pendingReplyParent = userMsg.id
            updateState {
                it.copy(
                    messages = it.messages + userMsg,
                    activeLeafId = userMsg.id,
                    branchAnchorId = null,
                    inputText = "",
                    pendingImageUris = emptyList(),
                    isLoading = true,
                    loadingStatus = "",
                    toolProgressCards = emptyList(),
                    streamingText = "",
                    currentToolSteps = emptyList()
                )
            }
            ensurePrefs(context)

            val provider = providers[_uiState.value.modelIndex.coerceIn(0, providers.size - 1)]

            try {
                if (uriStrs.isNotEmpty()) {
                    runVision(context, provider, uriStrs, text)
                } else {
                    val history = historyPairs()
                    AgentOrchestrator.executeFlow(
                        context = context, provider = provider, userMessage = text,
                        history = history.dropLast(1), agentsMode = true
                    ).collect { event -> handleAgentEvent(event, context) }
                }
            } catch (e: CancellationException) {
                val partial = consumeStreamText()
                val steps = _uiState.value.currentToolSteps
                updateState { it.copy(isLoading = false, streamingText = "", toolProgressCards = emptyList(), currentToolSteps = emptyList()) }
                if (partial.isNotBlank()) appendAssistant(partial, toolSteps = steps)
                throw e
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, streamingText = "") }
                appendAssistant("发生错误: ${e.message ?: "未知错误"}", isError = true)
            }
        }
    }

    /** 识图公共逻辑(send/regenerate 复用):多图 + 用户文本 → vision provider */
    private suspend fun runVision(context: Context, provider: ModelProvider, uriStrings: List<String>, userText: String) {
        updateState { it.copy(loadingStatus = "正在识别图片...") }
        val visionProvider = if (provider.hasCapability(ModelProvider.CAP_VISION)) provider
            else MyConfig.providersFor(context, ModelProvider.CAP_VISION).firstOrNull() ?: provider
        val imagesBytes = withContext(Dispatchers.IO) {
            uriStrings.mapNotNull { ChatMediaStore.readBytes(context, it) }
        }
        val result = HttpUtils.sendImagesRequestResult(
            visionProvider.requestUrl, visionProvider.modelId, imagesBytes,
            visionProvider.apiKey, MyConfig.CHAT_SYSTEM_PROMPT, userText, visionProvider.endpointType
        )
        updateState { it.copy(isLoading = false) }
        when (result) {
            is go.examate.hyp.util.ApiResult.Success -> {
                val content = HttpUtils.extractContent(result.data, visionProvider.endpointType)
                appendAssistant(if (content.isEmpty()) "解析失败:\n${result.data}" else content, isError = content.isEmpty())
            }
            else -> appendAssistant("请求失败: ${result.errorMessage()}", isError = true)
        }
    }

    /**
     * 生图模式直连管线:不走 Agent/function-calling,直接调生图 API。
     * 结果为 base64 或图片直链,经 appendAssistant 挂到消息树(渲染/全屏/保存链路已就绪)。
     */
    fun generateImage(context: Context, prompt: String) {
        if (prompt.isBlank()) return
        resetStreamBuffer()
        val branchParent = _uiState.value.branchAnchorId ?: _uiState.value.activeLeafId
        val userMsg = ChatMsg("user", prompt, false, System.currentTimeMillis(), parentId = branchParent)
        pendingReplyParent = userMsg.id
        updateState {
            it.copy(
                messages = it.messages + userMsg,
                activeLeafId = userMsg.id,
                branchAnchorId = null,
                inputText = "",
                isLoading = true,
                loadingStatus = "正在生成图片…",
                toolProgressCards = emptyList(),
                streamingText = "",
                currentToolSteps = emptyList()
            )
        }
        ensurePrefs(context)
        sendJob = viewModelScope.launch {
            try {
                // 未配置生图厂商:给出明确指引而非静默失败
                val hasImageGen = MyConfig.providersFor(context, ModelProvider.CAP_IMAGE_GEN).isNotEmpty()
                if (!hasImageGen) {
                    updateState { it.copy(isLoading = false, loadingStatus = "") }
                    appendAssistant("还没有可用的生图模型:到 API 设置里给支持文生图的厂商勾选「生图」能力(可另填生图模型/接口地址),完成后即可使用。", isError = true)
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    go.examate.hyp.agent.ImageTools.generate(context, prompt)
                }
                updateState { it.copy(isLoading = false, loadingStatus = "") }
                if (result == null) {
                    appendAssistant("生图失败:请检查生图模型的接口地址/模型 ID 配置或网络", isError = true)
                } else if (result.startsWith("http")) {
                    appendAssistant("", imageUrl = result)
                } else {
                    appendAssistant("", imageBase64 = result)
                }
            } catch (e: CancellationException) {
                updateState { it.copy(isLoading = false, loadingStatus = "", streamingText = "") }
                throw e
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, loadingStatus = "") }
                appendAssistant("生图失败: ${e.message ?: "未知错误"}", isError = true)
            }
        }
    }

    /** 停止当前生成(取消正在进行的请求,保留已输出的部分文本) */
    fun stopSend() {        sendJob?.cancel()
        sendJob = null
        val partial = consumeStreamText()
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
        resetStreamBuffer()

        val targetMsg = state.messages.firstOrNull { it.id == messageId } ?: return
        if (targetMsg.role != "assistant") return

        // 找该回复对应的 user 消息:优先其父,回退向前最近 user
        val userMsg = state.messages.firstOrNull { it.id == targetMsg.parentId && it.role == "user" }
            ?: run {
                val tIdx = state.messages.indexOfFirst { it.id == messageId }
                val uIdx = (0 until tIdx).reversed().firstOrNull { state.messages[it].role == "user" } ?: return
                state.messages[uIdx]
            }

        // 非破坏式:不删旧回复,新回复作为该 user 消息的兄弟分支;激活叶子指向 user 消息
        pendingReplyParent = userMsg.id
        updateState {
            it.copy(
                activeLeafId = userMsg.id,
                inputText = "",
                pendingImageUris = emptyList(),
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
        val imageUris = userMsg.imageUris.ifEmpty { listOfNotNull(userMsg.imageUriString) }

        sendJob = viewModelScope.launch {
            try {
                if (imageUris.isNotEmpty()) {
                    runVision(context, provider, imageUris, userText)
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
                val partial = consumeStreamText()
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
     * 删除指定消息(按 id)及其整棵子树(所有后代)。
     * 若激活叶子落在被删子树内,回退到被删节点的父或最后一条。
     */
    fun deleteMessage(messageId: String) {
        val state = _uiState.value
        val target = state.messages.firstOrNull { it.id == messageId } ?: return
        // 收集该消息及其所有后代(子树)
        val childrenOf = state.messages.groupBy { it.parentId }
        val toRemove = HashSet<String>()
        fun collect(id: String) {
            if (!toRemove.add(id)) return
            childrenOf[id]?.forEach { collect(it.id) }
        }
        collect(messageId)
        val remaining = state.messages.filterNot { it.id in toRemove }
        // 若激活叶子在被删子树内,回退到被删节点的父(仍存在时)或最后一条
        val newLeaf = if (state.activeLeafId in toRemove) {
            target.parentId?.takeIf { pid -> remaining.any { it.id == pid } } ?: remaining.lastOrNull()?.id
        } else state.activeLeafId
        updateState { it.copy(messages = remaining, activeLeafId = newLeaf) }
    }

    // ═════════════════════════════
    //  分支对话(消息树)
    // ═════════════════════════════

    /** 从某消息开分支:下一次发送将挂到该消息下,并把视图聚焦到该消息 */
    fun startBranchFrom(messageId: String) {
        if (_uiState.value.isLoading) return  // 生成中不允许改动树结构
        updateState { it.copy(branchAnchorId = messageId, activeLeafId = messageId) }
    }

    /** 取消待开分支状态:恢复到锤点子树的叶子,避免锤点之后的对话在 UI 上不可达 */
    fun cancelBranch() {
        updateState {
            val restored = it.branchAnchorId?.let { a -> descendToLeaf(a, it.messages) } ?: it.activeLeafId
            it.copy(branchAnchorId = null, activeLeafId = restored)
        }
    }

    /** 在某分支点(有多个子)的兄弟分支间切换 */
    fun switchBranch(parentMessageId: String, forward: Boolean) {
        if (_uiState.value.isLoading) return
        val msgs = _uiState.value.messages
        val children = msgs.filter { it.parentId == parentMessageId }.sortedBy { it.time }
        if (children.size < 2) return
        val pathIds = _uiState.value.activePath().map { it.id }.toSet()
        val curIdx = children.indexOfFirst { it.id in pathIds }.let { if (it < 0) 0 else it }
        val nextIdx = ((curIdx + if (forward) 1 else -1) + children.size) % children.size
        updateState { it.copy(activeLeafId = descendToLeaf(children[nextIdx].id, msgs)) }
    }

    /** 从某节点沿最新子链下探到叶子 */
    private fun descendToLeaf(startId: String, msgs: List<ChatMsg>): String {
        val childrenOf = msgs.groupBy { it.parentId }
        var cur = startId
        while (true) {
            val kids = childrenOf[cur]?.sortedBy { it.time }
            if (kids.isNullOrEmpty()) break
            cur = kids.last().id
        }
        return cur
    }

    /** 图谱点击聚焦某节点(下探到其子树叶子),并回到线性视图 */
    fun focusNode(messageId: String) {
        if (_uiState.value.isLoading) return
        val msgs = _uiState.value.messages
        if (msgs.none { it.id == messageId }) return
        updateState { it.copy(activeLeafId = descendToLeaf(messageId, msgs), graphMode = false) }
    }

    /** 切换知识图谱视图 */
    fun toggleGraphMode() {
        updateState { it.copy(graphMode = !it.graphMode) }
    }

    /** 关闭知识图谱视图(幂等:多次/快速点击只保持关闭,不会切回打开) */
    fun closeGraph() {
        updateState { if (it.graphMode) it.copy(graphMode = false) else it }
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
                resetStreamBuffer()
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
                streamBuffer.append(event.token)
                flushStream(false)
            }
            is go.examate.hyp.agent.AgentOrchestrator.AgentEvent.FinalTextEnd -> {
                val finalText = consumeStreamText()
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
                resetStreamBuffer()
                updateState { it.copy(streamingText = "") }
            }
        }
    }

    /** 当前激活路径转为 (role, content) 历史,仅保留 user/assistant */
    private fun historyPairs(): List<Pair<String, String>> =
        _uiState.value.activePath()
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
            val parent = pendingReplyParent ?: it.activeLeafId
            val msg = ChatMsg(
                "assistant", text, isError, System.currentTimeMillis(),
                imageBase64 = imageBase64, imageUrl = imageUrl, toolCalls = toolCalls,
                cards = cards, filePath = filePath, toolSteps = toolSteps,
                parentId = parent
            )
            it.copy(messages = it.messages + msg, activeLeafId = msg.id)
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
                pendingImageUris = emptyList(),
                isLoading = false,
                loadingStatus = "",
                toolProgressCards = emptyList(),
                activeLeafId = null,
                branchAnchorId = null,
                graphMode = false,
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

    /** 追加待发送图片(去重,最多 9 张) */
    fun addPendingImages(uriStrings: List<String>) {
        if (uriStrings.isEmpty()) return
        updateState { it.copy(pendingImageUris = (it.pendingImageUris + uriStrings).distinct().take(9)) }
    }

    /** 移除第 index 张待发送图片 */
    fun removePendingImageAt(index: Int) {
        updateState { it.copy(pendingImageUris = it.pendingImageUris.filterIndexed { i, _ -> i != index }) }
    }

    /** 清除全部待发送图片 */
    fun clearPendingImages() {
        updateState { it.copy(pendingImageUris = emptyList()) }
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
                activeLeafId = session.activeLeafId ?: session.messages.lastOrNull()?.id,
                branchAnchorId = null,
                graphMode = false,
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
        if (state.messages.isEmpty()) {
            // 会话被清空(如删光所有消息):从存储删除,避免旧内容复活
            ChatHistoryManager.deleteSession(context, state.currentSession.id)
            return
        }
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
            updateTime = System.currentTimeMillis(),
            activeLeafId = state.activeLeafId
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
    val pendingImageUris: List<String> = emptyList(),
    val showHistory: Boolean = false,
    val currentSession: ChatSession = ChatHistoryManager.newSession(),
    val historyList: List<ChatSession> = emptyList(),
    /** Agents 模式下,工具执行过程实时产出的结果卡片(余额/成绩等),显示在加载指示器下方 */
    val toolProgressCards: List<ActionCard> = emptyList(),
    /** 当前发送过程中实时累积的工具调用步骤(用于链式思考容器展示) */
    val currentToolSteps: List<ToolStep> = emptyList(),
    /** 当前聚焦的叶子消息 id(分支对话);null 表示取最后一条 */
    val activeLeafId: String? = null,
    /** 下一次发送将挂到此消息下(开分支);null 表示挂到激活叶子 */
    val branchAnchorId: String? = null,
    /** 是否处于知识图谱视图 */
    val graphMode: Boolean = false
) {
    /** 当前激活路径:从激活叶子沿 parentId 上溯到根,反转为根→叶 */
    fun activePath(): List<ChatMsg> {
        if (messages.isEmpty()) return emptyList()
        val byId = messages.associateBy { it.id }
        val leaf = byId[activeLeafId] ?: messages.last()
        val chain = ArrayList<ChatMsg>()
        val seen = HashSet<String>()
        var cur: ChatMsg? = leaf
        while (cur != null && seen.add(cur.id)) {
            chain.add(cur)
            cur = cur.parentId?.let { byId[it] }
        }
        return chain.asReversed()
    }
}
