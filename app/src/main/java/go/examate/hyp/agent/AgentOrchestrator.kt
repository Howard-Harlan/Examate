package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.ApiResult
import go.examate.hyp.util.HttpUtils
import go.examate.hyp.util.HttpUtils.SseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Agent 编排器:管理多步推理 + function calling 循环
 *
 * 采用 prompt-based function calling(兼容所有 API,不依赖原生 tools 支持):
 * 1. 系统提示词中描述工具及调用格式
 * 2. AI 在回复中用 <tool_call>{"name":"...","arguments":{...}}</tool_call> 标记调用
 * 3. app 解析标记,执行工具,将结果以 user 消息回传
 * 4. 重复直到无工具调用(最多 5 轮)
 *
 * Agents 模式:开启后允许 AI 多步推理,依次调用多个工具收集信息后综合回答
 */
object AgentOrchestrator {

    private const val TAG = "AgentOrchestrator"
    private const val MAX_ITERATIONS = 5
    private const val SUB_MAX_ITERATIONS = 3
    private val RENDERING_PROMPT =
        "\n\n## 渲染标记\n" +
            "数学:行内 ${'$'}...${'$'},块级 ${'$'}${'$'}...${'$'}${'$'}。JSON/CSV/SMILES 放入对应代码块。\n" +
            "化学:${'$'}smiles{CCO} 内联或 ```smiles 代码块。\n"

    /** Agent 执行过程中的实时事件(Flow 渐进式输出) */
    sealed class AgentEvent {
        /** AI 开始思考 / 规划调用哪些工具 */
        data class Thinking(val text: String) : AgentEvent()
        /** 某个工具开始执行 */
        data class ToolStarted(val name: String, val label: String) : AgentEvent()
        /** 某个工具执行完毕,附带结果文本、可选行动卡片和可选文件路径(用于预览) */
        data class ToolDone(
            val name: String,
            val output: String,
            val card: go.examate.hyp.util.ActionCard?,
            val filePath: String? = null
        ) : AgentEvent()
        /** 最终回复(AI 文本 + 卡片) */
        data class FinalReply(
            val text: String,
            val imageBase64: String? = null,
            val imageUrl: String? = null,
            val cards: List<go.examate.hyp.util.ActionCard> = emptyList(),
            val isError: Boolean = false,
            val filePath: String? = null
        ) : AgentEvent()
        /** 最终回复的流式文本 token(SSE 逐字输出) */
        data class FinalTextToken(val token: String) : AgentEvent()
        /** 最终回复的流式输出结束,附带卡片和图片信息 */
        data class FinalTextEnd(
            val cards: List<go.examate.hyp.util.ActionCard> = emptyList(),
            val imageBase64: String? = null,
            val imageUrl: String? = null,
            val filePath: String? = null
        ) : AgentEvent()
        /** 清除之前流式输出的文本(检测到工具调用时,清除已显示的部分文本) */
        data object ClearStream : AgentEvent()
    }

    /** Agent 回复结果 */
    data class AgentResponse(
        val text: String,           // 给用户显示的文本(markdown)
        val imageBase64: String? = null,  // 生成的图片(base64)
        val imageUrl: String? = null,     // 生成的图片(URL)
        val toolCallsLog: List<String> = emptyList(),  // 工具调用日志
        val cards: List<go.examate.hyp.util.ActionCard> = emptyList(), // 行动卡片
        val isError: Boolean = false,
        val filePath: String? = null  // 生成的文件路径(HTML/MD等,用于 UI 预览)
    )

