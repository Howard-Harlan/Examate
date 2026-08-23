package go.examate.hyp.campus.model

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile

// ════════════════════════════════════════
//  数据模型
// ════════════════════════════════════════

/**
 * 课表条目(本地持久化格式)
 *
 * 从服务端 [ScheduleItem] 解析而来,结构化存储星期/节次/周次,支持手动编辑与提醒。
 */
data class ScheduleEntry(
    /** 唯一标识(用于编辑/删除) */
    val id: String,
    val courseName: String,
    val teacher: String,
    val classroom: String,
    /** 星期几:1=周一 ... 7=周日 */
    val dayOfWeek: Int,
    /** 起始节次(1-based) */
    val startSection: Int,
    /** 结束节次(1-based,>= startSection) */
    val endSection: Int,
    /** 上课周次列表(如 [1,2,3,...,15]) */
    val weeks: List<Int>,
    /** 课程卡片颜色(ARGB Long) */
    val color: Long,
    /** 课前提醒分钟数(0=不提醒) */
    val reminderMinutes: Int = 0,
    /** 用户备注 */
    val note: String = ""
)

/**
 * 待办事项(关联到具体日期,与课表解耦)
 *
 * 不再绑定 dayOfWeek/section,而是直接关联到某一天(LocalDate)。
 * 这样:
 * - 代办可添加到任意日期,不受课程是否占用影响
 * - 周视图只显示"该天有代办"的标记
 * - 日视图显示当天所有代办 + 剩余课程
 *
 * @property date ISO 格式日期 yyyy-MM-dd
 * @property time 可选的具体时间 HH:MM(空=全天代办)
 */
data class ScheduleTodo(
    val id: String,
    val title: String,
    val date: String,
    val time: String = "",
    val done: Boolean = false,
    /** 优先级:0=无,1=低,2=中,3=高(影响排序与标识色) */
    val priority: Int = 0
)

// ════════════════════════════════════════
//  节次时间表(支持夏冬两套作息 + 自定义)
// ════════════════════════════════════════

/**
 * 夏季作息(默认,5月1日-9月30日)
 * 参考多所高校(青岛大学/西北工业大学等)通用时间表:50分钟一节,10分钟课间。
 */
val SUMMER_TIMES: List<Pair<String, String>> = listOf(
    "08:00" to "08:50",  // 1
    "09:00" to "09:50",  // 2
    "10:10" to "11:00",  // 3
    "11:10" to "12:00",  // 4
    "14:00" to "14:50",  // 5
    "15:00" to "15:50",  // 6
    "16:00" to "16:50",  // 7
    "17:00" to "17:50",  // 8
    "19:00" to "19:50",  // 9
    "20:00" to "20:50",  // 10
    "21:00" to "21:50",  // 11
    "22:00" to "22:50"   // 12
)

/**
 * 冬季作息(10月1日-次年4月30日,下午提前)
 * 参考多所高校通用时间表:50分钟一节,10分钟课间,下午13:30开始。
 */
val WINTER_TIMES: List<Pair<String, String>> = listOf(
    "08:00" to "08:50",  // 1
    "09:00" to "09:50",  // 2
    "10:10" to "11:00",  // 3
    "11:10" to "12:00",  // 4
    "13:30" to "14:20",  // 5
    "14:30" to "15:20",  // 6
    "15:30" to "16:20",  // 7
    "16:30" to "17:20",  // 8
    "18:30" to "19:20",  // 9
    "19:30" to "20:20",  // 10
    "20:30" to "21:20",  // 11
    "21:30" to "22:20"   // 12
)

/** 当前生效的节次时间(从存储读取,默认夏季) */
var PERIOD_TIMES: List<Pair<String, String>> = SUMMER_TIMES

const val MAX_SECTIONS = 12

/** 默认学期总周数(可在课表设置中修改) */
const val DEFAULT_TOTAL_WEEKS = 20

/** 默认期末周周次(可在课表设置中修改) */
const val DEFAULT_EXAM_WEEK = 18

/**
 * 计算学期开学第一个星期一(用于推算周次和表头日期)
 *
 * 优先使用 [meta] 中设置的 semesterStartDate;未设置则按默认推断:
 * - 9月及以后:当年9月1日(秋季开学)
 * - 9月以前:当年2月25日(春季开学)
 *
 * 注意:返回值与 inferCurrentWeek 共用,确保周次推断与表头日期同步。
 */
fun getSemesterStart(meta: ScheduleStorage.ScheduleMeta? = null): LocalDate {
    return meta?.semesterStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now().let { now ->
            if (now.monthValue >= 9) LocalDate.of(now.year, 9, 1)
            else LocalDate.of(now.year, 2, 25)
        }
}

/** 课程卡片配色盘(按课程名 hash 分配) */
val COURSE_COLORS: List<Long> = listOf(
    0xFF6C8EE3, // 蓝
    0xFF5BAA6F, // 绿
    0xFFE39B5B, // 橙
    0xFFB57BB8, // 紫
    0xFFE36C6C, // 红
    0xFF5BBAA8, // 青
    0xFFB8B57B, // 卡其
    0xFF7B8EB8  // 灰蓝
)

/** 按课程名 hash 分配颜色(稳定,同名课程颜色一致) */
fun courseColorFor(name: String): Long =
    COURSE_COLORS[(name.hashCode().and(Int.MAX_VALUE)) % COURSE_COLORS.size]

