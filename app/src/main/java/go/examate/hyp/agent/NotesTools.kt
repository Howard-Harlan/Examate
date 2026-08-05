package go.examate.hyp.agent

import android.content.Context
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.notes.NoteStorage
import go.examate.hyp.widget.ScheduleWidgetProvider
import org.json.JSONObject
import java.time.LocalDate

/**
 * 笔记与速记代办工具集 — 让 agent 能创建/列出笔记,并快速添加代办到当前课表。
 *
 * - CreateNote / ListNotes 为本地笔记操作(SAFE,只写本地文件)。
 * - QuickTodo 复用课表模块的代办存储,写入当前课表并刷新桌面小组件(PRIVILEGED,写操作)。
 */
object NotesTools {

    /** 1. 创建笔记 */
    private val CreateNote = object : ToolRegistry.Tool {
        override val name = "create_note"
        override val description = "创建一条笔记(Markdown),保存到本地笔记目录"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "title(标题,必填), content(正文,必填)"
        override val params = listOf(
            ToolRegistry.ParamDef("title", "string", "笔记标题", true),
            ToolRegistry.ParamDef("content", "string", "笔记正文(Markdown)", true)
        )
        override val displayLabel = "创建笔记"
        override val requiresLogin = false

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val title = args.optString("title", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 title 参数") }
            val content = args.optString("content", "")
                .ifEmpty { return ToolRegistry.Result(false, "缺少 content 参数") }
            val note = NoteStorage.createNote(context, title, content)
            return ToolRegistry.Result(true, "已创建笔记: ${note.title}")
        }
    }

    /** 2. 列出笔记 */
    private val ListNotes = object : ToolRegistry.Tool {
        override val name = "list_notes"
        override val description = "列出本地全部笔记(按更新时间倒序,仅返回标题)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""
        override val displayLabel = "列出笔记"
        override val requiresLogin = false

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val notes = NoteStorage.listNotes(context)
            if (notes.isEmpty()) {
                return ToolRegistry.Result(true, "暂无笔记。")
            }
            val sb = StringBuilder("共 ${notes.size} 条笔记:\n")
            notes.forEach { n ->
                sb.append("- ${n.title}\n")
            }
            return ToolRegistry.Result(true, sb.toString().trimEnd())
        }
    }

    /** 3. 速记代办 — 写入当前课表并刷新小组件 */
    private val QuickTodo = object : ToolRegistry.Tool {
        override val name = "quick_todo"
        override val description = "快速添加一条代办到当前课表指定日期(支持\"今天/明天/后天\"或 ISO 日期 yyyy-MM-dd)"
        override val category = ToolRegistry.Category.PRIVILEGED
        override val parametersHint = "title(代办内容,必填), date(日期,必填,支持\"今天/明天/后天\"或 yyyy-MM-dd), time(时间 HH:MM,可选)"
        override val params = listOf(
            ToolRegistry.ParamDef("title", "string", "代办内容", true),
            ToolRegistry.ParamDef("date", "string", "日期:今天/明天/后天 或 yyyy-MM-dd", true),
            ToolRegistry.ParamDef("time", "string", "具体时间 HH:MM(可选,空=全天代办)", false)
        )
        override val displayLabel = "添加速记代办"
        override val requiresLogin = false

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val title = args.optString("title", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 title 参数") }
            val dateRaw = args.optString("date", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 date 参数") }
            val time = args.optString("time", "").trim()

            val date = parseDate(dateRaw)
                ?: return ToolRegistry.Result(false, "无法解析日期: $dateRaw(支持 今天/明天/后天 或 yyyy-MM-dd)")
            if (time.isNotBlank()) {
                runCatching { java.time.LocalTime.parse(time) }.onFailure {
                    return ToolRegistry.Result(false, "时间格式错误: $time(应为 HH:MM)")
                }
            }

            val todo = ScheduleTodo(
                id = "todo_${System.currentTimeMillis()}",
                title = title,
                date = date.toString(),
                time = time
            )
            ScheduleStorage.addTodo(context, todo)
            ScheduleWidgetProvider.refreshAll(context)
            val whenText = if (time.isNotBlank()) "$date $time" else date.toString()
            return ToolRegistry.Result(true, "已添加速记代办: $title ($whenText)")
        }

        /** 解析日期:今天/明天/后天 或 ISO yyyy-MM-dd;失败返回 null */
        private fun parseDate(raw: String): LocalDate? {
            val s = raw.trim()
            return when (s) {
                "今天", "今日", "today" -> LocalDate.now()
                "明天", "明日", "tomorrow" -> LocalDate.now().plusDays(1)
                "后天" -> LocalDate.now().plusDays(2)
                else -> runCatching { LocalDate.parse(s) }.getOrNull()
            }
        }
    }

    fun all(): List<ToolRegistry.Tool> = listOf(CreateNote, ListNotes, QuickTodo)
}
