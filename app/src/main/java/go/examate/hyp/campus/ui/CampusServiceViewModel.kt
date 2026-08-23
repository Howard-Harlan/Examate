package go.examate.hyp.campus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CampusServiceActivity 的 ViewModel
 *
 * 将校园服务页的登录态、路由、账号表单状态从 Composable 中抽出,
 * 统一用 StateFlow 管理,Composable 通过 collectAsStateWithLifecycle() 收集。
 *
 * 优势:
 * 1. 状态与 UI 解耦,便于测试
 * 2. viewModelScope 自动绑定生命周期,屏幕旋转时不丢失
 * 3. 避免 Composable 重建时重复触发自动登录
 */
class CampusServiceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CampusRepository.getInstance()
    private val prefs = PrefsManager.getInstance(app)

    private val _uiState = MutableStateFlow(
        CampusServiceUiState(
            school = prefs.school,
            username = prefs.campusUsername,
            password = prefs.campusPassword,
            rememberLogin = prefs.rememberCampusLogin,
            // 启动时若 repo 已登录则直接显示已登录态
            loginState = if (repo.isLoggedIn) LoginState.LoggedIn else LoginState.Idle
        )
    )
    val uiState: StateFlow<CampusServiceUiState> = _uiState.asStateFlow()

    // ════════════════════════════════════════
    //  自动登录
    // ════════════════════════════════════════

    /**
     * 自动登录:已绑定学校 + 记住登录 + 有保存的账号密码 + 未登录
     */
    fun autoLogin() {
        if (repo.isLoggedIn) return
        if (!prefs.rememberCampusLogin) return
        if (prefs.campusUsername.isEmpty() || prefs.campusPassword.isEmpty()) return
        if (prefs.school.isEmpty()) return

        _uiState.update {
            it.copy(loginState = LoginState.Logging, loginError = "")
        }
        viewModelScope.launch {
            try {
                repo.login(prefs.campusUsername.trim(), prefs.campusPassword)
                repo.prefetchJwxtData(prefs, viewModelScope)
                fetchStudentName()
                _uiState.update {
                    it.copy(
                        username = prefs.campusUsername,
                        password = prefs.campusPassword,
                        loginState = LoginState.LoggedIn
                    )
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(loginError = e.message ?: "自动登录失败", loginState = LoginState.Failed)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loginError = "网络异常: ${e.message ?: "请检查网络连接"}",
                        loginState = LoginState.Failed
                    )
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  登录 / 退出
    // ════════════════════════════════════════

    fun login(username: String, password: String) {
        val s = _uiState.value
        if (s.loginState == LoginState.Logging) return
        if (username.isBlank() || password.isBlank()) {
            _uiState.update {
                it.copy(loginError = "请输入学号和密码", loginState = LoginState.Failed)
            }
            return
        }
        _uiState.update {
            it.copy(loginState = LoginState.Logging, loginError = "")
        }
        viewModelScope.launch {
            try {
                repo.login(username.trim(), password)
                repo.prefetchJwxtData(prefs, viewModelScope)
                prefs.campusUsername = username.trim()
                prefs.campusPassword = password
                fetchStudentName()
                _uiState.update {
                    it.copy(loginState = LoginState.LoggedIn)
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(loginError = e.message ?: "登录失败", loginState = LoginState.Failed)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loginError = "网络异常: ${e.message ?: "请检查网络连接"}",
                        loginState = LoginState.Failed
                    )
                }
            }
        }
    }

    fun logout() {
        repo.logout()
        // 退出登录后取消记住登录,避免下次进入又自动登录
        prefs.rememberCampusLogin = false
        _uiState.update {
            it.copy(
                rememberLogin = false,
                loginState = LoginState.Idle,
                loginError = "",
                studentName = ""
            )
        }
    }

    /** 登录后获取学生姓名(用于登录提示条显示) */
    private fun fetchStudentName() {
        viewModelScope.launch {
            try {
                val info = repo.getStudentInfo(prefs)
                _uiState.update { it.copy(studentName = info.name) }
            } catch (e: Exception) { /* 获取姓名失败不影响登录 */ }
        }
    }

    // ════════════════════════════════════════
    //  Setters
    // ════════════════════════════════════════

    fun setSchool(value: String) {
        prefs.school = value
        _uiState.update { it.copy(school = value) }
    }

    /** 关闭登录成功提示条 */
    fun dismissLoginBanner() {
        _uiState.update { it.copy(loginBannerDismissed = true) }
    }

    fun setRoute(route: CampusRoute) {
        _uiState.update { it.copy(route = route) }
    }

    fun setUsername(value: String) {
        _uiState.update { it.copy(username = value) }
    }

    fun setPassword(value: String) {
        _uiState.update { it.copy(password = value) }
    }

    fun setRememberLogin(value: Boolean) {
        prefs.rememberCampusLogin = value
        _uiState.update { it.copy(rememberLogin = value) }
    }

    fun setShowPicker(value: Boolean) {
        _uiState.update { it.copy(showPicker = value) }
    }
}

// ════════════════════════════════════════════════════
//  UI State
// ════════════════════════════════════════════════════

data class CampusServiceUiState(
    val school: String = "",
    val showPicker: Boolean = false,
    val route: CampusRoute = CampusRoute.Home,
    val username: String = "",
    val password: String = "",
    val rememberLogin: Boolean = false,
    val loginState: LoginState = LoginState.Idle,
    val loginError: String = "",
    val loginBannerDismissed: Boolean = false,
    val studentName: String = ""
)

/** 页面路由 */
sealed class CampusRoute {
    object Home : CampusRoute()
    object Jwxt : CampusRoute()
    /** 独立课表页面(周视图) */
    object Schedule : CampusRoute()
    /** @param tab Ecard 目标 Tab: "balance"/"bill"/"payfee",null 默认余额 */
    data class Ecard(val tab: String? = null) : CampusRoute()
    object Sc : CampusRoute()
    object Hsxs : CampusRoute()
    /** 智慧思政:功能列表(综测成绩/离返校/留校登记) */
    object Szs : CampusRoute()
    /** 智慧思政 → 综合测评成绩明细(jgf 系统) */
    object SzsScore : CampusRoute()
    /** 智慧思政 → 综测测评(hbsz-h5 系统) */
    object SzsEvaluating : CampusRoute()
    /** 智慧思政 → 离返校登记 */
    object HolidayLeave : CampusRoute()
    /** 智慧思政 → 寒暑假留校登记 */
    object StayInSchool : CampusRoute()
    /** 快捷服务:无需登录的轻量功能聚合页(校历等) */
    object QuickService : CampusRoute()
    /** 班级通讯录:需要登录智慧门户 */
    object ClassContact : CampusRoute()
    /** 教材清单:jiaocaigo.com 手机号登录后查询本学期教材 */
    object TextbookList : CampusRoute()
}

/** 登录状态 */
enum class LoginState { Idle, Logging, LoggedIn, Failed }