    /**
     * 执行一次完整的 Agent 对话(可能包含多轮工具调用)
     */
    suspend fun execute(
        context: Context,
        provider: ModelProvider,
        userMessage: String,
        history: List<Pair<String, String>>,
        agentsMode: Boolean,
        onToolCall: (String) -> Unit = {}
    ): AgentResponse = withContext(Dispatchers.IO) {
        val prefs = PrefsManager.getInstance(context)
        val campusLoggedIn = CampusRepository.getInstance().isLoggedIn &&
                prefs.campusUsername.isNotEmpty()

        // 注册工具到 ToolRegistry(幂等,重复调用安全)
        ToolRegistry.registerDefaults(context)

        // ★ 每次都发完整系统提示词(含工具列表+skills+memory),参考 rikkahub 依赖服务端 prompt caching。
        // 之前用精简提示词会导致 AI "忘记"有哪些工具可用(如查成绩不知道调 get_grades)。
        val systemPrompt = AgentTools.buildSystemPrompt(campusLoggedIn, agentsMode) +
                SkillManager.buildSkillsPrompt(context) +
                (if (prefs.isMemoryEnabled) MemoryManager.buildMemoryPrompt(context) else "") +
                RENDERING_PROMPT

        // 构建对话上下文(历史 + 当前消息) — 保留较多历史以维持工具调用连续性
        val messages = mutableListOf<Pair<String, String>>()
        history.takeLast(10).forEach { (role, content) ->
            messages.add(role to content)
        }
        messages.add("user" to userMessage)

        val toolCallLog = mutableListOf<String>()
        var imageBase64: String? = null
        var imageUrl: String? = null
        var filePath: String? = null

        for (iteration in 0 until MAX_ITERATIONS) {
            // 发送请求(纯 prompt-based,不带原生 tools 参数,兼容性最好)
            val result = HttpUtils.sendChatWithHistoryResult(
                provider.requestUrl, provider.modelId, messages,
                provider.apiKey, systemPrompt, provider.enableThinking, provider.endpointType
            )

            val response = when (result) {
                is go.examate.hyp.util.ApiResult.Success -> result.data
                else -> {
                    Log.e(TAG, "请求失败: ${result.errorMessage()}")
                    return@withContext AgentResponse(
                        "请求失败: ${result.errorMessage()}",
                        isError = true, toolCallsLog = toolCallLog
                    )
                }
            }

            // 提取文本内容
            val rawText = HttpUtils.extractContent(response, provider.endpointType)

            if (rawText.isEmpty()) {
                Log.e(TAG, "AI 回复为空, response=${response.take(500)}")
                return@withContext AgentResponse(
                    "AI 回复为空,请检查 API 配置或更换模型后重试",
                    isError = true, toolCallsLog = toolCallLog
                )
            }

            // 解析 <tool_call> 标记
            val toolCalls = AgentTools.extractToolCalls(rawText)
            val displayText = AgentTools.stripToolCalls(rawText)

            if (toolCalls.isEmpty()) {
                // 没有工具调用,返回最终回复
                val inferred = AgentTools.inferCampusCards(displayText, campusLoggedIn)
                return@withContext AgentResponse(displayText, imageBase64, imageUrl, toolCallLog, inferred, false, filePath)
            }

            // 有工具调用:先把 AI 的完整回复(含 <tool_call>)加入上下文
            messages.add("assistant" to rawText)

            // 执行每个工具调用,收集结果
            for (call in toolCalls) {
                val callDesc = "${call.name}(${call.arguments})"
                toolCallLog.add(callDesc)
                Log.d(TAG, "执行工具: $callDesc")
                onToolCall("正在调用: ${call.name}...")

                val result = AgentTools.execute(context, call)

                if (result.isImage && result.imageData != null) {
                    if (AgentTools.isBase64Image(result.imageData)) {
                        imageBase64 = result.imageData
                    } else {
                        imageUrl = result.imageData
                    }
                    messages.add("user" to "[工具结果] ${call.name}: 图片已生成")
                } else {
                    if (result.filePath != null) filePath = result.filePath
                    val resultText = if (result.success) result.output else "工具执行失败: ${result.output}"
                    messages.add("user" to "[工具结果] ${call.name}:\n$resultText")
                }
            }

            // 继续下一轮,AI 会基于工具结果继续回复
        }

        // 达到最大迭代次数,做最后一次请求让 AI 总结
        val lastResult = HttpUtils.sendChatWithHistoryResult(
            provider.requestUrl, provider.modelId, messages,
            provider.apiKey, systemPrompt, provider.enableThinking, provider.endpointType
        )
        val finalText = when (lastResult) {
            is go.examate.hyp.util.ApiResult.Success ->
                AgentTools.stripToolCalls(HttpUtils.extractContent(lastResult.data, provider.endpointType))
            else -> "已达到最大工具调用次数(${lastResult.errorMessage()})"
        }

        AgentResponse(finalText, imageBase64, imageUrl, toolCallLog, AgentTools.inferCampusCards(finalText, campusLoggedIn), false, filePath)
    }

