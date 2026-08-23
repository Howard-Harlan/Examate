package go.examate.hyp.music

import android.content.Context

/**
 * 歌词偏移存储 — 按曲目 id 持久化(SharedPreferences)。
 * offsetMs > 0:歌词提前显示;offsetMs < 0:歌词延后显示。
 * 生效方式:当前行判定用 (播放位置 + offset),点击跳播用 (歌词时间 - offset)。
 */
object LyricOffsetStore {
    private const val PREFS = "music_lyric_offset"

    fun get(context: Context, itemId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(itemId, 0L)

    fun set(context: Context, itemId: String, offsetMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(itemId, offsetMs).apply()
    }
}
