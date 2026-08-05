package go.examate.hyp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import go.examate.hyp.R
import go.examate.hyp.ScheduleActivity
import go.examate.hyp.campus.model.PERIOD_TIMES
import go.examate.hyp.campus.model.ScheduleEntry
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import java.time.LocalDate
import java.time.LocalTime

/**
 * 今日课表桌面小组件(RemoteViews,零第三方依赖)。
 *
 * 数据源复用课程表模块单一数据源([ScheduleStorage.coursesOn]/[ScheduleStorage.todosOn]),
 * 已按当前教学周精确过滤,与 App 内日视图、AI 聊天卡片一致。
 *
 * 尺寸自适应:小尺寸(长条)只显示接下来两节课;大尺寸显示今天全部课程。
 * 代办:有具体时间且落在某节课时段内 → 该课程行右侧显示提示符📌;
 *      全天代办或未命中节次 → 汇总显示在末尾。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, mgr, it) }
    }

    /** 尺寸变化(拉伸/缩小)时重建,以切换长条/完整两种展示 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidget(context, mgr, appWidgetId)
    }

    companion object {
        private const val COMPACT_MAX_HEIGHT_DP = 130
        private const val FULL_MAX_COURSES = 10

        /** 供 App 内课表/代办变化后主动刷新所有课表小组件 */
        fun refreshAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
                ids.forEach { updateWidget(context, mgr, it) }
            } catch (_: Exception) { /* 无小组件或系统限制,忽略 */ }
        }

        private fun periodStart(sec: Int): LocalTime? =
            PERIOD_TIMES.getOrNull(sec - 1)?.first?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

        private fun periodEnd(sec: Int): LocalTime? =
            PERIOD_TIMES.getOrNull(sec - 1)?.second?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

        /** 代办时间是否落在某节课时段 [start, end) 内 */
        private fun todoInCourse(todo: ScheduleTodo, e: ScheduleEntry): Boolean {
            if (todo.time.isBlank()) return false
            val t = runCatching { LocalTime.parse(todo.time) }.getOrNull() ?: return false
            val s = periodStart(e.startSection) ?: return false
            val en = periodEnd(e.endSection) ?: return false
            return !t.isBefore(s) && t.isBefore(en)
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            // 小组件可能在独立进程,先确保节次时间已从磁盘加载(否则用默认作息可能不准)
            runCatching { ScheduleStorage.loadTimeSchedule(context) }

            val today = LocalDate.now()
            val dayChar = when (today.dayOfWeek.value) {
                1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
            }
            val courses = try { ScheduleStorage.coursesOn(context, today) } catch (_: Exception) { emptyList() }
            val todos = try { ScheduleStorage.todosOn(context, today) } catch (_: Exception) { emptyList() }

            // 判断尺寸:高度小于阈值 → 长条(紧凑)模式
            val minH = runCatching {
                mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            }.getOrDefault(0)
            val compact = minH in 1 until COMPACT_MAX_HEIGHT_DP

            val views = RemoteViews(context.packageName, R.layout.widget_schedule)
            views.setTextViewText(R.id.widget_weekday, "周$dayChar")
            // 月/日显示(如 8/5)
            views.setTextViewText(R.id.widget_date, "${today.monthValue}/${today.dayOfMonth}")
            views.removeAllViews(R.id.widget_courses_container)

            // 紧凑模式只显示接下来未结束的两节;完整模式显示全部
            val now = LocalTime.now()
            val display = if (compact) {
                courses.filter { (periodEnd(it.endSection)?.isAfter(now)) ?: true }.take(2)
            } else {
                courses.take(FULL_MAX_COURSES)
            }

            val matchedTodoIds = HashSet<String>()
            when {
                courses.isEmpty() -> {
                    views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                    views.setTextViewText(R.id.widget_empty, "今天没课了,休息下吧 🌿")
                }
                display.isEmpty() -> {
                    views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                    views.setTextViewText(R.id.widget_empty, "今天没课了,休息下吧 🌿")
                }
                else -> {
                    views.setViewVisibility(R.id.widget_empty, View.GONE)
                    display.forEach { e ->
                        val row = RemoteViews(context.packageName, R.layout.widget_course_row)
                        val sec = if (e.startSection == e.endSection) "第${e.startSection}节"
                            else "第${e.startSection}-${e.endSection}节"
                        val text = listOf(sec, e.courseName, if (e.classroom.isNotEmpty()) "@${e.classroom}" else "")
                            .filter { it.isNotEmpty() }.joinToString("  ")
                        row.setTextViewText(R.id.row_course, text)
                        val matched = todos.filter { todoInCourse(it, e) }
                        if (matched.isNotEmpty()) {
                            matched.forEach { matchedTodoIds.add(it.id) }
                            row.setViewVisibility(R.id.row_todo, View.VISIBLE)
                            row.setTextViewText(R.id.row_todo, if (matched.size > 1) "📌${matched.size}" else "📌")
                        } else {
                            row.setViewVisibility(R.id.row_todo, View.GONE)
                        }
                        views.addView(R.id.widget_courses_container, row)
                    }
                }
            }

            // 末尾代办:未落在任何展示课程节次内的(全天代办 + 时间未命中)
            val endTodos = todos.filter { it.id !in matchedTodoIds }
            if (endTodos.isEmpty()) {
                views.setViewVisibility(R.id.widget_todos, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_todos, View.VISIBLE)
                val n = if (compact) 1 else 3
                val text = endTodos.take(n).joinToString("   ") { t ->
                    "📌 " + (if (t.time.isNotBlank()) "${t.time} " else "") + t.title
                } + if (endTodos.size > n) "  +${endTodos.size - n}" else ""
                views.setTextViewText(R.id.widget_todos, text)
            }

            // 点击整卡打开课表页
            val intent = Intent(context, ScheduleActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(context, 0, intent, piFlags)
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            mgr.updateAppWidget(id, views)
        }
    }
}
