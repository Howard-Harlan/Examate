package go.examate.hyp.campus.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.ChnuConfig
import go.examate.hyp.campus.model.AvailableCourse
import go.examate.hyp.campus.model.EvaluationItem
import go.examate.hyp.campus.model.Grade
import go.examate.hyp.campus.model.ScheduleItem
import go.examate.hyp.campus.model.SelectedCourse
import go.examate.hyp.campus.model.XkCredits
import go.examate.hyp.campus.model.XkStatus
import go.examate.hyp.config.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * JwxtScreen 的 ViewModel
 *
 * 将课表/成绩/教学评价/选课结果/选课五个 Tab 的状态从 Composable 中抽出,
 * 统一用 StateFlow 管理,Composable 通过 collectAsState() 收集。
 *
 * 优势:
 * 1. 状态与 UI 解耦,便于测试
 * 2. viewModelScope 自动绑定生命周期,屏幕旋转时不丢失
 * 3. 避免 Composable 重建时重复加载
 */
class JwxtViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CampusRepository.getInstance()
    private val prefs = PrefsManager.getInstance(app)
    private val defXnXq = ChnuConfig.currentXnXq()
    private val defXn = defXnXq.first
    private val defXq = defXnXq.second

    private val _uiState = MutableStateFlow(JwxtUiState())
    val uiState: StateFlow<JwxtUiState> = _uiState.asStateFlow()

    // ════════════════════════════════════════
    //  课表
    // ════════════════════════════════════════

    fun loadSchedule() {
        val s = _uiState.value.schedule
        if (s.loaded || s.loading || s.error.isNotEmpty()) return
        _uiState.update { it.copy(schedule = it.schedule.copy(loading = true)) }
        viewModelScope.launch {
            try {
                val data = repo.getSchedule(prefs, defXn, defXq)
                _uiState.update { it.copy(schedule = it.schedule.copy(items = data, loading = false, loaded = true)) }
            } catch (e: CampusException) {
                _uiState.update { it.copy(schedule = it.schedule.copy(loading = false, loaded = true, error = e.message ?: "查询失败")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(schedule = it.schedule.copy(loading = false, loaded = true, error = "网络异常: ${e.message}")) }
            }
        }
    }

    fun changeDate(date: LocalDate) {
        _uiState.update { it.copy(schedule = it.schedule.copy(currentDate = date)) }
    }

    fun retrySchedule() {
        _uiState.update { it.copy(schedule = it.schedule.copy(error = "", loaded = false)) }
        loadSchedule()
    }

    // ════════════════════════════════════════
    //  成绩
    // ════════════════════════════════════════

    fun setGradeXn(xn: String) {
        _uiState.update { it.copy(grade = it.grade.copy(xn = xn)) }
    }

    fun setGradeXq(xq: String) {
        _uiState.update { it.copy(grade = it.grade.copy(xq = xq)) }
    }

    fun queryGrades() {
        val g = _uiState.value.grade
        if (g.loading) return
        _uiState.update { it.copy(grade = it.grade.copy(loading = true, error = "", queried = true)) }
        viewModelScope.launch {
            try {
                val data = repo.getGrades(prefs, g.xn, g.xq)
                _uiState.update { it.copy(grade = it.grade.copy(items = data, loading = false)) }
            } catch (e: CampusException) {
                _uiState.update { it.copy(grade = it.grade.copy(loading = false, error = e.message ?: "查询失败")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(grade = it.grade.copy(loading = false, error = "网络异常: ${e.message}")) }
            }
        }
    }

    // ════════════════════════════════════════
    //  教学评价
    // ════════════════════════════════════════

    fun loadEvaluations(forceRefresh: Boolean = false) {
        val e = _uiState.value.evaluation
        if (e.loaded && !forceRefresh) return
        if (e.loading) return
        _uiState.update { it.copy(evaluation = it.evaluation.copy(loading = true, error = "")) }
        viewModelScope.launch {
            try {
                val data = repo.getTeachingEvaluation(prefs, forceRefresh = forceRefresh)
                _uiState.update { it.copy(evaluation = it.evaluation.copy(items = data, loading = false, loaded = true)) }
            } catch (e: CampusException) {
                _uiState.update { it.copy(evaluation = it.evaluation.copy(loading = false, loaded = true, error = e.message ?: "查询失败")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(evaluation = it.evaluation.copy(loading = false, loaded = true, error = "网络异常: ${e.message}")) }
            }
        }
    }

    // ════════════════════════════════════════
    //  选课结果
    // ════════════════════════════════════════

    fun setSelectedCourseXn(xn: String) {
        _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(xn = xn)) }
    }

    fun setSelectedCourseXq(xq: String) {
        _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(xq = xq)) }
    }

    fun querySelectedCourses() {
        val s = _uiState.value.selectedCourse
        if (s.loading) return
        _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(loading = true, error = "", queried = true)) }
        viewModelScope.launch {
            try {
                val data = repo.getSelectedCourses(prefs, s.xn, s.xq)
                _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(items = data, loading = false)) }
            } catch (e: CampusException) {
                _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(loading = false, error = e.message ?: "查询失败")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(loading = false, error = "网络异常: ${e.message}")) }
            }
        }
    }

    // ════════════════════════════════════════
    //  选课
    // ════════════════════════════════════════

    private val defXqCode = if (defXq == "1") "3" else "12"

    fun loadXkInfo() {
        val x = _uiState.value.xk
        if (x.loaded || x.loading || x.error.isNotEmpty()) return
        _uiState.update { it.copy(xk = it.xk.copy(loading = true)) }
        viewModelScope.launch {
            try {
                val s = repo.getXkStatus(prefs)
                if (s.isXk == "1") {
                    val xn = s.xn.ifEmpty { defXn }
                    val xq = s.xq.ifEmpty { defXqCode }
                    val credits = repo.getXkCredits(prefs, xn, xq)
                    val available = repo.getAvailableCourses(prefs, xn, xq)
                    val chosen = repo.getChosenCourses(prefs, xn, xq)
                    _uiState.update {
                        it.copy(xk = it.xk.copy(
                            status = s, credits = credits,
                            available = available, chosen = chosen,
                            loading = false, loaded = true
                        ))
                    }
                } else {
                    _uiState.update { it.copy(xk = it.xk.copy(status = s, loading = false, loaded = true)) }
                }
            } catch (e: CampusException) {
                _uiState.update { it.copy(xk = it.xk.copy(loading = false, loaded = true, error = e.message ?: "查询失败")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(xk = it.xk.copy(loading = false, loaded = true, error = "网络异常: ${e.message}")) }
            }
        }
    }

    fun chooseCourse(course: AvailableCourse) {
        val x = _uiState.value.xk
        if (x.operatingKey.isNotEmpty()) return
        val xn = x.status?.xn?.ifEmpty { defXn } ?: defXn
        val xq = x.status?.xq?.ifEmpty { defXqCode } ?: defXqCode
        _uiState.update { it.copy(xk = it.xk.copy(operatingKey = "a_${course.jxbId}")) }
        viewModelScope.launch {
            try {
                val msg = repo.chooseCourse(prefs, course.jxbId, course.kchId, course.doJxbId, course.kklxdm, xn, xq)
                toast(msg.ifEmpty { "选课成功" })
                refreshXkData(xn, xq)
            } catch (e: CampusException) {
                toast(e.message ?: "选课失败")
            } catch (e: Exception) {
                toast("网络异常: ${e.message}")
            } finally {
                _uiState.update { it.copy(xk = it.xk.copy(operatingKey = "")) }
            }
        }
    }

    fun cancelCourse(course: AvailableCourse) {
        val x = _uiState.value.xk
        if (x.operatingKey.isNotEmpty()) return
        val xn = x.status?.xn?.ifEmpty { defXn } ?: defXn
        val xq = x.status?.xq?.ifEmpty { defXqCode } ?: defXqCode
        _uiState.update { it.copy(xk = it.xk.copy(operatingKey = "c_${course.jxbId}")) }
        viewModelScope.launch {
            try {
                val msg = repo.cancelCourse(prefs, course.jxbId, course.kchId, course.doJxbId, xn, xq)
                toast(msg.ifEmpty { "退课成功" })
                refreshXkData(xn, xq)
            } catch (e: CampusException) {
                toast(e.message ?: "退课失败")
            } catch (e: Exception) {
                toast("网络异常: ${e.message}")
            } finally {
                _uiState.update { it.copy(xk = it.xk.copy(operatingKey = "")) }
            }
        }
    }

    fun refreshXk() {
        _uiState.update { it.copy(xk = it.xk.copy(loaded = false, error = "")) }
        loadXkInfo()
    }

    private suspend fun refreshXkData(xn: String, xq: String) {
        val credits = repo.getXkCredits(prefs, xn, xq)
        val available = repo.getAvailableCourses(prefs, xn, xq)
        val chosen = repo.getChosenCourses(prefs, xn, xq)
        _uiState.update {
            it.copy(xk = it.xk.copy(credits = credits, available = available, chosen = chosen))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
    }
}

// ════════════════════════════════════════════════════
//  UI State
// ════════════════════════════════════════════════════

data class JwxtUiState(
    val schedule: ScheduleState = ScheduleState(),
    val grade: GradeState = GradeState(),
    val evaluation: EvaluationState = EvaluationState(),
    val selectedCourse: SelectedCourseState = SelectedCourseState(),
    val xk: XkState = XkState()
)

data class ScheduleState(
    val items: List<ScheduleItem> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String = "",
    val currentDate: LocalDate = LocalDate.now()
)

data class GradeState(
    val xn: String = ChnuConfig.currentXnXq().first,
    val xq: String = ChnuConfig.currentXnXq().second,
    val loading: Boolean = false,
    val error: String = "",
    val items: List<Grade> = emptyList(),
    val queried: Boolean = false
)

data class EvaluationState(
    val loading: Boolean = false,
    val error: String = "",
    val items: List<EvaluationItem> = emptyList(),
    val loaded: Boolean = false
)

data class SelectedCourseState(
    val xn: String = ChnuConfig.currentXnXq().first,
    val xq: String = ChnuConfig.currentXnXq().second,
    val loading: Boolean = false,
    val error: String = "",
    val items: List<SelectedCourse> = emptyList(),
    val queried: Boolean = false
)

data class XkState(
    val loading: Boolean = false,
    val error: String = "",
    val loaded: Boolean = false,
    val status: XkStatus? = null,
    val credits: XkCredits? = null,
    val available: List<AvailableCourse> = emptyList(),
    val chosen: List<AvailableCourse> = emptyList(),
    val operatingKey: String = ""
)
