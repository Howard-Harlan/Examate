package go.examate.hyp.campus.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import go.examate.hyp.util.ToastUtil
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.ScheduleSettingsActivity
import go.examate.hyp.ScheduleWebImportActivity
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.ChnuConfig
import go.examate.hyp.campus.model.DEFAULT_TOTAL_WEEKS
import go.examate.hyp.campus.model.ScheduleEntry
import go.examate.hyp.campus.model.ScheduleImporter
import go.examate.hyp.campus.model.ScheduleItem
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.campus.model.convertScheduleItems
import go.examate.hyp.campus.model.getSemesterStart
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ════════════════════════════════════════
//  ViewModel
// ════════════════════════════════════════

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CampusRepository.getInstance()
    private val prefs = PrefsManager.getInstance(app)
    // 存储 key 使用当前激活课表的 id,与课表设置页保持一致,打通多套课表
    private val storageKey get() = ScheduleStorage.getCurrentScheduleMeta(getApplication()).id

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init { loadLocal() }

    /** 从本地存储加载(不发网络请求) */
    private fun loadLocal() {
        val ctx = getApplication<Application>()
        val entries = ScheduleStorage.loadEntries(ctx, storageKey)
        val todos = ScheduleStorage.loadTodos(ctx, storageKey)
        _uiState.update { it.copy(entries = entries, todos = todos, loaded = true) }
    }

    /** 从设置页返回后重新加载(可能切换了课表或修改了课程) */
    fun reloadFromStorage() {
        loadLocal()
    }

    /** 已有教务导入:从教务系统同步课表(使用校园服务登录态) */
    fun refreshFromServer() {
        if (_uiState.value.refreshing) return
        val s = _uiState.value
        // 登录态前置检查
        if (prefs.school.isBlank()) {
            _uiState.update { it.copy(error = "请先在校园服务页绑定学校并登录") }
            return
        }
        if (prefs.campusUsername.isBlank() || prefs.campusPassword.isBlank()) {
            _uiState.update { it.copy(error = "请先在校园服务页登录后再使用已有教务导入") }
            return
        }
        val fxn = s.importSemesterXn
        val fxq = s.importSemesterXq
        val creditMode = s.importCreditMode
        val includeExperiment = s.importIncludeExperiment
        _uiState.update { it.copy(refreshing = true, error = "", showImportDialog = false) }
        viewModelScope.launch {
            try {
                val allItems = mutableListOf<ScheduleItem>()
                // 理论课表
                val theoryItems = withContext(Dispatchers.IO) { repo.getSchedule(prefs, fxn, fxq, forceRefresh = true) }
                allItems.addAll(theoryItems)
                // 实验课表
                if (includeExperiment) {
                    try {
                        val expItems = withContext(Dispatchers.IO) { repo.getExperimentSchedule(prefs, fxn, fxq) }
                        allItems.addAll(expItems.map { it.copy(courseName = "${it.courseName}[实验]") })
                    } catch (_: Exception) {}
                }
                if (allItems.isEmpty()) {
                    _uiState.update { it.copy(refreshing = false, error = "教务系统未返回课表数据(可能本学期暂无课程)") }
                    return@launch
                }
                val newEntries = convertScheduleItems(allItems, creditMode)
                // 合并:保留用户手动添加的条目和提醒设置
                val oldById = _uiState.value.entries.associateBy { it.id }
                val merged = newEntries.map { e ->
                    val old = oldById[e.id]
                    if (old != null) e.copy(reminderMinutes = old.reminderMinutes, note = old.note, color = old.color)
                    else e
                } + _uiState.value.entries.filter { it.id.startsWith("usr_") || it.id.startsWith("file_") }
                persist(merged, _uiState.value.todos)
                val expMsg = if (includeExperiment) "含实验课" else ""
                val creditMsg = when(creditMode) { "note" -> "(学分已导入备注)" "credit_only" -> "(已导入学分)" else -> "" }
                _uiState.update { it.copy(entries = merged, refreshing = false, error = "",
                    importMessage = "导入成功: ${newEntries.size} 门课程$expMsg$creditMsg") }
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("未保存校园账号") == true -> "请先在校园服务页登录"
                    e.message?.contains("登录") == true -> "校园服务登录失败,请在校园服务页重新登录"
                    e is java.net.SocketTimeoutException -> "网络超时,请检查网络后重试"
                    e is java.net.UnknownHostException -> "无法连接教务系统,请检查网络"
                    else -> "导入失败: ${e.message ?: "未知错误"}"
                }
                _uiState.update { it.copy(refreshing = false, error = msg) }
            }
        }
    }

    fun showImportDialog() { _uiState.update { it.copy(showImportDialog = true) } }
    fun dismissImportDialog() { _uiState.update { it.copy(showImportDialog = false) } }
    fun setImportSemester(xn: String, xq: String) { _uiState.update { it.copy(importSemesterXn = xn, importSemesterXq = xq) } }
    fun setImportCreditMode(mode: String) { _uiState.update { it.copy(importCreditMode = mode) } }
    fun setImportIncludeExperiment(include: Boolean) { _uiState.update { it.copy(importIncludeExperiment = include) } }

    /** 从文件导入(CSV/Excel) */
    fun importFromFile(uri: android.net.Uri) {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true, error = "") }
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val result = withContext(Dispatchers.IO) { ScheduleImporter.importFromFile(ctx, uri) }
                if (result.entries.isEmpty()) {
                    _uiState.update { it.copy(refreshing = false, error = "未解析到有效课程(跳过 ${result.skipped} 行),请检查文件格式:需含课程名/星期/节次列") }
                    return@launch
                }
                // 替换服务端数据,保留用户手动添加的
                val userEntries = _uiState.value.entries.filter { it.id.startsWith("usr_") }
                val merged = result.entries + userEntries
                persist(merged, _uiState.value.todos)
                _uiState.update {
                    it.copy(entries = merged, refreshing = false, error = "",
                        importMessage = "导入成功: ${result.total} 门课程" + if (result.skipped > 0) "(跳过 ${result.skipped} 行)" else "")
                }
            } catch (e: Exception) {
                val msg = when {
                    e is java.io.FileNotFoundException -> "文件未找到,可能已被移除"
                    e is SecurityException -> "无权限读取该文件,请重新选择"
                    e.message?.contains("不支持") == true -> e.message ?: "不支持的文件格式"
                    else -> "导入失败: ${e.message ?: "未知错误"}"
                }
                _uiState.update { it.copy(refreshing = false, error = msg) }
            }
        }
    }

    /** 从网页识别导入(WebView 回传 JSON) */
    fun importFromWebJson(jsonStr: String) {
        _uiState.update { it.copy(refreshing = true, error = "") }
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { parseWebScheduleJson(jsonStr) }
                val newEntries = convertScheduleItems(items)
                if (newEntries.isEmpty()) {
                    _uiState.update { it.copy(refreshing = false, error = "未识别到课表数据,请确保已进入课表页面后再提取") }
                    return@launch
                }
                val userEntries = _uiState.value.entries.filter { it.id.startsWith("usr_") }
                val merged = newEntries + userEntries
                persist(merged, _uiState.value.todos)
                _uiState.update {
                    it.copy(entries = merged, refreshing = false, error = "",
                        importMessage = "网页导入成功: ${newEntries.size} 门课程")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(refreshing = false, error = "网页导入失败: ${e.message ?: "JSON 解析错误"}") }
            }
        }
    }

    /** 解析正方教务系统课表 JSON(网页提取) */
    private fun parseWebScheduleJson(jsonStr: String): List<go.examate.hyp.campus.model.ScheduleItem> {
        val json = org.json.JSONObject(jsonStr)
        val kbList = json.optJSONArray("kbList") ?: return emptyList()
        val result = mutableListOf<go.examate.hyp.campus.model.ScheduleItem>()
        for (i in 0 until kbList.length()) {
            val it = kbList.getJSONObject(i)
            result.add(go.examate.hyp.campus.model.ScheduleItem(
                day = it.optString("xqjmc"),
                section = it.optString("jc"),
                courseName = it.optString("kcmc"),
                teacher = it.optString("xm"),
                classroom = it.optString("cdmc"),
                weeks = it.optString("zcd"),
                credit = it.optString("xf", ""),
                courseCategory = it.optString("kclb", it.optString("kclbmc", ""))
            ))
        }
        return result
    }

    fun clearImportMessage() {
        _uiState.update { it.copy(importMessage = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    // ── 课程编辑 ──
    fun addCourse(entry: ScheduleEntry) {
        val newEntries = _uiState.value.entries + entry
        persist(newEntries, _uiState.value.todos)
        _uiState.update { it.copy(entries = newEntries) }
    }

    fun updateCourse(entry: ScheduleEntry) {
        val newEntries = _uiState.value.entries.map { if (it.id == entry.id) entry else it }
        persist(newEntries, _uiState.value.todos)
        _uiState.update { it.copy(entries = newEntries) }
    }

    fun deleteCourse(id: String) {
        val newEntries = _uiState.value.entries.filterNot { it.id == id }
        persist(newEntries, _uiState.value.todos)
        _uiState.update { it.copy(entries = newEntries) }
    }

    // ── 待办(已剥离到日视图,关联具体日期) ──
    fun addTodo(todo: ScheduleTodo) {
        val newTodos = _uiState.value.todos + todo
        persist(_uiState.value.entries, newTodos)
        _uiState.update { it.copy(todos = newTodos) }
    }

    fun toggleTodo(id: String) {
        val newTodos = _uiState.value.todos.map { if (it.id == id) it.copy(done = !it.done) else it }
        persist(_uiState.value.entries, newTodos)
        _uiState.update { it.copy(todos = newTodos) }
    }

    fun deleteTodo(id: String) {
        val newTodos = _uiState.value.todos.filterNot { it.id == id }
        persist(_uiState.value.entries, newTodos)
        _uiState.update { it.copy(todos = newTodos) }
    }

    private fun persist(entries: List<ScheduleEntry>, todos: List<ScheduleTodo>) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            ScheduleStorage.save(ctx, storageKey, entries, todos)
            // 课表/待办变化后刷新桌面小组件,保持与 App 内一致
            go.examate.hyp.widget.ScheduleWidgetProvider.refreshAll(ctx)
        }
    }
}