    // 工具并行执行的结果包装
    private data class ToolExecResult(
        val call: AgentTools.ToolCall,
        val result: AgentTools.ToolResult,
        val output: String,
        val card: go.examate.hyp.util.ActionCard?
    )

    /**
     * 执行一次完整的 Agent 对话(Flow 渐进式输出 + SSE 流式文本)。
     *
     * 相比 execute(),本函数在工具调用过程中持续 emit AgentEvent,
     * 让 ChatViewModel 可以实时更新 UI(显示工具状态气泡 + 结果卡片),
     * 最后一轮(无工具调用时)用 SSE 流式逐字输出文本,体验更自然。
     * 多个工具并行执行(用 coroutineScope + async),不再串行等待。
     */
    suspend fun executeFlow(
        context: Context,
        provider: ModelProvider,
        userMessage: String,
        history: List<Pair<String, String>>,
        agentsMode: Boolean
    ): Flow<AgentEvent> = flow {
        val prefs = PrefsManager.getInstance(context)
        val campusLoggedIn = CampusRepository.getInstance().isLoggedIn &&
                prefs.campusUsername.isNotEmpty()

        // 注册工具到 ToolRegistry(幂等)
        ToolRegistry.registerDefaults(context)

        // ★ 原生 function calling 主路径(chat/anthropic 格式):工具通过 API tools 数组注入,
        //   AI 不会忘记工具格式/用错工具名,工具调用阶段非流式(快),最终文本假流式 emit。
        //   参考 rikkahub 的原生 function calling 实现。
        val nativeTools = ToolSchemaBuilder.buildToolsJson(
            provider.endpointType, ToolRegistry.registeredToolNames()
        )
        if (nativeTools != null) {
            emitAll(executeFlowNative(
                context, provider, userMessage, history, nativeTools, campusLoggedIn, prefs
            ))
            return@flow
        }

        // 降级:prompt-based function calling(responses 等不支持原生 tools 的格式)
        // ★ 每次都发完整系统提示词(含工具列表+skills+memory),参考 rikkahub 依赖服务端 prompt caching
        val systemPrompt = AgentTools.buildSystemPrompt(campusLoggedIn, agentsMode) +
                SkillManager.buildSkillsPrompt(context) +
                (if (prefs.isMemoryEnabled) MemoryManager.buildMemoryPrompt(context) else "") +
                RENDERING_PROMPT

        val messages = mutableListOf<Pair<String, String>>()
        history.takeLast(10).forEach { (role, content) ->
            messages.add(role to content)
        }
        messages.add("user" to userMessage)

        var imageBase64: String? = null
        var imageUrl: String? = null
        var filePath: String? = null

        for (iteration in 0 until MAX_ITERATIONS) {
            // ★ 流式请求:逐字收集 token,同时实时透出给 UI
            val fullText = StringBuilder()
            var toolCallSeen = false
            var streamError: String? = null

            HttpUtils.sendChatWithHistoryStreamFlow(
                provider.requestUrl, provider.modelId, messages,
                provider.apiKey, systemPrompt, provider.enableThinking, provider.endpointType
            ).collect { sse ->
                when (sse) {
                    is HttpUtils.SseEvent.Token -> {
                        fullText.append(sse.text)
                        if (!toolCallSeen) {
                            // 检测到 <tool_call 标记 → 停止透出,清除已显示文本
                            if (fullText.contains("<tool_call")) {
                                toolCallSeen = true
                                emit(AgentEvent.ClearStream)
                            } else {
                                emit(AgentEvent.FinalTextToken(sse.text))
                            }
                        }
                    }
                    is HttpUtils.SseEvent.ThinkToken -> { /* 思考过程暂不透出 */ }
                    is HttpUtils.SseEvent.Error -> { streamError = sse.message }
                    HttpUtils.SseEvent.Done -> { /* 流结束后统一处理 */ }
                }
            }

            if (streamError != null) {
                emit(AgentEvent.FinalReply(streamError!!, isError = true))
                return@flow
            }

            val rawText = fullText.toString()

            if (rawText.isEmpty()) {
                emit(AgentEvent.FinalReply(
                    "AI 回复为空,请检查 API 配置或更换模型后重试",
                    isError = true
                ))
                return@flow
            }

            val toolCalls = AgentTools.extractToolCalls(rawText)
            val delegates = AgentTools.extractDelegates(rawText)
            val displayText = AgentTools.stripToolCalls(AgentTools.stripDelegates(rawText))

            if (toolCalls.isEmpty() && delegates.isEmpty()) {
                val inferred = AgentTools.inferCampusCards(displayText, campusLoggedIn)
                if (toolCallSeen) {
                    // 误判:曾清除流式文本但实际无工具调用,回退为整条回复
                    emit(AgentEvent.FinalReply(displayText, imageBase64, imageUrl, inferred, false, filePath))
                } else {
                    // 正常流式结束
                    emit(AgentEvent.FinalTextEnd(inferred, imageBase64, imageUrl, filePath))
                }
                return@flow
            }

            // 有工具调用或子任务派发:确保流式文本已清除
            if (!toolCallSeen) emit(AgentEvent.ClearStream)

            // 把 AI 的完整回复(含 <tool_call>/<delegate>)加入对话上下文
            messages.add("assistant" to rawText)

            // ★ 先透出工具 "开始执行" 事件
            toolCalls.forEach { call ->
                val label = toolDisplayLabel(call.name)
                emit(AgentEvent.ToolStarted(call.name, label))
            }

            // ★ 透出子任务 "开始执行" 事件
            delegates.forEachIndexed { idx, d ->
                emit(AgentEvent.ToolStarted("delegate_task", "派发子任务 ${idx + 1}: ${d.task.take(20)}..."))
            }

            // ★ 工具并行执行(全部在 IO 线程跑,互不阻塞)
            val toolResults = coroutineScope {
                toolCalls.map { call ->
                    async(Dispatchers.IO) {
                        val result = AgentTools.execute(context, call)
                        val output = if (result.success) result.output
                            else "执行失败: ${result.output}"
                        val card = buildResultCard(call.name, call.arguments, result)
                        ToolExecResult(call, result, output, card)
                    }
                }.map { it.await() }
            }

            // ★ 按工具调用顺序透出 "完成" 事件(Flow 线程,非 IO)
            toolResults.forEach { tr ->
                emit(AgentEvent.ToolDone(tr.call.name, tr.output, tr.card, tr.result.filePath))

                // 生图结果缓存
                if (tr.result.isImage && tr.result.imageData != null) {
                    if (AgentTools.isBase64Image(tr.result.imageData)) {
                        imageBase64 = tr.result.imageData
                    } else {
                        imageUrl = tr.result.imageData
                    }
                    messages.add("user" to "[工具结果] ${tr.call.name}: 图片已生成")
                } else {
                    if (tr.result.filePath != null) filePath = tr.result.filePath
                    messages.add("user" to "[工具结果] ${tr.call.name}:\n${compressToolResult(tr.output)}")
                }
            }

            // ★ 子任务串行派发执行(每个子任务独立 API 调用 + 简化工具循环)
            delegates.forEachIndexed { idx, d ->
                val subResult = executeSubTask(context, provider, d, messages)
                emit(AgentEvent.ToolDone("delegate_task", "子任务 ${idx + 1} 完成: ${subResult.take(200)}", null))
                messages.add("user" to "[子任务结果] ${d.task}:\n$subResult")
            }
        }

        // 达到最大迭代次数,最后一次流式请求让 AI 总结
        val fullText = StringBuilder()
        var streamError: String? = null

        HttpUtils.sendChatWithHistoryStreamFlow(
            provider.requestUrl, provider.modelId, messages,
            provider.apiKey, systemPrompt, provider.enableThinking, provider.endpointType
        ).collect { sse ->
            when (sse) {
                is HttpUtils.SseEvent.Token -> {
                    fullText.append(sse.text)
                    if (!fullText.contains("<tool_call")) {
                        emit(AgentEvent.FinalTextToken(sse.text))
                    }
                }
                is HttpUtils.SseEvent.ThinkToken -> {}
                is HttpUtils.SseEvent.Error -> { streamError = sse.message }
                HttpUtils.SseEvent.Done -> {}
            }
        }

        if (streamError != null) {
            emit(AgentEvent.FinalReply("已达到最大工具调用次数($streamError)", isError = true))
            return@flow
        }

        val finalText = AgentTools.stripToolCalls(fullText.toString())
        val inferred = AgentTools.inferCampusCards(finalText, campusLoggedIn)
        emit(AgentEvent.FinalTextEnd(inferred, imageBase64, imageUrl, filePath))
    }

