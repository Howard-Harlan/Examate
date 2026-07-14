package go.examate.hyp.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.ActionCard
import go.examate.hyp.util.HttpUtils
import org.json.JSONObject

/**
 * Agent 工具系统:定义 AI 可调用的工具(校园服务查询 + 生图)
 *
 * 采用 prompt-based function calling:AI 在回复中用 <tool_call>...</tool_call>
 * 标记工具调用,app 解析后执行,将结果回传给 AI 进行下一步推理。
 *
 * 这种方式兼容所有模型/API,不依赖原生 function calling 支持。
 */
object AgentTools {

    /** 工具调用结果 */
    data class ToolResult(
        val success: Boolean,
        val output: String,        // 返回给 AI 的文本
        val imageData: String? = null,  // 生图结果(base64 或 URL)
        val isImage: Boolean = false
    )

    /** 解析到的工具调用 */
    data class ToolCall(
        val name: String,
        val arguments: JSONObject
    )

    /** 从 AI 回复中提取所有 <tool_call> 标记(容错:支持无闭合标签、多行 JSON) */
    fun extractToolCalls(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        // 标准格式:<tool_call>{...}</tool_call>
        val regex = Regex("<tool_call>([\\s\\S]*?)</tool_call>")
        regex.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1].trim())?.let { calls.add(it) }
        }
        // 容错:无闭合标签的情况 <tool_call>{...}
        if (calls.isEmpty()) {
            val openRegex = Regex("<tool_call>([\\s\\S]*?)(?:</tool_call>|\\n\\n|$)")
            openRegex.findAll(text).forEach { match ->
                parseToolCallJson(match.groupValues[1].trim())?.let { calls.add(it) }
            }
        }
        // 容错:尝试提取 ```json {...} ``` 代码块中的工具调用(部分模型用代码块包裹)
        if (calls.isEmpty()) {
            val codeBlockRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\"name\"[\\s\\S]*?\\})\\s*```")
            codeBlockRegex.findAll(text).forEach { match ->
                parseToolCallJson(match.groupValues[1].trim())?.let { calls.add(it) }
            }
        }
        return calls
    }

    /** 解析工具调用的 JSON */
    private fun parseToolCallJson(jsonStr: String): ToolCall? {
        return try {
            val json = JSONObject(jsonStr)
            val name = json.optString("name", "")
            val args = json.optJSONObject("arguments") ?: JSONObject()
            if (name.isNotEmpty()) ToolCall(name, args) else null
        } catch (_: Exception) {
            null
        }
    }

    /** 从 AI 回复中移除 <tool_call> 标记,保留给用户看的文本 */
    fun stripToolCalls(text: String): String {
        var result = Regex("<tool_call>[\\s\\S]*?</tool_call>").replace(text, "")
        // 容错:移除无闭合标签的
        result = Regex("<tool_call>[\\s\\S]*?(?:\\n\\n|$)").replace(result, "")
        return result.trim()
    }

    /** 生成包含工具描述的系统提示词 */
    fun buildSystemPrompt(campusLoggedIn: Boolean, agentsMode: Boolean): String {
        val sb = StringBuilder()
        sb.append("你是一个强大、有洞察力的 AI 对话伙伴，拥有深厚的知识储备和缜密的推理能力。\n")
        sb.append("你不只是问答机器——你会主动思考、追问、补充视角，让对话更有深度和温度。\n")
        sb.append("面对开放性问题，你能展开有见地的讨论；面对具体任务，你高效精准地完成。\n")
        sb.append("你的回答应当自然、流畅、有逻辑，像一个睿智的朋友而非刻板的工具。\n\n")

        sb.append("## 工具调用能力\n\n")
        sb.append("你可以调用工具获取实时信息（校园数据等）或生成图片。需要调用时，在回复中包含:\n")
        sb.append("<tool_call>{\"name\": \"工具名\", \"arguments\": {\"参数\": \"值\"}}</tool_call>\n")
        sb.append("可以在 tool_call 标记外写简短说明。调用后，结果以 [工具结果] 返回，你需基于结果继续回答。\n")
        sb.append("注意:需要实际数据时才调用工具，日常对话和知识问答不需要调用。不要编造校园数据。\n\n")

        sb.append("## 可用工具\n\n")

        sb.append("### 校园服务工具\n")
        sb.append("- `get_student_info`: 获取学生个人信息（姓名/学号/院系/专业/班级）。无需参数。\n")
        sb.append("- `get_grades`: 查询成绩。参数: xn(学年,如\"2024-2025\",可选), xq(学期,\"1\"或\"2\",可选)。不传则查询全部。\n")
        sb.append("- `get_schedule`: 查询课表。参数: xn(学年,可选), xq(学期,可选)。\n")
        sb.append("- `get_exam_schedule`: 查询考试安排。参数: xn(学年,可选), xq(学期,可选)。\n")
        sb.append("- `get_selected_courses`: 查询已选课程。参数: xn(学年,可选), xq(学期,可选)。\n")
        sb.append("- `get_balance`: 查询一卡通余额。无需参数。\n")
        sb.append("- `get_score_summary`: 查询第二课堂成绩汇总。无需参数。\n")
        sb.append("- `get_score_detail`: 查询第二课堂成绩明细。无需参数。\n")
        sb.append("- `get_teaching_evaluation`: 查询教学评价列表。无需参数。\n\n")

        sb.append("### 生图工具\n")
        sb.append("- `generate_image`: 根据文字描述生成图片。参数: prompt(图片描述,必填), model(模型ID,可选,默认dall-e-3)。\n\n")

        sb.append("## 回复规范\n")
        sb.append("1. 工具返回的数据用 markdown 表格或列表整理展示\n")
        sb.append("2. 数学公式用 LaTeX 格式输出（行内用 $...$，块级用 $$...$$）\n")
        sb.append("3. 调用工具的 JSON 必须合法，参数名和值都用双引号\n")
        sb.append("4. 回答要简洁有力，避免空洞的套话和过度的客套\n")

        if (!campusLoggedIn) {
            sb.append("\n注意: 校园服务未登录，校园相关工具暂不可用，请提示用户先登录。\n")
        }

        return sb.toString()
    }

    /**
     * 执行工具调用
     * @param context 用于获取 PrefsManager
     * @param call 工具调用
     * @return 执行结果
     */
    suspend fun execute(context: Context, call: ToolCall): ToolResult {
        val prefs = PrefsManager.getInstance(context)
        val repo = CampusRepository.getInstance()

        return try {
            when (call.name) {
                "get_student_info" -> {
                    val output = cachedExecute("get_student_info") {
                        val info = repo.getStudentInfo(prefs)
                        "学生信息:\n姓名: ${info.name}\n学号: ${info.studentId}\n院系: ${info.department}\n专业: ${info.major}\n班级: ${info.className}"
                    }
                    ToolResult(true, output)
                }
                "get_grades" -> {
                    val xn = call.arguments.optString("xn", "").ifEmpty { null }
                    val xq = call.arguments.optString("xq", "").ifEmpty { null }
                    val cacheKey = "get_grades:${xn ?: "all"}:${xq ?: "all"}"
                    val output = cachedExecute(cacheKey) {
                        val grades = repo.getGrades(prefs, xn, xq)
                        if (grades.isEmpty()) "未查询到成绩数据"
                        else formatGrades(grades)
                    }
                    ToolResult(true, output)
                }
                "get_schedule" -> {
                    val xn = call.arguments.optString("xn", "").ifEmpty { null }
                    val xq = call.arguments.optString("xq", "").ifEmpty { null }
                    val cacheKey = "get_schedule:${xn ?: "all"}:${xq ?: "all"}"
                    val output = cachedExecute(cacheKey) {
                        val schedule = repo.getSchedule(prefs, xn, xq)
                        if (schedule.isEmpty()) "未查询到课表数据"
                        else formatSchedule(schedule)
                    }
                    ToolResult(true, output)
                }
                "get_exam_schedule" -> {
                    val xn = call.arguments.optString("xn", "").ifEmpty { null }
                    val xq = call.arguments.optString("xq", "").ifEmpty { null }
                    val cacheKey = "get_exam_schedule:${xn ?: "all"}:${xq ?: "all"}"
                    val output = cachedExecute(cacheKey) {
                        val exams = repo.getExamSchedule(prefs, xn, xq)
                        if (exams.isEmpty()) "未查询到考试安排"
                        else formatExams(exams)
                    }
                    ToolResult(true, output)
                }
                "get_selected_courses" -> {
                    val xn = call.arguments.optString("xn", "").ifEmpty { null }
                    val xq = call.arguments.optString("xq", "").ifEmpty { null }
                    val cacheKey = "get_selected_courses:${xn ?: "all"}:${xq ?: "all"}"
                    val output = cachedExecute(cacheKey) {
                        val courses = repo.getSelectedCourses(prefs, xn, xq)
                        if (courses.isEmpty()) "未查询到已选课程"
                        else formatSelectedCourses(courses)
                    }
                    ToolResult(true, output)
                }
                "get_balance" -> {
                    val output = cachedExecute("get_balance") {
                        val balance = repo.getBalance(prefs)
                        "一卡通余额: ${balance.balance} 元\n账户名: ${balance.accountName}\n卡号: ${balance.cardNo}"
                    }
                    ToolResult(true, output)
                }
                "get_score_summary" -> {
                    val output = cachedExecute("get_score_summary") {
                        val summary = repo.getScoreSummary(prefs)
                        val sb = StringBuilder("第二课堂总分: ${summary.totalScore}\n\n各模块得分:\n")
                        summary.modules.forEach { sb.append("- ${it.moduleName}: ${it.score}\n") }
                        sb.toString()
                    }
                    ToolResult(true, output)
                }
                "get_score_detail" -> {
                    val output = cachedExecute("get_score_detail") {
                        val details = repo.getScoreDetail(prefs)
                        if (details.isEmpty()) "未查询到第二课堂明细"
                        else {
                            val sb = StringBuilder("第二课堂成绩明细:\n| 模块 | 项目 | 得分 |\n|---|---|---|\n")
                            details.forEach { sb.append("| ${it.moduleName} | ${it.itemName} | ${it.score} |\n") }
                            sb.toString()
                        }
                    }
                    ToolResult(true, output)
                }
                "get_teaching_evaluation" -> {
                    val output = cachedExecute("get_teaching_evaluation") {
                        val evals = repo.getTeachingEvaluation(prefs)
                        if (evals.isEmpty()) "未查询到教学评价"
                        else formatEvaluations(evals)
                    }
                    ToolResult(true, output)
                }
                "generate_image" -> {
                    val prompt = call.arguments.optString("prompt", "")
                    if (prompt.isEmpty()) ToolResult(false, "生图失败: 缺少 prompt 参数")
                    else {
                        val model = call.arguments.optString("model", "dall-e-3")
                        val imageData = generateImage(context, prompt, model)
                        if (imageData != null) ToolResult(true, "图片已生成", imageData, true)
                        else ToolResult(false, "生图失败,请检查 API 配置或网络")
                    }
                }
                else -> ToolResult(false, "未知工具: ${call.name}")
            }
        } catch (e: go.examate.hyp.campus.CampusException) {
            ToolResult(false, "校园服务错误: ${e.message}")
        } catch (e: Exception) {
            ToolResult(false, "工具执行错误: ${e.message ?: "未知错误"}")
        }
    }

    /** 带缓存的执行:先查 CampusCache,命中直接返回,未命中执行并写入缓存 */
    private suspend fun cachedExecute(cacheKey: String, block: suspend () -> String): String {
        CampusCache.get(cacheKey)?.let { return "$it\n(缓存)" }
        val result = block()
        if (!result.startsWith("未查询到")) CampusCache.put(cacheKey, result)
        return result
    }

    // ════════════════════════════════════════
    //  数据格式化
    // ════════════════════════════════════════

    private fun formatGrades(grades: List<go.examate.hyp.campus.model.Grade>): String {
        val sb = StringBuilder("成绩列表:\n| 课程 | 学分 | 成绩 | 绩点 | 学年 | 学期 |\n|---|---|---|---|---|---|\n")
        grades.forEach { g ->
            sb.append("| ${g.courseName} | ${g.credit} | ${g.score} | ${g.gpa} | ${g.year} | ${g.semester} |\n")
        }
        return sb.toString()
    }

    private fun formatSchedule(schedule: List<go.examate.hyp.campus.model.ScheduleItem>): String {
        val sb = StringBuilder("课表:\n| 星期 | 节次 | 课程 | 教师 | 教室 | 周次 |\n|---|---|---|---|---|---|\n")
        schedule.forEach { s ->
            sb.append("| ${s.day} | ${s.section} | ${s.courseName} | ${s.teacher} | ${s.classroom} | ${s.weeks} |\n")
        }
        return sb.toString()
    }

    private fun formatExams(exams: List<go.examate.hyp.campus.model.ExamItem>): String {
        val sb = StringBuilder("考试安排:\n| 课程 | 日期 | 地点 | 座位号 |\n|---|---|---|---|\n")
        exams.forEach { e ->
            sb.append("| ${e.courseName} | ${e.date} | ${e.location} | ${e.seatNo} |\n")
        }
        return sb.toString()
    }

    private fun formatSelectedCourses(courses: List<go.examate.hyp.campus.model.SelectedCourse>): String {
        val sb = StringBuilder("已选课程:\n| 课程 | 教师 | 学分 | 上课时间 | 教室 | 类型 |\n|---|---|---|---|---|---|\n")
        courses.forEach { c ->
            sb.append("| ${c.courseName} | ${c.teacher} | ${c.credit} | ${c.classTime} | ${c.classroom} | ${c.courseType} |\n")
        }
        return sb.toString()
    }

    private fun formatEvaluations(evals: List<go.examate.hyp.campus.model.EvaluationItem>): String {
        val sb = StringBuilder("教学评价:\n| 课程 | 教师 | 评价状态 | 提交状态 |\n|---|---|---|---|\n")
        evals.forEach { e ->
            sb.append("| ${e.courseName} | ${e.teacher} | ${e.status} | ${e.submitStatus} |\n")
        }
        return sb.toString()
    }

    // ════════════════════════════════════════
    //  生图功能
    // ════════════════════════════════════════

    /**
     * 调用生图 API(OpenAI /v1/images/generations 格式)
     * 使用第一个配置了 API Key 的 provider 的 base URL
     */
    private fun generateImage(context: Context, prompt: String, model: String): String? {
        val providers = go.examate.hyp.config.MyConfig.loadProviders(context)
        val provider = providers.firstOrNull { it.apiKey.isNotEmpty() } ?: return null
        val baseUrl = ModelProvider.extractBaseUrl(provider.requestUrl)
        val url = "$baseUrl/v1/images/generations"

        val json = JSONObject()
        json.put("model", model)
        json.put("prompt", prompt)
        json.put("n", 1)
        json.put("size", "1024x1024")
        json.put("response_format", "b64_json")

        val response = HttpUtils.sendImageGenerationSync(url, json.toString(), provider.apiKey, "chat")
            ?: return null

        return try {
            val respJson = JSONObject(response)
            val data = respJson.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val b64 = data.getJSONObject(0).optString("b64_json", "")
                if (b64.isNotEmpty()) return b64
                val imgUrl = data.getJSONObject(0).optString("url", "")
                if (imgUrl.isNotEmpty()) return imgUrl
            }
            null
        } catch (e: Exception) {
            Log.e("AgentTools", "generateImage parse failed", e)
            null
        }
    }

    /** 判断 base64 字符串是否为有效的图片数据 */
    fun isBase64Image(data: String): Boolean {
        return data.length > 100 && data.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }

    /**
     * 从回复文本中推断"行动卡片"(校园服务快捷入口)。
     * - 提到"充值"/"余额不足"/"不够"且已登录 → 一卡通充值卡
     * - 提到"电费"/"房间"/"宿舍"且已登录 → 房间电费查询卡
     *
     * 统一实现,供 AgentOrchestrator(agents 分支)与 ChatViewModel(普通对话分支)复用,
     * 消除此前两份近似重复的逻辑。
     */
    fun inferCampusCards(text: String, campusLoggedIn: Boolean): List<ActionCard> {
        if (!campusLoggedIn) return emptyList()
        val lower = text.lowercase()
        val cards = mutableListOf<ActionCard>()
        if (lower.contains("充值") || lower.contains("余额不足") || lower.contains("不够")) {
            cards.add(
                ActionCard(
                    type = "ecard_topup",
                    title = "一卡通充值",
                    summary = "点击进入充值页面",
                    payload = mapOf("action" to "ecard_topup")
                )
            )
        }
        if (lower.contains("电费") || lower.contains("房间") || lower.contains("宿舍")) {
            cards.add(
                ActionCard(
                    type = "room_electricity",
                    title = "房间电费查询",
                    summary = "查询房间剩余电费",
                    payload = mapOf("action" to "room_electricity")
                )
            )
        }
        return cards
    }
}
