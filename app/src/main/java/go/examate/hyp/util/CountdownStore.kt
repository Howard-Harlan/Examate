package go.examate.hyp.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 倒数日本地存储。
 *
 * 存储:`{filesDir}/countdowns.json`(JSON 数组)。
 * 字段:id / name / date(yyyy-MM-dd)/ emoji / color(ARGB Int)
 */
object CountdownStore {

    data class Countdown(
        val id: String,
        val name: String,
        val date: String,      // yyyy-MM-dd
        val emoji: String = "⏳",
        val color: Long = 0xFF5B8DEF
    ) {
        /** 距今天数:正数=还有 N 天,0=就是今天,负数=已过 N 天 */
        fun daysLeft(today: LocalDate = LocalDate.now()): Long {
            return runCatching {
                ChronoUnit.DAYS.between(today, LocalDate.parse(date))
            }.getOrDefault(0L)
        }
    }

    private fun file(context: Context): File =
        File(context.filesDir, "countdowns.json")

    private fun readAll(context: Context): MutableList<Countdown> {
        val result = mutableListOf<Countdown>()
        val f = file(context)
        if (!f.exists()) return result
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                result.add(
                    Countdown(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        date = o.optString("date"),
                        emoji = o.optString("emoji", "⏳"),
                        color = o.optLong("color", 0xFF5B8DEF)
                    )
                )
            }
        }
        return result
    }

    private fun writeAll(context: Context, items: List<Countdown>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("date", c.date)
                    put("emoji", c.emoji)
                    put("color", c.color)
                })
            }
            file(context).writeText(arr.toString())
        }
    }

    /** 添加倒数日(按目标日期升序返回) */
    fun add(context: Context, name: String, date: String, emoji: String = "⏳", color: Long = 0xFF5B8DEF): Countdown {
        val now = System.currentTimeMillis()
        val item = Countdown(id = "cd_$now", name = name, date = date, emoji = emoji, color = color)
        writeAll(context, (readAll(context) + item).sortedBy { it.date })
        return item
    }

    /** 列表:未来日期在前(最近的排最前),再按日期升序 */
    fun list(context: Context): List<Countdown> {
        val today = LocalDate.now()
        return readAll(context).sortedBy { c ->
            val d = c.daysLeft(today)
            if (d >= 0) d else Long.MAX_VALUE / 2 + (-d)
        }
    }

    /** 未来最近的 N 个倒数日(用于首页卡片) */
    fun upcoming(context: Context, n: Int = 3): List<Countdown> =
        list(context).filter { it.daysLeft() >= 0 }.take(n)

    fun remove(context: Context, id: String) {
        writeAll(context, readAll(context).filterNot { it.id == id })
    }

    /** 更新倒数日(按 id 覆盖,保持排序) */
    fun update(context: Context, item: Countdown) {
        writeAll(context, readAll(context).map { if (it.id == item.id) item else it }.sortedBy { it.date })
    }
}
