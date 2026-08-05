package go.examate.hyp.campus

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 智慧校园门户客户端(oshall.chnu.edu.cn) — 班级通讯录。
 *
 * 真实 API 流程(通过抓包验证):
 * 1. CAS SSO 获取 ticket
 * 2. GET {base}/cas/client/validateLogin?ticket=xxx&service=xxx → 返回 JWT token
 * 3. GET {base}/applets/synthesis/query/studentPhoneListPage?pageNo=1&pageSize=100 → 通讯录,header 带 X-Access-Token
 *
 * API base = https://oshall.chnu.edu.cn/apiZhxy
 * 框架: Jeecg-Boot (与智慧思政 jgf 同框架)
 * 返回字段: phone / realname / departName
 */
internal class ZhxyApi(
    private val client: OkHttpClient,
    private val getServiceTicket: (String) -> Pair<String?, String?>
) {
    private val apiBase = ChnuConfig.ZHXY_API_BASE
    private val portalUrl = ChnuConfig.ZHXY_URL

    @Volatile
    private var token: String = ""

    @Volatile
    var loggedIn: Boolean = false
        private set

    /** CAS SSO 登录:GET cas/client/validateLogin → JWT token */
    fun login() {
        val (ticket, _) = getServiceTicket(portalUrl)
        if (ticket.isNullOrEmpty()) throw CampusException("智慧门户登录失败: 无法获取 CAS ticket")
        val req = Request.Builder()
            .url("$apiBase/cas/client/validateLogin?ticket=$ticket&service=$portalUrl")
            .header("Accept", "application/json")
            .header("User-Agent", ChnuConfig.UA)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("智慧门户登录失败: 响应解析错误")
            if (!json.optBoolean("success", false)) {
                throw CampusException("智慧门户登录失败: ${json.optString("message", "未知错误")}")
            }
            token = json.optJSONObject("result")?.optString("token", "") ?: ""
            if (token.isEmpty()) throw CampusException("智慧门户登录失败: 未获取到 token")
            loggedIn = true
        }
    }

    /** 查询班级通讯录:GET studentPhoneListPage,返回 phone/realname/departName */
    fun getStudentPhoneList(): List<go.examate.hyp.campus.model.ClassContact> {
        if (!loggedIn) throw CampusException("智慧门户未登录,请先调用 login()")
        val req = Request.Builder()
            .url("$apiBase/applets/synthesis/query/studentPhoneListPage?pageNo=1&pageSize=200")
            .header("X-Access-Token", token)
            .header("Accept", "application/json")
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", portalUrl)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("通讯录查询失败: 响应解析错误")
            if (!json.optBoolean("success", false)) {
                throw CampusException("通讯录查询失败: ${json.optString("message", "未知错误")}")
            }
            val records = json.optJSONObject("result")?.optJSONArray("records")
                ?: return emptyList()
            val list = mutableListOf<go.examate.hyp.campus.model.ClassContact>()
            for (i in 0 until records.length()) {
                val obj = records.optJSONObject(i) ?: continue
                list.add(
                    go.examate.hyp.campus.model.ClassContact(
                        studentName = obj.optString("realname", ""),
                        phone = obj.optString("phone", ""),
                        className = obj.optString("departName", "")
                    )
                )
            }
            // 按姓名拼音排序(中文用 Collator,英文按字母序)
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            return list.sortedWith { a, b -> collator.compare(a.studentName, b.studentName) }
        }
    }
}
