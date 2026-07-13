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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
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

/** 充值页面解析结果(对齐 Python ecard.py get_topup_page) */
private data class TopupPageInfo(
    val bankId: String,
    val logoFileName: String,
    val walletName: String,
    /** 充值可用的签约银行卡列表(含完整卡号,供 UI 选卡) */
    val bankCards: List<BankCard>,
    /** /topUp 页面上表单中的隐藏字段,提交 /topUp/confirm 时必须携带(否则服务端返回错误页) */
    val formFields: Map<String, String> = emptyMap()
)

/** 缴费表单字段(对齐 Python ecard.py get_pay_fee_form) */
private data class PayFeeForm(
    val typeNum: String,
    val token: String,
    val limitMoney: String,
    val limitMode: String,
    val itemName: String
)

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
    /** 淮师心声客户端(失物招领 + 满意度调查) */
    val hsxs = HsxsClient()

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
            // Step 1: 先初始化缴费页面(否则 getBalance 会返回空)
            val initForm = FormBody.Builder().add("itemNum", itemNum).build()
            val initReq = Request.Builder().url("$baseUrl/payFee/showItemListPayPage").post(initForm)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            try {
                client.newCall(initReq).execute().use { /* 初始化 session */ }
            } catch (_: Exception) { /* 即使异常也继续 */ }
            // Step 2: 查询房间余额,返回 {"feeDate":{"balance":797.0,"eleDailyUsed":0.0},"ajaxState":"3"}
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
                val feeDate = json.optJSONObject("feeDate") ?: json
                return RoomElectricity(
                    balance = feeDate.optString("balance", json.optString("balance", "")),
                    dailyUsed = feeDate.optString("eleDailyUsed", feeDate.optString("dailyUsed", ""))
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
         * 一卡通充值(银校转账)
         *
         * 流程对齐 Python 参考实现(chnu/ecard.py top_up):
         *  1. 确保卡信息(余额页解析 cardAccNum/eWalletId/epId)
         *  2. 校验查询密码(RSA),并取回加密后的密码
         *  3. POST /topUp/confirm 获取确认页(form#pay-form),
         *     该接口服务器会以 chunked 方式提前断开连接,需用 [readBodyTolerant] 容错读取
         *  4. 解析 form#pay-form 的 action 与隐藏字段
         *  5. RSA 加密密码 → POST 到 pay-form action 提交充值
         *
         * @param amount 充值金额(元),如 50.00
         * @param queryPassword 查询密码(通常为身份证后六位)
         * @param bankCardNum 签约银行卡完整卡号,为空时自动取第一张签约卡
         * @throws CampusException 未登录/密码错误/无签约卡/服务器内部错误
         */
        /**
         * 获取充值页面信息,动态提取签约银行卡 ID / 银行 logo / 钱包名称。
         * 对齐 Python ecard.py get_topup_page。
         * 之前硬编码 bankId="25"/logoFileName="",对非默认银行卡用户会导致充值失败。
         */
        /**
         * 获取充值页面信息,解析签约银行卡列表(含完整卡号)及钱包名称。
         * 对齐 Python ecard.py get_topup_page:签约卡来自充值页的
         * <label id="bankCard-{id}">…<input type="radio" name="inlineRadioOptions" value="完整卡号">…</label>。
         * 注意:完整卡号必须从充值页解析;/bankCard 页通常只给脱敏号,直接用于充值会失败。
         */
        private fun getTopupPage(): TopupPageInfo {
            if (cardInfo["cardAccNum"].isNullOrEmpty()) getBalance()
            val form = FormBody.Builder()
                .add("cardAccNum", cardInfo["cardAccNum"] ?: "")
                .add("eWalletId", cardInfo["eWalletId"] ?: "")
                .add("epId", cardInfo["epId"] ?: "")
                .build()
            val req = Request.Builder().url("$baseUrl/topUp").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                // 钱包名称(recharge-box)
                val walletName = Regex("""<div[^>]*class="recharge-box"[^>]*>([\s\S]*?)</div>""")
                    .find(html)?.groupValues?.get(1)?.let { stripTags(it).trim() } ?: ""
                // 签约银行卡列表:遍历 <label id="bankCard-{id}">…</label>,
                // 内部含 radio(input value=完整卡号)与 <i/img logofilename=…>
                val cards = mutableListOf<BankCard>()
                var firstLogo = ""
                val labelRegex = Regex("""id="bankCard-(\d+)"[^>]*>([\s\S]*?)</label>""")
                val fullRegex = Regex("""value="(\d{11,})"""")
                val logoRegex = Regex("""logofilename="([^"]+)"""")
                labelRegex.findAll(html).forEach { m ->
                    val id = m.groupValues[1]
                    val inner = m.groupValues[2]
                    val full = fullRegex.find(inner)?.groupValues?.get(1) ?: return@forEach
                    val short = stripTags(inner).trim()
                    val logo = logoRegex.find(inner)?.groupValues?.get(1) ?: ""
                    if (firstLogo.isEmpty()) firstLogo = logo
                    cards.add(BankCard(id, "", "", short, full, "", logo))
                }
                val bankId = cards.firstOrNull()?.cardId ?: "25"
                // 提取所有隐藏表单字段(对齐 Python _extract_card_info),后续提交 /topUp/confirm 时必须携带
                val formFields = mutableMapOf<String, String>()
                Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(html).forEach { tag ->
                    val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                        ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                    if (name != null) formFields[name] = value
                }
                return TopupPageInfo(bankId, firstLogo, walletName, cards, formFields)
            }
        }

        /**
         * 获取充值可用的签约银行卡列表(含完整卡号),供 UI 选卡步骤使用。
         * 对齐 Python ecard.py top_up 的"先展示签约卡让用户选择"流程。
         */
        fun getTopupBankCards(): List<BankCard> {
            checkSession()
            if (cardInfo["cardAccNum"].isNullOrEmpty()) getBalance()
            return getTopupPage().bankCards
        }

        fun topUp(amount: Double, queryPassword: String, bankCardNum: String = ""): PayResult {
            checkSession()
            if (queryPassword.isBlank()) throw CampusException("请输入密码")
            val amountStr = "%.2f".format(amount)
            // 确保卡信息(余额页解析 cardAccNum/eWalletId/epId)
            if (cardInfo["cardAccNum"].isNullOrEmpty()) getBalance()
            val cardAccNum = cardInfo["cardAccNum"] ?: "35719"
            val eWalletId = cardInfo["eWalletId"] ?: "1"
            val epId = cardInfo["epId"] ?: "1"

            // Step 1: 校验查询密码(RSA),并取回加密后的密码(后续提交复用,避免重复取公钥)
            val encryptedPwd = rsaEncryptPassword(queryPassword)

            // 获取充值页面信息(含签约银行卡列表)
            val topupPage = getTopupPage()
            // 确定签约银行卡:优先使用 UI 选中的卡号,否则取充值页解析到的第一张可用卡
            val bankNum = if (bankCardNum.isNotEmpty()) bankCardNum
            else topupPage.bankCards.firstOrNull { it.cardNumberFull.length > 10 }?.cardNumberFull
                ?: topupPage.bankCards.firstOrNull { it.cardNumberFull.isNotEmpty() }?.cardNumberFull
                ?: ""
            if (bankNum.isEmpty()) {
                throw CampusException("没有可用的签约银行卡,无法进行银校转账")
            }
            // 选中卡对应的 bankId / logo(不带正确 bankId 会导致充值失败)
            val selCard = topupPage.bankCards.firstOrNull { it.cardNumberFull == bankNum }
            val usedBankId = selCard?.cardId ?: topupPage.bankId
            val usedLogo = selCard?.logoFileName ?: topupPage.logoFileName
            // Step 3: 构建充值步骤数据并 POST /topUp/confirm
            //    关键:必须携带 /topUp 页面的隐藏表单字段,否则服务端返回内部错误页("服务不可用")
            val step1 = mutableMapOf(
                "epId" to epId,
                "cardAccNum" to cardAccNum,
                "eWalletId" to eWalletId,
                "eWalletName" to topupPage.walletName,
                "bankCardNum" to bankNum,
                "bankCardNumWithAsterisk" to "**${bankNum.takeLast(4)}",
                "payMode" to "1",
                "bankId" to usedBankId,
                "logoFileName" to usedLogo,
                "payChannelId" to "",
                "topUpDisableTime" to ""
            )
            // 合并 /topUp 页面表单隐藏字段(服务端要求)
            topupPage.formFields.forEach { (k, v) -> if (k !in step1) step1[k] = v }
            // 获取限额信息(dayLimit/oneLimit/moneyMax/continueTopUpMoney)
            try {
                val limitReq = Request.Builder().url("$baseUrl/public/queryContinueTopUpMoney").post(
                    FormBody.Builder()
                        .add("epId", epId)
                        .add("cardAccNum", cardAccNum)
                        .add("eWalletId", eWalletId)
                        .build()
                ).header("User-Agent", ChnuConfig.UA)
                    .header("Referer", "$baseUrl/topUp")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                client.newCall(limitReq).execute().use { resp ->
                    val json = parseJsonObject(readBodyTolerant(resp))
                    val map = json?.optJSONObject("retMap")
                    map?.optString("dayLimit")?.takeIf { it.isNotEmpty() }?.let { step1["dayLimit"] = it }
                    map?.optString("oneLimit")?.takeIf { it.isNotEmpty() }?.let { step1["oneLimit"] = it }
                    map?.optString("continueTopUpMoney")?.takeIf { it.isNotEmpty() }
                        ?.let { step1["continueTopUpMoney"] = it }
                    map?.optString("moneyMax")?.takeIf { it.isNotEmpty() }?.let { step1["moneyMax"] = it }
                }
            } catch (_: Exception) { /* 限额信息可选 */ }
            // 获取银行禁止充值时间
            try {
                val disableReq = Request.Builder().url("$baseUrl/public/queryTopUpDisableTime").post(
                    FormBody.Builder().add("epId", epId).add("bankId", usedBankId).build()
                ).header("User-Agent", ChnuConfig.UA)
                    .header("Referer", "$baseUrl/topUp")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                client.newCall(disableReq).execute().use { resp ->
                    val json = parseJsonObject(readBodyTolerant(resp))
                    val begin = json?.optString("disableBegin", "") ?: ""
                    val end = json?.optString("disableEnd", "") ?: ""
                    if (begin.isNotEmpty() && end.isNotEmpty()) {
                        step1["topUpDisableTime"] = "$begin-$end"
                    }
                }
            } catch (_: Exception) { /* 禁用时间可选 */ }

            // Step 3: POST /topUp/confirm 获取确认页(该接口会提前断开 chunked 连接)
            val confirmReq = Request.Builder().url("$baseUrl/topUp/confirm").post(
                FormBody.Builder().apply { step1.forEach { (k, v) -> add(k, v) } }.build()
            ).header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/topUp")
                .build()
            val confirmHtml = client.newCall(confirmReq).execute().use { readBodyTolerant(it) }
            if (confirmHtml.isEmpty()) {
                return PayResult(false, "获取充值确认页面失败(服务器无响应)", amountStr)
            }
            // 只检测明确的服务器 500 错误(不检测业务文案,业务错误由最终提交页解析)
            if (confirmHtml.contains("java.lang") || confirmHtml.contains("NullPointerException")) {
                val errMsg = extractPageErrorMessage(confirmHtml)
                    ?: "一卡通充值服务暂时不可用(服务器内部错误),请稍后重试"
                return PayResult(false, errMsg, amountStr)
            }
            // 解析 pay-form 的 action 与隐藏字段(确认页是正常步骤,不是错误)
            val (formAction, hiddenFields) = parsePayForm(confirmHtml)
            // 确定提交地址:优先使用 pay-form 的 action,解析失败则回退到 /topUp/quickPay
            val submitUrl = if (formAction.isNotEmpty()) resolveSubmitUrl(formAction)
                else resolveSubmitUrl("$baseUrl/topUp/quickPay")

            // 提交充值数据
            val postData = mutableMapOf<String, String>()
            if (hiddenFields.isNotEmpty()) postData.putAll(hiddenFields)
            postData["cardAccNum"] = cardAccNum
            postData["eWalletId"] = eWalletId
            postData["epId"] = epId
            postData["bankId"] = usedBankId
            postData["bankCardNum"] = bankNum
            postData["payMode"] = "1"
            postData["money"] = amountStr
            postData["payPwd"] = encryptedPwd

            val submitReq = Request.Builder().url(submitUrl).post(
                FormBody.Builder().apply { postData.forEach { (k, v) -> add(k, v) } }.build()
            ).header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/topUp/confirm")
                .build()
            val finalHtml = client.newCall(submitReq).execute().use { readBodyTolerant(it) }
            return parsePayResult(finalHtml, amountStr)
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
         * 获取缴费表单字段(类型编号/防重放 token/限额/项目名称等)。
         * 对齐 Python ecard.py get_pay_fee_page + get_pay_fee_form。
         * 这些字段缺失会导致服务端拒绝缴费提交。
         */
        private fun getPayFeeForm(itemNum: String): PayFeeForm {
            val form = FormBody.Builder().add("itemNum", itemNum).build()
            val req = Request.Builder().url("$baseUrl/payFee/showItemListPayPage").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                // 提取隐藏字段
                val fields = mutableMapOf<String, String>()
                Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(html).forEach { tag ->
                    val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                        ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                    if (name != null) fields[name] = value
                }
                // 项目名称(缴费项名称,如"滨湖1-5宿舍照明电费")
                val knownItemNames = mapOf("2" to "常工电控缴费", "3" to "滨湖1-5宿舍照明电费")
                var itemName = knownItemNames[itemNum] ?: ""
                val nameMatch = Regex("""(常工电控缴费|滨湖1-5宿舍照明电费|[^<]{1,20}(?:电费|电控|照明)[^<]{0,20})""").find(html)
                if (nameMatch != null) itemName = nameMatch.groupValues[1].trim()
                return PayFeeForm(
                    typeNum = fields["typeNum"] ?: "",
                    token = fields["token"] ?: "",
                    limitMoney = fields["limitMoney"] ?: "",
                    limitMode = fields["limitMode"] ?: "",
                    itemName = itemName
                )
            }
        }

        /**
         * 生活缴费(宿舍电费等)。
         *
         * 流程完全对齐网页端 itemPay() JS:
         *  1. RSA公钥 → 加密密码
         *  2. POST /publiccombo/checkpaypwd 验证密码(网页端在此步立即返回正确/错误)
         *  3. POST /payFee/payItemlist 提交表单(不含密码!验证通过后服务端信任会话)
         *
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

            // Step 1: RSA加密密码
            val encryptedPwd = rsaEncryptPassword(queryPassword)

            // Step 2: 获取缴费表单字段(字段名对齐网页端 id 属性)
            val feeForm = getPayFeeForm(itemNum)
            val limitMoney = feeForm.limitMoney.toDoubleOrNull() ?: 0.0
            if (limitMoney > 0 && amount > limitMoney) {
                return PayResult(false, "缴费金额超过限额(${feeForm.limitMoney}元)", amountStr)
            }

            // Step 3: POST checkpaypwd 验证密码(网页端 AJAX 验证,对/错立即返回)
            val pwdForm = FormBody.Builder().add("paypassword", encryptedPwd).build()
            val pwdReq = Request.Builder().url("$baseUrl/publiccombo/checkpaypwd").post(pwdForm)
                .header("User-Agent", ChnuConfig.UA)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/payFee/showItemListPayPage")
                .build()
            client.newCall(pwdReq).execute().use { resp ->
                val pwdText = readBodyTolerant(resp)
                val pwdJson = parseJsonObject(pwdText)
                val state = pwdJson?.optString("ajaxState", "")
                // AJAXSTATE: FAILED=2(密码错误), SUCCESS=3, PASSED=4
                if (state == "2") {
                    val msg = pwdJson?.optString("msg", "") ?: ""
                    return PayResult(false, msg.ifEmpty { "密码错误" }, amountStr)
                }
                if (state != "3" && state != "4") {
                    val msg = pwdJson?.optString("msg", "") ?: ""
                    return PayResult(false, msg.ifEmpty { "验证失败,请检查密码" }, amountStr)
                }
            }

            // Step 4: 提交缴费(字段名完全对齐网页端 elementObj)
            //   payMenoy=金额, cardaccNum=卡号(小写), ewalletMenoy=余额
            //   NO inputPassword(已通过 checkpaypwd 验证)
            //   NO limitMoney/limitMode/epId(网页端不传)
            val balance = cardInfo["balance"] ?: "0"
            val cardaccNum = cardInfo["cardAccNum"] ?: ""
            val form = FormBody.Builder()
                .add("itemNum", itemNum)
                .add("eWalletId", cardInfo["eWalletId"] ?: "1")
                .add("typeNum", feeForm.typeNum)
                .add("itemName", feeForm.itemName)
                .add("payMenoy", amountStr)               // 网页端字段名
                .add("ewalletMenoy", balance)              // 钱包余额
                .add("eWalletName", feeForm.itemName.ifEmpty { "卡1号钱包" })
                .add("areano", areaNo)
                .add("buildingno", buildingNo)
                .add("roomno", roomNo)
                .add("cardaccNum", cardaccNum)             // 小写!网页端字段名
                .add("token", feeForm.token)
                .build()
            val req = Request.Builder().url("$baseUrl/payFee/payItemlist").post(form)
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/payFee/showItemListPayPage")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = readBodyTolerant(resp)
                return parsePayResult(html, amountStr)
            }
        }

        /** RSA加密密码(不调用checkpaypwd,仅加密。内层类命名避免与外层AES encryptPassword冲突) */
        private fun rsaEncryptPassword(queryPassword: String): String {
            val reqKey = Request.Builder().url("$baseUrl/publiccombo/keyPair").post(FormBody.Builder().build())
                .header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            var modulus = ""
            var exponent = ""
            client.newCall(reqKey).execute().use { resp ->
                val text = readBodyTolerant(resp)
                val json = parseJsonObject(text) ?: throw CampusException("获取RSA公钥失败: $text")
                val keyMap = json.optJSONObject("publicKeyMap") ?: json
                modulus = keyMap.optString("modulus", "")
                exponent = keyMap.optString("exponent", "")
            }
            if (modulus.isEmpty()) throw CampusException("RSA公钥为空")
            return rsaEncrypt(queryPassword, modulus, exponent)
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

        /** 解析充值/缴费结果(兼容 JSON ajaxState 与 HTML 文本,对齐 Python _parse_result) */
        private fun parsePayResult(html: String, amount: String): PayResult {
            val trimmed = html.trim()
            // 优先按 JSON 解析(提交端点可能直接返回 {"ajaxState":"2","msg":"..."})
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                val json = parseJsonObject(trimmed)
                if (json != null) {
                    val state = json.optString("ajaxState", "")
                    val msg = json.optString("msg", "")
                return when (state) {
                    // AJAXSTATE: NOLOGIN:0, NOAUTH:1, FAILED:2, SUCCESS:3, PASSED:4
                    "3", "4" -> PayResult(true, msg.ifEmpty { "操作成功" }, amount)
                    "0", "1", "2" -> PayResult(false, msg.ifEmpty { "操作失败" }, amount)
                    else -> if (msg.isNotEmpty()) PayResult(false, msg, amount)
                    else parsePayResultHtml(trimmed, amount)
                }
                }
            }
            return parsePayResultHtml(trimmed, amount)
        }

        /** 从 HTML 文本解析充值/缴费结果 */
        private fun parsePayResultHtml(html: String, amount: String): PayResult {
            val plainText = stripTags(html)
            val successKeywords = listOf("成功", "充值成功", "缴费成功", "提交成功", "success")
            val failKeywords = listOf("失败", "余额不足", "错误", "异常", "密码错误")
            val isSuccess = successKeywords.any { it in plainText } && !failKeywords.any { it in plainText }
            val msg = Regex("""alert\(["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""(成功[^<。]*)""").find(plainText)?.groupValues?.get(1)
                ?: Regex("""(失败[^<。]*)""").find(plainText)?.groupValues?.get(1)
                ?: if (isSuccess) "操作成功" else "操作失败"
            return PayResult(isSuccess, msg, amount)
        }

        /**
         * 容错读取响应体。一卡通部分接口(如 /topUp/confirm)会以 chunked 方式提前
         * 断开连接,OkHttp 在读取完整分块流时会抛出 IOException(message 常为 null)。
         * 这里改用流式读取,捕获截断异常后保留已读取的部分内容
         * (对齐 Python ecard.py 的 _request_chunked)。
         */
        private fun readBodyTolerant(resp: Response): String {
            val body = resp.body ?: return ""
            val source = body.source()
            val out = StringBuilder()
            val buf = okio.Buffer()
            try {
                while (true) {
                    val n = source.read(buf, 8192L)
                    if (n == -1L) break
                    if (n > 0L) out.append(buf.readString(Charsets.UTF_8))
                }
            } catch (e: IOException) {
                // 截断/分块连接提前关闭:保留已读取的部分数据,继续解析
            }
            return out.toString()
        }

        /** 从确认页 HTML 解析 pay-form 的 action 与隐藏字段 */
        private fun parsePayForm(html: String): Pair<String, Map<String, String>> {
            var action = ""
            val hidden = mutableMapOf<String, String>()
            var found = false
            Regex("""<form\b([\s\S]*?)</form>""").findAll(html).forEach { m ->
                val formTag = m.groupValues[1]
                val formFull = m.value
                val isPayForm = formTag.contains("""id="pay-form"""") || formTag.contains("""id='pay-form'""")
                if (isPayForm || !found) {
                    val act = Regex("""action="([^"]*)"""").find(formTag)?.groupValues?.get(1) ?: ""
                    val fields = mutableMapOf<String, String>()
                    Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(formFull).forEach { tag ->
                        val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                            ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                        val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                        if (name != null) fields[name] = value
                    }
                    if (isPayForm) { action = act; hidden.putAll(fields); found = true }
                    else if (!found) { action = act; hidden.putAll(fields) }
                }
            }
            return action to hidden
        }

        /** 从错误 HTML 页面中提取可读错误消息 */
        private fun extractPageErrorMessage(html: String): String? {
            // 1. JS alert 弹窗
            Regex("""alert\(["']([^"']{2,})["']""").find(html)?.groupValues?.get(1)?.let { return it }
            // 2. 页面醒目文字(如"充值失败，原因:银行服务时间为00:00~20:00")
            Regex("""(?:充值|缴费)?失败[，。,.\s]*原因[：:]\s*([^<。]{5,})""").find(html)
                ?.groupValues?.get(1)?.let { return "充值失败: $it" }
            // 3. <div class="error"> 等错误提示容器
            Regex("""class="[^"]*error[^"]*"[^>]*>([^<]{3,})<""").find(html)
                ?.groupValues?.get(1)?.let { return it.trim() }
            // 4. 包含"失败"或"错误"的醒目文本
            Regex("""(充值失败|缴费失败|操作失败|密码错误|余额不足|服务时间[^<。]{0,30})""").find(html)
                ?.groupValues?.get(1)?.let { return it }
            return null
        }

        /** 将确认页 pay-form 的 action 解析为绝对 URL */
        private fun resolveSubmitUrl(action: String): String {
            if (action.startsWith("http://") || action.startsWith("https://")) return action
            if (action.isEmpty()) return "$baseUrl/topUp/confirm"
            if (action.startsWith("/")) {
                val base = baseUrl.toHttpUrl()
                val port = if (base.port != 80 && base.port != 443 && base.port > 0) ":${base.port}" else ""
                return "${base.scheme}://${base.host}$port$action"
            }
            return "$baseUrl/$action"
        }

        /**
         * RSA 加密(一卡通查询密码)。
         *
         * 对齐网页端 security.js 的 RSAUtils.encryptedString:
         *   ● modulus/exponent 十六进制 → BigInteger
         *   ● 明文分块,每块 2*(modulusDigits-1) 字节,零填充
         *   ● 逐块 modular exponentiation(无 PKCS1 填充)
         *   ● 输出 HEX 字符串,块间空格分隔
         *
         * 注意:这里不使用标准 PKCS1 填充,必须用 Raw RSA 对齐 JS 代码。
         */
        private fun rsaEncrypt(plain: String, modulusHex: String, exponentHex: String): String {
            try {
                val modulus = java.math.BigInteger(modulusHex, 16)
                val exponent = java.math.BigInteger(exponentHex, 16)
                // chunkSize = 2 * (modulus.bitLength() / 16 - 1) = (modulus.bitLength() - 16) / 8
                // JS: chunkSize = 2 * biHighIndex(this.m), where biHighIndex = digits.length - 1
                // digits are base-65536, so chunkSize = 2 * (bytes/2 - 1) = modulusBytes - 2
                val modulusBytes = (modulus.bitLength() + 7) / 8
                val chunkSize = modulusBytes - 2  // same as JS: 2 * biHighIndex
                val bytes = plain.toByteArray(Charsets.UTF_8)
                val sb = StringBuilder()
                var i = 0
                while (i < bytes.size) {
                    // 取 chunkSize 字节,不足补零
                    val block = ByteArray(chunkSize)
                    var j = 0
                    while (j < chunkSize && i + j < bytes.size) {
                        block[j] = bytes[i + j]
                        j++
                    }
                    // block → positive BigInteger (big-endian, unsigned)
                    val m = java.math.BigInteger(1, block)
                    val enc = m.modPow(exponent, modulus)
                    // HEX, 对齐 JS RSAUtils.biToHex
                    val hex = enc.toString(16)
                    // 填充前导零至 modulus 位数的 hex 长度
                    val expectedHexLen = modulus.bitLength() / 4
                    if (hex.length < expectedHexLen) {
                        sb.append("0".repeat(expectedHexLen - hex.length))
                    }
                    sb.append(hex)
                    if (i + chunkSize < bytes.size) sb.append(" ")
                    i += chunkSize
                }
                return sb.toString()
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

    // ════════════════════════════════════════════════
    //  淮师心声(Hsxs)客户端 — 失物招领 & 满意度调查
    //  CAS SSO → 用 service ticket 登录 → cookie 会话
    //  API: https://hsxs.chnu.edu.cn/api/
    // ════════════════════════════════════════════════
    inner class HsxsClient {
        private val baseUrl = ChnuConfig.HSXS_API_BASE
        private val webUrl = ChnuConfig.HSXS_BASE_URL

        @Volatile var loggedIn: Boolean = false
            private set
        @Volatile var userName: String = ""
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
            userName = info.optJSONObject("data")?.optString("user_name", userName) ?: userName
                .ifEmpty { info.optString("user_name", userName) }
            loggedIn = true
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
                val json = parseJsonObject(text)
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
                return parseJsonObject(text) ?: JSONObject()
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
                return parseJsonObject(text) ?: JSONObject()
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
                return parseJsonObject(text) ?: JSONObject()
            }
        }
    }
}
