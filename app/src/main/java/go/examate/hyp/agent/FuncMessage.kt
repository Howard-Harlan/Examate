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
 *
 * 工具 schema 从 ToolRegistry 注册表派生(各工具自带 description/params),新增工具无需改此处。
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

    // 工具 schema 单一真源:各工具的 description/params 内聚在其 Tool 对象中,
    // 由 buildToolsJson 统一从 ToolRegistry 注册表派生(新增工具无需在此登记)。

    /** 构建 tools JSON 数组字符串。
     *  @param endpointType API 格式(chat/anthropic/responses)
     *  @param availableNames 已注册可用的工具名集合,null 表示全部
     *  @return JSON 字符串,不支持原生 function calling 时返回 null */
    fun buildToolsJson(endpointType: String, availableNames: Set<String>? = null): String? {
        // 单一真源:所有工具(内置 + 自定义)统一从 ToolRegistry 注册表派生 schema
        var defs = ToolRegistry.registeredTools().map { t ->
            ToolDef(t.name, t.description, t.params.map { p ->
                ParamDef(p.name, p.type, p.description, p.required)
            })
        }
        if (availableNames != null) defs = defs.filter { it.name in availableNames }
        if (defs.isEmpty()) return null
        return when (endpointType) {
            "anthropic" -> buildAnthropicTools(defs)
            "chat" -> buildOpenAITools(defs)
            else -> null  // responses 等格式走 prompt-based
        }
    }

    /** OpenAI 格式 tools 数组 */
    private fun buildOpenAITools(defs: List<ToolDef>): String {
        val arr = JSONArray()
        defs.forEach { td ->
            val func = JSONObject()
            func.put("name", td.name)
            func.put("description", td.description)
            func.put("parameters", buildJsonSchema(td.params))
            arr.put(JSONObject().put("type", "function").put("function", func))
        }
        return arr.toString()
    }

    /** Anthropic 格式 tools 数组 */
    private fun buildAnthropicTools(defs: List<ToolDef>): String {
        val arr = JSONArray()
        defs.forEach { td ->
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