    /**
     * 原生 function calling 执行路径(OpenAI tools / Anthropic tool_use)。
     *
     * 工具通过 API tools 数组注入,AI 直接返回结构化 tool_calls,无需记忆 `<tool_call>` 文本格式,
     * 不会用错工具名。工具调用轮非流式(更快,省 SSE 开销),最终文本假流式分片 emit 保持视觉流畅。
     *
     * 参考 rikkahub 的 GenerationHandler:每轮重新拼接 system prompt,非流式请求 + extractNativeToolCalls。
     */
    private suspend fun executeFlowNative(
        context: Context,
        provider: ModelProvider,
        userMessage: String,
        history: List<Pair<String, String>>,
        toolsJson: String,
        campusLoggedIn: Boolean,
        prefs: PrefsManager
    ): Flow<AgentEvent> = flow {
        // 原生 FC:工具描述已通过 tools 数组注入,system prompt 只保留优先级指引和回复规范
        val systemPrompt = AgentTools.buildSystemPromptNative(campusLoggedIn) +
                SkillManager.buildSkillsPrompt(context) +
                (if (prefs.isMemoryEnabled) MemoryManager.buildMemoryPrompt(context) else "") +
                RENDERING_PROMPT

        // 构建 FuncMessage 列表(支持 tool_calls / tool_result 结构)
        val messages = mutableListOf<FuncMessage>()
        history.takeLast(10).forEach { (role, content) ->
            messages.add(FuncMessage.Text(role, content))
        }
        messages.add(FuncMessage.Text("user", userMessage))

        var imageBase64: String? = null
        var imageUrl: String? = null
        var filePath: String? = null

        for (iteration in 0 until MAX_ITERATIONS) {
            // 非流式请求(带 tools):比流式快,SSE 开销小,工具调用结果结构化
            val result = HttpUtils.sendChatWithToolsResult(
                provider.requestUrl, provider.modelId, messages,
                provider.apiKey, systemPrompt, provider.enableThinking,
                provider.endpointType, toolsJson
            )
            val response = when (result) {
                is ApiResult.Success -> result.data
                else -> {
                    emit(AgentEvent.FinalReply(
                        "请求失败: ${result.errorMessage()}", isError = true
                    ))
                    return@flow
                }
            }

            val parsed = HttpUtils.extractNativeToolCalls(response, provider.endpointType)

            if (parsed.toolCalls.isEmpty()) {
                // 无工具调用:最终回复,假流式分片 emit(快速显示,无额外请求)
                val text = parsed.text
                if (text.isEmpty()) {
                    emit(AgentEvent.FinalReply("AI 回复为空,请重试", isError = true))
                    return@flow
                }
                emitFinalTextStreaming(text)
                val inferred = AgentTools.inferCampusCards(text, campusLoggedIn)
                emit(AgentEvent.FinalTextEnd(inferred, imageBase64, imageUrl, filePath))
                return@flow
            }

            // 有工具调用:把 assistant 的 tool_calls 加入上下文
            messages.add(FuncMessage.AssistantToolCall(parsed.text, parsed.toolCalls))

            // 透出 "开始执行" 事件
            parsed.toolCalls.forEach { tc ->
                emit(AgentEvent.ToolStarted(tc.name, toolDisplayLabel(tc.name)))
            }

            // 并行执行工具
            val toolResults = coroutineScope {
                parsed.toolCalls.map { tc ->
                    async(Dispatchers.IO) {
                        val args = try { JSONObject(tc.arguments) } catch (_: Exception) { JSONObject() }
                        val call = AgentTools.ToolCall(tc.name, args)
                        val r = AgentTools.execute(context, call)
                        val output = if (r.success) r.output else "执行失败: ${r.output}"
                        val card = buildResultCard(tc.name, args, r)
                        Triple(tc, r, output to card)
                    }
                }.map { it.await() }
            }

            // 透出 "完成" 事件 + 把 tool_result 加入上下文
            toolResults.forEach { (tc, r, pair) ->
                val (output, card) = pair
                emit(AgentEvent.ToolDone(tc.name, output, card, r.filePath))
                if (r.isImage && r.imageData != null) {
                    if (AgentTools.isBase64Image(r.imageData)) imageBase64 = r.imageData
                    else imageUrl = r.imageData
                    messages.add(FuncMessage.ToolResult(tc.id, "[工具结果] ${tc.name}: 图片已生成"))
                } else {
                    if (r.filePath != null) filePath = r.filePath
                    messages.add(FuncMessage.ToolResult(tc.id,
                        "[工具结果] ${tc.name}:\n${compressToolResult(output)}"))
                }
            }
        }

        // 达到最大迭代:最后一次请求(tools=null 禁止再调工具)让 AI 总结
        val lastResult = HttpUtils.sendChatWithToolsResult(
            provider.requestUrl, provider.modelId, messages,
            provider.apiKey, systemPrompt, provider.enableThinking,
            provider.endpointType, null
        )
        val lastText = when (lastResult) {
            is ApiResult.Success -> HttpUtils.extractNativeToolCalls(lastResult.data, provider.endpointType).text
            else -> "已达到最大工具调用次数(${lastResult.errorMessage()})"
        }
        emitFinalTextStreaming(lastText)
        val inferred = AgentTools.inferCampusCards(lastText, campusLoggedIn)
        emit(AgentEvent.FinalTextEnd(inferred, imageBase64, imageUrl, filePath))
    }