/**
 * 随机选一个课程颜色(用于新增课程,避免多个新课程颜色都一样)
 * @param exclude 需要排除的颜色(如刚用过的),避免连续重复
 */
fun randomCourseColor(exclude: Long? = null): Long {
    val pool = if (exclude != null) COURSE_COLORS.filter { it != exclude } else COURSE_COLORS
    return pool.random()
}

// ════════════════════════════════════════
//  从服务端 ScheduleItem → ScheduleEntry 转换
// ════════════════════════════════════════

private val DAY_KEYWORDS = mapOf(
    1 to listOf("星期一", "周一", "1"),
    2 to listOf("星期二", "周二", "2"),
    3 to listOf("星期三", "周三", "3"),
    4 to listOf("星期四", "周四", "4"),
    5 to listOf("星期五", "周五", "5"),
    6 to listOf("星期六", "周六", "6"),
    7 to listOf("星期日", "周日", "星期天", "7", "日")
)

/** 解析星期字符串("星期一"/"周一"/"1") → 1-7,失败返回 0 */
fun parseDayOfWeek(day: String): Int {
    if (day.isBlank()) return 0
    DAY_KEYWORDS.forEach { (num, keywords) ->
        if (keywords.any { day.trim() == it || day.contains(it) }) return num
    }
    return 0
}

/** 解析节次字符串("1-2节"/"3-4节(单)"/"5") → (start, end) */
fun parseSection(section: String): Pair<Int, Int> {
    val s = section.trim()
        .replace(Regex("[^0-9\\-]"), "") // 去掉"节"/"单"/"双"等非数字字符
    if (s.isEmpty()) return 0 to 0
    return try {
        if (s.contains("-")) {
            val parts = s.split("-")
            parts[0].trim().toInt() to parts[1].trim().toInt()
        } else {
            val n = s.toInt()
            n to n
        }
    } catch (_: Exception) { 0 to 0 }
}

/** 解析周次字符串("1-15周"/"1,3,5,7周"/"1-16") → List<Int> */
fun parseWeeks(weeksStr: String): List<Int> {
    val cleaned = weeksStr.replace(Regex("[^0-9,\\-]"), "")
    if (cleaned.isEmpty()) return emptyList()
    val result = mutableListOf<Int>()
    cleaned.split(",").forEach { part ->
        val p = part.trim()
        if (p.contains("-")) {
            val range = p.split("-")
            val start = range.getOrNull(0)?.trim()?.toIntOrNull() ?: return@forEach
            val end = range.getOrNull(1)?.trim()?.toIntOrNull() ?: return@forEach
            result.addAll(start..end)
        } else {
            p.toIntOrNull()?.let { result.add(it) }
        }
    }
    return result.distinct().sorted()
}

/** 将服务端 List<ScheduleItem> 转为 List<ScheduleEntry> */
fun convertScheduleItems(items: List<ScheduleItem>, creditMode: String = "note"): List<ScheduleEntry> {
    return items.mapIndexed { idx, item ->
        val (startSec, endSec) = parseSection(item.section)
        val day = parseDayOfWeek(item.day)
        // 学分处理
        val creditNote = when {
            creditMode == "none" || item.credit.isEmpty() -> ""
            creditMode == "credit_only" -> "学分:${item.credit}"
            else -> {
                val parts = mutableListOf<String>()
                if (item.credit.isNotEmpty()) parts.add("学分:${item.credit}")
                if (item.courseCategory.isNotEmpty()) parts.add(item.courseCategory)
                parts.joinToString("; ")
            }
        }
        ScheduleEntry(
            id = "srv_$idx",
            courseName = item.courseName,
            teacher = item.teacher,
            classroom = item.classroom,
            dayOfWeek = day,
            startSection = startSec,
            endSection = endSec,
            weeks = parseWeeks(item.weeks),
            color = courseColorFor(item.courseName),
            note = creditNote
        )
    }.filter { it.dayOfWeek in 1..7 && it.startSection > 0 }
}

// ════════════════════════════════════════
//  本地持久化(JSON 文件)
// ════════════════════════════════════════

/**
 * 课表本地存储:JSON 文件,按用户名隔离。
 *
 * 文件路径: `{filesDir}/schedule_{username}.json`
 * 一次导入后持久保存,手动刷新才同步服务端。
 */
object ScheduleStorage {

    private fun file(context: Context, username: String): File =
        File(context.filesDir, "schedule_${username.ifEmpty { "default" }}.json")

