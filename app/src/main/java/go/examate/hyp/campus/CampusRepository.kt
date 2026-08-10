package go.examate.hyp.campus

import go.examate.hyp.campus.model.AvailableCourse
import go.examate.hyp.campus.model.BankCard
import go.examate.hyp.campus.model.BillRecord
import go.examate.hyp.campus.model.BuildingInfo
import go.examate.hyp.campus.model.ClassroomPageData
import go.examate.hyp.campus.model.EcardBalance
import go.examate.hyp.campus.model.EmptyClassroom
import go.examate.hyp.campus.model.EvaluationItem
import go.examate.hyp.campus.model.EvaluationQuestion
import go.examate.hyp.campus.model.ExamItem
import go.examate.hyp.campus.model.Grade
import go.examate.hyp.campus.model.GradeDetail
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
import go.examate.hyp.config.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 校园服务底层架构:统一管理 [ChnuClient] 的创建、登录与生命周期。
 *
 * 职责:
 * 1. 单例持有当前已登录的 [ChnuClient],跨页面共享会话(避免每次进入页面重新登录)。
 * 2. 子系统登录懒加载:首次访问教务/一卡通/第二课堂时自动触发 SSO,后续复用会话。
 * 3. 退出登录时清空 client 与 Prefs 中的账号密码。
 * 4. 内存缓存:课表/成绩等数据短期缓存(默认 5 分钟),避免重复请求。
 *
 * UI 层通过 [getInstance] 获取实例,在协程中调用各方法(内部为同步 IO)。
 */
class CampusRepository private constructor() {

    @Volatile
    private var client: ChnuClient? = null

    /** 当前已登录的统一认证账号(学号),未登录返回空 */
    val currentUsername: String get() = client?.username ?: ""

    /** CAS 是否已登录 */
    val isLoggedIn: Boolean get() = client?.loggedIn == true

    /**
     * 将 OkHttp 的 CAS cookie 注入到 WebView 的 CookieManager,
     * 使 WebView 打开 CAS 保护的页面时自动完成 SSO,无需再次登录。
     *
     * 调用时机:WebView 创建后(确保 CookieManager 已初始化),loadUrl 之前。
     *
     * 注意:不显式指定 domain 属性,由 CookieManager 从 URL 推断,
     * 避免部分 Android 版本因 domain 不匹配 URL host 而静默拒绝 cookie。
     */
    fun injectCookiesToWebView(): Boolean {
        val c = client ?: return false
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // 注意:setAcceptThirdPartyCookies(webView, boolean) 需要 WebView 实例,
        // 此处无 WebView 可传,第三方 Cookie 放行已在 WebViewActivity.configure() 中设置。
        c.getAllCookies().forEach { cookie ->
            val scheme = if (cookie.secure) "https" else "http"
            // 用 cookie.domain(去前导点)作为 URL host,使 CookieManager
            // 将 cookie 绑定到正确的域。不显式写 domain= 属性。
            val host = cookie.domain.trimStart('.')
            val url = "$scheme://$host${cookie.path}"
            // 构建 cookie 字符串:仅 name=value; path=...[; secure]
            // 不包含 domain 属性,避免 CookieManager 校验 domain 与 URL host 匹配时静默拒绝
            val sb = StringBuilder("${cookie.name}=${cookie.value}; path=${cookie.path}")
            if (cookie.secure) sb.append("; Secure")
            if (cookie.httpOnly) sb.append("; HttpOnly")
            cookieManager.setCookie(url, sb.toString())
        }
        // flush 确保 cookie 写入磁盘,供下次 WebView 使用
        cookieManager.flush()
        return true
    }

