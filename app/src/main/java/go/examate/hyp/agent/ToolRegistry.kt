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

    /** 工具参数定义(结构化 schema,供原生 function calling 生成 tools 数组) */
    data class ParamDef(
        val name: String,
        val type: String,       // "string" / "number" / "boolean"
        val description: String,
        val required: Boolean = false
    )

    /** 工具接口 — 每个工具自描述、自执行 */
    interface Tool {
        val name: String
        val description: String
        val category: Category
        /** 参数说明,注入系统提示词给 AI 看(prompt-based 用) */
        val parametersHint: String
        /** 结构化参数 schema(原生 FC 用)。内置工具可留空(schema 仍在 ToolSchemaBuilder);自定义/动态工具必填,以便自动生成 tools 数组 */
        val params: List<ParamDef> get() = emptyList()
        /** UI 加载标签(如"查询成绩...");留空则回退到 ToolRegistry 内置映射 */
        val displayLabel: String get() = ""
        /** 是否需要校园登录(仅校园工具重写为 true) */
        val requiresLogin: Boolean get() = false
        /** 是否可用(如校园工具需登录) */
        fun isAvailable(context: Context): Boolean = true
        suspend fun execute(context: Context, args: JSONObject): Result
    }

    enum class Category { SAFE, PRIVILEGED }

    // copy-on-write:读操作取不可变快照,写操作构建新列表原子替换,避免并发修改崩溃
    @Volatile
    private var tools: List<Tool> = emptyList()

    /** 注册工具 */
    fun register(tool: Tool) {
        if (tools.none { it.name == tool.name }) {
            tools = tools + tool
            Log.d(TAG, "已注册工具: ${tool.name}")
        }
    }

    /** 取消注册 */
    fun unregister(name: String) {
        tools = tools.filterNot { it.name == name }
    }

    /** 注册默认工具集 */
    fun registerDefaults(context: Context) {
        val prefs = PrefsManager.getInstance(context)
        val campusLoggedIn = CampusRepository.getInstance().isLoggedIn &&
                prefs.campusUsername.isNotEmpty()
        // 全量重建到本地列表,最后一次性原子替换 tools:
        //   既保证自定义工具增/删/改即时生效,又让读者始终看到完整快照(避免并发 clear 崩溃)
        val list = mutableListOf<Tool>()
        fun add(tool: Tool) { if (list.none { it.name == tool.name }) list.add(tool) }

        // 校园服务工具(未登录时跳过需登录的)
        CampusTools.all().forEach { if (campusLoggedIn || !it.requiresLogin) add(it) }
        FileTools.all().forEach { add(it) }        // 文件工具
        TerminalTool.all().forEach { add(it) }     // 终端工具
        WebTools.all().forEach { add(it) }         // 联网工具
        add(ImageTools.GenerateImage)              // 生图工具
        WaterCardTools.all().forEach { add(it) }   // 乐校通水卡
        ScheduleTools.all().forEach { add(it) }    // 课表/代办(本地)
        MusicTools.all().forEach { add(it) }       // 音乐(搜索/歌词/本地扫描/历史/音源)
        ReadingTools.all().forEach { add(it) }     // 阅读(搜书/书架/章节/书源)
        NotesTools.all().forEach { add(it) }   // 笔记与速记代办
        add(SkillManager.GetSkill)                 // 技能取回(渐进披露)
        // 用户自定义声明式工具(不发版即可添加;同名自动跳过,内置优先)
        CustomToolStore.load(context).forEach { spec ->
            if (spec.name.isNotBlank()) add(CustomToolFactory.toTool(spec))
        }
        tools = list
    }

    /** 获取已注册工具名集合(供 ToolSchemaBuilder 过滤可用工具) */
    fun registeredToolNames(): Set<String> = tools.map { it.name }.toSet()

    /** 获取已注册工具列表(供 schema 派生:内置 + 自定义/动态工具) */
    fun registeredTools(): List<Tool> = tools.toList()

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
        // 优先用工具自带标签(自定义/动态工具);内置工具留空 → 回退到下方映射
        tools.find { it.name == name }?.displayLabel?.takeIf { it.isNotBlank() }?.let { return it }
        return when (name) {
            "get_balance" -> "查询一卡通余额..."
            "get_water_balance" -> "查询水卡余额..."
            "get_ecard_bill" -> "查询一卡通账单..."
            "get_grades" -> "查询成绩..."
            "get_schedule" -> "查询课表..."
            "get_today_schedule" -> "查询今日课表..."
            "add_todo" -> "添加待办..."
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
            "create_note" -> "创建笔记"
            "list_notes" -> "列出笔记"
            "quick_todo" -> "添加速记代办"
            else -> "执行: $name..."
        }
    }

    /** 清空注册表(测试用) */
    fun clear() {
        tools = emptyList()
    }
}
