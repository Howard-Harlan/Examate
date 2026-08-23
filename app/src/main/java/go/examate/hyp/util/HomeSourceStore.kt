package go.examate.hyp.util

import android.content.Context

/**
 * 首页"我的卡片"显示源配置。
 *
 * 用户可在首页自定义添加/移除卡片源,数据实时同步对应功能:
 * - todo      待办(ScheduleStorage 同一数据源)
 * - countdown 倒数日(CountdownStore)
 * - notes     最近笔记(NoteStorage)
 * - schedule  今日课程(ScheduleStorage)
 */
object HomeSourceStore {

    data class SourceDef(
        val id: String,
        val title: String,
        val subtitle: String,
        val emoji: String
    )

    val ALL: List<SourceDef> = listOf(
        SourceDef("todo", "待办", "同步待办功能数据", "✅"),
        SourceDef("countdown", "倒数日", "最近的重要日子", "⏳"),
        SourceDef("notes", "最近笔记", "快速打开知识笔记", "📝"),
        SourceDef("schedule", "今日课程", "今天要上的课", "📚")
    )

    private const val PREFS = "HomeSources"

    fun enabledIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("enabled", setOf("todo", "countdown")) ?: setOf("todo", "countdown")

    fun isEnabled(context: Context, id: String): Boolean = id in enabledIds(context)

    fun add(context: Context, id: String) {
        val current = enabledIds(context).toMutableSet()
        current += id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet("enabled", current).apply()
    }

    fun remove(context: Context, id: String) {
        val current = enabledIds(context).toMutableSet()
        current -= id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet("enabled", current).apply()
    }
}
