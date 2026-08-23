package go.examate.hyp.util

import android.content.Context
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.getSemesterStart
import go.examate.hyp.config.PrefsManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * 节日引擎(数据驱动版)。
 *
 * 数据来源:
 * 1. 生日 — 用户资料(本地)
 * 2. 法定节假日 — 网络获取(timor.tech 节假日 API),本地仅做缓存,不内置日期
 * 3. 开学 / 期末周 / 放假 — 读取课表数据推导:
 *    - 开学   = 学期第一个星期一(meta.semesterStartDate / 默认推断),持续 7 天
 *    - 期末周 = 学期最后一周,提醒从最后一周的前一周开始(共 14 天)
 *    - 放假   = 最后一周结束次日开始,持续 60 天
 *
 * 两类消费:
 * 1. 首页 Hero 卡片:活动期间在 "Examate" 右侧空白区持续显示
 * 2. 欢迎页:活动期间首次进入 App 时弹一次(按节日 id + 年份 持久化已展示标记)
 */
object FestivalManager {

    data class Festival(
        val id: String,
        val name: String,
        val emoji: String,
        val greeting: String,
        val start: LocalDate,
        val end: LocalDate,      // 含当天
        val color: Long = 0xFF5B8DEF
    ) {
        val daysLeft: Long get() = runCatching {
            ChronoUnit.DAYS.between(LocalDate.now(), start)
        }.getOrDefault(0L)
    }

    private data class HolidayDay(val name: String, val date: LocalDate)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun cacheFile(context: Context, year: Int): File =
        File(context.filesDir, "holidays_$year.json")

