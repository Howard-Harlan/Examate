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
 * 工具参数定义与 ToolRegistry 中注册的工具保持一致,新增工具需在此处补充 schema。
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

    // ══════════════════════════════════════
    //  工具定义表(与 ToolRegistry 注册的工具保持一致,新增工具需在此补充 schema)
    // ══════════════════════════════════════

    private val toolDefs = listOf(
        // 校园查询工具
        ToolDef("get_student_info", "获取学生个人信息(姓名/学号/院系/专业/班级)"),
        ToolDef("get_grades", "查询成绩。用户说查成绩时必须调用此工具", listOf(
            ParamDef("xn", "string", "学年,如 2024-2025,可选", false),
            ParamDef("xq", "string", "学期,1 或 2,可选", false)
        )),
        ToolDef("get_schedule", "查询课程表", listOf(
            ParamDef("xn", "string", "学年,如 2024-2025,可选", false),
            ParamDef("xq", "string", "学期,1 或 2,可选", false)
        )),
        ToolDef("get_exam_schedule", "查询考试安排", listOf(
            ParamDef("xn", "string", "学年,如 2024-2025,可选", false),
            ParamDef("xq", "string", "学期,1 或 2,可选", false)
        )),
        ToolDef("get_selected_courses", "查询已选课程", listOf(
            ParamDef("xn", "string", "学年,如 2024-2025,可选", false),
            ParamDef("xq", "string", "学期,1 或 2,可选", false)
        )),
        ToolDef("get_balance", "查询一卡通余额。用户说查余额时必须调用此工具"),
        ToolDef("get_ecard_bill", "查询一卡通消费账单", listOf(
            ParamDef("recordType", "string", "消费类型,如 消费/充值,可选", false)
        )),
        ToolDef("get_score_summary", "查询第二课堂成绩汇总"),
        ToolDef("get_score_detail", "查询第二课堂成绩明细"),
        ToolDef("get_teaching_evaluation", "查询教学评价列表"),
        ToolDef("get_lost_found", "查询失物招领列表", listOf(
            ParamDef("type", "string", "类型 lost失物/found招领,可选", false),
            ParamDef("page", "number", "页码,默认1", false)
        )),
        // 联网工具
        ToolDef("web_search", "联网搜索获取实时信息(新闻/天气/外部知识)。不要用此工具查询校园信息", listOf(
            ParamDef("query", "string", "搜索关键词", true)
        )),
        ToolDef("web_fetch", "抓取指定 URL 的网页内容(提取正文文本)", listOf(
            ParamDef("url", "string", "网页地址", true)
        )),
        // 文件工具
        ToolDef("create_file", "创建文件(支持 .html/.md/.txt/.json 等),可生成网页或文档供用户预览", listOf(
            ParamDef("path", "string", "文件名,如 report.html", true),
            ParamDef("content", "string", "文件内容", true)
        )),
        ToolDef("read_file", "读取沙箱内的文件内容", listOf(
            ParamDef("path", "string", "文件名", true)
        )),
        ToolDef("list_dir", "列出沙箱目录中的文件", listOf(
            ParamDef("path", "string", "子目录,可选,默认根目录", false)
        )),
        ToolDef("edit_file", "编辑已有文件(替换或追加)。生成大文件时先创建骨架再用 append 分次追加", listOf(
            ParamDef("path", "string", "文件名", true),
            ParamDef("content", "string", "新内容", true),
            ParamDef("mode", "string", "replace替换/append追加,默认replace", false)
        )),
        ToolDef("insert_at_anchor", "在文件指定锚点处插入内容(分块构建大文件)。锚点格式:<!-- ANCHOR:名称 -->", listOf(
            ParamDef("path", "string", "文件名", true),
            ParamDef("anchor", "string", "锚点名称", true),
            ParamDef("content", "string", "要插入的内容", true)
        )),
        // 终端工具
        ToolDef("run_command", "在沙箱终端执行 shell 命令(文件操作/文本处理/grep 等)", listOf(
            ParamDef("command", "string", "命令字符串", true)
        )),
        // 生图工具
        ToolDef("generate_image", "根据文字描述生成图片", listOf(
            ParamDef("prompt", "string", "图片描述", true),
            ParamDef("model", "string", "模型ID,可选,默认 dall-e-3", false)
        ))
    )

    /** 构建 tools JSON 数组字符串。
     *  @param endpointType API 格式(chat/anthropic/responses)
     *  @param availableNames 已注册可用的工具名集合,null 表示全部
     *  @return JSON 字符串,不支持原生 function calling 时返回 null */
    fun buildToolsJson(endpointType: String, availableNames: Set<String>? = null): String? {
        val defs = if (availableNames != null) toolDefs.filter { it.name in availableNames }
                   else toolDefs
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
