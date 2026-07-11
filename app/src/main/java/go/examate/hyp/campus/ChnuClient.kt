package go.examate.hyp.campus

import android.util.Base64
import go.examate.hyp.campus.model.AvailableCourse
import go.examate.hyp.campus.model.BankCard
import go.examate.hyp.campus.model.BillRecord
import go.examate.hyp.campus.model.EcardBalance
import go.examate.hyp.campus.model.EvaluationItem
import go.examate.hyp.campus.model.ExamItem
import go.examate.hyp.campus.model.Grade
import go.examate.hyp.campus.model.ModuleScore
import go.examate.hyp.campus.model.PayArea
import go.examate.hyp.campus.model.PayBuilding
import go.examate.hyp.campus.model.PayResult
import go.examate.hyp.campus.model.RoomElectricity
import go.examate.hyp.campus.model.ScheduleItem
import go.examate.hyp.campus.model.ScoreDetail
import go.examate.hyp.campus.model.ScoreSummary
import go.examate.hyp.campus.model.SelectedCourse
import go.examate.hyp.campus.model.StudentInfo
import go.examate.hyp.campus.model.XkCredits
import go.examate.hyp.campus.model.XkStatus
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 淮北师范大学智慧校园 API 客户端
 *
 * 将 Python 项目(chnu 各模块)的逻辑转换为 Kotlin,封装:
 * - CAS 统一身份认证(AES-CBC + ZeroPadding 加密,三步登录)
 * - 教务系统(JWXT:成绩/课表/考试/个人信息)
 * - 一卡通系统(Ecard:余额/账单)
 * - 第二课堂(SC:成绩明细/汇总)
 *
 * 用 OkHttp 替代 Python requests,Cookie 由 [SimpleCookieJar] 内存管理,
 * SSL 信任所有证书(校园系统自签名),不跟随重定向由派生 client 实现。
 *
 * 所有方法为普通同步函数(非 suspend),调用方需在协程 withContext(Dispatchers.IO) 中调用。
 */
