package go.examate.hyp.agent

import android.content.Context
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import org.json.JSONObject

/**
 * 校园服务工具集 — 从 AgentTools 迁移到 Tool 接口
 * 每个工具自描述、自执行,带 5 分钟缓存
 */
object CampusTools {

    /** 带缓存的执行 */
    private suspend fun cachedExecute(cacheKey: String, block: suspend () -> String): String {
        CampusCache.get(cacheKey)?.let { return "$it\n(缓存)" }
        val result = block()
        if (!result.startsWith("未查询到")) CampusCache.put(cacheKey, result)
        return result
    }

    private abstract class CampusTool(
        override val name: String,
        override val description: String,
        override val parametersHint: String = ""
    ) : ToolRegistry.Tool {
        override val category = ToolRegistry.Category.SAFE
        override val requiresLogin = true
    }

    // ════════════════════════════════════════
    //  工具定义
    // ════════════════════════════════════════

    private val GetStudentInfo = object : CampusTool(
        "get_student_info", "获取学生个人信息(姓名/学号/院系/专业/班级)"
    ) {
        override suspend fun execute(context: Context, args: JSONObject) = ToolRegistry.Result(
            true, cachedExecute("get_student_info") {
                val info = CampusRepository.getInstance().getStudentInfo(PrefsManager.getInstance(context))
                "学生信息:\n姓名: ${info.name}\n学号: ${info.studentId}\n院系: ${info.department}\n专业: ${info.major}\n班级: ${info.className}"
            }
        )
    }