    /** 假流式:把完整文本分片 emit FinalTextToken,模拟逐字输出效果(无额外网络请求) */
    private suspend fun FlowCollector<AgentEvent>.emitFinalTextStreaming(text: String) {
        if (text.isEmpty()) return
        var i = 0
        val chunkSize = 3
        while (i < text.length) {
            val end = (i + chunkSize).coerceAtMost(text.length)
            emit(AgentEvent.FinalTextToken(text.substring(i, end)))
            i = end
            // 微小延迟让 UI 有"逐字"感,但不显著增加总时间
            if (text.length > 60) delay(8)
        }
    }

    /** 工具名 → 中文标签(委托给 ToolRegistry) */
    private fun toolDisplayLabel(name: String): String = ToolRegistry.getDisplayLabel(name)

    /** 压缩工具结果文本(超过阈值时截断,减少上下文 token 消耗)
     *  参考 rikkahub: 32KB 截断,既能容纳完整查询结果,又防止超长输出撑爆上下文 */
    private fun compressToolResult(text: String, maxLen: Int = 32_000): String {
        return if (text.length > maxLen) {
            text.take(maxLen) + "\n...（已截断,如需完整内容请用 read_file 读取或重新调用）"
        } else text
    }

    /**
     * 执行子任务 — 独立的 API 调用 + 简化工具循环(最多 SUB_MAX_ITERATIONS 轮)
     *
     * 子任务有独立上下文,可调用所有已注册工具,但轮数受限以控制 token 消耗。
     * 结果以文本形式返回给主 agent,由主 agent 综合后回复用户。
     */
    private suspend fun executeSubTask(
        context: Context,
        provider: ModelProvider,
        delegate: AgentTools.Delegate,
        parentMessages: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        val subSystemPrompt = "你是子任务执行器,专注于完成主 agent 分配给你的具体任务。\n" +
            "使用可用工具获取数据或创建文件,完成后用简洁文本返回结果。\n" +
            "不要寒暄,直接给出任务结果。如果生成了文件,说明文件路径。\n\n" +
            ToolRegistry.buildPromptSegment()

        val messages = mutableListOf<Pair<String, String>>()
        // 带上父对话最近 3 条作为背景
        parentMessages.takeLast(3).forEach { (role, content) ->
            messages.add(role to content.take(500))
        }
        messages.add("user" to "背景: ${delegate.context.ifEmpty { "无" }}\n\n请完成以下任务:\n${delegate.task}")

        for (iteration in 0 until SUB_MAX_ITERATIONS) {
            val result = HttpUtils.sendChatWithHistoryResult(
                provider.requestUrl, provider.modelId, messages,
                provider.apiKey, subSystemPrompt, false, provider.endpointType
            )
            val response = when (result) {
                is go.examate.hyp.util.ApiResult.Success -> result.data
                else -> return@withContext "子任务执行失败: ${result.errorMessage()}"
            }
            val rawText = HttpUtils.extractContent(response, provider.endpointType)
            if (rawText.isEmpty()) return@withContext "子任务返回为空"

            val toolCalls = AgentTools.extractToolCalls(rawText)
            if (toolCalls.isEmpty()) {
                return@withContext AgentTools.stripToolCalls(rawText)
            }

            messages.add("assistant" to rawText)
            for (call in toolCalls) {
                val r = AgentTools.execute(context, call)
                val resultText = if (r.success) r.output else "失败: ${r.output}"
                messages.add("user" to "[工具结果] ${call.name}:\n${compressToolResult(resultText)}")
            }
        }
        // 达到最大轮数,做最后一次总结
        val lastResult = HttpUtils.sendChatWithHistoryResult(
            provider.requestUrl, provider.modelId, messages,
            provider.apiKey, subSystemPrompt, false, provider.endpointType
        )
        return@withContext when (lastResult) {
            is go.examate.hyp.util.ApiResult.Success ->
                AgentTools.stripToolCalls(HttpUtils.extractContent(lastResult.data, provider.endpointType))
            else -> "子任务达到最大轮数但未完成"
        }
    }

