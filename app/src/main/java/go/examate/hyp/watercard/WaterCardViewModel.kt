package go.examate.hyp.watercard

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.config.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WaterCardViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("watercard_session", Context.MODE_PRIVATE)
    private val appPrefs = PrefsManager.getInstance(app)
    private val api = YxtApi()

    private val _uiState = MutableStateFlow(WaterCardUiState())
    val uiState: StateFlow<WaterCardUiState> = _uiState.asStateFlow()

    /** 支付事件：下单成功后通过此 Channel 通知 UI 拉起支付宝（Channel 防止事件丢失/重放） */
    private val _payEvent = Channel<PayEvent>(Channel.BUFFERED)
    val payEvent = _payEvent.receiveAsFlow()

    init { restoreSession() }

    // ════════════════════════════════════════════════
    //  会话持久化
    // ════════════════════════════════════════════════

    private fun restoreSession() {
        // 1. 优先恢复已登录会话（tokenInfo 仍有效时直接进入主页）
        val token = prefs.getString("token", "") ?: ""
        if (token.isNotBlank()) {
            val isV4 = prefs.getBoolean("isV4", true)
            val session = YxtSession(
                token = token,
                userId = prefs.getString("userId", "") ?: "",
                customerId = prefs.getString("customerId", "") ?: "",
                customerName = prefs.getString("customerName", "") ?: "",
                balanceId = prefs.getString("balanceId", "") ?: "",
                projectId = prefs.getString("projectId", "") ?: "",
                serviceId = prefs.getString("serviceId", "") ?: "",
                serviceName = prefs.getString("serviceName", "") ?: "",
                customerPhone = prefs.getString("customerPhone", "") ?: "",
                userName = prefs.getString("userName", "") ?: "",
                isV4 = isV4,
                tokenInfo = prefs.getString("tokenInfo", "") ?: "",
                v3BaseUrl = prefs.getString("v3BaseUrl", "") ?: "",
                schoolId = prefs.getString("schoolId", "") ?: "",
                investorId = prefs.getString("investorId", "") ?: "",
                studentHex = prefs.getString("studentHex", "") ?: ""
            )
            if (session.isLoggedIn) {
                api.session = session
                if (!isV4) api.restoreV3BaseUrl(session.v3BaseUrl)
                _uiState.update { it.copy(session = session, page = Page.MAIN) }
                return
            }
        }
        // 2. 未登录：从 Prefs 恢复账号/密码/学校（"快速登录态"，只需输验证码）
        val savedAccount = appPrefs.yxtAccount
        val savedPassword = appPrefs.yxtPassword
        val savedSchoolName = appPrefs.yxtSchoolName
        val savedSysVer = appPrefs.yxtSchoolSysVersion
        if (savedAccount.isNotBlank() && savedPassword.isNotBlank() && savedSchoolName.isNotBlank() && savedSysVer > 0) {
            val school = YxtSchool(
                schoolName = savedSchoolName,
                schoolAlias = appPrefs.yxtSchoolAlias,
                sysVersion = savedSysVer
            )
            _uiState.update {
                it.copy(
                    schoolKeyword = savedSchoolName,
                    selectedSchool = school,
                    userName = savedAccount,
                    password = savedPassword
                )
            }
        }
    }

    private fun saveSession(session: YxtSession) {
        prefs.edit().apply {
            putString("token", session.token)
            putString("userId", session.userId)
            putString("customerId", session.customerId)
            putString("customerName", session.customerName)
            putString("balanceId", session.balanceId)
            putString("projectId", session.projectId)
            putString("serviceId", session.serviceId)
            putString("serviceName", session.serviceName)
            putString("customerPhone", session.customerPhone)
            putString("userName", session.userName)
            putBoolean("isV4", session.isV4)
            putString("tokenInfo", session.tokenInfo)
            putString("v3BaseUrl", session.v3BaseUrl)
            putString("schoolId", session.schoolId)
            putString("investorId", session.investorId)
            putString("studentHex", session.studentHex)
            apply()
        }
    }

    private fun clearSession() {
        prefs.edit().clear().apply()
        api.session = null
    }

    // ════════════════════════════════════════════════
    //  登录流程
    // ════════════════════════════════════════════════

    fun updateSchoolKeyword(keyword: String) {
        _uiState.update { it.copy(schoolKeyword = keyword) }
    }

    fun searchSchool() {
        val keyword = _uiState.value.schoolKeyword.trim()
        if (keyword.isBlank()) return
        _uiState.update { it.copy(loginLoading = true, loginError = "") }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { api.searchSchool(keyword) }
                _uiState.update { it.copy(schoolList = list, loginLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loginLoading = false, loginError = e.message ?: "搜索失败") }
            }
        }
    }

    fun selectSchool(school: YxtSchool) {
        // 切换学校时清空验证码状态
        _uiState.update {
            it.copy(
                selectedSchool = school,
                captchaBase64 = "",
                captchaKey = "",
                captchaInput = "",
                loginError = ""
            )
        }
    }

    fun updateUserName(name: String) {
        _uiState.update { it.copy(userName = name) }
    }

    fun updatePassword(pwd: String) {
        _uiState.update { it.copy(password = pwd) }
    }

    fun updateCaptchaInput(code: String) {
        _uiState.update { it.copy(captchaInput = code) }
    }

    /** 获取验证码：V4=图形验证码(getLoginCodeImage), V3=图片验证码(getCode, 需手机号)
     *  官方 App 4.3.9 抓包确认：sysVersion=3 走 topservice.lxt6.cn */
    fun refreshCaptcha() {
        val s = _uiState.value
        val school = s.selectedSchool
        _uiState.update { it.copy(loginLoading = true, loginError = "") }
        viewModelScope.launch {
            try {
                val captcha = withContext(Dispatchers.IO) {
                    if (school != null && !school.isV4) {
                        val account = s.userName.replace(" ", "")
                        if (account.isBlank()) {
                            throw YxtException("请先输入手机号")
                        }
                        api.getV3Captcha(account)
                    } else {
                        api.getCaptcha()
                    }
                }
                _uiState.update {
                    it.copy(
                        captchaBase64 = captcha.captchaBase64,
                        captchaKey = captcha.captchaKey,
                        loginLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loginLoading = false, loginError = e.message ?: "获取验证码失败") }
            }
        }
    }

    /** 登录：sysVersion=3 走 V3 接口（topservice.lxt6.cn），sysVersion=4 走 V4 接口
     *  官方 App 4.3.9 抓包确认：淮北师范大学(sysVersion=3)走 topservice V3 接口 */
    fun login() {
        val s = _uiState.value
        if (s.userName.isBlank()) { _uiState.update { it.copy(loginError = "请输入用户名") }; return }
        if (s.password.isBlank()) { _uiState.update { it.copy(loginError = "请输入密码") }; return }
        if (s.captchaInput.isBlank()) { _uiState.update { it.copy(loginError = "请输入验证码") }; return }
        val school = s.selectedSchool
        val isV3 = school != null && !school.isV4
        if (!isV3 && s.captchaKey.isBlank()) {
            _uiState.update { it.copy(loginError = "请先获取验证码") }; return
        }
        if (isV3 && s.captchaBase64.isBlank()) {
            _uiState.update { it.copy(loginError = "请先获取验证码") }; return
        }

        _uiState.update { it.copy(loginLoading = true, loginError = "") }
        viewModelScope.launch {
            try {
                if (isV3) {
                    // V3 登录流程（topservice.lxt6.cn）
                    val account = s.userName.replace(" ", "")
                    val loginResult = withContext(Dispatchers.IO) {
                        api.loginV3(account, s.password, s.captchaInput)
                    }
                    val tokenInfo = api.getLastTokenInfo()
                    // 先用临时 session（只有 tokenInfo）调 getSchoolInfoV3 获取 schoolId
                    api.session = YxtSession(
                        token = loginResult.token,
                        userId = loginResult.userId,
                        customerId = "",
                        customerName = "",
                        balanceId = "",
                        projectId = "",
                        serviceId = "",
                        serviceName = "",
                        customerPhone = account,
                        userName = account,
                        isV4 = false,
                        tokenInfo = tokenInfo,
                        v3BaseUrl = api.v3BaseUrl,
                        schoolId = ""
                    )
                    // 登录后获取 schoolId（schoolId 不在搜索结果里，必须登录后获取）
                    var schoolId = ""
                    try {
                        schoolId = withContext(Dispatchers.IO) { api.getSchoolInfoV3() }
                        android.util.Log.d("WaterCardVM", "获取 schoolId: $schoolId")
                    } catch (e: Exception) {
                        android.util.Log.e("WaterCardVM", "获取 schoolId 失败", e)
                    }
                    // 获取 studentHex（抓包确认 V3 流程必须先调 getUserInfo，否则 getUserWallet 返回 Data:null）
                    var studentHex = ""
                    try {
                        val userInfo = withContext(Dispatchers.IO) { api.getUserInfoV3() }
                        studentHex = userInfo.studentHex
                        android.util.Log.d("WaterCardVM", "获取 studentHex: $studentHex")
                    } catch (e: Exception) {
                        android.util.Log.e("WaterCardVM", "获取用户信息失败", e)
                    }
                    // 获取 investorId
                    var investorId = ""
                    if (schoolId.isNotBlank()) {
                        try {
                            investorId = withContext(Dispatchers.IO) {
                                api.getInvestorInfoV3(schoolId)
                            }
                            android.util.Log.d("WaterCardVM", "获取 investorId: $investorId")
                        } catch (e: Exception) {
                            android.util.Log.e("WaterCardVM", "获取 investorId 失败", e)
                        }
                    }
                    // 更新 session（含 schoolId / investorId / studentHex）
                    api.session = api.session!!.copy(
                        schoolId = schoolId,
                        investorId = investorId,
                        studentHex = studentHex
                    )
                    // 获取钱包余额
                    if (investorId.isNotBlank() && schoolId.isNotBlank()) {
                        try {
                            val wallet = withContext(Dispatchers.IO) {
                                api.getWalletV3(investorId, schoolId, loginResult.token)
                            }
                            api.session = api.session!!.copy(balanceId = wallet.id)
                        } catch (e: Exception) {
                            android.util.Log.e("WaterCardVM", "获取钱包失败", e)
                        }
                    }
                } else {
                    // V4 登录流程
                    val loginResult = withContext(Dispatchers.IO) {
                        api.login(s.userName.replace(" ", ""), s.password, s.captchaInput, s.captchaKey)
                    }
                    api.session = YxtSession(
                        token = loginResult.token,
                        userId = loginResult.userId,
                        customerId = loginResult.customerId,
                        customerName = loginResult.customerName,
                        balanceId = "",
                        projectId = "",
                        serviceId = "",
                        serviceName = "",
                        customerPhone = "",
                        userName = loginResult.name.ifBlank { loginResult.customerName }
                    )
                    try {
                        val userInfo = withContext(Dispatchers.IO) { api.getUserInfo() }
                        api.session = api.session!!.copy(
                            balanceId = userInfo.balanceId,
                            projectId = userInfo.projectId,
                            serviceId = userInfo.serviceId,
                            serviceName = userInfo.serviceName,
                            customerPhone = userInfo.customerPhone,
                            userName = userInfo.name.ifBlank { loginResult.name }
                        )
                    } catch (_: Exception) { }
                }

                val finalSession = api.session!!
                saveSession(finalSession)
                // 登录成功后保存账号/密码/学校(下次进入只需输验证码)
                val school = s.selectedSchool
                if (school != null) {
                    appPrefs.yxtAccount = s.userName.replace(" ", "")
                    appPrefs.yxtPassword = s.password
                    appPrefs.yxtSchoolName = school.schoolName
                    appPrefs.yxtSchoolAlias = school.schoolAlias
                    appPrefs.yxtSchoolSysVersion = school.sysVersion
                }
                _uiState.update {
                    it.copy(
                        session = finalSession,
                        page = Page.MAIN,
                        loginLoading = false,
                        loginError = ""
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loginLoading = false, loginError = e.message ?: "登录失败") }
            }
        }
    }

    // ════════════════════════════════════════════════
    //  V4 忘记密码（V3 用户密码不在 V4 库，需重置后才能 V4 登录）
    //  流程: getLoginCodeImage → sendSmscode → resetPassword → login
    // ════════════════════════════════════════════════

    fun openResetDialog() {
        _uiState.update {
            it.copy(
                showResetDialog = true,
                resetStage = 0,
                resetCaptchaInput = "",
                resetSmsCode = "",
                resetNewPassword = "",
                resetError = ""
            )
        }
        refreshCaptcha()
    }

    fun closeResetDialog() {
        _uiState.update { it.copy(showResetDialog = false, resetLoading = false, resetError = "") }
    }

    fun updateResetCaptchaInput(v: String) = _uiState.update { it.copy(resetCaptchaInput = v) }
    fun updateResetSmsCode(v: String) = _uiState.update { it.copy(resetSmsCode = v) }
    fun updateResetNewPassword(v: String) = _uiState.update { it.copy(resetNewPassword = v) }

    /** 发送短信验证码（stage 0）：需先有图形验证码 */
    fun sendSms() {
        val s = _uiState.value
        val mobile = s.userName.replace(" ", "")
        if (mobile.isBlank()) { _uiState.update { it.copy(resetError = "请先在登录页输入手机号") }; return }
        if (s.resetCaptchaInput.isBlank()) { _uiState.update { it.copy(resetError = "请输入图形验证码") }; return }
        if (s.captchaKey.isBlank()) { _uiState.update { it.copy(resetError = "图形验证码未获取,请点击图片刷新") }; return }
        _uiState.update { it.copy(resetLoading = true, resetError = "") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.sendSmsCode(mobile, s.resetCaptchaInput, s.captchaKey)
                }
                _uiState.update { it.copy(resetLoading = false, resetStage = 1, resetError = "") }
            } catch (e: Exception) {
                _uiState.update { it.copy(resetLoading = false, resetError = e.message ?: "发送失败") }
            }
        }
    }

    /** 重置密码（stage 1）：用短信码 + 新密码重置 */
    fun resetPassword() {
        val s = _uiState.value
        val mobile = s.userName.replace(" ", "")
        if (s.resetSmsCode.isBlank()) { _uiState.update { it.copy(resetError = "请输入短信验证码") }; return }
        if (s.resetNewPassword.isBlank()) { _uiState.update { it.copy(resetError = "请输入新密码") }; return }
        _uiState.update { it.copy(resetLoading = true, resetError = "") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.resetPassword(mobile, s.resetNewPassword, s.resetSmsCode)
                }
                _uiState.update {
                    it.copy(
                        resetLoading = false,
                        showResetDialog = false,
                        password = s.resetNewPassword,
                        loginError = "密码已重置,请获取新验证码后登录"
                    )
                }
                // 刷新验证码供登录使用
                refreshCaptcha()
            } catch (e: Exception) {
                _uiState.update { it.copy(resetLoading = false, resetError = e.message ?: "重置失败") }
            }
        }
    }

    // ════════════════════════════════════════════════
    //  主页：余额 + 账单
    // ════════════════════════════════════════════════

    fun loadWallet() {
        val s = _uiState.value.session ?: return
        _uiState.update { it.copy(wallet = it.wallet?.copy(loading = true, error = "") ?: WalletState(loading = true)) }
        viewModelScope.launch {
            try {
                if (s.isV4) {
                    if (s.balanceId.isBlank()) {
                        _uiState.update { it.copy(wallet = WalletState(error = "未获取到 balanceId,请重新登录")) }
                        return@launch
                    }
                    val wallet = withContext(Dispatchers.IO) { api.getWalletInfo(s.balanceId) }
                    _uiState.update {
                        it.copy(wallet = WalletState(
                            balance = wallet.balanceText,
                            giftBalance = wallet.giftBalance,
                            totalBalance = wallet.totalBalance,
                            loading = false, loaded = true, error = ""
                        ))
                    }
                } else {
                    // V3: 如果 investorId/schoolId 缺失，自动重新获取（可能上次登录时获取失败）
                    var workSession = s
                    if (workSession.investorId.isBlank() || workSession.schoolId.isBlank()) {
                        android.util.Log.d("WaterCardVM", "investorId/schoolId 缺失，自动重新获取")
                        workSession = ensureV3SessionFields(workSession)
                        api.session = workSession
                        saveSession(workSession)
                        _uiState.update { it.copy(session = workSession) }
                    }
                    if (workSession.investorId.isBlank() || workSession.schoolId.isBlank()) {
                        _uiState.update { it.copy(wallet = WalletState(error = "获取学校/服务商信息失败,请退出重新登录")) }
                        return@launch
                    }
                    val wallet = withContext(Dispatchers.IO) {
                        api.getWalletV3(workSession.investorId, workSession.schoolId, workSession.userId)
                    }
                    api.session = workSession.copy(balanceId = wallet.id)
                    saveSession(api.session!!)
                    _uiState.update {
                        it.copy(
                            session = api.session,
                            wallet = WalletState(
                            balance = wallet.balance,
                            giftBalance = "",
                            totalBalance = wallet.balance,
                            loading = false, loaded = true, error = ""
                        ))
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(wallet = it.wallet?.copy(loading = false, error = e.message ?: "查询失败")
                        ?: WalletState(loading = false, error = e.message ?: "查询失败"))
                }
            }
        }
    }

    /**
     * V3 专用：确保 session 中 schoolId / investorId / studentHex / balanceId 都已获取。
     * 用于从旧 session 恢复时自动补全缺失字段。
     */
    private suspend fun ensureV3SessionFields(s: YxtSession): YxtSession {
        var session = s
        // 1. schoolId
        if (session.schoolId.isBlank()) {
            try {
                session = session.copy(schoolId = withContext(Dispatchers.IO) { api.getSchoolInfoV3() })
                android.util.Log.d("WaterCardVM", "补获 schoolId: ${session.schoolId}")
            } catch (e: Exception) {
                android.util.Log.e("WaterCardVM", "补获 schoolId 失败", e)
            }
        }
        // 2. studentHex
        if (session.studentHex.isBlank()) {
            try {
                session = session.copy(studentHex = withContext(Dispatchers.IO) { api.getUserInfoV3() }.studentHex)
                android.util.Log.d("WaterCardVM", "补获 studentHex: ${session.studentHex}")
            } catch (e: Exception) {
                android.util.Log.e("WaterCardVM", "补获 studentHex 失败", e)
            }
        }
        // 3. investorId
        if (session.investorId.isBlank() && session.schoolId.isNotBlank()) {
            try {
                session = session.copy(investorId = withContext(Dispatchers.IO) { api.getInvestorInfoV3(session.schoolId) })
                android.util.Log.d("WaterCardVM", "补获 investorId: ${session.investorId}")
            } catch (e: Exception) {
                android.util.Log.e("WaterCardVM", "补获 investorId 失败", e)
            }
        }
        return session
    }

    fun loadBills() {
        val s = _uiState.value.session ?: return
        _uiState.update { it.copy(bills = it.bills.copy(loading = true, error = "")) }
        viewModelScope.launch {
            try {
                val recharge = withContext(Dispatchers.IO) {
                    if (s.isV4) api.getRechargeRecords() else api.getRechargeRecordsV3(s.schoolId)
                }
                val consume = withContext(Dispatchers.IO) {
                    if (s.isV4) api.getConsumeRecords() else api.getConsumeRecordsV3(s.schoolId)
                }
                val all = (recharge + consume).sortedByDescending { it.time }
                _uiState.update {
                    it.copy(bills = BillState(items = all, loading = false, loaded = true, error = ""))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(bills = it.bills.copy(loading = false, error = e.message ?: "查询失败"))
                }
            }
        }
    }

    fun refreshAll() {
        // 仅刷新余额;账单改为用户在主页点击"账单记录"后才加载
        loadWallet()
    }

    // ════════════════════════════════════════════════
    //  充值
    // ════════════════════════════════════════════════

    fun updateRechargeAmount(amount: String) {
        _uiState.update { it.copy(rechargeAmount = amount) }
    }

    fun selectPayMethod(method: PayMethod) {
        _uiState.update { it.copy(payMethod = method) }
    }

    fun recharge() {
        val s = _uiState.value.session ?: return
        val amount = _uiState.value.rechargeAmount.trim()
        if (amount.isBlank()) { _uiState.update { it.copy(rechargeError = "请输入充值金额") }; return }
        val amountVal = amount.toDoubleOrNull()
        if (amountVal == null || amountVal <= 0) {
            _uiState.update { it.copy(rechargeError = "金额必须大于 0") }; return
        }

        val payMent = _uiState.value.payMethod.flagType
        // 微信支付:App 端商户未授权(-71),现尝试小程序渠道(channelType=3)
        // 注意:App 端无 openId,服务端可能仍拒绝;失败时错误信息会回显给用户
        if (s.isV4) {
            if (s.projectId.isBlank()) { _uiState.update { it.copy(rechargeError = "projectId 未获取,请重新登录") }; return }
        } else {
            if (s.investorId.isBlank() || s.schoolId.isBlank() || s.balanceId.isBlank()) {
                _uiState.update { it.copy(rechargeError = "缺少充值参数,请重新登录") }; return
            }
        }

        _uiState.update { it.copy(rechargeLoading = true, rechargeError = "", rechargeResult = null) }
        viewModelScope.launch {
            try {
                val order = withContext(Dispatchers.IO) {
                    if (s.isV4) {
                        api.createRechargeOrder(amount, payMent, s.projectId)
                    } else {
                        api.createRechargeOrderV3(amount, payMent, s.investorId, s.schoolId, s.balanceId)
                    }
                }
                _uiState.update {
                    it.copy(
                        rechargeLoading = false,
                        rechargeResult = order,
                        rechargeError = ""
                    )
                }
                // 根据支付方式拉起对应支付
                if (payMent == PayMethod.WECHAT.flagType) {
                    // 微信支付：需要 appId/partnerId/prepayId/nonceStr/timeStamp/sign
                    if (order.appId.isNotBlank() && order.prepayId.isNotBlank()) {
                        _payEvent.send(PayEvent.Wechat(
                            appId = order.appId,
                            partnerId = order.partnerId,
                            prepayId = order.prepayId,
                            nonceStr = order.nonceStr,
                            timeStamp = order.timeStamp,
                            sign = order.sign,
                            tradeNo = order.tradeNo
                        ))
                    } else {
                        _uiState.update { it.copy(rechargeError = "微信下单成功,但支付参数解析失败: ${order.raw.take(300)}") }
                    }
                } else {
                    // 支付宝：orderString 即 App 支付参数
                    // 抓包确认 V3 返回 payInfo.tradeNo = "alipay_sdk=...&method=alipay.trade.app.pay&..."
                    val orderStr = order.orderString.ifBlank { order.tradeNo }
                    if (orderStr.isNotBlank()) {
                        _payEvent.send(PayEvent.Alipay(orderStr, order.tradeNo))
                    } else {
                        _uiState.update { it.copy(rechargeError = "订单已创建,但未获取到支付参数") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(rechargeLoading = false, rechargeError = e.message ?: "充值失败")
                }
            }
        }
    }

    /** 支付回调（由 Activity 调用 PayTask 后回传结果） */
    fun onPayResult(success: Boolean, memo: String) {
        viewModelScope.launch {
            if (success) {
                _uiState.update {
                    it.copy(
                        rechargeResult = null,
                        rechargeError = "支付成功,正在刷新余额..."
                    )
                }
                loadWallet()
                _uiState.update { it.copy(rechargeError = "") }
            } else {
                _uiState.update {
                    it.copy(rechargeError = if (memo.isBlank()) "支付未完成" else memo)
                }
            }
        }
    }

    fun clearRechargeResult() {
        _uiState.update { it.copy(rechargeResult = null, rechargeError = "") }
    }

    // ════════════════════════════════════════════════
    //  退出登录
    // ════════════════════════════════════════════════

    /**
     * 退出登录
     * @param clearCredentials true=完全退出(清账号/密码/学校); false=仅退出会话(保留凭据,下次只需输验证码)
     */
    fun logout(clearCredentials: Boolean = true) {
        clearSession()
        if (clearCredentials) {
            appPrefs.clearYxtCredentials()
            _uiState.update { WaterCardUiState(page = Page.LOGIN) }
        } else {
            // 保留账号/密码/学校，仅清会话，下次进入只需输验证码
            val school = YxtSchool(
                schoolName = appPrefs.yxtSchoolName,
                schoolAlias = appPrefs.yxtSchoolAlias,
                sysVersion = appPrefs.yxtSchoolSysVersion
            )
            _uiState.update {
                WaterCardUiState(page = Page.LOGIN).copy(
                    schoolKeyword = appPrefs.yxtSchoolName,
                    selectedSchool = school,
                    userName = appPrefs.yxtAccount,
                    password = appPrefs.yxtPassword
                )
            }
        }
    }

    /** 切换账号:清空已保存凭据和当前表单,让用户重新输入学校/账号/密码 */
    fun switchAccount() {
        appPrefs.clearYxtCredentials()
        clearSession()
        _uiState.update { WaterCardUiState(page = Page.LOGIN) }
    }

    /** 判断是否处于"快速登录态"(已保存学校/账号/密码,只需输验证码) */
    fun isQuickLoginMode(): Boolean {
        val s = _uiState.value
        return s.selectedSchool != null &&
            s.userName.isNotBlank() &&
            s.password.isNotBlank() &&
            appPrefs.yxtAccount.isNotBlank()
    }
}

// ════════════════════════════════════════════════
//  UI 状态模型
// ════════════════════════════════════════════════

enum class Page { LOGIN, MAIN }

/** 支付方式（V4Recharge.payMent：1=微信 2=支付宝）。
 *  枚举顺序:支付宝在前(当前可用),微信在后(服务商未授权暂不可用) */
enum class PayMethod(val flagType: Int, val label: String) {
    ALIPAY(2, "支付宝"),
    WECHAT(1, "微信支付")
}

/** 支付事件（ViewModel → UI 一次性指令） */
sealed class PayEvent {
    /** 拉起支付宝：orderString 为 App 支付参数，tradeNo 为订单号 */
    data class Alipay(val orderString: String, val tradeNo: String) : PayEvent()
    /** 拉起微信支付：标准 App 支付参数 */
    data class Wechat(
        val appId: String, val partnerId: String, val prepayId: String,
        val nonceStr: String, val timeStamp: String, val sign: String,
        val tradeNo: String
    ) : PayEvent()
}

data class WaterCardUiState(
    val page: Page = Page.LOGIN,
    // 登录页
    val schoolKeyword: String = "",
    val schoolList: List<YxtSchool> = emptyList(),
    val selectedSchool: YxtSchool? = null,
    val userName: String = "",
    val password: String = "",
    val captchaBase64: String = "",
    val captchaKey: String = "",
    val captchaInput: String = "",
    val loginLoading: Boolean = false,
    val loginError: String = "",
    // 重置密码（V4 忘记密码流程：图形验证码→短信→重置）
    val showResetDialog: Boolean = false,
    val resetStage: Int = 0,  // 0=发送短信, 1=重置密码
    val resetCaptchaInput: String = "",
    val resetSmsCode: String = "",
    val resetNewPassword: String = "",
    val resetLoading: Boolean = false,
    val resetError: String = "",
    // 主页
    val session: YxtSession? = null,
    val wallet: WalletState? = null,
    val bills: BillState = BillState(),
    // 充值
    val rechargeAmount: String = "",
    val payMethod: PayMethod = PayMethod.ALIPAY,
    val rechargeLoading: Boolean = false,
    val rechargeResult: YxtRechargeOrder? = null,
    val rechargeError: String = ""
)

data class WalletState(
    val balance: String = "0.00",
    val giftBalance: String = "",
    val totalBalance: String = "",
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String = ""
)

data class BillState(
    val items: List<YxtBill> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String = ""
)
