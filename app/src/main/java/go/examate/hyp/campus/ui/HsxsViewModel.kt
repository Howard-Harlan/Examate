package go.examate.hyp.campus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * HsxsScreen 的 ViewModel
 *
 * 将淮师心声(失物招领 / 满意度调查)的状态从 Composable 中抽出,
 * 统一用 StateFlow 管理,Composable 通过 collectAsState() 收集。
 *
 * 优势:
 * 1. 状态与 UI 解耦,便于测试
 * 2. viewModelScope 自动绑定生命周期,屏幕旋转时不丢失
 * 3. 避免 Composable 重建时重复加载
 */
class HsxsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CampusRepository.getInstance()
    private val prefs = PrefsManager.getInstance(app)

    private val _uiState = MutableStateFlow(HsxsUiState())
    val uiState: StateFlow<HsxsUiState> = _uiState.asStateFlow()

    // ════════════════════════════════════════
    //  登录
    // ════════════════════════════════════════

    fun login() {
        val l = _uiState.value.login
        if (l.loggedIn || l.loginError.isNotEmpty()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.hsxsLogin(prefs) }
                _uiState.update { it.copy(login = it.login.copy(loggedIn = true)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(login = it.login.copy(loginError = e.message ?: "登录失败")) }
            }
        }
    }

    fun retryLogin() {
        _uiState.update { it.copy(login = HsxsLoginState()) }
        login()
    }

    // ════════════════════════════════════════
    //  失物招领
    // ════════════════════════════════════════

    fun loadLostList() {
        _uiState.update { it.copy(lostFound = it.lostFound.copy(loading = true, error = "")) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { parseDataArray(repo.getLostList(prefs)) }
                _uiState.update { it.copy(lostFound = it.lostFound.copy(items = data, loading = false)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(lostFound = it.lostFound.copy(loading = false, error = e.message ?: "加载失败")) }
            }
        }
    }

    fun setShowPublish(show: Boolean) {
        _uiState.update { it.copy(lostFound = it.lostFound.copy(showPublish = show)) }
    }

    // ════════════════════════════════════════
    //  满意度调查
    // ════════════════════════════════════════

    fun loadSurveyList() {
        _uiState.update { it.copy(survey = it.survey.copy(loading = true, error = "")) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { parseDataArray(repo.getQuestionList(prefs)) }
                _uiState.update { it.copy(survey = it.survey.copy(items = data, loading = false)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(survey = it.survey.copy(loading = false, error = e.message ?: "加载失败")) }
            }
        }
    }

    private fun parseDataArray(json: JSONObject): List<JSONObject> {
        val arr = json.optJSONObject("data")?.optJSONArray("data") ?: json.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
}

// ════════════════════════════════════════════════════
//  UI State
// ════════════════════════════════════════════════════

data class HsxsUiState(
    val login: HsxsLoginState = HsxsLoginState(),
    val lostFound: LostFoundState = LostFoundState(),
    val survey: SurveyState = SurveyState()
)

data class HsxsLoginState(
    val loggedIn: Boolean = false,
    val loginError: String = ""
)

data class LostFoundState(
    val items: List<JSONObject> = emptyList(),
    val loading: Boolean = true,
    val error: String = "",
    val showPublish: Boolean = false
)

data class SurveyState(
    val items: List<JSONObject> = emptyList(),
    val loading: Boolean = true,
    val error: String = ""
)
