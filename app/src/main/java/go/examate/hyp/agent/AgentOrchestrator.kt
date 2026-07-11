package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.HttpUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        "\n\n## 可渲染内容输出规范\n" +
            "- 数学公式：行内使用 `${'$'}...${'$'}`，独立公式使用 `${'$'}${'$'}...${'$'}${'$'}`。\n" +
            "- JSON：必须输出合法 JSON，推荐放入 ```json 代码块。\n" +
            "- CSV：表格数据需要可导出时，放入 ```csv 代码块，第一行写表头。\n" +
            "- 有机化学结构：单个分子使用 ```smiles 代码块，例如 `c1ccccc1`；句中分子可写 `${'$'}smiles{CCO}`。\n" +
            "- 有机反应使用 reaction SMILES：`反应物>条件>产物`，也放入 ```smiles 代码块。\n"

    /** Agent 回复结果 */
    data class AgentResponse(
        val text: String,           // 给用户显示的文本(markdown)
        val imageBase64: String? = null,  // 生成的图片(base64)
        val imageUrl: String? = null,     // 生成的图片(URL)
        val toolCallsLog: List<String> = emptyList(),  // 工具调用日志
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
                MemoryManager.buildMemoryPrompt(context) +
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
                return@withContext AgentResponse(displayText, imageBase64, imageUrl, toolCallLog)
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

        AgentResponse(finalText, imageBase64, imageUrl, toolCallLog)
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
        val messages = mutableListOf<Pair<String, String>>()
        history.takeLast(10).forEach { (role, content) ->
            messages.add(role to content)
        }
        val fullPrompt = systemPrompt +
                SkillManager.buildSkillsPrompt(context) +
                MemoryManager.buildMemoryPrompt(context) +
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
