package go.examate.hyp.campus

import go.examate.hyp.campus.model.AvailableCourse
import go.examate.hyp.campus.model.BuildingInfo
import go.examate.hyp.campus.model.ClassroomPageData
import go.examate.hyp.campus.model.EmptyClassroom
import go.examate.hyp.campus.model.EvaluationItem
import go.examate.hyp.campus.model.EvaluationQuestion
import go.examate.hyp.campus.model.ExamItem
import go.examate.hyp.campus.model.Grade
import go.examate.hyp.campus.model.GradeDetail
import go.examate.hyp.campus.model.ScheduleItem
import go.examate.hyp.campus.model.SelectedCourse
import go.examate.hyp.campus.model.SelectOption
import go.examate.hyp.campus.model.StudentInfo
import go.examate.hyp.campus.model.XkCredits
import go.examate.hyp.campus.model.XkStatus
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 教务系统(正方)客户端 — 从 [ChnuClient.JWXTClient] 提取。
 *
 * 通过智慧校园门户 SSO 登录,共享 [client] / [noRedirectClient] 以维持会话 cookie;
 * [getServiceTicket] 回调用于 CAS SSO 单点登录获取 service ticket。
 *
 * SSO 流程:
 *  1. CAS 获取 ticket → service=/sso/hsssologin
 *  2. GET /sso/hsssologin?ticket=xxx → 302 → /ticketlogin
 *  3. GET /ticketlogin → 302 → /xtgl/login_slogin.html(设置 JSESSIONID)
 *  4. GET /xtgl/index_initMenu.html → 验证含"教学管理"
 */
