package go.examate.hyp.music

import android.content.Context

/**
 * 播放路线 — 在线歌曲播放的两条路线(设置中选择,见 AccountSettingsPage):
 *  - OFFICIAL 官方账号播放:各平台官方 API 直连解析播放地址(不依赖音源脚本,基础能力)
 *  - SCRIPT   音源解析脚本播放:经导入的 LX 音源脚本解析取流(参照落雪 lx-music-mobile,
 *             支持音质选择/URL 缓存/失败换源)
 *
 * B站(bili)不受此路线影响:只能走登录后的官方 DASH 解析(BiliClient)。
 */
enum class PlayRoute(val id: String, val label: String, val desc: String) {
    OFFICIAL("official", "官方账号播放", "各平台官方 API 直连,不依赖音源脚本"),
    SCRIPT("script", "音源解析脚本", "经导入的 LX 音源脚本解析取流(需先导入并启用音源)");

    companion object {
        fun byId(id: String): PlayRoute = entries.firstOrNull { it.id == id } ?: OFFICIAL
    }
}

/**
 * 播放路线设置(SharedPreferences):
 *  - route:当前播放路线,默认 OFFICIAL(官方 API 是基础播放能力,参照落雪 musicSdk 官方源直接可播)
 *  - quality:在线播放音质(128k/320k/flac),默认 128k 兼容性最好;脚本路线下按源声明降级
 */
object PlayRoutePrefs {
    private const val PREFS = "play_route"
    private const val KEY_ROUTE = "route"
    private const val KEY_QUALITY = "quality"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getRoute(context: Context): PlayRoute =
        PlayRoute.byId(prefs(context).getString(KEY_ROUTE, PlayRoute.OFFICIAL.id)!!)

    fun setRoute(context: Context, route: PlayRoute) {
        prefs(context).edit().putString(KEY_ROUTE, route.id).apply()
    }

    /** 在线播放目标音质(128k/320k/flac) */
    fun getQuality(context: Context): String =
        prefs(context).getString(KEY_QUALITY, "128k") ?: "128k"

    fun setQuality(context: Context, quality: String) {
        prefs(context).edit().putString(KEY_QUALITY, quality).apply()
    }
}