data class ScheduleUiState(
    val entries: List<ScheduleEntry> = emptyList(),
    val todos: List<ScheduleTodo> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String = "",
    val importMessage: String = "",
    /** 教务导入选项弹窗 */
    val showImportDialog: Boolean = false,
    val importSemesterXn: String = ChnuConfig.currentXnXq().first,
    val importSemesterXq: String = ChnuConfig.currentXnXq().second,
    val importCreditMode: String = "note",
    val importIncludeExperiment: Boolean = false
)

// ════════════════════════════════════════
//  主页面
// ════════════════════════════════════════

@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val vm: ScheduleViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    // 当前课表元数据(用于控制显示周六/周日、学期周数等)
    var currentMeta by remember { mutableStateOf(ScheduleStorage.getCurrentScheduleMeta(context)) }
    // 当前周次:基于 meta 推算;未创建课程时默认第1周
    var currentWeek by remember { mutableStateOf(inferCurrentWeek(currentMeta, state.entries.isNotEmpty())) }
    // 视图模式:周视图(默认)/ 日视图 — 从 PrefsManager 读取上次选择,切换时持久化
    var viewMode by remember { mutableStateOf(prefs.scheduleViewMode) }
    // 日视图当前显示的日期(默认今天)
    var dayViewDate by remember { mutableStateOf(LocalDate.now()) }

    // 当 meta 或课程列表变化时,重新推算当前周次
    LaunchedEffect(currentMeta, state.entries.size) {
        currentWeek = inferCurrentWeek(currentMeta, state.entries.isNotEmpty())
    }

    // 弹窗状态
    var showImportDialog by remember { mutableStateOf(false) }
    var addingSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 周次选择面板
    var showWeekPicker by remember { mutableStateOf(false) }
    // 日视图的日期选择器(顶栏点击触发,默认显示设备今天)
    var showDayDatePicker by remember { mutableStateOf(false) }
    // 添加/编辑课程全屏页面(统一入口)
    var showAddCoursePage by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ScheduleEntry?>(null) }
    var initialSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 单次手势总位移(结束时判断方向,一个手势最多切换一周)
    val gestureTotalDrag = remember { floatArrayOf(0f) }

    // 加载作息时间配置
    LaunchedEffect(Unit) {
        ScheduleStorage.loadTimeSchedule(context)
        currentMeta = ScheduleStorage.getCurrentScheduleMeta(context)
    }

    // 文件选择器
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            vm.importFromFile(uri)
        }
    }

    // 网页导入结果
    val webImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val json = result.data?.getStringExtra("schedule_json")
            if (json != null) vm.importFromWebJson(json)
        }
    }

    // 设置页返回
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        ScheduleStorage.loadTimeSchedule(context)
        currentMeta = ScheduleStorage.getCurrentScheduleMeta(context)
        vm.reloadFromStorage()
    }
    // 待办页返回(可能增删了待办,需重新加载)
    val todoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        vm.reloadFromStorage()
    }

    // 显示导入结果/错误 Toast
    LaunchedEffect(state.importMessage, state.error) {
        if (state.importMessage.isNotEmpty()) {
            ToastUtil.show(context.applicationContext, state.importMessage)
            vm.clearImportMessage()
        }
        if (state.error.isNotEmpty()) {
            ToastUtil.showLong(context.applicationContext, state.error)
            vm.clearError()
        }
    }

    // ── 添加/编辑课程全屏页面(统一入口) ──
    if (showAddCoursePage) {
        val closeAddCourse: () -> Unit = {
            showAddCoursePage = false; editTarget = null; initialSlot = null
        }
        AddCoursePage(
            editEntry = editTarget,
            initialDay = initialSlot?.first ?: 1,
            initialSection = initialSlot?.second ?: 1,
            totalWeeks = currentMeta.totalWeeks,
            onBack = closeAddCourse,
            onSave = { entries ->
                if (editTarget != null) {
                    // 编辑模式:更新该课程
                    entries.forEach { vm.updateCourse(it) }
                } else {
                    // 新增模式:逐条添加(可能多个时间段)
                    entries.forEach { vm.addCourse(it) }
                }
                closeAddCourse()
            },
            onDelete = editTarget?.let { e ->
                { vm.deleteCourse(e.id); closeAddCourse() }
            }
        )
        return
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶栏:返回 + [<周次/日期>] + 视图切换 + 导入 + 添加课程 + 设置 ──
            // <> 融入日期胶囊内部,整体更紧凑
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                )
                // [< 周次/日期 >] 融合胶囊:点击中间文字展开选择器
                // start padding 加大,与左侧返回按钮拉开距离
                FrostedGlassCard(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // < 上一周/前一天
                        Box(
                            Modifier
                                .clickable {
                                    if (viewMode == "week") {
                                        if (currentWeek > 1) currentWeek--
                                    } else {
                                        dayViewDate = dayViewDate.minusDays(1)
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("<", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        // 中间:周次/日期(点击展开选择器)
                        Box(
                            Modifier
                                .clickable {
                                    if (viewMode == "week") showWeekPicker = true
                                    else showDayDatePicker = true
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val label = if (viewMode == "week") "第$currentWeek 周"
                            else if (dayViewDate == LocalDate.now()) "今天"
                            else "${dayViewDate.monthValue}/${dayViewDate.dayOfMonth}"
                            Text(
                                label,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        // > 下一周/后一天
                        Box(
                            Modifier
                                .clickable {
                                    if (viewMode == "week") {
                                        if (currentWeek < currentMeta.totalWeeks) currentWeek++
                                    } else {
                                        dayViewDate = dayViewDate.plusDays(1)
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(">", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // 视图切换:用文字"日"/"周"替代图标,理解更直观
                // 当前是周视图 → 显示"日"(可切换到日视图);当前是日视图 → 显示"周"
                // 切换后持久化到 PrefsManager,下次进入恢复该视图
                FrostedGlassCard(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            viewMode = if (viewMode == "week") "day" else "week"
                            prefs.scheduleViewMode = viewMode
                            // 切换到日视图时,默认显示今天
                            if (viewMode == "day") dayViewDate = LocalDate.now()
                        }
                ) {
                    Text(
                        if (viewMode == "week") "日" else "周",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                // 导入课表(下载图标)
                LiquidGlassIconButton(
                    icon = Icons.Filled.Download,
                    contentDescription = "导入课表",
                    onClick = { showImportDialog = true },
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(4.dp))
                // 添加课程(+ 图标,周/日视图统一显示)
                LiquidGlassIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "添加课程",
                    onClick = {
                        editTarget = null; initialSlot = null; showAddCoursePage = true
                    },
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(4.dp))
                LiquidGlassIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "设置",
                    onClick = { settingsLauncher.launch(Intent(context, ScheduleSettingsActivity::class.java)) },
                    modifier = Modifier.size(40.dp)
                )
            }

            if (state.refreshing) {
                Text("正在处理...", color = TextHint, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 14.dp))
            }

            if (viewMode == "week") {
                // ── 周视图网格(滑动切换周次,一个手势只切换一周) ──
                WeekScheduleGrid(
                    entries = state.entries,
                    todos = state.todos,
                    currentWeek = currentWeek,
                    showSaturday = currentMeta.showSaturday,
                    showSunday = currentMeta.showSunday,
                    semesterStartDate = currentMeta.semesterStartDate,
                    fontScale = currentMeta.fontScale,
                    onCourseClick = { entry ->
                        // 编辑现有课程:统一走 AddCoursePage
                        editTarget = entry; initialSlot = null; showAddCoursePage = true
                    },
                    onEmptyCellClick = { day, section -> addingSlot = day to section },
                    modifier = Modifier.weight(1f).pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { gestureTotalDrag[0] = 0f },
                            onDragEnd = {
                                // 手势结束:根据总位移方向切换一周(阈值 40px)
                                val total = gestureTotalDrag[0]
                                if (total <= -40f && currentWeek < currentMeta.totalWeeks) {
                                    currentWeek++
                                } else if (total >= 40f && currentWeek > 1) {
                                    currentWeek--
                                }
                                gestureTotalDrag[0] = 0f
                            }
                        ) { _, dragAmount ->
                            // 仅累积位移,不在拖动过程中切换(避免长按多周跳动)
                            gestureTotalDrag[0] += dragAmount
                        }
                    }
                )
            } else {
                // ── 日视图:当天课程 + 待办 ──
                DayScheduleView(
                    date = dayViewDate,
                    entries = state.entries,
                    todos = state.todos,
                    meta = currentMeta,
                    onAddTodo = { vm.addTodo(it) },
                    onToggleTodo = { vm.toggleTodo(it) },
                    onDeleteTodo = { vm.deleteTodo(it) },
                    onCourseClick = { entry ->
                        // 编辑现有课程:统一走 AddCoursePage
                        editTarget = entry; initialSlot = null; showAddCoursePage = true
                    },
                    onOpenAllTodos = {
                        todoLauncher.launch(Intent(context, go.examate.hyp.TodoActivity::class.java))
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // ── 周次选择器 ──
    if (showWeekPicker) {
        WeekPickerDialog(
            currentWeek = currentWeek,
            totalWeeks = currentMeta.totalWeeks,
            onDismiss = { showWeekPicker = false },
            onSelect = { w ->
                currentWeek = w
                showWeekPicker = false
            }
        )
    }

    // ── 日视图日期选择器(默认显示设备今天) ──
    if (showDayDatePicker) {
        DatePickerDialog(
            initialDate = dayViewDate,
            onDismiss = { showDayDatePicker = false },
            onConfirm = { date ->
                dayViewDate = date
                showDayDatePicker = false
            }
        )
    }

    // ── 导入弹窗 ──
    if (showImportDialog) {
        ImportMethodDialog(
            onDismiss = { showImportDialog = false },
            onFileImport = {
                showImportDialog = false
                filePicker.launch(arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream",
                    "*/*"
                ))
            },
            onJwxtImport = {
                showImportDialog = false
                if (prefs.school.isNotBlank() && prefs.campusUsername.isNotBlank() && prefs.campusPassword.isNotBlank()) {
                    vm.showImportDialog() // 先显示导入选项弹窗
                } else {
                    val hint = if (prefs.school.isBlank()) "请先在校园服务页绑定学校" else "请先在校园服务页登录"
                    ToastUtil.showLong(context.applicationContext, hint)
                }
            },
            onWebImport = {
                showImportDialog = false
                webImportLauncher.launch(Intent(context, ScheduleWebImportActivity::class.java))
            }
        )
    }

    // ── 教务导入选项弹窗 ──
    if (state.showImportDialog) {
        JwxtImportOptionsDialog(
            xn = state.importSemesterXn,
            xq = state.importSemesterXq,
            creditMode = state.importCreditMode,
            includeExperiment = state.importIncludeExperiment,
            onXnChange = { xn -> vm.setImportSemester(xn, state.importSemesterXq) },
            onXqChange = { xq -> vm.setImportSemester(state.importSemesterXn, xq) },
            onCreditModeChange = { vm.setImportCreditMode(it) },
            onIncludeExperimentChange = { vm.setImportIncludeExperiment(it) },
            onConfirm = { vm.refreshFromServer() },
            onDismiss = { vm.dismissImportDialog() }
        )
    }

    addingSlot?.let { (day, section) ->
        AddCellDialog(
            day = day,
            section = section,
            onAddCourse = {
                // 选"添加课程"后,关闭选择弹窗,统一进入 AddCoursePage(预填该格子的星期/节次)
                addingSlot = null
                editTarget = null
                initialSlot = day to section
                showAddCoursePage = true
            },
            onDismiss = { addingSlot = null }
        )
    }
}

/**
 * 推断当前是第几周
 * @param meta 当前课表元数据;若设置了 semesterStartDate 则按其推算,否则按默认(9月1日/2月25日)推算
 * @param hasEntries 是否已有课程;无课程时默认返回第1周,避免新用户一进来就跳到第20周
 */
private fun inferCurrentWeek(meta: ScheduleStorage.ScheduleMeta? = null, hasEntries: Boolean = false): Int {
    // 未创建课程:默认第1周,避免新用户一进来就跳到第N周
    if (!hasEntries) return 1
    val now = LocalDate.now()
    val totalWeeks = (meta?.totalWeeks ?: DEFAULT_TOTAL_WEEKS).coerceAtLeast(1)
    // 复用 getSemesterStart,确保周次推断与周视图表头日期同步
    val start = getSemesterStart(meta)
    val days = java.time.temporal.ChronoUnit.DAYS.between(start, now)
    if (days < 0) return 1  // 还没开学
    val week = (days / 7).toInt() + 1
    return week.coerceIn(1, totalWeeks)
}

// ════════════════════════════════════════
//  教务导入选项弹窗
// ════════════════════════════════════════

@Composable
private fun JwxtImportOptionsDialog(
    xn: String,
    xq: String,
    creditMode: String,
    includeExperiment: Boolean,
    onXnChange: (String) -> Unit,
    onXqChange: (String) -> Unit,
    onCreditModeChange: (String) -> Unit,
    onIncludeExperimentChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        FrostedGlassCard(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("教务课表导入选项", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))

                // 学期选择
                Text("选择学期", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 学年选择(近5年)
                    var showXnPicker by remember { mutableStateOf(false) }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(SurfaceVariant).clickable { showXnPicker = true }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(xn, color = TextPrimary, fontSize = 13.sp)
                    }
                    if (showXnPicker) {
                        val years = listOf("2020-2021", "2021-2022", "2022-2023", "2023-2024", "2024-2025", "2025-2026")
                        SingleChoiceDialog("学年", years.map { it to it }, xn,
                            { onXnChange(it); showXnPicker = false }, { showXnPicker = false })
                    }
                    var showXqPicker by remember { mutableStateOf(false) }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(SurfaceVariant).clickable { showXqPicker = true }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val xqLabel = SEMESTER_OPTIONS.firstOrNull { it.first == xq }?.second ?: xq
                        Text(xqLabel, color = TextPrimary, fontSize = 13.sp)
                    }
                    if (showXqPicker) {
                        SingleChoiceDialog("学期", SEMESTER_OPTIONS, xq,
                            { onXqChange(it); showXqPicker = false }, { showXqPicker = false })
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))

                // 学分导入方式
                Text("学分导入方式", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                val creditOptions = listOf(
                    "note" to "导入学分到备注(含课程类别)",
                    "credit_only" to "仅导入学分字段",
                    "none" to "不导入学分"
                )
                creditOptions.forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onCreditModeChange(mode) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (creditMode == mode) SelectedBg else SurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (creditMode == mode) {
                                Text("✓", color = Color.White, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = TextPrimary, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                Spacer(Modifier.height(10.dp))

                // 实验课表开关
                Row(
                    Modifier.fillMaxWidth().clickable { onIncludeExperimentChange(!includeExperiment) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (includeExperiment) SelectedBg else SurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (includeExperiment) Text("✓", color = Color.White, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("同时导入实验课表", color = TextPrimary, fontSize = 13.sp)
                        Text("实验课时间可能不一致,导入后课程名会标注[实验]", color = TextHint, fontSize = 10.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidGlassButton(
                        text = "取消", onClick = onDismiss,
                        style = ButtonStyle.Outline, height = 40.dp,
                        modifier = Modifier.weight(1f)
                    )
                    LiquidGlassButton(
                        text = "开始导入", onClick = onConfirm,
                        style = ButtonStyle.Primary, height = 40.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
