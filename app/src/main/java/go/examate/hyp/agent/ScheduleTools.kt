package go.examate.hyp.agent

import android.content.Context
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.widget.ScheduleWidgetProvider
import org.json.JSONObject
import java.time.LocalDate

/**
 * 课程表 / 待办工具 — 复用本地课程表模块的单一数据源,让 all in agent 能查今日课、加待办。
 *
 * 全部走本地存储(离线,已按当前教学周精确过滤),无需教务系统登录。
 * add_todo 写入后会主动刷新桌面小组件,保持三端(App/卡片/小组件)一致。
 */
object ScheduleTools {

    private fun weekdayCn(d: LocalDate): String = when (d.dayOfWeek.value) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"; 5 -> "周五"; 6 -> "周六"; else -> "周日"
    }

    /** 查询今天的课程 + 待办(本地,离线) */
    val GetTodaySchedule = object : ToolRegistry.Tool {
        override val name = "get_today_schedule"
        override val description = "查询今天的课程和待办(来自本地课程表,离线,已按当前教学周;无需教务登录)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val today = LocalDate.now()
            val courses = ScheduleStorage.coursesOn(context, today)
            val todos = ScheduleStorage.todosOn(context, today)
            if (courses.isEmpty() && todos.isEmpty()) {
                return ToolRegistry.Result(true, "${weekdayCn(today)} 今日无课,也没有待办。")
            }
            val sb = StringBuilder("${weekdayCn(today)} 今日安排:\n")
            if (courses.isNotEmpty()) {
                sb.append("课程:\n")
                courses.forEach { e ->
                    val sec = if (e.startSection == e.endSection) "第${e.startSection}节" else "第${e.startSection}-${e.endSection}节"
                    sb.append("- $sec ${e.courseName}")
                    if (e.classroom.isNotEmpty()) sb.append(" @${e.classroom}")
                    if (e.teacher.isNotEmpty()) sb.append(" (${e.teacher})")
                    sb.append("\n")
                }
            } else {
                sb.append("今日无课\n")
            }
            if (todos.isNotEmpty()) {
                sb.append("待办:\n")
                todos.forEach { t ->
                    sb.append("- ").append(if (t.done) "[已完成] " else "")
                    if (t.time.isNotEmpty()) sb.append("${t.time} ")
                    sb.append(t.title).append("\n")
                }
            }
            return ToolRegistry.Result(true, sb.toString().trimEnd())
        }
    }

    /** 添加一条待办(写入本地课表 + 刷新小组件) */
    val AddTodo = object : ToolRegistry.Tool {
        override val name = "add_todo"
        override val description = "在课程表待办中添加一条待办事项(写入本地,会同步到课表页与桌面小组件)"
        override val category = ToolRegistry.Category.PRIVILEGED
        override val parametersHint = "title(内容,必填), date(日期 yyyy-MM-dd 或 今天/明天/后天,可选,默认今天), time(时间 HH:mm,可选)"
        override val params = listOf(
            ToolRegistry.ParamDef("title", "string", "待办内容", true),
            ToolRegistry.ParamDef("date", "string", "日期 yyyy-MM-dd 或 今天/明天/后天,默认今天", false),
            ToolRegistry.ParamDef("time", "string", "时间 HH:mm,可选", false)
        )

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val title = args.optString("title", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少待办内容(title)") }
            val date = parseDate(args.optString("date", ""))
            val time = args.optString("time", "").trim()
            val todo = ScheduleTodo(
                id = "usr_${System.currentTimeMillis()}",
                title = title, date = date, time = time, done = false
            )
            ScheduleStorage.addTodo(context, todo)
            ScheduleWidgetProvider.refreshAll(context)
            val whenText = date + if (time.isNotEmpty()) " $time" else ""
            return ToolRegistry.Result(true, "已添加待办: $title ($whenText)")
        }
    }

    /** 解析日期:空/今天→今天,明天/后天,或 ISO yyyy-MM-dd;失败回退今天 */
    private fun parseDate(raw: String): String {
        val today = LocalDate.now()
        return when (val t = raw.trim()) {
            "", "今天" -> today.toString()
            "明天" -> today.plusDays(1).toString()
            "后天" -> today.plusDays(2).toString()
            else -> runCatching { LocalDate.parse(t).toString() }.getOrDefault(today.toString())
        }
    }

    fun all(): List<ToolRegistry.Tool> = listOf(GetTodaySchedule, AddTodo)
}