    fun save(context: Context, username: String, entries: List<ScheduleEntry>, todos: List<ScheduleTodo>) {
        val json = JSONObject().apply {
            put("entries", JSONArray().apply {
                entries.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("courseName", e.courseName)
                        put("teacher", e.teacher)
                        put("classroom", e.classroom)
                        put("dayOfWeek", e.dayOfWeek)
                        put("startSection", e.startSection)
                        put("endSection", e.endSection)
                        put("weeks", JSONArray(e.weeks))
                        put("color", e.color)
                        put("reminderMinutes", e.reminderMinutes)
                        put("note", e.note)
                    })
                }
            })
            put("todos", JSONArray().apply {
                todos.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("date", t.date)
                        put("time", t.time)
                        put("done", t.done)
                        put("priority", t.priority)
                    })
                }
            })
            put("savedAt", System.currentTimeMillis())
        }
        file(context, username).writeText(json.toString())
        // 课表变更后重新调度课前提醒(仅当前激活的课表)
        runCatching {
            if (getCurrentScheduleMeta(context).id == username) {
                go.examate.hyp.campus.ScheduleReminderScheduler.reschedule(context)
            }
        }
    }

    fun loadEntries(context: Context, username: String): List<ScheduleEntry> {
        val f = file(context, username)
        if (!f.exists()) return emptyList()
        return try {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("entries") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ScheduleEntry(
                    id = o.optString("id"),
                    courseName = o.optString("courseName"),
                    teacher = o.optString("teacher"),
                    classroom = o.optString("classroom"),
                    dayOfWeek = o.optInt("dayOfWeek"),
                    startSection = o.optInt("startSection"),
                    endSection = o.optInt("endSection"),
                    weeks = (0 until (o.optJSONArray("weeks")?.length() ?: 0)).mapNotNull { w ->
                        o.optJSONArray("weeks")?.optInt(w)
                    },
                    color = o.optLong("color", courseColorFor(o.optString("courseName"))),
                    reminderMinutes = o.optInt("reminderMinutes"),
                    note = o.optString("note")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun loadTodos(context: Context, username: String): List<ScheduleTodo> {
        val f = file(context, username)
        if (!f.exists()) return emptyList()
        return try {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("todos") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                // 兼容旧数据:无 date 字段时用今天,避免空指针
                val date = o.optString("date").ifBlank { LocalDate.now().toString() }
                ScheduleTodo(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    date = date,
                    time = o.optString("time"),
                    done = o.optBoolean("done"),
                    priority = o.optInt("priority", 0)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── 作息时间存储(多套时间表) ──

    /**
     * 时间表元数据:支持多套时间表(如"相山校区夏令时"/"相山校区冬令时"/自定义)。
     *
     * @property id 唯一ID
     * @property name 时间表名称
     * @property isCurrent 是否为当前激活的时间表
     * @property sectionCount 节数(默认 12,可自行增减)
     * @property classDurationMinutes 单节课时长(分钟,默认 45),用于新增节次时自动推算时间
     */
    data class TimeTableMeta(
        val id: String,
        val name: String,
        val isCurrent: Boolean = false,
        val sectionCount: Int = 12,
        val classDurationMinutes: Int = 45,
        val periods: List<Pair<String, String>> = emptyList()
    )

    /** 淮北师范大学相山校区夏令时(默认时间表,来自官方 CSV) */
    val CHNU_SUMMER_PERIODS: List<Pair<String, String>> = listOf(
        "08:00" to "08:45",  // 1
        "08:55" to "09:40",  // 2
        "10:00" to "10:45",  // 3
        "10:55" to "11:40",  // 4
        "14:30" to "15:15",  // 5
        "15:25" to "16:10",  // 6
        "16:30" to "17:15",  // 7
        "17:25" to "18:10",  // 8
        "19:30" to "20:15",  // 9
        "20:25" to "21:10"   // 10
    )

    /** 淮北师范大学相山校区冬令时(默认时间表,来自官方 CSV) */
    val CHNU_WINTER_PERIODS: List<Pair<String, String>> = listOf(
        "08:00" to "08:45",  // 1
        "08:55" to "09:40",  // 2
        "10:00" to "10:45",  // 3
        "10:55" to "11:40",  // 4
        "14:00" to "14:45",  // 5
        "14:55" to "15:40",  // 6
        "16:00" to "16:45",  // 7
        "16:55" to "17:40",  // 8
        "19:00" to "19:45",  // 9
        "19:55" to "20:40"   // 10
    )

    /** 淮北师范大学滨湖校区夏令时(上午较相山校区推迟 20 分钟,下午及以后不变) */
    val CHNU_BINHU_SUMMER_PERIODS: List<Pair<String, String>> = listOf(
        "08:20" to "09:05",  // 1
        "09:15" to "10:00",  // 2
        "10:20" to "11:05",  // 3
        "11:15" to "12:00",  // 4
        "14:30" to "15:15",  // 5
        "15:25" to "16:10",  // 6
        "16:30" to "17:15",  // 7
        "17:25" to "18:10",  // 8
        "19:30" to "20:15",  // 9
        "20:25" to "21:10"   // 10
    )

    /** 淮北师范大学滨湖校区冬令时(上午较相山校区推迟 20 分钟,下午及以后不变) */
    val CHNU_BINHU_WINTER_PERIODS: List<Pair<String, String>> = listOf(
        "08:20" to "09:05",  // 1
        "09:15" to "10:00",  // 2
        "10:20" to "11:05",  // 3
        "11:15" to "12:00",  // 4
        "14:00" to "14:45",  // 5
        "14:55" to "15:40",  // 6
        "16:00" to "16:45",  // 7
        "16:55" to "17:40",  // 8
        "19:00" to "19:45",  // 9
        "19:55" to "20:40"   // 10
    )

    /** 默认时间表列表(首次使用时创建) */
    private fun defaultTimeTableMetas(): List<TimeTableMeta> = listOf(
        TimeTableMeta(
            id = "chnu_summer",
            name = "相山校区夏令时",
            isCurrent = true,
            sectionCount = CHNU_SUMMER_PERIODS.size,
            classDurationMinutes = 45,
            periods = CHNU_SUMMER_PERIODS
        ),
        TimeTableMeta(
            id = "chnu_winter",
            name = "相山校区冬令时",
            isCurrent = false,
            sectionCount = CHNU_WINTER_PERIODS.size,
            classDurationMinutes = 45,
            periods = CHNU_WINTER_PERIODS
        ),
        TimeTableMeta(
            id = "chnu_binhu_summer",
            name = "滨湖校区夏令时",
            isCurrent = false,
            sectionCount = CHNU_BINHU_SUMMER_PERIODS.size,
            classDurationMinutes = 45,
            periods = CHNU_BINHU_SUMMER_PERIODS
        ),
        TimeTableMeta(
            id = "chnu_binhu_winter",
            name = "滨湖校区冬令时",
            isCurrent = false,
            sectionCount = CHNU_BINHU_WINTER_PERIODS.size,
            classDurationMinutes = 45,
            periods = CHNU_BINHU_WINTER_PERIODS
        )
    )

    private fun timeTableFile(context: Context): File =
        File(context.filesDir, "schedule_timetables.json")

    /** 加载所有时间表元数据(含 periods) */
    fun loadTimeTableMetas(context: Context): List<TimeTableMeta> {
        val f = timeTableFile(context)
        if (!f.exists()) {
            // 首次使用:尝试迁移旧数据,否则用默认时间表
            val migrated = migrateOldTimeSchedule(context)
            if (migrated.isNotEmpty()) return migrated
            return defaultTimeTableMetas()
        }
        return try {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("timetables") ?: return defaultTimeTableMetas()
            val result = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val periodsArr = o.optJSONArray("periods")
                val periods = (0 until (periodsArr?.length() ?: 0)).mapNotNull { p ->
                    val po = periodsArr?.optJSONObject(p) ?: return@mapNotNull null
                    po.optString("start") to po.optString("end")
                }
                TimeTableMeta(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    isCurrent = o.optBoolean("isCurrent"),
                    sectionCount = o.optInt("sectionCount", 12),
                    classDurationMinutes = o.optInt("classDurationMinutes", 45),
                    periods = periods
                )
            }
            if (result.isEmpty()) return defaultTimeTableMetas()
            // 迁移:老用户的文件可能只有相山校区时间表,补全缺失的默认时间表(如滨湖校区)
            val existingIds = result.map { it.id }.toSet()
            val missing = defaultTimeTableMetas().filter { it.id !in existingIds }
            if (missing.isNotEmpty()) {
                val merged = result + missing
                saveTimeTableMetas(context, merged)
                merged
            } else {
                result
            }
        } catch (_: Exception) { defaultTimeTableMetas() }
    }

    /** 保存所有时间表 */
    private fun saveTimeTableMetas(context: Context, metas: List<TimeTableMeta>) {
        val json = JSONObject().apply {
            put("timetables", JSONArray().apply {
                metas.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("name", m.name)
                        put("isCurrent", m.isCurrent)
                        put("sectionCount", m.sectionCount)
                        put("classDurationMinutes", m.classDurationMinutes)
                        put("periods", JSONArray().apply {
                            m.periods.forEach { (start, end) ->
                                put(JSONObject().put("start", start).put("end", end))
                            }
                        })
                    })
                }
            })
            put("savedAt", System.currentTimeMillis())
        }
        timeTableFile(context).writeText(json.toString())
        // 同步更新内存中的 PERIOD_TIMES 为当前时间表
        metas.firstOrNull { it.isCurrent }?.let { PERIOD_TIMES = it.periods }
    }

    /** 获取当前激活的时间表 */
    fun getCurrentTimeTable(context: Context): TimeTableMeta {
        val metas = loadTimeTableMetas(context)
        return metas.firstOrNull { it.isCurrent } ?: metas.firstOrNull() ?: defaultTimeTableMetas().first()
    }

    /** 新增时间表(默认 12 节,基于默认时间推算) */
    fun addTimeTableMeta(context: Context, name: String): TimeTableMeta {
        val metas = loadTimeTableMetas(context).toMutableList()
        // 默认 12 节,以夏季作息为模板
        val periods = SUMMER_TIMES.take(12)
        val newMeta = TimeTableMeta(
            id = "tt_${System.currentTimeMillis()}",
            name = name,
            isCurrent = false,
            sectionCount = 12,
            classDurationMinutes = 45,
            periods = periods
        )
        metas.add(newMeta)
        saveTimeTableMetas(context, metas)
        return newMeta
    }

    /** 删除时间表(不允许删除最后一套) */
    fun deleteTimeTableMeta(context: Context, id: String): Boolean {
        val metas = loadTimeTableMetas(context).toMutableList()
        if (metas.size <= 1) return false
        val target = metas.firstOrNull { it.id == id } ?: return false
        metas.remove(target)
        if (target.isCurrent && metas.isNotEmpty()) {
            metas[0] = metas[0].copy(isCurrent = true)
        }
        saveTimeTableMetas(context, metas)
        return true
    }

    /** 设为当前时间表 */
    fun setCurrentTimeTable(context: Context, id: String) {
        val metas = loadTimeTableMetas(context).map {
            it.copy(isCurrent = it.id == id)
        }
        saveTimeTableMetas(context, metas)
    }

    /** 更新时间表(名称/节数/时长/periods) */
    fun updateTimeTableMeta(context: Context, meta: TimeTableMeta) {
        val metas = loadTimeTableMetas(context).map {
            if (it.id == meta.id) meta else it
        }
        saveTimeTableMetas(context, metas)
    }

    /** 迁移旧版单时间表数据(schedule_times.json)到新格式 */
    private fun migrateOldTimeSchedule(context: Context): List<TimeTableMeta> {
        val oldFile = File(context.filesDir, "schedule_times.json")
        if (!oldFile.exists()) return emptyList()
        return try {
            val json = JSONObject(oldFile.readText())
            val type = json.optString("type", "summer")
            val arr = json.optJSONArray("times") ?: return emptyList()
            val times = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                o.optString("start") to o.optString("end")
            }
            if (times.isEmpty()) return emptyList()
            // 旧数据转为一个"自定义"时间表,与默认时间表并存
            val migrated = TimeTableMeta(
                id = "migrated_$type",
                name = if (type == "summer") "原夏季作息(已迁移)" else if (type == "winter") "原冬季作息(已迁移)" else "原自定义作息(已迁移)",
                isCurrent = true,
                sectionCount = times.size,
                classDurationMinutes = 45,
                periods = times
            )
            val metas = listOf(
                defaultTimeTableMetas()[0].copy(isCurrent = false),
                defaultTimeTableMetas()[1].copy(isCurrent = false),
                migrated
            )
            saveTimeTableMetas(context, metas)
            // 删除旧文件避免重复迁移
            oldFile.delete()
            metas
        } catch (_: Exception) { emptyList() }
    }

    // ── 旧版兼容 API(内部转发到当前时间表) ──

    private fun timeFile(context: Context): File =
        File(context.filesDir, "schedule_times.json")

    /** 保存作息时间配置(旧版 API:更新当前时间表的 periods) */
    fun saveTimeSchedule(context: Context, type: String, times: List<Pair<String, String>>) {
        val metas = loadTimeTableMetas(context).toMutableList()
        val idx = metas.indexOfFirst { it.isCurrent }
        if (idx >= 0) {
            metas[idx] = metas[idx].copy(periods = times, sectionCount = times.size)
            saveTimeTableMetas(context, metas)
        } else {
            // 兜底:写入第一个时间表
            if (metas.isNotEmpty()) {
                metas[0] = metas[0].copy(periods = times, sectionCount = times.size, isCurrent = true)
                saveTimeTableMetas(context, metas)
            }
        }
        PERIOD_TIMES = times
    }

    /** 加载作息时间配置,返回 (type, times)(旧版 API:读取当前时间表) */
    fun loadTimeSchedule(context: Context): Pair<String, List<Pair<String, String>>>? {
        val current = getCurrentTimeTable(context)
        PERIOD_TIMES = current.periods
        // type 字段仅供旧 UI 兼容,用 id 推断
        val type = when {
            current.id.contains("summer") -> "summer"
            current.id.contains("winter") -> "winter"
            else -> "custom"
        }
        return type to current.periods
    }

    // ── 多套课表管理 ──

    /**
     * 课表元数据:支持多套课表(如"上学期"/"下学期"),每套有独立的提醒设置。
     *
     * @property id 课表唯一ID
     * @property name 课表名称(如"2024上学期")
     * @property isCurrent 是否为当前激活的课表
     * @property reminderMinutes 课前提醒分钟数(0=不提醒)
     * @property reminderStyle 提醒方式:"notification"(消息通知) 或 "ring"(响铃)
     * @property ringtoneUri 提示铃声URI(空=默认铃声)
     * @property showSaturday 是否显示周六列
     * @property showSunday 是否显示周日列
     * @property semesterStartDate 开学第一个星期一(ISO 格式 yyyy-MM-dd),用于推算当前周次;空则按默认推断
     * @property totalWeeks 学期总周数(周次选择器网格范围),默认 20
     * @property examWeek 期末周周次(第几周考试),默认 18
     */
    data class ScheduleMeta(
        val id: String,
        val name: String,
        val isCurrent: Boolean = false,
        val reminderMinutes: Int = 0,
        val reminderStyle: String = "notification",
        val ringtoneUri: String = "",
        val showSaturday: Boolean = true,
        val showSunday: Boolean = true,
        val semesterStartDate: String? = null,
        val totalWeeks: Int = DEFAULT_TOTAL_WEEKS,
        val examWeek: Int = DEFAULT_EXAM_WEEK,
        /** 课程卡片字体缩放档:0=小 1=标准 2=大 */
        val fontScale: Int = 1
    )

    /** 提醒方式选项 */
    val REMINDER_STYLES = listOf(
        "notification" to "消息通知",
        "ring" to "响铃提醒"
    )

    /** 默认课表元数据列表 */
    private fun defaultScheduleMetas(): List<ScheduleMeta> = listOf(
        ScheduleMeta(id = "default", name = "默认课表", isCurrent = true)
    )

    private fun metaFile(context: Context): File =
        File(context.filesDir, "schedule_metas.json")

    /** 加载所有课表元数据 */
    fun loadScheduleMetas(context: Context): List<ScheduleMeta> {
        val f = metaFile(context)
        if (!f.exists()) return defaultScheduleMetas()
        return try {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("metas") ?: return defaultScheduleMetas()
            val result = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ScheduleMeta(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    isCurrent = o.optBoolean("isCurrent"),
                    reminderMinutes = o.optInt("reminderMinutes"),
                    reminderStyle = o.optString("reminderStyle", "notification"),
                    ringtoneUri = o.optString("ringtoneUri"),
                    showSaturday = o.optBoolean("showSaturday", true),
                    showSunday = o.optBoolean("showSunday", true),
                    semesterStartDate = o.optString("semesterStartDate").takeIf { it.isNotBlank() },
                    totalWeeks = o.optInt("totalWeeks", DEFAULT_TOTAL_WEEKS),
                    examWeek = o.optInt("examWeek", DEFAULT_EXAM_WEEK),
                    fontScale = o.optInt("fontScale", 1)
                )
            }
            if (result.isEmpty()) defaultScheduleMetas() else result
        } catch (_: Exception) { defaultScheduleMetas() }
    }

    /** 保存所有课表元数据 */
    fun saveScheduleMetas(context: Context, metas: List<ScheduleMeta>) {
        val json = JSONObject().apply {
            put("metas", JSONArray().apply {
                metas.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("name", m.name)
                        put("isCurrent", m.isCurrent)
                        put("reminderMinutes", m.reminderMinutes)
                        put("reminderStyle", m.reminderStyle)
                        put("ringtoneUri", m.ringtoneUri)
                        put("showSaturday", m.showSaturday)
                        put("showSunday", m.showSunday)
                        put("semesterStartDate", m.semesterStartDate ?: "")
                        put("totalWeeks", m.totalWeeks)
                        put("examWeek", m.examWeek)
                        put("fontScale", m.fontScale)
                    })
                }
            })
            put("savedAt", System.currentTimeMillis())
        }
        metaFile(context).writeText(json.toString())
    }

    /** 获取当前激活的课表 */
    fun getCurrentScheduleMeta(context: Context): ScheduleMeta {
        val metas = loadScheduleMetas(context)
        return metas.firstOrNull { it.isCurrent } ?: metas.firstOrNull() ?: defaultScheduleMetas().first()
    }

    /** 新增一套课表 */
    fun addScheduleMeta(context: Context, name: String): ScheduleMeta {
        val metas = loadScheduleMetas(context).toMutableList()
        val newMeta = ScheduleMeta(
            id = "sch_${System.currentTimeMillis()}",
            name = name,
            isCurrent = false
        )
        metas.add(newMeta)
        saveScheduleMetas(context, metas)
        return newMeta
    }

    /** 删除一套课表(不允许删除最后一套) */
    fun deleteScheduleMeta(context: Context, id: String): Boolean {
        val metas = loadScheduleMetas(context).toMutableList()
        if (metas.size <= 1) return false
        val target = metas.firstOrNull { it.id == id } ?: return false
        metas.remove(target)
        // 若删除的是当前课表,将第一套设为当前
        if (target.isCurrent && metas.isNotEmpty()) {
            metas[0] = metas[0].copy(isCurrent = true)
        }
        saveScheduleMetas(context, metas)
        return true
    }

    /** 设为当前课表 */
    fun setCurrentSchedule(context: Context, id: String) {
        val metas = loadScheduleMetas(context).map {
            it.copy(isCurrent = it.id == id)
        }
        saveScheduleMetas(context, metas)
    }

    /** 更新课表元数据(名称/提醒设置) */
    fun updateScheduleMeta(context: Context, meta: ScheduleMeta) {
        val metas = loadScheduleMetas(context).map {
            if (it.id == meta.id) meta else it
        }
        saveScheduleMetas(context, metas)
        // 提醒设置变更后重新调度
        if (meta.isCurrent) {
            runCatching { go.examate.hyp.campus.ScheduleReminderScheduler.reschedule(context) }
        }
    }

    // ════════════════════════════════════════
    //  单一数据源:供日视图 / AI 聊天卡片 / 桌面小组件共用
    //  按"当前教学周 + 星期"过滤,确保三方展示一致
    // ════════════════════════════════════════

    /** 当前激活课表是否存在任何课程(用于判断是否有本地课表可用) */
    fun hasSchedule(context: Context): Boolean =
        loadEntries(context, getCurrentScheduleMeta(context).id).isNotEmpty()

    /** 计算某日期属于第几教学周(开学前返回 -1) */
    fun weekOfDate(context: Context, date: LocalDate): Int {
        val start = getSemesterStart(getCurrentScheduleMeta(context))
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, date)
        return if (days < 0) -1 else (days / 7).toInt() + 1
    }

    /** 某日期的实际课程(已按当前教学周过滤,按起始节次排序) */
    fun coursesOn(context: Context, date: LocalDate): List<ScheduleEntry> {
        val meta = getCurrentScheduleMeta(context)
        val start = getSemesterStart(meta)
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, date)
        val week = if (days < 0) -1 else (days / 7).toInt() + 1
        if (week < 1) return emptyList()
        val dow = date.dayOfWeek.value  // 1=周一 ... 7=周日
        return loadEntries(context, meta.id)
            .filter { it.dayOfWeek == dow && week in it.weeks }
            .sortedBy { it.startSection }
    }

    /** 某日期的待办(未完成优先,再按时间排序) */
    fun todosOn(context: Context, date: LocalDate): List<ScheduleTodo> {
        val d = date.toString()
        return loadTodos(context, getCurrentScheduleMeta(context).id)
            .filter { it.date == d }
            .sortedWith(compareBy<ScheduleTodo> { it.done }.thenBy { it.time.ifBlank { "99:99" } })
    }

    /** 追加一条待办到当前课表(供 AI 工具/外部写入),整体覆盖保存 entries+todos */
    fun addTodo(context: Context, todo: ScheduleTodo) {
        val id = getCurrentScheduleMeta(context).id
        val entries = loadEntries(context, id)
        val todos = loadTodos(context, id) + todo
        save(context, id, entries, todos)
    }
}

