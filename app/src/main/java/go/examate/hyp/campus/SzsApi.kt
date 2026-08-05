package go.examate.hyp.campus

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 智慧思政客户端(jgf.chnu.edu.cn) — 综合测评成绩明细。
 *
 * 真实 API 流程(通过抓包验证):
 * 1. CAS SSO 获取 ticket
 * 2. GET {base}/cas/client/validateLogin?ticket=xxx&service=xxx → 返回 JWT token
 * 3. GET {base}/ChangeScore/List?pageNo=1&pageSize=100 → 成绩列表,header 带 X-Access-Token
 *
 * API base = http://jgf.chnu.edu.cn/hbsf-zhsz-plat (从页面内嵌 JS 的 window._CONFIG.domianURL 提取)
 * 框架: Jeecg-Boot (与智慧门户 oshall 同框架)
 */
internal class SzsApi(
    private val client: OkHttpClient,
    private val getServiceTicket: (String) -> Pair<String?, String?>
) {
    /** API 基础路径 — 从页面 window._CONFIG.domianURL 提取 */
    private val apiBase = "${ChnuConfig.SZS_BASE_URL}/hbsf-zhsz-plat"
    /** 前端页面路径(CAS service 参数用) */
    private val pageUrl = "${ChnuConfig.SZS_BASE_URL}/hbsz/changeScore"

    @Volatile
    private var token: String = ""

    @Volatile
    var loggedIn: Boolean = false
        private set

    /** CAS SSO 登录:GET cas/client/validateLogin → JWT token */
    fun login() {
        val (ticket, _) = getServiceTicket(pageUrl)
        if (ticket.isNullOrEmpty()) throw CampusException("智慧思政登录失败: 无法获取 CAS ticket")
        val req = Request.Builder()
            .url("$apiBase/cas/client/validateLogin?ticket=$ticket&service=$pageUrl")
            .header("Accept", "application/json")
            .header("User-Agent", ChnuConfig.UA)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("智慧思政登录失败: 响应解析错误")
            if (!json.optBoolean("success", false)) {
                throw CampusException("智慧思政登录失败: ${json.optString("message", "未知错误")}")
            }
            token = json.optJSONObject("result")?.optString("token", "") ?: ""
            if (token.isEmpty()) throw CampusException("智慧思政登录失败: 未获取到 token")
            loggedIn = true
        }
    }

    /** 获取综合测评成绩明细:GET ChangeScore/List */
    fun getChangeScore(): List<go.examate.hyp.campus.model.SzsScoreItem> {
        if (!loggedIn) throw CampusException("智慧思政未登录,请先调用 login()")
        val req = Request.Builder()
            .url("$apiBase/ChangeScore/List?pageNo=1&pageSize=100")
            .header("X-Access-Token", token)
            .header("Accept", "application/json")
            .header("User-Agent", ChnuConfig.UA)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("成绩查询失败: 响应解析错误")
            if (!json.optBoolean("success", false)) {
                throw CampusException("成绩查询失败: ${json.optString("message", "未知错误")}")
            }
            val records = json.optJSONObject("result")?.optJSONArray("records")
                ?: return emptyList()
            val items = mutableListOf<go.examate.hyp.campus.model.SzsScoreItem>()
            for (i in 0 until records.length()) {
                val obj = records.optJSONObject(i) ?: continue
                items.add(
                    go.examate.hyp.campus.model.SzsScoreItem(
                        testBatchName = obj.optString("testBatchName", ""),
                        studentName = obj.optString("studentName", ""),
                        studentNo = obj.optString("studentNo", ""),
                        className = obj.optString("className", ""),
                        college = obj.optString("college", ""),
                        major = obj.optString("major", ""),
                        dyScore = obj.optString("dyScore", ""),       // 德育成绩
                        zyScore = obj.optString("zyScore", ""),       // 专业成绩
                        tyScore = obj.optString("tyScore", ""),       // 体育成绩
                        nlfzEdu = obj.optString("nlfzEdu", ""),       // 劳动教育
                        tzEdu = obj.optString("tzEdu", ""),           // 体质
                        ygpbEdu = obj.optString("ygpbEdu", ""),       // 应购书本
                        dykjScore = obj.optString("dykjScore", ""),   // 党员科技
                        dyDeclareScore = obj.optString("dyDeclareScore", "")  // 党员申报
                    )
                )
            }
            return items
        }
    }
}