    /**
     * 获取智慧思政 H5 的 JWT token(供 WebView 注入到请求 header 与 localStorage)。
     *
     * H5 应用(oshall/hbsz-h5)基于 Jeecg-Boot,认证方式为:
     * - 请求 header: X-Access-Token
     * - 前端 localStorage: Access-Token / X-Access-Token / W-Access-Token / token
     *
     * 流程:CAS SSO 获取 ticket → validateLogin → JWT token
     *
     * @return JWT token,失败返回 null
     */
    suspend fun getHbszToken(): String? {
        val c = client ?: return null
        return withContext(Dispatchers.IO) {
            try {
                if (!c.hbsz.loggedIn) c.hbsz.login()
                c.hbsz.token.takeIf { it.isNotEmpty() }
            } catch (e: Exception) { null }
        }
    }

    // ═══ 内存缓存 ═══
    /** 缓存条目:数据 + 过期时间戳 */
    private data class CacheEntry(val data: Any, val expireAt: Long)

    /** 缓存:key = "username:type:params" → CacheEntry */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    /** 低频变动数据缓存有效期:24 小时(课表/成绩/学生信息/教学评价) */
    private val longCacheTtlMs = 24 * 60 * 60 * 1000L
    /** 常规数据缓存有效期:5 分钟 */
    private val shortCacheTtlMs = 5 * 60 * 1000L