    private val GetGrades = object : CampusTool(
        "get_grades", "查询成绩",
        "xn(学年如\"2024-2025\",可选), xq(学期\"1\"或\"2\",可选)"
    ) {
        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val xn = args.optString("xn", "").ifEmpty { null }
            val xq = args.optString("xq", "").ifEmpty { null }
            val output = cachedExecute("get_grades:${xn ?: "all"}:${xq ?: "all"}") {
                val grades = CampusRepository.getInstance().getGrades(PrefsManager.getInstance(context), xn, xq)
                if (grades.isEmpty()) "未查询到成绩数据"
                else "成绩列表:\n| 课程 | 学分 | 成绩 | 绩点 | 学年 | 学期 |\n|---|---|---|---|---|---|\n" +
                    grades.joinToString("") { "| ${it.courseName} | ${it.credit} | ${it.score} | ${it.gpa} | ${it.year} | ${it.semester} |\n" }
            }
            return ToolRegistry.Result(true, output)
        }
    }

    private val GetSchedule = object : CampusTool(
        "get_schedule", "查询课表", "xn(学年,可选), xq(学期,可选)"
    ) {
        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val xn = args.optString("xn", "").ifEmpty { null }
            val xq = args.optString("xq", "").ifEmpty { null }
            val output = cachedExecute("get_schedule:${xn ?: "all"}:${xq ?: "all"}") {
                val schedule = CampusRepository.getInstance().getSchedule(PrefsManager.getInstance(context), xn, xq)
                if (schedule.isEmpty()) "未查询到课表数据"
                else "课表:\n| 星期 | 节次 | 课程 | 教师 | 教室 | 周次 |\n|---|---|---|---|---|---|\n" +
                    schedule.joinToString("") { "| ${it.day} | ${it.section} | ${it.courseName} | ${it.teacher} | ${it.classroom} | ${it.weeks} |\n" }
            }
            return ToolRegistry.Result(true, output)
        }
    }

    private val GetExamSchedule = object : CampusTool(
        "get_exam_schedule", "查询考试安排", "xn(学年,可选), xq(学期,可选)"
    ) {
        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val xn = args.optString("xn", "").ifEmpty { null }
            val xq = args.optString("xq", "").ifEmpty { null }
            val output = cachedExecute("get_exam_schedule:${xn ?: "all"}:${xq ?: "all"}") {
                val exams = CampusRepository.getInstance().getExamSchedule(PrefsManager.getInstance(context), xn, xq)
                if (exams.isEmpty()) "未查询到考试安排"
                else "考试安排:\n| 课程 | 日期 | 地点 | 座位号 |\n|---|---|---|---|\n" +
                    exams.joinToString("") { "| ${it.courseName} | ${it.date} | ${it.location} | ${it.seatNo} |\n" }
            }
            return ToolRegistry.Result(true, output)
        }
    }

    private val GetSelectedCourses = object : CampusTool(
        "get_selected_courses", "查询已选课程", "xn(学年,可选), xq(学期,可选)"
    ) {
        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val xn = args.optString("xn", "").ifEmpty { null }
            val xq = args.optString("xq", "").ifEmpty { null }
            val output = cachedExecute("get_selected_courses:${xn ?: "all"}:${xq ?: "all"}") {
                val courses = CampusRepository.getInstance().getSelectedCourses(PrefsManager.getInstance(context), xn, xq)
                if (courses.isEmpty()) "未查询到已选课程"
                else "已选课程:\n| 课程 | 教师 | 学分 | 上课时间 | 教室 | 类型 |\n|---|---|---|---|---|---|\n" +
                    courses.joinToString("") { "| ${it.courseName} | ${it.teacher} | ${it.credit} | ${it.classTime} | ${it.classroom} | ${it.courseType} |\n" }
            }
            return ToolRegistry.Result(true, output)
        }
    }

    private val GetBalance = object : CampusTool(
        "get_balance", "查询一卡通余额"
    ) {
        override suspend fun execute(context: Context, args: JSONObject) = ToolRegistry.Result(
            true, cachedExecute("get_balance") {
                val b = CampusRepository.getInstance().getBalance(PrefsManager.getInstance(context))
                "一卡通余额: ${b.balance} 元\n账户名: ${b.accountName}\n卡号: ${b.cardNo}"
            }
        )
    }

    private val GetEcardBill = object : CampusTool(
        "get_ecard_bill", "查询一卡通消费账单",
        "record_type(类型如\"消费\"/\"充值\",可选)"
    ) {
        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val recordType = args.optString("record_type", "").ifEmpty { "" }
            val output = cachedExecute("get_ecard_bill:${recordType.ifEmpty { "all" }}") {
                val bills = CampusRepository.getInstance().getBillRecords(PrefsManager.getInstance(context), recordType = recordType)
                if (bills.isEmpty()) "未查询到账单记录"
                else "一卡通账单:\n| 时间 | 金额 | 类型 | 描述 |\n|---|---|---|---|\n" +
                    bills.take(30).joinToString("") { "| ${it.time} | ${it.amount} | ${it.type} | ${it.description} |\n" } +
                    if (bills.size > 30) "\n(共 ${bills.size} 条,仅显示前 30 条)" else ""
            }
            return ToolRegistry.Result(true, output)
        }
    }

    private val GetScoreSummary = object : CampusTool(
        "get_score_summary", "查询第二课堂成绩汇总"
    ) {
        override suspend fun execute(context: Context, args: JSONObject) = ToolRegistry.Result(
            true, cachedExecute("get_score_summary") {
                val s = CampusRepository.getInstance().getScoreSummary(PrefsManager.getInstance(context))
                val sb = StringBuilder("第二课堂总分: ${s.totalScore}\n\n各模块得分:\n")
                s.modules.forEach { sb.append("- ${it.moduleName}: ${it.score}\n") }
                sb.toString()
            }
        )
    }

    private val GetScoreDetail = object : CampusTool(
        "get_score_detail", "查询第二课堂成绩明细"
    ) {
        override suspend fun execute(context: Context, args: JSONObject) = ToolRegistry.Result(
            true, cachedExecute("get_score_detail") {
                val details = CampusRepository.getInstance().getScoreDetail(PrefsManager.getInstance(context))
                if (details.isEmpty()) "未查询到第二课堂明细"
                else "第二课堂成绩明细:\n| 模块 | 项目 | 得分 |\n|---|---|---|\n" +
                    details.joinToString("") { "| ${it.moduleName} | ${it.itemName} | ${it.score} |\n" }
            }
        )
    }

    private val GetTeachingEvaluation = object : CampusTool(
        "get_teaching_evaluation", "查询教学评价列表"
    ) {
        override suspend fun execute(context: Context, args: JSONObject) = ToolRegistry.Result(
            true, cachedExecute("get_teaching_evaluation") {
                val evals = CampusRepository.getInstance().getTeachingEvaluation(PrefsManager.getInstance(context))
                if (evals.isEmpty()) "未查询到教学评价"
                else "教学评价:\n| 课程 | 教师 | 评价状态 | 提交状态 |\n|---|---|---|---|\n" +
                    evals.joinToString("") { "| ${it.courseName} | ${it.teacher} | ${it.status} | ${it.submitStatus} |\n" }
            }
        )
    }

    private val GetLostFound = object : CampusTool(
        "get_lost_found", "查询失物招领列表",
        "type(\"1\"=寻物/\"2\"=招领,可选), page(页码,可选)"
    ) {
        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val type = args.optString("type", "").ifEmpty { "" }
            val page = args.optInt("page", 1).coerceAtLeast(1)
            val output = cachedExecute("get_lost_found:${type.ifEmpty { "all" }}:$page") {
                val resp = CampusRepository.getInstance().getLostList(PrefsManager.getInstance(context), page, type)
                val arr = resp.optJSONObject("data")?.optJSONArray("data")
                    ?: resp.optJSONArray("data")
                    ?: return@cachedExecute "未查询到失物招领信息"
                if (arr.length() == 0) return@cachedExecute "未查询到失物招领信息"
                val sb = StringBuilder("失物招领列表:\n| 类型 | 描述 | 校区 | 时间 |\n|---|---|---|---|\n")
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val typeLabel = if (item.optInt("type", 1) == 2) "招领" else "寻物"
                    val content = item.optString("content", "").take(50).replace("\n", " ")
                    val campus = item.optJSONObject("organise_info")?.optString("name", "") ?: ""
                    val time = item.optString("createtime", "").take(10)
                    sb.append("| $typeLabel | $content | $campus | $time |\n")
                }
                sb.toString()
            }
            return ToolRegistry.Result(true, output)
        }
    }

    /** 所有校园工具 */
    fun all(): List<ToolRegistry.Tool> = listOf(
        GetStudentInfo, GetGrades, GetSchedule, GetExamSchedule,
        GetSelectedCourses, GetBalance, GetEcardBill,
        GetScoreSummary, GetScoreDetail, GetTeachingEvaluation, GetLostFound
    )
}