    /** 获取某年节假日数据:一年获取一次,命中缓存即复用,不再反复请求网络 */
    private fun holidayDays(context: Context, year: Int): List<HolidayDay> {
        val f = cacheFile(context, year)
        // 每年只请求一次:该年份节假日数据已缓存则直接复用(节假日官方数据按年发布,年内稳定)
        if (f.exists()) return parseHolidayJson(f.readText())

        runCatching {
            val request = Request.Builder()
                .url("https://timor.tech/api/holiday/year/$year")
                .header("User-Agent", "Examate/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    f.writeText(body)
                }
            }
        }
        // 网络失败:回退本地缓存(哪怕过期)
        if (f.exists()) return parseHolidayJson(f.readText())
        return emptyList()
    }

    private fun parseHolidayJson(text: String): List<HolidayDay> {
        return runCatching {
            val json = JSONObject(text)
            val holiday = json.optJSONObject("holiday") ?: return emptyList()
            val fallbackYear = json.optString("year").ifBlank { LocalDate.now().year.toString() }
            val days = mutableListOf<HolidayDay>()
            holiday.keys().forEach { key ->
                val o = holiday.optJSONObject(key) ?: return@forEach
                if (o.optBoolean("holiday")) {
                    val date = o.optString("date").ifBlank { "$fallbackYear-$key" }
                    runCatching { days += HolidayDay(o.optString("name", "节日"), LocalDate.parse(date)) }
                }
            }
            days.sortedBy { it.date }
        }.getOrDefault(emptyList())
    }

    /** 把按天返回的节假日按"连续同名"合并为区间 */
    private fun groupRanges(days: List<HolidayDay>): List<Festival> {
        val result = mutableListOf<Festival>()
        var i = 0
        while (i < days.size) {
            val start = days[i]
            var j = i
            while (j + 1 < days.size &&
                days[j + 1].name == start.name &&
                ChronoUnit.DAYS.between(days[j].date, days[j + 1].date) == 1L
            ) j++
            val (emoji, greeting, color) = styleFor(start.name)
            result += Festival(
                id = start.name,
                name = start.name,
                emoji = emoji,
                greeting = greeting,
                start = start.date,
                end = days[j].date,
                color = color
            )
            i = j + 1
        }
        return result
    }

    /** 节日名称 → 展示样式(仅视觉映射,日期不内置) */
    private fun styleFor(name: String): Triple<String, String, Long> = when {
        name.contains("春节") -> Triple("🧧", "新春大吉,万事胜意。", 0xFFE53935)
        name.contains("端午") -> Triple("🐉", "粽叶飘香,端午安康。", 0xFF43A047)
        name.contains("中秋") -> Triple("🥮", "月圆人圆,中秋快乐。", 0xFFF2B69C)
        name.contains("国庆") -> Triple("🇨🇳", "山河锦绣,国泰民安。", 0xFFE53935)
        name.contains("元旦") -> Triple("🎉", "新年快乐,万象更新。", 0xFF5B8DEF)
        name.contains("劳动") -> Triple("💪", "劳动最光荣,好好休息。", 0xFF6EC9B4)
        name.contains("清明") -> Triple("🌿", "清明时节,追思踏青。", 0xFF43A047)
        else -> Triple("🎊", "节日快乐。", 0xFF5B8DEF)
    }

    /** 计算当前激活的节日列表(按开始日期排序) */
    fun activeFestivals(context: Context, today: LocalDate = LocalDate.now()): List<Festival> {
        val result = mutableListOf<Festival>()

        // 生日(当天)
        val birthday = PrefsManager.getInstance(context).birthday
        if (birthday.isNotBlank()) {
            runCatching {
                val bd = LocalDate.parse(birthday).withYear(today.year)
                result += Festival(
                    id = "birthday", name = "生日", emoji = "🎂",
                    greeting = "今天是你的生日,愿新的一岁所愿皆所得。",
                    start = bd, end = bd, color = 0xFFF2B69C
                )
            }
        }

        // 网络节假日(当年 + 次年,覆盖跨年节日)
        val networkDays = (holidayDays(context, today.year) + holidayDays(context, today.year + 1))
            .distinctBy { "${it.name}|${it.date}" }
        result += groupRanges(networkDays)

        // 课表数据推导:开学 / 期末周 / 放假(无课表时按默认学期推断,始终提供)
        val meta = ScheduleStorage.getCurrentScheduleMeta(context)
        // 期末周周次:来自课表设置的"期末周"(默认 18),钳制在 1..总周数 内
        val examWeek = meta.examWeek.coerceIn(1, meta.totalWeeks.coerceAtLeast(1))
        val semesterStart = getSemesterStart(meta)

        // 开学:第一周
        result += Festival(
            id = "school_start", name = "开学", emoji = "🎒",
            greeting = "新学期,新气象,冲鸭!",
            start = semesterStart, end = semesterStart.plusDays(6), color = 0xFF5B8DEF
        )
        // 期末周:考试周(提醒从考试周前一周开始,共 14 天)
        val examStart = semesterStart.plusWeeks((examWeek - 2).toLong().coerceAtLeast(0))
        val examEnd = semesterStart.plusWeeks((examWeek - 1).toLong()).plusDays(6)
        result += Festival(
            id = "exam_week", name = "期末周", emoji = "📝",
            greeting = "期末冲刺,稳住心态,你能行。",
            start = examStart, end = examEnd, color = 0xFF9B7BE8
        )
        // 放假:期末周结束后开始
        val vacStart = semesterStart.plusWeeks(examWeek.toLong())
        result += Festival(
            id = "vacation", name = "放假", emoji = "🏖️",
            greeting = "假期愉快,好好充电。",
            start = vacStart, end = vacStart.plusDays(59), color = 0xFF6EC9B4
        )

        return result
            .filter { !today.isBefore(it.start) && !today.isAfter(it.end) }
            .sortedBy { it.start }
    }

    /** 首次进入欢迎页:当前节日期间,每个节日每年只展示一次 */
    fun shouldShowWelcome(context: Context, festival: Festival): Boolean {
        val prefs = context.getSharedPreferences("festival_welcome", Context.MODE_PRIVATE)
        val key = "${festival.id}_${festival.start.year}"
        return !prefs.getBoolean(key, false)
    }

    fun markWelcomeShown(context: Context, festival: Festival) {
        val prefs = context.getSharedPreferences("festival_welcome", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("${festival.id}_${festival.start.year}", true).apply()
    }
}
