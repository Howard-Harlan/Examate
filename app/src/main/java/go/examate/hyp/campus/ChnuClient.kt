package go.examate.hyp.campus

import android.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
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
 * - 教务系统(JWXT:成绩/课表/考试/个人信息)→ 委托 [JwxtApi]
 * - 一卡通系统(Ecard:余额/账单)→ 委托 [EcardApi]
 * - 第二课堂(SC:成绩明细/汇总)→ 委托 [ScApi]
 * - 淮师心声(Hsxs:失物招领/问卷)→ 委托 [HsxsApi]
 *
 * 用 OkHttp 替代 Python requests,Cookie 由 [SimpleCookieJar] 内存管理,
 * SSL 信任所有证书(校园系统自签名),不跟随重定向由派生 client 实现。
 *
 * 所有方法为普通同步函数(非 suspend),调用方需在协程 withContext(Dispatchers.IO) 中调用。
 *
 * 公共 API(类型名 [JWXTClient]/[EcardClient]/[SCClient]/[HsxsClient] 及 [jwxt]/[ecard]/[sc]/[hsxs] 属性)
 * 保持向后兼容,实际实现已迁移到独立文件,降低单文件体积。
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
    /** 淮师心声客户端(失物招领 + 满意度调查) */
    val hsxs = HsxsClient()
    /** 智慧思政客户端(综合测评成绩) */
    val szs = SzsClient()
    /** 智慧门户客户端(班级通讯录) */
    val zhxy = ZhxyClient()

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
            .readTimeout(60, TimeUnit.SECONDS)
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

    // ==================== 教务系统客户端(委托) ====================

    /**
     * 教务系统(正方)客户端,通过智慧校园门户 SSO 登录。
     *
     * 实现已迁移至 [JwxtApi],本类仅作委托保留以维持向后兼容。
     *
     * SSO 流程:
     *  1. CAS 获取 ticket → service=/sso/hsssologin
     *  2. GET /sso/hsssologin?ticket=xxx → 302 → /ticketlogin
     *  3. GET /ticketlogin → 302 → /xtgl/login_slogin.html(设置 JSESSIONID)
     *  4. GET /xtgl/index_initMenu.html → 验证含"教学管理"
     */
    inner class JWXTClient {
        private val api = JwxtApi(client, noRedirectClient, username, ::getServiceTicket)

        val loggedIn: Boolean get() = api.loggedIn

        /** SSO 登录教务系统 */
        fun login() = api.login()

        /**
         * 查询学生个人信息。
         * 优先用 JSON 接口(xsxxwh_cxCkDgxsxx),失败回退 HTML 接口(xsgrxxwh_cxXsgrxx)。
         */
        fun getStudentInfo() = api.getStudentInfo()

        /** 查询成绩。xn/xq 为 null 时取当前学年学期 */
        fun getGrades(xn: String? = null, xq: String? = null) = api.getGrades(xn, xq)

        /** 查询课表。学期代码:1→"3",2→"12",3→"16" */
        fun getSchedule(xn: String? = null, xq: String? = null) = api.getSchedule(xn, xq)

        /** 查询考试安排 */
        fun getExamSchedule(xn: String? = null, xq: String? = null) = api.getExamSchedule(xn, xq)

        /**
         * 查询已选课程(本学期选课结果)
         *
         * GET /xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&xn=xxx&xq=xxx
         */
        fun getSelectedCourses(xn: String? = null, xq: String? = null) = api.getSelectedCourses(xn, xq)

        /**
         * 查询教学评价(学生评价)列表
         *
         * 流程:GET /xspjgl/xspj_cxXspjIndex.html?doType=details 初始化 session
         *      POST /xspjgl/xspj_cxXspjIndex.html?doType=query 查询
         */
        fun getTeachingEvaluation() = api.getTeachingEvaluation()

        /** 检查选课状态,返回是否在选课阶段及学年学期信息 */
        fun getXkStatus() = api.getXkStatus()

        /**
         * 查询可选课程(自主选课)。
         * @param xn 学年(如 "2025")
         * @param xq 学期代码(如 "12")
         */
        fun getAvailableCourses(xn: String, xq: String) = api.getAvailableCourses(xn, xq)

        /** 查询已选课程(选课模块) */
        fun getChosenCourses(xn: String, xq: String) = api.getChosenCourses(xn, xq)

        /** 选课(快速选课),返回结果消息 */
        fun chooseCourse(jxbId: String, kchId: String, doJxbId: String, kklxdm: String, xn: String, xq: String) =
            api.chooseCourse(jxbId, kchId, doJxbId, kklxdm, xn, xq)

        /** 退课,返回结果消息 */
        fun cancelCourse(jxbId: String, kchId: String, doJxbId: String, xn: String, xq: String) =
            api.cancelCourse(jxbId, kchId, doJxbId, xn, xq)

        /** 查询选课学分信息 */
        fun getXkCredits(xn: String, xq: String) = api.getXkCredits(xn, xq)

        // ── 新增功能 ──

        /** 查询成绩明细(某门课程的平时分/期末分/总分) */
        fun getGradeDetail(kch: String, xn: String, xq: String) = api.getGradeDetail(kch, xn, xq)

        /** 查询空闲教室 */
        fun getEmptyClassrooms(campusId: String = "1", roomTypeId: String = "", buildingId: String = "",
            startWeek: String = "", endWeek: String = "", weekDays: String = "", sectionRange: String = "") =
            api.getEmptyClassrooms(campusId, roomTypeId, buildingId, startWeek, endWeek, weekDays, sectionRange)

        /** 获取空闲教室页面数据(动态解析校区/楼号/场地类别选项) */
        fun getClassroomPageData() = api.getClassroomPageData()

        /** 获取教学评价题目 */
        fun getEvaluationQuestions(wjbm: String) = api.getEvaluationQuestions(wjbm)

        /** 提交教学评价 */
        fun submitEvaluation(wjbm: String, xn: String, xq: String, scores: Map<String, Double>) =
            api.submitEvaluation(wjbm, xn, xq, scores)

        /** 查询课表确认页(选课结果,任何时间可用) */
        fun getScheduleConfirmation(xn: String? = null, xq: String? = null) = api.getScheduleConfirmation(xn, xq)

        /** 查询实验课表 */
        fun getExperimentSchedule(xn: String? = null, xq: String? = null) = api.getExperimentSchedule(xn, xq)
    }

    // ==================== 一卡通系统客户端(委托) ====================

    /**
     * 校园一卡通客户端(easytong_portal,返回 HTML)。
     *
     * 实现已迁移至 [EcardApi],本类仅作委托保留以维持向后兼容。
     *
     * SSO 流程:
     *  1. CAS 获取 ticket → service=/ssoLogin
     *  2. GET ssoLogin?ticket=xxx(跟随 302)→ 200 自动提交表单 HTML
     *  3. POST /easytong_portal(空表单)→ 建立 session
     *  4. GET /balance 验证含"您好"
     */
    inner class EcardClient {
        private val api = EcardApi(client, ::getServiceTicket)

        val loggedIn: Boolean get() = api.loggedIn

        /** SSO 登录一卡通系统 */
        fun login() = api.login()

        /** 查询余额,返回余额、账户名、卡号与银行卡列表 */
        fun getBalance() = api.getBalance()

        /**
         * 查询已签约银行卡列表。
         * 解析 /bankCard 页面的 bankCardTypeName-{id}、bankCardNum-{id}、bankCard-{id} 元素及 radio input 完整卡号。
         */
        fun getBankCards() = api.getBankCards()

        /**
         * 查询房间电费(剩余金额与昨日用电)。
         * POST /payFee/getBalance,返回 JSON {balance, eleDailyUsed}。
         */
        fun getRoomElectricity(areaNo: String, buildingNo: String, roomNo: String, itemNum: String = "3") =
            api.getRoomElectricity(areaNo, buildingNo, roomNo, itemNum)

        /**
         * 查询账单记录。
         * @param startDate 开始日期(YYYY-MM-DD),默认 7 天前
         * @param endDate 结束日期(YYYY-MM-DD),默认今天
         * @param recordType 记录类型:1=消费,2=充值,3=补助,4=互转,""=全部
         *
         * 全部类型(""):分别查询消费(1)和充值(2)并合并。
         * 因为全类型查询页面不返回商户/地点/银行名称字段,只有按类型查才有。
         */
        fun getBillRecords(startDate: String? = null, endDate: String? = null, recordType: String = "") =
            api.getBillRecords(startDate, endDate, recordType)

        /** 获取充值可用的签约银行卡列表(供 UI 选卡步骤使用)。 */
        fun getTopupBankCards() = api.getTopupBankCards()

        fun topUp(amount: Double, queryPassword: String, bankCardNum: String = "") =
            api.topUp(amount, queryPassword, bankCardNum)

        /**
         * 获取生活缴费校区列表
         * @param itemNum 缴费项目编号:"2"=常工电控缴费,"3"=滨湖1-5宿舍照明电费
         */
        fun getPayAreas(itemNum: String = "3") = api.getPayAreas(itemNum)

        /** 获取楼栋列表(AJAX) */
        fun getBuildings(areaNo: String, itemNum: String = "3") = api.getBuildings(areaNo, itemNum)

        /** 获取房间列表(AJAX) */
        fun getRooms(areaNo: String, buildingNo: String, itemNum: String = "3") =
            api.getRooms(areaNo, buildingNo, itemNum)

        /**
         * 生活缴费(宿舍电费等)。
         *
         * 流程严格对齐网页端 itemPay() JS(lifeservice.js 第408行):
         *  1. POST /payFee/showItemListPayPage 获取缴费页面表单字段
         *  2. POST /publiccombo/keyPair 获取 RSA 公钥
         *  3. RSA 加密支付密码
         *  4. POST /publiccombo/checkpaypwd 校验支付密码(字段 paypassword)
         *     - ajaxState=3(SUCCESS)/4(PASSED)=通过; 2(FAILED)=密码错误
         *  5. POST /payFee/payItemlist 提交缴费(字段对齐 elementObj)
         *
         * ★ ajaxState 语义(对齐 portalcontants.js AJAXSTATE 常量):
         *   NOLOGIN:0 / NOAUTH:1 / FAILED:2 / SUCCESS:3 / PASSED:4
         *
         * ★ 提交字段名严格对齐 lifeservice.js itemPay() 的 elementObj:
         *   payMenoy(注意拼写,非 itemMoney), ewalletMenoy, cardaccNum(小写a),
         *   eWalletId, eWalletName, typeNum, itemName, token, itemNum
         *
         * @param queryPassword 支付密码(6位数字)
         */
        fun payFee(
            areaNo: String,
            buildingNo: String,
            roomNo: String,
            amount: Double,
            queryPassword: String,
            itemNum: String = "3"
        ) = api.payFee(areaNo, buildingNo, roomNo, amount, queryPassword, itemNum)
    }

    // ==================== 第二课堂客户端(委托) ====================

    /**
     * 第二课堂客户端(华博思道,前后端分离 + JWT)。
     *
     * 实现已迁移至 [ScApi],本类仅作委托保留以维持向后兼容。
     *
     * SSO 流程:
     *  1. CAS 获取 ticket → service=/hbsdWeb-prod
     *  2. GET /cas/client/checkSsoLogin?ticket=xxx&service=xxx → JWT token
     *  3. 后续请求用 X-Access-Token header
     */
    inner class SCClient {
        private val api = ScApi(client, username, ::getServiceTicket)

        val loggedIn: Boolean get() = api.loggedIn

        /** SSO 登录第二课堂,获取 JWT token */
        fun login() = api.login()

        /** 查询成绩明细(各项目得分) */
        fun getScoreDetail() = api.getScoreDetail()

        /** 查询成绩汇总(各模块总分) */
        fun getScoreSummary() = api.getScoreSummary()
    }

    // ════════════════════════════════════════════════
    //  淮师心声(Hsxs)客户端 — 失物招领 & 满意度调查
    //  CAS SSO → 用 service ticket 登录 → cookie 会话
    //  API: https://hsxs.chnu.edu.cn/api/
    //  实现已迁移至 HsxsApi,本类仅作委托保留以维持向后兼容
    // ════════════════════════════════════════════════
    inner class HsxsClient {
        private val api = HsxsApi(client, ::getServiceTicket)

        val loggedIn: Boolean get() = api.loggedIn
        val userName: String get() = api.userName
        val userPhone: String get() = api.userPhone

        fun login() = api.login()

        /** 获取校区列表 (GET + token) — 网页版使用 common/get_campus_list */
        fun getOrganiseList() = api.getOrganiseList()

        /** 获取当前用户信息(含手机号) */
        fun getUserInfo() = api.getUserInfo()

        /** 获取失物招领列表 (GET + token) */
        fun getLostList(page: Int = 1, pageSize: Int = 20, type: String = "") =
            api.getLostList(page, pageSize, type)

        /** 获取失物招领详情 (GET + token) */
        fun getLostDetail(id: String) = api.getLostDetail(id)

        /** 上传图片,返回文件路径 (POST multipart + token) */
        fun uploadImage(fileName: String, fileBytes: ByteArray) = api.uploadImage(fileName, fileBytes)

        /** 发布失物招领 (POST + token) */
        fun publishLost(body: Map<String, String>) = api.publishLost(body)

        /** 获取问卷列表 (GET + token) */
        fun getQuestionList(page: Int = 1, pageSize: Int = 20) = api.getQuestionList(page, pageSize)

        /** 获取问卷详情 (GET + token) */
        fun getQuestionDetail(id: String) = api.getQuestionDetail(id)

        /** 获取问卷题目列表 (GET + token) */
        fun getSurveyQuestions(id: String) = api.getSurveyQuestions(id)

        /** 提交问卷答案 (POST + token) */
        fun submitAnswer(questionId: String, answers: JSONObject) = api.submitAnswer(questionId, answers)

        /** 查看已答结果 (GET + token) */
        fun getMyAnswer(questionId: String) = api.getMyAnswer(questionId)
    }

    // ════════════════════════════════════════════════
    //  智慧思政(jgf)客户端 — 综合测评成绩
    //  CAS SSO → ticket 访问页面建立 session → 解析 HTML
    // ════════════════════════════════════════════════
    inner class SzsClient {
        private val api = SzsApi(client, ::getServiceTicket)

        val loggedIn: Boolean get() = api.loggedIn

        fun login() = api.login()

        /** 获取综合测评成绩明细 */
        fun getChangeScore() = api.getChangeScore()
    }

    // ════════════════════════════════════════════════
    //  智慧门户(oshall)客户端 — 班级通讯录
    //  CAS SSO → ticket 访问 zhxy-new 建立 session → 调用 /apiZhxy/ API
    // ════════════════════════════════════════════════
    inner class ZhxyClient {
        private val api = ZhxyApi(client, ::getServiceTicket)

        val loggedIn: Boolean get() = api.loggedIn

        fun login() = api.login()

        /** 查询班级通讯录(学生电话列表) */
        fun getStudentPhoneList() = api.getStudentPhoneList()
    }
}