    /** 从工具结果中构建 ActionCard(余额/成绩等),支撑回复下方的可交互卡片 */
    private fun buildResultCard(
        toolName: String,
        args: JSONObject,
        result: AgentTools.ToolResult
    ): go.examate.hyp.util.ActionCard? {
        if (!result.success) return null
        return when (toolName) {
            "get_balance" -> {
                val match = Regex("余额: ([\\d.]+) 元").find(result.output)
                val amount = match?.groupValues?.get(1)
                if (amount != null) {
                    go.examate.hyp.util.ActionCard(
                        "ecard_balance", "一卡通余额", "¥$amount",
                        mapOf("amount" to amount)
                    )
                } else null
            }
            "get_grades" -> go.examate.hyp.util.ActionCard(
                "campus_grades", "成绩查询", "已获取成绩数据"
            )
            "get_student_info" -> go.examate.hyp.util.ActionCard(
                "campus_info", "学生信息", "已获取个人信息"
            )
            else -> null
        }
    }

    /**
     * 普通对话(不带工具调用)
     * 兼容原有的发送逻辑,但支持对话历史
     */
    suspend fun simpleChat(
        context: Context,
        provider: ModelProvider,
        userMessage: String,
        history: List<Pair<String, String>>,
        systemPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        val prefs = PrefsManager.getInstance(context)
        val messages = mutableListOf<Pair<String, String>>()
        history.takeLast(10).forEach { (role, content) ->
            messages.add(role to content)
        }
        val fullPrompt = systemPrompt +
                SkillManager.buildSkillsPrompt(context) +
                (if (prefs.isMemoryEnabled) MemoryManager.buildMemoryPrompt(context) else "") +
                RENDERING_PROMPT
        messages.add("user" to userMessage)

        val result = HttpUtils.sendChatWithHistoryResult(
            provider.requestUrl, provider.modelId, messages,
            provider.apiKey, fullPrompt, provider.enableThinking, provider.endpointType
        )

        return@withContext when (result) {
            is go.examate.hyp.util.ApiResult.Success -> HttpUtils.extractContent(result.data, provider.endpointType)
            else -> null
        }
    }
}

