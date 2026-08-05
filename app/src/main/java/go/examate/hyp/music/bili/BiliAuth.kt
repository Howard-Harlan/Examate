package go.examate.hyp.music.bili

import android.content.Context

/**
 * B站登录态 — Cookie 持久化(SharedPreferences)。
 * 关键 Cookie:SESSDATA(鉴权)、bili_jct(csrf)、DedeUserID(uid)、buvid3(风控指纹)。
 * 登录经 WebView 抓取;buvid3 若缺失可由 BiliClient 补取。
 */
object BiliAuth {
    private const val PREFS = "bili_auth"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_UID = "uid"
    private const val KEY_UNAME = "uname"

    fun saveCookie(context: Context, cookie: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_COOKIE, cookie).apply()
    }

    fun cookie(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COOKIE, "") ?: ""

    fun isLoggedIn(context: Context): Boolean = cookie(context).contains("SESSDATA")

    fun saveUser(context: Context, uid: String, uname: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UID, uid).putString(KEY_UNAME, uname).apply()
    }

    fun uname(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNAME, "") ?: ""

    /** 从 Cookie 串取某字段值 */
    fun value(cookie: String, key: String): String {
        return cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.trim().orEmpty()
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