// ════════════════════════════════════════
//  文件导入:CSV / Excel(xlsx)
// ════════════════════════════════════════

/**
 * 从文件导入课表。
 *
 * 支持格式:
 * - CSV:每行一条课程,列顺序:课程名,教师,教室,星期,节次,周次
 * - xlsx:解析第一个 Sheet,第一行为表头(需含"课程名"/"星期"/"节次"等关键词),后续为数据行
 *
 * @return 解析出的课程列表,失败抛异常
 */
object ScheduleImporter {

    /** CSV 表头/列名关键词映射 */
    private val COLUMN_KEYWORDS = mapOf(
        "courseName" to listOf("课程名", "课程名称", "课程", "name", "course"),
        "teacher" to listOf("教师", "老师", "teacher"),
        "classroom" to listOf("教室", "地点", "classroom", "location", "room"),
        "day" to listOf("星期", "周次几", "day"),
        "section" to listOf("节次", "节", "section", "period"),
        "weeks" to listOf("周", "周次", "weeks", "week")
    )

    /** 导入结果 */
    data class ImportResult(val entries: List<ScheduleEntry>, val total: Int, val skipped: Int)

    /** 主入口:根据文件扩展名自动选择解析器 */
    fun importFromFile(context: Context, uri: Uri): ImportResult {
        val fileName = getFileName(context, uri) ?: ""
        return when {
            fileName.endsWith(".csv", true) -> importCsv(context, uri)
            fileName.endsWith(".xlsx", true) -> importXlsx(context, uri)
            fileName.endsWith(".xls", true) -> throw IllegalArgumentException("暂不支持 .xls 格式,请另存为 .xlsx 或 .csv")
            else -> throw IllegalArgumentException("不支持的文件格式,请使用 .csv 或 .xlsx")
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) it.getString(idx) else null
        }
    }

    // ── CSV 解析 ──

    private fun importCsv(context: Context, uri: Uri): ImportResult {
        // 读取原始字节,自动检测编码(UTF-8/GBK),并去除 BOM
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取文件")
        if (rawBytes.isEmpty()) throw IllegalArgumentException("CSV 文件为空")

        // 检测编码:UTF-8 BOM → UTF-8;否则尝试 UTF-8,失败回退 GBK
        val text = decodeCsvBytes(rawBytes)
        val lines = text.split("\n").map { it.removeSuffix("\r") }.filter { it.isNotBlank() }
        if (lines.isEmpty()) throw IllegalArgumentException("CSV 文件为空")

        val delimiter = if (lines[0].contains("\t")) "\t" else ","
        val headers = splitCsvLine(lines[0], delimiter).map { it.trim() }
        val colMap = mapColumns(headers)

        // 校验:至少要识别到课程名列,否则给出明确错误
        if (!colMap.containsKey("courseName")) {
            throw IllegalArgumentException("未识别到表头中的[课程名]列,检测到的表头: ${headers.joinToString(" | ")}")
        }

        val entries = mutableListOf<ScheduleEntry>()
        var skipped = 0
        for (i in 1 until lines.size) {
            val parts = splitCsvLine(lines[i], delimiter).map { it.trim() }
            try {
                val name = parts.getOrNull(colMap["courseName"] ?: -1)?.takeIf { it.isNotEmpty() } ?: run { skipped++; continue }
                val teacher = parts.getOrNull(colMap["teacher"] ?: -1) ?: ""
                val classroom = parts.getOrNull(colMap["classroom"] ?: -1) ?: ""
                val dayStr = parts.getOrNull(colMap["day"] ?: -1) ?: ""
                val sectionStr = parts.getOrNull(colMap["section"] ?: -1) ?: ""
                val weeksStr = parts.getOrNull(colMap["weeks"] ?: -1) ?: "1-20"

                val day = parseDayOfWeek(dayStr)
                val (startSec, endSec) = parseSection(sectionStr)
                if (day !in 1..7 || startSec <= 0) { skipped++; continue }

                entries.add(ScheduleEntry(
                    id = "file_${System.currentTimeMillis()}_${i}",
                    courseName = name,
                    teacher = teacher,
                    classroom = classroom,
                    dayOfWeek = day,
                    startSection = startSec,
                    endSection = if (endSec >= startSec) endSec else startSec,
                    weeks = parseWeeks(weeksStr).ifEmpty { (1..20).toList() },
                    color = courseColorFor(name)
                ))
            } catch (_: Exception) { skipped++ }
        }
        return ImportResult(entries, entries.size, skipped)
    }

    /** 解码 CSV 字节:优先 UTF-8 BOM,其次 UTF-8,最后 GBK(中文 Excel 常见) */
    private fun decodeCsvBytes(bytes: ByteArray): String {
        // UTF-8 BOM (EF BB BF)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16 LE BOM (FF FE)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        // 尝试 UTF-8,若含非法序列则回退 GBK
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            String(bytes, charset("GBK"))
        }
    }

    /** 拆分 CSV 行(支持引号包裹的字段) */
    private fun splitCsvLine(line: String, delimiter: String): List<String> {
        if (delimiter == "\t") return line.split("\t")
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    // ── XLSX 解析(无第三方库,直接解析 zip+xml) ──

    /**
     * xlsx 本质是 zip 压缩包,内含 XML 文件。
     * 解析 xl/worksheets/sheet1.xml 和 xl/sharedStrings.xml(共享字符串表)。
     */
    private fun importXlsx(context: Context, uri: Uri): ImportResult {
        val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.xlsx")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalArgumentException("无法读取文件")

        try {
            val zipFile = ZipFile(tempFile)
            // 读取共享字符串表
            val sharedStrings = mutableListOf<String>()
            zipFile.getEntry("xl/sharedStrings.xml")?.let { entry ->
                zipFile.getInputStream(entry).use { stream ->
                    parseSharedStrings(stream.bufferedReader().use { it.readText() }, sharedStrings)
                }
            }
            // 读取第一个 sheet
            val sheetEntry = zipFile.getEntry("xl/worksheets/sheet1.xml")
                ?: throw IllegalArgumentException("xlsx 中未找到工作表")
            val rows: List<List<String>> = zipFile.getInputStream(sheetEntry).use { stream ->
                parseSheetRows(stream.bufferedReader().use { it.readText() }, sharedStrings)
            }
            zipFile.close()

            if (rows.isEmpty()) throw IllegalArgumentException("工作表为空")

            // 第一行为表头
            val headers = rows[0].map { it.trim() }
            val colMap = mapColumns(headers)

            // 校验:至少要识别到课程名列
            if (!colMap.containsKey("courseName")) {
                throw IllegalArgumentException("未识别到表头中的[课程名]列,检测到的表头: ${headers.joinToString(" | ")}")
            }

            val entries = mutableListOf<ScheduleEntry>()
            var skipped = 0
            for (i in 1 until rows.size) {
                val row = rows[i]
                try {
                    val name = row.getOrNull(colMap["courseName"] ?: -1)?.takeIf { it.isNotEmpty() } ?: run { skipped++; continue }
                    val teacher = row.getOrNull(colMap["teacher"] ?: -1) ?: ""
                    val classroom = row.getOrNull(colMap["classroom"] ?: -1) ?: ""
                    val dayStr = row.getOrNull(colMap["day"] ?: -1) ?: ""
                    val sectionStr = row.getOrNull(colMap["section"] ?: -1) ?: ""
                    val weeksStr = row.getOrNull(colMap["weeks"] ?: -1) ?: "1-20"

                    val day = parseDayOfWeek(dayStr)
                    val (startSec, endSec) = parseSection(sectionStr)
                    if (day !in 1..7 || startSec <= 0) { skipped++; continue }

                    entries.add(ScheduleEntry(
                        id = "file_${System.currentTimeMillis()}_${i}",
                        courseName = name,
                        teacher = teacher,
                        classroom = classroom,
                        dayOfWeek = day,
                        startSection = startSec,
                        endSection = if (endSec >= startSec) endSec else startSec,
                        weeks = parseWeeks(weeksStr).ifEmpty { (1..20).toList() },
                        color = courseColorFor(name)
                    ))
                } catch (_: Exception) { skipped++ }
            }
            return ImportResult(entries, entries.size, skipped)
        } finally {
            tempFile.delete()
        }
    }

    /** 将表头行映射到标准字段名 */
    private fun mapColumns(headers: List<String>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        COLUMN_KEYWORDS.forEach { (field, keywords) ->
            for (i in headers.indices) {
                val h = headers[i].lowercase()
                if (keywords.any { h.contains(it.lowercase()) }) {
                    result[field] = i
                    break
                }
            }
        }
        return result
    }

    /** 解析 sharedStrings.xml,提取所有字符串到 [out] */
    private fun parseSharedStrings(xml: String, out: MutableList<String>) {
        // 简易解析:匹配 <si>...</si> 块内的 <t>...</t> 文本
        val siPattern = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
        val tPattern = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        siPattern.findAll(xml).forEach { siMatch ->
            val texts = tPattern.findAll(siMatch.groupValues[1]).map { it.groupValues[1] }.joinToString("")
            out.add(unescapeXml(texts))
        }
    }

    /** 解析 sheet XML,返回行列表(每行为单元格值列表) */
    private fun parseSheetRows(xml: String, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val rowPattern = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellPattern = Regex("<c[^>]*?(?:\\st=\"([^\"]+)\")?[^>]*>(?:<v>(.*?)</v>)?</c>", RegexOption.DOT_MATCHES_ALL)

        rowPattern.findAll(xml).forEach { rowMatch ->
            val cells = mutableListOf<String>()
            var maxCol = -1
            val cellData = mutableListOf<Triple<Int, String, String>>() // colIndex, type, value

            cellPattern.findAll(rowMatch.groupValues[1]).forEach { cellMatch ->
                val cellAttrs = cellMatch.value
                // 提取 r 属性(如 "A1","B1")获取列索引
                val rAttr = Regex("r=\"([A-Z]+)\\d+\"").find(cellAttrs)?.groupValues?.get(1) ?: ""
                val colIndex = colNameToIndex(rAttr)
                val type = cellMatch.groupValues[1] // "s"=共享字符串, "n"=数字, "str"=公式字符串
                val value = cellMatch.groupValues[2]
                if (colIndex > maxCol) maxCol = colIndex
                cellData.add(Triple(colIndex, type, value))
            }

            if (maxCol >= 0) {
                // 填充到最大列,空位用空字符串
                val rowArr = Array(maxCol + 1) { "" }
                cellData.forEach { (col, type, value) ->
                    rowArr[col] = when (type) {
                        "s" -> sharedStrings.getOrNull(value.toIntOrNull() ?: -1) ?: ""
                        else -> value
                    }
                }
                rows.add(rowArr.toList())
            }
        }
        return rows
    }

    /** 列名(A,B,...,Z,AA,AB,...) → 0-based 索引 */
    private fun colNameToIndex(name: String): Int {
        var idx = 0
        for (c in name) {
            idx = idx * 26 + (c - 'A' + 1)
        }
        return idx - 1
    }

    private fun unescapeXml(s: String): String {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
    }
}
