package go.examate.hyp.campus

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 智慧思政 H5 系统(oshall/hbsz-h5)API 客户端。
 *
 * 对应 H5 应用:
 * - 综测测评: https://oshall.chnu.edu.cn/hbsz-h5/evaluating
 * - 离返校登记: https://oshall.chnu.edu.cn/hbsz-h5?path=holidayLeave
 * - 寒暑假留校登记: https://oshall.chnu.edu.cn/hbsz-h5?path=stayInSchool
 *
 * 认证流程(与 ZhxyApi 一致,复用 oshall CAS SSO):
 * 1. CAS SSO 获取 ticket → GET cas/client/validateLogin → JWT token
 * 2. 后续请求 header 带 X-Access-Token
 *
 * 框架: Jeecg-Boot(与 oshall 同框架)
 *
 * 注意: API 端点路径基于 Jeecg-Boot 框架惯例推测,后续抓包验证后可能需调整。
 */
internal class HbszApi(
    private val client: OkHttpClient,
    private val getServiceTicket: (String) -> Pair<String?, String?>
) {
    /** API 基础路径 — hbsz-h5 后端 API */
    private val apiBase = ChnuConfig.HBSZ_API_BASE
    /** H5 前端页面 URL(CAS service 参数用) */
    private val pageUrl = ChnuConfig.HBSZ_H5_BASE

    @Volatile
    private var token: String = ""

    @Volatile
    var loggedIn: Boolean = false
        private set

    /** 当前 JWT token(供 WebView 注入到请求 header) */
    val tokenValue: String get() = token

    // ════════════════════════════════════════
    //  CAS SSO 登录
    // ════════════════════════════════════════

    /** CAS SSO 登录:GET cas/client/validateLogin → JWT token */
    fun login() {
        val (ticket, _) = getServiceTicket(pageUrl)
        if (ticket.isNullOrEmpty()) throw CampusException("智慧思政 H5 登录失败: 无法获取 CAS ticket")
        val req = Request.Builder()
            .url("$apiBase/cas/client/validateLogin?ticket=$ticket&service=$pageUrl")
            .header("Accept", "application/json")
            .header("User-Agent", ChnuConfig.UA)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("智慧思政 H5 登录失败: 响应解析错误")
            if (!json.optBoolean("success", false)) {
                throw CampusException("智慧思政 H5 登录失败: ${json.optString("message", "未知错误")}")
            }
            token = json.optJSONObject("result")?.optString("token", "") ?: ""
            if (token.isEmpty()) throw CampusException("智慧思政 H5 登录失败: 未获取到 token")
            loggedIn = true
        }
    }

    // ════════════════════════════════════════
    //  综测测评
    // ════════════════════════════════════════

    /**
     * 获取综测测评列表(对应 H5 /evaluating 页面)。
     *
     * API 路径基于 Jeecg-Boot 框架推测,后续抓包验证后可能需调整。
     */
    fun getEvaluatingList(): List<go.examate.hyp.campus.model.HbszEvaluatingItem> {
        if (!loggedIn) throw CampusException("智慧思政 H5 未登录")
        val req = Request.Builder()
            .url("$apiBase/evaluating/list?pageNo=1&pageSize=100")
            .header("X-Access-Token", token)
            .header("Accept", "application/json")
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", pageUrl)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("综测测评查询失败: 响应解析错误")
            if (!json.optBoolean("success", false)) {
                throw CampusException("综测测评查询失败: ${json.optString("message", "未知错误")}")
            }
            val records = json.optJSONObject("result")?.optJSONArray("records")
                ?: return emptyList()
            val items = mutableListOf<go.examate.hyp.campus.model.HbszEvaluatingItem>()
            for (i in 0 until records.length()) {
                val obj = records.optJSONObject(i) ?: continue
                items.add(
                    go.examate.hyp.campus.model.HbszEvaluatingItem(
                        batchName = obj.optString("batchName", obj.optString("testBatchName", "")),
                        studentName = obj.optString("studentName", obj.optString("realname", "")),
                        className = obj.optString("className", obj.optString("departName", "")),
                        totalScore = obj.optString("totalScore", obj.optString("score", "")),
                        dyScore = obj.optString("dyScore", ""),
                        zyScore = obj.optString("zyScore", ""),
                        tyScore = obj.optString("tyScore", ""),
                        status = obj.optString("status", "")
                    )
                )
            }
            return items
        }
    }

    // ════════════════════════════════════════
    //  离返校登记
    // ════════════════════════════════════════

    /**
     * 获取离返校登记记录(对应 H5 ?path=holidayLeave)。
     *
     * API 路径基于 Jeecg-Boot 框架推测,后续抓包验证后可能需调整。
     */
    fun getHolidayLeaveList(): List<go.examate.hyp.campus.model.HolidayLeaveRecord> {
        if (!loggedIn) throw CampusException("智慧思政 H5 未登录")
        val req = Request.Builder()
            .url("$apiBase/holidayLeave/list?pageNo=1&pageSize=100")
            .header("X-Access-Token", token)
            .header("Accept", "application/json")
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", pageUrl)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("离返校登记查询失败: 响应解析错误")
            if (!json.optBoolean("success", false)) {
                throw CampusException("离返校登记查询失败: ${json.optString("message", "未知错误")}")
            }
            val records = json.optJSONObject("result")?.optJSONArray("records")
                ?: return emptyList()
            val items = mutableListOf<go.examate.hyp.campus.model.HolidayLeaveRecord>()
            for (i in 0 until records.length()) {
                val obj = records.optJSONObject(i) ?: continue
                items.add(
                    go.examate.hyp.campus.model.HolidayLeaveRecord(
                        studentName = obj.optString("studentName", obj.optString("realname", "")),
                        type = obj.optString("type", obj.optString("leaveType", "")),
                        leaveDate = obj.optString("leaveDate", obj.optString("startDate", "")),
                        returnDate = obj.optString("returnDate", obj.optString("endDate", "")),
                        destination = obj.optString("destination", obj.optString("address", "")),
                        status = obj.optString("status", ""),
                        remark = obj.optString("remark", obj.optString("reason", ""))
                    )
                )
            }
            return items
        }
    }

    // ════════════════════════════════════════
    //  寒暑假留校登记
    // ════════════════════════════════════════

    /**
     * 获取留校登记记录(对应 H5 ?path=stayInSchool)。
     *
     * API 路径基于 Jeecg-Boot 框架推测,后续抓包验证后可能需调整。
     */
    fun getStayInSchoolList(): List<go.examate.hyp.campus.model.StayInSchoolRecord> {
        if (!loggedIn) throw CampusException("智慧思政 H5 未登录")
        val req = Request.Builder()
            .url("$apiBase/stayInSchool/list?pageNo=1&pageSize=100")
            .header("X-Access-Token", token)
            .header("Accept", "application/json")
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", pageUrl)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
                ?: throw CampusException("留校登记查询失败: 响应解析错误")
            if (!json.optBoolean("success", false)) {
                throw CampusException("留校登记查询失败: ${json.optString("message", "未知错误")}")
            }
            val records = json.optJSONObject("result")?.optJSONArray("records")
                ?: return emptyList()
            val items = mutableListOf<go.examate.hyp.campus.model.StayInSchoolRecord>()
            for (i in 0 until records.length()) {
                val obj = records.optJSONObject(i) ?: continue
                items.add(
                    go.examate.hyp.campus.model.StayInSchoolRecord(
                        studentName = obj.optString("studentName", obj.optString("realname", "")),
                        semester = obj.optString("semester", obj.optString("batchName", "")),
                        startDate = obj.optString("startDate", obj.optString("liveStartDate", "")),
                        endDate = obj.optString("endDate", obj.optString("liveEndDate", "")),
                        dormitory = obj.optString("dormitory", obj.optString("roomNo", "")),
                        reason = obj.optString("reason", obj.optString("stayReason", "")),
                        status = obj.optString("status", ""),
                        remark = obj.optString("remark", "")
                    )
                )
            }
            return items
        }
    }
}
