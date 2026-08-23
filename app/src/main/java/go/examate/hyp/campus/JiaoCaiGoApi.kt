package go.examate.hyp.campus

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 教材清单接口(jiaocaigo.com) — 手机号+短信验证码登录,查询本学期各课程教材清单。
 *
 * 流程: sendCode → checkCode(获 token) → showMaterial(课程列表) → queryTeachMaterialByCourse(教材)
 */
object JiaoCaiGoApi {
    private const val BASE = "https://jiaocaigo.com"
    private const val REFERER = "https://servicewechat.com/wxe8473d1762b326f0/82/page-frame.html"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B)"

    private const val PREFS = "jiaocaigo"
    private const val KEY_TOKEN = "token"
    private const val KEY_PHONE = "phone"

    data class Course(
        val id: String,
        val name: String,
        val code: String,
        val nature: String
    )

    data class Material(
        val name: String,
        val author: String,
        val press: String,
        val isbn: String,
        val price: Double,
        val cover: String,
        val reserveName: String
    )

    class JiaoCaiGoException(msg: String) : Exception(msg)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun buildGet(path: String, token: String = ""): Request {
        val builder = Request.Builder()
            .url("$BASE$path")
            .header("Referer", REFERER)
            .header("User-Agent", UA)
        if (token.isNotEmpty()) builder.header("token", token)
        return builder.build()
    }

    private fun parseMeta(json: String): JSONObject {
        val root = JSONObject(json)
        val meta = root.optJSONObject("meta") ?: throw JiaoCaiGoException("响应格式异常")
        if (meta.optInt("errCode", -1) != 0) {
            throw JiaoCaiGoException(meta.optString("errMsg", "请求失败"))
        }
        return root
    }

    /** 发送短信验证码,返回 verifystr(供 checkCode 使用) */
    fun sendCode(phone: String): String {
        client.newCall(buildGet("/bind/info/sendCode?phone=$phone")).execute().use { resp ->
            if (!resp.isSuccessful) throw JiaoCaiGoException("网络错误(${resp.code})")
            val root = parseMeta(resp.body!!.string())
            val data = root.optJSONObject("data") ?: throw JiaoCaiGoException("响应格式异常")
            val info = data.optString("info", "")
            if (info != "OK") throw JiaoCaiGoException(info.ifBlank { "发送失败" })
            return data.optString("verifystr")
        }
    }

    /** 校验验证码并登录,返回 token */
    fun checkCode(phone: String, code: String, verifystr: String): String {
        val body = FormBody.Builder()
            .add("validCode", code)
            .add("mobile", phone)
            .add("verifystr", verifystr)
            .build()
        val req = Request.Builder()
            .url("$BASE/bind/info/checkCode")
            .header("Referer", REFERER)
            .header("User-Agent", UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw JiaoCaiGoException("网络错误(${resp.code})")
            val root = parseMeta(resp.body!!.string())
            val data = root.optJSONObject("data") ?: throw JiaoCaiGoException("响应格式异常")
            return data.optString("token")
        }
    }

    /** 获取当前学期课程列表,返回 (学期名, 课程列表) */
    fun getCourses(token: String): Pair<String, List<Course>> {
        client.newCall(buildGet("/wx/teachMaterial/showMaterial?courseId=", token)).execute().use { resp ->
            if (!resp.isSuccessful) throw JiaoCaiGoException("网络错误(${resp.code})")
            val root = parseMeta(resp.body!!.string())
            val data = root.optJSONObject("data") ?: return "" to emptyList()
            val term = data.optString("term", "")
            val inner = data.optJSONObject("data") ?: return term to emptyList()
            val arr = inner.optJSONArray("confirmData") ?: return term to emptyList()
            val courses = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val c = o.optJSONObject("columns") ?: return@mapNotNull null
                Course(
                    id = c.optString("courseId"),
                    name = c.optString("courseName"),
                    code = c.optString("courseCode"),
                    nature = c.optString("courseNature")
                )
            }.filter { it.id.isNotEmpty() }
            return term to courses
        }
    }

    /** 按课程 ID 获取教材清单 */
    fun getMaterials(token: String, courseId: String): List<Material> {
        client.newCall(buildGet("/wx/teachMaterial/queryTeachMaterialByCourse?courseId=$courseId", token)).execute().use { resp ->
            if (!resp.isSuccessful) throw JiaoCaiGoException("网络错误(${resp.code})")
            val root = parseMeta(resp.body!!.string())
            val arr: JSONArray = root.optJSONArray("data") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val c = o.optJSONObject("columns") ?: return@mapNotNull null
                Material(
                    name = c.optString("name"),
                    author = c.optString("author"),
                    press = c.optString("press"),
                    isbn = c.optString("isbn"),
                    price = c.optDouble("teachMaterialPrice", c.optDouble("estimatedPrice", 0.0)),
                    cover = c.optString("cover"),
                    reserveName = c.optString("reserveName")
                )
            }
        }
    }

    // ═══ token 持久化 ═══

    fun saveToken(context: Context, token: String, phone: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token).putString(KEY_PHONE, phone).apply()
    }

    fun loadToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""

    fun loadPhone(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PHONE, "") ?: ""

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}