    /** 从缓存读取(过期或不存在返回 null) */
    @Suppress("UNCHECKED_CAST")
    private fun <T> cacheGet(key: String): T? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() > entry.expireAt) {
            cache.remove(key)
            return null
        }
        return entry.data as? T
    }

    /** 写入缓存 */
    private fun cachePut(key: String, data: Any, ttlMs: Long = shortCacheTtlMs) {
        cache[key] = CacheEntry(data, System.currentTimeMillis() + ttlMs)
    }

    /** 清空所有缓存(退出登录/手动刷新时调用) */
    fun clearCache() = cache.clear()

    /** 生成缓存 key */
    private fun cacheKey(type: String, vararg params: Any): String =
        "${currentUsername}:$type:${params.joinToString(",")}"

    /**
     * 使用保存的账号密码执行 CAS 登录。
     * @throws CampusException 登录失败
     */
    suspend fun login(username: String, password: String) {
        clearCache()
        val c = ChnuClient(username.trim(), password)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            c.login()
        }
        client = c
    }

    /**
     * 使用 Prefs 中保存的账号密码自动登录(若已有会话则直接返回)。
     * @return 已登录的 ChnuClient
     * @throws CampusException 未保存凭据或登录失败
     */
    suspend fun ensureLoggedIn(prefs: PrefsManager): ChnuClient {
        client?.let { if (it.loggedIn) return it }
        val u = prefs.campusUsername
        val p = prefs.campusPassword
        if (u.isBlank() || p.isBlank()) {
            throw CampusException("未保存校园账号,请先在校园服务页登录")
        }
        login(u, p)
        return client!!
    }

    /** 退出登录,清空会话与保存的凭据 */
    fun logout(prefs: PrefsManager? = null) {
        clearCache()
        client = null
        prefs?.let {
            it.campusUsername = ""
            it.campusPassword = ""
        }
    }

    // ════════════════════════════════════════════
    //  教务系统(JWXT)
    // ════════════════════════════════════════════

    suspend fun jwxtLogin(prefs: PrefsManager): ChnuClient =
        ensureLoggedIn(prefs).also {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!it.jwxt.loggedIn) it.jwxt.login()
            }
        }

    /**
     * 登录成功后预取关键数据(后台执行,不阻塞 UI)。
     * 课表、学生信息等低频数据预加载到缓存,用户进入页面时直接命中缓存。
     *
     * @param scope 外部传入的协程作用域(通常是 viewModelScope),
     *              避免 GlobalScope 导致协程无法随生命周期取消而泄漏。
     */
    fun prefetchJwxtData(prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val (xn, xq) = ChnuConfig.currentXnXq()
                kotlinx.coroutines.coroutineScope {
                    launch { getSchedule(prefs, xn, xq) }
                    launch { getStudentInfo(prefs) }
                }
            } catch (e: Exception) {
                // 预取失败静默忽略,不影响用户体验
            }
        }
    }

    suspend fun getStudentInfo(prefs: PrefsManager, forceRefresh: Boolean = false): StudentInfo {
        val key = cacheKey("studentInfo")
        if (!forceRefresh) cacheGet<StudentInfo>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getStudentInfo() }
        cachePut(key, data, longCacheTtlMs)
        return data
    }

    suspend fun getGrades(prefs: PrefsManager, xn: String? = null, xq: String? = null, forceRefresh: Boolean = false): List<Grade> {
        val key = cacheKey("grades", xn ?: "", xq ?: "")
        if (!forceRefresh) cacheGet<List<Grade>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getGrades(xn, xq) }
        cachePut(key, data, longCacheTtlMs)
        return data
    }

    suspend fun getSchedule(prefs: PrefsManager, xn: String? = null, xq: String? = null, forceRefresh: Boolean = false): List<ScheduleItem> {
        val key = cacheKey("schedule", xn ?: "", xq ?: "")
        if (!forceRefresh) cacheGet<List<ScheduleItem>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getSchedule(xn, xq) }
        cachePut(key, data, longCacheTtlMs)
        return data
    }

    suspend fun getExamSchedule(prefs: PrefsManager, xn: String? = null, xq: String? = null, forceRefresh: Boolean = false): List<ExamItem> {
        val key = cacheKey("examSchedule", xn ?: "", xq ?: "")
        if (!forceRefresh) cacheGet<List<ExamItem>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getExamSchedule(xn, xq) }
        cachePut(key, data, longCacheTtlMs)
        return data
    }

    suspend fun getSelectedCourses(prefs: PrefsManager, xn: String? = null, xq: String? = null, forceRefresh: Boolean = false): List<SelectedCourse> {
        val key = cacheKey("selectedCourses", xn ?: "", xq ?: "")
        if (!forceRefresh) cacheGet<List<SelectedCourse>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getSelectedCourses(xn, xq) }
        cachePut(key, data, longCacheTtlMs)
        return data
    }

    suspend fun getTeachingEvaluation(prefs: PrefsManager, forceRefresh: Boolean = false): List<EvaluationItem> {
        val key = cacheKey("teachingEval")
        if (!forceRefresh) cacheGet<List<EvaluationItem>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getTeachingEvaluation() }
        cachePut(key, data, longCacheTtlMs)
        return data
    }

    // 选课
    suspend fun getXkStatus(prefs: PrefsManager): XkStatus =
        withJwxt(prefs) { it.getXkStatus() }

    suspend fun getAvailableCourses(prefs: PrefsManager, xn: String, xq: String): List<AvailableCourse> =
        withJwxt(prefs) { it.getAvailableCourses(xn, xq) }

    suspend fun getChosenCourses(prefs: PrefsManager, xn: String, xq: String): List<AvailableCourse> =
        withJwxt(prefs) { it.getChosenCourses(xn, xq) }

    suspend fun chooseCourse(prefs: PrefsManager, jxbId: String, kchId: String, doJxbId: String, kklxdm: String, xn: String, xq: String): String =
        withJwxt(prefs) { it.chooseCourse(jxbId, kchId, doJxbId, kklxdm, xn, xq) }

    suspend fun cancelCourse(prefs: PrefsManager, jxbId: String, kchId: String, doJxbId: String, xn: String, xq: String): String =
        withJwxt(prefs) { it.cancelCourse(jxbId, kchId, doJxbId, xn, xq) }

    suspend fun getXkCredits(prefs: PrefsManager, xn: String, xq: String): XkCredits =
        withJwxt(prefs) { it.getXkCredits(xn, xq) }

    // ── 新增:成绩明细 ──

    suspend fun getGradeDetail(prefs: PrefsManager, kch: String, xn: String, xq: String): GradeDetail? =
        withJwxt(prefs) { it.getGradeDetail(kch, xn, xq) }

    // ── 新增:空闲教室 ──

    suspend fun getClassroomPageData(prefs: PrefsManager): ClassroomPageData =
        withJwxt(prefs) { it.getClassroomPageData() }

    suspend fun getEmptyClassrooms(
        prefs: PrefsManager, campusId: String = "1", roomTypeId: String = "", buildingId: String = "",
        startWeek: String = "", endWeek: String = "", weekDays: String = "", sectionRange: String = ""
    ): List<EmptyClassroom> =
        withJwxt(prefs) { it.getEmptyClassrooms(campusId, roomTypeId, buildingId, startWeek, endWeek, weekDays, sectionRange) }

    // ── 新增:教学评价题目与提交 ──

    suspend fun getEvaluationQuestions(prefs: PrefsManager, wjbm: String): List<EvaluationQuestion> =
        withJwxt(prefs) { it.getEvaluationQuestions(wjbm) }

    suspend fun submitEvaluation(prefs: PrefsManager, wjbm: String, xn: String, xq: String, scores: Map<String, Double>): String =
        withJwxt(prefs) { it.submitEvaluation(wjbm, xn, xq, scores) }

    // ── 新增:课表确认页(选课结果,任何时间可用) ──

    suspend fun getScheduleConfirmation(prefs: PrefsManager, xn: String? = null, xq: String? = null): List<SelectedCourse> =
        withJwxt(prefs) { it.getScheduleConfirmation(xn, xq) }

    // ── 新增:实验课表 ──

    suspend fun getExperimentSchedule(prefs: PrefsManager, xn: String? = null, xq: String? = null): List<ScheduleItem> =
        withJwxt(prefs) { it.getExperimentSchedule(xn, xq) }

    private suspend fun <T> withJwxt(prefs: PrefsManager, block: (ChnuClient.JWXTClient) -> T): T {
        val c = jwxtLogin(prefs)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            block(c.jwxt)
        }
    }

    // ════════════════════════════════════════════
    //  一卡通(Ecard)
    // ════════════════════════════════════════════

    suspend fun ecardLogin(prefs: PrefsManager): ChnuClient =
        ensureLoggedIn(prefs).also {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!it.ecard.loggedIn) it.ecard.login()
            }
        }

    suspend fun getBalance(prefs: PrefsManager, forceRefresh: Boolean = false): EcardBalance {
        val key = cacheKey("balance")
        if (!forceRefresh) cacheGet<EcardBalance>(key)?.let { return it }
        val data = withEcard(prefs) { it.getBalance() }
        cachePut(key, data)
        return data
    }

    suspend fun getBankCards(prefs: PrefsManager): List<BankCard> =
        withEcard(prefs) { it.getBankCards() }

    suspend fun getRoomElectricity(prefs: PrefsManager, areaNo: String, buildingNo: String, roomNo: String, itemNum: String = "3"): RoomElectricity =
        withEcard(prefs) { it.getRoomElectricity(areaNo, buildingNo, roomNo, itemNum) }

    suspend fun getBillRecords(
        prefs: PrefsManager,
        startDate: String? = null,
        endDate: String? = null,
        recordType: String = ""
    ): List<BillRecord> =
        withEcard(prefs) { it.getBillRecords(startDate, endDate, recordType) }

    /** 充值。bankCardNum 为充值页解析到的签约卡完整卡号(UI 选卡后传入)。 */
    suspend fun topUp(
        prefs: PrefsManager,
        amount: Double,
        queryPassword: String,
        bankCardNum: String = ""
    ): PayResult =
        withEcard(prefs) { it.topUp(amount, queryPassword, bankCardNum) }

    /** 获取充值可用的签约银行卡列表(供 UI 选卡步骤使用)。 */
    suspend fun getTopupBankCards(prefs: PrefsManager): List<BankCard> =
        withEcard(prefs) { it.getTopupBankCards() }

    suspend fun getPayAreas(prefs: PrefsManager, itemNum: String = "3"): List<PayArea> =
        withEcard(prefs) { it.getPayAreas(itemNum) }

    suspend fun getBuildings(prefs: PrefsManager, areaNo: String, itemNum: String = "3"): List<PayBuilding> =
        withEcard(prefs) { it.getBuildings(areaNo, itemNum) }

    suspend fun getRooms(prefs: PrefsManager, areaNo: String, buildingNo: String, itemNum: String = "3"): List<PayBuilding> =
        withEcard(prefs) { it.getRooms(areaNo, buildingNo, itemNum) }

    suspend fun payFee(
        prefs: PrefsManager,
        areaNo: String, buildingNo: String, roomNo: String,
        amount: Double, queryPassword: String, itemNum: String = "3"
    ): PayResult =
        withEcard(prefs) { it.payFee(areaNo, buildingNo, roomNo, amount, queryPassword, itemNum) }

    private suspend fun <T> withEcard(prefs: PrefsManager, block: (ChnuClient.EcardClient) -> T): T {
        val c = ecardLogin(prefs)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            block(c.ecard)
        }
    }

    // ════════════════════════════════════════════
    //  第二课堂(SC)
    // ════════════════════════════════════════════

    suspend fun scLogin(prefs: PrefsManager): ChnuClient =
        ensureLoggedIn(prefs).also {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!it.sc.loggedIn) it.sc.login()
            }
        }

    suspend fun getScoreDetail(prefs: PrefsManager, forceRefresh: Boolean = false): List<ScoreDetail> {
        val key = cacheKey("scoreDetail")
        if (!forceRefresh) cacheGet<List<ScoreDetail>>(key)?.let { return it }
        val data = withSc(prefs) { it.getScoreDetail() }
        cachePut(key, data)
        return data
    }

    suspend fun getScoreSummary(prefs: PrefsManager, forceRefresh: Boolean = false): ScoreSummary {
        val key = cacheKey("scoreSummary")
        if (!forceRefresh) cacheGet<ScoreSummary>(key)?.let { return it }
        val data = withSc(prefs) { it.getScoreSummary() }
        cachePut(key, data)
        return data
    }

    private suspend fun <T> withSc(prefs: PrefsManager, block: (ChnuClient.SCClient) -> T): T {
        val c = scLogin(prefs)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            block(c.sc)
        }
    }

    // ════════════════════════════════════════════════
    //  淮师心声(Hsxs) — 失物招领 & 满意度调查
    // ════════════════════════════════════════════════

    suspend fun hsxsLogin(prefs: PrefsManager): ChnuClient =
        ensureLoggedIn(prefs).also {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!it.hsxs.loggedIn) it.hsxs.login()
            }
        }
    suspend fun getLostList(prefs: PrefsManager, page: Int = 1, type: String = ""): JSONObject {
        return withHsxs(prefs) { it.getLostList(page, 20, type) }
    }

    suspend fun getLostDetail(prefs: PrefsManager, id: String): JSONObject {
        return withHsxs(prefs) { it.getLostDetail(id) }
    }

    suspend fun publishLost(prefs: PrefsManager, body: Map<String, String>): JSONObject {
        return withHsxs(prefs) { it.publishLost(body) }
    }

    suspend fun uploadImage(prefs: PrefsManager, fileName: String, fileBytes: ByteArray): String {
        return withHsxs(prefs) { it.uploadImage(fileName, fileBytes) }
    }

    suspend fun getOrganiseList(prefs: PrefsManager): JSONObject {
        return withHsxs(prefs) { it.getOrganiseList() }
    }

    suspend fun getUserInfo(prefs: PrefsManager): JSONObject {
        return withHsxs(prefs) { it.getUserInfo() }
    }

    suspend fun getQuestionList(prefs: PrefsManager, page: Int = 1): JSONObject {
        return withHsxs(prefs) { it.getQuestionList(page) }
    }

    suspend fun getQuestionDetail(prefs: PrefsManager, id: String): JSONObject {
        return withHsxs(prefs) { it.getQuestionDetail(id) }
    }

    suspend fun getSurveyQuestions(prefs: PrefsManager, id: String): JSONObject {
        return withHsxs(prefs) { it.getSurveyQuestions(id) }
    }

    suspend fun submitAnswer(prefs: PrefsManager, questionId: String, answers: JSONObject): JSONObject {
        return withHsxs(prefs) { it.submitAnswer(questionId, answers) }
    }

    suspend fun getMyAnswer(prefs: PrefsManager, questionId: String): JSONObject {
        return withHsxs(prefs) { it.getMyAnswer(questionId) }
    }

    private suspend fun <T> withHsxs(prefs: PrefsManager, block: (ChnuClient.HsxsClient) -> T): T {
        val c = hsxsLogin(prefs)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            block(c.hsxs)
        }
    }

    // ════════════════════════════════════════════════
    //  智慧思政(Szs) — 综合测评成绩
    // ════════════════════════════════════════════════

    suspend fun szsLogin(prefs: PrefsManager): ChnuClient =
        ensureLoggedIn(prefs).also {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!it.szs.loggedIn) it.szs.login()
            }
        }

    suspend fun getChangeScore(prefs: PrefsManager): List<go.examate.hyp.campus.model.SzsScoreItem> {
        return withSzs(prefs) { it.getChangeScore() }
    }

    private suspend fun <T> withSzs(prefs: PrefsManager, block: (ChnuClient.SzsClient) -> T): T {
        val c = szsLogin(prefs)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            block(c.szs)
        }
    }

    // ════════════════════════════════════════════════
    //  智慧门户(Zhxy) — 班级通讯录
    // ════════════════════════════════════════════════

    suspend fun zhxyLogin(prefs: PrefsManager): ChnuClient =
        ensureLoggedIn(prefs).also {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!it.zhxy.loggedIn) it.zhxy.login()
            }
        }

    suspend fun getStudentPhoneList(prefs: PrefsManager): List<go.examate.hyp.campus.model.ClassContact> {
        return withZhxy(prefs) { it.getStudentPhoneList() }
    }

    private suspend fun <T> withZhxy(prefs: PrefsManager, block: (ChnuClient.ZhxyClient) -> T): T {
        val c = zhxyLogin(prefs)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            block(c.zhxy)
        }
    }

    // ════════════════════════════════════════════════
    //  智慧思政 H5(hbsz) — 综测测评/离返校/留校登记
    // ════════════════════════════════════════════════

    suspend fun hbszLogin(prefs: PrefsManager): ChnuClient =
        ensureLoggedIn(prefs).also {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!it.hbsz.loggedIn) it.hbsz.login()
            }
        }

    suspend fun getEvaluatingList(prefs: PrefsManager): List<go.examate.hyp.campus.model.HbszEvaluatingItem> {
        return withHbsz(prefs) { it.getEvaluatingList() }
    }

    /**
     * 获取 hbsz JWT token(供 WebView 注入到请求 header,实现 H5 免登录)。
     * H5 用 W-Access-Token header,主站 PC 用 X-Access-Token header,
     * 两者都从 localStorage 读 token 放 header。
     */
    suspend fun getHbszToken(prefs: PrefsManager): String {
        return hbszLogin(prefs).hbsz.token
    }

    suspend fun getHolidayLeaveList(prefs: PrefsManager): List<go.examate.hyp.campus.model.HolidayLeaveRecord> {
        return withHbsz(prefs) { it.getHolidayLeaveList() }
    }

    suspend fun getStayInSchoolList(prefs: PrefsManager): List<go.examate.hyp.campus.model.StayInSchoolRecord> {
        return withHbsz(prefs) { it.getStayInSchoolList() }
    }

    private suspend fun <T> withHbsz(prefs: PrefsManager, block: (ChnuClient.HbszClient) -> T): T {
        val c = hbszLogin(prefs)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            block(c.hbsz)
        }
    }

    companion object {
        @Volatile
        private var instance: CampusRepository? = null

        fun getInstance(): CampusRepository =
            instance ?: synchronized(this) {
                instance ?: CampusRepository().also { instance = it }
            }
    }
}
