package go.examate.hyp.music.auth

import android.content.Context

/**
 * 音乐账号登录态 — 各平台 Cookie 统一持久化(SharedPreferences)。
 *
 * 官方播放路线走"模拟登录拿数据":播放解析时把已保存的 Cookie 附到官方 API 请求上,
 * 获取登录账号应有的音质/VIP 曲库。Cookie 仅存本地。
 *
 * 平台:wy=网易云 tx=QQ音乐 kg=酷狗 kw=酷我 mg=咪咕(bili 沿用独立的 BiliAuth)。
 */
object MusicAuth {
    private const val PREFS = "music_auth"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveCookie(context: Context, platform: String, cookie: String) {
        prefs(context).edit().putString("cookie_$platform", cookie).apply()
    }

    fun cookie(context: Context, platform: String): String =
        prefs(context).getString("cookie_$platform", "") ?: ""

    /** 是否已登录(Cookie 保存即视为登录 — 登录 Activity 已做成功判定) */
    fun isLoggedIn(context: Context, platform: String): Boolean =
        cookie(context, platform).isNotBlank()

    fun saveNick(context: Context, platform: String, nick: String) {
        prefs(context).edit().putString("nick_$platform", nick).apply()
    }

    /** 昵称;未取到时给平台默认称呼 */
    fun nick(context: Context, platform: String): String =
        prefs(context).getString("nick_$platform", "").orEmpty()

    fun logout(context: Context, platform: String) {
        prefs(context).edit()
            .remove("cookie_$platform")
            .remove("nick_$platform")
            .apply()
    }

    /** 从 Cookie 串取某字段值 */
    fun value(cookie: String, key: String): String =
        cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.trim().orEmpty()

    /** 平台 → UI 别名(与 MusicSourceDefs 一致,避免循环依赖此处直接映射) */
    fun aliasOf(platform: String): String = when (platform) {
        "wy" -> "小芸音乐"
        "tx" -> "小秋音乐"
        "kg" -> "小枸音乐"
        "kw" -> "小蜗音乐"
        "mg" -> "小蜜音乐"
        "bili" -> "小毕音乐"
        else -> platform
    }
}