internal class JwxtApi(
    private val client: OkHttpClient,
    private val noRedirectClient: OkHttpClient,
    private val username: String,
    private val getServiceTicket: (String) -> Pair<String?, String?>
) {
    private val baseUrl: String = ChnuConfig.JWXT_BASE_URL

    @Volatile
    var loggedIn: Boolean = false
        private set

    /** SSO 登录教务系统 */
    fun login() {
        val ssoService = "$baseUrl/sso/hsssologin"
        val (ticket, redirectUrl) = getServiceTicket(ssoService)
        if (ticket == null || redirectUrl == null) {
            throw CampusException("无法获取教务系统 CAS ticket")
        }
        // Step 1: GET /sso/hsssologin?ticket=xxx(不跟随)→ 302 → /ticketlogin
        val loc1 = noRedirectGet(redirectUrl)
            ?: throw CampusException("教务系统 SSO 未返回 302")
        if (!loc1.contains("ticketlogin")) {
            throw CampusException("教务系统 SSO 重定向目标异常: $loc1")
        }
        // Step 2: GET /ticketlogin(不跟随)→ 302 → /xtgl/login_slogin.html
        val ticketloginUrl = ChnuHtmlHelpers.absolutize(loc1, baseUrl)
        val loc2 = noRedirectGet(ticketloginUrl)
        // Step 3: 跟随后续重定向到主页
        if (!loc2.isNullOrEmpty()) {
            val nextUrl = ChnuHtmlHelpers.absolutize(loc2, baseUrl)
            try {
                val req = Request.Builder().url(nextUrl)
                    .header("User-Agent", ChnuConfig.UA).build()
                client.newCall(req).execute().use { /* 跟随重定向建立 session */ }
            } catch (e: IOException) {
                // SSL 异常时 cookie 可能已设置,忽略
            }
        }
        // Step 4: 验证主页含"教学管理"
        val verifyUrl = "$baseUrl/xtgl/index_initMenu.html"
        val req = Request.Builder().url(verifyUrl)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.isSuccessful && text.contains("教学管理")) {
                loggedIn = true
                return
            }
            throw CampusException("教务系统登录验证失败(主页未含\"教学管理\")")
        }
    }

    private fun checkSession() {
        if (!loggedIn) throw CampusException("教务系统未登录,请先调用 login()")
    }

    /** GET 请求(不跟随重定向),返回 Location header,非 302 返回 null */
    private fun noRedirectGet(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", ChnuConfig.UA).build()
        noRedirectClient.newCall(req).execute().use { resp ->
            if (resp.code == 302 || resp.code == 301) return resp.header("Location")
            return null
        }
    }

    /**
     * 查询学生个人信息。
     * 优先用 JSON 接口(xsxxwh_cxCkDgxsxx),失败回退 HTML 接口(xsgrxxwh_cxXsgrxx)。
     */
    fun getStudentInfo(): StudentInfo {
        checkSession()
        // JSON 接口
        val jsonUrl = with("$baseUrl/xsxxxggl/xsxxwh_cxCkDgxsxx.html".toHttpUrl()) {
            newBuilder().addQueryParameter("gnmkdm", "N100801").build()
        }
        val jsonReq = Request.Builder().url(jsonUrl)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(jsonReq).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
            if (json != null) {
                return StudentInfo(
                    name = json.optString("xm"),
                    studentId = json.optString("xh"),
                    department = json.optString("yxmc"),
                    major = json.optString("zymc"),
                    className = json.optString("bjmc")
                )
            }
        }
        // HTML 接口回退:解析 col-xs-2/col-xs-3 或 table
        val htmlUrl = with("$baseUrl/xsxxxggl/xsgrxxwh_cxXsgrxx.html".toHttpUrl()) {
            newBuilder().addQueryParameter("gnmkdm", "N100801").build()
        }
        val htmlReq = Request.Builder().url(htmlUrl)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(htmlReq).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            val info = ChnuHtmlHelpers.parseStudentInfoHtml(html)
            return StudentInfo(
                name = info["姓名"] ?: info["xm"] ?: "",
                studentId = info["学号"] ?: info["xh"] ?: username,
                department = info["院系"] ?: info["yxmc"] ?: "",
                major = info["专业"] ?: info["zymc"] ?: "",
                className = info["班级"] ?: info["bjmc"] ?: ""
            )
        }
    }

    /** 查询成绩。xn/xq 为 null 时取当前学年学期。服务器接受 xnm/xqm 参数(数字格式)。 */
    fun getGrades(xn: String? = null, xq: String? = null): List<Grade> {
        checkSession()
        val (curXn, curXq) = ChnuConfig.currentXnXq()
        val fxn = xn ?: curXn
        val fxq = xq ?: curXq
        // 转换为 xnm/xqm 格式(正方教务要求)
        val xnm = fxn.split("-").firstOrNull() ?: fxn  // "2025-2026" → "2025"
        val xqm = mapOf("1" to "3", "2" to "12", "3" to "16")[fxq] ?: fxq
        // 先 GET 页面初始化模块 session
        getInit("$baseUrl/cjcx/cjcx_cxDgXscj.html", "N305005")
        // 分页查询所有成绩 — 使用 xnm/xqm (非 xn/xq!)
        val items = fetchAllPages(
            "$baseUrl/cjcx/cjcx_cxDgXscj.html",
            FormBody.Builder()
                .add("xnm", xnm).add("xqm", xqm)
                .add("wyyzm", "").add("tyfjm", "").add("doType", "query")
        )
        val result = mutableListOf<Grade>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            result.add(Grade(
                courseName = it.optString("kcmc"),
                credit = it.optString("xf"),
                score = it.optString("cj"),
                gpa = it.optString("jd"),
                year = it.optString("xnmmc", it.optString("xn", "")),
                semester = it.optString("xqmmc", it.optString("xqmc", "")),
                courseCode = it.optString("kch"),
                jxbId = it.optString("jxb_id"),
                examType = it.optString("kcxzmc"),
                examNature = it.optString("ksxz"),
                college = it.optString("kkbmmc")
            ))
        }
        return result
    }

    /** 查询课表。学期代码:1→"3",2→"12",3→"16" */
    fun getSchedule(xn: String? = null, xq: String? = null): List<ScheduleItem> {
        checkSession()
        val (curXn, curXq) = ChnuConfig.currentXnXq()
        val fxn = xn ?: curXn
        val fxq = xq ?: curXq
        val xnm = fxn.split("-").firstOrNull() ?: fxn
        val xqm = mapOf("1" to "3", "2" to "12", "3" to "16")[fxq] ?: fxq
        getInit("$baseUrl/kbcx/xskbcx_cxXskbcxIndex.html", "N2151")
        val form = FormBody.Builder().add("xnm", xnm).add("xqm", xqm).build()
        val req = Request.Builder().url("$baseUrl/kbcx/xskbcx_cxXsKb.html").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return emptyList()
            val kbList = json.optJSONArray("kbList") ?: return emptyList()
            val result = mutableListOf<ScheduleItem>()
            for (i in 0 until kbList.length()) {
                val it = kbList.getJSONObject(i)
                result.add(ScheduleItem(
                    day = it.optString("xqjmc"),
                    section = it.optString("jc"),
                    courseName = it.optString("kcmc"),
                    teacher = it.optString("xm"),
                    classroom = it.optString("cdmc"),
                    weeks = it.optString("zcd"),
                    credit = it.optString("xf", ""),
                    courseCategory = it.optString("kclb", it.optString("kclbmc", ""))
                ))
            }
            return result
        }
    }

    /** 查询考试安排 */
    fun getExamSchedule(xn: String? = null, xq: String? = null): List<ExamItem> {
        checkSession()
        val (curXn, curXq) = ChnuConfig.currentXnXq()
        val fxn = xn ?: curXn
        val fxq = xq ?: curXq
        getInit("$baseUrl/kwgl/kscx_cxXsksxxIndex.html", "N351004")
        val items = fetchAllPages(
            "$baseUrl/kwgl/kscx_cxXsksxxIndex.html",
            FormBody.Builder()
                .add("xn", fxn).add("xq", fxq)
                .add("csr", "").add("doType", "query")
        )
        val result = mutableListOf<ExamItem>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            result.add(ExamItem(
                courseName = it.optString("kcmc"),
                date = it.optString("kssj"),
                location = it.optString("cdmc"),
                seatNo = it.optString("zwh")
            ))
        }
        return result
    }

    /**
     * 查询已选课程(本学期选课结果)
     *
     * GET /xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&xn=xxx&xq=xxx
     */
    fun getSelectedCourses(xn: String? = null, xq: String? = null): List<SelectedCourse> {
        checkSession()
        val (curXn, curXq) = ChnuConfig.currentXnXq()
        val fxn = xn ?: curXn
        val fxq = xq ?: curXq
        val xnm = fxn.split("-").firstOrNull() ?: fxn
        val xqm = mapOf("1" to "3", "2" to "12", "3" to "16")[fxq] ?: fxq
        val httpUrl = with("$baseUrl/xsxk/zzxkyzb_cxZzxkYzbIndex.html".toHttpUrl()) {
            newBuilder()
                .addQueryParameter("gnmkdm", "N253512")
                .addQueryParameter("xn", xnm)
                .addQueryParameter("xq", xqm)
                .build()
        }
        val req = Request.Builder().url(httpUrl)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val arr = try { JSONArray(text) } catch (e: Exception) {
                // 可能是 {items:[...]} 结构
                val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return emptyList()
                json.optJSONArray("items") ?: JSONArray(text)
            }
            val result = mutableListOf<SelectedCourse>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                result.add(SelectedCourse(
                    courseName = it.optString("kcmc"),
                    teacher = it.optString("jsxx", it.optString("xm", "")),
                    credit = it.optString("xf"),
                    classTime = it.optString("sksj", ""),
                    classroom = it.optString("jxdd", ""),
                    courseType = it.optString("xsmc", ""),
                    selected = it.optString("zxjg", "成功").contains("成功")
                ))
            }
            return result
        }
    }

    /**
     * 查询教学评价(学生评价)列表
     *
     * 流程:GET /xspjgl/xspj_cxXspjIndex.html?doType=details 初始化 session
     *      POST /xspjgl/xspj_cxXspjIndex.html?doType=query 查询
     */
    fun getTeachingEvaluation(): List<EvaluationItem> {
        checkSession()
        // 初始化 session
        val initUrl = with("$baseUrl/xspjgl/xspj_cxXspjIndex.html".toHttpUrl()) {
            newBuilder()
                .addQueryParameter("doType", "details")
                .addQueryParameter("gnmkdm", "N401605")
                .addQueryParameter("layout", "default")
                .build()
        }
        client.newCall(
            Request.Builder().url(initUrl)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
        ).execute().use { /* 初始化 session */ }

        // 分页查询所有评价
        val items = fetchAllPages(
            "$baseUrl/xspjgl/xspj_cxXspjIndex.html",
            FormBody.Builder().add("doType", "query")
        )
        val result = mutableListOf<EvaluationItem>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            result.add(EvaluationItem(
                courseName = it.optString("kcmc"),
                teacher = it.optString("jzgmc", it.optString("jsmc", it.optString("jsxm", ""))),
                score = it.optString("bfzpf"),
                status = it.optString("pjzt", ""),
                submitStatus = it.optString("tjztmc", ""),
                classTime = it.optString("sksj", ""),
                classroom = it.optString("jxdd", ""),
                college = it.optString("jgmc", ""),
                courseType = it.optString("xsmc", ""),
                wjbm = it.optString("pjmbmcb_id", it.optString("wjbm", "")),
                teacherId = it.optString("jgh_id", it.optString("jghId", "")),
                courseId = it.optString("kch_id", it.optString("kchId", "")),
                jxbId = it.optString("jxb_id", it.optString("jxbid", "")),
                schoolYear = "${it.optString("xnm", "")}-${it.optString("xqm", "")}"
            ))
        }
        return result
    }

    // ════════════════════════════════════════
    //  成绩明细查询(点击课程查看平时分/期末分/总分)
    // ════════════════════════════════════════

    /**
     * 查询某门课程的成绩明细(平时分/期末分/总分构成)。
     *
     * URL: cjcx/cjcx_cxDgXsxmcj.html?gnmkdm=N305007
     *
     * @param kch 课程代码(从 Grade.courseCode 获取)
     * @param xn 学年(如 "2024-2025")
     * @param xq 学期(如 "1" 或 "2")
     * @return 成绩明细,失败返回 null
     */
    fun getGradeDetail(jxbId: String, xn: String, xq: String): GradeDetail? {
        checkSession()
        val xnm = xn.split("-").firstOrNull() ?: xn
        val xqm = mapOf("1" to "3", "2" to "12", "3" to "16")[xq] ?: xq
        getInit("$baseUrl/cjcx/cjcx_cxDgXsxmcj.html", "N305007")
        val form = FormBody.Builder()
            .add("xnm", xnm).add("xqm", xqm)
            .add("jxb_id", jxbId)
            .add("queryModel.showCount", "50")
            .add("queryModel.currentPage", "1")
            .build()
        val req = Request.Builder()
            .url("$baseUrl/cjcx/cjcx_cxXsXmcjList.html")
            .post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return null
            val items = json.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            // 解析 xmblmc/xmcj (对齐网页端字段)
            var regular = ""; var finalExam = ""; var total = ""
            val subs = mutableMapOf<String, String>()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val name = it.optString("xmblmc", "")
                val score = it.optString("xmcj", "")
                subs[name] = score
                when { name.contains("平时") -> regular = score; name.contains("期末") || name.contains("考试") -> finalExam = score; name.contains("总评") || name.contains("总") -> total = score }
            }
            if (total.isEmpty() && items.length() >= 3) total = items.optJSONObject(items.length()-1)?.optString("xmcj","") ?: ""
            if (regular.isEmpty() && items.length() >= 1) regular = items.optJSONObject(0)?.optString("xmcj","") ?: ""
            if (finalExam.isEmpty() && items.length() >= 2) finalExam = items.optJSONObject(1)?.optString("xmcj","") ?: ""
            return GradeDetail(courseName = "", credit = "", gpa = "", regularScore = regular, finalExamScore = finalExam, totalScore = total, subItems = subs)
        }
    }

    // ════════════════════════════════════════
    //  空闲教室查询(模拟网页 searchForm 提交)
    // ════════════════════════════════════════

    /**
     * 从空闲教室页面动态解析所有 select 选项(校区/楼号/场地类别)。
     * 对齐网页端: select#xqh_id, select#lh, select#cdlb_id。
     */
    fun getClassroomPageData(): ClassroomPageData {
        checkSession()
        val url = "$baseUrl/cdjy/cdjy_cxKxcdlb.html".toHttpUrl()
            .newBuilder().addQueryParameter("gnmkdm", "N2155").build()
        val req = Request.Builder().url(url)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            // 解析指定 select 的选项
            fun parseSelect(selectName: String): List<SelectOption> {
                val pattern = Regex("""<select[^>]*name=["']${selectName}["'][^>]*>(.*?)</select>""", RegexOption.DOT_MATCHES_ALL)
                val match = pattern.find(html) ?: return emptyList()
                val options = mutableListOf<SelectOption>()
                Regex("""<option\s+value="([^"]*)"[^>]*>([^<]+)</option>""").findAll(match.groupValues[1]).forEach { m ->
                    options.add(SelectOption(m.groupValues[1].trim(), m.groupValues[2].trim()))
                }
                return options
            }
            return ClassroomPageData(
                campusOptions = parseSelect("xqh_id"),
                buildingOptions = parseSelect("lh"),
                roomTypeOptions = parseSelect("cdlb_id")
            )
        }
    }

    /**
     * 查询空闲教室。
     *
     * @param buildingId 教学楼ID(空=全部)
     * @param date 日期(YYYY-MM-DD,空=今天)
     * @param startSection 起始节次
     * @param endSection 结束节次
     */
    fun getEmptyClassrooms(
        campusId: String = "1",
        roomTypeId: String = "",
        buildingId: String = "",
        startWeek: String = "",
        endWeek: String = "",
        weekDays: String = "",
        sectionRange: String = ""
    ): List<EmptyClassroom> {
        checkSession()
        // 初始化session - 必须GET页面获得隐藏字段
        getInit("$baseUrl/cdjy/cdjy_cxKxcdlb.html", "N2155")
        val (curXn, curXq) = ChnuConfig.currentXnXq()
        val fxn = curXn.split("-").firstOrNull() ?: curXn
        val xqm = mapOf("1" to "3", "2" to "12", "3" to "16")[curXq] ?: curXq

        // 表单字段对齐网页隐藏字段(从页面HTML分析得到)
        val formBuilder = FormBody.Builder()
            .add("xnm", fxn).add("xqm", xqm)
            .add("cdyysqkz", "0")
            .add("glrycdyysfxysp", "1")
            .add("xqFlag", "0")
            .add("syr", "")  // 学号,服务器端自动获取
            .add("syrxm", "") // 姓名,服务器端自动获取
            .add("yuyjsctbj", "1")
            .add("jylysfbt", "").add("yyrssfbt", "")
            .add("yylbdmsfbt", "").add("ksssfbt", "")
            .add("cdyyfs_val", "")            .add("cdjylx", "")
            .add("qszws", "").add("jszws", "").add("cdmc", "")
            .add("jyfs", "2").add("qssj", "").add("jssj", "").add("sjfw", "")
            .add("qssd", "").add("jssd", "")
            .add("xqh_id", campusId)
        if (buildingId.isNotEmpty()) formBuilder.add("lh", buildingId)
        if (roomTypeId.isNotEmpty()) formBuilder.add("cdlb_id", roomTypeId)
        // 周次/星期/节次筛选 (对齐网页 kxcdlb.js)
        if (startWeek.isNotEmpty()) formBuilder.add("qssd", startWeek)
        if (endWeek.isNotEmpty()) formBuilder.add("jssd", endWeek)
        if (weekDays.isNotEmpty()) formBuilder.add("xqj", weekDays)
        if (sectionRange.isNotEmpty()) formBuilder.add("sjfw", sectionRange)
        formBuilder.add("queryModel.showCount", "50")
        formBuilder.add("queryModel.currentPage", "1")

        val req = Request.Builder().url("$baseUrl/cdjy/cdjy_cxKxcdlb.html?doType=query")
            .post(formBuilder.build())
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return emptyList()
            val items = json.optJSONArray("items") ?: return emptyList()
            val result = mutableListOf<EmptyClassroom>()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                result.add(EmptyClassroom(
                    classroomName = it.optString("cdmc", ""),
                    buildingName = it.optString("lh", ""),
                    capacity = it.optString("zws", ""),
                    roomType = it.optString("cdlbmc", ""),
                    campus = it.optString("xqmc", "")))
            }
            return result
        }
    }

    // ════════════════════════════════════════
    //  教学评价 - 获取评价题目 & 提交评价
    // ════════════════════════════════════════

    /**
     * 获取某门课程的评价指标/题目。
     * @param wjbm 问卷编码(从 EvaluationItem.wjbm 获取)
     */
    fun getEvaluationQuestions(wjbm: String): List<EvaluationQuestion> {
        checkSession()
        val form = FormBody.Builder()
            .add("doType", "getData")
            .add("wjbm", wjbm)
            .build()
        val req = Request.Builder()
            .url("$baseUrl/xspjgl/xspj_cxXspjIndex.html")
            .post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return emptyList()
            val items = json.optJSONArray("items")
                ?: json.optJSONObject("data")?.optJSONArray("items")
                ?: json.optJSONArray("data")
            if (items == null) return emptyList()
            val result = mutableListOf<EvaluationQuestion>()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                result.add(EvaluationQuestion(
                    id = it.optString("zbh", it.optString("id", "")),
                    name = it.optString("zbmc", it.optString("name", it.optString("mc", ""))),
                    maxScore = it.optDouble("zfz", it.optDouble("maxScore", 100.0)),
                    selectedScore = it.optDouble("pf", it.optDouble("selectedScore", -1.0)),
                    type = it.optString("type", "")
                ))
            }
            return result
        }
    }

    /**
     * 提交教学评价。
     * @param wjbm 问卷编码
     * @param xn 学年
     * @param xq 学期
     * @param scores 评价分数 Map<指标ID, 分数>
     */
    fun submitEvaluation(wjbm: String, xn: String, xq: String, scores: Map<String, Double>): String {
        checkSession()
        val builder = FormBody.Builder()
            .add("doType", "save")
            .add("wjbm", wjbm)
            .add("xn", xn)
            .add("xq", xq)
        scores.forEach { (id, score) ->
            builder.add("pf_$id", score.toInt().toString())
        }
        val req = Request.Builder()
            .url("$baseUrl/xspjgl/xspj_cxXspjIndex.html")
            .post(builder.build())
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
            val msg = json?.optString("msg", "") ?: ""
            if (msg.contains("成功") || text.contains("成功")) return "评价提交成功"
            return msg.ifEmpty { text.take(200) }
        }
    }

    // ════════════════════════════════════════
    //  课表确认页(选课结果) - 使用/kbcx/xskbqr 替代 xsxk/zzxkyzb
    // ════════════════════════════════════════

    /**
     * 查询已选课程(选课结果,来自课表确认页)。
     * 改用 /kbcx/xskbqr_cxXskbqrIndex.html?doType=details&gnmkdm=N2158
     * 此页面在任何时候都显示已确认的课表,不受选课时间段限制。
     */
    fun getScheduleConfirmation(xn: String? = null, xq: String? = null): List<SelectedCourse> {
        checkSession()
        val (curXn, curXq) = ChnuConfig.currentXnXq()
        val fxn = xn ?: curXn
        val fxq = xq ?: curXq
        val xnm = fxn.split("-").firstOrNull() ?: fxn
        val xqm = mapOf("1" to "3", "2" to "12", "3" to "16")[fxq] ?: fxq

        // 初始化 session - 必须带 doType=details
        getInit("$baseUrl/kbcx/xskbqr_cxXskbqrIndex.html", "N2158")
        val extraInitUrl = with("$baseUrl/kbcx/xskbqr_cxXskbqrIndex.html".toHttpUrl()) {
            newBuilder()
                .addQueryParameter("doType", "details")
                .addQueryParameter("gnmkdm", "N2158")
                .addQueryParameter("layout", "default")
                .build()
        }
        client.newCall(
            Request.Builder().url(extraInitUrl)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
        ).execute().use { }

        // 分页查询 - 使用 xnm/xqm 参数(非 xn/xq)
        val items = fetchAllPages(
            "$baseUrl/kbcx/xskbqr_cxXskbqrIndex.html",
            FormBody.Builder()
                .add("xnm", xnm).add("xqm", xqm)
                .add("doType", "query")
        )
        val result = mutableListOf<SelectedCourse>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            // jsxx 字段格式: "工号/姓名/职称" 如 "206075/刘书群/无"
            val jsxx = it.optString("jsxx", "")
            val teacherName = if (jsxx.contains("/")) jsxx.split("/").getOrElse(1) { jsxx } else jsxx
            result.add(SelectedCourse(
                courseName = it.optString("kcmc"),
                teacher = teacherName.ifEmpty { it.optString("xm", "") },
                credit = it.optString("xf", ""),
                classTime = it.optString("sksj", ""),
                classroom = it.optString("jxdd", ""),
                courseType = it.optString("kcxz", it.optString("xsmc", "")),
                selected = true,
                weeks = it.optString("zcd", ""),
                courseCode = it.optString("kch", ""),
                examMethod = it.optString("khfsmc", "")
            ))
        }
        return result
    }

    // ════════════════════════════════════════
    //  实验课表查询
    // ════════════════════════════════════════

    /**
     * 查询实验课表。实验课表可能与理论课时间不一致。
     */
    fun getExperimentSchedule(xn: String? = null, xq: String? = null): List<ScheduleItem> {
        checkSession()
        val (curXn, curXq) = ChnuConfig.currentXnXq()
        val fxn = xn ?: curXn
        val fxq = xq ?: curXq
        val xnm = fxn.split("-").firstOrNull() ?: fxn
        val xqm = mapOf("1" to "3", "2" to "12", "3" to "16")[fxq] ?: fxq

        getInit("$baseUrl/kbcx/xskbcx_cxXskbcxIndex.html", "N2151")
        val form = FormBody.Builder()
            .add("xnm", xnm).add("xqm", xqm)
            .add("kclb", "sy")
            .build()
        val req = Request.Builder().url("$baseUrl/kbcx/xskbcx_cxXsKb.html").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return emptyList()
            val kbList = json.optJSONArray("kbList") ?: return emptyList()
            val result = mutableListOf<ScheduleItem>()
            for (i in 0 until kbList.length()) {
                val it = kbList.getJSONObject(i)
                result.add(ScheduleItem(
                    day = it.optString("xqjmc"),
                    section = it.optString("jc"),
                    courseName = it.optString("kcmc"),
                    teacher = it.optString("xm"),
                    classroom = it.optString("cdmc"),
                    weeks = it.optString("zcd"),
                    credit = it.optString("xf", ""),
                    courseCategory = it.optString("kclb", it.optString("kclbmc", ""))
                ))
            }
            return result
        }
    }

    /** GET 页面初始化模块 session(带 gnmkdm) */
    private fun getInit(url: String, gnmkdm: String) {
        val httpUrl = with(url.toHttpUrl()) {
            newBuilder().addQueryParameter("gnmkdm", gnmkdm).build()
        }
        val req = Request.Builder().url(httpUrl)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { /* 初始化 session */ }
    }

    /**
     * 分页查询所有条目(正方教务通用)。
     *
     * 正方系统使用 jqGrid 风格的分页参数:
     *   - queryModel.showCount: 每页条数
     *   - queryModel.currentPage: 当前页码(从 1 开始)
     * 响应中通过 totalCount 获知总数, totalPage 获知总页数。
     *
     * 参考: c:\project\api\chnu\jwxt.py 的 _fetch_all_pages 实现。
     */
    private fun fetchAllPages(url: String, baseForm: FormBody.Builder, pageSize: Int = 100): JSONArray {
        val all = JSONArray()
        var currentPage = 1
        while (true) {
            val form = FormBody.Builder()
            baseForm.build().let { bf ->
                for (i in 0 until bf.size) {
                    form.add(bf.name(i), bf.value(i))
                }
            }
            // jqGrid 风格分页参数(正方系统标准)
            form.add("queryModel.showCount", pageSize.toString())
            form.add("queryModel.currentPage", currentPage.toString())
            val req = Request.Builder().url(url).post(form.build())
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            var itemsCount = 0
            var totalCount = 0
            var totalPages = 0
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = ChnuHtmlHelpers.parseJsonObject(text)
                if (json == null) break
                val items = json.optJSONArray("items") ?: JSONArray()
                itemsCount = items.length()
                totalCount = json.optInt("totalCount", 0)
                totalPages = json.optInt("totalPage", 0)
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    all.put(obj)
                }
            }
            // 无数据则停止
            if (itemsCount == 0) break
            // 已到最后一页 或 累计已达总数
            if (totalPages > 0 && currentPage >= totalPages) break
            if (totalCount > 0 && all.length() >= totalCount) break
            // 防御性上限
            if (currentPage >= 200) break
            currentPage++
        }
        return all
    }

    // ════════════════════════════════════════
    //  选课模块(自主选课)
    // ════════════════════════════════════════

    /** 检查选课状态,返回是否在选课阶段及学年学期信息 */
    fun getXkStatus(): XkStatus {
        checkSession()
        val url = with("$baseUrl/xsxk/zzxkyzb_cxZzxkYzbIndex.html".toHttpUrl()) {
            newBuilder().addQueryParameter("gnmkdm", "N253512").build()
        }
        val req = Request.Builder().url(url)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            val isXk = Regex("""iskxk\s*[:=]\s*["']?(\d)""").find(html)?.groupValues?.get(1) ?: "0"
            val xn = Regex("""xkxnm\s*[:=]\s*["']?(\d+)""").find(html)?.groupValues?.get(1) ?: ""
            val xq = Regex("""xkxqm\s*[:=]\s*["']?(\d+)""").find(html)?.groupValues?.get(1) ?: ""
            val xkkzId = Regex("""xkkz_id\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1) ?: ""
            val currentXnxq = Regex("""currentXnxq\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1) ?: ""
            return XkStatus(isXk, xn, xq, xkkzId, currentXnxq)
        }
    }

    /**
     * 查询可选课程(自主选课)。
     * @param xn 学年(如 "2025")
     * @param xq 学期代码(如 "12")
     */
    fun getAvailableCourses(xn: String, xq: String): List<AvailableCourse> {
        checkSession()
        // Step 1: 初始化选课页面
        getInit("$baseUrl/xsxk/zzxkyzb_cxZzxkYzbIndex.html", "N253512")
        // Step 2: 加载选课区域
        val formDisplay = FormBody.Builder()
            .add("xkxnm", xn).add("xkxqm", xq)
            .add("xkkz_id", "")
            .add("gnmkdm", "N253512")
            .build()
        client.newCall(
            Request.Builder().url("$baseUrl/xsxk/zzxkyzb_cxZzxkYzbDisplay.html").post(formDisplay)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
        ).execute().use { /* 初始化显示 */ }
        // Step 3: 分页查询所有可选课程
        val items = fetchAllPages(
            "$baseUrl/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html",
            FormBody.Builder()
                .add("xkxnm", xn).add("xkxqm", xq)
                .add("xkkz_id", "")
                .add("gnmkdm", "N253512")
        )
        val result = mutableListOf<AvailableCourse>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            result.add(AvailableCourse(
                courseName = it.optString("kcmc"),
                courseCode = it.optString("kch_id"),
                teacher = it.optString("jsxx", it.optString("jzgmc", "")),
                classTime = it.optString("sksj", ""),
                classroom = it.optString("jxdd", ""),
                credit = it.optString("xf"),
                capacity = "${it.optString("yl", "?")}/${it.optString("rl", "?")}",
                selected = it.optString("xkzt", ""),
                jxbId = it.optString("jxb_id"),
                kchId = it.optString("kch_id"),
                doJxbId = it.optString("do_jxb_id"),
                kklxdm = it.optString("kklxdm")
            ))
        }
        return result
    }

    /** 查询已选课程(选课模块) */
    fun getChosenCourses(xn: String, xq: String): List<AvailableCourse> {
        checkSession()
        val items = fetchAllPages(
            "$baseUrl/xsxk/zzxkyzb_cxZzxkYzbChoosed.html",
            FormBody.Builder()
                .add("xkxnm", xn).add("xkxqm", xq)
                .add("gnmkdm", "N253512")
        )
        val result = mutableListOf<AvailableCourse>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            result.add(AvailableCourse(
                courseName = it.optString("kcmc"),
                courseCode = it.optString("kch_id"),
                teacher = it.optString("jsxx", ""),
                classTime = it.optString("sksj", ""),
                classroom = it.optString("jxdd", ""),
                credit = it.optString("xf"),
                capacity = "",
                selected = "已选",
                jxbId = it.optString("jxb_id"),
                kchId = it.optString("kch_id"),
                doJxbId = it.optString("do_jxb_id"),
                kklxdm = it.optString("kklxdm")
            ))
        }
        return result
    }

    /** 选课(快速选课),返回结果消息 */
    fun chooseCourse(jxbId: String, kchId: String, doJxbId: String, kklxdm: String, xn: String, xq: String): String {
        checkSession()
        val form = FormBody.Builder()
            .add("jxb_id", jxbId)
            .add("kch_id", kchId)
            .add("do_jxb_id", doJxbId)
            .add("kklxdm", kklxdm)
            .add("xkxnm", xn)
            .add("xkxqm", xq)
            .build()
        val req = Request.Builder().url("$baseUrl/xsxk/zzxkyzb_xkZzxkyzbQuickly.html").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
            val flag = json?.optString("flag") ?: ""
            val msg = json?.optString("msg") ?: text
            return when {
                flag == "1" || msg.contains("成功") -> "选课成功"
                msg.contains("已选") -> "该课程已选"
                msg.contains("满") -> "课程容量已满"
                msg.contains("冲突") -> "时间冲突"
                msg.isNotEmpty() -> msg
                else -> "选课失败"
            }
        }
    }

    /** 退课,返回结果消息 */
    fun cancelCourse(jxbId: String, kchId: String, doJxbId: String, xn: String, xq: String): String {
        checkSession()
        val form = FormBody.Builder()
            .add("jxb_id", jxbId)
            .add("kch_id", kchId)
            .add("do_jxb_id", doJxbId)
            .add("xkxnm", xn)
            .add("xkxqm", xq)
            .build()
        val paths = listOf("xsxk/zzxkyzb_tuiKZzxkyzb.html", "xsxk/zzxkyzb_tuik.html")
        for (path in paths) {
            val req = Request.Builder().url("$baseUrl/$path").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 200) {
                    val text = resp.body?.string() ?: ""
                    val json = ChnuHtmlHelpers.parseJsonObject(text)
                    val flag = json?.optString("flag") ?: ""
                    val msg = json?.optString("msg") ?: text
                    if (flag == "1" || msg.contains("成功") || text.contains("成功")) return "退课成功"
                    if (msg.isNotEmpty()) return msg
                }
            }
        }
        return "退课失败"
    }

    /** 查询选课学分信息 */
    fun getXkCredits(xn: String, xq: String): XkCredits {
        checkSession()
        var required = ""
        var obtained = ""
        var history = ""
        // 应修/已修学分
        val form1 = FormBody.Builder()
            .add("xkxnm", xn).add("xkxqm", xq)
            .add("gnmkdm", "N253512")
            .build()
        client.newCall(
            Request.Builder().url("$baseUrl/xsxk/zzxkyzb_cxYinxyixxfByKklxdm.html").post(form1)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
            required = json?.optString("yxxf", json?.optString("yxxfmc", "")) ?: ""
            obtained = json?.optString("yxxf2", json?.optString("yxxfys", "")) ?: ""
        }
        // 历年已获学分
        val form2 = FormBody.Builder()
            .add("xkxnm", xn).add("xkxqm", xq)
            .add("gnmkdm", "N253512")
            .build()
        client.newCall(
            Request.Builder().url("$baseUrl/xsxk/zzxkyzb_cxZzxkYzbLnyhxf.html").post(form2)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text)
            history = json?.optString("lnyhxf", json?.optString("yhxf", "")) ?: ""
        }
        return XkCredits(required, obtained, history)
    }
}
