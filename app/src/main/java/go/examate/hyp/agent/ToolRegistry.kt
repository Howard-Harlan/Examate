package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.campus.CampusRepository
import org.json.JSONObject

/**
 * 工具注册表 — agents 底层工具架构
 *
 * 每个工具实现 [Tool] 接口并注册到此处,系统提示词和执行分发自动生成。
 * 新增工具只需:1)实现 Tool 接口 2)在 [registerDefaults] 中注册一行
 *
 * 工具分类:
 * - SAFE: 只读查询,无需确认(校园查询、文件读取、联网搜索)
 * - PRIVILEGED: 写操作,需用户确认(创建文件、执行命令)
 */
object ToolRegistry {

    private const val TAG = "ToolRegistry"

    /** 工具执行结果 */
    data class Result(
        val success: Boolean,
        val output: String,
        val imageData: String? = null,
        val isImage: Boolean = false,
        val filePath: String? = null  // 生成的文件路径(HTML/MD等),用于 UI 预览
    )

    /** 工具接口 — 每个工具自描述、自执行 */
    interface Tool {
        val name: String
        val description: String
        val category: Category
        /** 参数说明,注入系统提示词给 AI 看 */
        val parametersHint: String
        /** 是否需要校园登录(仅校园工具重写为 true) */
        val requiresLogin: Boolean get() = false
        /** 是否可用(如校园工具需登录) */
        fun isAvailable(context: Context): Boolean = true
        suspend fun execute(context: Context, args: JSONObject): Result
    }

    enum class Category { SAFE, PRIVILEGED }

    private val tools = mutableListOf<Tool>()

    /** 注册工具 */
    fun register(tool: Tool) {
        if (tools.none { it.name == tool.name }) {
            tools.add(tool)
            Log.d(TAG, "已注册工具: ${tool.name}")
        }
    }

    /** 取消注册 */
    fun unregister(name: String) {
        tools.removeAll { it.name == name }
    }

    /** 注册默认工具集 */
    fun registerDefaults(context: Context) {
        val prefs = PrefsManager.getInstance(context)
        val campusLoggedIn = CampusRepository.getInstance().isLoggedIn &&
                prefs.campusUsername.isNotEmpty()

        // 校园服务工具
        CampusTools.all().forEach { tool ->
            if (campusLoggedIn || !tool.requiresLogin) register(tool)
        }

        // 文件工具
        FileTools.all().forEach { register(it) }

        // 终端工具(沙箱命令执行)
        TerminalTool.all().forEach { register(it) }

        // 联网工具
        WebTools.all().forEach { register(it) }

        // 生图工具
        register(ImageTools.GenerateImage)
    }

    /** 获取已注册工具名集合(供 ToolSchemaBuilder 过滤可用工具) */
    fun registeredToolNames(): Set<String> = tools.map { it.name }.toSet()

    /** 生成工具说明的系统提示词片段 */
    fun buildPromptSegment(): String {
        if (tools.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## 可用工具\n\n")
        sb.append("你可以调用以下工具。调用方式:在回复中包含\n")
        sb.append("<tool_call>{\"name\": \"工具名\", \"arguments\": {\"参数\": \"值\"}}</tool_call>\n")
        sb.append("调用后结果以 [工具结果] 返回。需要实际数据时才调用,日常对话不需要。\n\n")

        // 按分类分组
        val safe = tools.filter { it.category == Category.SAFE }
        val privileged = tools.filter { it.category == Category.PRIVILEGED }

        if (safe.isNotEmpty()) {
            sb.append("### 查询工具\n")
            safe.forEach { t ->
                sb.append("- `${t.name}`: ${t.description}")
                if (t.parametersHint.isNotEmpty()) sb.append(" 参数: ${t.parametersHint}")
                sb.append("\n")
            }
            sb.append("\n")
        }

        if (privileged.isNotEmpty()) {
            sb.append("### 操作工具\n")
            privileged.forEach { t ->
                sb.append("- `${t.name}`: ${t.description}")
                if (t.parametersHint.isNotEmpty()) sb.append(" 参数: ${t.parametersHint}")
                sb.append("\n")
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    /** 执行工具调用 */
    suspend fun execute(context: Context, call: AgentTools.ToolCall): Result {
        val tool = tools.find { it.name == call.name }
            ?: return Result(false, "未知工具: ${call.name}")

        return try {
            if (!tool.isAvailable(context)) {
                return Result(false, "工具 ${call.name} 当前不可用")
            }
            tool.execute(context, call.arguments)
        } catch (e: go.examate.hyp.campus.CampusException) {
            Result(false, "校园服务错误: ${e.message}")
        } catch (e: Exception) {
            Result(false, "工具执行错误: ${e.message ?: "未知错误"}")
        }
    }

    /** 获取工具显示标签 */
    fun getDisplayLabel(name: String): String {
        return when (name) {
            "get_balance" -> "查询一卡通余额..."
            "get_ecard_bill" -> "查询一卡通账单..."
            "get_grades" -> "查询成绩..."
            "get_schedule" -> "查询课表..."
            "get_exam_schedule" -> "查询考试安排..."
            "get_selected_courses" -> "查询已选课程..."
            "get_student_info" -> "获取学生信息..."
            "get_score_summary" -> "查询第二课堂汇总..."
            "get_score_detail" -> "查询第二课堂明细..."
            "get_teaching_evaluation" -> "查询教学评价..."
            "get_lost_found" -> "查询失物招领..."
            "generate_image" -> "生成图片中..."
            "web_search" -> "联网搜索中..."
            "web_fetch" -> "抓取网页中..."
            "create_file" -> "创建文件中..."
            "read_file" -> "读取文件中..."
            "list_dir" -> "列出文件中..."
            "edit_file" -> "编辑文件中..."
            "insert_at_anchor" -> "填充内容中..."
            "run_command" -> "执行命令中..."
            "delegate_task" -> "执行子任务中..."
            else -> "执行: $name..."
        }
    }

    /** 清空注册表(测试用) */
    fun clear() {
        tools.clear()
    }
}
