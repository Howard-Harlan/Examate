package go.examate.hyp.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Function calling 消息类型
 *
 * 用于原生 function calling 协议(OpenAI tools / Anthropic tool_use),
 * 取代简单的 Pair<String, String> 以支持 tool_calls 和 tool_result。
 */
sealed class FuncMessage {

    /** 普通文本消息(user / assistant) */
    data class Text(val role: String, val content: String) : FuncMessage()

    /** 带工具调用的 assistant 消息 */
    data class AssistantToolCall(
        val content: String,
        val toolCalls: List<NativeToolCall>
    ) : FuncMessage()

    /** 工具执行结果回传 */
    data class ToolResult(
        val toolCallId: String,
        val content: String
    ) : FuncMessage()
}

/** 原生工具调用(从 API 响应中解析) */
data class NativeToolCall(
    val id: String,
    val name: String,
    val arguments: String   // 原始 JSON 字符串
)

/** 工具调用解析结果 */
data class ToolCallParseResult(
    val text: String,                   // AI 回复的文本部分
    val toolCalls: List<NativeToolCall>  // 原生工具调用(可能为空)
)

/**
 * 工具 Schema 构建器
 *
 * 为不同 API 格式生成 tools 数组:
 * - OpenAI chat:  [{"type":"function","function":{"name","description","parameters"}}]
 * - Anthropic:    [{"name","description","input_schema"}]
 * - responses:    null(不支持原生,走 prompt-based)
 */
object ToolSchemaBuilder {

    /** 工具定义 */
    private data class ToolDef(
        val name: String,
        val description: String,
        val params: List<ParamDef> = emptyList()
    )

    private data class ParamDef(
        val name: String,
        val type: String,       // "string" / "number" / "boolean"
        val description: String,
        val required: Boolean = false
    )

    // ════════════════════════════════════════
    //  工具定义表
    // ════════════════════════════════════════

    private val toolDefs = listOf(
        ToolDef("get_student_info", "获取学生个人信息(姓名/学号/院系/专业/班级)"),
        ToolDef("get_grades", "查询成绩", listOf(
            ParamDef("xn", "string", "学年,如 2024-2025", false),
            ParamDef("xq", "string", "学期,1 或 2", false)
        )),
        ToolDef("get_schedule", "查询课程表", listOf(
            ParamDef("xn", "string", "学年,如 2024-2025", false),
            ParamDef("xq", "string", "学期,1 或 2", false)
        )),
        ToolDef("get_exam_schedule", "查询考试安排", listOf(
            ParamDef("xn", "string", "学年,如 2024-2025", false),
            ParamDef("xq", "string", "学期,1 或 2", false)
        )),
        ToolDef("get_selected_courses", "查询已选课程", listOf(
            ParamDef("xn", "string", "学年,如 2024-2025", false),
            ParamDef("xq", "string", "学期,1 或 2", false)
        )),
        ToolDef("get_balance", "查询一卡通余额"),
        ToolDef("get_score_summary", "查询第二课堂成绩汇总"),
        ToolDef("get_score_detail", "查询第二课堂成绩明细"),
        ToolDef("get_teaching_evaluation", "查询教学评价列表"),
        ToolDef("generate_image", "根据文字描述生成图片", listOf(
            ParamDef("prompt", "string", "图片描述", true),
            ParamDef("model", "string", "模型ID,默认 dall-e-3", false)
        ))
    )

    /** 构建 tools JSON 数组字符串,不支持时返回 null */
    fun buildToolsJson(endpointType: String): String? {
        when (endpointType) {
            "anthropic" -> return buildAnthropicTools()
            "chat" -> return buildOpenAITools()
            else -> return null  // responses 格式走 prompt-based
        }
    }

    /** OpenAI 格式 tools 数组 */
    private fun buildOpenAITools(): String {
        val arr = JSONArray()
        toolDefs.forEach { td ->
            val func = JSONObject()
            func.put("name", td.name)
            func.put("description", td.description)
            func.put("parameters", buildJsonSchema(td.params))
            arr.put(JSONObject().put("type", "function").put("function", func))
        }
        return arr.toString()
    }

    /** Anthropic 格式 tools 数组 */
    private fun buildAnthropicTools(): String {
        val arr = JSONArray()
        toolDefs.forEach { td ->
            val tool = JSONObject()
            tool.put("name", td.name)
            tool.put("description", td.description)
            tool.put("input_schema", buildJsonSchema(td.params))
            arr.put(tool)
        }
        return arr.toString()
    }

    /** 构建 JSON Schema 参数定义 */
    private fun buildJsonSchema(params: List<ParamDef>): JSONObject {
        val schema = JSONObject()
        schema.put("type", "object")
        val properties = JSONObject()
        val required = JSONArray()
        params.forEach { p ->
            val prop = JSONObject()
            prop.put("type", p.type)
            prop.put("description", p.description)
            properties.put(p.name, prop)
            if (p.required) required.put(p.name)
        }
        schema.put("properties", properties)
        if (required.length() > 0) schema.put("required", required)
        return schema
    }
}
