package go.examate.hyp.campus

import go.examate.hyp.campus.model.ModuleScore
import go.examate.hyp.campus.model.ScoreDetail
import go.examate.hyp.campus.model.ScoreSummary
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第二课堂客户端(华博思道,前后端分离 + JWT)— 从 [ChnuClient.SCClient] 提取。
 *
 * 共享 [client] 以维持会话 cookie;[getServiceTicket] 回调用于 CAS SSO 单点登录获取 service ticket。
 *
 * SSO 流程:
 *  1. CAS 获取 ticket → service=/hbsdWeb-prod
 *  2. GET /cas/client/checkSsoLogin?ticket=xxx&service=xxx → JWT token
 *  3. 后续请求用 X-Access-Token header
 */
internal class ScApi(
    private val client: OkHttpClient,
    private val username: String,
    private val getServiceTicket: (String) -> Pair<String?, String?>
) {
    private val baseUrl: String = ChnuConfig.SC_BASE_URL
    private val apiBase: String = ChnuConfig.SC_API_BASE

    @Volatile
    var loggedIn: Boolean = false
        private set

    private var token: String = ""
    private var userInfo: JSONObject? = null

    /** SSO 登录第二课堂,获取 JWT token */
    fun login() {
        val scService = "$baseUrl/hbsdWeb-prod"
        val (ticket, _) = getServiceTicket(scService)
        if (ticket == null) {
            throw CampusException("无法获取第二课堂 CAS ticket")
        }
        val httpUrl = with("$apiBase/cas/client/checkSsoLogin".toHttpUrl()) {
            newBuilder()
                .addQueryParameter("ticket", ticket)
                .addQueryParameter("service", scService)
                .build()
        }
        val req = Request.Builder().url(httpUrl)
            .header("User-Agent", ChnuConfig.UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw CampusException("第二课堂 SSO 登录失败: HTTP ${resp.code}")
            }
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("第二课堂 SSO 响应解析失败: ${text.take(200)}")
            if (!json.optBoolean("success", false)) {
                throw CampusException("第二课堂 SSO 登录失败: ${json.optString("message", "登录失败")}")
            }
            val result = json.opt("result")
            when (result) {
                is JSONObject -> {
                    token = result.optString("token")
                    userInfo = result.optJSONObject("userInfo")
                }
                else -> token = result?.toString() ?: ""
            }
            if (token.isEmpty()) {
                throw CampusException("第二课堂 SSO 登录: 未获取到 token")
            }
            loggedIn = true
        }
    }

    private fun checkSession() {
        if (!loggedIn) throw CampusException("第二课堂系统未登录,请先调用 login()")
    }

    /** 获取用户 ID(学号) */
    private fun userId(): String =
        userInfo?.optString("id")?.ifEmpty { null }
            ?: userInfo?.optString("username")?.ifEmpty { null }
            ?: username

    /** 查询成绩明细(各项目得分) */
    fun getScoreDetail(): List<ScoreDetail> {
        checkSession()
        val httpUrl = with("$apiBase/score/scScoreCollect/scoreListByUserIdForScanCode".toHttpUrl()) {
            newBuilder().addQueryParameter("userId", userId()).build()
        }
        val req = Request.Builder().url(httpUrl)
            .header("X-Access-Token", token)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return emptyList()
            if (!json.optBoolean("success", false)) return emptyList()
            val result = json.opt("result")
            val arr: JSONArray = when (result) {
                is JSONArray -> result
                else -> JSONArray().also { if (result != null) it.put(result) }
            }
            val list = mutableListOf<ScoreDetail>()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                list.add(ScoreDetail(
                    moduleName = it.optString("moduleName"),
                    itemName = it.optString("itemName"),
                    score = it.optString("scoreInfo").ifEmpty { it.optString("score") }
                ))
            }
            return list
        }
    }

    /** 查询成绩汇总(各模块总分) */
    fun getScoreSummary(): ScoreSummary {
        checkSession()
        val httpUrl = with("$apiBase/score/scScoreCollect/list".toHttpUrl()) {
            newBuilder().addQueryParameter("userId", userId()).build()
        }
        val req = Request.Builder().url(httpUrl)
            .header("X-Access-Token", token)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return ScoreSummary("", emptyList())
            if (!json.optBoolean("success", false)) return ScoreSummary("", emptyList())
            val result = json.opt("result")
            // records 数组每条代表一个模块,userScore 为该模块得分
            val records: JSONArray = when {
                result is JSONObject && result.has("records") -> result.getJSONArray("records")
                result is JSONArray -> result
                else -> JSONArray()
            }
            val modules = mutableListOf<ModuleScore>()
            var total = 0.0
            for (i in 0 until records.length()) {
                val rec = records.getJSONObject(i)
                val moduleName = rec.optString("moduleName")
                val scoreStr = rec.optString("userScore")
                scoreStr.toDoubleOrNull()?.let { total += it }
                modules.add(ModuleScore(moduleName = moduleName, score = scoreStr))
            }
            return ScoreSummary(totalScore = "%.2f".format(total), modules = modules)
        }
    }
}
