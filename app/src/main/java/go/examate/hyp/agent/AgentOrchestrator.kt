package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.HttpUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
    private val RENDERING_PROMPT =
        "\n\n## 富内容渲染(必须使用渲染标记)\n" +
            "客户端内置 KaTeX 数学引擎和 SmilesDrawer 化学引擎。\n" +
            "你的回复如果涉及以下内容,**必须**用指定标记,否则客户端无法渲染:\n\n" +
            "- **数学公式**:行内用 ${'$'}...${'$'}(如 ${'$'}\\pi${'$'}、${'$'}x^2${'$'})；独立公式用 ${'$'}${'$'}...${'$'}${'$'} 或 ```tex 代码块。\n" +
            "- **JSON**:放入 ```json 代码块。\n" +
            "- **表格数据**:放入 ```csv 代码块(首行表头)。\n" +
            "- **有机分子结构**:单分子用 ${'$'}smiles{表达式} 内联(如 ${'$'}smiles{CCO}、${'$'}smiles{c1ccccc1})；独立结构图用 ```smiles 代码块(如苯环写 c1ccccc1)。\n" +
            "- **有机反应式**:用 reaction SMILES `反应物>条件>产物`,放入 ```smiles 代码块。\n\n" +
            "示例——苯环结构: ```smiles\\nc1ccccc1\\n```  示例——乙醇分子: ${'$'}smiles{CCO}\n" +
            "示例——行内数学: ${'$'}\\pi${'$'} 是圆周率,${'$'}E=mc^2${'$'} 是质能方程。\n"

    /** Agent 执行过程中的实时事件(Flow 渐进式输出) */
    sealed class AgentEvent {
        /** AI 开始思考 / 规划调用哪些工具 */
        data class Thinking(val text: String) : AgentEvent()
        /** 某个工具开始执行 */
        data class ToolStarted(val name: String, val label: String) : AgentEvent()
        /** 某个工具执行完毕,附带结果文本和可选行动卡片 */
        data class ToolDone(val name: String, val output: String, val card: go.examate.hyp.util.ActionCard?) : AgentEvent()
        /** 最终回复(AI 文本 + 卡片) */
        data class FinalReply(
            val text: String,
            val imageBase64: String? = null,
            val imageUrl: String? = null,
            val cards: List<go.examate.hyp.util.ActionCard> = emptyList(),
            val isError: Boolean = false
        ) : AgentEvent()
        /** 最终回复的流式文本 token(SSE 逐字输出) */
        data class FinalTextToken(val token: String) : AgentEvent()
        /** 最终回复的流式输出结束 */
        data object FinalTextEnd : AgentEvent()
    }

    /** Agent 回复结果 */
    data class AgentResponse(
        val text: String,           // 给用户显示的文本(markdown)
        val imageBase64: String? = null,  // 生成的图片(base64)
        val imageUrl: String? = null,     // 生成的图片(URL)
        val toolCallsLog: List<String> = emptyList(),  // 工具调用日志
        val cards: List<go.examate.hyp.util.ActionCard> = emptyList(), // 行动卡片
        val isError: Boolean = false
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

        val systemPrompt = AgentTools.buildSystemPrompt(campusLoggedIn, agentsMode) +
                SkillManager.buildSkillsPrompt(context) +
                (if (prefs.isMemoryEnabled) MemoryManager.buildMemoryPrompt(context) else "") +
                RENDERING_PROMPT

        // 构建对话上下文(历史 + 当前消息),用简单的 Pair 格式(兼容所有 API)
        val messages = mutableListOf<Pair<String, String>>()
        history.takeLast(10).forEach { (role, content) ->
            messages.add(role to content)
        }
        messages.add("user" to userMessage)

        val toolCallLog = mutableListOf<String>()
        var imageBase64: String? = null
        var imageUrl: String? = null

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
                return@withContext AgentResponse(displayText, imageBase64, imageUrl, toolCallLog, inferred)
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

        AgentResponse(finalText, imageBase64, imageUrl, toolCallLog, AgentTools.inferCampusCards(finalText, campusLoggedIn))
    }

    // 工具并行执行的结果包装
    private data class ToolExecResult(
        val call: AgentTools.ToolCall,
        val result: AgentTools.ToolResult,
        val output: String,
        val card: go.examate.hyp.util.ActionCard?
    )

    /**
     * 执行一次完整的 Agent 对话(Flow 渐进式输出)。
     *
     * 相比 execute(),本函数在工具调用过程中持续 emit AgentEvent,
     * 让 ChatViewModel 可以实时更新 UI(显示工具状态气泡 + 结果卡片),
     * 而不是等到全部完成再一次性显示。感知速度提升 3-5 倍。
     *
     * 另外:多个工具并行执行(用 coroutineScope + async),不再串行等待。
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

        for (iteration in 0 until MAX_ITERATIONS) {
            // HTTP 请求放在 IO 线程,避免阻塞 flow 收集(通常在 Main 线程)
            val apiResult = withContext(Dispatchers.IO) {
                HttpUtils.sendChatWithHistoryResult(
                    provider.requestUrl, provider.modelId, messages,
                    provider.apiKey, systemPrompt, provider.enableThinking, provider.endpointType
                )
            }

            val response = when (apiResult) {
                is go.examate.hyp.util.ApiResult.Success -> apiResult.data
                else -> {
                    emit(AgentEvent.FinalReply(
                        "请求失败: ${apiResult.errorMessage()}",
                        isError = true
                    ))
                    return@flow
                }
            }

            val rawText = HttpUtils.extractContent(response, provider.endpointType)

            if (rawText.isEmpty()) {
                emit(AgentEvent.FinalReply(
                    "AI 回复为空,请检查 API 配置或更换模型后重试",
                    isError = true
                ))
                return@flow
            }

            val toolCalls = AgentTools.extractToolCalls(rawText)
            val displayText = AgentTools.stripToolCalls(rawText)

            if (toolCalls.isEmpty()) {
                val inferred = AgentTools.inferCampusCards(displayText, campusLoggedIn)
                emit(AgentEvent.FinalReply(displayText, imageBase64, imageUrl, inferred))
                return@flow
            }

            // 把 AI 的完整回复(含 <tool_call>)加入对话上下文
            messages.add("assistant" to rawText)

            // ★ 先透出所有 "开始执行" 事件
            toolCalls.forEach { call ->
                val label = toolDisplayLabel(call.name)
                emit(AgentEvent.ToolStarted(call.name, label))
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
                emit(AgentEvent.ToolDone(tr.call.name, tr.output, tr.card))

                // 生图结果缓存
                if (tr.result.isImage && tr.result.imageData != null) {
                    if (AgentTools.isBase64Image(tr.result.imageData)) {
                        imageBase64 = tr.result.imageData
                    } else {
                        imageUrl = tr.result.imageData
                    }
                    messages.add("user" to "[工具结果] ${tr.call.name}: 图片已生成")
                } else {
                    messages.add("user" to "[工具结果] ${tr.call.name}:\n${tr.output}")
                }
            }
        }

        // 达到最大迭代次数,最后一次 API 调用让 AI 总结
        val lastApiResult = withContext(Dispatchers.IO) {
            HttpUtils.sendChatWithHistoryResult(
                provider.requestUrl, provider.modelId, messages,
                provider.apiKey, systemPrompt, provider.enableThinking, provider.endpointType
            )
        }
        val finalText = when (lastApiResult) {
            is go.examate.hyp.util.ApiResult.Success ->
                AgentTools.stripToolCalls(HttpUtils.extractContent(lastApiResult.data, provider.endpointType))
            else -> "已达到最大工具调用次数(${lastApiResult.errorMessage()})"
        }
        val inferred = AgentTools.inferCampusCards(finalText, campusLoggedIn)
        emit(AgentEvent.FinalReply(finalText, imageBase64, imageUrl, inferred))
    }

    /** 工具名 → 中文标签 */
    private fun toolDisplayLabel(name: String): String = when (name) {
        "get_balance" -> "查询一卡通余额..."
        "get_grades" -> "查询成绩..."
        "get_schedule" -> "查询课表..."
        "get_exam_schedule" -> "查询考试安排..."
        "get_selected_courses" -> "查询已选课程..."
        "get_student_info" -> "获取学生信息..."
        "get_score_summary" -> "查询第二课堂汇总..."
        "get_score_detail" -> "查询第二课堂明细..."
        "get_teaching_evaluation" -> "查询教学评价..."
        "generate_image" -> "生成图片中..."
        else -> "执行: $name..."
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
