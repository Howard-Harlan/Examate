package go.examate.hyp.campus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.model.ScoreDetail
import go.examate.hyp.campus.model.ScoreSummary
import go.examate.hyp.config.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ScScreen(第二课堂) 的 ViewModel
 *
 * 将成绩汇总/成绩明细两个 Tab 的状态从 Composable 中抽出,
 * 统一用 StateFlow 管理,Composable 通过 collectAsStateWithLifecycle() 收集。
 *
 * 优势:
 * 1. 状态与 UI 解耦,便于测试
 * 2. viewModelScope 自动绑定生命周期,屏幕旋转时不丢失
 * 3. 避免 Composable 重建时重复加载
 */
class ScViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CampusRepository.getInstance()
    private val prefs = PrefsManager.getInstance(app)

    private val _uiState = MutableStateFlow(ScUiState())
    val uiState: StateFlow<ScUiState> = _uiState.asStateFlow()

    // ════════════════════════════════════════
    //  成绩汇总
    // ════════════════════════════════════════

    fun loadSummary() {
        val s = _uiState.value.summary
        if (s.loaded || s.loading || s.error.isNotEmpty()) return
        _uiState.update { it.copy(summary = it.summary.copy(loading = true)) }
        viewModelScope.launch {
            try {
                val data = repo.getScoreSummary(prefs, forceRefresh = s.forceRefresh)
                _uiState.update {
                    it.copy(summary = it.summary.copy(
                        summary = data, loading = false, loaded = true, forceRefresh = false
                    ))
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(summary = it.summary.copy(
                        loading = false, loaded = true, forceRefresh = false, error = e.message ?: "查询失败"
                    ))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(summary = it.summary.copy(
                        loading = false, loaded = true, forceRefresh = false, error = "网络异常: ${e.message}"
                    ))
                }
            }
        }
    }

    fun retrySummary() {
        _uiState.update { it.copy(summary = it.summary.copy(error = "", loaded = false, forceRefresh = true)) }
        loadSummary()
    }

    fun refreshSummary() {
        _uiState.update { it.copy(summary = it.summary.copy(loaded = false, forceRefresh = true, error = "")) }
        loadSummary()
    }

    // ════════════════════════════════════════
    //  成绩明细
    // ════════════════════════════════════════

    fun loadDetail() {
        val d = _uiState.value.detail
        if (d.loading) return
        _uiState.update { it.copy(detail = it.detail.copy(loading = true, error = "", queried = true)) }
        viewModelScope.launch {
            try {
                val data = repo.getScoreDetail(prefs, forceRefresh = true)
                _uiState.update { it.copy(detail = it.detail.copy(details = data, loading = false)) }
            } catch (e: CampusException) {
                _uiState.update { it.copy(detail = it.detail.copy(loading = false, error = e.message ?: "查询失败")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(detail = it.detail.copy(loading = false, error = "网络异常: ${e.message}")) }
            }
        }
    }
}

// ════════════════════════════════════════════════════
//  UI State
// ════════════════════════════════════════════════════

data class ScUiState(
    val summary: SummaryState = SummaryState(),
    val detail: DetailState = DetailState()
)

data class SummaryState(
    val loading: Boolean = false,
    val error: String = "",
    val summary: ScoreSummary? = null,
    val loaded: Boolean = false,
    val forceRefresh: Boolean = false
)

data class DetailState(
    val loading: Boolean = false,
    val error: String = "",
    val details: List<ScoreDetail> = emptyList(),
    val queried: Boolean = false
)
