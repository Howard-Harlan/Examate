package go.examate.hyp.campus

import go.examate.hyp.campus.model.AvailableCourse
import go.examate.hyp.campus.model.BankCard
import go.examate.hyp.campus.model.BillRecord
import go.examate.hyp.campus.model.EcardBalance
import go.examate.hyp.campus.model.EvaluationItem
import go.examate.hyp.campus.model.ExamItem
import go.examate.hyp.campus.model.Grade
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

    // ═══ 内存缓存 ═══
    /** 缓存条目:数据 + 过期时间戳 */
    private data class CacheEntry(val data: Any, val expireAt: Long)

    /** 缓存:key = "username:type:params" → CacheEntry */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    /** 默认缓存有效期:5 分钟 */
    private val cacheTtlMs = 5 * 60 * 1000L

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
    private fun cachePut(key: String, data: Any) {
        cache[key] = CacheEntry(data, System.currentTimeMillis() + cacheTtlMs)
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

    suspend fun getStudentInfo(prefs: PrefsManager, forceRefresh: Boolean = false): StudentInfo {
        val key = cacheKey("studentInfo")
        if (!forceRefresh) cacheGet<StudentInfo>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getStudentInfo() }
        cachePut(key, data)
        return data
    }

    suspend fun getGrades(prefs: PrefsManager, xn: String? = null, xq: String? = null, forceRefresh: Boolean = false): List<Grade> {
        val key = cacheKey("grades", xn ?: "", xq ?: "")
        if (!forceRefresh) cacheGet<List<Grade>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getGrades(xn, xq) }
        cachePut(key, data)
        return data
    }

    suspend fun getSchedule(prefs: PrefsManager, xn: String? = null, xq: String? = null, forceRefresh: Boolean = false): List<ScheduleItem> {
        val key = cacheKey("schedule", xn ?: "", xq ?: "")
        if (!forceRefresh) cacheGet<List<ScheduleItem>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getSchedule(xn, xq) }
        cachePut(key, data)
        return data
    }

    suspend fun getExamSchedule(prefs: PrefsManager, xn: String? = null, xq: String? = null, forceRefresh: Boolean = false): List<ExamItem> {
        val key = cacheKey("examSchedule", xn ?: "", xq ?: "")
        if (!forceRefresh) cacheGet<List<ExamItem>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getExamSchedule(xn, xq) }
        cachePut(key, data)
        return data
    }

    suspend fun getSelectedCourses(prefs: PrefsManager, xn: String? = null, xq: String? = null, forceRefresh: Boolean = false): List<SelectedCourse> {
        val key = cacheKey("selectedCourses", xn ?: "", xq ?: "")
        if (!forceRefresh) cacheGet<List<SelectedCourse>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getSelectedCourses(xn, xq) }
        cachePut(key, data)
        return data
    }

    suspend fun getTeachingEvaluation(prefs: PrefsManager, forceRefresh: Boolean = false): List<EvaluationItem> {
        val key = cacheKey("teachingEval")
        if (!forceRefresh) cacheGet<List<EvaluationItem>>(key)?.let { return it }
        val data = withJwxt(prefs) { it.getTeachingEvaluation() }
        cachePut(key, data)
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

    suspend fun topUp(prefs: PrefsManager, amount: Double, queryPassword: String): PayResult =
        withEcard(prefs) { it.topUp(amount, queryPassword) }

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

    companion object {
        @Volatile
        private var instance: CampusRepository? = null

        fun getInstance(): CampusRepository =
            instance ?: synchronized(this) {
                instance ?: CampusRepository().also { instance = it }
            }
    }
}
