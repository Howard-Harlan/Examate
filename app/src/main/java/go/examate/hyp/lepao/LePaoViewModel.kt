package go.examate.hyp.lepao

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** 页面路由状态 */
sealed class LePaoPage {
    data class Main(val tab: Int = 0) : LePaoPage()
    object RouteEditor : LePaoPage()
}

/** 步道乐跑主页面 UI 状态 */
data class LePaoUiState(
    val page: LePaoPage = LePaoPage.Main(),
    val config: RunConfig = RunConfig(),
    val uploading: Boolean = false,
    val uploadProgress: String = "",
    val resultMsg: String? = null,
    val resultOk: Boolean = false,
    val userRecords: List<JSONObject> = emptyList(),
    val termScore: JSONObject? = null,
    val loadingRecords: Boolean = false,
    val importingRoute: Boolean = false,
    val importMsg: String? = null
)

/**
 * 步道乐跑 ViewModel — 将业务逻辑从 Composable 抽出，统一用 StateFlow 管理。
 * 与项目其他模块（CampusServiceViewModel/WaterCardViewModel 等）架构对齐。
 */
class LePaoViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    private val _uiState = MutableStateFlow(
        LePaoUiState(
            config = LePaoPrefs.loadRunConfig(context).copy(
                routePoints = LePaoPrefs.loadRoutePoints(context)
            )
        )
    )
    val uiState: StateFlow<LePaoUiState> = _uiState.asStateFlow()

    val routeDistanceKm: Float
        get() = LePaoGeoUtils.calcGeoDistance(_uiState.value.config.routePoints) / 1000f

    init {
        refreshToken()
        fetchUserData()
    }

    // ═══ Token 刷新 ═══

    private fun refreshToken() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = LePaoPrefs.load(context)
                val refreshToken = LePaoPrefs.getRefreshToken(context)
                if (prefs.optString("token", "").isNotEmpty() && refreshToken.isNotEmpty()) {
                    val api = LePaoApi(token = prefs.optString("token"))
                    val resp = api.refreshToken(refreshToken)
                    if (resp.optInt("status") == 1) {
                        val data = resp.optJSONObject("data")
                        if (data != null) {
                            LePaoPrefs.save(context,
                                data.optString("uid", prefs.optString("uid")),
                                data.optString("access_token"),
                                prefs.optString("school_id", LePaoPrefs.DEFAULT_SCHOOL_ID),
                                prefs.optString("student_num"),
                                refreshToken = data.optString("refresh_token"))
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // ═══ 用户数据 ═══

    fun fetchUserData() {
        _uiState.update { it.copy(loadingRecords = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = LePaoPrefs.load(context)
                val api = LePaoApi(prefs.optString("uid"), prefs.optString("token"),
                    prefs.optString("school_id", LePaoPrefs.DEFAULT_SCHOOL_ID), prefs.optString("student_num"))
                val year = java.text.SimpleDateFormat("yyyy", Locale.getDefault()).format(java.util.Date())
                val records = api.fetchAllRecords(year)

                // 从 getAllScores 获取当前学期 term_id，不再硬编码
                val allScores = api.getAllScores()
                val termList = allScores.optJSONObject("data")?.optJSONArray("term_list")
                val currentTermId = if (termList != null && termList.length() > 0) {
                    termList.getJSONObject(0).optJSONObject("term_info")?.optString("id", LePaoPrefs.DEFAULT_TERM_ID) ?: LePaoPrefs.DEFAULT_TERM_ID
                } else LePaoPrefs.DEFAULT_TERM_ID
                val scoreResp = api.getTermScore(currentTermId)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            loadingRecords = false,
                            userRecords = records,
                            termScore = if (scoreResp.optInt("status") == 1) scoreResp.optJSONObject("data") else it.termScore
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(loadingRecords = false) }
            }
        }
    }

    // ═══ 上传 ═══

    fun uploadFreeRun() = startUpload("自由跑") { api, cfg ->
        val avgSpeed = (cfg.straightSpeed + cfg.curveSpeed) / 2f
        LePaoRunner(api).uploadRun(
            cfg.routePoints, avgSpeed, cfg.stepLengthM,
            warmupS = cfg.warmupTime.toFloat(),
            cooldownS = cfg.cooldownTime.toFloat(),
            intervalS = (cfg.gpsIntervalMs / 1000f).coerceAtLeast(0.5f)
        )
    }

    fun uploadCampusRun() = startUpload("校园跑") { api, cfg ->
        val avgSpeed = (cfg.straightSpeed + cfg.curveSpeed) / 2f
        LePaoCampusRunner(api).uploadCampusRun(
            cfg.routePoints, avgSpeed, cfg.stepLengthM,
            warmupS = cfg.warmupTime.toFloat(),
            cooldownS = cfg.cooldownTime.toFloat(),
            intervalS = (cfg.gpsIntervalMs / 1000f).coerceAtLeast(0.5f)
        )
    }

    private fun startUpload(label: String, action: (LePaoApi, RunConfig) -> JSONObject) {
        _uiState.update { it.copy(uploading = true, resultMsg = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = LePaoPrefs.load(context)
                val api = LePaoApi(prefs.optString("uid"), prefs.optString("token"),
                    prefs.optString("school_id", LePaoPrefs.DEFAULT_SCHOOL_ID), prefs.optString("student_num"))
                val result = action(api, _uiState.value.config)
                withContext(Dispatchers.Main) {
                    val ok = result.optInt("status") == 1
                    val rid = result.optJSONObject("data")?.optString("record_id") ?: "?"
                    _uiState.update {
                        it.copy(
                            uploading = false, resultOk = ok,
                            resultMsg = if (ok) "✓ ${label}成功! ID=$rid" else "✗ ${result.optString("info")}"
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(uploading = false, resultOk = false, resultMsg = "异常: ${e.message}") }
                }
            }
        }
    }

    // ═══ 配置 ═══

    fun updateConfig(newConfig: RunConfig) {
        LePaoPrefs.saveRunConfig(context, newConfig)
        _uiState.update { it.copy(config = newConfig) }
    }

    fun saveRoutePoints(pts: List<Pair<Double, Double>>) {
        LePaoPrefs.saveRoutePoints(context, pts)
    }

    // ═══ 从历史记录导入路线 ═══

    fun saveRecordAsRoute(recordId: String) {
        _uiState.update { it.copy(importingRoute = true, importMsg = "获取记录详情...") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = LePaoPrefs.load(context)
                val api = LePaoApi(prefs.optString("uid"), prefs.optString("token"),
                    prefs.optString("school_id", LePaoPrefs.DEFAULT_SCHOOL_ID), prefs.optString("student_num"))

                val detail = api.recordDetail(recordId)

                if (detail.optInt("status") != 1) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(importingRoute = false, importMsg = "获取详情失败: ${detail.optString("info")}") }
                    }
                    return@launch
                }

                val data = detail.optJSONObject("data") ?: JSONObject()
                val recordFile = data.optString("file", "")
                if (recordFile.isEmpty() || recordFile == "1") {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(importingRoute = false, importMsg = "该记录无轨迹文件") }
                    }
                    return@launch
                }

                _uiState.update { it.copy(importMsg = "下载并解析轨迹...") }
                val routePts = LePaoTrack.downloadAndParseTrack(recordFile, targetCount = 200)
                if (routePts == null || routePts.size < 2) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(importingRoute = false, importMsg = "轨迹解析失败") }
                    }
                    return@launch
                }

                LePaoPrefs.saveRoutePoints(context, routePts)
                _uiState.update {
                    it.copy(
                        importingRoute = false,
                        importMsg = "✓ 已导入 ${routePts.size} 点路线",
                        config = it.config.copy(routePoints = routePts)
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(importingRoute = false, importMsg = "导入失败: ${e.message}") }
                }
            }
        }
    }

    fun clearImportMsg() {
        _uiState.update { it.copy(importMsg = null) }
    }

    // ═══ 页面导航 ═══

    fun navigate(page: LePaoPage) {
        _uiState.update { it.copy(page = page) }
    }

    fun editRouteDone(pts: List<Pair<Double, Double>>) {
        val newConfig = _uiState.value.config.copy(routePoints = pts)
        saveRoutePoints(pts)
        _uiState.update { it.copy(config = newConfig, page = LePaoPage.Main()) }
    }

    // ═══ 登出 ═══

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = LePaoPrefs.load(context)
                val api = LePaoApi(token = prefs.optString("token"))
                api.logout()
            } catch (_: Exception) { }
            LePaoPrefs.logout(context)
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