class ChnuClient(
    /** 统一认证账号(学号/工号) */
    val username: String,
    /** 统一认证密码(明文,不会打印到日志) */
    private val password: String
) {

    /** 信任所有证书的 client(跟随重定向),所有子系统共享以共享 cookie */
    private val client: OkHttpClient = createTrustAllClient(true)
    /** 不跟随重定向的派生 client,用于 CAS 表单提交和获取 Service Ticket */
    private val noRedirectClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** CAS 登录状态 */
    @Volatile
    var loggedIn: Boolean = false
        private set

    /** 教务系统客户端(共享 cookie) */
    val jwxt = JWXTClient()
    /** 一卡通系统客户端(共享 cookie) */
    val ecard = EcardClient()
    /** 第二课堂系统客户端(共享 cookie + JWT token) */
    val sc = SCClient()

    // ==================== SSL 信任所有证书 ====================

    /**
     * 创建信任所有证书的 OkHttpClient。
     * 校园系统(auth/jwxt/sc)使用自签名证书,一卡通为内网 HTTP,均需绕过证书校验。
     */
    private fun createTrustAllClient(followRedirects: Boolean): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .cookieJar(SimpleCookieJar())
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(ChnuConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // 一卡通余额页面可能很慢
            .build()
    }

    // ==================== AES 加密 ====================

    /**
     * AES-CBC + ZeroPadding 加密密码(与前端 common.js encryption() 一致)。
     * 输出 Base64 字符串。
     */
    private fun encryptPassword(pwd: String): String {
        val key = ChnuConfig.AES_KEY.toByteArray(Charsets.UTF_8)
        val iv = ChnuConfig.AES_IV.toByteArray(Charsets.UTF_8)
        val plaintext = pwd.toByteArray(Charsets.UTF_8)
        // ZeroPadding:已对齐则不填充,否则用 \x00 填到下一个 16 字节边界
        val blockSize = 16
        val padded = if (plaintext.size % blockSize == 0) plaintext
        else plaintext + ByteArray(blockSize - (plaintext.size % blockSize))
        // NoPadding 因已手动 ZeroPad
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(padded)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    // ==================== CAS 三步登录 ====================

    /**
     * 执行 CAS 统一身份认证登录(三步流程)。
     *
     * Step1: GET 登录页 → 提取 execution 等隐藏字段
     * Step2: POST /authserver/userLogin(AJAX 校验账号密码)
     * Step3: POST /authserver/login?service=xxx(表单提交,不跟随重定向)→ 302 Location 含 ticket = 成功
     *
     * @param serviceUrl 登录成功后跳转的目标系统 URL,默认智慧校园门户
     * @throws CampusException 登录失败(密码错误/需要滑动验证/网络异常等)
     */
    fun login(serviceUrl: String = ChnuConfig.ZHXY_URL) {
        // Step 1: GET 登录页,提取 execution 字段和 cookies
        val (fields, loginPageUrl) = fetchLoginPage(serviceUrl)
        if (fields["execution"].isNullOrEmpty()) {
            throw CampusException("无法从登录页面提取 execution 字段")
        }

        // Step 2: AJAX 验证用户名密码
        ajaxLogin(loginPageUrl)

        // Step 3: 表单提交(不跟随重定向)
        val encryptedPwd = encryptPassword(password).replace("%2B", "+")
        val formBuilder = FormBody.Builder()
            .add("username", username)
            .add("password", encryptedPwd)
            .add("execution", fields["execution"] ?: "e1s1")
            .add("_eventId", fields["_eventId"] ?: "submit")
            .add("loginType", fields["loginType"] ?: "")
            .add("geolocation", fields["geolocation"] ?: "")
        // 额外隐藏字段(staticDomainUrl 等)
        listOf("staticDomainUrl", "pc-remember", "h5-remember").forEach { k ->
            fields[k]?.let { formBuilder.add(k, it) }
        }
        val reqUrl = with(ChnuConfig.CAS_LOGIN_URL.toHttpUrl()) {
            newBuilder().addQueryParameter("service", serviceUrl).build()
        }
        val request = Request.Builder().url(reqUrl).post(formBuilder.build())
            .header("Referer", loginPageUrl)
            .build()
        noRedirectClient.newCall(request).execute().use { resp ->
            if (checkLoginSuccess(resp)) {
                loggedIn = true
                return
            }
            val body = try { resp.body?.string() ?: "" } catch (e: Exception) { "" }
            throw CampusException("CAS登录失败: ${parseLoginError(body)}")
        }
    }

    /**
     * Step 1: GET 登录页,提取所有隐藏 input 字段。
     * @return (字段映射, 登录页最终 URL[用于 AJAX 的 serviceUrl])
     */
    private fun fetchLoginPage(serviceUrl: String): Pair<MutableMap<String, String>, String> {
        val reqUrl = with(ChnuConfig.CAS_LOGIN_URL.toHttpUrl()) {
            newBuilder().addQueryParameter("service", serviceUrl).build()
        }
        val request = Request.Builder().url(reqUrl)
            .header("User-Agent", ChnuConfig.UA)
            .build()
        val html: String
        val finalUrl: String
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw CampusException("获取登录页失败: HTTP ${resp.code}")
            }
            html = resp.body?.string() ?: ""
            finalUrl = resp.request.url.toString()
        }
        val fields = mutableMapOf<String, String>()
        // execution 字段
        Regex("""name="execution"\s+value="([^"]+)"""").find(html)?.let {
            fields["execution"] = it.groupValues[1]
        }
        // _eventId 字段
        Regex("""name="_eventId"\s+value="([^"]+)"""").find(html)?.let {
            fields["_eventId"] = it.groupValues[1]
        }
        // loginType / geolocation / staticDomainUrl 等
        listOf("loginType", "geolocation", "staticDomainUrl", "pc-remember", "h5-remember").forEach { name ->
            Regex("""name="$name"\s+value="([^"]*)"""").find(html)?.let {
                fields[name] = it.groupValues[1]
            }
        }
        return fields to finalUrl
    }

    /**
     * Step 2: AJAX 验证登录(POST /authserver/userLogin)。
     * @throws CampusException 密码错误或需要滑动验证
     */
    private fun ajaxLogin(loginPageUrl: String) {
        val encryptedPwd = encryptPassword(password).replace("%2B", "+")
        val form = FormBody.Builder()
            .add("serviceUrl", loginPageUrl)
            .add("password", encryptedPwd)
            .add("username", username)
            .add("loginCaptcha", "2") // 初始值 2,滑动验证后为 1
            .add("type", "1")         // 1=账号登录
            .build()
        val ajaxUrl = "${ChnuConfig.CAS_BASE}/authserver/userLogin"
        val request = Request.Builder().url(ajaxUrl).post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", loginPageUrl)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw CampusException("AJAX登录响应解析失败: HTTP ${resp.code}")
            }
            if (!json.optBoolean("success", false)) {
                val msg = json.optString("message", "登录验证失败")
                if (json.optBoolean("is_verification", false)) {
                    throw CampusException("登录需要滑动验证,暂不支持自动滑动验证。服务器消息: $msg")
                }
                throw CampusException("登录验证失败: $msg")
            }
        }
    }

    /** 判断 CAS 表单提交是否登录成功(302 且 Location 含 ticket,或指向非登录页) */
    private fun checkLoginSuccess(resp: Response): Boolean {
        if (resp.code == 302 || resp.code == 301) {
            val location = resp.header("Location") ?: ""
            if (location.contains("ticket=")) return true
            // Location 指向非登录页面也可能是成功
            return location.isNotEmpty() && !location.contains("authserver/login")
        }
        return false
    }

    /** 解析登录失败错误信息 */
    private fun parseLoginError(html: String): String {
        Regex("""id="msg"[^>]*>([^<]+)""").find(html)?.let { return it.groupValues[1].trim() }
        Regex("""id="errorMsg"[^>]*>([^<]+)""").find(html)?.let { return it.groupValues[1].trim() }
        Regex("""alert\(["']([^"']+)["']""").find(html)?.let { return it.groupValues[1] }
        return "未知错误(用户名或密码不正确,或账户被锁定)"
    }

    // ==================== Service Ticket ====================

    /**
     * 通过 CAS 获取指定子系统的 Service Ticket。
     * 利用已登录的 TGC cookie,GET cas_login_url?service=xxx(不跟随重定向),
     * CAS 返回 302,Location 中含 ticket 参数。
     *
     * @param serviceUrl 子系统入口 URL
     * @return (ticket, redirectUrl),ticket 为 null 表示获取失败
     * @throws CampusException 尚未登录或网络异常
     */
    fun getServiceTicket(serviceUrl: String): Pair<String?, String?> {
        if (!loggedIn) throw CampusException("尚未登录,请先调用 login()")
        val reqUrl = with(ChnuConfig.CAS_LOGIN_URL.toHttpUrl()) {
            newBuilder().addQueryParameter("service", serviceUrl).build()
        }
        val request = Request.Builder().url(reqUrl)
            .header("User-Agent", ChnuConfig.UA)
            .build()
        noRedirectClient.newCall(request).execute().use { resp ->
            val location = resp.header("Location") ?: ""
            if (resp.code == 302 || resp.code == 301) {
                val m = Regex("""ticket=([^&]+)""").find(location)
                if (m != null) return m.groupValues[1] to location
                // 302 但无 ticket
                return null to location
            }
            // 200: CAS 返回登录页,TGC 可能已过期
            return null to null
        }
    }

    // ==================== 教务系统客户端 ====================

    /**
     * 教务系统(正方)客户端,通过智慧校园门户 SSO 登录。
     *
     * SSO 流程:
     *  1. CAS 获取 ticket → service=/sso/hsssologin
     *  2. GET /sso/hsssologin?ticket=xxx → 302 → /ticketlogin
     *  3. GET /ticketlogin → 302 → /xtgl/login_slogin.html(设置 JSESSIONID)
     *  4. GET /xtgl/index_initMenu.html → 验证含"教学管理"
     */
    inner class JWXTClient {
        private val baseUrl: String = ChnuConfig.JWXT_BASE_URL
        @Volatile var loggedIn: Boolean = false
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
            val ticketloginUrl = absolutize(loc1, baseUrl)
            val loc2 = noRedirectGet(ticketloginUrl)
            // Step 3: 跟随后续重定向到主页
            if (!loc2.isNullOrEmpty()) {
                val nextUrl = absolutize(loc2, baseUrl)
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
                val json = parseJsonObject(text)
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
                val info = parseStudentInfoHtml(html)
                return StudentInfo(
                    name = info["姓名"] ?: info["xm"] ?: "",
                    studentId = info["学号"] ?: info["xh"] ?: username,
                    department = info["院系"] ?: info["yxmc"] ?: "",
                    major = info["专业"] ?: info["zymc"] ?: "",
                    className = info["班级"] ?: info["bjmc"] ?: ""
                )
            }
        }

        /** 查询成绩。xn/xq 为 null 时取当前学年学期 */
        fun getGrades(xn: String? = null, xq: String? = null): List<Grade> {
            checkSession()
            val (curXn, curXq) = ChnuConfig.currentXnXq()
            val fxn = xn ?: curXn
            val fxq = xq ?: curXq
            // 先 GET 页面初始化模块 session
            getInit("$baseUrl/cjcx/cjcx_cxDgXscj.html", "N305005")
            // 分页查询所有成绩
            val items = fetchAllPages(
                "$baseUrl/cjcx/cjcx_cxDgXscj.html",
                FormBody.Builder()
                    .add("xn", fxn).add("xq", fxq)
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
                    year = it.optString("xn"),
                    semester = it.optString("xqmc")
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
                val json = parseJsonObject(text) ?: return emptyList()
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
                        weeks = it.optString("zcd")
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
                    val json = parseJsonObject(text) ?: return emptyList()
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
                    courseType = it.optString("xsmc", "")
                ))
            }
            return result
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
                    val json = parseJsonObject(text)
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
                val json = parseJsonObject(text)
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
                        val json = parseJsonObject(text)
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
                val json = parseJsonObject(text)
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
                val json = parseJsonObject(text)
                history = json?.optString("lnyhxf", json?.optString("yhxf", "")) ?: ""
            }
            return XkCredits(required, obtained, history)
        }
    }

    // ==================== 一卡通系统客户端 ====================

    /**
     * 校园一卡通客户端(easytong_portal,返回 HTML)。
     *
     * SSO 流程:
     *  1. CAS 获取 ticket → service=/ssoLogin
     *  2. GET ssoLogin?ticket=xxx(跟随 302)→ 200 自动提交表单 HTML
     *  3. POST /easytong_portal(空表单)→ 建立 session
     *  4. GET /balance 验证含"您好"
     */
    inner class EcardClient {
        private val baseUrl: String = ChnuConfig.ECARD_BASE_URL
        @Volatile var loggedIn: Boolean = false
            private set
        /** 卡信息(从余额页提取的 cardAccNum/eWalletId/epId) */
        private val cardInfo = mutableMapOf<String, String>()

        /** SSO 登录一卡通系统 */
        fun login() {
            val ssoService = "$baseUrl/ssoLogin"
            val (ticket, redirectUrl) = getServiceTicket(ssoService)
            if (ticket == null || redirectUrl == null) {
                throw CampusException("无法获取一卡通 CAS ticket")
            }
            // Step 2: 访问 ssoLogin?ticket=xxx(跟随重定向)→ 自动提交表单 HTML
            try {
                val req1 = Request.Builder().url(redirectUrl)
                    .header("User-Agent", ChnuConfig.UA).build()
                client.newCall(req1).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    // Step 3: 检测自动提交表单,POST /easytong_portal 完成 session
                    if (text.contains("myForm") && text.contains("easytong_portal")) {
                        val req2 = Request.Builder().url(baseUrl)
                            .post(FormBody.Builder().build())
                            .header("User-Agent", ChnuConfig.UA).build()
                        client.newCall(req2).execute().use { /* 建立 session */ }
                    }
                }
            } catch (e: IOException) {
                // 即使异常,session cookie 可能已设置,继续验证
            }
            // Step 4: 验证登录状态(访问余额页)
            val req = Request.Builder().url("$baseUrl/balance")
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (text.contains("智慧一卡通－登录")) {
                    throw CampusException("一卡通登录失败: 仍在登录页面")
                }
                if (text.contains("您好")) {
                    extractCardInfo(text)
                    loggedIn = true
                    return
                }
                throw CampusException("一卡通系统登录失败")
            }
        }

        private fun checkSession() {
            if (!loggedIn) throw CampusException("一卡通系统未登录,请先调用 login()")
        }

        /** 从余额页 HTML 提取卡信息(余额/账户名/cardAccNum/eWalletId/epId) */
        private fun extractCardInfo(html: String) {
            listOf("cardAccNum", "eWalletId", "epId").forEach { name ->
                Regex("""(?:id|name)="?$name"?\s+value="([^"]*)"""").find(html)?.let {
                    cardInfo[name] = it.groupValues[1]
                }
            }
            Regex("""<p class="money">([0-9.]+)""").find(html)?.let {
                cardInfo["balance"] = it.groupValues[1]
            }
            Regex("""您好[！!](.+?)<""").find(html)?.let {
                cardInfo["account_name"] = it.groupValues[1].trim()
            }
        }

        /** 查询余额,返回余额、账户名、卡号与银行卡列表 */
        fun getBalance(): EcardBalance {
            checkSession()
            val req = Request.Builder().url("$baseUrl/balance")
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                extractCardInfo(html)
                // 同时获取银行卡信息
                val bankCards = getBankCards()
                return EcardBalance(
                    balance = cardInfo["balance"] ?: "",
                    accountName = cardInfo["account_name"] ?: "",
                    cardNo = cardInfo["cardAccNum"] ?: "",
                    bankCards = bankCards
                )
            }
        }

        /**
         * 查询已签约银行卡列表。
         * 解析 /bankCard 页面的 bankCardTypeName-{id}、bankCardNum-{id}、bankCard-{id} 元素及 radio input 完整卡号。
         */
        fun getBankCards(): List<BankCard> {
            checkSession()
            val req = Request.Builder().url("$baseUrl/bankCard")
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                val cards = mutableListOf<BankCard>()
                // 提取 bank-tip
                val bankTip = Regex("""id="bank-tip"[^>]*>([^<]*)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
                // 提取卡 ID 列表(从 bankCardTypeName-{id} 模式)
                val cardIds = mutableSetOf<String>()
                Regex("""id="bankCardTypeName-(\d+)"""").findAll(html).forEach {
                    cardIds.add(it.groupValues[1])
                }
                Regex("""id="bankCardNum-(\d+)"""").findAll(html).forEach {
                    cardIds.add(it.groupValues[1])
                }
                cardIds.forEach { id ->
                    val cardType = Regex("""id="bankCardTypeName-$id"[^>]*>([^<]*)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
                    val masked = Regex("""id="bankCardNum-$id"[^>]*>([^<]*)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
                    val short = Regex("""id="bankCard-$id"[^>]*>([^<]*)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
                    // 完整卡号:从 radio input 中按 id 关联查找
                    // radio input 的 id 格式通常为 "bankCardRadio-{id}" 或 name 包含卡 id
                    val full = Regex("""<input[^>]*type="radio"[^>]*(?:id|name)="[^"]*$id[^"]*"[^>]*value="([^"]+)\"""").find(html)?.groupValues?.get(1)
                        ?: Regex("""<input[^>]*type="radio"[^>]*value="([^"]+)"[^>]*(?:id|name)="[^"]*$id[^"]*""""").find(html)?.groupValues?.get(1)
                        ?: ""
                    cards.add(BankCard(id, cardType, masked, short, full, bankTip))
                }
                return cards
            }
        }

        /**
         * 查询房间电费(剩余金额与昨日用电)。
         * POST /payFee/getBalance,返回 JSON {balance, eleDailyUsed}。
         */
        fun getRoomElectricity(areaNo: String, buildingNo: String, roomNo: String, itemNum: String = "3"): RoomElectricity {
            checkSession()
            val jsonBody = JSONObject()
                .put("itemNum", itemNum)
                .put("areano", areaNo)
                .put("buildingno", buildingNo)
                .put("roomno", roomNo)
                .toString()
            val form = FormBody.Builder().add("data", jsonBody).build()
            val req = Request.Builder().url("$baseUrl/payFee/getBalance").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = parseJsonObject(text) ?: return RoomElectricity("", "")
                return RoomElectricity(
                    balance = json.optString("balance", ""),
                    dailyUsed = json.optString("eleDailyUsed", json.optString("dailyUsed", ""))
                )
            }
        }

        /**
         * 查询账单记录。
         * @param startDate 开始日期(YYYY-MM-DD),默认 30 天前
         * @param endDate 结束日期(YYYY-MM-DD),默认今天
         * @param recordType 记录类型:1=消费,2=充值,3=补助,4=互转,""=全部
         */
        fun getBillRecords(
            startDate: String? = null,
            endDate: String? = null,
            recordType: String = ""
        ): List<BillRecord> {
            checkSession()
            val now = LocalDate.now()
            val end = endDate ?: now.toString()
            val start = startDate ?: now.minusDays(30).toString()
            // Step 1: GET /bill 获取隐藏字段
            val reqGet = Request.Builder().url("$baseUrl/bill")
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            val hidden = mutableMapOf<String, String>()
            client.newCall(reqGet).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(html).forEach { tag ->
                    val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                        ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                    if (name != null) hidden[name] = value
                }
            }
            // Step 2: POST /bill 查询
            val form = FormBody.Builder()
                .add("timeInterval", "")
                .add("typeFlag", recordType)
                .add("startdealTime", start)
                .add("enddealTime", end)
                .add("payepId", "")
                .add("eWalletId", hidden["eWalletId"] ?: cardInfo["eWalletId"] ?: "")
                .add("cardaccNum", hidden["cardaccNum"] ?: cardInfo["cardAccNum"] ?: "")
                .build()
            val req = Request.Builder().url("$baseUrl/bill").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            val typeName = mapOf(
                "1" to "消费记录", "2" to "充值记录",
                "3" to "补助流水", "4" to "互转流水", "" to "全部记录"
            )[recordType] ?: "记录"
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                return parseBillHtml(html, typeName)
            }
        }

        /**
         * 一卡通充值
         *
         * 流程:校验查询密码(RSA) → POST /topUp 获取充值页 → POST /topUp/confirm 确认
         * @param amount 充值金额(元)
         * @param queryPassword 查询密码(通常为身份证后六位),充值需先校验
         */
        fun topUp(amount: Double, queryPassword: String): PayResult {
            checkSession()
            if (queryPassword.isBlank()) throw CampusException("请输入密码")
            // 先校验查询密码(与生活缴费同一套 RSA + checkpaypwd)
            verifyPayPassword(queryPassword)
            if (cardInfo["cardAccNum"].isNullOrEmpty()) getBalance()
            val cardAccNum = cardInfo["cardAccNum"] ?: "35719"
            val eWalletId = cardInfo["eWalletId"] ?: "1"
            val epId = cardInfo["epId"] ?: "1"
            val amountStr = "%.2f".format(amount)
            // Step 1: 获取充值页面
            val form1 = FormBody.Builder()
                .add("cardAccNum", cardAccNum)
                .add("eWalletId", eWalletId)
                .add("epId", epId)
                .build()
            val req1 = Request.Builder().url("$baseUrl/topUp").post(form1)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            val formFields = mutableMapOf<String, String>()
            var amountField = "inputFee"
            client.newCall(req1).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                // 解析所有 input 字段
                Regex("""<input[^>]*>""").findAll(html).forEach { tag ->
                    val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                        ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                    if (name != null) formFields[name] = value
                    // 识别金额输入框
                    if (name != null && listOf("money", "amount", "fee", "totalfee", "inputfee")
                            .any { it in name.lowercase() }) {
                        amountField = name
                    }
                }
            }
            // Step 2: 提交充值
            val form2 = FormBody.Builder()
                .add(amountField, (amount * 100).toInt().toString())
                .add("cardAccNum", cardAccNum)
                .add("eWalletId", eWalletId)
                .add("epId", epId)
                .also { builder ->
                    formFields.forEach { (k, v) -> if (k != amountField) builder.add(k, v) }
                }
                .build()
            val req2 = Request.Builder().url("$baseUrl/topUp/confirm").post(form2)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/topUp")
                .build()
            client.newCall(req2).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                return parsePayResult(html, amountStr)
            }
        }

        /**
         * 获取生活缴费校区列表
         * @param itemNum 缴费项目编号:"2"=常工电控缴费,"3"=滨湖1-5宿舍照明电费
         */
        fun getPayAreas(itemNum: String = "3"): List<PayArea> {
            checkSession()
            val form = FormBody.Builder().add("itemNum", itemNum).build()
            val req = Request.Builder().url("$baseUrl/payFee/showItemListPayPage").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                val areas = mutableListOf<PayArea>()
                // 提取 select name="areano" 的 option 列表
                val selectRegex = Regex("""<select[^>]*(?:name|id)="areano"[^>]*>([\s\S]*?)</select>""")
                val selectContent = selectRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
                val optionRegex = Regex("""<option[^>]*value="([^"]*)"[^>]*>([^<]*)</option>""")
                optionRegex.findAll(selectContent).forEach { m ->
                    val id = m.groupValues[1].trim()
                    val name = m.groupValues[2].trim()
                    if (id.isNotEmpty()) areas.add(PayArea(id, name))
                }
                return areas
            }
        }

        /** 获取楼栋列表(AJAX) */
        fun getBuildings(areaNo: String, itemNum: String = "3"): List<PayBuilding> {
            checkSession()
            val jsonBody = JSONObject().put("itemNum", itemNum).put("areano", areaNo).toString()
            val form = FormBody.Builder().add("data", jsonBody).build()
            val req = Request.Builder().url("$baseUrl/payFee/getbuilding").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                return parseDormList(text, "buildingList")
            }
        }

        /** 获取房间列表(AJAX) */
        fun getRooms(areaNo: String, buildingNo: String, itemNum: String = "3"): List<PayBuilding> {
            checkSession()
            val jsonBody = JSONObject().put("itemNum", itemNum)
                .put("areano", areaNo)
                .put("buildingno", buildingNo)
                .toString()
            val form = FormBody.Builder().add("data", jsonBody).build()
            val req = Request.Builder().url("$baseUrl/payFee/getroom").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                return parseDormList(text, "roomList")
            }
        }

        /**
         * 生活缴费(电费等)
         *
         * 流程:获取RSA公钥 → 验证查询密码 → 提交缴费
         * @param queryPassword 查询密码(通常为身份证后六位)
         */
        fun payFee(
            areaNo: String,
            buildingNo: String,
            roomNo: String,
            amount: Double,
            queryPassword: String,
            itemNum: String = "3"
        ): PayResult {
            checkSession()
            val amountStr = "%.2f".format(amount)
            // Step 1: 校验查询密码(RSA + checkpaypwd)
            verifyPayPassword(queryPassword)
            // Step 2: 提交缴费
            val feeInCents = (amount * 100).toInt().toString()
            val form = FormBody.Builder()
                .add("itemNum", itemNum)
                .add("areano", areaNo)
                .add("buildingno", buildingNo)
                .add("roomno", roomNo)
                .add("inputFee", feeInCents)
                .add("totalFee", feeInCents)
                .build()
            // 尝试多个提交端点
            val submitPaths = listOf("payFee/payItemlist", "payFee/payFeelist")
            for (path in submitPaths) {
                val req = Request.Builder().url("$baseUrl/$path").post(form)
                    .header("User-Agent", ChnuConfig.UA)
                    .header("Referer", "$baseUrl/")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 200) {
                        val html = resp.body?.string() ?: ""
                        return parsePayResult(html, amountStr)
                    }
                }
            }
            return PayResult(false, "缴费提交失败,请稍后重试", amountStr)
        }

        /**
         * 校验查询密码:GET RSA 公钥 → RSA 加密密码 → POST /publiccombo/checkpaypwd。
         * 充值与生活缴费共用。
         * @throws CampusException 密码错误或获取公钥失败
         */
        private fun verifyPayPassword(queryPassword: String) {
            val reqKey = Request.Builder().url("$baseUrl/publiccombo/keyPair")
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            var modulus = ""
            var exponent = ""
            client.newCall(reqKey).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = parseJsonObject(text) ?: throw CampusException("获取RSA公钥失败")
                val keyMap = json.optJSONObject("publicKeyMap")
                modulus = keyMap?.optString("modulus") ?: ""
                exponent = keyMap?.optString("exponent") ?: ""
            }
            if (modulus.isEmpty()) throw CampusException("RSA公钥为空")
            val encryptedPwd = rsaEncrypt(queryPassword, modulus, exponent)
            val formPwd = FormBody.Builder().add("paypassword", encryptedPwd).build()
            val reqPwd = Request.Builder().url("$baseUrl/publiccombo/checkpaypwd").post(formPwd)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(reqPwd).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = parseJsonObject(text)
                if (json?.optString("ajaxState") == "1") {
                    throw CampusException("密码错误")
                }
            }
        }

        /** 解析楼栋/房间列表 JSON(返回 dormList/buildingList/roomList) */
        private fun parseDormList(text: String, field: String): List<PayBuilding> {
            val json = parseJsonObject(text) ?: return emptyList()
            val arr = json.optJSONArray(field) ?: json.optJSONArray("dormList") ?: return emptyList()
            val result = mutableListOf<PayBuilding>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                result.add(PayBuilding(
                    id = it.optString("no", it.optString("buildingNo", "")),
                    name = it.optString("name", it.optString("buildingName", ""))
                ))
            }
            return result
        }

        /** 解析充值/缴费结果 HTML */
        private fun parsePayResult(html: String, amount: String): PayResult {
            // 查找成功/失败提示
            val successKeywords = listOf("成功", "充值成功", "缴费成功", "提交成功")
            val failKeywords = listOf("失败", "余额不足", "错误", "异常")
            val plainText = stripTags(html)
            val isSuccess = successKeywords.any { it in plainText } && !failKeywords.any { it in plainText }
            // 提取提示语
            val msg = Regex("""alert\(["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""(成功[^<。]*)""").find(plainText)?.groupValues?.get(1)
                ?: Regex("""(失败[^<。]*)""").find(plainText)?.groupValues?.get(1)
                ?: if (isSuccess) "操作成功" else "操作失败"
            return PayResult(isSuccess, msg, amount)
        }

        /** RSA 加密(一卡通查询密码) */
        private fun rsaEncrypt(plain: String, modulusB64: String, exponentB64: String): String {
            return try {
                val modulus = java.math.BigInteger(1, Base64.decode(modulusB64, Base64.DEFAULT))
                val exponent = java.math.BigInteger(1, Base64.decode(exponentB64, Base64.DEFAULT))
                val keySpec = java.security.spec.RSAPublicKeySpec(modulus, exponent)
                val keyFactory = java.security.KeyFactory.getInstance("RSA")
                val publicKey = keyFactory.generatePublic(keySpec)
                val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey)
                val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            } catch (e: Exception) {
                throw CampusException("RSA加密失败: ${e.message}")
            }
        }

        /** 解析账单 HTML 表格为记录列表 */
        private fun parseBillHtml(html: String, typeName: String): List<BillRecord> {
            val records = mutableListOf<BillRecord>()
            // 提取第一个含多行的 <table>...</table>
            val tableMatch = Regex("""<table[^>]*>([\s\S]*?)</table>""").find(html)
            val tableContent = tableMatch?.groupValues?.get(1) ?: return emptyList()
            val rows = Regex("""<tr[^>]*>([\s\S]*?)</tr>""").findAll(tableContent).toList()
            if (rows.size < 2) return emptyList()
            // 跳过表头行,解析数据行
            for (i in 1 until rows.size) {
                val cells = Regex("""<td[^>]*>([\s\S]*?)</td>""").findAll(rows[i].value)
                    .map { c -> stripTags(c.groupValues[1]).trim() }.toList()
                if (cells.size < 2) continue
                if (cells.all { it.isEmpty() }) continue
                records.add(BillRecord(
                    time = cells.getOrNull(0) ?: "",
                    amount = cells.getOrNull(1) ?: "",
                    description = cells.drop(2).filter { it.isNotEmpty() }.joinToString(" "),
                    type = typeName
                ))
            }
            return records
        }
    }

    // ==================== 第二课堂客户端 ====================

    /**
     * 第二课堂客户端(华博思道,前后端分离 + JWT)。
     *
     * SSO 流程:
     *  1. CAS 获取 ticket → service=/hbsdWeb-prod
     *  2. GET /cas/client/checkSsoLogin?ticket=xxx&service=xxx → JWT token
     *  3. 后续请求用 X-Access-Token header
     */
    inner class SCClient {
        private val baseUrl: String = ChnuConfig.SC_BASE_URL
        private val apiBase: String = ChnuConfig.SC_API_BASE
        @Volatile var loggedIn: Boolean = false
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
                val json = parseJsonObject(text)
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
                val json = parseJsonObject(text) ?: return emptyList()
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
                val json = parseJsonObject(text) ?: return ScoreSummary("", emptyList())
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

    // ==================== 通用工具 ====================

    /** 解析 JSON 响应,兼容 `(json)` 包裹格式 */
    private fun parseJsonObject(text: String): JSONObject? {
        var t = text.trim()
        if (t.startsWith("(") && t.endsWith(")")) t = t.substring(1, t.length - 1)
        return try { JSONObject(t) } catch (e: Exception) { null }
    }

    /** 从学生信息 HTML 解析标签-值对(col-xs-2/col-xs-3 与表格) */
    private fun parseStudentInfoHtml(html: String): Map<String, String> {
        val info = mutableMapOf<String, String>()
        // col-xs-2 (label) + 紧邻 col-xs-3 (value)
        val labelRegex = Regex("""<div[^>]*class="[^"]*col-xs-2[^"]*"[^>]*>([\s\S]*?)</div>""")
        val allDivs = Regex("""<div[^>]*class="[^"]*col-xs-3[^"]*"[^>]*>([\s\S]*?)</div>""").findAll(html).toList()
        labelRegex.findAll(html).forEachIndexed { idx, m ->
            val label = stripTags(m.groupValues[1]).trim().trimEnd('：', ':')
            val value = allDivs.getOrNull(idx)?.let { stripTags(it.groupValues[1]).trim() } ?: ""
            if (label.isNotEmpty() && value.isNotEmpty()) info[label] = value
        }
        // 兼容表格结构
        if (info.isEmpty()) {
            val tableMatch = Regex("""<table[^>]*class="[^"]*table[^"]*"[^>]*>([\s\S]*?)</table>""").find(html)
            tableMatch?.let {
                val rows = Regex("""<tr[^>]*>([\s\S]*?)</tr>""").findAll(it.groupValues[1]).toList()
                rows.forEach { row ->
                    val cells = Regex("""<t[hd][^>]*>([\s\S]*?)</t[hd]>""").findAll(row.value)
                        .map { c -> stripTags(c.groupValues[1]).trim() }.toList()
                    var i = 0
                    while (i + 1 < cells.size) {
                        val k = cells[i].trimEnd('：', ':')
                        val v = cells[i + 1]
                        if (k.isNotEmpty() && v.isNotEmpty()) info[k] = v
                        i += 2
                    }
                }
            }
        }
        return info
    }

    /** 去除 HTML 标签,保留文本 */
    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&")

    /** 将可能的相对 URL 绝对化(以 http/https 开头则原样,否则拼接 baseUrl) */
    private fun absolutize(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("/")) {
            val base = baseUrl.toHttpUrl()
            return "${base.scheme}://${base.host}${if (base.port != 80 && base.port != 443 && base.port > 0) ":${base.port}" else ""}$url"
        }
        return "$baseUrl/$url"
    }
}
