package go.examate.hyp.music

import android.content.Context

/**
 * 账号与源开关设置(SharedPreferences):
 *  - 播放路线:已迁移到 PlayRoutePrefs(官方账号播放 / 音源解析脚本,二选一);
 *    旧 official_api 开关保留仅供历史数据兼容,不再被业务代码使用。
 *  - B站搜索开关:默认关闭,B站不出现在搜索源/聚合中;手动开启后显示
 */
object AccountPrefs {
    private const val PREFS = "music_account"


    /** B站是否出现在搜索源中(默认 true) */
    fun isBiliSearchEnabled(context: Context): Boolean =
        prefs(context).getBoolean("bili_search", true)

    fun setBiliSearchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("bili_search", enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
