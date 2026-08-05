package go.examate.hyp.campus.ui

import android.app.Application
import android.widget.Toast
import go.examate.hyp.util.ToastUtil
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.ChnuConfig
import go.examate.hyp.campus.model.AvailableCourse
import go.examate.hyp.campus.model.BuildingInfo
import go.examate.hyp.campus.model.EmptyClassroom
import go.examate.hyp.campus.model.EvaluationItem
import go.examate.hyp.campus.model.EvaluationQuestion
import go.examate.hyp.campus.model.Grade
import go.examate.hyp.campus.model.GradeDetail
import go.examate.hyp.campus.model.ScheduleItem
import go.examate.hyp.campus.model.SelectOption
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
 * 统一用 StateFlow 管理,Composable 通过 collectAsStateWithLifecycle() 收集。
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
        // 切换学年后自动重新查询(仅在已经查过一次后,避免首次进入就触发)
        if (_uiState.value.grade.queried) queryGrades()
    }

    fun setGradeXq(xq: String) {
        _uiState.update { it.copy(grade = it.grade.copy(xq = xq)) }
        if (_uiState.value.grade.queried) queryGrades()
    }

    fun queryGrades() {
        val g = _uiState.value.grade
        if (g.loading) return
        _uiState.update { it.copy(grade = it.grade.copy(loading = true, error = "", queried = true)) }
        viewModelScope.launch {
            try {
                // forceRefresh=true 确保切换学期时不会读到旧缓存
                val data = repo.getGrades(prefs, g.xn, g.xq, forceRefresh = true)
                _uiState.update { it.copy(grade = it.grade.copy(items = data, loading = false)) }
            } catch (e: CampusException) {
                _uiState.update { it.copy(grade = it.grade.copy(loading = false, error = e.message ?: "查询失败")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(grade = it.grade.copy(loading = false, error = "网络异常: ${e.message}")) }
            }
        }
    }

    /** 查询成绩明细(平时分/期末分/总分) — 需要 jxb_id 不是 kch */
    fun loadGradeDetail(grade: Grade) {
        if (grade.jxbId.isBlank()) return
        val g = _uiState.value.grade
        _uiState.update { it.copy(grade = it.grade.copy(detailLoading = true, detailError = "", detailGrade = grade)) }
        viewModelScope.launch {
            try {
                val detail = repo.getGradeDetail(prefs, grade.jxbId, g.xn, g.xq)
                _uiState.update { it.copy(grade = it.grade.copy(detail = detail, detailLoading = false)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(grade = it.grade.copy(detailLoading = false, detailError = e.message ?: "查询失败")) }
            }
        }
    }

    fun clearGradeDetail() {
        _uiState.update { it.copy(grade = it.grade.copy(detail = null, detailError = "")) }
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

    /** 加载某门课程的评价题目 */
    fun loadEvaluationQuestions(wjbm: String) {
        _uiState.update { it.copy(evaluation = it.evaluation.copy(questionLoading = true, questionError = "")) }
        viewModelScope.launch {
            try {
                val questions = repo.getEvaluationQuestions(prefs, wjbm)
                _uiState.update { it.copy(evaluation = it.evaluation.copy(
                    questions = questions, questionLoading = false,
                    selectedEvalWjbm = wjbm, evalScores = questions.associate { it.id to it.selectedScore }
                )) }
            } catch (e: Exception) {
                _uiState.update { it.copy(evaluation = it.evaluation.copy(questionLoading = false, questionError = e.message ?: "加载失败")) }
            }
        }
    }

    /** 更新评价分数 */
    fun setEvalScore(questionId: String, score: Double) {
        val scores = _uiState.value.evaluation.evalScores.toMutableMap()
        scores[questionId] = score
        _uiState.update { it.copy(evaluation = it.evaluation.copy(evalScores = scores)) }
    }

    /** 提交教学评价 */
    fun submitEvaluation() {
        val e = _uiState.value.evaluation
        if (e.submitting) return
        val wjbm = e.selectedEvalWjbm
        if (wjbm.isEmpty()) return
        val scores = e.evalScores
        val xn = _uiState.value.grade.xn
        val xq = _uiState.value.grade.xq
        _uiState.update { it.copy(evaluation = it.evaluation.copy(submitting = true, submitError = "")) }
        viewModelScope.launch {
            try {
                val msg = repo.submitEvaluation(prefs, wjbm, xn, xq, scores)
                _uiState.update { it.copy(evaluation = it.evaluation.copy(
                    submitting = false, submitResult = msg,
                    questions = emptyList(), selectedEvalWjbm = "", evalScores = emptyMap()
                )) }
                toast(msg)
                // 刷新评价列表
                loadEvaluations(forceRefresh = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(evaluation = it.evaluation.copy(submitting = false, submitError = e.message ?: "提交失败")) }
            }
        }
    }

    fun clearEvalQuestions() {
        _uiState.update { it.copy(evaluation = it.evaluation.copy(
            questions = emptyList(), selectedEvalWjbm = "", evalScores = emptyMap(),
            submitResult = "", submitError = "", questionError = ""
        )) }
    }

    /** 显示已评价课程的详情弹窗 */
    fun showEvalDetail(item: EvaluationItem) {
        _uiState.update { it.copy(evaluation = it.evaluation.copy(detailItem = item)) }
    }

    fun clearEvalDetail() {
        _uiState.update { it.copy(evaluation = it.evaluation.copy(detailItem = null)) }
    }

    // ════════════════════════════════════════
    //  选课结果
    // ════════════════════════════════════════

    fun setSelectedCourseXn(xn: String) {
        _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(xn = xn)) }
        if (_uiState.value.selectedCourse.queried) querySelectedCourses()
    }

    fun setSelectedCourseXq(xq: String) {
        _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(xq = xq)) }
        if (_uiState.value.selectedCourse.queried) querySelectedCourses()
    }

    fun querySelectedCourses() {
        val s = _uiState.value.selectedCourse
        if (s.loading) return
        _uiState.update { it.copy(selectedCourse = it.selectedCourse.copy(loading = true, error = "", queried = true)) }
        viewModelScope.launch {
            try {
                // 使用课表确认页接口(任何时间可用,不受选课时间段限制)
                val data = repo.getScheduleConfirmation(prefs, s.xn, s.xq)
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

    // ════════════════════════════════════════
    //  空闲教室
    // ════════════════════════════════════════

    fun loadBuildings() {
        val cr = _uiState.value.classroom
        if (cr.pageDataLoaded || cr.buildingsLoading) return
        _uiState.update { it.copy(classroom = it.classroom.copy(buildingsLoading = true)) }
        viewModelScope.launch {
            try {
                val pd = repo.getClassroomPageData(prefs)
                _uiState.update { it.copy(classroom = it.classroom.copy(
                    campusOptions = pd.campusOptions, buildingOptions = pd.buildingOptions,
                    roomTypeOptions = pd.roomTypeOptions,
                    buildingsLoading = false, pageDataLoaded = true
                )) }
            } catch (e: Exception) {
                _uiState.update { it.copy(classroom = it.classroom.copy(buildingsLoading = false)) }
            }
        }
    }

    fun setClassroomCampus(campusId: String) {
        _uiState.update { it.copy(classroom = it.classroom.copy(selectedCampusId = campusId)) }
    }

    fun setClassroomRoomType(roomTypeId: String) {
        _uiState.update { it.copy(classroom = it.classroom.copy(selectedRoomType = roomTypeId)) }
    }

    fun setClassroomBuilding(buildingId: String) {
        _uiState.update { it.copy(classroom = it.classroom.copy(selectedBuildingId = buildingId)) }
    }

    fun setClassroomStartWeek(w: String) {
        _uiState.update { it.copy(classroom = it.classroom.copy(startWeek = w)) }
    }

    fun setClassroomEndWeek(w: String) {
        _uiState.update { it.copy(classroom = it.classroom.copy(endWeek = w)) }
    }

    fun setClassroomWeekDays(days: String) {
        _uiState.update { it.copy(classroom = it.classroom.copy(weekDays = days)) }
    }

    fun setClassroomSectionRange(range: String) {
        _uiState.update { it.copy(classroom = it.classroom.copy(sectionRange = range)) }
    }

    fun queryEmptyClassrooms() {
        val cr = _uiState.value.classroom
        if (cr.loading) return
        _uiState.update { it.copy(classroom = it.classroom.copy(loading = true, error = "")) }
        viewModelScope.launch {
            try {
                val data = repo.getEmptyClassrooms(prefs, cr.selectedCampusId, cr.selectedRoomType, cr.selectedBuildingId,
                    cr.startWeek, cr.endWeek, cr.weekDays, cr.sectionRange)
                _uiState.update { it.copy(classroom = it.classroom.copy(items = data, loading = false, loaded = true)) }
            } catch (e: CampusException) {
                _uiState.update { it.copy(classroom = it.classroom.copy(loading = false, error = e.message ?: "查询失败")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(classroom = it.classroom.copy(loading = false, error = "网络异常: ${e.message}")) }
            }
        }
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
        ToastUtil.show(getApplication(), msg)
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
    val xk: XkState = XkState(),
    val classroom: ClassroomState = ClassroomState()
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
    val queried: Boolean = false,
    /** 成绩明细弹窗 */
    val detail: GradeDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String = "",
    /** 弹窗对应的课程(用于显示课程名/学分) */
    val detailGrade: Grade? = null
)

data class EvaluationState(
    val loading: Boolean = false,
    val error: String = "",
    val items: List<EvaluationItem> = emptyList(),
    val loaded: Boolean = false,
    /** 评价题目(待评价) */
    val questions: List<EvaluationQuestion> = emptyList(),
    val questionLoading: Boolean = false,
    val questionError: String = "",
    /** 已评价详情弹窗 */
    val detailItem: EvaluationItem? = null,
    /** 评价分数 Map<指标ID, 分数> */
    val evalScores: Map<String, Double> = emptyMap(),
    val selectedEvalWjbm: String = "",
    /** 提交中 */
    val submitting: Boolean = false,
    val submitError: String = "",
    val submitResult: String = ""
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

data class ClassroomState(
    val items: List<EmptyClassroom> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String = "",
    val buildingsLoading: Boolean = false,
    val pageDataLoaded: Boolean = false,
    /** 动态解析的选项列表 */
    val campusOptions: List<SelectOption> = emptyList(),
    val buildingOptions: List<SelectOption> = emptyList(),
    val roomTypeOptions: List<SelectOption> = emptyList(),
    /** 当前选中值 */
    val selectedCampusId: String = "1",
    val selectedBuildingId: String = "",
    val selectedRoomType: String = "",
    /** 周次/星期/节次筛选 (从网页 kxcdlb.js 对齐) */
    val startWeek: String = "",
    val endWeek: String = "",
    val weekDays: String = "", // 如 "1,3,5" 表示周一三五
    val sectionRange: String = "" // 如 "1-4节"
)