/**
 * 快捷操作:预定义的技能快捷入口按钮
 */
object QuickActions {

    data class Action(
        val id: String,
        val name: String,
        val icon: String,        // emoji 图标
        val description: String,
        val prompt: String,      // 预填充的用户消息
        val requiresAgents: Boolean = false  // 是否需要 agents 模式
    )

    val allActions = listOf(
        Action("grades", "查成绩", "📊", "查询指定学期的成绩", "帮我查一下成绩", true),
        Action("schedule", "查课表", "📅", "查询课程表", "帮我查一下课表", true),
        Action("exam", "查考试", "📝", "查询考试安排", "帮我查一下考试安排", true),
        Action("balance", "查余额", "💳", "查询一卡通余额", "帮我查一下一卡通余额", true),
        Action("score", "第二课堂", "🏆", "查询第二课堂成绩", "帮我查一下第二课堂成绩", true),
        Action("info", "学生信息", "👤", "查询学生个人信息", "帮我查一下学生信息", true),
        Action("image", "生图", "🎨", "根据描述生成图片", "帮我生成一张图片：", false),
        Action("translate", "翻译", "🌐", "翻译文本", "请帮我翻译以下内容：", false),
        Action("summary", "总结", "📋", "总结长文本", "请帮我总结以下内容：", false),
        Action("explain", "解题", "✏️", "解题或解释概念", "请帮我解答以下问题：", false)
    )
}
