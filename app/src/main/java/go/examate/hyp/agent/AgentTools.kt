package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.ActionCard
import go.examate.hyp.util.HttpUtils
import org.json.JSONObject

/**
 * Agent 工具系统 — 工具调用解析 + 系统提示词
 *
 * 工具执行委托给 [ToolRegistry],工具定义在 CampusTools/FileTools/WebTools/ImageTools
 *
 * 采用 prompt-based function calling:AI 在回复中用 <tool_call>...</tool_call>
 * 标记工具调用,app 解析后执行,将结果回传给 AI 进行下一步推理。
 */
object AgentTools {

    /** 工具调用结果(兼容旧接口) */
    data class ToolResult(
        val success: Boolean,
        val output: String,
        val imageData: String? = null,
        val isImage: Boolean = false,
        val filePath: String? = null
    )

    /** 解析到的工具调用 */
    data class ToolCall(
        val name: String,
        val arguments: JSONObject
    )

    /** 从 AI 回复中提取所有 <tool_call> 标记(容错:支持无闭合标签、多行 JSON) */
    fun extractToolCalls(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        val regex = Regex("<tool_call>([\\s\\S]*?)</tool_call>")
        regex.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1].trim())?.let { calls.add(it) }
        }
        if (calls.isEmpty()) {
            val openRegex = Regex("<tool_call>([\\s\\S]*?)(?:</tool_call>|\\n\\n|$)")
            openRegex.findAll(text).forEach { match ->
                parseToolCallJson(match.groupValues[1].trim())?.let { calls.add(it) }
            }
        }
        if (calls.isEmpty()) {
            val codeBlockRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\"name\"[\\s\\S]*?\\})\\s*```")
            codeBlockRegex.findAll(text).forEach { match ->
                parseToolCallJson(match.groupValues[1].trim())?.let { calls.add(it) }
            }
        }
        return calls
    }

    private fun parseToolCallJson(jsonStr: String): ToolCall? {
        return try {
            val json = JSONObject(jsonStr)
            val name = json.optString("name", "")
            val args = json.optJSONObject("arguments") ?: JSONObject()
            if (name.isNotEmpty()) ToolCall(name, args) else null
        } catch (_: Exception) { null }
    }

    /** 从 AI 回复中移除 <tool_call> 标记 */
    fun stripToolCalls(text: String): String {
        var result = Regex("<tool_call>[\\s\\S]*?</tool_call>").replace(text, "")
        result = Regex("<tool_call>[\\s\\S]*?(?:\\n\\n|$)").replace(result, "")
        return result.trim()
    }

    /** 子任务派发标记 */
    data class Delegate(
        val task: String,
        val context: String = ""
    )

    /** 从 AI 回复中提取 <delegate> 标记(子任务派发) */
    fun extractDelegates(text: String): List<Delegate> {
        val delegates = mutableListOf<Delegate>()
        val regex = Regex("<delegate>([\\s\\S]*?)</delegate>")
        regex.findAll(text).forEach { match ->
            try {
                val json = JSONObject(match.groupValues[1].trim())
                val task = json.optString("task", "")
                val ctx = json.optString("context", "")
                if (task.isNotEmpty()) delegates.add(Delegate(task, ctx))
            } catch (_: Exception) { }
        }
        // 无闭合标签的容错
        if (delegates.isEmpty()) {
            val openRegex = Regex("<delegate>([\\s\\S]*?)(?:</delegate>|\\n\\n|$)")
            openRegex.findAll(text).forEach { match ->
                try {
                    val json = JSONObject(match.groupValues[1].trim())
                    val task = json.optString("task", "")
                    val ctx = json.optString("context", "")
                    if (task.isNotEmpty()) delegates.add(Delegate(task, ctx))
                } catch (_: Exception) { }
            }
        }
        return delegates
    }

    /** 从 AI 回复中移除 <delegate> 标记 */
    fun stripDelegates(text: String): String {
        var result = Regex("<delegate>[\\s\\S]*?</delegate>").replace(text, "")
        result = Regex("<delegate>[\\s\\S]*?(?:\\n\\n|$)").replace(result, "")
        return result.trim()
    }

    /** 生成完整系统提示词(每次对话都发送,含完整工具说明)。
     *  参考 rikkahub: 每次发完整 prompt,依赖服务端 prompt caching 加速,避免 AI 遗忘工具 */
    fun buildSystemPrompt(campusLoggedIn: Boolean, agentsMode: Boolean): String {
        val sb = StringBuilder()
        sb.append("你是一个强大的 AI 对话伙伴，会主动思考、追问、补充视角，让对话有深度和温度。\n\n")

        // 工具说明从注册表动态生成
        sb.append(ToolRegistry.buildPromptSegment())

        sb.append("## 工具使用优先级(重要)\n")
        sb.append("- 查成绩/课表/考试/余额/学生信息/第二课堂等校园信息,必须直接调用对应工具(get_grades/get_schedule/get_balance 等),禁止用 web_search 查询校园信息\n")
        sb.append("- web_search 仅用于联网查实时信息(新闻/天气/外部知识),web_fetch 用于抓取指定网页\n")
        sb.append("- 生成图片用 generate_image,创建文档用 create_file\n")
        sb.append("- 用户说\"查成绩\"→直接调 get_grades;\"查余额\"→get_balance;\"查课表\"→get_schedule;\"查考试\"→get_exam_schedule\n")
        sb.append("- 不要先反问再调工具,能调就直接调,拿到结果再组织回答\n\n")

        sb.append("## 回复规范\n")
        sb.append("1. 工具数据用 markdown 表格或列表展示\n")
        sb.append("2. 数学公式用 $...$ 或 $$...$$\n")
        sb.append("3. 工具调用 JSON 必须合法,调用格式: <tool_call>{\"name\":\"...\",\"arguments\":{...}}</tool_call>\n")
        sb.append("4. 回答简洁有力，不套话\n")
        sb.append("5. 创建文件后只需告知\"文件已生成:文件名\",不要重复文件内容\n")
        sb.append("6. 复杂任务可用 <delegate>{\"task\":\"...\",\"context\":\"...\"}</delegate> 派发子任务\n")
        sb.append("7. 大文件先 create_file 创建骨架,再 insert_at_anchor 或 append 分块写入\n")

        if (!campusLoggedIn) {
            sb.append("\n注意: 校园服务未登录，校园相关工具不可用。\n")
        }

        return sb.toString()
    }

    /** 原生 function calling 专用系统提示词(工具通过 API tools 数组注入,无需在 prompt 中描述工具列表和调用格式)。
     *  保留工具使用优先级指引和回复规范,确保 AI 用对工具 */
    fun buildSystemPromptNative(campusLoggedIn: Boolean): String {
        val sb = StringBuilder()
        sb.append("你是一个强大的 AI 对话伙伴，会主动思考、追问、补充视角，让对话有深度和温度。\n\n")

        sb.append("## 工具使用优先级(重要)\n")
        sb.append("- 查成绩/课表/考试/余额/学生信息/第二课堂等校园信息,必须直接调用对应工具(get_grades/get_schedule/get_balance 等),禁止用 web_search 查询校园信息\n")
        sb.append("- web_search 仅用于联网查实时信息(新闻/天气/外部知识),web_fetch 用于抓取指定网页\n")
        sb.append("- 生成图片用 generate_image,创建文档用 create_file\n")
        sb.append("- 用户说\"查成绩\"→直接调 get_grades;\"查余额\"→get_balance;\"查课表\"→get_schedule;\"查考试\"→get_exam_schedule\n")
        sb.append("- 不要先反问再调工具,能调就直接调,拿到结果再组织回答\n\n")

        sb.append("## 回复规范\n")
        sb.append("1. 工具数据用 markdown 表格或列表展示\n")
        sb.append("2. 数学公式用 $...$ 或 $$...$$\n")
        sb.append("3. 回答简洁有力，不套话\n")
        sb.append("4. 创建文件后只需告知\"文件已生成:文件名\",不要重复文件内容\n")

        if (!campusLoggedIn) {
            sb.append("\n注意: 校园服务未登录，校园相关工具不可用。\n")
        }
        return sb.toString()
    }

    /** 执行工具调用 — 委托给 ToolRegistry */
    suspend fun execute(context: Context, call: ToolCall): ToolResult {
        val result = ToolRegistry.execute(context, call)
        return ToolResult(
            success = result.success,
            output = result.output,
            imageData = result.imageData,
            isImage = result.isImage,
            filePath = result.filePath
        )
    }

    /** 判断 base64 字符串是否为有效的图片数据 */
    fun isBase64Image(data: String): Boolean {
        return data.length > 100 && data.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }

    /** 从回复文本中推断行动卡片 */
    fun inferCampusCards(text: String, campusLoggedIn: Boolean): List<ActionCard> {
        if (!campusLoggedIn) return emptyList()
        val lower = text.lowercase()
        val cards = mutableListOf<ActionCard>()
        if (lower.contains("充值") || lower.contains("余额不足") || lower.contains("不够")) {
            cards.add(ActionCard("ecard_topup", "一卡通充值", "点击进入充值页面", mapOf("action" to "ecard_topup")))
        }
        if (lower.contains("电费") || lower.contains("房间") || lower.contains("宿舍")) {
            cards.add(ActionCard("room_electricity", "房间电费查询", "查询房间剩余电费", mapOf("action" to "room_electricity")))
        }
        return cards
    }
}
