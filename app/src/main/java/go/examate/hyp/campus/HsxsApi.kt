package go.examate.hyp.campus

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

/**
 * 淮师心声(Hsxs)客户端 — 失物招领 & 满意度调查。从 [ChnuClient.HsxsClient] 提取。
 *
 * CAS SSO → 用 service ticket 登录 → cookie 会话。
 * API: https://hsxs.chnu.edu.cn/api/
 *
 * 共享 [client] 以维持会话 cookie;[getServiceTicket] 回调用于 CAS SSO 单点登录获取 service ticket。
 */
internal class HsxsApi(
    private val client: OkHttpClient,
    private val getServiceTicket: (String) -> Pair<String?, String?>
) {
    private val baseUrl = ChnuConfig.HSXS_API_BASE
    private val webUrl = ChnuConfig.HSXS_BASE_URL

    @Volatile
    var loggedIn: Boolean = false
        private set

    @Volatile
    var userName: String = ""
        private set

    @Volatile
    var userPhone: String = ""
        private set

    private var authToken: String = ""

    fun login() {
        // CAS service ticket → validateLogin (GET) → token → getUserInfo
        val service = "$webUrl/web"
        val (ticket, _) = getServiceTicket(service)
        if (ticket.isNullOrEmpty()) throw CampusException("淮师心声登录失败: 无法获取 CAS ticket")
        // GET user/validateLogin?ticket=xxx&service=xxx
        val vlResp = apiGet("user/validateLogin", mapOf("ticket" to "$ticket", "service" to service))
        val data = vlResp.optJSONObject("data")
        authToken = data?.optString("token", "") ?: ""
        if (authToken.isEmpty()) throw CampusException("淮师心声登录失败: 未获取到 token")
        userName = data?.optString("user_name", "") ?: ""
        // GET user/getUserInfo (with token header)
        val info = apiGetWithToken("user/getUserInfo")
        val infoData = info.optJSONObject("data")
        userName = infoData?.optString("user_name", userName) ?: userName
        userPhone = infoData?.optString("user_phone", "") ?: ""
        loggedIn = true
    }

    /** 获取校区列表 (GET + token) — 网页版使用 common/get_campus_list */
    fun getOrganiseList(): JSONObject {
        checkSession()
        return apiGetWithToken("common/get_campus_list")
    }

    /** 获取当前用户信息(含手机号) */
    fun getUserInfo(): JSONObject {
        checkSession()
        return apiGetWithToken("user/getUserInfo")
    }

    private fun checkSession() {
        if (!loggedIn) throw CampusException("淮师心声未登录,请先调用 login()")
    }

    // ── 失物招领 ──
    // GET = Wt(getData), POST = ho(postData) — 从 JS 逆向确认

    /** 获取失物招领列表 (GET + token) */
    fun getLostList(page: Int = 1, pageSize: Int = 20, type: String = ""): JSONObject {
        checkSession()
        val params = mutableMapOf("page" to page.toString(), "rows" to pageSize.toString())
        if (type.isNotEmpty()) params["type"] = type
        return apiGetWithToken("lost/get_list", params)
    }

    /** 获取失物招领详情 (GET + token) */
    fun getLostDetail(id: String): JSONObject {
        checkSession()
        return apiGetWithToken("lost/get_detail", mutableMapOf("id" to id))
    }

    /** 上传图片,返回文件路径 (POST multipart + token) */
    fun uploadImage(fileName: String, fileBytes: ByteArray): String {
        checkSession()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName,
                RequestBody.create("image/*".toMediaType(), fileBytes))
            .build()
        val req = Request.Builder().url("$baseUrl/common/upload").post(body)
            .header("User-Agent", ChnuConfig.UA)
            .header("token", authToken)
            .header("Referer", "$webUrl/web/")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            val json = ChnuHtmlHelpers.parseJsonObject(text)
            val code = json?.optInt("code", -1) ?: -1
            if (code != 1) {
                val msg = json?.optString("msg", "上传失败") ?: "上传失败"
                throw CampusException(msg)
            }
            val url = json?.optJSONObject("data")?.optString("url", "") ?: ""
            if (url.isEmpty()) throw CampusException("上传返回的图片URL为空")
            return url
        }
    }

    /** 发布失物招领 (POST + token) */
    fun publishLost(body: Map<String, String>): JSONObject {
        checkSession()
        return apiPostWithToken("lost/add", body)
    }

    /** 获取问卷列表 (GET + token) */
    fun getQuestionList(page: Int = 1, pageSize: Int = 20): JSONObject {
        checkSession()
        return apiGetWithToken("question/get_list", mutableMapOf("page" to page.toString(), "rows" to pageSize.toString()))
    }

    /** 获取问卷详情 (GET + token) */
    fun getQuestionDetail(id: String): JSONObject {
        checkSession()
        return apiGetWithToken("question/get_detail", mutableMapOf("id" to id))
    }

    /** 获取问卷题目列表 (GET + token) */
    fun getSurveyQuestions(id: String): JSONObject {
        checkSession()
        return apiGetWithToken("question/get_answer", mutableMapOf("id" to id))
    }

    /** 提交问卷答案 (POST + token) */
    fun submitAnswer(questionId: String, answers: JSONObject): JSONObject {
        checkSession()
        return apiPostWithToken("question/answer", mutableMapOf("id" to questionId, "answerJson" to answers.toString()))
    }

    /** 查看已答结果 (GET + token) */
    fun getMyAnswer(questionId: String): JSONObject {
        checkSession()
        return apiGetWithToken("question/get_user_answer", mutableMapOf("id" to questionId))
    }

    // ── 基础HTTP方法 ──

    /** GET + token header */
    private fun apiGetWithToken(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = buildQueryUrl("$baseUrl/$path", params)
        val req = Request.Builder().url(url).get()
            .header("User-Agent", ChnuConfig.UA)
            .header("token", authToken)
            .header("Referer", "$webUrl/web/")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            return ChnuHtmlHelpers.parseJsonObject(text) ?: JSONObject()
        }
    }

    /** POST + token header */
    private fun apiPostWithToken(path: String, params: Map<String, String>): JSONObject {
        val form = FormBody.Builder()
        params.forEach { (k, v) -> form.add(k, v) }
        val req = Request.Builder().url("$baseUrl/$path").post(form.build())
            .header("User-Agent", ChnuConfig.UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("token", authToken)
            .header("Referer", "$webUrl/web/")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            return ChnuHtmlHelpers.parseJsonObject(text) ?: JSONObject()
        }
    }

    private fun buildQueryUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val sb = StringBuilder(base)
        sb.append("?")
        params.entries.joinTo(sb, "&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        return sb.toString()
    }

    /** No-token GET (for validateLogin before token is set) */
    private fun apiGet(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = buildQueryUrl("$baseUrl/$path", params)
        val req = Request.Builder().url(url)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$webUrl/web/")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            return ChnuHtmlHelpers.parseJsonObject(text) ?: JSONObject()
        }
    }
}
