package go.examate.hyp.music

import android.content.Context

/**
 * 搜索偏好持久化 — 记住用户上次选择的搜索源与搜索类型(歌曲/歌单),
 * 不再每次回到默认"聚合/歌曲"。
 */
object MusicSearchPrefs {
    private const val PREFS = "music_search"

    fun getSource(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("source", "all") ?: "all"

    fun setSource(context: Context, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("source", source).apply()
    }

    fun getType(context: Context): MusicSearchType {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("type", null)
        return MusicSearchType.entries.firstOrNull { it.name == name } ?: MusicSearchType.MUSIC
    }

    fun setType(context: Context, type: MusicSearchType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("type", type.name).apply()
    }
}
